package ir.smsfilter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ir.smsfilter.core.InboxPolicy
import ir.smsfilter.core.Tab
import ir.smsfilter.core.ThreadRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val clock = SimpleDateFormat("HH:mm", Locale.US)
private val dayFmt = SimpleDateFormat("dd MMM", Locale.US)
private val fullFmt = SimpleDateFormat("yyyy/MM/dd", Locale.US)
private val stampFmt = SimpleDateFormat("d MMM HH:mm", Locale.US)

/** MIUI badge / dot colours, independent of the accent */
private val BADGE_GREY = Color(0xFF4A4A4A)
private val FAB_AMBER = Color(0xFFFFB300)
private val SIM_BADGE = Color(0xFF2E7DFF)


private fun timeLabel(ts: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ts }
    return when {
        now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR) -> clock.format(Date(ts))
        now.get(Calendar.YEAR) == then.get(Calendar.YEAR) -> dayFmt.format(Date(ts))
        else -> fullFmt.format(Date(ts))
    }
}

private fun dayLabel(ts: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ts }
    val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    val yesterday = now.clone() as Calendar
    yesterday.add(Calendar.DAY_OF_YEAR, -1)
    return when {
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR) && sameYear -> "Today"
        yesterday.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR) && sameYear -> "Yesterday"
        else -> if (sameYear) dayFmt.format(Date(ts)) else fullFmt.format(Date(ts))
    }
}

private fun Sms.Thread.toRow() = ThreadRow(id, address, snippet, date, unread, outgoing)

/** cached inside Sms: every list row asked, and the raw probe cost a binder call each time */
@Composable
private fun multiSim(): Boolean {
    val ctx = LocalContext.current
    return remember { Sms.multiSim(ctx) }
}

@Composable
private fun SimBadge(simId: Int, size: Int = 16, fontSize: Int = 9) {
    val label = simLabel(simId) ?: return
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(SIM_BADGE),
        contentAlignment = Alignment.Center
    ) {
        // font padding sits below the baseline and pushes the digit off-centre in a small circle
        Text(
            label,
            color = Color.White,
            fontSize = fontSize.sp,
            lineHeight = fontSize.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
        )
    }
}

@Composable
private fun simLabel(simId: Int): String? {
    val ctx = LocalContext.current
    return remember(simId) { Sms.simLabel(ctx, simId) }
}

