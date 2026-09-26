package com.tgwsproxy.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Base64
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class WsHandshakeError(val statusCode: Int, val statusLine: String, val location: String?) :
    IOException("HTTP $statusCode: $statusLine") {
    val isRedirect: Boolean get() = statusCode in setOf(301, 302, 303, 307, 308)
}

/** Minimal blocking binary WebSocket client (port of raw_websocket.py). */
class RawWebSocket private constructor(
    private val socket: Socket,
    private val input: InputStream,
    private val output: OutputStream,
) : Closeable {
    @Volatile
    var isClosed = false
        private set
    private val writeLock = Any()
    private val frag = ByteArrayOutputStream()

    /** Best-effort liveness check for pooled idle connections. */
    val looksAlive: Boolean
        get() = !isClosed && !socket.isClosed && !socket.isInputShutdown &&
            runCatching { input.available() == 0 }.getOrDefault(false)

    fun send(data: ByteArray) {
        if (isClosed) throw IOException("WebSocket closed")
        synchronized(writeLock) {
            writeFrame(OP_BINARY, data)
            output.flush()
        }
    }

    fun sendBatch(parts: List<ByteArray>) {
        if (isClosed) throw IOException("WebSocket closed")
        synchronized(writeLock) {
            for (p in parts) writeFrame(OP_BINARY, p)
            output.flush()
        }
    }

    /** Next binary message, or null when the peer closed the socket. */
    fun recv(): ByteArray? {
        while (!isClosed) {
            val b0 = input.read()
            if (b0 < 0) return null
            val b1 = readByte()
            val fin = b0 and 0x80 != 0
            val opcode = b0 and 0x0F
            var length = (b1 and 0x7F).toLong()
            if (length == 126L) {
                length = ((readByte() shl 8) or readByte()).toLong()
            } else if (length == 127L) {
                length = 0
                repeat(8) { length = (length shl 8) or readByte().toLong() }
            }
            if (length > MAX_MESSAGE_LEN || length < 0) throw IOException("WS frame too large: $length")
            val mask = if (b1 and 0x80 != 0) readExactly(4) else null
            val payload = readExactly(length.toInt())
            if (mask != null) for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
            }

            when (opcode) {
                OP_CLOSE -> {
                    isClosed = true
                    runCatching {
                        synchronized(writeLock) {
                            writeFrame(OP_CLOSE, payload.copyOf(minOf(2, payload.size)))
                            output.flush()
                        }
                    }
                    return null
                }
                OP_PING -> {
                    runCatching {
                        synchronized(writeLock) {
                            writeFrame(OP_PONG, payload)
                            output.flush()
                        }
                    }
                }
                OP_PONG -> Unit
                OP_CONT, OP_TEXT, OP_BINARY -> {
                    if (fin && frag.size() == 0) return payload
                    frag.write(payload)
                    if (frag.size() > MAX_MESSAGE_LEN) throw IOException("WS message too large")
                    if (!fin) continue
                    val msg = frag.toByteArray()
                    frag.reset()
                    return msg
                }
            }
        }
        return null
    }

    override fun close() {
        if (isClosed) {
            runCatching { socket.close() }
            return
        }
        isClosed = true
        runCatching {
            synchronized(writeLock) {
                writeFrame(OP_CLOSE, ByteArray(0))
                output.flush()
            }
        }
        runCatching { socket.close() }
    }

    private fun writeFrame(opcode: Int, data: ByteArray) {
        val len = data.size
        val header = ByteArrayOutputStream(14)
        header.write(0x80 or opcode)
        when {
            len < 126 -> header.write(0x80 or len)
            len < 65536 -> {
                header.write(0x80 or 126)
                header.write(len ushr 8); header.write(len and 0xFF)
            }
            else -> {
                header.write(0x80 or 127)
                for (shift in 56 downTo 0 step 8) header.write(((len.toLong() ushr shift) and 0xFF).toInt())
            }
        }
        val mask = ByteArray(4).also { MtProto.random.nextBytes(it) }
        header.write(mask)
        val masked = ByteArray(len)
        for (i in 0 until len) masked[i] = (data[i].toInt() xor mask[i and 3].toInt()).toByte()
        output.write(header.toByteArray())
        output.write(masked)
    }

    private fun readByte(): Int {
        val b = input.read()
        if (b < 0) throw EOFException("WS stream ended")
        return b
    }

    private fun readExactly(n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) throw EOFException("WS stream ended")
            off += r
        }
        return buf
    }

    companion object {
        const val OP_CONT = 0x0
        const val OP_TEXT = 0x1
        const val OP_BINARY = 0x2
        const val OP_CLOSE = 0x8
        const val OP_PING = 0x9
        const val OP_PONG = 0xA
        const val MAX_MESSAGE_LEN = 16 * 1024 * 1024

        /**
         * Open a WebSocket to wss://[domain][path], but connect the TCP socket
         * to [host] (an IP or hostname). TLS SNI and certificate check use [domain].
         */
        fun connect(
            host: String,
            domain: String,
            path: String = "/apiws",
            timeoutMs: Int = 10_000,
            secure: Boolean = true,
            port: Int = if (secure) 443 else 80,
            bufferSize: Int = 256 * 1024,
            tls: TlsSettings = TlsSettings.default,
        ): RawWebSocket {
            val raw = Socket()
            try {
                configureSocket(raw, bufferSize)
                raw.connect(InetSocketAddress(host, port), minOf(timeoutMs, 10_000))
                raw.soTimeout = timeoutMs
                val sock: Socket = if (secure) {
                    val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                        .createSocket(raw, domain, port, true) as SSLSocket
                    val params = ssl.sslParameters
                    params.serverNames = listOf(SNIHostName(domain))
                    if (tls.useEndpointIdentification) params.endpointIdentificationAlgorithm = "HTTPS"
                    ssl.sslParameters = params
                    ssl.startHandshake()
                    val verifier = tls.hostnameVerifier
                    if (verifier != null && !verifier.verify(domain, ssl.session)) {
                        ssl.close()
                        throw IOException("TLS certificate does not match $domain")
                    }
                    ssl
                } else raw

                val input = BufferedInputStream(sock.getInputStream(), 64 * 1024)
                val output = BufferedOutputStream(sock.getOutputStream(), 64 * 1024)

                val key = Base64.getEncoder().encodeToString(ByteArray(16).also { MtProto.random.nextBytes(it) })
                val req = "GET $path HTTP/1.1\r\n" +
                    "Host: $domain\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $key\r\n" +
                    "Sec-WebSocket-Version: 13\r\n" +
                    "Sec-WebSocket-Protocol: binary\r\n" +
                    "\r\n"
                output.write(req.toByteArray(Charsets.US_ASCII))
                output.flush()

                val lines = ArrayList<String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    lines.add(line)
                    if (lines.size > 100) throw IOException("too many response headers")
                }
                if (lines.isEmpty()) {
                    sock.close()
                    throw WsHandshakeError(0, "empty response", null)
                }
                val first = lines[0]
                val status = first.split(' ', limit = 3).getOrNull(1)?.toIntOrNull() ?: 0
                if (status == 101) {
                    sock.soTimeout = 0
                    return RawWebSocket(sock, input, output)
                }
                val location = lines.drop(1)
                    .firstOrNull { it.substringBefore(':').trim().equals("location", ignoreCase = true) }
                    ?.substringAfter(':')?.trim()
                sock.close()
                throw WsHandshakeError(status, first, location)
            } catch (e: Exception) {
                runCatching { raw.close() }
                throw e
            }
        }

        private fun readLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
                if (sb.length > 8192) throw IOException("response line too long")
            }
        }

        fun configureSocket(s: Socket, bufferSize: Int) {
            runCatching { s.tcpNoDelay = true }
            runCatching { s.receiveBufferSize = bufferSize }
            runCatching { s.sendBufferSize = bufferSize }
        }

        fun isTimeout(e: Throwable): Boolean =
            e is SocketTimeoutException || e.cause is SocketTimeoutException
    }
}

/** TLS knobs that differ between desktop JVM and Android. */
class TlsSettings(
    /** JDK-style hostname check through SSLParameters. */
    val useEndpointIdentification: Boolean = true,
    /** Extra explicit hostname check (Android passes HttpsURLConnection's default verifier). */
    val hostnameVerifier: javax.net.ssl.HostnameVerifier? = null,
) {
    companion object {
        @Volatile
        var default = TlsSettings()
    }
}
