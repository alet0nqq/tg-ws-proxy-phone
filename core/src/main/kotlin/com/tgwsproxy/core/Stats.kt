package com.tgwsproxy.core

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class Stats {
    val connectionsTotal = AtomicLong()
    val connectionsActive = AtomicInteger()
    val connectionsWs = AtomicLong()
    val connectionsTcpFallback = AtomicLong()
    val connectionsCfProxy = AtomicLong()
    val connectionsFronting = AtomicLong()
    val connectionsBad = AtomicLong()
    val wsErrors = AtomicLong()
    val bytesUp = AtomicLong()
    val bytesDown = AtomicLong()
    val poolHits = AtomicLong()
    val poolMisses = AtomicLong()

    fun reset() {
        listOf(
            connectionsTotal, connectionsWs, connectionsTcpFallback, connectionsCfProxy, connectionsFronting, connectionsBad,
            wsErrors, bytesUp, bytesDown, poolHits, poolMisses,
        ).forEach { it.set(0) }
        connectionsActive.set(0)
    }

    fun summary(): String {
        val poolTotal = poolHits.get() + poolMisses.get()
        val pool = if (poolTotal > 0) "${poolHits.get()}/$poolTotal" else "n/a"
        return "total=${connectionsTotal.get()} active=${connectionsActive.get()} ws=${connectionsWs.get()} " +
            "tcp_fb=${connectionsTcpFallback.get()} cf=${connectionsCfProxy.get()} front=${connectionsFronting.get()} bad=${connectionsBad.get()} " +
            "err=${wsErrors.get()} pool=$pool up=${humanBytes(bytesUp.get())} down=${humanBytes(bytesDown.get())}"
    }

    companion object {
        fun humanBytes(n: Long): String {
            var v = n.toDouble()
            for (unit in listOf("B", "KB", "MB", "GB")) {
                if (v < 1024) return "%.1f%s".format(v, unit)
                v /= 1024
            }
            return "%.1fTB".format(v)
        }
    }
}
