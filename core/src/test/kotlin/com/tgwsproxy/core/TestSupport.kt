package com.tgwsproxy.core

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** Emulates the Telegram app side of an obfuscated2 connection with a proxy secret. */
class TestClient(val init: ByteArray, val enc: AesCtr, val dec: AesCtr) {
    companion object {
        fun makeInit(secret: ByteArray, dc: Int, isMedia: Boolean, protoTag: Int): TestClient {
            val init = ByteArray(64)
            while (true) {
                MtProto.random.nextBytes(init)
                if ((init[0].toInt() and 0xFF) != 0xEF) break
            }
            for (i in 0 until 4) init[56 + i] = (protoTag ushr (8 * i)).toByte()
            val idx = if (isMedia) -dc else dc
            init[60] = idx.toByte()
            init[61] = (idx shr 8).toByte()
            val enc = AesCtr(MtProto.sha256(init.copyOfRange(8, 40), secret), init.copyOfRange(40, 56))
            val encrypted = enc.update(init)
            val wire = init.copyOfRange(0, 56) + encrypted.copyOfRange(56, 64)
            val rev = init.copyOfRange(8, 56).reversedArray()
            val dec = AesCtr(MtProto.sha256(rev.copyOfRange(0, 32), secret), rev.copyOfRange(32, 48))
            return TestClient(wire, enc, dec)
        }
    }
}

fun abridgedPacket(payload: ByteArray): ByteArray {
    require(payload.size % 4 == 0)
    val words = payload.size / 4
    return if (words < 0x7F) byteArrayOf(words.toByte()) + payload
    else byteArrayOf(0x7F, words.toByte(), (words shr 8).toByte(), (words shr 16).toByte()) + payload
}

fun readExactly(input: InputStream, n: Int): ByteArray = DataInputStream(input).let { d ->
    ByteArray(n).also { d.readFully(it) }
}

/**
 * Plain-HTTP stand-in for kwsN.web.telegram.org/apiws: decodes the relay init,
 * then echoes every abridged packet back, recording WS frame sizes.
 */
class FakeTelegramWs : AutoCloseable {
    private val server = ServerSocket(0)
    val port: Int get() = server.localPort
    val frameSizes = CopyOnWriteArrayList<Int>()
    val paths = CopyOnWriteArrayList<String>()
    @Volatile var dcIdx: Int? = null
    @Volatile var respondStatus = 101

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { serve(s) }; runCatching { s.close() } }
            }
        }
    }

    private fun serve(s: Socket) {
        val input = s.getInputStream()
        val out = s.getOutputStream()
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) return
            head.append(b.toChar())
        }
        paths += head.lineSequence().first().split(' ')[1]
        if (respondStatus != 101) {
            out.write("HTTP/1.1 $respondStatus Found\r\nLocation: https://example.org/\r\nContent-Length: 0\r\n\r\n".toByteArray())
            out.flush()
            return
        }
        out.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n".toByteArray())
        out.flush()

        val init = readFrame(input) ?: return
        check(init.size == 64) { "relay init must be a separate 64-byte frame" }
        val dec = AesCtr(init.copyOfRange(8, 40), init.copyOfRange(40, 56))
        val plainInit = dec.update(init)
        dcIdx = ((plainInit[60].toInt() and 0xFF) or (plainInit[61].toInt() shl 8)).toShort().toInt()
        val rev = init.copyOfRange(8, 56).reversedArray()
        val enc = AesCtr(rev.copyOfRange(0, 32), rev.copyOfRange(32, 48))

        while (true) {
            val frame = readFrame(input) ?: return
            frameSizes += frame.size
            val plain = dec.update(frame)
            writeFrame(out, enc.update(plain)) // echo packet back
        }
    }

    private fun readFrame(input: InputStream): ByteArray? {
        val b0 = input.read()
        if (b0 < 0) return null
        val b1 = input.read()
        if (b0 and 0x0F == 0x8) return null
        var len = (b1 and 0x7F).toLong()
        if (len == 126L) len = ((input.read() shl 8) or input.read()).toLong()
        else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or input.read().toLong() } }
        val mask = if (b1 and 0x80 != 0) readExactly(input, 4) else null
        val payload = readExactly(input, len.toInt())
        if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
        return payload
    }

    private fun writeFrame(out: OutputStream, data: ByteArray) {
        val h = java.io.ByteArrayOutputStream()
        h.write(0x82)
        when {
            data.size < 126 -> h.write(data.size)
            data.size < 65536 -> { h.write(126); h.write(data.size shr 8); h.write(data.size and 0xFF) }
            else -> { h.write(127); for (sh in 56 downTo 0 step 8) h.write(((data.size.toLong() shr sh) and 0xFF).toInt()) }
        }
        synchronized(out) {
            out.write(h.toByteArray()); out.write(data); out.flush()
        }
    }

    override fun close() {
        server.close()
    }
}
