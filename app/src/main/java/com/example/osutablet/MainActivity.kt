package com.example.osutablet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.graphics.RectF
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.osutablet.net.ConnectionState
import com.example.osutablet.net.TabletConnection
import com.example.osutablet.input.TabletInputRouter
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Hosts the tablet surface and the setup UI.
 *
 * Touch handling is deliberately centralized here rather than in the view: in
 * play mode every touch inside the active area belongs to the tablet pipeline,
 * including touches that land over decorative chrome.
 */
class MainActivity : AppCompatActivity(), AreaChangedListener {

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
    private lateinit var inputPanel: LinearLayout
    private lateinit var editTextWidth: EditText
    private lateinit var editTextHeight: EditText
    private lateinit var buttonApplySize: Button

    private lateinit var connection: TabletConnection
    private lateinit var router: TabletInputRouter
    private lateinit var areaStore: TabletAreaStore

    private var batteryReceiver: BroadcastReceiver? = null

    private var isSetupMode = false

    /** Mirrors the connection state so touch routing can consult it cheaply. */
    private var isConnected = false

    private val originalArea = RectF()
    private val controlBounds = Rect()

    /** Controls that must keep their own touches while in play mode. */
    private val interactiveControls: List<View> by lazy {
        listOf(fabSetup, buttonRefresh, buttonSave, buttonCancel, buttonApplySize)
    }

    /** Physical pixels per millimetre, per axis. Pixels are rarely square. */
    private var pixelsPerMmX = 1f
    private var pixelsPerMmY = 1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()

        val metrics = resources.displayMetrics
        pixelsPerMmX = (metrics.xdpi / MM_PER_INCH).takeIf { it > 0f } ?: metrics.density
        pixelsPerMmY = (metrics.ydpi / MM_PER_INCH).takeIf { it > 0f } ?: metrics.density

        areaStore = TabletAreaStore(this)
        connection = TabletConnection()
        router = TabletInputRouter(connection)

        editableAreaView.listener = this
        areaStore.load()?.let { editableAreaView.setNormalizedArea(it) }

        makeAppFullscreen()

        buttonRefresh.setOnClickListener { connection.connect(force = true) }
        fabSetup.setOnClickListener { enterSetupMode() }
        buttonSave.setOnClickListener { saveAndExitSetupMode() }
        buttonCancel.setOnClickListener { cancelSetupMode() }
        buttonApplySize.setOnClickListener { applyNumericalSize() }

