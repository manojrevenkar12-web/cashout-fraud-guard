package fraudguard.world

import fraudguard.core.AccountInfo
import fraudguard.core.Action
import fraudguard.core.AppealRoute
import fraudguard.core.Case
import fraudguard.core.Cashout
import fraudguard.core.Claim
import fraudguard.core.DayLog
import fraudguard.core.Decision
import fraudguard.core.Desk
import fraudguard.core.DeviceInfo
import fraudguard.core.Integrity
import fraudguard.core.Method
import fraudguard.core.Net
import fraudguard.core.Observed
import fraudguard.core.Settings
import fraudguard.core.StepUpResult
import fraudguard.core.arr
import fraudguard.core.d
import fraudguard.core.i
import fraudguard.core.obj
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

/** Who an account really is. Only the world knows. */
enum class Kind { HONEST, FARM, MULTI, GEO_VPN, TAKEOVER, OFFER }

/** An account's hidden profile: who is behind it and how they behave. */
class Profile(
    val id: Int,
    val kind: Kind,
    val human: Int,
    val farm: Int,
    val signupDay: Int,
    val country: String,
    val language: String,
    val activeProb: Double,
    val minutesMedian: Double,
    val centsPerMinute: Double,
    val spread: Double,
    val vpnShare: Double,
    val dcShare: Double,
    val geoMismatch: Double,
    val homePrefix: Int,
    val prefixPool: IntArray,
    val offersPerDay: Double,
    val offerMinutesEach: Double,
    val offerCents: Int,
    val devices: List<Pair<Int, DeviceInfo>>,
    val payouts: List<Pair<Int, Int>>,
    val threshold: Int,
    val firstThreshold: Int,
    val startBalance: Int,
    val revenuePerCentPaid: Double,
    val dailyMarginCents: Double,
    val claim: Claim,
    val quitsAfterFirst: Boolean,
    val takeoverDay: Int,
    val stolenCents: Int,
    val reserve: Boolean,
) {
    fun device(day: Int) = devices.last { it.first <= day }.second
    fun payout(day: Int) = payouts.last { it.first <= day }.second
    fun fraudAt(day: Int) = kind != Kind.HONEST && (kind != Kind.TAKEOVER || day >= takeoverDay)
}

/** One run's record of what happened, with the truth attached. Only evaluation reads it. */
class Events {
    class Paid(val cashout: Long, val account: Int, val reqDay: Int, val waitHours: Double, val cents: Int, val fraud: Boolean, val lossCents: Double, val kind: Kind)
    class Churn(val account: Int, val day: Int, val lossCents: Double, val cause: String)
    class Request(val cashout: Long, val day: Int, val fraud: Boolean, val kind: Kind, val account: Int, val cents: Int)
    class Block(val account: Int, val day: Int, val fraud: Boolean, val kind: Kind, val byPerson: Boolean)
    class Appeal(val day: Int, val fraud: Boolean, val requeued: Boolean)

    val paid = ArrayList<Paid>()
    val churn = ArrayList<Churn>()
    val requests = ArrayList<Request>()
    val blocks = ArrayList<Block>()
    val restored = ArrayList<Triple<Int, Int, Boolean>>() // account, day, fraud
    val appeals = ArrayList<Appeal>()
    val analystMinutes = HashMap<Int, Double>()
    val stepUps = HashMap<Int, Int>()
    val supportMinutes = HashMap<Int, Double>()
    val heldOpenAtEnd = ArrayList<Pair<Boolean, Int>>()
    class Review(val day: Int, val reason: String, val fraud: Boolean, val saysFraud: Boolean, val ringMembers: Int, val appeal: Boolean)
    val reviews = ArrayList<Review>()
}

/** What a design's run keeps: the observed store the desk sees, and the world-side state of every account. */
class Run {
    val obs = Observed()
    val events = Events()
    internal val balance = HashMap<Int, Int>()
    internal val activation = HashMap<Int, Int>()
    internal val blocked = HashMap<Int, Int>()
    internal val churned = HashSet<Int>()
    internal val quit = HashSet<Int>()
    internal val cashedOut = HashSet<Int>()
    internal val pending = LinkedHashMap<Long, Case>()
    internal val blockedCashout = HashMap<Int, Cashout>()
    internal val reports = ArrayList<Triple<Int, Int, Int>>() // due day, account, kind(1=chargeback 2=clawback)
    internal val appealedAccounts = HashSet<Int>()
    internal var nextCashout = 1L
    internal val seenDevices = HashSet<Int>()

