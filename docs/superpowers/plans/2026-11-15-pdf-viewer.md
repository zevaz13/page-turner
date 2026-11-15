# M2 PDF Viewer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A native Android reader that opens a PDF score and steps through it line by line with the foot remote (tap next, double tap previous, hold slide back).

**Architecture:** One Gradle project `android/` with three modules. `:core` is an Android library holding all logic as plain Kotlin with no Android types in its public API (gesture classifier, staff-line detection on a grayscale buffer, stepping, per-file memory), unit-tested on the JVM against page fixtures rendered from the example PDFs. `:keylab` is the existing diagnostic app, moved unchanged. `:reader` is the new app: document picker, `PdfRenderer` rendering, a custom View that draws the current page band, and key handling that feeds `:core`.

**Tech Stack:** Kotlin, AGP 9.5.1 (built-in Kotlin), Gradle 9.9.1 wrapper, JDK 21, `android.graphics.pdf.PdfRenderer`, JUnit 4.13.2, `javax.imageio` in tests only.

**Spec:** `docs/superpowers/specs/2026-11-15-pdf-viewer-design.md`

## Global Constraints

- `compileSdk = minSdk = targetSdk = 34` (one device: SM-T733, Android 14).
- No AndroidX or other runtime libraries; JUnit only as `testImplementation`.
- Reader app ID `xyz.pageturner.reader`; KeyLab keeps `xyz.pageturner.keylab`.
- Gesture thresholds: long press 400 ms, double-tap window 250 ms (D14).
- Volume Up and Volume Down are consumed while the reader is in the foreground; framework repeats (`repeatCount > 0`) are ignored (D4).
- Foreground only: no accessibility service, no global key capture.
- Remote device name `"Bluetooth remote RL7"`; it sleeps 300 s after the last release (README, 2026-11-15).
- Example PDFs stay out of git (`pdfExamples/` is ignored); tests use committed 1-bit PNG fixtures.
- Build and install over wireless adb: `ANDROID_SERIAL=<tablet-ip>:<port> ./gradlew :reader:installDebug` (WORKFLOW 2b, 5).

## Review Focus

1. A page where detection finds no systems (title-only page, text page, failed scan): the reader must still step through it (fallback 0-45%, 40-85%, ...) and never crash or stall. Tests: Task 2 `blankPageHasNoSystems`, Task 3 `emptyPageUsesFallbackSteps`.
2. One system taller than the visible height (portrait, or a very tall system): a tap must still move forward within it. Test: Task 3 `systemTallerThanScreenStepsWithinIt`.
3. Rotating the tablet mid-piece: the same line stays at the top. Test: Task 3 `changingVisibleHeightKeepsLineAtTop`.
4. Holding at the top of a page: the slide stops at the page top, it does not jump to the previous page. Test: Task 3 `slideBackClampsAtPageTop`.
5. Reopening a recent file that was moved or deleted, or whose permission is gone: show a short message and stay on the picker. Manual check: Task 5 step 7.

---

### Task 1: Multi-module project; move KeyLab and the classifier

**Files:**
- Move: `keylab/` -> `android/` (wrapper, `gradle.properties`, `settings.gradle.kts`, `local.properties` untracked)
- Create: `android/build.gradle.kts` (root: `plugins { id("com.android.application") version "9.5.1" apply false; id("com.android.library") version "9.5.1" apply false }`)
- Create: `android/core/build.gradle.kts` (`com.android.library`, namespace `xyz.pageturner.core`, `testImplementation("junit:junit:4.13.2")`)
- Move: `keylab/src/main/kotlin/.../GestureClassifier.kt` -> `android/core/src/main/kotlin/xyz/pageturner/core/GestureClassifier.kt` (package `xyz.pageturner.core`)
- Move: its test -> `android/core/src/test/kotlin/xyz/pageturner/core/GestureClassifierTest.kt`
- Move: KeyLab app -> `android/keylab/` (`build.gradle.kts` with `implementation(project(":core"))`; imports updated)
- Create: `android/reader/` skeleton (namespace and app ID `xyz.pageturner.reader`, label "Page Turner", one empty `ScoreActivity` launcher, `implementation(project(":core"))`)
- Modify: `android/.gitignore`, root `.gitignore` (add `android/.kotlin/`), `WORKFLOW.md` section 5 (paths become `android/`, commands `:keylab:installDebug`, `:reader:installDebug`), `CLAUDE.md` (KeyLab location)

