package xyz.pageturner.keylab

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

// Tunables. Override at launch without rebuilding, e.g.:
//   adb shell am start -n xyz.pageturner.keylab/.MainActivity --el longMs 800 --el doubleMs 350
private const val LONG_PRESS_MS = 400L   // foot taps 26-322 ms, deliberate holds >= 476 ms (2026-11-15)
private const val DOUBLE_TAP_MS = 250L   // foot double taps: second press 28-91 ms after release

private const val REMOTE_NAME = "Bluetooth remote RL7"
private const val REMOTE_SLEEP_MS = 300_000L      // measured 2026-11-15: disconnects 300 s after last release
private const val WAKE_KEY_TIMEOUT_MS = 2_000L    // reconnect with no key after this = waking press probably lost
private const val MAX_SCREEN_LINES = 300
private const val TAG = "KeyLab"

private const val CSV_HEADER =
    "kind,wall,uptime,action,key,eventTime,downTime,repeat,deviceId,device,source,deltaMs,holdMs,info"

class MainActivity : Activity(), InputManager.InputDeviceListener {
    private val handler = Handler(Looper.getMainLooper())
    private val wallFmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private lateinit var classifier: GestureClassifier
    private lateinit var inputManager: InputManager

    private lateinit var statusView: TextView
    private lateinit var pageView: TextView
    private lateinit var detailView: TextView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView

    private val csvRows = ArrayList<String>()          // whole session, for export
    private val screenLines = ArrayDeque<String>()
    private val deviceNames = HashMap<Int, String>()   // removed devices can no longer be looked up

    private var page = 1
    private var lastEventTime = -1L
    private var lastDownTime = -1L
    private var remoteId: Int? = null
    private var remoteConnectedAt = -1L                // uptime of last (re)connect
    private var remoteLastActivity = -1L               // uptime of last release (or connect); sleep timer base
    private var remoteDisconnectedWall: String? = null
    private var awaitingWakeKey = false

    private val tick = Runnable { emit(classifier.onTick(SystemClock.uptimeMillis())) }
    private val statusTicker = object : Runnable {
        override fun run() { updateStatus(); handler.postDelayed(this, 500) }
    }
    private val wakeKeyCheck = Runnable {
        if (awaitingWakeKey) {
            awaitingWakeKey = false
            record("NOTE", info = "remote connected but no key within ${WAKE_KEY_TIMEOUT_MS} ms: waking press probably LOST")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        classifier = GestureClassifier(
            longPressMs = intent.getLongExtra("longMs", LONG_PRESS_MS),
            doubleTapMs = intent.getLongExtra("doubleMs", DOUBLE_TAP_MS),
        )
        buildUi()
        inputManager = getSystemService(InputManager::class.java)
        inputManager.registerInputDeviceListener(this, handler)
        findRemote()
        record("NOTE", info = "start longMs=${classifier.longPressMs} doubleMs=${classifier.doubleTapMs} " +
            "remote=${if (remoteId != null) "connected" else "not connected"}")
        handler.post(statusTicker)
    }

    override fun onDestroy() {
        inputManager.unregisterInputDeviceListener(this)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // dispatchKeyEvent sees every key before views do, so logging here catches all of them.
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        logKey(e)
        val isVolume = e.keyCode == KeyEvent.KEYCODE_VOLUME_UP || e.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (!isVolume) return super.dispatchKeyEvent(e)

        if (e.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            // Framework-generated repeats (repeatCount > 0) must not count as new presses.
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                lastDownTime = e.downTime
                emit(classifier.onDown(e.eventTime))
            } else if (e.action == KeyEvent.ACTION_UP) {
                emit(classifier.onUp(e.eventTime))
            }
        }
        return true // consume: no volume change, no volume bar
    }

    private fun logKey(e: KeyEvent) {
        val delta = if (lastEventTime < 0) "" else (e.eventTime - lastEventTime).toString()
        lastEventTime = e.eventTime
        val hold = if (e.action == KeyEvent.ACTION_UP) (e.eventTime - e.downTime).toString() else ""
        val action = when (e.action) {
            KeyEvent.ACTION_DOWN -> "DOWN"; KeyEvent.ACTION_UP -> "UP"; else -> "A${e.action}"
        }
        val devName = e.device?.name ?: "virtual(${e.deviceId})"
        val isRemote = e.deviceId == remoteId
        if (isRemote && e.action == KeyEvent.ACTION_UP) remoteLastActivity = SystemClock.uptimeMillis()

        var info = ""
        if (isRemote && awaitingWakeKey && e.action == KeyEvent.ACTION_DOWN) {
            awaitingWakeKey = false
            handler.removeCallbacks(wakeKeyCheck)
            info = "first key ${e.eventTime - remoteConnectedAt} ms after connect"
        }
        record(
            "KEY", action = action, key = KeyEvent.keyCodeToString(e.keyCode).removePrefix("KEYCODE_"),
            eventTime = e.eventTime.toString(), downTime = e.downTime.toString(),
            repeat = e.repeatCount.toString(), deviceId = e.deviceId.toString(), device = devName,
            source = "0x%08x".format(e.source), delta = delta, hold = hold, info = info,
        )
    }

    private fun emit(gestures: List<Gesture>) {
        val now = SystemClock.uptimeMillis()
        for (g in gestures) {
            page = when (g) {
                Gesture.SHORT -> page + 1
                Gesture.LONG, Gesture.DOUBLE -> maxOf(1, page - 1)
            }
            val lag = if (lastDownTime >= 0) now - lastDownTime else -1
            pageView.text = "Page $page"
            detailView.text = "$g  (${if (g == Gesture.SHORT) "next" else "previous"}), fired $lag ms after press down"
            record("GESTURE", action = g.name, info = "page=$page lagFromDownMs=$lag")
        }
        handler.removeCallbacks(tick)
        classifier.nextDeadline()?.let { handler.postAtTime(tick, it) } // same clock as KeyEvent.eventTime
    }

