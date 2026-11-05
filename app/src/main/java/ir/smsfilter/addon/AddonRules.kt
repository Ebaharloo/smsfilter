package ir.smsfilter.addon

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import ir.smsfilter.FilterService
import ir.smsfilter.Learned
import ir.smsfilter.Normalizer
import ir.smsfilter.Sms
import ir.smsfilter.ThreadFlags
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class AddonRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val enabled: Boolean = true,
    val priority: Int = 0,
    val senderContains: String = "",
    val keywordContains: String = "",
    val keywords: List<String> = emptyList(),
    val keywordMode: String = "AND",
    val keywordRegex: Boolean = false,
    // Plain keywords only: the term must sit between non-word chars / string edges. Regex mode
    // ignores it, because the pattern carries its own boundaries.
    val keywordWholeWord: Boolean = false,
    val minLength: Int = 0,
    val maxLength: Int = 0,
    val daysOfWeek: String = "",
    val startHour: Int = -1,
    val endHour: Int = -1,
    val onlyFromContacts: Boolean = false,
    val onlySpam: Boolean = false,
    val onlyNotSpam: Boolean = false,
    val otpRequired: Boolean? = null,
    val emailTo: String = "",
    val emailSubject: String = "",
    val emailBody: String = "",
    val emailAppPackage: String = "",
    val action: String = "copy_otp",
    val actionParam: String = "",
    // BuzzKill MultiTool: one rule fires several actions, in this order. `action` stays the primary
    // (and the only one for every rule saved before this field existed).
    val actions: List<String> = emptyList(),
    val cooldownSeconds: Int = 0,
    // BuzzKill runs a trigger on rules that name no condition; this app refuses that by default
    // so a half-written rule cannot swallow every SMS.
    val matchAny: Boolean = false,
    // BuzzKill `KeywordMatching.Combination`: an AND/OR tree with a NOT flag on every node.
    // null = use the legacy flat fields above, which is what every rule saved so far has.
    val conditions: Cond? = null,
    // Per-action parameters. Empty = derive from action/actions/actionParam/email* (the legacy view).
    val actionList: List<Act> = emptyList()
)

/**
 * Condition tree. [Group] holds the boolean logic, the rest are leaves.
 * An empty AND group is true ("match all"), an empty OR group is false.
 * `Group(op = "BROKEN")` is the sentinel for unreadable JSON: it evaluates false, never true.
 */
sealed class Cond {
    data class Group(val op: String = "AND", val not: Boolean = false, val items: List<Cond> = emptyList()) : Cond()
    data class Sender(val value: String = "", val mode: String = "contains", val not: Boolean = false) : Cond()
    data class Keyword(
        val text: String = "",
        // contains | word | exact | regex. `sender`/`any` reuse the same normalization as the body.
        val match: String = "contains",
        val scope: String = "body",
        val not: Boolean = false
    ) : Cond()
    data class Length(val min: Int = 0, val max: Int = 0, val not: Boolean = false) : Cond()
    data class Time(
        val days: Set<Int> = emptySet(),
        val startHour: Int = -1,
        val endHour: Int = -1,
        val not: Boolean = false
    ) : Cond()
    data class Contact(val not: Boolean = false) : Cond()
    data class Spam(val isSpam: Boolean = true, val not: Boolean = false) : Cond()
    data class Otp(val required: Boolean = true, val not: Boolean = false) : Cond()
    data class Emoji(val present: Boolean = true, val not: Boolean = false) : Cond()
    data class Link(val present: Boolean = true, val not: Boolean = false) : Cond()
}

/** One action plus its own parameters, so two `forward`s can go to two numbers. */
data class Act(val type: String = "", val params: Map<String, String> = emptyMap())

object AddonRules {
    private const val KEY = "addon_rules_json"
    private const val MAX_REGEX_SUBJECT = 200
    private const val MAX_PATTERN_LEN = 120
    private const val MAX_QUANTIFIERS = 2
    private const val ADDON_NOTIF_ID = 1_000_000
    private const val DEC_KEY = "addon_decisions"
    internal const val DEC_MAX = 20
    const val MAX_COOLDOWN = 86_400
    // Ceilings for a stored/imported condition tree. Violation = unusable rule, never match-all.
    const val MAX_TREE_DEPTH = 6
    const val MAX_TREE_NODES = 64
    private const val BROKEN = "BROKEN"
    private val GROUP_PREFIX = Regex("\\(\\?[:=!<]")
    private val NESTED_QUANTIFIER = Regex("\\([^)]*[+*?|][^)]*\\)\\s*[+*{]")

