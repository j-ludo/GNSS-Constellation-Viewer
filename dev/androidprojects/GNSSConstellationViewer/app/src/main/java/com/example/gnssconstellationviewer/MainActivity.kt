package com.example.gnssconstellationviewer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.material.chip.Chip
import android.util.Log

class MainActivity : AppCompatActivity(), OnMapReadyCallback {
    private lateinit var map: GoogleMap
    private lateinit var locationManager: LocationManager
    private val LOCATION_PERMISSION_REQUEST = 1001
    private val positionMarkers = mutableMapOf<Int, Marker?>()

    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            Log.e("GNSS_DEBUG", "Satellite Status Changed")

            // Track which constellations have satellites used in fix
            val constellationsInUse = mutableSetOf<Int>()

            for (i in 0 until status.satelliteCount) {
                val type = status.getConstellationType(i)
                if (status.usedInFix(i)) {
                    constellationsInUse.add(type)
                }
            }

            // Get the current location
            if (ActivityCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                val location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                location?.let { currentLocation ->
                    // Update markers for each constellation that has satellites used in fix
                    constellationsInUse.forEach { constellation ->
                        if (isConstellationSelected(constellation)) {
                            // Create slightly offset positions for different constellations
                            val adjustedLocation = Location(currentLocation)
                            when (constellation) {
                                GnssStatus.CONSTELLATION_GPS -> {
                                    // No offset for GPS
                                }
                                GnssStatus.CONSTELLATION_GLONASS -> {
                                    adjustedLocation.latitude += 0.0001
                                }
                                GnssStatus.CONSTELLATION_GALILEO -> {
                                    adjustedLocation.longitude += 0.0001
                                }
                                GnssStatus.CONSTELLATION_BEIDOU -> {
                                    adjustedLocation.latitude -= 0.0001
                                }
                            }
                            updateConstellationPosition(constellation, adjustedLocation)
                        }
                    }
                }
            }
        }
    }

    private val locationCallback = object : android.location.LocationListener {
        override fun onLocationChanged(location: Location) {
            Log.e("GNSS_DEBUG", "Location update received: ${location.latitude}, ${location.longitude}")
            Log.e("GNSS_DEBUG", "Provider: ${location.provider}")

            // If the provider is "gps", check which constellations contributed to this fix
            if (location.provider == "gps") {
                location.extras?.let { extras ->
                    // Get the satellites used in this fix
                    val beidouActive = extras.getBoolean("beidou_used", false)
                    val glonassActive = extras.getBoolean("glonass_used", false)
                    val galileoActive = extras.getBoolean("galileo_used", false)

                    // Update markers for each active constellation
                    if (isConstellationSelected(GnssStatus.CONSTELLATION_GPS)) {
                        updateConstellationPosition(GnssStatus.CONSTELLATION_GPS, location)
                    }
                    if (beidouActive && isConstellationSelected(GnssStatus.CONSTELLATION_BEIDOU)) {
                        // Create a slightly offset position for BeiDou
                        val beidouLocation = Location(location)
                        beidouLocation.latitude += 0.0001  // Small offset for visibility
                        updateConstellationPosition(GnssStatus.CONSTELLATION_BEIDOU, beidouLocation)
                    }
                    if (glonassActive && isConstellationSelected(GnssStatus.CONSTELLATION_GLONASS)) {
                        // Create a slightly offset position for GLONASS
                        val glonassLocation = Location(location)
                        glonassLocation.latitude -= 0.0001  // Small offset for visibility
                        updateConstellationPosition(GnssStatus.CONSTELLATION_GLONASS, glonassLocation)
                    }
                    if (galileoActive && isConstellationSelected(GnssStatus.CONSTELLATION_GALILEO)) {
                        // Create a slightly offset position for Galileo
                        val galileoLocation = Location(location)
                        galileoLocation.longitude += 0.0001  // Small offset for visibility
                        updateConstellationPosition(GnssStatus.CONSTELLATION_GALILEO, galileoLocation)
                    }

                    Log.e("GNSS_DEBUG", "BeiDou active: $beidouActive")
                    Log.e("GNSS_DEBUG", "GLONASS active: $glonassActive")
                    Log.e("GNSS_DEBUG", "Galileo active: $galileoActive")
                }
            }
        }

        override fun onProviderEnabled(provider: String) {
            Log.e("GNSS_DEBUG", "Provider enabled: $provider")
        }

        override fun onProviderDisabled(provider: String) {
            Log.e("GNSS_DEBUG", "Provider disabled: $provider")
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("GNSS_DEBUG", "App starting")
        setContentView(R.layout.activity_main)

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        startLocationUpdates()

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        setupConstellationChips()
    }

    private fun setupConstellationChips() {
        findViewById<Chip>(R.id.chipGPS).setOnCheckedChangeListener { _, isChecked ->
            handleConstellation(GnssStatus.CONSTELLATION_GPS, isChecked)
        }
        findViewById<Chip>(R.id.chipGLONASS).setOnCheckedChangeListener { _, isChecked ->
            handleConstellation(GnssStatus.CONSTELLATION_GLONASS, isChecked)
        }
        findViewById<Chip>(R.id.chipGalileo).setOnCheckedChangeListener { _, isChecked ->
            handleConstellation(GnssStatus.CONSTELLATION_GALILEO, isChecked)
        }
        findViewById<Chip>(R.id.chipBeidou).setOnCheckedChangeListener { _, isChecked ->
            handleConstellation(GnssStatus.CONSTELLATION_BEIDOU, isChecked)
        }
    }

    private fun handleConstellation(constellation: Int, isChecked: Boolean) {
        if (!isChecked) {
            positionMarkers[constellation]?.remove()
            positionMarkers[constellation] = null
        }
    }

    private fun isConstellationSelected(constellation: Int): Boolean {
        val chipId = when (constellation) {
            GnssStatus.CONSTELLATION_GPS -> R.id.chipGPS
            GnssStatus.CONSTELLATION_GLONASS -> R.id.chipGLONASS
            GnssStatus.CONSTELLATION_GALILEO -> R.id.chipGalileo
            GnssStatus.CONSTELLATION_BEIDOU -> R.id.chipBeidou
            else -> return false
        }
        return findViewById<Chip>(chipId).isChecked
    }

    private fun updateConstellationPosition(constellation: Int, location: Location) {
        runOnUiThread {
            val position = LatLng(location.latitude, location.longitude)
            val (title, color) = when (constellation) {
                GnssStatus.CONSTELLATION_GPS -> "GPS Position" to BitmapDescriptorFactory.HUE_RED
                GnssStatus.CONSTELLATION_GLONASS -> "GLONASS Position" to BitmapDescriptorFactory.HUE_BLUE
                GnssStatus.CONSTELLATION_GALILEO -> "Galileo Position" to BitmapDescriptorFactory.HUE_GREEN
                GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou Position" to BitmapDescriptorFactory.HUE_YELLOW
                else -> "Unknown System" to BitmapDescriptorFactory.HUE_RED
            }

            val existingMarker = positionMarkers[constellation]
            if (existingMarker != null) {
                existingMarker.position = position
            } else {
                positionMarkers[constellation] = map.addMarker(
                    MarkerOptions()
                        .position(position)
                        .title(title)
                        .icon(BitmapDescriptorFactory.defaultMarker(color))
                )
            }

            // Move camera to this position if it's the first marker
            if (positionMarkers.values.count { it != null } == 1) {
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(position, 15f))
            }
        }
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            // Get list of all providers
            val providers = locationManager.allProviders
            Log.e("GNSS_DEBUG", "Available providers: $providers")

            // Request updates from each provider
            providers.forEach { provider ->
                try {
                    Log.e("GNSS_DEBUG", "Requesting updates from provider: $provider")
                    locationManager.requestLocationUpdates(
                        provider,
                        1000, // 1 second minimum time
                        0f,   // 0 meters minimum distance
                        locationCallback
                    )
                } catch (e: Exception) {
                    Log.e("GNSS_DEBUG", "Error requesting updates from $provider: ${e.message}")
                }
            }

            // Register for GNSS status updates
            locationManager.registerGnssStatusCallback(gnssStatusCallback, null)

            // Try to get last known location
            providers.forEach { provider ->
                try {
                    val lastLocation = locationManager.getLastKnownLocation(provider)
                    if (lastLocation != null) {
                        Log.e("GNSS_DEBUG", "Last known location from $provider: ${lastLocation.latitude}, ${lastLocation.longitude}")
                    }
                } catch (e: Exception) {
                    Log.e("GNSS_DEBUG", "Error getting last location from $provider: ${e.message}")
                }
            }
        } else {
            Log.e("GNSS_DEBUG", "No location permission")
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap

        // Disable the "My Location" button and layer
        map.uiSettings.isMyLocationButtonEnabled = false
        map.isMyLocationEnabled = false

        // Request location permissions to receive GNSS data
        checkLocationPermission()
    }

    private fun checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST
            )
        } else {
            startLocationUpdates()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            locationManager.removeUpdates(locationCallback)
            locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
        }
    }
}