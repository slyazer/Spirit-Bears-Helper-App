package com.macromonitor.app.domain

object BridgeEndpoints {
    const val WIFI = "http://192.168.1.15:8787"
    const val TAILSCALE = "http://100.116.124.113:8787"
    val all = listOf(WIFI, TAILSCALE)

    fun preferred(onWifi: Boolean): String = if (onWifi) WIFI else TAILSCALE
}
