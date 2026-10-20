package pl.adamw.drivesync

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Jeden kopiowany folder: folder w telefonie wybrany przez systemowe okno (SAF, OPEN_DOCUMENT_TREE)
 * i ścieżka folderu docelowego na Drive, np. "Telefon/signal backup" (liczona od "Mój dysk").
 * id wiąże pliki w bazie z folderem (ścieżki plików są względne, więc w dwóch folderach mogą się powtarzać).
 * watch: nowe pliki wysyłane od razu, nie tylko o godzinie z ustawień (SyncWorker.armWatch).
 */
data class FolderPair(val id: Long, val treeUri: Uri, val drivePath: String, val watch: Boolean = false)

/** Ustawienia aplikacji (SharedPreferences). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Kopiowane foldery. Do wersji 0.6 był jeden (tree_uri + drive_path) - przenoszony jako folder o id 1. */
    var pairs: List<FolderPair>
        get() {
            val json = prefs.getString("pairs", null)
            if (json == null) {
                val tree = prefs.getString("tree_uri", null) ?: return emptyList()
                return listOf(FolderPair(1, Uri.parse(tree), prefs.getString("drive_path", "")!!)).also { pairs = it }
            }
            val arr = JSONArray(json)
            return List(arr.length()) {
                val o = arr.getJSONObject(it)
                FolderPair(o.getLong("id"), Uri.parse(o.getString("tree")), o.getString("drive"), o.optBoolean("watch"))
            }
        }
        set(v) {
            val arr = JSONArray()
            v.forEach {
                arr.put(JSONObject().put("id", it.id).put("tree", it.treeUri.toString()).put("drive", it.drivePath)
                    .put("watch", it.watch))
            }
            prefs.edit().putString("pairs", arr.toString()).remove("tree_uri").remove("drive_path").apply()
        }

    fun addPair(tree: Uri, drivePath: String): FolderPair {
        val current = pairs
        val pair = FolderPair((current.maxOfOrNull { it.id } ?: 0) + 1, tree, cleanPath(drivePath))
        pairs = current + pair
        return pair
    }

    fun setDrivePath(id: Long, drivePath: String) {
        pairs = pairs.map { if (it.id == id) it.copy(drivePath = cleanPath(drivePath)) else it }
    }

    fun setWatch(id: Long, watch: Boolean) {
        pairs = pairs.map { if (it.id == id) it.copy(watch = watch) else it }
    }

    fun removePair(id: Long) {
        pairs = pairs.filter { it.id != id }
    }

    /** Dozwolone sieci; co najmniej jedna jest zawsze włączona. Domyślnie z wersji 0.1/0.2 ("tylko Wi-Fi"). */
    var allowWifi: Boolean
        get() = prefs.getBoolean("allow_wifi", true)
        set(v) = prefs.edit().putBoolean("allow_wifi", v).apply()

    var allowMobile: Boolean
        get() = prefs.getBoolean("allow_mobile", !prefs.getBoolean("wifi_only", true))
        set(v) = prefs.edit().putBoolean("allow_mobile", v).apply()

    var chargingOnly: Boolean
        get() = prefs.getBoolean("charging_only", false)
        set(v) = prefs.edit().putBoolean("charging_only", v).apply()

    /** Godzina codziennej wysyłki (minuty od północy), domyślnie 3:00. */
    var syncTimeMinutes: Int
        get() = prefs.getInt("sync_time", 3 * 60)
        set(v) = prefs.edit().putInt("sync_time", v).apply()

    /** Czas, na który ustawiony jest budzik codziennej wysyłki (do nadrobienia po wyłączeniu telefonu). */
    var scheduledAt: Long
        get() = prefs.getLong("scheduled_at", 0)
        set(v) = prefs.edit().putLong("scheduled_at", v).apply()

    /** Ostatnia synchronizacja bez błędów (wszystkie gotowe pliki są na Drive). */
    var lastSyncAt: Long
        get() = prefs.getLong("last_sync_at", 0)
        set(v) = prefs.edit().putLong("last_sync_at", v).apply()

    /** Ostatnia próba (udana lub nie) i jej błąd - null, gdy się udała. */
    var lastAttemptAt: Long
        get() = prefs.getLong("last_attempt_at", 0)
        set(v) = prefs.edit().putLong("last_attempt_at", v).apply()

    var lastError: String?
        get() = prefs.getString("last_error", null)
        set(v) = prefs.edit().putString("last_error", v).apply()

    /** Ile plików z folderu w telefonie nie ma jeszcze na Drive (stan po ostatniej próbie, -1 = nieznany). */
    var pendingCount: Int
        get() = prefs.getInt("pending_count", -1)
        set(v) = prefs.edit().putInt("pending_count", v).apply()

    /**
     * Logowanie przez Google Play Services i klienta OAuth autora (tylko w buildzie z builtinAuth=true
     * w local.properties). W przeciwnym razie własny klient OAuth użytkownika (CustomOAuth).
     */
    var useBuiltinAuth: Boolean
        get() = BuildConfig.BUILTIN_AUTH && prefs.getBoolean("builtin_auth", true)
        set(v) = prefs.edit().putBoolean("builtin_auth", v).apply()

    /** Client ID klienta OAuth typu "Desktop app" z projektu Google Cloud użytkownika. */
    var customClientId: String
        get() = prefs.getString("custom_client_id", "")!!
        set(v) = prefs.edit().putString("custom_client_id", v).apply()

    /** Ustawiane, gdy synchronizacja w tle wymaga ponownego zalogowania do Google. */
    var needsSignIn: Boolean
        get() = prefs.getBoolean("needs_sign_in", true)
        set(v) = prefs.edit().putBoolean("needs_sign_in", v).apply()

    companion object {
        fun cleanPath(path: String) = path.trim('/', ' ')
    }
}
