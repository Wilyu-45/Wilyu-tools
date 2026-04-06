package com.example.shakevideoplayer

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import android.media.MediaPlayer
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener, GestureDetector.OnGestureListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var hasGyroscope = false
    
    private lateinit var videoView: VideoView
    private lateinit var tvStatus: TextView
    private lateinit var btnSelectVideo: Button
    private lateinit var btnToggleDetect: Button
    private lateinit var seekBarSensitivity: SeekBar
    private lateinit var tvSensitivityValue: TextView
    private lateinit var etDuration: EditText
    private lateinit var layoutMain: LinearLayout
    private lateinit var videoContainer: FrameLayout
    private lateinit var tvTitle: TextView
    private lateinit var layoutSensitivity: LinearLayout
    private lateinit var layoutDuration: LinearLayout
    private lateinit var doubleTapOverlay: View
    private lateinit var gestureDetector: GestureDetector
    
    private var hasVideo = false
    private var isPlaying = false
    private var isDetecting = false
    private var videoWidth = 0
    private var videoHeight = 0
    
    private var lastShakeTime: Long = 0
    private var shakeThreshold = 10.0f
    private val shakeCooldown = 50L
    
    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var isFirstUpdate = true
    private var gyroFirstUpdate = true
    private var gyroscopeThreshold = 0.1f

    private var playDuration = 2000L
    private val handler = Handler(Looper.getMainLooper())
    private val stopRunnable = Runnable {
        if (isPlaying) {
            videoView.pause()
            isPlaying = false
            tvStatus.text = getString(R.string.video_paused)
        }
    }

    private val selectVideoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            setupVideo(it)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            openVideoSelector()
        } else {
            Toast.makeText(this, "需要存储权限才能选择视频", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        initSensor()
        setupClickListeners()
    }

    private fun initViews() {
        videoView = findViewById(R.id.videoView)
        tvStatus = findViewById(R.id.tvStatus)
        btnSelectVideo = findViewById(R.id.btnSelectVideo)
        btnToggleDetect = findViewById(R.id.btnToggleDetect)
        seekBarSensitivity = findViewById(R.id.seekBarSensitivity)
        tvSensitivityValue = findViewById(R.id.tvSensitivityValue)
        etDuration = findViewById(R.id.etDuration)
        layoutMain = findViewById(R.id.layoutMain)
        videoContainer = findViewById(R.id.videoContainer)
        tvTitle = findViewById(R.id.tvTitle)
        layoutSensitivity = findViewById(R.id.layoutSensitivity)
        layoutDuration = findViewById(R.id.layoutDuration)
        
        btnToggleDetect.setOnClickListener {
            toggleDetection()
        }
        
        seekBarSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val displayValue = progress + 1
                val threshold = (11 - displayValue) * 0.1f + 0.2f
                shakeThreshold = threshold.toFloat()
                tvSensitivityValue.text = displayValue.toString()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        etDuration.setOnEditorActionListener { _, _, _ ->
            updatePlayDuration()
            false
        }
        etDuration.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                updatePlayDuration()
            }
        }
        
        val mediaController = MediaController(this)
        mediaController.setAnchorView(videoView)
        videoView.setMediaController(mediaController)
        
        videoView.setOnCompletionListener {
            isPlaying = false
            tvStatus.text = getString(R.string.video_paused)
        }
        
        videoView.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, "视频播放出错", Toast.LENGTH_SHORT).show()
            false
        }
        
        doubleTapOverlay = findViewById(R.id.doubleTapOverlay)
        
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isDetecting) {
                    toggleDetection()
                }
                return true
            }
        })
        
        doubleTapOverlay.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun updatePlayDuration() {
        val input = etDuration.text.toString()
        val seconds = input.toFloatOrNull()
        if (seconds != null && seconds > 0) {
            playDuration = (seconds * 1000).toLong()
        } else {
            etDuration.setText("2")
            playDuration = 2000L
        }
    }

    private fun initSensor() {
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        hasGyroscope = gyroscope != null

        if (accelerometer == null) {
            Toast.makeText(this, "设备不支持加速度传感器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleDetection() {
        if (!hasVideo) {
            Toast.makeText(this, "请先选择视频", Toast.LENGTH_SHORT).show()
            return
        }
        
        isDetecting = !isDetecting
        
        if (isDetecting) {
            enterFullscreenMode()
        } else {
            exitFullscreenMode()
        }
    }
    
    private fun enterFullscreenMode() {
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        
        layoutMain.setPadding(0, 0, 0, 0)
        
        val layoutParams = videoContainer.layoutParams as LinearLayout.LayoutParams
        layoutParams.weight = 0f
        layoutParams.height = FrameLayout.LayoutParams.MATCH_PARENT
        videoContainer.layoutParams = layoutParams
        
        if (videoWidth > 0 && videoHeight > 0) {
            if (videoWidth > videoHeight) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
        
        tvTitle.visibility = View.GONE
        layoutSensitivity.visibility = View.GONE
        layoutDuration.visibility = View.GONE
        tvStatus.visibility = View.GONE
        btnSelectVideo.visibility = View.GONE
        btnToggleDetect.visibility = View.GONE
        doubleTapOverlay.visibility = View.VISIBLE
        
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        if (hasGyroscope) {
            gyroscope?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
        isFirstUpdate = true
        gyroFirstUpdate = true
    }

    private fun exitFullscreenMode() {
        btnToggleDetect.text = "开始检测"
        btnToggleDetect.visibility = View.VISIBLE
        
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        
        layoutMain.setPadding(48, 48, 48, 48)
        
        val layoutParams = videoContainer.layoutParams as LinearLayout.LayoutParams
        layoutParams.weight = 1f
        layoutParams.height = 0
        videoContainer.layoutParams = layoutParams
        
        tvTitle.visibility = View.VISIBLE
        layoutSensitivity.visibility = View.VISIBLE
        layoutDuration.visibility = View.VISIBLE
        tvStatus.visibility = View.VISIBLE
        btnSelectVideo.visibility = View.VISIBLE
        doubleTapOverlay.visibility = View.GONE
        
        sensorManager.unregisterListener(this)
        if (isPlaying) {
            videoView.pause()
            isPlaying = false
            tvStatus.text = getString(R.string.video_paused)
        }
        handler.removeCallbacks(stopRunnable)
    }

    private fun setupClickListeners() {
        btnSelectVideo.setOnClickListener {
            checkPermissionAndSelectVideo()
        }
    }

    private fun checkPermissionAndSelectVideo() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED) {
                openVideoSelector()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.READ_MEDIA_VIDEO)
            }
        } else {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                openVideoSelector()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

    private fun openVideoSelector() {
        selectVideoLauncher.launch("video/*")
    }

    private fun setupVideo(uri: Uri) {
        hasVideo = true
        videoView.setVideoURI(uri)
        tvStatus.text = getString(R.string.shake_to_play)
        
        videoView.setOnPreparedListener { mediaPlayer ->
            videoWidth = mediaPlayer.videoWidth
            videoHeight = mediaPlayer.videoHeight
            
            val params = videoView.layoutParams as FrameLayout.LayoutParams
            params.width = FrameLayout.LayoutParams.MATCH_PARENT
            params.height = FrameLayout.LayoutParams.MATCH_PARENT
            params.gravity = android.view.Gravity.CENTER
            videoView.layoutParams = params
        }
        
        Toast.makeText(this, "视频已加载，晃动手机播放", Toast.LENGTH_SHORT).show()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            when (it.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    detectShake(it.values[0], it.values[1], it.values[2])
                }
                Sensor.TYPE_GYROSCOPE -> {
                    detectShakeByGyroscope(it.values[0], it.values[1], it.values[2])
                }
            }
        }
    }

    private fun detectShake(x: Float, y: Float, z: Float) {
        if (!hasVideo) return

        val currentTime = System.currentTimeMillis()

        if (currentTime - lastShakeTime < shakeCooldown) {
            return
        }

        if (isFirstUpdate) {
            lastX = x
            lastY = y
            lastZ = z
            isFirstUpdate = false
            return
        }

        val deltaX = x - lastX
        val deltaY = y - lastY
        val deltaZ = z - lastZ

        lastX = x
        lastY = y
        lastZ = z

        val acceleration = sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ)

        if (acceleration > shakeThreshold) {
            lastShakeTime = currentTime
            onShakeDetected()
        }
    }

    private fun detectShakeByGyroscope(x: Float, y: Float, z: Float) {
        if (!hasVideo) return

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastShakeTime < shakeCooldown) {
            return
        }

        if (gyroFirstUpdate) {
            gyroFirstUpdate = false
            return
        }

        val rotation = sqrt(x * x + y * y + z * z)

        if (rotation > gyroscopeThreshold) {
            lastShakeTime = currentTime
            onShakeDetected()
        }
    }

    private fun onShakeDetected() {
        if (!hasVideo) return
        
        handler.removeCallbacks(stopRunnable)
        
        if (!isPlaying) {
            videoView.start()
            isPlaying = true
            tvStatus.text = getString(R.string.video_playing)
        }
        
        handler.postDelayed(stopRunnable, playDuration)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }

    override fun onDown(e: MotionEvent): Boolean = true

    override fun onShowPress(e: MotionEvent) {}

    override fun onSingleTapUp(e: MotionEvent): Boolean = false

    override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean = false

    override fun onLongPress(e: MotionEvent) {}

    override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean = false

    override fun onResume() {
        super.onResume()
        if (isDetecting) {
            accelerometer?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
            if (hasGyroscope) {
                gyroscope?.let {
                    sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (isDetecting) {
            sensorManager.unregisterListener(this)
        }
        
        if (isPlaying) {
            videoView.pause()
            isPlaying = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(stopRunnable)
        videoView.stopPlayback()
    }
}
