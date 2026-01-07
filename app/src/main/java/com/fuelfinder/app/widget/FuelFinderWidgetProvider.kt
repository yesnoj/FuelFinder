package com.fuelfinder.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.widget.RemoteViews
import android.util.Log
import com.fuelfinder.app.ApiClient
import com.fuelfinder.app.DistributorDto
import com.fuelfinder.app.FuelStation
import com.fuelfinder.app.FuelType
import com.fuelfinder.app.MainActivity
import com.fuelfinder.app.R
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.*
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

class FuelFinderWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val TAG = "FuelFinderWidget"
        const val ACTION_UPDATE_WIDGET = "com.fuelfinder.app.UPDATE_WIDGET"
        const val ACTION_NAVIGATE = "com.fuelfinder.app.NAVIGATE"
        const val ACTION_TOGGLE_SORT = "com.fuelfinder.app.TOGGLE_SORT"
        const val EXTRA_STATION_LAT = "station_lat"
        const val EXTRA_STATION_LON = "station_lon"
        const val EXTRA_WIDGET_ID = "widget_id"

        private const val PREF_NAME = "FuelFinderWidget"
        private const val KEY_SORT_MODE = "sort_mode_"
        private const val KEY_UPDATE_INTERVAL = "update_interval"
        private const val KEY_MAX_RESULTS = "max_results"
        private const val KEY_FUEL_TYPE = "fuel_type"
        private const val KEY_LOOK_AHEAD_KM = "look_ahead_km"
        private const val KEY_ALONG_ROUTE_MODE = "along_route_mode"
        private const val KEY_USE_REAL_DISTANCE = "use_real_distance"
        private const val KEY_APP_INITIALIZED = "app_initialized"

        // Colori hardcoded per evitare problemi con i widget
        private const val COLOR_GREEN = "#4CAF50"
        private const val COLOR_ORANGE = "#FF6F00"
        private const val COLOR_RED = "#F44336"
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        Log.d(TAG, "onUpdate called for ${appWidgetIds.size} widgets")
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        Log.d(TAG, "onReceive: ${intent.action}")

        when (intent.action) {
            ACTION_UPDATE_WIDGET -> {
                val appWidgetId = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    val appWidgetManager = AppWidgetManager.getInstance(context)
                    updateWidget(context, appWidgetManager, appWidgetId)
                }
            }
            ACTION_NAVIGATE -> {
                val lat = intent.getDoubleExtra(EXTRA_STATION_LAT, 0.0)
                val lon = intent.getDoubleExtra(EXTRA_STATION_LON, 0.0)
                navigateToStation(context, lat, lon)
            }
            ACTION_TOGGLE_SORT -> {
                val appWidgetId = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    toggleSortMode(context, appWidgetId)
                    val appWidgetManager = AppWidgetManager.getInstance(context)
                    updateWidget(context, appWidgetManager, appWidgetId)
                }
            }
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        Log.d(TAG, "Widget enabled")
        scheduleNextUpdate(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        Log.d(TAG, "Widget disabled")
        cancelScheduledUpdate(context)
    }

    private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        try {
            Log.d(TAG, "Updating widget $appWidgetId")

            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

            // Controlla se l'app è stata inizializzata
            if (!prefs.getBoolean(KEY_APP_INITIALIZED, false)) {
                Log.d(TAG, "App not initialized, showing setup widget")
                showSetupWidget(context, appWidgetManager, appWidgetId)
                return
            }

            // Carica le impostazioni salvate
            val maxResults = minOf(prefs.getInt(KEY_MAX_RESULTS, 3), 3)
            val fuelType = prefs.getString(KEY_FUEL_TYPE, FuelType.GASOLIO.value) ?: FuelType.GASOLIO.value
            val lookAheadKm = prefs.getInt(KEY_LOOK_AHEAD_KM, 10)
            val alongRouteMode = prefs.getBoolean(KEY_ALONG_ROUTE_MODE, false) // Default 360°
            val sortMode = prefs.getString("${KEY_SORT_MODE}$appWidgetId", "PRICE") ?: "PRICE"

            Log.d(TAG, "Settings: maxResults=$maxResults, fuel=$fuelType, lookAhead=$lookAheadKm, sortMode=$sortMode")

            // Mostra widget di caricamento
            showLoadingWidget(context, appWidgetManager, appWidgetId)

            // Ottieni la posizione corrente e cerca i distributori
            fetchStationsAndUpdate(context, appWidgetManager, appWidgetId, maxResults, fuelType, lookAheadKm, alongRouteMode, sortMode)

        } catch (e: Exception) {
            Log.e(TAG, "Error updating widget", e)
            showErrorWidget(context, appWidgetManager, appWidgetId, "Errore widget")
        }
    }

    private fun showSetupWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_fuel_finder_setup)

        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_setup_container, pendingIntent)

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    private fun showLoadingWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_fuel_finder)
        views.setTextViewText(R.id.widget_empty_text, "Caricamento...")
        views.setViewVisibility(R.id.widget_empty_text, android.view.View.VISIBLE)
        views.setViewVisibility(R.id.station_1, android.view.View.GONE)
        views.setViewVisibility(R.id.station_2, android.view.View.GONE)
        views.setViewVisibility(R.id.station_3, android.view.View.GONE)
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    private fun fetchStationsAndUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        maxResults: Int,
        fuelType: String,
        lookAheadKm: Int,
        alongRouteMode: Boolean,
        sortMode: String
    ) {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

        try {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location: Location? ->
                    if (location != null) {
                        Log.d(TAG, "Location obtained: ${location.latitude}, ${location.longitude}")
                        searchStations(context, appWidgetManager, appWidgetId, location, maxResults, fuelType, lookAheadKm, alongRouteMode, sortMode)
                    } else {
                        Log.w(TAG, "Location is null")
                        showErrorWidget(context, appWidgetManager, appWidgetId, "Posizione non disponibile")
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to get location", e)
                    showErrorWidget(context, appWidgetManager, appWidgetId, "Errore GPS")
                }
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission denied", e)
            showErrorWidget(context, appWidgetManager, appWidgetId, "Permessi GPS mancanti")
        }
    }

    private fun searchStations(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        location: Location,
        maxResults: Int,
        fuelType: String,
        lookAheadKm: Int,
        alongRouteMode: Boolean,
        sortMode: String
    ) {
        Log.d(TAG, "Searching stations at ${location.latitude}, ${location.longitude}")

        ApiClient.fuelService.getNearbyStations(
            latitude = location.latitude,
            longitude = location.longitude,
            distanceKm = lookAheadKm,
            fuel = fuelType,
            results = maxResults * 2
        ).enqueue(object : Callback<List<DistributorDto>> {
            override fun onResponse(call: Call<List<DistributorDto>>, response: Response<List<DistributorDto>>) {
                Log.d(TAG, "API response: ${response.code()}")
                if (response.isSuccessful) {
                    val stations = processStations(response.body() ?: emptyList(), location, maxResults, alongRouteMode, sortMode)
                    Log.d(TAG, "Found ${stations.size} stations, sorting by: $sortMode")
                    displayStations(context, appWidgetManager, appWidgetId, stations, sortMode)
                } else {
                    Log.e(TAG, "API error: ${response.code()}")
                    showErrorWidget(context, appWidgetManager, appWidgetId, "Errore API")
                }
            }

            override fun onFailure(call: Call<List<DistributorDto>>, t: Throwable) {
                Log.e(TAG, "Network error", t)
                showErrorWidget(context, appWidgetManager, appWidgetId, "Errore rete")
            }
        })
    }

    private fun processStations(
        dtos: List<DistributorDto>,
        location: Location,
        maxResults: Int,
        alongRouteMode: Boolean,
        sortMode: String
    ): List<FuelStation> {
        val stations = dtos.mapNotNull { dto ->
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
                prices = mapOf("fuel" to price),
                airDistanceKm = distanceKm,
                routeDistanceKm = null,
                routeDurationSec = null,
                lastUpdate = dto.data
            )
        }

        val filtered = if (alongRouteMode && location.hasBearing()) {
            filterStationsAlongRoute(stations, location, maxResults)
        } else {
            stations.sortedBy { it.airDistanceKm }.take(maxResults)
        }

        // IMPORTANTE: Ordina DOPO il filtraggio in base al sortMode
        return when (sortMode) {
            "PRICE" -> filtered.sortedBy { it.prices.values.firstOrNull() ?: Double.MAX_VALUE }
            "DISTANCE" -> filtered.sortedBy { it.airDistanceKm ?: Double.MAX_VALUE }
            else -> filtered
        }
    }

    private fun filterStationsAlongRoute(stations: List<FuelStation>, location: Location, maxResults: Int): List<FuelStation> {
        val bearingDeg = location.bearing.toDouble()
        val origin = Pair(location.latitude, location.longitude)

        return stations.filter { station ->
            val angle = angleToPoint(origin, Pair(station.latitude, station.longitude), bearingDeg)
            angle < 70.0
        }.sortedBy { it.airDistanceKm }.take(maxResults)
    }

    private fun displayStations(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        stations: List<FuelStation>,
        sortMode: String
    ) {
        try {
            val views = RemoteViews(context.packageName, R.layout.widget_fuel_finder)

            // Imposta l'indicatore di ordinamento
            val sortText = if (sortMode == "PRICE") "• Prezzo" else "• Distanza"
            views.setTextViewText(R.id.widget_sort_indicator, sortText)

            // Setup click handlers
            setupClickHandlers(context, views, appWidgetId)

            // Aggiorna ora ultimo aggiornamento
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            views.setTextViewText(R.id.widget_update_time, "Aggiornato: ${timeFormat.format(Date())}")

            // Mostra le stazioni con i colori corretti
            displayStationList(context, views, stations, appWidgetId, sortMode)

            appWidgetManager.updateAppWidget(appWidgetId, views)

        } catch (e: Exception) {
            Log.e(TAG, "Error displaying stations", e)
            showErrorWidget(context, appWidgetManager, appWidgetId, "Errore visualizzazione")
        }
    }

    private fun setupClickHandlers(context: Context, views: RemoteViews, appWidgetId: Int) {
        // Sort button
        val sortIntent = Intent(context, FuelFinderWidgetProvider::class.java).apply {
            action = ACTION_TOGGLE_SORT
            putExtra(EXTRA_WIDGET_ID, appWidgetId)
        }
        val sortPendingIntent = PendingIntent.getBroadcast(
            context,
            appWidgetId * 1000,
            sortIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_sort_button, sortPendingIntent)

        // Refresh button
        val refreshIntent = Intent(context, FuelFinderWidgetProvider::class.java).apply {
            action = ACTION_UPDATE_WIDGET
            putExtra(EXTRA_WIDGET_ID, appWidgetId)
        }
        val refreshPendingIntent = PendingIntent.getBroadcast(
            context,
            appWidgetId * 2000,
            refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_refresh_button, refreshPendingIntent)

        // Open app
        val openAppIntent = Intent(context, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_header, openAppPendingIntent)
    }

    private fun displayStationList(context: Context, views: RemoteViews, stations: List<FuelStation>, appWidgetId: Int, sortMode: String) {
        // Hide all stations first
        views.setViewVisibility(R.id.station_1, android.view.View.GONE)
        views.setViewVisibility(R.id.station_2, android.view.View.GONE)
        views.setViewVisibility(R.id.station_3, android.view.View.GONE)

        if (stations.isEmpty()) {
            views.setTextViewText(R.id.widget_empty_text, "Nessun distributore trovato")
            views.setViewVisibility(R.id.widget_empty_text, android.view.View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widget_empty_text, android.view.View.GONE)

            // IMPORTANTE: Determina i colori in base all'ordinamento
            val colorMapping = when (sortMode) {
                "PRICE" -> {
                    // Se ordinato per prezzo: verde per il più economico, poi arancio
                    mapOf(
                        0 to COLOR_GREEN,
                        1 to COLOR_ORANGE,
                        2 to COLOR_RED
                    )
                }
                "DISTANCE" -> {
                    // Se ordinato per distanza: verde per il più vicino, poi arancio
                    mapOf(
                        0 to COLOR_GREEN,
                        1 to COLOR_ORANGE,
                        2 to COLOR_RED
                    )
                }
                else -> {
                    // Default
                    mapOf(
                        0 to COLOR_GREEN,
                        1 to COLOR_ORANGE,
                        2 to COLOR_RED
                    )
                }
            }

            stations.take(3).forEachIndexed { index, station ->
                val stationViewId = when (index) {
                    0 -> R.id.station_1
                    1 -> R.id.station_2
                    else -> R.id.station_3
                }

                views.setViewVisibility(stationViewId, android.view.View.VISIBLE)

                val nameId = context.resources.getIdentifier("station_${index + 1}_name", "id", context.packageName)
                val priceId = context.resources.getIdentifier("station_${index + 1}_price", "id", context.packageName)
                val distanceId = context.resources.getIdentifier("station_${index + 1}_distance", "id", context.packageName)
                val navigateId = context.resources.getIdentifier("station_${index + 1}_navigate", "id", context.packageName)

                views.setTextViewText(nameId, station.name)

                // Formatta prezzo e distanza
                val priceText = String.format("€%.3f", station.prices.values.firstOrNull() ?: 0.0)
                val distanceText = String.format("%.1f km", station.airDistanceKm ?: 0.0)

                views.setTextViewText(priceId, priceText)
                views.setTextViewText(distanceId, distanceText)

                // NOTA: Non possiamo cambiare i colori dinamicamente nei widget
                // I colori sono definiti nel layout XML e rimangono fissi
                // Il verde indica sempre il primo risultato (migliore secondo l'ordinamento corrente)

                // Navigation click
                val navigateIntent = Intent(context, FuelFinderWidgetProvider::class.java).apply {
                    action = ACTION_NAVIGATE
                    putExtra(EXTRA_STATION_LAT, station.latitude)
                    putExtra(EXTRA_STATION_LON, station.longitude)
                }
                val navigatePendingIntent = PendingIntent.getBroadcast(
                    context,
                    appWidgetId * 1000 + index + 1,
                    navigateIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(navigateId, navigatePendingIntent)
            }
        }
    }

    private fun showErrorWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, error: String) {
        Log.e(TAG, "Showing error widget: $error")
        val views = RemoteViews(context.packageName, R.layout.widget_fuel_finder)
        views.setTextViewText(R.id.widget_empty_text, error)
        views.setViewVisibility(R.id.widget_empty_text, android.view.View.VISIBLE)
        views.setViewVisibility(R.id.station_1, android.view.View.GONE)
        views.setViewVisibility(R.id.station_2, android.view.View.GONE)
        views.setViewVisibility(R.id.station_3, android.view.View.GONE)

        // Mantieni i click handler anche in caso di errore
        setupClickHandlers(context, views, appWidgetId)

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    private fun toggleSortMode(context: Context, appWidgetId: Int) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val currentMode = prefs.getString("${KEY_SORT_MODE}$appWidgetId", "PRICE") ?: "PRICE"
        val newMode = if (currentMode == "PRICE") "DISTANCE" else "PRICE"
        prefs.edit().putString("${KEY_SORT_MODE}$appWidgetId", newMode).apply()
        Log.d(TAG, "Sort mode changed from $currentMode to $newMode")
    }

    private fun navigateToStation(context: Context, lat: Double, lon: Double) {
        val uri = Uri.parse("google.navigation:q=$lat,$lon&mode=d")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            setPackage("com.google.android.apps.maps")
        }

        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        } else {
            val browserUri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lon")
            val browserIntent = Intent(Intent.ACTION_VIEW, browserUri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(browserIntent)
        }
    }

    private fun scheduleNextUpdate(context: Context) {
        // Implementazione semplificata - usa updatePeriodMillis nel widget_info
        Log.d(TAG, "Update scheduling delegated to system")
    }

    private fun cancelScheduledUpdate(context: Context) {
        // Cleanup se necessario
        Log.d(TAG, "Update scheduling cancelled")
    }

    // Utility functions
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

    private fun angleToPoint(origin: Pair<Double, Double>, point: Pair<Double, Double>, bearingDeg: Double): Double {
        val dLat = Math.toRadians(point.first - origin.first)
        val dLon = Math.toRadians(point.second - origin.second)
        val lat1 = Math.toRadians(origin.first)

        val y = sin(dLon) * cos(Math.toRadians(point.first))
        val x = cos(lat1) * sin(Math.toRadians(point.first)) - sin(lat1) * cos(Math.toRadians(point.first)) * cos(dLon)
        val targetBearing = Math.toDegrees(atan2(y, x))

        var diff = targetBearing - bearingDeg
        while (diff > 180) diff -= 360
        while (diff < -180) diff += 360

        return abs(diff)
    }
}