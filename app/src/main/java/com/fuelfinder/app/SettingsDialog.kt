package com.fuelfinder.app

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt

class SettingsDialog(
    context: Context,
    private val initialLookAheadKm: Int,
    private val initialMaxResults: Int,
    private val initialFrequencyMin: Int,
    private val initialUseRealDistance: Boolean = false,  // Default false per non usare distanze reali
    private val onSettingsChanged: (lookAheadKm: Int, maxResults: Int, frequencyMin: Int, useRealDistance: Boolean) -> Unit
) : Dialog(context) {

    private var selectedLookAheadKm = initialLookAheadKm
    private var selectedMaxResults = initialMaxResults
    private var selectedFrequencyMin = initialFrequencyMin
    private var selectedUseRealDistance = initialUseRealDistance

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_settings)
        setTitle("Impostazioni")

        val seekLookAhead = findViewById<Slider>(R.id.seekRadius)
        val tvLookAheadValue = findViewById<TextView>(R.id.tvRadiusValue)

        val seekMaxResults = findViewById<Slider>(R.id.seekMaxResults)
        val tvMaxResultsValue = findViewById<TextView>(R.id.tvMaxResultsValue)

        val radioGroupFreq = findViewById<RadioGroup>(R.id.radioGroupFrequency)
        val swRealDistance = findViewById<SwitchCompat>(R.id.swRealDistance)
        val tvRealDistanceInfo = findViewById<TextView>(R.id.tvRealDistanceInfo)

        val btnCancel = findViewById<MaterialButton>(R.id.btnCancel)
        val btnOk = findViewById<MaterialButton>(R.id.btnOk)

        // Look-ahead slider: 5..50 step 5 (nuovo range)
        seekLookAhead.valueFrom = 5f
        seekLookAhead.valueTo = 50f
        seekLookAhead.stepSize = 5f

        val normalizedInitialLookAhead = (initialLookAheadKm / 5.0).roundToInt() * 5
        selectedLookAheadKm = normalizedInitialLookAhead.coerceIn(5, 50)
        seekLookAhead.value = selectedLookAheadKm.toFloat()
        tvLookAheadValue.text = "$selectedLookAheadKm km"

        seekLookAhead.addOnChangeListener { _, value, _ ->
            selectedLookAheadKm = value.toInt()
            tvLookAheadValue.text = "${value.toInt()} km"
        }

        // Max results slider: 1..10 step 1 (nuovo range)
        seekMaxResults.valueFrom = 1f
        seekMaxResults.valueTo = 10f
        seekMaxResults.stepSize = 1f

        selectedMaxResults = initialMaxResults.coerceIn(1, 10)
        seekMaxResults.value = selectedMaxResults.toFloat()
        tvMaxResultsValue.text = "$selectedMaxResults"

        seekMaxResults.addOnChangeListener { _, value, _ ->
            selectedMaxResults = value.toInt()
            tvMaxResultsValue.text = "${value.toInt()}"
        }

        // Frequency radio buttons
        when (initialFrequencyMin) {
            1 -> radioGroupFreq.check(R.id.radioFreq1)
            3 -> radioGroupFreq.check(R.id.radioFreq3)
            5 -> radioGroupFreq.check(R.id.radioFreq5)
            else -> radioGroupFreq.check(R.id.radioFreq1)
        }

        radioGroupFreq.setOnCheckedChangeListener { _, checkedId ->
            selectedFrequencyMin = when (checkedId) {
                R.id.radioFreq1 -> 1
                R.id.radioFreq3 -> 3
                R.id.radioFreq5 -> 5
                else -> 1
            }
        }

        // Real distance switch
        swRealDistance.isChecked = initialUseRealDistance
        selectedUseRealDistance = initialUseRealDistance

        swRealDistance.setOnCheckedChangeListener { _, isChecked ->
            selectedUseRealDistance = isChecked
            // Mostra/nascondi info in base allo stato
            tvRealDistanceInfo.text = if (isChecked) {
                "Usa l'API Google Maps per calcolare distanze stradali reali (consuma crediti API)"
            } else {
                "Usa distanza in linea d'aria (gratuito)"
            }
        }

        // Imposta il testo iniziale dell'info
        tvRealDistanceInfo.text = if (initialUseRealDistance) {
            "Usa l'API Google Maps per calcolare distanze stradali reali (consuma crediti API)"
        } else {
            "Usa distanza in linea d'aria (gratuito)"
        }

        btnCancel.setOnClickListener { dismiss() }
        btnOk.setOnClickListener {
            onSettingsChanged(selectedLookAheadKm, selectedMaxResults, selectedFrequencyMin, selectedUseRealDistance)
            dismiss()
        }

        setCanceledOnTouchOutside(false)
    }

    override fun onStart() {
        super.onStart()
        val dm = context.resources.displayMetrics
        val width = (dm.widthPixels * 0.92f).toInt()
        window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
    }
}