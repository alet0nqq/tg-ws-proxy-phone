package com.tgwsproxy.core

object Endpoints {
    const val WS_PATH = "/apiws"
    const val WS_PATH_TEST = "/apiws_test"

    val DC_DEFAULT_IPS: Map<Int, String> = mapOf(
        1 to "149.154.175.50",
        2 to "149.154.167.51",
        3 to "149.154.175.100",
        4 to "149.154.167.91",
        5 to "149.154.171.5",
        203 to "91.105.192.100",
    )

    val DC_TEST_IPS: Map<Int, String> = mapOf(
        1 to "149.154.175.10",
        2 to "149.154.167.40",
        3 to "149.154.175.117",
    )

    fun wsDomains(dc: Int, isMedia: Boolean): List<String> {
        val d = if (dc == 203) 2 else dc
        val main = "kws$d.web.telegram.org"
        val alt = "kws$d-1.web.telegram.org"
        return if (isMedia) listOf(alt, main) else listOf(main, alt)
    }
}
