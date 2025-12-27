package com.example.osutablet // Make sure this matches your package name

import android.content.*
import android.graphics.RectF
import android.os.*
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*

// MODIFIED: Implement the new listener interface
class MainActivity : AppCompatActivity(), AreaChangedListener {

    // --- UI Components ---
    private lateinit var statusLayout: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var statusSubtext: TextView
    private lateinit var buttonRefresh: Button
    private lateinit var textTime: TextView
    private lateinit var textBattery: TextView
    private lateinit var editableAreaView: EditableAreaView
    private lateinit var fabSetup: FloatingActionButton
    private lateinit var setupControls: LinearLayout
    private lateinit var buttonSave: Button
    private lateinit var buttonCancel: Button
    private lateinit var statusContentContainer: LinearLayout

    // NEW: Add variables for the new input panel
    private lateinit var inputPanel: LinearLayout
    private lateinit var editTextWidth: EditText
    private lateinit var editTextHeight: EditText
    private lateinit var buttonApplySize: Button

    // --- Networking & Coroutines ---
    private var output: PrintWriter? = null
    private var socket: Socket? = null
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    // --- Time & Battery ---
    private val timeHandler = Handler(Looper.getMainLooper())
    private lateinit var timeRunnable: Runnable
    private var batteryReceiver: BroadcastReceiver? = null

    // --- State & Conversion ---
    private var isSetupMode = false
    private val originalArea = RectF()
    // NEW: Add variable for screen density
    private var pixelsPerMm: Float = 1f // Default value, will be calculated in onCreate

    companion object {
        const val PREFS_NAME = "OsuTabletPrefs"
        const val KEY_LEFT = "areaLeft"
        const val KEY_TOP = "areaTop"
        const val KEY_RIGHT = "areaRight"
        const val KEY_BOTTOM = "areaBottom"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Find all UI components
        statusLayout = findViewById(R.id.status_layout)
        statusText = findViewById(R.id.status_text)
        statusSubtext = findViewById(R.id.status_subtext)
        buttonRefresh = findViewById(R.id.button_refresh)
        textTime = findViewById(R.id.text_time)
        textBattery = findViewById(R.id.text_battery)
        editableAreaView = findViewById(R.id.editable_area_view)
        fabSetup = findViewById(R.id.fab_setup)
        setupControls = findViewById(R.id.setup_controls)
        buttonSave = findViewById(R.id.button_save)
        buttonCancel = findViewById(R.id.button_cancel)
        statusContentContainer = findViewById(R.id.status_content_container)

        // NEW: Find the new input panel views
        inputPanel = findViewById(R.id.input_panel)
        editTextWidth = findViewById(R.id.edit_text_width)
        editTextHeight = findViewById(R.id.edit_text_height)
        buttonApplySize = findViewById(R.id.button_apply_size)

        // NEW: Calculate the conversion factor for this device's screen
        val dpi = resources.displayMetrics.xdpi
        pixelsPerMm = dpi / 25.4f // 25.4 mm in an inch

        // NEW: Set the listener for manual dragging updates
        editableAreaView.listener = this

        makeAppFullscreen()
        loadLayout()

        // Button listeners
        buttonRefresh.setOnClickListener { connectToServer() }
        fabSetup.setOnClickListener { enterSetupMode() }
        buttonSave.setOnClickListener { saveAndExitSetupMode() }
        buttonCancel.setOnClickListener { cancelSetupMode() }
        // NEW: Add listener for the new apply button
        buttonApplySize.setOnClickListener { applyNumericalSize() }

        connectToServer()
    }

    // NEW: Function to handle the "Apply" button click
    private fun applyNumericalSize() {
        val widthStr = editTextWidth.text.toString()
        val heightStr = editTextHeight.text.toString()

        if (widthStr.isNotEmpty() && heightStr.isNotEmpty()) {
            val widthMm = widthStr.toFloatOrNull() ?: 0f
            val heightMm = heightStr.toFloatOrNull() ?: 0f

            // Convert mm to pixels and update the view
            editableAreaView.resizeArea(widthMm * pixelsPerMm, heightMm * pixelsPerMm)
        }
    }

    // NEW: This function is called by the listener in EditableAreaView
    override fun onAreaManuallyChanged(newArea: RectF) {
        // When the user finishes dragging, update the numbers in the input boxes
        updateInputFields(newArea)
    }

    // NEW: A helper function to update the EditText fields
    private fun updateInputFields(area: RectF) {
        val widthMm = area.width() / pixelsPerMm
        val heightMm = area.height() / pixelsPerMm
        editTextWidth.setText(String.format(Locale.US, "%.1f", widthMm))
        editTextHeight.setText(String.format(Locale.US, "%.1f", heightMm))
    }

    // --- Time and Battery Handlers ---
    private fun startTimeUpdater() {
        timeRunnable = Runnable {
            val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
            textTime.text = timeFormat.format(Date())
            timeHandler.postDelayed(timeRunnable, 1000)
        }
        timeHandler.post(timeRunnable)
    }

