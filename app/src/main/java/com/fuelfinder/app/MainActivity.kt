package com.fuelfinder.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
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
import com.google.maps.android.clustering.ClusterManager
import androidx.appcompat.widget.SwitchCompat
import com.fuelfinder.app.widget.WidgetPreferencesHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mapView: MapView
    private lateinit var googleMap: GoogleMap

    private lateinit var btnSettings: ImageButton
    private lateinit var ivLoc: ImageView
    private lateinit var tvModeLabel: TextView
    private lateinit var ivLocContainer: View

    private lateinit var topBar: View                     // contenitore barra in alto

    private lateinit var chipGroupSort: ChipGroup
    private var selectedFuelType = FuelType.GASOLIO
    private lateinit var stationRecyclerView: RecyclerView
    private lateinit var bottomSheet: View
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>
    private lateinit var tvUpdateStatus: TextView

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    private var currentLocation: Location? = null
    private var userMarker: Marker? = null

    private var isLiveSearchActive = false

    // Settings
    private var lookAheadKm = 10
    private var maxResults = 5
    private var lookAheadKm360 = 5
    private var maxResults360 = 20
    private var updateFrequencyMin = 1
    private var useRealDistance = false

    // Modalità: true = percorso, false = 360°
    private var alongRouteMode = false

    private var lastGoodBearingDeg: Double? = null
    private var sortMode = SortMode.PRICE

    private lateinit var clusterManager: ClusterManager<FuelStationClusterItem>
    private val currentStations = mutableListOf<FuelStation>()
    private lateinit var stationAdapter: StationAdapter

    private val updateHandler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null
    private var locationRetryCount = 0

    private val lastUpdateDf = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ITALY)
    private val mainScope = CoroutineScope(Dispatchers.Main + Job())
    private val realDistanceCalculator = RealDistanceCalculator()

    // ── Modalità punto personalizzato ────────────────────────────────────────
    private var isCustomLocationMode = false
    private var customSearchPoint: LatLng? = null    // punto scelto dall'utente
    private var customPointMarker: Marker? = null    // marker "punto di ricerca"

    // Viste overlay modalità punto personalizzato (definite nel layout XML)
    private lateinit var ivCustomPin: ImageView
    private lateinit var btnSearchHere: com.google.android.material.floatingactionbutton.FloatingActionButton
    private lateinit var btnExitCustomMode: com.google.android.material.floatingactionbutton.FloatingActionButton
    private lateinit var btnToggleList: com.google.android.material.floatingactionbutton.FloatingActionButton
    private lateinit var customModeButtons: View
    private lateinit var cardTopInfo: com.google.android.material.card.MaterialCardView

    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 1001
        private const val DEFAULT_ZOOM = 13f
        private const val MAX_LOCATION_RETRIES = 5
        private const val CORRIDOR_KM = 3.0
        private const val AHEAD_MAX_ANGLE_DEG = 70.0
        private const val BEARING_ACC_MAX_DEG = 45f

        // Colori card top in modalità normale vs punto personalizzato
        private val COLOR_CARD_NORMAL  = Color.parseColor("#FFFFFF")   // bianco normale
        private val COLOR_CARD_CUSTOM  = Color.parseColor("#FFFFFF")   // bianca anche in modalità punto
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_main)

        initViews()
        setupCustomLocationOverlay()

        WidgetPreferencesHelper.markAppAsInitialized(this)
        syncSettingsFromWidget()

        initMap(savedInstanceState)
        initLocation()
        setupListeners()
        checkLocationPermission()

        mainScope.launch {
            BrandLogoManager.init(this@MainActivity)
            if (::clusterManager.isInitialized && currentStations.isNotEmpty()) {
                clusterManager.cluster()
            }
        }
    }

    // ── Init viste base ───────────────────────────────────────────────────────

    private fun initViews() {
        mapView         = findViewById(R.id.mapView)
        btnSettings       = findViewById(R.id.btnSettings)
        ivLoc             = findViewById(R.id.ivLoc)
        tvModeLabel       = findViewById(R.id.tvModeLabel)
        ivLocContainer    = findViewById(R.id.ivLocContainer)

        topBar            = findViewById(R.id.topBar)
        cardTopInfo     = findViewById(R.id.cardTopInfo)

        chipGroupSort = findViewById(R.id.chipGroupSort)

        stationRecyclerView = findViewById(R.id.recyclerViewStations)
        bottomSheet         = findViewById(R.id.bottomSheet)
        tvUpdateStatus      = findViewById(R.id.tvUpdateStatus)

        // Overlay punto personalizzato (definito nel layout XML)
        ivCustomPin       = findViewById(R.id.ivCustomPin)
        btnSearchHere     = findViewById(R.id.btnSearchHere)
        btnExitCustomMode = findViewById(R.id.btnExitCustomMode)
        btnToggleList     = findViewById(R.id.btnToggleList)
        customModeButtons = findViewById(R.id.customModeButtons)

        stationAdapter = StationAdapter(
            onNavigate = { station -> navigateToStation(station) },
            onItemClick = { station -> focusStationOnMap(station) }
        )
        stationRecyclerView.layoutManager = LinearLayoutManager(this)
        stationRecyclerView.adapter = stationAdapter

        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet)
        bottomSheetBehavior.isHideable = true
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN

        // Sincronizza icona btnToggleList con lo stato del bottom sheet
        bottomSheetBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(view: View, newState: Int) {}
            override fun onSlide(view: View, slideOffset: Float) {}
        })

        // Limita l'altezza massima del bottom sheet in modo da non coprire la card superiore
        bottomSheet.viewTreeObserver.addOnGlobalLayoutListener {
            val cardBottom = cardTopInfo.bottom + 24.dpToPx()
            val screenH = resources.displayMetrics.heightPixels
            val maxH = (screenH - cardBottom).coerceAtLeast(200.dpToPx())
            val lp = bottomSheet.layoutParams
            if (lp.height != maxH) {
                lp.height = maxH
                bottomSheet.layoutParams = lp
            }
        }

        ivLoc.visibility = View.VISIBLE        // Listener overlay
        btnSearchHere.setOnClickListener {
            if (isCustomLocationMode) {
                val target = googleMap.cameraPosition.target
                customSearchPoint = target
                searchFromCustomPoint(target)
            } else {
                toggleLiveSearch()
            }
        }
        btnExitCustomMode.setOnClickListener {
            exitCustomLocationMode()
        }
        btnToggleList.setOnClickListener {
            if (bottomSheetBehavior.state == BottomSheetBehavior.STATE_EXPANDED) {
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
            } else {
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
    }

    private fun setupCustomLocationOverlay() {
        // Nessuna operazione: tutto è definito nel layout XML
    }

    // ── Modalità punto personalizzato ────────────────────────────────────────

    private fun enterCustomLocationMode() {
        isCustomLocationMode = true

        // Card grigia + label
        cardTopInfo.setCardBackgroundColor(COLOR_CARD_CUSTOM)
        tvModeLabel.text = "Posizione manuale"

        // Mostra pin centrale e pulsanti overlay
        ivCustomPin.visibility = View.VISIBLE
        customModeButtons.visibility = View.VISIBLE

        // In modalità manuale il FAB diventa "cerca qui" (verde, lente)
        btnSearchHere.backgroundTintList = ContextCompat.getColorStateList(this, R.color.green_dark)
        btnSearchHere.setImageResource(R.drawable.ic_search)

        // Nascondi bottom sheet
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN

        if (isLiveSearchActive) stopLiveSearch()
    }

    private fun exitCustomLocationMode() {
        isCustomLocationMode = false
        customSearchPoint = null

        // Rimuovi eventuale marker punto personalizzato
        customPointMarker?.remove()
        customPointMarker = null

        // Ripristina card bianca + label
        cardTopInfo.setCardBackgroundColor(COLOR_CARD_NORMAL)
        tvModeLabel.text = "GPS"

        // Nascondi overlay
        ivCustomPin.visibility = View.GONE
        customModeButtons.visibility = View.GONE

        // Ripristina FAB allo stato GPS (primario, lente)
        btnSearchHere.backgroundTintList = ContextCompat.getColorStateList(this, R.color.primary)
        btnSearchHere.setImageResource(R.drawable.ic_search)

        // Torna alla posizione GPS
        currentLocation?.let {
            googleMap.animateCamera(
                CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), DEFAULT_ZOOM)
            )
        }

        // Pulisce i risultati
        clusterManager.clearItems()
        clusterManager.cluster()
        currentStations.clear()
        stationAdapter.updateStations(emptyList())
        tvUpdateStatus.text = "In attesa ricerca…"
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
    }

    private fun searchFromCustomPoint(point: LatLng) {
        tvUpdateStatus.text = "Ricerca in corso…"
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED

        // Marker che indica il punto di ricerca
        customPointMarker?.remove()
        customPointMarker = googleMap.addMarker(
            MarkerOptions()
                .position(point)
                .title("Punto di ricerca")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
        )

        ApiClient.fuelService.getNearbyStations(
            latitude  = point.latitude,
            longitude = point.longitude,
            distanceKm = lookAheadKm360,
            fuel      = selectedFuelType.value,
            results   = 200
        ).enqueue(object : Callback<List<DistributorDto>> {

            override fun onResponse(call: Call<List<DistributorDto>>, response: Response<List<DistributorDto>>) {
                if (!response.isSuccessful) {
                    tvUpdateStatus.text = "Errore API: ${response.code()}"
                    return
                }
                val body = response.body().orEmpty()
                if (body.isEmpty()) {
                    tvUpdateStatus.text = "Nessun distributore trovato"
                    stationAdapter.updateStations(emptyList())
                    return
                }

                val stations = body.mapNotNull { dto ->
                    val lat   = dto.latitudine  ?: return@mapNotNull null
                    val lon   = dto.longitudine ?: return@mapNotNull null
                    val price = dto.prezzo      ?: return@mapNotNull null
                    val dist  = haversineKm(point.latitude, point.longitude, lat, lon)
                    if (dist > lookAheadKm360) return@mapNotNull null
                    FuelStation(
                        id = "${dto.ranking ?: 0}_${lat}_${lon}",
                        name = dto.gestore ?: "Distributore",
                        brand = dto.gestore ?: "",
                        address = dto.indirizzo ?: "Indirizzo non disponibile",
                        latitude = lat,
                        longitude = lon,
                        prices = mapOf(selectedFuelType.value to price),
                        airDistanceKm = dist,
                        routeDistanceKm = null,
                        routeDurationSec = null,
                        lastUpdate = dto.data,
                        bandieraId = dto.bandieraId
                    )
                }.sortedBy { it.airDistanceKm }.take(maxResults360)

                updateStationDisplay(stations)

                tvUpdateStatus.text = "Punto personalizzato · ${stations.size} trovati · raggio ${lookAheadKm360}km"
            }

            override fun onFailure(call: Call<List<DistributorDto>>, t: Throwable) {
                tvUpdateStatus.text = "Errore rete"
                showToast("Errore di connessione: ${t.message}")
            }
        })
    }

    // ── Map ready ─────────────────────────────────────────────────────────────

    private fun initMap(savedInstanceState: Bundle?) {
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(this)
    }

    private fun initLocation() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { updateUserLocation(it) }
                locationRetryCount = 0
            }
        }
    }

    private fun setupListeners() {
        btnSettings.setOnClickListener { openSettings() }

        ivLocContainer.setOnClickListener {
            if (isCustomLocationMode) exitCustomLocationMode() else enterCustomLocationMode()
        }

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
        googleMap.uiSettings.isZoomControlsEnabled = true
        googleMap.uiSettings.isMyLocationButtonEnabled = false
        googleMap.uiSettings.isCompassEnabled = true

        googleMap.setInfoWindowAdapter(object : GoogleMap.InfoWindowAdapter {
            override fun getInfoWindow(marker: Marker): View? = null
            override fun getInfoContents(marker: Marker): View {
                val v = LayoutInflater.from(this@MainActivity).inflate(R.layout.marker_info_window, null)
                val tvTitle = v.findViewById<TextView>(R.id.tvInfoTitle)
                val tvLine1 = v.findViewById<TextView>(R.id.tvInfoLine1)
                val tvLine2 = v.findViewById<TextView>(R.id.tvInfoLine2)
                tvTitle.text = marker.title ?: ""
                val st = marker.tag as? FuelStation
                tvLine1.text = when {
                    st?.routeDistanceKm != null -> String.format("%.1f km (strada)", st.routeDistanceKm)
                    st?.airDistanceKm   != null -> String.format("%.1f km", st.airDistanceKm)
                    else -> ""
                }
                tvLine2.text = st?.let { "Aggiornato: ${formatRelativeUpdate(it.lastUpdate)}" } ?: ""
                return v
            }
        })

        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(42.5, 12.5), 6f))

        clusterManager = ClusterManager<FuelStationClusterItem>(this, googleMap).apply {
            renderer = FuelStationClusterRenderer(this@MainActivity, googleMap, this)
            setOnClusterItemClickListener { item ->
                showNavigationDialog(item.station)
                true  // consuma il click, non aprire info window
            }
        }
        googleMap.setOnCameraIdleListener(clusterManager)
        googleMap.setOnMarkerClickListener(clusterManager)

        enableMyLocation()
    }

    // ── Permessi ─────────────────────────────────────────────────────────────

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

    // ── Lifecycle ─────────────────────────────────────────────────────────────

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

    // ── Location ──────────────────────────────────────────────────────────────

    private fun updateUserLocation(location: Location) {
        currentLocation = location
        if (location.hasBearing() && location.bearingAccuracyDegrees <= BEARING_ACC_MAX_DEG) {
            lastGoodBearingDeg = location.bearing.toDouble()
        }
        val latLng = LatLng(location.latitude, location.longitude)
        if (userMarker == null) {
            userMarker = googleMap.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .title("La tua posizione")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
            )
        } else {
            userMarker?.position = latLng
        }
        if (isLiveSearchActive && currentStations.isNotEmpty()) {
            updateAirDistancesOnly()
        }
    }

    // ── Ricerca normale ───────────────────────────────────────────────────────

    private fun toggleLiveSearch() {
        if (isLiveSearchActive) stopLiveSearch() else startLiveSearch()
    }

    private fun startLiveSearch() {
        if (currentLocation == null) {
            showToast("Attendo la posizione GPS…")
            if (locationRetryCount < MAX_LOCATION_RETRIES) {
                locationRetryCount++
                updateHandler.postDelayed({ startLiveSearch() }, 2000)
            } else {
                showToast("Impossibile ottenere la posizione GPS")
                locationRetryCount = 0
            }
            return
        }
        isLiveSearchActive = true
        btnSearchHere.backgroundTintList = ContextCompat.getColorStateList(this, R.color.red)
        btnSearchHere.setImageResource(R.drawable.ic_stop)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
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

    private fun stopLiveSearch() {
        isLiveSearchActive = false
        btnSearchHere.backgroundTintList = ContextCompat.getColorStateList(this, R.color.primary)
        btnSearchHere.setImageResource(R.drawable.ic_search)
        updateRunnable?.let { updateHandler.removeCallbacks(it) }
        clusterManager.clearItems()
        clusterManager.cluster()
        currentStations.clear()
        stationAdapter.updateStations(emptyList())
        tvUpdateStatus.text = "In attesa ricerca…"
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
    }

    private fun searchAndUpdate() {
        val loc = currentLocation ?: return
        tvUpdateStatus.text = "Ricerca in corso…"
        val activeRadius     = if (alongRouteMode) lookAheadKm else lookAheadKm360
        val activeMaxResults = if (alongRouteMode) maxResults  else maxResults360

        ApiClient.fuelService.getNearbyStations(
            latitude   = loc.latitude,
            longitude  = loc.longitude,
            distanceKm = activeRadius,
            fuel       = selectedFuelType.value,
            results    = 200
        ).enqueue(object : Callback<List<DistributorDto>> {

            override fun onResponse(call: Call<List<DistributorDto>>, response: Response<List<DistributorDto>>) {
                if (!response.isSuccessful) { tvUpdateStatus.text = "Errore API: ${response.code()}"; return }
                val body = response.body().orEmpty()
                if (body.isEmpty()) { tvUpdateStatus.text = "Nessun distributore trovato"; stationAdapter.updateStations(emptyList()); return }

                val stationsRaw = body.mapNotNull { dto ->
                    val lat   = dto.latitudine  ?: return@mapNotNull null
                    val lon   = dto.longitudine ?: return@mapNotNull null
                    val price = dto.prezzo      ?: return@mapNotNull null
                    val dist  = haversineKm(loc.latitude, loc.longitude, lat, lon)
                    if (dist > activeRadius) return@mapNotNull null
                    FuelStation(
                        id = "${dto.ranking ?: 0}_${lat}_${lon}",
                        name = dto.gestore ?: "Distributore",
                        brand = dto.gestore ?: "",
                        address = dto.indirizzo ?: "Indirizzo non disponibile",
                        latitude = lat, longitude = lon,
                        prices = mapOf(selectedFuelType.value to price),
                        airDistanceKm = dist, routeDistanceKm = null, routeDurationSec = null,
                        lastUpdate = dto.data, bandieraId = dto.bandieraId
                    )
                }

                val finalList = if (!alongRouteMode) {
                    stationsRaw.sortedBy { it.airDistanceKm }.take(activeMaxResults)
                } else {
                    filterStationsAlongDirectionWithFallback(stationsRaw)
                }

                updateStationDisplay(finalList)
                val modeText = if (alongRouteMode) "Percorso" else "360°"
                tvUpdateStatus.text = "Trovati: ${finalList.size} ($modeText) • Max: ${activeRadius}km • ${getCurrentTime()}"

                if (useRealDistance && alongRouteMode) updateRealDistances()
            }

            override fun onFailure(call: Call<List<DistributorDto>>, t: Throwable) {
                tvUpdateStatus.text = "Errore rete"
                showToast("Errore di connessione: ${t.message}")
            }
        })
    }

    // ── Filtri direzione ──────────────────────────────────────────────────────

    private fun filterStationsAlongDirectionWithFallback(all: List<FuelStation>): List<FuelStation> {
        val loc = currentLocation ?: return all
        val bearingDeg = getBearingForRouteMode(loc) ?: run {
            showToast("Direzione non disponibile: mostro risultati a 360°"); return all
        }
        val origin  = LatLng(loc.latitude, loc.longitude)
        val dirUnit = bearingToUnitVector(bearingDeg)
        val out = ArrayList<FuelStation>(all.size)
        for (st in all) {
            val p     = LatLng(st.latitude, st.longitude)
            val proj  = projectToDirectionKm(origin, p, dirUnit)
            val cross = crossTrackKm(origin, p, dirUnit)
            val angle = angleToPointDeg(origin, p, bearingDeg)
            if (proj <= 0.5 || proj > lookAheadKm || cross > CORRIDOR_KM || angle > AHEAD_MAX_ANGLE_DEG) continue
            out.add(st)
        }
        out.sortBy { projectToDirectionKm(origin, LatLng(it.latitude, it.longitude), dirUnit) }
        return out.take(maxResults)
    }

    private fun getBearingForRouteMode(loc: Location): Double? {
        if (loc.hasBearing() && loc.bearingAccuracyDegrees <= BEARING_ACC_MAX_DEG) return loc.bearing.toDouble()
        return lastGoodBearingDeg
    }

    // ── Display stazioni ──────────────────────────────────────────────────────

    private fun updateStationDisplay(stations: List<FuelStation>) {
        clusterManager.clearItems()
        currentStations.clear()
        currentStations.addAll(stations)
        stations.forEach { clusterManager.addItem(FuelStationClusterItem(it)) }
        clusterManager.cluster()
        applySortAndRefresh()

        if (stations.isNotEmpty()) {
            val b = LatLngBounds.Builder()
            // In modalità punto usa il punto custom come centro dei bounds
            customSearchPoint?.let { b.include(it) }
                ?: currentLocation?.let { b.include(LatLng(it.latitude, it.longitude)) }
            stations.forEach { b.include(LatLng(it.latitude, it.longitude)) }
            try { googleMap.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 100)) }
            catch (_: Exception) {}
        }
    }

    private fun applySortAndRefresh() {
        val sorted = when (sortMode) {
            SortMode.PRICE    -> currentStations.sortedBy { it.prices.values.firstOrNull() ?: Double.MAX_VALUE }
            SortMode.DISTANCE -> currentStations.sortedBy { it.routeDistanceKm ?: it.airDistanceKm ?: Double.MAX_VALUE }
        }
        stationAdapter.updateStations(sorted)
    }

    private fun updateAirDistancesOnly() {
        val loc = currentLocation ?: return
        currentStations.forEach { s -> s.airDistanceKm = haversineKm(loc.latitude, loc.longitude, s.latitude, s.longitude) }
        applySortAndRefresh()
    }

    private fun updateRealDistances() {
        val loc = currentLocation ?: return
        tvUpdateStatus.text = "${tvUpdateStatus.text} • Calcolo distanze stradali..."
        mainScope.launch {
            try {
                val origin       = Pair(loc.latitude, loc.longitude)
                val destinations = currentStations.map { Pair(it.latitude, it.longitude) }
                val results      = mutableListOf<RealDistanceCalculator.RealDistanceResult?>()
                for (i in destinations.indices step 10) {
                    val batch = destinations.subList(i, minOf(i + 10, destinations.size))
                    results.addAll(withContext(Dispatchers.IO) { realDistanceCalculator.getBatchDistances(origin, batch) })
                }
                currentStations.forEachIndexed { idx, st ->
                    results.getOrNull(idx)?.let { r -> st.routeDistanceKm = r.distanceKm; st.routeDurationSec = r.durationMinutes * 60 }
                }
                applySortAndRefresh()
                clusterManager.cluster()
                val modeText = if (alongRouteMode) "Percorso" else "360°"
                tvUpdateStatus.text = "Trovati: ${currentStations.size} ($modeText) • Distanze stradali calcolate"
            } catch (e: Exception) {
                showToast("Errore nel calcolo distanze stradali"); e.printStackTrace()
            }
        }
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    private fun openSettings() {
        SettingsDialog(
            context = this,
            initialLookAheadKm = lookAheadKm,
            initialMaxResults = maxResults,
            initialLookAheadKm360 = lookAheadKm360,
            initialMaxResults360 = maxResults360,
            initialFrequencyMin = updateFrequencyMin,
            initialUseRealDistance = useRealDistance,
            initialAlongRouteMode = alongRouteMode,
            initialFuelType = selectedFuelType
        ) { newLookAheadKm, newMaxRes, newLookAheadKm360, newMaxRes360, newFreq, newUseRealDistance, newAlongRouteMode, newFuelType ->
            lookAheadKm        = newLookAheadKm
            maxResults         = newMaxRes
            lookAheadKm360     = newLookAheadKm360
            maxResults360      = newMaxRes360
            updateFrequencyMin = newFreq
            useRealDistance    = newUseRealDistance
            alongRouteMode     = newAlongRouteMode
            selectedFuelType   = newFuelType
            saveSettingsToWidget()
            showToast("Impostazioni salvate")
            if (isCustomLocationMode) {
                customSearchPoint?.let { searchFromCustomPoint(it) }
            } else if (isLiveSearchActive) {
                updateRunnable?.let { updateHandler.removeCallbacks(it) }
                searchAndUpdate()
                updateRunnable = object : Runnable {
                    override fun run() {
                        if (isLiveSearchActive) { searchAndUpdate(); updateHandler.postDelayed(this, updateFrequencyMin * 60000L) }
                    }
                }
                updateHandler.postDelayed(updateRunnable!!, updateFrequencyMin * 60000L)
            }
        }.show()
    }

    // ── Location updates ──────────────────────────────────────────────────────

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val req = LocationRequest.create().apply {
            interval = 5000; fastestInterval = 2000
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }
        fusedLocationClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
    }

    // ── Geometria ─────────────────────────────────────────────────────────────

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r    = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a    = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun bearingToUnitVector(bearingDeg: Double): DoubleArray {
        val rad = Math.toRadians(bearingDeg); return doubleArrayOf(sin(rad), cos(rad))
    }
    private fun projectToDirectionKm(origin: LatLng, p: LatLng, dir: DoubleArray): Double {
        val (e, n) = toLocalENKm(origin, p); return e * dir[0] + n * dir[1]
    }
    private fun crossTrackKm(origin: LatLng, p: LatLng, dir: DoubleArray): Double {
        val (e, n) = toLocalENKm(origin, p); return kotlin.math.abs(e * (-dir[1]) + n * dir[0])
    }
    private fun angleToPointDeg(origin: LatLng, p: LatLng, bearingDeg: Double): Double {
        val (e, n) = toLocalENKm(origin, p)
        return kotlin.math.abs(normalizeAngleDeg(Math.toDegrees(atan2(e, n)) - bearingDeg))
    }
    private fun normalizeAngleDeg(a: Double): Double { var x = a; while (x > 180) x -= 360.0; while (x < -180) x += 360.0; return x }
    private fun toLocalENKm(origin: LatLng, p: LatLng): Pair<Double, Double> {
        val lat0 = Math.toRadians(origin.latitude); val r = 6371.0
        return Pair(Math.toRadians(p.longitude - origin.longitude) * r * cos(lat0), Math.toRadians(p.latitude - origin.latitude) * r)
    }

    // ── Widget sync ───────────────────────────────────────────────────────────

    private fun saveSettingsToWidget() {
        WidgetPreferencesHelper.saveSettings(
            context = this, lookAheadKm = lookAheadKm, maxResults = maxResults,
            lookAheadKm360 = lookAheadKm360, maxResults360 = maxResults360,
            updateIntervalMin = updateFrequencyMin, fuelType = selectedFuelType,
            alongRouteMode = alongRouteMode, useRealDistance = useRealDistance
        )
    }

    private fun syncSettingsFromWidget() {
        val s = WidgetPreferencesHelper.loadSettings(this)
        if (!s.isInitialized) return
        lookAheadKm = s.lookAheadKm; maxResults = s.maxResults
        lookAheadKm360 = s.lookAheadKm360; maxResults360 = s.maxResults360
        updateFrequencyMin = s.updateIntervalMin; alongRouteMode = s.alongRouteMode; useRealDistance = s.useRealDistance
        selectedFuelType = when (s.fuelType) {
            FuelType.GASOLIO.value -> FuelType.GASOLIO
            FuelType.BENZINA.value -> FuelType.BENZINA
            FuelType.GPL.value     -> FuelType.GPL
            FuelType.METANO.value  -> FuelType.METANO
            else                   -> FuelType.GASOLIO
        }
    }

    // ── Navigazione ───────────────────────────────────────────────────────────

    private fun showNavigationDialog(station: FuelStation) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_station_detail, null)

        // Logo
        val ivLogo = view.findViewById<ImageView>(R.id.ivDialogLogo)
        val logoBitmap = BrandLogoManager.getBitmapByBrand(station.brand, 48)
            ?: station.bandieraId?.let { BrandLogoManager.getBitmapByBrand(BrandLogoManager.getBrandName(it), 48) }
        if (logoBitmap != null) {
            ivLogo.setImageBitmap(logoBitmap)
            ivLogo.visibility = View.VISIBLE
        }

        // Nome
        view.findViewById<TextView>(R.id.tvDialogName).text = station.name

        // Indirizzo
        view.findViewById<TextView>(R.id.tvDialogAddress).apply {
            text = station.address
            visibility = if (station.address.isNotBlank()) View.VISIBLE else View.GONE
        }

        // Distanza
        val distTv = view.findViewById<TextView>(R.id.tvDialogDistance)
        val dist = station.routeDistanceKm ?: station.airDistanceKm
        if (dist != null && dist > 0.0) {
            distTv.text = String.format("%.1f km", dist)
        } else {
            distTv.visibility = View.GONE
        }

        // Ultimo aggiornamento
        val updateTv = view.findViewById<TextView>(R.id.tvDialogUpdate)
        if (!station.lastUpdate.isNullOrBlank()) {
            updateTv.text = formatRelativeUpdate(station.lastUpdate)
        } else {
            updateTv.visibility = View.GONE
        }

        // Prezzo
        val price = station.prices.values.firstOrNull()
        val priceTv = view.findViewById<TextView>(R.id.tvDialogPrice)
        if (price != null) {
            priceTv.text = String.format("€ %.3f", price)
        } else {
            priceTv.visibility = View.GONE
        }

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDialogNavigate)
            .setOnClickListener {
                dialog.dismiss()
                navigateToStation(station)
            }

        dialog.show()
    }

    private fun focusStationOnMap(station: FuelStation) {
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        val latLng = com.google.android.gms.maps.model.LatLng(station.latitude, station.longitude)
        googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16f))
    }

    private fun navigateToStation(station: FuelStation) {
        val uri    = Uri.parse("google.navigation:q=${station.latitude},${station.longitude}&mode=d")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.google.android.apps.maps") }
        if (intent.resolveActivity(packageManager) != null) startActivity(intent)
        else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${station.latitude},${station.longitude}")))
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private fun getCurrentTime(): String = android.text.format.DateFormat.getTimeFormat(this).format(System.currentTimeMillis())
    private fun showToast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun formatRelativeUpdate(lastUpdate: String?): String {
        if (lastUpdate.isNullOrBlank()) return "n/d"
        val ageMin = computeAgeMinutes(lastUpdate) ?: return "n/d"
        return humanizeAge(ageMin)
    }
    private fun computeAgeMinutes(lastUpdate: String): Long? {
        return try { val t = lastUpdateDf.parse(lastUpdate)?.time ?: return null; val d = System.currentTimeMillis() - t; if (d < 0) null else TimeUnit.MILLISECONDS.toMinutes(d) } catch (_: Exception) { null }
    }
    private fun humanizeAge(m: Long): String = when {
        m < 1  -> "pochi secondi fa"; m < 2 -> "1 minuto fa"; m < 60 -> "${m} minuti fa"
        m/60 < 2 -> "1 ora fa"; m/60 < 24 -> "${m/60} ore fa"
        m/1440 < 2 -> "1 gg fa"; m/1440 < 7 -> "${m/1440} gg fa"
        m/10080 < 2 -> "1 sett fa"; m/10080 < 5 -> "${m/10080} sett fa"
        m/43200 < 2 -> "1 mese fa"; m/43200 < 12 -> "${m/43200} mesi fa"
        else -> "${m/525600} anni fa"
    }

    private fun getMarkerIcon(index: Int): BitmapDescriptor = when (index) {
        0    -> BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)
        1, 2 -> BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE)
        else -> BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)
    }

    private enum class SortMode { PRICE, DISTANCE }
}

// --- Modelli ---
data class FuelStation(
    val id: String, val name: String, val brand: String, val address: String,
    val latitude: Double, val longitude: Double,
    val prices: Map<String, Double>,
    var airDistanceKm: Double?, var routeDistanceKm: Double?, var routeDurationSec: Int?,
    val lastUpdate: String?, val bandieraId: Int? = null
)

enum class FuelType(val value: String) {
    GASOLIO("gasolio"), BENZINA("benzina"), GPL("gpl"), METANO("metano")
}

private fun Int.dpToPx(): Int = (this * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
