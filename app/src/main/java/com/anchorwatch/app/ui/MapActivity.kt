package com.anchorwatch.app.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.anchorwatch.app.R
import com.anchorwatch.app.repository.SettingsRepository
import com.anchorwatch.app.service.AnchorAlarmService
import com.anchorwatch.app.service.GpsService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay

class MapActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var repo: SettingsRepository
    private var boatMarker: Marker? = null
    private var anchorMarker: Marker? = null
    private var radiusCircle: Polygon? = null
    private var trackLine: Polyline? = null
    private var centredOnBoat = false
    private var currentTileMode = TileMode.CHART
    private var seaMarkOverlay: TilesOverlay? = null

    enum class TileMode { SATELLITE, CHART }

    companion object {
        /**
         * OpenSeaMap seamark overlay — free, unrestricted, community-maintained.
         * Shown on top of both chart and satellite layers.
         */
        val OPENSEAMAP_SOURCE = object : OnlineTileSourceBase(
            "OpenSeaMap", 0, 19, 256, ".png",
            arrayOf("https://tiles.openseamap.org/seamark/")
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                val zoom = MapTileIndex.getZoom(pMapTileIndex).toInt()
                val x    = MapTileIndex.getX(pMapTileIndex).toInt()
                val y    = MapTileIndex.getY(pMapTileIndex).toInt()
                return "https://tiles.openseamap.org/seamark/$zoom/$x/$y.png"
            }
        }

        /**
         * Mapbox satellite raster tiles. Requires a free user token (pk.eyJ1...).
         * Token is passed in at call time — never hardcoded.
         */
        fun buildMapboxSatellite(token: String) = object : OnlineTileSourceBase(
            "Mapbox_Satellite", 0, 22, 512, ".jpg",
            arrayOf("https://api.mapbox.com/")
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                val zoom = MapTileIndex.getZoom(pMapTileIndex).toInt()
                val x    = MapTileIndex.getX(pMapTileIndex).toInt()
                val y    = MapTileIndex.getY(pMapTileIndex).toInt()
                return "https://api.mapbox.com/v4/mapbox.satellite/$zoom/$x/$y@2x.jpg90" +
                       "?access_token=$token"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_map)
        supportActionBar?.title = "Map"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        repo = SettingsRepository(this)

        mapView = findViewById(R.id.mapView)
        mapView.setMultiTouchControls(true)
        mapView.minZoomLevel = 2.0
        mapView.maxZoomLevel = 25.0

        // Restore last viewport, falling back to zoom 14 centred on first GPS fix
        val prefs = getSharedPreferences("map_prefs", MODE_PRIVATE)
        val savedZoom = prefs.getFloat("zoom", 0f).toDouble()
        val savedLat  = prefs.getFloat("lat", Float.MAX_VALUE)
        val savedLon  = prefs.getFloat("lon", Float.MAX_VALUE)
        if (savedZoom > 0 && savedLat != Float.MAX_VALUE) {
            mapView.controller.setZoom(savedZoom)
            mapView.controller.setCenter(GeoPoint(savedLat.toDouble(), savedLon.toDouble()))
            centredOnBoat = true
        } else {
            mapView.controller.setZoom(14.0)
        }

        setTileMode(TileMode.CHART)

        lifecycleScope.launch {
            GpsService.trackMinDistanceM = repo.trackMinDistance.first()
        }

        findViewById<Button>(R.id.btnSatellite).setOnClickListener {
            onSatelliteTapped()
        }
        findViewById<Button>(R.id.btnChart).setOnClickListener {
            setTileMode(TileMode.CHART)
        }

        lifecycleScope.launch {
            GpsService.currentLocation.collect { location ->
                location ?: return@collect
                val point = GeoPoint(location.latitude, location.longitude)
                if (!centredOnBoat) {
                    mapView.controller.setCenter(point)
                    centredOnBoat = true
                }
                if (boatMarker == null) {
                    boatMarker = Marker(mapView).apply {
                        title = "Boat"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        icon = androidx.core.content.ContextCompat.getDrawable(
                            this@MapActivity, android.R.drawable.ic_menu_compass)
                    }
                    mapView.overlays.add(boatMarker)
                }
                boatMarker?.position = point
                updateTrack()
                mapView.invalidate()
            }
        }

        lifecycleScope.launch {
            AnchorAlarmService.anchorLocation.collect { anchor ->
                anchor ?: run {
                    anchorMarker?.let { mapView.overlays.remove(it) }
                    anchorMarker = null
                    radiusCircle?.let { mapView.overlays.remove(it) }
                    radiusCircle = null
                    mapView.invalidate()
                    return@collect
                }
                val anchorPoint = GeoPoint(anchor.latitude, anchor.longitude)
                if (anchorMarker == null) {
                    anchorMarker = Marker(mapView).apply {
                        title = "Anchor"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        icon = makeAnchorIcon()
                    }
                    mapView.overlays.add(anchorMarker)
                }
                anchorMarker?.position = anchorPoint
                val radius = repo.alarmRadius.first().toDouble()
                drawRadiusCircle(anchorPoint, radius)
                mapView.invalidate()
            }
        }
    }

    // ------------------------------------------------------------------
    // Satellite tap handling
    // ------------------------------------------------------------------

    /**
     * Called when the user taps the Satellite button.
     * - If a token is stored: switch immediately.
     * - If no token and offline: show offline message.
     * - If no token and online: show the Mapbox setup dialog.
     */
    private fun onSatelliteTapped() {
        lifecycleScope.launch {
            val token = repo.mapboxToken.first()
            when {
                token.isNotEmpty() -> {
                    // Token already saved — switch straight to satellite
                    setTileMode(TileMode.SATELLITE, token)
                }
                !isOnline() -> {
                    // No token, no connection — explain and bail
                    AlertDialog.Builder(this@MapActivity)
                        .setTitle("Satellite Unavailable Offline")
                        .setMessage(
                            "Satellite view requires a free Mapbox account and access token.\n\n" +
                            "You appear to be offline. Please connect to the internet, " +
                            "then tap Satellite again to set up your free account."
                        )
                        .setPositiveButton("OK", null)
                        .show()
                }
                else -> {
                    // No token but online — show the first-run setup dialog
                    showMapboxSetupDialog()
                }
            }
        }
    }

    /**
     * First-run Mapbox setup dialog. Opens the Mapbox signup page in the browser
     * and keeps the dialog open so the user can paste their token when they return.
     */
    private fun showMapboxSetupDialog() {
        val dialogView = layoutInflater.inflate(android.R.layout.simple_list_item_1, null)

        // Build the dialog content programmatically so we have full control
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
        }

        val tvInfo = android.widget.TextView(this).apply {
            text =
                "Satellite view uses Mapbox — a free account gives you high-resolution " +
                "global imagery with no cost to you.\n\n" +
                "⚠️ Mapbox will ask for a credit card during sign-up. You will NOT be " +
                "charged — the free tier is more than enough for personal use. " +
                "You can remove your card details after signing up if you prefer.\n\n" +
                "1. Tap \"Create free account\" below\n" +
                "2. Sign up at mapbox.com\n" +
                "3. Go to Account → Access Tokens\n" +
                "4. Copy your default public token (starts with pk.)\n" +
                "5. Come back here and paste it below"
            textSize = 13f
            setTextColor(android.graphics.Color.BLACK)
            setPadding(0, 0, 0, 24)
        }

        val etToken = android.widget.EditText(this).apply {
            hint = "Paste your token here (pk.eyJ1...)"
            setHintTextColor(android.graphics.Color.parseColor("#666666"))
            // Force black text explicitly — theme can override setTextColor(Color),
            // so we also set it via a ColorStateList which takes higher precedence
            setTextColor(android.content.res.ColorStateList.valueOf(android.graphics.Color.BLACK))
            // TYPE_CLASS_TEXT shows characters as typed — never use PASSWORD variation
            // or the token will appear as dots and the user cannot verify what they pasted
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }

        layout.addView(tvInfo)
        layout.addView(etToken)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Set Up Satellite View")
            .setView(layout)
            .setPositiveButton("Save Token", null)   // overridden below to prevent auto-dismiss
            .setNeutralButton("Create free account") { _, _ ->
                // Open Mapbox signup — dialog stays open (neutral button dismisses,
                // but user re-taps Satellite which re-shows it; acceptable UX)
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://account.mapbox.com/auth/signup")))
            }
            .setNegativeButton("Cancel", null)
            .create()

        // Override positive button to validate before dismissing
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val token = etToken.text.toString().trim()
                when {
                    token.isEmpty() -> {
                        Toast.makeText(this,
                            "Please paste your Mapbox token first",
                            Toast.LENGTH_SHORT).show()
                    }
                    !token.startsWith("pk.") -> {
                        Toast.makeText(this,
                            "That doesn't look right — Mapbox tokens start with pk.",
                            Toast.LENGTH_LONG).show()
                    }
                    else -> {
                        lifecycleScope.launch {
                            repo.setMapboxToken(token)
                            dialog.dismiss()
                            setTileMode(TileMode.SATELLITE, token)
                            Toast.makeText(this@MapActivity,
                                "Token saved — satellite view enabled",
                                Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            // Keep dialog open when "Create free account" is tapped
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://account.mapbox.com/auth/signup")))
                // Do NOT dismiss — user needs to come back and paste the token
            }
        }

        dialog.show()
    }

    // ------------------------------------------------------------------
    // Tile mode switching
    // ------------------------------------------------------------------

    /**
     * Switch between satellite (Mapbox) and chart (OSM) base layers.
     * OpenSeaMap seamark symbols are shown on top in both modes.
     * minZoomLevel and maxZoomLevel are set explicitly so OSMDroid never
     * inherits zoom limits from the tile source.
     */
    private fun setTileMode(mode: TileMode, mapboxToken: String = "") {
        currentTileMode = mode

        seaMarkOverlay?.let { mapView.overlays.remove(it) }
        seaMarkOverlay = null

        when (mode) {
            TileMode.SATELLITE -> mapView.setTileSource(buildMapboxSatellite(mapboxToken))
            TileMode.CHART     -> mapView.setTileSource(TileSourceFactory.MAPNIK)
        }

        // Explicit zoom bounds — must be set AFTER setTileSource to override
        // any limits the tile source declares.
        // maxZoomLevel intentionally exceeds the tile source's native zoom so
        // OSMDroid over-scales (stretches) existing tiles rather than fetching
        // new ones — lets the user zoom right in on the track without extra data.
        mapView.minZoomLevel = 2.0
        mapView.maxZoomLevel = 25.0

        // OpenSeaMap seamark overlay on top of whichever base layer is active
        val tileProvider = MapTileProviderBasic(this, OPENSEAMAP_SOURCE)
        seaMarkOverlay = TilesOverlay(tileProvider, this).apply {
            loadingBackgroundColor = android.graphics.Color.TRANSPARENT
            loadingLineColor       = android.graphics.Color.TRANSPARENT
        }
        mapView.overlays.add(seaMarkOverlay)
        mapView.invalidate()
    }

    // ------------------------------------------------------------------
    // Connectivity check
    // ------------------------------------------------------------------

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ------------------------------------------------------------------
    // Map helpers
    // ------------------------------------------------------------------

    private fun updateTrack() {
        val points = GpsService.trackPoints.value
        if (points.size < 2) return
        trackLine?.let { mapView.overlays.remove(it) }
        trackLine = Polyline(mapView).apply {
            setPoints(points.map { GeoPoint(it.latitude, it.longitude) })
            color = 0xFF00BCD4.toInt()
            width = 4f
            title = "Boat track"
        }
        mapView.overlays.add(0, trackLine)
    }

    private fun drawRadiusCircle(center: GeoPoint, radiusMetres: Double) {
        radiusCircle?.let { mapView.overlays.remove(it) }
        radiusCircle = Polygon(mapView).apply {
            points = Polygon.pointsAsCircle(center, radiusMetres)
            fillColor = 0x220000FF
            strokeColor = 0xFF0000FF.toInt()
            strokeWidth = 2f
            title = "Alarm radius"
        }
        mapView.overlays.add(0, radiusCircle)
    }

    private fun makeAnchorIcon(): android.graphics.drawable.BitmapDrawable {
        val sizePx = 96
        val bitmap = android.graphics.Bitmap.createBitmap(
            sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#1565C0")
            textSize = 80f
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val xPos = sizePx / 2f
        val yPos = (sizePx / 2f) - ((paint.descent() + paint.ascent()) / 2f)
        canvas.drawText("⚓", xPos, yPos, paint)
        return android.graphics.drawable.BitmapDrawable(resources, bitmap)
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        val centre = mapView.mapCenter
        getSharedPreferences("map_prefs", MODE_PRIVATE).edit()
            .putFloat("zoom", mapView.zoomLevelDouble.toFloat())
            .putFloat("lat",  centre.latitude.toFloat())
            .putFloat("lon",  centre.longitude.toFloat())
            .apply()
        mapView.onPause()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
