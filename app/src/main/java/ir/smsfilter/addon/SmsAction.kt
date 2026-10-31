package ir.smsfilter.addon

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import ir.smsfilter.FilterService
import ir.smsfilter.IncomingSms
import ir.smsfilter.Sms

data class SmsEvent(
    val sender: String,
    val body: String,
    val date: Long,
    val subId: Int,
    val threadId: Long,
    val isSpam: Boolean,
    val fromContact: Boolean,
    val otp: String?,
    val notifyId: Int = 0
)

interface SmsAction {
    val id: String
    fun enabled(ctx: Context): Boolean = true
    fun onSmsReceived(ctx: Context, event: SmsEvent)
}

object AddonBus {
    private val actions = mutableListOf<SmsAction>()

    init {
        register(OtpAutoCopyAction)
        register(CopyBodyAction)
        register(AutoReplyAction)
    }

    fun register(action: SmsAction) {
        if (actions.none { it.id == action.id }) actions += action
    }

    fun unregister(id: String) {
        actions.removeAll { it.id == id }
    }

    // One decision line per SMS, never per rule. Everything is inside runCatching: a trace bug must
    // not break the receive path.
    fun dispatch(ctx: Context, event: SmsEvent) {
        val now = System.currentTimeMillis()
        var matched: List<AddonRule> = emptyList()
        val ran = mutableListOf<String>()
        val cooled = mutableListOf<String>()
        var miss: String? = null
        runCatching {
            matched = AddonRules.matching(ctx, event, now)
            if (matched.isEmpty()) {
                // No rule claims this SMS: the legacy per-toggle actions still run, unchanged.
                for (a in actions.toList()) {
                    if (!a.enabled(ctx)) continue
                    try {
                        a.onSmsReceived(ctx, event)
                        ran += a.id
                    } catch (t: Throwable) {
                        Log.e("SMSAddon", "action ${a.id} failed", t)
                    }
                }
                miss = AddonRules.firstMiss(ctx, event, now) ?: if (ran.isEmpty()) "no rule" else null
                return@runCatching
            }
            for (rule in matched) {
                if (!AddonRules.allowedNow(ctx, rule, now)) {
                    cooled += rule.name.ifBlank { rule.id.take(6) }
                    if (miss == null) miss = "cooldown"
                    continue
                }
                // One failing rule must not stop the rules after it.
                val ok = try {
                    AddonRules.executeAll(ctx, rule, event, ran = ran)
                } catch (t: Throwable) {
                    Log.e("SMSAddon", "rule ${rule.id} failed", t)
                    0
                }
                // Arming the cooldown on a total failure would swallow the retry window.
                if (ok > 0) AddonRules.markFired(ctx, rule, now)
                else if (miss == null) miss = "action failed"
            }
        }.onFailure { Log.e("SMSAddon", "dispatch failed", it) }
        AddonRules.recordDecision(ctx, AddonRules.decisionLine(now, event, matched, ran, miss, cooled))
    }
}

object OtpAutoCopyAction : SmsAction {
    override val id = "otp_autocopy"
    override fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(FilterService.PREFS, Context.MODE_PRIVATE)
            .getBoolean("addon_otp_autocopy", false)

    override fun onSmsReceived(ctx: Context, event: SmsEvent) {
        val code = event.otp ?: return
        val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText("code", code))
        Toast.makeText(ctx, "کد $code کپی شد", Toast.LENGTH_SHORT).show()
    }
}

object CopyBodyAction : SmsAction {
    override val id = "copy_body"
    override fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(FilterService.PREFS, Context.MODE_PRIVATE)
            .getBoolean("addon_copy_body", false)

    override fun onSmsReceived(ctx: Context, event: SmsEvent) {
        if (event.isSpam) return
        val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText("sms", event.body))
        Toast.makeText(ctx, "متن پیام کپی شد", Toast.LENGTH_SHORT).show()
    }
}

object AutoReplyAction : SmsAction {
    override val id = "autoreply"
    override fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(FilterService.PREFS, Context.MODE_PRIVATE)
            .getBoolean("addon_autoreply", false)

    override fun onSmsReceived(ctx: Context, event: SmsEvent) {
        if (event.isSpam) return
        if (!event.fromContact) return
        if (event.sender.isBlank()) return
        val prefs = ctx.getSharedPreferences(FilterService.PREFS, Context.MODE_PRIVATE)
        val template = prefs.getString("addon_autoreply_text", "پیام شما دریافت شد").orEmpty()
        if (template.isBlank()) return
        Sms.send(ctx, event.sender, template)
    }
}