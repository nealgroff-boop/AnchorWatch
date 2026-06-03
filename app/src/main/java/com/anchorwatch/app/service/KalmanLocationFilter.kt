package com.anchorwatch.app.service

import android.location.Location
import android.util.Log
import com.anchorwatch.app.service.AnchorLogger
import kotlin.math.*

/**
 * IMU-assisted Kalman filter for GPS position smoothing.
 *
 * Design philosophy
 * ─────────────────
 * GPS is the sole source of truth for *position*.  The IMU is used only to
 * maintain a short-term *velocity* estimate between GPS fixes.  The IMU never
 * moves the position estimate directly — this prevents accelerometer noise or
 * wave-induced spikes from drifting the filter away from reality.
 *
 * State vector: [lat (°), lon (°), vN (m/s), vE (m/s)]
 *
 * Predict step (IMU rate ~50 Hz)
 *   • Updates vN/vE from rotated linear acceleration.
 *   • Applies velocity damping (τ ≈ 1 s) for responsive drag detection.
 *   • Does NOT update lat/lon — position only moves on GPS updates.
 *   • Grows velocity covariance only (position covariance unchanged).
 *
 * Update step (GPS rate, configurable)
 *   • Standard Kalman position measurement update.
 *   • Innovation gate: rejects fixes that jump more than [GATE_NORMAL_M].
 *   • Adaptive gate: if [MAX_CONSECUTIVE_REJECTS] consecutive fixes are
 *     rejected the gate widens to [GATE_WIDE_M] for one fix to allow
 *     self-recovery, then returns to normal.
 *
 * Radial velocity
 *   • Computed from the EKF velocity state after each GPS update.
 *   • Positive = boat moving away from anchor.
 *   • Reliable because velocity is GPS-corrected, not raw IMU integration.
 */
class KalmanLocationFilter {

    companion object {
        private const val TAG = "EKF"
        private const val EARTH_R = 6_371_000.0

        // Innovation gate — distance in metres beyond which a GPS fix is rejected
        // Optimized for responsive anchor drag detection:
        //   • GATE_NORMAL_M: Increased from 25→40m to accept real boat movement
        //     while still rejecting random GPS spikes
        //   • GATE_WIDE_M: Increased from 80→100m for recovery after signal loss
        private const val GATE_NORMAL_M      = 40.0   // Allows faster response to movement
        private const val GATE_WIDE_M        = 100.0  // Recovery gate after repeated rejects
        private const val MAX_CONSECUTIVE_REJECTS = 4 // widen gate after this many rejects

        // Velocity damping: decay constant in seconds.
        // vN *= exp(-dt / TAU_S) each predict step.
        // Optimized from 3.0s → 1.0s for:
        //   • Faster detection of anchor drag (velocity becomes visible sooner)
        //   • Better tracking of changing speeds
        //   • Still suppresses wave-induced rocking (1s ~ 2-3 wave periods)
        private const val VEL_DAMPING_TAU_S  = 1.0    // Faster response to drag

        // Process noise added to velocity covariance each predict step
        private const val VEL_NOISE_MS       = 0.05   // m/s std-dev per second

        // GPS measurement noise scale (accuracy is 1-sigma; we scale slightly)
        // Optimized from 1.2 → 1.0 to trust GPS measurements more directly
        // This reduces lag by making the filter respond faster to GPS fixes
        private const val GPS_NOISE_SCALE    = 1.0    // Trust GPS more
    }

    // ── State ──────────────────────────────────────────────────────────
    private var lat = 0.0   // degrees — only updated on GPS fix
    private var lon = 0.0   // degrees — only updated on GPS fix
    private var vN  = 0.0   // m/s north — updated by IMU + GPS
    private var vE  = 0.0   // m/s east  — updated by IMU + GPS

    // Covariance P (4×4 row-major).
    // During predict we only touch the velocity rows/cols (indices 10,11,14,15).
    // Position covariance (indices 0,1,4,5) only changes on GPS update.
    private val P = DoubleArray(16) { 0.0 }

    private var initialised        = false
    private var lastGpsTimeMs      = 0L
    private var consecutiveRejects = 0

    // ── Anchor reference (for radial velocity) ────────────────────────────────
    var anchorLat: Double  = 0.0
    var anchorLon: Double  = 0.0
    var anchorSet: Boolean = false

    /** Radial velocity (m/s).  Positive = moving away from anchor.
     *  Updated after every accepted GPS fix. */
    var radialVelocityMs: Double = 0.0
        private set

