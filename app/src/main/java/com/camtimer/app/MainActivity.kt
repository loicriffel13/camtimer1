package com.camtimer.app

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private lateinit var counter: TextView
    private lateinit var recordButton: TextView
    private lateinit var timerButton: TextView
    private var configuredMs = 120_000L
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
    private var remainingMs = 120_000L
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
                remainingMs -= now - lastTick
                lastTick = now
                updateCounter()
            }
            handler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(20, 24, 32))
            setPadding(12, 12, 12, 20)
            fitsSystemWindows = true
        }
        preview = PreviewView(this)
        val cameraBox = FrameLayout(this)
        cameraBox.addView(preview, FrameLayout.LayoutParams(-1, -1))
        counter = EditText(this).apply {
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setSelectAllOnFocus(true)
            typeface = Typeface.DEFAULT_BOLD
            setOnEditorActionListener { _, _, _ -> applyTypedTime(); true }
            setOnFocusChangeListener { _, focused -> if (!focused) applyTypedTime() }
            textSize = 42f
            setTextColor(Color.WHITE)
            setShadowLayer(5f, 2f, 2f, Color.BLACK)
            gravity = Gravity.CENTER
            setBackgroundColor(0x55000000)
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
                setTextColor(Color.BLACK)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setStroke(5, Color.DKGRAY)
                }
                elevation = 8f
                setOnClickListener { click() }
            }
        timerButton = roundButton("▶", Color.rgb(0, 225, 60)) {
            applyTypedTime()
            running = !running
            if (running) lastTick = android.os.SystemClock.elapsedRealtime()
            updateTimerButton()
        }
        val buttonSize = (68 * resources.displayMetrics.density).toInt()
        recordButton = roundButton("●", Color.rgb(220, 25, 35)) {
            if (recording == null) beginRecording() else {
                recordButton.isEnabled = false
                recording?.stop()
            }
        }
        val resetButton = roundButton("↺", Color.rgb(105, 115, 130)) {
            applyTypedTime()
            running = false
            remainingMs = configuredMs
            updateCounter()
            updateTimerButton()
        }
        resetButton.setTextColor(Color.WHITE)
        resetButton.contentDescription = "RESET chronomètre"
        // Equal-width cells anchor START at left and ENR at right.
        fun addControl(view: TextView, alignment: Int) {
            val slot = FrameLayout(this)
            slot.addView(view, FrameLayout.LayoutParams(buttonSize, buttonSize, alignment))
            controls.addView(slot, LinearLayout.LayoutParams(0, buttonSize, 1f))
        }
        addControl(timerButton, Gravity.START or Gravity.CENTER_VERTICAL)
        addControl(resetButton, Gravity.CENTER)
        addControl(recordButton, Gravity.END or Gravity.CENTER_VERTICAL)
        root.addView(controls)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContentView(root)
        updateCounter()
        updateTimerButton()
        recordButton.contentDescription = "ENR enregistrement"
        handler.post(tick)
        permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    private fun setCircleColor(view: TextView, color: Int) {
        (view.background as? GradientDrawable)?.setColor(color)
    }

    private fun updateTimerButton() {
        timerButton.text = if (running) "■" else "▶"
        setCircleColor(timerButton, if (running) Color.rgb(235, 25, 35) else Color.rgb(0, 225, 60))
        timerButton.contentDescription = if (running) "STOP chronomètre" else "START chronomètre"
    }

    private fun applyTypedTime() {
        if (!counter.hasFocus()) return
        val value = counter.text.toString().trim().removePrefix("+")
        val parts = value.split(":")
        val seconds = if (parts.size == 2) {
            val m = parts[0].toLongOrNull()
            val sec = parts[1].toLongOrNull()
            if (m != null && sec != null && sec in 0..59) m * 60 + sec else null
        } else value.toLongOrNull()
        if (seconds != null && seconds in 1..86400) {
            configuredMs = seconds * 1000L
            remainingMs = configuredMs
            lastTick = android.os.SystemClock.elapsedRealtime()
        } else {
            Toast.makeText(this, "Durée attendue : MM:SS (ex. 02:30)", Toast.LENGTH_SHORT).show()
        }
        counter.clearFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(counter.windowToken, 0)
        updateCounter()
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
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "CamTimer_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CamTimer")
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
                    recordButton.text = "■"
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
                    recordButton.text = "●"
                    recordButton.contentDescription = "ENR enregistrement"
                    Toast.makeText(this, if (event.hasError()) "Erreur vidéo: ${event.error}" else "Vidéo enregistrée", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(tick)
        handler.removeCallbacks(blink)
        recording?.stop()
        super.onDestroy()
    }
}