    private fun registerBatteryReceiver() {
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level != -1 && scale != -1) {
                    val batteryPct = level * 100 / scale.toFloat()
                    textBattery.text = "${batteryPct.toInt()}%"
                }
            }
        }
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    // --- Connection Logic with Disconnection Detection ---
    private fun connectToServer() {
        updateStatusUI("Connecting...", "", showRefresh = false)
        scope.launch {
            try {
                socket?.close()
                socket = Socket("localhost", 28200)
                output = PrintWriter(socket!!.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))
                val serverMessage = withTimeoutOrNull(5000) { reader.readLine() }

                if (serverMessage == null) {
                    throw IOException("Server did not send hostname in time.")
                }

                val hostname = if (serverMessage.startsWith("HOSTNAME:")) {
                    serverMessage.substringAfter("HOSTNAME:")
                } else { "Unknown PC" }

                withContext(Dispatchers.Main) {
                    updateStatusUI("Connected", "to: $hostname", showRefresh = false, hideContent = true)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                handleDisconnection("Connection Failed")
            }
        }
    }

    private fun handleDisconnection(reason: String) {
        scope.launch {
            withContext(Dispatchers.Main) {
                socket?.close()
                socket = null
                updateStatusUI(reason, "Check PC server & USB connection.", showRefresh = true)
            }
        }
    }

    private fun updateStatusUI(title: String, subtitle: String, showRefresh: Boolean, hideContent: Boolean = false) {
        statusText.text = title
        statusSubtext.text = subtitle
        statusSubtext.visibility = if (subtitle.isNotEmpty()) View.VISIBLE else View.GONE
        buttonRefresh.visibility = if (showRefresh) View.VISIBLE else View.GONE
        statusContentContainer.visibility = if (hideContent) View.GONE else View.VISIBLE
        statusLayout.visibility = View.VISIBLE
    }

    // --- Sending Data with Error Handling ---
    private fun sendData(message: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (socket?.isConnected == true) {
                    output?.println(message)
                    if (output?.checkError() == true) {
                        throw IOException("PrintWriter error, connection lost.")
                    }
                } else {
                    throw IOException("Socket is not connected.")
                }
            } catch (e: IOException) {
                handleDisconnection("Disconnected")
            }
        }
    }

    // --- Touch and Setup Mode Logic ---
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isSetupMode) {
            return super.onTouchEvent(event)
        }
        val activeArea = editableAreaView.getArea()
        if (activeArea.contains(event.x, event.y)) {
            val normalizedX = (event.x - activeArea.left) / activeArea.width()
            val normalizedY = (event.y - activeArea.top) / activeArea.height()
            val data = String.format(Locale.US, "%.4f,%.4f", normalizedX, normalizedY)
            val actionStr = when (event.action) {
                MotionEvent.ACTION_DOWN -> "DOWN"
                MotionEvent.ACTION_MOVE -> "MOVE"
                MotionEvent.ACTION_UP -> "UP"
                else -> null
            }
            actionStr?.let { sendData("$it:$data") }
        }
        return true
    }

    // MODIFIED: Update enterSetupMode to show the panel and populate fields
    private fun enterSetupMode() {
        isSetupMode = true
        originalArea.set(editableAreaView.getArea())
        editableAreaView.setSetupMode(true)
        fabSetup.visibility = View.GONE
        setupControls.visibility = View.VISIBLE
        inputPanel.visibility = View.VISIBLE // Show the input panel
        updateInputFields(originalArea) // Populate with current dimensions
    }

    private fun saveAndExitSetupMode() {
        saveLayout()
        exitSetupMode()
    }

    private fun cancelSetupMode() {
        editableAreaView.setArea(originalArea)
        exitSetupMode()
    }

    // MODIFIED: Update exitSetupMode to hide the panel
    private fun exitSetupMode() {
        isSetupMode = false
        editableAreaView.setSetupMode(false)
        fabSetup.visibility = View.VISIBLE
        setupControls.visibility = View.GONE
        inputPanel.visibility = View.GONE // Hide the input panel
    }

    // --- Layout and UI Helper Functions ---
    private fun saveLayout() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            val area = editableAreaView.getArea()
            putFloat(KEY_LEFT, area.left)
            putFloat(KEY_TOP, area.top)
            putFloat(KEY_RIGHT, area.right)
            putFloat(KEY_BOTTOM, area.bottom)
            apply()
        }
    }

    private fun loadLayout() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_LEFT)) {
            val area = RectF(
                prefs.getFloat(KEY_LEFT, 100f),
                prefs.getFloat(KEY_TOP, 100f),
                prefs.getFloat(KEY_RIGHT, 600f),
                prefs.getFloat(KEY_BOTTOM, 500f)
            )
            editableAreaView.setArea(area)
        } else {
            editableAreaView.post {
                val viewWidth = editableAreaView.width
                val viewHeight = editableAreaView.height
                val defaultWidth = viewWidth * 0.8f
                val defaultHeight = viewHeight * 0.8f
                val left = (viewWidth - defaultWidth) / 2
                val top = (viewHeight - defaultHeight) / 2
                editableAreaView.setArea(RectF(left, top, left + defaultWidth, top + defaultHeight))
            }
        }
    }

    private fun makeAppFullscreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
            window.insetsController?.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN)
        }
    }

    // --- Android Lifecycle Management ---
    override fun onResume() {
        super.onResume()
        startTimeUpdater()
        registerBatteryReceiver()
    }

    override fun onPause() {
        super.onPause()
        timeHandler.removeCallbacks(timeRunnable)
        unregisterReceiver(batteryReceiver)
    }



    override fun onDestroy() {
        super.onDestroy()
        scope.launch { socket?.close() }
        job.cancel()
    }
}