# Page-Turner: Workflows

How to set things up, run the tests, and (soon) build the app. Findings and decisions live in `README.md`.

## 1. Where things live
- **Computer:** `~/HobbyProjects/page-turner/`
  - `README.md`: findings, decisions, risks, open questions
  - `WORKFLOW.md`: this file
  - `key-tester.html`: web key tester (also hosted at https://claude.ai/artifact/NJr5mWcnbdLWyv6GeiZK3R). It cannot see volume keys, so it is only useful for remotes that send normal keys.
  - `test-logs/`: raw `getevent` captures
- **Claude Project "HobbyProjects":** mirror of README and this file, plus the main backlog (`claude/hobby-ideas-backlog.md`).
- **Convention:** every active project gets its own folder with a README (findings) and WORKFLOW (how to run things).

## 2. Tablet setup (once)
1. Settings > About tablet > Software information > tap **Build number** 7 times (enter PIN).
2. Settings > **Developer options** (bottom of the main list): switch it on.
3. Turn on **USB debugging**.
4. Plug into the computer with a data-capable cable. Tick "Always allow from this computer" and tap Allow.
5. Optional later: **Wireless debugging** (same screen) for cable-free installs and logs.

Samsung's **Auto Blocker** (Settings > Security and privacy) may block USB commands. Turn it off while developing.

## 3. Computer setup
- adb installed (Debian/Ubuntu/Mint: `sudo apt install adb`).
- Check: `adb devices` should list your tablet's serial followed by `device`.

| Symptom | Fix |
|---|---|
| `unauthorized` | Developer options > Revoke USB debugging authorizations, replug, accept the prompt |
| `no permissions` | Linux udev rule or `plugdev` group for the tablet's USB ID |
| Nothing listed | Different cable (many are charge-only) or port; check Auto Blocker |

## 4. Testing the remote with adb
The remote is the input device named **"Bluetooth remote RL7"**. Its `/dev/input/eventN` number can change after it disconnects and reconnects, so look it up each time.

**a) Find the device**
```
adb shell getevent -lp | grep -B1 "Bluetooth remote"
```
The `add device` line above the name has the path (it was `/dev/input/event14`).

**b) Capture presses with timestamps**
```
adb shell getevent -lt /dev/input/event14 | tee press.log
```
Leave the screen untouched. Press and release quickly a few times, then hold once.

**c) Idle wake-up test (still to do)**
```
adb shell getevent -lt | grep -E "add device|remove device|name:|KEY_"
```
Leave it running, do not touch the remote for about 5 minutes, then press the Android button once. Look for:
- `remove device` lines during the wait: the remote dropped the connection.
- `add device` lines after the press: it reconnected.
- `KEY_VOLUMEUP DOWN`/`UP` after the press: the first press got through. If not, a second press was needed.
- Note how long the press felt to take effect.

**d) Computing hold times from a saved log**
```
awk '/KEY_VOLUMEUP +DOWN/{t=$2+0} /KEY_VOLUMEUP +UP/{printf "hold %.0f ms\n",($2+0-t)*1000}' press.log
```

**How to read the output**
- Timestamps are kernel time in seconds since boot, when the tablet received the event. They do not include the physical press time, so they cannot show a wake-up delay by themselves.
- `MSC_SCAN 000c00e9` is HID Consumer usage Volume Increment (Volume Up).
- Hold time = UP timestamp minus DOWN timestamp. Hand taps measured 214-299 ms.
- Both remote buttons (Android, iOS) send identical events.

## 5. Planned build workflow (native Android app)
**Roles**
- This chat: planning, decisions, documentation.
- Claude Code in a terminal on the computer, in `~/HobbyProjects/page-turner/`: writes the Kotlin app, builds, installs to the tablet over adb, and reads `adb logcat` while the remote is pressed.
- Tablet: only developer options and the app. The Claude app on the tablet is not needed.

**Toolchain (decision pending)**
- Android Studio: Google lists 8 GB RAM and 8 GB free disk for Studio alone, 16 GB each with the emulator. Heavy.
- Lighter route: adb plus Android command-line tools, a JDK, Gradle and one SDK platform/build-tools package, driven from the terminal. No IDE, no emulator.
- Decide after `free -h` and `df -h ~` on the computer.

**Loop (to be confirmed once the project exists)**
1. Edit the code.
2. Build and install, for example `./gradlew installDebug`.
3. `adb logcat` filtered to the app's tag.
4. Press the remote, read the log, iterate.

**Simulating the remote without Bluetooth:** `adb shell input keyevent 24` injects Volume Up (KEYCODE_VOLUME_UP = 24), so page-turn logic can be tested from the computer. The real tablet is needed for Bluetooth and wake-up behavior.

**App behavior from the test results**
- Short press = next page.
- Previous page = double tap or long press (about 600-800 ms threshold). Foot presses still need measuring.
- A foreground activity receives KEYCODE_VOLUME_UP in `onKeyDown`/`onKeyUp` and returns true to consume it, so the volume bar does not appear.
- Check `repeatCount` so held keys do not skip pages.
- Handle the first press after idle (depends on the wake-up test).

## 6. Milestones
- **M0:** idle wake-up test and foot-press hold times (this file, section 4).
- **M1:** KeyLab: small native app that logs key events with timestamps and runs a wake-up latency test.
- **M2:** PDF viewer with next/previous from the remote, remembers last page.
- **M3:** polish (fit modes, half-page turns, setlists, night mode).
- **Later:** piano MIDI on the tablet (soft pedal turns pages, practice logging, visualizer); custom BLE pedal if the remote is not reliable enough.
