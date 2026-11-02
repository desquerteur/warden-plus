package com.warden.blocker.ui.wizard

import androidx.compose.runtime.Composable

/**
 * Places in the wizard's "Protection" page for features of later versions. Set them once at
 * app start (e.g. in `WardenApp.onCreate`); see docs/SETUP_WIZARD.md.
 */
object WizardSlots {
    /**
     * 0.2.4 "Block protection" (Off / PIN / Delay / Locked until). Replaces the built-in PIN /
     * strict-mode choice; call `onChosen` once the user picked a level.
     */
    var blockProtection: (@Composable (onChosen: () -> Unit) -> Unit)? = null

    /** 0.3.0 "Maximum protection" (settings guard, device owner). Hidden while null. */
    var maximumProtection: (@Composable () -> Unit)? = null
}
