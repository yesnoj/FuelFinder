package com.fuelfinder.app

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt

class SettingsDialog(
    context: Context,
    private val initialLookAheadKm: Int,
    private val initialMaxResults: Int,
    private val initialLookAheadKm360: Int,
    private val initialMaxResults360: Int,
    private val initialFrequencyMin: Int,
    private val initialUseRealDistance: Boolean = false,
    private val initialAlongRouteMode: Boolean = false,
    private val initialFuelType: FuelType = FuelType.GASOLIO,
    private val onSettingsChanged: (
        lookAheadKm: Int,
        maxResults: Int,
        lookAheadKm360: Int,
        maxResults360: Int,
        frequencyMin: Int,
        useRealDistance: Boolean,
        alongRouteMode: Boolean,
        fuelType: FuelType
    ) -> Unit
) : Dialog(context) {

    private var selectedLookAheadKm = initialLookAheadKm
    private var selectedMaxResults = initialMaxResults
    private var selectedLookAheadKm360 = initialLookAheadKm360
    private var selectedMaxResults360 = initialMaxResults360
    private var selectedFrequencyMin = initialFrequencyMin
    private var selectedUseRealDistance = initialUseRealDistance
    private var selectedAlongRouteMode = initialAlongRouteMode
    private var selectedFuelType = initialFuelType

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_settings)

        // Titolo dinamico in base alla modalità corrente
        setTitle(if (selectedAlongRouteMode) "Impostazioni · Percorso" else "Impostazioni · 360°")

        val seekLookAhead    = findViewById<Slider>(R.id.seekRadius)
        val tvLookAheadValue = findViewById<TextView>(R.id.tvRadiusValue)
        val tvRadiusLabel    = findViewById<TextView>(R.id.tvRadiusLabel)
        val seekMaxResults   = findViewById<Slider>(R.id.seekMaxResults)
        val tvMaxResultsValue = findViewById<TextView>(R.id.tvMaxResultsValue)
        val radioGroupFreq   = findViewById<RadioGroup>(R.id.radioGroupFrequency)
        val swRealDistance   = findViewById<SwitchCompat>(R.id.swRealDistance)
        val tvRealDistanceInfo = findViewById<TextView>(R.id.tvRealDistanceInfo)
        val swAlongRoute     = findViewById<SwitchCompat>(R.id.swAlongRoute)
        val tvAlongRouteLabel = findViewById<TextView>(R.id.tvAlongRouteLabel)
        val tvAlongRouteInfo = findViewById<TextView>(R.id.tvAlongRouteInfo)
        val chipGroupFuel    = findViewById<ChipGroup>(R.id.chipGroupFuel)
        val btnCancel = findViewById<MaterialButton>(R.id.btnCancel)
        val btnOk     = findViewById<MaterialButton>(R.id.btnOk)

        // Imposta chip carburante iniziale
        val chipIdForFuel = when (initialFuelType) {
            FuelType.GASOLIO -> R.id.chipGasolio
            FuelType.BENZINA -> R.id.chipBenzina
            FuelType.GPL     -> R.id.chipGpl
            FuelType.METANO  -> R.id.chipMetano
        }
        chipGroupFuel.check(chipIdForFuel)
        chipGroupFuel.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedFuelType = when (checkedIds.firstOrNull()) {
                R.id.chipGasolio -> FuelType.GASOLIO
                R.id.chipBenzina -> FuelType.BENZINA
                R.id.chipGpl     -> FuelType.GPL
                R.id.chipMetano  -> FuelType.METANO
                else             -> FuelType.GASOLIO
            }
        }

        // Titolo e label raggio: cambiano dinamicamente con lo switch
        fun applyRouteMode(isRoute: Boolean) {
            tvRadiusLabel.text = if (isRoute) "Prossimi km di viaggio" else "Raggio di ricerca"
            if (isRoute) {
                seekLookAhead.valueFrom = 5f; seekLookAhead.valueTo = 50f; seekLookAhead.stepSize = 5f
                val v = ((selectedLookAheadKm / 5.0).roundToInt() * 5).coerceIn(5, 50)
                selectedLookAheadKm = v; seekLookAhead.value = v.toFloat(); tvLookAheadValue.text = "$v km"
                seekMaxResults.valueFrom = 1f; seekMaxResults.valueTo = 10f; seekMaxResults.stepSize = 1f
                val m = selectedMaxResults.coerceIn(1, 10)
                selectedMaxResults = m; seekMaxResults.value = m.toFloat(); tvMaxResultsValue.text = "$m"
                // Percorso: switch attivo
                swRealDistance.isEnabled = true
                swRealDistance.alpha = 1.0f
                tvRealDistanceInfo.text = if (selectedUseRealDistance)
                    "Usa Google Maps per distanze stradali reali (consuma crediti API)"
                else
                    "Usa distanza in linea d'aria (gratuito)"
            } else {
                seekLookAhead.valueFrom = 1f; seekLookAhead.valueTo = 10f; seekLookAhead.stepSize = 1f
                val v = selectedLookAheadKm360.coerceIn(1, 10)
                selectedLookAheadKm360 = v; seekLookAhead.value = v.toFloat(); tvLookAheadValue.text = "$v km"
                seekMaxResults.valueFrom = 5f; seekMaxResults.valueTo = 200f; seekMaxResults.stepSize = 5f
                val m = ((selectedMaxResults360 / 5.0).roundToInt() * 5).coerceIn(5, 200)
                selectedMaxResults360 = m; seekMaxResults.value = m.toFloat(); tvMaxResultsValue.text = "$m"
                // 360°: switch disabilitato con spiegazione
                swRealDistance.isEnabled = false
                swRealDistance.isChecked = false
                swRealDistance.alpha = 0.4f
                tvRealDistanceInfo.text = "In modalità 360° è disponibile solo la distanza in linea d'aria"
            }
        }

        // Applica stato iniziale
        applyRouteMode(selectedAlongRouteMode)
        swAlongRoute.isChecked = selectedAlongRouteMode
        tvAlongRouteLabel.text = if (selectedAlongRouteMode) "Modalità percorso" else "Modalità 360°"
        tvAlongRouteInfo.text = if (selectedAlongRouteMode)
            "Cerca distributori lungo il percorso di navigazione attivo"
        else
            "Cerca distributori in tutte le direzioni intorno alla posizione attuale (360°)"

        swAlongRoute.setOnCheckedChangeListener { _, isChecked ->
            selectedAlongRouteMode = isChecked
            applyRouteMode(isChecked)
            tvAlongRouteLabel.text = if (isChecked) "Modalità percorso" else "Modalità 360°"
            tvAlongRouteInfo.text = if (isChecked)
                "Cerca distributori lungo il percorso di navigazione attivo"
            else
                "Cerca distributori in tutte le direzioni intorno alla posizione attuale (360°)"
        }

        // Listener slider raggio
        seekLookAhead.addOnChangeListener { _, value, _ ->
            if (selectedAlongRouteMode) { selectedLookAheadKm = value.toInt() }
            else { selectedLookAheadKm360 = value.toInt() }
            tvLookAheadValue.text = "${value.toInt()} km"
        }

        // Listener slider max risultati
        seekMaxResults.addOnChangeListener { _, value, _ ->
            if (selectedAlongRouteMode) { selectedMaxResults = value.toInt() }
            else { selectedMaxResults360 = value.toInt() }
            tvMaxResultsValue.text = "${value.toInt()}"
        }

        // Distanza reale — stato gestito da applyRouteMode, qui solo il listener
        swRealDistance.isChecked = initialUseRealDistance
        selectedUseRealDistance = initialUseRealDistance
        swRealDistance.setOnCheckedChangeListener { _, isChecked ->
            selectedUseRealDistance = isChecked
            tvRealDistanceInfo.text = if (isChecked)
                "Usa Google Maps per distanze stradali reali (consuma crediti API)"
            else
                "Usa distanza in linea d'aria (gratuito)"
        }
        when (initialFrequencyMin) {
            1    -> radioGroupFreq.check(R.id.radioFreq1)
            3    -> radioGroupFreq.check(R.id.radioFreq3)
            5    -> radioGroupFreq.check(R.id.radioFreq5)
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

        btnCancel.setOnClickListener { dismiss() }
        btnOk.setOnClickListener {
            onSettingsChanged(
                selectedLookAheadKm,
                selectedMaxResults,
                selectedLookAheadKm360,
                selectedMaxResults360,
                selectedFrequencyMin,
                selectedUseRealDistance,
                selectedAlongRouteMode,
                selectedFuelType
            )
            dismiss()
        }

        setCanceledOnTouchOutside(false)
    }

    override fun onStart() {
        super.onStart()
        val dm = context.resources.displayMetrics
        val width = (dm.widthPixels * 0.92f).toInt()
        window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
    }
}