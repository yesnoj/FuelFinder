package com.fuelfinder.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
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
import androidx.appcompat.widget.SwitchCompat
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

    private lateinit var btnFindFuel: MaterialButton
    private lateinit var btnSettings: ImageButton

    // Switch + label in alto
    private lateinit var swSearchModeTop: SwitchCompat
    private lateinit var tvModeLabel: TextView

    private lateinit var chipGroupSort: ChipGroup
    private lateinit var chipGroupFuel: ChipGroup

    private lateinit var stationRecyclerView: RecyclerView
    private lateinit var bottomSheet: View
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>
    private lateinit var tvUpdateStatus: TextView

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    private var currentLocation: Location? = null
    private var userMarker: Marker? = null

    private var isLiveSearchActive = false
    private var selectedFuelType = FuelType.GASOLIO

    // Settings
    private var lookAheadKm = 10  // Default 10 km invece di 70
    private var maxResults = 5     // Default 5 risultati invece di 50
    private var updateFrequencyMin = 1
    private var useRealDistance = false  // Default false per non usare distanze reali

    // Modalità: true = percorso, false = 360°
    private var alongRouteMode = true

    // Per quando sei in modalità percorso ma il GPS non dà bearing subito
    private var lastGoodBearingDeg: Double? = null

    private var sortMode = SortMode.PRICE

    private val stationMarkers = mutableListOf<Marker>()
    private val currentStations = mutableListOf<FuelStation>()
    private lateinit var stationAdapter: StationAdapter

    private val updateHandler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null
    private var locationRetryCount = 0

    private val lastUpdateDf = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ITALY)

    // Coroutine scope per operazioni async
    private val mainScope = CoroutineScope(Dispatchers.Main + Job())
    private val realDistanceCalculator = RealDistanceCalculator()

    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 1001
        private const val DEFAULT_ZOOM = 13f
        private const val MAX_LOCATION_RETRIES = 5

        // Autostrada: filtro laterale (km)
        private const val CORRIDOR_KM = 3.0

        // "Davanti" rispetto al bearing
        private const val AHEAD_MAX_ANGLE_DEG = 70.0

        // Bearing considerato affidabile
        private const val BEARING_ACC_MAX_DEG = 45f
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

        swSearchModeTop = findViewById(R.id.swSearchModeTop)
        tvModeLabel = findViewById(R.id.tvModeLabel)

        chipGroupSort = findViewById(R.id.chipGroupSort)
        chipGroupFuel = findViewById(R.id.chipGroupFuel)

        stationRecyclerView = findViewById(R.id.recyclerViewStations)
        bottomSheet = findViewById(R.id.bottomSheet)
        tvUpdateStatus = findViewById(R.id.tvUpdateStatus)

        stationAdapter = StationAdapter { station -> navigateToStation(station) }
        stationRecyclerView.layoutManager = LinearLayoutManager(this)
        stationRecyclerView.adapter = stationAdapter

        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED

        // Stato iniziale UI modalità
        swSearchModeTop.isChecked = alongRouteMode
        tvModeLabel.text = if (alongRouteMode) "Percorso" else "360°"
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
                locationRetryCount = 0
            }
        }
    }

    private fun setupListeners() {
        btnFindFuel.setOnClickListener { toggleLiveSearch() }
        btnSettings.setOnClickListener { openSettings() }

        // switch: cambia modalità e rilancia la ricerca se attiva
        swSearchModeTop.setOnCheckedChangeListener { _, isChecked ->
            alongRouteMode = isChecked
            tvModeLabel.text = if (alongRouteMode) "Percorso" else "360°"

            if (isLiveSearchActive) {
                searchAndUpdate()
            }
        }

        chipGroupFuel.setOnCheckedChangeListener { _, checkedId ->
            selectedFuelType = when (checkedId) {
                R.id.chipGasolio -> FuelType.GASOLIO
                R.id.chipBenzina -> FuelType.BENZINA
                R.id.chipGpl -> FuelType.GPL
                R.id.chipMetano -> FuelType.METANO
                else -> FuelType.GASOLIO
            }
            if (isLiveSearchActive) searchAndUpdate()
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

        // InfoWindow custom (titolo + distanza + aggiornato relativo)
        googleMap.setInfoWindowAdapter(object : GoogleMap.InfoWindowAdapter {
            override fun getInfoWindow(marker: Marker): View? = null

            override fun getInfoContents(marker: Marker): View {
                val v = LayoutInflater.from(this@MainActivity).inflate(R.layout.marker_info_window, null)
                val tvTitle = v.findViewById<TextView>(R.id.tvInfoTitle)
                val tvLine1 = v.findViewById<TextView>(R.id.tvInfoLine1)
                val tvLine2 = v.findViewById<TextView>(R.id.tvInfoLine2)

                tvTitle.text = marker.title ?: ""
                val st = marker.tag as? FuelStation

                // Mostra distanza reale se disponibile, altrimenti distanza aerea
                val distanceText = when {
                    st?.routeDistanceKm != null -> String.format("%.1f km (strada)", st.routeDistanceKm)
                    st?.airDistanceKm != null -> String.format("%.1f km", st.airDistanceKm)
                    else -> ""
                }
                tvLine1.text = distanceText
                tvLine2.text = st?.let { "Aggiornato: ${formatRelativeUpdate(it.lastUpdate)}" } ?: ""

                return v
            }
        })

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

        // Memorizza ultimo bearing "buono"
        if (location.hasBearing() && location.bearingAccuracyDegrees <= BEARING_ACC_MAX_DEG) {
            lastGoodBearingDeg = location.bearing.toDouble()
        }

        val latLng = LatLng(location.latitude, location.longitude)
        userMarker?.remove()
        userMarker = googleMap.addMarker(
            MarkerOptions()
                .position(latLng)
                .title("La tua posizione")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
        )

        // Aggiorna SOLO le distanze in linea d'aria quando cambia la posizione
        // Le distanze reali vengono aggiornate solo con la frequenza impostata
        if (isLiveSearchActive && currentStations.isNotEmpty()) {
            updateAirDistancesOnly()
            refreshOpenInfoWindows()
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
                updateHandler.postDelayed({ startLiveSearch() }, 2000)
            } else {
                showToast("Impossibile ottenere la posizione GPS")
                locationRetryCount = 0
            }
            return
        }

        isLiveSearchActive = true

        btnFindFuel.apply {
            text = "Stop ricerca"
            setIconResource(R.drawable.ic_stop)
            setBackgroundTintList(ContextCompat.getColorStateList(context, R.color.red))
        }

        bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED

        // ricerca immediata
        searchAndUpdate()

        // ricerca periodica
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

        // Usa lookAheadKm come raggio di ricerca per l'API
        val fetchRadiusKm = lookAheadKm

        ApiClient.fuelService.getNearbyStations(
            latitude = loc.latitude,
            longitude = loc.longitude,
            distanceKm = fetchRadiusKm,  // Usa direttamente lookAheadKm
            fuel = selectedFuelType.value,
            results = maxResults * 2  // Richiedi più risultati per poi filtrare
        ).enqueue(object : Callback<List<DistributorDto>> {

            override fun onResponse(call: Call<List<DistributorDto>>, response: Response<List<DistributorDto>>) {
                if (!response.isSuccessful) {
                    tvUpdateStatus.text = "Errore API: ${response.code()}"
                    showToast("Errore nel recupero dei dati: ${response.code()}")
                    return
                }

                val body = response.body().orEmpty()
                if (body.isEmpty()) {
                    tvUpdateStatus.text = "Nessun distributore trovato"
                    stationAdapter.updateStations(emptyList())
                    return
                }

                val stationsRaw = body.mapNotNull { dto ->
                    val lat = dto.latitudine ?: return@mapNotNull null
                    val lon = dto.longitudine ?: return@mapNotNull null
                    val price = dto.prezzo ?: return@mapNotNull null

                    // Calcola subito la distanza aerea per filtrare
                    val distanceKm = haversineKm(loc.latitude, loc.longitude, lat, lon)

                    // Filtra per distanza massima (lookAheadKm)
                    if (distanceKm > lookAheadKm) return@mapNotNull null

                    FuelStation(
                        id = "${dto.ranking ?: 0}_${lat}_${lon}",
                        name = dto.gestore ?: "Distributore",
                        brand = dto.gestore ?: "",
                        address = dto.indirizzo ?: "Indirizzo non disponibile",
                        latitude = lat,
                        longitude = lon,
                        prices = mapOf(selectedFuelType.value to price),
                        airDistanceKm = distanceKm,  // Imposta subito la distanza
                        routeDistanceKm = null,
                        routeDurationSec = null,
                        lastUpdate = dto.data
                    )
                }

                val finalList = if (!alongRouteMode) {
                    // 360° - già filtrati per distanza, prendi i primi maxResults
                    stationsRaw.sortedBy { it.airDistanceKm }.take(maxResults)
                } else {
                    // percorso (con fallback automatico a 360° se manca bearing)
                    filterStationsAlongDirectionWithFallback(stationsRaw)
                }

                updateStationDisplay(finalList)

                // Mostra il conteggio corretto in base alla modalità
                val modeText = if (alongRouteMode) "Percorso" else "360°"
                tvUpdateStatus.text = "Trovati: ${finalList.size} ($modeText) • Max: ${lookAheadKm}km • Aggiornato: ${getCurrentTime()}"

                // Aggiorna le distanze aeree (già calcolate sopra)
                // updateAirDistances() - non serve più, già fatto sopra

                // Calcola le distanze reali SOLO se abilitato
                if (useRealDistance) {
                    updateRealDistances()
                }
            }

            override fun onFailure(call: Call<List<DistributorDto>>, t: Throwable) {
                tvUpdateStatus.text = "Errore rete"
                showToast("Errore di connessione: ${t.message}")
            }
        })
    }

    /**
     * Modalità percorso:
     * - se ho bearing: filtro davanti + corridoio
     * - se NON ho bearing: fallback 360° (non lasciare lista vuota)
     */
    private fun filterStationsAlongDirectionWithFallback(all: List<FuelStation>): List<FuelStation> {
        val loc = currentLocation ?: return all
        val bearingDeg = getBearingForRouteMode(loc)

        if (bearingDeg == null) {
            // fallback 360°
            showToast("Direzione non disponibile: mostro risultati a 360°")
            return all
        }

        val origin = LatLng(loc.latitude, loc.longitude)
        val dirUnit = bearingToUnitVector(bearingDeg)

        val out = ArrayList<FuelStation>(all.size)
        for (st in all) {
            val p = LatLng(st.latitude, st.longitude)

            val proj = projectToDirectionKm(origin, p, dirUnit)   // km "davanti"
            val cross = crossTrackKm(origin, p, dirUnit)          // km laterali

            if (proj <= 0.5) continue
            if (proj > lookAheadKm.toDouble()) continue
            if (cross > CORRIDOR_KM) continue

            val angle = angleToPointDeg(origin, p, bearingDeg)
            if (angle > AHEAD_MAX_ANGLE_DEG) continue

            out.add(st)
        }

        out.sortBy { st ->
            val p = LatLng(st.latitude, st.longitude)
            projectToDirectionKm(origin, p, dirUnit)
        }

        return out.take(maxResults)
    }

    private fun getBearingForRouteMode(loc: Location): Double? {
        if (loc.hasBearing() && loc.bearingAccuracyDegrees <= BEARING_ACC_MAX_DEG) {
            return loc.bearing.toDouble()
        }
        return lastGoodBearingDeg
    }

    private fun updateStationDisplay(stations: List<FuelStation>) {
        stationMarkers.forEach { it.remove() }
        stationMarkers.clear()

        currentStations.clear()
        currentStations.addAll(stations)

        stations.forEachIndexed { index, st ->
            val marker = googleMap.addMarker(
                MarkerOptions()
                    .position(LatLng(st.latitude, st.longitude))
                    .title("${st.name} - €${String.format("%.3f", st.prices.values.firstOrNull() ?: 0.0)}/L")
                    .snippet("")
                    .icon(getMarkerIcon(index))
            )
            marker?.tag = st
            if (marker != null) stationMarkers.add(marker)
        }

        applySortAndRefresh()

        if (stations.isNotEmpty()) {
            val b = LatLngBounds.Builder()
            currentLocation?.let { b.include(LatLng(it.latitude, it.longitude)) }
            stations.forEach { b.include(LatLng(it.latitude, it.longitude)) }

            try {
                googleMap.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 100))
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    private fun applySortAndRefresh() {
        val sorted = when (sortMode) {
            SortMode.PRICE -> currentStations.sortedBy { it.prices.values.firstOrNull() ?: Double.MAX_VALUE }
            SortMode.DISTANCE -> currentStations.sortedBy {
                // Usa distanza reale se disponibile, altrimenti distanza aerea
                it.routeDistanceKm ?: it.airDistanceKm ?: Double.MAX_VALUE
            }
        }
        stationAdapter.updateStations(sorted)
    }

    private fun updateAirDistancesOnly() {
        val loc = currentLocation ?: return
        val lat1 = loc.latitude
        val lon1 = loc.longitude

        currentStations.forEach { s ->
            // Aggiorna SOLO la distanza aerea, mantiene quella stradale se c'era
            s.airDistanceKm = haversineKm(lat1, lon1, s.latitude, s.longitude)
        }

        // Ordina in base al criterio selezionato
        applySortAndRefresh()
    }

    private fun updateAirDistances() {
        val loc = currentLocation ?: return
        val lat1 = loc.latitude
        val lon1 = loc.longitude

        currentStations.forEach { s ->
            s.airDistanceKm = haversineKm(lat1, lon1, s.latitude, s.longitude)
        }

        if (!useRealDistance) {
            applySortAndRefresh()
            refreshOpenInfoWindows()
        }
    }

    private fun updateRealDistances() {
        val loc = currentLocation ?: return

        tvUpdateStatus.text = "${tvUpdateStatus.text} • Calcolo distanze stradali..."

        // Calcola le distanze reali in batch (più efficiente)
        mainScope.launch {
            try {
                val origin = Pair(loc.latitude, loc.longitude)
                val destinations = currentStations.map { Pair(it.latitude, it.longitude) }

                // Dividi in batch di 10 per rispettare i limiti API
                val batchSize = 10
                val results = mutableListOf<RealDistanceCalculator.RealDistanceResult?>()

                for (i in destinations.indices step batchSize) {
                    val batch = destinations.subList(i, minOf(i + batchSize, destinations.size))
                    val batchResults = withContext(Dispatchers.IO) {
                        realDistanceCalculator.getBatchDistances(origin, batch)
                    }
                    results.addAll(batchResults)
                }

                // Aggiorna le stazioni con le distanze reali
                currentStations.forEachIndexed { index, station ->
                    results.getOrNull(index)?.let { result ->
                        station.routeDistanceKm = result.distanceKm
                        station.routeDurationSec = result.durationMinutes * 60
                    }
                }

                applySortAndRefresh()
                refreshOpenInfoWindows()

                val modeText = if (alongRouteMode) "Percorso" else "360°"
                tvUpdateStatus.text = "Trovati: ${currentStations.size} ($modeText) • Distanze stradali calcolate"

            } catch (e: Exception) {
                showToast("Errore nel calcolo distanze stradali")
                e.printStackTrace()
            }
        }
    }

    private fun refreshOpenInfoWindows() {
        stationMarkers.forEach { m ->
            if (m.isInfoWindowShown) {
                m.hideInfoWindow()
                m.showInfoWindow()
            }
        }
    }

    private fun openSettings() {
        SettingsDialog(
            context = this,
            initialLookAheadKm = lookAheadKm,
            initialMaxResults = maxResults,
            initialFrequencyMin = updateFrequencyMin,
            initialUseRealDistance = useRealDistance  // Passa il nuovo parametro
        ) { newLookAheadKm, newMaxRes, newFreq, newUseRealDistance ->
            lookAheadKm = newLookAheadKm
            maxResults = newMaxRes
            updateFrequencyMin = newFreq
            useRealDistance = newUseRealDistance  // Salva la nuova impostazione

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

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // --- "Aggiornato: 3 ore fa" ---
    private fun formatRelativeUpdate(lastUpdate: String?): String {
        if (lastUpdate.isNullOrBlank()) return "n/d"
        val ageMin = computeAgeMinutes(lastUpdate) ?: return "n/d"
        return humanizeAge(ageMin)
    }

    private fun computeAgeMinutes(lastUpdate: String): Long? {
        return try {
            val t = lastUpdateDf.parse(lastUpdate)?.time ?: return null
            val diffMs = System.currentTimeMillis() - t
            if (diffMs < 0) return null
            TimeUnit.MILLISECONDS.toMinutes(diffMs)
        } catch (_: Exception) {
            null
        }
    }

    private fun humanizeAge(ageMin: Long): String {
        if (ageMin < 1) return "pochi secondi fa"
        if (ageMin < 2) return "1 minuto fa"
        if (ageMin < 60) return "${ageMin} minuti fa"

        val ageHours = ageMin / 60
        if (ageHours < 2) return "1 ora fa"
        if (ageHours < 24) return "${ageHours} ore fa"

        val ageDays = ageHours / 24
        if (ageDays < 2) return "1 gg fa"
        if (ageDays < 7) return "${ageDays} gg fa"

        val ageWeeks = ageDays / 7
        if (ageWeeks < 2) return "1 sett fa"
        if (ageWeeks < 5) return "${ageWeeks} sett fa"

        val ageMonths = ageDays / 30
        if (ageMonths < 2) return "1 mese fa"
        if (ageMonths < 12) return "${ageMonths} mesi fa"

        val ageYears = ageDays / 365
        return if (ageYears < 2) "1 anno fa" else "${ageYears} anni fa"
    }

    // --- distanza aria (haversine) ---
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

    // --- geometria locale EN (km) ---
    private fun bearingToUnitVector(bearingDeg: Double): DoubleArray {
        val rad = Math.toRadians(bearingDeg)
        val east = sin(rad)
        val north = cos(rad)
        return doubleArrayOf(east, north)
    }

    private fun projectToDirectionKm(origin: LatLng, p: LatLng, dirUnitEN: DoubleArray): Double {
        val (dxE, dyN) = toLocalENKm(origin, p)
        return dxE * dirUnitEN[0] + dyN * dirUnitEN[1]
    }

    private fun crossTrackKm(origin: LatLng, p: LatLng, dirUnitEN: DoubleArray): Double {
        val (dxE, dyN) = toLocalENKm(origin, p)
        val cross = dxE * (-dirUnitEN[1]) + dyN * dirUnitEN[0]
        return kotlin.math.abs(cross)
    }

    private fun angleToPointDeg(origin: LatLng, p: LatLng, bearingDeg: Double): Double {
        val (dxE, dyN) = toLocalENKm(origin, p)
        val targetBearing = Math.toDegrees(atan2(dxE, dyN))
        val diff = normalizeAngleDeg(targetBearing - bearingDeg)
        return kotlin.math.abs(diff)
    }

    private fun normalizeAngleDeg(a: Double): Double {
        var x = a
        while (x > 180) x -= 360.0
        while (x < -180) x += 360.0
        return x
    }

    private fun toLocalENKm(origin: LatLng, p: LatLng): Pair<Double, Double> {
        val lat0 = Math.toRadians(origin.latitude)
        val dLat = Math.toRadians(p.latitude - origin.latitude)
        val dLon = Math.toRadians(p.longitude - origin.longitude)
        val r = 6371.0
        val northKm = dLat * r
        val eastKm = dLon * r * cos(lat0)
        return Pair(eastKm, northKm)
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

// --- Modelli ---
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