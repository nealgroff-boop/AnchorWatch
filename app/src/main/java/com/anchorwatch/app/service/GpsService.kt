package com.anchorwatch.app.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.anchorwatch.app.ui.MainActivity
import com.google.android.gms.location.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.*

class GpsService : Service() {

    companion object {
        const val CHANNEL_ID  = "anchorwatch_channel"
        const val NOTIFICATION_ID = 1
        const val TAG = "GpsService"
        const val ACTION_RESTART = "com.anchorwatch.app.GPS_RESTART"

        val currentLocation    = MutableStateFlow<Location?>(null)
        val gpsLostSeconds     = MutableStateFlow(0L)
        val lastAccuracy       = MutableStateFlow(0f)
        val trackPoints        = MutableStateFlow<List<Location>>(emptyList())
        val worstGpsGapSeconds = MutableStateFlow(0L)
        var trackMinDistanceM  = 5f

        /** Radial velocity (m/s) from the EKF — positive = moving away from anchor. */
        val radialVelocityMs   = MutableStateFlow(0f)

        /** Running instance — set in onCreate(), cleared in onDestroy(). */
        var instance: GpsService? = null

        val averageSnr   = MutableStateFlow(-1f)
        val satsUsed     = MutableStateFlow(0)
        val satsVisible  = MutableStateFlow(0)
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager

    private val handler = Handler(Looper.getMainLooper())
    private var gpsLossCounter  = 0L
    private var firstFixAcquired = false
    private var gpsLossRunning   = false

    var refreshIntervalMs: Long  = 3000L
    var accuracyThresholdM: Float = 15f

    // ── EKF ───────────────────────────────────────────────────────────
    private val ekf = KalmanLocationFilter()

    // ── IMU state ──────────────────────────────────────────────────────────
    // Rotation matrix (3×3) from device frame → world (NED) frame, from
    // TYPE_ROTATION_VECTOR.  Initialised to identity.
    private val rotationMatrix = FloatArray(9) { if (it % 4 == 0) 1f else 0f }
    private var lastImuTimeNs  = 0L
    private var imuReady       = false  // true once first rotation vector received

    private val imuListener = object : SensorEventListener {

        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {

                Sensor.TYPE_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    imuReady = true
                }

                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    if (!imuReady) return
                    val nowNs = event.timestamp
                    if (lastImuTimeNs == 0L) { lastImuTimeNs = nowNs; return }
                    val dtS = ((nowNs - lastImuTimeNs) / 1e9).coerceIn(0.0, 0.1)
                    lastImuTimeNs = nowNs

                    // Rotate device-frame linear acceleration into world NED frame.
                    // event.values = [ax, ay, az] in device frame (gravity removed).
                    val ax = event.values[0]; val ay = event.values[1]; val az = event.values[2]
                    val R  = rotationMatrix
                    // World-frame: x=East, y=North, z=Up  (Android convention)
                    val worldX = R[0]*ax + R[1]*ay + R[2]*az   // East
                    val worldY = R[3]*ax + R[4]*ay + R[5]*az   // North

                    ekf.predict(aN = worldY.toDouble(), aE = worldX.toDouble(), dtS = dtS)

                    // Publish radial velocity at IMU rate (smooth indicator for UI)
                    radialVelocityMs.value = ekf.radialVelocityMs.toFloat()
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    // ── GNSS status ────────────────────────────────────────────────────────
    private var lastSatLogTime = 0L
    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var usedCount = 0; var visCount = 0; var snrSum = 0f
            for (i in 0 until status.satelliteCount) {
                visCount++
                if (status.usedInFix(i)) { usedCount++; snrSum += status.getCn0DbHz(i) }
            }
            satsVisible.value = visCount
            satsUsed.value    = usedCount
            averageSnr.value  = if (usedCount > 0) snrSum / usedCount else -1f
            // Log satellite status at most once every 30 seconds to keep file size down
            val now = System.currentTimeMillis()
            if (now - lastSatLogTime > 30_000L) {
                lastSatLogTime = now
                AnchorLogger.log(TAG, "SATS  used=$usedCount  visible=$visCount" +
                        "  avgSnr=${"%.1f".format(averageSnr.value)}dB")
            }
        }
    }

    private val gpsLossRunnable = object : Runnable {
        override fun run() {
            gpsLossCounter++
            gpsLostSeconds.value = gpsLossCounter
            if (gpsLossCounter > worstGpsGapSeconds.value) worstGpsGapSeconds.value = gpsLossCounter
            handler.postDelayed(this, 1000)
        }
    }

