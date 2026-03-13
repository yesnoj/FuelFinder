package com.fuelfinder.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class StationAdapter(
    private val onNavigate: (FuelStation) -> Unit,
    private val onItemClick: (FuelStation) -> Unit
) : RecyclerView.Adapter<StationAdapter.VH>() {

    private val items = mutableListOf<FuelStation>()

    fun updateStations(newItems: List<FuelStation>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_station, parent, false)
        return VH(v, onNavigate, onItemClick)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class VH(itemView: View, private val onNavigate: (FuelStation) -> Unit, private val onItemClick: (FuelStation) -> Unit) :
        RecyclerView.ViewHolder(itemView) {

        private val tvName: TextView = itemView.findViewById(R.id.tvStationName)
        private val tvAddr: TextView = itemView.findViewById(R.id.tvStationAddress)
        private val tvPrice: TextView = itemView.findViewById(R.id.tvStationPrice)
        private val tvDistance: TextView = itemView.findViewById(R.id.tvStationDistance)
        private val tvLastUpdate: TextView = itemView.findViewById(R.id.tvLastUpdate)
        private val btnNavigate: MaterialButton = itemView.findViewById(R.id.btnNavigate)
        private val ivLogo: ImageView? = itemView.findViewById(R.id.ivBrandLogo)

        private val df = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ITALY)

        fun bind(s: FuelStation) {
            tvName.text = s.name
            tvAddr.text = s.address

            // Price
            val price = s.prices.values.firstOrNull()
            tvPrice.text = if (price != null) String.format("€ %.3f", price) else "Prezzo n/d"

            // Distance
            tvDistance.text = when {
                s.routeDistanceKm != null -> {
                    val duration = s.routeDurationSec
                    if (duration != null) {
                        val minutes = duration / 60
                        String.format("🚗 %.1f km • %d min", s.routeDistanceKm, minutes)
                    } else {
                        String.format("🚗 %.1f km (strada)", s.routeDistanceKm)
                    }
                }
                s.airDistanceKm != null -> String.format("📍 %.1f km", s.airDistanceKm)
                else -> ""
            }

            // Last update
            tvLastUpdate.text = try {
                val parsed = df.parse(s.lastUpdate ?: "")
                if (parsed != null) {
                    val diffMs = System.currentTimeMillis() - parsed.time
                    val hours = TimeUnit.MILLISECONDS.toHours(diffMs)
                    if (hours < 24) "Aggiornato ${hours}h fa" else "Aggiornato ${hours / 24}gg fa"
                } else ""
            } catch (e: Exception) { "" }

            // Navigate button → Google Maps
            btnNavigate.setOnClickListener { onNavigate(s) }

            // Click sulla card → zoom mappa
            itemView.setOnClickListener { onItemClick(s) }

            // Brand logo
            if (ivLogo != null) {
                val bitmap = BrandLogoManager.getBitmapByBrand(s.brand, sizeDp = 40)
                if (bitmap != null) {
                    ivLogo.setImageBitmap(bitmap)
                    ivLogo.visibility = View.VISIBLE
                } else {
                    ivLogo.visibility = View.GONE
                }
            }
        }
    }
}
