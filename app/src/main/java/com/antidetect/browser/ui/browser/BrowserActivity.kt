package com.antidetect.browser.ui.browser

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.antidetect.browser.AntiDetectApp
import com.antidetect.browser.data.ProfileEntity
import com.antidetect.browser.fingerprint.FingerprintRepository
import com.antidetect.browser.gecko.GeckoSessionManager
import com.antidetect.browser.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoView
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

class BrowserActivity : ComponentActivity() {

    private lateinit var sessionManager: GeckoSessionManager
    private var currentProfile: ProfileEntity? = null
    /** Profile with template fully applied (GPU/screen/tz/cores). Used for every navigation. */
    private var effectiveProfile: ProfileEntity? = null
    private var geckoView: GeckoView? = null

    /** SAF launcher for <input type="file"> prompts */
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        uris.forEach { uri ->
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
        }
        sessionManager.completeFilePrompt(uris.ifEmpty { null })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionManager = GeckoSessionManager(this)
        sessionManager.setHostActivity(this)

        sessionManager.onFilePromptRequest = { prompts, _ ->
            val mimeTypes = prompts.firstOrNull()?.mimeTypes
                ?.takeIf { it.isNotEmpty() }
                ?: arrayOf("*/*")
            runOnUiThread {
                filePickerLauncher.launch(mimeTypes)
            }
        }

        val profileId = intent.getLongExtra("profile_id", 0L)
        if (profileId == 0L) {
            finish()
            return
        }

