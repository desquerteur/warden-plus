# Maximaler Schutz: Warden+ als Device Owner

*English summary at the end.*

Normalerweise kann man eine Sperr-App mit ein paar Tipps aushebeln: Bedienungshilfe ausschalten,
„Beenden erzwingen“, VPN trennen, Uhrzeit verstellen, abgesicherter Modus. Als **Device Owner**
(„Geräteeigentümer“) bekommt Warden+ von Android selbst die Rechte, das zu verhindern – ohne Root.

Das ist ein **Experten-Modus**. Die Einrichtung dauert etwa 15 Minuten und braucht einmal einen
Windows-PC mit USB-Kabel.

> **Es gibt immer einen Rückweg.** In Warden+ unter *Settings → Maximum protection →
> Remove device owner* wird alles wieder entfernt – höchstens nach 24 Stunden Wartezeit, auch wenn
> du die PIN vergessen hast. Einen Werksreset brauchst du dafür nie.

## Was du brauchst

- Dein Handy mit installiertem Warden+ (einmal geöffnet).
- Einen Windows-PC und ein USB-Kabel, das Daten überträgt (nicht nur ein Ladekabel).
- Deine Passwörter für Google- und Samsung-Konto (du meldest dich kurz ab und wieder an).

## Schritt 1: USB-Debugging einschalten

1. **Einstellungen → Telefoninfo → Softwareinformationen** öffnen.
2. **7× auf „Buildnummer“ tippen**, bis „Entwicklermodus wurde aktiviert“ erscheint
   (evtl. PIN eingeben).
3. **Einstellungen → Entwickleroptionen** (ganz unten) → **USB-Debugging** einschalten.
4. **Nur Samsung (One UI 6 und neuer):** **Einstellungen → Sicherheit und Datenschutz →
   Automatische Blockierung** (*Auto Blocker*) **ausschalten** – sonst blockiert das Handy alle
   Befehle über USB. Nach der Einrichtung kannst du sie wieder einschalten.

## Schritt 2: ADB auf den PC holen

1. Die **„SDK Platform-Tools for Windows“** direkt bei Google herunterladen:
   <https://developer.android.com/tools/releases/platform-tools>
2. Die ZIP-Datei entpacken, z. B. nach `C:\platform-tools`.
3. Handy anschließen. Auf dem Handy erscheint **„USB-Debugging zulassen?“** → **Zulassen**
   (Haken bei „Von diesem Computer immer zulassen“ ist praktisch).

## Schritt 3: Alle Konten vorübergehend abmelden

Android erlaubt den Device Owner **nur, wenn kein einziges Konto angemeldet ist**. Das ist eine
Sicherheitsregel von Android, nicht von Warden+.

1. **Einstellungen → Konten und Sicherung → Konten verwalten.**
2. Jedes Konto antippen → **Konto entfernen**: Google, **Samsung-Konto**, und alles andere, was in der
   Liste steht (z. B. Microsoft, Outlook, Telegram).
3. Deine Fotos, Kontakte und Mails bleiben in der Cloud. Nach Schritt 5 meldest du dich wieder an
   und alles kommt zurück.
4. **Samsung: weitere Benutzer/Profile.** Auch diese verhindern die Einrichtung:
   - **Sicherer Ordner** (*Secure Folder*): Inhalte vorher sichern, dann in den Einstellungen des
     Sicheren Ordners → *Deinstallieren*.
   - **Dual Messenger** (zweites WhatsApp o. ä.): *Einstellungen → Erweiterte Funktionen →
     Dual Messenger* → ausschalten.
   - Ein **Arbeitsprofil** (Firmen-Apps): dann bitte nicht weitermachen, frag deine IT.

Tipp: Das Skript in Schritt 4 sagt dir genau, welche Konten/Profile noch da sind.

## Schritt 4: Warden+ zum Device Owner machen

