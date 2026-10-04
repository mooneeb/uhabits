package org.isoron.uhabits.core.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import org.isoron.platform.time.LocalDate

@Serializable
data class RecordedEntry(val value: Int, val notes: String)

@Serializable
data class ReminderSettings(val hour: Int, val minute: Int, val days: Int)

object RegisterValues {
    private val habitId = Regex("[a-f0-9]{32,70}|[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")

    fun date(value: String): LocalDate {
        require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value)) { "Invalid habit date" }
        val parts = value.split('-').map { it.toInt() }
        require(parts[0] in 1900..2199 && parts[1] in 1..12 && parts[2] in 1..31) { "Invalid habit date" }
        val result = LocalDate(parts[0], parts[1], parts[2])
        require(result.year == parts[0] && result.month == parts[1] && result.day == parts[2]) { "Invalid habit date" }
        return result
    }

    fun validate(key: String, value: JsonElement) {
        val parts = key.split(':')
        when (parts.first()) {
            "habit" -> {
                require(parts.size == 3 && habitId.matches(parts[1])) { "Invalid habit identity" }
                when (parts[2]) {
                    "name" -> require(text(value).isNotBlank() && text(value).length <= 1000) { "Invalid habit name" }
                    "question", "description" -> require(text(value).length <= 10000) { "Habit text is too long" }
                    "color" -> require(number(value) in 0..19) { "Invalid habit color" }
                    "position" -> require(number(value) >= 0) { "Invalid habit order" }
                    "archived", "deleted" -> require((value as? JsonPrimitive)?.booleanOrNull != null && !value.isString) { "Invalid habit state" }
                    "tracking" -> TrackingSettings.fromJson(value)
                    "reminder" -> if (value != JsonNull) {
                        require(value is JsonObject && value.keys == setOf("hour", "minute", "days")) { "Invalid reminder" }
                        require(number(value.getValue("hour")) in 0..23 && number(value.getValue("minute")) in 0..59 && number(value.getValue("days")) in 0..127) { "Invalid reminder" }
                    }
                    else -> throw IllegalArgumentException("Unsupported habit field")
                }
            }
            "entry" -> {
                require(parts.size == 3 && habitId.matches(parts[1])) { "Invalid entry identity" }
                date(parts[2])
                require(value is JsonObject && value.keys == setOf("value", "notes")) { "Invalid entry" }
                require(number(value.getValue("value")) >= -1 && text(value.getValue("notes")).length <= 10000) { "Invalid entry value or notes" }
            }
            "setting" -> {
                require(parts.size == 2) { "Invalid shared setting" }
                when (parts[1]) {
                    "dayStart" -> require(number(value) in setOf(0, 3)) { "Invalid day start" }
                    "weekStart" -> require(number(value) in 1..7) { "Invalid week start" }
                    else -> throw IllegalArgumentException("Unsupported shared setting")
                }
            }
            else -> throw IllegalArgumentException("Unsupported change identity")
        }
    }

    fun text(value: JsonElement): String {
        require(value is JsonPrimitive && value.isString) { "Expected text" }
        return value.content
    }

    fun number(value: JsonElement): Int {
        require(value is JsonPrimitive && !value.isString) { "Expected integer" }
        return value.intOrNull ?: throw IllegalArgumentException("Expected integer")
    }

    fun entry(value: JsonElement): RecordedEntry = Json.decodeFromJsonElement(value)
}