        lifecycleScope.launch {
            currentProfile = AntiDetectApp.instance.repository.getProfile(profileId)
            if (currentProfile == null) {
                finish()
                return@launch
            }

            setContent {
                AntiDetectTheme {
                    var profileState by remember { mutableStateOf(currentProfile!!) }
                    var publicIp by remember { mutableStateOf<String?>(null) }
                    var ipLoading by remember { mutableStateOf(true) }
                    var clearToast by remember { mutableStateOf(false) }

                    fun refreshIp() {
                        ipLoading = true
                        lifecycleScope.launch {
                            publicIp = fetchPublicIp(effectiveProfile ?: profileState)
                            ipLoading = false
                        }
                    }

                    LaunchedEffect(profileState.proxyEnabled, profileState.proxyHost, profileState.proxyPort) {
                        refreshIp()
                    }

                    if (clearToast) {
                        LaunchedEffect(clearToast) {
                            kotlinx.coroutines.delay(1500)
                            clearToast = false
                        }
                    }

                    BrowserScreen(
                        profile = profileState,
                        publicIp = publicIp,
                        ipLoading = ipLoading,
                        clearFeedback = clearToast,
                        onBack = { finish() },
                        onCreateGeckoView = createGeckoView@{ ctx ->
                            geckoView?.let { return@createGeckoView it }

                            val gv = GeckoView(ctx).apply {
                                layoutParams = FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                            }
                            val base = currentProfile!!
                            val profile = try {
                                val repo = FingerprintRepository(this@BrowserActivity)
                                runBlocking {
                                    val catalog = repo.getCatalog()
                                    fun osFamily(s: String): String {
                                        val x = s.lowercase()
                                        return when {
                                            x.contains("ios") || x.contains("iphone") || x.contains("ipad") -> "ios"
                                            x.contains("android") -> "android"
                                            x.contains("mac") -> "mac"
                                            x.contains("win") -> "windows"
                                            x.contains("linux") || x.contains("ubuntu") || x.contains("fedora") -> "linux"
                                            else -> x
                                        }
                                    }
                                    val wantOs = osFamily(base.os)
                                    fun score(it: com.antidetect.browser.fingerprint.FingerprintTemplate): Int {
                                        var s = 0
                                        if (osFamily(it.os) == wantOs) s += 10
                                        if (it.name.equals(base.fingerprintTemplate, true) ||
                                            it.id.equals(base.fingerprintTemplate, true)) s += 50
                                        if (base.name.contains(it.name, true)) s += 30
                                        if (it.name.contains(base.name.take(20), true)) s += 20
                                        // Token overlap (Iris, RTX 4060, Galaxy, etc.)
                                        for (tok in base.name.split("·", " ", "-").map { it.trim() }.filter { it.length > 2 }) {
                                            if (it.name.contains(tok, true) || it.webglRenderer.contains(tok, true)) s += 8
                                        }
                                        return s
                                    }
                                    val tpl = catalog.maxByOrNull { score(it) }?.takeIf { score(it) >= 10 }
                                        ?: catalog.firstOrNull { osFamily(it.os) == wantOs }

                                    if (tpl != null) {
                                        sessionManager.setTemplate(tpl)
                                        // Always materialize full template → profile so GPU/screen/cores
                                        // match CreepJS expectations even if DB row has zeros / stale values.
                                        repo.applyToProfile(base, tpl).copy(
                                            webglVendor = tpl.webglVendor,
                                            webglRenderer = tpl.webglRenderer,
                                            screenWidth = if (tpl.screenWidth > 0) tpl.screenWidth else base.screenWidth,
                                            screenHeight = if (tpl.screenHeight > 0) tpl.screenHeight else base.screenHeight,
                                            hardwareConcurrency = if (tpl.hardwareConcurrency > 0)
                                                tpl.hardwareConcurrency else base.hardwareConcurrency.coerceAtLeast(4),
                                            deviceMemory = if (tpl.deviceMemory > 0)
                                                tpl.deviceMemory else base.deviceMemory.coerceAtLeast(4),
                                            timezone = tpl.timezone.ifBlank { base.timezone.ifBlank { "America/Mexico_City" } },
                                            language = tpl.language.ifBlank { base.language },
                                            platform = tpl.platform.ifBlank { base.platform },
                                            userAgent = tpl.userAgent.ifBlank { base.userAgent },
                                            os = tpl.os.ifBlank { base.os },
                                            fingerprintTemplate = tpl.name,
                                            // Keep user network / session prefs
                                            autoCleanOnExit = base.autoCleanOnExit,
                                            blockWebRTC = base.blockWebRTC,
                                            blockThirdPartyCookies = base.blockThirdPartyCookies,
                                            proxyEnabled = base.proxyEnabled,
                                            proxyType = base.proxyType,
                                            proxyHost = base.proxyHost,
                                            proxyPort = base.proxyPort,
                                            proxyUsername = base.proxyUsername,
                                            proxyPassword = base.proxyPassword,
                                            noiseSeed = base.noiseSeed
                                        )
                                    } else base
                                }
                            } catch (_: Exception) {
                                base
                            }
                            effectiveProfile = profile
                            currentProfile = profile
                            profileState = profile

                            // Install extension with GPU/screen baked in BEFORE first navigation.
                            // runBlocking is already used above for catalog; same pattern here.
                            try {
                                runBlocking(Dispatchers.IO) {
                                    sessionManager.installExtensionBlocking(profile)
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("BrowserActivity", "Extension install: ${e.message}")
                            }

                            val session = sessionManager.createSession(profile)
                            gv.setSession(session)
                            geckoView = gv
                            session.loadUri(sessionManager.urlWithConfig("https://www.google.com", profile))
                            gv
                        },
                        onNavigate = { url ->
                            val session = sessionManager.getSession(profileId) ?: return@BrowserScreen
                            val target = if (url.isBlank()) {
                                "https://www.google.com"
                            } else {
                                normalizeUrl(url)
                            }
                            val p = effectiveProfile ?: currentProfile
                            session.loadUri(sessionManager.urlWithConfig(target, p))
                        },
                        onReload = {
                            sessionManager.getSession(profileId)?.reload()
                        },
                        onToggleProxy = { enabled ->
                            val base = effectiveProfile ?: currentProfile ?: return@BrowserScreen
                            if (base.proxyType == "None" || base.proxyHost.isBlank()) return@BrowserScreen
                            val updated = base.copy(proxyEnabled = enabled)
                            effectiveProfile = updated
                            currentProfile = updated
                            profileState = updated
                            sessionManager.applyProxyPrefs(updated)
                            lifecycleScope.launch {
                                AntiDetectApp.instance.repository.save(updated)
                            }
                            refreshIp()
                        },
                        onToggleAutoClean = { clean ->
                            val base = effectiveProfile ?: currentProfile ?: return@BrowserScreen
                            val updated = base.copy(autoCleanOnExit = clean)
                            effectiveProfile = updated
                            currentProfile = updated
                            profileState = updated
                            lifecycleScope.launch {
                                AntiDetectApp.instance.repository.save(updated)
                            }
                        },
                        onClearBrowsingData = {
                            // Clear only THIS profile's data – other profiles stay isolated
                            sessionManager.wipeProfileData(profileId)
                            clearToast = true
                            sessionManager.getSession(profileId)?.loadUri("about:blank")
                        },
                        onRefreshIp = { refreshIp() }
                    )
                }
            }
        }
    }

