package ir.smsfilter

import android.content.Context
import org.json.JSONObject
import ir.smsfilter.Normalizer.normalize

/**
 * Hashed char n-gram logistic regression, trained offline by tools/train.py.
 * Must match the Python feature extraction exactly: normalize -> drop spaces -> char n-grams
 * (n=2..5) -> FNV-1a 32bit -> bucket. Weights are int8 with one shared scale.
 */
class Model(j: JSONObject) {
    private val buckets: Int
    private val nMin: Int
    private val nMax: Int
    private val scale: Double
    private val bias: Double
    private val w: ShortArray

    init {
        buckets = j.getInt("m")
        nMin = j.getInt("ngram_min")
        nMax = j.getInt("ngram_max")
        scale = j.getDouble("scale")
        bias = j.getDouble("bias")
        // int16 little-endian: int8 was too coarse (moved ~27pp of decisions on real data)
        val raw = java.util.Base64.getDecoder().decode(j.getString("weights_b64"))
        w = ShortArray(buckets)
        for (i in 0 until buckets) {
            w[i] = ((raw[2 * i].toInt() and 0xFF) or (raw[2 * i + 1].toInt() shl 8)).toShort()
        }
    }

    /** spam probability in [0,1] */
    fun prob(text: String): Double {
        val s = normalize(text).replace(" ", "")
        if (s.isEmpty()) return 0.0
        var z = 0.0
        var n = 0
        for (size in nMin..nMax) {
            var i = 0
            while (i + size <= s.length) {
                val bucket = fnv1a(s.substring(i, i + size)) and (buckets - 1)
                z += w[bucket] * scale
                i++
                n++
            }
        }
        if (n == 0) return 0.0
        // training normalized by sqrt(total grams); mirror that so probabilities transfer
        z = z / Math.sqrt(n.toDouble()) + bias
        return 1.0 / (1.0 + Math.exp(-maxOf(-30.0, minOf(30.0, z))))
    }

    companion object {
        fun fromAssets(ctx: Context) =
            Model(JSONObject(ctx.assets.open("model.json").bufferedReader().use { it.readText() }))
    }

    private fun fnv1a(s: String): Int {
        var h = -2128831035 // 2166136261 as signed int
        for (b in s.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toInt() and 0xFF)
            h *= 16777619
        }
        return h and 0x7FFFFFFF
    }
}
