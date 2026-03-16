package com.fuelfinder.app.auto

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.content.Intent
import android.text.SpannableString
import android.text.Spannable
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.*
import androidx.core.app.ActivityCompat
import androidx.core.graphics.drawable.IconCompat
import com.fuelfinder.app.*
import com.fuelfinder.app.widget.WidgetPreferencesHelper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.*
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.*

// ─────────────────────────────────────────────────────────────
// SCHERMATA 1 – Menu principale
// ─────────────────────────────────────────────────────────────
class FuelFinderCarScreen(carContext: CarContext) : Screen(carContext) {

    private var currentLocation: Location? = null
    private var selectedFuelType = FuelType.GASOLIO
    private var lookAheadKm = 10
    private var maxResults = 5
    private var lookAheadKm360 = 5
    private var maxResults360 = 20
    private var useRealDistance = false
    private var updateIntervalMin = 1
    private var alongRouteMode = false
    private var locationReady = false

    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    init {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(carContext)
        loadSettings()
        updateLocation()
    }

    override fun onGetTemplate(): Template {
        val itemListBuilder = ItemList.Builder()

        if (!locationReady) {
            itemListBuilder.setNoItemsMessage("Ricerca posizione GPS...")
        } else {
            val fuelLabel = getFuelLabel()
            itemListBuilder
                .addItem(Row.Builder()
                    .setTitle("I più vicini")
                    .addText("$fuelLabel · ordina per distanza")
                    .setOnClickListener { openList("DISTANCE") }
                    .setBrowsable(true)
                    .build())
                .addItem(Row.Builder()
                    .setTitle("I più economici")
                    .addText("$fuelLabel · ordina per prezzo")
                    .setOnClickListener { openList("PRICE") }
                    .setBrowsable(true)
                    .build())
                .addItem(Row.Builder()
                    .setTitle("Carburante: $fuelLabel")
                    .addText("Tocca per cambiare tipo")
                    .setOnClickListener {
                        cycleFuelType()
                        invalidate()
                    }
                    .build())
                .addItem(Row.Builder()
                    .setTitle("⚙ Impostazioni attive")
                    .addText(run {
                        val activeRadius = if (alongRouteMode) lookAheadKm else lookAheadKm360
                        val activeMax = if (alongRouteMode) maxResults else maxResults360
                        val modeLabel = if (alongRouteMode) "Percorso" else "360°"
                        val distLabel = if (useRealDistance && alongRouteMode) "Dist. reale" else "Aria"
                        "$modeLabel · ${activeRadius}km · max $activeMax · $distLabel · aggiorn. ${updateIntervalMin}min"
                    })
                    .build())
        }

        return ListTemplate.Builder()
            .setTitle("FuelFinder")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(itemListBuilder.build())
            .build()
    }

    private fun openList(sortMode: String) {
        val location = currentLocation ?: return
        loadSettings()
        val activeRadius = if (alongRouteMode) lookAheadKm else lookAheadKm360
        val activeMax = if (alongRouteMode) maxResults else maxResults360
        val activeRealDistance = useRealDistance && alongRouteMode
        screenManager.push(
            FuelFinderStationListScreen(
                carContext = carContext,
                initialLocation = location,
                selectedFuelType = selectedFuelType,
                sortMode = sortMode,
                lookAheadKm = activeRadius,
                maxResults = activeMax,
                useRealDistance = activeRealDistance,
                updateIntervalMin = updateIntervalMin,
                alongRouteMode = alongRouteMode
            )
        )
    }

    private fun loadSettings() {
        val settings = WidgetPreferencesHelper.loadSettings(carContext)
        lookAheadKm = settings.lookAheadKm
        maxResults = settings.maxResults
        lookAheadKm360 = settings.lookAheadKm360
        maxResults360 = settings.maxResults360
        useRealDistance = settings.useRealDistance
        updateIntervalMin = settings.updateIntervalMin
        alongRouteMode = settings.alongRouteMode
        selectedFuelType = FuelType.values().find { it.value == settings.fuelType } ?: FuelType.GASOLIO
    }

