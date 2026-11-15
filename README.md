# Page-Turner (hands-free sheet music on the Samsung tablet)

Status: ACTIVE. Started 2026-11-12. Part of the HobbyProjects backlog (see `hobby-ideas-backlog.md` in the Claude Project).

## Goal
Turn pages of PDF sheet music on a Samsung Android tablet without using hands, while playing a digital piano. Eventually with my own PDF reader app that handles the Bluetooth input itself.

## Decision (2026-11-12): the "click" solution
Use the Bluetooth camera-shutter remote I already own as a foot-pressed page-turn button.
- Why: zero extra hardware, no soldering, already pairs with the tablet, can be pressed with the foot.
- Custom BLE pedal (ESP32 / nRF52840) is kept as a later upgrade, not the starting point.

## What we know / assume
- Most Bluetooth camera shutters are BLE HID devices that send one key press (often Volume Up or Enter; varies by model). To the tablet it looks like a keyboard key. **Verified 2026-11-13 for my remote: it sends Volume Up.**
- Native Android apps receive any key code via key-down/key-up events. A web page usually does NOT receive Volume keys (the system handles them first) but can receive Enter/arrow keys. So the key it sends affects the app choice (native vs. web).
- Existing reader apps (e.g. MobileSheets) support Bluetooth page-turn devices and MIDI-to-action mapping, but one forum thread reports a DIY ESP32 pedal not being recognized in its pedal settings. If I write my own app this no longer matters.

## Test checklist (do first, ~10 minutes)
1. Which key does it send? (key-tester app on the tablet; nRF Connect shows the HID report map).
2. Does it send key-down and key-up separately? If yes, short vs. long press is detectable (next vs. previous from one button).
3. Wake-up latency: press after several minutes idle. Is the first press slow or lost? This is the make-or-break test for use mid-piece.
4. One button or two?
5. Range and battery (coin cell) behavior; does it auto-disconnect?
6. Ergonomics: can I press it reliably with a foot while using the sustain pedal? (Small remote on the floor may slide.)

Helper (built 2026-11-12): `key-tester.html` in this folder, also hosted at https://claude.ai/artifact/NJr5mWcnbdLWyv6GeiZK3R (open it in Chrome on the tablet). It shows key / code / keyCode, press and release timing, auto-repeat, screen-tap events, an event log, and a wake-up latency test (5 s / 30 s / 2 min / 5 min idle, then press when the screen turns teal; the 5 s run is the baseline). The "Copy summary" button gives a text block to paste back into the chat for the notes. Caveat: the test measures reaction time plus the remote's wake-up delay, so differences under about 100 ms are noise; repeat each length 3 times.

## Test results
- 2026-11-13: remote sends **Volume Up**. The web tester shows nothing: expected, not a bug.
- 2026-11-13, `adb shell getevent -lt` (raw log in `test-logs/getevent-remote-rl7-2026-11-13.txt`): the remote appears as input device **"Bluetooth remote RL7"** and declares KEY_MUTE, KEY_VOLUMEDOWN, KEY_VOLUMEUP, KEY_POWER. The button(s) send HID Consumer usage 0x0C/0xE9 (Volume Up).
  - The remote has two physical buttons, labeled "Android" and "iOS". A labeled capture (`test-logs/getevent-remote-rl7-buttons-2026-11-13.txt`: Android button, then iOS button) confirmed both produce identical events: KEY_VOLUMEUP, MSC_SCAN 000c00e9, holds of 266 ms and 241 ms. So there is no separate next/previous button. (The earlier 5-press capture is unlabeled.)
  - Press and release arrive as separate events at the real press and release times. Hold times (UP minus DOWN): 214, 218, 299, 4208 (deliberate long hold), 505 ms.
  - No extra DOWN events during the 4.2 s hold: no kernel-level auto-repeat. (Android may still synthesize repeats in the framework; the app should check repeatCount.)
  - Conclusion: long-press detection is feasible (short taps were ~200-300 ms by hand; a threshold of roughly 600-800 ms would separate them; foot presses still to be measured).
  - One-time observation: the input device node `/dev/input/event14` did not exist after the remote had been idle ("No such file") and reappeared at the same path after pressing the button. Suggests the remote disconnects when idle and reconnects on press. Not yet confirmed with add/remove device lines or a lost-press test.
- Still to measure: foot-press hold times, and the idle wake-up behavior (is the press that wakes the remote lost?). Options: `adb shell getevent -lt | grep -E "add device|remove device|name:|KEY_"` across a 2-3 minute idle gap, an existing reader app, or the native KeyLab app.

