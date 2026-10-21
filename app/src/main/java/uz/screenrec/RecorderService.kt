package uz.screenrec

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

class RecorderService : Service(), SensorEventListener {

    companion object {
        const val ACTION_START = "uz.screenrec.START"
        const val ACTION_STOP = "uz.screenrec.STOP"
        private const val CHANNEL = "rec"
        private const val NOTIF_ID = 1
        private const val SHAKE_G = 3.0f      // silkitish kuchi (kattaroq = qattiqroq silkitish kerak)
        private const val SHAKE_COUNT = 3     // necha marta silkitish kerak

        @Volatile var running = false
        @Volatile var startTime = 0L
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var lastShake = 0L
    private var shakeCount = 0

    // O'yin tovushi rejimi uchun
    private var audio: AudioCapture? = null
    private var tmpVideo: File? = null
    private var tmpAudio: File? = null
    private var useMux = false
    @Volatile private var saving = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun newName() =
        "Rec_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"

    private fun newVideoUri(): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, newName())
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ScreenRec")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        return contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
    }

    private fun publish(u: Uri) {
        val v = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        contentResolver.update(u, v, null, null)
    }

    private fun startRecording(intent: Intent) {
        val code = intent.getIntExtra("code", 0)
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra("data", Intent::class.java)
        else intent.getParcelableExtra("data")
        val w = intent.getIntExtra("w", 1280)
        val h = intent.getIntExtra("h", 720)
        val dpi = intent.getIntExtra("dpi", 320)
        val fps = intent.getIntExtra("fps", 30)
        val bitrate = intent.getIntExtra("bitrate", 6_000_000)
        val mic = intent.getBooleanExtra("mic", false)
        val game = intent.getBooleanExtra("game", false)

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Ekran yozish", NotificationManager.IMPORTANCE_LOW)
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Ekran yozilmoqda")
            .setContentText("To'xtatish uchun bosing")
            .setContentIntent(stopPi)
            .setOngoing(true)
            .build()

        var fgType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if ((mic || game) && Build.VERSION.SDK_INT >= 30 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) fgType = fgType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        startForeground(NOTIF_ID, notif, fgType)

        if (running) return
        if (saving) {
            Toast.makeText(this, "Oldingi video saqlanmoqda, biroz kuting", Toast.LENGTH_LONG).show()
            return
        }

        useMux = game

        try {
            if (data == null) throw IllegalStateException("Ruxsat ma'lumoti yo'q")

            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this)
            else @Suppress("DEPRECATION") MediaRecorder()

            if (useMux) {
                // video vaqtincha faylga yoziladi, tovush bilan keyin birlashtiriladi
                tmpVideo = File(cacheDir, "rec_video.mp4").also { it.delete() }
                tmpAudio = File(cacheDir, "rec_audio.m4a").also { it.delete() }
            } else {
                uri = newVideoUri()
                pfd = contentResolver.openFileDescriptor(uri!!, "w")
            }

            // Apparat (hardware) H.264 kodlash: protsessorni yuklamaydi
            if (mic && !game) r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (mic && !game) {
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioEncodingBitRate(128_000)
                r.setAudioSamplingRate(44_100)
            }
            r.setVideoSize(w, h)
            r.setVideoFrameRate(fps)
            r.setVideoEncodingBitRate(bitrate)
            if (useMux) r.setOutputFile(tmpVideo!!.absolutePath)
            else r.setOutputFile(pfd!!.fileDescriptor)
            r.prepare()
            recorder = r

            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = mpm.getMediaProjection(code, data)
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopRecording() }
            }, null)
            projection = p

            display = p.createVirtualDisplay(
                "rec", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, null
            )

            if (useMux) {
                audio = AudioCapture(p, true, mic, tmpAudio!!).also { it.start() }
            }
            r.start()

            running = true
            startTime = SystemClock.elapsedRealtime()

            val sm = getSystemService(SENSOR_SERVICE) as SensorManager
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Xato: ${e.message}", Toast.LENGTH_LONG).show()
            cleanup(deleteFile = true)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopRecording() {
        if (!running) {
            if (!saving) stopSelf()
            return
        }
        running = false
        val mux = useMux
        val v = tmpVideo
        val a = tmpAudio
        cleanup(deleteFile = false)

        if (mux && v != null && a != null) {
            tmpVideo = null
            tmpAudio = null
            saving = true
            Toast.makeText(this, "Saqlanmoqda...", Toast.LENGTH_SHORT).show()
            Thread {
                val result = saveWithAudio(v, a)
                Handler(Looper.getMainLooper()).post {
                    saving = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    val msg = when (result) {
                        1 -> "Saqlandi: Movies/ScreenRec"
                        2 -> "Tovushni qo'shib bo'lmadi, video tovushsiz saqlandi"
                        else -> "Saqlashda xato"
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    stopSelf()
                }
            }.start()
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            Toast.makeText(this, "Saqlandi: Movies/ScreenRec", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    /** 1 = tovush bilan saqlandi, 2 = faqat video saqlandi, 0 = xato */
    private fun saveWithAudio(video: File, audioFile: File): Int {
        var u: Uri? = null
        try {
            u = newVideoUri() ?: return 0
            contentResolver.openFileDescriptor(u, "rw")!!.use {
                Mux.run(video, audioFile, it.fileDescriptor)
            }
            publish(u)
            return 1
        } catch (e: Exception) {
            u?.let { try { contentResolver.delete(it, null, null) } catch (_: Exception) { } }
            try {
                val u2 = newVideoUri() ?: return 0
                contentResolver.openOutputStream(u2)!!.use { o ->
                    video.inputStream().use { it.copyTo(o) }
                }
                publish(u2)
                return 2
            } catch (_: Exception) {
                return 0
            }
        } finally {
            video.delete()
            audioFile.delete()
        }
    }

    private fun cleanup(deleteFile: Boolean) {
        (getSystemService(SENSOR_SERVICE) as SensorManager).unregisterListener(this)
        try { audio?.stop() } catch (_: Exception) { }
        audio = null
        try { recorder?.stop() } catch (_: RuntimeException) { }
        try { recorder?.reset() } catch (_: Exception) { }
        recorder?.release()
        recorder = null
        display?.release()
        display = null
        try { projection?.stop() } catch (_: Exception) { }
        projection = null
        try { pfd?.close() } catch (_: Exception) { }
        pfd = null
        uri?.let {
            if (deleteFile) {
                contentResolver.delete(it, null, null)
            } else {
                publish(it)
            }
        }
        uri = null
        if (deleteFile) {
            tmpVideo?.delete()
            tmpAudio?.delete()
            tmpVideo = null
            tmpAudio = null
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (!running) return
        val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / 9.81f
        if (g < SHAKE_G) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastShake < 150) return          // bitta silkitishni ikki marta sanamaslik
        if (now - lastShake > 1200) shakeCount = 0 // uzoq tanaffus bo'lsa qaytadan
        lastShake = now
        shakeCount++
        // yozish boshlangach dastlabki 3 soniya e'tiborsiz (telefonni qo'lga olish paytida)
        if (shakeCount >= SHAKE_COUNT && now - startTime > 3000) {
            shakeCount = 0
            stopRecording()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        if (running) { running = false; cleanup(deleteFile = false) }
        super.onDestroy()
    }
}
