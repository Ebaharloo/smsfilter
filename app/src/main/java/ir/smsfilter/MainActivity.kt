package ir.smsfilter

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.smsfilter.core.InboxPolicy
import ir.smsfilter.addon.AddonRule
import ir.smsfilter.addon.AddonRules
import ir.smsfilter.addon.FeatureSelfTest
import ir.smsfilter.addon.SmsEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** MIUI Messages palette: true-black dark theme, blue accent, grey text. */
private fun miuiDark() = darkColorScheme(
    primary = Color(0xFF2E7DFF),
    onPrimary = androidx.compose.ui.graphics.Color.White,
    secondaryContainer = Color(0xFF1A2E4A),
    onSecondaryContainer = androidx.compose.ui.graphics.Color.White,
    background = Color(0xFF000000),
    onBackground = androidx.compose.ui.graphics.Color.White,
    surface = Color(0xFF000000),
    onSurface = androidx.compose.ui.graphics.Color.White,
    surfaceVariant = Color(0xFF262626),
    onSurfaceVariant = Color(0xFF9E9E9E),
    // The container roles are the app's own, not the baseline purple-grey: AlertDialog paints
    // surfaceContainerHigh and a tonal elevation over #000 measured 1.05:1 - no container at all.
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF131A22),
    surfaceContainer = Color(0xFF1A222C),
    surfaceContainerHigh = Color(0xFF222B36),
    surfaceContainerHighest = Color(0xFF2A3440),
    surfaceDim = Color(0xFF000000),
    surfaceBright = Color(0xFF2A3440),
    outline = Color(0xFF3A3A3A),
    outlineVariant = Color(0xFF37424F)
)

private fun miuiLight() = lightColorScheme(
    primary = Color(0xFF2E7DFF),
    onPrimary = androidx.compose.ui.graphics.Color.White,
    secondaryContainer = Color(0xFFE3EDFF),
    onSecondaryContainer = Color(0xFF0B1B33),
    background = androidx.compose.ui.graphics.Color.White,
    onBackground = Color(0xFF111111),
    surface = androidx.compose.ui.graphics.Color.White,
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFF2F2F2),
    onSurfaceVariant = Color(0xFF6B6B6B),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F9FC),
    surfaceContainer = Color(0xFFF1F4F9),
    surfaceContainerHigh = Color(0xFFEBEFF6),
    surfaceContainerHighest = Color(0xFFE3E8F0),
    surfaceDim = Color(0xFFDDE3EC),
    surfaceBright = Color(0xFFFFFFFF),
    outline = Color(0xFFDADADA),
    outlineVariant = Color(0xFFD5DBE4)
)

/** an outside request to write to somebody: address book, a browser `sms:` link, a notification */
data class ComposeRequest(val address: String, val body: String? = null)

class MainActivity : ComponentActivity() {

