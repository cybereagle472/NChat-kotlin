package com.nchat.app.network

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Reads a field as text whatever JSON type actually arrived.
 *
 * Two message fields are written by *both* clients and neither is strictly typed on the wire:
 *
 * - `duration` — the reference sends whole **seconds as a number**
 *   (`ChatView.tsx:1915`: `duration: durationSeconds`), this client sends `"12"`, and D1's
 *   `duration TEXT` column turns either into a string;
 * - `thumbnail_url` — a URL for a photo/video, but a **bar array** for a voice note's waveform
 *   (`ChatView.tsx:1916` sends `waveform: downsampleWaveform(...)` into it).
 *
 * With a plain `String?` property, one voice note from the web app failed the *entire*
 * conversation decode — the history request threw, the repository caught it, and the chat
 * looked empty. Accepting number / array / string means one odd field costs nothing.
 */
object LenientString : KSerializer<String?> {

    override val descriptor =
        PrimitiveSerialDescriptor("com.nchat.app.LenientString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? {
        val json = decoder as? JsonDecoder ?: return decoder.decodeString()
        return when (val element = json.decodeJsonElement()) {
            is JsonNull -> null
            is JsonPrimitive -> element.contentOrNull
            is JsonArray -> element.joinToString(",") { it.text() }
            else -> element.toString()
        }
    }

    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
    }

    private fun JsonElement.text(): String = (this as? JsonPrimitive)?.contentOrNull.orEmpty()
}
