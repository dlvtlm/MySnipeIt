package com.example.mysnipeit.data.models

import com.google.gson.annotations.SerializedName

data class BoundingBox(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

data class DetectedTarget(
    val id: String,
    @SerializedName("class")
    val targetType: String,  // "HUMAN", "VEHICLE", etc.
    val confidence: Float,
    val bbox: BoundingBox,
    val timestamp: Long = 0L
)

enum class TargetType {
    HUMAN,
    VEHICLE,
    STRUCTURE,
    UNKNOWN
}