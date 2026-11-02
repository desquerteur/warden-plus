# Releasing Warden+

## Deutsch

Neue Versionen baut GitHub automatisch. Die App findet sie über **Einstellungen → Updates → Check for updates**.
Alle Versionen müssen mit **demselben Schlüssel** signiert sein, sonst lassen sich Updates nicht installieren.

### Einmalig: Schlüssel anlegen

1. Im Projektordner in PowerShell ausführen:

   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts\create-signing-key.ps1
   ```

   Das Skript legt an (Passwörter werden nicht angezeigt):
   - `%USERPROFILE%\.warden-plus\release.jks` – der Schlüssel
   - `keystore.properties` im Projektordner – damit lokale Release-Builds signiert werden
   - `%USERPROFILE%\.warden-plus\github-secrets.txt` – die Werte für GitHub

2. **Sicherung:** Den Ordner `%USERPROFILE%\.warden-plus` sicher aufbewahren (z. B. Passwort-Manager,
   verschlüsselter USB-Stick). Ist der Schlüssel weg, kann die installierte App nie wieder aktualisiert werden.
   `release.jks`, `keystore.properties` und `github-secrets.txt` niemals teilen oder committen.

3. Auf GitHub im Repository: **Settings → Secrets and variables → Actions → New repository secret**.
   Die vier Einträge aus `github-secrets.txt` anlegen (Name = erste Zeile, Wert = Zeile darunter):
   `WARDEN_KEYSTORE_BASE64`, `WARDEN_KEYSTORE_PASSWORD`, `WARDEN_KEY_ALIAS`, `WARDEN_KEY_PASSWORD`.
   Danach `github-secrets.txt` löschen (der Schlüssel selbst bleibt in `release.jks`).

### Neue Version veröffentlichen

```bash
git tag v0.2.0
git push origin v0.2.0
```

Oder auf GitHub: **Actions → Release → Run workflow** und die Version (z. B. `0.2.0`) eingeben.

Der Workflow testet, baut die signierte APK und hängt sie als `warden-plus-0.2.0.apk` an das Release an.
Versionen haben immer die Form `MAJOR.MINOR.PATCH`; MINOR und PATCH gehen bis 99
(versionCode = major·10000 + minor·100 + patch, z. B. 0.2.0 → 200).

### Wichtig für die erste Installation

Eine Debug-Version (selbst gebaut oder aus der CI) ist mit einem anderen Schlüssel signiert.
Wer sie installiert hat, muss sie **einmal deinstallieren** und die Release-APK von GitHub installieren.
Danach funktionieren Updates direkt in der App.

---

## English

1. Run `powershell -ExecutionPolicy Bypass -File scripts\create-signing-key.ps1` once. It creates
   `%USERPROFILE%\.warden-plus\release.jks`, `keystore.properties` (local signing) and
   `%USERPROFILE%\.warden-plus\github-secrets.txt`. Back up `%USERPROFILE%\.warden-plus` – losing the key
   means installed apps can never be updated again.
2. Add the four values from `github-secrets.txt` under **Settings → Secrets and variables → Actions**:
   `WARDEN_KEYSTORE_BASE64`, `WARDEN_KEYSTORE_PASSWORD`, `WARDEN_KEY_ALIAS`, `WARDEN_KEY_PASSWORD`.
3. Release: `git tag v0.2.0 && git push origin v0.2.0` (or **Actions → Release → Run workflow**).
   The workflow runs the unit tests, builds the signed APK and attaches `warden-plus-<version>.apk`
   to a GitHub release. The app's updater (**Settings → Updates**) picks up the latest release.

Debug builds are signed with a different key: uninstall once and install the release APK to get in-app updates.

Local builds: `gradlew assembleDebug` needs no key. `gradlew assembleRelease` uses `keystore.properties`
(or the `WARDEN_KEYSTORE_FILE`, `WARDEN_KEYSTORE_PASSWORD`, `WARDEN_KEY_ALIAS`, `WARDEN_KEY_PASSWORD`
environment variables) and stops with a clear error if they are missing.
`-PversionName=1.2.3 -PversionCode=10203` overrides the version (in PowerShell, quote them:
`'-PversionName=1.2.3'`).
