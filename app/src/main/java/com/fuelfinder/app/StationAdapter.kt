package com.fuelfinder.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class StationAdapter(
    private val onNavigate: (FuelStation) -> Unit
) : RecyclerView.Adapter<StationAdapter.VH>() {

    private val items = mutableListOf<FuelStation>()

    fun updateStations(newItems: List<FuelStation>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_station, parent, false)
        return VH(v, onNavigate)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class VH(itemView: View, private val onNavigate: (FuelStation) -> Unit) :
        RecyclerView.ViewHolder(itemView) {

        private val tvName: TextView = itemView.findViewById(R.id.tvStationName)
        private val tvAddr: TextView = itemView.findViewById(R.id.tvStationAddress)
        private val tvPrice: TextView = itemView.findViewById(R.id.tvStationPrice)
        private val tvDistance: TextView = itemView.findViewById(R.id.tvStationDistance)
        private val tvLastUpdate: TextView = itemView.findViewById(R.id.tvLastUpdate)
        private val btnNavigate: MaterialButton = itemView.findViewById(R.id.btnNavigate)

        private val df = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ITALY)

        fun bind(s: FuelStation) {
            tvName.text = s.name
            tvAddr.text = s.address

            // Price text
            val price = s.prices.values.firstOrNull()
            tvPrice.text = if (price != null) {
                String.format("€ %.3f", price)
            } else {
                "Prezzo n/d"
            }

            // Distance text - mostra distanza reale se disponibile
            tvDistance.text = when {
                s.routeDistanceKm != null -> {
                    // Mostra anche il tempo di percorrenza se disponibile
                    val duration = s.routeDurationSec
                    if (duration != null) {
                        val minutes = duration / 60
                        String.format("🚗 %.1f km • %d min", s.routeDistanceKm, minutes)
                    } else {
                        String.format("🚗 %.1f km (strada)", s.routeDistanceKm)
                    }
                }
                s.airDistanceKm != null -> String.format("📍 %.1f km (linea d'aria)", s.airDistanceKm)
                else -> "Calcolo..."
            }

            // Last update label (relative time)
            tvLastUpdate.text = formatLastUpdateRelative(s.lastUpdate)

            // Price color based on update age
            val ctx = itemView.context
            val colorPrimary = ContextCompat.getColor(ctx, R.color.primary)
            val colorWarn = ContextCompat.getColor(ctx, R.color.accent)
            val colorOld = ContextCompat.getColor(ctx, R.color.red)

            tvPrice.setTextColor(
                when (val ageMin = computeAgeMinutes(s.lastUpdate)) {
                    null -> colorPrimary // se non sappiamo la data, non allarmiamo
                    in 0..(6 * 60) -> colorPrimary
                    in (6 * 60 + 1)..(24 * 60) -> colorWarn
                    else -> colorOld
                }
            )

            // Colora anche la distanza per evidenziare quando è stradale
            if (s.routeDistanceKm != null) {
                tvDistance.setTextColor(ContextCompat.getColor(ctx, R.color.green))
            } else {
                tvDistance.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            }

            btnNavigate.setOnClickListener { onNavigate(s) }
        }

        private fun computeAgeMinutes(lastUpdate: String?): Long? {
            if (lastUpdate.isNullOrBlank()) return null
            return try {
                val t = df.parse(lastUpdate)?.time ?: return null
                val diffMs = System.currentTimeMillis() - t
                if (diffMs < 0) return null // clock/format inconsistente
                TimeUnit.MILLISECONDS.toMinutes(diffMs)
            } catch (_: Exception) {
                null
            }
        }

        private fun formatLastUpdateRelative(lastUpdate: String?): String {
            if (lastUpdate.isNullOrBlank()) return "Aggiornamento n/d"

            val ageMin = computeAgeMinutes(lastUpdate)
                ?: return "Aggiornato: $lastUpdate"

            return "Aggiornato: ${humanizeAge(ageMin)}"
        }

        private fun humanizeAge(ageMin: Long): String {
            // 0..59 min
            if (ageMin < 1) return "pochi secondi fa"
            if (ageMin < 2) return "1 minuto fa"
            if (ageMin < 60) return "${ageMin} minuti fa"

            val ageHours = ageMin / 60
            if (ageHours < 2) return "1 ora fa"
            if (ageHours < 24) return "${ageHours} ore fa"

            val ageDays = ageHours / 24
            if (ageDays < 2) return "1 gg fa"
            if (ageDays < 7) return "${ageDays} gg fa"

            val ageWeeks = ageDays / 7
            if (ageWeeks < 2) return "1 sett fa"
            if (ageWeeks < 5) return "${ageWeeks} sett fa"

            val ageMonths = ageDays / 30
            if (ageMonths < 2) return "1 mese fa"
            if (ageMonths < 12) return "${ageMonths} mesi fa"

            val ageYears = ageDays / 365
            return if (ageYears < 2) "1 anno fa" else "${ageYears} anni fa"
        }
    }
}