package com.anchorwatch.app.service

import android.app.*
import android.content.Intent
import android.location.Location
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.*
import android.content.pm.PackageManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.anchorwatch.app.repository.SettingsRepository
import com.anchorwatch.app.service.AnchorLogger
import com.anchorwatch.app.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.math.*

class AnchorAlarmService : Service() {

    companion object {
        const val TAG = "AnchorAlarmService"
        const val CHANNEL_ID = "anchor_alarm_channel"
        const val NOTIFICATION_ID = 2

        const val ACTION_SET_ANCHOR = "SET_ANCHOR"
        const val ACTION_CLEAR_ANCHOR = "CLEAR_ANCHOR"
        const val ACTION_SILENCE = "SILENCE_ALARM"
        const val ACTION_REARM = "REARM_ALARM"
        const val ACTION_TEST_SMS = "TEST_SMS"
        const val ACTION_SET_ANCHOR_OFFSET = "SET_ANCHOR_OFFSET"
        const val ACTION_SET_ANCHOR_DIRECT = "SET_ANCHOR_DIRECT"

        const val EXTRA_DISTANCE = "EXTRA_DISTANCE"
        const val EXTRA_BEARING = "EXTRA_BEARING"
        const val EXTRA_LAT = "EXTRA_LAT"
        const val EXTRA_LON = "EXTRA_LON"

        const val AUTO_SILENCE_MS = 300000L
        const val REQUIRED_CONSECUTIVE_FIXES = 3
        const val MAX_ANCHOR_SPEED_MS = 2.5f

        val anchorLocation = MutableStateFlow<Location?>(null)
        val distanceFromAnchor = MutableStateFlow(0f)
        val alarmActive = MutableStateFlow(false)
        val anchorSet = MutableStateFlow(false)
        val alarmCancelled = MutableStateFlow(false)  // true after user cancels; cleared on re-arm or anchor reset
    }

    private lateinit var repo: SettingsRepository
    private var mediaPlayer: MediaPlayer? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    private var cachedRadius: Float = SettingsRepository.DEFAULT_ALARM_RADIUS_M
    private var smsSent = false
    private var alarmSilenced = false  // set when crew silence the alarm; blocks retriggering until anchor is reset
    private var alarmTriggeredTime = 0L
    private val ALARM_COOLDOWN_MS = 60000L
    private var consecutiveDragCount = 0
    private var lastFixTimeMs = 0L          // wallclock time of last accepted GPS fix
    private val WATCHDOG_INTERVAL_MS = 15_000L   // check every 15 seconds
    private val WATCHDOG_TIMEOUT_MS  = 60_000L   // restart GpsService after 60s no fix
    private var lastValidLocation: Location? = null
    private var callAnswered = false
    private var alarmPhoneNumber = ""
    private var callAttemptCount = 0
    private val MAX_CALL_ATTEMPTS = 10

    private val CALL_RETRY_INTERVAL_MS = 3 * 60 * 1000L  // 3 minutes

