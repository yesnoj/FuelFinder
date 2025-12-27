package com.fuelfinder.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.*
import com.google.android.gms.maps.*
import com.google.android.gms.maps.model.*
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.text.DecimalFormat
import kotlin.math.*

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    // UI Components
    private lateinit var mapView: MapView
    private lateinit var googleMap: GoogleMap
    private lateinit var btnFindFuel: ExtendedFloatingActionButton
    private lateinit var fuelTypeChipGroup: ChipGroup
    private lateinit var stationRecyclerView: RecyclerView
    private lateinit var bottomSheet: View
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>
    private lateinit var tvSpeed: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvUpdateStatus: TextView
    private lateinit var liveIndicator: View
    
    // Location
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var currentLocation: Location? = null
    private var userMarker: Marker? = null
    
    // State
    private var isLiveSearchActive = false
    private var selectedFuelType = FuelType.DIESEL
    private var searchRadius = 10 // km
    private var updateFrequency = 30 // seconds
    private var tripStartTime = System.currentTimeMillis()
    private var tripDistance = 0.0
    private var lastLocation: Location? = null
    
    // Stations
    private val stationMarkers = mutableListOf<Marker>()
    private val currentStations = mutableListOf<FuelStation>()
    private lateinit var stationAdapter: StationAdapter
    
    // Update Handler
    private val updateHandler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null
    
    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 1001
        private const val DEFAULT_ZOOM = 13f
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        initViews()
        initMap(savedInstanceState)
        initLocation()
        setupListeners()
        checkLocationPermission()
    }
    
    private fun initViews() {
        mapView = findViewById(R.id.mapView)
        btnFindFuel = findViewById(R.id.btnFindFuel)
        fuelTypeChipGroup = findViewById(R.id.chipGroupFuelType)
        stationRecyclerView = findViewById(R.id.recyclerViewStations)
        bottomSheet = findViewById(R.id.bottomSheet)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvDistance = findViewById(R.id.tvDistance)
        tvTime = findViewById(R.id.tvTime)
        tvUpdateStatus = findViewById(R.id.tvUpdateStatus)
        liveIndicator = findViewById(R.id.liveIndicator)
        
        // Setup RecyclerView
        stationAdapter = StationAdapter { station ->
            navigateToStation(station)
        }
        stationRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = stationAdapter
        }
        
        // Setup BottomSheet
        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        bottomSheetBehavior.peekHeight = 200
    }
    
    private fun initMap(savedInstanceState: Bundle?) {
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(this)
    }
    
    private fun initLocation() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    updateUserLocation(location)
                }
            }
        }
    }
    
    private fun setupListeners() {
        // Fuel Find Button
        btnFindFuel.setOnClickListener {
            toggleLiveSearch()
        }
        
        // Fuel Type Selection
        fuelTypeChipGroup.setOnCheckedChangeListener { _, checkedId ->
            selectedFuelType = when (checkedId) {
                R.id.chipDiesel -> FuelType.DIESEL
                R.id.chipBenzina -> FuelType.BENZINA
                R.id.chipGPL -> FuelType.GPL
                R.id.chipMetano -> FuelType.METANO
                else -> FuelType.DIESEL
            }
            if (isLiveSearchActive) {
                searchAndUpdate()
            }
        }
        
        // Settings Button
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            openSettings()
        }
    }
    
    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        
        // Map settings
        googleMap.uiSettings.apply {
            isZoomControlsEnabled = true
            isMyLocationButtonEnabled = false // We use custom button
            isCompassEnabled = true
        }
        
        // Move camera to Italy center initially
        val italy = LatLng(42.5, 12.5)
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(italy, 6f))
        
        enableMyLocation()
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
    
    private fun enableMyLocation() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            googleMap.isMyLocationEnabled = false // We use custom marker
            getLastKnownLocation()
        }
    }
    
    private fun getLastKnownLocation() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            location?.let {
                updateUserLocation(it)
                googleMap.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng(it.latitude, it.longitude),
                        DEFAULT_ZOOM
                    )
                )
            }
        }
    }
    
    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        
        val locationRequest = LocationRequest.create().apply {
            interval = 5000 // 5 seconds
            fastestInterval = 2000
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }
        
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }
    
    private fun updateUserLocation(location: Location) {
        currentLocation = location
        
        // Update map marker
        val latLng = LatLng(location.latitude, location.longitude)
        
        userMarker?.remove()
        userMarker = googleMap.addMarker(
            MarkerOptions()
                .position(latLng)
                .title("La tua posizione")
                .icon(BitmapDescriptorFactory.fromResource(R.drawable.ic_user_location))
        )
        
        // Calculate trip stats
        lastLocation?.let { last ->
            val distance = last.distanceTo(location) / 1000 // km
            tripDistance += distance
        }
        lastLocation = location
        
        // Update UI
        updateTripStats(location.speed)
        
        // Update stations if live search is active
        if (isLiveSearchActive) {
            updateStationDistances()
        }
    }
    
    private fun updateTripStats(speed: Float) {
        val elapsedMinutes = (System.currentTimeMillis() - tripStartTime) / 60000
        
        tvSpeed.text = "${(speed * 3.6).toInt()} km/h"
        tvDistance.text = String.format("%.1f km", tripDistance)
        tvTime.text = "$elapsedMinutes min"
    }
    
    private fun toggleLiveSearch() {
        if (isLiveSearchActive) {
            stopLiveSearch()
        } else {
            startLiveSearch()
        }
    }
    
    private fun startLiveSearch() {
        isLiveSearchActive = true
        
        btnFindFuel.apply {
            text = "Stop Ricerca"
            setIconResource(R.drawable.ic_stop)
            setBackgroundColor(ContextCompat.getColor(context, R.color.green))
        }
        
        liveIndicator.visibility = View.VISIBLE
        
        // Start searching
        searchAndUpdate()
        
        // Schedule periodic updates
        updateRunnable = object : Runnable {
            override fun run() {
                if (isLiveSearchActive) {
                    searchAndUpdate()
                    updateHandler.postDelayed(this, updateFrequency * 1000L)
                }
            }
        }
        updateHandler.postDelayed(updateRunnable!!, updateFrequency * 1000L)
        
        // Show bottom sheet
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        
        showToast("Ricerca live attivata")
    }
    
    private fun stopLiveSearch() {
        isLiveSearchActive = false
        
        btnFindFuel.apply {
            text = "Cerca Benzinaio"
            setIconResource(R.drawable.ic_fuel)
            setBackgroundColor(ContextCompat.getColor(context, R.color.primary))
        }
        
        liveIndicator.visibility = View.GONE
        
        // Stop updates
        updateRunnable?.let {
            updateHandler.removeCallbacks(it)
        }
        
        // Clear markers
        stationMarkers.forEach { it.remove() }
        stationMarkers.clear()
        currentStations.clear()
        stationAdapter.updateStations(emptyList())
        
        // Hide bottom sheet
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        
        showToast("Ricerca live disattivata")
    }
    
    private fun searchAndUpdate() {
        val location = currentLocation ?: return
        
        tvUpdateStatus.text = "Aggiornamento..."
        
        // Call API to get stations
        ApiClient.fuelService.getNearbyStations(
            latitude = location.latitude,
            longitude = location.longitude,
            radius = searchRadius,
            fuelType = selectedFuelType.value
        ).enqueue(object : Callback<StationResponse> {
            override fun onResponse(
                call: Call<StationResponse>,
                response: Response<StationResponse>
            ) {
                if (response.isSuccessful) {
                    response.body()?.let { stationResponse ->
                        updateStationDisplay(stationResponse.stations)
                    }
                }
                tvUpdateStatus.text = "Ultimo agg: ${getCurrentTime()}"
            }
            
            override fun onFailure(call: Call<StationResponse>, t: Throwable) {
                // For demo, generate mock stations
                val mockStations = generateMockStations(location)
                updateStationDisplay(mockStations)
                tvUpdateStatus.text = "Ultimo agg: ${getCurrentTime()} (Demo)"
            }
        })
    }
    
    private fun generateMockStations(location: Location): List<FuelStation> {
        val stations = mutableListOf<FuelStation>()
        val brands = listOf("Q8", "Eni", "Tamoil", "IP", "Total", "Agip", "Shell")
        
        for (i in 0..7) {
            val angle = Math.random() * 2 * Math.PI
            val distance = Math.random() * searchRadius
            val lat = location.latitude + (distance / 111) * cos(angle)
            val lon = location.longitude + (distance / (111 * cos(Math.toRadians(location.latitude)))) * sin(angle)
            
            stations.add(
                FuelStation(
                    id = "station_$i",
                    name = "${brands.random()} Station",
                    brand = brands.random(),
                    address = "Via Demo ${(1..200).random()}",
                    latitude = lat,
                    longitude = lon,
                    distance = distance.toFloat(),
                    prices = mapOf(
                        FuelType.DIESEL.value to (1.75 + Math.random() * 0.15).toFloat(),
                        FuelType.BENZINA.value to (1.85 + Math.random() * 0.15).toFloat(),
                        FuelType.GPL.value to (0.75 + Math.random() * 0.10).toFloat(),
                        FuelType.METANO.value to (1.25 + Math.random() * 0.10).toFloat()
                    )
                )
            )
        }
        
        return stations.sortedBy { it.prices[selectedFuelType.value] ?: Float.MAX_VALUE }
    }
    
    private fun updateStationDisplay(stations: List<FuelStation>) {
        // Clear old markers
        stationMarkers.forEach { it.remove() }
        stationMarkers.clear()
        
        // Update current stations
        currentStations.clear()
        currentStations.addAll(stations)
        
        // Add new markers
        stations.forEachIndexed { index, station ->
            val marker = googleMap.addMarker(
                MarkerOptions()
                    .position(LatLng(station.latitude, station.longitude))
                    .title(station.name)
                    .snippet("€${station.prices[selectedFuelType.value]}/L")
                    .icon(getMarkerIcon(index))
            )
            marker?.tag = station
            stationMarkers.add(marker!!)
        }
        
        // Update RecyclerView
        stationAdapter.updateStations(stations)
        
        // Adjust map bounds
        if (stations.isNotEmpty()) {
            val builder = LatLngBounds.Builder()
            currentLocation?.let {
                builder.include(LatLng(it.latitude, it.longitude))
            }
            stations.forEach {
                builder.include(LatLng(it.latitude, it.longitude))
            }
            googleMap.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 100))
        }
    }
    
    private fun updateStationDistances() {
        val location = currentLocation ?: return
        
        currentStations.forEach { station ->
            val results = FloatArray(1)
            Location.distanceBetween(
                location.latitude,
                location.longitude,
                station.latitude,
                station.longitude,
                results
            )
            station.distance = results[0] / 1000 // Convert to km
        }
        
        // Re-sort and update display
        val sortedStations = currentStations.sortedBy { 
            it.prices[selectedFuelType.value] ?: Float.MAX_VALUE 
        }
        stationAdapter.updateStations(sortedStations)
    }
    
    private fun navigateToStation(station: FuelStation) {
        val uri = Uri.parse(
            "google.navigation:q=${station.latitude},${station.longitude}&mode=d"
        )
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
        }
        
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
            showToast("Navigazione verso ${station.name}")
        } else {
            // Fallback to browser
            val browserUri = Uri.parse(
                "https://www.google.com/maps/dir/?api=1&destination=${station.latitude},${station.longitude}"
            )
            startActivity(Intent(Intent.ACTION_VIEW, browserUri))
        }
    }
    
    private fun getMarkerIcon(index: Int): BitmapDescriptor {
        return if (index == 0) {
            BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)
        } else {
            BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)
        }
    }
    
    private fun getCurrentTime(): String {
        val now = System.currentTimeMillis()
        val formatter = android.text.format.DateFormat.getTimeFormat(this)
        return formatter.format(now)
    }
    
    private fun openSettings() {
        SettingsDialog(this) { radius, frequency ->
            searchRadius = radius
            updateFrequency = frequency
            
            if (isLiveSearchActive) {
                // Restart with new settings
                stopLiveSearch()
                Handler(Looper.getMainLooper()).postDelayed({
                    startLiveSearch()
                }, 100)
            }
        }.show()
    }
    
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                enableMyLocation()
                startLocationUpdates()
            } else {
                showToast("Permesso posizione necessario per il funzionamento")
            }
        }
    }
    
    // Lifecycle methods for MapView
    override fun onResume() {
        super.onResume()
        mapView.onResume()
        if (::fusedLocationClient.isInitialized) {
            startLocationUpdates()
        }
    }
    
    override fun onPause() {
        super.onPause()
        mapView.onPause()
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        mapView.onDestroy()
        updateRunnable?.let {
            updateHandler.removeCallbacks(it)
        }
    }
    
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }
    
    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }
}

// Data Classes
data class FuelStation(
    val id: String,
    val name: String,
    val brand: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    var distance: Float,
    val prices: Map<String, Float>
)

data class StationResponse(
    val stations: List<FuelStation>
)

enum class FuelType(val value: String) {
    DIESEL("diesel"),
    BENZINA("benzina"),
    GPL("gpl"),
    METANO("metano")
}
