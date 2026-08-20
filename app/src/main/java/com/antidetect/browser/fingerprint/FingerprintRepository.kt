package com.antidetect.browser.fingerprint

import android.content.Context
import android.net.Uri
import android.util.Log
import com.antidetect.browser.data.ProfileEntity
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Loads, imports, exports and remotely syncs fingerprint templates.
 *
 * Sources (priority order when building the catalog):
 *  1. Built-in assets under assets/fingerprints
 *  2. User-imported files (persisted under filesDir/fingerprints)
 *  3. Optional remote URL (GitHub raw / REST)
 */
class FingerprintRepository(private val context: Context) {

    companion object {
        private const val TAG = "FingerprintRepo"
        private const val ASSETS_DIR = "fingerprints"
        private const val LOCAL_DIR = "fingerprints"
    }

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    suspend fun getCatalog(): List<FingerprintTemplate> = withContext(Dispatchers.IO) {
        val fromAssets = loadFromAssets()
        val fromLocal = loadFromLocalStorage()
        val map = LinkedHashMap<String, FingerprintTemplate>()
        fromAssets.forEach { map[it.id.ifBlank { it.name }] = it }
        fromLocal.forEach { map[it.id.ifBlank { it.name }] = it }
        map.values.toList().sortedBy { it.name }
    }

    fun getTemplateNames(catalog: List<FingerprintTemplate>): List<String> =
        catalog.map { it.name }

    /**
     * Synchronous resolve for use on the UI / Gecko threads.
     * Matches by fingerprintTemplate name, id, profile name, then first of same OS.
     */
    fun resolveTemplateSync(profile: ProfileEntity): FingerprintTemplate? {
        val all = try {
            val map = LinkedHashMap<String, FingerprintTemplate>()
            loadFromAssets().forEach { map[it.id.ifBlank { it.name }] = it }
            loadFromLocalStorage().forEach { map[it.id.ifBlank { it.name }] = it }
            map.values.toList()
        } catch (e: Exception) {
            Log.e(TAG, "resolveTemplateSync load failed", e)
            emptyList()
        }
        if (all.isEmpty()) return null

        fun osFamily(s: String): String {
            val x = s.lowercase()
            return when {
                x.contains("ios") || x.contains("iphone") || x.contains("ipad") -> "ios"
                x.contains("android") -> "android"
                x.contains("mac") -> "mac"
                x.contains("win") -> "windows"
                x.contains("linux") || x.contains("ubuntu") || x.contains("fedora") ||
                    x.contains("arch") || x.contains("debian") || x.contains("pop") -> "linux"
                else -> x
            }
        }
        val want = osFamily(profile.os)
        val key = profile.fingerprintTemplate.trim()
        val tokens = (profile.name + " " + key).split("·", " ", "-")
            .map { it.trim() }.filter { it.length > 2 }

        fun score(it: FingerprintTemplate): Int {
            var s = 0
            if (osFamily(it.os) == want) s += 10 else return -1
            if (it.name.equals(key, true) || it.id.equals(key, true)) s += 50
            if (key.isNotBlank() && it.name.equals(key, true)) s += 40
            if (profile.name.contains(it.name, true)) s += 30
            if (it.name.contains(profile.name.take(24), true)) s += 15
            for (tok in tokens) {
                if (it.name.contains(tok, true)) s += 8
                if (it.webglRenderer.contains(tok, true)) s += 6
            }
            return s
        }
        return all.map { it to score(it) }
            .filter { it.second >= 10 }
            .maxByOrNull { it.second }
            ?.first
            ?: all.firstOrNull { osFamily(it.os) == want }
    }

