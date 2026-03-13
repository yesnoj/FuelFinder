package com.fuelfinder.app

import android.content.Context
import android.graphics.*
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object BrandLogoManager {

    private var logos: JSONObject? = null
    @Volatile var isReady = false

    // Dimensione logo grezzo dentro il pin (dp)
    private const val LOGO_SIZE_DP    = 44
    // Dimensione logo per lista RecyclerView / Android Auto
    private const val LIST_LOGO_SIZE_DP = 40

    // Pin shape: larghezza=56dp, altezza box=56dp, punta=14dp → totale 70dp
    private const val PIN_W_DP        = 56
    private const val PIN_BOX_DP      = 56
    private const val PIN_TIP_DP      = 14
    private const val PIN_CORNER_DP   = 10
    private const val PIN_PADDING_DP  = 6
    private const val PIN_BORDER_DP   = 2f
    // Striscia prezzo in cima al pin
    private const val PRICE_STRIP_DP  = 15

    @Volatile private var screenDensity: Float = 1f

    // Cache bitmap grezzi (logo senza pin)  chiave = id*1000+sizeDp
    private val bitmapCacheById   = LruCache<Int, Bitmap>(400)
    private val bitmapCacheByName = LruCache<String, Bitmap>(400)
    // Cache pin completi  chiave = id o "name@size"
    private val pinCacheById      = LruCache<Int, Bitmap>(200)
    private val pinCacheByName    = LruCache<String, Bitmap>(200)
    // Cache pin con prezzo  chiave = "id_price" o "brand_price"
    private val pinWithPriceCache = LruCache<String, BitmapDescriptor>(300)

    private val nameToId = mutableMapOf<String, Int>()

    // ── Init ─────────────────────────────────────────────────────────────────

    suspend fun init(context: Context) = withContext(Dispatchers.IO) {
        if (logos != null) return@withContext
        try {
            screenDensity = context.resources.displayMetrics.density

            val json = context.assets.open("brand_logos.json").bufferedReader().use { it.readText() }
            logos = JSONObject(json)

            val keys = logos!!.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val id = key.toIntOrNull() ?: continue
                val name = logos!!.optJSONObject(key)?.optString("name") ?: continue
                nameToId[normalize(name)] = id
            }

            preloadBitmaps(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11))

            isReady = true
            android.util.Log.d("BrandLogo", "BrandLogoManager.init() OK — ${nameToId.size} brand indicizzati")

        } catch (e: Exception) {
            android.util.Log.e("BrandLogo", "Failed to load brand_logos.json", e)
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Pin-shaped marker descriptor per bandieraId — solo main thread */
    fun getDescriptor(bandieraId: Int?): BitmapDescriptor? {
        if (bandieraId == null || bandieraId == 0) return null
        val pin = getPinById(bandieraId) ?: return null
        return BitmapDescriptorFactory.fromBitmap(pin)
    }

    /** Pin-shaped marker descriptor per nome brand — solo main thread */
    fun getDescriptorByName(brandName: String?): BitmapDescriptor? {
        if (brandName.isNullOrBlank()) return null
        val pin = getPinByName(brandName) ?: return null
        return BitmapDescriptorFactory.fromBitmap(pin)
    }

    /** Pin con striscia prezzo in cima — usa cache separata per evitare di sporcare la cache per id */
    fun getDescriptorWithPrice(bandieraId: Int?, brandName: String?, priceText: String?): BitmapDescriptor? {
        val cacheKey = "${bandieraId ?: brandName}_${priceText}"
        pinWithPriceCache.get(cacheKey)?.let { return it }

        val logo: Bitmap = ((if (bandieraId != null && bandieraId > 0) getBitmapById(bandieraId, LOGO_SIZE_DP) else null)
            ?: if (!brandName.isNullOrBlank()) getBitmapByName(brandName, LOGO_SIZE_DP) else null)
            ?: return null

        val pin = buildPin(logo, priceText)
        val descriptor = BitmapDescriptorFactory.fromBitmap(pin)
        pinWithPriceCache.put(cacheKey, descriptor)
        return descriptor
    }

    /** Bitmap grezzo (senza pin) per lista RecyclerView / Android Auto */
    fun getBitmapByBrand(brandName: String?, sizeDp: Int = LIST_LOGO_SIZE_DP): Bitmap? {
        if (brandName.isNullOrBlank()) return null
        return getBitmapByName(brandName, sizeDp)
    }

    fun getBrandName(bandieraId: Int?): String? {
        if (bandieraId == null || bandieraId == 0) return null
        return logos?.optJSONObject(bandieraId.toString())?.optString("name")
    }

    // ── Pin cache ─────────────────────────────────────────────────────────────

    private fun getPinById(id: Int): Bitmap? {
        pinCacheById.get(id)?.let { return it }
        val logo = getBitmapById(id, LOGO_SIZE_DP) ?: return null
        val pin  = buildPin(logo)
        pinCacheById.put(id, pin)
        return pin
    }

    private fun getPinByName(brandName: String): Bitmap? {
        val key = normalize(brandName)
        pinCacheByName.get(key)?.let { return it }
        val logo = getBitmapByName(brandName, LOGO_SIZE_DP) ?: return null
        val pin  = buildPin(logo)
        pinCacheByName.put(key, pin)
        return pin
    }

    // ── Pin drawing ───────────────────────────────────────────────────────────

    /**
     * Disegna una cornice pin:
     *  ┌──────────┐
     *  │  [logo]  │  ← box arrotondato con bordo
     *  └────┬─────┘
     *       ▼       ← punta triangolare centrata
     */
    private fun buildPin(logo: Bitmap, priceText: String? = null): Bitmap {
        val d = screenDensity
        val w     = (PIN_W_DP   * d).toInt()
        val boxH  = (PIN_BOX_DP * d).toInt()
        val tipH  = (PIN_TIP_DP * d).toInt()
        val totalH = boxH + tipH
        val corner = PIN_CORNER_DP * d
        val pad   = (PIN_PADDING_DP * d).toInt()
        val border = PIN_BORDER_DP * d
        val stripH = if (priceText != null) (PRICE_STRIP_DP * d).toInt() else 0

        val bmp = Bitmap.createBitmap(w, totalH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // 1. Ombra leggera
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(40, 0, 0, 0)
            maskFilter = BlurMaskFilter(4 * d, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRoundRect(
            RectF(border + 2*d, border + 2*d, w - border - 2*d, boxH.toFloat() - border + 2*d),
            corner, corner, shadowPaint
        )

        // 2. Box bianco riempimento
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val boxRect = RectF(border, border, w - border, boxH - border.toFloat())
        canvas.drawRoundRect(boxRect, corner, corner, fillPaint)

        // 3. Striscia prezzo in cima (angoli superiori arrotondati, stessa curva del box)
        if (priceText != null && stripH > 0) {
            val stripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#2E7D32")
                style = Paint.Style.FILL
            }
            canvas.save()
            canvas.clipRect(border, border, w - border, border + stripH)
            canvas.drawRoundRect(boxRect, corner, corner, stripPaint)
            canvas.restore()

            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = PRICE_STRIP_DP * 0.62f * d
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            val textY = border + stripH / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(priceText, w / 2f, textY, textPaint)
        }

        // 4. Punta triangolare bianca
        val tipPath = Path().apply {
            val tipLeft  = w / 2f - tipH * 0.7f
            val tipRight = w / 2f + tipH * 0.7f
            val tipTop   = boxH - border
            moveTo(tipLeft,  tipTop)
            lineTo(tipRight, tipTop)
            lineTo(w / 2f,   totalH.toFloat())
            close()
        }
        canvas.drawPath(tipPath, fillPaint)

        // 5. Bordo grigio scuro attorno al box + punta
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 60, 60, 60)
            style = Paint.Style.STROKE
            strokeWidth = border
        }
        canvas.drawRoundRect(boxRect, corner, corner, strokePaint)
        canvas.drawPath(tipPath, strokePaint)

        // 6. Logo centrato nella zona sotto la striscia prezzo
        val logoAreaTop = border + stripH
        val logoAreaH   = (boxH - border - logoAreaTop).toInt()
        val logoSize    = (logoAreaH * 0.82f).toInt().coerceAtLeast(1)
        val scaledLogo  = Bitmap.createScaledBitmap(logo, logoSize, logoSize, true)
        val logoX = (w - logoSize) / 2f
        val logoY = logoAreaTop + (logoAreaH - logoSize) / 2f
        canvas.drawBitmap(scaledLogo, logoX, logoY, null)

        return bmp
    }

    // ── Bitmap cache ──────────────────────────────────────────────────────────

    private fun getBitmapById(id: Int, sizeDp: Int): Bitmap? {
        val cacheKey = id * 1000 + sizeDp
        bitmapCacheById.get(cacheKey)?.let { return it }
        return decodeBitmapById(id, sizeDp)
    }

    private fun getBitmapByName(brandName: String, sizeDp: Int): Bitmap? {
        if (logos == null) {
            android.util.Log.w("BrandLogo", "getBitmapByName('$brandName'): logos ancora null")
            return null
        }
        val cacheKey = normalize(brandName) + "@$sizeDp"
        bitmapCacheByName.get(cacheKey)?.let { return it }

        val normalKey = normalize(brandName)
        val id = nameToId[normalKey]
            ?: nameToId.entries.firstOrNull { (n, _) ->
                normalKey.contains(n) || n.contains(normalKey)
            }?.value
            ?: run {
                android.util.Log.w("BrandLogo", "nessun match per '$normalKey'")
                return null
            }

        val bitmap = decodeBitmapById(id, sizeDp) ?: return null
        bitmapCacheByName.put(cacheKey, bitmap)
        return bitmap
    }

    private fun decodeBitmapById(id: Int, sizeDp: Int): Bitmap? {
        return try {
            val entry = logos?.optJSONObject(id.toString()) ?: return null
            val b64 = entry.getString("png")
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val targetPx = (sizeDp * screenDensity).toInt().coerceAtLeast(32)
            val scaled = Bitmap.createScaledBitmap(raw, targetPx, targetPx, true)
            bitmapCacheById.put(id * 1000 + sizeDp, scaled)
            scaled
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun preloadBitmaps(ids: List<Int>) = withContext(Dispatchers.IO) {
        ids.forEach { id ->
            if (bitmapCacheById.get(id * 1000 + LOGO_SIZE_DP) == null) {
                decodeBitmapById(id, LOGO_SIZE_DP)
            }
        }
    }

    private fun normalize(s: String): String =
        s.trim().lowercase()
            .replace("-", " ")
            .replace("_", " ")
            .replace(".", " ")
            .replace(Regex("\\s+"), " ")
}