    // --- Remote connection tracking (D13) ---

    private fun findRemote() {
        for (id in InputDevice.getDeviceIds()) {
            val d = InputDevice.getDevice(id) ?: continue
            deviceNames[id] = d.name
            if (d.name == REMOTE_NAME) {
                remoteId = id
                remoteConnectedAt = SystemClock.uptimeMillis()
                remoteLastActivity = remoteConnectedAt
            }
        }
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        val name = InputDevice.getDevice(deviceId)?.name ?: "?"
        deviceNames[deviceId] = name
        record("DEVICE", action = "added", deviceId = deviceId.toString(), device = name)
        if (name == REMOTE_NAME) {
            remoteId = deviceId
            remoteConnectedAt = SystemClock.uptimeMillis()
            remoteLastActivity = remoteConnectedAt
            remoteDisconnectedWall = null
            awaitingWakeKey = true
            handler.postDelayed(wakeKeyCheck, WAKE_KEY_TIMEOUT_MS)
        }
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        val name = deviceNames.remove(deviceId) ?: "?"
        record("DEVICE", action = "removed", deviceId = deviceId.toString(), device = name)
        if (deviceId == remoteId) {
            remoteId = null
            remoteDisconnectedWall = LocalTime.now().format(wallFmt).substring(0, 8)
            awaitingWakeKey = false
            handler.removeCallbacks(wakeKeyCheck)
        }
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        val name = InputDevice.getDevice(deviceId)?.name ?: "?"
        record("DEVICE", action = "changed", deviceId = deviceId.toString(), device = name)
    }

    private fun updateStatus() {
        if (remoteId != null) {
            val idle = SystemClock.uptimeMillis() - remoteLastActivity
            val left = (REMOTE_SLEEP_MS - idle).coerceAtLeast(0)
            statusView.text = "Remote: CONNECTED · idle ${mmss(idle)} · sleeps in about ${mmss(left)}"
            statusView.setBackgroundColor(if (left > 60_000) Color.rgb(27, 94, 32) else Color.rgb(230, 120, 0))
        } else {
            val since = remoteDisconnectedWall?.let { " since $it" } ?: ""
            statusView.text = "Remote: ASLEEP / not connected$since · tap once to wake (that tap may be lost)"
            statusView.setBackgroundColor(Color.rgb(183, 28, 28))
        }
    }

    private fun mmss(ms: Long): String = "%d:%02d".format(ms / 60_000, (ms / 1000) % 60)

    // --- Logging and export ---

    private fun record(
        kind: String, action: String = "", key: String = "", eventTime: String = "", downTime: String = "",
        repeat: String = "", deviceId: String = "", device: String = "", source: String = "",
        delta: String = "", hold: String = "", info: String = "",
    ) {
        val wall = LocalTime.now().format(wallFmt)
        val row = listOf(kind, wall, SystemClock.uptimeMillis().toString(), action, key, eventTime, downTime,
            repeat, deviceId, device, source, delta, hold, info).joinToString(",") { csvField(it) }
        csvRows.add(row)
        Log.i(TAG, row) // `adb logcat -s KeyLab -v raw` gives plain CSV rows

        val screen = buildString {
            append(wall).append(' ').append(kind).append(' ')
            if (action.isNotEmpty()) append(action).append(' ')
            if (key.isNotEmpty()) append(key).append(' ')
            if (repeat.isNotEmpty() && repeat != "0") append("rep=").append(repeat).append(' ')
            if (device.isNotEmpty()) append('[').append(device).append("] ")
            if (delta.isNotEmpty()) append("Δ=").append(delta).append(' ')
            if (hold.isNotEmpty()) append("hold=").append(hold).append(' ')
            append(info)
        }
        screenLines.addLast(screen)
        while (screenLines.size > MAX_SCREEN_LINES) screenLines.removeFirst()
        logView.text = screenLines.joinToString("\n")
        scroll.post { scroll.scrollTo(0, logView.height) }
    }

    private fun csvField(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    private fun export() {
        val name = "keylab-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".csv"
        val file = File(getExternalFilesDir(null), name)
        file.writeText(CSV_HEADER + "\n" + csvRows.joinToString("\n") + "\n")
        record("NOTE", info = "exported ${csvRows.size} rows to ${file.absolutePath}")
    }

    private fun clear() {
        csvRows.clear(); screenLines.clear()
        page = 1; lastEventTime = -1
        pageView.text = "Page 1"; detailView.text = ""
        record("NOTE", info = "cleared")
    }

    private fun buildUi() {
        statusView = TextView(this).apply {
            textSize = 20f; setTextColor(Color.WHITE); setPadding(32, 24, 32, 24)
        }
        pageView = TextView(this).apply {
            text = "Page 1"; textSize = 56f; typeface = Typeface.DEFAULT_BOLD; setPadding(32, 24, 32, 0)
        }
        detailView = TextView(this).apply {
            textSize = 20f; setPadding(32, 0, 32, 16)
            text = "long ≥ ${classifier.longPressMs} ms · double ≤ ${classifier.doubleTapMs} ms"
        }
        // Buttons are not focusable so a keyboard Enter or the remote never "clicks" them.
        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label; isFocusable = false; setOnClickListener { onClick() }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(24, 0, 24, 0)
            addView(button("Clear") { clear() })
            addView(button("Export CSV") { export() })
        }
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 13f; setPadding(32, 16, 32, 32)
        }
        scroll = ScrollView(this).apply { addView(logView); isFocusable = false }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusView)
            addView(pageView)
            addView(detailView)
            addView(buttons)
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        })
    }
}