    /** Accounts the service learned were fraud from chargebacks or ad-network clawbacks, with the day it learned. */
    val fraudReports = HashMap<Int, Int>()

    /** Analyst verdicts on the account level: account -> (day, fraud). Labels for training. */
    val verdicts = HashMap<Int, Pair<Int, Boolean>>()
}

class World private constructor(val seed: Long, private val cfg: JsonObject) {
    private val k = Keyed(seed)
    val historyDays = cfg.i("historyDays")
    val liveDays = cfg.i("liveDays")
    val horizon = historyDays + liveDays
    private val profiles = ArrayList<Profile>()
    private val byId = HashMap<Int, Profile>()
    private val farmReserve = HashMap<Int, ArrayDeque<Int>>()
    private val stepUpCfg = cfg.obj("stepUp")
    private val analysts = cfg.obj("analysts")
    private val players = cfg.obj("players")
    private val reportCfg = cfg.obj("reports")
    private val support = cfg.obj("support")

    private var nextAccount = 1
    private var nextDevice = 1
    private var nextFingerprint = 1
    private var nextPayout = 1
    private var nextHuman = 1

    fun profile(account: Int): Profile = byId.getValue(account)
    val accounts: List<Profile> get() = profiles

    private fun range(o: JsonObject, key: String): Pair<Double, Double> = o.arr(key).let { it[0].jsonPrimitive.double to it[1].jsonPrimitive.double }
    private fun irange(o: JsonObject, key: String): Pair<Int, Int> = o.arr(key).let { it[0].jsonPrimitive.int to it[1].jsonPrimitive.int }

    private fun device(r: java.util.SplittableRandom, fingerprint: Int? = null, integrity: Integrity? = null): DeviceInfo {
        val fp = fingerprint ?: nextFingerprint++
        val integ = integrity ?: when {
            r.nextDouble() < 0.01 -> Integrity.FAILED
            else -> Integrity.STRONG
        }
        return DeviceInfo(nextDevice++, fp, integ)
    }

    private fun add(p: Profile) {
        profiles.add(p)
        byId[p.id] = p
    }

    private fun country(r: java.util.SplittableRandom): Pair<String, String> {
        val w = cfg.obj("countries")
        var x = r.nextDouble()
        for ((c, v) in w) {
            x -= v.jsonPrimitive.double
            if (x <= 0) {
                val lang = when (c) {
                    "DE" -> "de"
                    "BR" -> "pt"
                    "US" -> if (r.nextDouble() < cfg.d("spanishShareUS")) "es" else "en"
                    else -> "en"
                }
                return c to lang
            }
        }
        return "US" to "en"
    }

