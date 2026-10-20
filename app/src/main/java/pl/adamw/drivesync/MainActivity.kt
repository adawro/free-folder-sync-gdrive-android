package pl.adamw.drivesync

import android.Manifest
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.PowerManager
import android.os.Bundle
import android.widget.Toast
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
    var pairs by remember { mutableStateOf(settings.pairs) }
    val pathEdits = remember { mutableStateMapOf<Long, String>() }  // niezapisane zmiany ścieżek na Drive
    var removing by remember { mutableStateOf<FolderPair?>(null) }
    var clientId by remember { mutableStateOf(settings.customClientId) }
    var clientSecret by remember { mutableStateOf(CustomOAuth.clientSecret(context).orEmpty()) }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        runCatching { Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(result.data) }
            .onSuccess { settings.needsSignIn = false; signInError = null; SyncWorker.schedule(context) }
            .onFailure { signInError = context.getString(R.string.connect_failed, it.message) }
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
            .onFailure { signInError = context.getString(R.string.connect_failed, it.message) }
    }

    val addFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (settings.pairs.any { it.treeUri == uri }) {
            Toast.makeText(context, R.string.folder_already_added, Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Domyślnie "Telefon/<nazwa folderu>"; dwa foldery o tej samej nazwie dostają " 2", " 3"...
        val base = context.getString(R.string.default_drive_parent) + "/" + (LocalFiles.treeName(context, uri) ?: "folder")
        val used = settings.pairs.map { it.drivePath }.toSet()
        val path = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }.first { it !in used }
        settings.addPair(uri, path)
        pairs = settings.pairs
        SyncWorker.schedule(context)
        refresh++
    }

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        SyncWorker.schedule(context)  // budzik po aktualizacji z wersji bez budzika
    }

    // Stan zadań WorkManagera (ręczne + okresowe)
    // Wszystkie wysyłki mają znacznik TAG; SCHEDULED osobno, bo zadanie z budzika sprzed 0.7 go nie ma
    val tagged by WorkManager.getInstance(context).getWorkInfosByTagFlow(SyncWorker.TAG).collectAsState(emptyList())
    val periodic by WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(SyncWorker.SCHEDULED).collectAsState(emptyList())
    val works = (tagged + periodic).distinctBy { it.id }
    val running = works.firstOrNull { it.state == WorkInfo.State.RUNNING }
    // Po błędzie zadanie czeka w kolejce na ponowienie (Result.retry)
    val retryAt = works.filter { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0 }
        .minOfOrNull { it.nextScheduleTimeMillis }

    // Log i liczniki z bazy (co 2 s, bo zapisuje je worker)
    var log by remember { mutableStateOf(emptyList<LogEntry>()) }
    var uploadedCounts by remember { mutableStateOf(emptyMap<Long, Int>()) }
    var exactAlarms by remember { mutableStateOf(true) }
    var status by remember { mutableIntStateOf(0) }  // zmiana = ponowny odczyt stanu ostatniej próby
    var batteryOk by remember { mutableStateOf(true) }
    var dataSaverOk by remember { mutableStateOf(true) }
    LaunchedEffect(refresh) {
        while (true) {
            withContext(Dispatchers.IO) {
                log = db.recentLog(40)
                uploadedCounts = db.uploadedCounts()
            }
            status = (settings.lastAttemptAt xor settings.lastSyncAt).hashCode() + (settings.lastError?.hashCode() ?: 0)
            // Zgodę na dokładne budziki użytkownik zmienia w ustawieniach systemu
            val exact = SyncWorker.canScheduleExact(context)
            if (exact && !exactAlarms) SyncWorker.schedule(context)
            exactAlarms = exact
            // Bez tych zgód Android w nocy odcina aplikacji sieć ("Unable to resolve host www.googleapis.com")
            batteryOk = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
            dataSaverOk = context.getSystemService(ConnectivityManager::class.java).restrictBackgroundStatus !=
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
            delay(2000)
        }
    }

    val treeNames = remember(pairs, refresh) {
        pairs.associate { it.id to runCatching { LocalFiles.treeName(context, it.treeUri) }.getOrNull() }
    }
    val fmt = remember { SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()) }

    Column(
        Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.app_subtitle),
            style = MaterialTheme.typography.bodySmall)

        Section(stringResource(R.string.section_account)) {
            val connected = !settings.needsSignIn.also { refresh }
            Text(stringResource(if (connected) R.string.connected else R.string.not_connected))
            signInError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            if (BuildConfig.BUILTIN_AUTH) {
                Toggle(stringResource(R.string.builtin_auth), settings.useBuiltinAuth.also { refresh }) {
                    settings.useBuiltinAuth = it
                    settings.needsSignIn = true
                    signInError = null
                    refresh++
                }
            }
            if (settings.useBuiltinAuth) {
                Button(onClick = { signIn() }) { Text(stringResource(if (connected) R.string.reconnect else R.string.connect)) }
            } else {
                Text(
                    stringResource(R.string.custom_client_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = clientId, onValueChange = { clientId = it }, enabled = !connected,
                    label = { Text(stringResource(R.string.client_id)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = clientSecret, onValueChange = { clientSecret = it }, enabled = !connected,
                    label = { Text(stringResource(R.string.client_secret)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                )
                // Po zalogowaniu pola są zablokowane; zmiana klienta dopiero po wylogowaniu
                if (connected) {
                    OutlinedButton(onClick = { CustomOAuth.signOut(context); refresh++ }) {
                        Text(stringResource(R.string.sign_out))
                    }
                } else {
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
                    ) { Text(stringResource(R.string.sign_in_browser)) }
                }
            }
        }

        Section(stringResource(R.string.section_folders)) {
            if (pairs.isEmpty()) Text(stringResource(R.string.no_folders), style = MaterialTheme.typography.bodySmall)
            pairs.forEachIndexed { i, pair ->
                if (i > 0) HorizontalDivider()
                // Folder usunięty w telefonie albo cofnięty dostęp - nazwy nie da się odczytać
                Text(treeNames[pair.id] ?: stringResource(R.string.folder_unavailable),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (treeNames[pair.id] == null) MaterialTheme.colorScheme.error else Color.Unspecified)
                val edit = pathEdits[pair.id] ?: pair.drivePath
                OutlinedTextField(
                    value = edit,
                    onValueChange = { pathEdits[pair.id] = it },
                    label = { Text(stringResource(R.string.drive_path_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.uploaded_count, uploadedCounts[pair.id] ?: 0),
                    style = MaterialTheme.typography.bodySmall)
                Row(
                    Modifier.fillMaxWidth().toggleable(value = pair.watch, role = Role.Checkbox) {
                        settings.setWatch(pair.id, it)
                        pairs = settings.pairs
                        SyncWorker.armWatch(context)
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = pair.watch, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.watch_label))
                }
                if (pair.watch) Text(stringResource(R.string.watch_hint), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { removing = pair }) { Text(stringResource(R.string.remove_folder)) }
                    val newPath = Settings.cleanPath(edit)
                    if (newPath != pair.drivePath && newPath.isNotEmpty()) {
                        Button(onClick = {
                            settings.setDrivePath(pair.id, newPath)
                            // Nowe miejsce na Drive: pliki tego folderu idą tam od nowa (identyczne już
                            // leżące w nowym miejscu są tylko powiązane, nie wysyłane drugi raz)
                            db.forgetDrive(pair.id)
                            db.log(context.getString(R.string.log_target_changed, newPath))
                            pathEdits.remove(pair.id)
                            pairs = settings.pairs
                            refresh++
                        }) { Text(stringResource(R.string.save)) }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { SyncWorker.syncNow(context, pair.id); refresh++ },
                        enabled = running == null && !settings.needsSignIn && newPath == pair.drivePath,
                    ) { Text(stringResource(R.string.sync_folder)) }
                }
            }
            Button(onClick = { addFolder.launch(null) }) { Text(stringResource(R.string.add_folder)) }
        }

        removing?.let { pair ->
            AlertDialog(
                onDismissRequest = { removing = null },
                title = { Text(stringResource(R.string.remove_folder_title, treeNames[pair.id] ?: pair.drivePath)) },
                text = { Text(stringResource(R.string.remove_folder_text, pair.drivePath)) },
                confirmButton = {
                    TextButton(onClick = {
                        settings.removePair(pair.id)
                        db.forgetPair(pair.id)
                        // Zgoda na folder jest zbędna, chyba że ten sam folder został dodany ponownie
                        if (settings.pairs.none { it.treeUri == pair.treeUri }) {
                            runCatching {
                                context.contentResolver.releasePersistableUriPermission(pair.treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                        }
                        pathEdits.remove(pair.id)
                        pairs = settings.pairs
                        removing = null
                        SyncWorker.schedule(context)
                        refresh++
                    }) { Text(stringResource(R.string.remove_folder)) }
                },
                dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } },
            )
        }

        Section(stringResource(R.string.section_when)) {
            Text(stringResource(R.string.upload_via), style = MaterialTheme.typography.bodyMedium)
            Toggle(stringResource(R.string.wifi), settings.allowWifi.also { refresh }) {
                settings.allowWifi = it
                if (!it) settings.allowMobile = true  // co najmniej jedna sieć
                SyncWorker.schedule(context); SyncWorker.constraintsChanged(context); refresh++
            }
            Toggle(stringResource(R.string.mobile_data), settings.allowMobile.also { refresh }) {
                settings.allowMobile = it
                if (!it) settings.allowWifi = true
                SyncWorker.schedule(context); SyncWorker.constraintsChanged(context); refresh++
            }
            Toggle(stringResource(R.string.charging_only), settings.chargingOnly.also { refresh }) {
                settings.chargingOnly = it; SyncWorker.schedule(context); SyncWorker.constraintsChanged(context); refresh++
            }
            val time = settings.syncTimeMinutes.also { refresh }
            val hhmm = "%d:%02d".format(time / 60, time % 60)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.daily_at), Modifier.weight(1f))
                OutlinedButton(onClick = {
                    TimePickerDialog(context, { _, h, m ->
                        settings.syncTimeMinutes = h * 60 + m
                        SyncWorker.schedule(context)
                        refresh++
                    }, time / 60, time % 60, true).show()
                }) { Text(hhmm) }
            }
            Text(
                stringResource(R.string.next_run, fmt.format(SyncWorker.nextRun(settings).time), hhmm),
                style = MaterialTheme.typography.bodySmall,
            )
            if (!exactAlarms) {
                Text(stringResource(R.string.exact_alarm_missing, hhmm), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    context.startActivity(
                        Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                    )
                }) { Text(stringResource(R.string.exact_alarm_allow)) }
            }
            if (!batteryOk) {
                Text(stringResource(R.string.battery_missing), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    context.startActivity(
                        Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                    )
                }) { Text(stringResource(R.string.battery_allow)) }
            }
            if (!dataSaverOk) {
                Text(stringResource(R.string.data_saver_missing), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    context.startActivity(
                        Intent(android.provider.Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    )
                }) { Text(stringResource(R.string.data_saver_allow)) }
            }
        }

        Section(stringResource(R.string.section_status)) {
            Text(stringResource(R.string.uploaded_count, uploadedCounts.values.sum()))
            val last = settings.lastSyncAt.also { refresh; status }
            Text(stringResource(R.string.last_sync, if (last > 0) fmt.format(Date(last)) else stringResource(R.string.never)))
            val pending = settings.pendingCount
            if (pending > 0) Text(stringResource(R.string.pending_count, pending), color = MaterialTheme.colorScheme.error)
            val error = settings.lastError
            if (error != null && running == null) {
                Text(stringResource(R.string.last_attempt_failed, fmt.format(Date(settings.lastAttemptAt)), error),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (pairs.any { it.watch }) {
                val armed = works.any { SyncWorker.WATCH_TAG in it.tags && !it.state.isFinished }
                Text(stringResource(if (armed) R.string.watch_armed else R.string.watch_not_armed),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (armed) Color.Unspecified else MaterialTheme.colorScheme.error)
            }
            if (running == null && retryAt != null && retryAt != Long.MAX_VALUE) {
                Text(stringResource(R.string.retry_at, fmt.format(Date(retryAt))), style = MaterialTheme.typography.bodySmall)
            }
            if (running != null) {
                val file = running.progress.getString("file")
                val pct = running.progress.getInt("pct", 0)
                Text(if (file != null) stringResource(R.string.uploading_file, file, pct) else stringResource(R.string.scanning))
                LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
            }
            Button(
                onClick = { SyncWorker.syncNow(context); refresh++ },
                enabled = running == null && pairs.isNotEmpty() && !settings.needsSignIn,
            ) { Text(stringResource(R.string.sync_now)) }
        }

        Section(stringResource(R.string.section_log)) {
            if (log.isEmpty()) Text(stringResource(R.string.log_empty), style = MaterialTheme.typography.bodySmall)
            else OutlinedButton(onClick = {
                // Cały dziennik do schowka (np. żeby wkleić treść błędu z Google)
                val text = log.joinToString("\n") { (if (it.error) "! " else "") + fmt.format(Date(it.ts)) + "  " + it.message }
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText(context.getString(R.string.section_log), text))
                Toast.makeText(context, R.string.log_copied, Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.log_copy)) }
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
