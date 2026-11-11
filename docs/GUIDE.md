# Privacy Guard user guide

This guide takes you from installing the app to reading what it finds and acting on it. Every screen and button is named as it appears in the app.

- [1. Install](#1-install)
- [2. Setup (5 minutes, once)](#2-setup-5-minutes-once)
- [3. Today](#3-today)
- [4. Activity](#4-activity)
- [5. Apps](#5-apps)
- [6. Blocking](#6-blocking)
- [7. Widget and Quick Settings tile](#7-widget-and-quick-settings-tile)
- [8. Common tasks](#8-common-tasks)
- [9. Troubleshooting](#9-troubleshooting)
- [10. FAQ](#10-faq)

---

## 1. Install

1. Download `PrivacyGuard-x.y.z.apk` from the [Releases](../../releases) page.
2. Open it on the phone and allow installing from your browser or file manager when Android asks.
3. Android 11 or newer is needed.

Privacy Guard has no account and sends nothing anywhere. The only downloads are the optional block lists.

## 2. Setup (5 minutes, once)

Open **Today → Setup**. It has four steps; the app shows *"N of 4 steps done"*.

### Step 1. Usage access
Tap **Open Settings**, find Privacy Guard and allow it. This shows which app is on screen and how much data each app sends.

### Step 2. Full access
Android only shows ordinary apps a little. Full access lets Privacy Guard read the system's own access records: who used what, when, for how long, and whether the app was on screen.

**Option A – from a computer, once (USB or Wi-Fi).** Install [platform-tools](https://developer.android.com/tools/releases/platform-tools), connect the phone with USB debugging on, then tap **Copy commands** in the app and run them, or type:

```bash
adb shell pm grant com.privacyguard android.permission.DUMP
adb shell pm grant com.privacyguard android.permission.PACKAGE_USAGE_STATS
```

This stays after restarts.

**Option B – no computer, with Wireless debugging.**
1. Turn on *Developer options* (Settings → About phone → tap *Build number* 7 times).
2. In the app tap **Start**.
3. In *Developer options → Wireless debugging* turn it on and tap **Pair device with pairing code**.
4. A Privacy Guard notification appears: type the 6-digit code into it. Split screen helps to see both.

After pairing the app tries to give itself permanent access, like option A. If your phone refuses, Option B still works, but after every restart you tap **Reconnect** (Wireless debugging must be on).

Option B is also what enables the actions that need a shell: removing permissions, the firewall and the debloater.

The chip at the top of Today shows the level: **Full access (permanent)**, **Full access (Wireless debugging)** or **Limited**.

### Step 3. Continuous monitoring
Turn it on. A small permanent notification appears; it watches the microphone, camera and screen and alerts you immediately when something happens with the screen off. Here you also find **Clipboard alerts**: a notification when an app reads what you copied while it is not on screen.

### Step 4. Record destinations (local VPN)
Turn it on and accept Android's VPN prompt. The VPN handles only DNS (server names), not your traffic, so speed is unchanged. It shows which servers every app contacts and makes blocking possible.

- Private DNS should be *Off* or *Automatic* (Settings → Connections → More connection settings → Private DNS). With a named Private DNS the app cannot see or block names.
- Only one VPN can run at a time; another VPN app turns this one off.

### Battery
Allow unrestricted battery use for Privacy Guard (Settings → Apps → Privacy Guard → Battery), otherwise the system may stop it at night. On Xiaomi, Huawei, Oppo, Vivo and similar phones also allow *Autostart*. The background service uses about 1% of the battery per day.

---

## 3. Today

**The numbers at the top.** *Critical* and *High* count distinct cases (app + kind of data), with the number of single events in brackets. *Tracker requests* are requests to known tracker servers. Then microphone and camera use, and data sent in the background.

**Refresh** reads the newest records. **Today PDF** and **7-day PDF** save a report to `Download/PrivacyGuard`; a report is also made every night at 23:50.

**Alerts.** Sensitive access while the app was in the background or the screen was off. Each alert has **Block…**:
- *…: allow only while in use* for the microphone, camera and location, or *…: block completely* for contacts, calls, SMS and similar (the app then gets an empty list),
- **Block this app's internet**.

**Data access followed by a tracker.** An app used something sensitive (contacts, location…) and contacted a tracker within 10 minutes. This is a match in time only: the traffic is encrypted, so its content cannot be seen.

**Trackers today.** Ad networks, data brokers and analytics companies contacted today, with the apps that contacted them. **Block** blocks that server for every app.

**History.** Shows since when data is recorded and how much space it uses. **Clear history** deletes everything and starts from zero. Activity is kept 60 days and the DNS log 30 days.

---

## 4. Activity

**All activity today.** Every access by every app, on screen or not, grouped by category (Location, Contacts & personal data, Photos & files…). Tap a category to see all apps in it.

**DNS log.** Every server name an app asked for today.
- **Flagged** shows only known trackers, block-list hits and names with tracking words (analytics, telemetry, pixel…); **All** shows everything.
- Search by app or server.
- **Block** / **Allow** per server. If something stopped working, find its blocked server here and tap **Allow**.

---

## 5. Apps

**Spyware check.** Lists apps with powers that allow watching you: accessibility services (read the screen), notification access, device admin, keyboards, VPN apps, drawing over other apps, installing apps, usage access, a hidden icon, or installed from outside an app store. Apps that combine a hidden icon or unknown source with surveillance powers are shown in red. Most other matches are normal apps; check that you know each one. **Settings** opens the app's page to remove it or its access. Certificates you installed are listed too, because they can be used to read HTTPS traffic.

**Privacy score · last 7 days.** 100 is clean; points are lost for alerts, tracker companies contacted, tracker SDKs inside and heavy background uploads. **Tap an app** for a 30-day chart of sensitive access and tracker requests. **Limit internet** lets the app go online only while it is on screen.

**Tracker SDKs inside apps.** Found in each app's manifest and, after **Deep scan**, in its code (about 1,000 signatures from App Manager and Exodus Privacy). An SDK inside does not mean it is active.

**Permission advisor.** Permissions an app holds but has not used, or that don't fit the kind of app. **Remove** takes it away (the app asks again if it needs it); **Restore** gives it back. Needs Wireless debugging; otherwise Settings opens.

**Changes after updates.** When an app updates and starts asking for more permissions or adds tracker SDKs, you get a notification and it is listed here.

**Inspect an app.** Opens an app's package without running it.
- The summary shows risky settings (backup allowed, unencrypted http, debuggable…), dangerous permissions, parts other apps can open without permission, and the links it opens.
- **Report** and **Manifest** open the full text in a read-only viewer with search and **Copy all**.
- **Find servers & keys** lists the servers, URLs, IP addresses and keys written in its code; tracker servers are marked. Most keys are public client keys every copy of the app contains.

**Debloater.** Removes pre-installed apps you don't use, for your user only. Nothing is deleted from the phone, and **Restore** brings any app back (a factory reset brings everything back too).
- By default it shows apps marked *Safe* and *Replace*. **Advanced** adds *Caution* and *Unsafe* ones after a warning; read each app's warning before removing it.
- Tap an app for its description and which apps depend on it.
- Needs Wireless debugging. Apps that were installed from a store show **Reinstall**, which opens the store.

---

## 6. Blocking

All network blocking works through the local VPN (Setup, step 4).

**Block lists.** Ad, tracker and malware lists (HaGeZi), checked for every app. An app's own servers (Instagram → instagram.com) and Facebook/Instagram/WhatsApp content are never blocked by a list. Lists update weekly; **Update lists now** downloads them at once.

**Firewall.** Android's own per-app network switch: it cuts *all* traffic of the apps in *Apps without internet*, including hardcoded IP addresses and their own encrypted DNS. Needs Wireless debugging; after a restart it comes back on the next **Reconnect**.
**Stop DNS bypass** sends apps that talk straight to public DNS servers (8.8.8.8, 1.1.1.1…) or use encrypted DNS back through the phone's DNS, so blocking still sees them.

**Internet only while on screen.** Apps added with *Limit internet* on the Apps tab. Music or navigation apps would stop in the background.

**Blocking.**
- **Block third-party trackers**: e.g. the Facebook SDK inside another app is blocked, Instagram inside Instagram is not. System apps are never touched.
- **Apps without internet**, **Blocked domains** and **Limited permissions** list everything you blocked, each with **Remove** or **Restore**.

---

## 7. Widget and Quick Settings tile

- **Widget**: long-press the home screen → Widgets → Privacy Guard. It shows today's alerts, tracker requests, microphone/camera use and the VPN state, and opens the app when tapped.
- **Tile**: pull down Quick Settings → edit (pencil) → drag **Privacy VPN** into place. It turns the local VPN on and off.

---

## 8. Common tasks

| I want to… | Do this |
|---|---|
| See who used the microphone at night | Today → Alerts, or Activity → All activity → Microphone |
| Stop an app from tracking me | Blocking → turn on block lists and *Block third-party trackers* |
| Cut an app off completely | Alert → **Block…** → *Block this app's internet*, then connect Wireless debugging for the firewall |
| Let an app use the camera only while open | Alert → **Block…** → *Camera: allow only while in use* |
| Find out if I have spyware | Apps → Spyware check |
| Fix an app that broke after blocking | Activity → DNS log → All → search the app → **Allow** its blocked server |
| Remove bloatware | Apps → Debloater (Wireless debugging connected) |
| Share a report | Today → **7-day PDF** |

---

## 9. Troubleshooting

**The chip says "Limited".** Do Setup step 2. With option B, Wireless debugging must be on; tap **Reconnect** after a restart.

**"Setup incomplete".** Tap **Open setup**; the missing steps are marked.

**No servers in the DNS log.** The local VPN is off (step 4), another VPN replaced it, or Private DNS is set to a hostname.

**An app or website stopped working.** See *Fix an app that broke* above. You can also turn off a list in Blocking → Block lists.

**No alerts at night although something happened.** Allow unrestricted battery use and autostart (section 2, *Battery*).

**Removing a permission or a system app fails.** These need Wireless debugging connected (the chip says *Full access (Wireless debugging)*). Without it the app shows the command to run from a computer.

**After a restart the firewall is off.** Android clears it at every restart. Turn on Wireless debugging and tap **Reconnect**; the monitor also re-applies it within 15 minutes once connected.

---

## 10. FAQ

**Does Privacy Guard see my messages or what I say?** No. It sees *when* and *how long* an app used something, and *which servers* it contacted, never the content.

**Does the VPN slow down my internet?** No. Only DNS requests (server names) pass through it; the traffic goes directly.

**Is a red alert proof of spying?** No. It means "worth a look". Many apps have good reasons; the alert tells you the facts so you can decide.

**Why is Wireless debugging needed?** Android lets only the shell remove permissions, switch off an app's network or remove system apps. Wireless debugging gives Privacy Guard that shell on the phone itself, without a computer.

**Where is my data?** Only on the phone, in the app's private storage. Uninstalling the app deletes it.

**Which phones work?** Android 11 or newer. It is tested on Samsung (Android 17) and on clean Android 11 and 14. Some manufacturers block permanent access; Wireless debugging then works instead.
