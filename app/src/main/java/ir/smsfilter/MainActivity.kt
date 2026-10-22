package ir.smsfilter

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** MIUI Messages palette: true-black dark theme, blue accent, grey text. */
private fun miuiDark() = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF2E7DFF),
    onPrimary = androidx.compose.ui.graphics.Color.White,
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFF1A2E4A),
    onSecondaryContainer = androidx.compose.ui.graphics.Color.White,
    background = androidx.compose.ui.graphics.Color(0xFF000000),
    onBackground = androidx.compose.ui.graphics.Color.White,
    surface = androidx.compose.ui.graphics.Color(0xFF000000),
    onSurface = androidx.compose.ui.graphics.Color.White,
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF262626),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF9E9E9E),
    outline = androidx.compose.ui.graphics.Color(0xFF3A3A3A)
)

private fun miuiLight() = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF2E7DFF),
    onPrimary = androidx.compose.ui.graphics.Color.White,
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFFE3EDFF),
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFF0B1B33),
    background = androidx.compose.ui.graphics.Color.White,
    onBackground = androidx.compose.ui.graphics.Color(0xFF111111),
    surface = androidx.compose.ui.graphics.Color.White,
    onSurface = androidx.compose.ui.graphics.Color(0xFF111111),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFF2F2F2),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF6B6B6B),
    outline = androidx.compose.ui.graphics.Color(0xFFDADADA)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(this)
        val learned = Learned(this)
        val clf = Classifier(RuleSet.load(this), Model.fromAssets(this), learned)
        val openAddress = intent?.getStringExtra("address")
        setContent { App(store, clf, learned, openAddress) }
    }
}

private val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.US)
private const val PREFS = "smsfilter"
private const val KEY_THEME = "theme"
private const val KEY_FONT = "font_scale"

enum class Page(val title: String) {
    Inbox("Inbox"),
    Archived("Archived"),
    Ads("Ads"),
    Blocked("Blocked"),
    Test("Test a message"),
    Appearance("Appearance"),
    Settings("Settings")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(store: Store, clf: Classifier, learned: Learned, openAddress: String? = null) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(PREFS, 0) }
    var theme by remember { mutableStateOf(prefs.getString(KEY_THEME, "auto") ?: "auto") }
    var fontScale by remember { mutableStateOf(prefs.getFloat(KEY_FONT, 1.15f)) }
    var page by remember { mutableStateOf(if (openAddress != null) Page.Inbox else Page.Inbox) }
    var thread by remember { mutableStateOf<Pair<Long, String>?>(null) }  // threadId to address
    // opened from a notification: jump straight into that conversation
    LaunchedEffect(openAddress) {
        if (openAddress != null) {
            val id = withContext(Dispatchers.IO) { Sms.threadIdFor(ctx, openAddress) }
            if (id != null) thread = id to openAddress
        }
    }
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

    val base = LocalDensity.current
    MaterialTheme(colorScheme = if (dark) miuiDark() else miuiLight()) {
      CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
        ModalNavigationDrawer(
            drawerState = drawer,
            drawerContent = {
                ModalDrawerSheet {
                    Text("SMS Filter", style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(20.dp))
                    Page.entries.forEach { p ->
                        NavigationDrawerItem(
                            label = { Text(p.title) },
                            selected = page == p,
                            onClick = {
                                page = p
                                if (p == Page.Inbox && thread != null) { /* keep thread */ }
                                scope.launch { drawer.close() }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                        )
                    }
                    Divider(Modifier.padding(vertical = 8.dp))
                    Text("v0.2 · offline · on-device", style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                }
            }
        ) {
            Scaffold(
                topBar = {
                    if (page == Page.Inbox && thread != null) {
                        // the thread screen draws its own header (avatar, name, back)
                    } else {
                        TopAppBar(
                            title = { Text(page.title) },
                            navigationIcon = {
                                IconButton(onClick = { scope.launch { drawer.open() } }) {
                                    Icon(Icons.Filled.Menu, contentDescription = "menu")
                                }
                            }
                        )
                    }
                }
            ) { pad ->
                Surface(Modifier.fillMaxSize().padding(pad), color = MaterialTheme.colorScheme.background) {
                    when (page) {
                        Page.Inbox -> {
                            val open = thread
                            if (open == null) ConversationsPage(
                                onOpenThread = { id, addr -> thread = id to addr }
                            )
                            else ThreadPage(open.first, open.second) { thread = null }
                        }
                        Page.Archived -> ConversationsPage(
                            onOpenThread = { id, addr -> thread = id to addr },
                            archivedMode = true
                        )
                        Page.Ads -> AdsPage(store, learned)
                        Page.Blocked -> BlockedPage()
                        Page.Test -> TestPage(clf)
                        Page.Appearance -> AppearancePage(
                            theme = theme,
                            fontScale = fontScale,
                            onTheme = { picked ->
                                theme = picked
                                prefs.edit().putString(KEY_THEME, picked).apply()
                            },
                            onFont = { picked ->
                                fontScale = picked
                                prefs.edit().putFloat(KEY_FONT, picked).apply()
                            }
                        )
                        Page.Settings -> SettingsPage()
                    }
                }
            }
        }
      }
    }
}

