package fraudguard.core

/** A cash-out waiting for an analyst. */
class Case(val cashout: Cashout, val decision: Decision, val openedDay: Int, var priority: Double) {
    var claim: Claim? = null
    var appealed = false
    /** Selfie attempts so far; a case waiting for the player's selfie is not an analyst's work yet. */
    var selfieAttempts = 0
    val awaitingSelfie: Boolean get() = decision.reasons.firstOrNull() == Reason.STEP_UP_FAILED && selfieAttempts < MAX_SELFIE_ATTEMPTS

    companion object {
        const val MAX_SELFIE_ATTEMPTS = 3
    }
}

enum class AppealRoute { REQUEUE, REPLY_ONLY }

/**
 * A way of deciding cash-outs. The world calls it for every request, every selfie result, every analyst verdict and
 * every appeal, and answers with what happens next. The production service implements the same calls.
 */
interface Desk {
    val name: String
    fun decide(c: Cashout, o: Observed): Decision
    fun afterStepUp(c: Cashout, r: StepUpResult, before: Decision, o: Observed): Decision
    /** The order analysts work the queue in. */
    fun order(queue: Collection<Case>): List<Case>
    /** Whether analysts get a case file with the ring and the signals. */
    val evidenceFile: Boolean
    /** Whether support gets a drafted reply in the player's language for each appeal, to check and send. */
    val draftsReplies: Boolean
    /** Records an analyst's verdict; returns ring members the same analyst should decide in the same review. */
    fun onVerdict(account: Int, fraud: Boolean, o: Observed): List<Int>
    fun onAppeal(case: Case, text: String, o: Observed): AppealRoute
}
