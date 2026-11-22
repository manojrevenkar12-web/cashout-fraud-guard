package fraudguard.core

import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * L2-regularised logistic regression on standardised features, fitted by full-batch gradient descent. Small on
 * purpose: the weights are readable, the fit is deterministic, and the top contributions become the reasons an
 * analyst sees.
 */
class RiskModel(val weights: DoubleArray, val bias: Double, val mean: DoubleArray, val sd: DoubleArray, val mask: Set<Int> = emptySet()) {
    private fun z(x: DoubleArray) = DoubleArray(x.size) { i -> if (i in mask) 0.0 else (x[i] - mean[i]) / sd[i] }

    fun score(x: DoubleArray): Double {
        val v = z(x)
        var s = bias
        for (i in v.indices) s += weights[i] * v[i]
        return 1.0 / (1.0 + exp(-s))
    }

    /** The reasons behind a score: features whose contribution raises the risk by at least [min] log-odds, largest first. */
    fun reasons(x: DoubleArray, min: Double = 0.4, max: Int = 3): List<Reason> {
        val v = z(x)
        return v.indices.map { it to weights[it] * v[it] }.filter { it.second >= min }.sortedByDescending { it.second }
            .map { Signals.REASONS[it.first] }.distinct().take(max)
    }

    /** The model as JSON, so the service loads exactly the model the evaluation measured. */
    fun toJson(trainedOn: String): kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.buildJsonObject {
        put("trained_on", kotlinx.serialization.json.JsonPrimitive(trainedOn))
        put("features", kotlinx.serialization.json.JsonArray(Signals.NAMES.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        put("bias", kotlinx.serialization.json.JsonPrimitive(bias))
        put("weights", kotlinx.serialization.json.JsonArray(weights.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        put("mean", kotlinx.serialization.json.JsonArray(mean.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        put("sd", kotlinx.serialization.json.JsonArray(sd.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        put("mask", kotlinx.serialization.json.JsonArray(mask.sorted().map { kotlinx.serialization.json.JsonPrimitive(it) }))
    }

    companion object {
        fun fromJson(o: kotlinx.serialization.json.JsonObject): RiskModel {
            fun arr(k: String) = o.arr(k).map { it.jsonPrimitive.double }.toDoubleArray()
            val names = o.arr("features").map { it.jsonPrimitive.content }
            require(names == Signals.NAMES) { "model features $names do not match this build's ${Signals.NAMES}" }
            return RiskModel(arr("weights"), o.d("bias"), arr("mean"), arr("sd"), o.arr("mask").map { it.jsonPrimitive.int }.toSet())
        }

        fun fit(xs: List<DoubleArray>, ys: List<Int>, l2: Double, iterations: Int, rate: Double = 0.5, mask: Set<Int> = emptySet()): RiskModel {
            require(xs.size == ys.size && xs.isNotEmpty()) { "need labelled examples" }
            val d = xs[0].size
            val mean = DoubleArray(d) { j -> xs.sumOf { it[j] } / xs.size }
            val sd = DoubleArray(d) { j -> sqrt(xs.sumOf { (it[j] - mean[j]) * (it[j] - mean[j]) } / xs.size).takeIf { s -> s > 1e-9 } ?: 1.0 }
            val z = xs.map { x -> DoubleArray(d) { j -> if (j in mask) 0.0 else (x[j] - mean[j]) / sd[j] } }
            val w = DoubleArray(d)
            var b = 0.0
            val n = z.size.toDouble()
            repeat(iterations) {
                val g = DoubleArray(d)
                var gb = 0.0
                for (k in z.indices) {
                    var s = b
                    val x = z[k]
                    for (j in 0 until d) s += w[j] * x[j]
                    val err = 1.0 / (1.0 + exp(-s)) - ys[k]
                    for (j in 0 until d) g[j] += err * x[j]
                    gb += err
                }
                for (j in 0 until d) w[j] -= rate * (g[j] / n + l2 * w[j])
                b -= rate * gb / n
            }
            return RiskModel(w, b, mean, sd, mask)
        }
    }
}