@Composable
fun ConversationsPage(
    onOpenThread: (Long, String) -> Unit,
    archivedMode: Boolean = false,
    learned: Learned? = null,
    store: Store? = null
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var threads by remember { mutableStateOf(listOf<Sms.Thread>()) }
    var names by remember { mutableStateOf(mapOf<String, String?>()) }
    var pinned by remember { mutableStateOf(setOf<Long>()) }
    var muted by remember { mutableStateOf(setOf<Long>()) }
    var archived by remember { mutableStateOf(setOf<Long>()) }
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(Tab.ALL) }
    var reload by remember { mutableStateOf(0) }
    var selection by remember { mutableStateOf(setOf<Long>()) }
    var newMessage by remember { mutableStateOf(false) }
    var simByThread by remember { mutableStateOf(mapOf<Long, Int>()) }
    var contactSuffixes by remember { mutableStateOf(setOf<String>()) }
    var spamIds by remember { mutableStateOf(setOf<Long>()) }
    val snack = remember { SnackbarHostState() }

    LaunchedEffect(reload) {
        val loaded = withContext(Dispatchers.IO) { Sms.threads(ctx) }
        threads = loaded
        pinned = ThreadFlags.pinned(ctx)
        muted = ThreadFlags.muted(ctx)
        archived = withContext(Dispatchers.IO) {
            ThreadFlags.archived(ctx) + ThreadFlags.locallyArchived(ctx)
        }
        simByThread = loaded.associate { it.id to it.simId }
        contactSuffixes = withContext(Dispatchers.IO) { Contacts.suffixes(ctx) }
        spamIds = ThreadFlags.spamThreads(ctx)
        names = withContext(Dispatchers.IO) {
            val bySuffix = Contacts.namesFor(ctx)
            loaded.associate { it.address to bySuffix[Contacts.suffixOf(it.address)] }
        }
    }

    // list rules stay in InboxPolicy so they are unit-tested without Android. Memoized: this used
    // to re-group, re-filter and re-count every conversation on each keystroke and reload.
    val ordered = remember(threads, archived, archivedMode, query, names, spamIds, pinned) {
        InboxPolicy.order(
            InboxPolicy.visible(threads.map { it.toRow() }, archived, archivedMode, query, names, spamIds),
            pinned
        )
    }
    val tabCounts = remember(ordered, contactSuffixes) { InboxPolicy.counts(ordered, contactSuffixes) }
    val shown = remember(ordered, tab, contactSuffixes) {
        ordered.filter { InboxPolicy.inTab(it, tab, InboxPolicy.suffix(it.address) in contactSuffixes) }
    }

    fun archiveThreads(ids: Set<Long>) = scope.launch {
        val target = !archivedMode
        withContext(Dispatchers.IO) { ThreadFlags.setArchived(ctx, ids, target) }
        selection = emptySet()
        reload++
        // Google Messages gives an undo for archive; without one a mis-swipe is permanent
        val result = snack.showSnackbar(
            message = if (target) "Conversation archived" else "Moved back to inbox",
            actionLabel = "UNDO",
            duration = SnackbarDuration.Short
        )
        if (result == SnackbarResult.ActionPerformed) {
            withContext(Dispatchers.IO) { ThreadFlags.setArchived(ctx, ids, !target) }
            reload++
        }
    }

    // heavy swipe: the user says this is an ad, so teach the model and get it out of the inbox
    fun filterThread(row: ThreadRow) = scope.launch {
        withContext(Dispatchers.IO) {
            AdActions.markAd(ctx, store, learned, row.address, row.snippet, row.date, row.id)
        }
        reload++
        snack.showSnackbar("Moved to Ads — the filter learned from it")
    }

    fun toggleRead(row: ThreadRow) = scope.launch {
        withContext(Dispatchers.IO) { Sms.setThreadRead(ctx, row.id, row.unread == 0) }
        reload++
    }

    fun deleteThreads(ids: Set<Long>) = scope.launch {
        withContext(Dispatchers.IO) { ids.forEach { Sms.deleteThread(ctx, it) } }
        selection = emptySet()
        reload++
    }

    fun markRead(ids: Set<Long>) = scope.launch {
        withContext(Dispatchers.IO) { ids.forEach { Sms.markThreadRead(ctx, it) } }
        selection = emptySet()
        reload++
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (selection.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { selection = emptySet() }) {
                        Icon(Icons.Filled.Close, contentDescription = "cancel")
                    }
                    Text("${selection.size} selected", style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { markRead(selection) }) { Text("Read") }
                    TextButton(onClick = { archiveThreads(selection) }) {
                        Text(if (archivedMode) "Unarchive" else "Archive")
                    }
                    TextButton(onClick = { deleteThreads(selection) }) { Text("Delete") }
                }
            } else {
                SearchPill(query, { query = it })
                // scrollable tab bar: full names, underline indicator, no chip styling
                ScrollableTabRow(
                    selectedTabIndex = Tab.entries.indexOf(tab),
                    edgePadding = 0.dp,
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Tab.entries.forEach { t ->
                        val n = tabCounts[t] ?: 0
                        Tab(
                            selected = tab == t,
                            onClick = { tab = t },
                            text = {
                                Text(
                                    "${t.label} $n",
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                }
            }

            if (shown.isEmpty()) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (archivedMode) "No archived conversations"
                        else if (tab == Tab.ALL) "No messages"
                        else "Nothing in ${tab.label}",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        if (archivedMode) "Swipe a conversation left to archive it."
                        else "New SMS will show up here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)) {
                    items(shown, key = { it.id }) { row ->
                        val selected = row.id in selection
                        SwipeRow(
                            rightLabel = "Read",
                            leftLabel = if (archivedMode) "Inbox" else "Archive",
                            heavyLabel = "Filter ad",
                            onRight = { toggleRead(row) },
                            onLeft = { archiveThreads(setOf(row.id)) },
                            onHeavy = { filterThread(row) },
                            enabled = selection.isEmpty(),
                            onTap = {
                                if (selection.isNotEmpty()) {
                                    selection = if (selected) selection - row.id else selection + row.id
                                } else {
                                    Sms.markThreadRead(ctx, row.id)
                                    onOpenThread(row.id, row.address)
                                }
                            },
                            onLongPress = {
                                selection = if (selected) selection - row.id else selection + row.id
                            }
                        ) {
                            ConversationRow(
                                row = row,
                                name = names[row.address]?.takeIf {
                                    Contacts.digits(it) != Contacts.digits(row.address)
                                },
                                pinned = row.id in pinned,
                                muted = row.id in muted,
                                selected = selected,
                                selectionMode = selection.isNotEmpty(),
                                simId = simByThread[row.id] ?: -1
                            )
                        }
                    }
                }
            }
        }

        SnackbarHost(
            snack,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp)
        )

        if (!archivedMode && selection.isEmpty()) {
            FloatingActionButton(
                onClick = { newMessage = true },
                containerColor = FAB_AMBER,
                contentColor = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
            ) { Icon(Icons.Filled.Add, contentDescription = "new message") }
        }
    }

    if (newMessage) {
        RecipientPicker(
            onPick = { number -> newMessage = false; onOpenThread(-1L, number) },
            onDismiss = { newMessage = false }
        )
    }
}