**Am einfachsten mit dem Skript** (prüft alles und erklärt jeden Fehler, verändert sonst nichts).
Im Warden+-Projektordner in PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\set-device-owner.ps1
```

Nur prüfen, ohne etwas zu setzen: `... set-device-owner.ps1 -CheckOnly`.
Findet es adb nicht: `... set-device-owner.ps1 -AdbPath C:\platform-tools\adb.exe`.
Das Skript schaltet nebenbei auch **Gray mode** frei (Erlaubnis `WRITE_SECURE_SETTINGS`, siehe
[GRAY_MODE.md](GRAY_MODE.md)).

**Oder von Hand** (im Ordner `C:\platform-tools`):

```powershell
.\adb.exe shell dpm set-device-owner com.warden.blocker/.system.WardenDeviceAdminReceiver
```

Erfolg sieht so aus: `Success: Device owner set to package com.warden.blocker`.

## Schritt 5: Konten wieder anmelden

**Einstellungen → Konten und Sicherung → Konten verwalten → Konto hinzufügen.** Google und Samsung
wieder anmelden, Sicheren Ordner bei Bedarf neu einrichten. Das stört den Device Owner nicht mehr.
USB-Debugging kannst du wieder ausschalten (und Samsungs „Automatische Blockierung“ wieder an).

## Schritt 6: Regeln in Warden+ einschalten

Warden+ öffnen → **Settings → Maximum protection**. Jede Regel hat einen eigenen Schalter:

| Regel | Was sie tut |
|---|---|
| Switch off blocked apps | Während die Sperre läuft, legt Android hart gesperrte Apps (und Apps, deren Limit erreicht ist) still – sie sind ausgegraut und gehen auch ohne Bedienungshilfe nicht auf. Telefon, Einstellungen, Startbildschirm, Tastatur und Warden+ werden nie stillgelegt. Apps mit „Pause“ bleiben benutzbar. |
| Always-on website filter | Android hält den Warden+-Filter (VPN) dauerhaft verbunden; „Trennen“ geht nicht. |
| Lock VPN settings | VPN-Einstellungen gesperrt – kein anderes VPN kann den Filter ersetzen. |
| Block uninstalling Warden+ | Warden+ lässt sich nicht deinstallieren (auch nicht per PC). |
| Lock app controls | „Beenden erzwingen“, „Daten löschen“, „Deinstallieren“ sind für alle Apps gesperrt. |
| Lock date & time | Uhrzeit kommt automatisch aus dem Netz und lässt sich nicht verstellen – Zeitpläne können nicht ausgetrickst werden. |
| Block safe mode | Der abgesicherte Modus (startet ohne Warden+) ist gesperrt. |

Bewusst **nicht** dabei: Werksreset sperren und USB-Debugging sperren – sonst gäbe es keinen
Rückweg mehr außerhalb der App.

## Wieder entfernen (Notausgang)

- **In der App:** *Settings → Maximum protection → Remove device owner.* Warden+ nimmt alle Regeln
  zurück (Apps wieder an, VPN und Uhrzeit frei) und hört auf, Device Owner zu sein. Danach ist es
  eine ganz normale App und lässt sich auch deinstallieren.
- **Wartezeit:** Verlangt die *Block protection* eine Wartezeit (Verzögerung oder „gesperrt bis“),
  gilt sie – aber **nie länger als 24 Stunden**. Danach erscheint „Remove device owner now“.
- **PIN vergessen?** Auf dem gesperrten Einstellungs-Bildschirm steht ebenfalls *Remove device owner*.
  Ohne PIN dauert es immer 24 Stunden, dann geht es.
- **Per PC geht das Entfernen nicht** (`dpm remove-active-admin` funktioniert bei normalen Apps
  nicht) – der Weg führt immer über die App. Ein Werksreset entfernt den Device Owner natürlich
  auch, ist aber nie nötig.

## Typische Fehlermeldungen

| Meldung (gekürzt) | Bedeutung | Lösung |
|---|---|---|
| `no devices/emulators found` | Handy nicht erkannt | Datenkabel nutzen, USB-Debugging an, „Zulassen“ tippen |
| `unauthorized` | PC noch nicht freigegeben | Handy entsperren, „USB-Debugging zulassen?“ → Zulassen |
| `...already some accounts on the device` | Noch ein Konto angemeldet | Alle Konten abmelden (Schritt 3), evtl. neu starten |
| `...already several users on the device` | Sicherer Ordner / Dual Messenger / Arbeitsprofil | Entfernen (Schritt 3), neu starten |
| `...device owner is already set` | Schon ein Device Owner da | Ist es Warden+: fertig. Sonst Firmenhandy → IT fragen |
| `...the device is already provisioned` | Gerät gilt als „fertig eingerichtet“ (selten, Hersteller-abhängig) | Konten/Profile prüfen; manche Geräte erlauben es nur direkt nach einem Werksreset |
| `Unknown admin: ComponentInfo{...}` | Warden+ fehlt oder ist zu alt | Aktuelle Warden+-Version installieren und einmal öffnen |
| `SecurityException` / `MANAGE_DEVICE_ADMINS` | Das Handy verbietet USB-Befehle | Samsung: „Automatische Blockierung“ aus (Schritt 1). Andere Hersteller: in den Entwickleroptionen „USB-Debugging (Sicherheitseinstellungen)“ einschalten |

## Hinweise für Samsung (Knox)

- Samsung-Geräte unterstützen den Device Owner normal (Android-Enterprise-Standard). Knox selbst
  musst du nicht einrichten.
- Nach der Einrichtung zeigt das Handy evtl. „Dieses Gerät wird von deiner Organisation verwaltet“ –
  die „Organisation“ ist Warden+ auf deinem eigenen Handy. Warden+ liest keine Daten und kann das
  Handy weder sperren noch löschen.
- Ist dein Handy über **Knox Mobile Enrollment / Knox Guard** an eine Firma gebunden (Firmen- oder
  Leasinghandy), geht die Einrichtung nicht – und sollte es auch nicht.
- Das Samsung-Konto heißt in Android `com.osp.app.signin`; das Skript zeigt es als „Samsung-Konto“.
- One UI 6+: **Automatische Blockierung** (Auto Blocker) muss für die Einrichtung aus sein (Schritt 1).

## Updates

App-Updates von Warden+ funktionieren als Device Owner ganz normal. Ausnahme: der Wechsel von einer
**Debug-** auf eine **Release-Version** (anderer Schlüssel) braucht eine Deinstallation – dafür vorher
den Device Owner in der App entfernen.

---

## English summary

Device-owner mode lets Android itself enforce Warden+'s rules (no root): suspend blocked apps,
always-on VPN, lock VPN settings, block uninstalling Warden+, block Force stop / Clear data, lock
date & time, block safe mode – each switch on its own in *Settings → Maximum protection*.

1. Enable **Developer options → USB debugging** (Samsung One UI 6+: turn **Auto Blocker** off).
2. Get Google's **SDK Platform-Tools**: <https://developer.android.com/tools/releases/platform-tools>
3. **Sign out of every account** (Google, Samsung, …) and remove Secure Folder / Dual Messenger /
   work profiles – Android refuses a device owner otherwise.
4. Run `powershell -ExecutionPolicy Bypass -File scripts\set-device-owner.ps1` (checks everything,
   explains errors), or `adb shell dpm set-device-owner com.warden.blocker/.system.WardenDeviceAdminReceiver`.
5. Sign back in to your accounts.
6. Pick the rules in Warden+.

**Way back:** *Settings → Maximum protection → Remove device owner* lifts every rule and ends
device-owner mode — after at most 24 hours of waiting, even with a forgotten PIN (from the locked
Settings screen). It can't be removed via ADB, and a factory reset is never needed.
