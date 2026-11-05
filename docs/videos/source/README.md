# Intro video source

`docs/videos/warden-plus-intro.mp4` (and the preview `docs/images/warden-plus-intro-preview.gif`)
is built in code from real captures of Warden+ 0.3.6 (debug build, Android 14 emulator,
904 × 2316 "fold cover" screen). No sound, no generated images.

| File | What it is |
|---|---|
| `intro.html` | The scene: chapters, captions word by word (soft blur/slide), floating and gently tilting phone frames, a slowly moving gradient, soft tap circles where the recordings were tapped, and small accent chips (tick, count-up). `window.seek(t)` draws the frame at `t` seconds; all motion uses cubic ease-in-out/ease-out curves. Colours from `ui/theme/Theme.kt` (indigo `#4F5BD5`, dark indigo `#3A44A8`, coral `#E86A5C`). |
| `build.mjs` | Cuts the screen recordings into 60 fps frame sequences, renders every frame with headless Edge/Chrome (Playwright) in 5-second parts (a stopped run resumes), encodes H.264 60 fps (`yuv420p`), joins the parts with `+faststart` and makes the preview GIF (20 fps). Clip offsets, speeds and tap positions are in `SEGMENTS`. |
| `hero.html` | Layout of `docs/images/hero.jpg` (Home, Usage and Blocklist side by side). |
| `build-images.mjs` | Turns PNG screenshots (`adb exec-out screencap -p`) into the JPGs in `docs/images/` (max. 900 px wide) and renders `hero.jpg` from `hero.html`: `node build-images.mjs --caps <png dir> --tools <tools dir>`. |

## Rebuild

1. Record the raw clips on a device or emulator (they are not in the repo, they are large):
   `adb shell screenrecord --time-limit 30 /sdcard/x.mp4`, then `adb pull`. Put them in one folder
   with these names: `home-on.mp4` (switch blocking on), `web-add.mp4` (add a website in
   Blocklist), `pause.mp4` (open a blocked app → breathing pause), `hosts.mp4` (add a
   ready-made hosts list), `schedule.mp4` (new schedule with the clock picker), `block-until.mp4`
   (Home › Block until › Pick date & time), `protection.mp4` (Settings › Protection, choose PIN),
   `usage.mp4` (Usage tab counting up), `wizard-done.mp4` (setup wizard summary ticks),
   `guide.mp4` (a spotlight guide with the finger). Start/length/speed of each clip are in
   `SEGMENTS` in `build.mjs` — adjust them (and the `taps`, in seconds of the recording and device pixels) to your recordings.
2. The stills come from `docs/images/` (`hosts-lists.jpg`, `allow-only.jpg`, `categories.jpg`,
   `protection.jpg`, `usage-dark.jpg`).
3. Install the tools somewhere outside the repo and build:

   ```bash
   mkdir /tmp/vidtools && cd /tmp/vidtools && npm init -y && npm install playwright-core ffmpeg-static
   node docs/videos/source/build.mjs --raw /path/to/raw --tools /tmp/vidtools --browser msedge
   ```

   Edge or Chrome must be installed locally (`--browser chrome` for Chrome). Rendering the
   ~51 s at 60 fps takes 10+ minutes. Steps can be run one by one: `--step prepare`, `--step render`
   (resumable, parts of `--part-seconds 5`), `--step encode`. Options: `--work <dir>` for the
   scratch folder, `--crf 27` for the quality/size trade-off, `--gif-from 0 --gif-dur 9 --gif-fps 20`
   for the GIF excerpt.
