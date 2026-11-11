package io.github.nanomuse.sync

import com.openminis.app.ui.chat.AssistantBlock
import io.github.nanomuse.hub.Device
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RemoteChatBridgeTest {
    private val device = Device("desk", "Desk", "computer", "Linux", "", true, listOf("task"), 0)
    private class Host : RemoteChatBridge.Host {
        var running = false
        override val busy: Boolean get() = running
        var draft = "@Desk hello"
        var userCount = 0
        var saved = 0
        var failSave = false
        var updates = 0
        var ended = 0
        var tracked = 0
        val completions = mutableListOf<Boolean>()
        val errors = mutableListOf<RemoteChatBridge.Issue>()
        override fun setBusy(value: Boolean) { running = value }
        override fun error(issue: RemoteChatBridge.Issue, device: String) { errors += issue }
        override fun clearDraft() { draft = "" }
        override fun restoreDraft(text: String) { draft = text }
        override suspend fun session() = "session"
        override suspend fun user(sessionId: String, text: String) { userCount++ }
        override fun started(sessionId: String) { tracked++ }
        override fun ended(sessionId: String, completed: Boolean) { tracked--; completions += completed }
        override fun show(id: String, task: RemoteChatBridge.Task) { updates++ }
        override suspend fun finish(sessionId: String, id: String, task: RemoteChatBridge.Task) {
            check(!failSave) { "storage unavailable" }
            saved++
        }
        override fun afterTurn() { ended++ }
    }
    private class Task(private val wait: Boolean = false) : RemoteChatBridge.Task {
        override val text = "remote answer"
        override val blocks = emptyList<AssistantBlock>()
        override val failed = false
        override var stopped = false
        var stops = 0
        var lateUpdate: (() -> Unit)? = null
        override suspend fun run(sessionId: String, update: () -> Unit) {
            lateUpdate = update
            update()
            if (wait) awaitCancellation()
        }
        override fun stop() { stops++; stopped = true }
    }

    @Test fun `direct routing records one user and one answer and never creates a local task`() = runTest {
        val host = Host()
        var created = 0
        val task = Task()
        val bridge = RemoteChatBridge(this, host) { d, text, _, _ ->
            created++; assertEquals("desk", d.id); assertEquals("hello", text); task
        }
        assertFalse(bridge.send("ordinary message", listOf(device), false, false))
        assertEquals(0, created)
        assertTrue(bridge.send("@Desk hello", listOf(device), false, false))
        runCurrent()
        assertEquals(1, created)
        assertEquals(1, host.userCount)
        assertEquals(1, host.saved)
        assertEquals(1, host.ended)
        assertEquals(listOf(true), host.completions)
        assertEquals(0, host.tracked)
        assertFalse(host.busy)
        val updates = host.updates
        task.lateUpdate!!()
        assertEquals("a completed reply must not be resurrected", updates, host.updates)
    }

    @Test fun `offline attachments and busy refusals keep the draft and never call the transport`() = runTest {
        for (kind in listOf("offline", "attachments", "busy")) {
            // The actual composer clears its field immediately before invoking sendMessage.
            val host = Host().apply { running = kind == "busy"; draft = "" }
            val bridge = RemoteChatBridge(this, host) { _, _, _, _ -> error("must not start") }
            assertTrue(bridge.send("@Desk hello", listOf(device.copy(online = kind != "offline")), kind == "attachments", false))
            assertEquals("@Desk hello", host.draft)
            assertEquals(0, host.saved)
            assertEquals(1, host.errors.size)
        }
    }

    @Test fun `cancelling a running task persists once and releases the conversation`() = runTest {
        val host = Host()
        val task = Task(wait = true)
        val bridge = RemoteChatBridge(this, host) { _, _, _, _ -> task }
        bridge.send("@Desk hello", listOf(device), false, false)
        assertEquals(1, host.tracked)
        assertTrue(bridge.cancel())
        runCurrent()
        assertEquals(1, host.saved)
        assertEquals(listOf(false), host.completions)
        assertEquals(0, host.tracked)
        assertFalse(host.busy)
        assertFalse(bridge.cancel())
    }

    @Test fun `failed persistence releases tracking without marking an answer unread`() = runTest {
        val host = Host().apply { failSave = true }
        val bridge = RemoteChatBridge(this, host) { _, _, _, _ -> Task() }
        bridge.send("@Desk hello", listOf(device), false, false)
        runCurrent()
        assertEquals(0, host.saved)
        assertEquals(listOf(false), host.completions)
        assertEquals(0, host.tracked)
        assertFalse(host.busy)
    }

    @Test fun `a mention connects the chat and later messages go there without it`() = runTest {
        val host = Host()
        val sent = mutableListOf<Pair<String, String?>>()
        val bridge = RemoteChatBridge(this, host) { _, text, _, conversation -> sent += text to conversation; Task() }
        assertTrue(bridge.send("@Desk hello", listOf(device), false, false))
        runCurrent()
        assertEquals("desk", bridge.target.value?.id)
        assertTrue(bridge.send("and the second one?", listOf(device), false, false))
        runCurrent()
        assertEquals(listOf("hello", "and the second one?"), sent.map { it.first })
        val conversation = sent.first().second
        assertNotNull(conversation)
        assertEquals("both turns continue one conversation there", conversation, sent.last().second)
        bridge.disconnect()
        assertFalse(bridge.send("now here", listOf(device), false, false))
    }

    @Test fun `a bare mention only connects`() = runTest {
        val host = Host()
        val bridge = RemoteChatBridge(this, host) { _, _, _, _ -> error("must not start") }
        assertTrue(bridge.send("@Desk", listOf(device), false, false))
        assertEquals("desk", bridge.target.value?.id)
        assertEquals("", host.draft)
        assertEquals(0, host.userCount)
    }

    @Test fun `a connected chat never falls back to this phone`() = runTest {
        val host = Host().apply { draft = "" }
        val bridge = RemoteChatBridge(this, host) { _, _, _, _ -> error("must not start") }
        bridge.connect(device)
        assertTrue(bridge.send("hello", listOf(device.copy(online = false)), false, false))
        assertEquals(listOf(RemoteChatBridge.Issue.OFFLINE_CONNECTED), host.errors)
        assertEquals("hello", host.draft)
        assertEquals("desk", bridge.target.value?.id)
        // the list not loaded yet: offline, still connected
        assertTrue(bridge.send("hello", emptyList(), false, false))
        assertEquals("desk", bridge.target.value?.id)
        // gone from the account: disconnected, said once
        val other = device.copy(id = "laptop", name = "Laptop")
        assertTrue(bridge.send("hello", listOf(other), false, false))
        assertEquals(RemoteChatBridge.Issue.GONE, host.errors.last())
        assertNull(bridge.target.value)
    }

    @Test fun `the connection is kept once the chat is saved, and switching devices starts a new conversation`() = runTest {
        val store = MemoryDeviceTargetStore()
        val host = Host()
        val bridge = RemoteChatBridge(this, host, store) { _, _, _, _ -> Task() }
        bridge.send("@Desk hello", listOf(device), false, false)
        runCurrent()
        val kept = store.get("session")
        assertEquals("desk", kept?.id)
        val first = kept!!.conversation
        bridge.connect(device.copy(name = "Desk 2"))
        assertEquals("a rename keeps the conversation", first, bridge.target.value?.conversation)
        bridge.connect(device.copy(id = "laptop", name = "Laptop"))
        assertNotEquals(first, bridge.target.value?.conversation)
    }
}
