package fraudguard.eval

import fraudguard.world.Events
import fraudguard.world.Kind
import fraudguard.world.World
import java.util.SplittableRandom

/** One simulated week of one design, in dollars, counts and shares. */
class Week(val v: Map<String, Double>)

object Metrics {
    val KEYS = listOf(
        "total_cost", "fraud_loss", "friction_loss", "analyst_cost", "support_cost", "step_up_cost", "fraud_paid_share", "wrong_bans",
        "wrong_bans_per_10k", "honest_late_share", "analyst_hours", "step_ups", "honest_appeals_requeued", "fraud_appeals_requeued",
    )

    fun weeks(e: Events, w: World): List<Week> {
        val h = w.historyDays
        val paid = e.paid.associateBy { it.cashout }
        val out = ArrayList<Week>()
        for (wk in 0 until w.liveDays / 7) {
            val days = (h + wk * 7) until (h + wk * 7 + 7)
            val req = e.requests.filter { it.day in days }
            val honest = req.filter { !it.fraud }
            val fraud = req.filter { it.fraud }
            val fraudLoss = e.paid.filter { it.reqDay in days && it.fraud }.sumOf { it.lossCents } / 100
            val friction = e.churn.filter { it.day in days }.sumOf { it.lossCents } / 100
            val minutes = days.sumOf { e.analystMinutes[it] ?: 0.0 }
            val stepUps = days.sumOf { e.stepUps[it] ?: 0 }
            val analystCost = w.analystCostCents(minutes) / 100
            val stepUpCost = w.stepUpCostCents(stepUps) / 100
            val supportCost = w.supportCostCents(days.sumOf { e.supportMinutes[it] ?: 0.0 }) / 100
            val fraudCents = fraud.sumOf { it.cents.toLong() }.toDouble()
            val fraudPaid = fraud.sumOf { r -> if (r.cashout in paid) r.cents.toLong() else 0L }.toDouble()
            val wrong = e.blocks.count { it.day in days && !it.fraud }
            val late = honest.count { r -> (paid[r.cashout]?.waitHours ?: Double.MAX_VALUE) > 24.0 }
            val ap = e.appeals.filter { it.day in days }
            out.add(
                Week(
                    mapOf(
                        "total_cost" to fraudLoss + friction + analystCost + supportCost + stepUpCost,
                        "support_cost" to supportCost,
                        "fraud_loss" to fraudLoss,
                        "friction_loss" to friction,
                        "analyst_cost" to analystCost,
                        "step_up_cost" to stepUpCost,
                        "fraud_paid_share" to if (fraudCents > 0) fraudPaid / fraudCents else 0.0,
                        "wrong_bans" to wrong.toDouble(),
                        "wrong_bans_per_10k" to if (honest.isNotEmpty()) wrong * 10_000.0 / honest.size else 0.0,
                        "honest_late_share" to if (honest.isNotEmpty()) late.toDouble() / honest.size else 0.0,
                        "analyst_hours" to minutes / 60,
                        "step_ups" to stepUps.toDouble(),
                        "honest_appeals_requeued" to ap.count { !it.fraud && it.requeued }.toDouble(),
                        "fraud_appeals_requeued" to ap.count { it.fraud && it.requeued }.toDouble(),
                    ),
                ),
            )
        }
        return out
    }

    /** Facts about a design's whole live period that are not weekly sums. */
    fun extras(e: Events, w: World): Map<String, Double> {
        val h = w.historyDays
        val liveBlocks = e.blocks.filter { it.day >= h }
        val farmAccounts = e.requests.filter { it.day >= h && it.kind == Kind.FARM }.map { it.account }.toSet()
        val paidCount = e.paid.filter { it.reqDay >= h && it.kind == Kind.FARM }.groupingBy { it.account }.eachCount()
        val stoppedEarly = farmAccounts.count { (paidCount[it] ?: 0) <= 1 }
        val honestAppeals = e.appeals.filter { it.day >= h && !it.fraud }
        val paidBy = e.paid.associateBy { it.cashout }
        val flow = HashMap<String, Double>()
        for (who in listOf("honest", "fraud")) for (how in listOf("paid_now", "paid_after_selfie", "paid_later", "not_paid")) flow["flow_${who}_$how"] = 0.0
        for (r in e.requests.filter { it.day >= h }) {
            val who = if (r.fraud) "fraud" else "honest"
            val wait = paidBy[r.cashout]?.waitHours
            val how = when {
                wait == null -> "not_paid"
                wait == 0.0 -> "paid_now"
                wait <= 0.2 -> "paid_after_selfie"
                else -> "paid_later"
            }
            flow.merge("flow_${who}_$how", 1.0, Double::plus)
        }
        return flow + mapOf(
            "bans_by_a_person_share" to if (liveBlocks.isEmpty()) 1.0 else liveBlocks.count { it.byPerson }.toDouble() / liveBlocks.size,
            "farm_accounts_stopped_by_second_payout_share" to if (farmAccounts.isEmpty()) 0.0 else stoppedEarly.toDouble() / farmAccounts.size,
            "honest_restored" to e.restored.count { it.second >= h && !it.third }.toDouble(),
            "honest_appeals" to honestAppeals.size.toDouble(),
            "honest_held_open_at_end" to e.heldOpenAtEnd.count { !it.first }.toDouble(),
        ) + Kind.entries.filter { it != Kind.HONEST }.associate { k ->
            "fraud_loss_${k.name.lowercase()}" to e.paid.filter { it.reqDay >= h && it.fraud && it.kind == k }.sumOf { it.lossCents } / 100
        }
    }

    /** Mean per week with a 95% interval, resampling whole weeks. */
    fun boot(xs: List<Double>, n: Int = 2000, seed: Long = 7): List<Double> {
        val r = SplittableRandom(seed)
        val means = DoubleArray(n) { (0 until xs.size).sumOf { xs[r.nextInt(xs.size)] } / xs.size }.sorted()
        return listOf(xs.average(), means[(n * 0.025).toInt()], means[(n * 0.975).toInt() - 1])
    }

    /** 1 - design/baseline on the same resampled weeks (the weeks are paired by seed and week). */
    fun bootCut(design: List<Double>, base: List<Double>, n: Int = 2000, seed: Long = 11): List<Double> {
        val r = SplittableRandom(seed)
        val cuts = DoubleArray(n) {
            var a = 0.0
            var b = 0.0
            repeat(design.size) { val k = r.nextInt(design.size); a += design[k]; b += base[k] }
            1 - a / b
        }.sorted()
        return listOf(1 - design.sum() / base.sum(), cuts[(n * 0.025).toInt()], cuts[(n * 0.975).toInt() - 1])
    }
}
