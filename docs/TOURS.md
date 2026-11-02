# Guides (spotlight tours)

The guides live in `app/src/main/java/com/warden/blocker/ui/tour/`. No library: a small engine
of its own.

| File | What it does |
|---|---|
| `Tours.kt` | Target ids (`TourIds`) and every tour, one line per step |
| `TourModel.kt` | Pure logic: next/back/skip, progress, the way between screens (`TourPath`), card placement |
| `TourTarget.kt` | `Modifier.tourTarget(id)` and the registry of where targets are |
| `TourHost.kt` | Overlay over the whole app: spotlight, finger animation, guide card, auto-start, page tips |
| `SettingsGuides.kt` / `SettingsPathAnimation.kt` | Drawn mini settings screens for steps outside the app |
| `TourController.kt` / `TourStore.kt` | Running tour and saved state (own DataStore file `warden_tours`) |
| `TourButtons.kt` | "Restart guide" card (Settings › Help) |

## Add a step

1. Mark the element on its screen:
   ```kotlin
   Card(Modifier.fillMaxWidth().tourTarget(TourIds.SETTINGS_BLOCK_PROTECTION)) { … }
   ```
   (Add a constant to `TourIds` if it is new. For a composable without a `modifier`
   parameter, wrap it: `Box(Modifier.tourTarget(…)) { Thing() }`.)
2. Add one line to the tour in `Tours.kt`:
   ```kotlin
   step(T.SETTINGS_BLOCK_PROTECTION, SETTINGS, "Block protection", "One or two short sentences."),
   ```
   Optional: `guide = SettingsGuide.BATTERY` adds the animated Android settings walk-through
   and its "open" button; `ifMissing = MissingTarget.CENTER` shows the text in the middle
   when the element isn't on screen.

That's all. The guide walks there by itself (finger taps the tab/button, the app really
navigates) using `TourPath`. A new sub-screen needs one entry in `TourPath.subScreens`
(parent tab + the button that opens it).

Steps whose target isn't on screen are skipped (e.g. the Protection card while Settings is locked).
The "Protection" tour walks Settings › Protection (block protection modes, PIN, checklist,
uninstall protection, Settings guard, Maximum protection), then strict focus and "Block until".

## Rules

- Max. 10 steps per tour; title + 1–2 short sentences that say *why* (a unit test checks).
- Texts in English, simple words. For Android settings name Samsung's labels first
  ("Installed apps") and the stock name in the note.
- Mini settings screens are drawn, never screenshots.
- With system animations off (`ANIMATOR_DURATION_SCALE` = 0) everything jumps; the mini
  screens become numbered sentences.
- Screens fade / slide when they change (docs/STYLE.md). The spotlight keeps following its
  target while it moves in, so a restyled card must keep its `tourTarget` id.

## Behaviour

- "Getting started" is offered at the end of the setup wizard (docs/SETUP_WIZARD.md); if the
  app was closed half-way through it, it continues on the next start.
- The Android-settings walk-throughs are also used by the setup wizard.
- People who skipped "Getting started" get a small "Tips for this page?" chip on the first
  visit of Blocklist, Schedules, Usage and Settings.
- Settings › Help › "Restart guide" starts Getting started again at once; "Other guides" picks
  another guide or shows the page tips again.
- System back = one step back (on the first step: close).
