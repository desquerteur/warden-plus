package com.warden.blocker.block

import com.warden.blocker.allow.AllowEdit
import com.warden.blocker.data.BlockType
import com.warden.blocker.data.BlockedItem
import com.warden.blocker.data.InterceptMode
import com.warden.blocker.data.Schedule
import org.json.JSONArray
import org.json.JSONObject

/**
 * One weakening, complete enough to run later: in "Delay" mode it waits in a queue (stored as
 * JSON), so it carries everything needed to apply it — not a reference to UI state.
 */
sealed class ProtectedChange(val kind: Loosening) {
    data object TurnOffBlocking : ProtectedChange(Loosening.TURN_OFF_BLOCKING)
    data object EndFocus : ProtectedChange(Loosening.END_FOCUS)
    data class RemoveItem(val itemId: Long, val label: String) : ProtectedChange(Loosening.REMOVE_ITEM)
    /** Saves [item] as given; [disables] when that switches it off (rather than weakening it). */
    data class UpdateItem(val item: BlockedItem, val disables: Boolean) :
        ProtectedChange(if (disables) Loosening.DISABLE_ITEM else Loosening.WEAKEN_ITEM)
    data class RemoveSchedule(val scheduleId: Long, val name: String) : ProtectedChange(Loosening.REMOVE_SCHEDULE)
    data class UpdateSchedule(val schedule: Schedule, val disables: Boolean) :
        ProtectedChange(if (disables) Loosening.DISABLE_SCHEDULE else Loosening.CHANGE_SCHEDULE)
    data class DisableHostsList(val listId: String, val name: String) : ProtectedChange(Loosening.DISABLE_HOSTS_LIST)
    data class RemoveHostsList(val listId: String, val name: String) : ProtectedChange(Loosening.REMOVE_HOSTS_LIST)
    data class DisableFeedBlock(val featureKey: String, val label: String) : ProtectedChange(Loosening.DISABLE_FEED_BLOCK)
    data class SettingOff(val setting: Setting) : ProtectedChange(Loosening.SETTING_OFF)
    data class SetProtection(val settings: ProtectionSettings) : ProtectedChange(Loosening.WEAKEN_PROTECTION)
    data object DisableUninstallProtection : ProtectedChange(Loosening.DISABLE_UNINSTALL_PROTECTION)
    data class ChangePin(val hash: String, val salt: String) : ProtectedChange(Loosening.CHANGE_PIN)
    data object RemovePin : ProtectedChange(Loosening.REMOVE_PIN)
    /** A loosening of "Allow only" or the category blocks (see [com.warden.blocker.allow.AllowPolicy.loosenings]). */
    data class LoosenAllow(val edit: AllowEdit) : ProtectedChange(Loosening.LOOSEN_ALLOW)

    enum class Setting(val label: String) { ALWAYS_ON("Always on"), BLOCK_DOH("Block DNS-over-HTTPS"), BLOCK_NOTIFICATIONS("Block notifications") }

    /** What happens, for "… at 15:40" in the pending list and in messages. */
    fun describe(): String = when (this) {
        TurnOffBlocking -> "Blocking will be turned off"
        EndFocus -> "The focus session will end"
        is RemoveItem -> "$label will be removed from the blocklist"
        is UpdateItem -> if (disables) "${item.label} will be unblocked" else "${item.label} will be blocked less strictly"
        is RemoveSchedule -> "Schedule “$name” will be deleted"
        is UpdateSchedule -> if (disables) "Schedule “${schedule.name}” will be switched off" else "Schedule “${schedule.name}” will change"
        is DisableHostsList -> "List “$name” will be switched off"
        is RemoveHostsList -> "List “$name” will be removed"
        is DisableFeedBlock -> "$label will be allowed again"
        is SettingOff -> "“${setting.label}” will be switched off"
        is SetProtection -> "Block protection will be weakened"
        DisableUninstallProtection -> "Uninstall protection will be turned off"
        is ChangePin -> "Your PIN will change"
        RemovePin -> "Your PIN will be removed"
        is LoosenAllow -> edit.describe()
    }

    /** Stable names (not class names, which a minified build would rename). */
    private fun typeName(): String = when (this) {
        TurnOffBlocking -> "TurnOffBlocking"
        EndFocus -> "EndFocus"
        is RemoveItem -> "RemoveItem"
        is UpdateItem -> "UpdateItem"
        is RemoveSchedule -> "RemoveSchedule"
        is UpdateSchedule -> "UpdateSchedule"
        is DisableHostsList -> "DisableHostsList"
        is RemoveHostsList -> "RemoveHostsList"
        is DisableFeedBlock -> "DisableFeedBlock"
        is SettingOff -> "SettingOff"
        is SetProtection -> "SetProtection"
        DisableUninstallProtection -> "DisableUninstallProtection"
        is ChangePin -> "ChangePin"
        RemovePin -> "RemovePin"
        is LoosenAllow -> "LoosenAllow"
    }

