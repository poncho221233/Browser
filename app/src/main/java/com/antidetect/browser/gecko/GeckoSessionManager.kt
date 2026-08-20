package com.antidetect.browser.gecko

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import com.antidetect.browser.data.ProfileEntity
import com.antidetect.browser.fingerprint.FingerprintRepository
import com.antidetect.browser.fingerprint.FingerprintTemplate
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebRequestError
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.lang.ref.WeakReference

/**
 * Manages isolated GeckoSessions per profile.
 *
 * • Native prefs for WebRTC / DNS leak prevention
 * • PermissionDelegate – auto GRANT/DENY from ProfileEntity switches
 * • PromptDelegate – file upload via SAF (ActivityResultLauncher)
 * • Forwards full fingerprint payload to the content-script extension
 */
class GeckoSessionManager(private val context: Context) {

    companion object {
        private const val TAG = "GeckoSessionManager"
        private const val EXTENSION_ID = "antidetect-spoof-v5@local"
        private const val EXTENSION_LOCATION = "resource://android/assets/extension/"

        /** Process-wide singleton – GeckoView allows only ONE GeckoRuntime. */
        @Volatile
        private var sharedRuntime: GeckoRuntime? = null

        @Volatile
            @Volatile private var lastPageUrl: String = ""

    private var sharedExtension: WebExtension? = null

        @Volatile
        var activeConfigJson: String? = null
            private set

        fun setActiveConfig(json: String?) {
            activeConfigJson = json
        }
    }

    private val runtime: GeckoRuntime?
        get() = sharedRuntime
    private var spoofExtension: WebExtension?
        get() = sharedExtension
        set(value) { sharedExtension = value }
    private var latestConfigJson: String?
        get() = activeConfigJson
        set(value) { setActiveConfig(value) }
    private val activeSessions = mutableMapOf<Long, GeckoSession>()
    private val fingerprintRepo by lazy { FingerprintRepository(context) }

    private var lastTemplate: FingerprintTemplate? = null

    /** Weak ref to the hosting Activity so PromptDelegate can launch SAF */
    private var hostActivityRef: WeakReference<Activity>? = null

    /**
     * Callback registered by BrowserActivity:
     * receives the Gecko file-prompt callback so the Activity can
     * launch the system picker and later complete it.
     */
    var onFilePromptRequest: ((
        prompts: Array<out GeckoSession.PromptDelegate.FilePrompt>,
        callback: GeckoSession.PromptDelegate.PromptInstanceDelegate
    ) -> Unit)? = null

    fun setHostActivity(activity: Activity?) {
        hostActivityRef = if (activity != null) WeakReference(activity) else null
    }

    fun setTemplate(tpl: FingerprintTemplate?) {
        lastTemplate = tpl
    }

    // ------------------------------------------------------------------
    // Runtime
    // ------------------------------------------------------------------

    fun getOrCreateRuntime(profile: ProfileEntity): GeckoRuntime {
        sharedRuntime?.let { return it }
        synchronized(GeckoSessionManager::class.java) {
            sharedRuntime?.let { return it }
            val builder = GeckoRuntimeSettings.Builder()
                .aboutConfigEnabled(true)
                .consoleOutput(false)
                .debugLogging(false)
                .javaScriptEnabled(true)
                .remoteDebuggingEnabled(false)
            // Enable extension process if API exists (needed for content_scripts on some builds)
            try {
                val m = builder.javaClass.methods.firstOrNull {
                    it.name == "extensionsProcessEnabled" && it.parameterTypes.size == 1
                }
                m?.invoke(builder, true)
            } catch (_: Exception) {}
            try {
                val m = builder.javaClass.methods.firstOrNull {
                    it.name == "extensionsEnabled" && it.parameterTypes.size == 1
                }
                m?.invoke(builder, true)
            } catch (_: Exception) {}
            val rt = GeckoRuntime.create(context.applicationContext, builder.build())
            sharedRuntime = rt
            // Built-in extension from APK assets. Per-profile config comes from ConfigServer.
            try {
                ConfigServer.start()
                rt.webExtensionController
                    .ensureBuiltIn(EXTENSION_LOCATION, EXTENSION_ID)
                    .accept(
                        { ext ->
                            sharedExtension = ext
                            Log.i(TAG, "ensureBuiltIn ok id=${ext?.id}")
                            activeConfigJson?.let { ConfigServer.updateConfig(it) }
                        },
                        { e -> Log.e(TAG, "ensureBuiltIn failed", e) }
                    )
            } catch (e: Exception) {
                Log.e(TAG, "ensureBuiltIn error", e)
            }
            return rt
        }
    }

    /**
     * Package + install the spoof extension with THIS profile's GPU/screen baked in.
     * Call from a background thread before the first loadUri.
     */
    /**
     * Publish profile fingerprint to ConfigServer so content-scripts can fetch it.
     * Also ensures runtime + built-in extension exist.
     */
    fun installExtensionBlocking(profile: ProfileEntity, timeoutMs: Long = 3000L): Boolean {
        val rt = getOrCreateRuntime(profile)
        val json = buildConfigJson(profile).toString()
        latestConfigJson = json
        activeConfigJson = json
        ConfigServer.start()
        ConfigServer.updateConfig(json)
        // Wait briefly for ensureBuiltIn callback if still pending
        val latch = CountDownLatch(1)
        if (sharedExtension != null) {
            latch.countDown()
        } else {
            try {
                rt.webExtensionController
                    .ensureBuiltIn(EXTENSION_LOCATION, EXTENSION_ID)
                    .accept(
                        { ext ->
                            sharedExtension = ext
                            ConfigServer.updateConfig(json)
                            latch.countDown()
                        },
                        { latch.countDown() }
                    )
            } catch (_: Exception) {
                latch.countDown()
            }
        }
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {}
        Log.i(TAG, "Config published to 127.0.0.1:${ConfigServer.PORT} gpu=${JSONObject(json).optString("webglRenderer").take(40)}")
        return true
    }

    /** Push fingerprint JSON into extension background so every origin can read it. */
    private fun pushConfigToExtension(json: String) {
        try { ConfigServer.updateConfig(json) } catch (_: Exception) {}
        val ext = sharedExtension ?: return
        val rt = sharedRuntime ?: return
        try {
            val cfgObj = try { JSONObject(json) } catch (_: Exception) { return }
            if (cfgObj.optString("webglRenderer").length < 4) return
            val envelope = JSONObject()
                .put("type", "setConfig")
                .put("config", cfgObj)
            try {
                val ctrl = rt.webExtensionController
                val delegate = object : WebExtension.MessageDelegate {
                    override fun onMessage(
                        nativeApp: String,
                        message: Any,
                        sender: WebExtension.MessageSender
                    ): GeckoResult<Any>? {
                        Log.i(TAG, "Native msg from ext: $message")
                        return GeckoResult.fromValue(envelope)
                    }
                }
                // GeckoView API varies – only call setMessageDelegate via reflection
                setMessageDelegateReflect(ctrl, ext, delegate, "browser")
            } catch (e: Exception) {
                Log.d(TAG, "pushConfigToExtension ctrl: ${e.message}")
            }
            Log.i(TAG, "Config queued for extension gpu=${cfgObj.optString("webglRenderer").take(40)}")
        } catch (e: Exception) {
            Log.w(TAG, "pushConfigToExtension failed: ${e.message}")
        }
    }

