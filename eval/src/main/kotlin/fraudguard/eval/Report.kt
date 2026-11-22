package fraudguard.eval

import fraudguard.core.b
import fraudguard.core.d
import fraudguard.core.obj
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Locale

/** REPORT.md and the README results block, generated from reports/. Never edited by hand. */
object Report {
    private fun load(set: String): JsonObject? = File(Evaluate.reports(), "$set.json").takeIf { it.exists() }?.let { Json.parseToJsonElement(it.readText()).jsonObject }
    private fun JsonObject.m(k: String, i: Int = 0) = this[k]!!.jsonArray[i].jsonPrimitive.double
    private fun f(fmt: String, vararg a: Any) = String.format(Locale.ROOT, fmt, *a)
    private fun usd(x: Double) = f("$%,.0f", x)
    private fun pct(x: Double, d: Int = 0) = f("%.${d}f%%", x * 100)

    fun headline(doc: JsonObject): String {
        val d = doc.obj("designs")
        val g = d.obj("guard")
        val t = d.obj("typical")
        return f(
            "Against a typical setup (VPN block, selfie at the first payout, large cash-outs reviewed in order), the guard cut the weekly cost of fraud and friction by **%s** (95%% interval %s to %s): the fraud loss fell from %s to %s a week, honest players wrongly banned fell from %s to %s per 10,000 honest cash-outs, and the share of honest cash-outs not paid within 24 hours went from %s to %s. Analysts spent %.0f hours a week instead of %.0f, and every ban was decided by a person.",
            pct(g.m("total_cost_cut_vs_typical")), pct(g.m("total_cost_cut_vs_typical", 1)), pct(g.m("total_cost_cut_vs_typical", 2)),
            usd(t.m("fraud_loss")), usd(g.m("fraud_loss")), f("%.1f", t.m("wrong_bans_per_10k")), f("%.1f", g.m("wrong_bans_per_10k")),
            pct(t.m("honest_late_share"), 1), pct(g.m("honest_late_share"), 1), g.m("analyst_hours"), t.m("analyst_hours"),
        )
    }

    fun tables(doc: JsonObject): String {
        val fa = doc.obj("facts")
        val b = StringBuilder()
        b.append(
            f(
                "Seeds %s · %,.0f accounts, %,.0f of them fraudulent · %,.0f live cash-outs over 8 weeks, %,.0f of them fraud · model trained on %,.0f labelled cash-outs from the 8 history weeks · config `%s`. Intervals are 95%%, resampling whole weeks. Money in US dollars per week.%n%n",
                doc["seeds"]!!.jsonArray.joinToString(", ") { it.jsonPrimitive.content }, fa.d("accounts"), fa.d("fraud_accounts"), fa.d("live_cashouts"),
                fa.d("live_fraud_cashouts"), fa.d("training_examples"), doc["config_sha"]!!.jsonPrimitive.content,
            ),
        )
        b.append("| Design | Cost of fraud and friction | Fraud loss | Honest players lost | Analysts | Support | Selfies | Share of fraud money paid | Wrong bans per 10k honest cash-outs | Honest cash-outs paid after 24 h | Bans decided by a person |\n")
        b.append("|---|---|---|---|---|---|---|---|---|---|---|\n")
        for ((name, v) in doc.obj("designs")) {
            val r = v.jsonObject
            val bold = if (name == "guard") "**" else ""
            b.append(
                f(
                    "| %s%s%s | %s (%s–%s) | %s | %s | %s | %s | %s | %s | %.1f | %s | %s |%n", bold, r["label"]!!.jsonPrimitive.content, bold,
                    usd(r.m("total_cost")), usd(r.m("total_cost", 1)), usd(r.m("total_cost", 2)), usd(r.m("fraud_loss")), usd(r.m("friction_loss")),
                    usd(r.m("analyst_cost")), usd(r.m("support_cost")), usd(r.m("step_up_cost")), pct(r.m("fraud_paid_share")), r.m("wrong_bans_per_10k"), pct(r.m("honest_late_share"), 1),
                    pct(r.d("bans_by_a_person_share")),
                ),
            )
        }
        val g = doc.obj("designs").obj("guard")
        b.append(
            f(
                "%nThe guard stopped %s of emulator-farm accounts before their second paid cash-out, re-reviewed %.0f honest players' appeals sooner because their claim explained the hold, and restored %.0f wrongly blocked players.%n",
                pct(g.d("farm_accounts_stopped_by_second_payout_share"), 1), g.m("honest_appeals_requeued") * doc.obj("facts").d("weeks"), g.d("honest_restored"),
            ),
        )
        b.append("\n**What the risk model learned** (first seed, standardised weights; positive raises risk):\n\n| Signal | Weight |\n|---|---|\n")
        doc.obj("model_weights_first_seed").entries.sortedByDescending { kotlin.math.abs(it.value.jsonPrimitive.double) }.forEach {
            b.append(f("| %s | %+.2f |%n", it.key.replace('_', ' '), it.value.jsonPrimitive.double))
        }
        return b.toString()
    }

    fun write() {
        val dev = load("dev")
        val held = load("heldout")
        val main = held ?: dev ?: error("no reports yet: run the dev evaluation first")
        val which = if (held != null) "held-out" else "development"
        val rep = StringBuilder("# Results\n\nGenerated by `./gradlew :eval:readme` from `reports/*.json`. Do not edit.\n\n## ")
        rep.append(which.replaceFirstChar { it.uppercase() }).append(" seeds\n\n").append(headline(main)).append("\n\n").append(tables(main))
        if (held != null && dev != null) rep.append("\n## Development seeds\n\n").append(headline(dev)).append("\n\n").append(tables(dev))
        File("REPORT.md").writeText(rep.toString())
        val readme = File("README.md")
        if (readme.exists()) {
            val block = "_From the $which seeds, run once; generated by `./gradlew :eval:readme`._\n\n" + headline(main) + "\n\n" + tables(main)
            readme.writeText(readme.readText().replace(Regex("(?s)<!-- results:start -->.*<!-- results:end -->"), Regex.escapeReplacement("<!-- results:start -->\n$block\n<!-- results:end -->")))
        }
    }

    fun gate(doc: JsonObject, gates: JsonObject): List<String> {
        val g = gates.obj("guard")
        val r = doc.obj("designs").obj("guard")
        val fails = ArrayList<String>()
        if (r.m("total_cost_cut_vs_typical", 1) < g.d("costCutVsTypicalLowMin")) fails.add("cost cut low end below ${g.d("costCutVsTypicalLowMin")}")
        if (r.m("fraud_loss_cut_vs_typical") < g.d("fraudLossCutVsTypicalMin")) fails.add("fraud loss cut below ${g.d("fraudLossCutVsTypicalMin")}")
        if (r.m("wrong_bans_per_10k") > g.d("wrongBansPer10kMax")) fails.add("wrong bans per 10k above ${g.d("wrongBansPer10kMax")}")
        if (r.m("honest_late_share") > g.d("honestHeldOver24hShareMax")) fails.add("honest cash-outs paid late above ${g.d("honestHeldOver24hShareMax")}")
        if (g.b("everyBanByAPerson") && r.d("bans_by_a_person_share") < 1.0) fails.add("a ban was not decided by a person")
        return fails
    }
}
