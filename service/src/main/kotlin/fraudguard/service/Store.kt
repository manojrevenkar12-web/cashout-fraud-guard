package fraudguard.service

import fraudguard.core.Action
import fraudguard.core.Cashout
import fraudguard.core.Decision
import fraudguard.core.Method
import fraudguard.core.Reason
import liquibase.Contexts
import liquibase.LabelExpression
import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import java.sql.Connection
import java.sql.SQLIntegrityConstraintViolationException
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

class OutboxEvent(val id: Long, val topic: String, val payload: String)

/**
 * Where requests, decisions, verdicts and outgoing events are kept. Every cash-out request is stored once under its
 * request id, with its first decision and an outbox event, in one transaction; a retry reads the stored decision.
 */
interface DecisionStore {
    /** Stores the request and its first decision once. Returns the decision on record and whether it was already there. */
    fun saveFirst(requestId: String, c: Cashout, d: Decision, event: String): Pair<Decision, Boolean>
    fun latest(requestId: String): Pair<Cashout, Decision>?
    fun saveNext(requestId: String, d: Decision, event: String)
    fun saveVerdict(account: Int, fraud: Boolean, analyst: String, event: String)
    fun unpublished(limit: Int): List<OutboxEvent>
    fun markPublished(ids: List<Long>)
}

internal fun Decision.reasonsText() = reasons.joinToString(",") { it.name }
internal fun reasonsOf(s: String) = if (s.isBlank()) emptyList() else s.split(",").map { Reason.valueOf(it) }

class MemoryDecisionStore : DecisionStore {
    private val requests = ConcurrentHashMap<String, Cashout>()
    private val decisions = ConcurrentHashMap<String, MutableList<Decision>>()
    private val outbox = ArrayList<OutboxEvent>()
    private val published = HashSet<Long>()
    val verdicts = ArrayList<Triple<Int, Boolean, String>>()

    @Synchronized
    override fun saveFirst(requestId: String, c: Cashout, d: Decision, event: String): Pair<Decision, Boolean> {
        decisions[requestId]?.let { return it.first() to true }
        requests[requestId] = c
        decisions[requestId] = mutableListOf(d)
        outbox.add(OutboxEvent(outbox.size + 1L, TOPIC_DECISIONS, event))
        return d to false
    }

    @Synchronized
    override fun latest(requestId: String) = requests[requestId]?.let { it to decisions.getValue(requestId).last() }

    @Synchronized
    override fun saveNext(requestId: String, d: Decision, event: String) {
        decisions.getValue(requestId).add(d)
        outbox.add(OutboxEvent(outbox.size + 1L, TOPIC_DECISIONS, event))
    }

    @Synchronized
    override fun saveVerdict(account: Int, fraud: Boolean, analyst: String, event: String) {
        verdicts.add(Triple(account, fraud, analyst))
        outbox.add(OutboxEvent(outbox.size + 1L, TOPIC_VERDICTS, event))
    }

    @Synchronized
    override fun unpublished(limit: Int) = outbox.filter { it.id !in published }.take(limit)

    @Synchronized
    override fun markPublished(ids: List<Long>) {
        published.addAll(ids)
    }
}

/** MySQL (Cloud SQL) store. The schema is owned by the Liquibase changelog. */
class JdbcDecisionStore(private val ds: DataSource) : DecisionStore {
    private fun <T> tx(block: (Connection) -> T): T = ds.connection.use { c ->
        c.autoCommit = false
        try {
            block(c).also { c.commit() }
        } catch (e: Exception) {
            c.rollback()
            throw e
        }
    }

    private fun insertDecision(c: Connection, requestId: String, d: Decision) {
        c.prepareStatement("INSERT INTO decision (request_id, action, score, reasons, priority) VALUES (?, ?, ?, ?, ?)").use {
            it.setString(1, requestId)
            it.setString(2, d.action.name)
            it.setDouble(3, d.score)
            it.setString(4, d.reasonsText())
            it.setDouble(5, d.priority)
            it.executeUpdate()
        }
    }

    private fun insertOutbox(c: Connection, topic: String, payload: String) {
        c.prepareStatement("INSERT INTO outbox (topic, payload) VALUES (?, ?)").use {
            it.setString(1, topic)
            it.setString(2, payload)
            it.executeUpdate()
        }
    }

