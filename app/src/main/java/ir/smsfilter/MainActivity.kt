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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDrawerState
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

enum class Page(val title: String) {
    Messages("Messages"),
    Test("Test a message"),
    Appearance("Appearance"),
    Settings("Settings")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(store: Store, clf: Classifier, learned: Learned) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(PREFS, 0) }
    var theme by remember { mutableStateOf(prefs.getString(KEY_THEME, "auto") ?: "auto") }
    var page by remember { mutableStateOf(Page.Messages) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val systemDark = (ctx.resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
    val dark = when (theme) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }

    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        ModalNavigationDrawer(
            drawerState = drawer,
            drawerContent = {
                ModalDrawerSheet {
                    Text(
                        "SMS Filter",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(20.dp)
                    )
                    Page.entries.forEach { p ->
                        NavigationDrawerItem(
                            label = { Text(p.title) },
                            selected = page == p,
                            onClick = {
                                page = p
                                scope.launch { drawer.close() }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                        )
                    }
                    Divider(Modifier.padding(vertical = 8.dp))
                    Text(
                        "v0.1 · offline · on-device",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                }
            }
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(page.title) },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawer.open() } }) {
                                Icon(Icons.Filled.Menu, contentDescription = "menu")
                            }
                        }
                    )
                }
            ) { pad ->
                Surface(Modifier.fillMaxSize().padding(pad), color = MaterialTheme.colorScheme.background) {
                    when (page) {
                        Page.Messages -> MessagesPage(store, clf, learned)
                        Page.Test -> TestPage(clf)
                        Page.Appearance -> AppearancePage(theme) { picked ->
                            theme = picked
                            prefs.edit().putString(KEY_THEME, picked).apply()
                        }
                        Page.Settings -> SettingsPage()
                    }
                }
            }
        }
    }
}

