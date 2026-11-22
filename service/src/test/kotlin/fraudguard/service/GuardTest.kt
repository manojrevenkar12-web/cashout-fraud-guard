package fraudguard.service

import fraudguard.core.AccountInfo
import fraudguard.core.Action
import fraudguard.core.Cashout
import fraudguard.core.DayLog
import fraudguard.core.DeviceInfo
import fraudguard.core.Integrity
import fraudguard.core.Method
import fraudguard.core.Net
import fraudguard.core.Reason
import fraudguard.core.StepUpResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GuardTest {
    private val store = MemoryDecisionStore()
    private val guard = Guard(GuardModule.loadPolicy(), store)

    private fun activity(account: Int, device: Int, fingerprint: Int, day: Int = 10) = guard.recordActivity(
        DayLog(account, day, 45f, 40, 15, 0, 0f, 3f, Net.MOBILE, 7, true, device),
        DeviceInfo(device, fingerprint, Integrity.STRONG),
        AccountInfo(account, 0, "US"),
    )

    private fun cashout(account: Int, device: Int, payout: Int, cents: Int = 500) = Cashout(0, account, 11, 9, cents, Method.PAYPAL, payout, device)

    @Test
    fun theExportedModelLoadsAndARetryGetsTheDecisionOnRecord() {
        activity(1, 101, 9001)
        val (first, replayed) = guard.check("req-1", cashout(1, 101, 5001))
        assertFalse(replayed)
        val (again, replayed2) = guard.check("req-1", cashout(1, 101, 5001, cents = 99_999))
        assertTrue(replayed2)
        assertEquals(first, again)
        assertEquals(1, store.unpublished(10).size, "one outbox event per decision, not per call")
    }

    @Test
    fun oneFaceOnTwoAccountsSharingAPhoneWaitsForAnAnalystWithTheRingInTheCaseFile() {
        activity(1, 101, 9001)
        activity(2, 102, 9001) // same hardware, second account
        val (d1, _) = guard.check("a", cashout(1, 101, 5001))
        assertEquals(Action.STEP_UP, d1.action)
        assertEquals(Action.PAY, guard.selfie("a", StepUpResult(true, true, 77)).action)
        val (d2, _) = guard.check("b", cashout(2, 102, 5002))
        assertEquals(Action.STEP_UP, d2.action)
        val held = guard.selfie("b", StepUpResult(true, true, 77))
        assertEquals(Action.HOLD, held.action)
        assertEquals(Reason.FACE_REUSED, held.reasons.first())
        val file = guard.caseFile("b")
        assertEquals(2, file.ringSize)
        assertEquals(listOf(1), file.ringSample)
        assertEquals(listOf(1), guard.verdict(2, fraud = true, analyst = "ana"), "the analyst decides the rest of the ring now")
        assertThrows(IllegalArgumentException::class.java) { guard.selfie("b", StepUpResult(true, true, 77)) }
    }

    @Test
    fun anAppealGetsADraftInThePlayersLanguageThatNamesNoCheck() {
        activity(1, 101, 9001)
        activity(2, 102, 9001)
        guard.check("a", cashout(1, 101, 5001))
        guard.selfie("a", StepUpResult(false, false, null)) // the selfie was not finished: held
        val a = guard.appeal("a", "¿Por qué mi retiro está pendiente? Mi hijo juega en el mismo teléfono, cada uno tiene su cuenta.")
        assertEquals("es", a.language)
        assertEquals("FAMILY_SHARES_DEVICE", a.claim)
        assertEquals("rules", a.reader)
        assertTrue(a.replyDraft.startsWith("Gracias"))
        for (r in Reason.entries) assertFalse(r.name in a.replyDraft)
        assertThrows(IllegalArgumentException::class.java) { guard.appeal("nope", "hi") }
        assertThrows(IllegalArgumentException::class.java) { guard.verdict(1, true, " ") }
    }
}