    private fun build() {
        val h = cfg.obj("honest")
        val f = cfg.obj("fraud")
        val mobilePrefixes = h.i("mobilePrefixes")
        val vpnExits = IntArray(h.i("vpnExitPrefixes")) { 50_000 + it }
        val (apLo, apHi) = range(h, "activeProb")
        val (thLo, thHi) = irange(h, "thresholdCents")

        fun honest(i: Long, sharedDevice: DeviceInfo? = null, sharedPayout: Int? = null, claim: Claim? = null): Profile {
            val r = k.rng("honest", i)
            val (country, lang) = country(r)
            val vpn = r.nextDouble() < h.d("vpnUser")
            val grinder = r.nextDouble() < h.d("grinder")
            val traveller = r.nextDouble() < h.d("traveller")
            val oldPhone = r.nextDouble() < h.d("oldPhone")
            val change = r.nextDouble() < h.d("phoneChange")
            val first = sharedDevice ?: device(r, integrity = if (oldPhone) Integrity.BASIC else null)
            val devices = mutableListOf(-10_000 to first)
            if (change && sharedDevice == null) devices.add(r.between(0, horizon - 1) to device(r))
            val threshold = r.between(thLo, thHi)
            return Profile(
                id = nextAccount++, kind = Kind.HONEST, human = nextHuman++, farm = -1, signupDay = r.between(-300, horizon - 10),
                country = country, language = lang, activeProb = apLo + r.nextDouble() * (apHi - apLo),
                minutesMedian = h.d("minutesMedian") * (if (grinder) 3.0 else 1.0), centsPerMinute = h.d("centsPerMinute") * r.lognormal(1.0, 0.25),
                spread = if (grinder) 1.6 else h.d("startSpreadH"), vpnShare = if (vpn) 0.7 else 0.01, dcShare = 0.005,
                geoMismatch = if (traveller) 0.25 else if (vpn) 0.5 else 0.01, homePrefix = r.nextInt(mobilePrefixes), prefixPool = vpnExits,
                offersPerDay = 0.05, offerMinutesEach = 20.0, offerCents = 120, devices = devices, payouts = listOf(-10_000 to (sharedPayout ?: nextPayout++)),
                threshold = threshold, firstThreshold = h.i("firstThresholdCents"), startBalance = r.nextInt(threshold),
                revenuePerCentPaid = h.d("revenuePerCentPaid"), dailyMarginCents = h.d("dailyMarginCents") * r.lognormal(1.0, 0.5),
                claim = claim ?: when {
                    change -> Claim.NEW_PHONE
                    vpn -> Claim.VPN_FOR_PRIVACY
                    traveller -> Claim.TRAVELLING
                    else -> Claim.NO_REASON
                },
                quitsAfterFirst = false, takeoverDay = Int.MAX_VALUE, stolenCents = 0, reserve = false,
            )
        }

        val n = cfg.i("honestPlayers")
        for (i in 0 until n) add(honest(i.toLong()))
        val groups = (n * h.d("familyGroups")).roundToInt()
        for (g in 0 until groups) {
            val r = k.rng("family", g.toLong())
            val size = r.between(2, 3)
            val first = honest(1_000_000L + g * 10, claim = Claim.FAMILY_SHARES_DEVICE)
            add(first)
            for (m in 1 until size) {
                val payout = if (r.nextDouble() < h.d("familySamePayout")) first.payout(0) else null
                val shared = DeviceInfo(nextDevice++, first.devices[0].second.fingerprint, first.devices[0].second.integrity)
                add(honest(1_000_000L + g * 10 + m, sharedDevice = shared, sharedPayout = payout, claim = Claim.FAMILY_SHARES_DEVICE))
            }
        }

        // Emulator farms: many accounts, few emulator images, few payout accounts, datacenter networks, scripted play.
        for (fm in 0 until f.i("farms")) {
            val r = k.rng("farm", fm.toLong())
            val (aLo, aHi) = irange(f, "farmAccounts")
            val size = r.between(aLo, aHi)
            val fps = IntArray(r.between(irange(f, "farmFingerprints").first, irange(f, "farmFingerprints").second)) { nextFingerprint++ }
            val integ = fps.associateWith { if (r.nextDouble() < 0.85) Integrity.FAILED else Integrity.BASIC }
            val pays = IntArray(r.between(irange(f, "farmPayoutAccounts").first, irange(f, "farmPayoutAccounts").second)) { nextPayout++ }
            val prefixes = IntArray(3) { 100_000 + fm * 10 + it }
            val operators = IntArray(f.i("farmOperators")) { nextHuman++ }
            val start = r.between(-20, horizon - 25)
            val reserve = ArrayDeque<Int>()
            for (a in 0 until size * 3) {
                val isReserve = a >= size
                val ar = k.rng("farmacct", fm.toLong(), a.toLong())
                val fp = fps[ar.nextInt(fps.size)]
                val p = Profile(
                    id = nextAccount++, kind = Kind.FARM, human = operators[ar.nextInt(operators.size)], farm = fm,
                    signupDay = if (isReserve) Int.MAX_VALUE else start + ar.nextInt(15), country = "US", language = "en",
                    activeProb = 0.95, minutesMedian = 300.0, centsPerMinute = h.d("centsPerMinute"), spread = 0.3,
                    vpnShare = 0.25, dcShare = 0.5, geoMismatch = 0.6, homePrefix = prefixes[0], prefixPool = prefixes,
                    offersPerDay = 0.0, offerMinutesEach = 1.0, offerCents = 0,
                    devices = listOf(-10_000 to DeviceInfo(nextDevice++, fp, integ.getValue(fp))),
                    payouts = listOf(-10_000 to pays[ar.nextInt(pays.size)]), threshold = 500, firstThreshold = 100, startBalance = 0,
                    revenuePerCentPaid = f.d("farmRevenuePerCentPaid"), dailyMarginCents = 0.0, claim = Claim.FAMILY_SHARES_DEVICE,
                    quitsAfterFirst = false, takeoverDay = Int.MAX_VALUE, stolenCents = 0, reserve = isReserve,
                )
                add(p)
                if (isReserve) reserve.addLast(p.id)
            }
            farmReserve[fm] = reserve
        }

        // Multi-accounters: one person, real phone, several accounts after resets, mostly one PayPal.
        for (m in 0 until f.i("multiHumans")) {
            val r = k.rng("multi", m.toLong())
            val human = nextHuman++
            val phoneFp = nextFingerprint++
            val pay = nextPayout++
            val start = r.between(-10, horizon - 15)
            val (country, lang) = country(r)
            for (a in 0 until r.between(irange(f, "multiAccounts").first, irange(f, "multiAccounts").second)) {
                val ar = k.rng("multiacct", m.toLong(), a.toLong())
                val fp = if (ar.nextDouble() < f.d("multiSameFingerprint")) phoneFp else nextFingerprint++
                add(
                    Profile(
                        id = nextAccount++, kind = Kind.MULTI, human = human, farm = -1, signupDay = start + ar.nextInt(10), country = country, language = lang,
                        activeProb = 0.8, minutesMedian = 25.0, centsPerMinute = h.d("centsPerMinute") * 1.2, spread = 2.5, vpnShare = 0.05, dcShare = 0.0,
                        geoMismatch = 0.02, homePrefix = r.nextInt(mobilePrefixes), prefixPool = vpnExits, offersPerDay = 0.1, offerMinutesEach = 15.0, offerCents = 120,
                        devices = listOf(-10_000 to DeviceInfo(nextDevice++, fp, Integrity.STRONG)),
                        payouts = listOf(-10_000 to if (ar.nextDouble() < f.d("multiSamePayout")) pay else nextPayout++), threshold = 300, firstThreshold = 100,
                        startBalance = 0, revenuePerCentPaid = f.d("multiRevenuePerCentPaid"), dailyMarginCents = 0.0,
                        claim = if (ar.nextDouble() < 0.5) Claim.FAMILY_SHARES_DEVICE else Claim.NEW_PHONE,
                        quitsAfterFirst = ar.nextDouble() < f.d("multiQuitAfterFirst"), takeoverDay = Int.MAX_VALUE, stolenCents = 0, reserve = false,
                    ),
                )
            }
        }

        // Geo arbitrage: a real person outside the paying market, on a VPN to earn at its rates.
        for (g in 0 until f.i("geoVpn")) {
            val base = honest(2_000_000L + g)
            add(copy(base, kind = Kind.GEO_VPN, vpnShare = 0.9, geoMismatch = 0.9, country = "US", language = "en",
                revenue = f.d("geoRevenuePerCentPaid"), claim = if (g % 2 == 0) Claim.VPN_FOR_PRIVACY else Claim.TRAVELLING))
        }

        // Taken-over or sold accounts: years of honest play, then a new phone, a new PayPal and the whole balance out.
        for (t in 0 until f.i("takeovers")) {
            val r = k.rng("takeover", t.toLong())
            val base = honest(3_000_000L + t)
            val day = r.between(3, horizon - 3)
            val (sLo, sHi) = irange(f, "takeoverBalanceCents")
            add(copy(base, kind = Kind.TAKEOVER, signupDay = -300 - r.nextInt(300),
                devices = base.devices.take(1) + (day to DeviceInfo(nextDevice++, nextFingerprint++, Integrity.STRONG)),
                payouts = base.payouts + (day to nextPayout++), takeoverDay = day, stolen = r.between(sLo, sHi), revenue = 0.0, claim = Claim.NEW_PHONE))
        }

        // Offerwall fraud: offers "completed" in seconds; the advertiser claws the money back weeks later.
        for (o in 0 until f.i("offerFraud")) {
            val base = honest(4_000_000L + o)
            add(copy(base, kind = Kind.OFFER, offersPerDay = 3.0, offerMinutesEach = 0.5, offerCents = 150, revenue = f.d("offerRevenuePerCentPaid"), claim = Claim.NO_REASON))
        }
    }

