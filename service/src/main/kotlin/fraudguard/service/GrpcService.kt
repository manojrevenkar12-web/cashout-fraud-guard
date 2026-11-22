package fraudguard.service

import fraudguard.core.AccountInfo
import fraudguard.core.Cashout
import fraudguard.core.DayLog
import fraudguard.core.Decision
import fraudguard.core.DeviceInfo
import fraudguard.core.Integrity
import fraudguard.core.Method
import fraudguard.core.Net
import fraudguard.core.StepUpResult
import fraudguard.v1.Activity
import fraudguard.v1.ActivityAck
import fraudguard.v1.Appeal
import fraudguard.v1.AppealAnswer as AppealAnswerProto
import fraudguard.v1.CaseFile
import fraudguard.v1.CaseFileRequest
import fraudguard.v1.CashoutDecision
import fraudguard.v1.CashoutGuardGrpcKt
import fraudguard.v1.CashoutRequest
import fraudguard.v1.SelfieResult
import fraudguard.v1.Verdict
import fraudguard.v1.VerdictAck
import io.grpc.Status
import io.grpc.StatusException
import jakarta.inject.Inject

/** The gRPC face of [Guard]. Bad input is INVALID_ARGUMENT; an unknown request is NOT_FOUND. */
class GuardGrpcService @Inject constructor(private val guard: Guard) : CashoutGuardGrpcKt.CashoutGuardCoroutineImplBase() {
    private val requestId = Regex("[A-Za-z0-9._:-]{1,64}")

    private fun invalid(msg: String): Nothing = throw StatusException(Status.INVALID_ARGUMENT.withDescription(msg))

    private fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: IllegalArgumentException) {
        val unknown = e.message?.startsWith("unknown request") == true
        throw StatusException((if (unknown) Status.NOT_FOUND else Status.FAILED_PRECONDITION).withDescription(e.message))
    }

    private fun Decision.toProto(id: String, replayed: Boolean): CashoutDecision = CashoutDecision.newBuilder()
        .setRequestId(id)
        .setAction(fraudguard.v1.Action.valueOf(action.name))
        .setScore(score)
        .addAllReasons(reasons.map { it.name })
        .setPriority(priority)
        .setReplayed(replayed)
        .build()

    override suspend fun recordActivity(request: Activity): ActivityAck {
        if (request.account <= 0 || request.device <= 0) invalid("account and device are required")
        val net = when (request.network) {
            fraudguard.v1.Network.VPN -> Net.VPN
            fraudguard.v1.Network.DATACENTER -> Net.DATACENTER
            fraudguard.v1.Network.MOBILE -> Net.MOBILE
            else -> Net.RESIDENTIAL
        }
        val integrity = when (request.integrity) {
            fraudguard.v1.Integrity.FAILED -> Integrity.FAILED
            fraudguard.v1.Integrity.BASIC -> Integrity.BASIC
            else -> Integrity.STRONG
        }
        guard.recordActivity(
            DayLog(request.account, request.day, request.minutes, request.earnedCents, request.adViews, request.offers, request.offerMinutes, request.startSpreadH,
                net, request.ipPrefix, request.ipMatchesStoreCountry, request.device),
            DeviceInfo(request.device, request.deviceFingerprint, integrity),
            AccountInfo(request.account, request.signupDay, request.storeCountry.ifBlank { "US" }),
        )
        return ActivityAck.newBuilder().setAccount(request.account).build()
    }

    override suspend fun check(request: CashoutRequest): CashoutDecision {
        if (!requestId.matches(request.requestId)) invalid("request_id must be 1-64 of [A-Za-z0-9._:-]")
        if (request.account <= 0 || request.cents <= 0) invalid("account and a positive amount are required")
        val method = runCatching { Method.valueOf(request.method.uppercase()) }.getOrElse { invalid("method must be PAYPAL or GIFT_CARD") }
        val c = Cashout(request.requestId.hashCode().toLong(), request.account, request.day, request.hour, request.cents, method, request.payoutAccount, request.device)
        val (d, replayed) = guarded { guard.check(request.requestId, c) }
        return d.toProto(request.requestId, replayed)
    }

    override suspend fun submitSelfie(request: SelfieResult): CashoutDecision {
        val r = StepUpResult(request.completed, request.live, if (request.hasTemplate) request.faceTemplate else null)
        return guarded { guard.selfie(request.requestId, r) }.toProto(request.requestId, false)
    }

    override suspend fun getCaseFile(request: CaseFileRequest): CaseFile {
        val e = guarded { guard.caseFile(request.requestId) }
        return CaseFile.newBuilder().setAccount(e.account).setScore(e.score).addAllReasons(e.reasons.map { it.name }).putAllFeatures(e.features)
            .setRingSize(e.ringSize).setRingConfirmedFraud(e.ringConfirmedFraud).addAllRingSample(e.ringSample).setAccountsOnPayoutAccount(e.accountsOnPayoutAccount).build()
    }

    override suspend fun recordVerdict(request: Verdict): VerdictAck {
        if (request.analyst.isBlank()) invalid("a verdict needs the analyst who made it")
        return VerdictAck.newBuilder().addAllRingToReview(guarded { guard.verdict(request.account, request.fraud, request.analyst) }).build()
    }

    override suspend fun readAppeal(request: Appeal): AppealAnswerProto {
        if (request.text.length > 4000) invalid("appeal text is limited to 4000 characters")
        val a = guarded { guard.appeal(request.requestId, request.text) }
        return AppealAnswerProto.newBuilder().setLanguage(a.language).setClaim(a.claim).setRequeued(a.requeued).setReplyDraft(a.replyDraft).setReader(a.reader).build()
    }
}