    fun load(ctx: Context): MutableList<AddonRule> {
        val raw = ctx.getSharedPreferences(FilterService.PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<AddonRule>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += AddonRule(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    name = o.optString("name", ""),
                    enabled = o.optBoolean("enabled", true),
                    priority = o.optInt("priority", i),
                    senderContains = o.optString("senderContains", ""),
                    keywordContains = o.optString("keywordContains", ""),
                    keywords = run {
                        val a = o.optJSONArray("keywords")
                        val list = (0 until (a?.length() ?: 0)).mapNotNull { i -> a?.opt(i) as? String }
                        // Rules saved before the keyword list existed only have keywordContains.
                        if (list.isNotEmpty()) list else listOf(o.optString("keywordContains", "")).filter { it.isNotBlank() }
                    },
                    keywordMode = o.optString("keywordMode", "AND"),
                    keywordRegex = o.optBoolean("keywordRegex", false),
                    keywordWholeWord = o.optBoolean("keywordWholeWord", false),
                    minLength = o.optInt("minLength", 0),
                    maxLength = o.optInt("maxLength", 0),
                    daysOfWeek = o.optString("daysOfWeek", ""),
                    startHour = o.optInt("startHour", -1),
                    endHour = o.optInt("endHour", -1),
                    onlyFromContacts = o.optBoolean("onlyFromContacts", false),
                    onlySpam = o.optBoolean("onlySpam", false),
                    onlyNotSpam = o.optBoolean("onlyNotSpam", false),
                    otpRequired = if (o.has("otpRequired")) o.optBoolean("otpRequired") else null,
                    emailTo = o.optString("emailTo", ""),
                    emailSubject = o.optString("emailSubject", ""),
                    emailBody = o.optString("emailBody", ""),
                    emailAppPackage = o.optString("emailAppPackage", ""),
                    action = o.optString("action", "copy_otp"),
                    actionParam = o.optString("actionParam", ""),
                    actions = run {
                        val a = o.optJSONArray("actions")
                        (0 until (a?.length() ?: 0)).mapNotNull { i -> a?.opt(i) as? String }
                    },
                    cooldownSeconds = o.optInt("cooldownSeconds", 0),
                    matchAny = o.optBoolean("matchAny", false),
                    // Present-but-unreadable is dead (BROKEN), absent is the legacy flat path.
                    conditions = if (o.has("conditions")) parseCond(o.opt("conditions"), 1, IntArray(1)) else null,
                    actionList = parseActs(o.optJSONArray("actionList"))
                )
            }
            out
        } catch (t: Throwable) {
            Log.e("AddonRules", "load failed", t)
            mutableListOf()
        }
    }

    fun save(ctx: Context, rules: List<AddonRule>) {
        val arr = JSONArray()
        rules.forEach { r ->
            val o = JSONObject()
            // A tree rule still writes the flat fields, projected from its leaves, so an older build
            // (and the current flat editor) shows something instead of a blank rule.
            val tree = r.conditions
            val ls = tree?.let { leaves(it).toList() }.orEmpty()
            val kws = ls.filterIsInstance<Cond.Keyword>().filter { !it.not }
            val len = ls.filterIsInstance<Cond.Length>().firstOrNull()
            val time = ls.filterIsInstance<Cond.Time>().firstOrNull()
            val spam = ls.filterIsInstance<Cond.Spam>().firstOrNull()
            val otpLeaf = ls.filterIsInstance<Cond.Otp>().firstOrNull()
            o.put("id", r.id)
            o.put("name", r.name)
            o.put("enabled", r.enabled)
            o.put("priority", r.priority)
            o.put("senderContains", if (tree == null) r.senderContains else ls.filterIsInstance<Cond.Sender>().firstOrNull()?.value.orEmpty())
            o.put("keywordContains", if (tree == null) (r.keywords.firstOrNull() ?: r.keywordContains) else kws.firstOrNull()?.text.orEmpty())
            o.put("keywords", JSONArray(if (tree == null) r.keywords else kws.map { it.text }))
            o.put("keywordMode", if (tree == null) r.keywordMode else "AND")
            o.put("keywordRegex", if (tree == null) r.keywordRegex else kws.firstOrNull()?.match.equals("regex", true))
            o.put("keywordWholeWord", if (tree == null) r.keywordWholeWord else kws.firstOrNull()?.match.equals("word", true))
            o.put("minLength", len?.min ?: if (tree == null) r.minLength else 0)
            o.put("maxLength", len?.max ?: if (tree == null) r.maxLength else 0)
            o.put("daysOfWeek", time?.days?.joinToString(",") ?: if (tree == null) r.daysOfWeek else "")
            o.put("startHour", time?.startHour ?: if (tree == null) r.startHour else -1)
            o.put("endHour", time?.endHour ?: if (tree == null) r.endHour else -1)
            o.put("onlyFromContacts", if (tree == null) r.onlyFromContacts else ls.any { it is Cond.Contact })
            o.put("onlySpam", if (tree == null) r.onlySpam else spam?.isSpam == true)
            o.put("onlyNotSpam", if (tree == null) r.onlyNotSpam else spam?.isSpam == false)
            val otp = if (tree == null) r.otpRequired else otpLeaf?.required
            if (otp != null) o.put("otpRequired", otp)
            o.put("emailTo", r.emailTo)
            o.put("emailSubject", r.emailSubject)
            o.put("emailBody", r.emailBody)
            o.put("emailAppPackage", r.emailAppPackage)
            o.put("action", r.action)
            o.put("actionParam", r.actionParam)
            o.put("actions", JSONArray(r.actions))
            if (r.actionList.isNotEmpty()) {
                o.put("actionList", JSONArray().apply { r.actionList.forEach { a -> put(actJson(a)) } })
            }
            o.put("cooldownSeconds", r.cooldownSeconds)
            o.put("matchAny", r.matchAny)
            tree?.let { o.put("conditions", condJson(it)) }
            arr.put(o)
        }
        ctx.getSharedPreferences(FilterService.PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun ensureDefaults(ctx: Context) {
        if (load(ctx).isNotEmpty()) return
        val p = ctx.getSharedPreferences(FilterService.PREFS, Context.MODE_PRIVATE)
        val rules = mutableListOf<AddonRule>()
        if (p.getBoolean("addon_otp_autocopy", false)) {
            rules += AddonRule(name = "Auto-copy OTP", action = "copy_otp", otpRequired = true)
        }
        if (p.getBoolean("addon_copy_body", false)) {
            rules += AddonRule(name = "Auto-copy body", action = "copy_body", onlyNotSpam = true)
        }
        if (p.getBoolean("addon_autoreply", false)) {
            rules += AddonRule(
                name = "Auto-reply",
                action = "autoreply",
                onlyFromContacts = true,
                onlyNotSpam = true,
                actionParam = p.getString("addon_autoreply_text", "پیام شما دریافت شد").orEmpty()
            )
        }
        if (rules.isNotEmpty()) save(ctx, rules)
    }

    fun matches(rule: AddonRule, event: SmsEvent, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (!rule.enabled) return false
        val tree = rule.conditions ?: return legacyMatches(rule, event, nowMillis)
        return treeProblem(tree) == null && eval(tree, Ctx(event, nowMillis), null)
    }

    private fun legacyMatches(rule: AddonRule, event: SmsEvent, nowMillis: Long): Boolean {
        val active = conditions(rule, event, nowMillis)
        // An empty list means "no condition" here (true); matching() is what keeps such a rule inert.
        return active.isEmpty() || active.all { it.second }
    }

    // BuzzKill RuleFinder.b: every rule is tested, matching ones run in priority order.
    fun matching(ctx: Context, event: SmsEvent, nowMillis: Long = System.currentTimeMillis()): List<AddonRule> {
        ensureDefaults(ctx)
        // sortedBy is stable, so equal priorities keep their saved order.
        return load(ctx).sortedBy { it.priority }.filter { rule ->
            if (!rule.enabled) return@filter false
            val tree = rule.conditions
            if (tree != null) return@filter treeProblem(tree) == null && eval(tree, Ctx(event, nowMillis), null)
            val active = conditions(rule, event, nowMillis)
            (active.isNotEmpty() || rule.matchAny) && active.all { it.second }
        }
    }

    // Why this rule said no: the first failing condition, "no conditions" for an inert rule.
    // The dry-run dialog and the decision trace both read it; null means the rule matches.
    fun missReason(rule: AddonRule, event: SmsEvent, nowMillis: Long = System.currentTimeMillis()): String? {
        if (!rule.enabled) return "disabled"
        val tree = rule.conditions
        if (tree != null) {
            treeProblem(tree)?.let { return it }
            val fails = mutableListOf<String>()
            return if (eval(tree, Ctx(event, nowMillis), fails)) null else fails.firstOrNull() ?: "conditions"
        }
        val active = conditions(rule, event, nowMillis)
        return when {
            active.isEmpty() && !rule.matchAny -> "no conditions"
            else -> active.firstOrNull { !it.second }?.first
        }
    }

    // Same answer for a whole rule set, so a miss in dispatch can name its cause.
    fun firstMiss(ctx: Context, event: SmsEvent, nowMillis: Long = System.currentTimeMillis()): String? =
        load(ctx).sortedBy { it.priority }.mapNotNull { missReason(it, event, nowMillis) }.firstOrNull()

    // The conditions matches() evaluates, each with the name the trace/dry-run shows.
    // A raw-blank value is skipped here, which is why a spaces-only keyword counts as
    // "no condition" and therefore makes the rule inert.
    private fun conditions(rule: AddonRule, event: SmsEvent, nowMillis: Long): MutableList<Pair<String, Boolean>> {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = nowMillis }
        val active = mutableListOf<Pair<String, Boolean>>()
        if (rule.senderContains.isNotBlank()) {
            // A term that normalizes to "" would make contains("") match every SMS: unusable, never true.
            val sender = Normalizer.normalize(rule.senderContains)
            active += "sender" to (sender.isNotEmpty() && Normalizer.normalize(event.sender).contains(sender))
        }
        val terms = when {
            rule.keywords.isNotEmpty() -> rule.keywords
            rule.keywordContains.isNotBlank() -> listOf(rule.keywordContains)
            else -> emptyList()
        }
        if (terms.isNotEmpty()) {
            // normalize() folds Arabic/Persian letter variants, digits and case; squash() also
            // bridges ZWNJ and spaced-out evasion ("ت خ ف ی ف"). Empty kw would match everything.
            val body = Normalizer.normalize(event.body)
            val squashed = Normalizer.squash(body)
            // Word mode reads `body`, never `squashed`: squash deletes the spaces/punctuation the
            // boundary test needs, so it would silently degrade whole-word back to a substring hit.
            fun hit(raw: String): Boolean = if (rule.keywordRegex) regexHit(raw, body) else {
                val kw = Normalizer.normalize(raw)
                when {
                    kw.isEmpty() -> false
                    rule.keywordWholeWord -> wholeWordHit(body, kw)
                    else -> body.contains(kw) || squashed.contains(Normalizer.squash(kw))
                }
            }
            // "-term" = must be absent; "\-term" = literal hyphen. A blank or bare-hyphen term is
            // ignored beside a usable sibling; alone it leaves nothing usable, so the rule never matches.
            val parsed = terms.map { keywordTerm(it) }
            val negTerms = parsed.filter { it.first && it.second.isNotBlank() && it.second != "-" }.map { it.second }
            val positives = parsed.filterNot { it.first }.map { it.second }
            // A negative that can never hit ("-(" in regex mode, "-" + ZWNJ) would be vacuously satisfied
            // and turn the rule into match-all, so it makes the rule unsatisfiable instead.
            val negUsable = negTerms.all { t -> if (rule.keywordRegex) regexUsable(t) else Normalizer.normalize(t).isNotEmpty() }
            val usable = parsed.any { it.second.isNotBlank() && !(it.first && it.second == "-") }
            if (parsed.isNotEmpty() && (!usable || !negUsable)) active += "keyword" to false
            else {
                val posOk = positives.isEmpty() ||
                    if (rule.keywordMode.trim().equals("OR", ignoreCase = true)) positives.any(::hit) else positives.all(::hit)
                active += "keyword" to (posOk && negTerms.none(::hit))
            }
        }
        if (rule.minLength > 0) active += "length" to (event.body.length >= rule.minLength)
        if (rule.maxLength > 0) active += "length" to (event.body.length <= rule.maxLength)
        if (rule.daysOfWeek.isNotBlank()) {
            val days = rule.daysOfWeek.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
            if (days.isNotEmpty()) {
                active += "time" to (cal.get(java.util.Calendar.DAY_OF_WEEK) in days)
            }
        }
        if (rule.startHour >= 0 && rule.endHour >= 0) {
            val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
            active += "time" to if (rule.startHour <= rule.endHour) {
                h in rule.startHour..rule.endHour
            } else {
                h >= rule.startHour || h <= rule.endHour
            }
        }
        if (rule.onlyFromContacts) active += "contact" to event.fromContact
        if (rule.onlySpam) active += "spam" to event.isSpam
        if (rule.onlyNotSpam) active += "spam" to !event.isSpam
        if (rule.otpRequired == true) active += "otp" to (event.otp != null)
        if (rule.otpRequired == false) active += "otp" to (event.otp == null)

        return active
    }

    // ---------- condition tree (BuzzKill KeywordMatching.Combination) ----------

    private class Ctx(val event: SmsEvent, nowMillis: Long) {
        val sender by lazy { Normalizer.normalize(event.sender) }
        val body by lazy { Normalizer.normalize(event.body) }
        val squash by lazy { Normalizer.squash(body) }
        val cal: java.util.Calendar = java.util.Calendar.getInstance().apply { timeInMillis = nowMillis }
    }

    // Depth/node ceilings: a corrupt or imported JSON tree must go dead, never collapse to match-all.
    fun treeProblem(tree: Cond): String? {
        val count = IntArray(1)
        fun walk(c: Cond, depth: Int): Boolean {
            count[0]++
            if (depth > MAX_TREE_DEPTH || count[0] > MAX_TREE_NODES) return false
            return c !is Cond.Group || c.items.all { walk(it, depth + 1) }
        }
        return if (walk(tree, 1)) null else "conditions too complex"
    }

    private fun leaf(name: String, v: Boolean, fails: MutableList<String>?): Boolean {
        if (!v) fails?.add(name)
        return v
    }

    // A leaf whose value normalizes to "" can never hit; it is false even under `not`, because the
    // inverse of "can never hit" would be a free pass over every SMS.
    private fun eval(c: Cond, x: Ctx, fails: MutableList<String>?): Boolean = when (c) {
        is Cond.Group -> {
            val v = when {
                c.op.equals("AND", true) -> c.items.all { eval(it, x, fails) }
                c.op.equals("OR", true) -> c.items.any { eval(it, x, fails) }
                else -> false // unknown/BROKEN operation: dead, never a free pass
            }
            if (c.not) !v else v
        }
        is Cond.Sender -> {
            val want = Normalizer.normalize(c.value)
            val hit = want.isNotEmpty() &&
                if (c.mode.equals("exact", true)) x.sender == want else x.sender.contains(want)
            leaf("sender", if (c.not) !hit && want.isNotEmpty() else hit, fails)
        }
        is Cond.Keyword -> {
            val kw = Normalizer.normalize(c.text)
            val regex = c.match.equals("regex", true)
            val usable = if (regex) regexUsable(c.text) else kw.isNotEmpty()
            fun field(t: String): Boolean = when {
                !usable -> false
                regex -> regexHit(c.text, t)
                c.match.equals("word", true) -> wholeWordHit(t, kw)
                c.match.equals("exact", true) -> t == kw
                else -> t.contains(kw) || Normalizer.squash(t).contains(Normalizer.squash(kw))
            }
            val hit = usable && when (c.scope.lowercase()) {
                "sender" -> field(x.sender)
                "any" -> field(x.sender) || field(x.body)
                else -> field(x.body)
            }
            leaf("keyword", if (c.not) usable && !hit else hit, fails)
        }
        is Cond.Length -> {
            val n = x.event.body.length
            val v = (c.min <= 0 || n >= c.min) && (c.max <= 0 || n <= c.max)
            leaf("length", if (c.not) !v else v, fails)
        }
        is Cond.Time -> {
            val dayOk = c.days.isEmpty() || x.cal.get(java.util.Calendar.DAY_OF_WEEK) in c.days
            val hourOk = if (c.startHour >= 0 && c.endHour >= 0) {
                val h = x.cal.get(java.util.Calendar.HOUR_OF_DAY)
                // wrap-around window (22..6) kept: this app's long-standing behaviour, not BuzzKill's same-day compare
                if (c.startHour <= c.endHour) h in c.startHour..c.endHour else h >= c.startHour || h <= c.endHour
            } else true
            val v = dayOk && hourOk
            leaf("time", if (c.not) !v else v, fails)
        }
        is Cond.Contact -> leaf("contact", if (c.not) !x.event.fromContact else x.event.fromContact, fails)
        is Cond.Spam -> {
            val v = x.event.isSpam == c.isSpam
            leaf("spam", if (c.not) !v else v, fails)
        }
        is Cond.Otp -> {
            val v = (x.event.otp != null) == c.required
            leaf("otp", if (c.not) !v else v, fails)
        }
        is Cond.Emoji -> {
            val v = hasEmoji(x.event.body) == c.present
            leaf("emoji", if (c.not) !v else v, fails)
        }
        is Cond.Link -> {
            val v = LINK.containsMatchIn(x.event.body) == c.present
            leaf("link", if (c.not) !v else v, fails)
        }
    }

    // shortcut: emoji is a codepoint-range heuristic (pictographs, dingbats, arrows, VS16); upgrade to
    // an emoji table if a "no emoji" rule ever needs to be exact.
    private fun hasEmoji(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val cp = Character.codePointAt(s, i)
            if (cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2190..0x21FF || cp == 0xFE0F) return true
            i += Character.charCount(cp)
        }
        return false
    }

    // shortcut: only http(s):// and www. count as a link; bare domains and t.me/… are not links yet.
    private val LINK = Regex("https?://|www\\.", RegexOption.IGNORE_CASE)

    // The legacy flat fields as an equivalent tree. The evaluator does not need it (null conditions keep
    // the old path), but the editor and importers do: this is the exact translation the matcher semantics
    // were ported from, so a migrated rule and a flat rule agree.
    fun toTree(rule: AddonRule): Cond {
        rule.conditions?.let { return it }
        val items = mutableListOf<Cond>()
        if (rule.senderContains.isNotBlank()) items += Cond.Sender(rule.senderContains)
        val terms = when {
            rule.keywords.isNotEmpty() -> rule.keywords
            rule.keywordContains.isNotBlank() -> listOf(rule.keywordContains)
            else -> emptyList()
        }
        if (terms.isNotEmpty()) {
            val parsed = terms.map { keywordTerm(it) }
            val match = when {
                rule.keywordRegex -> "regex"
                rule.keywordWholeWord -> "word"
                else -> "contains"
            }
            val pos = parsed.filterNot { it.first }.map { Cond.Keyword(it.second, match) }
            // Same drops as the flat matcher: blank negatives and the bare "-" are not negatives at all.
            val neg = parsed.filter { it.first && it.second.isNotBlank() && it.second != "-" }
                .map { Cond.Keyword(it.second, match, not = true) }
            val usable = parsed.any { it.second.isNotBlank() && !(it.first && it.second == "-") }
            if (!usable) items += Cond.Keyword("") // unusable term set: the flat path makes the condition false
            else {
                if (pos.isNotEmpty()) {
                    items += if (rule.keywordMode.trim().equals("OR", true)) Cond.Group("OR", items = pos)
                    else Cond.Group("AND", items = pos)
                }
                items += neg
            }
        }
        if (rule.minLength > 0 || rule.maxLength > 0) items += Cond.Length(rule.minLength, rule.maxLength)
        val days = rule.daysOfWeek.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
        if (days.isNotEmpty() || (rule.startHour >= 0 && rule.endHour >= 0)) {
            items += Cond.Time(days, rule.startHour, rule.endHour)
        }
        if (rule.onlyFromContacts) items += Cond.Contact()
        if (rule.onlySpam) items += Cond.Spam(true)
        if (rule.onlyNotSpam) items += Cond.Spam(false)
        rule.otpRequired?.let { items += Cond.Otp(it) }
        return Cond.Group("AND", items = items)
    }

    // ---------- condition tree JSON ----------

    private fun leaves(c: Cond): Sequence<Cond> =
        if (c is Cond.Group) c.items.asSequence().flatMap { leaves(it) } else sequenceOf(c)

    private fun condJson(c: Cond): JSONObject = JSONObject().apply {
        put("not", when (c) {
            is Cond.Group -> c.not
            is Cond.Sender -> c.not
            is Cond.Keyword -> c.not
            is Cond.Length -> c.not
            is Cond.Time -> c.not
            is Cond.Contact -> c.not
            is Cond.Spam -> c.not
            is Cond.Otp -> c.not
            is Cond.Emoji -> c.not
            is Cond.Link -> c.not
        })
        when (c) {
            is Cond.Group -> {
                put("type", "group")
                put("op", c.op)
                put("items", JSONArray().apply { c.items.forEach { i -> put(condJson(i)) } })
            }
            is Cond.Sender -> {
                put("type", "sender"); put("value", c.value); put("mode", c.mode)
            }
            is Cond.Keyword -> {
                put("type", "keyword"); put("text", c.text); put("match", c.match); put("scope", c.scope)
            }
            is Cond.Length -> {
                put("type", "length"); put("min", c.min); put("max", c.max)
            }
            is Cond.Time -> {
                put("type", "time"); put("days", JSONArray(c.days.sorted())); put("start", c.startHour); put("end", c.endHour)
            }
            is Cond.Contact -> put("type", "contact")
            is Cond.Spam -> {
                put("type", "spam"); put("isSpam", c.isSpam)
            }
            is Cond.Otp -> {
                put("type", "otp"); put("required", c.required)
            }
            is Cond.Emoji -> {
                put("type", "emoji"); put("present", c.present)
            }
            is Cond.Link -> {
                put("type", "link"); put("present", c.present)
            }
        }
    }

    // Unreadable node -> BROKEN (false), and the node counter stops a hostile import before it can
    // allocate a wide tree. Unknown type from a newer build is dead here, not a free pass.
    private fun parseCond(v: Any?, depth: Int, count: IntArray): Cond {
        count[0]++
        if (depth > MAX_TREE_DEPTH || count[0] > MAX_TREE_NODES) return Cond.Group(BROKEN)
        val o = v as? JSONObject ?: return Cond.Group(BROKEN)
        return runCatching {
            when (o.optString("type")) {
                "group" -> {
                    val a = o.optJSONArray("items")
                    Cond.Group(
                        op = o.optString("op", "AND"),
                        not = o.optBoolean("not", false),
                        items = (0 until (a?.length() ?: 0)).map { parseCond(a?.opt(it), depth + 1, count) }
                    )
                }
                "sender" -> Cond.Sender(o.optString("value", ""), o.optString("mode", "contains"), o.optBoolean("not", false))
                "keyword" -> Cond.Keyword(
                    o.optString("text", ""),
                    o.optString("match", "contains"),
                    o.optString("scope", "body"),
                    o.optBoolean("not", false)
                )
                "length" -> Cond.Length(o.optInt("min", 0), o.optInt("max", 0), o.optBoolean("not", false))
                "time" -> {
                    val d = o.optJSONArray("days")
                    val days = (0 until (d?.length() ?: 0)).mapNotNull { d?.optInt(it) }.toSet()
                    Cond.Time(days, o.optInt("start", -1), o.optInt("end", -1), o.optBoolean("not", false))
                }
                "contact" -> Cond.Contact(o.optBoolean("not", false))
                "spam" -> Cond.Spam(o.optBoolean("isSpam", true), o.optBoolean("not", false))
                "otp" -> Cond.Otp(o.optBoolean("required", true), o.optBoolean("not", false))
                "emoji" -> Cond.Emoji(o.optBoolean("present", true), o.optBoolean("not", false))
                "link" -> Cond.Link(o.optBoolean("present", true), o.optBoolean("not", false))
                else -> Cond.Group(BROKEN)
            }
        }.getOrDefault(Cond.Group(BROKEN))
    }

    private fun actJson(a: Act): JSONObject = JSONObject().apply {
        put("type", a.type)
        put("params", JSONObject(a.params as Map<*, *>))
    }

    private fun parseActs(a: JSONArray?): List<Act> = (0 until (a?.length() ?: 0)).mapNotNull { i ->
        val o = a?.optJSONObject(i) ?: return@mapNotNull null
        val type = o.optString("type", "")
        if (type.isBlank()) return@mapNotNull null
        val params = mutableMapOf<String, String>()
        val p = o.optJSONObject("params")
        if (p != null) {
            val keys = p.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                params[k] = p.optString(k, "")
            }
        }
        Act(type, params)
    }

    // Java's \w and \b are ASCII-only, so "(\\W|^)رمز(\\W|$)" matches inside "رمزنگاری": every
    // Persian letter looks like a boundary. isLetterOrDigit is Unicode-aware, no flag needed.
    private fun wholeWordHit(body: String, keyword: String): Boolean {
        var i = body.indexOf(keyword)
        while (i >= 0) {
            val end = i + keyword.length
            val left = i == 0 || !isWordChar(body[i - 1])
            val right = end == body.length || !isWordChar(body[end])
            if (left && right) return true
            i = body.indexOf(keyword, i + 1)
        }
        return false
    }

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_'

    private fun firedKey(rule: AddonRule) = "addon_fired_${rule.id}"

    // 0 = every time. The last fire is read from prefs, so a process restart cannot re-arm a window
    // that is still running.
    fun allowedNow(ctx: Context, rule: AddonRule, nowMillis: Long): Boolean {
        if (rule.cooldownSeconds <= 0) return true
        val last = ctx.getSharedPreferences(FilterService.PREFS, Context.MODE_PRIVATE)
            .getLong(firedKey(rule), 0L)
        return last == 0L || nowMillis - last >= rule.cooldownSeconds * 1000L
    }

    fun markFired(ctx: Context, rule: AddonRule, nowMillis: Long) {
        if (rule.cooldownSeconds <= 0) return
        ctx.getSharedPreferences(FilterService.PREFS, Context.MODE_PRIVATE)
            .edit().putLong(firedKey(rule), nowMillis).apply()
    }

    // Seam: Sms.send needs a radio and the SEND_SMS grant, so the JVM tests swap this.
    internal var send: (Context, String, String) -> Boolean = { c, a, b -> Sms.send(c, a, b) }

    // Seam for every activity launch (open_app/call/share/email): resolves first so a missing target is
    // a plain false instead of an ActivityNotFoundException, and the JVM tests can swap it.
    internal var launch: (Context, Intent) -> Boolean = { c, i ->
        try {
            if (i.resolveActivity(c.packageManager) == null) false
            else {
                c.startActivity(i)
                true
            }
        } catch (t: Throwable) {
            Log.w("AddonRules", "launch failed: ${i.action}", t)
            false
        }
    }

    // The rule's actions with their own params. An old rule (action/actions/actionParam/email*) is
    // translated on the fly, so `actionList` only has to hold what the user actually configured.
    fun actsOf(rule: AddonRule): List<Act> = if (rule.actionList.isNotEmpty()) rule.actionList else {
        rule.actions.ifEmpty { listOf(rule.action) }.map { Act(it, legacyParams(rule, it)) }
    }

    private fun legacyParams(rule: AddonRule, type: String): Map<String, String> = when (type) {
        "email" -> mapOf(
            "to" to rule.emailTo.ifBlank { rule.actionParam },
            "subject" to rule.emailSubject,
            "body" to rule.emailBody,
            "app" to rule.emailAppPackage
        )
        "forward", "call" -> mapOf("number" to rule.actionParam)
        "open_app" -> mapOf("package" to rule.actionParam)
        "autoreply", "modify_text", "alert" -> mapOf("text" to rule.actionParam)
        else -> emptyMap()
    }

    // Clamp before it reaches the model: 11+ digits used to overflow toIntOrNull() into 0 = "every time".
    fun clampCooldown(text: String): Int =
        text.filter { it.isDigit() }.take(9).toIntOrNull()?.coerceAtMost(MAX_COOLDOWN) ?: 0

    fun executeAll(ctx: Context, rule: AddonRule, event: SmsEvent, dryRun: Boolean = false, ran: MutableList<String>? = null): Int {
        var ok = 0
        for (a in actsOf(rule)) {
            // One dead action (no email app, provider refusing the send) must not eat the rest of the rule.
            try {
                if (executeAction(ctx, a, event, dryRun)) {
                    ok++
                    ran?.add(a.type)
                }
            } catch (t: Throwable) {
                Log.e("AddonRules", "action ${a.type} of rule ${rule.id} failed", t)
            }
        }
        return ok
    }

    // Single-action entry point kept for callers/tests that drive one action off a legacy field.
    fun execute(ctx: Context, rule: AddonRule, event: SmsEvent, dryRun: Boolean = false): Boolean =
        executeAction(ctx, actsOf(rule).first(), event, dryRun)

    // Success means "did the thing", not "did not throw": a guarded-out action must leave the cooldown
    // unarmed, or a rule that did nothing blocks itself for its whole window.
    internal fun executeAction(ctx: Context, act: Act, event: SmsEvent, dryRun: Boolean = false): Boolean {
        if (dryRun) {
            android.util.Log.d("AddonSelfTest", "dry-run action=${act.type}")
            return true
        }
        val p = act.params
        return when (act.type) {
            "copy_otp" -> {
                val code = event.otp
                if (code == null) false else {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    if (cm == null) false else {
                        cm.setPrimaryClip(ClipData.newPlainText("code", code))
                        safeToast(ctx, "کد $code کپی شد")
                        true
                    }
                }
            }
            "copy_body" -> {
                if (event.isSpam) false else {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    if (cm == null) false else {
                        cm.setPrimaryClip(ClipData.newPlainText("sms", event.body))
                        safeToast(ctx, "متن پیام کپی شد")
                        true
                    }
                }
            }
            "autoreply" -> if (!event.isSpam && event.fromContact && event.sender.isNotBlank()) {
                val template = p["text"].orEmpty().ifBlank {
                    ctx.getSharedPreferences(FilterService.PREFS, Context.MODE_PRIVATE)
                        .getString("addon_autoreply_text", "پیام شما دریافت شد").orEmpty()
                }
                if (template.isBlank()) false else send(ctx, event.sender, template)
            } else false
            "forward" -> if (p["number"].orEmpty().isNotBlank()) {
                send(ctx, p["number"].orEmpty(), event.body)
            } else false
            // Empty number = reply to the sender. ACTION_DIAL only: this app never places the call itself.
            "call" -> {
                val number = p["number"].orEmpty().trim().ifBlank { event.sender }.trim()
                if (number.isEmpty()) false else {
                    val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    launch(ctx, intent)
                }
            }
            // Launches the app with the message in EXTRA_TEXT; the MAIN/LAUNCHER fallback still opens a
            // package whose launch intent the manager refuses to hand out.
            "open_app" -> {
                val pkg = p["package"].orEmpty().trim()
                if (pkg.isEmpty()) false else {
                    val intent = runCatching { ctx.packageManager.getLaunchIntentForPackage(pkg) }.getOrNull()
                        ?: Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_LAUNCHER)
                            setPackage(pkg)
                        }
                    intent.putExtra(Intent.EXTRA_TEXT, event.body)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    launch(ctx, intent)
                }
            }
            "share" -> {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, event.body)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val pkg = p["package"].orEmpty().trim()
                if (pkg.isNotEmpty()) {
                    intent.setPackage(pkg)
                    launch(ctx, intent)
                } else {
                    val chooser = runCatching { Intent.createChooser(intent, "Share") }.getOrNull() ?: return false
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    launch(ctx, chooser)
                }
            }
            "mark_read" -> if (event.threadId > 0) {
                Sms.markThreadRead(ctx, event.threadId)
                true
            } else false
            "mute" -> if (event.threadId > 0 && !ThreadFlags.isMuted(ctx, event.threadId)) {
                ThreadFlags.toggleMute(ctx, event.threadId)
                true
            } else false
            "unmute" -> if (event.threadId > 0 && ThreadFlags.isMuted(ctx, event.threadId)) {
                ThreadFlags.toggleMute(ctx, event.threadId)
                true
            } else false
            "pin" -> if (event.threadId > 0 && !ThreadFlags.isPinned(ctx, event.threadId)) {
                ThreadFlags.togglePin(ctx, event.threadId)
                true
            } else false
            "unpin" -> if (event.threadId > 0 && ThreadFlags.isPinned(ctx, event.threadId)) {
                ThreadFlags.togglePin(ctx, event.threadId)
                true
            } else false
            "archive" -> if (event.threadId > 0) {
                ThreadFlags.setArchived(ctx, event.threadId, true)
                true
            } else false
            "unarchive" -> if (event.threadId > 0) {
                ThreadFlags.setArchived(ctx, event.threadId, false)
                true
            } else false
            "quarantine" -> if (event.threadId > 0) {
                ThreadFlags.quarantine(ctx, event.threadId)
                true
            } else false
            "restore" -> if (event.threadId > 0) {
                ThreadFlags.restore(ctx, event.threadId)
                true
            } else false
            "block_sender" -> if (event.sender.isNotBlank()) {
                Learned(ctx).blockSender(event.sender)
                true
            } else false
            // notifyId 0 means this SMS was never posted under an id (digest/silent path): nothing to cancel.
            "dismiss" -> if (event.notifyId > 0) {
                ctx.getSystemService(NotificationManager::class.java)?.cancel(event.notifyId)
                true
            } else false
            "modify_text" -> if (event.notifyId > 0) {
                ctx.getSystemService(NotificationManager::class.java)?.cancel(event.notifyId)
                postAddonNotification(ctx, event, p["text"].orEmpty().ifBlank { event.body }, true)
            } else false
            "alert" -> postAddonNotification(ctx, event, p["text"].orEmpty().ifBlank { event.body }, true)
            "email" -> {
                val to = p["to"].orEmpty().trim()
                val subject = template(p["subject"].orEmpty(), event).ifBlank { "SMS from ${event.sender}" }
                val body = template(p["body"].orEmpty(), event).ifBlank { event.body }
                val app = p["app"].orEmpty().trim()
                if (to.isBlank()) false else try {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "message/rfc822"
                        putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
                        putExtra(Intent.EXTRA_SUBJECT, subject)
                        putExtra(Intent.EXTRA_TEXT, body)
                        if (app.isNotEmpty()) setPackage(app)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val ok = if (app.isEmpty()) {
                        val chooser = Intent.createChooser(intent, "Send email")
                        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        launch(ctx, chooser)
                    } else {
                        launch(ctx, intent)
                    }
                    if (!ok) safeToast(ctx, "No email app available")
                    ok
                } catch (t: Throwable) {
                    safeToast(ctx, "No email app available")
                    false
                }
            }
            // An unknown/mistyped action name did nothing.
            else -> false
        }
    }

    // The clipboard write is the effect; a Toast that cannot be built must not turn it into a failed fire.
    private fun safeToast(ctx: Context, text: String) {
        try {
            Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Log.w("AddonRules", "toast failed", t)
        }
    }

    // "-term" is a negative (must be absent); "\-term" is a literal leading hyphen; the bare
    // "-"/"--" leftovers are dropped by the caller, since they would match nothing usable.
    fun keywordTerm(raw: String): Pair<Boolean, String> = when {
        raw.startsWith("\\-") -> false to raw.substring(1)
        raw.startsWith("-") -> true to raw.substring(1)
        else -> false to raw
    }

    // Bound every user pattern: length, overlapping/nested quantifiers (catastrophic backtracking), subject size.
    // Whether a user regex can ever match. The editor calls this so a refused pattern is visible
    // instead of silently never matching; regexHit must stay in lockstep, so it gates on this too.
    fun regexUsable(pattern: String): Boolean = pattern.isNotBlank() && try {
        when {
            pattern.length > MAX_PATTERN_LEN -> false
            riskyPattern(pattern) -> false
            else -> { Regex(pattern); true }
        }
    } catch (t: Throwable) {
        false
    }

    // shortcut: regexes never see text past 200 chars, so an end-anchored term misses on long SMS;
    // upgrade to RE2/J or a worker thread with a timeout when long-body regex rules are really needed.
    private fun regexHit(pattern: String, normalizedBody: String): Boolean = try {
        when {
            !regexUsable(pattern) -> false
            else -> Regex(pattern).containsMatchIn(normalizedBody.take(MAX_REGEX_SUBJECT))
        }
    } catch (t: Throwable) {
        // Invalid regex, or catastrophic backtracking (StackOverflowError): the term fails, matches() must not throw.
        false
    }

    // Rejected shapes: a quantified group whose body already holds a quantifier or alternation ("(a|aa)+",
    // "(a+)+", "(ab?)+"), and a pile of quantifiers ("a*a*a*b" measured 3.4s on 200 chars). Group prefixes
    // like "(?:" are syntax, stripped first so "(?:ab)+" stays usable.
    // shortcut: 3+ quantifiers are refused even when bounded ("\d{2}/\d{2}/\d{4}"); upgrade to per-quantifier
    // width analysis when date-style patterns matter.
    private fun riskyPattern(pattern: String): Boolean {
        val p = pattern.replace(GROUP_PREFIX, "(")
        return NESTED_QUANTIFIER.containsMatchIn(p) ||
            p.count { it == '*' || it == '+' || it == '?' || it == '{' } > MAX_QUANTIFIERS
    }

    private fun template(text: String, event: SmsEvent): String {
        if (text.isBlank()) return ""
        return text.replace("{sender}", event.sender)
            .replace("{body}", event.body)
            .replace("{otp}", event.otp.orEmpty())
    }

    // ---------- decision trace: one line per SMS, newest first, capped ----------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FilterService.PREFS, Context.MODE_PRIVATE)

    fun recentDecisions(ctx: Context): List<String> = try {
        val a = JSONArray(prefs(ctx).getString(DEC_KEY, "[]") ?: "[]")
        (0 until a.length()).map { a.getString(it) }
    } catch (t: Throwable) {
        emptyList()
    }

    fun clearDecisions(ctx: Context) {
        prefs(ctx).edit().remove(DEC_KEY).apply()
    }

    // Never grows past DEC_MAX and never throws into the receive path.
    internal fun recordDecision(ctx: Context, line: String) {
        runCatching {
            val p = prefs(ctx)
            // A corrupted pref self-heals: unreadable old value starts a fresh list instead of failing forever.
            val old = runCatching { JSONArray(p.getString(DEC_KEY, "[]") ?: "[]") }.getOrDefault(JSONArray())
            val out = JSONArray().put(line)
            for (i in 0 until minOf(old.length(), DEC_MAX - 1)) out.put(old.get(i))
            p.edit().putString(DEC_KEY, out.toString()).apply()
        }.onFailure { Log.w("AddonRules", "decision trace write failed", it) }
    }

    // 40 code points, never half a surrogate pair: a lone surrogate is invalid UTF-16 in the stored trace.
    private fun head40(s: String) = s.substring(0, s.offsetByCodePoints(0, minOf(40, s.codePointCount(0, s.length))))

    // Display-ready line: who, what arrived, which rule(s) it hit, what actually ran (or why not).
    fun decisionLine(
        millis: Long,
        event: SmsEvent,
        matched: List<AddonRule>,
        ran: List<String>,
        miss: String?,
        cooled: List<String> = emptyList()
    ): String {
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(millis))
        val head = "$time · ${event.sender.ifBlank { "?" }} · ${head40(event.body).replace('\n', ' ')}"
        return when {
            matched.isNotEmpty() -> {
                val names = matched.joinToString(" + ") { it.name.ifBlank { it.id.take(6) } }
                val cool = if (cooled.isEmpty()) "" else "; cooling: ${cooled.joinToString(", ")}"
                "$head → $names: ${ran.joinToString(", ").ifBlank { "nothing (${miss ?: "no effect"})" }}$cool"
            }
            ran.isNotEmpty() -> "$head → legacy: ${ran.joinToString(", ")}"
            else -> "$head → no rule (${miss ?: "none"})"
        }
    }

    // Addon notifications get their own id space: an alert must never replace the SMS notification the
    // app posted under 2000 + address.hashCode(), and one id per conversation keeps a sender from colliding.
    // shortcut: 500k-wide windows, so only an exact 500k thread-id offset or a hash tie can collide;
    // upgrade to a persisted per-sender id map if notifications ever start vanishing.
    internal fun addonNotifyId(event: SmsEvent): Int {
        val key = event.threadId.takeIf { it > 0 }?.rem(500_000)
            ?: ((event.sender.hashCode() and 0x7FFFFFFF) % 500_000).toLong()
        return ADDON_NOTIF_ID + key.toInt()
    }

    private fun postAddonNotification(ctx: Context, event: SmsEvent, text: String, sound: Boolean): Boolean {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return false
        val channelId = "addon_actions"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(channelId, "Addon actions", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val n = android.app.Notification.Builder(ctx, channelId)
            .setContentTitle(event.sender.ifBlank { "SMS" })
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .apply { if (sound) setDefaults(android.app.Notification.DEFAULT_ALL) }
            .build()
        nm.notify(addonNotifyId(event), n)
        return true
    }
}