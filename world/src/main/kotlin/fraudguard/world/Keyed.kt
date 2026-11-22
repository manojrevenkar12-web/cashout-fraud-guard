package fraudguard.world

import java.util.SplittableRandom
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Keyed randomness: every draw is a pure function of (seed, what is drawn, which entity), so every design meets the
 * same players, the same bugs in their behaviour and the same analyst mistakes. Keys are strings and longs only,
 * whose hash codes are fixed across JVMs.
 */
class Keyed(private val seed: Long) {
    fun rng(tag: String, a: Long = 0, b: Long = 0, c: Long = 0): SplittableRandom {
        var h = seed * -7046029254386353131L xor tag.hashCode().toLong()
        for (x in longArrayOf(a, b, c)) {
            h = (h xor x) * -4658895280553007687L
            h = h xor (h ushr 31)
        }
        return SplittableRandom(h)
    }

    fun u(tag: String, a: Long = 0, b: Long = 0, c: Long = 0): Double = rng(tag, a, b, c).nextDouble()
}

fun SplittableRandom.normal(): Double {
    val u1 = nextDouble().coerceAtLeast(1e-12)
    val u2 = nextDouble()
    return sqrt(-2 * ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)
}

fun SplittableRandom.lognormal(median: Double, sigma: Double) = median * exp(sigma * normal())

fun SplittableRandom.between(lo: Int, hi: Int) = lo + nextInt(hi - lo + 1)

fun SplittableRandom.poisson(mean: Double): Int {
    val l = exp(-mean)
    var k = 0
    var p = 1.0
    do {
        k++
        p *= nextDouble()
    } while (p > l && k < 1000)
    return k - 1
}