/** the filtered ads, kept out of the inbox entirely */
@Composable
fun AdsPage(store: Store, learned: Learned) {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf(listOf<Msg>()) }
    var counts by remember { mutableStateOf(mapOf<Label, Int>()) }

    fun refresh() = scope.launch {
        withContext(Dispatchers.IO) {
            rows = store.list(Label.SPAM)
            counts = store.counts()
        }
    }
    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text(
            "ads ${counts[Label.SPAM] ?: 0} · kept ${counts[Label.IMPORTANT] ?: 0} · unsure ${counts[Label.UNSURE] ?: 0}",
            style = MaterialTheme.typography.bodySmall
        )
        if (rows.isEmpty()) {
            Text("Nothing filtered yet — tap «تبلیغ» on a message to teach the filter.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 12.dp))
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(rows, key = { it.id }) { m -> AdRow(m, store, learned, ::refresh) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AdRow(m: Msg, store: Store, learned: Learned, refresh: () -> Unit) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).combinedClickable(
        onClick = {}, onLongClick = {}
    )) {
        Text("${m.sender}  ${fmt.format(Date(m.ts))}", style = MaterialTheme.typography.labelSmall)
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Text(m.text, style = MaterialTheme.typography.bodyMedium)
        }
        Text("SPAM ${m.score} · ${m.why.take(80)}", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Row {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            store.relabel(m.id, Label.IMPORTANT)
                            learned.markImportant(m.sender)
                            // put it back into the real inbox
                            Sms.insertInbox(LocalContextHolder.ctx!!, m.sender, m.text, m.ts)
                        }; refresh()
                    }
                }) { Text("این مهم است") }
            }
        }
    }
}

/** tiny holder so AdRow can reach a Context without threading it through every call */
object LocalContextHolder {
    var ctx: android.content.Context? = null
}

/**
 * MIUI-style blocklist: senders and keywords, each removable.
 * Blocking never deletes: the message is quarantined and stays restorable on the Ads page.
 */