/** MIUI-style SIM chooser: a sheet with each SIM, its slot, and the current pick ticked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimPickerSheet(
    selected: Int?,
    title: String = "Send with",
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val sims = remember { Sms.sims(ctx) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            if (sims.isEmpty()) {
                Text("No SIM information available", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            sims.forEach { (sub, label) ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(sub) }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SimBadge(sub, 22, 11)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(label, fontSize = 16.sp)
                        Text(
                            "subscription id $sub",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    RadioButton(selected = sub == selected, onClick = { onPick(sub) })
                }
            }
        }
    }
}

/** Xiaomi-style recipient picker: search the address book, or type a number. */
@Composable
private fun RecipientPicker(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var people by remember { mutableStateOf(listOf<Contacts.Person>()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(false) }
    var number by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        people = withContext(Dispatchers.IO) { Contacts.all(ctx) }
        loading = false
        // no contacts permission or empty address book: typing is the only way in
        if (people.isEmpty()) manual = true
    }

    val shown = remember(people, query) {
        if (query.isBlank()) people.take(200)
        else people.filter {
            it.name.contains(query, true) || Contacts.digits(it.number).contains(Contacts.digits(query))
        }.take(200)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // Dialog windows get zero insets, so clear the gesture bar by hand
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(bottom = 24.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "close")
                    }
                    Text("New message", fontSize = 20.sp, fontWeight = FontWeight.Medium)
                }

                if (manual) {
                    OutlinedTextField(
                        number, { number = it },
                        label = { Text("phone number") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    )
                    TextButton(
                        enabled = Contacts.digits(number).length >= 4,
                        onClick = { onPick(number) },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    ) { Text("Start conversation") }
                } else {
                    SearchPill(query, { query = it }, "Search contacts")
                    if (loading) {
                        Text("Loading contacts…", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(20.dp))
                    }
                    LazyColumn(Modifier.weight(1f)) {
                        items(shown, key = { it.number + it.name }) { p ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onPick(p.number) }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Avatar(p.number, 44.dp, 15)
                                Column(Modifier.padding(start = 14.dp)) {
                                    Text(p.name, fontSize = 16.sp, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                    if (p.name != p.number) Text(p.number, fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    if (!loading && shown.isEmpty()) {
                        Text("No contact matches.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(20.dp))
                    }
                }

                TextButton(
                    onClick = { manual = !manual },
                    modifier = Modifier.padding(start = 8.dp, bottom = 12.dp)
                ) { Text(if (manual) "Choose from contacts" else "Enter a number instead") }
            }
        }
    }
}

/** MIUI search field: filled surface, small radius, no border */
@Composable
internal fun SearchPill(
    query: String,
    onChange: (String) -> Unit,
    placeholder: String = "Search messages"
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).height(44.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp)) {
            Icon(Icons.Filled.Search, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(19.dp))
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp),
                modifier = Modifier.weight(1f).padding(start = 10.dp),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text(placeholder, fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    inner()
                }
            )
            if (query.isNotEmpty()) IconButton(onClick = { onChange("") }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "clear",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** MIUI row: 56dp avatar, 16sp title, 15sp preview, dot + 18dp badge, no divider */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    row: ThreadRow,
    name: String?,
    pinned: Boolean,
    muted: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    simId: Int
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 92.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.background
            )
            .padding(start = 16.dp, end = 20.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) Checkbox(checked = selected, onCheckedChange = null)
        Avatar(row.address, 40.dp, 14, neutral = true)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pinned) Icon(
                    Icons.Filled.Star, contentDescription = "pinned",
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp)
                )
                Text(
                    name ?: remember(row.address) { Contacts.displayName(row.address) },
                    fontSize = 17.sp,
                    fontWeight = if (row.unread > 0) FontWeight.SemiBold else FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = if (pinned) 6.dp else 0.dp)
                )
                if (muted) Text("muted", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp))
            }
            BodyText(
                remember(row.snippet) { row.snippet.replace('\n', ' ').trim() },
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp)
            )
            // which SIM the newest message of this thread arrived on
            if (multiSim() && simId >= 0) {
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    SimBadge(simId)
                }
            }
        }
        // time above the badge, both flush right: the preview never runs under them
        Column(
            Modifier.align(Alignment.Top).padding(start = 10.dp, top = 2.dp),
            horizontalAlignment = Alignment.End
        ) {
            Text(
                remember(row.date) { timeLabel(row.date) },
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (row.unread > 0) {
                Box(
                    Modifier.padding(top = 6.dp).height(18.dp).widthIn(min = 18.dp)
                        .clip(RoundedCornerShape(5.dp)).background(BADGE_GREY),
                    contentAlignment = Alignment.Center
                ) {
                    Text("${row.unread}", color = Color.White, fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ThreadPage(threadId: Long, address: String, learned: Learned? = null, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("drafts", 0) }
    var resolvedId by remember { mutableStateOf(threadId) }
    var messages by remember { mutableStateOf(listOf<Sms.Message>()) }
    var draft by remember { mutableStateOf(prefs.getString(threadId.toString(), "").orEmpty()) }
    var name by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Sms.Message?>(null) }
    var details by remember { mutableStateOf<Sms.Message?>(null) }
    var blocked by remember { mutableStateOf(Blocked.isBlocked(ctx, address)) }
    var pinned by remember { mutableStateOf(ThreadFlags.isPinned(ctx, resolvedId)) }
    var muted by remember { mutableStateOf(ThreadFlags.isMuted(ctx, resolvedId)) }
    var findOpen by remember { mutableStateOf(false) }
    var find by remember { mutableStateOf("") }
    var pins by remember { mutableStateOf(MessagePins.list(ctx, resolvedId)) }
    // which SIM replies go out on; defaults to the SIM the last incoming message arrived on
    var sendSim by remember { mutableStateOf<Int?>(null) }
    var chooseSim by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun reload() = scope.launch {
        val id = withContext(Dispatchers.IO) {
            if (threadId > 0) threadId else Sms.threadIdFor(ctx, address) ?: 0L
        }
        resolvedId = id
        messages = withContext(Dispatchers.IO) { Sms.messages(ctx, id) }
        if (name == null) name = withContext(Dispatchers.IO) {
            Contacts.nameFor(ctx, address)?.takeIf { Contacts.digits(it) != Contacts.digits(address) }
        }
        if (sendSim == null) {
            val incomingSim = messages.lastOrNull { it.incoming }?.simId?.takeIf { it >= 0 }
            sendSim = incomingSim ?: withContext(Dispatchers.IO) { Sms.sims(ctx).firstOrNull()?.first }
        }
        if (id > 0) {
            withContext(Dispatchers.IO) { Sms.markThreadRead(ctx, id) }
            MessagePins.prune(ctx, id, messages.map { it.id }.toSet())
            pins = MessagePins.list(ctx, id)
            pinned = ThreadFlags.isPinned(ctx, id)
            muted = ThreadFlags.isMuted(ctx, id)
        }
    }
    LaunchedEffect(threadId) { reload() }
    LaunchedEffect(messages.size) {
        // jump straight to the newest bubble: animating from index 0 looked like the app scrolling by itself
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }
    LaunchedEffect(draft) {
        delay(400)
        prefs.edit().putString(threadId.toString(), draft).apply()
    }

    fun doSend(text: String, sub: Int?) {
        if (text.isBlank()) return
        draft = ""
        prefs.edit().remove(threadId.toString()).apply()
        scope.launch {
            withContext(Dispatchers.IO) { Sms.send(ctx, address, text, sub) }
            reload()
        }
    }

    val visible = remember(messages, find) {
        if (find.isBlank()) messages else messages.filter { it.body.contains(find, true) }
    }
    // day chips, computed once per conversation instead of twice per visible row
    val dayOf = remember(visible) { visible.map { dayLabel(it.date) } }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "back") }
            Avatar(address, 38.dp, 13, neutral = true)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(name ?: Contacts.displayName(address),
                    fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        if (blocked) "blocked" else null,
                        if (muted) "muted" else null,
                        // do not print the same string twice when the display name is the number
                        address.takeIf {
                            it.isNotBlank() && it != name && Contacts.digits(it) != Contacts.digits(name.orEmpty())
                        }
                    ).joinToString(" · "),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            if (multiSim() && (sendSim ?: -1) >= 0) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 4.dp)) {
                    SimBadge(sendSim ?: -1, 16, 10)
                    Text("SIM ${simLabel(sendSim ?: -1) ?: ""}", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp))
                }
            }
            IconButton(onClick = { findOpen = !findOpen; if (!findOpen) find = "" }) {
                Icon(Icons.Filled.Search, contentDescription = "find in conversation")
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "menu")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Call") },
                        leadingIcon = { Icon(Icons.Filled.Phone, null, modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        onClick = { menuOpen = false; dial(ctx, address) })
                    DropdownMenuItem(text = { Text(if (pinned) "Unpin" else "Pin to top") },
                        leadingIcon = { Icon(Icons.Filled.Star, null, modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        onClick = {
                        menuOpen = false
                        if (resolvedId > 0) ThreadFlags.togglePin(ctx, resolvedId)
                        pinned = !pinned
                    })
                    DropdownMenuItem(text = { Text(if (muted) "Unmute" else "Mute") },
                        leadingIcon = { Icon(Icons.Filled.Notifications, null, modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        onClick = {
                        menuOpen = false
                        if (resolvedId > 0) ThreadFlags.toggleMute(ctx, resolvedId)
                        muted = !muted
                    })
                    DropdownMenuItem(text = { Text("This is advertising") },
                        leadingIcon = { Icon(Icons.Filled.Warning, null, modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        onClick = {
                            menuOpen = false
                            val sample = messages.lastOrNull { it.incoming }?.body.orEmpty()
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    AdActions.markAd(ctx, null, learned, address, sample, threadId = resolvedId)
                                }
                                onBack()
                            }
                        })
                    DropdownMenuItem(text = { Text(if (blocked) "Unblock sender" else "Block sender") },
                        leadingIcon = { Icon(Icons.Filled.Warning, null, modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        onClick = {
                        menuOpen = false
                        if (blocked) Blocked.remove(ctx, address) else Blocked.add(ctx, address)
                        blocked = !blocked
                    })
                    DropdownMenuItem(text = { Text("Archive") },
                        leadingIcon = { Icon(Icons.Filled.List, null, modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        onClick = {
                        menuOpen = false
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                if (resolvedId > 0) ThreadFlags.setArchived(ctx, resolvedId, true)
                            }
                            onBack()
                        }
                    })
                    DropdownMenuItem(text = { Text("Mark as unread") },
                        leadingIcon = { Icon(Icons.Filled.MailOutline, null, modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        onClick = {
                        menuOpen = false
                        scope.launch {
                            withContext(Dispatchers.IO) { Sms.setThreadRead(ctx, resolvedId, false) }
                            onBack()
                        }
                    })
                    DropdownMenuItem(text = { Text("Delete conversation") },
                        leadingIcon = { Icon(Icons.Filled.Delete, null, modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                        menuOpen = false
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                if (resolvedId > 0) Sms.deleteThread(ctx, resolvedId)
                            }
                            onBack()
                        }
                    })
                }
            }
        }

        if (findOpen) {
            OutlinedTextField(
                find, { find = it },
                placeholder = { Text("Find in this conversation") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            )
            if (find.isNotBlank()) Text(
                "${visible.size} of ${messages.size} messages",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
        }

        val bannerId = InboxPolicy.pinnedBanner(pins, messages.map { it.id })
        val banner = messages.firstOrNull { it.id == bannerId }
        if (banner != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp)
                    .combinedClickable(onClick = {
                        val idx = visible.indexOfFirst { it.id == banner.id }
                        if (idx >= 0) scope.launch { listState.animateScrollToItem(idx) }
                    }, onLongClick = {
                        if (resolvedId > 0) MessagePins.toggle(ctx, resolvedId, banner.id)
                        pins = MessagePins.list(ctx, resolvedId)
                    })
            ) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Star, contentDescription = "pinned message",
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                    BodyText(banner.body.replace('\n', ' '), fontSize = 12.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 6.dp))
                    if (pins.size > 1) Text("${pins.size} pinned", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), state = listState) {
            itemsIndexed(visible) { i, m ->
                val prev = visible.getOrNull(i - 1)
                if (i == 0 || dayOf[i - 1] != dayOf[i]) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center) {
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp)) {
                            Text(dayOf[i], fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
                        }
                    }
                }
                val grouped = prev != null && prev.incoming == m.incoming && m.date - prev.date < 3 * 60_000
                Bubble(
                    m = m,
                    grouped = grouped,
                    onLongPress = { selected = m },
                    onUrl = { url -> openUrl(ctx, url) },
                    onCall = { number -> dial(ctx, number) },
                    onCopyOtp = { code -> copy(ctx, code) }
                )
            }
        }

        IncomingSms.otpOf(messages.lastOrNull()?.body.orEmpty())?.let { code ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                AssistChip(onClick = { copy(ctx, code) }, label = { Text("Copy code $code") })
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f)
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp),
                        maxLines = 5,
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            if (draft.isEmpty()) Text("Text message", fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            inner()
                        }
                    )
                    if (multiSim()) {
                        Box(
                            Modifier.padding(start = 8.dp).size(24.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.background)
                                .combinedClickable(onClick = { chooseSim = true }, onLongClick = {}),
                            contentAlignment = Alignment.Center
                        ) {
                            if ((sendSim ?: -1) >= 0) SimBadge(sendSim ?: -1, 16, 10)
                            else Text("SIM", fontSize = 8.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(
                    if (draft.isNotBlank()) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                ).combinedClickable(onClick = {
                    if (draft.isNotBlank()) doSend(draft, sendSim)
                }, onLongClick = {}),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Send, contentDescription = "send",
                    tint = if (draft.isNotBlank()) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }

    if (chooseSim) {
        SimPickerSheet(
            selected = sendSim,
            onPick = { sendSim = it; chooseSim = false },
            onDismiss = { chooseSim = false }
        )
    }

    if (selected != null) {
        val m = selected!!
        ModalBottomSheet(
            onDismissRequest = { selected = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                BodyText(
                    m.body.replace('\n', ' '),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
                Divider(color = MaterialTheme.colorScheme.surfaceVariant)
                SheetAction("Copy") { copy(ctx, m.body); selected = null }
                SheetAction("This is an ad") {
                    // teach the model, keep our copy, remove the ad from the inbox
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            AdActions.markAd(ctx, Store.get(ctx), learned, address, m.body, m.date, resolvedId)
                            Sms.deleteMessage(ctx, m.id)
                        }
                        selected = null
                        onBack()
                    }
                }
                SheetAction(
                    if (m.id in pins) "Unpin message" else "Pin message",
                    icon = { Icon(Icons.Filled.Star, null, modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary) }
                ) {
                    if (resolvedId > 0) MessagePins.toggle(ctx, resolvedId, m.id)
                    pins = MessagePins.list(ctx, resolvedId)
                    selected = null
                }
                SheetAction("Forward", icon = {
                    Icon(Icons.Filled.Send, null, modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }) { draft = m.body; selected = null }
                SheetAction("Share", icon = {
                    Icon(Icons.Filled.Share, null, modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }) { share(ctx, m.body); selected = null }
                SheetAction("Details", icon = {
                    Icon(Icons.Filled.Info, null, modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }) { details = m; selected = null }
                SheetAction("Call sender", icon = {
                    Icon(Icons.Filled.Phone, null, modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }) { dial(ctx, address); selected = null }
                SheetAction("Delete", danger = true, icon = {
                    Icon(Icons.Filled.Delete, null, modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.error)
                }) {
                    scope.launch {
                        withContext(Dispatchers.IO) { Sms.deleteMessage(ctx, m.id) }
                        selected = null
                        reload()
                    }
                }
            }
        }
    }

    details?.let { m ->
        AlertDialog(
            onDismissRequest = { details = null },
            title = { Text("Details") },
            text = {
                Column {
                    Text("address: ${m.address}")
                    Text("time: ${fullFmt.format(Date(m.date))} ${clock.format(Date(m.date))}")
                    Text("direction: ${if (m.incoming) "received" else "sent"}")
                    Text("row id: ${m.id}")
                    Text("thread: ${m.threadId}")
                    Text("sim: ${if (m.simId >= 0) m.simId.toString() else "unknown"}")
                }
            },
            confirmButton = { TextButton(onClick = { details = null }) { Text("Close") } }
        )
    }
}

/** MIUI bubble: no tail, 18dp radius, timestamp above the bubble instead of inside it */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    m: Sms.Message,
    grouped: Boolean,
    onLongPress: () -> Unit,
    onUrl: (String) -> Unit,
    onCall: (String) -> Unit,
    onCopyOtp: (String) -> Unit
) {
    val mine = !m.incoming
    val bg = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    // regex work per bubble, once per message instead of once per scroll frame
    val url = remember(m.body) { InboxPolicy.URL.find(m.body)?.value }
    val phone = remember(m.body) { if (url == null) InboxPolicy.PHONE.find(m.body)?.value else null }
    val otp = remember(m.body) { IncomingSms.otpOf(m.body) }

    Column(Modifier.fillMaxWidth().padding(top = if (grouped) 2.dp else 8.dp)) {
        Row(Modifier.fillMaxWidth(),
            horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                if (multiSim()) SimBadge(m.simId)
                Text(stampFmt.format(Date(m.date)), fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth(),
            horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
            Surface(
                color = bg,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.widthIn(max = 300.dp)
                    .combinedClickable(onClick = { url?.let(onUrl) }, onLongClick = onLongPress)
            ) {
                Column(Modifier.padding(horizontal = 19.dp, vertical = 14.dp)) {
                    BodyText(
                        m.body,
                        fontSize = 17.sp,
                        color = fg,
                        lineHeight = 19.sp,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (url != null || phone != null || otp != null) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 6.dp)) {
                            if (url != null) Text("open link", fontSize = 11.sp,
                                color = fg.copy(alpha = 0.85f),
                                modifier = Modifier.padding(end = 10.dp))
                            if (phone != null) Text("call", fontSize = 11.sp,
                                color = fg.copy(alpha = 0.85f),
                                modifier = Modifier.padding(end = 10.dp))
                            if (otp != null) Text("copy code", fontSize = 11.sp,
                                color = fg.copy(alpha = 0.85f))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SheetAction(
    label: String,
    icon: (@Composable () -> Unit)? = null,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon?.invoke() }
        Text(
            label,
            fontSize = 16.sp,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}

private fun copy(ctx: Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("text", text))
    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
}

private fun share(ctx: Context, text: String) {
    try {
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }, "Share"))
    } catch (t: Throwable) {
    }
}

private fun openUrl(ctx: Context, url: String) {
    try {
        val full = if (url.startsWith("http")) url else "https://$url"
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(full)))
    } catch (t: Throwable) {
    }
}

private fun dial(ctx: Context, number: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Contacts.digits(number)}")))
    } catch (t: Throwable) {
    }
}