**Interfaces:**
- Produces: `xyz.pageturner.core.GestureClassifier(longPressMs: Long, doubleTapMs: Long)` with `onDown(t: Long): List<Gesture>`, `onUp(t: Long): List<Gesture>`, `onTick(t: Long): List<Gesture>`, `nextDeadline(): Long?`; `enum class Gesture { SHORT, DOUBLE, LONG }`. Unchanged behaviour.

- [ ] **Step 1:** `git mv keylab android`, then create the module folders and move files as listed. Use `git mv` so history follows.
- [ ] **Step 2:** Run `cd android && ./gradlew :core:testDebugUnitTest`. Expected: 7 tests pass (`GestureClassifierTest`).
- [ ] **Step 3:** Run `./gradlew :keylab:installDebug :reader:installDebug`, launch both (`adb shell am start -n xyz.pageturner.keylab/.MainActivity`, `... xyz.pageturner.reader/.ScoreActivity`). Expected: KeyLab behaves as before (`adb shell input keyevent 24` gives a SHORT); the reader shows an empty screen titled "Page Turner".
- [ ] **Step 4:** Commit: `git commit -m "Restructure into android/ with :core, :keylab, :reader modules"`.

### Task 2: Staff-line system detection in `:core`

**Files:**
- Create: `tools/render-fixtures.sh` (renders fixtures with `pdftoppm -mono -png -scale-to-x 1600 -scale-to-y -1` from `pdfExamples/`)
- Create: `android/core/src/test/resources/pages/{mozart-p2,heller-p1,heller-p2,heller-p3,mendelssohn-p2,silenthill-p1}.png` (1-bit, 1600 px wide, about 40-150 KB each)
- Create: `android/core/src/main/kotlin/xyz/pageturner/core/GrayImage.kt`
- Create: `android/core/src/main/kotlin/xyz/pageturner/core/SystemDetector.kt`
- Test: `android/core/src/test/kotlin/xyz/pageturner/core/SystemDetectorTest.kt` (loads PNGs with `javax.imageio.ImageIO`, converts to `GrayImage`)

**Interfaces:**
- Produces:
  - `class GrayImage(val width: Int, val height: Int, val pixels: ByteArray)` (row-major, 0 = black, 255 = white, read as unsigned)
  - `data class PageAnalysis(val systems: List<IntRange>, val content: Rect)` where `IntRange` is the staff extent of one system in pixel rows (top staff line to bottom staff line), and `data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int)` is the bounding box of the page's ink (exclusive right/bottom)
  - `object SystemDetector { fun analyse(img: GrayImage): PageAnalysis }`

- [ ] **Step 1: Render fixtures.** Write `tools/render-fixtures.sh` (pages: Mozart p2, Heller p1-p3, Mendelssohn p2, Silent Hill p1) and run it. Commit the script and PNGs together with the test in step 5.
- [ ] **Step 2: Write the failing tests**

```kotlin
@Test fun systemCountsMatchTheScores() {
    val expected = mapOf("mozart-p2" to 6, "heller-p1" to 5, "heller-p2" to 5, "heller-p3" to 4,
                         "mendelssohn-p2" to 8, "silenthill-p1" to 4)
    for ((name, n) in expected) assertEquals(name, n, SystemDetector.analyse(load(name)).systems.size)
}
@Test fun hellerP2SystemPositions() {   // % of page height, from the 2026-11-15 spike, ±1.5
    val a = SystemDetector.analyse(load("heller-p2")); val h = load("heller-p2").height
    val want = listOf(8.4 to 18.6, 25.9 to 36.5, 44.6 to 54.2, 62.4 to 71.9, 80.2 to 89.7)
    a.systems.zip(want).forEach { (r, w) ->
        assertEquals(w.first, 100.0 * r.first / h, 1.5); assertEquals(w.second, 100.0 * r.last / h, 1.5) }
}
@Test fun hellerP1TitleIsAboveFirstSystemButInsideContent() {
    val a = SystemDetector.analyse(load("heller-p1"))
    assertTrue(a.content.top < a.systems.first().first - 0.05 * load("heller-p1").height)
}
@Test fun blankPageHasNoSystems() {
    val a = SystemDetector.analyse(GrayImage(800, 1100, ByteArray(800 * 1100) { -1 }))
    assertTrue(a.systems.isEmpty())
}
@Test fun systemsAreOrderedAndDisjoint() { /* for every fixture: systems[i].last < systems[i+1].first */ }
```