@Composable
fun BlockedPage() {
    val ctx = LocalContext.current
    var numbers by remember { mutableStateOf(Blocked.list(ctx).sorted()) }
    var keywords by remember { mutableStateOf(Blocked.keywords(ctx).sorted()) }
    var number by remember { mutableStateOf("") }
    var keyword by remember { mutableStateOf("") }
    fun refresh() {
        numbers = Blocked.list(ctx).sorted()
        keywords = Blocked.keywords(ctx).sorted()
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item {
            Text("Blocked numbers", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 14.dp))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(
                    number, { number = it },
                    label = { Text("number or shortcode") }, singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    enabled = number.trim().length >= 3,
                    onClick = { Blocked.add(ctx, number.trim()); number = ""; refresh() }
                ) { Text("Add") }
            }
        }
        if (numbers.isEmpty()) {
            item {
                Text("Nothing blocked. Block from a conversation's ⋮ menu, or add a number here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(numbers) { n ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(n, style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = { Blocked.remove(ctx, n); refresh() }) { Text("Unblock") }
            }
        }
        item {
            Divider(Modifier.padding(vertical = 8.dp))
            Text("Blocked keywords", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(
                    keyword, { keyword = it },
                    label = { Text("any message containing…") }, singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    enabled = keyword.trim().length >= 2,
                    onClick = { Blocked.addKeyword(ctx, keyword); keyword = ""; refresh() }
                ) { Text("Add") }
            }
        }
        if (keywords.isEmpty()) {
            item {
                Text("No keywords yet. A keyword blocks every message that contains it — including verification codes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(keywords) { k ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(k, style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = { Blocked.removeKeyword(ctx, k); refresh() }) { Text("Unblock") }
            }
        }
        item {
            Divider(Modifier.padding(vertical = 8.dp))
            Text("Blocked messages are never deleted — they are kept on the Ads page, where one tap restores them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp))
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
            if (v.norm.isNotBlank()) Text("normalized: ${v.norm.take(300)}",
                style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun AppearancePage(
    theme: String,
    fontScale: Float,
    onTheme: (String) -> Unit,
    onFont: (Float) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Text size", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1.0f to "Small", 1.15f to "Normal", 1.35f to "Large", 1.55f to "Extra large")
                .forEach { (value, label) ->
                    FilterChip(
                        selected = kotlin.math.abs(fontScale - value) < 0.01f,
                        onClick = { onFont(value) },
                        label = { Text(label) }
                    )
                }
        }
        Text("Applies to the whole app, including Persian text in messages.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Text("Theme", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("auto" to "Auto", "light" to "Light", "dark" to "Dark").forEach { (key, label) ->
                FilterChip(selected = theme == key, onClick = { onTheme(key) }, label = { Text(label) })
            }
        }
        Text("Auto follows your phone's day/night setting.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Text("Preview", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        ListItem(
            headlineContent = { Text("بانک سامان") },
            supportingContent = { Text("برداشت مبلغ ۱٬۴۰۲٬۷۵۶ ریال، مانده ۱۹۹٬۰۶۴٬۵۵۲") },
            leadingContent = { Avatar("+98210001", 52.dp, 16) }
        )
    }
}

@Composable
fun SettingsPage() {
    val ctx = LocalContext.current
    LocalContextHolder.ctx = ctx
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
    val roleManager = remember { ctx.getSystemService(RoleManager::class.java) }
    val isDefaultSms = remember { roleManager?.isRoleAvailable(RoleManager.ROLE_SMS) == true &&
        roleManager.isRoleHeld(RoleManager.ROLE_SMS) }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Filter ads", style = MaterialTheme.typography.bodyLarge)
            Switch(checked = filterOn, onCheckedChange = {
                filterOn = it
                ctx.getSharedPreferences(PREFS, 0).edit().putBoolean("filter_on", it).apply()
            })
        }
        Divider(Modifier.padding(vertical = 6.dp))
        Text("Your SMS app", style = MaterialTheme.typography.titleMedium)
        Text(if (isDefaultSms) "SMS Filter is your SMS app — ads are never written to the inbox."
             else "MIUI Messages still receives SMS. Ads are only hidden from notifications.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!isDefaultSms) {
            Button(onClick = {
                val roleManager = ctx.getSystemService(RoleManager::class.java)
                if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
                    (ctx as? ComponentActivity)?.startActivityForResult(
                        roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS), 1001
                    )
                }
            }) { Text("Make SMS Filter my SMS app") }
        }
        Divider(Modifier.padding(vertical = 6.dp))
        Text("Permissions", style = MaterialTheme.typography.titleMedium)
        Text("notification access: ${if (listenerOk) "granted" else "missing"}")
        Text("read SMS: ${if (smsOk) "granted" else "missing"}")
        Text("contacts: ${if (contactsOk) "granted" else "missing"}")
        TextButton(onClick = {
            (ctx as? ComponentActivity)?.requestPermissions(
                arrayOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS,
                    Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS), 1
            )
        }) { Text("Request SMS permissions") }
        TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
            Text("Open notification access settings")
        }
        Divider(Modifier.padding(vertical = 6.dp))
        Text("Data", style = MaterialTheme.typography.titleMedium)
        Text("Everything stays on this phone. Your taps train the filter and are never uploaded.",
            style = MaterialTheme.typography.bodySmall)
    }
}
