package app.drokpo.android.core.model

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject

// Decoding-tolerance building blocks shared by the response models. Every
// custom serializer here reads a whole JsonElement first and decodes from the
// tree, so a failure inside one element can never leave the streaming parser
// half-way through the payload.

/**
 * Decodes to `value == null` instead of sinking the whole array when one
 * element is malformed or has an unknown type (forward compatibility with
 * future card kinds the backend may start serving).
 */
@Serializable(with = FailableItemSerializer::class)
data class FailableItem<Wrapped>(val value: Wrapped?)

class FailableItemSerializer<Wrapped>(
    private val wrapped: KSerializer<Wrapped>,
) : KSerializer<FailableItem<Wrapped>> {
    override val descriptor: SerialDescriptor =
        SerialDescriptor("app.drokpo.FailableItem<${wrapped.descriptor.serialName}>", wrapped.descriptor.nullable)

    override fun deserialize(decoder: Decoder): FailableItem<Wrapped> {
        val input = decoder.asJsonDecoder()
        val element = input.decodeJsonElement()
        return FailableItem(input.tryDecode(wrapped, element))
    }

    override fun serialize(encoder: Encoder, value: FailableItem<Wrapped>) {
        encoder.encodeNullableSerializableValue(wrapped, value.value)
    }
}

/**
 * `[FailableItem<T>]` + `compactMap(\.value)` in one step: a JSON array whose
 * undecodable elements are dropped. A non-array value still fails, exactly
 * like Swift's `decodeIfPresent([FailableItem<T>].self, …)`.
 */
open class FailableListSerializer<T>(wrapped: KSerializer<T>) : KSerializer<List<T>> {
    private val list = ListSerializer(FailableItemSerializer(wrapped))

    override val descriptor: SerialDescriptor =
        SerialDescriptor("app.drokpo.FailableList<${wrapped.descriptor.serialName}>", list.descriptor)

    override fun deserialize(decoder: Decoder): List<T> =
        list.deserialize(decoder).mapNotNull { it.value }

    override fun serialize(encoder: Encoder, value: List<T>) {
        list.serialize(encoder, value.map { FailableItem(it) })
    }
}

/**
 * The spec doesn't say whether list endpoints return a bare array or a wrapper
 * object, so accept both shapes.
 *
 * Mirrors the Swift decoder exactly: a bare array decodes strictly (one bad
 * element fails the whole list); a wrapper object yields the first value that
 * decodes as `[Element]` — so one bad element there silently skips that key —
 * and an object with no such value yields an empty list.
 */
@Serializable(with = TolerantListSerializer::class)
data class TolerantList<Element>(val items: List<Element>)

class TolerantListSerializer<Element>(element: KSerializer<Element>) : KSerializer<TolerantList<Element>> {
    private val list = ListSerializer(element)

    override val descriptor: SerialDescriptor =
        SerialDescriptor("app.drokpo.TolerantList<${element.descriptor.serialName}>", list.descriptor)

    override fun deserialize(decoder: Decoder): TolerantList<Element> {
        val input = decoder.asJsonDecoder()
        return when (val root = input.decodeJsonElement()) {
            is JsonArray -> TolerantList(input.json.decodeFromJsonElement(list, root))
            is JsonObject -> TolerantList(
                root.values.firstNotNullOfOrNull { value -> input.tryDecode(list, value) } ?: emptyList(),
            )
            else -> throw SerializationException("Expected a JSON array or object, found $root")
        }
    }

    override fun serialize(encoder: Encoder, value: TolerantList<Element>) {
        list.serialize(encoder, value.items)
    }
}

// MARK: - Helpers for the hand-written serializers

internal fun Decoder.asJsonDecoder(): JsonDecoder =
    this as? JsonDecoder ?: throw SerializationException("Drokpo models can only be decoded from JSON")

internal fun Encoder.asJsonEncoder(): JsonEncoder =
    this as? JsonEncoder ?: throw SerializationException("Drokpo models can only be encoded to JSON")

/** Swift's `try?` around a decode: any decoding failure becomes null. */
internal fun <T> JsonDecoder.tryDecode(deserializer: DeserializationStrategy<T>, element: JsonElement): T? =
    try {
        json.decodeFromJsonElement(deserializer, element)
    } catch (e: IllegalArgumentException) {
        // SerializationException is an IllegalArgumentException; the plain
        // kind comes from JsonElement casts (e.g. `.jsonObject` on an array).
        null
    }

/** `container.decode(T.self, forKey:)` — fails on a missing or null value. */
internal fun <T> JsonDecoder.decodeRequired(
    deserializer: DeserializationStrategy<T>,
    obj: JsonObject,
    key: String,
): T {
    val element = obj[key] ?: throw SerializationException("Missing required key '$key'")
    return try {
        json.decodeFromJsonElement(deserializer, element)
    } catch (e: SerializationException) {
        throw e
    } catch (e: IllegalArgumentException) {
        throw SerializationException("Invalid value for '$key': ${e.message}", e)
    }
}

internal fun JsonElement.asJsonObject(what: String): JsonObject =
    this as? JsonObject ?: throw SerializationException("Expected $what to be a JSON object, found $this")
