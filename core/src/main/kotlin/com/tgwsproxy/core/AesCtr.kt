package com.tgwsproxy.core

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Streaming AES-CTR with a 128-bit big-endian counter.
 *
 * Implemented on top of AES/ECB so the behaviour is identical on every JCE
 * provider (desktop JDK, Android Conscrypt), including arbitrary chunk sizes.
 */
class AesCtr(key: ByteArray, iv: ByteArray) {
    private val ecb: Cipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
    }
    private val counter = iv.copyOf()
    private var keystream = ByteArray(0)
    private var ksPos = 0

    init {
        require(key.size == 32 || key.size == 16) { "bad AES key length ${key.size}" }
        require(iv.size == 16) { "bad IV length ${iv.size}" }
    }

    fun update(data: ByteArray, off: Int = 0, len: Int = data.size - off): ByteArray {
        val out = ByteArray(len)
        var i = 0
        while (i < len) {
            if (ksPos == keystream.size) refill(len - i)
            val n = minOf(len - i, keystream.size - ksPos)
            for (j in 0 until n) {
                out[i + j] = (data[off + i + j].toInt() xor keystream[ksPos + j].toInt()).toByte()
            }
            i += n
            ksPos += n
        }
        return out
    }

    /** Advance the keystream by [n] bytes without producing output. */
    fun skip(n: Int) {
        update(ByteArray(n))
    }

    private fun refill(needed: Int) {
        val blocks = ((needed + 15) / 16).coerceIn(1, MAX_BLOCKS)
        val counters = ByteArray(blocks * 16)
        for (b in 0 until blocks) {
            System.arraycopy(counter, 0, counters, b * 16, 16)
            increment()
        }
        keystream = ecb.doFinal(counters)
        ksPos = 0
    }

    private fun increment() {
        for (i in 15 downTo 0) {
            val v = (counter[i].toInt() and 0xFF) + 1
            counter[i] = v.toByte()
            if (v <= 0xFF) return
        }
    }

    private companion object {
        const val MAX_BLOCKS = 4096
    }
}