## Risks
- Sleep/wake latency may drop the first press. Early evidence: the remote's input device vanished while idle (see Test results).
- Both buttons send the same Volume Up, so "previous page" must come from a long press or double tap (or a screen tap). A second identical remote would not help.
- Remote is not designed for foot use: may slide or get stepped on. Fix: velcro it to a board or wedge; later a 3D-printed housing.
- Confirmed: the system intercepts Volume Up before web pages see it, so a web app cannot use this remote.
- Some remotes need a long hold before the reader app reacts (reported by a MobileSheets forum user with other remotes); check press duration needed.

## App plan (my own PDF reader) - decided 2026-11-13: native Android
Open choice: web app (PDF.js, runs in Chrome, fastest to iterate, needs HTTPS, volume keys unavailable) vs. native Android (Kotlin; built-in PdfRenderer; robust key and Bluetooth handling; Java background helps) vs. Flutter.
Decision (2026-11-13): the remote sends Volume Up and the web key tester registered nothing (Android handles volume keys before web pages see them), so the web-app route is out. Go native Android (Kotlin). A foreground activity can receive KEYCODE_VOLUME_UP in onKeyDown and consume it so the volume bar does not appear. First step: a small native "KeyLab" test app (key log + wake-up latency test) that grows into the reader. Needs Android Studio (or Gradle + adb) on the Linux machine.

MVP features:
- Open a PDF from a list (local storage)
- Fit-to-width and half-page turns
- Next / previous page from the remote (and tap)
- Remember last page per PDF
- Later: setlists, annotations, night mode, tempo/practice logging

Stretch (ties into other backlog projects):
- Receive piano MIDI on the tablet (Android MIDI API / Web MIDI): soft pedal turns pages, practice journal knows which piece and for how long, on-tablet visualizer.
- Score following (DTW) for automatic page turns, needs MusicXML/MIDI of the piece.

## Upgrade path (if the click solution is not enough)
1. Open the remote and wire a footswitch across the button contacts via a 3.5 mm jack (soldering at work).
2. Custom BLE pedal: Seeed XIAO nRF52840 (LiPo charging built in, very low sleep power) or ESP32-C3. Two pedals, long-press events, battery level, LED feedback from the app. Use a custom GATT service if my app handles the connection, or BLE HID keys for compatibility with other apps. Teensy 4.x has no Bluetooth.
3. nRF Connect app is the debugging tool for any BLE pedal before the app exists.

## Parts / cost
- Now: $0 (existing remote, existing tablet).
- Optional: velcro/board for ergonomics, key-tester page (free), basic soldering iron for home (~$30, Pinecil-class).
- Upgrade: BLE dev board $10-25, footswitches $5-15 each, enclosure.

## Open questions
- Resolved: two buttons (Android / iOS), both send Volume Up. Previous page = long press or double tap.
- Is Android Studio / adb set up on the Linux machine?
- Which reader app is on the tablet now (needed only for comparison/fallback)?
- Piano brand/model and MIDI connection (for the MIDI stretch goals)?

## Sources
- MobileSheets features (Bluetooth/USB page turns, MIDI over USB/Bluetooth/WiFi): https://apps.apple.com/app/1620569987
- MobileSheets + Roland pedals via MIDI: https://zubersoft.com/mobilesheets/forum/thread-9439.html
- MobileSheets + DIY ESP32 pedal question: https://zubersoft.com/mobilesheets/forum/post-45729.html
- ESP32 BLE page turner (Hackaday): https://hackaday.com/2021/05/27/this-esp32-bluetooth-page-turner-cant-get-any-easier
- luebbe/ble-page-turner: https://github.com/luebbe/ble-page-turner
- Soundhouse DIY page-turning pedal guide: https://www.soundhouse.co.jp/en/contents/column/index?post=4114

## Log
- 2026-11-13: Labeled capture confirms the two buttons (Android / iOS) are identical: both Volume Up. adb installed and tablet authorized. getevent shows separate press/release with ~200-500 ms hand holds, no kernel auto-repeat; remote seems to disconnect when idle. Next: idle/lost-press test, other buttons.
- 2026-11-13: Remote sends Volume Up; web tester sees nothing; decided on a native Android app. Next: measure timing/latency (adb getevent, existing reader, or KeyLab app).
- 2026-11-12: Built the key tester (`key-tester.html`, headless-browser checked on desktop; not yet run on the tablet with the real remote).
- 2026-11-12: Brainstormed pedal options (ESP32/nRF52840 BLE HID vs. custom GATT + own app). User has a Bluetooth camera-shutter remote that pairs with the tablet; chose the "click" solution with foot press, no extra hardware. Next: run the test checklist, then decide native vs. web app.
