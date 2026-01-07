package com.fuelfinder.app.auto

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.core.app.ActivityCompat
import com.fuelfinder.app.*
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.*
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import kotlin.math.*

/**
 * Schermata principale per Android Auto
 * Interfaccia semplificata ottimizzata per la guida
 */
class FuelFinderCarScreen(carContext: CarContext) : Screen(carContext) {

    private var currentLocation: Location? = null
    private val stations = mutableListOf<FuelStation>()
    private var isLoading = false
    private var selectedFuelType = FuelType.GASOLIO
    private var sortMode = "DISTANCE" // Default per Auto: ordinamento per distanza

    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    init {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(carContext)
        loadSettings()
        updateLocation()
    }

    override fun onGetTemplate(): Template {
        return if (isLoading) {
            createLoadingTemplate()
        } else if (stations.isEmpty()) {
            createEmptyTemplate()
        } else {
            createStationListTemplate()
        }
    }

    private fun createLoadingTemplate(): Template {
        return MessageTemplate.Builder("Ricerca distributori...")
            .setTitle("FuelFinder")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }

    private fun createEmptyTemplate(): Template {
        // Usa un ItemList vuoto con un messaggio
        val itemList = ItemList.Builder()
            .setNoItemsMessage("Nessun distributore trovato")
            .build()

        val actionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle("Riprova")
                    .setOnClickListener { searchStations() }
                    .build()
            )
            .build()

        return ListTemplate.Builder()
            .setTitle("FuelFinder")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(itemList)
            .setActionStrip(actionStrip)
            .build()
    }

    private fun createStationListTemplate(): Template {
        val itemListBuilder = ItemList.Builder()

        // Aggiungi massimo 6 stazioni (limite Android Auto)
        stations.take(6).forEach { station ->
            val row = Row.Builder()
                .setTitle(station.name)
                .addText(formatPrice(station))
                .addText(formatDistance(station))
                .setOnClickListener(ParkedOnlyOnClickListener.create {
                    navigateToStation(station)
                })
                .build()

            itemListBuilder.addItem(row)
        }

        // Header con azioni
        val actionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle(if (sortMode == "DISTANCE") "Prezzo" else "Distanza")
                    .setOnClickListener {
                        toggleSortMode()
                        invalidate()
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle(getFuelLabel())
                    .setOnClickListener {
                        cycleFuelType()
                        searchStations()
                    }
                    .build()
            )
            .build()

        // Usa ListTemplate che è universalmente supportato
        return ListTemplate.Builder()
            .setTitle("Distributori vicini")
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(actionStrip)
            .setSingleList(itemListBuilder.build())
            .build()
    }

    private fun loadSettings() {
        val prefs = carContext.getSharedPreferences("FuelFinderWidget", CarContext.MODE_PRIVATE)
        val fuelTypeString = prefs.getString("fuel_type", FuelType.GASOLIO.value) ?: FuelType.GASOLIO.value
        selectedFuelType = FuelType.values().find { it.value == fuelTypeString } ?: FuelType.GASOLIO
    }

    private fun updateLocation() {
        if (ActivityCompat.checkSelfPermission(
                carContext,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // Gestisci permessi
            return
        }

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            location?.let {
                currentLocation = it
                searchStations()
            }
        }
    }

    private fun searchStations() {
        val location = currentLocation ?: return

        isLoading = true
        invalidate()

        // Usa impostazioni salvate o default
        val prefs = carContext.getSharedPreferences("FuelFinderWidget", CarContext.MODE_PRIVATE)
        val lookAheadKm = prefs.getInt("look_ahead_km", 10)
        val maxResults = minOf(prefs.getInt("max_results", 5), 6) // Max 6 per Android Auto

        ApiClient.fuelService.getNearbyStations(
            latitude = location.latitude,
            longitude = location.longitude,
            distanceKm = lookAheadKm,
            fuel = selectedFuelType.value,
            results = maxResults * 2
        ).enqueue(object : Callback<List<DistributorDto>> {
            override fun onResponse(
                call: Call<List<DistributorDto>>,
                response: Response<List<DistributorDto>>
            ) {
                if (response.isSuccessful) {
                    val dtos = response.body() ?: emptyList()
                    processStations(dtos, location, maxResults)
                }
                isLoading = false
                invalidate()
            }

            override fun onFailure(call: Call<List<DistributorDto>>, t: Throwable) {
                isLoading = false
                invalidate()
            }
        })
    }

    private fun processStations(dtos: List<DistributorDto>, location: Location, maxResults: Int) {
        stations.clear()

        val processed = dtos.mapNotNull { dto ->
            val lat = dto.latitudine ?: return@mapNotNull null
            val lon = dto.longitudine ?: return@mapNotNull null
            val price = dto.prezzo ?: return@mapNotNull null

            val distanceKm = haversineKm(location.latitude, location.longitude, lat, lon)

            FuelStation(
                id = "${dto.ranking ?: 0}_${lat}_${lon}",
                name = dto.gestore ?: "Distributore",
                brand = dto.gestore ?: "",
                address = dto.indirizzo ?: "Indirizzo non disponibile",
                latitude = lat,
                longitude = lon,
                prices = mapOf(selectedFuelType.value to price),
                airDistanceKm = distanceKm,
                routeDistanceKm = null,
                routeDurationSec = null,
                lastUpdate = dto.data
            )
        }

        // Ordina secondo il criterio selezionato
        val sorted = when (sortMode) {
            "PRICE" -> processed.sortedBy { it.prices.values.firstOrNull() ?: Double.MAX_VALUE }
            else -> processed.sortedBy { it.airDistanceKm ?: Double.MAX_VALUE }
        }

        stations.addAll(sorted.take(maxResults))
    }

    private fun toggleSortMode() {
        sortMode = if (sortMode == "DISTANCE") "PRICE" else "DISTANCE"

        // Riordina le stazioni esistenti
        val sorted = when (sortMode) {
            "PRICE" -> stations.sortedBy { it.prices.values.firstOrNull() ?: Double.MAX_VALUE }
            else -> stations.sortedBy { it.airDistanceKm ?: Double.MAX_VALUE }
        }

        stations.clear()
        stations.addAll(sorted)
    }

    private fun cycleFuelType() {
        selectedFuelType = when (selectedFuelType) {
            FuelType.GASOLIO -> FuelType.BENZINA
            FuelType.BENZINA -> FuelType.GPL
            FuelType.GPL -> FuelType.METANO
            FuelType.METANO -> FuelType.GASOLIO
        }
    }

    private fun getFuelLabel(): String {
        return when (selectedFuelType) {
            FuelType.GASOLIO -> "Diesel"
            FuelType.BENZINA -> "Benzina"
            FuelType.GPL -> "GPL"
            FuelType.METANO -> "Metano"
        }
    }

    private fun formatPrice(station: FuelStation): String {
        val price = station.prices.values.firstOrNull() ?: 0.0
        return String.format("€%.3f/L", price)
    }

    private fun formatDistance(station: FuelStation): String {
        val distance = station.airDistanceKm ?: 0.0
        return String.format("%.1f km", distance)
    }

    private fun navigateToStation(station: FuelStation) {
        val intent = Intent(CarContext.ACTION_NAVIGATE,
            Uri.parse("geo:${station.latitude},${station.longitude}"))
        carContext.startCarApp(intent)
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
}