        // Connecting is left to onResume, which always follows onCreate.
        // Doing it in both places raced two sessions against each other.
        observeConnection()
    }

    private fun bindViews() {
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
        inputPanel = findViewById(R.id.input_panel)
        editTextWidth = findViewById(R.id.edit_text_width)
        editTextHeight = findViewById(R.id.edit_text_height)
        buttonApplySize = findViewById(R.id.button_apply_size)
    }

    // --- Touch routing ----------------------------------------------------

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Only swallow touches when there is actually a session to send them
        // to. While disconnected the overlay owns the screen, and capturing
        // input here made its Retry button unclickable — the button sits inside
        // the active area, so the tablet pipeline consumed the press first.
        if (isSetupMode || !isConnected) return super.dispatchTouchEvent(event)

        // Visible controls keep their own touches; everything else feeds the
        // tablet pipeline, whether or not decorative views sit under the finger.
        if (event.actionMasked == MotionEvent.ACTION_DOWN && isOverVisibleControl(event)) {
            return super.dispatchTouchEvent(event)
        }
        if (router.onTouchEvent(event)) {
            // Ask the compositor to stop batching input for this gesture. This
            // is the supported way to cut a frame of latency off a drawing or
            // gaming surface.
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                window.decorView.requestUnbufferedDispatch(event)
            }
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (!isSetupMode && router.onHoverEvent(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    /**
     * True when the press lands on a control the user can currently operate.
     *
     * Uses screen coordinates on both sides: getHitRect would give bounds in
     * each view's own parent, which is wrong for anything nested inside the
     * status overlay.
     */
    private fun isOverVisibleControl(event: MotionEvent): Boolean {
        val x = event.rawX.toInt()
        val y = event.rawY.toInt()
        return interactiveControls.any { control ->
            control.isShown && control.getGlobalVisibleRect(controlBounds) &&
                controlBounds.contains(x, y)
        }
    }

    // --- Area editing -----------------------------------------------------

    override fun onAreaChanging(newArea: RectF) {
        updateInputFields(newArea)
        router.setArea(newArea)
    }

    override fun onAreaChanged(newArea: RectF) {
        updateInputFields(newArea)
        router.setArea(newArea)
    }

    private fun applyNumericalSize() {
        val widthMm = editTextWidth.text.toString().toFloatOrNull()
        val heightMm = editTextHeight.text.toString().toFloatOrNull()
        if (widthMm == null || heightMm == null || widthMm <= 0f || heightMm <= 0f) {
            updateInputFields(editableAreaView.getArea())
            return
        }
        editableAreaView.resizeArea(widthMm * pixelsPerMmX, heightMm * pixelsPerMmY)
    }

    private fun updateInputFields(area: RectF) {
        editTextWidth.setText(String.format(Locale.US, "%.1f", area.width() / pixelsPerMmX))
        editTextHeight.setText(String.format(Locale.US, "%.1f", area.height() / pixelsPerMmY))
    }

    private fun enterSetupMode() {
        isSetupMode = true
        // Any stroke in flight must terminate before input stops flowing, or
        // the PC is left holding a button.
        router.reset()
        originalArea.set(editableAreaView.getArea())
        editableAreaView.setSetupMode(true)
        fabSetup.visibility = View.GONE
        setupControls.visibility = View.VISIBLE
        inputPanel.visibility = View.VISIBLE
        updateInputFields(originalArea)
    }

    private fun saveAndExitSetupMode() {
        areaStore.save(editableAreaView.getNormalizedArea())
        exitSetupMode()
    }

    private fun cancelSetupMode() {
        editableAreaView.setArea(originalArea)
        exitSetupMode()
    }

    private fun exitSetupMode() {
        isSetupMode = false
        editableAreaView.setSetupMode(false)
        fabSetup.visibility = View.VISIBLE
        setupControls.visibility = View.GONE
        inputPanel.visibility = View.GONE
        router.setArea(editableAreaView.getArea())
    }

    // --- Connection status ------------------------------------------------

    private fun observeConnection() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                connection.state.collectLatest(::renderConnectionState)
            }
        }
    }

    private fun renderConnectionState(state: ConnectionState) {
        val connected = state is ConnectionState.Connected
        if (isConnected && !connected) {
            // The link died mid-stroke; drop our local stroke state so the next
            // connection does not resume from a stale pointer.
            router.reset()
        }
        isConnected = connected
        renderStatus(state)
    }

    private fun renderStatus(state: ConnectionState) = when (state) {
        is ConnectionState.Idle ->
            updateStatusUI(getString(R.string.status_idle), "", showRefresh = true)

        is ConnectionState.Connecting ->
            updateStatusUI(getString(R.string.status_connecting), "", showRefresh = false)

        is ConnectionState.Connected -> updateStatusUI(
            title = getString(R.string.status_connected),
            subtitle = getString(R.string.status_connected_to, state.hostname),
            showRefresh = false,
            hideContent = true,
        )

        is ConnectionState.Disconnected -> updateStatusUI(
            title = state.reason,
            subtitle = getString(R.string.status_check_server),
            showRefresh = true,
        )
    }

    private fun updateStatusUI(
        title: String,
        subtitle: String,
        showRefresh: Boolean,
        hideContent: Boolean = false,
    ) {
        statusText.text = title
        statusSubtext.text = subtitle
        statusSubtext.visibility = if (subtitle.isNotEmpty()) View.VISIBLE else View.GONE
        buttonRefresh.visibility = if (showRefresh) View.VISIBLE else View.GONE
        statusContentContainer.visibility = if (hideContent) View.GONE else View.VISIBLE
        statusLayout.visibility = View.VISIBLE
    }

    // --- Clock and battery ------------------------------------------------

    private fun startClock() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val format = SimpleDateFormat("h:mm a", Locale.getDefault())
                while (true) {
                    textTime.text = format.format(Date())
                    delay(CLOCK_INTERVAL_MS)
                }
            }
        }
    }

    private fun registerBatteryReceiver() {
        if (batteryReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level < 0 || scale <= 0) return
                textBattery.text = getString(R.string.battery_percent, level * 100 / scale)
            }
        }
        // Android 14 requires an explicit export flag; omitting it throws.
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        batteryReceiver = receiver
    }

    private fun unregisterBatteryReceiver() {
        batteryReceiver?.let { runCatching { unregisterReceiver(it) } }
        batteryReceiver = null
    }

    // --- Window -----------------------------------------------------------

    private fun makeAppFullscreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
        }
    }

    // --- Lifecycle --------------------------------------------------------

    override fun onStart() {
        super.onStart()
        startClock()
        registerBatteryReceiver()
    }

    override fun onResume() {
        super.onResume()
        router.setArea(editableAreaView.getArea())
        connection.connect()
    }

    override fun onPause() {
        super.onPause()
        // Leaving the foreground stops the event stream. Terminate the stroke
        // explicitly so a held button never outlives the app being visible.
        router.reset()
    }

    override fun onStop() {
        super.onStop()
        unregisterBatteryReceiver()
        // Stop retrying a USB link nobody is looking at; onResume reconnects.
        connection.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        connection.close()
    }

    private companion object {
        const val MM_PER_INCH = 25.4f
        const val CLOCK_INTERVAL_MS = 1_000L
    }
}
