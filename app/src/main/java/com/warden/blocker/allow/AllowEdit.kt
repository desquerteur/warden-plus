package com.warden.blocker.allow

import org.json.JSONObject

/**
 * One change to [AllowConfig], as a small step rather than a whole new config: in Block
 * protection's "Delay" mode it waits in the queue (stored as JSON) and is applied on top of
 * whatever the config is by then, so later changes made in between aren't overwritten.
 */
sealed class AllowEdit {
    data class SetAppMode(val mode: ListMode) : AllowEdit()
    data class SetWebMode(val mode: ListMode) : AllowEdit()
    data class SetScope(val scope: AllowScope) : AllowEdit()
    data class SetAllowCamera(val on: Boolean) : AllowEdit()
    data class SetAllowSms(val on: Boolean) : AllowEdit()
    data class SetAppAllowed(val packageName: String, val label: String, val allowed: Boolean) : AllowEdit()
    data class SetDomainAllowed(val domain: String, val allowed: Boolean) : AllowEdit()
    data class SetCategoryBlocked(val category: AppCategory, val blocked: Boolean) : AllowEdit()
    /** [value] is a category name, [AppCategories.NONE], or null to undo the correction. */
    data class SetOverride(val packageName: String, val label: String, val value: String?) : AllowEdit()

    fun applyTo(c: AllowConfig): AllowConfig = when (this) {
        is SetAppMode -> c.copy(appMode = mode)
        is SetWebMode -> c.copy(webMode = mode)
        is SetScope -> c.copy(scope = scope)
        is SetAllowCamera -> c.copy(allowCamera = on)
        is SetAllowSms -> c.copy(allowSms = on)
        is SetAppAllowed -> c.copy(allowedApps = if (allowed) c.allowedApps + packageName else c.allowedApps - packageName)
        is SetDomainAllowed -> c.copy(allowedDomains = if (allowed) c.allowedDomains + domain else c.allowedDomains - domain)
        is SetCategoryBlocked ->
            c.copy(blockedCategories = if (blocked) c.blockedCategories + category else c.blockedCategories - category)
        is SetOverride -> c.copy(
            categoryOverrides = if (value == null) c.categoryOverrides - packageName else c.categoryOverrides + (packageName to value),
        )
    }

    /** What happens, for the pending list on Home and the PIN dialog. Written for the loosening case. */
    fun describe(): String = when (this) {
        is SetAppMode -> if (mode == ListMode.ALLOW_ONLY) "Apps will switch to “Allow only”" else "Apps will switch back to the block list"
        is SetWebMode -> if (mode == ListMode.ALLOW_ONLY) "Websites will switch to “Allow only”" else "Websites will switch back to the block list"
        is SetScope -> "“Allow only” will apply ${scope.label.replaceFirstChar { it.lowercase() }}"
        is SetAllowCamera -> if (on) "The camera will stay open in “Allow only”" else "The camera will be blocked in “Allow only”"
        is SetAllowSms -> if (on) "Messages will stay open in “Allow only”" else "Messages will be blocked in “Allow only”"
        is SetAppAllowed -> if (allowed) "$label will be allowed" else "$label will no longer be allowed"
        is SetDomainAllowed -> if (allowed) "$domain will be allowed" else "$domain will no longer be allowed"
        is SetCategoryBlocked -> if (blocked) "${category.label} will be blocked" else "${category.label} will no longer be blocked"
        is SetOverride -> when (value) {
            null -> "$label will use its own category again"
            AppCategories.NONE -> "$label will not count as any category"
            else -> "$label will count as ${AppCategories.parse(value)?.singular ?: value}"
        }
    }

    /** Stable names (not class names, which a minified build would rename). */
    private fun typeName(): String = when (this) {
        is SetAppMode -> "AppMode"
        is SetWebMode -> "WebMode"
        is SetScope -> "Scope"
        is SetAllowCamera -> "Camera"
        is SetAllowSms -> "Sms"
        is SetAppAllowed -> "App"
        is SetDomainAllowed -> "Domain"
        is SetCategoryBlocked -> "Category"
        is SetOverride -> "Override"
    }

    fun toJson(): JSONObject = JSONObject().put("op", typeName()).also { o ->
        when (this) {
            is SetAppMode -> o.put("mode", mode.name)
            is SetWebMode -> o.put("mode", mode.name)
            is SetScope -> o.put("scope", scope.name)
            is SetAllowCamera -> o.put("on", on)
            is SetAllowSms -> o.put("on", on)
            is SetAppAllowed -> o.put("pkg", packageName).put("label", label).put("on", allowed)
            is SetDomainAllowed -> o.put("domain", domain).put("on", allowed)
            is SetCategoryBlocked -> o.put("category", category.name).put("on", blocked)
            is SetOverride -> o.put("pkg", packageName).put("label", label).put("value", value ?: JSONObject.NULL)
        }
    }

    companion object {
        /** Throws for anything unreadable; [com.warden.blocker.block.ProtectedChange.fromJson] turns that into null. */
        fun fromJson(o: JSONObject): AllowEdit = when (val op = o.getString("op")) {
            "AppMode" -> SetAppMode(ListMode.valueOf(o.getString("mode")))
            "WebMode" -> SetWebMode(ListMode.valueOf(o.getString("mode")))
            "Scope" -> SetScope(AllowScope.valueOf(o.getString("scope")))
            "Camera" -> SetAllowCamera(o.getBoolean("on"))
            "Sms" -> SetAllowSms(o.getBoolean("on"))
            "App" -> SetAppAllowed(o.getString("pkg"), o.getString("label"), o.getBoolean("on"))
            "Domain" -> SetDomainAllowed(o.getString("domain"), o.getBoolean("on"))
            "Category" -> SetCategoryBlocked(AppCategory.valueOf(o.getString("category")), o.getBoolean("on"))
            "Override" -> SetOverride(o.getString("pkg"), o.getString("label"), if (o.isNull("value")) null else o.getString("value"))
            else -> error("unknown allow edit $op")
        }
    }
}
