# JDrive WebUSB: bypass `Restriction prevents installing` (24 Nov 2026)

## Hebrew summary (לקח)

**הבעיה:** ההתקנה שלנו נכשלה על יחידות DesaySV / Chery / Jaecoo (למשל T13J) עם  
`java.lang.SecurityException: Restriction prevents installing`  
כי השתמשנו ב־`pm install`. פקודת ה־shell של PackageManager בודקת את  
`DISALLOW_INSTALL_APPS` / `no_install_apps` **לפני** יצירת סשן, ולכן כל דגל  
(`-r -t -g`, `--user 0`, ניסיון `clear-restriction`) נדחה.

**למה JConfig / JCarTools / DSA (Vadim) הצליחו על אותו רכב:** הם **לא** קוראים ל־`pm install`.  
הם דוחפים APK + DEX (או stub) ל־`/data/local/tmp` ומריצים  
`CLASSPATH=<dex> app_process /system/bin <MainClass> <apk>`  
שמדבר ישירות עם **PackageInstaller Session API** תחת הקשר של `com.android.shell`.

**מה שינינו:** בנינו helper משלנו (`com.azran.installer.SessionInstaller`), דוחפים אותו  
מהאתר, ומשתמשים בו כנתיב ההתקנה הראשי. `pm install -r -d -g` נשאר רק כ־fallback.

## Technical root cause

On restricted head units, `UserManager` has `no_install_apps` set.  
`PackageManagerShellCommand.doCreateSession` calls  
`UserManager.hasUserRestriction(DISALLOW_INSTALL_APPS)` and throws  
`SecurityException: Restriction prevents installing` before any session is created.

Verified live 24 Nov 2026 on customer T13J serial `35508d92`:  
- `dumpsys user` showed `Restrictions` including `no_install_apps`  
- `pm install -r -t -g` / `--user 0` / `install-create` all failed the same way  
- No device owner (`dumpsys device_policy` empty)  
- Brazilian JConfig install of **their** APK on the **same** car succeeded

## Competitor technique (analysis only — we do not ship their DEX)

Common pattern across JConfig, and confirmed in spirit by JCarTools (`/data/local/tmp/stub …`) and DSA/jaecoo-apps.store (same WebUSB install family):

1. Push APK → `/data/local/tmp/…`
2. Push helper DEX (or stub) → `/data/local/tmp/…`
3. `CLASSPATH=<dex> app_process /system/bin <Class> <apkPath>`
4. Helper:
   - `ActivityThread.systemMain()` → `getSystemContext()`  
   - `createPackageContext("com.android.shell", CONTEXT_IGNORE_SECURITY=2)`  
   - `PackageInstaller.SessionParams(MODE_FULL_INSTALL)`  
   - Reflect `installFlags |= INSTALL_REPLACE_EXISTING (2) | INSTALL_ALLOW_DOWNGRADE (0x80) | INSTALL_ALL_WHITELIST_RESTRICTED_PERMISSIONS (0x400000)`  
   - `createSession` → `openSession` → `openWrite`/`fsync` → `commit` + `SessionCallback.onFinished`

Note: AOSP names for the 0x80 / 0x400000 flags are ALLOW_DOWNGRADE and ALL_WHITELIST_RESTRICTED_PERMISSIONS respectively (some informal notes swap the names; values above match AOSP).

## What we changed

| Item | Change |
|------|--------|
| `native/com/azran/installer/SessionInstaller.java` | Our own minimal session installer |
| `public/session-installer.dex` | Compiled with android-34 + d8 (min-api 26) |
| `src/adb-client.ts` `installApk()` | Primary: push DEX + `app_process SessionInstaller`; fallback: `pm install -r -d -g` (+ user retries) |
| `src/main.ts` | Hebrew status strings for the new steps |
| GitHub Pages | Redeployed with new JS hash + DEX asset |

**Not shipped:** JConfig `s6.dex`, `sop6.*` class names, or their runner scripts.

## How to verify

1. Open https://guyazran95-del.github.io/jdrive-installer/ (hard refresh).  
2. Confirm Network tab loads `session-installer.dex` and JS asset `index-Bj2Jh6x3.js` (hash may change on later deploys).  
3. Connect a restricted T13J/DesaySV unit over WebUSB.  
4. Install JDrive APK — status should show PackageInstaller / app_process, then Success.  
5. Optional CLI check of the same helper:  
   `adb push session-installer.dex /data/local/tmp/`  
   `adb push app.apk /data/local/tmp/`  
   `adb shell 'CLASSPATH=/data/local/tmp/session-installer.dex app_process /system/bin com.azran.installer.SessionInstaller /data/local/tmp/app.apk'`

## Remaining risks

- Some units may kill `app_process` / deny shell PackageInstaller; then fallback `pm install` still fails under Restriction.  
- `PendingIntent.FLAG_MUTABLE` needs API 31+; older HU may need a flag tweak (JConfig uses the same combo and works on T13J).  
- OEM SELinux policies could block writing session streams (unseen so far on DesaySV).  
- After install, some car apps still need separate `pm grant` / AppOps (out of scope here).
