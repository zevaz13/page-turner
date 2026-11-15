# Page-turner (Claude Code context)

Read `README.md` (findings, decisions) and `WORKFLOW.md` (commands) first. Root conventions are in `../CLAUDE.md`.

## Goal
Hands-free page turns for PDF sheet music on a Samsung Android tablet, using a foot-pressed Bluetooth shutter remote, through my own native Android PDF reader app (Kotlin).

## Facts established (2026-11-13, raw data in `test-logs/`)
- The remote shows up as input device "Bluetooth remote RL7". It sends HID Consumer Volume Up: Linux `KEY_VOLUMEUP`, scan code `0x000c00e9`, Android `KEYCODE_VOLUME_UP` (24).
- It has two buttons (Android / iOS). They send identical events.
- Press and release arrive as separate events. Hand holds were 214-299 ms (one 505 ms). There is no kernel-level auto-repeat.
- A web page cannot receive volume keys, so the app must be native.
- The remote's input device node vanished once after idle and came back when pressed, so it probably disconnects when idle.
- **Unverified and the main risk:** whether the first press after idle is lost. Foot-press hold times are also unmeasured.

## Decisions
- Native Android in Kotlin.
- Short press = next page. Previous page = double tap or long press (threshold roughly 600-800 ms). To be validated with foot presses.
- The foreground activity consumes the volume key (`onKeyDown`/`onKeyUp` return true) so the volume UI does not appear. Use `repeatCount` to ignore repeats. Foreground-only design: no accessibility services or global key interception unless Sebastian decides otherwise.
- Build, install and log loop runs from this terminal: `adb install`, `adb logcat`. The Claude app on the tablet is not needed.
- Toolchain: prefer the light route (JDK, Android command-line tools, Gradle, one SDK platform and build-tools package). Android Studio only if Sebastian asks. Nothing is installed yet. Ask before installing and report download sizes first.
- Simulate the remote from the computer with `adb shell input keyevent 24`. The real tablet is needed for Bluetooth and wake-up behavior.

## First session checklist (in order)
1. `adb devices` should show the tablet as `device`.
2. Record tablet details in README: `adb shell getprop ro.product.model`, `ro.build.version.release`, `ro.build.version.sdk`. Use them to pick minSdk/targetSdk.
3. Check the machine: `free -h`, `df -h ~`, `java -version`, and any existing Android SDK (`$ANDROID_HOME`, `~/Android/Sdk`).
4. Propose a toolchain install plan with sizes and wait for approval.
5. Run the idle wake-up test with Sebastian (`WORKFLOW.md`, section 4c). You can run the `adb shell getevent -lt | grep -E "add device|remove device|name:|KEY_"` capture yourself in the background with a long timeout, tell him when about 5 minutes have passed so he presses the Android button once, then analyze the timestamps. Ask him how long the press felt to take effect.
6. Save the raw capture in `test-logs/` and record results, conclusions and the dated log entry in README.

## Milestone M1: KeyLab test app
Location: `keylab/` (Gradle project, Kotlin, a single Activity). Plain Android Views, minimal dependencies.

Features:
- Scrolling log of every `KeyEvent`: action (down/up), key name (`KeyEvent.keyCodeToString`), `eventTime`, `downTime`, `repeatCount`, device name, source, milliseconds since the previous event, and hold time on key-up.
- Consume `KEYCODE_VOLUME_UP` and `KEYCODE_VOLUME_DOWN` while in the foreground. Log all other keys and let them through.
- Mirror every event to logcat with tag `KeyLab` in a parseable CSV-like format.
- Live gesture classifier: short press, double tap (second press within a configurable window, start with 400 ms), long press (fires while held, configurable threshold, start with 700 ms). Constants at the top of the file.
- Wake-up latency mode: random idle of 5 s, 30 s, 2 min or 5 min, then a visual cue; measure cue to first key-down; mark LOST after a 4 s timeout. Show a results table.
- Keep the screen on during tests.
- Export the log and results as CSV to the app's external files directory so they can be pulled with `adb pull`.

Non-goals: PDF rendering, Bluetooth pairing or handling (the OS delivers the key).

Acceptance: builds with the Gradle command line, installs with adb, pressing the remote shows events, `adb shell input keyevent 24` shows a synthetic event, and logcat output is easy to parse.

## Later milestones
- **M2:** PDF viewer using the built-in `android.graphics.pdf.PdfRenderer`. Open a PDF through the document picker, render pages to bitmaps with fit-to-width, next/previous from the remote, remember the last page per file.
- **M3:** polish (half-page turns, setlists, night mode).
- **Later:** piano MIDI on the tablet (soft pedal turns pages, practice logging). A custom BLE pedal only if the remote proves unreliable.

## Project conventions
- Keep the code small and readable. Comment the why, not the what.
- When behavior is uncertain (Android version differences, Samsung quirks), test it on the tablet through adb instead of assuming, and write the result in README.
- Save measurements in `test-logs/` with device name and date in the filename.
