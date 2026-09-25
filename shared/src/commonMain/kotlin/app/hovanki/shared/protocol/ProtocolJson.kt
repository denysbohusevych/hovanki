package app.hovanki.shared.protocol

import kotlinx.serialization.json.Json

/** JSON settings used on both ends of the wire (Ktor client and Spring server). */
val protocolJson: Json =
    Json {
        // An older client must keep working when the server adds fields.
        ignoreUnknownKeys = true
        // Unknown enum values fall back to the property default instead of failing.
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = false
    }