    private fun normalizeUrl(input: String): String {
        val trimmed = input.trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.contains(".") && !trimmed.contains(" ") -> "https://$trimmed"
            else -> "https://duckduckgo.com/?q=${java.net.URLEncoder.encode(trimmed, "UTF-8")}"
        }
    }

    override fun onDestroy() {
        sessionManager.setHostActivity(null)
        currentProfile?.let { profile ->
            // Always close the Gecko session; wipe only if auto-clean is on
            sessionManager.closeSession(profile.id, wipeData = profile.autoCleanOnExit)
        }
        super.onDestroy()
    }

    companion object {
        /**
         * Public IP as seen through the profile proxy when enabled.
         * Isolated per call – does not share cookies with other profiles.
         */
        suspend fun fetchPublicIp(profile: ProfileEntity? = null): String? = withContext(Dispatchers.IO) {
            val endpoints = listOf(
                "https://api.ipify.org",
                "https://ifconfig.me/ip",
                "https://icanhazip.com"
            )
            val javaProxy: Proxy? = try {
                if (profile != null &&
                    profile.proxyEnabled &&
                    profile.proxyType != "None" &&
                    profile.proxyHost.isNotBlank() &&
                    profile.proxyPort > 0
                ) {
                    val type = when (profile.proxyType.uppercase()) {
                        "SOCKS", "SOCKS5", "SOCKS4" -> Proxy.Type.SOCKS
                        else -> Proxy.Type.HTTP
                    }
                    Proxy(type, InetSocketAddress(profile.proxyHost, profile.proxyPort))
                } else null
            } catch (_: Exception) {
                null
            }

            for (ep in endpoints) {
                try {
                    val conn = if (javaProxy != null) {
                        URL(ep).openConnection(javaProxy)
                    } else {
                        URL(ep).openConnection()
                    } as HttpURLConnection
                    conn.connectTimeout = 6000
                    conn.readTimeout = 6000
                    conn.requestMethod = "GET"
                    conn.instanceFollowRedirects = true
                    // Basic auth for HTTP proxy if configured
                    if (javaProxy != null &&
                        profile != null &&
                        profile.proxyUsername.isNotBlank()
                    ) {
                        val auth = android.util.Base64.encodeToString(
                            "${profile.proxyUsername}:${profile.proxyPassword}".toByteArray(),
                            android.util.Base64.NO_WRAP
                        )
                        conn.setRequestProperty("Proxy-Authorization", "Basic $auth")
                    }
                    conn.inputStream.bufferedReader().use { reader ->
                        val ip = reader.readText().trim()
                        if (ip.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) return@withContext ip
                        if (ip.contains(":") && ip.length < 45) return@withContext ip
                    }
                } catch (_: Exception) {
                }
            }
            null
        }

        /** Short GPU label for the status bar */
        fun shortGpu(profile: ProfileEntity): String {
            val r = profile.webglRenderer.ifBlank { profile.webglVendor }
            if (r.isBlank()) return "GPU —"
            val patterns = listOf(
                Regex("""RTX\s*\d{3,4}\s*\w*""", RegexOption.IGNORE_CASE),
                Regex("""GTX\s*\d{3,4}\s*\w*""", RegexOption.IGNORE_CASE),
                Regex("""RX\s*\d{3,4}\s*\w*""", RegexOption.IGNORE_CASE),
                Regex("""UHD\s*\d{3,4}""", RegexOption.IGNORE_CASE),
                Regex("""Iris\(TM\)\s*Plus[^\],]*""", RegexOption.IGNORE_CASE),
                Regex("""Iris[^\],]*""", RegexOption.IGNORE_CASE),
                Regex("""Apple\s*M[1-4]\s*\w*""", RegexOption.IGNORE_CASE),
                Regex("""Adreno\s*\(TM\)\s*\d+""", RegexOption.IGNORE_CASE),
                Regex("""Mali[^\],]*""", RegexOption.IGNORE_CASE),
                Regex("""Intel\(R\)[^\],]*""", RegexOption.IGNORE_CASE)
            )
            for (p in patterns) {
                val m = p.find(r)
                if (m != null) return m.value.replace(Regex("""\s+"""), " ").trim()
            }
            return r.take(28).trim()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    profile: ProfileEntity,
    publicIp: String?,
    ipLoading: Boolean,
    clearFeedback: Boolean = false,
    onBack: () -> Unit,
    onCreateGeckoView: (android.content.Context) -> GeckoView,
    onNavigate: (String) -> Unit,
    onReload: () -> Unit,
    onToggleProxy: (Boolean) -> Unit,
    onToggleAutoClean: (Boolean) -> Unit,
    onClearBrowsingData: () -> Unit = {},
    onRefreshIp: () -> Unit
) {
    var urlText by remember { mutableStateOf("https://www.google.com") }
    val hasProxyConfigured =
        profile.proxyType != "None" && profile.proxyHost.isNotBlank() && profile.proxyPort > 0
    val gpuLabel = remember(profile.webglRenderer, profile.webglVendor) {
        BrowserActivity.shortGpu(profile)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
    ) {
        // ── Address bar ──────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }

            BasicTextField(
                value = urlText,
                onValueChange = { urlText = it },
                singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                cursorBrush = SolidColor(AccentPurple),
                modifier = Modifier
                    .weight(1f)
                    .background(SurfaceVariant, RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                decorationBox = { inner ->
                    if (urlText.isEmpty()) {
                        Text("Search or enter address", color = TextSecondary, fontSize = 14.sp)
                    }
                    inner()
                }
            )

            IconButton(onClick = { onNavigate(urlText) }) {
                Text("→", color = AccentPurple, fontSize = 20.sp)
            }
            IconButton(onClick = onReload) {
                Icon(Icons.Default.Refresh, contentDescription = "Reload", tint = TextSecondary)
            }
        }

        // ── Status bar: IP · GPU · Proxy · Session · Clear ───────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Public IP (through proxy when ON)
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = SurfaceVariant,
                onClick = onRefreshIp
            ) {
                Text(
                    when {
                        ipLoading -> "IP: …"
                        publicIp != null -> "IP: $publicIp"
                        else -> "IP: —"
                    },
                    color = TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            // GPU of selected profile
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = SurfaceVariant
            ) {
                Text(
                    "GPU: $gpuLabel",
                    color = TextPrimary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            // Proxy toggle (only if configured in profile)
            if (hasProxyConfigured) {
                FilterChip(
                    selected = profile.proxyEnabled,
                    onClick = { onToggleProxy(!profile.proxyEnabled) },
                    label = {
                        Text(
                            if (profile.proxyEnabled)
                                "Proxy ON"
                            else
                                "Proxy OFF",
                            fontSize = 11.sp
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AccentPurple,
                        selectedLabelColor = TextPrimary
                    )
                )
            } else {
                Text(
                    "Sin proxy",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }

            // Session keep / clean on exit
            FilterChip(
                selected = !profile.autoCleanOnExit,
                onClick = { onToggleAutoClean(!profile.autoCleanOnExit) },
                label = {
                    Text(
                        if (profile.autoCleanOnExit) "Borrar al salir" else "Guardar sesión",
                        fontSize = 11.sp
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AccentPurple,
                    selectedLabelColor = TextPrimary
                )
            )

            // Manual clear NOW (only this profile)
            FilterChip(
                selected = false,
                onClick = onClearBrowsingData,
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (clearFeedback) "Limpiado" else "Limpiar",
                            fontSize = 11.sp
                        )
                    }
                }
            )
        }

        // Profile line
        Text(
            text = "Perfil: ${profile.name} · ${profile.os}",
            color = TextSecondary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark)
                .padding(horizontal = 16.dp, vertical = 2.dp)
        )

        AndroidView(
            factory = { onCreateGeckoView(it) },
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
        )
    }
}
