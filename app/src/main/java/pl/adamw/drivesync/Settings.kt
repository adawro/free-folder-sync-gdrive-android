package pl.adamw.drivesync

import android.content.Context
import android.net.Uri

/** Ustawienia aplikacji (SharedPreferences). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Folder w telefonie wybrany przez systemowe okno (SAF, OPEN_DOCUMENT_TREE). */
    var treeUri: Uri?
        get() = prefs.getString("tree_uri", null)?.let(Uri::parse)
        set(v) = prefs.edit().putString("tree_uri", v?.toString()).apply()

    /** Ścieżka folderu docelowego na Drive, np. "Telefon/signal backup" (liczona od "Mój dysk"). */
    var drivePath: String
        get() = prefs.getString("drive_path", "")!!
        set(v) = prefs.edit().putString("drive_path", v.trim('/', ' ')).apply()

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

    var lastSyncAt: Long
        get() = prefs.getLong("last_sync_at", 0)
        set(v) = prefs.edit().putLong("last_sync_at", v).apply()

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
}
