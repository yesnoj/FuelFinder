package com.fuelfinder.app.auto

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Service per Android Auto usando la nuova Car App Library
 * Questa implementazione permette all'app di essere lanciata dal launcher di Android Auto
 */
class FuelFinderCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        // Accetta tutti gli host per semplicità
        // In produzione, potresti voler limitare agli host Google
        return if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }
    }

    override fun onCreateSession(): Session {
        return FuelFinderCarSession()
    }
}

/**
 * Session per gestire l'interazione con Android Auto
 */
class FuelFinderCarSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        return FuelFinderCarScreen(carContext)
    }
}