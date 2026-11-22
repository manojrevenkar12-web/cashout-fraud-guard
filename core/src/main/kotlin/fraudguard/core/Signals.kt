package fraudguard.core

import kotlin.math.ln
import kotlin.math.min

/**
 * The features the risk model reads for one cash-out, from the last [WINDOW] days of the account's logs and from the
 * link graph. Each feature maps to the [Reason] an analyst sees when it drives a decision.
 */
object Signals {
    const val WINDOW = 14

    val NAMES = listOf(
        "device_failed_attestation", "device_basic_attestation", "vpn_or_datacenter_share", "geo_mismatch_share",
        "earn_cents_per_minute", "scripted_timing", "offers_per_offer_hour", "account_age_days", "new_payout_account",
        "ring_size", "ring_confirmed_fraud", "accounts_on_payout_account", "accounts_on_fingerprint",
        "datacenter_network_fraud_share", "amount", "devices_in_window",
    )
    val REASONS = listOf(
        Reason.EMULATOR, Reason.WEAK_DEVICE, Reason.VPN_OR_DATACENTER, Reason.GEO_MISMATCH, Reason.EARN_SPEED,
        Reason.SCRIPTED_TIMING, Reason.OFFER_SPEED, Reason.NEW_ACCOUNT, Reason.PAYOUT_SWITCH, Reason.RING,
        Reason.RING_CONFIRMED, Reason.SHARED_PAYOUT, Reason.SHARED_DEVICE, Reason.DATACENTER_NEIGHBOURS,
        Reason.LARGE_AMOUNT, Reason.SHARED_DEVICE,
    )

    /** Indices of the features that come from the link graph; a design without the graph zeroes them. */
    val GRAPH = setOf(9, 10, 11, 12, 13)

    fun features(c: Cashout, o: Observed): DoubleArray {
        val logs = o.window(c.account, c.day - WINDOW + 1, c.day)
        val n = logs.size.coerceAtLeast(1).toDouble()
        val devs = logs.map { it.device }.toSet() + c.device
        val integrities = devs.mapNotNull { o.devices[it]?.integrity }
        val minutes = logs.sumOf { it.minutes.toDouble() }
        val cents = logs.sumOf { it.earnedCents.toLong() }.toDouble()
        val offers = logs.sumOf { it.offers }
        val offerHours = logs.sumOf { it.offerMinutes.toDouble() } / 60.0
        val spread = if (logs.isEmpty()) 3.0 else logs.sumOf { it.startSpreadH.toDouble() } / n
        val age = c.day - (o.accounts[c.account]?.signupDay ?: c.day)
        val pay = o.payouts[c.account]
        val firstSeen = pay?.get(c.payoutAccount) ?: c.day
        val switched = pay != null && pay.size > 1 && c.day - firstSeen <= 3 && pay.values.min() < firstSeen
        val fp = o.devices[c.device]?.fingerprint
        val lastNet = logs.lastOrNull()
        return doubleArrayOf(
            if (Integrity.FAILED in integrities) 1.0 else 0.0,
            if (Integrity.BASIC in integrities && Integrity.FAILED !in integrities) 1.0 else 0.0,
            logs.count { it.net == Net.VPN || it.net == Net.DATACENTER } / n,
            logs.count { !it.ipMatchesStoreCountry } / n,
            ln(1.0 + cents / (minutes + 1.0)),
            1.0 / (spread + 0.25),
            ln(1.0 + offers / (offerHours + 0.05)),
            ln(1.0 + age.coerceAtLeast(0)),
            if (switched) 1.0 else 0.0,
            ln(o.graph.ringSize(c.account).toDouble()),
            min(o.graph.ringConfirmed(c.account), 3).toDouble(),
            ln(o.graph.sharing(Graph.PAYOUT, c.payoutAccount).coerceAtLeast(1).toDouble()),
            ln((if (fp == null) 1 else o.graph.sharing(Graph.FINGERPRINT, fp)).coerceAtLeast(1).toDouble()),
            if (lastNet == null || lastNet.net == Net.RESIDENTIAL || lastNet.net == Net.MOBILE) 0.0 else o.datacenterFraudShare(lastNet.ipPrefix, c.day, 14),
            ln(1.0 + c.cents / 100.0),
            ln(devs.size.toDouble()),
        )
    }
}
