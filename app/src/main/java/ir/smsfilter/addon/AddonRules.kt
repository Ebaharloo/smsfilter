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
        return active.isEmpty() || active.all { it }
    }

    // BuzzKill RuleFinder.b: every rule is tested, matching ones run in priority order.
    fun matching(ctx: Context, event: SmsEvent, nowMillis: Long = System.currentTimeMillis()): List<AddonRule> {
        ensureDefaults(ctx)
        // sortedBy is stable, so equal priorities keep their saved order.
        return load(ctx).sortedBy { it.priority }.filter { rule ->
            if (!rule.enabled) return@filter false
            val active = conditions(rule, event, nowMillis)
            (active.isNotEmpty() || rule.matchAny) && active.all { it }
        }
    }

    // The conditions matches() evaluates. A raw-blank value is skipped here, which is why a
    // spaces-only keyword counts as "no condition" and therefore makes the rule inert.
    private fun conditions(rule: AddonRule, event: SmsEvent, nowMillis: Long): MutableList<Boolean> {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = nowMillis }
        val active = mutableListOf<Boolean>()
        if (rule.senderContains.isNotBlank()) {
            // A term that normalizes to "" would make contains("") match every SMS: unusable, never true.
            val sender = Normalizer.normalize(rule.senderContains)
            active += sender.isNotEmpty() && Normalizer.normalize(event.sender).contains(sender)
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
            active += if (rule.keywordMode.trim().equals("OR", ignoreCase = true)) hits.any { it } else hits.all { it }
        }
        if (rule.minLength > 0) active += event.body.length >= rule.minLength
        if (rule.maxLength > 0) active += event.body.length <= rule.maxLength
        if (rule.daysOfWeek.isNotBlank()) {
            val days = rule.daysOfWeek.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
            if (days.isNotEmpty()) {
                active += cal.get(java.util.Calendar.DAY_OF_WEEK) in days
            }
        }
        if (rule.startHour >= 0 && rule.endHour >= 0) {
            val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
            active += if (rule.startHour <= rule.endHour) {
                h in rule.startHour..rule.endHour
            } else {
                h >= rule.startHour || h <= rule.endHour
            }
        }
        if (rule.onlyFromContacts) active += event.fromContact
        if (rule.onlySpam) active += event.isSpam
        if (rule.onlyNotSpam) active += !event.isSpam
        if (rule.otpRequired == true) active += event.otp != null
        if (rule.otpRequired == false) active += event.otp == null

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

    fun executeAll(ctx: Context, rule: AddonRule, event: SmsEvent, dryRun: Boolean = false) {
        for (a in rule.actions.ifEmpty { listOf(rule.action) }) {
            // One dead action (no email app, provider refusing the send) must not eat the rest of the rule.
            try {
                execute(ctx, rule.copy(action = a), event, dryRun)
            } catch (t: Throwable) {
                Log.e("AddonRules", "action $a of rule ${rule.id} failed", t)
            }
        }
    }

    fun execute(ctx: Context, rule: AddonRule, event: SmsEvent, dryRun: Boolean = false) {
        if (dryRun) {
            android.util.Log.d("AddonSelfTest", "dry-run action=${rule.action}")
            return
        }
        when (rule.action) {
            "copy_otp" -> event.otp?.let { code ->
                val cm = ctx.getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("code", code))
                Toast.makeText(ctx, "کد $code کپی شد", Toast.LENGTH_SHORT).show()
            }
            "copy_body" -> if (!event.isSpam) {
                val cm = ctx.getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("sms", event.body))
                Toast.makeText(ctx, "متن پیام کپی شد", Toast.LENGTH_SHORT).show()
            }
            "autoreply" -> if (!event.isSpam && event.fromContact && event.sender.isNotBlank()) {
                val template = rule.actionParam.ifBlank {
                    ctx.getSharedPreferences(FilterService.PREFS, Context.MODE_PRIVATE)
                        .getString("addon_autoreply_text", "پیام شما دریافت شد").orEmpty()
                }
                if (template.isNotBlank()) Sms.send(ctx, event.sender, template)
            }
            "forward" -> if (rule.actionParam.isNotBlank()) {
                Sms.send(ctx, rule.actionParam, event.body)
            }
            "mark_read" -> if (event.threadId > 0) {
                Sms.markThreadRead(ctx, event.threadId)
            }
            "mute" -> if (event.threadId > 0 && !ThreadFlags.isMuted(ctx, event.threadId)) {
                ThreadFlags.toggleMute(ctx, event.threadId)
            }
            "unmute" -> if (event.threadId > 0 && ThreadFlags.isMuted(ctx, event.threadId)) {
                ThreadFlags.toggleMute(ctx, event.threadId)
            }
            "pin" -> if (event.threadId > 0 && !ThreadFlags.isPinned(ctx, event.threadId)) {
                ThreadFlags.togglePin(ctx, event.threadId)
            }
            "unpin" -> if (event.threadId > 0 && ThreadFlags.isPinned(ctx, event.threadId)) {
                ThreadFlags.togglePin(ctx, event.threadId)
            }
            "archive" -> if (event.threadId > 0) {
                ThreadFlags.setArchived(ctx, event.threadId, true)
            }
            "unarchive" -> if (event.threadId > 0) {
                ThreadFlags.setArchived(ctx, event.threadId, false)
            }
            "quarantine" -> if (event.threadId > 0) {
                ThreadFlags.quarantine(ctx, event.threadId)
            }
            "restore" -> if (event.threadId > 0) {
                ThreadFlags.restore(ctx, event.threadId)
            }
            "block_sender" -> if (event.sender.isNotBlank()) {
                Learned(ctx).blockSender(event.sender)
            }
            "dismiss" -> if (event.notifyId > 0) {
                ctx.getSystemService(NotificationManager::class.java)?.cancel(event.notifyId)
            }
            "modify_text" -> if (event.notifyId > 0) {
                ctx.getSystemService(NotificationManager::class.java)?.cancel(event.notifyId)
                postAddonNotification(ctx, event, rule.actionParam.ifBlank { event.body }, true)
            }
            "alert" -> postAddonNotification(ctx, event, rule.actionParam.ifBlank { event.body }, true)
            "email" -> {
                val to = rule.emailTo.ifBlank { rule.actionParam }.trim()
                val subject = template(rule.emailSubject, event).ifBlank { "SMS from ${event.sender}" }
                val body = template(rule.emailBody, event).ifBlank { event.body }
                if (to.isNotBlank()) {
                    try {
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
                    } catch (t: Throwable) {
                        android.widget.Toast.makeText(ctx, "No email app available", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
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

    private fun postAddonNotification(ctx: Context, event: SmsEvent, text: String, sound: Boolean) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
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
        val id = if (event.notifyId > 0) event.notifyId else (event.threadId.toInt().takeIf { it > 0 } ?: 1)
        nm.notify(id, n)
    }
}