# Page-Turner: Plan

Where we discuss decisions and tick off milestones. Measurements and findings stay in `README.md`; commands stay in `WORKFLOW.md`. Dates are YYYY-MM-DD.

## Milestones

- [x] **M-1 Feasibility** (2026-11-13): the remote sends Volume Up with separate press and release, two identical buttons, no kernel auto-repeat. Web route ruled out.
- [ ] **M0 Setup and measurements**
  - [x] Git repository (2026-11-15), pushed to private GitHub repo `zevaz13/page-turner`
  - [x] Wireless adb connected (2026-11-15)
  - [x] Tablet details recorded (2026-11-15): SM-T733, Android 14, SDK 34
  - [x] Toolchain plan approved (2026-11-15)
  - [x] JDK 21 + Android cmdline-tools 23.0 + `platforms;android-34` installed (2026-11-15, SDK dir 313 MB)
  - [x] Gradle wrapper and "hello" APK installed on the tablet (2026-11-15): AGP 9.5.1, Gradle 9.9.1, build-tools 36.0.0 (fetched by AGP). First build 71 s, install over Wi-Fi 8 s.
  - [x] Idle wake-up test, first pass (2026-11-15): 5-min idle timeout; waking press lost in 1 of 2 trials
  - [ ] Waking-press loss rate: 5-10 more trials
  - [ ] Foot-press hold times measured
- [ ] **M1 KeyLab**: key logger, gesture classifier, wake-up latency mode, CSV export (spec in `CLAUDE.md`).
- [ ] **M2 PDF viewer**: `PdfRenderer`, document picker, fit-to-width, remote turns, last page remembered per file.
- [ ] **M3 Polish**: half-page turns, setlists, night mode.
- [ ] **Later**: piano MIDI (soft pedal turns pages, practice log); custom BLE pedal only if the remote proves unreliable.

## First steps (M0), in order, and why

1. **Connect the tablet**: done 2026-11-15 over wireless debugging (WORKFLOW 2b).
   Why: every later step (tablet details, wake-up test, installs) goes through adb.
2. **Record tablet details** (`getprop ro.product.model`, `ro.build.version.release`, `ro.build.version.sdk`).
   Why: the SDK level sets `targetSdk`/`compileSdk` and tells us which SDK platform to download. `minSdk` can simply be the tablet's own level, since this app is only for one device.
   Result: SDK 34, so `minSdk = targetSdk = compileSdk = 34` and the SDK package is `platforms;android-34`.
3. **Run the idle wake-up test** (WORKFLOW 4c). It needs only adb.
   Why: this is the make-or-break risk. If the waking press is lost, the gesture design changes before any code is written (for example a "wake" tap at the end of each page, or moving the custom pedal earlier). Running it before the toolchain install means a bad result costs no download time.
4. **Toolchain plan, then install after approval.**
   Why: Gradle, the Android Gradle Plugin and the SDK have to match each other, and JDK 17 is the safe baseline for current AGP versions. The terminal-only route avoids Android Studio's weight.
5. **Hello APK**: an empty Activity, `./gradlew installDebug`, launched on the tablet.
   Why: this proves the build, install and logcat loop works before KeyLab adds its own problems. It becomes the `keylab/` skeleton.
6. **Foot-press hold times**, measured with getevent now or with KeyLab later.
   Why: the long-press threshold (600-800 ms by hand) has to sit clearly above the foot taps.

## Machine check (2026-11-15)

| Item | Value | Note |
|---|---|---|
| OS | Linux Mint 22.3 | apt has `openjdk-17-jdk-headless` |
| RAM | 15 GiB, about 7 GiB available | Enough for a Gradle daemon; no emulator planned |
| Disk | 141 GB free | No constraint |
| CPU | 8 threads | |
| Java | OpenJDK 21.0.13 (apt) | |
| Android SDK | `~/Android/Sdk` (no `$ANDROID_HOME`, by choice) | |
| adb | 34.0.4 (apt) | Wireless pairing works; mDNS discovery does not |
| git | 2.43, user configured | |

## Toolchain (installed 2026-11-15)

