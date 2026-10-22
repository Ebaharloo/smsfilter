package ir.smsfilter

import android.content.Context
import org.json.JSONObject
import ir.smsfilter.Normalizer.normalize
import ir.smsfilter.Normalizer.squash

data class SpamGroup(val weight: Int, val terms: List<String>)

class RuleSet(json: JSONObject) {

    /** hard groups (OTP, transactions, government) - declared first because [guardAbs] sorts on it */
    val guardHard: List<String> = json.getJSONArray("guard_hard").strings()

    /** hard groups first, once: sorting this per message was pure waste */
    val guardAbs: List<Pair<String, List<String>>> = buildList {
        val g = json.getJSONObject("guard")
        for (name in g.keys()) {
            if (name.startsWith("_")) continue
            add(name to g.getJSONArray(name).strings())
        }
    }.sortedByDescending { it.first in guardHard }

    val softGuardConf = json.getJSONObject("model").getDouble("soft_guard_conf")

    val spam: List<SpamGroup> = buildList {
        val s = json.getJSONObject("spam")
        for (group in s.keys()) {
            if (group.startsWith("_")) continue
            val o = s.getJSONObject(group)
            add(SpamGroup(o.getInt("weight"), o.getJSONArray("terms").strings()))
        }
    }

    private val st = json.getJSONObject("structure")
    val shortlinkHosts = st.getJSONArray("shortlink_hosts").strings()
    val urlWeight = st.getInt("url_weight")
    val shortlinkWeight = st.getInt("shortlink_weight")
    val emojiWeight = st.getInt("emoji_ge5_weight")
    val spacedWeight = st.getInt("spaced_letters_weight")
    val unknownSenderWeight = st.getInt("unknown_sender_weight")

    private val th = json.getJSONObject("thresholds")
    val thrSpam = th.getInt("spam")
    val thrUnsure = th.getInt("unsure")
    val amountRegex = Regex(th.getString("amount_regex"))

    private val mdl = json.getJSONObject("model")
    val spamP = mdl.getDouble("spam_p")
    val unsureP = mdl.getDouble("unsure_p")

    private val snd = json.getJSONObject("senders")
    val allowExact = snd.getJSONArray("allow_exact").strings()
    val allowContains = snd.getJSONArray("allow_contains").strings()
    val blockExact = snd.getJSONArray("block_exact").strings()

    private val memo = java.util.concurrent.ConcurrentHashMap<String, String>(512)

    /**
     * Squashed normalized form of a rule term, memoized.
     * classify() looks up hundreds of terms per message and re-normalizing each one (regex work)
     * dominated the scan of a 4000-message inbox.
     */
    fun sq(term: String): String = memo[term] ?: squash(normalize(term)).also { memo[term] = it }

    companion object {
        @Volatile private var cached: RuleSet? = null

        /** Rules ship in assets/rules.json (symlinked from /rules) so the app and the Python mirror share one file. */
        fun load(ctx: Context): RuleSet = cached ?: synchronized(this) {
            cached ?: ctx.assets.open("rules.json").bufferedReader().use {
                RuleSet(JSONObject(it.readText())).also { r -> cached = r }
            }
        }
    }
}

fun org.json.JSONArray.strings(): List<String> =
    (0 until length()).map { optString(it) }
