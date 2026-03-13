package com.fuelfinder.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.clustering.Cluster
import com.google.maps.android.clustering.ClusterItem
import com.google.maps.android.clustering.ClusterManager
import com.google.maps.android.clustering.view.DefaultClusterRenderer

// ─────────────────────────────────────────
// ClusterItem wrapper for FuelStation
// ─────────────────────────────────────────

class FuelStationClusterItem(val station: FuelStation) : ClusterItem {

    private val latLng = LatLng(station.latitude, station.longitude)

    override fun getPosition(): LatLng = latLng
    override fun getTitle(): String = station.name
    override fun getSnippet(): String {
        val price = station.prices.values.firstOrNull()
        return if (price != null) String.format("%.3f €/L", price) else ""
    }
    override fun getZIndex(): Float = 0f
}

// ─────────────────────────────────────────
// Custom renderer: brand logo for singles,
// numbered teal bubble for clusters
// ─────────────────────────────────────────

class FuelStationClusterRenderer(
    context: Context,
    map: GoogleMap,
    clusterManager: ClusterManager<FuelStationClusterItem>
) : DefaultClusterRenderer<FuelStationClusterItem>(context, map, clusterManager) {

    private val density = context.resources.displayMetrics.density

    init {
        android.util.Log.d("BrandLogo", "FuelStationClusterRenderer CREATED")
    }

    // In maps-utils 3.x onBeforeClusterItemRendered è spesso ignorato.
    // onClusterItemRendered è chiamato DOPO che il marker è stato aggiunto alla mappa
    // ed è l'unico posto affidabile per cambiare icona + tag.
    override fun onClusterItemRendered(
        item: FuelStationClusterItem,
        marker: com.google.android.gms.maps.model.Marker
    ) {
        val st = item.station
        android.util.Log.d("BrandLogo", "onClusterItemRendered: name=${st.name} brand=${st.brand} bandieraId=${st.bandieraId} isReady=${BrandLogoManager.isReady}")

        // Tag per InfoWindowAdapter
        marker.tag = st

        if (!BrandLogoManager.isReady) {
            // Logo non ancora disponibili — rimane il marker default,
            // clusterManager.cluster() verrà chiamato da MainActivity quando init() finisce
            marker.setIcon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
            return
        }

        val price = st.prices.values.firstOrNull()
        val priceText = if (price != null) String.format("%.3f €", price) else null

        val descriptor = BrandLogoManager.getDescriptorWithPrice(st.bandieraId, st.brand, priceText)
            ?: BrandLogoManager.getDescriptorWithPrice(null, st.brand, priceText)

        android.util.Log.d("BrandLogo", "  → descriptor=${descriptor != null}")

        marker.setIcon(descriptor ?: BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
    }

    // Mantenuto per compatibilità ma non è il metodo principale in 3.x
    override fun onBeforeClusterItemRendered(
        item: FuelStationClusterItem,
        markerOptions: com.google.android.gms.maps.model.MarkerOptions
    ) {
        android.util.Log.d("BrandLogo", "onBeforeClusterItemRendered chiamato (3.x)")
    }

    override fun onClusterRendered(
        cluster: Cluster<FuelStationClusterItem>,
        marker: com.google.android.gms.maps.model.Marker
    ) {
        marker.setIcon(makeClusterIcon(cluster.size))
    }

    override fun onBeforeClusterRendered(
        cluster: Cluster<FuelStationClusterItem>,
        markerOptions: com.google.android.gms.maps.model.MarkerOptions
    ) {
        markerOptions.icon(makeClusterIcon(cluster.size))
    }

    override fun shouldRenderAsCluster(cluster: Cluster<FuelStationClusterItem>): Boolean {
        return cluster.size >= 3
    }

    // ── Draws a filled circle with a count label ──────────────────────────────

    private val clusterIconCache = mutableMapOf<Int, BitmapDescriptor>()

    private fun makeClusterIcon(count: Int): BitmapDescriptor {
        // Round count to cache bucket (1,2,3…9,10,20,50,100+)
        val bucket = when {
            count < 10 -> count
            count < 20 -> 10
            count < 50 -> 20
            count < 100 -> 50
            else -> 100
        }
        clusterIconCache[bucket]?.let { return it }

        val sizeDp = when {
            count < 10 -> 40
            count < 50 -> 48
            else -> 56
        }
        val sizePx = (sizeDp * density).toInt()
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Outer shadow ring
        val paintShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(60, 0, 0, 0)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, paintShadow)

        // Inner filled circle (teal matching app theme)
        val paintCircle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00897B")  // teal_700
            style = Paint.Style.FILL
        }
        val radius = sizePx / 2f - (2 * density)
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, radius, paintCircle)

        // Count text
        val textSize = when {
            count < 10 -> 14f
            count < 100 -> 13f
            else -> 11f
        }
        val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize * density
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val label = if (count >= 100) "99+" else count.toString()
        val textY = sizePx / 2f - (paintText.descent() + paintText.ascent()) / 2f
        canvas.drawText(label, sizePx / 2f, textY, paintText)

        val descriptor = BitmapDescriptorFactory.fromBitmap(bitmap)
        clusterIconCache[bucket] = descriptor
        return descriptor
    }
}