- [ ] **Step 3:** Run `./gradlew :core:testDebugUnitTest --tests '*SystemDetectorTest*'`. Expected: FAIL (unresolved `SystemDetector`).
- [ ] **Step 4: Implement `SystemDetector.analyse(img: GrayImage): PageAnalysis`.** Dark pixel = value < 140. Algorithm (from the spike, made adaptive):
  1. Row profile: dark-pixel count per row. Staff-line rows are local peaks above `0.5 * (95th-percentile row count)`, not a fixed share of width (fixed shares broke on Heller vs Mendelssohn).
  2. Merge adjacent peak rows (gap ≤ 1 px) into lines; group runs of 5 lines whose spacings differ by less than 60% of the smallest and are each under 3% of the page height into staves.
  3. Join consecutive staves into one system when at least 2 columns are dark through every row of the gap between them (barline or brace).
  4. `content`: the box of rows and columns whose dark count exceeds 2% of the maximum count (drops scanner specks and the "Powered by CamScanner" footer only if it is that faint; do not special-case it).
- [ ] **Step 5:** Run the tests. Expected: all 5 pass. Commit: `git commit -m "core: staff-line system detection with page fixtures"`.

### Task 3: Viewing bands and the stepper in `:core`

**Files:**
- Create: `android/core/src/main/kotlin/xyz/pageturner/core/Stepper.kt`
- Test: `android/core/src/test/kotlin/xyz/pageturner/core/StepperTest.kt`

**Interfaces:**
- Consumes: `PageAnalysis` (Task 2).
- Produces:
  - `data class Band(val top: Float, val bottom: Float)` (fractions of the content height, 0 = content top)
  - `fun viewingBands(a: PageAnalysis): List<Band>`: line i spans from halfway into the gap above (first line: content top, so titles are included) to halfway into the gap below (last line: content bottom)
  - `data class Position(val page: Int, val top: Float)`
  - `sealed interface Step { data class Moved(val pos: Position, val pageTurned: Boolean) : Step; object EndOfPiece : Step; object AtStart : Step }`
  - `class Stepper(val bands: (page: Int) -> List<Band>, val pageCount: Int, var visible: Float, val corrections: MutableMap<LineKey, Float> = mutableMapOf())` with `var pos: Position`, `fun next(): Step`, `fun previous(): Step`, `fun slideBack(delta: Float)`, `fun endSlide()` (stores the correction for the line now at the top), `fun lineAtTop(): Int`, `fun setVisible(v: Float)` (keeps `lineAtTop()` at the top)
  - `data class LineKey(val page: Int, val line: Int)`
  - Constant `FALLBACK_OVERLAP = 0.05f` (fraction of content height)
- `visible` is the fraction of the content height that fits on screen; the reader computes it.

- [ ] **Step 1: Write the failing tests** (bands built by hand; `visible` chosen per test)

```kotlin
// Two pages with 5 lines each: bands [0,.2),[.2,.4),[.4,.6),[.6,.8),[.8,1]
@Test fun nextJumpsToFirstLineNotFullyVisible()      // visible .5, pos top 0 -> next top .4
@Test fun lastViewOfPageTurnsToNextPageFirstLine()    // pos top .8 -> Moved(Position(1, 0f), pageTurned = true)
@Test fun tapOnLastViewOfPieceIsEndOfPiece()          // page 1, top .8 -> EndOfPiece, pos unchanged
@Test fun previousReturnsToExactPriorView()           // next, next, previous -> pos equals after first next
@Test fun previousAtStartIsAtStart()                  // fresh stepper -> AtStart
@Test fun emptyPageUsesFallbackSteps()                // bands empty, visible .45 -> tops 0, .40, .80, then page turn
@Test fun systemTallerThanScreenStepsWithinIt()       // one band [0,1], visible .3 -> top advances by .3 - FALLBACK_OVERLAP
@Test fun changingVisibleHeightKeepsLineAtTop()       // at line 2, setVisible(.8) -> lineAtTop() still 2
@Test fun slideBackClampsAtPageTop()                  // top .1, slideBack(.5) -> top 0, page unchanged
@Test fun endSlideRemembersCorrection()               // land on line 3, slideBack(.05), endSlide(); later next() onto line 3 lands at .55 not .6
```

