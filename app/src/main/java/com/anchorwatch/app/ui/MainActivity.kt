package com.anchorwatch.app.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.anchorwatch.app.R
import com.anchorwatch.app.service.AnchorAlarmService
import com.anchorwatch.app.service.AnchorLogger
import com.anchorwatch.app.service.GpsService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    // Permission request codes
    private val RC_LOCATION = 100
    private val RC_BACKGROUND_LOCATION = 101
    private val RC_SMS = 102
    private val RC_NOTIFICATIONS = 103
    private val RC_BATTERY = 104
    private val RC_PHONE = 105
    private var pendingBatteryOptimizationRequest = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        supportActionBar?.title = "AnchorWatch"
        setupUI()
        startPermissionFlow()
    }

    // ---------------------------------------------------------------
    // Permission flow — sequential, each step waits for the previous
    // ---------------------------------------------------------------

    private fun startPermissionFlow() {
        when {
            !hasLocationPermission() -> requestLocationPermission()
            !hasBackgroundLocationPermission() -> requestBackgroundLocation()
            !hasSmsPermissions() -> requestSmsPermissions()
            !hasNotificationPermission() -> requestNotificationPermission()
            !hasPhonePermissions() -> requestPhonePermissions()
            !isBatteryOptimizationExempt() -> requestBatteryOptimization()
            else -> startServicesAndWelcome()
        }
    }

    // Step 1 — Fine location
    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestLocationPermission() {
        AlertDialog.Builder(this)
            .setTitle("Location Permission Required")
            .setMessage(
                "AnchorWatch needs access to your precise location to monitor " +
                "your boat's position and detect anchor dragging.\n\n" +
                "Please grant location permission on the next screen.")
            .setPositiveButton("Continue") { _, _ ->
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ),
                    RC_LOCATION
                )
            }
            .setCancelable(false)
            .show()
    }

    // Step 2 — Background location (Android 10+)
    private fun hasBackgroundLocationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startPermissionFlow()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Background Location Required")
            .setMessage(
                "AnchorWatch must monitor your position even when the screen " +
                "is off or the app is in the background — otherwise the anchor " +
                "alarm cannot work while you sleep.\n\n" +
                "On the next screen, please select " +
                "\"Allow all the time\".")
            .setPositiveButton("Open Settings") { _, _ ->
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                    RC_BACKGROUND_LOCATION
                )
            }
            .setCancelable(false)
            .show()
    }

    // Step 3 — SMS
    private fun hasSmsPermissions() =
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestSmsPermissions() {
        AlertDialog.Builder(this)
            .setTitle("SMS Permission Required")
            .setMessage(
                "AnchorWatch needs to send and receive SMS messages to:\n\n" +
                "• Alert your chosen contact when the anchor drags\n" +
                "• Respond to position requests sent to the boat's phone\n\n" +
                "Please grant SMS permissions on the next screen.")
            .setPositiveButton("Continue") { _, _ ->
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.SEND_SMS,
                        Manifest.permission.RECEIVE_SMS,
                        Manifest.permission.READ_SMS
                    ),
                    RC_SMS
                )
            }
            .setCancelable(false)
            .show()
    }

    // Step 4 — Notifications (Android 13+)
    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            startPermissionFlow()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Notification Permission Required")
            .setMessage(
                "AnchorWatch uses a persistent notification to keep the GPS " +
                "service running reliably in the background.\n\n" +
                "Please allow notifications on the next screen.")
            .setPositiveButton("Continue") { _, _ ->
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    RC_NOTIFICATIONS
                )
            }
            .setCancelable(false)
            .show()
    }

    // Step 5 — Phone (CALL_PHONE + READ_PHONE_STATE)
    private fun hasPhonePermissions() =
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestPhonePermissions() {
        AlertDialog.Builder(this)
            .setTitle("Phone Permission Required")
            .setMessage(
                "AnchorWatch needs phone permission to:\n\n" +
                "\u2022 Call your emergency contact when the anchor drags\n" +
                "\u2022 Detect when the call has been answered\n\n" +
                "Please grant phone permissions on the next screen.")
            .setPositiveButton("Continue") { _, _ ->
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.CALL_PHONE,
                        Manifest.permission.READ_PHONE_STATE
                    ),
                    RC_PHONE
                )
            }
            .setCancelable(false)
            .show()
    }

    // Step 6 — Battery optimization exemption
    private fun isBatteryOptimizationExempt(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestBatteryOptimization() {
        AlertDialog.Builder(this)
            .setTitle("Disable Battery Optimization")
            .setMessage(
                "Android's battery optimization can shut down AnchorWatch " +
                "while you sleep — silencing the anchor drag alarm when you " +
                "need it most.\n\n" +
                "A popup will appear asking if you want to stop optimizing " +
                "battery usage for AnchorWatch.\n\n" +
                "Please tap \"Allow\". " +
                "The app will minimize briefly — just reopen it to continue.")
            .setPositiveButton("Open Battery Settings") { _, _ ->
                pendingBatteryOptimizationRequest = true
                try {
                    val intent = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // Some devices don't support direct intent — open general settings
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
            .setNegativeButton("Skip") { _, _ ->
                showBatterySkipWarningDialog()
            }
            .setCancelable(false)
            .show()
    }

    // Handle permission results — advance to next step
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            RC_LOCATION -> {
                if (grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startPermissionFlow()
                } else {
                    showPermissionDeniedDialog(
                        "Location permission is required for AnchorWatch to work.",
                        RC_LOCATION
                    )
                }
            }
            RC_BACKGROUND_LOCATION -> {
                // Proceed even if denied — warn user
                if (grantResults.isEmpty() ||
                    grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(
                        this,
                        "Warning: Alarm may not work with screen off without background location",
                        Toast.LENGTH_LONG
                    ).show()
                }
                startPermissionFlow()
            }
            RC_SMS -> {
                if (grantResults.isEmpty() ||
                    grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    if (!ActivityCompat.shouldShowRequestPermissionRationale(
                            this, Manifest.permission.SEND_SMS)) {
                        // "Don't ask again" — system won't show dialog; send user to settings
                        showGoToSettingsDialog(
                            "SMS Permission Blocked",
                            "SMS alerts are blocked. To enable them, open App Settings " +
                            "and grant SMS permission manually."
                        )
                    } else {
                        // Simple denial — warn and move on
                        Toast.makeText(
                            this,
                            "Warning: SMS alerts will not work without SMS permission",
                            Toast.LENGTH_LONG
                        ).show()
                        startPermissionFlow()
                    }
                } else {
                    startPermissionFlow()
                }
            }
            RC_NOTIFICATIONS -> {
                startPermissionFlow()
            }
            RC_PHONE -> {
                if (grantResults.isEmpty() ||
                    grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    if (!ActivityCompat.shouldShowRequestPermissionRationale(
                            this, Manifest.permission.CALL_PHONE)) {
                        // "Don't ask again" — send user to settings
                        showGoToSettingsDialog(
                            "Phone Permission Blocked",
                            "Phone call alerts are blocked. To enable them, open App Settings " +
                            "and grant Phone permission manually."
                        )
                    } else {
                        // Simple denial — warn and move on
                        Toast.makeText(
                            this,
                            "Warning: Phone calls will not work without phone permission",
                            Toast.LENGTH_LONG
                        ).show()
                        startPermissionFlow()
                    }
                } else {
                    startPermissionFlow()
                }
            }
        }
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            RC_NOTIFICATIONS -> {
                // User returned from App Settings after granting/denying notifications
                startPermissionFlow()
            }
            // RC_BATTERY omitted — battery optimisation dialogs do not reliably call
            // onActivityResult on all devices. Handled in onResume instead.
        }
    }

    override fun onResume() {
        super.onResume()
        if (pendingBatteryOptimizationRequest) {
            pendingBatteryOptimizationRequest = false
            if (!isBatteryOptimizationExempt()) {
                Toast.makeText(
                    this,
                    "Warning: Battery optimization may interrupt the anchor alarm",
                    Toast.LENGTH_LONG
                ).show()
            }
            startServicesAndWelcome()
        }
    }

    private fun showBatterySkipWarningDialog() {
        AlertDialog.Builder(this)
            .setTitle("⚠️ This Could Silence Your Alarm")
            .setMessage(
                "Without this setting, Android may put AnchorWatch to sleep " +
                "while you're below deck or resting — even though the app is " +
                "running.\n\n" +
                "If that happens, the anchor drag alarm will not sound and " +
                "no SMS or phone call will be made.\n\n" +
                "We strongly recommend enabling this before you rely on the app. " +
                "Would you like to try again?")
            .setPositiveButton("Try Again") { _, _ ->
                requestBatteryOptimization()
            }
            .setNegativeButton("Skip Anyway") { _, _ ->
                startServicesAndWelcome()
            }
            .setCancelable(false)
            .show()
    }

    private fun showWelcomeDialog() {
        AlertDialog.Builder(this)
            .setTitle("Welcome to AnchorWatch ⚓")
            .setMessage(
                "Setup is complete — AnchorWatch is now running.\n\n" +
                "Before you set your first anchor, please take a moment to " +
                "read the Help guide. It explains how to set your alarm radius, " +
                "configure SMS alerts, and get the best results from the app.\n\n" +
                "You'll find Help in the menu (⋮) at the top right of this screen.")
            .setPositiveButton("Open Help Now") { _, _ ->
                startActivity(Intent(this, HelpActivity::class.java))
            }
            .setNegativeButton("Maybe Later", null)
            .show()
    }

    private fun showGoToSettingsDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("$message\n\nAfter granting permission, return to AnchorWatch.")
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                })
                startPermissionFlow()
            }
            .setNegativeButton("Skip") { _, _ ->
                startPermissionFlow()
            }
            .setCancelable(false)
            .show()
    }

    private fun showPermissionDeniedDialog(message: String, requestCode: Int) {
        AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage("$message\n\nWould you like to try again?")
            .setPositiveButton("Try Again") { _, _ ->
                startPermissionFlow()
            }
            .setNegativeButton("Exit") { _, _ ->
                finishAffinity()
            }
            .setCancelable(false)
            .show()
    }

    // ---------------------------------------------------------------
    // Services
    // ---------------------------------------------------------------

    private fun startServicesAndWelcome() {
        startForegroundService(Intent(this, GpsService::class.java))
        startForegroundService(Intent(this, AnchorAlarmService::class.java))
        val prefs = getSharedPreferences("anchorwatch_prefs", MODE_PRIVATE)
        if (!prefs.getBoolean("welcome_shown", false)) {
            prefs.edit().putBoolean("welcome_shown", true).apply()
            showWelcomeDialog()
        }
    }

    // ---------------------------------------------------------------
    // UI
    // ---------------------------------------------------------------

    private fun setupUI() {
        val tvBoatPosition  = findViewById<TextView>(R.id.tvBoatPosition)
        val tvAnchorPosition = findViewById<TextView>(R.id.tvAnchorPosition)
        val tvGpsInfo       = findViewById<TextView>(R.id.tvGpsInfo)
        val tvAnchorStatus  = findViewById<TextView>(R.id.tvAnchorStatus)
        val tvDistance      = findViewById<TextView>(R.id.tvDistance)
        val tvAlarmStatus   = findViewById<TextView>(R.id.tvAlarmStatus)
        val btnSetAnchor    = findViewById<Button>(R.id.btnSetAnchor)
        val btnSetAnchorOffset = findViewById<Button>(R.id.btnSetAnchorOffset)
        val btnClearAnchor  = findViewById<Button>(R.id.btnClearAnchor)
        val btnSilence      = findViewById<Button>(R.id.btnSilence)

        // Boat position
        lifecycleScope.launch {
            GpsService.currentLocation.collect { location ->
                tvBoatPosition.text = if (location != null) {
                    "${"%.6f".format(location.latitude)}\n" +
                    "${"%.6f".format(location.longitude)}"
                } else {
                    "Waiting for GPS..."
                }
            }
        }

        // Anchor position
        lifecycleScope.launch {
            AnchorAlarmService.anchorLocation.collect { anchor ->
                tvAnchorPosition.text = if (anchor != null) {
                    "${"%.6f".format(anchor.latitude)}\n" +
                    "${"%.6f".format(anchor.longitude)}"
                } else {
                    "Not set"
                }
            }
        }

        // GPS info line — accuracy, SNR, satellite counts — colour driven by SNR
        lifecycleScope.launch {
            GpsService.averageSnr.collect { snr ->
                val accuracy = GpsService.lastAccuracy.value
                val used     = GpsService.satsUsed.value
                val visible  = GpsService.satsVisible.value
                val snrText  = if (snr >= 0f) "${"%.1f".format(snr)} dBHz" else "--"
                tvGpsInfo.text = "Acc: ${accuracy.toInt()}m  SNR: $snrText  Sats: $used/$visible"
                tvGpsInfo.setTextColor(snrColour(snr))
            }
        }
        // Also refresh the line when accuracy updates (SNR may not have changed)
        lifecycleScope.launch {
            GpsService.lastAccuracy.collect { accuracy ->
                val snr     = GpsService.averageSnr.value
                val used    = GpsService.satsUsed.value
                val visible = GpsService.satsVisible.value
                val snrText = if (snr >= 0f) "${"%.1f".format(snr)} dBHz" else "--"
                tvGpsInfo.text = "Acc: ${accuracy.toInt()}m  SNR: $snrText  Sats: $used/$visible"
                tvGpsInfo.setTextColor(snrColour(snr))
            }
        }

        lifecycleScope.launch {
            AnchorAlarmService.anchorSet.collect { set ->
                tvAnchorStatus.text = if (set) "⚓ Anchor Set" else "⚓ No Anchor Set"
                btnClearAnchor.isEnabled = set
            }
        }

        lifecycleScope.launch {
            AnchorAlarmService.distanceFromAnchor.collect { distance ->
                tvDistance.text = if (AnchorAlarmService.anchorSet.value)
                    "Distance from anchor: ${distance.toInt()}m"
                else ""
            }
        }

        lifecycleScope.launch {
            AnchorAlarmService.alarmActive.collect { active ->
                tvAlarmStatus.text = if (active) "🚨 ALARM ACTIVE" else ""
                if (active) {
                    btnSilence.isEnabled = true
                    btnSilence.text = "Cancel Alarm"
                }
            }
        }

        lifecycleScope.launch {
            AnchorAlarmService.alarmCancelled.collect { cancelled ->
                if (cancelled) {
                    btnSilence.isEnabled = true
                    btnSilence.text = "Re-Arm Alarm"
                } else if (!AnchorAlarmService.alarmActive.value) {
                    btnSilence.isEnabled = false
                    btnSilence.text = "Cancel Alarm"
                }
            }
        }

        btnSetAnchor.setOnClickListener {
            startService(Intent(this, AnchorAlarmService::class.java)
                .apply { action = AnchorAlarmService.ACTION_SET_ANCHOR })
        }

        btnSetAnchorOffset.setOnClickListener {
            showAnchorOffsetDialog()
        }

        btnClearAnchor.setOnClickListener {
            startService(Intent(this, AnchorAlarmService::class.java)
                .apply { action = AnchorAlarmService.ACTION_CLEAR_ANCHOR })
        }

        btnSilence.setOnClickListener {
            if (AnchorAlarmService.alarmCancelled.value) {
                // Currently showing "Re-Arm Alarm" — re-arm the watch
                startService(Intent(this, AnchorAlarmService::class.java)
                    .apply { action = AnchorAlarmService.ACTION_REARM })
            } else {
                // Currently showing "Cancel Alarm" — cancel the active alarm
                startService(Intent(this, AnchorAlarmService::class.java)
                    .apply { action = AnchorAlarmService.ACTION_SILENCE })
            }
        }
    }

    /**
     * Returns a display colour based on average SNR (dBHz).
     * < 10   → red       (very poor — blocked signal)
     * 10–20  → orange    (poor)
     * 20–30  → yellow    (fair)
     * 30–45  → light green (good)
     * > 45   → green     (excellent)
     * -1     → grey      (no data yet)
     */
    private fun snrColour(snr: Float): Int = when {
        snr < 0f   -> android.graphics.Color.parseColor("#9E9E9E") // grey — no data
        snr < 10f  -> android.graphics.Color.parseColor("#EF5350") // red
        snr < 20f  -> android.graphics.Color.parseColor("#FFA726") // orange
        snr < 30f  -> android.graphics.Color.parseColor("#FFEE58") // yellow
        snr < 45f  -> android.graphics.Color.parseColor("#AED581") // light green
        else       -> android.graphics.Color.parseColor("#66BB6A") // green
    }

    private fun showAnchorOffsetDialog() {
        // Tab bar
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val btnTabOffset = Button(this).apply {
            text = "Distance & Bearing"
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setBackgroundColor(android.graphics.Color.parseColor("#1565C0"))
            setTextColor(android.graphics.Color.WHITE)
            textSize = 12f
        }
        val btnTabDirect = Button(this).apply {
            text = "Lat / Lon"
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setBackgroundColor(android.graphics.Color.parseColor("#333333"))
            setTextColor(android.graphics.Color.WHITE)
            textSize = 12f
        }
        tabRow.addView(btnTabOffset)
        tabRow.addView(btnTabDirect)

        // Offset panel
        val panelOffset = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val tvDistLabel = TextView(this).apply { text = "Distance to anchor (metres)" }
        val etDistance = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "e.g. 30"
        }
        val tvBearLabel = TextView(this).apply {
            text = "Bearing to anchor (degrees 0–360)"
            setPadding(0, 20, 0, 0)
        }
        val etBearing = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "e.g. 270 for West"
        }
        panelOffset.addView(tvDistLabel)
        panelOffset.addView(etDistance)
        panelOffset.addView(tvBearLabel)
        panelOffset.addView(etBearing)

        // Direct lat/lon panel
        val panelDirect = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
        }
        val tvLatLabel = TextView(this).apply { text = "Anchor latitude (decimal degrees)" }
        val etLat = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            hint = "e.g. -17.123456"
        }
        val tvLonLabel = TextView(this).apply {
            text = "Anchor longitude (decimal degrees)"
            setPadding(0, 20, 0, 0)
        }
        val etLon = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            hint = "e.g. 168.456789"
        }
        panelDirect.addView(tvLatLabel)
        panelDirect.addView(etLat)
        panelDirect.addView(tvLonLabel)
        panelDirect.addView(etLon)

        // Root layout
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 20, 60, 20)
        }
        root.addView(tabRow)
        val spacer = android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 16)
        }
        root.addView(spacer)
        root.addView(panelOffset)
        root.addView(panelDirect)

        // Tab switching
        var activeTab = 0
        btnTabOffset.setOnClickListener {
            activeTab = 0
            panelOffset.visibility = android.view.View.VISIBLE
            panelDirect.visibility = android.view.View.GONE
            btnTabOffset.setBackgroundColor(android.graphics.Color.parseColor("#1565C0"))
            btnTabDirect.setBackgroundColor(android.graphics.Color.parseColor("#333333"))
        }
        btnTabDirect.setOnClickListener {
            activeTab = 1
            panelOffset.visibility = android.view.View.GONE
            panelDirect.visibility = android.view.View.VISIBLE
            btnTabDirect.setBackgroundColor(android.graphics.Color.parseColor("#1565C0"))
            btnTabOffset.setBackgroundColor(android.graphics.Color.parseColor("#333333"))
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Set Anchor Position")
            .setView(root)
            .setPositiveButton("Set Anchor", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (activeTab == 0) {
                    // Offset mode
                    val distance = etDistance.text.toString().toFloatOrNull()
                    val bearing  = etBearing.text.toString().toFloatOrNull()
                    if (distance != null && bearing != null) {
                        startService(Intent(this, AnchorAlarmService::class.java).apply {
                            action = AnchorAlarmService.ACTION_SET_ANCHOR_OFFSET
                            putExtra(AnchorAlarmService.EXTRA_DISTANCE, distance)
                            putExtra(AnchorAlarmService.EXTRA_BEARING, bearing)
                        })
                        dialog.dismiss()
                    } else {
                        Toast.makeText(this,
                            "Please enter valid distance and bearing",
                            Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // Direct lat/lon mode
                    val lat = etLat.text.toString().toDoubleOrNull()
                    val lon = etLon.text.toString().toDoubleOrNull()
                    if (lat != null && lon != null &&
                        lat in -90.0..90.0 && lon in -180.0..180.0) {
                        startService(Intent(this, AnchorAlarmService::class.java).apply {
                            action = AnchorAlarmService.ACTION_SET_ANCHOR_DIRECT
                            putExtra(AnchorAlarmService.EXTRA_LAT, lat)
                            putExtra(AnchorAlarmService.EXTRA_LON, lon)
                        })
                        dialog.dismiss()
                    } else {
                        Toast.makeText(this,
                            "Please enter valid latitude (−90 to 90) and longitude (−180 to 180)",
                            Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        dialog.show()
    }

    // ---------------------------------------------------------------
    // Menu
    // ---------------------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.menu_toggle_logging)?.title =
            if (AnchorLogger.enabled) "Disable Logging" else "Enable Logging"
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.menu_map -> {
                startActivity(Intent(this, MapActivity::class.java))
                true
            }
            R.id.menu_help -> {
                startActivity(Intent(this, HelpActivity::class.java))
                true
            }
            R.id.menu_toggle_logging -> {
                AnchorLogger.enabled = !AnchorLogger.enabled
                val msg = if (AnchorLogger.enabled)
                    "Logging ON — writing to ${AnchorLogger.getLogFile().name}"
                else
                    "Logging OFF"
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                invalidateOptionsMenu()
                true
            }
            R.id.menu_share_log -> {
                shareLogFile()
                true
            }
            R.id.menu_exit -> {
                exitApp()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun shareLogFile() {
        val file = AnchorLogger.getLogFile()
        if (!file.exists() || file.length() == 0L) {
            Toast.makeText(this, "No log file yet — enable logging first", Toast.LENGTH_LONG).show()
            return
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "AnchorWatch Log")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share log file"))
    }

    private fun exitApp() {
        AlertDialog.Builder(this)
            .setTitle("Exit AnchorWatch")
            .setMessage("This will stop all monitoring. Are you sure?")
            .setPositiveButton("Exit") { _, _ ->
                stopService(Intent(this, AnchorAlarmService::class.java))
                stopService(Intent(this, GpsService::class.java))
                finishAffinity()
                android.os.Process.killProcess(android.os.Process.myPid())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