    private fun updateLocation() {
        if (ActivityCompat.checkSelfPermission(
                carContext, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                currentLocation = location
                locationReady = true
                invalidate()
            } else {
                mainScope.launch {
                    delay(2000)
                    updateLocation()
                }
            }
        }.addOnFailureListener {
            mainScope.launch {
                delay(2000)
                updateLocation()
            }
        }
    }

    private fun buildSettingsSummary(): String {
        val activeRadius = if (alongRouteMode) lookAheadKm else lookAheadKm360
        val activeMax = if (alongRouteMode) maxResults else maxResults360
        val modeLabel = if (alongRouteMode) "Percorso" else "360°"
        val distLabel = if (useRealDistance && alongRouteMode) "Dist. reale" else "Aria"
        return "$modeLabel · ${activeRadius}km · max $activeMax · $distLabel · aggiorn. ${updateIntervalMin}min"
    }

    private fun cycleFuelType() {
        selectedFuelType = when (selectedFuelType) {
            FuelType.GASOLIO -> FuelType.BENZINA
            FuelType.BENZINA -> FuelType.GPL
            FuelType.GPL -> FuelType.METANO
            FuelType.METANO -> FuelType.GASOLIO
        }
    }

    private fun getFuelLabel() = when (selectedFuelType) {
        FuelType.GASOLIO -> "Diesel"
        FuelType.BENZINA -> "Benzina"
        FuelType.GPL -> "GPL"
        FuelType.METANO -> "Metano"
    }
}