    var acceptedCount = 0; private set
    var rejectedCount = 0; private set

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * IMU predict step.
     * [aN], [aE] — linear acceleration (m/s²) in world North/East frame.
     * [dtS]      — seconds since last IMU sample.
     *
     * Only velocity is updated here.  Position stays fixed until GPS arrives.
     */
    fun predict(aN: Double, aE: Double, dtS: Double) {
        if (!initialised) return
        val dt = dtS.coerceIn(0.0, 0.1)   // clamp; ignore gaps > 100 ms

        // Velocity integration from IMU acceleration
        vN += aN * dt
        vE += aE * dt

        // Velocity damping — exponential decay toward zero.
        // Removes accumulated bias from wave rocking between GPS fixes.
        val decay = exp(-dt / VEL_DAMPING_TAU_S)
        vN *= decay
        vE *= decay

        // Grow velocity covariance only (position covariance is GPS-driven)
        val qVel = (VEL_NOISE_MS * dt).let { it * it }
        P[10] += qVel
        P[15] += qVel
    }

    /**
     * GPS measurement update.
     * @return Smoothed [Location] from EKF estimate, or the current estimate
     *         (unchanged) if the fix was rejected by the innovation gate.
     */
    fun process(raw: Location): Location? {
        if (!initialised) return initialise(raw)

        // If no IMU predict has run since last fix, damp velocity manually
        val dtS = ((raw.time - lastGpsTimeMs) / 1000.0).coerceIn(0.0, 30.0)
        if (dtS > 0) {
            val decay = exp(-dtS / VEL_DAMPING_TAU_S)
            vN *= decay; vE *= decay
            val qVel = (VEL_NOISE_MS * dtS).let { it * it }
            P[10] += qVel; P[15] += qVel
        }

        // ── Innovation gate ───────────────────────────────────────────────
        val innovM = distanceMetres(lat, lon, raw.latitude, raw.longitude)
        val gate   = if (consecutiveRejects >= MAX_CONSECUTIVE_REJECTS) GATE_WIDE_M
                     else GATE_NORMAL_M

        if (innovM > gate) {
            consecutiveRejects++
            rejectedCount++
            Log.w(TAG, "Fix REJECTED  innov=${innovM.toInt()}m  gate=${gate.toInt()}m" +
                    "  (${consecutiveRejects} consecutive)")
            AnchorLogger.log(TAG, "GPS-REJECT  innov=${innovM.toInt()}m  gate=${gate.toInt()}m" +
                    "  streak=$consecutiveRejects  rawAcc=${raw.accuracy.toInt()}m" +
                    "  rawLat=${"%.6f".format(raw.latitude)}  rawLon=${"%.6f".format(raw.longitude)}")
            // Return current estimate so distance display stays stable
            return buildLocation(raw)
        }

        // Fix accepted — reset rejection streak
        consecutiveRejects = 0

        // ── Measurement noise R from GPS reported accuracy ────────────────
        val sigmaM = (raw.accuracy * GPS_NOISE_SCALE).toDouble().coerceAtLeast(1.0)
        val rLat   = metresToDegLat(sigmaM).let { it * it }
        val rLon   = metresToDegLon(sigmaM, lat).let { it * it }

        // ── Kalman gain K = P Hᵀ (H P Hᵀ + R)⁻¹ ─────────────────────────
        // H = [1 0 0 0 ; 0 1 0 0]  — we observe lat and lon only
        val sLat = P[0] + rLat
        val sLon = P[5] + rLon

        val kLatLat = P[0]  / sLat;  val kLonLat = P[4]  / sLat
        val kVnLat  = P[8]  / sLat;  val kVeLat  = P[12] / sLat
        val kLatLon = P[1]  / sLon;  val kLonLon = P[5]  / sLon
        val kVnLon  = P[9]  / sLon;  val kVeLon  = P[13] / sLon

        // ── State update ──────────────────────────────────────────────────
        val iLat = raw.latitude  - lat
        val iLon = raw.longitude - lon

        lat += kLatLat * iLat + kLatLon * iLon
        lon += kLonLat * iLat + kLonLon * iLon
        vN  += kVnLat  * iLat + kVnLon  * iLon
        vE  += kVeLat  * iLat + kVeLon  * iLon

        // ── Covariance update P = (I - KH) P ─────────────────────────────
        updateCovariance(kLatLat, kLonLat, kVnLat, kVeLat,
                         kLatLon, kLonLon, kVnLon, kVeLon)

        lastGpsTimeMs = raw.time
        acceptedCount++

        updateRadialVelocity()

        val accM = estimatedAccuracyM().toFloat()
        Log.d(TAG, "Fix #$acceptedCount  innov=${innovM.toInt()}m  " +
                "vN=${"%.2f".format(vN)} vE=${"%.2f".format(vE)}  " +
                "radial=${"%.2f".format(radialVelocityMs)}m/s  acc≈${accM.toInt()}m")
        AnchorLogger.log(TAG, "GPS-ACCEPT #$acceptedCount" +
                "  lat=${"%.6f".format(lat)}  lon=${"%.6f".format(lon)}" +
                "  innov=${innovM.toInt()}m  vN=${"%.2f".format(vN)}  vE=${"%.2f".format(vE)}" +
                "  radial=${"%.2f".format(radialVelocityMs)}m/s" +
                "  estAcc=${accM.toInt()}m  rawAcc=${raw.accuracy.toInt()}m")

        return buildLocation(raw, accM)
    }

