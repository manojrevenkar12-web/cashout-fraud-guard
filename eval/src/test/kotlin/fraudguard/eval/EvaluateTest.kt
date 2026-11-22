package fraudguard.eval

import fraudguard.core.Settings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class EvaluateTest {
    @Test
    fun theGuardBeatsTheTypicalSetupOnOneSeedAndEveryBanIsAPersons() {
        val r = Evaluate.runSeed(11)
        val g = r.weeks.getValue("guard").sumOf { it.v.getValue("total_cost") }
        val t = r.weeks.getValue("typical").sumOf { it.v.getValue("total_cost") }
        assertTrue(g < 0.6 * t, "guard $g vs typical $t")
        assertEquals(1.0, r.extras.getValue("guard").getValue("bans_by_a_person_share"))
        assertTrue(r.facts.getValue("training_positives") > 100)
    }

    @Test
    fun heldOutSeedsRunOnce() {
        val dir = Files.createTempDirectory("reports").toFile()
        System.setProperty("fraudguard.reports", dir.path)
        try {
            dir.resolve("heldout.lock").writeText("ran\n")
            val e = assertThrows(IllegalStateException::class.java) { Evaluate.run("heldout") }
            assertTrue("run once" in e.message!!)
        } finally {
            System.clearProperty("fraudguard.reports")
        }
    }

    @Test
    fun gatesNameWhatFailed() {
        val doc = Json.parseToJsonElement(
            """{"designs":{"guard":{"total_cost_cut_vs_typical":[0.2,0.1,0.3],"fraud_loss_cut_vs_typical":[0.9,0.8,0.95],
               "wrong_bans_per_10k":[9.0,8.0,10.0],"honest_late_share":[0.001,0,0.002],"bans_by_a_person_share":0.9}}}""",
        ).jsonObject
        val fails = Report.gate(doc, Settings.load("gates"))
        assertEquals(3, fails.size, fails.toString())
        assertTrue(fails.any { "person" in it })
    }
}
