package com.camtimer.app

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.media.AudioManager
import android.media.AudioTrack
import android.media.AudioAttributes
import android.media.AudioFormat
import kotlin.math.sin
import kotlin.math.PI
import android.view.WindowManager
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.text.TextWatcher
import android.text.Editable
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.camera.core.Preview
import androidx.core.content.ContextCompat
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.PixelFormat
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ExportException
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private lateinit var counter: TextView
    private lateinit var recordButton: TextView
    private lateinit var timerButton: TextView
    private data class TimerSample(val atMs: Long, val remaining: Long)
    private val samples = mutableListOf<TimerSample>()
    private var videoStartMs = 0L
    private var exporting = false
    private var configuredMs = 180_000L
    private val preferences by lazy { getSharedPreferences("camtimer_settings", Context.MODE_PRIVATE) }
    private var alertEnabled = true
    private var autoRecordEnabled = false
    private var autoRecordAtMs = 15_000L
    private var autoRecordTriggered = false
    private var alertPlayed = false
    private val beepSound by lazy { createHighBeep() }
    private val beepTasks = mutableListOf<Runnable>()
    private var blinkOn = true
    private val blink = object : Runnable {
        override fun run() {
            if (recording != null) {
                blinkOn = !blinkOn
                recordButton.alpha = if (blinkOn) 1f else 0.35f
                handler.postDelayed(this, 500)
            }
        }
    }
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private val handler = Handler(Looper.getMainLooper())
    private var remainingMs = 180_000L
    private var running = false
    private var lastTick = 0L

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.CAMERA] == true) startCamera()
        else Toast.makeText(this, "Autorisation caméra nécessaire", Toast.LENGTH_LONG).show()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (running) {
                val now = android.os.SystemClock.elapsedRealtime()
                val before = remainingMs
                remainingMs -= now - lastTick
                lastTick = now
                if (alertEnabled && !alertPlayed && before > 15_000L && remainingMs <= 15_000L && remainingMs > 0L) {
                    alertPlayed = true
                    playSingleAlert()
                }
                if (autoRecordEnabled && !autoRecordTriggered &&
                    before > autoRecordAtMs && remainingMs <= autoRecordAtMs &&
                    recording == null && !exporting) {
                    autoRecordTriggered = true
                    beginRecording()
                }
                updateCounter()
            }
            if (videoStartMs != 0L) samples.add(TimerSample(SystemClock.elapsedRealtime() - videoStartMs, remainingMs))
            handler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        alertEnabled = preferences.getBoolean("alert_enabled", true)
        autoRecordEnabled = preferences.getBoolean("auto_record_enabled", false)
        autoRecordAtMs = preferences.getLong("auto_record_at_ms", 15_000L)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(20, 24, 32))
            setPadding(12, 12, 12, 20)
            fitsSystemWindows = true
        }
        val settingsBar = FrameLayout(this)
        val settingsButton = TextView(this).apply {
            text = "⚙"
            textSize = 29f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = "Paramètres"
            setOnClickListener { showSettingsPage() }
        }
        val settingsSize = (48 * resources.displayMetrics.density).toInt()
        settingsBar.addView(settingsButton, FrameLayout.LayoutParams(settingsSize, settingsSize, Gravity.END or Gravity.CENTER_VERTICAL))
        root.addView(settingsBar, LinearLayout.LayoutParams(-1, settingsSize))
        preview = PreviewView(this)
        val cameraBox = FrameLayout(this)
        cameraBox.addView(preview, FrameLayout.LayoutParams(-1, -1))
        counter = TextView(this).apply {
            typeface = Typeface.DEFAULT_BOLD
            textSize = 42f
            setTextColor(Color.WHITE)
            setShadowLayer(5f, 2f, 2f, Color.BLACK)
            gravity = Gravity.CENTER
            setBackgroundColor(0x55000000)
            contentDescription = "Régler les minutes et secondes"
            setOnClickListener { showTimePicker() }
        }
        cameraBox.addView(counter, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        root.addView(cameraBox, LinearLayout.LayoutParams(-1, 0, 1f))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 12)
        }
        fun roundButton(symbol: String, color: Int, click: () -> Unit): TextView =
            TextView(this).apply {
                text = symbol
                textSize = 34f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.BLUE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setStroke(3, Color.rgb(220, 225, 232))
                }
                elevation = 8f
                setOnClickListener { click() }
            }
        timerButton = roundButton("▶", Color.WHITE) {
            running = !running
            if (running) {
                lastTick = android.os.SystemClock.elapsedRealtime()
                if (remainingMs >= configuredMs - 100L) {
                    autoRecordTriggered = false
                    alertPlayed = false
                }
            }
            updateKeepScreenOn()
            updateTimerButton()
        }
        val buttonSize = (68 * resources.displayMetrics.density).toInt()
        recordButton = roundButton("●", Color.WHITE) {
            // Camera-style red symbol within a white circular shutter.

            if (recording == null) beginRecording() else {
                recordButton.isEnabled = false
                recording?.stop()
            }
        }
        recordButton.text = ""
        recordButton.background = controlDrawable(isRecording = true, active = false)
        // Equal-width cells anchor START at left and ENR at right.
        fun addControl(view: TextView, alignment: Int) {
            val slot = FrameLayout(this)
            slot.addView(view, FrameLayout.LayoutParams(buttonSize, buttonSize, alignment))
            controls.addView(slot, LinearLayout.LayoutParams(0, buttonSize, 1f))
        }
        addControl(timerButton, Gravity.CENTER)
        addControl(recordButton, Gravity.CENTER)
        root.addView(controls)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContentView(root)
        updateCounter()
        updateTimerButton()
        recordButton.contentDescription = "ENR enregistrement"
        handler.post(tick)
        permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    private fun controlDrawable(isRecording: Boolean, active: Boolean): Drawable =
        object : Drawable() {
            private val brush = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun draw(canvas: Canvas) {
                val b = bounds
                val cx = b.exactCenterX()
                val cy = b.exactCenterY()
                val r = minOf(b.width(), b.height()) * 0.47f
                brush.style = Paint.Style.FILL
                // Raised metallic rim, like the reference pictogram.
                brush.color = Color.rgb(115, 118, 121)
                canvas.drawCircle(cx, cy + r * 0.04f, r, brush)
                brush.color = Color.rgb(205, 207, 209)
                canvas.drawCircle(cx, cy - r * 0.05f, r * 0.93f, brush)
                brush.color = Color.rgb(45, 48, 50)
                canvas.drawCircle(cx, cy, r * 0.82f, brush)
                val baseColor = if (isRecording || active) Color.rgb(237, 12, 24) else Color.rgb(0, 231, 36)
                brush.color = baseColor
                canvas.drawCircle(cx, cy, r * 0.72f, brush)
                // Small glossy reflection at the top of the red shutter.
                if (isRecording || active) {
                    brush.color = Color.rgb(255, 105, 111)
                    canvas.drawOval(RectF(cx-r*0.38f, cy-r*0.62f, cx+r*0.38f, cy-r*0.32f), brush)
                }
                brush.color = Color.BLACK
                if (isRecording || active) {
                    val h = r * 0.30f
                    canvas.drawRect(cx-h, cy-h, cx+h, cy+h, brush)
                } else {
                    val path = android.graphics.Path().apply {
                        moveTo(cx-r*0.24f, cy-r*0.42f)
                        lineTo(cx+r*0.46f, cy)
                        lineTo(cx-r*0.24f, cy+r*0.42f)
                        close()
                    }
                    canvas.drawPath(path, brush)
                }
            }
            override fun setAlpha(alpha: Int) { brush.alpha = alpha; invalidateSelf() }
            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { brush.colorFilter = colorFilter; invalidateSelf() }
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }

    private fun setCircleColor(view: TextView, color: Int) {
        (view.background as? GradientDrawable)?.setColor(color)
    }

    private fun updateTimerButton() {
        timerButton.text = ""
        timerButton.background = controlDrawable(isRecording = false, active = running)
        timerButton.contentDescription = if (running) "STOP chronomètre" else "START chronomètre"
    }

    private fun showTimePicker() {
        if (running) {
            Toast.makeText(this, "Arrête le chrono pour modifier la durée", Toast.LENGTH_SHORT).show()
            return
        }
        val minutes = NumberPicker(this).apply {
            minValue = 0
            maxValue = 99
            value = (configuredMs / 60_000L).toInt()
            wrapSelectorWheel = true
            setFormatter { String.format(Locale.US, "%02d", it) }
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
        }
        val seconds = NumberPicker(this).apply {
            minValue = 0
            maxValue = 59
            value = ((configuredMs / 1000L) % 60L).toInt()
            wrapSelectorWheel = true
            setFormatter { String.format(Locale.US, "%02d", it) }
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
        }
        val pickerRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            addView(minutes, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(this@MainActivity).apply {
                text = ":"
                textSize = 32f
                gravity = Gravity.CENTER
            })
            addView(seconds, LinearLayout.LayoutParams(0, -2, 1f))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Durée du chronomètre")
            .setView(pickerRow)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Valider") { _, _ ->
                val chosen = (minutes.value * 60L + seconds.value) * 1000L
                if (chosen == 0L) {
                    Toast.makeText(this, "Choisis une durée supérieure à zéro", Toast.LENGTH_SHORT).show()
                } else {
                    configuredMs = chosen
                    remainingMs = chosen
                    alertPlayed = false
                    autoRecordTriggered = false
                    cancelBeeps()
                    updateCounter()
                }
            }.show()
    }

    private fun showSettingsPage() {
        val spacing = (18 * resources.displayMetrics.density).toInt()
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(spacing, spacing, spacing, spacing)
            setBackgroundColor(Color.rgb(20, 24, 32))
        }
        fun heading(label: String): TextView = TextView(this).apply {
            text = label
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(0, spacing / 2, 0, spacing / 2)
        }
        val soundSwitch = Switch(this).apply {
            text = "Signal sonore à 15 secondes (5 000 Hz, 2 s)"
            textSize = 16f
            setTextColor(Color.WHITE)
            isChecked = alertEnabled
        }
        page.addView(soundSwitch)
        page.addView(heading("Enregistrement automatique"))
        val autoSwitch = Switch(this).apply {
            text = "Démarrer REC automatiquement"
            textSize = 16f
            setTextColor(Color.WHITE)
            isChecked = autoRecordEnabled
        }
        page.addView(autoSwitch)
        page.addView(heading("Démarrer l'enregistrement quand il reste :"))
        val minutes = NumberPicker(this).apply {
            minValue = 0
            maxValue = 99
            value = (autoRecordAtMs / 60_000L).toInt().coerceIn(0, 99)
            setFormatter { String.format(Locale.US, "%02d", it) }
            wrapSelectorWheel = true
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
        }
        val seconds = NumberPicker(this).apply {
            minValue = 0
            maxValue = 59
            value = ((autoRecordAtMs / 1000L) % 60L).toInt()
            setFormatter { String.format(Locale.US, "%02d", it) }
            wrapSelectorWheel = true
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
        }
        val wheels = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            addView(minutes, LinearLayout.LayoutParams(0, -2, 1f))
            addView(heading(" : "))
            addView(seconds, LinearLayout.LayoutParams(0, -2, 1f))
        }
        page.addView(wheels)
        val help = TextView(this).apply {
            text = "Exemple : 00:15 lance la vidéo à 15 secondes de la fin. Le bouton REC reste utilisable manuellement. Le réglage est conservé."
            setTextColor(Color.LTGRAY)
            textSize = 14f
        }
        page.addView(help)
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Paramètres CamTimer")
            .setView(page)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Enregistrer") { _, _ ->
                alertEnabled = soundSwitch.isChecked
                autoRecordEnabled = autoSwitch.isChecked
                autoRecordAtMs = (minutes.value * 60L + seconds.value) * 1000L
                preferences.edit()
                    .putBoolean("alert_enabled", alertEnabled)
                    .putBoolean("auto_record_enabled", autoRecordEnabled)
                    .putLong("auto_record_at_ms", autoRecordAtMs)
                    .apply()
                if (!alertEnabled) cancelBeeps()
                Toast.makeText(this, "Paramètres enregistrés", Toast.LENGTH_SHORT).show()
            }.create()
        dialog.show()
    }

    private fun updateKeepScreenOn() {
        if (running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun createHighBeep(): ShortArray {
        val rate = 44100
        val count = rate * 2
        return ShortArray(count) { i ->
            val fade = minOf(1.0, i / 220.0, (count - i) / 220.0)
            (sin(2.0 * PI * 5000.0 * i / rate) * 21000.0 * fade).toInt().toShort()
        }
    }

    private fun playHighBeep() {
        val samples = beepSound
        Thread {
            try {
                val audio = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(44100)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(samples.size * 2)
                    .build()
                audio.write(samples, 0, samples.size)
                audio.play()
                Thread.sleep(2100)
                audio.stop()
                audio.release()
            } catch (_: Exception) { }
        }.start()
    }

    private fun playSingleAlert() {
        playHighBeep()
    }

    private fun cancelBeeps() {
        beepTasks.forEach { handler.removeCallbacks(it) }
        beepTasks.clear()
    }

    private fun updateCounter() {
        if (counter.hasFocus()) return
        // Display zero once: positive overtime starts at +00:01.
        val absoluteSeconds = if (remainingMs >= 0L) {
            (remainingMs + 999L) / 1000L
        } else {
            (-remainingMs) / 1000L
        }
        val sign = if (remainingMs <= -1000L) "+" else ""
        counter.text = String.format(Locale.US, "%s%02d:%02d", sign, absoluteSeconds / 60, absoluteSeconds % 60)
        counter.setTextColor(if (remainingMs < 0) Color.GREEN else Color.WHITE)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val cameraPreview = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
            val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD)).build()
            videoCapture = VideoCapture.withOutput(recorder)
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, cameraPreview, videoCapture)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun beginRecording() {
        val capture = videoCapture ?: return
        if (exporting) return
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "CamTimer_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Camera")
        }
        val options = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values).build()
        var pending = capture.output.prepareRecording(this, options)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled()
        }
        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    videoStartMs = SystemClock.elapsedRealtime()
                    samples.clear()
                    samples.add(TimerSample(0L, remainingMs))
                    recordButton.background = controlDrawable(isRecording = true, active = true)
                    recordButton.contentDescription = "STOP enregistrement"
                    blinkOn = true
                    recordButton.alpha = 1f
                    handler.removeCallbacks(blink)
                    handler.postDelayed(blink, 500)
                }
                is VideoRecordEvent.Finalize -> {
                    handler.removeCallbacks(blink)
                    blinkOn = true
                    recordButton.alpha = 1f
                    recording = null
                    recordButton.isEnabled = true
                    recordButton.background = controlDrawable(isRecording = true, active = false)
                    recordButton.contentDescription = "ENR enregistrement"
                    videoStartMs = 0L
                    if (event.hasError()) {
                        Toast.makeText(this, "Erreur vidéo: ${event.error}", Toast.LENGTH_LONG).show()
                    } else {
                        exportWithTimer(event.outputResults.outputUri, samples.toList())
                    }
                }
            }
        }
    }


    @androidx.annotation.OptIn(UnstableApi::class)
    private fun exportWithTimer(source: Uri, timeline: List<TimerSample>) {
        if (source == Uri.EMPTY || timeline.isEmpty()) {
            Toast.makeText(this, "Vidéo sans incrustation : source indisponible", Toast.LENGTH_LONG).show()
            return
        }
        exporting = true
        recordButton.isEnabled = false
        Toast.makeText(this, "Incrustation du chrono en cours…", Toast.LENGTH_SHORT).show()
        val destination = File(cacheDir, "camtimer_overlay_${System.currentTimeMillis()}.mp4")
        val overlay = object : BitmapOverlay() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textSize = 72f
                textAlign = Paint.Align.CENTER
                setShadowLayer(5f, 2f, 2f, Color.BLACK)
            }
            private var lastSecond = Long.MIN_VALUE
            private var lastGreen = false
            private var bitmap = Bitmap.createBitmap(480, 120, Bitmap.Config.ARGB_8888)

            override fun getBitmap(presentationTimeUs: Long): Bitmap {
                val elapsed = presentationTimeUs / 1000L
                var low = 0
                var high = timeline.size - 1
                while (low < high) {
                    val mid = (low + high + 1) / 2
                    if (timeline[mid].atMs <= elapsed) low = mid else high = mid - 1
                }
                val ms = timeline[low].remaining
                val sec = if (ms >= 0) (ms + 999L) / 1000L else (-ms) / 1000L
                val green = ms < 0
                if (sec != lastSecond || green != lastGreen) {
                    lastSecond = sec
                    lastGreen = green
                    bitmap.eraseColor(Color.TRANSPARENT)
                    val canvas = Canvas(bitmap)
                    val label = String.format(Locale.US, "%s%02d:%02d", if (ms <= -1000L) "+" else "", sec / 60, sec % 60)
                    paint.color = if (green) Color.GREEN else Color.WHITE
                    canvas.drawRoundRect(25f, 8f, 455f, 112f, 15f, 15f, Paint().apply { color = 0x77000000 })
                    canvas.drawText(label, 240f, 88f, paint)
                }
                return bitmap
            }

            override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
                OverlaySettings.Builder().setBackgroundFrameAnchor(0f, 0.80f).build()
        }
        val edited = EditedMediaItem.Builder(MediaItem.fromUri(source))
            .setEffects(Effects(emptyList(), listOf(OverlayEffect(listOf(overlay)))))
            .build()
        val transformer = Transformer.Builder(this)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: androidx.media3.transformer.Composition, exportResult: ExportResult) {
                    try {
                        contentResolver.openOutputStream(source, "wt")?.use { output ->
                            FileInputStream(destination).use { input -> input.copyTo(output) }
                        } ?: throw IllegalStateException("Impossible d'ouvrir la vidéo")
                        Toast.makeText(this@MainActivity, "Vidéo enregistrée avec chronomètre", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "Incrustation non sauvegardée : ${e.message}", Toast.LENGTH_LONG).show()
                    } finally {
                        destination.delete()
                        exporting = false
                        recordButton.isEnabled = true
                    }
                }

                override fun onError(composition: androidx.media3.transformer.Composition, exportResult: ExportResult, exportException: ExportException) {
                    destination.delete()
                    exporting = false
                    recordButton.isEnabled = true
                    Toast.makeText(this@MainActivity, "Vidéo originale conservée, incrustation impossible", Toast.LENGTH_LONG).show()
                }
            }).build()
        try {
            transformer.start(edited, destination.absolutePath)
        } catch (e: Exception) {
            exporting = false
            recordButton.isEnabled = true
            Toast.makeText(this, "Erreur incrustation : ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(tick)
        handler.removeCallbacks(blink)
        cancelBeeps()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        recording?.stop()
        super.onDestroy()
    }
}
