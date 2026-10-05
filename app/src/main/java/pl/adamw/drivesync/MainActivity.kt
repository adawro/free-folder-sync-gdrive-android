package pl.adamw.drivesync

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }
}

@Composable
private fun Screen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as ComponentActivity
    val settings = remember { Settings(context) }
    val db = remember { Db.get(context) }
    val scope = rememberCoroutineScope()

    // Licznik wymuszający odświeżenie widoku po zmianach ustawień / logu
    var refresh by remember { mutableIntStateOf(0) }
    var signInError by remember { mutableStateOf<String?>(null) }
    var drivePath by remember { mutableStateOf(settings.drivePath) }
    var clientId by remember { mutableStateOf(settings.customClientId) }
    var clientSecret by remember { mutableStateOf(CustomOAuth.clientSecret(context).orEmpty()) }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        runCatching { Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(result.data) }
            .onSuccess { settings.needsSignIn = false; signInError = null; SyncWorker.schedule(context) }
            .onFailure { signInError = "Nie udało się połączyć: ${it.message}" }
        refresh++
    }

    fun signIn() = scope.launch {
        runCatching { Auth.authorize(context) }
            .onSuccess { r ->
                if (r.hasResolution()) {
                    consent.launch(IntentSenderRequest.Builder(r.pendingIntent!!.intentSender).build())
                } else {
                    settings.needsSignIn = false
                    signInError = null
                    SyncWorker.schedule(context)
                    refresh++
                }
            }
            .onFailure { signInError = "Nie udało się połączyć: ${it.message}" }
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        settings.treeUri = uri
        if (settings.drivePath.isEmpty()) {
            settings.drivePath = "Telefon/" + (LocalFiles.treeName(context, uri) ?: "folder")
            drivePath = settings.drivePath
        }
        SyncWorker.schedule(context)
        refresh++
    }

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }

    // Stan zadań WorkManagera (ręczne + okresowe)
    val now by WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(SyncWorker.NOW).collectAsState(emptyList())
    val periodic by WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(SyncWorker.NIGHTLY).collectAsState(emptyList())
    val running = (now + periodic).firstOrNull { it.state == WorkInfo.State.RUNNING }

    // Log i liczniki z bazy (co 2 s, bo zapisuje je worker)
    var log by remember { mutableStateOf(emptyList<LogEntry>()) }
    var uploadedCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) {
        while (true) {
            withContext(Dispatchers.IO) {
                log = db.recentLog(40)
                uploadedCount = db.uploadedCount()
            }
            delay(2000)
        }
    }

    val treeName = remember(refresh) { settings.treeUri?.let { LocalFiles.treeName(context, it) } }
    val fmt = remember { SimpleDateFormat("dd.MM HH:mm:ss", Locale("pl")) }

    Column(
        Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Free Folder Sync", style = MaterialTheme.typography.headlineMedium)
        Text("Kopiowanie folderu z telefonu na Google Drive (w jedną stronę, nic nie jest usuwane).",
            style = MaterialTheme.typography.bodySmall)

        Section("1. Konto Google") {
            val connected = !settings.needsSignIn.also { refresh }
            Text(if (connected) "Połączono z Google Drive" else "Nie połączono")
            signInError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            if (BuildConfig.BUILTIN_AUTH) {
                Toggle("Logowanie wbudowane (konto autora)", settings.useBuiltinAuth.also { refresh }) {
                    settings.useBuiltinAuth = it
                    settings.needsSignIn = true
                    signInError = null
                    refresh++
                }
            }
            if (settings.useBuiltinAuth) {
                Button(onClick = { signIn() }) { Text(if (connected) "Połącz ponownie" else "Połącz z Google Drive") }
            } else {
                Text(
                    "Własny klient OAuth z Twojego projektu Google Cloud (typ \"Desktop app\", włączone Google Drive API). " +
                        "Instrukcja w README projektu.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = clientId, onValueChange = { clientId = it },
                    label = { Text("Client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = clientSecret, onValueChange = { clientSecret = it },
                    label = { Text("Client secret") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            CustomOAuth.saveClient(context, clientId, clientSecret)
                            signInError = null
                            CustomOAuth.signIn(context) { error ->
                                signInError = error
                                if (error == null) SyncWorker.schedule(context)
                                refresh++
                            }
                        },
                        enabled = clientId.isNotBlank() && clientSecret.isNotBlank(),
                    ) { Text(if (connected) "Zaloguj ponownie" else "Zaloguj przez przeglądarkę") }
                    if (connected) {
                        OutlinedButton(onClick = { CustomOAuth.signOut(context); refresh++ }) { Text("Wyloguj") }
                    }
                }
            }
        }

        Section("2. Folder w telefonie") {
            Text(treeName ?: "Nie wybrano")
            OutlinedButton(onClick = { pickFolder.launch(null) }) { Text("Wybierz folder") }
        }

        Section("3. Folder na Google Drive") {
            OutlinedTextField(
                value = drivePath,
                onValueChange = { drivePath = it },
                label = { Text("Ścieżka od \"Mój dysk\"") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (drivePath.trim('/', ' ') != settings.drivePath) {
                Button(onClick = {
                    settings.drivePath = drivePath
                    drivePath = settings.drivePath
                    SyncWorker.schedule(context)
                    refresh++
                }) { Text("Zapisz") }
            }
        }

        Section("4. Kiedy wysyłać") {
            Text("Wysyłaj przez:", style = MaterialTheme.typography.bodyMedium)
            Toggle("Wi-Fi", settings.allowWifi.also { refresh }) {
                settings.allowWifi = it
                if (!it) settings.allowMobile = true  // co najmniej jedna sieć
                SyncWorker.schedule(context); refresh++
            }
            Toggle("Dane komórkowe", settings.allowMobile.also { refresh }) {
                settings.allowMobile = it
                if (!it) settings.allowWifi = true
                SyncWorker.schedule(context); refresh++
            }
            Toggle("Tylko podczas ładowania", settings.chargingOnly.also { refresh }) {
                settings.chargingOnly = it; SyncWorker.schedule(context); refresh++
            }
            val time = settings.syncTimeMinutes.also { refresh }
            val hhmm = "%d:%02d".format(time / 60, time % 60)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Codziennie o", Modifier.weight(1f))
                OutlinedButton(onClick = {
                    TimePickerDialog(context, { _, h, m ->
                        settings.syncTimeMinutes = h * 60 + m
                        SyncWorker.schedule(context)
                        refresh++
                    }, time / 60, time % 60, true).show()
                }) { Text(hhmm) }
            }
            Text(
                "Następna wysyłka: ${fmt.format(SyncWorker.nextRun(settings).time)}. " +
                    "Jeśli o $hhmm warunki nie są spełnione, wysyłka nastąpi, gdy tylko będą.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Section("Stan") {
            Text("Wysłanych plików: $uploadedCount")
            val last = settings.lastSyncAt.also { refresh }
            Text("Ostatnia synchronizacja: " + if (last > 0) fmt.format(Date(last)) else "jeszcze nie było")
            if (running != null) {
                val file = running.progress.getString("file")
                val pct = running.progress.getInt("pct", 0)
                Text(if (file != null) "Wysyłanie: $file ($pct%)" else "Sprawdzanie folderu…")
                LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
            }
            Button(
                onClick = { SyncWorker.syncNow(context); refresh++ },
                enabled = running == null && settings.treeUri != null && !settings.needsSignIn,
            ) { Text("Synchronizuj teraz") }
        }

        Section("Dziennik") {
            if (log.isEmpty()) Text("Pusto", style = MaterialTheme.typography.bodySmall)
            log.forEach { e ->
                Row {
                    Text(fmt.format(Date(e.ts)), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        e.message, style = MaterialTheme.typography.bodySmall,
                        color = if (e.error) MaterialTheme.colorScheme.error else Color.Unspecified,
                    )
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
