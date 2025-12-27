package com.fuelfinder.app

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import android.widget.RadioGroup
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt

class SettingsDialog(
    context: Context,
    private val initialRadiusKm: Int,
    private val initialMaxResults: Int,
    private val initialFrequencyMin: Int,
    private val onSettingsChanged: (radiusKm: Int, maxResults: Int, frequencyMin: Int) -> Unit
) : Dialog(context) {

    private var selectedRadiusKm = initialRadiusKm
    private var selectedMaxResults = initialMaxResults
    private var selectedFrequencyMin = initialFrequencyMin

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_settings)
        setTitle("Impostazioni")

        val seekRadius = findViewById<Slider>(R.id.seekRadius)
        val tvRadiusValue = findViewById<TextView>(R.id.tvRadiusValue)
        val seekMaxResults = findViewById<Slider>(R.id.seekMaxResults)
        val tvMaxResultsValue = findViewById<TextView>(R.id.tvMaxResultsValue)
        val radioGroupFreq = findViewById<RadioGroup>(R.id.radioGroupFrequency)
        val btnCancel = findViewById<MaterialButton>(R.id.btnCancel)
        val btnOk = findViewById<MaterialButton>(R.id.btnOk)

        // Radius slider
        seekRadius.valueFrom = 5f
        seekRadius.valueTo = 50f
        seekRadius.stepSize = 5f
        seekRadius.value = initialRadiusKm.toFloat()
        tvRadiusValue.text = "$initialRadiusKm km"

        seekRadius.addOnChangeListener { _, value, _ ->
            selectedRadiusKm = value.toInt()
            tvRadiusValue.text = "${value.toInt()} km"
        }

        // Max results slider: 5..50 step 5
        seekMaxResults.valueFrom = 5f
        seekMaxResults.valueTo = 50f
        seekMaxResults.stepSize = 5f

        val normalizedInitialMax = (initialMaxResults / 5.0).roundToInt() * 5
        selectedMaxResults = normalizedInitialMax.coerceIn(5, 50)
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

        btnCancel.setOnClickListener { dismiss() }

        btnOk.setOnClickListener {
            onSettingsChanged(selectedRadiusKm, selectedMaxResults, selectedFrequencyMin)
            dismiss()
        }

        setCanceledOnTouchOutside(false)
    }

    override fun onStart() {
        super.onStart()

        // Dialog più largo (ottimo su Pixel 8a)
        val dm = context.resources.displayMetrics
        val width = (dm.widthPixels * 0.92f).toInt()

        window?.setLayout(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }
}
