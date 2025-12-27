package com.fuelfinder.app

import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

/**
 * API Client per gestire le chiamate al backend
 */
object ApiClient {
    
    private const val BASE_URL = BuildConfig.BASE_URL
    
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.BODY
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
    }
    
    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .addInterceptor { chain ->
            val original = chain.request()
            val request = original.newBuilder()
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .addHeader("X-API-Key", BuildConfig.PREZZI_BENZINA_API_KEY)
                .method(original.method, original.body)
                .build()
            chain.proceed(request)
        }
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create(GsonBuilder().create()))
        .build()
    
    val fuelService: FuelService = retrofit.create(FuelService::class.java)
    val directionsService: DirectionsService = retrofit.create(DirectionsService::class.java)
}

/**
 * Service per le API dei benzinai
 */
interface FuelService {
    
    @GET("stations/nearby")
    fun getNearbyStations(
        @Query("lat") latitude: Double,
        @Query("lon") longitude: Double,
        @Query("radius") radius: Int,
        @Query("fuel_type") fuelType: String,
        @Query("limit") limit: Int = 20
    ): Call<StationResponse>
    
    @GET("stations/{id}")
    fun getStationDetails(
        @Path("id") stationId: String
    ): Call<FuelStation>
    
    @POST("stations/search")
    fun searchStations(
        @Body request: SearchRequest
    ): Call<StationResponse>
}

/**
 * Service per Google Directions API
 */
interface DirectionsService {
    
    @GET("https://maps.googleapis.com/maps/api/directions/json")
    fun getDirections(
        @Query("origin") origin: String,
        @Query("destination") destination: String,
        @Query("mode") mode: String = "driving",
        @Query("key") apiKey: String = BuildConfig.GOOGLE_MAPS_API_KEY,
        @Query("language") language: String = "it"
    ): Call<DirectionsResponse>
    
    @GET("https://maps.googleapis.com/maps/api/distancematrix/json")
    fun getDistanceMatrix(
        @Query("origins") origins: String,
        @Query("destinations") destinations: String,
        @Query("mode") mode: String = "driving",
        @Query("key") apiKey: String = BuildConfig.GOOGLE_MAPS_API_KEY,
        @Query("language") language: String = "it"
    ): Call<DistanceMatrixResponse>
}

/**
 * Request/Response models
 */
data class SearchRequest(
    val latitude: Double,
    val longitude: Double,
    val radius: Int,
    val fuelType: String,
    val maxResults: Int = 20,
    val sortBy: String = "price" // "price", "distance"
)

data class DirectionsResponse(
    val routes: List<Route>,
    val status: String
) {
    data class Route(
        val legs: List<Leg>,
        val overview_polyline: Polyline
    )
    
    data class Leg(
        val distance: Distance,
        val duration: Duration,
        val start_location: LatLng,
        val end_location: LatLng,
        val steps: List<Step>
    )
    
    data class Distance(
        val text: String,
        val value: Int // meters
    )
    
    data class Duration(
        val text: String,
        val value: Int // seconds
    )
    
    data class LatLng(
        val lat: Double,
        val lng: Double
    )
    
    data class Step(
        val distance: Distance,
        val duration: Duration,
        val polyline: Polyline
    )
    
    data class Polyline(
        val points: String
    )
}

data class DistanceMatrixResponse(
    val rows: List<Row>,
    val status: String
) {
    data class Row(
        val elements: List<Element>
    )
    
    data class Element(
        val distance: Distance?,
        val duration: Duration?,
        val status: String
    )
    
    data class Distance(
        val text: String,
        val value: Int // meters
    )
    
    data class Duration(
        val text: String,
        val value: Int // seconds
    )
}

/**
 * Utility per calcolare distanze reali usando Google Directions
 */
class RealDistanceCalculator {
    
    /**
     * Calcola la distanza stradale reale tra due punti
     */
    suspend fun getRealDistance(
        originLat: Double,
        originLon: Double,
        destLat: Double,
        destLon: Double
    ): RealDistanceResult? {
        return try {
            val origin = "$originLat,$originLon"
            val destination = "$destLat,$destLon"
            
            val response = ApiClient.directionsService.getDirections(
                origin = origin,
                destination = destination
            ).execute()
            
            if (response.isSuccessful && response.body()?.routes?.isNotEmpty() == true) {
                val route = response.body()!!.routes[0]
                val leg = route.legs[0]
                
                RealDistanceResult(
                    distanceKm = leg.distance.value / 1000.0,
                    distanceText = leg.distance.text,
                    durationMinutes = leg.duration.value / 60,
                    durationText = leg.duration.text,
                    polyline = route.overview_polyline.points
                )
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * Calcola distanze multiple in batch (più efficiente)
     */
    suspend fun getBatchDistances(
        origin: Pair<Double, Double>,
        destinations: List<Pair<Double, Double>>
    ): List<RealDistanceResult?> {
        return try {
            val originStr = "${origin.first},${origin.second}"
            val destinationsStr = destinations.joinToString("|") { 
                "${it.first},${it.second}" 
            }
            
            val response = ApiClient.directionsService.getDistanceMatrix(
                origins = originStr,
                destinations = destinationsStr
            ).execute()
            
            if (response.isSuccessful && response.body()?.rows?.isNotEmpty() == true) {
                response.body()!!.rows[0].elements.map { element ->
                    if (element.status == "OK" && element.distance != null && element.duration != null) {
                        RealDistanceResult(
                            distanceKm = element.distance.value / 1000.0,
                            distanceText = element.distance.text,
                            durationMinutes = element.duration.value / 60,
                            durationText = element.duration.text,
                            polyline = null // Not available in distance matrix
                        )
                    } else {
                        null
                    }
                }
            } else {
                destinations.map { null }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            destinations.map { null }
        }
    }
    
    data class RealDistanceResult(
        val distanceKm: Double,
        val distanceText: String,
        val durationMinutes: Int,
        val durationText: String,
        val polyline: String?
    )
}
