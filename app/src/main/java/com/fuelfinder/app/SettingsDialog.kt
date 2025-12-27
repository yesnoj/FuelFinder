package com.fuelfinder.app

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import com.google.android.material.slider.Slider

/**
 * Settings:
 * - fuel type
 * - search radius (km) used to LIMIT which stations we fetch (the displayed distance is route-based via Directions API)
 * - update frequency (seconds)
 */
class SettingsDialog(
    context: Context,
    private val initialFuelType: FuelType,
    private val initialRadiusKm: Int,
    private val initialFrequencySec: Int,
    private val onSettingsChanged: (fuelType: FuelType, radiusKm: Int, frequencySec: Int) -> Unit
) : Dialog(context) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_settings)
        setTitle("Impostazioni")

        val spinnerFuel = findViewById<Spinner>(R.id.spinnerFuelType)
        val seekRadius = findViewById<Slider>(R.id.seekRadius)
        val seekFreq = findViewById<Slider>(R.id.seekFrequency)
        val tvRadiusValue = findViewById<TextView>(R.id.tvRadiusValue)
        val tvFreqValue = findViewById<TextView>(R.id.tvFrequencyValue)

        val fuelItems = listOf(
            FuelType.DIESEL to "Diesel",
            FuelType.BENZINA to "Benzina",
            FuelType.GPL to "GPL",
            FuelType.METANO to "Metano"
        )

        spinnerFuel.adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            fuelItems.map { it.second }
        )

        // set initial selection
        val initIndex = fuelItems.indexOfFirst { it.first == initialFuelType }.coerceAtLeast(0)
        spinnerFuel.setSelection(initIndex)

        // Configure sliders in code to avoid aapt attribute issues on some setups
        seekRadius.valueFrom = 1f
        seekRadius.valueTo = 50f
        seekRadius.stepSize = 1f
        seekRadius.value = initialRadiusKm.toFloat()
        tvRadiusValue.text = "${initialRadiusKm} km"
        seekRadius.addOnChangeListener { _, value, _ ->
            tvRadiusValue.text = "${value.toInt()} km"
        }

        seekFreq.valueFrom = 10f
        seekFreq.valueTo = 120f
        seekFreq.stepSize = 5f
        seekFreq.value = initialFrequencySec.toFloat()
        tvFreqValue.text = "${initialFrequencySec} sec"
        seekFreq.addOnChangeListener { _, value, _ ->
            tvFreqValue.text = "${value.toInt()} sec"
        }

        // Click outside to dismiss is ok, but we need explicit "OK".
        // Easiest: use dialog buttons via window decor? We'll just close on back, and save on dismiss? No.
        // So: when dialog is dismissed, apply settings (simple behavior for this demo).
        setOnDismissListener {
            val selected = fuelItems[spinnerFuel.selectedItemPosition].first
            val radius = seekRadius.value.toInt()
            val freq = seekFreq.value.toInt()
            onSettingsChanged(selected, radius, freq)
        }
    }
}