    private fun copy(
        b: Profile, kind: Kind, vpnShare: Double = b.vpnShare, geoMismatch: Double = b.geoMismatch, country: String = b.country, language: String = b.language,
        revenue: Double, claim: Claim, signupDay: Int = b.signupDay, devices: List<Pair<Int, DeviceInfo>> = b.devices, payouts: List<Pair<Int, Int>> = b.payouts,
        takeoverDay: Int = Int.MAX_VALUE, stolen: Int = 0, offersPerDay: Double = b.offersPerDay, offerMinutesEach: Double = b.offerMinutesEach, offerCents: Int = b.offerCents,
    ) = Profile(
        b.id, kind, b.human, -1, signupDay, country, language, b.activeProb, b.minutesMedian, b.centsPerMinute, b.spread, vpnShare, b.dcShare, geoMismatch,
        b.homePrefix, b.prefixPool, offersPerDay, offerMinutesEach, offerCents, devices, payouts, b.threshold, b.firstThreshold, b.startBalance, revenue,
        if (kind == Kind.TAKEOVER) b.dailyMarginCents else 0.0, claim, false, takeoverDay, stolen, false,
    )

    // ---- behaviour, one day at a time ----

    private fun logFor(p: Profile, day: Int): DayLog? {
        val r = k.rng("log", p.id.toLong(), day.toLong())
        val after = p.kind == Kind.TAKEOVER && day >= p.takeoverDay
        if (r.nextDouble() > (if (after) 0.5 else p.activeProb)) return null
        val minutes = r.lognormal(p.minutesMedian, 0.6)
        val offers = r.poisson(p.offersPerDay)
        val offerMinutes = offers * p.offerMinutesEach * r.lognormal(1.0, 0.4)
        val earned = (minutes * p.centsPerMinute * r.lognormal(1.0, 0.3) + offers * p.offerCents).roundToInt()
        val vpn = if (after) 0.5 else p.vpnShare
        val x = r.nextDouble()
        val net = when {
            x < vpn -> Net.VPN
            x < vpn + p.dcShare -> Net.DATACENTER
            x < (1 + vpn + p.dcShare) / 2 -> Net.MOBILE
            else -> Net.RESIDENTIAL
        }
        val prefix = when (net) {
            Net.VPN, Net.DATACENTER -> p.prefixPool[r.nextInt(p.prefixPool.size)]
            else -> p.homePrefix
        }
        return DayLog(
            p.id, day, minutes.toFloat(), earned, (minutes / 3).roundToInt(), offers, offerMinutes.toFloat(),
            (p.spread * r.lognormal(1.0, 0.3)).toFloat(), net, prefix, r.nextDouble() >= p.geoMismatch, p.device(day).id,
        )
    }