- [ ] **Step 2:** Run `./gradlew :core:testDebugUnitTest --tests '*StepperTest*'`. Expected: FAIL (unresolved `Stepper`).
- [ ] **Step 3: Implement `Stepper` and `viewingBands`.** `next()`: find the first band whose bottom is below `top + visible`; if its top is below the current top (a single band taller than the screen) or there are no bands, advance by `visible - FALLBACK_OVERLAP`; if all bands are visible, turn the page; on the last page, `EndOfPiece`. Landing top for line i = `corrections[LineKey(page, i)] ?: bands[i].top`, capped at `1 - visible` when the rest of the page is shorter than the screen. `previous()` pops a history stack of positions pushed by `next()` and by the start of a slide.
- [ ] **Step 4:** Run the tests. Expected: all 10 pass. Commit: `git commit -m "core: viewing bands and line stepper"`.

### Task 4: Per-file memory in `:core`

**Files:**
- Create: `android/core/src/main/kotlin/xyz/pageturner/core/ScoreMemory.kt`
- Test: `android/core/src/test/kotlin/xyz/pageturner/core/ScoreMemoryTest.kt`

**Interfaces:**
- Consumes: `Position`, `LineKey` (Task 3).
- Produces: `class ScoreMemory(var last: Position?, val corrections: MutableMap<LineKey, Float>)` with `fun save(out: java.io.OutputStream)` and `companion fun load(input: java.io.InputStream?): ScoreMemory` (null or corrupt input gives an empty memory). Format: `java.util.Properties` (`last=page,top`, `corr.<page>.<line>=top`); `org.json` is avoided because it is a stub in JVM tests.

- [ ] **Step 1: Write the failing tests:** `roundTripKeepsLastPositionAndCorrections`, `nullInputGivesEmptyMemory`, `corruptInputGivesEmptyMemory` (input `"last=abc\ncorr.x.y=1"`).
- [ ] **Step 2:** Run `./gradlew :core:testDebugUnitTest --tests '*ScoreMemoryTest*'`. Expected: FAIL.
- [ ] **Step 3:** Implement `ScoreMemory`.
- [ ] **Step 4:** Run the tests. Expected: 3 pass. Commit: `git commit -m "core: per-file score memory"`.

### Task 5: Reader: open a PDF and turn whole pages (first tablet milestone)

