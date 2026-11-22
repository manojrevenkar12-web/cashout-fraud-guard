package fraudguard.eval

import fraudguard.core.RiskModel
import fraudguard.core.Settings
import fraudguard.core.Signals
import fraudguard.core.d
import fraudguard.core.i
import fraudguard.core.obj
import fraudguard.world.Run
import fraudguard.world.World
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.time.LocalDate
import kotlin.system.exitProcess

/** Runs every design on the same seeds, writes reports/<set>.json, and guards the held-out run. */
object Evaluate {
    fun reports(): File = File(System.getProperty("fraudguard.reports", "reports"))

    class Trained(val models: Map<Pair<Boolean, Boolean>, RiskModel>, val examples: Int, val positives: Int) {
        val model get() = models.getValue(false to false)
    }

    /**
     * Labels the service had by the end of the history weeks. Only what a person or a payment network confirmed counts:
     * analyst verdicts, chargebacks and ad-network clawbacks. Cash-outs an automatic rule blocked are left out, or the
     * model would learn the old rule back.
     */
    fun train(rec: Recorder, run: Run, cfg: JsonObject): Trained {
        val autoBlocked = run.events.blocks.filter { !it.byPerson }.map { it.account }.toSet()
        val paid = run.events.paid.map { it.cashout }.toSet()
        val xs = ArrayList<DoubleArray>()
        val ys = ArrayList<Int>()
        for ((id, v) in rec.features.entries.sortedBy { it.key }) {
            val (account, x) = v
            val verdict = run.verdicts[account]
            val y = when {
                account in run.fraudReports || verdict?.second == true -> 1
                account in autoBlocked -> 2
                verdict?.second == false || id in paid -> 0
                else -> continue
            }
            xs.add(x)
            ys.add(y)
        }
        val m = cfg.obj("model")
        val models = HashMap<Pair<Boolean, Boolean>, RiskModel>()
        for (ruleLabels in listOf(false, true)) {
            val keep = ys.indices.filter { ruleLabels || ys[it] != 2 }
            val x = keep.map { xs[it] }
            val y = keep.map { if (ys[it] == 0) 0 else 1 }
            for (masked in listOf(false, true)) {
                models[ruleLabels to masked] = RiskModel.fit(x, y, m.d("l2"), m.i("iterations"), m.d("rate"), mask = if (masked) Signals.GRAPH else emptySet())
            }
        }
        return Trained(models, ys.count { it != 2 }, ys.count { it == 1 })
    }

    class SeedResult(val weeks: Map<String, List<Week>>, val extras: Map<String, Map<String, Double>>, val facts: Map<String, Double>, val weights: Map<String, Double>)

    fun runSeed(seed: Long, cfg: JsonObject = Settings.load("guard")): SeedResult {
        val world = World.simulate(seed)
        val h = world.historyDays
        val weeks = LinkedHashMap<String, List<Week>>()
        val extras = LinkedHashMap<String, Map<String, Double>>()
        var trained: Trained? = null
        var facts = emptyMap<String, Double>()
        for (name in Designs.names(cfg)) {
            val run = Run()
            val rec = Recorder()
            world.play(TypicalDesk(cfg, rec), 0 until h, run)
            val t = trained ?: train(rec, run, cfg).also { trained = it }
            val desk = Designs.desk(cfg, name) { rule, masked -> t.models.getValue(rule to masked) }
            world.play(desk, h until world.horizon, run)
            weeks[name] = Metrics.weeks(run.events, world)
            extras[name] = Metrics.extras(run.events, world)
            if (facts.isEmpty()) {
                val live = run.events.requests.filter { it.day >= h }
                facts = mapOf(
                    "accounts" to world.accounts.count { !it.reserve }.toDouble(),
                    "fraud_accounts" to world.accounts.count { !it.reserve && it.kind.name != "HONEST" }.toDouble(),
                    "live_cashouts" to live.size.toDouble(),
                    "live_fraud_cashouts" to live.count { it.fraud }.toDouble(),
                    "training_examples" to t.examples.toDouble(),
                    "training_positives" to t.positives.toDouble(),
                )
            }
        }
        val t = trained!!
        return SeedResult(weeks, extras, facts, Signals.NAMES.zip(t.model.weights.toList()).toMap())
    }

