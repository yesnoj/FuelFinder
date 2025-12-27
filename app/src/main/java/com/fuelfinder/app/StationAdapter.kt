package com.fuelfinder.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class StationAdapter(
    private val onClick: (FuelStation) -> Unit
) : RecyclerView.Adapter<StationAdapter.VH>() {

    private val items = mutableListOf<FuelStation>()

    fun updateStations(newItems: List<FuelStation>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_station, parent, false)
        return VH(v, onClick)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class VH(itemView: View, private val onClick: (FuelStation) -> Unit) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvStationName)
        private val tvAddr: TextView = itemView.findViewById(R.id.tvStationAddress)
        private val tvPrice: TextView = itemView.findViewById(R.id.tvStationPrice)
        private val tvDistance: TextView = itemView.findViewById(R.id.tvStationDistance)

        fun bind(s: FuelStation) {
            tvName.text = s.name
            tvAddr.text = s.address
            val p = s.prices.values.firstOrNull()
            tvPrice.text = if (p != null) String.format("€ %.3f / L", p) else "Prezzo n/d"
            val dist = when {
                s.routeDistanceKm != null -> String.format("%.1f km", s.routeDistanceKm)
                s.airDistanceKm != null -> String.format("~%.1f km", s.airDistanceKm)
                else -> "--"
            }
            tvDistance.text = dist
            itemView.setOnClickListener { onClick(s) }
        }
    }
}
