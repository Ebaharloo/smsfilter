package ir.smsfilter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(this)
        val learned = Learned(this)
        val clf = Classifier(RuleSet.load(this), Model.fromAssets(this), learned)
        setContent { App(store, clf, learned) }
    }
}

private val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.US)
private const val PREFS = "smsfilter"
private const val KEY_THEME = "theme"   // auto | light | dark

@Composable
fun App(store: Store, clf: Classifier, learned: Learned) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(PREFS, 0) }
    var theme by remember { mutableStateOf(prefs.getString(KEY_THEME, "auto") ?: "auto") }
    val systemDark = (ctx.resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
    val dark = when (theme) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Screen(store, clf, learned, theme) { picked ->
                theme = picked
                prefs.edit().putString(KEY_THEME, picked).apply()
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Screen(store: Store, clf: Classifier, learned: Learned, theme: String, onTheme: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(1) }
    var rows by remember { mutableStateOf(listOf<Msg>()) }
    var counts by remember { mutableStateOf(mapOf<Label, Int>()) }
    var filterOn by remember {
        mutableStateOf(ctx.getSharedPreferences(PREFS, 0).getBoolean("filter_on", true))
    }
    var scanning by remember { mutableStateOf(false) }
    var scanInfo by remember { mutableStateOf("") }
    var showDebug by remember { mutableStateOf(false) }  // hidden: long-press the title
    var probe by remember { mutableStateOf("") }
    var verdict by remember { mutableStateOf<Verdict?>(null) }

    val nm = remember { ctx.getSystemService(android.app.NotificationManager::class.java) }
    val hasAccess = remember {
        nm.isNotificationListenerAccessGranted(android.content.ComponentName(ctx, FilterService::class.java))
    }
    var hasSmsRead by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) ==
                PackageManager.PERMISSION_GRANTED &&
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    fun refresh() = scope.launch {
        withContext(Dispatchers.IO) {
            rows = store.list(if (tab == 2) null else if (tab == 0) Label.SPAM else Label.IMPORTANT)
            counts = store.counts()
        }
    }
    LaunchedEffect(tab) { refresh() }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "SMS Filter",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { showDebug = !showDebug })
            )
            Switch(checked = filterOn, onCheckedChange = {
                filterOn = it
                ctx.getSharedPreferences(PREFS, 0).edit().putBoolean("filter_on", it).apply()
            })
        }
        Text(
            "filtered ${counts[Label.SPAM] ?: 0} · kept ${counts[Label.IMPORTANT] ?: 0} · unsure ${counts[Label.UNSURE] ?: 0}",
            style = MaterialTheme.typography.bodySmall
        )

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            listOf("auto" to "Auto", "light" to "Light", "dark" to "Dark").forEach { (key, label) ->
                FilterChip(selected = theme == key, onClick = { onTheme(key) }, label = { Text(label) })
            }
        }

        if (!hasSmsRead) {
            TextButton(onClick = {
                (ctx as? ComponentActivity)?.requestPermissions(
                    arrayOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS), 1
                )
                hasSmsRead = androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) ==
                    PackageManager.PERMISSION_GRANTED
            }) { Text("Grant SMS + contacts access") }
        }
        if (!hasAccess) {
            TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
                Text("Grant notification access")
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !scanning, onClick = {
                scanning = true
                scanInfo = "scanning inbox…"
                scope.launch {
                    val n = withContext(Dispatchers.IO) {
                        val contacts = Contacts.suffixes(ctx)
                        val sms = SmsReader.scan(ctx, 1000)
                        var spam = 0
                        for (s in sms) {
                            val v = clf.classify(s.body, s.address, contacts)
                            if (v.label == Label.SPAM) spam++
                            store.add(s.address, s.body, v, s.date)
                            store.updateVerdict(s.date, s.body, v)   // never touches his own labels
                        }
                        "${sms.size} messages · $spam ads · ${contacts.size} contacts allowed"
                    }
                    scanInfo = n
                    scanning = false
                    refresh()
                }
            }) { Text(if (scanning) "Scanning…" else "Scan inbox") }
            if (scanInfo.isNotEmpty()) Text(
                scanInfo, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 14.dp)
            )
        }

        if (showDebug) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(probe, { probe = it }, label = { Text("test a message") },
                    modifier = Modifier.weight(1f))
                Button(onClick = { verdict = clf.classify(probe, "") }) { Text("Check") }
            }
            verdict?.let {
                Text("${it.label} score=${it.score} · ${it.why.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
            }
        }

        TabRow(selectedTabIndex = tab) {
            listOf("Quarantine", "Kept", "All").forEachIndexed { i, name ->
                Tab(tab == i, onClick = { tab = i }, text = { Text(name) })
            }
        }

        LazyColumn(Modifier.weight(1f)) {
            items(rows, key = { it.id }) { m ->
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp)
                        .combinedClickable(
                            onClick = {                       // open the real conversation in the SMS app
                                val target = Contacts.digits(m.sender)
                                if (target.isNotEmpty()) {
                                    try {
                                        ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$target")))
                                    } catch (t: Throwable) {
                                        // no SMS app that handles smsto: stay in the filter
                                    }
                                }
                            },
                            onLongClick = {}
                        )
                ) {
                    // meta and score stay LTR: mixing an LTR run into RTL text scrambles both
                    Text("${m.sender}  ${fmt.format(Date(m.ts))}", style = MaterialTheme.typography.labelSmall)
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        Text(m.text, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        "${m.label} ${m.score} · ${m.why.take(90)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        Row {
                            TextButton(onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        store.relabel(m.id, Label.IMPORTANT)
                                        learned.markImportant(m.sender)
                                    }; refresh()
                                }
                            }) { Text("این مهم است") }
                            TextButton(onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        store.relabel(m.id, Label.SPAM)
                                        learned.markAd(m.sender, m.text)
                                    }; refresh()
                                }
                            }) { Text("تبلیغ") }
                        }
                    }
                }
            }
        }
    }
}
