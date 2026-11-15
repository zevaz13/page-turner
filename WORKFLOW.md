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
5. **Wireless debugging** is the default way to connect (see section 2b). USB is the fallback.

Samsung's **Auto Blocker** (Settings > Security and privacy) may block USB commands. Turn it off while developing.

## 2b. Wireless debugging (default, verified 2026-11-15)
Tablet and computer must be on the same Wi-Fi network. Automatic network discovery (`adb mdns services`) found nothing on this network, so connect by address by hand.

**Pair (once per computer):** Developer options > Wireless debugging > **Pair device with pairing code**. Keep the dialog open and run:
```
adb pair <tablet-ip>:<pairing-port> <6-digit-code>
```
**Connect (each session):** the address on the main Wireless debugging screen ("IP address & Port") has a different port from the pairing one.
```
adb connect <tablet-ip>:<port>
adb devices            # should show <tablet-ip>:<port>  device
```
- The port changes when wireless debugging is toggled or the tablet reboots. Pairing is kept, so you only need a new `adb connect`.
- With more than one device attached, add `-s <tablet-ip>:<port>` to adb commands.
- Fallback with a fixed port: connect by USB once, run `adb tcpip 5555`, unplug, then `adb connect <tablet-ip>:5555`. This lasts until the next reboot.
- The tablet is on 5 GHz Wi-Fi (5220 MHz), so adb traffic does not share the 2.4 GHz band with the remote's Bluetooth.

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

**Checking whether a press reached Android** (ground truth; getevent can miss an event right after the device node appears):
```
adb shell dumpsys audio | grep adjustSuggestedStreamVolume
```
One `ADJUST_RAISE` line per delivered Volume Up, stamped with the tablet's clock (it ran about 0.4-0.6 s ahead of the computer on 2026-11-15). The remote disconnects after 5 minutes idle, so a wake-up trial needs a wait of more than 5 minutes after the last press.

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

**Toolchain (installed 2026-11-15)**
- JDK: `openjdk-21-jdk-headless` (apt).
- SDK root: `~/Android/Sdk`. No `ANDROID_HOME` and no PATH changes; Gradle finds the SDK through `sdk.dir` in each project's `local.properties` (not committed).
- SDK manager: `~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=$HOME/Android/Sdk --list_installed`. cmdline-tools 23 also ships a new `android` CLI (`android sdk` replaces `sdkmanager`); `sdkmanager` still works.
- Installed: `cmdline-tools;latest` (23.0), `platforms;android-34`. Build-tools are downloaded by the Android Gradle Plugin on the first build.

**Toolchain options considered (2026-11-13)**
- Android Studio: Google lists 8 GB RAM and 8 GB free disk for Studio alone, 16 GB each with the emulator. Heavy.
- Lighter route: adb plus Android command-line tools, a JDK, Gradle and one SDK platform/build-tools package, driven from the terminal. No IDE, no emulator.
- Decide after `free -h` and `df -h ~` on the computer.

**Loop (verified 2026-11-15, over wireless adb)**
```
cd ~/HobbyProjects/page-turner/keylab
ANDROID_SERIAL=<tablet-ip>:<port> ./gradlew installDebug      # build + install (~8 s once warm)
adb shell am start -n xyz.pageturner.keylab/.MainActivity      # launch
adb logcat -s KeyLab                                           # app log only (-c first to clear)
```
- `ANDROID_SERIAL` is only needed when more than one device is attached.
- `local.properties` (not committed) holds `sdk.dir=/home/<user>/Android/Sdk`. Create it on a fresh checkout.
- Versions: AGP 9.5.1, Gradle 9.9.1 (wrapper), JDK 21, compile/target/min SDK 34. The first build took 71 s and downloaded about 700 MB into `~/.gradle`.
- Known harmless warning: "Configuration.setVisible(boolean) ... deprecated" comes from AGP itself, not our build script.

**KeyLab (M1) usage**
- Screen: remote status bar (green = connected with sleep countdown, orange = under 1 min to sleep, red = asleep), simulated page number, last gesture and how late it fired, live event log.
- Gestures (Volume Up only): SHORT = next, LONG (held 700 ms or more, fires while held) or DOUBLE (second press within 400 ms of the first release) = previous. A SHORT is reported 400 ms late because of the double-tap window.
- Change thresholds without rebuilding: `adb shell am start -S -n xyz.pageturner.keylab/.MainActivity --el longMs 800 --el doubleMs 350`
- Live CSV: `adb logcat -s KeyLab -v raw`. Rows: `kind,wall,uptime,action,key,eventTime,downTime,repeat,deviceId,device,source,deltaMs,holdMs,info`; kinds are KEY, GESTURE, DEVICE, NOTE.
- If the remote reconnects and no key arrives within 2 s, a NOTE says the waking press was probably lost.
- Export: tap **Export CSV**, then `adb pull /sdcard/Android/data/xyz.pageturner.keylab/files/ .`
- `adb shell input keyevent --longpress 24` is not a real long press (its UP carries the DOWN time, hold 0), so test long presses with the remote.

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