    private fun activeOn(p: Profile, day: Int, run: Run): Boolean {
        val from = if (p.reserve) run.activation[p.id] ?: return false else p.signupDay
        return from <= day && p.id !in run.blocked && p.id !in run.churned && p.id !in run.quit
    }

    /** Runs [desk] over [days]. History and live periods are separate calls on the same [run]. */
    fun play(desk: Desk, days: IntRange, run: Run) {
        for (day in days) {
            val requests = ArrayList<Cashout>()
            for (p in profiles) {
                if (!activeOn(p, day, run)) continue
                if (p.id !in run.obs.accounts) {
                    run.obs.addAccount(AccountInfo(p.id, if (p.reserve) run.activation.getValue(p.id) else p.signupDay, p.country))
                    run.balance[p.id] = if (p.signupDay < 0 && !p.reserve) p.startBalance else 0
                }
                val log = logFor(p, day) ?: continue
                val dev = p.device(day)
                if (run.seenDevices.add(dev.id)) run.obs.addDevice(dev)
                run.obs.log(log)
                var bal = run.balance.getValue(p.id) + log.earnedCents
                if (day == p.takeoverDay) bal += p.stolenCents
                val first = p.id !in run.cashedOut
                if (bal >= (if (first) p.firstThreshold else p.threshold)) {
                    val r = k.rng("cashout", p.id.toLong(), day.toLong())
                    requests.add(Cashout(run.nextCashout++, p.id, day, r.nextInt(24), bal, if (r.nextDouble() < 0.8) Method.PAYPAL else Method.GIFT_CARD, p.payout(day), dev.id))
                    run.cashedOut.add(p.id)
                    bal = 0
                    if (p.quitsAfterFirst && first) run.quit.add(p.id)
                }
                run.balance[p.id] = bal
            }
            requests.sortWith(compareBy({ it.hour }, { it.id }))
            for (c in requests) {
                val p = profile(c.account)
                run.obs.seePayout(c.account, c.payoutAccount, day)
                run.events.requests.add(Events.Request(c.id, day, p.fraudAt(day), p.kind, p.id, c.cents))
                handle(desk, c, desk.decide(c, run.obs), run, day, stepped = false)
            }
            selfieRetries(desk, run, day)
            analystsWork(desk, run, day)
            lateReports(desk, run, day)
            appeals(desk, run, day)
            churn(run, day)
        }
        if (days.last == horizon - 1) for (cs in run.pending.values) run.events.heldOpenAtEnd.add(profile(cs.cashout.account).fraudAt(cs.cashout.day) to cs.cashout.cents)
    }

