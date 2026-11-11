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
    private var alertFrequencyHz = 800
    private var alertDurationMs = 2000L
    private var alertAtMs = 15_000L
    private var autoRecordEnabled = false
    private var autoRecordAtMs = 15_000L
    private var autoRecordTriggered = false
    private var alertPlayed = false
    private var beepSound: ShortArray? = null
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
                if (alertEnabled && !alertPlayed && before > alertAtMs && remainingMs <= alertAtMs && remainingMs > 0L) {
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
        alertFrequencyHz = preferences.getInt("alert_frequency_hz", 800).coerceIn(100, 5000)
        alertDurationMs = preferences.getLong("alert_duration_ms", 2000L).coerceIn(500L, 5000L)
        alertAtMs = preferences.getLong("alert_at_ms", 15_000L).coerceIn(1000L, 5999000L)
        autoRecordEnabled = preferences.getBoolean("auto_record_enabled", false)
        autoRecordAtMs = preferences.getLong("auto_record_at_ms", 15_000L)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(20, 24, 32))
            setPadding(12, 12, 12, 20)
            fitsSystemWindows = true
        }
        val settingsButton = TextView(this).apply {
            text = "⚙"
            textSize = 29f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = "Paramètres"
            setOnClickListener { showSettingsPage() }
        }
        val settingsSize = (48 * resources.displayMetrics.density).toInt()
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
        // Overlay settings on the camera preview beside the timer, without shrinking it.
        val settingsParams = FrameLayout.LayoutParams(settingsSize, settingsSize, Gravity.TOP or Gravity.END)
        cameraBox.addView(settingsButton, settingsParams)
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
            if (running) {
                // STOP resets the countdown to the selected starting duration.
                running = false
                remainingMs = configuredMs
                alertPlayed = false
                autoRecordTriggered = false
                cancelBeeps()
                updateCounter()
            } else {
                running = true
                lastTick = android.os.SystemClock.elapsedRealtime()
                autoRecordTriggered = false
                alertPlayed = false
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
                // Camera shutter: white outer ring, dark inner gap, red dot or square.
                // Timer: white circular background, blue play or stop symbol.
                brush.color = Color.WHITE
                canvas.drawCircle(cx, cy, r, brush)
                if (isRecording) {
                    brush.color = Color.rgb(24, 28, 35)
                    canvas.drawCircle(cx, cy, r * 0.83f, brush)
                    brush.color = Color.rgb(232, 30, 45)
                    if (active) {
                        val h = r * 0.38f
                        canvas.drawRoundRect(RectF(cx-h, cy-h, cx+h, cy+h), r*0.10f, r*0.10f, brush)
                    } else {
                        canvas.drawCircle(cx, cy, r * 0.66f, brush)
                    }
                } else {
                    brush.color = Color.rgb(20, 110, 235)
                    if (active) {
                        val h = r * 0.34f
                        canvas.drawRoundRect(RectF(cx-h, cy-h, cx+h, cy+h), r*0.07f, r*0.07f, brush)
                    } else {
                        val path = android.graphics.Path().apply {
                            moveTo(cx-r*0.27f, cy-r*0.42f)
                            lineTo(cx+r*0.43f, cy)
                            lineTo(cx-r*0.27f, cy+r*0.42f)
                            close()
                        }
                        canvas.drawPath(path, brush)
                    }
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
        val density = resources.displayMetrics.density
        val pad = (14 * density).toInt()
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad)
            setBackgroundColor(Color.rgb(20, 24, 32))
        }
        fun label(value: String, size: Float = 15f): TextView = TextView(this).apply {
            text = value
            textSize = size
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
        }
        fun numeric(initial: String, decimal: Boolean = false): EditText = EditText(this).apply {
            setText(initial)
            textSize = 17f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setSelectAllOnFocus(true)
            gravity = Gravity.CENTER
            singleLine = true
            inputType = if (decimal) android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                else android.text.InputType.TYPE_CLASS_NUMBER
            background = GradientDrawable().apply {
                setColor(Color.rgb(48, 56, 72))
                cornerRadius = 7 * density
                setStroke((1 * density).toInt().coerceAtLeast(1), Color.LTGRAY)
            }
            setPadding((5 * density).toInt(), 0, (5 * density).toInt(), 0)
        }
        fun row(title: String, field: EditText, unit: String) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (5 * density).toInt(), 0, (5 * density).toInt())
            }
            line.addView(label(title), LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(field, LinearLayout.LayoutParams((85 * density).toInt(), (43 * density).toInt()))
            line.addView(label("  " + unit, 13f))
            page.addView(line)
        }
        val soundSwitch = Switch(this).apply {
            text = "Activer le signal sonore"
            setTextColor(Color.WHITE)
            isChecked = alertEnabled
        }
        page.addView(soundSwitch)
        val frequency = numeric(alertFrequencyHz.toString())
        val duration = numeric(String.format(Locale.US, "%.1f", alertDurationMs / 1000.0), true)
        val alertTime = numeric((alertAtMs / 1000L).toString())
        row("Fréquence", frequency, "Hz")
        row("Durée", duration, "s")
        row("Son à", alertTime, "s restantes")
        page.addView(label("Enregistrement automatique", 17f).apply {
            setPadding(0, pad, 0, pad / 2)
        })
        val autoSwitch = Switch(this).apply {
            text = "Démarrer REC automatiquement"
            setTextColor(Color.WHITE)
            isChecked = autoRecordEnabled
        }
        page.addView(autoSwitch)
        val minutes = numeric((autoRecordAtMs / 60_000L).toString())
        val seconds = numeric(((autoRecordAtMs / 1000L) % 60L).toString())
        val autoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        autoRow.addView(label("REC à", 15f), LinearLayout.LayoutParams(0, -2, 1f))
        autoRow.addView(minutes, LinearLayout.LayoutParams((58 * density).toInt(), (43 * density).toInt()))
        autoRow.addView(label(" : "), LinearLayout.LayoutParams(-2, -2))
        autoRow.addView(seconds, LinearLayout.LayoutParams((58 * density).toInt(), (43 * density).toInt()))
        autoRow.addView(label("  min : s", 13f))
        page.addView(autoRow)
        page.addView(label("Les réglages sont conservés. REC reste utilisable manuellement.", 12f).apply {
            setTextColor(Color.LTGRAY)
            setPadding(0, pad, 0, 0)
        })
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            addView(page)
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Paramètres CamTimer")
            .setView(scroll)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Enregistrer", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val hz = frequency.text.toString().toIntOrNull()
                val secondsSound = duration.text.toString().replace(',', '.').toDoubleOrNull()
                val trigger = alertTime.text.toString().toIntOrNull()
                val minAuto = minutes.text.toString().toIntOrNull()
                val secAuto = seconds.text.toString().toIntOrNull()
                if (hz == null || hz !in 100..5000 ||
                    secondsSound == null || secondsSound !in 0.5..5.0 ||
                    trigger == null || trigger !in 1..120 ||
                    minAuto == null || minAuto !in 0..99 ||
                    secAuto == null || secAuto !in 0..59) {
                    Toast.makeText(this, "Valeurs : 100-5000 Hz, 0,5-5 s, alerte 1-120 s, REC 0-99 min / 0-59 s", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                alertEnabled = soundSwitch.isChecked
                alertFrequencyHz = hz
                alertDurationMs = (secondsSound * 1000).toLong()
                alertAtMs = trigger * 1000L
                autoRecordEnabled = autoSwitch.isChecked
                autoRecordAtMs = (minAuto * 60L + secAuto) * 1000L
                preferences.edit()
                    .putBoolean("alert_enabled", alertEnabled)
                    .putInt("alert_frequency_hz", alertFrequencyHz)
                    .putLong("alert_duration_ms", alertDurationMs)
                    .putLong("alert_at_ms", alertAtMs)
                    .putBoolean("auto_record_enabled", autoRecordEnabled)
                    .putLong("auto_record_at_ms", autoRecordAtMs)
                    .apply()
                if (!alertEnabled) cancelBeeps()
                dialog.dismiss()
                Toast.makeText(this, "Paramètres enregistrés", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
    }

    private fun updateKeepScreenOn() {
        if (running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun createHighBeep(): ShortArray {
        val rate = 44100
        val count = (rate * alertDurationMs / 1000L).toInt()
        return ShortArray(count) { i ->
            val fade = minOf(1.0, i / 220.0, (count - i) / 220.0)
            (sin(2.0 * PI * alertFrequencyHz * i / rate) * 21000.0 * fade).toInt().toShort()
        }
    }

    private fun playHighBeep() {
        val samples = createHighBeep()
        val duration = alertDurationMs
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
                Thread.sleep(duration + 100L)
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
