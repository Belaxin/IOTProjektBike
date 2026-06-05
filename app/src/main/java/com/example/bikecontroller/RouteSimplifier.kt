package com.example.bikecontroller

import org.osmdroid.util.GeoPoint
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

object RouteSimplifier {

    /**
     * Simplifies a list of GeoPoint points using the Douglas-Peucker algorithm.
     */
    fun simplify(points: List<GeoPoint>, epsilon: Double): List<GeoPoint> {
        if (points.size < 3) return points

        var maxDistance = 0.0
        var index = 0
        for (i in 1 until points.size - 1) {
            val distance = perpendicularDistance(points[i], points.first(), points.last())
            if (distance > maxDistance) {
                index = i
                maxDistance = distance
            }
        }

        return if (maxDistance > epsilon) {
            val left = simplify(points.subList(0, index + 1), epsilon)
            val right = simplify(points.subList(index, points.size), epsilon)
            left.dropLast(1) + right
        } else {
            listOf(points.first(), points.last())
        }
    }

    private fun perpendicularDistance(p: GeoPoint, start: GeoPoint, end: GeoPoint): Double {
        val x = p.latitude
        val y = p.longitude
        val x1 = start.latitude
        val y1 = start.longitude
        val x2 = end.latitude
        val y2 = end.longitude

        val numerator = abs((y2 - y1) * x - (x2 - x1) * y + x2 * y1 - y2 * x1)
        val denominator = sqrt((y2 - y1).pow(2.0) + (x2 - x1).pow(2.0))
        return if (denominator == 0.0) 0.0 else numerator / denominator
    }
    
    /**
     * Attempts to simplify a route down to a target number of points.
     */
    fun simplifyToTarget(points: List<GeoPoint>, targetMax: Int): List<GeoPoint> {
        if (points.size <= targetMax) return points
        
        var epsilon = 0.00001
        var simplified = points
        
        // Iteratively increase epsilon until we reach targetMax or epsilon gets too big
        while (simplified.size > targetMax && epsilon < 0.1) {
            simplified = simplify(points, epsilon)
            epsilon *= 1.5
        }
        
        return simplified
    }
}
