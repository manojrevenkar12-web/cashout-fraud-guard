package fraudguard.service

import fraudguard.core.AccountInfo
import fraudguard.core.Action
import fraudguard.core.AppealReader
import fraudguard.core.Appeals
import fraudguard.core.Cashout
import fraudguard.core.DayLog
import fraudguard.core.Decision
import fraudguard.core.DeviceInfo
import fraudguard.core.Evidence
import fraudguard.core.Observed
import fraudguard.core.Policy
import fraudguard.core.RuleAppealReader
import fraudguard.core.StepUpResult
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What the appeal reader made of a message, and the reply draft for support to check and send. */
data class AppealAnswer(val language: String, val claim: String, val requeued: Boolean, val replyDraft: String, val reader: String)

/**
 * The service's logic, independent of gRPC and of the database: the same [Policy] the evaluation measured, the
 * observed store fed from the activity topic, and a [DecisionStore] that keeps each request's first decision once.
 */
class Guard(private val policy: Policy, private val store: DecisionStore, private val reader: AppealReader = RuleAppealReader) {
    private val obs = Observed()
    private val lock = Any()

    fun recordActivity(log: DayLog, device: DeviceInfo, info: AccountInfo) = synchronized(lock) {
        if (info.id !in obs.accounts) obs.addAccount(info)
        if (device.id !in obs.devices) obs.addDevice(device)
        obs.log(log)
    }

    private fun event(requestId: String, c: Cashout, d: Decision) = buildJsonObject {
        put("request_id", requestId)
        put("account", c.account)
        put("cents", c.cents)
        put("action", d.action.name)
        put("score", JsonPrimitive(d.score))
        put("reasons", d.reasons.joinToString(",") { it.name })
    }.toString()

    /** Decides a cash-out once. A retry with the same request id gets the decision on record back, marked replayed. */
    fun check(requestId: String, c: Cashout): Pair<Decision, Boolean> = synchronized(lock) {
        store.latest(requestId)?.let { return it.second to true }
        obs.seePayout(c.account, c.payoutAccount, c.day)
        val d = policy.decide(c, obs)
        store.saveFirst(requestId, c, d, event(requestId, c, d))
    }

    fun selfie(requestId: String, r: StepUpResult): Decision = synchronized(lock) {
        val (c, before) = store.latest(requestId) ?: throw IllegalArgumentException("unknown request $requestId")
        require(before.action == Action.STEP_UP) { "request $requestId is not waiting for a selfie (${before.action})" }
        val d = policy.afterStepUp(c, r, before, obs)
        store.saveNext(requestId, d, event(requestId, c, d))
        d
    }

    fun caseFile(requestId: String): Evidence = synchronized(lock) {
        val (c, d) = store.latest(requestId) ?: throw IllegalArgumentException("unknown request $requestId")
        Evidence.of(c, d, obs)
    }

    /** An analyst's verdict. Returns the rest of the ring for the same analyst to decide now. */
    fun verdict(account: Int, fraud: Boolean, analyst: String): List<Int> = synchronized(lock) {
        require(analyst.isNotBlank()) { "a verdict needs the analyst who made it" }
        val ring = policy.onVerdict(account, fraud, obs)
        store.saveVerdict(account, fraud, analyst, buildJsonObject { put("account", account); put("fraud", fraud); put("analyst", analyst) }.toString())
        ring
    }

    /** Reads an appeal. The reading may move the case up the queue; it never pays, unblocks or names a check. */
    fun appeal(requestId: String, text: String): AppealAnswer = synchronized(lock) {
        val (c, d) = store.latest(requestId) ?: throw IllegalArgumentException("unknown request $requestId")
        val reading = try {
            reader.read(text)
        } catch (e: Exception) {
            RuleAppealReader.read(text)
        }
        val requeue = c.account !in obs.confirmedFraud && d.action == Action.HOLD && Appeals.explains(reading.claim, d.reasons)
        AppealAnswer(reading.language, reading.claim.name, requeue, Appeals.reply(reading, requeue), if (reader === RuleAppealReader) "rules" else "model")
    }
}
