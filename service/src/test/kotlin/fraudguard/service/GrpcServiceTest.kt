package fraudguard.service

import fraudguard.v1.Activity
import fraudguard.v1.Appeal
import fraudguard.v1.CashoutGuardGrpcKt
import fraudguard.v1.CashoutRequest
import fraudguard.v1.Network
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GrpcServiceTest {
    private val name = InProcessServerBuilder.generateName()
    private val server = InProcessServerBuilder.forName(name).directExecutor()
        .addService(GuardGrpcService(Guard(GuardModule.loadPolicy(), MemoryDecisionStore()))).build().start()
    private val channel = InProcessChannelBuilder.forName(name).directExecutor().build()
    private val stub = CashoutGuardGrpcKt.CashoutGuardCoroutineStub(channel)

    @AfterEach
    fun close() {
        channel.shutdownNow()
        server.shutdownNow()
    }

    private fun req(id: String, cents: Int = 500) = CashoutRequest.newBuilder().setRequestId(id).setAccount(1).setDay(11).setHour(9).setCents(cents)
        .setMethod("paypal").setPayoutAccount(5001).setDevice(101).build()

    @Test
    fun checkIsIdempotentOverGrpc() = runBlocking {
        stub.recordActivity(Activity.newBuilder().setAccount(1).setDay(10).setMinutes(45f).setEarnedCents(40).setNetwork(Network.MOBILE).setDevice(101).setDeviceFingerprint(9001).setStartSpreadH(3f).build())
        val first = stub.check(req("r-1"))
        val again = stub.check(req("r-1"))
        assertTrue(again.replayed)
        assertEquals(first.action, again.action)
        val file = stub.getCaseFile(fraudguard.v1.CaseFileRequest.newBuilder().setRequestId("r-1").build())
        assertEquals(1, file.account)
        assertTrue(file.featuresMap.containsKey("ring_size"))
    }

    @Test
    fun badInputIsRefusedWithTheRightStatus() = runBlocking {
        val bad = assertThrows(StatusException::class.java) { runBlocking { stub.check(req("no spaces allowed")) } }
        assertEquals(Status.Code.INVALID_ARGUMENT, bad.status.code)
        val unknown = assertThrows(StatusException::class.java) { runBlocking { stub.readAppeal(Appeal.newBuilder().setRequestId("missing").setText("hi").build()) } }
        assertEquals(Status.Code.NOT_FOUND, unknown.status.code)
        val zero = assertThrows(StatusException::class.java) { runBlocking { stub.check(req("r-2", cents = 0)) } }
        assertEquals(Status.Code.INVALID_ARGUMENT, zero.status.code)
    }
}
