package fraudguard.core

/** Device attestation verdict, as Play Integrity reports it. */
enum class Integrity { STRONG, BASIC, FAILED }

/** The kind of network an IP belongs to, as an IP reputation provider reports it. */
enum class Net { RESIDENTIAL, MOBILE, DATACENTER, VPN }

enum class Method { PAYPAL, GIFT_CARD }

/** One account's activity on one day, as the backend logs it. Money is integer cents. */
data class DayLog(
    val account: Int,
    val day: Int,
    val minutes: Float,
    val earnedCents: Int,
    val adViews: Int,
    val offers: Int,
    val offerMinutes: Float,
    /** Standard deviation of session start hours that day: people are irregular, scripts are not. */
    val startSpreadH: Float,
    val net: Net,
    val ipPrefix: Int,
    val ipMatchesStoreCountry: Boolean,
    val device: Int,
)

data class AccountInfo(val id: Int, val signupDay: Int, val storeCountry: String)

/** A device as the app sees it: an install id, a hardware fingerprint that survives a reset, and attestation. */
data class DeviceInfo(val id: Int, val fingerprint: Int, val integrity: Integrity)

/** A cash-out request. [payoutAccount] is a hash of the PayPal or gift-card e-mail. */
data class Cashout(
    val id: Long,
    val account: Int,
    val day: Int,
    val hour: Int,
    val cents: Int,
    val method: Method,
    val payoutAccount: Int,
    val device: Int,
)

/** What a selfie check returns: whether the player finished it, whether it was a live person, and a face template. */
data class StepUpResult(val completed: Boolean, val live: Boolean, val faceTemplate: Int?)

enum class Action { PAY, STEP_UP, HOLD, BLOCK }

/** Why a cash-out was not paid at once. Shown to analysts; never shown to players. */
enum class Reason {
    EMULATOR, WEAK_DEVICE, VPN_OR_DATACENTER, GEO_MISMATCH, EARN_SPEED, SCRIPTED_TIMING, OFFER_SPEED,
    NEW_ACCOUNT, PAYOUT_SWITCH, RING, RING_CONFIRMED, SHARED_PAYOUT, SHARED_DEVICE, DATACENTER_NEIGHBOURS,
    LARGE_AMOUNT, FACE_REUSED, FACE_CHANGED, STEP_UP_FAILED, WATCHED,
}

data class Decision(val action: Action, val score: Double, val reasons: List<Reason>, val priority: Double = 0.0)
