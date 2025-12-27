package com.fuelfinder.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

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

    class VH(itemView: View, private val onNavigate: (FuelStation) -> Unit) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvStationName)
        private val tvAddr: TextView = itemView.findViewById(R.id.tvStationAddress)
        private val tvPrice: TextView = itemView.findViewById(R.id.tvStationPrice)
        private val tvDistance: TextView = itemView.findViewById(R.id.tvStationDistance)
        private val btnNavigate: MaterialButton = itemView.findViewById(R.id.btnNavigate)

        fun bind(s: FuelStation) {
            tvName.text = s.name
            tvAddr.text = s.address

            // Format price
            val price = s.prices.values.firstOrNull()
            tvPrice.text = if (price != null) {
                String.format("€ %.3f", price)
            } else {
                "Prezzo n/d"
            }

            // Format distance - prioritize air distance as it's always available and updated
            val dist = when {
                s.airDistanceKm != null -> String.format("%.1f km", s.airDistanceKm)
                else -> "Calcolo..."
            }
            tvDistance.text = dist

            // Navigation button
            btnNavigate.setOnClickListener {
                onNavigate(s)
            }
        }
    }
}