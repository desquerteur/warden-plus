# Warden+

> [!IMPORTANT]
> **This is NOT the original Warden project.**
> Warden+ is an unofficial, community-maintained fork of
> **[ChristopherLaughlin/warden](https://github.com/ChristopherLaughlin/warden)**.
> All credit for the original app — its architecture, blocking engine and most of the code —
> goes to the original author. This fork only adds a few improvements on top.
> It is not affiliated with or endorsed by the original project.
> Please report bugs in the added features **here**, not upstream.

**A free, open-source website & app blocker for Android** — no subscriptions, no ads, no
account, no data collection. Warden+ keeps everything the original does and adds the
features listed below.

---

## What Warden+ adds

| Change | Details |
|---|---|
| **Hosts-list import** | Import blocklists such as [StevenBlack/hosts](https://github.com/StevenBlack/hosts) from a file on the phone or from an https URL. One-tap presets for StevenBlack *Unified* plus the *fakenews / gambling / porn / social* extensions. Every domain in an active list (and its subdomains) is blocked by the local DNS filter. URL lists refresh weekly. ~78 000 domains use about 1 MB of RAM. |
| **New statistics dashboard** | Today / week / month view with screen-time bar charts, a top-apps ring chart, trends compared to the previous period, unlocks, number of blocked apps and websites, focus streak, and a per-app history. Works on narrow and wide screens. |
| **In-app updates** | *Settings → Updates* checks this repository's latest GitHub Release, shows the release notes and downloads + installs the new version (after verifying it is signed with the same key). Optional daily check with a notification. |
| **Automatic builds** | GitHub Actions run the tests on every push and build a signed release APK for every `v*` tag. See [`docs/RELEASING.md`](docs/RELEASING.md). |
| **Narrow-screen fixes** | The schedule editor shows all seven weekdays on narrow phones (e.g. Galaxy Z Fold cover screen); bottom navigation labels no longer wrap. |
| **Navigation fix** | You can always leave *Add apps* / *In-app feeds* (back arrow, *Done* button, back gesture). |
| **Accessibility help** | On Android 13+ the setup screen explains how to unlock the "restricted setting" so app blocking can be enabled for an app installed from an APK. |

The full original feature set (DNS-based website blocking, accessibility-based app blocking,
mindful pause, per-app limits, schedules, strict mode, PIN lock, focus sessions, notification
blocking, DoH blocking, …) is described in the
[original README](https://github.com/ChristopherLaughlin/warden#readme) and in
[`SPEC.md`](SPEC.md).

## Install

1. Download the newest `warden-plus-<version>.apk` from
   **[Releases](https://github.com/desquerteur/warden-plus/releases/latest)**.
2. Open it on your phone and allow *Install unknown apps* for the app you opened it with.
   - If you have the original Warden or an older debug build of Warden+ installed, **uninstall
     it first** — the package name is the same but the signing key differs.
3. Grant the permissions in the setup screen (VPN, Accessibility, Usage access, Notifications).
   - **Android 13+:** if the Accessibility switch is greyed out ("Restricted setting"), tap it
     once, then open *Settings → Apps → Warden+ → ⋮ → Allow restricted settings*, and switch
     Accessibility on again. The in-app setup screen walks you through this.

Later updates can be installed directly from *Settings → Updates* inside the app.

## Building

You need **JDK 17+** and the **Android SDK (API 34)** — easiest via Android Studio.

```bash
./gradlew testDebugUnitTest assembleDebug   # app/build/outputs/apk/debug/app-debug.apk
```

Signed release builds and the GitHub release workflow are described in
[`docs/RELEASING.md`](docs/RELEASING.md). See [`SETUP.md`](SETUP.md) for running on a device.

## Staying in sync with upstream

```bash
git remote add upstream https://github.com/ChristopherLaughlin/warden.git
git fetch upstream
git merge upstream/main
```

## License

[GPL-3.0](LICENSE), the same license as the original project. Warden+ is a modified version
of Warden by Christopher Laughlin; the modifications are listed above and in the git history
(everything after commit `ad62876`). See [`NOTICE`](NOTICE).
