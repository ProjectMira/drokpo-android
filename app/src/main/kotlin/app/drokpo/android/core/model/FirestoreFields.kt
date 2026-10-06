package app.drokpo.android.core.model

import com.google.firebase.Timestamp
import java.time.Instant
import java.util.Date

// Typed reads over a Firestore `DocumentSnapshot.getData()` map — the Kotlin
// counterparts of Swift's `data["x"] as? String` casts. Android's Firestore SDK
// returns every integer as Long (never Int) and timestamps as
// com.google.firebase.Timestamp, so a straight `as? Int` / `as? Map<String,
// Int>` port would silently fail (or throw later on unboxing); use these.

fun Map<String, Any?>.firestoreString(key: String): String? = this[key] as? String

fun Map<String, Any?>.firestoreBoolean(key: String): Boolean? = this[key] as? Boolean

/** `data["x"] as? Int` — any Firestore number (Long/Double) narrowed to Int. */
fun Map<String, Any?>.firestoreInt(key: String): Int? = (this[key] as? Number)?.toInt()

fun Map<String, Any?>.firestoreDouble(key: String): Double? = (this[key] as? Number)?.toDouble()

/** `data["x"] as? [String]` — null unless every element is a String. */
fun Map<String, Any?>.firestoreStringList(key: String): List<String>? {
    val list = this[key] as? List<*> ?: return null
    return if (list.all { it is String }) list.map { it as String } else null
}

/** `data["x"] as? [String: Any]` */
fun Map<String, Any?>.firestoreMap(key: String): Map<String, Any?>? {
    val map = this[key] as? Map<*, *> ?: return null
    if (!map.keys.all { it is String }) return null
    @Suppress("UNCHECKED_CAST")
    return map as Map<String, Any?>
}

/** `data["x"] as? [String: Int]` — null unless every value is a number. */
fun Map<String, Any?>.firestoreIntMap(key: String): Map<String, Int>? {
    val map = firestoreMap(key) ?: return null
    if (!map.values.all { it is Number }) return null
    return map.mapValues { (_, value) -> (value as Number).toInt() }
}

/** `(data["x"] as? Timestamp)?.dateValue()` */
fun Map<String, Any?>.firestoreInstant(key: String): Instant? = when (val value = this[key]) {
    is Timestamp -> Instant.ofEpochSecond(value.seconds, value.nanoseconds.toLong())
    is Date -> value.toInstant()
    else -> null
}
