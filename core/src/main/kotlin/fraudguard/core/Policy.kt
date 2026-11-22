package fraudguard.core

/** How a design decides. Values come from the guard settings file. */
data class PolicyConfig(
    val payBelow: Double,
    val reviewFrom: Double,
    val stepUp: Boolean,
    val useGraph: Boolean,
    val ringPropagation: Boolean,
    val priorityQueue: Boolean,
    val appealReader: Boolean,
    val holdConfirmedRing: Int,
    /** Days a passed selfie stays good before a borderline cash-out asks for another. */
    val selfieValidDays: Int = 30,
    /** Whether an analyst decides the rest of a confirmed ring in the same review, from one case file. */
    val ringReview: Boolean = true,
) {
    companion object {
        fun from(d: kotlinx.serialization.json.JsonObject) = PolicyConfig(
            payBelow = d.d("payBelow"), reviewFrom = d.d("reviewFrom"), stepUp = d.b("stepUp"), useGraph = d.b("useGraph"),
            ringPropagation = d.b("ringPropagation"), priorityQueue = d.b("priorityQueue"), appealReader = d.b("appealReader"),
            holdConfirmedRing = d.i("holdConfirmedRing"), ringReview = d.b("ringReview"),
        )
    }
}

/**
 * The cash-out decision. The model scores; this code decides. The guard never bans an account by itself: it pays,
 * asks for a selfie, or holds the cash-out for an analyst, who decides every ban.
 */
class Policy(val cfg: PolicyConfig, val model: RiskModel) {
    companion object {
        /** An account an analyst cleared still waits for a person when this many of its ring are confirmed fraud. */
        const val CLEARED_RING_LIMIT = 3
    }

    fun decide(c: Cashout, o: Observed): Decision {
        if (c.account in o.confirmedFraud) return Decision(Action.BLOCK, 1.0, listOf(Reason.RING_CONFIRMED))
        val x = Signals.features(c, o)
        val score = model.score(x)
        val reasons = model.reasons(x)
        if (c.account in o.watched) return Decision(Action.HOLD, score, listOf(Reason.WATCHED) + reasons, priority = 2.0 + score)
        val ringConfirmed = o.graph.ringConfirmed(c.account)
        val cleared = c.account in o.confirmedHonest
        if (cfg.useGraph && cfg.holdConfirmedRing > 0 && ringConfirmed >= cfg.holdConfirmedRing && (!cleared || ringConfirmed >= CLEARED_RING_LIMIT)) {
            return Decision(Action.HOLD, score, listOf(Reason.RING_CONFIRMED) + reasons, priority = 2.0 + score)
        }
        val selfieStale = c.day - (o.lastSelfieDay[c.account] ?: Int.MIN_VALUE / 2) > cfg.selfieValidDays
        // A shared phone or PayPal is a family or one person with many accounts; a selfie tells them apart.
        val shared = cfg.useGraph && o.graph.ringSize(c.account) >= 2
        return when {
            score >= cfg.reviewFrom -> Decision(Action.HOLD, score, reasons, priority = score * (1 + c.cents / 1000.0))
            cfg.stepUp && selfieStale && (score >= cfg.payBelow || shared) ->
                Decision(Action.STEP_UP, score, if (shared && score < cfg.payBelow) listOf(Reason.RING) else reasons)
            score < cfg.payBelow || cfg.stepUp -> Decision(Action.PAY, score, reasons)
            else -> Decision(Action.HOLD, score, reasons, priority = score * (1 + c.cents / 1000.0))
        }
    }

    /** After a selfie: pay a live, new face; hold a reused or changed face, or a selfie that was not finished. */
    fun afterStepUp(c: Cashout, r: StepUpResult, before: Decision, o: Observed): Decision {
        if (!r.completed || !r.live || r.faceTemplate == null) {
            return Decision(Action.HOLD, before.score, listOf(Reason.STEP_UP_FAILED) + before.reasons, priority = before.score)
        }
        val prior = o.faceOf[c.account]
        val others = o.recordFace(c.account, r.faceTemplate, c.day)
        return when {
            prior != null && prior != r.faceTemplate -> Decision(Action.HOLD, before.score, listOf(Reason.FACE_CHANGED) + before.reasons, priority = 1.5 + before.score)
            others.isNotEmpty() -> Decision(Action.HOLD, before.score, listOf(Reason.FACE_REUSED) + before.reasons, priority = 1.5 + before.score)
            else -> Decision(Action.PAY, before.score, before.reasons)
        }
    }

    /**
     * An analyst's verdict. On fraud, returns the rest of the ring for the same analyst to decide in the same review,
     * from one case file; the ones not decided stay on watch, so their next cash-outs wait for a person.
     */
    fun onVerdict(account: Int, fraud: Boolean, o: Observed): List<Int> {
        if (!fraud) {
            o.confirmHonest(account)
            return emptyList()
        }
        o.confirmFraud(account)
        if (!cfg.ringPropagation) return emptyList()
        val ring = o.graph.ringMembers(account).filter { it != account && it !in o.confirmedFraud && it !in o.confirmedHonest }
        o.watched.addAll(ring)
        return if (cfg.ringReview) ring else emptyList()
    }
}

/** An analyst's case file: what tripped, the ring, and the shared keys, so a review takes minutes, not a search. */
data class Evidence(
    val account: Int,
    val score: Double,
    val reasons: List<Reason>,
    val features: Map<String, Double>,
    val ringSize: Int,
    val ringConfirmedFraud: Int,
    val ringSample: List<Int>,
    val accountsOnPayoutAccount: Int,
) {
    companion object {
        fun of(c: Cashout, d: Decision, o: Observed): Evidence {
            val x = Signals.features(c, o)
            return Evidence(
                c.account, d.score, d.reasons, Signals.NAMES.zip(x.toList()).toMap(), o.graph.ringSize(c.account),
                o.graph.ringConfirmed(c.account), o.graph.ringMembers(c.account).filter { it != c.account }.sorted().take(20),
                o.graph.sharing(Graph.PAYOUT, c.payoutAccount),
            )
        }
    }
}
