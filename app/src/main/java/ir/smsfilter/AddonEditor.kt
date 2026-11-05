package ir.smsfilter

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ir.smsfilter.addon.Act
import ir.smsfilter.addon.AddonRule
import ir.smsfilter.addon.AddonRules
import ir.smsfilter.addon.Cond

// Package labels are a binder call per app; a rule list redraws on every scroll.
private val appLabels = mutableMapOf<String, String>()

internal fun appLabel(ctx: Context, pkg: String): String = appLabels.getOrPut(pkg) {
    runCatching {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}

/**
 * The rule builder: IF is a nested condition tree, THEN is one card per action with its own
 * parameters. Full-screen dialog, because a rule is a sentence and the dialog was a chip wall.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddonRuleEditor(rule: AddonRule, onSave: (AddonRule) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    // A legacy rule is materialised on open: the user edits the tree the matcher will actually run.
    var root by remember(rule.id) {
        val tree = rule.conditions ?: AddonRules.toTree(rule)
        mutableStateOf<Cond>(if (tree is Cond.Group) tree else Cond.Group(items = listOf(tree)))
    }
    var acts by remember(rule.id) { mutableStateOf(AddonRules.actsOf(rule)) }
    var name by remember(rule.id) { mutableStateOf(rule.name) }
    var enabled by remember(rule.id) { mutableStateOf(rule.enabled) }
    var cooldown by remember(rule.id) {
        mutableStateOf(if (rule.cooldownSeconds > 0) rule.cooldownSeconds.toString() else "")
    }
    var matchAny by remember(rule.id) { mutableStateOf(rule.matchAny) }
    var addAction by remember { mutableStateOf(false) }
    // Which action is picking a package, and from which list ("email" | "launch" | "send").
    var pick by remember { mutableStateOf<Pair<Act, String>?>(null) }

    fun appsFor(intent: Intent): List<Pair<String, String>> =
        ctx.packageManager.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .map { it to appLabel(ctx, it) }
            .sortedBy { it.second.lowercase() }
    val emailApps = remember {
        appsFor(Intent(Intent.ACTION_SEND).apply { type = "message/rfc822" })
    }
    val launchApps = remember {
        appsFor(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
    }
    val sendApps = remember {
        appsFor(Intent(Intent.ACTION_SEND).apply { type = "text/plain" })
    }

    fun editAct(target: Act, f: (Act) -> Act) {
        acts = acts.map { if (it === target) f(it) else it }
    }

    // An unusable pattern is dead at runtime, so Save refuses it here too, not just in the leaf.
    fun badPatterns(c: Cond): List<String> = when (c) {
        is Cond.Group -> c.items.flatMap { badPatterns(it) }
        is Cond.Keyword -> if (c.match.equals("regex", true) && !AddonRules.regexUsable(c.text)) listOf(c.text) else emptyList()
        else -> emptyList()
    }
    val badRegex = badPatterns(root)
    val problem = when {
        acts.isEmpty() -> "Add at least one action."
        badRegex.isNotEmpty() -> "Regex never matches: ${badRegex.joinToString(" | ")}"
        AddonRules.leafCount(root) == 0 && !matchAny -> "Add a condition, or turn on \"Any SMS\"."
        else -> null
    }

    fun save() {
        val first = acts.first()
        val mail = acts.firstOrNull { it.type == "email" }?.params
        fun flat(key: String) = first.params[key]?.takeIf { it.isNotBlank() }
        onSave(
            rule.copy(
                name = name.trim(),
                conditions = root,
                actionList = acts,
                // The flat fields stay in sync for an older build reading the same prefs.
                action = first.type,
                actions = acts.map { it.type },
                actionParam = flat("number") ?: flat("text") ?: flat("to") ?: flat("package") ?: "",
                emailTo = mail?.get("to").orEmpty(),
                emailSubject = mail?.get("subject").orEmpty(),
                emailBody = mail?.get("body").orEmpty(),
                emailAppPackage = mail?.get("app").orEmpty(),
                cooldownSeconds = AddonRules.clampCooldown(cooldown),
                matchAny = matchAny,
                enabled = enabled
            )
        )
    }

    // Back closes the editor, not the page behind it (the page's own BackHandler would win otherwise).
    BackHandler { onCancel() }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // Dialog windows get zero insets, so the bars are cleared by hand.
            Scaffold(
                modifier = Modifier.systemBarsPadding(),
                topBar = {
                    TopAppBar(
                        title = { Text("Edit rule") },
                        navigationIcon = {
                            IconButton(onClick = onCancel) {
                                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    )
                },
                bottomBar = {
                    Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column {
                            problem?.let {
                                Text(
                                    it,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)
                                )
                            }
                            Row(
                                // A dialog window reports zero insets, so the gesture bar is cleared by hand.
                                Modifier.fillMaxWidth().navigationBarsPadding()
                                    .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 40.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
                                Button(onClick = { save() }, enabled = problem == null, modifier = Modifier.weight(1f)) {
                                    Text("Save")
                                }
                            }
                        }
                    }
                }
            ) { pad ->
                Column(
                    Modifier.fillMaxSize().padding(pad).imePadding()
                        .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Rule name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text("WHEN an SMS arrives", style = MaterialTheme.typography.titleSmall)
                    Text("IF", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    CondCard(root, 1, { f -> root = f(root) }, onRemove = null)

                    if (AddonRules.leafCount(root) == 0) {
                        Text(
                            "No conditions yet — this rule matches nothing.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Any SMS (match everything)", Modifier.weight(1f))
                            Switch(checked = matchAny, onCheckedChange = { matchAny = it })
                        }
                    }

                    Text("THEN", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    acts.forEachIndexed { index, act ->
                        ActCard(
                            act = act,
                            index = index,
                            count = acts.size,
                            onEdit = { f -> editAct(act, f) },
                            onRemove = { acts = acts.filterIndexed { i, _ -> i != index } },
                            onMove = { delta ->
                                // identity, not equality: two identical `forward` cards would tie on ==
                                val from = acts.indexOfFirst { it === act }
                                val to = from + delta
                                if (from >= 0 && to in acts.indices) {
                                    acts = acts.toMutableList().apply { add(to, removeAt(from)) }
                                }
                            },
                            onPick = { kind -> pick = act to kind }
                        )
                    }
                    OutlinedButton(onClick = { addAction = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Add action")
                    }

                    OutlinedTextField(
                        value = cooldown,
                        onValueChange = { cooldown = it.filter(Char::isDigit).take(9) },
                        label = { Text("Cooldown seconds") },
                        supportingText = { Text("0 = every time, max ${AddonRules.MAX_COOLDOWN} s (24 h)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Enabled", Modifier.weight(1f))
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }
                }
            }
        }
    }

    if (addAction) {
        TypeSheet(
            title = "Add action",
            types = ACTION_TYPES,
            onDismiss = { addAction = false },
            onPick = { type ->
                addAction = false
                acts = acts + Act(type)
            }
        )
    }

    pick?.let { (target, kind) ->
        val (title, options, key) = when (kind) {
            "email" -> Triple("Email app", emailApps, "app")
            "launch" -> Triple("Open with", launchApps, "package")
            else -> Triple("Share with", sendApps, "package")
        }
        AppAlertDialog(
            onDismissRequest = { pick = null },
            title = { Text(title) },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    if (kind != "email") {
                        item {
                            ListItem(
                                headlineContent = { Text("No app — show the chooser") },
                                modifier = Modifier.clickable {
                                    editAct(target) { it.copy(params = it.params - key) }
                                    pick = null
                                }
                            )
                        }
                    }
                    items(options, key = { it.first }) { (pkg, label) ->
                        ListItem(
                            headlineContent = { Text(label) },
                            supportingContent = { Text(pkg, style = MaterialTheme.typography.bodySmall) },
                            trailingContent = if (target.params[key] == pkg) {
                                { Icon(Icons.Filled.Check, contentDescription = null) }
                            } else null,
                            modifier = Modifier.clickable {
                                editAct(target) { it.copy(params = it.params + (key to pkg)) }
                                pick = null
                            }
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pick = null }) { Text("Cancel") } }
        )
    }
}

// ---------- IF: the condition tree ----------

@Composable
private fun CondCard(
    c: Cond,
    depth: Int,
    onReplace: ((Cond) -> Cond) -> Unit,
    onRemove: (() -> Unit)?
) {
    if (c is Cond.Group) GroupCard(c, depth, onReplace, onRemove) else LeafCard(c, onReplace, onRemove)
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun GroupCard(
    g: Cond.Group,
    depth: Int,
    onReplace: ((Cond) -> Cond) -> Unit,
    onRemove: (() -> Unit)?
) {
    var adding by remember { mutableStateOf(false) }
    val canAdd = depth < AddonRules.MAX_TREE_DEPTH && AddonRules.nodeCount(g) < AddonRules.MAX_TREE_NODES

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = g.op.equals("AND", true),
                    onClick = { onReplace { (it as Cond.Group).copy(op = "AND") } },
                    label = { Text("AND") }
                )
                FilterChip(
                    selected = g.op.equals("OR", true),
                    onClick = { onReplace { (it as Cond.Group).copy(op = "OR") } },
                    label = { Text("OR") }
                )
                Spacer(Modifier.weight(1f))
                NotControl(g.not) { v -> onReplace { (it as Cond.Group).copy(not = v) } }
                onRemove?.let { RemoveButton(it) }
            }
            g.items.forEachIndexed { i, child ->
                CondCard(
                    child,
                    depth + 1,
                    onReplace = { f ->
                        onReplace { node ->
                            (node as Cond.Group).copy(
                                items = node.items.mapIndexed { j, x -> if (j == i) f(x) else x }
                            )
                        }
                    },
                    onRemove = {
                        onReplace { node ->
                            (node as Cond.Group).copy(items = node.items.filterIndexed { j, _ -> j != i })
                        }
                    }
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { adding = true }, enabled = canAdd) { Text("Add condition") }
                TextButton(
                    onClick = { onReplace { (it as Cond.Group).copy(items = it.items + Cond.Group()) } },
                    enabled = canAdd
                ) { Text("Add group") }
            }
            if (!canAdd) {
                Text(
                    "Nesting is capped at ${AddonRules.MAX_TREE_DEPTH} levels.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (adding) {
        TypeSheet(
            title = "Add condition",
            types = CONDITION_TYPES,
            onDismiss = { adding = false },
            onPick = { type ->
                adding = false
                onReplace { (it as Cond.Group).copy(items = it.items + defaultCond(type)) }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LeafCard(c: Cond, onReplace: ((Cond) -> Cond) -> Unit, onRemove: (() -> Unit)?) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    condTitle(c),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                NotControl(c.not) { v -> onReplace { withNot(it, v) } }
                onRemove?.let { RemoveButton(it) }
            }
            when (c) {
                is Cond.Sender -> {
                    OutlinedTextField(
                        value = c.value,
                        onValueChange = { v -> onReplace { (it as Cond.Sender).copy(value = v) } },
                        label = { Text("Sender") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !c.mode.equals("exact", true),
                            onClick = { onReplace { (it as Cond.Sender).copy(mode = "contains") } },
                            label = { Text("contains") }
                        )
                        FilterChip(
                            selected = c.mode.equals("exact", true),
                            onClick = { onReplace { (it as Cond.Sender).copy(mode = "exact") } },
                            label = { Text("exact") }
                        )
                    }
                }
                is Cond.Keyword -> {
                    val bad = c.match.equals("regex", true) && !AddonRules.regexUsable(c.text)
                    OutlinedTextField(
                        value = c.text,
                        onValueChange = { v -> onReplace { (it as Cond.Keyword).copy(text = v) } },
                        label = { Text(if (c.match.equals("regex", true)) "Pattern" else "Text") },
                        isError = bad,
                        supportingText = if (bad) {
                            { Text("Invalid or too complex — this condition never matches.") }
                        } else null,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MATCH_MODES.forEach { m ->
                            FilterChip(
                                selected = c.match.equals(m, true),
                                onClick = { onReplace { (it as Cond.Keyword).copy(match = m) } },
                                label = { Text(m) }
                            )
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SCOPES.forEach { (scope, label) ->
                            FilterChip(
                                selected = c.scope.equals(scope, true),
                                onClick = { onReplace { (it as Cond.Keyword).copy(scope = scope) } },
                                label = { Text(label) }
                            )
                        }
                    }
                }
                is Cond.Length -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = if (c.min > 0) c.min.toString() else "",
                        onValueChange = { v ->
                            onReplace { (it as Cond.Length).copy(min = v.filter(Char::isDigit).take(6).toIntOrNull() ?: 0) }
                        },
                        label = { Text("Min") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = if (c.max > 0) c.max.toString() else "",
                        onValueChange = { v ->
                            onReplace { (it as Cond.Length).copy(max = v.filter(Char::isDigit).take(6).toIntOrNull() ?: 0) }
                        },
                        label = { Text("Max") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                is Cond.Time -> {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DAY_CHIPS.forEach { (label, day) ->
                            FilterChip(
                                selected = day in c.days,
                                onClick = {
                                    onReplace {
                                        (it as Cond.Time).copy(days = if (day in c.days) c.days - day else c.days + day)
                                    }
                                },
                                label = { Text(label) }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HourField("From", c.startHour) { h -> onReplace { (it as Cond.Time).copy(startHour = h) } }
                        HourField("To", c.endHour) { h -> onReplace { (it as Cond.Time).copy(endHour = h) } }
                    }
                }
                is Cond.Contact -> Text(
                    "Matches when the sender is in your contacts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                is Cond.Spam -> SwitchRow("Is spam", c.isSpam) { v -> onReplace { (it as Cond.Spam).copy(isSpam = v) } }
                is Cond.Otp -> SwitchRow("Has OTP code", c.required) { v -> onReplace { (it as Cond.Otp).copy(required = v) } }
                is Cond.Emoji -> SwitchRow("Has emoji", c.present) { v -> onReplace { (it as Cond.Emoji).copy(present = v) } }
                is Cond.Link -> SwitchRow("Has link", c.present) { v -> onReplace { (it as Cond.Link).copy(present = v) } }
                is Cond.Group -> Unit // CondCard routes groups to GroupCard
            }
        }
    }
}

@Composable
private fun NotControl(not: Boolean, onChange: (Boolean) -> Unit) {
    Text(
        "NOT",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(end = 8.dp)
    )
    Switch(checked = not, onCheckedChange = onChange)
}

@Composable
private fun RemoveButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(Icons.Filled.Close, contentDescription = "Remove") }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.HourField(label: String, hour: Int, onChange: (Int) -> Unit) {
    OutlinedTextField(
        value = if (hour in 0..23) hour.toString() else "",
        onValueChange = { v ->
            val n = v.filter(Char::isDigit).take(2).toIntOrNull()
            onChange(if (n != null && n in 0..23) n else -1)
        },
        label = { Text("$label hour") },
        supportingText = { Text("0-23, blank = any") },
        singleLine = true,
        modifier = Modifier.weight(1f)
    )
}

// ---------- THEN: one card per action ----------

@Composable
private fun ActCard(
    act: Act,
    index: Int,
    count: Int,
    onEdit: ((Act) -> Act) -> Unit,
    onRemove: () -> Unit,
    onMove: (Int) -> Unit,
    onPick: (String) -> Unit
) {
    val ctx = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    AddonRules.summarizeAct(act) { pkg -> appLabel(ctx, pkg) },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { onMove(-1) }, enabled = index > 0) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up")
                }
                IconButton(onClick = { onMove(1) }, enabled = index < count - 1) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down")
                }
                RemoveButton(onRemove)
            }
            when (act.type) {
                "email" -> {
                    ParamField("To", act, "to", onEdit)
                    ParamField("Subject", act, "subject", onEdit)
                    ParamField("Body", act, "body", onEdit)
                    PackageField("Email app", act, "app", onEdit) { onPick("email") }
                }
                "forward" -> ParamField("Number", act, "number", onEdit)
                "autoreply" -> ParamField("Reply text", act, "text", onEdit, multiLine = true)
                "modify_text" -> ParamField("Notification text", act, "text", onEdit, multiLine = true)
                "alert" -> ParamField("Notification text", act, "text", onEdit, multiLine = true)
                "open_app" -> PackageField("App", act, "package", onEdit) { onPick("launch") }
                "call" -> ParamField("Number", act, "number", onEdit, helper = "Blank = reply to the sender")
                "share" -> PackageField("App (optional)", act, "package", onEdit) { onPick("send") }
                else -> Unit
            }
        }
    }
}

@Composable
private fun ParamField(
    label: String,
    act: Act,
    key: String,
    onEdit: ((Act) -> Act) -> Unit,
    helper: String? = null,
    multiLine: Boolean = false
) {
    OutlinedTextField(
        value = act.params[key].orEmpty(),
        onValueChange = { v -> onEdit { it.copy(params = it.params + (key to v)) } },
        label = { Text(label) },
        supportingText = helper?.let { { Text(it) } },
        singleLine = !multiLine,
        minLines = if (multiLine) 2 else 1,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun PackageField(
    label: String,
    act: Act,
    key: String,
    onEdit: ((Act) -> Act) -> Unit,
    onPick: () -> Unit
) {
    val ctx = LocalContext.current
    val pkg = act.params[key].orEmpty()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onPick, modifier = Modifier.weight(1f)) {
            Text(
                "$label: ${if (pkg.isBlank()) "choose" else appLabel(ctx, pkg)}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (pkg.isNotBlank()) {
            TextButton(onClick = { onEdit { it.copy(params = it.params - key) } }) { Text("Clear") }
        }
    }
}

// ---------- sheets and pickers ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypeSheet(
    title: String,
    types: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp).padding(bottom = 16.dp)) {
            items(types, key = { it.first }) { (type, label) ->
                ListItem(headlineContent = { Text(label) }, modifier = Modifier.clickable { onPick(type) })
            }
        }
    }
}

private fun defaultCond(type: String): Cond = when (type) {
    "sender" -> Cond.Sender()
    "keyword" -> Cond.Keyword()
    "length" -> Cond.Length()
    "time" -> Cond.Time()
    "contact" -> Cond.Contact()
    "spam" -> Cond.Spam()
    "otp" -> Cond.Otp()
    "emoji" -> Cond.Emoji()
    else -> Cond.Link()
}

private fun condTitle(c: Cond): String = when (c) {
    is Cond.Sender -> "SENDER"
    is Cond.Keyword -> "KEYWORD"
    is Cond.Length -> "LENGTH"
    is Cond.Time -> "TIME"
    is Cond.Contact -> "CONTACT"
    is Cond.Spam -> "SPAM"
    is Cond.Otp -> "OTP"
    is Cond.Emoji -> "EMOJI"
    is Cond.Link -> "LINK"
    is Cond.Group -> "GROUP"
}

private fun withNot(c: Cond, not: Boolean): Cond = when (c) {
    is Cond.Group -> c.copy(not = not)
    is Cond.Sender -> c.copy(not = not)
    is Cond.Keyword -> c.copy(not = not)
    is Cond.Length -> c.copy(not = not)
    is Cond.Time -> c.copy(not = not)
    is Cond.Contact -> c.copy(not = not)
    is Cond.Spam -> c.copy(not = not)
    is Cond.Otp -> c.copy(not = not)
    is Cond.Emoji -> c.copy(not = not)
    is Cond.Link -> c.copy(not = not)
}

private val MATCH_MODES = listOf("contains", "word", "exact", "regex")
private val SCOPES = listOf("body" to "body", "sender" to "sender", "any" to "anywhere")

// Calendar.DAY_OF_WEEK numbering: 1 = Sunday … 7 = Saturday, the order Time stores.
private val DAY_CHIPS = listOf("Sun" to 1, "Mon" to 2, "Tue" to 3, "Wed" to 4, "Thu" to 5, "Fri" to 6, "Sat" to 7)

private val CONDITION_TYPES = listOf(
    "sender" to "Sender",
    "keyword" to "Keyword",
    "length" to "Length",
    "time" to "Day and time",
    "contact" to "From contacts",
    "spam" to "Spam",
    "otp" to "Has OTP",
    "emoji" to "Has emoji",
    "link" to "Has link"
)

private val ACTION_TYPES = listOf(
    "copy_otp" to "Copy OTP",
    "copy_body" to "Copy message",
    "autoreply" to "Auto-reply",
    "forward" to "Forward SMS",
    "email" to "Send email",
    "open_app" to "Open app",
    "call" to "Dial a number",
    "share" to "Share message",
    "alert" to "Show alert notification",
    "modify_text" to "Replace notification text",
    "mark_read" to "Mark read",
    "mute" to "Mute",
    "unmute" to "Unmute",
    "pin" to "Pin",
    "unpin" to "Unpin",
    "archive" to "Archive",
    "unarchive" to "Unarchive",
    "quarantine" to "Quarantine",
    "restore" to "Restore",
    "block_sender" to "Block sender",
    "dismiss" to "Dismiss notification"
)
