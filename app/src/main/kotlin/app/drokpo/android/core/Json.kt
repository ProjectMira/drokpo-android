package app.drokpo.android.core

import kotlinx.serialization.json.Json

/**
 * The one JSON configuration for the REST API — the counterpart of the iOS
 * app's default `JSONDecoder()`/`JSONEncoder()`:
 *
 * - `ignoreUnknownKeys`: the backend returns raw Firestore docs (fcmTokens,
 *   status, location, updatedAt, …); Swift's decoder ignores extra keys too.
 * - `explicitNulls = false`: a null property is omitted on encode, matching
 *   Swift's synthesized `encodeIfPresent` — PATCH bodies must never send
 *   `"field": null` (omitted means "unchanged"; "" means "clear").
 * - `encodeDefaults = true`: non-optional properties with defaults are still
 *   written (Swift encodes every non-optional property).
 * - `coerceInputValues = true`: a JSON null (or unknown enum value) for a
 *   non-null property with a default falls back to the default instead of
 *   failing the whole response — strictly more tolerant than Swift.
 * - `isLenient = false`: strings must be quoted, like JSONDecoder.
 */
val DrokpoJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
    isLenient = false
}
