package app.leximo.ecu_vue

import app.leximo.ecu_vue.transport.BlockingPromptTransport
import app.leximo.ecu_vue.transport.boundedIo
import app.leximo.ecuvue.core.DiagnosticException
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import java.io.IOException
import java.util.concurrent.CountDownLatch

class PromptTransportTest {
    @Test fun fragmentedRepliesAndConcurrentCommandsRemainOrdered() = runBlocking {
        val transport = TestPrompt(ArrayDeque(listOf("41 0", "C 0C ", "A0\r>", "43 00>")))
        val first = async { transport.send("010C") }
        val second = async { transport.send("03") }
        assertEquals("41 0C 0C A0\r", first.await())
        assertEquals("43 00", second.await())
        assertEquals(listOf("010C\r", "03\r"), transport.writes)
    }
    @Test fun oversizedReplyClosesTheTransport() = runBlocking {
        val transport = TestPrompt(ArrayDeque(listOf("A".repeat(4096))).also { repeat(16) { _ -> it.add("A".repeat(4096)) } })
        try { transport.send("03"); fail("Oversized reply accepted") }
        catch (error: DiagnosticException) { assertEquals("connectionLost", error.key) }
        assertTrue(transport.closed)
    }
    @Test fun deadlinesCloseAnOtherwiseBlockedRead() = runBlocking {
        val closed = CountDownLatch(1)
        try {
            boundedIo(50, { closed.countDown() }) { closed.await(); throw IOException("Closed by deadline") }
            fail("Expected timeout")
        } catch (error: DiagnosticException) { assertEquals("timeout", error.key) }
        assertEquals(0, closed.count)
    }
    @Test fun rawCommandFormattingCannotInjectAnotherRequest() = runBlocking {
        val transport = TestPrompt(ArrayDeque())
        try { transport.send("03\r04"); fail("Injected command accepted") }
        catch (error: DiagnosticException) { assertEquals("commandFailed", error.key) }
        assertTrue(transport.writes.isEmpty())
    }
}
private class TestPrompt(private val replies: ArrayDeque<String>) : BlockingPromptTransport() {
    val writes = mutableListOf<String>()
    var closed = false
    override suspend fun connect() {}
    override fun write(bytes: ByteArray) { writes += bytes.decodeToString() }
    override fun read(buffer: ByteArray): Int {
        val reply = replies.removeFirstOrNull() ?: return -1
        val bytes = reply.encodeToByteArray()
        bytes.copyInto(buffer)
        return bytes.size
    }
    override fun close() { closed = true }
}
