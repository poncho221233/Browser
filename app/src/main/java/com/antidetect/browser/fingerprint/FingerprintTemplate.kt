package com.antidetect.browser.fingerprint

import com.google.gson.annotations.SerializedName

/**
 * Serializable fingerprint blueprint.
 * Loaded from assets/fingerprints JSON files, SAF import, or remote URL.
 */
data class FingerprintTemplate(
    @SerializedName("id")
    val id: String = "",

    @SerializedName("name")
    val name: String = "",

    @SerializedName("os")
    val os: String = "Windows",

    @SerializedName("browser")
    val browser: String = "Chrome",

    @SerializedName("version")
    val version: String = "126.0.0.0",

    // ---- Hardware ----
    @SerializedName("screenWidth")
    val screenWidth: Int = 1920,

    @SerializedName("screenHeight")
    val screenHeight: Int = 1080,

    @SerializedName("devicePixelRatio")
    val devicePixelRatio: Double = 1.0,

    @SerializedName("hardwareConcurrency")
    val hardwareConcurrency: Int = 8,

    @SerializedName("deviceMemory")
    val deviceMemory: Int = 8,

    @SerializedName("webglVendor")
    val webglVendor: String = "Google Inc. (NVIDIA)",

    @SerializedName("webglRenderer")
    val webglRenderer: String = "ANGLE (NVIDIA, NVIDIA GeForce RTX 3080 Direct3D11 vs_5_0 ps_5_0, D3D11)",

    // ---- Identity ----
    @SerializedName("userAgent")
    val userAgent: String = "",

    @SerializedName("platform")
    val platform: String = "Win32",

    @SerializedName("language")
    val language: String = "en-US",

    @SerializedName("languages")
    val languages: List<String> = listOf("en-US", "en"),

    // Client Hints as a free-form map
    @SerializedName("clientHints")
    val clientHints: Map<String, String> = emptyMap(),

    // ---- Noise seeds (optional; profile.noiseSeed is primary) ----
    @SerializedName("canvasSeed")
    val canvasSeed: Long? = null,

    @SerializedName("audioSeed")
    val audioSeed: Long? = null,

    // ---- Media devices ----
    @SerializedName("microphones")
    val microphones: Int = 1,

    @SerializedName("speakers")
    val speakers: Int = 1,

    @SerializedName("webcams")
    val webcams: Int = 0,

    // ---- Fonts / Voices ----
    @SerializedName("fonts")
    val fonts: List<String> = emptyList(),

    @SerializedName("voices")
    val voices: List<VoiceEntry> = emptyList(),

    // ---- Network / Battery defaults ----
    @SerializedName("timezone")
    val timezone: String = "America/Mexico_City",

    @SerializedName("connectionType")
    val connectionType: String = "wifi",

    @SerializedName("effectiveType")
    val effectiveType: String = "4g",

    @SerializedName("downlink")
    val downlink: Double = 10.0,

    @SerializedName("rtt")
    val rtt: Int = 50,

    @SerializedName("batteryLevel")
    val batteryLevel: Double = 0.75,

    @SerializedName("batteryCharging")
    val batteryCharging: Boolean = false
) {
    data class VoiceEntry(
        @SerializedName("name") val name: String = "",
        @SerializedName("lang") val lang: String = "en-US",
        @SerializedName("localService") val localService: Boolean = true,
        @SerializedName("default") val default: Boolean = false
    )
}
