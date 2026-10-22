package ir.smsfilter

/**
 * Persian normalization. Must stay in sync with tools/mirror.py:normalize().
 * Order matters: unify letters -> digits -> drop invisibles/diacritics -> collapse.
 */
object Normalizer {
    private const val ZWNJ = '\u200C'
    private const val ZWJ = '\u200D'
    private const val LRM = '\u200E'
    private const val RLM = '\u200F'
    private const val TATWEEL = '\u0640'

    private val LETTER_MAP = mapOf(
        'ي' to 'ی', 'ى' to 'ی', 'ك' to 'ک', 'ۀ' to 'ه', 'أ' to 'ا', 'إ' to 'ا',
        'آ' to 'ا', 'ؤ' to 'و', 'ة' to 'ه', 'ئ' to 'ی'
    )

    fun normalize(input: String): String {
        val sb = StringBuilder(input.length)
        for (c in input) {
            val mapped = LETTER_MAP[c]
            when {
                mapped != null -> sb.append(mapped)
                c in '۰'..'۹' -> sb.append('0' + (c - '۰'))
                c in '٠'..'٩' -> sb.append('0' + (c - '٠'))
                c == ZWNJ || c == ZWJ -> sb.append(' ')
                c == LRM || c == RLM || c == TATWEEL -> {}
                c in '\u064B'..'\u0652' || c == '\u0670' -> {}
                else -> sb.append(c.lowercaseChar())
            }
        }
        return sb.toString().replace(REPEAT_PUNCT, "$1").replace(SPACES, " ").trim()
    }

    private val REPEAT_PUNCT = Regex("([!?؟.,،*\\-_=])\\1{2,}")
    private val SPACES = Regex("\\s+")

    /** Keyword matching form: no spaces at all, so spaced-out evasion ("ت خ ف ی ف") still matches. */
    fun squash(s: String): String = s.replace(" ", "")
}
