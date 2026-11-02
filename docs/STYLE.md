# Look & motion

Every page follows Usage and Settings, the two pages that looked best: calm filled cards in
the theme's own colours, a clear bold page title, small coloured group headings, one muted
line under each title, and nothing that shouts. Building blocks live in
`app/src/main/java/com/warden/blocker/ui/CommonComponents.kt`, motion helpers in `ui/Motion.kt`.

## Rules (short)

1. **Page** – `PageTitle` (headlineMedium, bold) on tabs, `SubScreenTopBar` (back arrow) on
   sub-screens. Side margin `pageGutter()`: 12 dp below 360 dp width (fold cover display),
   else 20 dp. Cards 12 dp apart (`CardSpacing`), 16 dp inside (`CardPadding`).
2. **Cards** – always the filled `Card` (no elevated or outlined cards): `SectionCard` (title +
   content), `SwitchCard` (title, muted line, switch), `ActionCard` / `ActionGroup` +
   `ActionRow` (icon, title, summary, chevron) for anything that opens another page,
   `WarningCard` (error container) for real problems only.
3. **Text** – card titles `titleMedium`; the second line `SupportingText` (bodySmall,
   `onSurfaceVariant`); intros `IntroText`; groups of cards get a `SectionHeader`
   (titleSmall, primary). Hints with an info icon: `InfoNote`. Empty lists: `EmptyState`.
4. **Icons & colour** – outlined icons tinted `primary`, 20–24 dp. Only the original theme
   colours (indigo / coral, or the phone's dynamic colours); red only for errors. One filled
   button per card at most; the rest outlined or text buttons. Status ticks: `AnimatedCheck`
   / `StatusIcon` instead of ✓/✗ characters.
5. **Narrow screens** – rows of buttons or chips use `FlowRow`; nothing may be cut off at
   320 dp. Pickers (clock, calendar) stay as they are.

## Motion

Short, calm, never playful (`Motion.SHORT` 150 ms, `MEDIUM` 260 ms, `LONG` 700 ms).

| Where | What |
|---|---|
| Navigation | Tabs fade into each other; sub-screens slide in from the end and back out (`navEnter` / `navExit`). The bottom bar slides away for the setup wizard. |
| Lists | Entries appear, move and disappear with `Modifier.animateItem()` (Blocklist, Schedules, hosts lists, allowed websites, Usage app list). |
| Cards | Parts that appear fold open (`AnimatedVisibility`, `animateContentSize`): protection modes, categories, pause options, notification access, guard, updates. |
| Home | Shield badge changes colour and icon; a few soft pulses when protection turns on (then it stops). Headline cross-fades; streak counts up. |
| Usage | Tiles count up (`rememberCountUp`), charts and share bars grow in. |
| Wizard | Pages slide like a book (Next / Back), progress glides, ticks are drawn (`AnimatedCheck`). |
| PIN | Dots fill with a small bounce; a wrong PIN shakes the row. |
| Block / pause screen | Fades in; a soft circle breathes (8 s per breath) behind the timer or shield. |

With Android's "Remove animations" (`ANIMATOR_DURATION_SCALE` = 0) `LocalMotion` is false and
everything just switches: no slides, no pulses, no breathing. No animation loops forever except
the breathing circle, which only runs while the block screen is in front.

Tours: targets keep their `tourTarget` ids when cards are restyled; the guide follows a target
while it slides in (see docs/TOURS.md).
