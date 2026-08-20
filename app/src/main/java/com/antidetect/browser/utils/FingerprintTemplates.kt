package com.antidetect.browser.utils

import com.antidetect.browser.data.ProfileEntity

object FingerprintTemplates {

    data class Template(
        val name: String,
        val os: String,
        val userAgent: String,
        val platform: String,
        val language: String,
        val screenWidth: Int,
        val screenHeight: Int,
        val webglVendor: String,
        val webglRenderer: String,
        val timezone: String
    )

    val templates: List<Template> = listOf(
        Template(
            name = "Windows 11 · Chrome 126",
            os = "Windows",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            platform = "Win32",
            language = "es-MX",
            screenWidth = 1920,
            screenHeight = 1080,
            webglVendor = "Google Inc. (NVIDIA)",
            webglRenderer = "ANGLE (NVIDIA, NVIDIA GeForce RTX 4080 Direct3D11 vs_5_0 ps_5_0, D3D11)",
            timezone = "America/Mexico_City"
        ),
        Template(
            name = "macOS Apple M3 Max · Chrome",
            os = "macOS",
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            platform = "MacIntel",
            language = "es-MX",
            screenWidth = 3024,
            screenHeight = 1964,
            webglVendor = "Google Inc. (Apple)",
            webglRenderer = "ANGLE (Apple, ANGLE Metal Renderer: Apple M3 Max, Unspecified Version)",
            timezone = "America/Mexico_City"
        ),
        Template(
            name = "Linux Ubuntu · Firefox",
            os = "Linux",
            userAgent = "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:127.0) Gecko/20100101 Firefox/127.0",
            platform = "Linux x86_64",
            language = "es-MX",
            screenWidth = 1920,
            screenHeight = 1080,
            webglVendor = "Intel",
            webglRenderer = "Mesa Intel(R) UHD Graphics 620 (KBL GT2)",
            timezone = "America/Mexico_City"
        ),
        Template(
            name = "Android 14 · Chrome Mobile",
            os = "Android",
            userAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.122 Mobile Safari/537.36",
            platform = "Linux armv8l",
            language = "es-MX",
            screenWidth = 1008,
            screenHeight = 2244,
            webglVendor = "Qualcomm",
            webglRenderer = "Adreno (TM) 740",
            timezone = "America/Mexico_City"
        ),
        Template(
            name = "Windows 10 · Edge",
            os = "Windows",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 Edg/126.0.0.0",
            platform = "Win32",
            language = "es-MX",
            screenWidth = 2560,
            screenHeight = 1440,
            webglVendor = "Google Inc. (AMD)",
            webglRenderer = "ANGLE (AMD, AMD Radeon RX 6800 XT Direct3D11 vs_5_0 ps_5_0, D3D11)",
            timezone = "America/Mexico_City"
        )
    )

    fun applyTemplate(base: ProfileEntity, templateName: String): ProfileEntity {
        val t = templates.find { it.name == templateName } ?: return base
        return base.copy(
            name = if (base.name.isBlank()) t.name else base.name,
            os = t.os,
            fingerprintTemplate = t.name,
            userAgent = t.userAgent,
            platform = t.platform,
            language = t.language,
            screenWidth = t.screenWidth,
            screenHeight = t.screenHeight,
            webglVendor = t.webglVendor,
            webglRenderer = t.webglRenderer,
            timezone = t.timezone
        )
    }

    fun randomAvatarColor(): Int {
        val colors = listOf(
            0xFF6366F1.toInt(), 0xFF8B5CF6.toInt(), 0xFFEC4899.toInt(),
            0xFFEF4444.toInt(), 0xFFF59E0B.toInt(), 0xFF22C55E.toInt(),
            0xFF06B6D4.toInt(), 0xFF3B82F6.toInt(), 0xFFA855F7.toInt()
        )
        return colors.random()
    }
}
