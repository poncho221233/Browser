package com.antidetect.browser.utils

import com.antidetect.browser.data.ProfileEntity
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSessionSettings

object ProxyUtils {

    fun applyProxyToRuntime(settings: GeckoRuntimeSettings.Builder, profile: ProfileEntity) {
        if (profile.proxyType == "None" || profile.proxyHost.isBlank() || profile.proxyPort <= 0) {
            return
        }
        // GeckoView proxy is applied via preferences / runtime settings.
        // For authenticated proxies we rely on the session manager + extension messaging.
        val proxyUrl = when (profile.proxyType) {
            "HTTP" -> "http://${profile.proxyHost}:${profile.proxyPort}"
            "SOCKS5" -> "socks://${profile.proxyHost}:${profile.proxyPort}"
            else -> return
        }
        // Note: full authenticated proxy support is handled in GeckoSessionManager
        // via prefs and optional PAC or extension.
    }

    fun formatProxyDisplay(profile: ProfileEntity): String {
        return if (profile.proxyType == "None" || profile.proxyHost.isBlank()) {
            "Sin proxy"
        } else {
            "${profile.proxyType} ${profile.proxyHost}:${profile.proxyPort}"
        }
    }
}