    /** consumed by the UI once it has navigated; null = no pending request */
    private val pending = mutableStateOf<ComposeRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store.get(this)
        val learned = Learned(this)
        val clf = Classifier(RuleSet.load(this), Model.fromAssets(this), learned)
        pending.value = requestFrom(intent)
        setContent { App(store, clf, learned, pending) }
    }

    /** singleTop: the address book re-delivers the intent to the running instance */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pending.value = requestFrom(intent)
    }

    private fun requestFrom(intent: Intent?): ComposeRequest? {
        if (intent == null) return null
        when (intent.action) {
            Intent.ACTION_SENDTO, Intent.ACTION_VIEW ->
                ir.smsfilter.core.ComposeIntent.parse(intent.dataString.orEmpty())?.let { (number, body) ->
                    if (number.isNotBlank()) return ComposeRequest(number, body)
                }
        }
        return intent.getStringExtra("address")?.takeIf { it.isNotBlank() }?.let { ComposeRequest(it) }
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
    Settings("Settings"),
    Features("Features"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(
    store: Store,
    clf: Classifier,
    learned: Learned,
    pending: MutableState<ComposeRequest?> = mutableStateOf(null)
) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(PREFS, 0) }
    var theme by remember { mutableStateOf(prefs.getString(KEY_THEME, "auto") ?: "auto") }
    var fontScale by remember { mutableStateOf(prefs.getFloat(KEY_FONT, 1.15f)) }
    var page by remember { mutableStateOf(Page.Inbox) }
    var thread by remember { mutableStateOf<Pair<Long, String>?>(null) }  // threadId to address
    // one snapshot for both list pages: switching Inbox <-> Archive must not re-query the provider
    var inbox by remember { mutableStateOf<InboxData?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        // 120 ms coalesces the burst of onChange callbacks one provider write fires
        kotlinx.coroutines.delay(120)
        val fresh = withContext(Dispatchers.IO) {
            val threads = Sms.threads(ctx)
            val bySuffix = Contacts.namesFor(ctx)
            // a "name" that is just the number is not a name; both checks used to run per row, per
            // recomposition, with a regex normalisation each time
            val names = HashMap<String, String>(threads.size)
            for (t in threads) {
                val n = bySuffix[Contacts.suffixOf(t.address)] ?: continue
                if (Contacts.digits(n) != Contacts.digits(t.address)) names[t.address] = n
            }
            InboxData(
                threads = threads,
                names = names,
                // bidi isolation is real regex work; doing it here keeps it off the scroll path
                previews = threads.associate {
                    it.id to ir.smsfilter.core.InboxPolicy.forDisplay(
                        it.snippet.replace('\n', ' ').trim()
                    )
                },
                pinned = ThreadFlags.pinned(ctx),
                muted = ThreadFlags.muted(ctx),
                archived = ThreadFlags.archived(ctx) + ThreadFlags.locallyArchived(ctx),
                spam = ThreadFlags.spamThreads(ctx),
                contactSuffixes = Contacts.suffixes(ctx),
                // one person with two numbers: both rows would show the same name, so the list adds
                // the last digits to tell the conversations apart
                ambiguousNames = HashMap<String, Int>().also { counts ->
                    names.values.forEach { n -> counts[n] = (counts[n] ?: 0) + 1 }
                }.filterValues { it > 1 }.keys
            )
        }
        inbox = fresh
    }
    // The provider changes behind our back: a message that arrived while the list is open, another
    // app writing, or our own action. Without this the inbox never showed a new SMS until the page
    // was reopened.
    DisposableEffect(Unit) {
        val observer = object : android.database.ContentObserver(
            android.os.Handler(android.os.Looper.getMainLooper())
        ) {
            override fun onChange(selfChange: Boolean) { reload++ }
        }
        ctx.contentResolver.registerContentObserver(
            android.provider.Telephony.Sms.CONTENT_URI, true, observer
        )
        onDispose { ctx.contentResolver.unregisterContentObserver(observer) }
    }
    var composeBody by remember { mutableStateOf<String?>(null) }
    // opened from the address book / a sms: link / a notification: go straight to that conversation
    val request = pending.value
    LaunchedEffect(request) {
        val req = request ?: return@LaunchedEffect
        // the lookup must finish before the request is cleared: clearing it first changes this
        // effect's key and cancels the coroutine, which is how the address-book intent opened the
        // app on the inbox and did nothing
        val id = withContext(Dispatchers.IO) { Sms.threadIdFor(ctx, req.address) } ?: -1L
        page = Page.Inbox
        composeBody = req.body
        thread = id to req.address
        pending.value = null
    }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // system back must walk the app's own stack: thread -> list, page -> inbox, not finish()
    BackHandler(enabled = thread != null || page != Page.Inbox) {
        if (thread != null) thread = null else page = Page.Inbox
    }

    val systemDark = (ctx.resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
    val dark = when (theme) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }

    val base = LocalDensity.current
    // The system scale is the user's accessibility setting; the app's own size multiplies it
    // instead of replacing it. Dialogs re-apply this value (see AppAlertDialog).
    val appDensity = Density(base.density, base.fontScale * fontScale)
    MaterialTheme(colorScheme = if (dark) miuiDark() else miuiLight()) {
      CompositionLocalProvider(
          LocalDensity provides appDensity,
          LocalAppDensity provides appDensity
      ) {
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
                                // switch after the drawer is gone: changing the page mid-animation
                                // looked like the content was jumping between inbox and archive
                                scope.launch { drawer.close(); page = p }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                        )
                    }
                    Divider(Modifier.padding(vertical = 8.dp))
                    // read the real versionName: a hardcoded string went stale every release
                    val version = remember {
                        try {
                            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
                        } catch (t: Throwable) {
                            null
                        }
                    }
                    Text("v${version ?: "?"} · offline · on-device", style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                }
            }
        ) {
            Scaffold(
                topBar = {
                    if ((page == Page.Inbox && thread != null) || page == Page.Ads) {
                        // these screens draw their own header (back arrow, title, menu)
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
                    // A readable measure: without it a 2275 dp window stretched one row's actions and
                    // its reorder arrows 1885 dp apart. 840 dp still holds the editor's chip rows.
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        Box(Modifier.fillMaxHeight().widthIn(max = 840.dp).fillMaxWidth()) {
                    when (page) {
                        Page.Inbox -> {
                            val open = thread
                            if (open == null) ConversationsPage(
                                data = inbox,
                                onReload = { reload++ },
                                onOpenThread = { id, addr -> thread = id to addr },
                                learned = learned,
                                store = store
                            )
                            else ThreadPage(open.first, open.second, learned, composeBody, reload) {
                                thread = null
                                composeBody = null
                                reload++      // read state / spam marks may have changed
                            }
                        }
                        Page.Archived -> ConversationsPage(
                            data = inbox,
                            onReload = { reload++ },
                            onOpenThread = { id, addr -> thread = id to addr },
                            archivedMode = true,
                            learned = learned,
                            store = store
                        )
                        Page.Ads -> AdsPage(store, learned) { page = Page.Inbox }
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
Page.Features -> FeaturesPage()
                    }
                        }
                    }
                }
            }
        }
      }
    }
}