**Files:**
- Create: `android/reader/src/main/kotlin/xyz/pageturner/reader/ScoreActivity.kt` (key handling, menu-less fullscreen)
- Create: `android/reader/src/main/kotlin/xyz/pageturner/reader/PageRenderer.kt` (wraps `PdfRenderer`; renders a page to a grayscale `GrayImage` for analysis and an ARGB `Bitmap` cropped to the content box for display; one background `HandlerThread`; caches current and next page)
- Create: `android/reader/src/main/kotlin/xyz/pageturner/reader/ScoreView.kt` (draws the cropped page bitmap scaled to view width minus 2% margin each side, translated by the current top)
- Create: `android/reader/src/main/kotlin/xyz/pageturner/reader/Recents.kt` (recent URIs in `SharedPreferences`, max 10)
- Modify: `android/reader/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `GestureClassifier`, `SystemDetector`, `GrayImage`, `PageAnalysis` (Tasks 1-2).
- Produces: `PageRenderer.request(page: Int, widthPx: Int, done: (RenderedPage) -> Unit)` with `data class RenderedPage(val page: Int, val bitmap: Bitmap, val analysis: PageAnalysis)` (analysis in content-box coordinates); `ScoreView.show(page: RenderedPage, top: Float, animate: Boolean)`.

- [ ] **Step 1:** On start without a file: a plain list of recent files plus an "Open PDF" button (`ACTION_OPEN_DOCUMENT`, type `application/pdf`, `takePersistableUriPermission` for read).
- [ ] **Step 2:** Opening a file renders page 1 cropped to its content box and fit to width. `SHORT` -> next page, `DOUBLE` -> previous page (whole pages for now). Volume keys consumed; `FLAG_KEEP_SCREEN_ON`; immersive fullscreen.
- [ ] **Step 3:** Build and install: `ANDROID_SERIAL=<tablet-ip>:<port> ./gradlew :reader:installDebug`. Copy the examples: `adb push pdfExamples/*.pdf /sdcard/Download/`.
- [ ] **Step 4:** On the tablet: open each example; `adb shell input keyevent 24` turns pages; the foot remote turns pages; no volume bar appears. Expected: all four files open and turn.
- [ ] **Step 5:** Log per page render + analysis time with tag `Reader` (`adb logcat -s Reader`). Expected: under 500 ms per page for Heller (largest scan); if slower, note it in README and continue (pages are prefetched).
- [ ] **Step 6:** Commit: `git commit -m "reader: open PDFs, content-width pages, whole-page turns"`.
- [ ] **Step 7 (Review Focus 5):** Delete a recently opened file from Downloads (`adb shell rm /sdcard/Download/<file>`), tap it in the recent list. Expected: a short message ("This file is no longer available") and the list stays; no crash in `adb logcat`.
- [ ] **Step 8:** Ask Sebastian to try it on the tablet; record anything he wants changed in PLAN.md open questions.

### Task 6: Reader: line stepping, slide back, memory

**Files:**
- Modify: `ScoreActivity.kt`, `ScoreView.kt`
- Create: `android/reader/src/main/kotlin/xyz/pageturner/reader/MemoryStore.kt` (one `ScoreMemory` file per score in `filesDir/memory/<sha1 of URI>.properties`)

**Interfaces:**
- Consumes: `Stepper`, `Step`, `viewingBands`, `ScoreMemory` (Tasks 3-4); `PageRenderer`, `ScoreView` (Task 5).

- [ ] **Step 1:** Replace whole-page turns with `Stepper`: `visible = viewHeight / (viewWidthAfterMargins * contentAspect)` per page; `SHORT` -> `next()`, `DOUBLE` -> `previous()`. Jump animation 180 ms (`JUMP_ANIM_MS = 180`, 0 disables) only within a page; page turns are instant.
- [ ] **Step 2:** Hold: on `LONG`, start a frame callback that calls `slideBack(HOLD_SPEED * visible * dtSeconds)` with `HOLD_SPEED = 0.10f`; on the Volume Up `ACTION_UP` that follows, `endSlide()`.
- [ ] **Step 3:** Debug overlay: `SHOW_BANDS = false` constant; when true, draw the bands as translucent boxes (for tuning on the tablet).
- [ ] **Step 4:** Persist: save `ScoreMemory` (last position, corrections) in `onPause`; restore on open. Rotation: `configChanges="orientation|screenSize"` and call `setVisible()` with the new geometry.
- [ ] **Step 5:** Build, install, and check on the tablet with Heller and Mendelssohn in landscape and portrait: taps land on whole lines; double tap returns; holding slides back and the correction is used next time; rotating keeps the line; reopening returns to the last line.
- [ ] **Step 6:** Commit: `git commit -m "reader: line stepping, slide back, per-file memory"`.

### Task 7: End of piece, remote indicator, docs

**Files:**
- Modify: `ScoreActivity.kt`, `ScoreView.kt`
- Modify: `README.md` (results and log), `PLAN.md` (tick M2, open questions), `WORKFLOW.md` (reader commands)

- [ ] **Step 1:** On `Step.EndOfPiece`: a pill "End of piece" slides up from the bottom and fades after 1.6 s, with a small bump of the page (respect the system "remove animations" setting). On `Step.AtStart`: the bump only.
- [ ] **Step 2:** Remote indicator: a small dot in a top corner, green when `"Bluetooth remote RL7"` is connected, red when not (KeyLab's `InputDeviceListener` logic). No countdown.
- [ ] **Step 3:** Build, install, verify on the tablet: last line of Silent Hill (2 pages) shows the animation; turning the remote off turns the dot red.
- [ ] **Step 4:** Update README (dated results: detection accuracy on the four files, render times), PLAN (tick M2 items, list what Sebastian wants changed), WORKFLOW (reader build/install/log commands). Commit: `git commit -m "reader: end-of-piece animation, remote indicator; docs"`.