    /** Full reset — call when anchor is cleared. */
    fun reset() {
        initialised = false
        lat = 0.0; lon = 0.0; vN = 0.0; vE = 0.0
        P.fill(0.0)
        lastGpsTimeMs = 0L
        radialVelocityMs = 0.0
        consecutiveRejects = 0
        acceptedCount = 0; rejectedCount = 0
        Log.d(TAG, "EKF reset")
        AnchorLogger.log(TAG, "RESET  accepted=$acceptedCount  rejected=$rejectedCount")
    }

    // ── Private ─────────────────────────────────────────────────────────

    private fun initialise(raw: Location): Location {
        lat = raw.latitude; lon = raw.longitude
        vN = 0.0; vE = 0.0
        val sigmaM = raw.accuracy.toDouble().coerceAtLeast(1.0)
        val pPos   = metresToDegLat(sigmaM).let { it * it }
        P.fill(0.0)
        P[0]  = pPos; P[5]  = pPos    // position uncertainty seeded from GPS accuracy
        P[10] = 0.25; P[15] = 0.25    // velocity std-dev ~0.5 m/s initially
        lastGpsTimeMs  = raw.time
        initialised    = true
        acceptedCount  = 1
        consecutiveRejects = 0
        Log.d(TAG, "EKF initialised  lat=$lat  lon=$lon  acc=${raw.accuracy}m")
        AnchorLogger.log(TAG, "INIT  lat=${"%.6f".format(lat)}  lon=${"%.6f".format(lon)}  gpsAcc=${raw.accuracy.toInt()}m")
        return buildLocation(raw)
    }

    /** P = (I - KH) P  for H = [1 0 0 0 ; 0 1 0 0]. */
    private fun updateCovariance(
        kll: Double, kLl: Double, kvNl: Double, kvEl: Double,
        klL: Double, kLL: Double, kvNL: Double, kvEL: Double
    ) {
        val newP = DoubleArray(16)
        for (col in 0..3) {
            newP[0  + col] = (1 - kll) * P[0 + col] + (-klL)  * P[4 + col]
            newP[4  + col] = (-kLl)    * P[0 + col] + (1-kLL) * P[4 + col]
            newP[8  + col] = (-kvNl)   * P[0 + col] + (-kvNL) * P[4 + col] + P[8  + col]
            newP[12 + col] = (-kvEl)   * P[0 + col] + (-kvEL) * P[4 + col] + P[12 + col]
        }
        newP.copyInto(P)
        // Symmetrise to prevent numerical drift
        for (r in 0..3) for (c in r + 1..3) {
            val avg = (P[r * 4 + c] + P[c * 4 + r]) / 2.0
            P[r * 4 + c] = avg; P[c * 4 + r] = avg
        }
    }

    private fun updateRadialVelocity() {
        if (!anchorSet) { radialVelocityMs = 0.0; return }
        val dN   = degreesLatToMetres(lat - anchorLat)
        val dE   = degreesLonToMetres(lon - anchorLon, (lat + anchorLat) / 2.0)
        val dist = sqrt(dN * dN + dE * dE).coerceAtLeast(0.1)
        radialVelocityMs = (vN * (dN / dist)) + (vE * (dE / dist))
    }

    private fun estimatedAccuracyM(): Double {
        val varLat = degreesLatToMetres(sqrt(P[0].coerceAtLeast(0.0))).let { it * it }
        val varLon = degreesLonToMetres(sqrt(P[5].coerceAtLeast(0.0)), lat).let { it * it }
        return sqrt((varLat + varLon) / 2.0)
    }

    private fun buildLocation(raw: Location, acc: Float = raw.accuracy) =
        Location("ekf").apply {
            latitude  = this@KalmanLocationFilter.lat
            longitude = this@KalmanLocationFilter.lon
            altitude  = raw.altitude
            bearing   = raw.bearing
            speed     = sqrt(vN * vN + vE * vE).toFloat()
            time      = raw.time
            accuracy  = acc
        }

    private fun metresToDegLat(m: Double) = m / EARTH_R * (180.0 / PI)
    private fun metresToDegLon(m: Double, latDeg: Double): Double {
        val cosLat = cos(Math.toRadians(latDeg)).coerceAtLeast(1e-10)
        return m / (EARTH_R * cosLat) * (180.0 / PI)
    }
    private fun degreesLatToMetres(deg: Double) = deg * PI / 180.0 * EARTH_R
    private fun degreesLonToMetres(deg: Double, latDeg: Double): Double {
        val cosLat = cos(Math.toRadians(latDeg)).coerceAtLeast(1e-10)
        return deg * PI / 180.0 * EARTH_R * cosLat
    }
    private fun distanceMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = degreesLatToMetres(lat2 - lat1)
        val dLon = degreesLonToMetres(lon2 - lon1, (lat1 + lat2) / 2.0)
        return sqrt(dLat * dLat + dLon * dLon)
    }
}
