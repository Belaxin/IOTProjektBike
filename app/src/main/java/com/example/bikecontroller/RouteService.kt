package com.example.bikecontroller

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Path

interface RouteService {
    // OSRM expects: lon,lat;lon,lat
    @GET("route/v1/bicycle/{coords}?overview=full&geometries=geojson&steps=true")
    suspend fun getRoute(
        @Path("coords") coords: String
    ): OSRMResponse
}

data class OSRMResponse(
    @SerializedName("routes") val routes: List<OSRMRoute>
)

data class OSRMRoute(
    @SerializedName("geometry") val geometry: Geometry,
    @SerializedName("distance") val distance: Double,
    @SerializedName("duration") val duration: Double,
    @SerializedName("legs") val legs: List<OSRMLeg>
)

data class OSRMLeg(
    @SerializedName("steps") val steps: List<OSRMStep>,
    @SerializedName("distance") val distance: Double,
    @SerializedName("duration") val duration: Double
)

data class OSRMStep(
    @SerializedName("geometry") val geometry: Geometry,
    @SerializedName("distance") val distance: Double,
    @SerializedName("duration") val duration: Double,
    @SerializedName("name") val name: String?,
    @SerializedName("maneuver") val maneuver: OSRMManeuver?
)

data class OSRMManeuver(
    @SerializedName("type") val type: String,
    @SerializedName("modifier") val modifier: String?,
    @SerializedName("bearing_after") val bearingAfter: Float?,
    @SerializedName("bearing_before") val bearingBefore: Float?
)

data class Geometry(
    @SerializedName("coordinates") val coordinates: List<List<Double>>, // [[lon, lat], ...]
    @SerializedName("type") val type: String
)

data class NavigationStep(
    val instruction: String,
    val distance: Double,
    val geometry: List<org.osmdroid.util.GeoPoint>,
    val maneuverType: String?,
    val modifier: String?
)