@Composable
fun MessagesPage(store: Store, clf: Classifier, learned: Learned) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(1) }
    var rows by remember { mutableStateOf(listOf<Msg>()) }
    var counts by remember { mutableStateOf(mapOf<Label, Int>()) }
    var scanning by remember { mutableStateOf(false) }
    var scanInfo by remember { mutableStateOf("") }

    fun refresh() = scope.launch {
        withContext(Dispatchers.IO) {
            rows = store.list(if (tab == 2) null else if (tab == 0) Label.SPAM else Label.IMPORTANT)
            counts = store.counts()
        }
    }
    LaunchedEffect(tab) { refresh() }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text(
            "filtered ${counts[Label.SPAM] ?: 0} · kept ${counts[Label.IMPORTANT] ?: 0} · unsure ${counts[Label.UNSURE] ?: 0}",
            style = MaterialTheme.typography.bodySmall
        )
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                            store.add(s.address, s.body, v, s.date)      // copy first, always
                            store.updateVerdict(s.date, s.body, v)       // never touches your own labels
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

        TabRow(selectedTabIndex = tab) {
            listOf("Quarantine", "Kept", "All").forEachIndexed { i, name ->
                Tab(tab == i, onClick = { tab = i }, text = { Text(name) })
            }
        }

        LazyColumn(Modifier.weight(1f)) {
            items(rows, key = { it.id }) { m -> MessageRow(m, store, learned, ::refresh) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(m: Msg, store: Store, learned: Learned, refresh: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).combinedClickable(
            onClick = {                                   // open the real conversation
                val target = Contacts.digits(m.sender)
                if (target.isNotEmpty()) {
                    try {
                        ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$target")))
                    } catch (t: Throwable) {
                        // no SMS app handles smsto: stay here
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

@Composable
fun TestPage(clf: Classifier) {
    var probe by remember { mutableStateOf("") }
    var sender by remember { mutableStateOf("") }
    var verdict by remember { mutableStateOf<Verdict?>(null) }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Paste any message to see what the filter would do with it.",
            style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(probe, { probe = it }, label = { Text("message text") },
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(sender, { sender = it }, label = { Text("sender (optional)") },
            modifier = Modifier.fillMaxWidth())
        Button(onClick = { verdict = clf.classify(probe, sender) }) { Text("Check") }
        verdict?.let { v ->
            Text(v.label.name, style = MaterialTheme.typography.titleMedium)
            Text("score ${v.score}", style = MaterialTheme.typography.bodySmall)
            Text("why: ${v.why.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
            if (v.norm.isNotBlank()) {
                Text("normalized: ${v.norm.take(300)}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun AppearancePage(theme: String, onTheme: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Theme", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("auto" to "Auto", "light" to "Light", "dark" to "Dark").forEach { (key, label) ->
                FilterChip(selected = theme == key, onClick = { onTheme(key) }, label = { Text(label) })
            }
        }
        Text("Auto follows your phone's day/night setting.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Divider(Modifier.padding(vertical = 8.dp))
        Text("Language", style = MaterialTheme.typography.titleMedium)
        Text("Interface is English, message text stays Persian. Full Farsi interface: later.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun SettingsPage() {
    val ctx = LocalContext.current
    val nm = remember { ctx.getSystemService(android.app.NotificationManager::class.java) }
    var filterOn by remember {
        mutableStateOf(ctx.getSharedPreferences(PREFS, 0).getBoolean("filter_on", true))
    }
    val listenerOk = remember {
        nm.isNotificationListenerAccessGranted(android.content.ComponentName(ctx, FilterService::class.java))
    }
    val smsOk = androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) ==
        PackageManager.PERMISSION_GRANTED
    val contactsOk = androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) ==
        PackageManager.PERMISSION_GRANTED

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Filter notifications", style = MaterialTheme.typography.bodyLarge)
            Switch(checked = filterOn, onCheckedChange = {
                filterOn = it
                ctx.getSharedPreferences(PREFS, 0).edit().putBoolean("filter_on", it).apply()
            })
        }
        Divider(Modifier.padding(vertical = 6.dp))
        Text("Permissions", style = MaterialTheme.typography.titleMedium)
        Text("notification access: ${if (listenerOk) "granted" else "missing"}")
        Text("read SMS: ${if (smsOk) "granted" else "missing"}")
        Text("contacts: ${if (contactsOk) "granted" else "missing"}")
        TextButton(onClick = {
            (ctx as? ComponentActivity)?.requestPermissions(
                arrayOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS), 1
            )
        }) { Text("Request SMS + contacts") }
        TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
            Text("Open notification access settings")
        }
        Divider(Modifier.padding(vertical = 6.dp))
        Text("Clean your SMS app (root)", style = MaterialTheme.typography.titleMedium)
        var rootClean by remember {
            mutableStateOf(ctx.getSharedPreferences(PREFS, 0).getBoolean("root_clean", false))
        }
        var rootState by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        var cleanOut by remember { mutableStateOf("") }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            rootState = withContext(Dispatchers.IO) {
                if (RootDbCleaner.available()) "granted" else "not granted"
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Allow editing the SMS database")
                Text("Deletes ads from the real inbox so MIUI/Google Messages never shows them. " +
                    "A copy of every deleted message stays in Quarantine, and a backup file is written first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = rootClean, onCheckedChange = {
                rootClean = it
                ctx.getSharedPreferences(PREFS, 0).edit().putBoolean("root_clean", it).apply()
                if (it) Thread { rootState = if (RootDbCleaner.available()) "granted" else "denied" }.start()
            })
        }
        Text("root: ${rootState ?: "checking…"}", style = MaterialTheme.typography.labelSmall)
        Button(
            enabled = rootClean && !busy,
            onClick = {
                busy = true
                cleanOut = "working…"
                Thread {
                    val contacts = Contacts.suffixes(ctx)
                    val clf = Classifier(RuleSet.load(ctx), Model.fromAssets(ctx), Learned(ctx))
                    val store = Store(ctx)
                    val sms = SmsReader.scan(ctx, 3000)
                    val ids = ArrayList<Long>()
                    for (s in sms) {
                        val v = clf.classify(s.body, s.address, contacts)
                        store.add(s.address, s.body, v, s.date)     // copy first, always
                        store.updateVerdict(s.date, s.body, v)
                        if (v.label == Label.SPAM && s.id > 0) ids.add(s.id)
                    }
                    val r = RootDbCleaner.deleteRows(ctx, ids)
                    cleanOut = if (r.ok) "deleted ${r.deleted} ads · backup ${r.backup?.substringAfterLast('/')}"
                    else "not cleaned: ${r.message}"
                    busy = false
                }.start()
            }
        ) { Text(if (busy) "Cleaning…" else "Clean my SMS app now") }
        if (cleanOut.isNotEmpty()) Text(cleanOut, style = MaterialTheme.typography.bodySmall)
        Divider(Modifier.padding(vertical = 6.dp))
        Text("Data", style = MaterialTheme.typography.titleMedium)
        Text("Everything stays on this phone. Your taps train the filter and are never uploaded.",
            style = MaterialTheme.typography.bodySmall)
    }
}