| Part | How | Approx. size |
|---|---|---|
| JDK 21 headless | `sudo apt install openjdk-21-jdk-headless` | 130 MB download, 293 MB installed |
| Android command-line tools 23.0 | Zip from dl.google.com, SHA-1 checked, into `~/Android/Sdk/cmdline-tools/latest` | 181 MB |
| SDK `platforms;android-34` | `sdkmanager` | 63 MB; build-tools left to the Android Gradle Plugin to fetch |
| platform-tools | Skip; reuse the apt adb unless versions clash | 0 |
| Gradle 9.9.1 (wrapper) + AGP 9.5.1 + Kotlin dependency cache | First build downloads them into `~/.gradle` | 692 MB in `~/.gradle`; build-tools 36.0.0 added to the SDK (SDK now 481 MB) |
| **Total** | | **~1.5 GB on disk** |

Android Studio and the emulator are not planned.

## Information needed from Sebastian

1. ~~Tablet connected?~~ Done over Wi-Fi.
2. ~~Approval to install~~ Done; SDK license accepted 2026-11-15.
3. **Time for the wake-up test**: about 10 minutes with the remote, ideally done with your foot.
4. ~~App ID~~: `xyz.pageturner.keylab` (2026-11-15).
5. **Your sheet music PDFs**: mostly scanned (images) or engraved (vector, e.g. from MuseScore)? Rough page count and file size? This affects render speed and caching in M2, not M1.
6. ~~Git remote~~: private GitHub repo, SSH remote `origin` (2026-11-15).

## Decision log

| # | Date | Decision | Reason | Status |
|---|---|---|---|---|
| D1 | 2026-11-12 | Use the existing Bluetooth shutter remote as a foot button | No new hardware; already pairs | Done |
| D2 | 2026-11-13 | Native Android app in Kotlin | Web pages never receive Volume Up | Done |
| D3 | 2026-11-13 | Short press = next page; previous page = double tap or long press | Both buttons send the same key | Thresholds to validate with foot presses |
| D4 | 2026-11-13 | Foreground-only key handling (consume in `onKeyDown`/`onKeyUp`, ignore `repeatCount > 0`) | Simple, no accessibility permissions | Done |
| D5 | 2026-11-13 | Terminal toolchain (JDK, cmdline-tools, Gradle), no Android Studio | Lighter; the adb loop is enough | Installed 2026-11-15; JDK 21 instead of 17 (both LTS; newest cmdline-tools may need newer Java) |
| D6 | 2026-11-15 | Git repo in `page-turner/`; small commits; build outputs and `local.properties` ignored | Track code and docs history | Done; remote is a private GitHub repo (SSH) |
| D7 | 2026-11-15 | Run the wake-up test before the toolchain install | Its result may change the design | Proposed |
| D9 | 2026-11-15 | No `~/.bashrc` changes; the SDK path goes in `local.properties` (not committed) and tools are called by full path | No system or shell config changes | Done |
| D10 | 2026-11-15 | AGP 9.5.1 / Gradle 9.9.1 (current), single-module project, no AndroidX, Kotlin compiled by AGP's built-in support | Newest stable; fewest moving parts | Done |
| D11 | 2026-11-15 | App ID `xyz.pageturner.keylab`, `minSdk = targetSdk = compileSdk = 34` | One device, Android 14 | Done |
| D8 | 2026-11-15 | Wireless debugging is the default adb connection; USB is the fallback | Few USB ports; the tablet stays free on the music stand | Trial: log drops and port changes |

## Open questions

- Waking press: lost in 1 of 2 trials (2026-11-15). What is the rate, and does it depend on sleep length or hold time? Design options if it is frequent: (a) a deliberate wake tap after a pause of more than 5 min, with the app showing whether the remote is connected; (b) accept it and press again when the page does not turn; (c) a custom pedal (upgrade path). The app can show connection status, because Android reports input devices being added and removed (`InputManager.InputDeviceListener`).
- Double tap or long press for "previous"? A double tap delays every "next" by the double-tap window (~400 ms) unless "next" fires on the first press and a second press reverses it. Decide after KeyLab shows real foot timings.
- How stable is wireless adb? (Drops, port changes after sleep.)