    override fun saveFirst(requestId: String, c: Cashout, d: Decision, event: String): Pair<Decision, Boolean> = try {
        tx { conn ->
            conn.prepareStatement("INSERT INTO cashout_request (request_id, account_id, day, hour, cents, method, payout_account, device) VALUES (?, ?, ?, ?, ?, ?, ?, ?)").use {
                it.setString(1, requestId)
                it.setInt(2, c.account)
                it.setInt(3, c.day)
                it.setInt(4, c.hour)
                it.setInt(5, c.cents)
                it.setString(6, c.method.name)
                it.setInt(7, c.payoutAccount)
                it.setInt(8, c.device)
                it.executeUpdate()
            }
            insertDecision(conn, requestId, d)
            insertOutbox(conn, TOPIC_DECISIONS, event)
            d to false
        }
    } catch (e: SQLIntegrityConstraintViolationException) {
        // the same request id arrived twice (a retry, or two pods at once): the first decision stands
        first(requestId)!! to true
    }

    private fun first(requestId: String): Decision? = ds.connection.use { c ->
        c.prepareStatement("SELECT action, score, reasons, priority FROM decision WHERE request_id = ? ORDER BY id LIMIT 1").use { s ->
            s.setString(1, requestId)
            s.executeQuery().use { r -> if (r.next()) Decision(Action.valueOf(r.getString(1)), r.getDouble(2), reasonsOf(r.getString(3)), r.getDouble(4)) else null }
        }
    }

    override fun latest(requestId: String): Pair<Cashout, Decision>? = ds.connection.use { c ->
        c.prepareStatement(
            """SELECT q.account_id, q.day, q.hour, q.cents, q.method, q.payout_account, q.device, d.action, d.score, d.reasons, d.priority
               FROM cashout_request q JOIN decision d ON d.request_id = q.request_id
               WHERE q.request_id = ? ORDER BY d.id DESC LIMIT 1""",
        ).use { s ->
            s.setString(1, requestId)
            s.executeQuery().use { r ->
                if (!r.next()) return null
                Cashout(requestId.hashCode().toLong(), r.getInt(1), r.getInt(2), r.getInt(3), r.getInt(4), Method.valueOf(r.getString(5)), r.getInt(6), r.getInt(7)) to
                    Decision(Action.valueOf(r.getString(8)), r.getDouble(9), reasonsOf(r.getString(10)), r.getDouble(11))
            }
        }
    }

    override fun saveNext(requestId: String, d: Decision, event: String) = tx { c ->
        insertDecision(c, requestId, d)
        insertOutbox(c, TOPIC_DECISIONS, event)
    }

    override fun saveVerdict(account: Int, fraud: Boolean, analyst: String, event: String) = tx { c ->
        c.prepareStatement("INSERT INTO verdict (account_id, fraud, analyst) VALUES (?, ?, ?)").use {
            it.setInt(1, account)
            it.setBoolean(2, fraud)
            it.setString(3, analyst)
            it.executeUpdate()
        }
        insertOutbox(c, TOPIC_VERDICTS, event)
    }

    override fun unpublished(limit: Int): List<OutboxEvent> = ds.connection.use { c ->
        c.prepareStatement("SELECT id, topic, payload FROM outbox WHERE published_at IS NULL ORDER BY id LIMIT ?").use { s ->
            s.setInt(1, limit)
            s.executeQuery().use { r -> buildList { while (r.next()) add(OutboxEvent(r.getLong(1), r.getString(2), r.getString(3))) } }
        }
    }

    override fun markPublished(ids: List<Long>) {
        if (ids.isEmpty()) return
        tx { c ->
            c.prepareStatement("UPDATE outbox SET published_at = CURRENT_TIMESTAMP(3) WHERE id = ?").use { s ->
                for (id in ids) {
                    s.setLong(1, id)
                    s.addBatch()
                }
                s.executeBatch()
            }
        }
    }
}

object Migrations {
    const val CHANGELOG = "db/changelog/db.changelog-master.yaml"

    fun run(ds: DataSource) {
        ds.connection.use { c ->
            val db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(JdbcConnection(c))
            @Suppress("DEPRECATION")
            Liquibase(CHANGELOG, ClassLoaderResourceAccessor(), db).update(Contexts(), LabelExpression())
        }
    }
}

const val TOPIC_DECISIONS = "cashout-decisions"
const val TOPIC_VERDICTS = "analyst-verdicts"
