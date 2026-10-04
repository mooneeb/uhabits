package org.isoron.uhabits.core.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable
data class TrackingSettings(
    val type: Int,
    val freqNum: Int,
    val freqDen: Int,
    val targetValue: Double,
    val targetType: Int,
    val unit: String
) {
    fun validate() {
        require(type in 0..1 && targetType in 0..1) { "Unsupported habit or target type" }
        require(freqNum > 0 && freqDen >= freqNum && freqDen <= 1000000) { "Invalid habit frequency" }
        require(targetValue.isFinite() && targetValue >= 0 && targetValue <= 2147483.647) { "Invalid numeric target" }
        require(unit.length <= 1000) { "Unit is too long" }
    }

    companion object {
        fun fromJson(value: JsonElement): TrackingSettings {
            val fields = setOf("type", "freqNum", "freqDen", "targetValue", "targetType", "unit")
            require(value is JsonObject && value.keys == fields) { "Incomplete tracking settings" }
            for (field in fields - "unit") {
                require((value[field] as? JsonPrimitive)?.isString == false) { "Tracking values must be numbers" }
            }
            require((value["unit"] as? JsonPrimitive)?.isString == true) { "Unit must be text" }
            return try {
                Json.decodeFromJsonElement<TrackingSettings>(value).also { it.validate() }
            } catch (error: Exception) {
                throw IllegalArgumentException("Invalid tracking settings", error)
            }
        }
    }
}