/**
 * Filtered ads: the same visual language as the inbox (row height, avatar, one-line preview),
 * with the controls this page was missing - back, search and an overflow menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdsPage(store: Store, learned: Learned, onBack: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf(listOf<Msg>()) }
    var previews by remember { mutableStateOf(mapOf<Long, String>()) }
    var counts by remember { mutableStateOf(mapOf<Label, Int>()) }
    var showUnsure by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmRecheck by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<Msg?>(null) }
    val snack = remember { SnackbarHostState() }

    fun refresh() = scope.launch {
        withContext(Dispatchers.IO) {
            // "unsure 85" used to be counted in the header and listed nowhere: those rows are the
            // ones worth a human look, so they get their own view
            val loaded = store.list(if (showUnsure) Label.UNSURE else Label.SPAM, limit = 20000)
            rows = loaded
            // bidi isolation is regex work: do it here, off the scroll path
            previews = loaded.associate { it.id to InboxPolicy.forDisplay(it.text.replace('\n', ' ')) }
            counts = store.counts()
        }
    }
    LaunchedEffect(Unit) { refresh() }

    val shown = remember(rows, query) {
        if (query.isBlank()) rows
        else rows.filter {
            it.text.contains(query, true) || it.sender.contains(query, true)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "back")
                }
                Text("Ads", fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    modifier = Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Scan inbox for ads") },
                            onClick = {
                                menuOpen = false
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        InboxScan.run(
                                            ctx,
                                            Classifier(RuleSet.load(ctx), Model.fromAssets(ctx), learned),
                                            store
                                        )
                                    }
                                    refresh()
                                    snack.showSnackbar(
                                        "scanned ${result.scanned} · kept ${result.spam} ads for review — nothing hidden"
                                    )
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Re-check with current filter") },
                            onClick = { menuOpen = false; confirmRecheck = true }
                        )
                        DropdownMenuItem(text = { Text("Delete all ads") },
                            onClick = { menuOpen = false; confirmClear = true })
                        DropdownMenuItem(
                            text = { Text("Export labels for training") },
                            onClick = {
                                menuOpen = false
                                scope.launch {
                                    val path = withContext(Dispatchers.IO) {
                                        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
                                        dir.mkdirs()
                                        val f = java.io.File(dir, "labels-${System.currentTimeMillis()}.jsonl")
                                        f.writeText(store.labelsJsonl())
                                        f.absolutePath
                                    }
                                    val n = store.labels().size
                                    snack.showSnackbar("exported $n labels → $path")
                                }
                            })
                        DropdownMenuItem(
                            text = { Text("Import my earlier ad marks") },
                            onClick = {
                                menuOpen = false
                                scope.launch {
                                    val n = withContext(Dispatchers.IO) {
                                        InboxScan.importOldMarksAsLabels(ctx, store)
                                    }
                                    refresh()
                                    snack.showSnackbar("$n messages from conversations you marked became labels")
                                }
                            })
                    }
                }
            }
            SearchPill(query, { query = it }, "Search ads")
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = !showUnsure,
                    onClick = { if (showUnsure) { showUnsure = false; refresh() } },
                    label = { Text("Ads ${counts[Label.SPAM] ?: 0}") }
                )
                FilterChip(
                    selected = showUnsure,
                    onClick = { if (!showUnsure) { showUnsure = true; refresh() } },
                    label = { Text("Unsure ${counts[Label.UNSURE] ?: 0}") }
                )
            }
            Text(
                "filtered ${counts[Label.SPAM] ?: 0} · kept ${counts[Label.IMPORTANT] ?: 0} · unsure ${counts[Label.UNSURE] ?: 0}" +
                    if ((counts[Label.SPAM] ?: 0) > rows.size) " · showing newest ${rows.size}" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
            if (shown.isEmpty()) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    Text(
                        if (rows.isEmpty()) {
                            if (showUnsure) "Nothing is unsure right now" else "Nothing filtered yet"
                        } else "No match",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text("Ads the filter catches land here instead of your inbox.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.id }) { m ->
                        SwipeRow(
                            rightLabel = "Restore",
                            leftLabel = "Block",
                            heavyLabel = "Delete",
                            onRight = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { AdActions.restore(ctx, store, learned, m) }
                                    refresh()
                                    snack.showSnackbar("Restored to inbox")
                                }
                            },
                            onLeft = {
                                scope.launch {
                                    // blocking is a user verdict too: keep it for training before acting
                                    withContext(Dispatchers.IO) { store.recordLabel(m.sender, m.text, Label.SPAM) }
                                    Blocked.add(ctx, m.sender)
                                    // blocking is forward-looking: this stored ad stays until the user
                                    // restores or deletes it, and the snackbar has to say so
                                    snack.showSnackbar("${Contacts.displayName(m.sender)} blocked — this ad stays here")
                                }
                            },
                            onHeavy = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        // keep the verdict even though the copy goes away
                                        store.recordLabel(m.sender, m.text, Label.SPAM)
                                        store.delete(m.id)
                                    }
                                    refresh()
                                    snack.showSnackbar("Ad deleted — your label is kept for training")
                                }
                            }
                        ) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 92.dp)
                                .clickable { open = m }
                                .padding(start = 16.dp, end = 20.dp, top = 14.dp, bottom = 14.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Avatar(m.sender, 40.dp, 14, neutral = true)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(Contacts.displayName(m.sender), fontSize = 17.sp, maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                BodyText(
                                    previews[m.id] ?: m.text,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    preformatted = true,
                                    modifier = Modifier.padding(top = 3.dp)
                                )
                            }
                            Text(fmt.format(Date(m.ts)), fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        }
                    }
                }
            }
        }
        SnackbarHost(snack, modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
    }

    if (confirmClear) {
        AppAlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete all ads?") },
            text = { Text("Removes the kept ad copies from this app. Your SMS inbox is not touched.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { store.deleteAll(Label.SPAM) }
                        confirmClear = false
                        refresh()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }

    if (confirmRecheck) {
        AppAlertDialog(
            onDismissRequest = { confirmRecheck = false },
            title = { Text("Re-check the ads page?") },
            text = {
                Text(
                    "Runs the current rules over the ${rows.size} messages kept here. " +
                        "Anything that no longer looks like an ad comes back to the inbox; " +
                        "nothing is deleted."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRecheck = false
                    scope.launch {
                        val res = withContext(Dispatchers.IO) {
                            InboxScan.recheck(
                                ctx,
                                Classifier(RuleSet.load(ctx), Model.fromAssets(ctx), learned),
                                store
                            )
                        }
                        refresh()
                        snack.showSnackbar(
                            "re-checked ${res.scanned} · moved ${res.restored} back to the inbox"
                        )
                    }
                }) { Text("Re-check") }
            },
            dismissButton = { TextButton(onClick = { confirmRecheck = false }) { Text("Cancel") } }
        )
    }

    open?.let { m ->
        ModalBottomSheet(
            onDismissRequest = { open = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(Contacts.displayName(m.sender), fontSize = 17.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                BodyText(m.text, fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 20.dp))
                Text("SPAM ${m.score} · ${m.why}", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                Divider(Modifier.padding(vertical = 6.dp))
                ListItem(
                    headlineContent = { Text("Not an ad - restore to inbox") },
                    modifier = Modifier.clickable {
                        scope.launch {
                            withContext(Dispatchers.IO) { AdActions.restore(ctx, store, learned, m) }
                            open = null
                            refresh()
                        }
                    }
                )
                ListItem(
                    headlineContent = { Text("Block this sender") },
                    modifier = Modifier.clickable {
                        Blocked.add(ctx, m.sender)
                        scope.launch {
                            snack.showSnackbar("${Contacts.displayName(m.sender)} blocked")
                        }
                        open = null
                    }
                )
                ListItem(
                    headlineContent = { Text("Delete this ad") },
                    modifier = Modifier.clickable {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                store.recordLabel(m.sender, m.text, Label.SPAM)
                                store.delete(m.id)
                            }
                            open = null
                            refresh()
                        }
                    }
                )
            }
        }
    }
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
            if (v.norm.isNotBlank()) BodyText("normalized: ${v.norm.take(300)}", fontSize = 11.sp)
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
            supportingContent = { BodyText("برداشت مبلغ ۱٬۴۰۲٬۷۵۶ ریال، مانده ۱۹۹٬۰۶۴٬۵۵۲", fontSize = 14.sp) },
            leadingContent = { Avatar("+98210001", 52.dp, 16) }
        )
    }
}

@Composable
fun SettingsPage() {
    val ctx = LocalContext.current
    val nm = remember { ctx.getSystemService(android.app.NotificationManager::class.java) }
    var filterOn by remember {
        mutableStateOf(ctx.getSharedPreferences(PREFS, 0).getBoolean("filter_on", true))
    }
    var listenerOk by remember { mutableStateOf(false) }
    var smsOk by remember { mutableStateOf(false) }
    var sendOk by remember { mutableStateOf(false) }
    var contactsOk by remember { mutableStateOf(false) }
    var isDefaultSms by remember { mutableStateOf(false) }
    val roleManager = remember { ctx.getSystemService(RoleManager::class.java) }

    // Permission and role state used to be read once inside remember{}, so the page kept claiming
    // "missing" after the user granted it in the dialog it had just opened.
    fun refresh() {
        listenerOk = try {
            nm.isNotificationListenerAccessGranted(android.content.ComponentName(ctx, FilterService::class.java))
        } catch (t: Throwable) {
            false
        }
        smsOk = androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
        contactsOk = androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        sendOk = androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED
        isDefaultSms = roleManager?.isRoleAvailable(RoleManager.ROLE_SMS) == true &&
            roleManager.isRoleHeld(RoleManager.ROLE_SMS)
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(Unit) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

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
        Text("send SMS: ${if (sendOk) "granted" else "missing"}")
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
        Text("Features", style = MaterialTheme.typography.titleMedium)
        Text("SMS features (BuzzKill-style) are managed on the Features page.", style = MaterialTheme.typography.bodySmall)
        Divider(Modifier.padding(vertical = 6.dp))
        Text("Data", style = MaterialTheme.typography.titleMedium)
        Text("Everything stays on this phone. Your taps train the filter and are never uploaded.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun FeaturesPage() {
    val ctx = LocalContext.current
    var rules by remember { mutableStateOf(AddonRules.load(ctx)) }
    var editing by remember { mutableStateOf<AddonRule?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf<AddonRule?>(null) }
    var deleting by remember { mutableStateOf<AddonRule?>(null) }
    var showDecisions by remember { mutableStateOf(false) }
    var decisions by remember { mutableStateOf(emptyList<String>()) }
    fun persist() { AddonRules.save(ctx, rules) }
    fun addRule() {
        // max+1, not size: after a delete a size-based priority can tie with a live rule.
        editing = AddonRule(name = "New rule", priority = (rules.maxOfOrNull { it.priority } ?: -1) + 1)
        showEditor = true
    }
    // priority is the only ordering the engine reads, so a move rewrites the whole run as 0..n-1.
    fun move(from: Int, to: Int) {
        val ordered = rules.sortedBy { it.priority }
        if (to !in ordered.indices) return
        val moved = ordered.toMutableList().apply { add(to, removeAt(from)) }
        rules = moved.mapIndexed { i, r -> r.copy(priority = i) }.toMutableList()
        persist()
    }
    val ordered = rules.sortedBy { it.priority }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Features", style = MaterialTheme.typography.titleMedium)
        Text("BuzzKill-style SMS rules. Every matching rule runs, in priority order. Core filtering stays untouched.",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { addRule() }) { Text("Add rule") }
            TextButton(onClick = {
                val result = FeatureSelfTest.run(ctx)
                android.widget.Toast.makeText(ctx, result.lines().last(), android.widget.Toast.LENGTH_LONG).show()
            }) { Text("Self-test") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (rules.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("No rules yet", style = MaterialTheme.typography.titleMedium)
                        Text("A rule watches incoming SMS and runs its actions when the conditions match.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center)
                        Button(onClick = { addRule() }) { Text("Add rule") }
                    }
                }
            }
            item {
                // Collapsed by default: the rules stay the page's subject.
                Column {
                    TextButton(onClick = {
                        showDecisions = !showDecisions
                        if (showDecisions) decisions = AddonRules.recentDecisions(ctx)
                    }) { Text(if (showDecisions) "▾ Recent decisions" else "▸ Recent decisions") }
                    if (showDecisions) {
                        if (decisions.isEmpty()) {
                            Text("No decisions yet.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            // Time/sender are Latin inside a Persian body: BodyText picks the
                            // paragraph direction and isolates the Latin runs.
                            decisions.forEach {
                                BodyText(it, fontSize = androidx.compose.ui.unit.TextUnit.Unspecified,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = {
                                AddonRules.clearDecisions(ctx)
                                decisions = emptyList()
                            }) { Text("Clear") }
                        }
                    }
                }
            }
            itemsIndexed(ordered, key = { _, r -> r.id }) { index, rule ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.medium,
                    // A tonal step over true black was 1.05:1; the container needs an edge as well.
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(rule.name.ifBlank { rule.action }, style = MaterialTheme.typography.bodyLarge)
                            Switch(checked = rule.enabled, onCheckedChange = { v ->
                                rules = rules.map { if (it.id == rule.id) it.copy(enabled = v) else it }.toMutableList()
                                persist()
                            })
                        }
                        Text("IF  " + conditionSummary(rule), style = MaterialTheme.typography.bodySmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("THEN  " + actionSummary(rule, ctx), style = MaterialTheme.typography.bodySmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        // One cluster: SpaceBetween put the reorder arrows a screen away from the actions.
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { editing = rule; showEditor = true }) { Text("Edit") }
                            TextButton(onClick = { testing = rule }) { Text("Test") }
                            TextButton(onClick = { deleting = rule }) { Text("Delete") }
                            // Up/down beats a drag handle here: two taps, reachable, and no drag state to keep.
                            IconButton(onClick = { move(index, index - 1) }, enabled = index > 0) {
                                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up")
                            }
                            IconButton(onClick = { move(index, index + 1) }, enabled = index < ordered.lastIndex) {
                                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEditor && editing != null) {
        AddonRuleEditor(editing!!,
            onSave = { updated ->
                val next = rules.filterNot { it.id == updated.id }.toMutableList()
                next += updated
                rules = next
                persist()
                showEditor = false
            },
            onCancel = { showEditor = false })
    }

    // Destructive and persisted: one tap must not be enough.
    deleting?.let { rule ->
        AppAlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete rule?") },
            text = { Text("\u201c${isolate(rule.name.ifBlank { rule.action })}\u201d and its settings will be removed. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    rules = rules.filterNot { it.id == rule.id }.toMutableList()
                    persist()
                    deleting = null
                }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text("Delete")
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }

    // Dry-run: same matching code as the receive path, no actions, nothing written.
    testing?.let { rule ->
        var body by remember(rule.id) { mutableStateOf("") }
        var sender by remember(rule.id) { mutableStateOf("") }
        var spam by remember(rule.id) { mutableStateOf(false) }
        // Sender and spam feed the same conditions the receive path evaluates, so sender/spam/contact
        // rules are testable. Contacts are not resolved here: from-contact rules stay untestable.
        val reason = AddonRules.missReason(
            rule, SmsEvent(sender, body, 0L, 0, 0L, spam, false, null, 0)
        )
        AppAlertDialog(
            onDismissRequest = { testing = null },
            title = { Text("Test: ${isolate(rule.name.ifBlank { rule.action })}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = sender, onValueChange = { sender = it },
                        label = { Text("Sender") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = body, onValueChange = { body = it },
                        label = { Text("Message body") }, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Mark as spam")
                        Switch(checked = spam, onCheckedChange = { spam = it })
                    }
                    Text(
                        if (reason == null) "MATCH — the actions would run"
                        else "NO MATCH — ${reason} failed"
                    )
                }
            },
            confirmButton = { TextButton(onClick = { testing = null }) { Text("Close") } })
    }
}

private fun conditionSummary(rule: AddonRule): String {
    // Legacy rules are read as the tree they were migrated into, so the row shows real nesting.
    val tree = rule.conditions ?: AddonRules.toTree(rule)
    if (rule.conditions == null && AddonRules.leafCount(tree) == 0 && !rule.matchAny) {
        return "no conditions — never fires"
    }
    return AddonRules.summarize(tree)
}

private fun actionSummary(rule: AddonRule, ctx: android.content.Context): String {
    val base = AddonRules.actsOf(rule).joinToString(", ") { act ->
        AddonRules.summarizeAct(act) { pkg -> appLabel(ctx, pkg) }
    }
    return if (rule.cooldownSeconds > 0) "$base · max once per ${rule.cooldownSeconds}s" else base
}