    // ── Lifecycle ───────────────────────────────��─────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationManager     = getSystemService(LOCATION_SERVICE) as LocationManager
        sensorManager       = getSystemService(SENSOR_SERVICE)   as SensorManager
        acquireWakeLock()
        checkBatteryOptimisation()
        createNotificationChannel()
        try { locationManager.registerGnssStatusCallback(gnssStatusCallback, handler) }
        catch (e: SecurityException) { Log.w(TAG, "GNSS status unavailable: ${e.message}") }
        registerImuSensors()
        AnchorLogger.log(TAG, "SERVICE-START  build=${Build.MANUFACTURER}/${Build.MODEL}  sdk=${Build.VERSION.SDK_INT}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("AnchorWatch running"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        startLocationUpdates()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        AnchorLogger.log(TAG, "SERVICE-DESTROY")
        fusedLocationClient.removeLocationUpdates(locationCallback)
        locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
        sensorManager.unregisterListener(imuListener)
        handler.removeCallbacks(gpsLossRunnable)
        if (wakeLock.isHeld) wakeLock.release()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── IMU registration ──────────────────────────────────────────────────────

    private fun registerImuSensors() {
        val rotVec  = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val linAcc  = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

        if (rotVec == null || linAcc == null) {
            Log.w(TAG, "IMU sensors unavailable — EKF will run GPS-only mode")
            return
        }
        // SENSOR_DELAY_GAME ≈ 50 Hz — fast enough for good velocity estimates
        // without draining the battery excessively at anchor.
        sensorManager.registerListener(imuListener, rotVec, SensorManager.SENSOR_DELAY_GAME, handler)
        sensorManager.registerListener(imuListener, linAcc, SensorManager.SENSOR_DELAY_GAME, handler)
        Log.d(TAG, "IMU sensors registered (rotation vector + linear acceleration)")
    }

    // ── GPS updates ────────────────────────────────────────────────────────

    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, refreshIntervalMs)
            .setMinUpdateIntervalMillis(refreshIntervalMs)
            .setMinUpdateDistanceMeters(0f)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val raw = result.lastLocation ?: return
                if (raw.accuracy >= accuracyThresholdM) {
                    Log.d(TAG, "Pre-rejected: accuracy ${raw.accuracy}m >= ${accuracyThresholdM}m")
                    AnchorLogger.log(TAG, "PRE-REJECT  acc=${raw.accuracy.toInt()}m  threshold=${accuracyThresholdM.toInt()}m")
                    return
                }

                val smoothed = ekf.process(raw) ?: run {
                    Log.w(TAG, "EKF returned null — using raw fix")
                    publishLocation(raw); return
                }

                if (gpsLossRunning) {
                    handler.removeCallbacks(gpsLossRunnable)
                    gpsLossRunning = false
                    if (gpsLossCounter > worstGpsGapSeconds.value) worstGpsGapSeconds.value = gpsLossCounter
                    AnchorLogger.log(TAG, "GPS-RESTORED  outage=${gpsLossCounter}s")
                    gpsLossCounter = 0; gpsLostSeconds_reset()
                }
                firstFixAcquired = true
                radialVelocityMs.value = ekf.radialVelocityMs.toFloat()
                publishLocation(smoothed)
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                if (!availability.isLocationAvailable && firstFixAcquired && !gpsLossRunning) {
                    gpsLossRunning = true
                    AnchorLogger.log(TAG, "GPS-LOST  satsUsed=${satsUsed.value}  satsVisible=${satsVisible.value}")
                    handler.post(gpsLossRunnable)
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission not granted: ${e.message}")
        }
    }

    private fun gpsLostSeconds_reset() { gpsLostSeconds.value = 0 }

    // ── Public API ──��───────────────────────────────────────────────────────

    /**
     * Reset the EKF.  Call when anchor is cleared so the new session starts
     * with a clean state.  Also clears the anchor reference in the filter so
     * radial velocity reverts to zero until a new anchor is set.
     */
    fun resetFilter() {
        ekf.reset()
        ekf.anchorSet = false
        Log.d(TAG, "EKF reset")
        AnchorLogger.log(TAG, "FILTER-RESET")
    }

    /**
     * Tell the EKF where the anchor is so it can compute radial velocity.
     * Call this whenever an anchor is set or restored.
     */
    fun setAnchorReference(lat: Double, lon: Double) {
        ekf.anchorLat = lat
        ekf.anchorLon = lon
        ekf.anchorSet = true
        Log.d(TAG, "EKF anchor reference set: $lat, $lon")
        AnchorLogger.log(TAG, "ANCHOR-REF  lat=%.6f  lon=%.6f".format(lat, lon))
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun publishLocation(location: Location) {
        lastAccuracy.value    = location.accuracy
        currentLocation.value = location
        addTrackPoint(location)
    }

    private fun addTrackPoint(location: Location) {
        val current = trackPoints.value
        val last    = current.lastOrNull()
        if (last == null || location.distanceTo(last) >= trackMinDistanceM)
            trackPoints.value = current + location
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AnchorWatch::GpsWakeLock")
        wakeLock.acquire(12 * 60 * 60 * 1000L)
    }

    private fun checkBatteryOptimisation() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val exempt = pm.isIgnoringBatteryOptimizations(packageName)
        AnchorLogger.log(TAG, "BATTERY-OPT-EXEMPT=$exempt")
        if (!exempt) {
            Log.w(TAG, "Battery optimisation NOT disabled — service may be killed by OS")
            // Post a high-priority notification so the user sees it even with screen off
            val nm = getSystemService(NotificationManager::class.java)
            val warnChannel = NotificationChannel(
                "anchorwatch_warn", "AnchorWatch Warnings",
                NotificationManager.IMPORTANCE_HIGH
            )
            nm.createNotificationChannel(warnChannel)
            val pi = PendingIntent.getActivity(this, 99,
                Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                PendingIntent.FLAG_IMMUTABLE)
            val note = NotificationCompat.Builder(this, "anchorwatch_warn")
                .setContentTitle("⚠ Battery Optimisation Active")
                .setContentText("AnchorWatch may be shut down overnight. Tap to disable.")
                .setSmallIcon(com.anchorwatch.app.R.drawable.ic_anchor)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(99, note)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "AnchorWatch",
            NotificationManager.IMPORTANCE_LOW).apply { description = "GPS anchor watch active" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AnchorWatch").setContentText(text)
            .setSmallIcon(com.anchorwatch.app.R.drawable.ic_anchor)
            .setContentIntent(pi).setOngoing(true).build()
    }
}
