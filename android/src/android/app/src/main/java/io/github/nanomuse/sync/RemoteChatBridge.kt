package io.github.nanomuse.sync

import com.openminis.app.ui.chat.AssistantBlock
import io.github.nanomuse.hub.Device
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * The small host adapter keeps task lifecycle out of the upstream chat view model. It also holds
 * the device the chat is connected to (#907): "@Desk …" connects the chat, later messages go to
 * Desk without the mention, and the chip above the composer switches back.
 */
class RemoteChatBridge(
    private val scope: CoroutineScope,
    private val host: Host,
    private val store: DeviceTargetStore = MemoryDeviceTargetStore(),
    /** The device, the text, the call id and the `conversation` the task names (null: the device picks). */
    private val newTask: (Device, String, String, String?) -> Task,
) {
    enum class Issue { AMBIGUOUS, OFFLINE, OFFLINE_CONNECTED, GONE, ATTACHMENTS, BUSY, FAILED }
    interface Task {
        val text: String
        val blocks: List<AssistantBlock>
        val failed: Boolean
        val stopped: Boolean get() = false
        suspend fun run(sessionId: String, update: () -> Unit)
        fun stop()
    }
    interface Host {
        val busy: Boolean
        /** The chat's saved session id; null while it is a draft, whose connection is kept in memory only. */
        val savedSession: String? get() = null
        fun setBusy(value: Boolean)
        fun error(issue: Issue, device: String = "")
        fun clearDraft()
        fun restoreDraft(text: String)
        suspend fun session(): String
        suspend fun user(sessionId: String, text: String)
        fun started(sessionId: String) {}
        fun ended(sessionId: String, completed: Boolean) {}
        fun show(id: String, task: Task)
        suspend fun finish(sessionId: String, id: String, task: Task)
        fun afterTurn()
    }
    private class Active(val id: String, val task: Task) { var job: Job? = null }
    private var active: Active? = null

    private val _target = MutableStateFlow(host.savedSession?.let(store::get))
    /** The device this chat sends to; null for this phone. */
    val target: StateFlow<DeviceTarget?> = _target.asStateFlow()

    /** Connect the chat to [device] (the chip's menu, or a mention). */
    fun connect(device: Device) = setTarget(_target.value?.to(device.id, device.name) ?: DeviceTarget.fresh(device.id, device.name))

    /** Back to this phone (the chip's ×). */
    fun disconnect() = setTarget(null)

    private fun setTarget(next: DeviceTarget?) {
        if (_target.value == next) return
        _target.value = next
        host.savedSession?.let { store.put(it, next) }
    }

    private fun refuse(text: String, issue: Issue, device: String = ""): Boolean {
        // The composer clears its field before dispatch; a refusal must put it back.
        host.restoreDraft(text)
        host.error(issue, device)
        return true
    }

    /** True means addressed here, including a refusal. It must not fall into the local model. */
    fun send(text: String, devices: List<Device>, attachments: Boolean, compacting: Boolean): Boolean {
        val connected = _target.value
        val route = when (val resolved = RemoteDeviceRoute.resolve(text, devices)) {
            is RemoteDeviceRoute.Selected -> {
                // "@Desk …" connects the chat; a bare "@Desk" only connects.
                connect(resolved.device)
                if (resolved.text.isBlank()) { host.clearDraft(); return true }
                resolved
            }
            RemoteDeviceRoute.Ambiguous -> return refuse(text, Issue.AMBIGUOUS)
            RemoteDeviceRoute.None -> {
                // A connected chat sends there, never to the model here: a device that cannot
                // take the message says so instead.
                connected ?: return false
                val device = devices.firstOrNull { it.id == connected.id }
                    ?: if (devices.isEmpty()) return refuse(text, Issue.OFFLINE_CONNECTED, connected.name)
                    else { disconnect(); return refuse(text, Issue.GONE, connected.name) }
                if (!device.online) return refuse(text, Issue.OFFLINE_CONNECTED, device.name)
                RemoteDeviceRoute.Selected(device, text)
            }
        }
        if (host.busy || compacting) return refuse(text, Issue.BUSY)
        if (!route.device.online) return refuse(text, Issue.OFFLINE_CONNECTED, route.device.name)
        if (attachments) return refuse(text, Issue.ATTACHMENTS)
        val id = UUID.randomUUID().toString()
        val run = Active(id, newTask(route.device, route.text, id, _target.value?.conversation))
        active = run
        host.setBusy(true)
        host.clearDraft()
        run.job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var sessionId: String? = null
            var userShown = false
            var taskReturned = false
            try {
                sessionId = host.session()
                // A draft became a saved chat: its connection is kept from now on.
                store.put(sessionId, _target.value)
                host.user(sessionId, text)
                userShown = true
                host.started(sessionId)
                host.show(id, run.task)
                run.task.run(sessionId) { if (active === run) host.show(id, run.task) }
                taskReturned = true
            } catch (e: CancellationException) {
                run.task.stop()
                throw e
            } catch (_: Exception) {
                host.error(Issue.FAILED, route.device.name)
            } finally {
                withContext(NonCancellable) {
                    var saved = false
                    try {
                        if (userShown && sessionId != null) {
                            host.finish(sessionId, id, run.task)
                            saved = true
                        }
                        else host.restoreDraft(text)
                    } catch (_: Exception) {
                        host.error(Issue.FAILED, route.device.name)
                    } finally {
                        if (userShown && sessionId != null) {
                            host.ended(sessionId, saved && taskReturned && !run.task.failed && !run.task.stopped)
                        }
                        if (active === run) {
                            active = null
                            host.setBusy(false)
                            host.afterTurn()
                        }
                    }
                }
            }
        }
        return true
    }

    fun cancel(): Boolean {
        val run = active ?: return false
        run.task.stop()
        host.show(run.id, run.task)
        run.job?.cancel()
        return true
    }
}

/** Connections for a bridge with nowhere to keep them (tests, previews). */
class MemoryDeviceTargetStore : DeviceTargetStore {
    private val saved = mutableMapOf<String, DeviceTarget>()
    override fun get(sessionId: String): DeviceTarget? = saved[sessionId]
    override fun put(sessionId: String, target: DeviceTarget?) {
        if (target == null) saved.remove(sessionId) else saved[sessionId] = target
    }
}
