package fraudguard.service

import com.google.inject.AbstractModule
import com.google.inject.Guice
import com.google.inject.Provides
import com.google.inject.Singleton
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import fraudguard.core.AppealReader
import fraudguard.core.Policy
import fraudguard.core.PolicyConfig
import fraudguard.core.RiskModel
import fraudguard.core.RuleAppealReader
import fraudguard.core.Settings
import fraudguard.core.obj
import io.grpc.ServerBuilder
import io.grpc.protobuf.services.HealthStatusManager
import io.grpc.health.v1.HealthCheckResponse.ServingStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/** Wires the service from the environment: Cloud SQL through JDBC, the exported model, the guard's policy, the appeal reader. */
class GuardModule(private val env: Map<String, String>) : AbstractModule() {
    @Provides
    @Singleton
    fun dataSource(): DataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = env["JDBC_URL"] ?: "jdbc:mysql://localhost:3306/fraudguard"
            username = env["DB_USER"] ?: "fraudguard"
            password = env["DB_PASSWORD"] ?: ""
            maximumPoolSize = (env["DB_POOL"] ?: "10").toInt()
        },
    )

    @Provides
    @Singleton
    fun store(ds: DataSource): DecisionStore = JdbcDecisionStore(ds).also { Migrations.run(ds) }

    @Provides
    @Singleton
    fun policy(): Policy = loadPolicy()

    @Provides
    @Singleton
    fun reader(): AppealReader = env["ANTHROPIC_API_KEY"]?.takeIf { it.isNotBlank() }?.let { ClaudeAppealReader(it, env["ANTHROPIC_MODEL"] ?: "claude-haiku-5-5") } ?: RuleAppealReader

    @Provides
    @Singleton
    fun guard(policy: Policy, store: DecisionStore, reader: AppealReader) = Guard(policy, store, reader)

    companion object {
        /** The guard design from the settings and the model the evaluation exported. */
        fun loadPolicy(): Policy {
            val model = GuardModule::class.java.getResource("/model/risk-model.json")?.readText() ?: error("model/risk-model.json missing from the classpath")
            return Policy(PolicyConfig.from(Settings.load("guard").obj("designs").obj("guard")), RiskModel.fromJson(Json.parseToJsonElement(model).jsonObject))
        }
    }
}

/** Publishes outbox rows. Production binds a Pub/Sub publisher; this build logs them. */
fun interface Publisher {
    fun publish(topic: String, payload: String)
}

class OutboxRelay(private val store: DecisionStore, private val publisher: Publisher) {
    fun drain(batch: Int = 100): Int {
        val events = store.unpublished(batch)
        for (e in events) publisher.publish(e.topic, e.payload)
        store.markPublished(events.map { it.id })
        return events.size
    }
}

fun main() {
    val injector = Guice.createInjector(GuardModule(System.getenv()))
    val health = HealthStatusManager()
    val port = (System.getenv("PORT") ?: "50051").toInt()
    val server = ServerBuilder.forPort(port).addService(injector.getInstance(GuardGrpcService::class.java)).addService(health.healthService).build().start()
    health.setStatus("", ServingStatus.SERVING)
    val relay = OutboxRelay(injector.getInstance(DecisionStore::class.java)) { topic, payload -> println("publish $topic $payload") }
    val scheduler = Executors.newSingleThreadScheduledExecutor()
    scheduler.scheduleWithFixedDelay({ runCatching { relay.drain() } }, 1, 1, TimeUnit.SECONDS)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            health.enterTerminalState()
            server.shutdown().awaitTermination(20, TimeUnit.SECONDS)
            scheduler.shutdown()
        },
    )
    println("cashout-fraud-guard listening on $port")
    server.awaitTermination()
}
