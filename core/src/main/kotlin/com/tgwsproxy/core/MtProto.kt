package com.tgwsproxy.core

import java.security.MessageDigest
import java.security.SecureRandom

/** MTProto obfuscated2 transport helpers (port of tg_ws_proxy.py / utils.py). */
object MtProto {
    const val HANDSHAKE_LEN = 64
    const val SKIP_LEN = 8
    const val PREKEY_LEN = 32
    const val KEY_LEN = 32
    const val IV_LEN = 16
    const val PROTO_TAG_POS = 56
    const val DC_IDX_POS = 60

    const val PROTO_ABRIDGED = 0xEFEFEFEF.toInt()
    const val PROTO_INTERMEDIATE = 0xEEEEEEEE.toInt()
    const val PROTO_PADDED_INTERMEDIATE = 0xDDDDDDDD.toInt()

    private val RESERVED_STARTS = listOf(
        byteArrayOf(0x48, 0x45, 0x41, 0x44), // HEAD
        byteArrayOf(0x50, 0x4F, 0x53, 0x54), // POST
        byteArrayOf(0x47, 0x45, 0x54, 0x20), // GET
        byteArrayOf(0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte()),
        byteArrayOf(0xDD.toByte(), 0xDD.toByte(), 0xDD.toByte(), 0xDD.toByte()),
        byteArrayOf(0x16, 0x03, 0x01, 0x02),
    )

    val random = SecureRandom()

    class ClientHandshake(
        val dc: Int,
        val isMedia: Boolean,
        /** Protocol tag as little-endian int (one of PROTO_*). */
        val proto: Int,
        val protoTag: ByteArray,
        /** handshake[8:56] — client prekey + IV. */
        val prekeyAndIv: ByteArray,
    )

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) md.update(p)
        return md.digest()
    }

    /** Validate the 64-byte client init against [secret]; null on wrong secret / protocol. */
    fun parseClientHandshake(handshake: ByteArray, secret: ByteArray): ClientHandshake? {
        require(handshake.size == HANDSHAKE_LEN)
        val prekeyAndIv = handshake.copyOfRange(SKIP_LEN, SKIP_LEN + PREKEY_LEN + IV_LEN)
        val key = sha256(prekeyAndIv.copyOfRange(0, PREKEY_LEN), secret)
        val iv = prekeyAndIv.copyOfRange(PREKEY_LEN, PREKEY_LEN + IV_LEN)
        val dec = AesCtr(key, iv).update(handshake)

        val tag = dec.copyOfRange(PROTO_TAG_POS, PROTO_TAG_POS + 4)
        val proto = leInt(tag, 0)
        if (proto != PROTO_ABRIDGED && proto != PROTO_INTERMEDIATE && proto != PROTO_PADDED_INTERMEDIATE) {
            return null
        }
        val dcIdx = ((dec[DC_IDX_POS].toInt() and 0xFF) or (dec[DC_IDX_POS + 1].toInt() shl 8)).toShort().toInt()
        return ClientHandshake(
            dc = kotlin.math.abs(dcIdx),
            isMedia = dcIdx < 0,
            proto = proto,
            protoTag = tag,
            prekeyAndIv = prekeyAndIv,
        )
    }

    /** Build the obfuscated2 init we send to Telegram (no secret, raw keys). */
    fun generateRelayInit(protoTag: ByteArray, dcIdx: Int): ByteArray {
        val rnd = ByteArray(HANDSHAKE_LEN)
        while (true) {
            random.nextBytes(rnd)
            if ((rnd[0].toInt() and 0xFF) == 0xEF) continue
            if (RESERVED_STARTS.any { rnd.startsWith(it) }) continue
            if (rnd[4].toInt() == 0 && rnd[5].toInt() == 0 && rnd[6].toInt() == 0 && rnd[7].toInt() == 0) continue
            break
        }
        val key = rnd.copyOfRange(SKIP_LEN, SKIP_LEN + PREKEY_LEN)
        val iv = rnd.copyOfRange(SKIP_LEN + PREKEY_LEN, SKIP_LEN + PREKEY_LEN + IV_LEN)
        val encrypted = AesCtr(key, iv).update(rnd)

        val tail = ByteArray(8)
        System.arraycopy(protoTag, 0, tail, 0, 4)
        tail[4] = (dcIdx and 0xFF).toByte()
        tail[5] = ((dcIdx shr 8) and 0xFF).toByte()
        tail[6] = random.nextInt().toByte()
        tail[7] = random.nextInt().toByte()

        val result = rnd.copyOf()
        for (i in 0 until 8) {
            val keystream = encrypted[PROTO_TAG_POS + i].toInt() xor rnd[PROTO_TAG_POS + i].toInt()
            result[PROTO_TAG_POS + i] = (tail[i].toInt() xor keystream).toByte()
        }
        return result
    }

    /**
     * Four stream ciphers:
     * cltDec — decrypt data from the Telegram app, cltEnc — encrypt data to the app,
     * tgEnc — encrypt data to Telegram servers, tgDec — decrypt data from servers.
     */
    class CryptoCtx(val cltDec: AesCtr, val cltEnc: AesCtr, val tgEnc: AesCtr, val tgDec: AesCtr)

    fun buildCryptoCtx(clientPrekeyAndIv: ByteArray, secret: ByteArray, relayInit: ByteArray): CryptoCtx {
        val cltDec = AesCtr(
            sha256(clientPrekeyAndIv.copyOfRange(0, PREKEY_LEN), secret),
            clientPrekeyAndIv.copyOfRange(PREKEY_LEN, PREKEY_LEN + IV_LEN),
        )
        val rev = clientPrekeyAndIv.reversedArray()
        val cltEnc = AesCtr(
            sha256(rev.copyOfRange(0, PREKEY_LEN), secret),
            rev.copyOfRange(PREKEY_LEN, PREKEY_LEN + IV_LEN),
        )
        cltDec.skip(HANDSHAKE_LEN)

        val relayKeyIv = relayInit.copyOfRange(SKIP_LEN, SKIP_LEN + PREKEY_LEN + IV_LEN)
        val tgEnc = AesCtr(relayKeyIv.copyOfRange(0, KEY_LEN), relayKeyIv.copyOfRange(KEY_LEN, KEY_LEN + IV_LEN))
        val relayRev = relayKeyIv.reversedArray()
        val tgDec = AesCtr(relayRev.copyOfRange(0, KEY_LEN), relayRev.copyOfRange(KEY_LEN, KEY_LEN + IV_LEN))
        tgEnc.skip(HANDSHAKE_LEN)

        return CryptoCtx(cltDec, cltEnc, tgEnc, tgDec)
    }

    fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }
}
