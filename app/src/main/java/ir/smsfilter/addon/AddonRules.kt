package ir.smsfilter.addon

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
    val matchAny: Boolean = false
)

object AddonRules {
    private const val KEY = "addon_rules_json"
    private const val MAX_REGEX_SUBJECT = 200
    private const val MAX_PATTERN_LEN = 120
    private const val MAX_QUANTIFIERS = 2
    private const val ADDON_NOTIF_ID = 1_000_000
    private const val DEC_KEY = "addon_decisions"
    internal const val DEC_MAX = 20
    const val MAX_COOLDOWN = 86_400
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
                    matchAny = o.optBoolean("matchAny", false)
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
            o.put("id", r.id)
            o.put("name", r.name)
            o.put("enabled", r.enabled)
            o.put("priority", r.priority)
            o.put("senderContains", r.senderContains)
            o.put("keywordContains", r.keywords.firstOrNull() ?: r.keywordContains)
            o.put("keywords", JSONArray(r.keywords))
            o.put("keywordMode", r.keywordMode)
            o.put("keywordRegex", r.keywordRegex)
            o.put("minLength", r.minLength)
            o.put("maxLength", r.maxLength)
            o.put("daysOfWeek", r.daysOfWeek)
            o.put("startHour", r.startHour)
            o.put("endHour", r.endHour)
            o.put("onlyFromContacts", r.onlyFromContacts)
            o.put("onlySpam", r.onlySpam)
            o.put("onlyNotSpam", r.onlyNotSpam)
            if (r.otpRequired != null) o.put("otpRequired", r.otpRequired)
            o.put("emailTo", r.emailTo)
            o.put("emailSubject", r.emailSubject)
            o.put("emailBody", r.emailBody)
            o.put("emailAppPackage", r.emailAppPackage)
            o.put("action", r.action)
            o.put("actionParam", r.actionParam)
            o.put("actions", JSONArray(r.actions))
            o.put("cooldownSeconds", r.cooldownSeconds)
            o.put("matchAny", r.matchAny)
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
            val active = conditions(rule, event, nowMillis)
            (active.isNotEmpty() || rule.matchAny) && active.all { it.second }
        }
    }

    // Why this rule said no: the first failing condition, "no conditions" for an inert rule.
    // The dry-run dialog and the decision trace both read it; null means the rule matches.
    fun missReason(rule: AddonRule, event: SmsEvent, nowMillis: Long = System.currentTimeMillis()): String? {
        if (!rule.enabled) return "disabled"
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
            val hits = terms.map { term ->
                if (term.isBlank()) false
                else if (rule.keywordRegex) regexHit(term, body)
                else {
                    val kw = Normalizer.normalize(term)
                    kw.isNotEmpty() && (body.contains(kw) || squashed.contains(Normalizer.squash(kw)))
                }
            }
            active += "keyword" to if (rule.keywordMode.trim().equals("OR", ignoreCase = true)) hits.any { it } else hits.all { it }
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

    // Clamp before it reaches the model: 11+ digits used to overflow toIntOrNull() into 0 = "every time".
    fun clampCooldown(text: String): Int =
        text.filter { it.isDigit() }.take(9).toIntOrNull()?.coerceAtMost(MAX_COOLDOWN) ?: 0

    fun executeAll(ctx: Context, rule: AddonRule, event: SmsEvent, dryRun: Boolean = false, ran: MutableList<String>? = null): Int {
        var ok = 0
        for (a in rule.actions.ifEmpty { listOf(rule.action) }) {
            // One dead action (no email app, provider refusing the send) must not eat the rest of the rule.
            try {
                if (execute(ctx, rule.copy(action = a), event, dryRun)) {
                    ok++
                    ran?.add(a)
                }
            } catch (t: Throwable) {
                Log.e("AddonRules", "action $a of rule ${rule.id} failed", t)
            }
        }
        return ok
    }

    // Success means "did the thing", not "did not throw": a guarded-out action must leave the cooldown
    // unarmed, or a rule that did nothing blocks itself for its whole window.
    fun execute(ctx: Context, rule: AddonRule, event: SmsEvent, dryRun: Boolean = false): Boolean {
        if (dryRun) {
            android.util.Log.d("AddonSelfTest", "dry-run action=${rule.action}")
            return true
        }
        return when (rule.action) {
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
                val template = rule.actionParam.ifBlank {
                    ctx.getSharedPreferences(FilterService.PREFS, Context.MODE_PRIVATE)
                        .getString("addon_autoreply_text", "پیام شما دریافت شد").orEmpty()
                }
                if (template.isBlank()) false else send(ctx, event.sender, template)
            } else false
            "forward" -> if (rule.actionParam.isNotBlank()) {
                send(ctx, rule.actionParam, event.body)
            } else false
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
                postAddonNotification(ctx, event, rule.actionParam.ifBlank { event.body }, true)
            } else false
            "alert" -> postAddonNotification(ctx, event, rule.actionParam.ifBlank { event.body }, true)
            "email" -> {
                val to = rule.emailTo.ifBlank { rule.actionParam }.trim()
                val subject = template(rule.emailSubject, event).ifBlank { "SMS from ${event.sender}" }
                val body = template(rule.emailBody, event).ifBlank { event.body }
                if (to.isBlank()) false else try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "message/rfc822"
                        putExtra(android.content.Intent.EXTRA_EMAIL, arrayOf(to))
                        putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
                        putExtra(android.content.Intent.EXTRA_TEXT, body)
                        if (rule.emailAppPackage.isNotBlank()) setPackage(rule.emailAppPackage)
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (rule.emailAppPackage.isBlank()) {
                        ctx.startActivity(android.content.Intent.createChooser(intent, "Send email"))
                    } else {
                        ctx.startActivity(intent)
                    }
                    true
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