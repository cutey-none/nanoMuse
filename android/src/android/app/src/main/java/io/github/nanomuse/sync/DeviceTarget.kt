package io.github.nanomuse.sync

import android.content.Context
import org.json.JSONObject
import java.util.UUID

/**
 * A chat connected to one device of the account (#907): after "@Mac …" the chat stays with
 * the Mac, and later messages go there without the mention until the chip above the composer
 * switches back to this phone. Kept per chat, on this phone, by device id; not synced.
 * [conversation] is what every task of the chat names as its `conversation`, so the device
 * keeps one conversation per chat (docs/hub.md, Tasks). iOS: NanoMuseDeviceTarget.swift.
 */
data class DeviceTarget(val id: String, val name: String, val conversation: String) {
    /** The same device under a newer name keeps its conversation; another device starts a new one. */
    fun to(deviceId: String, deviceName: String): DeviceTarget =
        if (deviceId == id) copy(name = deviceName) else fresh(deviceId, deviceName)

    companion object {
        fun fresh(deviceId: String, deviceName: String) =
            DeviceTarget(deviceId, deviceName, "chat-${UUID.randomUUID()}")
    }
}

/** Where connections live between launches, by session id. */
interface DeviceTargetStore {
    fun get(sessionId: String): DeviceTarget?
    fun put(sessionId: String, target: DeviceTarget?)
}

class PrefsDeviceTargetStore(context: Context) : DeviceTargetStore {
    private val prefs = context.applicationContext.getSharedPreferences("nanomuse_device_targets", Context.MODE_PRIVATE)

    override fun get(sessionId: String): DeviceTarget? {
        val raw = prefs.getString(sessionId, null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            DeviceTarget(o.getString("id"), o.optString("name"), o.getString("conversation"))
        }.getOrNull()
    }

    override fun put(sessionId: String, target: DeviceTarget?) {
        val edit = prefs.edit()
        if (target == null) edit.remove(sessionId)
        else edit.putString(sessionId, JSONObject().put("id", target.id).put("name", target.name).put("conversation", target.conversation).toString())
        edit.apply()
    }
}
