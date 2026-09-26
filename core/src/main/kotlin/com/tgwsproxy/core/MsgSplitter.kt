package com.tgwsproxy.core

/**
 * Splits the (already re-encrypted) upstream TCP stream into individual
 * MTProto transport packets so each one goes out as a separate WS frame.
 */
class MsgSplitter(relayInit: ByteArray, private val proto: Int) {
    private val dec = AesCtr(relayInit.copyOfRange(8, 40), relayInit.copyOfRange(40, 56)).apply {
        skip(MtProto.HANDSHAKE_LEN)
    }
    private var cipherBuf = ByteArray(0)
    private var plainBuf = ByteArray(0)
    private var disabled = false

    fun split(chunk: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()
        if (disabled) return listOf(chunk)

        cipherBuf += chunk
        plainBuf += dec.update(chunk)

        val parts = ArrayList<ByteArray>()
        var offset = 0
        val len = cipherBuf.size
        while (offset < len) {
            val packetLen = nextPacketLen(offset, len - offset) ?: break
            if (packetLen <= 0) {
                parts.add(cipherBuf.copyOfRange(offset, len))
                offset = len
                disabled = true
                break
            }
            parts.add(cipherBuf.copyOfRange(offset, offset + packetLen))
            offset += packetLen
        }
        if (offset > 0) {
            cipherBuf = cipherBuf.copyOfRange(offset, len)
            plainBuf = plainBuf.copyOfRange(offset, len)
        }
        return parts
    }

    fun flush(): List<ByteArray> {
        if (cipherBuf.isEmpty()) return emptyList()
        val tail = cipherBuf
        cipherBuf = ByteArray(0)
        plainBuf = ByteArray(0)
        return listOf(tail)
    }

    private fun nextPacketLen(offset: Int, avail: Int): Int? {
        if (avail <= 0) return null
        return when (proto) {
            MtProto.PROTO_ABRIDGED -> nextAbridgedLen(offset, avail)
            MtProto.PROTO_INTERMEDIATE, MtProto.PROTO_PADDED_INTERMEDIATE -> nextIntermediateLen(offset, avail)
            else -> 0
        }
    }

    private fun nextAbridgedLen(offset: Int, avail: Int): Int? {
        val first = plainBuf[offset].toInt() and 0xFF
        val payloadLen: Int
        val headerLen: Int
        if (first == 0x7F || first == 0xFF) {
            if (avail < 4) return null
            payloadLen = ((plainBuf[offset + 1].toInt() and 0xFF) or
                ((plainBuf[offset + 2].toInt() and 0xFF) shl 8) or
                ((plainBuf[offset + 3].toInt() and 0xFF) shl 16)) * 4
            headerLen = 4
        } else {
            payloadLen = (first and 0x7F) * 4
            headerLen = 1
        }
        if (payloadLen <= 0) return 0
        val packetLen = headerLen + payloadLen
        return if (avail < packetLen) null else packetLen
    }

    private fun nextIntermediateLen(offset: Int, avail: Int): Int? {
        if (avail < 4) return null
        val payloadLen = MtProto.leInt(plainBuf, offset) and 0x7FFFFFFF
        if (payloadLen <= 0) return 0
        val packetLen = 4L + payloadLen
        if (packetLen > Int.MAX_VALUE) return 0
        return if (avail < packetLen) null else packetLen.toInt()
    }
}