    private val callRetryRunnable = object : Runnable {
        override fun run() {
            if (alarmActive.value && !callAnswered && alarmPhoneNumber.isNotEmpty()) {
                if (callAttemptCount < MAX_CALL_ATTEMPTS) {
                    callAttemptCount++
                    Log.d(TAG, "Retrying phone call to $alarmPhoneNumber (attempt $callAttemptCount/$MAX_CALL_ATTEMPTS)")
                    makePhoneCall(alarmPhoneNumber)
                    handler.postDelayed(this, CALL_RETRY_INTERVAL_MS)
                } else {
                    Log.d(TAG, "Max call attempts ($MAX_CALL_ATTEMPTS) reached — stopping retries")
                }
            }
        }
    }
      
private val callStateCallback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
                Log.d(TAG, "Call answered — stopping retry loop")
                callAnswered = true
                handler.removeCallbacks(callRetryRunnable)
            }
        }
    }
    

    private val autoSilenceRunnable = Runnable {
        Log.d(TAG, "Auto-silencing after 5 minutes")
        silenceAlarm()
    }

    private val watchRunnable = object : Runnable {
        override fun run() {
            checkAnchor()
            handler.postDelayed(this, 1000)
        }
    }

    /** Watchdog — checks every 15 s that GpsService is still delivering fixes.
     *  If no fix arrives for 60 s it stops and restarts GpsService. */
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val silentMs = now - lastFixTimeMs
            if (lastFixTimeMs > 0 && silentMs > WATCHDOG_TIMEOUT_MS) {
                Log.w(TAG, "WATCHDOG: no GPS fix for ${silentMs / 1000}s — restarting GpsService")
                AnchorLogger.log(TAG, "WATCHDOG-RESTART  silentMs=$silentMs")
                restartGpsService()
                lastFixTimeMs = now   // reset so we don't re-trigger immediately
            }
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private fun restartGpsService() {
        try {
            stopService(Intent(this, GpsService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "GpsService stop failed: ${e.message}")
        }
        handler.postDelayed({
            try {
                startForegroundService(Intent(this, GpsService::class.java))
                AnchorLogger.log(TAG, "WATCHDOG-GPS-RESTARTED")
            } catch (e: Exception) {
                Log.e(TAG, "GpsService restart failed: ${e.message}")
                AnchorLogger.log(TAG, "WATCHDOG-RESTART-FAILED  err=${e.message}")
            }
        }, 2000L)  // 2 second gap so old service fully unbinds before new one starts
    }

    override fun onCreate() {
        super.onCreate()
        repo = SettingsRepository(this)
        createNotificationChannel()
        resetAlarmState()
        // Register phone state listener to detect answered calls (requires READ_PHONE_STATE)
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED) {
            val telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
            telephonyManager.registerTelephonyCallback(mainExecutor, callStateCallback)
        } else {
            Log.w(TAG, "READ_PHONE_STATE not granted — call state detection disabled")
        }
        // Load any previously saved anchor position
        serviceScope.launch {
            loadSavedAnchor()
        }
        // Cache alarm radius — collect once for the service lifetime instead of
        // reading DataStore on every checkAnchor() tick
        serviceScope.launch {
            repo.alarmRadius.collect { cachedRadius = it }
        }
        // Start GPS watchdog
        handler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
        AnchorLogger.log(TAG, "ALARM-SERVICE-START")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Anchor watch active"))

        when (intent?.action) {
            ACTION_SET_ANCHOR -> setAnchor()
            ACTION_SET_ANCHOR_OFFSET -> {
                val distance = intent.getFloatExtra(EXTRA_DISTANCE, 0f)
                val bearing = intent.getFloatExtra(EXTRA_BEARING, 0f)
                setAnchorWithOffset(distance, bearing)
            }
            ACTION_SET_ANCHOR_DIRECT -> {
                val lat = intent.getDoubleExtra(EXTRA_LAT, Double.MAX_VALUE)
                val lon = intent.getDoubleExtra(EXTRA_LON, Double.MAX_VALUE)
                if (lat != Double.MAX_VALUE && lon != Double.MAX_VALUE)
                    setAnchorAtPosition(lat, lon)
            }
            ACTION_CLEAR_ANCHOR -> clearAnchor()
            ACTION_SILENCE -> silenceAlarm()
            ACTION_REARM -> rearmAlarm()
            ACTION_TEST_SMS -> testSms()
            else -> {
                handler.removeCallbacks(watchRunnable)
                handler.post(watchRunnable)
            }
        }
        return START_STICKY
    }

    private suspend fun loadSavedAnchor() {
        val isSet = repo.savedAnchorSet.first()
        if (isSet) {
            val lat = repo.savedAnchorLat.first()
            val lon = repo.savedAnchorLon.first()
            if (lat != 0.0 && lon != 0.0) {
                val savedLoc = Location("saved").apply {
                    latitude = lat
                    longitude = lon
                }
                anchorLocation.value = savedLoc
                anchorSet.value = true
                GpsService.instance?.setAnchorReference(lat, lon)
                Log.d(TAG, "Restored saved anchor: $lat, $lon")
                updateNotification("Anchor restored — watching")
            }
        }
    }

    private fun resetAlarmState() {
        alarmActive.value = false
        anchorSet.value = false
        anchorLocation.value = null
        distanceFromAnchor.value = 0f
        alarmCancelled.value = false
        smsSent = false
        alarmSilenced = false
        alarmTriggeredTime = 0L
        consecutiveDragCount = 0
        lastValidLocation = null
        stopAlarmSound()
        handler.removeCallbacks(autoSilenceRunnable)
    }

    private fun setAnchor() {
        val current = GpsService.currentLocation.value
        if (current != null) {
            anchorLocation.value = current
            anchorSet.value = true
            smsSent = false
            alarmSilenced = false
            alarmCancelled.value = false
            alarmTriggeredTime = 0L
            consecutiveDragCount = 0
            lastValidLocation = current
            silenceAlarm()
            // Save to disk
            serviceScope.launch {
                repo.saveAnchorPosition(current.latitude, current.longitude)
            }
            GpsService.instance?.setAnchorReference(current.latitude, current.longitude)
            Log.d(TAG, "Anchor set at ${current.latitude}, ${current.longitude}")
            AnchorLogger.log(TAG, "ANCHOR-SET  lat=${"%.6f".format(current.latitude)}" +
                    "  lon=${"%.6f".format(current.longitude)}  gpsAcc=${current.accuracy.toInt()}m")
            updateNotification("Anchor set — watching")
        }
    }

    private fun setAnchorWithOffset(distanceMetres: Float, bearingDegrees: Float) {
        val current = GpsService.currentLocation.value ?: return
        val earthRadius = 6371000.0
        val lat1 = Math.toRadians(current.latitude)
        val lon1 = Math.toRadians(current.longitude)
        val bearing = Math.toRadians(bearingDegrees.toDouble())
        val d = distanceMetres.toDouble()

        val lat2 = asin(
            sin(lat1) * cos(d / earthRadius) +
            cos(lat1) * sin(d / earthRadius) * cos(bearing)
        )
        val lon2 = lon1 + atan2(
            sin(bearing) * sin(d / earthRadius) * cos(lat1),
            cos(d / earthRadius) - sin(lat1) * sin(lat2)
        )

        val anchorLoc = Location("offset").apply {
            latitude = Math.toDegrees(lat2)
            longitude = Math.toDegrees(lon2)
        }
        anchorLocation.value = anchorLoc
        anchorSet.value = true
        smsSent = false
        alarmSilenced = false
        alarmCancelled.value = false
        alarmTriggeredTime = 0L
        consecutiveDragCount = 0
        lastValidLocation = current
        silenceAlarm()
        // Save to disk
        serviceScope.launch {
            repo.saveAnchorPosition(anchorLoc.latitude, anchorLoc.longitude)
        }
        GpsService.instance?.setAnchorReference(anchorLoc.latitude, anchorLoc.longitude)
        AnchorLogger.log(TAG, "ANCHOR-SET-OFFSET  lat=${"%.6f".format(anchorLoc.latitude)}" +
                "  lon=${"%.6f".format(anchorLoc.longitude)}" +
                "  dist=${distanceMetres.toInt()}m  bearing=${bearingDegrees.toInt()}°")
        updateNotification("Anchor set by offset — watching")
    }

    private fun setAnchorAtPosition(lat: Double, lon: Double) {
        val anchorLoc = Location("direct").apply {
            latitude = lat
            longitude = lon
        }
        anchorLocation.value = anchorLoc
        anchorSet.value = true
        smsSent = false
        alarmSilenced = false
        alarmCancelled.value = false
        alarmTriggeredTime = 0L
        consecutiveDragCount = 0
        lastValidLocation = GpsService.currentLocation.value
        silenceAlarm()
        serviceScope.launch {
            repo.saveAnchorPosition(lat, lon)
        }
        GpsService.instance?.setAnchorReference(lat, lon)
        Log.d(TAG, "Anchor set directly at $lat, $lon")
        AnchorLogger.log(TAG, "ANCHOR-SET-DIRECT  lat=${"%.6f".format(lat)}  lon=${"%.6f".format(lon)}")
        updateNotification("Anchor set — watching")
    }

    private fun clearAnchor() {
        handler.removeCallbacks(watchRunnable)
        handler.removeCallbacks(autoSilenceRunnable)
        AnchorLogger.log(TAG, "ANCHOR-CLEARED")
        serviceScope.launch {
            repo.clearSavedAnchor()
        }
        // Reset Kalman filter so stale position state doesn't corrupt the next session
        GpsService.instance?.resetFilter()
        // Clear the breadcrumb track so the map starts fresh next anchoring
        GpsService.trackPoints.value = emptyList()
        resetAlarmState()
        updateNotification("Anchor watch active")
        handler.post(watchRunnable)
    }

    private fun isValidFix(newLocation: Location): Boolean {
        val previous = lastValidLocation ?: return true
        val timeDeltaSeconds = (newLocation.time - previous.time) / 1000.0
        if (timeDeltaSeconds <= 0) return false
        val distance = newLocation.distanceTo(previous)
        val speedMs = distance / timeDeltaSeconds
        if (speedMs > MAX_ANCHOR_SPEED_MS) {
            Log.w(TAG, "Fix rejected: ${speedMs}m/s — ${distance}m in ${timeDeltaSeconds}s")
            AnchorLogger.log(TAG, "SPEED-REJECT  speed=${"%.1f".format(speedMs)}m/s" +
                    "  dist=${distance.toInt()}m  dt=${timeDeltaSeconds.toInt()}s")
            return false
        }
        return true
    }

    private fun checkAnchor() {
        val anchor = anchorLocation.value ?: return
        val rawFix = GpsService.currentLocation.value ?: return

        if (!isValidFix(rawFix)) return

        lastValidLocation = rawFix
        lastFixTimeMs = System.currentTimeMillis()
        val distance = rawFix.distanceTo(anchor)
        distanceFromAnchor.value = distance

        val radius = cachedRadius
        val now    = SystemClock.elapsedRealtime()
        val canTrigger = !alarmActive.value && !alarmSilenced &&
                         (now - alarmTriggeredTime) > ALARM_COOLDOWN_MS

        if (distance > radius) {
            consecutiveDragCount++
            Log.d(TAG, "Outside radius: ${distance}m ($consecutiveDragCount/$REQUIRED_CONSECUTIVE_FIXES)" +
                    "  radial=${"%.2f".format(GpsService.radialVelocityMs.value)}m/s")
            AnchorLogger.log(TAG, "OUTSIDE-RADIUS  dist=${distance.toInt()}m  radius=${radius.toInt()}m" +
                    "  count=$consecutiveDragCount/$REQUIRED_CONSECUTIVE_FIXES" +
                    "  radial=${"%.2f".format(GpsService.radialVelocityMs.value)}m/s")
            if (consecutiveDragCount >= REQUIRED_CONSECUTIVE_FIXES && canTrigger) {
                Log.w(TAG, "DRAG CONFIRMED")
                AnchorLogger.log(TAG, "DRAG-CONFIRMED  dist=${distance.toInt()}m  radius=${radius.toInt()}m" +
                        "  gpsAcc=${rawFix.accuracy.toInt()}m")
                triggerAlarm(distance, radius, rawFix.accuracy)
            }
        } else {
            if (consecutiveDragCount > 0) {
                AnchorLogger.log(TAG, "INSIDE-RADIUS  dist=${distance.toInt()}m  radius=${radius.toInt()}m  (count reset)")
                consecutiveDragCount = 0
            }
            if (alarmActive.value) silenceAlarm()
        }
    }

    private fun triggerAlarm(distance: Float, radius: Float, accuracy: Float) {
        alarmActive.value = true
        alarmTriggeredTime = SystemClock.elapsedRealtime()
        AnchorLogger.log(TAG, "ALARM-TRIGGERED  dist=${distance.toInt()}m  radius=${radius.toInt()}m  gpsAcc=${accuracy.toInt()}m")
        playAlarmSound()
        updateNotification("ANCHOR DRAG! ${distance.toInt()}m > ${radius.toInt()}m")
        handler.removeCallbacks(autoSilenceRunnable)
        handler.postDelayed(autoSilenceRunnable, AUTO_SILENCE_MS)

        if (!smsSent) {
            serviceScope.launch {
                val number = repo.smsNumber.first()
                val boat = GpsService.currentLocation.value
                if (number.isNotEmpty() && boat != null) {
                    sendSms(
                        number,
                        "ANCHOR DRAG ALARM! " +
                        "Distance: ${distance.toInt()}m from anchor. " +
                        "GPS accuracy: ${accuracy.toInt()}m. " +
                        "Boat position: ${mapsUrl(boat.latitude, boat.longitude)}"
                    )
                    smsSent = true
                    alarmPhoneNumber = number
                    callAnswered = false
                    callAttemptCount = 1
                    makePhoneCall(number)
                    // Schedule retries every 3 minutes until answered or alarm silenced
                    handler.removeCallbacks(callRetryRunnable)
                    handler.postDelayed(callRetryRunnable, CALL_RETRY_INTERVAL_MS)
                }
            }
        }
    }

    private fun silenceAlarm() {
        alarmActive.value = false
        alarmTriggeredTime = 0L
        consecutiveDragCount = 0
        AnchorLogger.log(TAG, "ALARM-SILENCED  anchorStillSet=${anchorSet.value}")
        if (anchorSet.value) {
            alarmSilenced = true          // block retriggering until re-armed or anchor reset
            alarmCancelled.value = true   // tells UI to show "Re-Arm Alarm" button
        }
        callAnswered = true  // Stop any pending call retries
        callAttemptCount = 0
        handler.removeCallbacks(callRetryRunnable)
        stopAlarmSound()
        handler.removeCallbacks(autoSilenceRunnable)
        updateNotification(
            if (anchorSet.value) "Alarm cancelled — tap Re-Arm to resume watch" else "Anchor watch active")
    }

    private fun rearmAlarm() {
        alarmSilenced = false
        alarmCancelled.value = false
        consecutiveDragCount = 0
        smsSent = false
        callAnswered = false
        callAttemptCount = 0
        Log.d(TAG, "Alarm re-armed — watching for drag again")
        AnchorLogger.log(TAG, "ALARM-REARMED")
        updateNotification("Anchor set — watching")
    }

    private fun playAlarmSound() {
        stopAlarmSound()
        serviceScope.launch {
            val soundUriStr = repo.alarmSoundUri.first()
            val soundUri = if (soundUriStr.isNotEmpty()) {
                Uri.parse(soundUriStr)
            } else {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            }
            try {
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(this@AnchorAlarmService, soundUri)
                    isLooping = true
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play sound: ${e.message}")
            }
        }
    }

    private fun stopAlarmSound() {
        mediaPlayer?.apply {
            try {
                if (isPlaying) stop()
                release()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping: ${e.message}")
            }
        }
        mediaPlayer = null
    }

    private fun mapsUrl(lat: Double, lon: Double) =
        "https://maps.google.com/maps?q=${"%.6f".format(lat)},${"%.6f".format(lon)}"

    private fun sendSms(number: String, message: String) {
        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            smsManager.sendTextMessage(number, null, message, null, null)
            Log.d(TAG, "SMS sent to $number")
        } catch (e: Exception) {
            Log.e(TAG, "SMS failed: ${e.message}")
        }
    }

    private fun makePhoneCall(number: String) {
        try {
            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$number")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(callIntent)
            Log.d(TAG, "Phone call initiated to $number")
        } catch (e: Exception) {
            Log.e(TAG, "Phone call failed: ${e.message}")
        }
    }

    private fun testSms() {
        serviceScope.launch {
            val number = repo.smsNumber.first()
            val boat = GpsService.currentLocation.value
            if (number.isNotEmpty() && boat != null) {
                sendSms(number,
                    "ANCHORWATCH TEST: GPS accuracy: ${boat.accuracy.toInt()}m. " +
                    "Position: ${mapsUrl(boat.latitude, boat.longitude)}")
                // Brief delay so the SMS lands before the call interrupts
                kotlinx.coroutines.delay(2000)
                makePhoneCall(number)
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Anchor Alarm",
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "Anchor drag alarm" }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AnchorWatch")
            .setContentText(text)
            .setSmallIcon(com.anchorwatch.app.R.drawable.ic_anchor)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(watchRunnable)
        handler.removeCallbacks(autoSilenceRunnable)
        handler.removeCallbacks(callRetryRunnable)
        handler.removeCallbacks(watchdogRunnable)
        AnchorLogger.log(TAG, "ALARM-SERVICE-DESTROY")
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED) {
            val telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
            telephonyManager.unregisterTelephonyCallback(callStateCallback)
        }
        stopAlarmSound()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
