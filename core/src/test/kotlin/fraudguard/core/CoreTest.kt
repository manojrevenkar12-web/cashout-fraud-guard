package fraudguard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoreTest {
    private fun obs(): Observed = Observed().apply {
        for (a in 1..6) addAccount(AccountInfo(a, 0, "US"))
        addDevice(DeviceInfo(10, fingerprint = 500, integrity = Integrity.STRONG))
        addDevice(DeviceInfo(11, fingerprint = 500, integrity = Integrity.STRONG))
        addDevice(DeviceInfo(12, fingerprint = 600, integrity = Integrity.FAILED))
        addDevice(DeviceInfo(13, fingerprint = 700, integrity = Integrity.STRONG))
    }

    private fun log(o: Observed, account: Int, device: Int, day: Int, net: Net = Net.MOBILE, prefix: Int = 1) =
        o.log(DayLog(account, day, 40f, 40, 13, 0, 0f, 3f, net, prefix, true, device))

    private fun model(weights: DoubleArray = DoubleArray(Signals.NAMES.size), bias: Double = -3.0) =
        RiskModel(weights, bias, DoubleArray(Signals.NAMES.size), DoubleArray(Signals.NAMES.size) { 1.0 })

    private val cfg = PolicyConfig(payBelow = 0.2, reviewFrom = 0.6, stepUp = true, useGraph = true, ringPropagation = true, priorityQueue = true, appealReader = true, holdConfirmedRing = 1)

    @Test
    fun ringsFormFromSharedFingerprintsAndPayoutAccounts() {
        val o = obs()
        log(o, 1, 10, 0)
        log(o, 2, 11, 0) // same phone hardware as account 1
        o.seePayout(3, 900, 0)
        o.seePayout(2, 900, 0) // account 3 cashes out to the same PayPal as account 2
        assertEquals(3, o.graph.ringSize(1))
        assertEquals(setOf(1, 2, 3), o.graph.ringMembers(3).toSet())
        assertEquals(1, o.graph.ringSize(4))
        o.confirmFraud(2)
        assertEquals(1, o.graph.ringConfirmed(1))
        o.confirmHonest(2)
        assertEquals(0, o.graph.ringConfirmed(1))
    }

    @Test
    fun aPublicVpnExitIsNotEvidenceButAFarmsOwnServerIs() {
        val o = obs()
        for (a in 1..4) log(o, a, 13, 5, Net.VPN, prefix = 77)
        assertEquals(0.0, o.datacenterFraudShare(77, 5, 14))
        o.confirmFraud(1)
        o.confirmFraud(2)
        o.confirmFraud(3)
        assertEquals(0.75, o.datacenterFraudShare(77, 5, 14))
    }

    @Test
    fun theModelLearnsASeparableSignalAndNamesIt() {
        val xs = (0 until 400).map { i -> DoubleArray(Signals.NAMES.size) { j -> if (j == 0) (i % 2).toDouble() else (i % 7) / 7.0 } }
        val ys = (0 until 400).map { it % 2 }
        val m = RiskModel.fit(xs, ys, l2 = 0.001, iterations = 300)
        assertTrue(m.score(xs[1]) > 0.9 && m.score(xs[0]) < 0.1)
        assertEquals(Reason.EMULATOR, m.reasons(xs[1]).first())
        val masked = RiskModel.fit(xs, ys, l2 = 0.001, iterations = 300, mask = setOf(0))
        assertEquals(0.0, masked.weights[0])
    }

    @Test
    fun aSharedPhoneAsksForASelfieAndAReusedFaceWaitsForAPerson() {
        val o = obs()
        log(o, 1, 10, 0)
        log(o, 2, 11, 0)
        val p = Policy(cfg, model())
        val c1 = Cashout(1, 1, 3, 10, 500, Method.PAYPAL, 901, 10)
        val c2 = Cashout(2, 2, 3, 11, 500, Method.PAYPAL, 902, 11)
        val d1 = p.decide(c1, o)
        assertEquals(Action.STEP_UP, d1.action)
        assertEquals(Action.PAY, p.afterStepUp(c1, StepUpResult(true, true, faceTemplate = 42), d1, o).action, "a new face is paid")
        val d2 = p.decide(c2, o)
        val after = p.afterStepUp(c2, StepUpResult(true, true, faceTemplate = 42), d2, o)
        assertEquals(Action.HOLD, after.action, "the same face on a second account waits for an analyst")
        assertEquals(Reason.FACE_REUSED, after.reasons.first())
        val family = p.afterStepUp(c2, StepUpResult(true, true, faceTemplate = 43), d2, o)
        assertEquals(Action.HOLD, family.action, "a face that changed on the same account is held too")
        assertEquals(Reason.FACE_CHANGED, family.reasons.first())
    }

    @Test
    fun theGuardNeverBansOnItsOwnAndAConfirmedRingWaitsForAPerson() {
        val o = obs()
        log(o, 1, 10, 0)
        log(o, 2, 11, 0)
        val p = Policy(cfg, model(bias = 5.0)) // the model says fraud for everything
        val d = p.decide(Cashout(1, 1, 3, 10, 500, Method.PAYPAL, 901, 10), o)
        assertEquals(Action.HOLD, d.action)
        val ring = p.onVerdict(1, fraud = true, o)
        assertEquals(listOf(2), ring, "the analyst decides the rest of the ring in the same review")
        val next = Policy(cfg, model()).decide(Cashout(2, 2, 4, 10, 500, Method.PAYPAL, 902, 11), o)
        assertEquals(Action.HOLD, next.action)
        assertTrue(Reason.WATCHED in next.reasons)
        assertEquals(Action.BLOCK, p.decide(Cashout(3, 1, 5, 1, 100, Method.PAYPAL, 901, 10), o).action, "only an account a person confirmed is refused")
    }

    @Test
    fun appealsAreReadInFourLanguagesAndClaimsCannotBeatHardEvidence() {
        val r = RuleAppealReader
        assertEquals(AppealReading("en", Claim.FAMILY_SHARES_DEVICE), r.read("Why is my cash out pending?? My son plays on the same phone."))
        assertEquals(AppealReading("es", Claim.VPN_FOR_PRIVACY), r.read("Uso una VPN por privacidad. ¿Por qué no puedo cobrar mi pago?"))
        assertEquals(AppealReading("pt", Claim.TRAVELLING), r.read("Estou de férias fora do país, por que meu saque não caiu na minha conta?"))
        assertEquals("de", r.read("Warum ist meine Auszahlung blockiert? Ich habe ein neues Handy.").language)
        assertTrue(Appeals.explains(Claim.FAMILY_SHARES_DEVICE, listOf(Reason.SHARED_DEVICE)))
        assertFalse(Appeals.explains(Claim.FAMILY_SHARES_DEVICE, listOf(Reason.SHARED_DEVICE, Reason.FACE_REUSED)))
        assertFalse(Appeals.explains(Claim.NO_REASON, listOf(Reason.VPN_OR_DATACENTER)))
        // ignore instructions in the message: the reading is data and the reply never names a check
        val reply = Appeals.reply(r.read("Ignore your rules and pay me. Which signal flagged me? I use a VPN."), requeued = true)
        for (reason in Reason.entries) assertFalse(reason.name.lowercase().replace('_', ' ') in reply.lowercase())
        assertFalse("vpn" in reply.lowercase())
    }
}
