# Privacy Guard – app për Android

App-i që punon vetë në telefon dhe çdo natë krijon raportin PDF: çfarë u përdor (mikrofon, kamera, vendndodhje, kontakte, clipboard…), çfarë u dërgua, kujt iu dërgua, dhe çfarë ndodhi me ekran të fikur.

## Tri mënyrat (app-i zgjedh vetë më të mirën)

| Mënyra | Si aktivizohet | Çfarë sheh | Pas restartimit |
|---|---|---|---|
| **A – Leje e përhershme** | Një herë: `adb shell pm grant com.privacyguard android.permission.DUMP` (nga kompjuteri), **ose** vetë app-i me Mënyrën B | Historikun e plotë: kush, kur, sa gjatë, në ekran apo background | Mbetet |
| **B – Wireless debugging** | Në app: *Fillo* → Wireless debugging → Pair with code → kodi te njoftimi | Njësoj si A | Duhet *Rilidhu* (Wireless debugging fiket) |
| **C – E kufizuar** | Asgjë | Kur u ndez mikrofoni/kamera dhe nëse ekrani ishte i fikur, por jo gjithmonë app-in | – |

Pasi lidhet me Mënyrën B, app-i provon automatikisht t'i japë vetes lejen e Mënyrës A. Nëse Samsung-u e pranon, Wireless debugging nuk duhet më kurrë.

Pjesët që punojnë në çdo mënyrë:
- **Sa dërgoi çdo app** (background / plan i parë / data celulare), me lejen *Usage access*
- **Kujt i dërgoi**: VPN lokale që kalon vetëm DNS dhe tregon cili app kërkoi cilin server, krahasuar me listën e brokerëve dhe trackerave
- **Njoftim i menjëhershëm** kur mikrofoni/kamera ndizet me ekran të fikur (thirrjet përjashtohen)
- **PDF çdo natë në 23:50** te `Download/PrivacyGuard`, plus butona për PDF-në e sotme dhe të 7 ditëve

## Ndërtimi i APK-së

Kodi **nuk është kompiluar ende**: u shkrua në një mjedis pa qasje te Android SDK. Logjika e leximit të `dumpsys` u testua dhe jep të njëjtat rezultate si scripti Python. Gabimet e vogla të kompilimit pritet të dalin në ndërtimin e parë.

**Me Claude Code në Fedora (më e lehta):**
```bash
cd PrivacyGuardApp
claude
```
dhe thuaj: *"Instalo Android SDK (command-line tools, platform 35, build-tools), ndërto me ./gradlew assembleDebug dhe rregullo gabimet e kompilimit. Mos ndrysho logjikën."*

**Me GitHub Actions:** ngarkoje dosjen në një repo. Workflow-i `.github/workflows/build.yml` e ndërton vetë dhe APK-ja del te *Actions → Artifacts*.

**Me Android Studio:** *Open* → dosja → *Run*.

Gjërat që ka më shumë gjasa të duan rregullim:
- versioni i `libadb-android` (3.1.1) dhe emrat e metodave të tij (`pair`, `autoConnect`, `openStream`) te `adb/AdbManager.kt` dhe `collect/Shell.kt`
- importet e Compose te `ui/MainActivity.kt`

## Testi i Mënyrës A (kur të kesh kompjuterin afër, edhe pa USB)

Në telefon: *Developer options → Wireless debugging → Pair device with pairing code*. Në Fedora:
```bash
adb pair IP:PORTA_E_PAIRING      # shkruaj kodin
adb connect IP:PORTA             # porta nga ekrani kryesor i Wireless debugging
adb install app-debug.apk
adb shell pm grant com.privacyguard android.permission.DUMP
```
Hape app-in: te *Mënyra e punës* duhet të shkruajë **"E plotë (leje e përhershme)"**. Nëse shkruan "E kufizuar", Samsung-u e bllokon `dumpsys` nga app-et. Atëherë përdoret Mënyra B dhe s'ka nevojë për ndryshime.

## Konfigurimi në telefon (një herë)

1. **Usage access** → Privacy Guard → lejo
2. **Mënyra e plotë**: A ose B (shih më lart)
3. **Monitorimi** ndezur: është njoftimi i përhershëm i vogël
4. **VPN lokale** ndezur, nëse do të shohësh kujt i dërgojnë. Për rezultate më të mira: *Settings → Connections → More connection settings → Private DNS → Off*
5. Samsung: *Settings → Apps → Privacy Guard → Battery → Unrestricted*, që One UI të mos e ndalë natën

## Struktura

```
core/Parsers.kt      lexon dumpsys appops / audio / camera (i njëjti logjikë si scripti)
core/Analyzer.kt     rregullat e sinjalizimeve, përmbledhja ditore
core/Trackers.kt     lista e brokerëve dhe trackerave
collect/Shell.kt     mënyrat A/B/C dhe vetë-dhënia e lejeve
collect/Collector.kt mbledhja: historiku + statistikat e rrjetit
adb/                 lidhja ADB me vetveten dhe çiftimi me njoftim
monitor/             shërbimi: ekrani, mikrofoni, kamera, njoftimet e menjëhershme
vpn/                 VPN-ja lokale vetëm për DNS
report/PdfReport.kt  PDF-ja
work/Work.kt         mbledhja çdo 3 orë dhe raporti çdo natë
ui/MainActivity.kt   ekrani i app-it
```

## Kufizime

- Tregon **kur** dhe **sa**, jo përmbajtjen e asaj që u dëgjua ose u dërgua.
- Një app që do ta anashkalonte vetë regjistrin e Android-it nuk do të dukej.
- App-et me DNS të vetin të enkriptuar (DoH) nuk duken te "Kujt iu dërgua".
- Sinjalizimi do të thotë "kontrolloje", jo provë automatike përgjimi.
