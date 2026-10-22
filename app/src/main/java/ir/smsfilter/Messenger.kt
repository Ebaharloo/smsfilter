package ir.smsfilter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.smsfilter.core.InboxPolicy
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

@Composable
fun ConversationsPage(
    onOpenThread: (Long, String) -> Unit,
    archivedMode: Boolean = false
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var threads by remember { mutableStateOf(listOf<Sms.Thread>()) }
    var names by remember { mutableStateOf(mapOf<String, String?>()) }
    var pinned by remember { mutableStateOf(setOf<Long>()) }
    var muted by remember { mutableStateOf(setOf<Long>()) }
    var archived by remember { mutableStateOf(setOf<Long>()) }
    var query by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }
    var selection by remember { mutableStateOf(setOf<Long>()) }

    LaunchedEffect(reload) {
        val loaded = withContext(Dispatchers.IO) { Sms.threads(ctx) }
        threads = loaded
        pinned = ThreadFlags.pinned(ctx)
        muted = ThreadFlags.muted(ctx)
        archived = withContext(Dispatchers.IO) {
            ThreadFlags.archived(ctx) + ThreadFlags.locallyArchived(ctx)
        }
        names = withContext(Dispatchers.IO) {
            loaded.take(150).associate { it.address to Contacts.nameFor(ctx, it.address) }
        }
    }

    // all list rules live in InboxPolicy so they are unit-tested without Android
    val shown = InboxPolicy.order(
        InboxPolicy.visible(threads.map { it.toRow() }, archived, archivedMode, query, names),
        pinned
    )

    fun archiveThreads(ids: Set<Long>) = scope.launch {
        withContext(Dispatchers.IO) { ids.forEach { ThreadFlags.setArchived(ctx, it, !archivedMode) } }
        selection = emptySet()
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
            OutlinedTextField(
                query, { query = it },
                placeholder = { Text("Search messages") },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }

        if (shown.isEmpty()) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (archivedMode) "No archived conversations" else "No conversations",
                    style = MaterialTheme.typography.titleMedium)
                Text(
                    if (archivedMode) "Swipe a conversation left to archive it."
                    else "Messages appear here once SMS arrives.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.id }) { row ->
                    val selected = row.id in selection
                    SwipeRow(
                        row = row,
                        name = names[row.address]?.takeIf { Contacts.digits(it) != Contacts.digits(row.address) },
                        pinned = row.id in pinned,
                        muted = row.id in muted,
                        selected = selected,
                        selectionMode = selection.isNotEmpty(),
                        onClick = {
                            if (selection.isNotEmpty()) {
                                selection = if (selected) selection - row.id else selection + row.id
                            } else {
                                Sms.markThreadRead(ctx, row.id)
                                onOpenThread(row.id, row.address)
                            }
                        },
                        onLongClick = {
                            selection = if (selected) selection - row.id else selection + row.id
                        },
                        onArchive = { archiveThreads(setOf(row.id)) },
                        onToggleRead = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    val cv = ContentValues()
                                    cv.put(Telephony.Sms.READ, if (row.unread > 0) 1 else 0)
                                    ctx.contentResolver.update(
                                        Telephony.Sms.CONTENT_URI, cv,
                                        "${Telephony.Sms.THREAD_ID}=?", arrayOf(row.id.toString())
                                    )
                                }
                                reload++
                            }
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun SwipeRow(
    row: ThreadRow,
    name: String?,
    pinned: Boolean,
    muted: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onArchive: () -> Unit,
    onToggleRead: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> onArchive()
                SwipeToDismissBoxValue.StartToEnd -> onToggleRead()
                else -> Unit
            }
            false   // the list reloads from the provider; never leave a hole in the UI
        }
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = !selectionMode,
        enableDismissFromEndToStart = !selectionMode,
        backgroundContent = {
            val label = if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) "Archive" else "Read"
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) { Text(label, style = MaterialTheme.typography.labelLarge) }
        }
    ) {
        ConversationRow(row, name, pinned, muted, selected, selectionMode, onClick, onLongClick)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    row: ThreadRow,
    name: String?,
    pinned: Boolean,
    muted: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.background
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) Checkbox(checked = selected, onCheckedChange = { onClick() })
        Avatar(row.address, 52.dp, 16)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pinned) Icon(
                    Icons.Filled.Star, contentDescription = "pinned",
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp)
                )
                Text(
                    name ?: row.address.ifBlank { "Unknown" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (row.unread > 0) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = if (pinned) 4.dp else 0.dp)
                )
                if (muted) Text("muted", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 6.dp))
                Text(
                    timeLabel(row.date),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (row.unread > 0) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                if (row.outgoing) Text("✓ ", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    row.snippet.replace('\n', ' '),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (row.unread > 0) {
                    Box(
                        Modifier.padding(start = 8.dp).size(22.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("${row.unread}", color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelMedium, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun ThreadPage(threadId: Long, address: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("drafts", 0) }
    var messages by remember { mutableStateOf(listOf<Sms.Message>()) }
    var draft by remember { mutableStateOf(prefs.getString(threadId.toString(), "").orEmpty()) }
    var name by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Sms.Message?>(null) }
    var details by remember { mutableStateOf<Sms.Message?>(null) }
    var pickSim by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(Blocked.isBlocked(ctx, address)) }
    var pinned by remember { mutableStateOf(ThreadFlags.isPinned(ctx, threadId)) }
    var muted by remember { mutableStateOf(ThreadFlags.isMuted(ctx, threadId)) }
    var findOpen by remember { mutableStateOf(false) }
    var find by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    fun reload() = scope.launch {
        messages = withContext(Dispatchers.IO) { Sms.messages(ctx, threadId) }
        if (name == null) name = withContext(Dispatchers.IO) {
            Contacts.nameFor(ctx, address)?.takeIf { Contacts.digits(it) != Contacts.digits(address) }
        }
        withContext(Dispatchers.IO) { Sms.markThreadRead(ctx, threadId) }
    }
    LaunchedEffect(threadId) { reload() }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
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

    val visible = if (find.isBlank()) messages else messages.filter { it.body.contains(find, true) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "back") }
            Avatar(address, 42.dp, 14)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(name ?: address.ifBlank { "Unknown" },
                    style = MaterialTheme.typography.titleMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                if (name != null || blocked || muted) Text(
                    listOfNotNull(
                        if (blocked) "blocked" else null,
                        if (muted) "muted" else null,
                        if (name != null) address else null
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { findOpen = !findOpen; if (!findOpen) find = "" }) {
                Icon(Icons.Filled.Search, contentDescription = "find in conversation")
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "menu")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Call") }, onClick = {
                        menuOpen = false
                        dial(ctx, address)
                    })
                    DropdownMenuItem(text = { Text(if (pinned) "Unpin" else "Pin to top") }, onClick = {
                        menuOpen = false
                        ThreadFlags.togglePin(ctx, threadId)
                        pinned = !pinned
                    })
                    DropdownMenuItem(text = { Text(if (muted) "Unmute" else "Mute") }, onClick = {
                        menuOpen = false
                        ThreadFlags.toggleMute(ctx, threadId)
                        muted = !muted
                    })
                    DropdownMenuItem(text = { Text(if (blocked) "Unblock sender" else "Block sender") }, onClick = {
                        menuOpen = false
                        if (blocked) Blocked.remove(ctx, address) else Blocked.add(ctx, address)
                        blocked = !blocked
                    })
                    DropdownMenuItem(text = { Text("Archive") }, onClick = {
                        menuOpen = false
                        scope.launch {
                            withContext(Dispatchers.IO) { ThreadFlags.setArchived(ctx, threadId, true) }
                            onBack()
                        }
                    })
                    DropdownMenuItem(text = { Text("Mark as unread") }, onClick = {
                        menuOpen = false
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val cv = ContentValues()
                                cv.put(Telephony.Sms.READ, 0)
                                ctx.contentResolver.update(
                                    Telephony.Sms.CONTENT_URI, cv,
                                    "${Telephony.Sms.THREAD_ID}=?", arrayOf(threadId.toString())
                                )
                            }
                            onBack()
                        }
                    })
                    DropdownMenuItem(text = { Text("Delete conversation") }, onClick = {
                        menuOpen = false
                        scope.launch {
                            withContext(Dispatchers.IO) { Sms.deleteThread(ctx, threadId) }
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

        LazyColumn(Modifier.weight(1f).padding(horizontal = 10.dp), state = listState) {
            itemsIndexed(visible) { i, m ->
                val prev = visible.getOrNull(i - 1)
                if (prev == null || dayLabel(prev.date) != dayLabel(m.date)) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text(dayLabel(m.date), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Bubble(
                    m = m,
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
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                draft, { draft = it },
                placeholder = { Text("Text message") },
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.weight(1f), maxLines = 5
            )
            IconButton(
                enabled = draft.isNotBlank(),
                onClick = {
                    val sims = Sms.sims(ctx)
                    if (sims.size > 1) pickSim = true else doSend(draft, sims.firstOrNull()?.first)
                },
                modifier = Modifier.size(48.dp).clip(CircleShape)
                    .background(
                        if (draft.isNotBlank()) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
            ) {
                Icon(
                    Icons.Filled.Send, contentDescription = "send",
                    tint = if (draft.isNotBlank()) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (pickSim) {
        val sims = Sms.sims(ctx)
        AlertDialog(
            onDismissRequest = { pickSim = false },
            title = { Text("Send with") },
            text = {
                Column {
                    sims.forEach { (sub, label) ->
                        TextButton(onClick = { pickSim = false; doSend(draft, sub) }) { Text(label) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickSim = false }) { Text("Cancel") } }
        )
    }

    selected?.let { m ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("Message") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick = { copy(ctx, m.body); selected = null }) { Text("Copy") }
                    TextButton(onClick = { draft = m.body; selected = null }) { Text("Forward") }
                    TextButton(onClick = { share(ctx, m.body); selected = null }) { Text("Share") }
                    TextButton(onClick = { details = m; selected = null }) { Text("Details") }
                    TextButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { Sms.deleteMessage(ctx, m.id) }
                            selected = null
                            reload()
                        }
                    }) { Text("Delete") }
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("Close") } }
        )
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
                }
            },
            confirmButton = { TextButton(onClick = { details = null }) { Text("Close") } }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    m: Sms.Message,
    onLongPress: () -> Unit,
    onUrl: (String) -> Unit,
    onCall: (String) -> Unit,
    onCopyOtp: (String) -> Unit
) {
    val mine = !m.incoming
    val bg = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val url = InboxPolicy.URL.find(m.body)?.value
    val phone = if (url == null) InboxPolicy.PHONE.find(m.body)?.value else null

    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = bg,
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (mine) 16.dp else 4.dp,
                bottomEnd = if (mine) 4.dp else 16.dp
            ),
            modifier = Modifier.widthIn(max = 330.dp)
                .combinedClickable(onClick = { url?.let(onUrl) }, onLongClick = onLongPress)
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    m.body,
                    color = fg,
                    textAlign = if (m.body.any { it in '\u0600'..'\u06FF' }) TextAlign.Right else TextAlign.Start,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (url != null) Text("open link",
                        style = MaterialTheme.typography.labelSmall,
                        color = fg.copy(alpha = 0.9f),
                        modifier = Modifier.padding(end = 8.dp))
                    if (phone != null) Text("call",
                        style = MaterialTheme.typography.labelSmall,
                        color = fg.copy(alpha = 0.9f),
                        modifier = Modifier.padding(end = 8.dp))
                    IncomingSms.otpOf(m.body)?.let {
                        Text("copy code",
                            style = MaterialTheme.typography.labelSmall,
                            color = fg.copy(alpha = 0.9f),
                            modifier = Modifier.padding(end = 8.dp))
                    }
                    Box(Modifier.weight(1f))
                    Text(
                        clock.format(Date(m.date)),
                        style = MaterialTheme.typography.labelSmall,
                        color = fg.copy(alpha = 0.7f)
                    )
                }
            }
        }
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
