# Gray mode

Colours make phones more tempting – gray makes them boring. Warden+ can switch the whole screen
to grayscale with Android's own **colour correction** (Accessibility › Colour correction ›
Grayscale), so no extra app or overlay is needed.

Settings › **Gray mode**:

| Choice | The screen is gray … |
|---|---|
| **Always** | all the time while gray mode is on |
| **While blocking** | whenever blocking is on: main switch (with "Always on" or a running schedule), focus sessions and "Block until" |
| **On a schedule** | every day between two times, e.g. 21:00–07:00 (past midnight is fine) |

Switching gray mode **on** (or making it cover more time) is instant. Switching it **off**, or
making it cover less time, follows **Block protection** (PIN, Delay or Locked until) – just like
turning blocking off. A **Quick Settings tile** "Gray mode" does the same from the notification
shade (Edit tiles › drag "Gray mode" in).

## The one-time permission (ADB)

Android lets an app change colour correction only with the permission
`WRITE_SECURE_SETTINGS`, which can't be granted on the phone itself – only once from a computer.
It stays granted until Warden+ is uninstalled (updates keep it).

**Easiest:** run the script (it checks adb and the phone first):

```powershell
powershell -ExecutionPolicy Bypass -File scripts\enable-gray-mode.ps1
```

**By hand:**

1. Phone: Settings › About phone › Software information › tap **Build number** 7 times.
2. Settings › Developer options › **USB debugging** on.
3. Connect the phone by USB, allow the computer on the phone ("Allow USB debugging?").
4. On the computer (in the `platform-tools` folder):

   ```
   adb shell pm grant com.warden.blocker android.permission.WRITE_SECURE_SETTINGS
   ```

5. Open Warden+ › Settings › Gray mode – the setup hint disappears.

`scripts\set-device-owner.ps1` grants this permission too, so after "Maximum protection" setup
gray mode works right away.

## Without a computer

Switch grayscale on yourself: Settings › Accessibility › Vision enhancements › **Color
correction** › on › **Grayscale** (Pixel: Accessibility › Color and motion › Color correction).
Samsung also has Digital Wellbeing › **Bedtime mode** › Grayscale on a schedule. Warden+ can't
switch these by itself, and they aren't protected.

## Your own colour correction stays safe

Before turning the screen gray Warden+ remembers how colour correction was set (off, or e.g. a
red-green filter you use). When gray ends it puts exactly that back. If you changed colour
correction yourself in the meantime, your change is kept.

## Limits

- `adb exec-out screencap` screenshots stay in colour: colour correction is applied by the
  display, after the screenshot is taken.
- Uninstalling Warden+ while the screen is gray leaves it gray – switch it off under
  Accessibility › Colour correction.
- Phones whose maker replaced Android's colour correction may ignore the setting. Samsung
  One UI and Pixel use Android's.
