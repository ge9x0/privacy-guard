# Privacy Guard

An Android app that shows what your apps really do: who used the microphone, camera, location, contacts or clipboard, when it happened (on screen, in the background, with the screen off), which servers each app contacted, and which trackers are built into them. It can also block trackers and ads, cut an app's internet, remove pre-installed bloatware and take apart an app's package to see what is inside.

Everything runs on the phone. No account, no cloud, no analytics. The only network requests the app makes are the optional block-list downloads (from jsDelivr, with GitHub as a fallback).

**Requirements:** Android 11 or newer. Tested on a Samsung Galaxy A35 (Android 17) and on clean Android 11 and Android 14 emulators.

## Features

| Tab | What it does |
|---|---|
| **Today** | Alerts (sensitive access in the background or with the screen off), data access followed by a tracker, trackers contacted today, daily and 7-day PDF reports, setup |
| **Activity** | Every access by every app, grouped by category; DNS log of every server each app asked for, with tracker / block-list / keyword flags |
| **Apps** | Privacy score per app, tracker SDKs inside each app (deep scan of the code), permission advisor, **Inspect an app** (manifest decoder, servers and keys written in the code, read-only code viewer), **Debloater** |
| **Blocking** | Block lists (HaGeZi), firewall, internet only while on screen, blocked apps/domains, limited permissions |

Also: instant notification when the mic or camera turns on with the screen off, clipboard read alerts, a new-app alert with a tracker scan, a weekly privacy score, and a PDF report every night at 23:50 in `Download/PrivacyGuard`.

## Access levels

Android only lets ordinary apps see a little. Privacy Guard picks the best level it has:

| Level | How to get it | What you get |
|---|---|---|
| **Full access (permanent)** | Once, from a computer: the commands under *Today → Setup → step 2, option A*. Or option B below; the app then tries to grant itself permanent access. | Full history: who, when, for how long, on screen or not. Survives restarts. |
| **Full access (Wireless debugging)** | *Today → Setup → step 2 → Start*, then pair with the code from *Developer options → Wireless debugging*. No computer needed. | The same, plus actions that need a shell: removing permissions, debloating, the firewall. Tap *Reconnect* after a restart. |
| **Limited** | Nothing | When the mic/camera turned on and whether the screen was off, but not always which app. |

Some manufacturers block permanent access; Wireless debugging then works instead.

## Setup on the phone

1. **Usage access**: allow Privacy Guard.
2. **Full access**: option A or B above.
3. **Continuous monitoring**: on (a small permanent notification).
4. **Local VPN**: on, to see and block where apps connect. It only handles DNS; your traffic does not go through it. Private DNS should be *Off* or *Automatic*.
5. Allow unrestricted battery use for Privacy Guard, so the system does not stop it at night (on Xiaomi, Huawei, Oppo and similar phones also allow autostart).

## Building

```bash
git clone <this repo> && cd PrivacyGuardApp
./gradlew assembleDebug            # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest        # unit tests
./gradlew connectedDebugAndroidTest   # taps every button on every tab (use an emulator: it presses Remove/Block too)
```

JDK 17+ and the Android SDK (platform 35) are needed. GitHub Actions (`.github/workflows/build.yml`) builds the APK, runs the unit tests and lint on every push; the APK is under *Actions → Artifacts*.

## How it works

```
collect/   Shell access levels, the collector (appops history + network usage)
core/      parsers for dumpsys output, alert rules, tracker list
monitor/   foreground service: screen, mic, camera, clipboard, instant alerts
vpn/       local DNS-only VPN: logging, blocking, catching apps that bypass the phone's DNS
block/     blocking rules, block lists, firewall, foreground-app detection
apps/      privacy score, SDK scanner (manifest + DEX), permission advisor, debloater
inspect/   binary XML (AndroidManifest / resources.arsc) parser, servers and keys finder
report/    PDF report
ui/        Compose screens, code viewer
```

- **Blocking** happens at DNS level through the local VPN: blocked names get NXDOMAIN. An app's own servers (Instagram → instagram.com) are never blocked by a list. System apps are not touched.
- **Firewall** uses Android's own per-app network switch (`cmd connectivity`), which also stops hardcoded IPs and the app's own encrypted DNS. It needs Wireless debugging and is applied again after a restart on the next Reconnect.
- **Debloater** runs `pm uninstall -k --user 0`: the app is removed for your user only and stays in the system image, so *Restore* (`cmd package install-existing`) brings it back. Only system apps are offered; risky ones are hidden behind *Advanced*.

## Limitations

- It shows **when** and **how much**, never the content of what was heard or sent.
- "Data access followed by a tracker" is a match in time, not proof that the data was sent.
- An app that sidesteps Android's own access records would not appear.
- Without the VPN, the app cannot tell which servers an app contacted.
- An alert means "worth a look", not automatic proof of spying.

## License and credits

Privacy Guard is free software under **GPL-3.0-or-later** (see `LICENSE`).

- **Tracker signatures** (`app/src/main/res/values/am_trackers.xml`) are copied unchanged from [App Manager](https://github.com/MuntashirAkon/AppManager) by Muntashir Al-Islam (GPL-3.0-or-later, commit `dc4154f`), which is based on the [Exodus Privacy](https://exodus-privacy.eu.org) database.
- **libadb-android** by the same author (JitPack dependency).
- **Block lists** are downloaded on the phone from [HaGeZi DNS blocklists](https://github.com/hagezi/dns-blocklists); they are not part of this repository.
- **Debloat list** (`app/src/main/assets/debloat/*.json`) is copied unchanged from [android-debloat-list](https://github.com/MuntashirAkon/android-debloat-list) (commit `10c8249`), © Muntashir Al-Islam and contributors, under **AGPL-3.0** (see `assets/debloat/COPYING` and `NOTICE.txt`). AGPL-3.0 is compatible with GPL-3.0 (section 13).
- The DEX scanner, binary XML parser, firewall and DNS bypass catcher were written for this project (ideas from App Manager, no copied code).