    /** Safe across GeckoView builds where setMessageDelegate may be missing. */
    private fun setMessageDelegateReflect(
        controller: Any,
        ext: WebExtension,
        delegate: WebExtension.MessageDelegate,
        nativeApp: String
    ) {
        try {
            val method = controller.javaClass.methods.firstOrNull { m ->
                m.name == "setMessageDelegate" && m.parameterTypes.size == 3
            }
            if (method != null) {
                method.invoke(controller, ext, delegate, nativeApp)
            } else {
                Log.d(TAG, "setMessageDelegate not present on ${controller.javaClass.name}")
            }
        } catch (e: Exception) {
            Log.d(TAG, "setMessageDelegateReflect: ${e.message}")
        }
    }

    private fun applyNativePrefs(rt: GeckoRuntime, profile: ProfileEntity) {
        try {
            // Soft WebRTC policy (recommended for captcha + anti-detect):
            // Keep peerconnection ENABLED so Google/reCAPTCHA work, but never expose
            // host / LAN candidates. Page-world JS also filters host candidates.
            val prefs = mutableMapOf<String, Any>(
                // Always leave WebRTC API available – killing it is a bot signal
                "media.peerconnection.enabled" to true,
                // Hide LAN / host candidates (CreepJS "Local IP Addresses")
                "media.peerconnection.ice.default_address_only" to true,
                "media.peerconnection.ice.no_host" to true,
                "media.peerconnection.ice.relay_only" to true,
                "media.peerconnection.ice.proxy_only_if_behind_proxy" to true,
                "media.peerconnection.turn.disable" to false,
                "media.peerconnection.use_document_dns" to false,
                "media.navigator.mediastreamfakeenabled" to false,
                "media.peerconnection.ice.force_interface" to "",
                // mDNS hostnames only (no raw local IPs) when host somehow leaks
                "media.peerconnection.ice.obfuscate_host_addresses" to true,
                "network.dns.disablePrefetch" to true,
                "network.dns.blockDotOnion" to true,
                "network.proxy.allow_hijacking_localhost" to false,
                // Cookies: reCAPTCHA / Google login need third-party cookies
                "network.cookie.cookieBehavior" to if (profile.blockThirdPartyCookies) 1 else 0,
                "network.cookie.thirdparty.sessionOnly" to false,
                "dom.storage_access.enabled" to true,
                "dom.storage.enabled" to true,
                // Reduce "unusual traffic" friction
                "privacy.trackingprotection.enabled" to false,
                "privacy.trackingprotection.pbmode.enabled" to false,
                "privacy.resistFingerprinting" to false,
                "network.http.sendRefererHeader" to 2,
                "network.http.referer.XOriginPolicy" to 0,
                "intl.accept_languages" to (if (profile.language.isNotBlank())
                    "${profile.language},es,en-US,en" else "es-MX,es,en-US,en"),
                "intl.locale.requested" to (profile.language.ifBlank { "es-MX" }),
                "javascript.enabled" to true,
                "dom.event.clipboardevents.enabled" to true
            )

            val useProxy = profile.proxyEnabled &&
                profile.proxyType != "None" &&
                profile.proxyHost.isNotBlank() &&
                profile.proxyPort > 0

            if (useProxy) {
                prefs["media.peerconnection.ice.proxy_only"] = true
                prefs["media.peerconnection.ice.relay_only"] = true
                when (profile.proxyType.uppercase()) {
                    "SOCKS5", "SOCKS" -> {
                        prefs["network.proxy.type"] = 1
                        prefs["network.proxy.socks"] = profile.proxyHost
                        prefs["network.proxy.socks_port"] = profile.proxyPort
                        prefs["network.proxy.socks_version"] = 5
                        prefs["network.proxy.socks_remote_dns"] = true
                        prefs["network.proxy.proxy_over_tls"] = true
                        prefs["network.proxy.no_proxies_on"] = ""
                        prefs["network.dns.disabled"] = false
                    }
                    "HTTP", "HTTPS" -> {
                        prefs["network.proxy.type"] = 1
                        prefs["network.proxy.http"] = profile.proxyHost
                        prefs["network.proxy.http_port"] = profile.proxyPort
                        prefs["network.proxy.ssl"] = profile.proxyHost
                        prefs["network.proxy.ssl_port"] = profile.proxyPort
                        prefs["network.proxy.share_proxy_settings"] = true
                        prefs["network.proxy.no_proxies_on"] = ""
                    }
                }
            } else {
                prefs["network.proxy.type"] = 0
            }

            pushPrefsViaReflection(rt, prefs)
            Log.i(
                TAG,
                "Native prefs applied (softWebRTC, proxy=${profile.proxyType}, enabled=${profile.proxyEnabled})"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not apply all native prefs – JS fallback active", e)
        }
    }

    /** Re-apply proxy / WebRTC prefs for an open session (toggle without restart). */
    fun applyProxyPrefs(profile: ProfileEntity) {
        val rt = sharedRuntime ?: return
        applyNativePrefs(rt, profile)
    }

    /** Per-profile on-disk directory for optional future engine data */
    fun profileDataDir(profileId: Long): File {
        val dir = File(context.filesDir, "gecko_profiles/p_$profileId")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Container id used by GeckoView to isolate cookies / storage / cache */
    fun contextIdFor(profileId: Long): String = "gestortdd_p_$profileId"

    private fun pushPrefsViaReflection(rt: GeckoRuntime, prefs: Map<String, Any>) {
        try {
            val settings = rt.settings
            // Try every plausible setPref signature across GeckoView builds
            val candidates = settings.javaClass.methods.filter { m ->
                val n = m.name.lowercase()
                (n == "setpref" || n == "setprefs" || n.contains("pref")) &&
                    m.parameterTypes.isNotEmpty()
            }
            var applied = 0
            for (method in candidates) {
                try {
                    when (method.parameterTypes.size) {
                        2 -> {
                            prefs.forEach { (k, v) ->
                                try {
                                    method.invoke(settings, k, v)
                                    applied++
                                } catch (_: Exception) {}
                            }
                        }
                        1 -> {
                            try {
                                method.invoke(settings, prefs)
                                applied += prefs.size
                            } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
            }
            // Also try GeckoRuntime / GeckoView Preferences API if present
            try {
                val rtMethods = rt.javaClass.methods.filter {
                    it.name.contains("Pref", ignoreCase = true) && it.parameterTypes.size == 2
                }
                for (m in rtMethods) {
                    prefs.forEach { (k, v) ->
                        try { m.invoke(rt, k, v); applied++ } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
            Log.d(TAG, "Prefs reflection applied≈$applied keys")
        } catch (e: Exception) {
            Log.d(TAG, "Reflection pref push skipped: ${e.message}")
        }
    }

    /**
     * Build a per-profile extension XPI with __EMBEDDED_CONFIG baked into inject.js
     * so the page-world spoof always receives the correct GPU / screen / timezone
     * even when native messaging is unavailable.
     */

    /**
     * Build per-profile extension directory + XPI.
     * Ships config.json + __EMBEDDED_CONFIG so content scripts always
     * receive the correct GPU / screen / timezone for THIS profile.
     */
    private fun packageExtensionWithConfig(profile: ProfileEntity): File {
        val work = File(context.cacheDir, "ext_pkg_${profile.id}")
        if (work.exists()) work.deleteRecursively()
        work.mkdirs()

        val assetNames = listOf("manifest.json", "background.js", "early-spoof.js", "inject.js", "page-world.js")
        for (name in assetNames) {
            try {
                context.assets.open("extension/$name").use { input ->
                    File(work, name).outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "asset $name missing: ${e.message}")
            }
        }

        val configJson = buildConfigJson(profile)
        val cfgStr = configJson.toString()
        File(work, "config.json").writeText(cfgStr)

        // Bake into inject.js – first lines so content-script has GPU/screen at document_start
        val injectFile = File(work, "inject.js")
        if (injectFile.exists()) {
            val original = injectFile.readText()
            injectFile.writeText(
                "var __EMBEDDED_CONFIG = " + cfgStr + ";\n" +
                    "try{if(typeof browser!=='undefined'&&browser.runtime){" +
                    "browser.runtime.sendMessage({type:'setConfig',config:__EMBEDDED_CONFIG}).catch(function(){});" +
                    "}}catch(e){}\n" +
                    original
            )
        }

        // Bake into early-spoof.js (runs FIRST at document_start)
        val earlyFile = File(work, "early-spoof.js")
        if (earlyFile.exists()) {
            val earlyOrig = earlyFile.readText()
            earlyFile.writeText("var __EMBEDDED_CONFIG = " + cfgStr + ";\n" + earlyOrig)
        }

        // Bake into page-world.js – apply immediately when loaded into page
        val pageFile = File(work, "page-world.js")
        if (pageFile.exists()) {
            val pageOrig = pageFile.readText()
            pageFile.writeText(
                "try{sessionStorage.setItem('__adcfg',JSON.stringify(" + cfgStr + "));}catch(e){}\n" +
                    "try{window.__GESTOR_CFG=" + cfgStr + ";window.__cfg=" + cfgStr + ";}catch(e){}\n" +
                    pageOrig
            )
        }

        // Bake into background.js – __EMBEDDED_CONFIG is read at top of background.js
        val bgFile = File(work, "background.js")
        if (bgFile.exists()) {
            val bgOrig = bgFile.readText()
            bgFile.writeText("var __EMBEDDED_CONFIG = " + cfgStr + ";\n" + bgOrig)
        }

        val xpi = File(context.cacheDir, "antidetect_${profile.id}.xpi")
        if (xpi.exists()) xpi.delete()
        ZipOutputStream(FileOutputStream(xpi)).use { zos ->
            for (name in assetNames + listOf("config.json")) {
                val f = File(work, name)
                if (!f.exists()) continue
                zos.putNextEntry(ZipEntry(name))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        Log.i(
            TAG,
            "Packaged ext profile=${profile.id} gpu=${configJson.optString("webglRenderer").take(40)} " +
                "screen=${configJson.optInt("screenWidth")}x${configJson.optInt("screenHeight")}"
        )
        return xpi
    }


    private fun buildConfigJson(profile: ProfileEntity): JSONObject {
        // Always resolve fingerprint from assets by name – do not rely only on lastTemplate
        val resolved = try {
            fingerprintRepo.resolveTemplateSync(profile)
        } catch (e: Exception) {
            Log.w(TAG, "resolveTemplateSync failed: ${e.message}")
            null
        }
        // NEVER reuse lastTemplate from another profile (cross-contamination bug)
        val tpl = resolved
        if (resolved != null) {
            lastTemplate = resolved
            Log.i(
                TAG,
                "Template resolved name=${resolved.name} gpu=${resolved.webglRenderer} " +
                    "screen=${resolved.screenWidth}x${resolved.screenHeight} " +
                    "cores=${resolved.hardwareConcurrency} mem=${resolved.deviceMemory}"
            )
        } else {
            Log.w(TAG, "No template for profile=${profile.name} tplKey=${profile.fingerprintTemplate} os=${profile.os}")
            lastTemplate = null
        }

        val osL = (profile.os.ifBlank { tpl?.os ?: "" }).lowercase()

        // PROFILE entity wins when it already has real values (set at Launch from template).
        // Template is fallback. Never bleed GPU/UA from a previous profile.
        fun good(s: String?) = !s.isNullOrBlank() && s.length > 3
        val gpuVendor = when {
            good(profile.webglVendor) -> profile.webglVendor
            good(tpl?.webglVendor) -> tpl!!.webglVendor
            osL.contains("mac") -> "Google Inc. (Apple)"
            osL.contains("ios") || osL.contains("iphone") -> "Apple Inc."
            osL.contains("android") -> "Qualcomm"
            osL.contains("linux") -> "Intel"
            else -> "Google Inc. (NVIDIA)"
        }
        val gpuRenderer = when {
            good(profile.webglRenderer) -> profile.webglRenderer
            good(tpl?.webglRenderer) -> tpl!!.webglRenderer
            osL.contains("mac") ->
                "ANGLE (Apple, ANGLE Metal Renderer: Intel(R) Iris(TM) Plus Graphics, Unspecified Version)"
            osL.contains("ios") || osL.contains("iphone") -> "Apple GPU"
            osL.contains("android") -> "Adreno (TM) 750"
            osL.contains("linux") -> "Mesa Intel(R) UHD Graphics 620 (KBL GT2)"
            else -> "ANGLE (NVIDIA, NVIDIA GeForce RTX 4060 Direct3D11 vs_5_0 ps_5_0, D3D11)"
        }
        val ua = when {
            good(profile.userAgent) && profile.userAgent.length > 20 -> profile.userAgent
            good(tpl?.userAgent) && (tpl?.userAgent?.length ?: 0) > 20 -> tpl!!.userAgent
            osL.contains("android") ->
                "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
            osL.contains("ios") || osL.contains("iphone") ->
                "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"
            osL.contains("mac") ->
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
            osL.contains("linux") ->
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
            else ->
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        }
        val platform = when {
            good(profile.platform) -> profile.platform
            good(tpl?.platform) -> tpl!!.platform
            osL.contains("android") -> "Linux armv8l"
            osL.contains("ios") || osL.contains("iphone") -> "iPhone"
            osL.contains("mac") -> "MacIntel"
            osL.contains("linux") -> "Linux x86_64"
            else -> "Win32"
        }
        val screenW = when {
            profile.screenWidth > 0 -> profile.screenWidth
            tpl != null && tpl.screenWidth > 0 -> tpl.screenWidth
            osL.contains("android") -> 1080
            osL.contains("ios") -> 393
            osL.contains("mac") -> 2560
            else -> 1920
        }
        val screenH = when {
            profile.screenHeight > 0 -> profile.screenHeight
            tpl != null && tpl.screenHeight > 0 -> tpl.screenHeight
            osL.contains("android") -> 2400
            osL.contains("ios") -> 852
            osL.contains("mac") -> 1600
            else -> 1080
        }
        // Profile override wins, then template, then OS defaults
        val cores = profile.hardwareConcurrency.takeIf { it > 0 }
            ?: tpl?.hardwareConcurrency?.takeIf { it > 0 }
            ?: when {
                osL.contains("mac") -> 12
                osL.contains("android") || osL.contains("ios") -> 8
                else -> 8
            }
        val mem = profile.deviceMemory.takeIf { it > 0 }
            ?: tpl?.deviceMemory?.takeIf { it > 0 }
            ?: when {
                osL.contains("mac") -> 16
                osL.contains("android") || osL.contains("ios") -> 8
                else -> 8
            }
        val dpr = tpl?.devicePixelRatio?.takeIf { it > 0 }
            ?: when {
                osL.contains("mac") -> 2.0
                osL.contains("android") || osL.contains("ios") -> 3.0
                else -> 1.0
            }
        val tz = tpl?.timezone?.takeIf { it.isNotBlank() }
            ?: profile.timezone.ifBlank { "America/Mexico_City" }
        val langs = when {
            tpl?.languages?.isNotEmpty() == true -> tpl.languages
            profile.language.isNotBlank() -> listOf(profile.language)
            else -> listOf("es-MX", "es", "en-US", "en")
        }
        val osOut = profile.os.takeIf { it.isNotBlank() } ?: tpl?.os?.takeIf { it.isNotBlank() } ?: "Windows"

        return JSONObject().apply {
            put("noiseSeed", profile.noiseSeed)
            put("userAgent", ua)
            put("platform", platform)
            put("language", (tpl?.language ?: profile.language).ifBlank { langs.firstOrNull() ?: "es-MX" })
            put("languages", org.json.JSONArray(langs))
            put("screenWidth", screenW)
            put("screenHeight", screenH)
            put("webglVendor", gpuVendor)
            put("webglRenderer", gpuRenderer)
            put("gpuVendor", gpuVendor)
            put("gpuRenderer", gpuRenderer)
            put("canvasNoise", profile.canvasNoise)
            put("webglNoise", profile.webglNoise)
            put("audioNoise", profile.audioNoise)
            put("clientRectsNoise", profile.clientRectsNoise)
            put("fontsNoise", profile.fontsNoise)
            put("blockWebRTC", profile.blockWebRTC)
            put("timezone", tz)
            put("geoMode", profile.geoMode)
            put("geoLatitude", profile.geoLatitude)
            put("geoLongitude", profile.geoLongitude)
            put("microphones", tpl?.microphones ?: profile.microphones)
            put("speakers", tpl?.speakers ?: profile.speakers)
            put("webcams", tpl?.webcams ?: profile.webcams)
            put("os", osOut)
            put("name", profile.name)
            put("fingerprintTemplate", profile.fingerprintTemplate.ifBlank { tpl?.name ?: "" })
            put("hardwareConcurrency", cores)
            put("deviceMemory", mem)
            put("devicePixelRatio", dpr)

            val extras = fingerprintRepo.buildExtensionExtras(tpl)
            for ((k, v) in extras) {
                when (v) {
                    null -> put(k, JSONObject.NULL)
                    is Number -> put(k, v)
                    is Boolean -> put(k, v)
                    is String -> put(k, v)
                    is List<*> -> put(k, org.json.JSONArray(v))
                    is Map<*, *> -> put(k, JSONObject(v as Map<*, *>))
                    else -> put(k, v.toString())
                }
            }
            // Final authority: template identity must not be overwritten by extras
            put("webglVendor", gpuVendor)
            put("webglRenderer", gpuRenderer)
            put("gpuVendor", gpuVendor)
            put("gpuRenderer", gpuRenderer)
            put("screenWidth", screenW)
            put("screenHeight", screenH)
            put("hardwareConcurrency", cores)
            put("deviceMemory", mem)
            put("devicePixelRatio", dpr)
            put("userAgent", ua)
            put("platform", platform)
            put("os", osOut)
            put("timezone", tz)
            put("languages", org.json.JSONArray(langs))
        }
    }



    /**
     * Install / update the spoof extension as a per-profile XPI with embedded config.
     * ensureBuiltIn alone cannot carry per-profile GPU/screen data.
     */
    private fun installSpoofExtension(runtime: GeckoRuntime, profile: ProfileEntity? = null) {
        if (profile == null) return
        try {
            latestConfigJson = buildConfigJson(profile).toString()
            val xpi = packageExtensionWithConfig(profile)
            val uri = xpi.toURI().toString() // file:/...
            Log.i(TAG, "Installing per-profile XPI $uri")

            // Prefer update if already installed, else install
            val controller = runtime.webExtensionController
            val existing = sharedExtension
            if (existing != null) {
                try {
                    // GeckoView: install() replaces or update() when available
                    val updateM = controller.javaClass.methods.firstOrNull { m ->
                        m.name == "install" || m.name == "installBuiltIn" || m.name == "update"
                    }
                    controller.install(uri).accept(
                        { ext ->
                            sharedExtension = ext
                            Log.i(TAG, "XPI installed for profile ${profile.id}")
                        },
                        { e -> Log.e(TAG, "XPI install failed: ${e?.message}") }
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "XPI update path: ${e.message}")
                    try {
                        controller.install(uri).accept(
                            { ext -> sharedExtension = ext },
                            { err -> Log.e(TAG, "XPI install err: ${err?.message}") }
                        )
                    } catch (e2: Exception) {
                        Log.e(TAG, "XPI install exception: ${e2.message}")
                    }
                }
            } else {
                controller.install(uri).accept(
                    { ext ->
                        sharedExtension = ext
                        Log.i(TAG, "XPI first install profile ${profile.id}")
                    },
                    { e ->
                        Log.e(TAG, "XPI install failed, fallback ensureBuiltIn: ${e?.message}")
                        try {
                            controller.ensureBuiltIn(EXTENSION_LOCATION, EXTENSION_ID).accept(
                                { ext -> sharedExtension = ext },
                                { err -> Log.e(TAG, "ensureBuiltIn fallback failed", err) }
                            )
                        } catch (_: Exception) {}
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "installSpoofExtension failed: ${e.message}")
        }
    }

    /**
     * Prepare navigation URL. Publishes config to ConfigServer and appends a
     * COMPACT #__ad= fragment so early-spoof.js can read GPU/screen at document_start
     * even when localhost fetch is blocked on HTTPS pages. early-spoof strips the hash.
     */
    fun urlWithConfig(url: String, profile: ProfileEntity? = null): String {
        val full = when {
            profile != null -> buildConfigJson(profile)
            latestConfigJson != null -> try { JSONObject(latestConfigJson!!) } catch (_: Exception) { return url }
            else -> return url
        }
        val json = full.toString()
        latestConfigJson = json
        activeConfigJson = json
        pushConfigToExtension(json)
        try { ConfigServer.updateConfig(json) } catch (_: Exception) {}

        // Compact payload – only fields needed for spoof (keeps hash small)
        val compact = JSONObject().apply {
            put("webglVendor", full.optString("webglVendor"))
            put("webglRenderer", full.optString("webglRenderer"))
            put("gpuVendor", full.optString("gpuVendor"))
            put("gpuRenderer", full.optString("gpuRenderer"))
            put("screenWidth", full.optInt("screenWidth", 1920))
            put("screenHeight", full.optInt("screenHeight", 1080))
            put("hardwareConcurrency", full.optInt("hardwareConcurrency", 8))
            put("deviceMemory", full.optInt("deviceMemory", 8))
            put("devicePixelRatio", full.optDouble("devicePixelRatio", 2.0))
            put("userAgent", full.optString("userAgent"))
            put("platform", full.optString("platform"))
            put("os", full.optString("os"))
            put("language", full.optString("language"))
            put("timezone", full.optString("timezone"))
            put("noiseSeed", full.optLong("noiseSeed", 0))
            if (full.has("languages")) put("languages", full.optJSONArray("languages"))
        }
        val b64 = android.util.Base64.encodeToString(
            compact.toString().toByteArray(Charsets.UTF_8),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
        )
        val base = url.substringBefore("#")
        // Only append if hash stays reasonable (avoid breaking long redirects)
        return if (b64.length < 2500) "$base#__ad=$b64" else base
    }

    fun createSession(profile: ProfileEntity): GeckoSession {
        // Reuse existing session for this profile if still open – still refresh config
        activeSessions[profile.id]?.let { existing ->
            val cfg = buildConfigJson(profile).toString()
            latestConfigJson = cfg
            activeConfigJson = cfg
            pushConfigToExtension(cfg)
            try {
                existing.progressDelegate = object : GeckoSession.ProgressDelegate {
                    override fun onPageStop(session: GeckoSession, success: Boolean) {
                        if (success) injectConfigScript(session, cfg)
                    }
                }
            } catch (_: Exception) {}
            return existing
        }

        val rt = getOrCreateRuntime(profile)
        applyNativePrefs(rt, profile)
        installSpoofExtension(rt, profile)
        latestConfigJson = buildConfigJson(profile).toString()

        val isMobileOs = profile.os.contains("android", ignoreCase = true) ||
            profile.os.contains("ios", ignoreCase = true) ||
            profile.os.contains("iphone", ignoreCase = true) ||
            profile.userAgent.contains("iPhone", ignoreCase = true) ||
            profile.userAgent.contains("Mobile", ignoreCase = true) &&
                profile.userAgent.contains("Android", ignoreCase = true)
        // Ensure on-disk profile folder exists (isolation boundary)
        profileDataDir(profile.id)

        val builder = GeckoSessionSettings.Builder()
            .usePrivateMode(false)
            .userAgentMode(
                if (isMobileOs) GeckoSessionSettings.USER_AGENT_MODE_MOBILE
                else GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
            )
            .userAgentOverride(profile.userAgent)
            .allowJavascript(profile.javascriptEnabled)
            .suspendMediaWhenInactive(true)

        // Isolate cookies / localStorage / IndexedDB / cache per profile (container tabs)
        try {
            builder.contextId(contextIdFor(profile.id))
        } catch (e: Exception) {
            Log.w(TAG, "contextId not supported on this GeckoView: ${e.message}")
        }

        val settings = builder.build()
        val session = GeckoSession(settings)
        session.open(rt)
        session.permissionDelegate = ProfilePermissionDelegate(profile, context)
        session.promptDelegate = ProfilePromptDelegate(this)

        // Config for extension + optional post-load inject.
        // NEVER use loadUri("javascript:") – it cancels the real navigation.
        // NEVER set a NavigationDelegate proxy that returns false – it blocks loads.
        val cfgJson = buildConfigJson(profile).toString()
        latestConfigJson = cfgJson
        activeConfigJson = cfgJson
        pushConfigToExtension(cfgJson)

        // Track URL so we inject once per navigation after load completes
        var lastInjectUrl = ""
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                lastPageUrl = url
                try {
                    ConfigServer.updateConfig(latestConfigJson ?: cfgJson)
                } catch (_: Exception) {}
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (!success) return
                val j = latestConfigJson ?: cfgJson
                // Always re-publish config
                try { ConfigServer.updateConfig(j) } catch (_: Exception) {}
                // One-shot page-world inject after load (backup if content-script missed)
                injectConfigScript(session, j)
            }
        }

        sendFingerprintConfig(session, profile)
        activeSessions[profile.id] = session
        Log.i(
            TAG,
            "Session created profile=${profile.id} contextId=${contextIdFor(profile.id)} " +
                "gpu=${JSONObject(cfgJson).optString("webglRenderer").take(48)} " +
                "screen=${JSONObject(cfgJson).optInt("screenWidth")}x${JSONObject(cfgJson).optInt("screenHeight")}"
        )
        return session
    }

    /**
     * Pushes fingerprint JSON into the page world.
     * Prefers GeckoSession.evaluateJS / similar when available; never navigates away.
     */
    private fun isCaptchaOrAuthUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val u = url.lowercase()
        return listOf(
            "recaptcha", "hcaptcha", "captcha", "challenges.cloudflare",
            "accounts.google", "login.microsoft", "auth0.com", "okta.com"
        ).any { u.contains(it) }
    }

    private fun injectConfigScript(session: GeckoSession, json: String) {
        if (isCaptchaOrAuthUrl(lastPageUrl)) {
            Log.d(TAG, "skip spoof inject on captcha/auth url")
            return
        }
        try {
            val escaped = JSONObject.quote(json) // safe JS string literal
            // Aggressive page-world spoof. Runs on every page start/stop.
            // Critical: wrap canvas.getContext so NEW WebGL contexts always return spoofed GPU.
            val js =
                """
                (function(){try{
                  var cfg=JSON.parse($escaped);
                  window.__GESTOR_CFG=cfg;
                  try{sessionStorage.setItem('__adcfg',JSON.stringify(cfg));}catch(e){}
                  if(typeof window.__gestorApplyConfig==='function'){try{window.__gestorApplyConfig(cfg);}catch(e){}}

                  var d=Object.defineProperty;
                  function g(o,p,v){try{d(o,p,{get:function(){return v},set:function(){},configurable:true,enumerable:true});}catch(e){}}
                  var ua=cfg.userAgent||'';
                  var plat=cfg.platform||'Win32';
                  var lang=cfg.language||'es-MX';
                  var langs=Array.isArray(cfg.languages)&&cfg.languages.length?cfg.languages:[lang,'es','en-US'];
                  var cores=(cfg.hardwareConcurrency|0)||8;
                  var mem=(cfg.deviceMemory|0)||8;
                  var sw=(cfg.screenWidth|0)||1920;
                  var sh=(cfg.screenHeight|0)||1080;
                  var V=cfg.webglVendor||cfg.gpuVendor||'Google Inc. (NVIDIA)';
                  var R=cfg.webglRenderer||cfg.gpuRenderer||'ANGLE (NVIDIA, NVIDIA GeForce RTX 4060 Direct3D11 vs_5_0 ps_5_0, D3D11)';
                  var tz=cfg.timezone||'America/Mexico_City';
                  var osL=String(cfg.os||'').toLowerCase();
                  var touch=(osL.indexOf('android')>=0||osL.indexOf('ios')>=0||osL.indexOf('iphone')>=0)?5:0;
                  if(ua.length>20){
                    try{
                      var NP=Navigator.prototype;
                      g(NP,'userAgent',ua);g(NP,'platform',plat);g(NP,'language',lang);
                      g(NP,'languages',Object.freeze(langs.slice()));
                      g(NP,'hardwareConcurrency',cores);g(NP,'deviceMemory',mem);
                      g(NP,'maxTouchPoints',touch);g(NP,'vendor','Google Inc.');
                      g(NP,'webdriver',false);
                      try{g(navigator,'userAgent',ua);g(navigator,'platform',plat);}catch(e){}
                    }catch(e){}
                  }
                  // Screen – prototype + instance (Gecko often ignores prototype-only)
                  try{
                    var SP=Screen.prototype;
                    g(SP,'width',sw);g(SP,'height',sh);g(SP,'availWidth',sw);g(SP,'availHeight',Math.max(sh-40,1));
                    g(SP,'colorDepth',24);g(SP,'pixelDepth',24);
                    try{
                      g(window.screen,'width',sw);g(window.screen,'height',sh);
                      g(window.screen,'availWidth',sw);g(window.screen,'availHeight',Math.max(sh-40,1));
                    }catch(e){}
                    try{
                      g(window,'innerWidth',Math.min(sw,1400));g(window,'innerHeight',Math.min(sh,900));
                      g(window,'outerWidth',Math.min(sw,1400));g(window,'outerHeight',Math.min(sh,900));
                      g(window,'devicePixelRatio',(cfg.devicePixelRatio>0?cfg.devicePixelRatio:1));
                    }catch(e){}
                  }catch(e){}

                  // WebGL – wrap getParameter on prototypes AND every context from getContext
                  try{
                    function wrapGP(orig){
                      if(!orig)return orig;
                      return function(p){
                        p=p|0;
                        if(p===37445||p===0x9245||p===7936||p===0x1F00)return V;
                        if(p===37446||p===0x9246||p===7937||p===0x1F01)return R;
                        try{return orig.call(this,p);}catch(e){return null;}
                      };
                    }
                    function patchCtx(ctx){
                      if(!ctx||ctx.__gestorGpu)return ctx;
                      try{
                        var o=ctx.getParameter.bind(ctx);
                        ctx.getParameter=wrapGP(o);
                        ctx.__gestorGpu=1;
                      }catch(e){}
                      return ctx;
                    }
                    if(typeof WebGLRenderingContext!=='undefined'&&WebGLRenderingContext.prototype){
                      try{WebGLRenderingContext.prototype.getParameter=wrapGP(WebGLRenderingContext.prototype.getParameter);}catch(e){}
                      try{
                        var ogE=WebGLRenderingContext.prototype.getExtension;
                        WebGLRenderingContext.prototype.getExtension=function(n){
                          var e=ogE.call(this,n);
                          if(e&&/debug_renderer_info/i.test(String(n||''))){
                            try{e.UNMASKED_VENDOR_WEBGL=37445;e.UNMASKED_RENDERER_WEBGL=37446;}catch(x){}
                          }
                          return e;
                        };
                      }catch(e){}
                    }
                    if(typeof WebGL2RenderingContext!=='undefined'&&WebGL2RenderingContext.prototype){
                      try{WebGL2RenderingContext.prototype.getParameter=wrapGP(WebGL2RenderingContext.prototype.getParameter);}catch(e){}
                    }
                    function wrapGetContext(proto){
                      if(!proto||!proto.getContext||proto.getContext.__gestor)return;
                      var og=proto.getContext;
                      proto.getContext=function(type,attrs){
                        var ctx=og.call(this,type,attrs);
                        try{
                          if(ctx&&type&&/webgl/i.test(String(type)))patchCtx(ctx);
                        }catch(e){}
                        return ctx;
                      };
                      proto.getContext.__gestor=1;
                    }
                    try{wrapGetContext(HTMLCanvasElement.prototype);}catch(e){}
                    try{if(typeof OffscreenCanvas!=='undefined')wrapGetContext(OffscreenCanvas.prototype);}catch(e){}
                  }catch(e){}

                  // Timezone
                  try{Date.prototype.getTimezoneOffset=function(){return 360;};}catch(e){}
                  try{if(Intl&&Intl.DateTimeFormat){var ro=Intl.DateTimeFormat.prototype.resolvedOptions;
                    Intl.DateTimeFormat.prototype.resolvedOptions=function(){var o=ro.apply(this,arguments);try{o.timeZone=tz;}catch(e){}return o;};}}catch(e){}

                  // Soft WebRTC: keep API alive (reCAPTCHA), drop host/LAN candidates only
                  try{
                    function softRtc(Orig){
                      if(!Orig||Orig.__pwRtc)return Orig;
                      function PC(c,o){
                        var pc=new Orig(c,o);
                        try{
                          var _on=null;
                          Object.defineProperty(pc,'onicecandidate',{
                            get:function(){return _on;},
                            set:function(fn){
                              _on=typeof fn==='function'?function(ev){
                                if(ev&&ev.candidate&&ev.candidate.candidate){
                                  var x=ev.candidate.candidate;
                                  if(/ typ host /.test(x)||/192\\.168\\.|10\\.|172\\.(1[6-9]|2\\d|3[01])\\./.test(x))return;
                                }
                                fn.call(pc,ev);
                              }:fn;
                            },configurable:true
                          });
                        }catch(e){}
                        return pc;
                      }
                      PC.prototype=Orig.prototype;
                      try{PC.__pwRtc=1;}catch(e){}
                      return PC;
                    }
                    if(window.RTCPeerConnection)window.RTCPeerConnection=softRtc(window.RTCPeerConnection);
                    if(window.webkitRTCPeerConnection)window.webkitRTCPeerConnection=softRtc(window.webkitRTCPeerConnection);
                    if(window.mozRTCPeerConnection)window.mozRTCPeerConnection=softRtc(window.mozRTCPeerConnection);
                  }catch(e){}
                }catch(e){}})();
                """.trimIndent()

            // Called only from onPageStop (page already loaded) – safe to use javascript:
            // as a one-shot backup. Extension + ConfigServer handle document_start.
            var injected = false
            try {
                val methods = session.javaClass.methods
                val eval = methods.firstOrNull { m ->
                    (m.name == "evaluateJS" || m.name == "evaluateJavascript" ||
                        m.name == "executeScript") && m.parameterTypes.size in 1..2
                }
                if (eval != null) {
                    when (eval.parameterTypes.size) {
                        1 -> eval.invoke(session, js)
                        2 -> eval.invoke(session, js, null)
                    }
                    injected = true
                    Log.d(TAG, "injectConfigScript via ${eval.name}")
                }
            } catch (e: Exception) {
                Log.d(TAG, "evaluateJS unavailable: ${e.message}")
            }

            // Careful one-shot javascript: ONLY after page stop, skip captcha hosts.
            // Extension may not run content_scripts on this GeckoView build.
            if (!injected) {
                try {
                    val seed = (
                        "(function(){try{var c=" + escaped + ";" +
                        "window.__GESTOR_CFG=c;window.__cfg=c;" +
                        "try{sessionStorage.setItem('__adcfg',JSON.stringify(c));}catch(e){}" +
                        "var D=Object.defineProperty;function g(o,p,v){try{D(o,p,{get:function(){return v},set:function(){},configurable:true,enumerable:true})}catch(e){}}" +
                        "var V=c.webglVendor||c.gpuVendor||'Google Inc. (Apple)';" +
                        "var R=c.webglRenderer||c.gpuRenderer||'ANGLE (Apple, ANGLE Metal Renderer: Intel(R) Iris(TM) Plus Graphics, Unspecified Version)';" +
                        "var sw=(c.screenWidth|0)||2560,sh=(c.screenHeight|0)||1600;" +
                        "var cores=(c.hardwareConcurrency|0)||12,mem=(c.deviceMemory|0)||16;" +
                        "var osL=String(c.os||'').toLowerCase();var touch=/android|ios|iphone/.test(osL)?5:0;" +
                        "if(!touch&&(sw<1280||sh<720)){sw=2560;sh=1600;}" +
                        "try{var NP=Navigator.prototype;if(c.userAgent)g(NP,'userAgent',c.userAgent);" +
                        "g(NP,'platform',c.platform||'MacIntel');g(NP,'hardwareConcurrency',cores);g(NP,'deviceMemory',mem);g(NP,'maxTouchPoints',touch);g(NP,'webdriver',false);}catch(e){}" +
                        "try{var SP=Screen.prototype;g(SP,'width',sw);g(SP,'height',sh);g(SP,'availWidth',sw);g(SP,'availHeight',Math.max(sh-40,1));" +
                        "g(window.screen,'width',sw);g(window.screen,'height',sh);}catch(e){}" +
                        "try{function wgp(o){return function(p){p=p|0;if(p===37445||p===7936||p===0x9245)return V;if(p===37446||p===7937||p===0x9246)return R;try{return o.call(this,p)}catch(e){return null}}};" +
                        "if(window.WebGLRenderingContext)WebGLRenderingContext.prototype.getParameter=wgp(WebGLRenderingContext.prototype.getParameter);" +
                        "if(window.WebGL2RenderingContext)WebGL2RenderingContext.prototype.getParameter=wgp(WebGL2RenderingContext.prototype.getParameter);" +
                        "var og=HTMLCanvasElement.prototype.getContext;if(og&&!og.__gs){HTMLCanvasElement.prototype.getContext=function(t,a){var cx=og.call(this,t,a);try{if(cx&&/webgl/i.test(String(t)))cx.getParameter=wgp(cx.getParameter.bind(cx));}catch(e){}return cx;};HTMLCanvasElement.prototype.getContext.__gs=1;}}catch(e){}" +
                        "}catch(e){}})();"
                    )
                    val b64 = android.util.Base64.encodeToString(
                        seed.toByteArray(Charsets.UTF_8),
                        android.util.Base64.NO_WRAP
                    )
                    if (b64.length < 9000) {
                        // Delay so captcha iframes can finish starting; main frame gets spoof
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            try {
                                session.loadUri("javascript:void(eval(atob('$b64')))")
                                Log.d(TAG, "GPU spoof via javascript: delayed")
                            } catch (e: Exception) {
                                Log.d(TAG, "js spoof failed: ${e.message}")
                            }
                        }, 400)
                        injected = true
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "javascript: build failed: ${e.message}")
                }
            }

            latestConfigJson = json
            activeConfigJson = json
            pushConfigToExtension(json)
            try { ConfigServer.updateConfig(json) } catch (_: Exception) {}
            Log.i(
                TAG,
                "injectConfigScript done injected=$injected gpu=${JSONObject(json).optString("webglRenderer").take(48)}"
            )
        } catch (e: Exception) {
            Log.w(TAG, "injectConfigScript failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // PermissionDelegate – auto GRANT / DENY from profile switches
    // ------------------------------------------------------------------

    /**
     * Decides every Gecko permission request without showing system dialogs
     * (unless the Android OS permission itself is missing – then we deny).
     *
     * Mapping:
     *  GEOLOCATION  → geoMode != "Block"  (+ Manual supplies fake coords via JS)
     *  MEDIA_AUDIO  → microphones > 0
     *  MEDIA_VIDEO  → webcams > 0
     *  NOTIFICATION → always DENY (anti-detect default)
     *  others       → DENY
     */
    class ProfilePermissionDelegate(
        private val profile: ProfileEntity,
        private val context: Context
    ) : GeckoSession.PermissionDelegate {

        override fun onContentPermissionRequest(
            session: GeckoSession,
            perm: GeckoSession.PermissionDelegate.ContentPermission
        ): GeckoResult<Int> {
            val result = when (perm.permission) {
                GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION -> {
                    if (profile.geoMode == "Block") {
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                    } else {
                        // Manual / AutoIP – allow at Gecko level; JS supplies coords when Manual
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                    }
                }
                GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION ->
                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE ->
                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                GeckoSession.PermissionDelegate.PERMISSION_XR ->
                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE ->
                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE ->
                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS ->
                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                GeckoSession.PermissionDelegate.PERMISSION_STORAGE_ACCESS ->
                    if (profile.blockThirdPartyCookies)
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                    else
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                else -> GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
            }
            Log.d(TAG, "ContentPermission ${perm.permission} → $result")
            return GeckoResult.fromValue(result)
        }

        override fun onMediaPermissionRequest(
            session: GeckoSession,
            uri: String,
            video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
            audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
            callback: GeckoSession.PermissionDelegate.MediaCallback
        ) {
            val wantVideo = video != null && video.isNotEmpty() && profile.webcams > 0
            val wantAudio = audio != null && audio.isNotEmpty() && profile.microphones > 0

            // Also require the corresponding Android runtime permission
            val hasCam = ContextCompat.checkSelfPermission(
                context, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
            val hasMic = ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            when {
                wantVideo && wantAudio && hasCam && hasMic ->
                    callback.grant(video?.firstOrNull(), audio?.firstOrNull())
                wantVideo && hasCam ->
                    callback.grant(video?.firstOrNull(), null)
                wantAudio && hasMic ->
                    callback.grant(null, audio?.firstOrNull())
                else -> {
                    Log.d(TAG, "MediaPermission DENY (video=$wantVideo audio=$wantAudio)")
                    callback.reject()
                }
            }
        }

        override fun onAndroidPermissionsRequest(
            session: GeckoSession,
            permissions: Array<out String>?,
            callback: GeckoSession.PermissionDelegate.Callback
        ) {
            // We never request new Android permissions at runtime from Gecko;
            // deny so the page gets a clean failure instead of a system dialog.
            Log.d(TAG, "AndroidPermissionsRequest DENY: ${permissions?.joinToString()}")
            callback.reject()
        }
    }

    // ------------------------------------------------------------------
    // PromptDelegate – file upload via SAF
    // ------------------------------------------------------------------

    /**
     * Handles Gecko prompts. File prompts are forwarded to the hosting
     * Activity which launches the system document picker and later calls
     * [completeFilePrompt].
     */
    class ProfilePromptDelegate(
        private val manager: GeckoSessionManager
    ) : GeckoSession.PromptDelegate {

        override fun onFilePrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.FilePrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
            Log.i(TAG, "onFilePrompt type=${prompt.type} mime=${prompt.mimeTypes?.joinToString()}")

            // Store the Gecko callback so BrowserActivity can complete it
            val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()

            // Notify the Activity (if registered)
            val handler = manager.onFilePromptRequest
            if (handler != null) {
                // Wrap so Activity receives a simple completion path
                                // Use the modern confirm/dismiss API
                try {
                    handler(arrayOf(prompt), object : GeckoSession.PromptDelegate.PromptInstanceDelegate {
                        // Placeholder – Activity drives completion via manager.completeFilePrompt
                    })
                } catch (e: Exception) {
                    Log.w(TAG, "File prompt handler error", e)
                }
            }

            // Keep the result pending; BrowserActivity will complete it
            manager.pendingFileResult = result
            manager.pendingFilePrompt = prompt
            return result
        }

        // Dismiss any other prompts silently (alert/confirm/popup) to avoid UI leaks
        override fun onAlertPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.AlertPrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
            return GeckoResult.fromValue(prompt.dismiss())
        }

        override fun onButtonPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.ButtonPrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
            return GeckoResult.fromValue(prompt.dismiss())
        }

        override fun onTextPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.TextPrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
            return GeckoResult.fromValue(prompt.dismiss())
        }

        override fun onPopupPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.PopupPrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
            return GeckoResult.fromValue(prompt.dismiss())
        }
    }

    // Pending file-prompt state (package-visible for BrowserActivity)
    var pendingFileResult: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? = null
    var pendingFilePrompt: GeckoSession.PromptDelegate.FilePrompt? = null

    /**
     * Called by BrowserActivity after the user picks files (or cancels).
     * @param uris content:// URIs from SAF, or empty/null to dismiss
     */
    fun completeFilePrompt(uris: List<Uri>?) {
        val prompt = pendingFilePrompt
        val result = pendingFileResult
        pendingFilePrompt = null
        pendingFileResult = null

        if (prompt == null || result == null) return

        try {
            if (uris.isNullOrEmpty()) {
                result.complete(prompt.dismiss())
            } else {
                // confirm(Uri) / confirm(Uri[]) depending on single vs multiple
                val response = if (uris.size == 1) {
                    prompt.confirm(context, uris[0])
                } else {
                    prompt.confirm(context, uris.toTypedArray())
                }
                result.complete(response)
            }
            Log.i(TAG, "File prompt completed with ${uris?.size ?: 0} uri(s)")
        } catch (e: Exception) {
            Log.e(TAG, "completeFilePrompt failed", e)
            try { result.complete(prompt.dismiss()) } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------
    // Config → extension
    // ------------------------------------------------------------------

    private fun sendFingerprintConfig(session: GeckoSession, profile: ProfileEntity) {
        // Reuse the same logic as URL-hash config so GPU / cores / timezone stay consistent
        val config = buildConfigJson(profile)
        latestConfigJson = config.toString()

        val ext = spoofExtension
        if (ext != null) {
            try {
                val delegate = object : WebExtension.MessageDelegate {
                    override fun onMessage(
                        nativeApp: String,
                        message: Any,
                        sender: WebExtension.MessageSender
                    ): GeckoResult<Any>? {
                        val json = latestConfigJson ?: config.toString()
                        val cfgObj = try {
                            org.json.JSONObject(json)
                        } catch (_: Exception) {
                            config
                        }
                        Log.i(TAG, "Native getConfig → gpu=${cfgObj.optString("webglRenderer")} cores=${cfgObj.opt("hardwareConcurrency")}")
                        // Always return { config: {...} } so background + content-script parse it
                        val envelope = org.json.JSONObject()
                            .put("type", "setConfig")
                            .put("config", cfgObj)
                        return GeckoResult.fromValue(envelope)
                    }
                }
                // Only via reflection – setMessageDelegate is not on all GeckoView APIs
                setMessageDelegateReflect(session.webExtensionController, ext, delegate, "browser")
                // Proactively push config so background storage.local is THIS profile (not previous)
                try {
                    val envelope = org.json.JSONObject()
                        .put("type", "setConfig")
                        .put("config", config)
                    // Some GeckoView builds accept session.sendMessage / extension port
                    val ctrl = session.webExtensionController
                    val sendM = ctrl.javaClass.methods.firstOrNull { m ->
                        m.name.contains("sendMessage", ignoreCase = true) || m.name == "postMessage"
                    }
                    sendM?.let { m ->
                        try {
                            when (m.parameterTypes.size) {
                                2 -> m.invoke(ctrl, ext, envelope)
                                3 -> m.invoke(ctrl, ext, envelope, null)
                                else -> {}
                            }
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.w(TAG, "setMessageDelegate failed: ${e.message}")
            }
        } else {
            Log.w(TAG, "Spoof extension not ready yet")
        }

        // Immediate page inject (does not wait for extension messaging)
        injectConfigScript(session, latestConfigJson ?: config.toString())
        Log.i(TAG, "Fingerprint config prepared for profile ${profile.id} sw=${profile.screenWidth}x${profile.screenHeight} gpu=${config.optString("webglRenderer")}")
    }

    fun closeSession(profileId: Long, wipeData: Boolean = false) {
        activeSessions.remove(profileId)?.close()
        if (wipeData) {
            wipeProfileData(profileId)
        }
    }

    /**
     * Delete on-disk profile folder. Cookie/storage isolation is primarily via
     * contextId; wiping the folder removes any auxiliary files we store.
     */
    fun wipeProfileData(profileId: Long) {
        try {
            val dir = File(context.filesDir, "gecko_profiles/p_$profileId")
            if (dir.exists()) {
                dir.deleteRecursively()
                Log.i(TAG, "Wiped profile data dir for $profileId")
            }
        } catch (e: Exception) {
            Log.w(TAG, "wipeProfileData failed: ${e.message}")
        }
        // Also ask Gecko StorageController to clear data for this context when available
        try {
            val rt = sharedRuntime ?: return
            val sc = rt.storageController
            val ctxId = contextIdFor(profileId)
            // clearDataFromHost / clearData – best-effort across GeckoView versions
            val methods = sc.javaClass.methods
            val clearAll = methods.firstOrNull { it.name.contains("clearData", ignoreCase = true) }
            clearAll?.let { m ->
                try {
                    when (m.parameterTypes.size) {
                        0 -> m.invoke(sc)
                        1 -> m.invoke(sc, Long.MAX_VALUE)
                        else -> {}
                    }
                    Log.i(TAG, "StorageController clear requested for context $ctxId")
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.d(TAG, "StorageController wipe skipped: ${e.message}")
        }
    }

    fun closeAll() {
        activeSessions.values.forEach { it.close() }
        activeSessions.clear()
        // Do NOT shutdown sharedRuntime – GeckoView forbids creating a second one
        // in the same process. Keep the singleton alive for the app lifetime.
    }

    fun getSession(profileId: Long): GeckoSession? = activeSessions[profileId]
}
