package com.camtimer.app

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
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
    private lateinit var recordButton: Button
    private lateinit var duration: EditText
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
        counter = TextView(this).apply {
            textSize = 42f
            setTextColor(Color.WHITE)
            setShadowLayer(5f, 2f, 2f, Color.BLACK)
            gravity = Gravity.CENTER
            setBackgroundColor(0x55000000)
        }
        cameraBox.addView(counter, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        root.addView(cameraBox, LinearLayout.LayoutParams(-1, 0, 1f))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(20, 24, 32))
        }
        duration = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("120")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            hint = "Durée en secondes"
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setSingleLine(true)
            setOnEditorActionListener { _, _, _ ->
                applyDuration()
                hideKeyboard()
                true
            }
        }
        controls.addView(duration)
        duration.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!running) applyDuration()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        val timerButtons = LinearLayout(this)
        fun button(label: String, action: () -> Unit) {
            timerButtons.addView(Button(this).apply {
                text = label
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        button("START") {
            hideKeyboard()
            duration.clearFocus()
            if (!running) {
                running = true
                lastTick = android.os.SystemClock.elapsedRealtime()
            }
        }
        button("PAUSE") { running = false; hideKeyboard() }
        button("RESET") {
            running = false
            applyDuration()
            hideKeyboard()
        }
        controls.addView(timerButtons)
        recordButton = Button(this).apply {
            text = "REC"
            setOnClickListener { if (recording == null) beginRecording() else recording?.stop() }
        }
        root.addView(controls)
        // Separate recording button: remains visible at the bottom of the screen.
        root.addView(recordButton, LinearLayout.LayoutParams(-1, -2))
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContentView(root)
        updateCounter()
        handler.post(tick)
        permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    private fun applyDuration() {
        val seconds = duration.text.toString().toLongOrNull() ?: return
        remainingMs = seconds.coerceIn(1L, 86400L) * 1000L
        updateCounter()
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(duration.windowToken, 0)
    }

    private fun updateCounter() {
        val absoluteSeconds = (kotlin.math.abs(remainingMs) / 1000L)
        val sign = if (remainingMs < 0) "+" else ""
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
                is VideoRecordEvent.Start -> recordButton.text = "STOP"
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    recordButton.text = "REC"
                    Toast.makeText(this, if (event.hasError()) "Erreur vidéo: ${event.error}" else "Vidéo enregistrée", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(tick)
        recording?.stop()
        super.onDestroy()
    }
}
