package pl.adamw.drivesync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Opcjonalny status backupu innej maszyny (np. rclone na serwerze domowym), pobierany
 * z adresu podanego w ustawieniach - zwykle przez VPN. Oczekiwany JSON:
 *
 *   {"state": "ok" | "error" | "stale" | "none", "last": <epoch s>, "age_min": 12,
 *    "dirs_ok": 9, "dirs_err": 0, "failed": ["katalog", ...],
 *    "host": {"up_h": 26, "net": true}}      <- opcjonalnie: uptime serwera i czy ma internet
 *
 * Werdykt (state) liczy serwer; aplikacja go tylko pokazuje.
 */
data class ServerStatus(
    val state: String,
    val lastAt: Long,       // ms, 0 = nieznany
    val dirsOk: Int,
    val dirsErr: Int,
    val failed: List<String>,
    val upH: Int,           // -1 = serwer nie podaje
    val net: Boolean?,      // null = serwer nie podaje
) {
    companion object {
        fun parse(json: String): ServerStatus {
            val o = JSONObject(json)
            val failed = o.optJSONArray("failed")
            val host = o.optJSONObject("host")
            return ServerStatus(
                state = o.optString("state", "none"),
                lastAt = o.optLong("last", 0) * 1000,
                dirsOk = o.optInt("dirs_ok"),
                dirsErr = o.optInt("dirs_err"),
                failed = List(failed?.length() ?: 0) { failed!!.getString(it) },
                upH = host?.optInt("up_h", -1) ?: -1,
                net = host?.takeIf { it.has("net") }?.optBoolean("net"),
            )
        }

        /** Krótki timeout: bez VPN adres jest nieosiągalny i ma to wyjść szybko jako "brak danych". */
        suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            try {
                if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
                conn.inputStream.bufferedReader().use { it.readText() }.also { parse(it) }
            } finally {
                conn.disconnect()
            }
        }
    }
}
