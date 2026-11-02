package com.warden.blocker.allow

import com.warden.blocker.block.Loosening
import com.warden.blocker.block.StrictGuard

/**
 * Adapter to Block protection for [AllowLoosening]s: switching "Allow only" back to the block
 * list, adding to an allow list, switching a category off or un-marking an app.
 *
 * Today only the existing strict lock applies (a strict focus session refuses every loosening,
 * with a short message). The 0.2.4 Block protection (PIN / delay / locked) isn't on this branch
 * yet — wire it in [isLooseningAllowed] when merging.
 */
class ProtectionHook(private val strictGuard: StrictGuard) {

    /** False (and the user has been told why) when [changes] must not happen right now. */
    suspend fun isLooseningAllowed(changes: Set<AllowLoosening>): Boolean {
        if (changes.isEmpty()) return true
        if (strictGuard.refuse(Loosening.SETTING_OFF)) return false
        return true // TODO wire: 0.2.4 Block protection (PIN / delay / locked) for `changes`
    }
}
