package com.warden.blocker.owner

import android.content.Context
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import com.warden.blocker.util.CriticalApps

/**
 * Apps device-owner mode must never suspend: everything [CriticalApps] protects (phone, settings,
 * launcher, Warden+) plus the keyboards and the default phone app. Without a keyboard the PIN
 * can't be typed; without the phone app no emergency call.
 */
object ProtectedPackages {

    fun collect(context: Context, candidates: Collection<String>): Set<String> = buildSet {
        add(context.packageName)
        defaultKeyboard(context)?.let(::add)
        runCatching {
            context.getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.forEach { add(it.packageName) }
        }
        runCatching { context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()?.let(::add)
        candidates.filterTo(this) { CriticalApps.isCritical(context, it) }
    }

    private fun defaultKeyboard(context: Context): String? = runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
