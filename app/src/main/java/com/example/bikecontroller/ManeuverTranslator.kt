package com.example.bikecontroller

object ManeuverTranslator {
    fun translate(maneuverType: String?, modifier: String?, name: String?): String {
        if (maneuverType == null) {
            return name?.let { "Continue on $it" } ?: "Continue"
        }

        val base = when (maneuverType) {
            "turn" -> when (modifier) {
                "left" -> "Turn left"
                "right" -> "Turn right"
                "sharp left" -> "Turn sharply left"
                "sharp right" -> "Turn sharply right"
                "slight left" -> "Turn slightly left"
                "slight right" -> "Turn slightly right"
                "straight" -> "Go straight"
                "uturn" -> "Make a U-turn"
                else -> "Turn"
            }
            "new name" -> "Continue"
            "depart" -> "Depart"
            "arrive" -> "Arrive at destination"
            "merge" -> when (modifier) {
                "left" -> "Merge left"
                "right" -> "Merge right"
                else -> "Merge"
            }
            "ramp" -> when (modifier) {
                "left" -> "Take left ramp"
                "right" -> "Take right ramp"
                else -> "Take ramp"
            }
            "on ramp" -> "Enter ramp"
            "off ramp" -> "Exit ramp"
            "fork" -> when (modifier) {
                "left" -> "Fork left"
                "right" -> "Fork right"
                else -> "Fork"
            }
            "end of road" -> when (modifier) {
                "left" -> "End of road, turn left"
                "right" -> "End of road, turn right"
                else -> "End of road"
            }
            "continue" -> "Continue"
            "roundabout" -> "Enter roundabout"
            "rotary" -> "Enter roundabout"
            "roundabout turn" -> when (modifier) {
                "left" -> "Turn left at roundabout"
                "right" -> "Turn right at roundabout"
                "straight" -> "Go straight at roundabout"
                else -> "Go through roundabout"
            }
            "notification" -> "Continue"
            "exit roundabout" -> "Exit roundabout"
            "exit rotary" -> "Exit roundabout"
            else -> "Continue"
        }

        return if (name != null && !name.isEmpty() && maneuverType != "arrive") {
            "$base on ${name}"
        } else {
            base
        }
    }

    fun getInstructionDistance(distanceMeters: Double): String {
        return when {
            distanceMeters < 100 -> "nearby"
            distanceMeters < 1000 -> "${(distanceMeters / 100).toInt() * 100}m"
            else -> "${String.format("%.1f", distanceMeters / 1000)}km"
        }
    }
}
