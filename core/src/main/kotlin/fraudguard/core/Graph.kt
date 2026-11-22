package fraudguard.core

/**
 * Accounts linked by a shared hardware fingerprint or a shared payout account, kept as a union-find with member
 * lists at each root so a ring's size and its confirmed fraudsters are read in constant time.
 */
class Graph {
    private val parent = HashMap<Int, Int>()
    private val members = HashMap<Int, MutableList<Int>>()
    private val confirmed = HashMap<Int, Int>()
    private val ownerOfKey = HashMap<Long, Int>()
    private val accountsOfKey = HashMap<Long, MutableSet<Int>>()

    fun add(account: Int) {
        if (account !in parent) {
            parent[account] = account
            members[account] = mutableListOf(account)
        }
    }

    fun find(a: Int): Int {
        add(a)
        var r = a
        while (parent[r] != r) r = parent[r]!!
        var x = a
        while (parent[x] != r) {
            val n = parent[x]!!
            parent[x] = r
            x = n
        }
        return r
    }

    private fun union(a: Int, b: Int) {
        var ra = find(a)
        var rb = find(b)
        if (ra == rb) return
        if (members[ra]!!.size < members[rb]!!.size) ra = rb.also { rb = ra }
        parent[rb] = ra
        members[ra]!!.addAll(members.remove(rb)!!)
        confirmed[ra] = (confirmed[ra] ?: 0) + (confirmed.remove(rb) ?: 0)
    }

    /** Links [account] to every other account seen with the same key. Keys: 1=fingerprint, 2=payout account. */
    fun link(account: Int, kind: Int, value: Int) {
        val key = (kind.toLong() shl 32) or (value.toLong() and 0xffffffffL)
        accountsOfKey.getOrPut(key) { HashSet() }.add(account)
        val owner = ownerOfKey.putIfAbsent(key, account)
        if (owner != null) union(owner, account) else add(account)
    }

    fun sharing(kind: Int, value: Int): Int = accountsOfKey[(kind.toLong() shl 32) or (value.toLong() and 0xffffffffL)]?.size ?: 0

    fun ringSize(account: Int): Int = members[find(account)]!!.size

    fun ringMembers(account: Int): List<Int> = members[find(account)]!!

    fun ringConfirmed(account: Int): Int = confirmed[find(account)] ?: 0

    fun markFraud(account: Int) {
        val r = find(account)
        confirmed[r] = (confirmed[r] ?: 0) + 1
    }

    fun unmarkFraud(account: Int) {
        val r = find(account)
        confirmed[r] = ((confirmed[r] ?: 0) - 1).coerceAtLeast(0)
    }

    companion object {
        const val FINGERPRINT = 1
        const val PAYOUT = 2
    }
}