    fun summarize(set: String, seeds: List<Long>, results: List<SeedResult>, cfg: JsonObject): JsonObject {
        val names = Designs.names(cfg)
        val base = results.flatMap { it.weeks.getValue("typical") }
        return buildJsonObject {
            put("set", set)
            putJsonArray("seeds") { seeds.forEach { add(JsonPrimitive(it)) } }
            put("config_sha", Settings.sha("world", "guard", "gates", "seeds"))
            put("generated_at", LocalDate.now().toString())
            putJsonObject("facts") {
                for (k in results[0].facts.keys) put(k, results.sumOf { it.facts.getValue(k) })
                put("weeks", base.size)
            }
            putJsonObject("designs") {
                for (n in names) {
                    val wk = results.flatMap { it.weeks.getValue(n) }
                    putJsonObject(n) {
                        put("label", Designs.spec(cfg, n)["label"]!!)
                        for (k in Metrics.KEYS) putJsonArray(k) { Metrics.boot(wk.map { it.v.getValue(k) }).forEach { add(JsonPrimitive(round(it))) } }
                        putJsonArray("total_cost_cut_vs_typical") {
                            Metrics.bootCut(wk.map { it.v.getValue("total_cost") }, base.map { it.v.getValue("total_cost") }).forEach { add(JsonPrimitive(round(it))) }
                        }
                        putJsonArray("fraud_loss_cut_vs_typical") {
                            Metrics.bootCut(wk.map { it.v.getValue("fraud_loss") }, base.map { it.v.getValue("fraud_loss") }).forEach { add(JsonPrimitive(round(it))) }
                        }
                        for (k in results[0].extras.getValue(n).keys) {
                            val vals = results.map { it.extras.getValue(n).getValue(k) }
                            put(k, round(if (k.endsWith("share")) vals.average() else vals.sum()))
                        }
                    }
                }
            }
            putJsonObject("model_weights_first_seed") { results[0].weights.forEach { (k, v) -> put(k, round(v)) } }
        }
    }

    private fun round(x: Double) = Math.round(x * 10_000) / 10_000.0

    fun run(set: String) {
        val cfg = Settings.load("guard")
        val seeds = Settings.load("seeds")[set]!!.jsonArray.map { it.jsonPrimitive.long }
        val dir = reports().also { it.mkdirs() }
        if (set == "heldout") {
            val lock = File(dir, "heldout.lock")
            check(!lock.exists()) { "held-out seeds already ran (${lock.path}); they run once" }
            lock.writeText("held-out run started ${LocalDate.now()} with config ${Settings.sha("world", "guard", "gates", "seeds")}\n")
        }
        val results = seeds.map { runSeed(it, cfg) }
        val doc = summarize(set, seeds, results, cfg)
        File(dir, "$set.json").writeText(Settings.JSON.encodeToString(JsonObject.serializer(), doc) + "\n")
        println(buildJsonObject { put("set", set); putJsonArray("seeds") { seeds.forEach { add(JsonPrimitive(it)) } } })
    }

    /** Trains the guard's model on the first development seed's history and writes it for the service. */
    fun exportModel() {
        val cfg = Settings.load("guard")
        val seed = Settings.load("seeds")["dev"]!!.jsonArray.first().jsonPrimitive.long
        val world = World.simulate(seed)
        val run = Run()
        val rec = Recorder()
        world.play(TypicalDesk(cfg, rec), 0 until world.historyDays, run)
        val m = train(rec, run, cfg).model
        File("models").mkdirs()
        File("models/risk-model.json").writeText(Settings.JSON.encodeToString(JsonObject.serializer(), m.toJson("history weeks of development seed $seed")) + "\n")
        println("wrote models/risk-model.json")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            when (args.firstOrNull()) {
                "dev" -> run("dev")
                "heldout" -> run("heldout")
                "readme" -> Report.write()
                "export-model" -> exportModel()
                "gate" -> {
                    val fails = listOf("dev", "heldout").flatMap { s ->
                        val f = File(reports(), "$s.json")
                        if (f.exists()) Report.gate(kotlinx.serialization.json.Json.parseToJsonElement(f.readText()).jsonObject, Settings.load("gates")).map { "$s: $it" } else emptyList()
                    }
                    fails.forEach { println("GATE FAILED: $it") }
                    if (fails.isNotEmpty()) exitProcess(1)
                    println("gates passed")
                }
                else -> error("usage: dev | heldout | readme | gate")
            }
        } catch (e: IllegalStateException) {
            System.err.println(e.message)
            exitProcess(2)
        }
    }
}
