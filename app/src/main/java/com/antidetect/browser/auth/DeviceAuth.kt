package com.antidetect.browser.auth

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Device authorization via Firebase Firestore SDK.
 *
 * Flow (like your old project):
 *  1. App opens → device auto-registers in authorized_devices if missing (enabled=false)
 *  2. You see it in Firebase Console and set enabled=true
 *  3. User taps Retry / reopens → enters
 *
 * Firestore rules needed:
 *   match /authorized_devices/{deviceId} {
 *     allow read: if true;
 *     allow create: if request.resource.data.enabled == false;
 *     allow update: if false;  // only Console / Admin SDK
 *   }
 */
object DeviceAuth {

    private const val TAG = "DeviceAuth"
    private const val PREFS = "gestor_device_auth"
    private const val KEY_ID = "device_id"
    private const val KEY_CACHE_OK = "auth_ok_until"
    private const val CACHE_MS = 6 * 60 * 60 * 1000L
    private const val COLLECTION = "authorized_devices"

    fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }

        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (_: Exception) {
            null
        }
        val id = when {
            !androidId.isNullOrBlank() && androidId != "9774d56d682e549c" ->
                "android-$androidId"
            else -> "uuid-${UUID.randomUUID()}"
        }
        prefs.edit().putString(KEY_ID, id).apply()
        return id
    }

    fun clearCache(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_CACHE_OK).apply()
    }

    suspend fun isAuthorized(
        context: Context,
        projectId: String = "",
        apiKey: String = "",
        authUrl: String = ""
    ): AuthResult = withContext(Dispatchers.IO) {
        val id = deviceId(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val until = prefs.getLong(KEY_CACHE_OK, 0L)
        if (until > System.currentTimeMillis()) {
            return@withContext AuthResult(true, id, "OK (caché)")
        }

        try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Firebase init failed", e)
            return@withContext AuthResult(
                false, id,
                "Falta google-services.json válido para com.antidetect.browser"
            )
        }

        try {
            val db = FirebaseFirestore.getInstance()
            val ref = db.collection(COLLECTION).document(id)
            val snap = ref.get().await()

            if (!snap.exists()) {
                // Auto-register as pending (enabled=false) so admin only toggles the flag
                val data = hashMapOf<String, Any>(
                    "enabled" to false,
                    "status" to "pending",
                    "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
                    "sdk" to Build.VERSION.SDK_INT,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "lastSeen" to FieldValue.serverTimestamp()
                )
                try {
                    ref.set(data).await()
                    Log.i(TAG, "Registered pending device $id")
                } catch (ce: Exception) {
                    Log.w(TAG, "Auto-register failed (check rules allow create)", ce)
                    return@withContext AuthResult(
                        false, id,
                        "No se pudo registrar. Reglas: allow create: if request.resource.data.enabled == false;"
                    )
                }
                return@withContext AuthResult(
                    false, id,
                    "Dispositivo registrado como pendiente. En Firestore pon enabled=true"
                )
            }

            // Touch lastSeen (best-effort; ignore if rules block update)
            try {
                ref.update(
                    mapOf(
                        "lastSeen" to FieldValue.serverTimestamp(),
                        "model" to "${Build.MANUFACTURER} ${Build.MODEL}"
                    )
                )
            } catch (_: Exception) { /* ignore */ }

            val enabled = when (val v = snap.get("enabled")) {
                is Boolean -> v
                is String -> v.equals("true", true) || v == "1"
                is Number -> v.toInt() != 0
                null -> false
                else -> false
            }

            if (enabled) {
                prefs.edit()
                    .putLong(KEY_CACHE_OK, System.currentTimeMillis() + CACHE_MS)
                    .apply()
                AuthResult(true, id, "Dispositivo autorizado")
            } else {
                AuthResult(
                    false, id,
                    "Pendiente de autorización. En Firestore cambia enabled a true"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Firestore failed", e)
            val msg = e.message.orEmpty()
            when {
                msg.contains("PERMISSION", true) ->
                    AuthResult(
                        false, id,
                        "Reglas: allow read: if true; allow create: if request.resource.data.enabled == false;"
                    )
                until > 0L ->
                    AuthResult(true, id, "Sin red – autorización previa")
                else ->
                    AuthResult(false, id, "Error Firestore: ${e.message}")
            }
        }
    }

    data class AuthResult(
        val authorized: Boolean,
        val deviceId: String,
        val message: String
    )
}
