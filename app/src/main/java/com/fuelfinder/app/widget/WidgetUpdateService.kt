package com.fuelfinder.app.widget

import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.*

/**
 * Service per aggiornare il widget periodicamente in background.
 * Viene avviato dal WidgetProvider e si auto-ferma dopo l'aggiornamento.
 */
class WidgetUpdateService : Service() {
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            updateWidget(appWidgetId)
        } else {
            updateAllWidgets()
        }
        
        return START_NOT_STICKY
    }
    
    private fun updateWidget(appWidgetId: Int) {
        serviceScope.launch {
            try {
                val updateIntent = Intent(applicationContext, FuelFinderWidgetProvider::class.java).apply {
                    action = FuelFinderWidgetProvider.ACTION_UPDATE_WIDGET
                    putExtra(FuelFinderWidgetProvider.EXTRA_WIDGET_ID, appWidgetId)
                }
                sendBroadcast(updateIntent)
            } finally {
                withContext(Dispatchers.Main) {
                    stopSelf()
                }
            }
        }
    }
    
    private fun updateAllWidgets() {
        serviceScope.launch {
            try {
                val manager = AppWidgetManager.getInstance(applicationContext)
                val widgetIds = manager.getAppWidgetIds(
                    ComponentName(applicationContext, FuelFinderWidgetProvider::class.java)
                )
                
                widgetIds.forEach { widgetId ->
                    val updateIntent = Intent(applicationContext, FuelFinderWidgetProvider::class.java).apply {
                        action = FuelFinderWidgetProvider.ACTION_UPDATE_WIDGET
                        putExtra(FuelFinderWidgetProvider.EXTRA_WIDGET_ID, widgetId)
                    }
                    sendBroadcast(updateIntent)
                    delay(100) // Piccolo delay tra gli aggiornamenti per non sovraccaricare
                }
            } finally {
                withContext(Dispatchers.Main) {
                    stopSelf()
                }
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
    
    companion object {
        fun startUpdate(context: Context, appWidgetId: Int? = null) {
            val intent = Intent(context, WidgetUpdateService::class.java)
            appWidgetId?.let {
                intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, it)
            }
            context.startService(intent)
        }
    }
}
