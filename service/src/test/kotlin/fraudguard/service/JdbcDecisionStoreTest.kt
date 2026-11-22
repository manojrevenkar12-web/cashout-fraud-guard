package fraudguard.service

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import fraudguard.core.Action
import fraudguard.core.Cashout
import fraudguard.core.Decision
import fraudguard.core.Method
import fraudguard.core.Reason
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Runs against a real MySQL 8.4 in a container: the schema comes from the Liquibase changelog, not from the test. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcDecisionStoreTest {
    private val mysql = MySQLContainer<Nothing>(DockerImageName.parse("mysql:8.4")).apply { start() }
    private val ds = HikariDataSource(HikariConfig().apply { jdbcUrl = mysql.jdbcUrl; username = mysql.username; password = mysql.password; maximumPoolSize = 8 })
    private val store = JdbcDecisionStore(ds).also { Migrations.run(ds) }

    @AfterAll
    fun stop() {
        ds.close()
        mysql.stop()
    }

    private val c = Cashout(0, 7, 11, 9, 500, Method.PAYPAL, 5001, 101)

    @Test
    fun aRequestIsDecidedOnceEvenWhenEightPodsAskAtOnce() {
        val pool = Executors.newFixedThreadPool(8)
        val results = (0 until 8).map { i -> pool.submit(Callable { store.saveFirst("race-1", c, Decision(Action.PAY, 0.01 * i, emptyList()), "{}") }) }.map { it.get() }
        pool.shutdown()
        assertEquals(1, results.count { !it.second }, "exactly one writer wins")
        assertEquals(1, results.map { it.first.score }.toSet().size, "every caller gets the winner's decision")
        val count = ds.connection.use { it.createStatement().executeQuery("SELECT COUNT(*) FROM decision WHERE request_id = 'race-1'").use { r -> r.next(); r.getInt(1) } }
        assertEquals(1, count)
    }

    @Test
    fun laterDecisionsAppendAndTheOutboxIsDrainedOnce() {
        store.saveFirst("flow-1", c, Decision(Action.STEP_UP, 0.3, listOf(Reason.RING)), "{\"a\":1}")
        store.saveNext("flow-1", Decision(Action.HOLD, 0.3, listOf(Reason.FACE_REUSED, Reason.RING), 1.8), "{\"a\":2}")
        val (cash, latest) = store.latest("flow-1")!!
        assertEquals(500, cash.cents)
        assertEquals(Action.HOLD, latest.action)
        assertEquals(listOf(Reason.FACE_REUSED, Reason.RING), latest.reasons)
        store.saveVerdict(7, true, "ana", "{}")
        val published = ArrayList<String>()
        val relay = OutboxRelay(store) { topic, _ -> published.add(topic) }
        assertTrue(relay.drain() >= 3)
        assertEquals(0, relay.drain(), "nothing is published twice")
        assertTrue(TOPIC_VERDICTS in published)
    }
}
