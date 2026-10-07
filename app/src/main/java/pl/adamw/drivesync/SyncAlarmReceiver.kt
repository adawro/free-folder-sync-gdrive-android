package pl.adamw.drivesync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Codzienna wysyłka o godzinie z ustawień. Budzik (AlarmManager) zamiast opóźnionego zadania WorkManagera,
 * bo to drugie Android w nocy (Doze, App Standby) odkłada nawet o kilka godzin - do najbliższego okna serwisowego
 * albo otwarcia aplikacji.
 */
class SyncAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_RUN -> {
                SyncWorker.runScheduled(context)
                SyncWorker.schedule(context)  // następny dzień
            }
            else -> {
                // Restart telefonu, aktualizacja aplikacji, zmiana czasu, zgoda na dokładne budziki:
                // budziki AlarmManagera nie przetrwają restartu - trzeba ustawić od nowa.
                // Telefon był wyłączony o zaplanowanej godzinie -> wysyłka od razu.
                val s = Settings(context)
                if (s.scheduledAt in 1..System.currentTimeMillis() && s.lastSyncAt < s.scheduledAt) {
                    SyncWorker.runScheduled(context)
                }
                SyncWorker.schedule(context)
            }
        }
    }

    companion object {
        const val ACTION_RUN = "pl.adamw.drivesync.SCHEDULED_SYNC"
    }
}