    private fun handle(desk: Desk, c: Cashout, d: Decision, run: Run, day: Int, stepped: Boolean) {
        when (d.action) {
            Action.PAY -> pay(c, run, day, if (stepped) 0.1 else 0.0)
            Action.BLOCK -> {
                if (c.account !in run.blocked) run.blockedCashout[c.account] = c
                block(c.account, run, day, byPerson = false)
            }
            Action.HOLD -> run.pending[c.id] = Case(c, d, day, d.priority).also { if (stepped) it.selfieAttempts = 1 }
            Action.STEP_UP -> if (stepped) {
                run.pending[c.id] = Case(c, d, day, d.priority)
            } else {
                run.events.stepUps.merge(day, 1, Int::plus)
                handle(desk, c, desk.afterStepUp(c, stepUp(c), d, run.obs), run, day, stepped = true)
            }
        }
    }

    /** A player whose selfie did not go through tries again the next day; after three tries the case goes to an analyst. */
    private fun selfieRetries(desk: Desk, run: Run, day: Int) {
        for (cs in run.pending.values.filter { it.awaitingSelfie && it.openedDay < day }.toList()) {
            val p = profile(cs.cashout.account)
            if (p.id in run.churned) continue
            run.pending.remove(cs.cashout.id)
            run.events.stepUps.merge(day, 1, Int::plus)
            val d = desk.afterStepUp(cs.cashout, stepUp(cs.cashout, cs.selfieAttempts), cs.decision, run.obs)
            when (d.action) {
                Action.PAY -> pay(cs.cashout, run, day, 0.0)
                Action.BLOCK -> block(cs.account(), run, day, byPerson = false)
                else -> run.pending[cs.cashout.id] = Case(cs.cashout, d, cs.openedDay, d.priority).also { it.selfieAttempts = cs.selfieAttempts + 1 }
            }
        }
    }

    private fun Case.account() = cashout.account

    private fun stepUp(c: Cashout, attempt: Int = 0): StepUpResult {
        val p = profile(c.account)
        val r = k.rng("selfie", c.id, attempt.toLong())
        val kindKey = if (p.fraudAt(c.day)) p.kind.name else Kind.HONEST.name
        if (r.nextDouble() > stepUpCfg.obj("complete").d(kindKey)) return StepUpResult(false, false, null)
        if (r.nextDouble() > stepUpCfg.d("live")) return StepUpResult(true, false, null)
        val buyer = p.kind == Kind.TAKEOVER && c.day >= p.takeoverDay
        val face = if (r.nextDouble() < stepUpCfg.d("templateNoise")) 9_000_000 + r.nextInt(1_000_000) else if (buyer) 8_000_000 + p.id else p.human
        return StepUpResult(true, true, face)
    }

    private fun pay(c: Cashout, run: Run, day: Int, extraHours: Double) {
        val p = profile(c.account)
        val fraud = p.fraudAt(c.day)
        val wait = if (day == c.day) extraHours else (day - c.day) * 24.0 + (24 - c.hour)
        val loss = if (fraud) c.cents * (1 - p.revenuePerCentPaid) else 0.0
        run.events.paid.add(Events.Paid(c.id, c.account, c.day, wait, c.cents, fraud, loss, p.kind))
        if (fraud && p.kind == Kind.TAKEOVER && k.u("chargeback", c.id) < reportCfg.d("takeoverChargebackProb")) {
            run.reports.add(Triple(day + reportCfg.i("takeoverChargebackDays"), c.account, 1))
        }
        if (fraud && p.kind == Kind.OFFER && k.u("clawback", c.id) < reportCfg.d("offerClawbackProb")) {
            run.reports.add(Triple(day + reportCfg.i("offerClawbackDays"), c.account, 2))
        }
    }

