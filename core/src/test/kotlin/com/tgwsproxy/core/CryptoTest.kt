package com.tgwsproxy.core

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CryptoTest {
    private fun hex(s: String) = ProxyConfig.hexToBytes(s)

    @Test
    fun aesCtrMatchesJdkInArbitraryChunks() {
        val rnd = Random(1)
        repeat(20) {
            val key = rnd.nextBytes(32)
            val iv = rnd.nextBytes(16).also { if (it[15] == 0.toByte()) it[15] = -1; it.fill(-1, 8, 16) } // exercise carry
            val data = rnd.nextBytes(rnd.nextInt(1, 5000))
            val jdk = Cipher.getInstance("AES/CTR/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            }.doFinal(data)

            val ctr = AesCtr(key, iv)
            val out = java.io.ByteArrayOutputStream()
            var off = 0
            while (off < data.size) {
                val n = minOf(rnd.nextInt(1, 100), data.size - off)
                out.write(ctr.update(data, off, n))
                off += n
            }
            assertContentEquals(jdk, out.toByteArray())
        }
    }

    /** Vectors produced by upstream proxy/tg_ws_proxy.py (_try_handshake + _build_crypto_ctx). */
    @Test
    fun matchesUpstreamPythonVectors() {
        val secret = ByteArray(16) { it.toByte() }
        val client = hex(
            "030a11181f262d343b424950575e656c737a81888f969da4abb2b9c0c7ced5dce3eaf1f8ff060d141b222930373e454c535a61686f767d84db3b9332a893e5b4",
        )
        val relay = hex(
            "05121f2c394653606d7a8794a1aebbc8d5e2effc091623303d4a5764717e8b98a5b2bfccd9e6f3000d1a2734414e5b6875828f9ca9b6c3d0ddeaf704111e2b38",
        )
        val hs = assertNotNull(MtProto.parseClientHandshake(client, secret))
        assertEquals(4, hs.dc)
        assertTrue(hs.isMedia)
        assertEquals(MtProto.PROTO_ABRIDGED, hs.proto)

        val ctx = MtProto.buildCryptoCtx(hs.prekeyAndIv, secret, relay)
        val up = ByteArray(100) { it.toByte() }
        val down = ByteArray(112) { (200 + it % 56).toByte() }
        assertContentEquals(
            hex(
                "819102e4a0b599baf940cda419ce50b8609c5bb16d226e045cceeb737e17af94cb0a92e78c9346ebff2281962310bc59b3eccc6a153e3a8c43701604a75e4b3d38ea518f606a0abf939f563eafd605956b6ab8cccf3627f1a67b1904c56ca1e78d592f0e",
            ),
            ctx.tgEnc.update(ctx.cltDec.update(up)),
        )
        assertContentEquals(
            hex(
                "c1416fb17e26595302ab74983dff05610cddb48d2db28001d83c53d8ad20decdaba46100792c7cea9f555dca5d6e2e00103eb4724e935e96e9fd2178e6b71b8069281c1a45f780c8f05f32d70a57924a30b92f9394436260a026e2f4dc4af1eb246ca726973fbe96bdd18f7189a259f9",
            ),
            ctx.cltEnc.update(ctx.tgDec.update(down)),
        )
    }

    @Test
    fun wrongSecretIsRejected() {
        val secret = ByteArray(16) { 7 }
        val client = TestClient.makeInit(secret, dc = 2, isMedia = false, protoTag = MtProto.PROTO_INTERMEDIATE).init
        assertNotNull(MtProto.parseClientHandshake(client, secret))
        assertNull(MtProto.parseClientHandshake(client, ByteArray(16) { 8 }))
    }

    @Test
    fun relayInitCarriesProtoAndDc() {
        val tag = byteArrayOf(0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte())
        repeat(50) {
            val init = MtProto.generateRelayInit(tag, -3)
            assertTrue((init[0].toInt() and 0xFF) != 0xEF)
            val dec = AesCtr(init.copyOfRange(8, 40), init.copyOfRange(40, 56)).update(init)
            assertContentEquals(tag, dec.copyOfRange(56, 60))
            assertEquals(-3, ((dec[60].toInt() and 0xFF) or (dec[61].toInt() shl 8)).toShort().toInt())
        }
    }

    @Test
    fun cfDomainDecodingMatchesUpstream() {
        assertEquals("pclead.co.uk", CfProxyDomains.decode("virkgj.com"))
        assertEquals("pyatdesyatodin.co.uk", CfProxyDomains.decode("dmohrsgmohcrwb.com"))
        assertEquals(20, CfProxyDomains.defaults.size)
    }

    @Test
    fun msgSplitterSplitsAbridgedPackets() {
        val relay = MtProto.generateRelayInit(byteArrayOf(-17, -17, -17, -17), 2)
        val enc = AesCtr(relay.copyOfRange(8, 40), relay.copyOfRange(40, 56)).apply { skip(64) }
        val packets = listOf(
            byteArrayOf(2) + ByteArray(8) { 1 },
            byteArrayOf(0x7F, 0x00, 0x01, 0x00) + ByteArray(1024) { 2 },
            byteArrayOf(1) + ByteArray(4) { 3 },
        )
        val stream = enc.update(packets.reduce { a, b -> a + b })
        val splitter = MsgSplitter(relay, MtProto.PROTO_ABRIDGED)
        val out = ArrayList<ByteArray>()
        var off = 0
        for (n in listOf(3, 500, 7, 600)) {
            val end = minOf(off + n, stream.size)
            out += splitter.split(stream.copyOfRange(off, end))
            off = end
        }
        out += splitter.split(stream.copyOfRange(off, stream.size))
        assertEquals(packets.map { it.size }, out.map { it.size })
        assertContentEquals(stream, out.reduce { a, b -> a + b })
    }

    @Test
    fun configParsing() {
        assertEquals(
            mapOf(2 to listOf("149.154.167.220", "149.154.167.99"), 4 to listOf("1.2.3.4")),
            ProxyConfig.parseDcIpList("2:149.154.167.220\n 4:1.2.3.4\n2:149.154.167.99\n2:149.154.167.220"),
        )
        assertEquals(listOf("a.com", "b.org"), ProxyConfig.parseDomainList("a.com, b.org A.com"))
        val cfg = ProxyConfig(secret = "00112233445566778899aabbccddeeff")
        assertEquals("tg://proxy?server=127.0.0.1&port=1443&secret=dd00112233445566778899aabbccddeeff", cfg.telegramLink())
    }

    @Test
    fun censorHidesForeignDomains() {
        assertEquals("kws2.web.telegram.org", ProxyLog.censorDomains("kws2.web.telegram.org"))
        assertEquals("kw**.pcl***.c*.uk", ProxyLog.censorDomains("kws2.pclead.co.uk"))
    }
}
