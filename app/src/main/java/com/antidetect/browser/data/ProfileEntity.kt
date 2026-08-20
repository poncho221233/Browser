package com.antidetect.browser.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.concurrent.ThreadLocalRandom

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // Basic
    val name: String = "",
    val os: String = "Windows",                 // Windows | macOS | Linux | Android
    val fingerprintTemplate: String = "Default",
    val avatarColor: Int = 0xFF6366F1.toInt(),
    val lastUsed: Long = 0L,

    /**
     * Fixed per-profile PRNG seed. Generated once at profile creation and never
     * changed. Guarantees that Canvas / WebGL / Audio / ClientRects / measureText
     * noise is identical across page loads of the same profile and completely
     * different between distinct profiles.
     */
    val noiseSeed: Long = ThreadLocalRandom.current().nextLong(),

    // Identity
    val userAgent: String = "",
    val platform: String = "",
    val language: String = "es-MX",

    // Hardware – empty/neutral defaults; template ALWAYS fills these on apply
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    val webglVendor: String = "",
    val webglRenderer: String = "",
    /** CPU cores reported via navigator.hardwareConcurrency (0 = use template) */
    val hardwareConcurrency: Int = 0,
    /** Device memory GB via navigator.deviceMemory (0 = use template) */
    val deviceMemory: Int = 0,

    // Fingerprint noise modes: Real | AutoNoise | Disabled
    val canvasNoise: String = "AutoNoise",
    val webglNoise: String = "AutoNoise",
    val audioNoise: String = "AutoNoise",
    val clientRectsNoise: String = "AutoNoise",
    val quadsNoise: String = "AutoNoise",
    val fontsNoise: String = "AutoNoise",

    // Flags – defaults tuned so Google reCAPTCHA / login work out of the box.
    // blockWebRTC=true → soft mode: native ICE no_host + JS filters host/LAN.
    // Never kills RTCPeerConnection (breaks captcha and is a bot signal).
    val blockWebRTC: Boolean = true,
    // Keep false: Google login / reCAPTCHA need third-party cookies
    val blockThirdPartyCookies: Boolean = false,
    /** true = borrar cookies/storage al salir; false = guardar sesión (recomendado) */
    val autoCleanOnExit: Boolean = false,
    val javascriptEnabled: Boolean = true,
    /** Usar el proxy configurado (host/puerto en pestaña Proxy) */
    val proxyEnabled: Boolean = true,

    // Network
    val timezone: String = "America/Mexico_City",
    val geoMode: String = "Block",              // Block | AutoIP | Manual
    val geoLatitude: Double = 0.0,
    val geoLongitude: Double = 0.0,

    // Devices
    val microphones: Int = 1,
    val speakers: Int = 1,
    val webcams: Int = 0,
    val portsToBlock: String = "3389,5900,22",

    // Proxy
    val proxyType: String = "None",             // None | HTTP | SOCKS5
    val proxyHost: String = "",
    val proxyPort: Int = 0,
    val proxyUsername: String = "",
    val proxyPassword: String = ""
)
