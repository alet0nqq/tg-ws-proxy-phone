package com.tgwsproxy.core

import kotlin.system.exitProcess

/** Desktop / debugging entry point: `java -jar core.jar --port 1443 --secret ...`. */
fun main(args: Array<String>) {
    var cfg = ProxyConfig()
    var verbose = false
    val dcIps = ArrayList<String>()
    var dcIpGiven = false
    val cfDomains = ArrayList<String>()
    val workerDomains = ArrayList<String>()
    var i = 0
    fun next(): String = args.getOrNull(++i) ?: run { System.err.println("missing value for ${args[i - 1]}"); exitProcess(2) }
    while (i < args.size) {
        when (val a = args[i]) {
            "--port" -> cfg = cfg.copy(port = next().toInt())
            "--host" -> cfg = cfg.copy(host = next())
            "--secret" -> cfg = cfg.copy(secret = next())
            "--dc-ip" -> { dcIpGiven = true; dcIps.add(next()) }
            "--pool-size" -> cfg = cfg.copy(poolSize = next().toInt().coerceAtLeast(0))
            "--buf-kb" -> cfg = cfg.copy(bufferSize = next().toInt().coerceAtLeast(4) * 1024)
            "--cfproxy-domain" -> cfDomains.add(next())
            "--cfproxy-worker-domain" -> workerDomains.add(next())
            "--no-cfproxy" -> cfg = cfg.copy(fallbackCfProxy = false)
            "--no-secure" -> cfg = cfg.copy(disableSecure = true)
            "--force-test-dc" -> cfg = cfg.copy(forceTestDc = true)
            "-v", "--verbose" -> verbose = true
            "-h", "--help" -> {
                println(
                    """
                    Usage: tg-ws-proxy [options]
                      --port N                 listen port (default 1443)
                      --host H                 listen host (default 127.0.0.1)
                      --secret HEX             32 hex chars (random if omitted)
                      --dc-ip DC:IP            target IP for a DC (repeatable, also per DC)
                      --pool-size N            warm WS connections per DC (default 2)
                      --buf-kb N               socket buffer size in KB (default 256)
                      --cfproxy-domain D       own Cloudflare-proxied domain (repeatable)
                      --cfproxy-worker-domain D Cloudflare Worker domain (repeatable)
                      --no-cfproxy             disable CF proxy fallback
                      --no-secure              port 80 for CF proxy / worker
                      --force-test-dc          route everything to test DCs
                      -v, --verbose            debug logging
                    """.trimIndent(),
                )
                return
            }
            else -> { System.err.println("unknown option $a"); exitProcess(2) }
        }
        i++
    }
    try {
        if (dcIpGiven) cfg = cfg.copy(dcRedirects = ProxyConfig.parseDcIpList(dcIps.joinToString(",")))
        cfg = cfg.copy(
            cfProxyUserDomains = ProxyConfig.parseDomainList(cfDomains.joinToString(",")),
            cfProxyWorkerDomains = ProxyConfig.parseDomainList(workerDomains.joinToString(",")),
        )
        cfg.validate()
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        exitProcess(1)
    }

    val server = ProxyServer(cfg, ProxyLog.console(if (verbose) LogLevel.DEBUG else LogLevel.INFO))
    server.start()
    println("Connect: ${cfg.telegramLink()}")
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
    Thread.currentThread().join()
}
