package com.pulseloop.coach.attachments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.UUID

/**
 * Ported from [CoachAttachmentRef] in CoachAttachmentStore.swift.
 * A reference to an image attached to a coach message. The bytes live on disk
 * in `<filesDir>/coach_attachments/<file>`; the message persists only this
 * small ref (as JSON in `CoachMessageEntity.attachmentsJson`). Mirrors the
 * `*JSON` ref convention already used for `PendingAction` — no Room blob, so
 * the database stays small and fast.
 */
@Serializable
data class CoachAttachmentRef(
    /** Filename within `coach_attachments/` (e.g. `<uuid>.jpg`). */
    val file: String,
    /** MIME type of the stored bytes (always `image/jpeg` in v1). */
    val mime: String = CoachAttachmentStore.MIME_TYPE,
    val width: Int,
    val height: Int,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** JSON form for the (array-valued) `attachmentsJson` field. */
        fun encode(refs: List<CoachAttachmentRef>): String? {
            if (refs.isEmpty()) return null
            return try {
                json.encodeToString(ListSerializer(serializer()), refs)
            } catch (_: Exception) {
                null
            }
        }

        fun decode(fromJSON: String?): List<CoachAttachmentRef> {
            if (fromJSON.isNullOrBlank()) return emptyList()
            return try {
                json.decodeFromString(ListSerializer(serializer()), fromJSON)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}

/**
 * Ported from [CoachImagePayload] in CoachAttachmentStore.swift.
 * The wire-ready forms of one image, built once from a [CoachAttachmentRef]'s
 * bytes and handed to the request builders. Each provider picks the shape it
 * needs: OpenAI/OpenRouter take the `data:` URL; Gemini takes the raw base64 +
 * `mimeType`.
 */
data class CoachImagePayload(
    /** `data:image/jpeg;base64,<…>` — used by OpenAI `input_image` and OpenRouter `image_url`. */
    val dataURL: String,
    /** Bare base64 (no `data:` prefix) — used by Gemini `inlineData.data`. */
    val rawBase64: String,
    val mimeType: String,
)

/**
 * Ported from [CoachAttachmentStore] in CoachAttachmentStore.swift.
 * On-device store for coach image attachments: compresses + writes incoming
 * images, loads them back for the chat bubble, and produces the base64
 * payloads the model clients send.
 */
object CoachAttachmentStore {
    /** Longest-edge cap applied before JPEG-encoding. Keeps request payloads
     *  small (all three providers bill by image size / cap total request bytes)
     *  while staying sharp enough for the model to read charts and labels. */
    private const val MAX_DIMENSION = 1024
    private const val JPEG_QUALITY = 70
    const val MIME_TYPE = "image/jpeg"

    /** `<filesDir>/coach_attachments/`, created lazily. */
    private fun directory(context: Context): File {
        val dir = File(context.filesDir, "coach_attachments")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun fileFor(context: Context, ref: CoachAttachmentRef): File =
        File(directory(context), ref.file)

    // ── Save ─────────────────────────────────────────────────────────────

    /** Downscales + JPEG-compresses `bitmap`, writes it to a new `<uuid>.jpg`,
     *  and returns the ref. Returns null if the bytes can't be produced or written. */
    fun save(context: Context, bitmap: Bitmap): CoachAttachmentRef? {
        val scaled = downscaled(bitmap)
        val bytes = ByteArrayOutputStream().use { out ->
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) return null
            out.toByteArray()
        }
        val file = "${UUID.randomUUID()}.jpg"
        return try {
            File(directory(context), file).writeBytes(bytes)
            CoachAttachmentRef(file = file, mime = MIME_TYPE, width = scaled.width, height = scaled.height)
        } catch (_: Exception) {
            null
        }
    }

    /** Photo-picker convenience: decodes a content `Uri` and saves it. */
    fun save(context: Context, uri: Uri): CoachAttachmentRef? {
        val bitmap = try {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        } ?: return null
        return save(context, bitmap)
    }

    private fun downscaled(bitmap: Bitmap): Bitmap {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge <= MAX_DIMENSION) return bitmap
        val ratio = MAX_DIMENSION.toFloat() / longEdge
        val w = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val h = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    // ── Load ─────────────────────────────────────────────────────────────

    fun data(context: Context, ref: CoachAttachmentRef): ByteArray? = try {
        fileFor(context, ref).takeIf { it.exists() }?.readBytes()
    } catch (_: Exception) {
        null
    }

    fun loadBitmap(context: Context, ref: CoachAttachmentRef): Bitmap? {
        val bytes = data(context, ref) ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    fun delete(context: Context, ref: CoachAttachmentRef) {
        try {
            fileFor(context, ref).delete()
        } catch (_: Exception) {
            // best-effort cleanup
        }
    }

    // ── Wire payloads ────────────────────────────────────────────────────

    /** Builds the model-ready payload (data URL + raw base64) for a stored ref. */
    fun payload(context: Context, ref: CoachAttachmentRef): CoachImagePayload? {
        val bytes = data(context, ref) ?: return null
        val base64 = Base64.getEncoder().encodeToString(bytes)
        return CoachImagePayload(
            dataURL = "data:${ref.mime};base64,$base64",
            rawBase64 = base64,
            mimeType = ref.mime,
        )
    }

    fun payloads(context: Context, refs: List<CoachAttachmentRef>): List<CoachImagePayload> =
        refs.mapNotNull { payload(context, it) }
}
