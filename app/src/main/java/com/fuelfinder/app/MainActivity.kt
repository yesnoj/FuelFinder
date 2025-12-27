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
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    // UI
    private lateinit var mapView: MapView
    private lateinit var googleMap: GoogleMap
    private lateinit var btnFindFuel: MaterialButton
    private lateinit var btnSettings: ImageButton
    private lateinit var chipGroupSort: ChipGroup
    private lateinit var chipGroupFuel: ChipGroup
    private lateinit var stationRecyclerView: RecyclerView
    private lateinit var bottomSheet: View
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>
    private lateinit var tvUpdateStatus: TextView

    // Location
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var currentLocation: Location? = null
    private var userMarker: Marker? = null

    // Search state
    private var isLiveSearchActive = false
    private var selectedFuelType = FuelType.GASOLIO
    private var searchRadiusKm = 10
    private var maxResults = 20
    private var updateFrequencyMin = 1
    private var sortMode = SortMode.PRICE
    private var originalSearchRadius = 10

    // Stations
    private val stationMarkers = mutableListOf<Marker>()
    private val currentStations = mutableListOf<FuelStation>()
    private lateinit var stationAdapter: StationAdapter

    // Update handler
    private val updateHandler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null
    private var locationRetryCount = 0

    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 1001
        private const val DEFAULT_ZOOM = 13f
        private const val MAX_LOCATION_RETRIES = 5
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
        chipGroupFuel = findViewById(R.id.chipGroupFuel)
        stationRecyclerView = findViewById(R.id.recyclerViewStations)
        bottomSheet = findViewById(R.id.bottomSheet)
        tvUpdateStatus = findViewById(R.id.tvUpdateStatus)

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
                locationResult.lastLocation?.let {
                    updateUserLocation(it)
                    locationRetryCount = 0
                }
            }
        }
    }

    private fun setupListeners() {
        btnFindFuel.setOnClickListener { toggleLiveSearch() }
        btnSettings.setOnClickListener { openSettings() }

        // Fuel type selection
        chipGroupFuel.setOnCheckedChangeListener { _, checkedId ->
            selectedFuelType = when (checkedId) {
                R.id.chipGasolio -> FuelType.GASOLIO
                R.id.chipBenzina -> FuelType.BENZINA
                R.id.chipGpl -> FuelType.GPL
                R.id.chipMetano -> FuelType.METANO
                else -> FuelType.GASOLIO
            }

            // If search is active, update immediately
            if (isLiveSearchActive) {
                searchAndUpdate()
            }
        }

        // Sort mode selection
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
            googleMap.isMyLocationEnabled = false
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

        // Update marker
        val latLng = LatLng(location.latitude, location.longitude)
        userMarker?.remove()
        userMarker = googleMap.addMarker(
            MarkerOptions()
                .position(latLng)
                .title("La tua posizione")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
        )

        // Update distances if searching - this happens automatically when location changes
        if (isLiveSearchActive && currentStations.isNotEmpty()) {
            updateAirDistances()
            applySortAndRefresh()
            updateMarkerSnippets()
        }
    }

    private fun toggleLiveSearch() {
        if (isLiveSearchActive) stopLiveSearch() else startLiveSearch()
    }

    private fun startLiveSearch() {
        if (currentLocation == null) {
            showToast("Attendo la posizione GPS…")

            if (locationRetryCount < MAX_LOCATION_RETRIES) {
                locationRetryCount++
                updateHandler.postDelayed({
                    if (currentLocation != null) {
                        startLiveSearch()
                    } else {
                        showToast("GPS non ancora disponibile. Riprovo...")
                        startLiveSearch()
                    }
                }, 2000)
            } else {
                showToast("Impossibile ottenere la posizione GPS")
                locationRetryCount = 0
            }
            return
        }

        locationRetryCount = 0
        isLiveSearchActive = true

        searchRadiusKm = originalSearchRadius

        btnFindFuel.apply {
            text = "Stop ricerca"
            setIconResource(R.drawable.ic_stop)
            setBackgroundTintList(ContextCompat.getColorStateList(context, R.color.red))
        }

        bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED

        // Immediate search
        searchAndUpdate()

        // Periodic updates
        updateRunnable = object : Runnable {
            override fun run() {
                if (isLiveSearchActive) {
                    searchAndUpdate()
                    updateHandler.postDelayed(this, updateFrequencyMin * 60000L)
                }
            }
        }
        updateHandler.postDelayed(updateRunnable!!, updateFrequencyMin * 60000L)
    }

    private fun stopLiveSearch() {
        isLiveSearchActive = false

        btnFindFuel.apply {
            text = "Avvia ricerca"
            setIconResource(R.drawable.ic_fuel)
            setBackgroundTintList(ContextCompat.getColorStateList(context, R.color.primary))
        }

        updateRunnable?.let { updateHandler.removeCallbacks(it) }

        stationMarkers.forEach { it.remove() }
        stationMarkers.clear()
        currentStations.clear()
        stationAdapter.updateStations(emptyList())
        tvUpdateStatus.text = "In attesa ricerca…"

        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    private fun searchAndUpdate() {
        val loc = currentLocation ?: return

        tvUpdateStatus.text = "Ricerca in corso…"

        println("DEBUG: Searching - lat: ${loc.latitude}, lon: ${loc.longitude}, radius: $searchRadiusKm km, fuel: ${selectedFuelType.value}, max: $maxResults")

        ApiClient.fuelService.getNearbyStations(
            latitude = loc.latitude,
            longitude = loc.longitude,
            distanceKm = searchRadiusKm,
            fuel = selectedFuelType.value,
            results = maxResults
        ).enqueue(object : Callback<List<DistributorDto>> {
            override fun onResponse(
                call: Call<List<DistributorDto>>,
                response: Response<List<DistributorDto>>
            ) {
                if (!response.isSuccessful) {
                    tvUpdateStatus.text = "Errore API: ${response.code()}"
                    showToast("Errore nel recupero dei dati: ${response.code()}")
                    return
                }

                val body = response.body()
                println("DEBUG: API Response - ${body?.size ?: 0} stations found")

                if (body.isNullOrEmpty()) {
                    tvUpdateStatus.text = "Nessun distributore trovato"
                    if (searchRadiusKm < 50) {
                        showToast("Amplio il raggio di ricerca a ${min(searchRadiusKm * 2, 50)} km...")
                        searchRadiusKm = min(searchRadiusKm * 2, 50)
                        searchAndUpdate()
                    } else {
                        showToast("Nessun distributore trovato nel raggio massimo")
                        currentStations.clear()
                        stationAdapter.updateStations(emptyList())
                        stationMarkers.forEach { it.remove() }
                        stationMarkers.clear()
                    }
                    return
                }

                val stations = body.mapNotNull { dto ->
                    val lat = dto.latitudine ?: return@mapNotNull null
                    val lon = dto.longitudine ?: return@mapNotNull null
                    val price = dto.prezzo ?: return@mapNotNull null

                    FuelStation(
                        id = "${dto.ranking ?: 0}_${lat}_${lon}",
                        name = dto.gestore ?: "Distributore",
                        brand = dto.gestore ?: "",
                        address = dto.indirizzo ?: "Indirizzo non disponibile",
                        latitude = lat,
                        longitude = lon,
                        prices = mapOf(selectedFuelType.value to price),
                        airDistanceKm = null,
                        routeDistanceKm = null,
                        routeDurationSec = null,
                        lastUpdate = dto.data
                    )
                }

                updateStationDisplay(stations)
                tvUpdateStatus.text = "Trovati: ${stations.size} • Aggiornato: ${getCurrentTime()}"

                updateAirDistances()
            }

            override fun onFailure(call: Call<List<DistributorDto>>, t: Throwable) {
                tvUpdateStatus.text = "Errore rete"
                showToast("Errore di connessione: ${t.message}")
                println("DEBUG: Network error - ${t.message}")
            }
        })
    }

    private fun updateStationDisplay(stations: List<FuelStation>) {
        stationMarkers.forEach { it.remove() }
        stationMarkers.clear()

        currentStations.clear()
        currentStations.addAll(stations)

        stations.forEachIndexed { index, st ->
            val distanceText = st.airDistanceKm?.let {
                String.format("%.1f km", it)
            } ?: ""

            val marker = googleMap.addMarker(
                MarkerOptions()
                    .position(LatLng(st.latitude, st.longitude))
                    .title("${st.name} - €${String.format("%.3f", st.prices.values.firstOrNull() ?: 0.0)}/L")
                    .snippet(distanceText)
                    .icon(getMarkerIcon(index))
            )
            if (marker != null) {
                marker.tag = st
                stationMarkers.add(marker)
            }
        }

        applySortAndRefresh()

        if (stations.isNotEmpty()) {
            val b = LatLngBounds.Builder()
            currentLocation?.let { b.include(LatLng(it.latitude, it.longitude)) }
            stations.forEach { b.include(LatLng(it.latitude, it.longitude)) }

            try {
                googleMap.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 100))
            } catch (e: Exception) {
                googleMap.moveCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng(currentLocation?.latitude ?: 42.5, currentLocation?.longitude ?: 12.5),
                        DEFAULT_ZOOM
                    )
                )
            }
        }
    }

    private fun updateMarkerSnippets() {
        stationMarkers.forEach { marker ->
            val station = marker.tag as? FuelStation
            station?.airDistanceKm?.let {
                marker.snippet = String.format("%.1f km", it)
            }
        }
    }

    private fun applySortAndRefresh() {
        val sorted = when (sortMode) {
            SortMode.PRICE -> currentStations.sortedBy {
                it.prices.values.firstOrNull() ?: Double.MAX_VALUE
            }
            SortMode.DISTANCE -> currentStations.sortedBy {
                it.airDistanceKm ?: Double.MAX_VALUE
            }
        }
        stationAdapter.updateStations(sorted)
    }

    private fun updateAirDistances() {
        val loc = currentLocation ?: return
        val lat1 = loc.latitude
        val lon1 = loc.longitude

        currentStations.forEach { s ->
            s.airDistanceKm = haversineKm(lat1, lon1, s.latitude, s.longitude)
        }
        applySortAndRefresh()
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
        return when (index) {
            0 -> BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)
            1, 2 -> BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE)
            else -> BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)
        }
    }

    private fun getCurrentTime(): String {
        val formatter = android.text.format.DateFormat.getTimeFormat(this)
        return formatter.format(System.currentTimeMillis())
    }

    private fun openSettings() {
        SettingsDialog(
            context = this,
            initialRadiusKm = searchRadiusKm,
            initialMaxResults = maxResults,
            initialFrequencyMin = updateFrequencyMin
        ) { radiusKm, maxRes, frequencyMin ->
            originalSearchRadius = radiusKm
            searchRadiusKm = radiusKm
            maxResults = maxRes
            updateFrequencyMin = frequencyMin

            showToast("Impostazioni salvate")

            if (isLiveSearchActive) {
                updateRunnable?.let { updateHandler.removeCallbacks(it) }
                searchAndUpdate()

                updateRunnable = object : Runnable {
                    override fun run() {
                        if (isLiveSearchActive) {
                            searchAndUpdate()
                            updateHandler.postDelayed(this, updateFrequencyMin * 60000L)
                        }
                    }
                }
                updateHandler.postDelayed(updateRunnable!!, updateFrequencyMin * 60000L)
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
                showToast("Permesso posizione necessario per il funzionamento dell'app")
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
        if (::fusedLocationClient.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
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

// Models
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
    var routeDurationSec: Int?,
    val lastUpdate: String?
)

enum class FuelType(val value: String) {
    GASOLIO("gasolio"),
    BENZINA("benzina"),
    GPL("gpl"),
    METANO("metano")
}
