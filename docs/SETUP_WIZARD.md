# Setup wizard

Code: `app/src/main/java/com/warden/blocker/ui/wizard/`. Route `setup` in `MainActivity` (the
old `SetupScreen` is gone).

| File | What it does |
|---|---|
| `WizardModel.kt` | Pages (`WizardStep`), state, pure logic: order, "Step 3 of 7", open/done, auto-open, Home card, auto-advance |
| `WizardStore.kt` / `WizardController.kt` | Saved state (own DataStore file `warden_setup`), moves |
| `WizardChecks.kt` | What Android says is done (accessibility, VPN consent, usage access, battery, notifications) |
| `SetupWizardScreen.kt` | The pages |
| `SetupProgressCard.kt` | Home card "Setup 5/7 – continue", auto-open after a fresh install |
| `WizardSlots.kt` | Places for protection features of later versions |

## Pages

Welcome → Notifications (Android 13+) → App blocking (accessibility; on Android 13+ first
"try once" and "Allow restricted settings") → Website blocking (VPN consent; help for
Tailscale & co.; Always-on VPN recommended) → Usage access → Battery (background exemption +
Samsung "Never sleeping apps") → Protection → First blocks (app picker, StevenBlack list) →
Done (summary ✓/○, "Turn on blocking now", "Take a 1-minute tour", "Finish").

- Each page checks Android itself; coming back from Android's settings with it done shows a
  green tick and moves on (`WizardStep.autoAdvance`).
- Everything can be put off ("Later" / "Skip for now"). Put-off pages show as "Later" in the
  summary and no longer count for the Home card.
- Opens by itself only on the first start after a fresh install (`firstInstallTime ==
  lastUpdateTime`). People updating from an older version just get the Home card while
  something is open. Settings › "Setup wizard" opens it any time (on the summary once
  finished).
- "Finish" without the tour marks "Getting started" as skipped, so the small page tips appear.

## For 0.2.4 / 0.3.0 (protection)

The Protection page has two slots. Set them once at app start, e.g. in `WardenApp.onCreate`:

```kotlin
WizardSlots.blockProtection = { onChosen -> BlockProtectionPicker(onChosen = onChosen) }
WizardSlots.maximumProtection = { MaximumProtectionCard() }
```

- `blockProtection` replaces the built-in Off / PIN / PIN + strict mode choice. Call
  `onChosen()` once the user picked a level (also "Off"): that marks the page done.
- `maximumProtection` is shown under "Uninstall protection"; hidden while null.
- A new permission page = one entry in `WizardStep` (+ its check in `WizardChecks.systemDone`
  and its page in `SetupWizardScreen`). Unit tests: `WizardLogicTest`.
