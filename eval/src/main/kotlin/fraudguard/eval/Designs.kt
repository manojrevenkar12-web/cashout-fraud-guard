package fraudguard.eval

import fraudguard.core.Action
import fraudguard.core.AppealReader
import fraudguard.core.AppealRoute
import fraudguard.core.Appeals
import fraudguard.core.Case
import fraudguard.core.Cashout
import fraudguard.core.Decision
import fraudguard.core.Desk
import fraudguard.core.Graph
import fraudguard.core.Integrity
import fraudguard.core.Net
import fraudguard.core.Observed
import fraudguard.core.Policy
import fraudguard.core.PolicyConfig
import fraudguard.core.Reason
import fraudguard.core.RiskModel
import fraudguard.core.RuleAppealReader
import fraudguard.core.Signals
import fraudguard.core.StepUpResult
import fraudguard.core.b
import fraudguard.core.d
import fraudguard.core.i
import fraudguard.core.obj
import fraudguard.core.s
import kotlinx.serialization.json.JsonObject

/** Features recorded at decision time, so the model trains on what was known then. */
class Recorder {
    val features = HashMap<Long, Pair<Int, DoubleArray>>() // cash-out -> (account, features)
    fun record(c: Cashout, o: Observed) {
        features[c.id] = c.account to Signals.features(c, o)
    }
}

/**
 * A setup many rewarded apps run: block a cash-out made over a VPN, ask for a selfie at the first payout (liveness
 * only), and send large cash-outs to analysts, who work them in arrival order without a case file.
 */
class TypicalDesk(cfg: JsonObject, private val rec: Recorder? = null) : Desk {
    override val name = "typical"
    private val reviewAbove = cfg.obj("typical").i("reviewAboveCents")
    override val evidenceFile = false
    override val draftsReplies = false

    override fun decide(c: Cashout, o: Observed): Decision {
        rec?.record(c, o)
        if (c.account in o.confirmedFraud) return Decision(Action.BLOCK, 1.0, listOf(Reason.RING_CONFIRMED))
        val today = o.window(c.account, c.day, c.day).lastOrNull()
        if (today != null && (today.net == Net.VPN || today.net == Net.DATACENTER)) return Decision(Action.BLOCK, 1.0, listOf(Reason.VPN_OR_DATACENTER))
        if (c.account !in o.faceOf) return Decision(Action.STEP_UP, 0.5, emptyList())
        if (c.cents >= reviewAbove) return Decision(Action.HOLD, 0.5, listOf(Reason.LARGE_AMOUNT), 0.0)
        return Decision(Action.PAY, 0.0, emptyList())
    }

    override fun afterStepUp(c: Cashout, r: StepUpResult, before: Decision, o: Observed): Decision {
        if (!r.completed || !r.live || r.faceTemplate == null) return Decision(Action.HOLD, 0.5, listOf(Reason.STEP_UP_FAILED), 0.0)
        o.recordFace(c.account, r.faceTemplate, c.day)
        return if (c.cents >= reviewAbove) Decision(Action.HOLD, 0.5, listOf(Reason.LARGE_AMOUNT), 0.0) else Decision(Action.PAY, 0.0, emptyList())
    }

    override fun order(queue: Collection<Case>) = queue.sortedWith(compareBy({ it.openedDay }, { it.cashout.id }))
    override fun onVerdict(account: Int, fraud: Boolean, o: Observed): List<Int> {
        if (fraud) o.confirmFraud(account) else o.confirmHonest(account)
        return emptyList()
    }
    override fun onAppeal(case: Case, text: String, o: Observed) = AppealRoute.REPLY_ONLY
}

/** Ban on any red flag: an emulator verdict, VPN use, a shared phone or a ring of three. */
class StrictDesk(cfg: JsonObject) : Desk {
    override val name = "strict_rules"
    private val s = cfg.obj("strict")
    override val evidenceFile = false
    override val draftsReplies = false

