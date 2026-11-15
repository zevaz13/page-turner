# M2 PDF viewer: line-by-line page turning (design)

Date: 2026-11-15. Status: approved by Sebastian ("approved for now; we will change things once it runs on the tablet").
Mockup: https://claude.ai/artifact/3k7arEMppHZq1jDF7TjrvK (private; Heller and Mendelssohn pages, tap / double tap / hold).

## Why
Sebastian plays piano from PDF scores on a Galaxy Tab S7 FE and turns pages with a foot-pressed Bluetooth remote (one key, Volume Up; see README). Whole-page turns at full-page zoom make the notes small, especially on dense scans. He wants the app to zoom so a line of music fills the screen width and to step through the score **line by line**, so the next music is always on screen at a readable size.

## Goals
1. Open a PDF from the tablet (document picker). Remember the last position per file.
2. Show the page at the width of its **content** (white side margins trimmed), with a small margin on each side, in both landscape and portrait.
3. Find the lines of music (systems) on each page automatically.
4. Remote gestures (thresholds from D14: long press 400 ms, double-tap window 250 ms):
   - **Tap = next**: jump so the first line that was not fully visible is at the top of the screen. After the last line of a page, go to the first line of the next page.
   - **Double tap = previous**: go back to the previous view.
   - **Hold = slide back**: after 400 ms the music slides down (the view moves toward the top of the page) until release. For when a jump overshot and cut off the top of a line. The corrected position is remembered for that spot in that file.
5. Each view starts just above a line of music with enough padding for notes outside the staff (ledger lines, dynamics, pedal marks), and shows only whole lines where possible.
6. Tap on the last view of the piece: a short animation saying there is no more music ("End of piece"). Nothing else changes.
7. Landscape and portrait both step through lines the same way; only how many lines fit differs.

## Non-goals (for M2)
- Annotations, setlists, night mode, half-page turns (M3).
- Bluetooth pairing or handling (the OS delivers the key).
- Horizontal panning or half-line views. Zoom stops at "content width = screen width".
- Accessibility services or global key capture (D4: foreground only).
- Score following, MIDI (later).

## Anti-goals (would make it a failure even if it works)
- A jump that lands mid-line or cuts off the top of the line you need, with no quick way to fix it.
- A page turn that feels laggy: the jump must appear immediately after the gesture is recognized (target: under 50 ms after the classifier fires; pages are rendered and analysed ahead of time).
- The volume changing or the volume bar showing while the reader is open.
- Losing the place: rotating the tablet, the screen sleeping, or reopening the file must come back to the same line.

## Decided by Sebastian
- Gestures: tap next, double tap previous, hold slides back (reverses an overshoot). Keep double tap (D14 thresholds).
- Jump to the next unseen line (line 4 after seeing 1-3), never repeat the last line, with padding so the whole line including notes outside the staff is visible.
- End-of-piece animation on the last line.
- Remember hold corrections if possible.
- Portrait should be zoomed more than full-page; trimming margins and stepping lines in portrait too was proposed and not objected to (confirm on the tablet).
- Expect to tune behaviour on the tablet after the first build.

## Claude's calls (check these)
- **Line detection by staff lines** (spike 2026-11-15 on the four `pdfExamples/` files): render the page in grayscale at about 1600 px wide; rows whose dark-pixel share exceeds a threshold are staff-line rows; five evenly spaced lines make a staff; consecutive staves joined by a column that is dark through the whole gap (barline or brace) form one system. Results: Mozart 6/6, Heller 5/5, Mendelssohn 8/8 (only at 1600 px), Silent Hill 4/4. Fixed thresholds were fragile (Heller failed at the looser setting), so the builder should make them adaptive (row-profile peaks relative to the page's own maximum, tolerance for slight scan skew) and test against all four files.
- **Viewing band per line**: from halfway into the gap above to halfway into the gap below; first line extends to the top of the content (so a title just above line 1 is included), last line to the bottom of the content.
- **Fallback when detection fails** on a page (no systems, or a system taller than the screen): step by a fixed fraction with overlap (Sebastian's original description: show 0-45%, then 40-85%, ...).
- **Previous** goes back exactly one view (a history stack within the session); at the start of the piece it does nothing visible except a small bump.
- **Jump animation**: quick slide of about 180 ms; turn on/off is a constant (Sebastian may prefer instant).
- **Hold slide speed**: about 10% of the visible height per second; constant at the top of the file.
- **Remembered data** (per file, keyed by the document URI): last position (page + line index + offset) and hold corrections (page + line index -> top offset). Stored as a small `java.util.Properties` file per score in app storage (plain text, testable on the JVM). Persist URI read permission so files reopen without the picker.
- **Rotation** keeps the same line at the top of the screen.
- **Remote status**: a small, unobtrusive indicator (connected / asleep), reusing KeyLab's input-device listener, because the waking press can be lost (D12).
- **Fullscreen (immersive)** and keep-screen-on while a score is open. Volume keys consumed as in KeyLab.
- **Project layout**: turn `keylab/` into one Gradle project `android/` with modules `:core` (pure Kotlin: gesture classifier, line detection on a grayscale buffer, stepping logic; JVM unit tests), `:keylab` (existing diagnostic app, unchanged app ID) and `:reader` (new app, ID `xyz.pageturner.reader`). Detection tests use small grayscale page fixtures rendered from `pdfExamples/` and committed; the PDFs themselves stay out of git.
- **Rendering**: Android's built-in `android.graphics.pdf.PdfRenderer`; render the current page and the next one ahead of time on a background thread, run detection once per page and cache the result for the file.

## Left to the builder
- Exact detection thresholds and how to handle skew.
- Bitmap resolution vs memory (the Heller scan pages are 1292x1760 pt).
- Visual design of the end-of-piece animation and the remote indicator, within "small, calm, readable from the piano bench".
- How the document picker screen looks (a simple recent-files list is enough).

## Open, to settle on the tablet
- Is the portrait size (about 80% of printed size with margins trimmed) big enough?
- Instant jump vs short slide.
- Hold slide speed.
- Whether the first press after the remote wakes up needs any handling beyond the status indicator.