    private fun block(account: Int, run: Run, day: Int, byPerson: Boolean) {
        if (account in run.blocked) return
        val p = profile(account)
        run.blocked[account] = day
        run.events.blocks.add(Events.Block(account, day, p.fraudAt(day), p.kind, byPerson))
        run.pending.values.removeIf { it.cashout.account == account }
        if (p.kind == Kind.FARM && k.u("replace", account.toLong()) < cfg.obj("fraud").d("farmReplaceProb")) {
            farmReserve[p.farm]?.firstOrNull { it !in run.activation }?.let { run.activation[it] = day + cfg.obj("fraud").i("farmReplaceDelayDays") }
        }
    }

    private fun analystsWork(desk: Desk, run: Run, day: Int) {
        var minutes = analysts.d("minutesPerDay")
        val per = if (desk.evidenceFile) analysts.d("minutesPerCaseWithFile") else analysts.d("minutesPerCase")
        var used = 0.0
        val done = HashSet<Int>()
        for (cs in desk.order(run.pending.values.filter { !it.awaitingSelfie })) {
            if (minutes < per) break
            val account = cs.cashout.account
            if (account in done || cs.cashout.id !in run.pending) continue
            done.add(account)
            minutes -= per
            used += per
            val p = profile(account)
            val fraud = p.fraudAt(cs.cashout.day)
            val acc = when {
                !fraud && cs.claim != null && cs.claim == p.claim -> analysts.d("accuracyWithTrueClaim")
                desk.evidenceFile -> analysts.d("accuracyWithFile")
                else -> analysts.d("accuracy")
            }
            val correct = k.u("verdict", account.toLong(), day.toLong()) < acc
            val saysFraud = if (correct) fraud else !fraud
            run.verdicts[account] = day to saysFraud
            val cases = run.pending.values.filter { it.cashout.account == account }
            if (saysFraud) {
                block(account, run, day, byPerson = true)
            } else {
                if (account in run.blocked) {
                    run.blocked.remove(account)
                    run.events.restored.add(Triple(account, day, fraud))
                    run.blockedCashout.remove(account)?.let { pay(it, run, day, 0.0) }
                }
                for (x in cases) {
                    run.pending.remove(x.cashout.id)
                    if (x.cashout.id > 0) pay(x.cashout, run, day, 0.0)
                }
            }
            val ring = desk.onVerdict(account, saysFraud, run.obs)
            run.events.reviews.add(Events.Review(day, cs.decision.reasons.firstOrNull()?.name ?: "NONE", fraud, saysFraud, ring.count { it !in run.blocked }, cs.appealed))
            for (m in ring) {
                if (m in run.blocked || m in done) continue
                done.add(m)
                minutes -= analysts.d("minutesPerRingMember")
                used += analysts.d("minutesPerRingMember")
                val mp = profile(m)
                val mFraud = mp.fraudAt(day)
                val mSays = if (k.u("ring-verdict", m.toLong(), day.toLong()) < analysts.d("accuracyWithFile")) mFraud else !mFraud
                run.verdicts[m] = day to mSays
                if (mSays) block(m, run, day, byPerson = true) else run.pending.values.filter { it.cashout.account == m }.toList().forEach { x ->
                    run.pending.remove(x.cashout.id)
                    pay(x.cashout, run, day, 0.0)
                }
                desk.onVerdict(m, mSays, run.obs)
            }
        }
        run.events.analystMinutes.merge(day, used, Double::plus)
    }

    private fun lateReports(desk: Desk, run: Run, day: Int) {
        val due = run.reports.filter { it.first == day }
        for ((_, account, _) in due) {
            if (account !in run.fraudReports) run.fraudReports[account] = day
            run.obs.confirmFraud(account)
            block(account, run, day, byPerson = true)
            desk.onVerdict(account, true, run.obs)
        }
    }

