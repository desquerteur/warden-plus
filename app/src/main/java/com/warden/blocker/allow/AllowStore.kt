package com.warden.blocker.allow

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.allowDataStore by preferencesDataStore(name = "warden_allow")

/**
 * Persists [AllowConfig] in its own DataStore file — not in Room (no schema change) and not in
 * the main settings file, so it merges cleanly with other work on those.
 */
class AllowStore(private val context: Context) {

    private object Keys {
        val APP_MODE = stringPreferencesKey("app_mode")
        val WEB_MODE = stringPreferencesKey("web_mode")
        val SCOPE = stringPreferencesKey("scope")
        val ALLOWED_APPS = stringSetPreferencesKey("allowed_apps")
        val ALLOWED_DOMAINS = stringSetPreferencesKey("allowed_domains")
        val BLOCKED_CATEGORIES = stringSetPreferencesKey("blocked_categories")
        val CATEGORY_OVERRIDES = stringSetPreferencesKey("category_overrides")
        val ALLOW_CAMERA = booleanPreferencesKey("allow_camera")
        val ALLOW_SMS = booleanPreferencesKey("allow_sms")
        /** Launchable packages seen last time — to spot apps installed while Warden+ wasn't running. */
        val KNOWN_PACKAGES = stringSetPreferencesKey("known_packages")
    }

    val config: Flow<AllowConfig> = context.allowDataStore.data.map { it.toConfig() }

    suspend fun current(): AllowConfig = config.first()

    /** Applies [transform] atomically and returns the config before and after. */
    suspend fun update(transform: (AllowConfig) -> AllowConfig): Pair<AllowConfig, AllowConfig> {
        var result: Pair<AllowConfig, AllowConfig>? = null
        context.allowDataStore.edit { prefs ->
            val old = prefs.toConfig()
            val updated = transform(old)
            if (updated != old) prefs.write(updated)
            result = old to updated
        }
        return result!!
    }

    /** Null until the first look at the installed apps. */
    suspend fun knownPackages(): Set<String>? = context.allowDataStore.data.first()[Keys.KNOWN_PACKAGES]

    suspend fun setKnownPackages(packages: Set<String>) =
        context.allowDataStore.edit { it[Keys.KNOWN_PACKAGES] = packages }.let { }

    suspend fun addKnownPackage(packageName: String) =
        context.allowDataStore.edit { p ->
            // Before the first full look nothing is "known" yet; that look will include it.
            val known = p[Keys.KNOWN_PACKAGES] ?: return@edit
            p[Keys.KNOWN_PACKAGES] = known + packageName
        }.let { }

    private fun Preferences.toConfig() = AllowConfig(
        appMode = enumOr(this[Keys.APP_MODE], ListMode.BLOCK_LIST),
        webMode = enumOr(this[Keys.WEB_MODE], ListMode.BLOCK_LIST),
        scope = enumOr(this[Keys.SCOPE], AllowScope.WHENEVER_BLOCKING),
        allowedApps = this[Keys.ALLOWED_APPS] ?: emptySet(),
        allowedDomains = this[Keys.ALLOWED_DOMAINS] ?: emptySet(),
        blockedCategories = (this[Keys.BLOCKED_CATEGORIES] ?: emptySet()).mapNotNullTo(HashSet()) { AppCategories.parse(it) },
        categoryOverrides = AppCategories.decodeOverrides(this[Keys.CATEGORY_OVERRIDES] ?: emptySet()),
        allowCamera = this[Keys.ALLOW_CAMERA] ?: true,
        allowSms = this[Keys.ALLOW_SMS] ?: true,
    )

    private fun MutablePreferences.write(c: AllowConfig) {
        this[Keys.APP_MODE] = c.appMode.name
        this[Keys.WEB_MODE] = c.webMode.name
        this[Keys.SCOPE] = c.scope.name
        this[Keys.ALLOWED_APPS] = c.allowedApps
        this[Keys.ALLOWED_DOMAINS] = c.allowedDomains
        this[Keys.BLOCKED_CATEGORIES] = c.blockedCategories.mapTo(HashSet()) { it.name }
        this[Keys.CATEGORY_OVERRIDES] = AppCategories.encodeOverrides(c.categoryOverrides)
        this[Keys.ALLOW_CAMERA] = c.allowCamera
        this[Keys.ALLOW_SMS] = c.allowSms
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
