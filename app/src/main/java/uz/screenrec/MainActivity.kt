package uz.screenrec

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {

    private class Preset(val label: String, val shortSide: Int, val fps: Int, val bitrate: Int)

    private val presets = listOf(
        Preset("Yengil  ·  720p  ·  30 fps", 720, 30, 4_000_000),
        Preset("Maxsus  ·  620p  ·  50 fps", 620, 50, 7_000_000),
        Preset("Muvozanat  ·  720p  ·  60 fps", 720, 60, 8_000_000),
        Preset("Yuqori  ·  1080p  ·  60 fps", 1080, 60, 12_000_000)
    )
    private var selected = 1  // 1 = 620p 50 fps
    private var mic = false
    private var game = false
    private var landscape = true

    private val REQ_PROJ = 100
    private val REQ_PERM = 101
    private val REQ_PERM_GAME = 103

    private lateinit var timerText: TextView
    private lateinit var startBtn: Button
    private lateinit var micBtn: Button
    private lateinit var gameBtn: Button
    private lateinit var orientBtn: Button
    private val presetBtns = mutableListOf<Button>()
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_PERM + 1)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E1116"))
            setPadding(dp(24), dp(48), dp(24), dp(24))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        root.addView(TextView(this).apply {
            text = "EKRAN YOZISH"
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F5B301"))
            gravity = Gravity.CENTER
        })
        timerText = TextView(this).apply {
            text = "00:00"
            textSize = 48f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(24))
        }
        root.addView(timerText)
        root.addView(TextView(this).apply {
            text = "Yozishni to'xtatish: telefonni 3 marta qattiq silkiting"
            textSize = 13f
            setTextColor(Color.parseColor("#9AA4B2"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(20))
        })

        presets.forEachIndexed { i, p ->
            val b = button(p.label) { selected = i; refresh() }
            presetBtns.add(b); root.addView(b)
        }
        orientBtn = button("") { landscape = !landscape; refresh() }
        root.addView(orientBtn)
        micBtn = button("") { toggleMic() }
        root.addView(micBtn)
        gameBtn = button("") { toggleGame() }
        root.addView(gameBtn)

        startBtn = button("") { onStartStop() }
        root.addView(startBtn)

        setContentView(root)
        refresh()
    }

    override fun onResume() { super.onResume(); handler.post(ticker) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(ticker) }

    private fun refresh() {
        val rec = RecorderService.running
        startBtn.text = if (rec) "TO'XTATISH" else "YOZISHNI BOSHLASH"
        paint(startBtn, if (rec) "#C62828" else "#2E7D32", Color.WHITE)
        timerText.text = if (rec) {
            val s = (SystemClock.elapsedRealtime() - RecorderService.startTime) / 1000
            "%02d:%02d".format(s / 60, s % 60)
        } else "00:00"
        presetBtns.forEachIndexed { i, b ->
            if (i == selected) paint(b, "#F5B301", Color.BLACK) else paint(b, "#1F2630", Color.WHITE)
            b.isEnabled = !rec
        }
        orientBtn.text = if (landscape) "Yo'nalish: Gorizontal (o'yin)" else "Yo'nalish: Vertikal"
        micBtn.text = if (mic) "Mikrofon: YOQILGAN" else "Mikrofon: o'chiq"
        paint(orientBtn, "#1F2630", Color.WHITE)
        paint(micBtn, if (mic) "#F5B301" else "#1F2630", if (mic) Color.BLACK else Color.WHITE)
        gameBtn.text = if (game) "O'yin tovushi: YOQILGAN" else "O'yin tovushi: o'chiq"
        paint(gameBtn, if (game) "#F5B301" else "#1F2630", if (game) Color.BLACK else Color.WHITE)
        gameBtn.isEnabled = !rec
        orientBtn.isEnabled = !rec
        micBtn.isEnabled = !rec
    }

    private fun toggleGame() {
        if (!game && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_PERM_GAME)
            return
        }
        game = !game
        refresh()
    }

    private fun toggleMic() {
        if (!mic && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_PERM)
            return
        }
        mic = !mic
        refresh()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_PERM) {
            mic = results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED
            refresh()
        } else if (code == REQ_PERM_GAME) {
            game = results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED
            refresh()
        }
    }

    private fun onStartStop() {
        if (RecorderService.running) {
            startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
        } else {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJ)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PROJ || resultCode != RESULT_OK || data == null) return

        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)
        val long = max(dm.widthPixels, dm.heightPixels)
        val short = min(dm.widthPixels, dm.heightPixels)
        val p = presets[selected]
        val targetShort = min(p.shortSide, short)
        val targetLong = (long.toFloat() * targetShort / short).toInt()
        val s = even(targetShort)
        val l = even(targetLong)
        val w = if (landscape) l else s
        val h = if (landscape) s else l

        val svc = Intent(this, RecorderService::class.java).apply {
            action = RecorderService.ACTION_START
            putExtra("code", resultCode)
            putExtra("data", data)
            putExtra("w", w); putExtra("h", h); putExtra("dpi", dm.densityDpi)
            putExtra("fps", p.fps); putExtra("bitrate", p.bitrate)
            putExtra("mic", mic)
            putExtra("game", game)
        }
        startForegroundService(svc)
        moveTaskToBack(true)
    }

    // 16 ga bo'linadigan o'lcham: apparat kodlagich uchun eng xavfsiz
    private fun even(v: Int) = ((v + 8) / 16) * 16

    private fun paint(b: Button, bg: String, fg: Int) {
        b.setTextColor(fg)
        b.background = GradientDrawable().apply {
            setColor(Color.parseColor(bg)); cornerRadius = dp(14).toFloat()
        }
    }

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        setTypeface(null, Typeface.BOLD)
        isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(54)
        ).apply { bottomMargin = dp(12) }
        setOnClickListener { onClick() }
        paint(this, "#1F2630", Color.WHITE)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
