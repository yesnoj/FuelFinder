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
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    // UI
    private lateinit var mapView: MapView
    private lateinit var googleMap: GoogleMap
    private lateinit var btnFindFuel: ExtendedFloatingActionButton
    private lateinit var btnSettings: ImageButton
    private lateinit var chipGroupSort: ChipGroup
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
    private var lastLocation: Location? = null
    private var userMarker: Marker? = null

    // Trip stats
    private var tripStartTime = System.currentTimeMillis()
    private var tripDistanceKm = 0.0

    // Search state
    private var isLiveSearchActive = false
    private var selectedFuelType = FuelType.DIESEL
    private var searchRadiusKm = 10 // used ONLY to limit results we fetch
    private var updateFrequencySec = 30
    private var sortMode = SortMode.PRICE

    // Stations
    private val stationMarkers = mutableListOf<Marker>()
    private val currentStations = mutableListOf<FuelStation>()
    private lateinit var stationAdapter: StationAdapter

    // Update handler
    private val updateHandler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null

    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 1001
        private const val DEFAULT_ZOOM = 13f
        private const val MAX_DIRECTIONS_REQUESTS = 12 // keep quota under control
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
        btnSettings = findViewById(R.id.btnSettings)
        chipGroupSort = findViewById(R.id.chipGroupSort)
        stationRecyclerView = findViewById(R.id.recyclerViewStations)
        bottomSheet = findViewById(R.id.bottomSheet)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvDistance = findViewById(R.id.tvDistance)
        tvTime = findViewById(R.id.tvTime)
        tvUpdateStatus = findViewById(R.id.tvUpdateStatus)
        liveIndicator = findViewById(R.id.liveIndicator)

        stationAdapter = StationAdapter { station ->
            navigateToStation(station)
        }
        stationRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = stationAdapter
        }

        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    private fun initMap(savedInstanceState: Bundle?) {
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(this)
    }

    private fun initLocation() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { updateUserLocation(it) }
            }
        }
    }

    private fun setupListeners() {
        btnFindFuel.setOnClickListener { toggleLiveSearch() }

        btnSettings.setOnClickListener { openSettings() }

        chipGroupSort.setOnCheckedChangeListener { _, checkedId ->
            sortMode = when (checkedId) {
                R.id.chipSortDistance -> SortMode.DISTANCE
                else -> SortMode.PRICE
            }
            applySortAndRefresh()
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map

        googleMap.uiSettings.apply {
            isZoomControlsEnabled = true
            isMyLocationButtonEnabled = false
            isCompassEnabled = true
        }

        // Italy as default
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(42.5, 12.5), 6f))
        enableMyLocation()
    }

    // --------------------
    // Permissions & location
    // --------------------
    private fun checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
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
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            googleMap.isMyLocationEnabled = false // we draw our own marker
            getLastKnownLocation()
        }
    }

    private fun getLastKnownLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return

        fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
            loc?.let {
                updateUserLocation(it)
                googleMap.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), DEFAULT_ZOOM)
                )
            }
        }
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val req = LocationRequest.create().apply {
            interval = 5000
            fastestInterval = 2000
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }

        fusedLocationClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
    }

    private fun updateUserLocation(location: Location) {
        currentLocation = location

        // Trip stats
        lastLocation?.let { prev ->
            val deltaM = prev.distanceTo(location).toDouble()
            if (deltaM.isFinite() && deltaM >= 0) tripDistanceKm += deltaM / 1000.0
        }
        lastLocation = location
        updateTripStats(location.speed)

        // Marker
        val latLng = LatLng(location.latitude, location.longitude)
        userMarker?.remove()
        userMarker = googleMap.addMarker(
            MarkerOptions()
                .position(latLng)
                .title("La tua posizione")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
        )

        // If live search is active, we refresh distances (route-based) for shown stations
        if (isLiveSearchActive && currentStations.isNotEmpty()) {
            // update air distances first (cheap), then fetch directions on a limited subset
            updateAirDistances()
            fetchRouteDistancesForTopStations()
        }
    }

    private fun updateTripStats(speedMps: Float) {
        val elapsedMin = ((System.currentTimeMillis() - tripStartTime) / 60000).coerceAtLeast(0)
        tvSpeed.text = "${(speedMps * 3.6f).toInt()} km/h"
        tvDistance.text = String.format("%.1f km", tripDistanceKm)
        tvTime.text = "$elapsedMin min"
    }

    // --------------------
    // Live search
    // --------------------
    private fun toggleLiveSearch() {
        if (isLiveSearchActive) stopLiveSearch() else startLiveSearch()
    }

    private fun startLiveSearch() {
        if (currentLocation == null) {
            showToast("Attendo la posizione GPS…")
            return
        }

        isLiveSearchActive = true
        liveIndicator.visibility = View.VISIBLE

        btnFindFuel.apply {
            text = "Stop ricerca"
            setIconResource(R.drawable.ic_stop)
            setBackgroundColor(ContextCompat.getColor(context, R.color.green))
        }

        bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED

        // immediate search
        searchAndUpdate()

        // periodic updates
        updateRunnable = object : Runnable {
            override fun run() {
                if (isLiveSearchActive) {
                    searchAndUpdate()
                    updateHandler.postDelayed(this, updateFrequencySec * 1000L)
                }
            }
        }
        updateHandler.postDelayed(updateRunnable!!, updateFrequencySec * 1000L)
    }

    private fun stopLiveSearch() {
        isLiveSearchActive = false
        liveIndicator.visibility = View.GONE

        btnFindFuel.apply {
            text = "Avvia ricerca"
            setIconResource(R.drawable.ic_fuel)
            setBackgroundColor(ContextCompat.getColor(context, R.color.primary))
        }

        updateRunnable?.let { updateHandler.removeCallbacks(it) }

        // clear markers + list
        stationMarkers.forEach { it.remove() }
        stationMarkers.clear()
        currentStations.clear()
        stationAdapter.updateStations(emptyList())
        tvUpdateStatus.text = "In attesa ricerca…"
    }

    private fun searchAndUpdate() {
        val loc = currentLocation ?: return

        tvUpdateStatus.text = "Ricerca in corso…"

        ApiClient.fuelService.getNearbyStations(
            latitude = loc.latitude,
            longitude = loc.longitude,
            distanceKm = searchRadiusKm,
            fuel = selectedFuelType.value,
            results = 20
        ).enqueue(object : Callback<List<DistributorDto>> {
            override fun onResponse(
                call: Call<List<DistributorDto>>,
                response: Response<List<DistributorDto>>
            ) {
                if (!response.isSuccessful) {
                    tvUpdateStatus.text = "Errore API: ${response.code()}"
                    return
                }

                val stations = (response.body() ?: emptyList()).mapNotNull { dto ->
                    val lat = dto.latitudine ?: return@mapNotNull null
                    val lon = dto.longitudine ?: return@mapNotNull null
                    val price = (dto.prezzo ?: return@mapNotNull null).toDouble()

                    FuelStation(
                        id = (dto.ranking?.toString() ?: "0") + "_" + lat + "_" + lon,
                        name = dto.gestore ?: "Distributore",
                        brand = dto.gestore ?: "",
                        address = dto.indirizzo ?: "",
                        latitude = lat,
                        longitude = lon,
                        prices = mapOf(selectedFuelType.value to price),
                        airDistanceKm = null,
                        routeDistanceKm = null,
                        routeDurationSec = null
                    )
                }

                updateStationDisplay(stations)
                tvUpdateStatus.text = "Trovati: ${stations.size} • ${getCurrentTime()}"

                // Update distances
                updateAirDistances()
                fetchRouteDistancesForTopStations()
            }

            override fun onFailure(call: Call<List<DistributorDto>>, t: Throwable) {
                tvUpdateStatus.text = "Errore rete: ${t.message ?: "sconosciuto"}"
            }
        })
    }

    private fun updateStationDisplay(stations: List<FuelStation>) {
        // clear old station markers
        stationMarkers.forEach { it.remove() }
        stationMarkers.clear()

        currentStations.clear()
        currentStations.addAll(stations)

        // add markers
        stations.forEachIndexed { index, st ->
            val marker = googleMap.addMarker(
                MarkerOptions()
                    .position(LatLng(st.latitude, st.longitude))
                    .title(st.name)
                    .snippet(st.address)
                    .icon(getMarkerIcon(index))
            )
            if (marker != null) {
                marker.tag = st
                stationMarkers.add(marker)
            }
        }

        applySortAndRefresh()

        // adjust bounds
        if (stations.isNotEmpty()) {
            val b = LatLngBounds.Builder()
            currentLocation?.let { b.include(LatLng(it.latitude, it.longitude)) }
            stations.forEach { b.include(LatLng(it.latitude, it.longitude)) }
            googleMap.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 100))
        }
    }

    private fun applySortAndRefresh() {
        val sorted = when (sortMode) {
            SortMode.PRICE -> currentStations.sortedBy { it.prices.values.firstOrNull() ?: Double.MAX_VALUE }
            SortMode.DISTANCE -> currentStations.sortedBy {
                it.routeDistanceKm ?: it.airDistanceKm ?: Double.MAX_VALUE
            }
        }
        stationAdapter.updateStations(sorted)
    }

    // --------------------
    // Distances
    // --------------------
    private fun updateAirDistances() {
        val loc = currentLocation ?: return
        val lat1 = loc.latitude
        val lon1 = loc.longitude

        currentStations.forEach { s ->
            s.airDistanceKm = haversineKm(lat1, lon1, s.latitude, s.longitude)
        }
        applySortAndRefresh()
    }

    private fun fetchRouteDistancesForTopStations() {
        val loc = currentLocation ?: return

        // pick a subset (closest by air distance) to keep API calls limited
        val subset = currentStations
            .sortedBy { it.airDistanceKm ?: Double.MAX_VALUE }
            .take(MAX_DIRECTIONS_REQUESTS)

        subset.forEach { st ->
            val origin = "${loc.latitude},${loc.longitude}"
            val dest = "${st.latitude},${st.longitude}"
            ApiClient.directionsService.getDirections(
                origin = origin,
                destination = dest,
                mode = "driving",
                apiKey = BuildConfig.GOOGLE_MAPS_API_KEY
            ).enqueue(object : Callback<DirectionsResponse> {
                override fun onResponse(call: Call<DirectionsResponse>, response: Response<DirectionsResponse>) {
                    val body = response.body()
                    val leg = body?.routes?.firstOrNull()?.legs?.firstOrNull()
                    val distMeters = leg?.distance?.value
                    val durSec = leg?.duration?.value
                    if (distMeters != null) st.routeDistanceKm = distMeters / 1000.0
                    if (durSec != null) st.routeDurationSec = durSec
                    applySortAndRefresh()
                }

                override fun onFailure(call: Call<DirectionsResponse>, t: Throwable) {
                    // keep air distance fallback
                }
            })
        }
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    // --------------------
    // Navigation + marker icons
    // --------------------
    private fun navigateToStation(station: FuelStation) {
        val uri = Uri.parse("google.navigation:q=${station.latitude},${station.longitude}&mode=d")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
        }

        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
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
        val formatter = android.text.format.DateFormat.getTimeFormat(this)
        return formatter.format(System.currentTimeMillis())
    }

    private fun openSettings() {
        SettingsDialog(
            context = this,
            initialFuelType = selectedFuelType,
            initialRadiusKm = searchRadiusKm,
            initialFrequencySec = updateFrequencySec
        ) { fuelType, radiusKm, frequencySec ->
            selectedFuelType = fuelType
            searchRadiusKm = radiusKm
            updateFrequencySec = frequencySec

            showToast("Impostazioni aggiornate: ${fuelType.value}")

            if (isLiveSearchActive) {
                // restart timer + refresh now
                updateRunnable?.let { updateHandler.removeCallbacks(it) }
                searchAndUpdate()
                updateRunnable = object : Runnable {
                    override fun run() {
                        if (isLiveSearchActive) {
                            searchAndUpdate()
                            updateHandler.postDelayed(this, updateFrequencySec * 1000L)
                        }
                    }
                }
                updateHandler.postDelayed(updateRunnable!!, updateFrequencySec * 1000L)
            }
        }.show()
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
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
                showToast("Permesso posizione necessario")
            }
        }
    }

    // MapView lifecycle
    override fun onResume() {
        super.onResume()
        mapView.onResume()
        if (::fusedLocationClient.isInitialized) startLocationUpdates()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        if (::fusedLocationClient.isInitialized) fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView.onDestroy()
        updateRunnable?.let { updateHandler.removeCallbacks(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    private enum class SortMode { PRICE, DISTANCE }
}

// --------------------
// Models
// --------------------
data class FuelStation(
    val id: String,
    val name: String,
    val brand: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val prices: Map<String, Double>,
    var airDistanceKm: Double?,
    var routeDistanceKm: Double?,
    var routeDurationSec: Int?
)

enum class FuelType(val value: String) {
    DIESEL("diesel"),
    BENZINA("benzina"),
    GPL("gpl"),
    METANO("metano")
}