    private fun appeals(desk: Desk, run: Run, day: Int) {
        val heldDays = players.i("appealIfHeldDays")
        val candidates = ArrayList<Case>()
        for ((account, since) in run.blocked) {
            if (day - since != 1 || account in run.appealedAccounts) continue
            val p = profile(account)
            val key = if (p.fraudAt(since)) "FRAUD" else "HONEST"
            if (k.u("appeal-blocked", account.toLong()) < players.obj("appealIfBlocked").d(key)) {
                // a blocked account's appeal reopens a review of the account
                candidates.add(Case(Cashout(-account.toLong(), account, since, 0, 0, Method.PAYPAL, p.payout(since), p.device(since).id), Decision(Action.BLOCK, 1.0, emptyList()), day, 0.0))
            }
        }
        for (cs in run.pending.values) {
            if (day - cs.openedDay != heldDays || cs.cashout.account in run.appealedAccounts) continue
            val p = profile(cs.cashout.account)
            val key = if (p.fraudAt(cs.cashout.day)) "FRAUD" else "HONEST"
            if (k.u("appeal-held", cs.cashout.id) < players.obj("appealIfHeld").d(key)) candidates.add(cs)
        }
        for (cs in candidates) {
            val p = profile(cs.cashout.account)
            run.appealedAccounts.add(p.id)
            cs.appealed = true
            val text = Texts.appeal(p.claim, p.language, k.rng("appeal-text", p.id.toLong()))
            var route = desk.onAppeal(cs, text, run.obs)
            if (route == AppealRoute.REPLY_ONLY && k.u("support", p.id.toLong()) < players.d("supportReReviewShare")) route = AppealRoute.REQUEUE
            run.events.appeals.add(Events.Appeal(day, p.fraudAt(cs.cashout.day), route == AppealRoute.REQUEUE))
            run.events.supportMinutes.merge(day, if (desk.draftsReplies) support.d("minutesPerTicketWithDraft") else support.d("minutesPerTicket"), Double::plus)
            if (route == AppealRoute.REQUEUE && cs.cashout.id !in run.pending) run.pending[cs.cashout.id] = cs
        }
    }

    private fun churn(run: Run, day: Int) {
        val tail = players.i("churnTailDays")
        fun lost(p: Profile) = p.dailyMarginCents * p.activeProb * (horizon - day + tail)
        val waiting = run.pending.values.map { it.cashout.account }.toSet()
        for (a in waiting) {
            val p = profile(a)
            if (p.kind != Kind.HONEST || a in run.churned) continue
            val oldest = run.pending.values.filter { it.cashout.account == a }.minOf { it.openedDay }
            if (day - oldest >= 1 && k.u("wait-churn", a.toLong(), day.toLong()) < players.d("waitChurnPerDay")) {
                run.churned.add(a)
                run.events.churn.add(Events.Churn(a, day, lost(p), "waited"))
            }
        }
        for ((a, since) in run.blocked) {
            val p = profile(a)
            if (p.kind != Kind.HONEST || day - since != 3 || a in run.churned) continue
            if (k.u("block-churn", a.toLong()) < players.d("blockChurn")) {
                run.churned.add(a)
                run.events.churn.add(Events.Churn(a, day, lost(p), "blocked"))
            }
        }
        for ((a, d, _) in run.events.restored) {
            val p = profile(a)
            if (d != day || p.kind != Kind.HONEST || a in run.churned) continue
            val since = run.events.blocks.last { it.account == a }.day
            if (day - since <= 3 && k.u("restore-churn", a.toLong()) < players.d("blockChurnIfRestoredWithin3Days")) {
                run.churned.add(a)
                run.events.churn.add(Events.Churn(a, day, lost(p), "blocked"))
            }
        }
    }

    /** Days the analysts' cost and the step-up fees are counted in, in cents. */
    fun analystCostCents(minutes: Double) = minutes / 60.0 * analysts.d("costPerHourCents")
    fun stepUpCostCents(count: Int) = count * stepUpCfg.d("costCents")
    fun supportCostCents(minutes: Double) = minutes / 60.0 * support.d("costPerHourCents")

    companion object {
        fun simulate(seed: Long, cfg: JsonObject = Settings.load("world")): World = World(seed, cfg).also { it.build() }
    }
}
