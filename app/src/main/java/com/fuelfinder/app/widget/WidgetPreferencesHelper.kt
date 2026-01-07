package com.fuelfinder.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.fuelfinder.app.FuelType

/**
 * Helper class per gestire le preferenze condivise tra l'app principale e il widget.
 * Sincronizza le impostazioni e aggiorna il widget quando cambiano.
 */
object WidgetPreferencesHelper {
    
    private const val PREF_NAME = "FuelFinderWidget"
    const val KEY_UPDATE_INTERVAL = "update_interval"
    const val KEY_MAX_RESULTS = "max_results"
    const val KEY_FUEL_TYPE = "fuel_type"
    const val KEY_LOOK_AHEAD_KM = "look_ahead_km"
    const val KEY_ALONG_ROUTE_MODE = "along_route_mode"
    const val KEY_USE_REAL_DISTANCE = "use_real_distance"
    const val KEY_APP_INITIALIZED = "app_initialized"
    
    /**
     * Salva le impostazioni dell'app per il widget
     */
    fun saveSettings(
        context: Context,
        lookAheadKm: Int,
        maxResults: Int,
        updateIntervalMin: Int,
        fuelType: FuelType,
        alongRouteMode: Boolean,
        useRealDistance: Boolean
    ) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putInt(KEY_LOOK_AHEAD_KM, lookAheadKm)
            putInt(KEY_MAX_RESULTS, maxResults)
            putInt(KEY_UPDATE_INTERVAL, updateIntervalMin)
            putString(KEY_FUEL_TYPE, fuelType.value)
            putBoolean(KEY_ALONG_ROUTE_MODE, alongRouteMode)
            putBoolean(KEY_USE_REAL_DISTANCE, useRealDistance)
            putBoolean(KEY_APP_INITIALIZED, true)
            apply()
        }
        
        // Aggiorna tutti i widget attivi
        updateAllWidgets(context)
    }
    
    /**
     * Carica le impostazioni salvate
     */
    fun loadSettings(context: Context): Settings {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return Settings(
            lookAheadKm = prefs.getInt(KEY_LOOK_AHEAD_KM, 10),
            maxResults = prefs.getInt(KEY_MAX_RESULTS, 5),
            updateIntervalMin = prefs.getInt(KEY_UPDATE_INTERVAL, 1),
            fuelType = prefs.getString(KEY_FUEL_TYPE, FuelType.GASOLIO.value) ?: FuelType.GASOLIO.value,
            alongRouteMode = prefs.getBoolean(KEY_ALONG_ROUTE_MODE, true),
            useRealDistance = prefs.getBoolean(KEY_USE_REAL_DISTANCE, false),
            isInitialized = prefs.getBoolean(KEY_APP_INITIALIZED, false)
        )
    }
    
    /**
     * Marca l'app come inizializzata (usato al primo avvio)
     */
    fun markAppAsInitialized(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_APP_INITIALIZED, true).apply()
        updateAllWidgets(context)
    }
    
    /**
     * Forza l'aggiornamento di tutti i widget
     */
    fun updateAllWidgets(context: Context) {
        val intent = Intent(context, FuelFinderWidgetProvider::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, FuelFinderWidgetProvider::class.java)
            )
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        }
        context.sendBroadcast(intent)
    }
    
    /**
     * Data class per le impostazioni
     */
    data class Settings(
        val lookAheadKm: Int,
        val maxResults: Int,
        val updateIntervalMin: Int,
        val fuelType: String,
        val alongRouteMode: Boolean,
        val useRealDistance: Boolean,
        val isInitialized: Boolean
    )
}
