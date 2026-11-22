package fraudguard.core

/**
 * Everything the fraud service can see: accounts, devices, daily activity logs, payout accounts, the link graph,
 * analyst verdicts and selfie templates. The simulated world appends to it; it never holds who is really a fraudster.
 */
class Observed {
    val accounts = HashMap<Int, AccountInfo>()
    val devices = HashMap<Int, DeviceInfo>()
    private val logs = HashMap<Int, ArrayList<DayLog>>()
    val payouts = HashMap<Int, LinkedHashMap<Int, Int>>() // account -> payout account -> first day seen
    val graph = Graph()
    val confirmedFraud = HashSet<Int>()
    val confirmedHonest = HashSet<Int>()
    val watched = HashSet<Int>()
    val faceOf = HashMap<Int, Int>()
    val lastSelfieDay = HashMap<Int, Int>()
    private val accountsOfFace = HashMap<Int, MutableSet<Int>>()
    private val prefixSeen = HashMap<Int, HashMap<Int, Int>>() // datacenter/VPN prefix -> account -> last day

    fun addAccount(a: AccountInfo) {
        accounts[a.id] = a
        graph.add(a.id)
    }

    fun addDevice(d: DeviceInfo) {
        devices[d.id] = d
    }

    fun log(l: DayLog) {
        logs.getOrPut(l.account) { ArrayList() }.add(l)
        devices[l.device]?.let { graph.link(l.account, Graph.FINGERPRINT, it.fingerprint) }
        if (l.net == Net.DATACENTER || l.net == Net.VPN) prefixSeen.getOrPut(l.ipPrefix) { HashMap() }[l.account] = l.day
    }

    fun seePayout(account: Int, payoutAccount: Int, day: Int) {
        payouts.getOrPut(account) { LinkedHashMap() }.putIfAbsent(payoutAccount, day)
        graph.link(account, Graph.PAYOUT, payoutAccount)
    }

    /** Logs of [account] from day [from] to day [to], inclusive. */
    fun window(account: Int, from: Int, to: Int): List<DayLog> {
        val l = logs[account] ?: return emptyList()
        var i = l.size
        while (i > 0 && l[i - 1].day >= from) i--
        return l.subList(i, l.size).filter { it.day <= to }
    }

    /**
     * Of the accounts seen on this VPN or datacenter network in the last [days] days, the share already confirmed as
     * fraud. A public VPN exit is shared by thousands of honest users; a farm's own servers are not.
     */
    fun datacenterFraudShare(prefix: Int, day: Int, days: Int): Double {
        val recent = prefixSeen[prefix]?.filter { day - it.value <= days }?.keys ?: return 0.0
        return if (recent.isEmpty()) 0.0 else recent.count { it in confirmedFraud }.toDouble() / recent.size
    }

    /** Records a selfie template; returns the other accounts already seen with the same face. */
    fun recordFace(account: Int, template: Int, day: Int): Set<Int> {
        faceOf[account] = template
        lastSelfieDay[account] = day
        val s = accountsOfFace.getOrPut(template) { HashSet() }
        s.add(account)
        return s - account
    }

    fun confirmFraud(account: Int) {
        if (confirmedFraud.add(account)) graph.markFraud(account)
        confirmedHonest.remove(account)
    }

    /** An analyst cleared the account, for example after an appeal. */
    fun confirmHonest(account: Int) {
        if (confirmedFraud.remove(account)) graph.unmarkFraud(account)
        confirmedHonest.add(account)
        watched.remove(account)
    }
}