    private fun loadFromAssets(): List<FingerprintTemplate> {
        val result = mutableListOf<FingerprintTemplate>()
        try {
            val names = context.assets.list(ASSETS_DIR) ?: return emptyList()
            for (name in names) {
                if (!name.endsWith(".json", ignoreCase = true)) continue
                try {
                    context.assets.open("$ASSETS_DIR/$name").use { stream ->
                        val json = stream.bufferedReader().readText()
                        val tpl = gson.fromJson(json, FingerprintTemplate::class.java)
                        if (tpl != null) result.add(tpl)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse asset $name", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cannot list assets/$ASSETS_DIR", e)
        }
        return result
    }

    private fun localDir(): java.io.File {
        val dir = java.io.File(context.filesDir, LOCAL_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun loadFromLocalStorage(): List<FingerprintTemplate> {
        val result = mutableListOf<FingerprintTemplate>()
        val dir = localDir()
        dir.listFiles()?.filter { it.extension.equals("json", true) }?.forEach { file ->
            try {
                val tpl = gson.fromJson(file.readText(), FingerprintTemplate::class.java)
                if (tpl != null) result.add(tpl)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse local ${file.name}", e)
            }
        }
        return result
    }

    suspend fun importFromUri(uri: Uri): FingerprintTemplate? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val json = BufferedReader(InputStreamReader(stream)).readText()
                val tpl = gson.fromJson(json, FingerprintTemplate::class.java)
                    ?: return@withContext null
                val safeName = (tpl.id.ifBlank { tpl.name }.ifBlank { "imported" })
                    .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val out = java.io.File(localDir(), "$safeName.json")
                out.writeText(gson.toJson(tpl))
                Log.i(TAG, "Imported template: ${tpl.name}")
                tpl
            }
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            null
        }
    }

    suspend fun importBatch(uris: List<Uri>): List<FingerprintTemplate> {
        return uris.mapNotNull { importFromUri(it) }
    }

    suspend fun exportToUri(template: FingerprintTemplate, dest: Uri): Boolean =
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(dest)?.use { out ->
                    out.write(gson.toJson(template).toByteArray(Charsets.UTF_8))
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Export failed", e)
                false
            }
        }

    suspend fun exportCatalogToUri(catalog: List<FingerprintTemplate>, dest: Uri): Boolean =
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(dest)?.use { out ->
                    out.write(gson.toJson(catalog).toByteArray(Charsets.UTF_8))
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Catalog export failed", e)
                false
            }
        }

    suspend fun syncRemote(url: String): List<FingerprintTemplate> = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext emptyList()
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "Remote sync HTTP ${conn.responseCode}")
                return@withContext emptyList()
            }
            val body = conn.inputStream.bufferedReader().readText()
            val listType = object : TypeToken<List<FingerprintTemplate>>() {}.type
            val parsed: List<FingerprintTemplate> = try {
                gson.fromJson(body, listType) ?: emptyList()
            } catch (e: Exception) {
                listOfNotNull(gson.fromJson(body, FingerprintTemplate::class.java))
            }
            parsed.forEach { tpl ->
                val safeName = (tpl.id.ifBlank { tpl.name }.ifBlank { "remote" })
                    .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                java.io.File(localDir(), "$safeName.json").writeText(gson.toJson(tpl))
            }
            Log.i(TAG, "Remote sync: ${parsed.size} templates")
            parsed
        } catch (e: Exception) {
            Log.e(TAG, "Remote sync failed", e)
            emptyList()
        }
    }

    fun applyToProfile(base: ProfileEntity, tpl: FingerprintTemplate): ProfileEntity {
        return base.copy(
            name = if (base.name.isBlank()) tpl.name else base.name,
            os = tpl.os,
            fingerprintTemplate = tpl.name,
            userAgent = tpl.userAgent,
            platform = tpl.platform,
            language = tpl.language,
            screenWidth = tpl.screenWidth,
            screenHeight = tpl.screenHeight,
            webglVendor = tpl.webglVendor,
            webglRenderer = tpl.webglRenderer,
            hardwareConcurrency = tpl.hardwareConcurrency,
            deviceMemory = tpl.deviceMemory,
            microphones = tpl.microphones,
            speakers = tpl.speakers,
            webcams = tpl.webcams,
            timezone = tpl.timezone
        )
    }

    fun buildExtensionExtras(tpl: FingerprintTemplate?): Map<String, Any?> {
        if (tpl == null) return emptyMap()
        return mapOf(
            "hardwareConcurrency" to tpl.hardwareConcurrency,
            "deviceMemory" to tpl.deviceMemory,
            "devicePixelRatio" to tpl.devicePixelRatio,
            "languages" to tpl.languages,
            "clientHints" to tpl.clientHints,
            "canvasSeed" to tpl.canvasSeed,
            "audioSeed" to tpl.audioSeed,
            "fonts" to tpl.fonts,
            "voices" to tpl.voices.map {
                mapOf(
                    "name" to it.name,
                    "lang" to it.lang,
                    "localService" to it.localService,
                    "default" to it.default
                )
            },
            "connectionType" to tpl.connectionType,
            "effectiveType" to tpl.effectiveType,
            "downlink" to tpl.downlink,
            "rtt" to tpl.rtt,
            "batteryLevel" to tpl.batteryLevel,
            "batteryCharging" to tpl.batteryCharging
        )
    }
}
