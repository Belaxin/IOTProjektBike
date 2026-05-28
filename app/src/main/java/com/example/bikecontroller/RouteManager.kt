package com.example.bikecontroller

import kotlin.math.*

/**
 * RouteManager handles route optimization and simplification.
 * ESP32 has limited memory, so we keep 20-40 waypoints max.
 */
class RouteManager {

    /**
     * Simplify route using Douglas-Peucker algorithm.
     * This reduces the number of points while preserving the route shape.
     */
    fun simplifyRoute(
        points: List<Pair<Double, Double>>,
        maxPoints: Int = 30,
        epsilon: Double = 0.0001
    ): List<Pair<Double, Double>> {
        if (points.size <= 2) return points

        // First pass: Douglas-Peucker with adaptive epsilon
        var simplified = douglasPeucker(points, epsilon)

        // If still too many points, aggressively reduce
        var currentEpsilon = epsilon
        while (simplified.size > maxPoints && currentEpsilon < 0.01) {
            currentEpsilon *= 2
            simplified = douglasPeucker(points, currentEpsilon)
        }

        // If still too long, sample every nth point
        if (simplified.size > maxPoints) {
            simplified = sampleEveryNth(simplified, simplified.size / maxPoints + 1)
        }

        // Always keep start and end
        if (simplified.size > 2 && 
            (simplified.first() != points.first() || simplified.last() != points.last())) {
            simplified = listOf(points.first()) + simplified.drop(1).dropLast(1) + points.last()
        }

        return simplified.take(maxPoints)
    }

    /**
     * Douglas-Peucker simplification algorithm.
     */
    private fun douglasPeucker(
        points: List<Pair<Double, Double>>,
        epsilon: Double
    ): List<Pair<Double, Double>> {
        if (points.size < 3) return points

        var maxDist = 0.0
        var maxIndex = 0

        // Find point with maximum distance from line
        for (i in 1 until points.size - 1) {
            val dist = perpendicularDistance(points[i], points[0], points[points.size - 1])
            if (dist > maxDist) {
                maxDist = dist
                maxIndex = i
            }
        }

        // If max distance > epsilon, recursively simplify
        if (maxDist > epsilon) {
            val left = douglasPeucker(points.subList(0, maxIndex + 1), epsilon)
            val right = douglasPeucker(points.subList(maxIndex, points.size), epsilon)
            return left.dropLast(1) + right
        }

        return listOf(points[0], points[points.size - 1])
    }

    /**
     * Calculate perpendicular distance from point to line segment.
     */
    private fun perpendicularDistance(
        point: Pair<Double, Double>,
        lineStart: Pair<Double, Double>,
        lineEnd: Pair<Double, Double>
    ): Double {
        val px = point.first
        val py = point.second
        val x1 = lineStart.first
        val y1 = lineStart.second
        val x2 = lineEnd.first
        val y2 = lineEnd.second

        val num = abs((y2 - y1) * px - (x2 - x1) * py + x2 * y1 - y2 * x1)
        val den = sqrt((y2 - y1) * (y2 - y1) + (x2 - x1) * (x2 - x1))
        return if (den == 0.0) 0.0 else num / den
    }

    /**
     * Sample every nth point from the route.
     */
    private fun sampleEveryNth(
        points: List<Pair<Double, Double>>,
        n: Int
    ): List<Pair<Double, Double>> {
        if (n <= 1) return points
        
        return (0 until points.size step n).map { points[it] } +
               if ((points.size - 1) % n != 0) listOf(points.last()) else emptyList()
    }

    /**
     * Calculate distance between two coordinates in kilometers.
     */
    fun haversineDistance(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val R = 6371.0 // Earth's radius in km
        val dLat = (lat2 - lat1) * PI / 180.0
        val dLon = (lon2 - lon1) * PI / 180.0
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    /**
     * Calculate total route distance in kilometers.
     */
    fun calculateRouteDistance(points: List<Pair<Double, Double>>): Double {
        if (points.size < 2) return 0.0
        
        var totalDistance = 0.0
        for (i in 0 until points.size - 1) {
            totalDistance += haversineDistance(
                points[i].first, points[i].second,
                points[i + 1].first, points[i + 1].second
            )
        }
        return totalDistance
    }

    /**
     * Generate simulated movement along route for testing.
     */
    fun generateSimulatedPath(
        points: List<Pair<Double, Double>>,
        stepCount: Int = 100
    ): List<Pair<Double, Double>> {
        if (points.size < 2 || stepCount < 2) return points

        val result = mutableListOf<Pair<Double, Double>>()
        val segmentSteps = stepCount / (points.size - 1)

        for (i in 0 until points.size - 1) {
            val start = points[i]
            val end = points[i + 1]
            
            for (j in 0..segmentSteps) {
                val t = j / segmentSteps.toDouble()
                val lat = start.first + (end.first - start.first) * t
                val lon = start.second + (end.second - start.second) * t
                result.add(lat to lon)
            }
        }

        return result.distinctBy { "${it.first},${it.second}" }
    }
}
