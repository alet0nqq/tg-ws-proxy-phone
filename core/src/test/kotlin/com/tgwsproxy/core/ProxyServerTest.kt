package com.tgwsproxy.core

import java.net.Socket
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProxyServerTest {
    private val secretHex = "0123456789abcdef0123456789abcdef"
    private val secret = ProxyConfig.hexToBytes(secretHex)
    private val fake = FakeTelegramWs()
    private var server: ProxyServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop()
        fake.close()
    }

    private fun startProxy(poolSize: Int): ProxyServer {
        val cfg = ProxyConfig(
            port = 0,
            secret = secretHex,
            dcRedirects = mapOf(2 to "127.0.0.1", 4 to "127.0.0.1"),
            poolSize = poolSize,
            fallbackCfProxy = false,
            refreshCfDomains = false,
            wsPort = fake.port,
            wsSecure = false,
        )
        return ProxyServer(cfg, ProxyLog.console(LogLevel.DEBUG)).also { it.start(); server = it }
    }

    private fun roundTrip(proxy: ProxyServer, dc: Int, isMedia: Boolean) {
        val client = TestClient.makeInit(secret, dc, isMedia, MtProto.PROTO_ABRIDGED)
        Socket("127.0.0.1", proxy.localPort).use { s ->
            s.soTimeout = 10_000
            val out = s.getOutputStream()
            val input = s.getInputStream()
            out.write(client.init)
            val rnd = Random(dc)
            val packets = listOf(16, 4, 1020, 70_000, 8).map { abridgedPacket(rnd.nextBytes(it)) }
            // Send everything in one write so the proxy has to split it into WS frames.
            out.write(client.enc.update(packets.reduce { a, b -> a + b }))
            out.flush()
            for (p in packets) {
                val echoed = client.dec.update(readExactly(input, p.size))
                assertContentEquals(p, echoed)
            }
        }
    }

    @Test
    fun bridgesClientToWebSocketAndBack() {
        val proxy = startProxy(poolSize = 0)
        roundTrip(proxy, dc = 2, isMedia = false)
        assertEquals(2, fake.dcIdx)
        assertEquals(listOf(17, 5, 1024, 70_004, 9), fake.frameSizes.toList())
        assertEquals("/apiws", fake.paths.first())
        assertEquals(1, proxy.stats.connectionsWs.get())
    }

    @Test
    fun mediaConnectionUsesNegativeDcAndPool() {
        val proxy = startProxy(poolSize = 1)
        Thread.sleep(500) // let the pool warm up
        roundTrip(proxy, dc = 4, isMedia = true)
        assertEquals(-4, fake.dcIdx)
        assertTrue(proxy.stats.poolHits.get() >= 1, "expected a pool hit: ${proxy.stats.summary()}")
    }

    @Test
    fun wrongSecretCountsAsBad() {
        val proxy = startProxy(poolSize = 0)
        val client = TestClient.makeInit(ByteArray(16), 2, false, MtProto.PROTO_ABRIDGED)
        Socket("127.0.0.1", proxy.localPort).use { s ->
            s.getOutputStream().write(client.init)
            s.shutdownOutput()
            s.soTimeout = 5000
            assertEquals(-1, s.getInputStream().read())
        }
        assertEquals(1, proxy.stats.connectionsBad.get())
    }

    @Test
    fun redirectsBlacklistDcWhenNoFallback() {
        fake.respondStatus = 302
        val proxy = startProxy(poolSize = 0)
        val client = TestClient.makeInit(secret, 2, false, MtProto.PROTO_ABRIDGED)
        Socket("127.0.0.1", proxy.localPort).use { s ->
            s.soTimeout = 3_000
            s.getOutputStream().write(client.init)
            // Both kws2 and kws2-1 answer 302 -> DC is blacklisted and the proxy moves on to
            // TCP fallback (the real DC is not reachable from tests, so just wait a bit).
            runCatching { s.getInputStream().read() }
        }
        assertEquals(2, fake.paths.size)
        assertEquals(2, proxy.stats.wsErrors.get())
    }
}