// ─────────────────────────────────────────────────────────────
// SCHERMATA 2 – Lista risultati con mappa (PlaceListMapTemplate)
// ─────────────────────────────────────────────────────────────
class FuelFinderStationListScreen(
    carContext: CarContext,
    initialLocation: Location,
    private val selectedFuelType: FuelType,
    private var sortMode: String,
    private val lookAheadKm: Int,
    private val maxResults: Int,
    private val useRealDistance: Boolean,
    private val updateIntervalMin: Int,
    private val alongRouteMode: Boolean
) : Screen(carContext) {

    private var location: Location = initialLocation
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(carContext)

    private val stations = mutableListOf<FuelStation>()
    private var isLoading = false
    private var lastGoodBearingDeg: Double? = null

    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var refreshJob: Job? = null

    // Limite effettivo del head unit (di solito 6, ma alcuni supportano di più)
    private val placeListLimit: Int by lazy {
        try {
            carContext.getCarService(ConstraintManager::class.java)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_PLACE_LIST)
        } catch (e: Exception) { 6 }
    }

    companion object {
        private const val CORRIDOR_KM = 3.0
        private const val AHEAD_MAX_ANGLE_DEG = 70.0
        private const val BEARING_ACC_MAX_DEG = 45f
    }

    init {
        if (location.hasBearing() && location.bearingAccuracyDegrees <= BEARING_ACC_MAX_DEG) {
            lastGoodBearingDeg = location.bearing.toDouble()
        }
        mainScope.launch { BrandLogoManager.init(carContext) }
        startPeriodicRefresh()
    }

    override fun onGetTemplate(): Template {
        val modeLabel = if (alongRouteMode) "Percorso" else "360°"
        val title = if (sortMode == "DISTANCE") "I più vicini · ${getFuelLabel()} · $modeLabel"
                    else "I più economici · ${getFuelLabel()} · $modeLabel"

        if (isLoading) {
            return PlaceListMapTemplate.Builder()
                .setTitle(title)
                .setHeaderAction(Action.BACK)
                .setLoading(true)
                .build()
        }

        val itemListBuilder = ItemList.Builder()

        if (stations.isEmpty()) {
            itemListBuilder.setNoItemsMessage("Nessun distributore nel raggio di ${lookAheadKm}km")
        } else {
            stations.take(placeListLimit).forEach { station ->
                val logoIcon = buildCarIcon(station.brand)
                val placeMarker = if (logoIcon != null) {
                    PlaceMarker.Builder().setIcon(logoIcon, PlaceMarker.TYPE_IMAGE).build()
                } else {
                    PlaceMarker.Builder().setColor(CarColor.PRIMARY).build()
                }

                val place = Place.Builder(
                    CarLocation.create(station.latitude, station.longitude)
                ).setMarker(placeMarker).build()

                val distKm = station.routeDistanceKm ?: station.airDistanceKm ?: 0.0
                val distanceSpan = DistanceSpan.create(
                    Distance.create(distKm, Distance.UNIT_KILOMETERS_P1)
                )
                val distSpannable = SpannableString("  ")
                distSpannable.setSpan(distanceSpan, 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

                // Titolo semplice — il logo è già sul marker della mappa
                val titleSpannable = SpannableString(station.name)

                // Riga 1: prezzo + data aggiornamento colorata (max 2 righe totali in PlaceListMapTemplate)
                val priceAndDate = SpannableString(buildString {
                    append(formatPrice(station))
                    val update = formatUpdateWithColor(station.lastUpdate)
                    if (update != null) append("  •  $update")
                })
                // Riapplica il colore sulla parte data
                val updateColored = formatUpdateWithColor(station.lastUpdate)
                val priceText = formatPrice(station)
                val fullText = if (updateColored != null) "$priceText  •  $updateColored" else priceText
                val line1 = if (updateColored != null) {
                    val s = SpannableString("$priceText  •  $updateColored")
                    val colorSpan = (updateColored as? SpannableString)
                        ?.getSpans(0, updateColored.length, ForegroundCarColorSpan::class.java)
                        ?.firstOrNull()
                    if (colorSpan != null) {
                        val offset = "$priceText  •  ".length
                        s.setSpan(colorSpan, offset, s.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    s
                } else {
                    SpannableString(priceText)
                }

                val rowBuilder = Row.Builder()
                    .setTitle(titleSpannable)
                    .addText(line1)       // riga 1: prezzo • data
                    .addText(distSpannable) // riga 2: distanza
                    .setMetadata(Metadata.Builder().setPlace(place).build())
                    .setOnClickListener(ParkedOnlyOnClickListener.create {
                        navigateToStation(station)
                    })

                itemListBuilder.addItem(rowBuilder.build())
            }
        }

        // Anchor = posizione corrente dell'auto
        val anchor = Place.Builder(CarLocation.create(location.latitude, location.longitude))
            .setMarker(PlaceMarker.Builder()
                .setColor(CarColor.BLUE)
                .build())
            .build()

        val actionStrip = ActionStrip.Builder()
            .addAction(Action.Builder()
                .setTitle(if (sortMode == "DISTANCE") "Più economici" else "Più vicini")
                .setOnClickListener {
                    sortMode = if (sortMode == "DISTANCE") "PRICE" else "DISTANCE"
                    reorderStations()
                    invalidate()
                }
                .build())
            .build()

        return PlaceListMapTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(Action.BACK)
            .setItemList(itemListBuilder.build())
            .setAnchor(anchor)
            .setActionStrip(actionStrip)
            .build()
    }

    private fun startPeriodicRefresh() {
        refreshJob?.cancel()
        refreshJob = mainScope.launch {
            while (true) {
                refreshLocationAndSearch()
                delay(updateIntervalMin * 60 * 1000L)
            }
        }
    }

    private suspend fun refreshLocationAndSearch() {
        if (ActivityCompat.checkSelfPermission(
                carContext, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                val newLocation = com.google.android.gms.tasks.Tasks.await(
                    fusedLocationClient.lastLocation,
                    5, java.util.concurrent.TimeUnit.SECONDS
                )
                if (newLocation != null) location = newLocation
            } catch (e: Exception) { /* usa posizione precedente */ }
        }
        searchStations()
    }

    private fun searchStations() {
        isLoading = true
        invalidate()

        ApiClient.fuelService.getNearbyStations(
            latitude = location.latitude,
            longitude = location.longitude,
            distanceKm = lookAheadKm,
            fuel = selectedFuelType.value,
            results = 200
        ).enqueue(object : Callback<List<DistributorDto>> {
            override fun onResponse(call: Call<List<DistributorDto>>, response: Response<List<DistributorDto>>) {
                if (response.isSuccessful) {
                    val dtos = response.body() ?: emptyList()
                    if (useRealDistance) {
                        mainScope.launch {
                            processStationsWithRealDistance(dtos)
                            isLoading = false
                            invalidate()
                        }
                    } else {
                        processStations(dtos)
                        isLoading = false
                        invalidate()
                    }
                } else {
                    isLoading = false
                    invalidate()
                }
            }

            override fun onFailure(call: Call<List<DistributorDto>>, t: Throwable) {
                isLoading = false
                invalidate()
            }
        })
    }

    private fun processStations(dtos: List<DistributorDto>) {
        stations.clear()

        val raw = dtos.mapNotNull { dto ->
            val lat = dto.latitudine ?: return@mapNotNull null
            val lon = dto.longitudine ?: return@mapNotNull null
            val price = dto.prezzo ?: return@mapNotNull null
            val dist = haversineKm(location.latitude, location.longitude, lat, lon)
            if (dist > lookAheadKm) return@mapNotNull null
            FuelStation(
                id = "${dto.ranking ?: 0}_${lat}_${lon}",
                name = dto.gestore ?: "Distributore",
                brand = dto.gestore ?: "",
                address = dto.indirizzo ?: "",
                latitude = lat, longitude = lon,
                prices = mapOf(selectedFuelType.value to price),
                airDistanceKm = dist,
                routeDistanceKm = null, routeDurationSec = null,
                lastUpdate = dto.data
            )
        }

        val filtered = if (alongRouteMode) filterAlongRoute(raw) else raw
        val sorted = when (sortMode) {
            "PRICE" -> filtered.sortedBy { it.prices.values.firstOrNull() ?: Double.MAX_VALUE }
            else    -> filtered.sortedBy { it.airDistanceKm ?: Double.MAX_VALUE }
        }
        stations.addAll(sorted.take(maxResults))
    }

    private suspend fun processStationsWithRealDistance(dtos: List<DistributorDto>) {
        val raw = dtos.mapNotNull { dto ->
            val lat = dto.latitudine ?: return@mapNotNull null
            val lon = dto.longitudine ?: return@mapNotNull null
            val price = dto.prezzo ?: return@mapNotNull null
            val dist = haversineKm(location.latitude, location.longitude, lat, lon)
            if (dist > lookAheadKm) return@mapNotNull null
            FuelStation(
                id = "${dto.ranking ?: 0}_${lat}_${lon}",
                name = dto.gestore ?: "Distributore",
                brand = dto.gestore ?: "",
                address = dto.indirizzo ?: "",
                latitude = lat, longitude = lon,
                prices = mapOf(selectedFuelType.value to price),
                airDistanceKm = dist,
                routeDistanceKm = null, routeDurationSec = null,
                lastUpdate = dto.data
            )
        }

        val filtered = if (alongRouteMode) filterAlongRoute(raw) else raw
        val candidates = filtered.sortedBy { it.airDistanceKm ?: Double.MAX_VALUE }.take(maxResults * 2)

        val calculator = RealDistanceCalculator()
        val realDistances = calculator.getBatchDistances(
            origin = Pair(location.latitude, location.longitude),
            destinations = candidates.map { Pair(it.latitude, it.longitude) }
        )

        val withReal = candidates.mapIndexed { i, station ->
            val real = realDistances.getOrNull(i)
            station.copy(routeDistanceKm = real?.distanceKm, routeDurationSec = real?.durationMinutes?.let { it * 60 })
        }

        val sorted = when (sortMode) {
            "PRICE" -> withReal.sortedBy { it.prices.values.firstOrNull() ?: Double.MAX_VALUE }
            else    -> withReal.sortedBy { it.routeDistanceKm ?: it.airDistanceKm ?: Double.MAX_VALUE }
        }
        stations.clear()
        stations.addAll(sorted.take(maxResults))
    }

    private fun filterAlongRoute(all: List<FuelStation>): List<FuelStation> {
        val bearingDeg = getBearing() ?: return all
        val originLat = location.latitude
        val originLon = location.longitude
        val dirUnit = bearingToUnitVector(bearingDeg)
        return all.filter { st ->
            val proj  = projectToDirectionKm(originLat, originLon, st.latitude, st.longitude, dirUnit)
            val cross = crossTrackKm(originLat, originLon, st.latitude, st.longitude, dirUnit)
            val angle = angleToPointDeg(originLat, originLon, st.latitude, st.longitude, bearingDeg)
            proj > 0.5 && proj <= lookAheadKm && cross <= CORRIDOR_KM && angle <= AHEAD_MAX_ANGLE_DEG
        }.sortedBy { st ->
            projectToDirectionKm(originLat, originLon, st.latitude, st.longitude, bearingToUnitVector(bearingDeg))
        }
    }

    private fun reorderStations() {
        val sorted = when (sortMode) {
            "PRICE" -> stations.sortedBy { it.prices.values.firstOrNull() ?: Double.MAX_VALUE }
            else    -> stations.sortedBy { if (useRealDistance) it.routeDistanceKm ?: it.airDistanceKm ?: Double.MAX_VALUE
                                           else it.airDistanceKm ?: Double.MAX_VALUE }
        }
        stations.clear()
        stations.addAll(sorted)
    }

    private fun getBearing(): Double? {
        if (location.hasBearing() && location.bearingAccuracyDegrees <= BEARING_ACC_MAX_DEG)
            return location.bearing.toDouble()
        return lastGoodBearingDeg
    }

    // ── Geometria locale ──────────────────────────────────────
    private fun bearingToUnitVector(b: Double) = doubleArrayOf(sin(Math.toRadians(b)), cos(Math.toRadians(b)))
    private fun toLocalENKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Pair<Double, Double> {
        val r = 6371.0; val lat0 = Math.toRadians(lat1)
        return Pair(Math.toRadians(lon2 - lon1) * r * cos(lat0), Math.toRadians(lat2 - lat1) * r)
    }
    private fun projectToDirectionKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double, d: DoubleArray): Double {
        val (e, n) = toLocalENKm(lat1, lon1, lat2, lon2); return e * d[0] + n * d[1]
    }
    private fun crossTrackKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double, d: DoubleArray): Double {
        val (e, n) = toLocalENKm(lat1, lon1, lat2, lon2); return abs(e * (-d[1]) + n * d[0])
    }
    private fun angleToPointDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double, bearingDeg: Double): Double {
        val (e, n) = toLocalENKm(lat1, lon1, lat2, lon2)
        var diff = Math.toDegrees(atan2(e, n)) - bearingDeg
        while (diff > 180) diff -= 360.0; while (diff < -180) diff += 360.0; return abs(diff)
    }
    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0; val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat/2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon/2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    // ── UI helpers ────────────────────────────────────────────
    private fun buildCarIcon(brandName: String?): CarIcon? {
        val bitmap = BrandLogoManager.getBitmapByBrand(brandName, sizeDp = 100) ?: return null
        return try { CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build() } catch (e: Exception) { null }
    }

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ITALY)

    private fun formatUpdateWithColor(lastUpdate: String?): CharSequence? {
        if (lastUpdate.isNullOrBlank()) return null
        return try {
            val parsed = dateFormat.parse(lastUpdate) ?: return null
            val hours = TimeUnit.MILLISECONDS.toHours(System.currentTimeMillis() - parsed.time)
            val label = when {
                hours < 24  -> "${hours}h fa"
                else        -> "${hours / 24}gg fa"
            }
            val color = when {
                hours < 24  -> CarColor.GREEN
                hours < 72  -> CarColor.YELLOW
                else        -> CarColor.createCustom(0xFFFF7043.toInt(), 0xFFFF7043.toInt()) // arancione chiaro
            }
            val s = SpannableString(label)
            s.setSpan(ForegroundCarColorSpan.create(color), 0, s.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            s
        } catch (e: Exception) { null }
    }

    private fun formatPrice(station: FuelStation): String {
        val price = station.prices.values.firstOrNull() ?: 0.0
        return String.format("€%.3f/L", price)
    }

    private fun formatDistance(station: FuelStation): String {
        return if (useRealDistance && station.routeDistanceKm != null)
            String.format("%.1f km (strada)", station.routeDistanceKm)
        else String.format("%.1f km", station.airDistanceKm ?: 0.0)
    }

    private fun navigateToStation(station: FuelStation) {
        val intent = Intent(CarContext.ACTION_NAVIGATE,
            Uri.parse("geo:${station.latitude},${station.longitude}"))
        carContext.startCarApp(intent)
    }

    private fun getFuelLabel() = when (selectedFuelType) {
        FuelType.GASOLIO -> "Diesel"; FuelType.BENZINA -> "Benzina"
        FuelType.GPL -> "GPL"; FuelType.METANO -> "Metano"
    }
}
