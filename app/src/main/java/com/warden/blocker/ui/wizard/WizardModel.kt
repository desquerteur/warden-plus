package com.warden.blocker.ui.wizard

import com.warden.blocker.ui.tour.SettingsGuide

/**
 * The pages of the setup wizard, in order. [task] pages count for "Step 3 of 7" and the Home
 * card; [optional] ones offer "Later"; [autoAdvance] pages are checked against Android itself
 * and move on by themselves once the user comes back from Android's settings with it done.
 */
enum class WizardStep(
    val title: String,
    val task: Boolean = true,
    val optional: Boolean = true,
    val minSdk: Int = 0,
    val autoAdvance: Boolean = false,
    /** Android-settings walk-throughs shown on this page (see [SettingsGuide]). */
    val guides: List<SettingsGuide> = emptyList(),
) {
    WELCOME("Welcome", task = false),
    NOTIFICATIONS("Notifications", minSdk = 33, autoAdvance = true),
    APP_BLOCKING(
        "App blocking", optional = false, autoAdvance = true,
        guides = listOf(SettingsGuide.RESTRICTED, SettingsGuide.ACCESSIBILITY),
    ),
    WEBSITE_BLOCKING(
        "Website blocking", optional = false, autoAdvance = true,
        guides = listOf(SettingsGuide.VPN_CONSENT, SettingsGuide.OTHER_VPN),
    ),
    USAGE("Usage access", autoAdvance = true),
    BATTERY("Battery", autoAdvance = true, guides = listOf(SettingsGuide.BATTERY)),
    PROTECTION("Protection"),
    FIRST_BLOCKS("First blocks"),
    DONE("Done", task = false),
}

enum class WizardStatus {
    /** Never opened (fresh install, or an older version before the wizard existed). */
    NEW,
    IN_PROGRESS,

    /** Left with "Skip setup" or the back arrow: only the Home card reminds. */
    DISMISSED,
    FINISHED,
}

data class WizardState(
    val status: WizardStatus = WizardStatus.NEW,
    val step: WizardStep = WizardStep.WELCOME,
    val skipped: Set<WizardStep> = emptySet(),
    /** A protection level was picked on purpose (also "Off"). */
    val protectionChosen: Boolean = false,
)

object WizardLogic {

    fun steps(sdk: Int): List<WizardStep> = WizardStep.entries.filter { sdk >= it.minSdk }

    fun tasks(sdk: Int): List<WizardStep> = steps(sdk).filter { it.task }

    fun next(step: WizardStep, sdk: Int): WizardStep {
        val all = steps(sdk)
        return all.getOrNull(all.indexOf(step) + 1) ?: WizardStep.DONE
    }

    /** null on the first page. */
    fun back(step: WizardStep, sdk: Int): WizardStep? {
        val all = steps(sdk)
        return all.getOrNull(all.indexOf(step) - 1)
    }

    /** "Step 3 of 7" for task pages; null for Welcome and Done. */
    fun position(step: WizardStep, sdk: Int): Pair<Int, Int>? {
        val tasks = tasks(sdk)
        val i = tasks.indexOf(step)
        return if (i < 0) null else (i + 1) to tasks.size
    }

    /** Tasks neither done nor skipped. */
    fun open(sdk: Int, done: Set<WizardStep>, skipped: Set<WizardStep>): List<WizardStep> =
        tasks(sdk).filter { it !in done && it !in skipped }

    /** "Setup 5/7": done tasks of all tasks. */
    fun progress(sdk: Int, done: Set<WizardStep>): Pair<Int, Int> {
        val tasks = tasks(sdk)
        return tasks.count { it in done } to tasks.size
    }

    /** The page on screen (a saved page that doesn't exist on this Android version falls back to Welcome). */
    fun currentStep(state: WizardState, sdk: Int): WizardStep =
        state.step.takeIf { it in steps(sdk) } ?: WizardStep.WELCOME

    /**
     * Opens by itself once, on the first start after a fresh install. After that (also when
     * it was interrupted) and for people updating from an older version, the Home card reminds.
     */
    fun shouldAutoOpen(status: WizardStatus, freshInstall: Boolean): Boolean =
        freshInstall && status == WizardStatus.NEW

    /**
     * "Setup 5/7 – continue" on Home while a task is open. Not while app blocking is missing:
     * Home has its own, louder card for that one.
     */
    fun showHomeCard(sdk: Int, done: Set<WizardStep>, skipped: Set<WizardStep>): Boolean =
        WizardStep.APP_BLOCKING in done && open(sdk, done, skipped).isNotEmpty()

    /** The user came back from Android's settings and the page is now done: move on. */
    fun shouldAutoAdvance(step: WizardStep, doneOnEnter: Boolean, doneNow: Boolean): Boolean =
        step.autoAdvance && !doneOnEnter && doneNow
}
