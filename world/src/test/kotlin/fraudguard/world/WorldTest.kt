package fraudguard.world

import fraudguard.core.Action
import fraudguard.core.AppealRoute
import fraudguard.core.Case
import fraudguard.core.Cashout
import fraudguard.core.Decision
import fraudguard.core.Desk
import fraudguard.core.Observed
import fraudguard.core.StepUpResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class WorldTest {
    /** Pays everything; the simplest desk. */
    private object PayAll : Desk {
        override val name = "pay_all"
        override val evidenceFile = false
        override val draftsReplies = false
        override fun decide(c: Cashout, o: Observed) = Decision(Action.PAY, 0.0, emptyList())
        override fun afterStepUp(c: Cashout, r: StepUpResult, before: Decision, o: Observed) = before
        override fun order(queue: Collection<Case>) = queue.toList()
        override fun onVerdict(account: Int, fraud: Boolean, o: Observed) = emptyList<Int>()
        override fun onAppeal(case: Case, text: String, o: Observed) = AppealRoute.REPLY_ONLY
    }

    @Test
    fun sameSeedSameWorldSameOutcome() {
        fun once(): List<Long> {
            val w = World.simulate(5)
            val run = Run()
            w.play(PayAll, 0 until 21, run)
            return run.events.paid.map { it.cashout * 31 + it.cents }
        }
        val a = once()
        assertTrue(a.size > 1000)
        assertEquals(a, once())
    }

    @Test
    fun theObservedStoreHoldsNoTruth() {
        val fields = Observed::class.java.declaredFields.map { it.type.name } + fraudguard.core.Cashout::class.java.declaredFields.map { it.type.name }
        assertFalse(fields.any { it.startsWith("fraudguard.world") }, "Observed must not reference the world: $fields")
    }

    @Test
    fun theWorldReadsOnlyItsOwnConfig() {
        val src = File("world/src/main/kotlin").walk().filter { it.isFile }.joinToString("\n") { it.readText() }
        assertTrue("Settings.load(\"world\")" in src)
        for (other in listOf("guard", "gates", "seeds")) assertFalse("Settings.load(\"$other\")" in src, "world reads $other")
    }

    @Test
    fun familiesShareAPhoneButNotAFaceWhileMultiAccountersShareBoth() {
        val w = World.simulate(5)
        val family = w.accounts.filter { it.kind == Kind.HONEST && it.claim.name == "FAMILY_SHARES_DEVICE" }
        val byFingerprint = family.groupBy { it.devices[0].second.fingerprint }.values.filter { it.size > 1 }
        assertTrue(byFingerprint.isNotEmpty())
        assertTrue(byFingerprint.all { g -> g.map { it.human }.toSet().size == g.size }, "family members are different people")
        val multi = w.accounts.filter { it.kind == Kind.MULTI }.groupBy { it.human }.values
        assertTrue(multi.all { it.size >= 2 })
    }

    @Test
    fun payingEveryoneLosesMoneyToEveryKindOfFraud() {
        val w = World.simulate(5)
        val run = Run()
        w.play(PayAll, 0 until 56, run)
        val kinds = run.events.paid.filter { it.fraud }.map { it.kind }.toSet()
        assertEquals(setOf(Kind.FARM, Kind.MULTI, Kind.GEO_VPN, Kind.TAKEOVER, Kind.OFFER), kinds)
        assertTrue(run.events.paid.filter { it.fraud }.sumOf { it.lossCents } > 0)
    }
}