    fun toJson(): JSONObject = JSONObject().put("type", typeName()).also { o ->
        when (this) {
            TurnOffBlocking, EndFocus, DisableUninstallProtection, RemovePin -> Unit
            is RemoveItem -> o.put("id", itemId).put("label", label)
            is UpdateItem -> o.put("item", itemJson(item)).put("disables", disables)
            is RemoveSchedule -> o.put("id", scheduleId).put("name", name)
            is UpdateSchedule -> o.put("schedule", scheduleJson(schedule)).put("disables", disables)
            is DisableHostsList -> o.put("id", listId).put("name", name)
            is RemoveHostsList -> o.put("id", listId).put("name", name)
            is DisableFeedBlock -> o.put("key", featureKey).put("label", label)
            is SettingOff -> o.put("setting", setting.name)
            is SetProtection -> o.put("mode", settings.mode.name)
                .put("delay", settings.delayMinutes).put("until", settings.lockedUntil)
            is ChangePin -> o.put("hash", hash).put("salt", salt)
            is LoosenAllow -> o.put("edit", edit.toJson())
        }
    }

    companion object {
        /** Null for anything unreadable (e.g. written by a newer version), which is then dropped. */
        fun fromJson(o: JSONObject): ProtectedChange? = runCatching {
            when (o.getString("type")) {
                "TurnOffBlocking" -> TurnOffBlocking
                "EndFocus" -> EndFocus
                "DisableUninstallProtection" -> DisableUninstallProtection
                "RemovePin" -> RemovePin
                "RemoveItem" -> RemoveItem(o.getLong("id"), o.getString("label"))
                "UpdateItem" -> UpdateItem(itemFrom(o.getJSONObject("item")), o.getBoolean("disables"))
                "RemoveSchedule" -> RemoveSchedule(o.getLong("id"), o.getString("name"))
                "UpdateSchedule" -> UpdateSchedule(scheduleFrom(o.getJSONObject("schedule")), o.getBoolean("disables"))
                "DisableHostsList" -> DisableHostsList(o.getString("id"), o.getString("name"))
                "RemoveHostsList" -> RemoveHostsList(o.getString("id"), o.getString("name"))
                "DisableFeedBlock" -> DisableFeedBlock(o.getString("key"), o.getString("label"))
                "SettingOff" -> SettingOff(Setting.valueOf(o.getString("setting")))
                "SetProtection" -> SetProtection(
                    ProtectionSettings(ProtectionMode.valueOf(o.getString("mode")), o.getInt("delay"), o.getLong("until")),
                )
                "ChangePin" -> ChangePin(o.getString("hash"), o.getString("salt"))
                "LoosenAllow" -> LoosenAllow(AllowEdit.fromJson(o.getJSONObject("edit")))
                else -> null
            }
        }.getOrNull()

        private fun itemJson(i: BlockedItem) = JSONObject()
            .put("id", i.id).put("type", i.type.name).put("value", i.value).put("label", i.label)
            .put("enabled", i.enabled).put("createdAt", i.createdAt).put("mode", i.interceptMode.name)
            .put("pause", i.pauseSeconds).put("prompt", i.customPrompt ?: JSONObject.NULL)
            .put("opens", i.openLimitPerDay).put("minutes", i.dailyLimitMinutes).put("cooldown", i.cooldownMinutes)

        private fun itemFrom(o: JSONObject) = BlockedItem(
            id = o.getLong("id"), type = BlockType.valueOf(o.getString("type")), value = o.getString("value"),
            label = o.getString("label"), enabled = o.getBoolean("enabled"), createdAt = o.getLong("createdAt"),
            interceptMode = InterceptMode.valueOf(o.getString("mode")), pauseSeconds = o.getInt("pause"),
            customPrompt = if (o.isNull("prompt")) null else o.getString("prompt"),
            openLimitPerDay = o.getInt("opens"), dailyLimitMinutes = o.getInt("minutes"), cooldownMinutes = o.getInt("cooldown"),
        )

        private fun scheduleJson(s: Schedule) = JSONObject()
            .put("id", s.id).put("name", s.name).put("enabled", s.enabled)
            .put("days", s.daysMask).put("start", s.startMinute).put("end", s.endMinute)

        private fun scheduleFrom(o: JSONObject) = Schedule(
            id = o.getLong("id"), name = o.getString("name"), enabled = o.getBoolean("enabled"),
            daysMask = o.getInt("days"), startMinute = o.getInt("start"), endMinute = o.getInt("end"),
        )
    }
}

/** A weakening waiting in "Delay" mode; [dueAt] is when it takes effect. */
data class PendingChange(val id: Long, val dueAt: Long, val change: ProtectedChange) {
    companion object {
        fun encode(list: List<PendingChange>): String = JSONArray().apply {
            list.forEach { put(JSONObject().put("id", it.id).put("due", it.dueAt).put("change", it.change.toJson())) }
        }.toString()

        fun decode(json: String?): List<PendingChange> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(json)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.getJSONObject(i)
                    val change = ProtectedChange.fromJson(o.getJSONObject("change")) ?: return@mapNotNull null
                    PendingChange(o.getLong("id"), o.getLong("due"), change)
                }
            }.getOrDefault(emptyList())
        }
    }
}