    override fun decide(c: Cashout, o: Observed): Decision {
        if (c.account in o.confirmedFraud) return Decision(Action.BLOCK, 1.0, listOf(Reason.RING_CONFIRMED))
        val x = Signals.features(c, o)
        val fp = o.devices[c.device]?.fingerprint
        val flags = buildList {
            if (o.devices[c.device]?.integrity == Integrity.FAILED) add(Reason.EMULATOR)
            if (x[2] >= s.d("vpnShare")) add(Reason.VPN_OR_DATACENTER)
            if (fp != null && o.graph.sharing(Graph.FINGERPRINT, fp) >= s.i("sharedFingerprint")) add(Reason.SHARED_DEVICE)
            if (o.graph.ringSize(c.account) >= s.i("ringSize")) add(Reason.RING)
        }
        if (flags.isNotEmpty()) return Decision(Action.BLOCK, 1.0, flags)
        if (c.account !in o.faceOf) return Decision(Action.STEP_UP, 0.5, emptyList())
        return Decision(Action.PAY, 0.0, emptyList())
    }

    override fun afterStepUp(c: Cashout, r: StepUpResult, before: Decision, o: Observed): Decision {
        if (!r.completed || !r.live || r.faceTemplate == null) return Decision(Action.HOLD, 0.5, listOf(Reason.STEP_UP_FAILED), 0.0)
        o.recordFace(c.account, r.faceTemplate, c.day)
        return Decision(Action.PAY, 0.0, emptyList())
    }

    override fun order(queue: Collection<Case>) = queue.sortedWith(compareBy({ it.openedDay }, { it.cashout.id }))
    override fun onVerdict(account: Int, fraud: Boolean, o: Observed): List<Int> {
        if (fraud) o.confirmFraud(account) else o.confirmHonest(account)
        return emptyList()
    }
    override fun onAppeal(case: Case, text: String, o: Observed) = AppealRoute.REPLY_ONLY
}

/** The guard: the core [Policy], an analyst queue ordered by money at risk, and the appeal reader. */
class GuardDesk(override val name: String, val policy: Policy, override val evidenceFile: Boolean, private val reader: AppealReader?) : Desk {
    override val draftsReplies = reader != null
    private val lastReasons = HashMap<Int, List<Reason>>()

    override fun decide(c: Cashout, o: Observed): Decision = policy.decide(c, o).also { lastReasons[c.account] = it.reasons }

    override fun afterStepUp(c: Cashout, r: StepUpResult, before: Decision, o: Observed) =
        policy.afterStepUp(c, r, before, o).also { lastReasons[c.account] = it.reasons }

    override fun order(queue: Collection<Case>): List<Case> =
        if (policy.cfg.priorityQueue) queue.sortedWith(compareByDescending<Case> { it.priority }.thenBy { it.openedDay }.thenBy { it.cashout.id })
        else queue.sortedWith(compareBy({ it.openedDay }, { it.cashout.id }))

    override fun onVerdict(account: Int, fraud: Boolean, o: Observed): List<Int> = policy.onVerdict(account, fraud, o)

    override fun onAppeal(case: Case, text: String, o: Observed): AppealRoute {
        val r = reader ?: return AppealRoute.REPLY_ONLY
        // a ban a person decided from a case file is re-reviewed only through support, not on the player's word
        if (case.cashout.account in o.confirmedFraud) return AppealRoute.REPLY_ONLY
        val reading = r.read(text)
        val reasons = case.decision.reasons.ifEmpty { lastReasons[case.cashout.account] ?: emptyList() }
        if (!Appeals.explains(reading.claim, reasons)) return AppealRoute.REPLY_ONLY
        case.claim = reading.claim
        case.priority += 1.0
        return AppealRoute.REQUEUE
    }
}

object Designs {
    fun spec(cfg: JsonObject, name: String): JsonObject {
        val d = cfg.obj("designs").obj(name)
        if (!d.containsKey("base")) return d
        val base = spec(cfg, d.s("base"))
        return JsonObject(base + d.filterKeys { it != "base" })
    }

    fun names(cfg: JsonObject): List<String> = cfg.obj("designs").keys.toList()

    fun policyConfig(d: JsonObject) = PolicyConfig.from(d)

    /** [models] is keyed by (trained on rule labels, graph features masked). */
    fun desk(cfg: JsonObject, name: String, models: (Boolean, Boolean) -> RiskModel): Desk {
        val d = spec(cfg, name)
        return when (d.s("kind")) {
            "typical" -> TypicalDesk(cfg)
            "strict" -> StrictDesk(cfg)
            else -> {
                val pc = policyConfig(d)
                val m = models(d.b("trainOnRuleLabels"), !pc.useGraph)
                GuardDesk(name, Policy(pc, m), d.b("evidenceFile"), if (pc.appealReader) RuleAppealReader else null)
            }
        }
    }
}
