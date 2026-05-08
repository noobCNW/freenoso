package com.xs.reader.tts

import kotlinx.coroutines.flow.Flow

data class TtsVoice(
    val engineId: String,
    val id: String,
    val displayName: String,
    val locale: String? = null,
    val gender: String? = null,
    val style: String? = null
)

sealed class TtsAudio {
    data class Pcm(
        val bytes: ByteArray,
        val sampleRate: Int,
        val channels: Int,
        val bitDepth: Int = 16
    ) : TtsAudio()

    data class EncodedFile(
        val filePath: String,
        val mimeType: String
    ) : TtsAudio()
}

data class TtsRequest(
    val text: String,
    val voice: TtsVoice,
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f
)

interface TtsEngine {
    val id: String
    val displayName: String
    val needsNetwork: Boolean
    val needsApiKey: Boolean

    suspend fun listVoices(): List<TtsVoice>

    /**
     * 合成一个文本片段,以一段或多段音频回送。
     * 简单实现可一次发完,流式实现按 chunk 推送,便于边合成边播。
     */
    fun synthesize(request: TtsRequest): Flow<TtsAudio>

    suspend fun close() {}
}
