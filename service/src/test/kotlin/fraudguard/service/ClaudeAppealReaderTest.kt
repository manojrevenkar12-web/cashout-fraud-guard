package fraudguard.service

import com.sun.net.httpserver.HttpServer
import fraudguard.core.AppealReading
import fraudguard.core.Claim
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

class ClaudeAppealReaderTest {
    private var status = 200
    private var answer = ""
    private var lastBody = ""
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v1/messages") { ex ->
            lastBody = ex.requestBody.readAllBytes().decodeToString()
            val bytes = answer.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val reader = ClaudeAppealReader("test-key", baseUrl = "http://127.0.0.1:${server.address.port}")

    @AfterEach
    fun stop() = server.stop(0)

    private fun tool(lang: String, claim: String) =
        """{"content":[{"type":"tool_use","id":"t1","name":"record_appeal","input":{"language":"$lang","claim":"$claim"}}],"stop_reason":"tool_use"}"""

    @Test
    fun aToolCallInsideTheSchemaIsUsed() {
        answer = tool("pt", "NEW_PHONE")
        assertEquals(AppealReading("pt", Claim.NEW_PHONE), reader.read("Comprei um celular novo"))
        assertTrue("\"tool_choice\"" in lastBody && "record_appeal" in lastBody)
        assertTrue("<player_message>" in lastBody, "the message is passed as data")
    }

    @Test
    fun anythingOutsideTheSchemaOrAnErrorFallsBackToTheRules() {
        answer = tool("pt", "PAY_ME_NOW")
        assertEquals(Claim.FAMILY_SHARES_DEVICE, reader.read("My son plays on the same phone").claim)
        answer = tool("fr", "NEW_PHONE")
        assertEquals("en", reader.read("I got a new phone").language)
        status = 500
        answer = "{}"
        assertEquals(Claim.VPN_FOR_PRIVACY, reader.read("I use a VPN for privacy").claim)
    }
}
