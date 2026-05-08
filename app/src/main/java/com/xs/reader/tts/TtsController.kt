package com.xs.reader.tts

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class TtsState(
    val isPlaying: Boolean = false,
    val engineId: String = "system",
    val voiceId: String? = null,
    val activeRange: IntRange? = null,
    val activeBookId: Long? = null,
    val activeChapterIndex: Int = 0,
    val sentenceIndex: Int = 0,
    val totalSentences: Int = 0,
    val errorMessage: String? = null
)

interface TtsHost {
    suspend fun loadChapter(bookId: Long, chapterIndex: Int): Pair<String, String>?
    fun nextChapter(bookId: Long, chapterIndex: Int): Int?
    fun onSentenceChanged(bookId: Long, chapterIndex: Int, range: IntRange?)
}

@Singleton
class TtsController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: TtsEngineRegistry
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(TtsState())
    val state: StateFlow<TtsState> = _state.asStateFlow()

    @Volatile private var host: TtsHost? = null
    private var player: ExoPlayer? = null
    private var playJob: Job? = null

    fun bindHost(h: TtsHost?) { host = h }

    fun ensurePlayer(): ExoPlayer {
        return player ?: ExoPlayer.Builder(context).build().also {
            player = it
            it.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _state.update { s -> s.copy(isPlaying = isPlaying) }
                }
            })
        }
    }

    fun start(
        bookId: Long,
        chapterIndex: Int,
        fromOffset: Int,
        engineId: String,
        voice: TtsVoice,
        speed: Float,
        pitch: Float
    ) {
        stopInternal()
        // 立即把 isPlaying 设为 true 并清除上次错误,让 UI 切换到"朗读中"状态
        _state.update {
            it.copy(
                isPlaying = true,
                activeBookId = bookId,
                activeChapterIndex = chapterIndex,
                engineId = engineId,
                voiceId = voice.id,
                errorMessage = null
            )
        }
        playJob = scope.launch {
            try {
                val host = host ?: run {
                    _state.update { it.copy(isPlaying = false, errorMessage = "朗读未就绪,请重试") }
                    return@launch
                }
                playChapter(host, bookId, chapterIndex, fromOffset, engineId, voice, speed, pitch)
            } catch (e: Exception) {
                _state.update { it.copy(isPlaying = false, errorMessage = e.message ?: "朗读异常") }
            }
        }
    }

    fun pause() { player?.pause() }
    fun resume() { player?.play() }
    fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    fun stop() = stopInternal()

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    private fun stopInternal() {
        playJob?.cancel()
        playJob = null
        player?.stop()
        player?.clearMediaItems()
        val s = _state.value
        host?.onSentenceChanged(s.activeBookId ?: -1, s.activeChapterIndex, null)
        _state.update { it.copy(isPlaying = false, activeRange = null) }
    }

    fun release() {
        stopInternal()
        player?.release()
        player = null
        scope.coroutineContext.cancelChildren()
    }

    private suspend fun playChapter(
        host: TtsHost,
        bookId: Long,
        chapterIndex: Int,
        fromOffset: Int,
        engineId: String,
        voice: TtsVoice,
        speed: Float,
        pitch: Float
    ) {
        val chapter = host.loadChapter(bookId, chapterIndex) ?: return
        val body = chapter.second
        val sentences = SentenceSplitter.split(body, fromOffset)
        if (sentences.isEmpty()) {
            host.nextChapter(bookId, chapterIndex)?.let { next ->
                playChapter(host, bookId, next, 0, engineId, voice, speed, pitch)
            }
            return
        }
        _state.update {
            it.copy(
                activeBookId = bookId,
                activeChapterIndex = chapterIndex,
                engineId = engineId,
                voiceId = voice.id,
                totalSentences = sentences.size,
                sentenceIndex = 0,
                errorMessage = null
            )
        }
        var consecutiveFailures = 0
        for ((idx, sentence) in sentences.withIndex()) {
            _state.update { it.copy(sentenceIndex = idx, activeRange = sentence.range) }
            host.onSentenceChanged(bookId, chapterIndex, sentence.range)
            val engine = registry.byId(engineId)
            val req = TtsRequest(text = sentence.text, voice = voice, speed = speed, pitch = pitch)
            val synthesizeResult = withContext(Dispatchers.IO) {
                runCatching { engine.synthesize(req).firstOrNull() }
            }
            val audio = synthesizeResult.getOrNull()
            if (audio == null) {
                consecutiveFailures++
                val errMsg = synthesizeResult.exceptionOrNull()?.message
                _state.update {
                    it.copy(
                        errorMessage = "朗读合成失败" +
                            (errMsg?.let { m -> ": $m" } ?: "") +
                            " (引擎: $engineId, 音色: ${voice.id})"
                    )
                }
                // 连续 3 次失败,熔断停止,避免死循环不停刷新 UI
                if (consecutiveFailures >= 3) {
                    _state.update {
                        it.copy(
                            isPlaying = false,
                            errorMessage = "连续合成失败,已停止朗读。请到设置 -> 朗读引擎 检查引擎可用性。"
                        )
                    }
                    return
                }
                continue
            }
            consecutiveFailures = 0
            val mediaItem = when (audio) {
                is TtsAudio.EncodedFile -> MediaItem.fromUri("file://${audio.filePath}")
                is TtsAudio.Pcm -> MediaItem.fromUri(audio.bytesToWavCacheFile().toURI().toString())
            }
            val p = ensurePlayer()
            withContext(Dispatchers.Main) {
                p.setMediaItem(mediaItem)
                p.prepare()
                p.play()
            }
            awaitPlaybackEnd(p)
        }
        host.onSentenceChanged(bookId, chapterIndex, null)
        _state.update { it.copy(activeRange = null) }
        host.nextChapter(bookId, chapterIndex)?.let { next ->
            playChapter(host, bookId, next, 0, engineId, voice, speed, pitch)
        } ?: _state.update { it.copy(isPlaying = false) }
    }

    private suspend fun awaitPlaybackEnd(p: ExoPlayer) = suspendCancellableCoroutine { cont ->
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    if (cont.isActive) {
                        p.removeListener(this)
                        cont.resumeWith(Result.success(Unit))
                    }
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (cont.isActive) {
                    p.removeListener(this)
                    cont.resumeWith(Result.success(Unit))
                }
            }
        }
        p.addListener(listener)
        cont.invokeOnCancellation { p.removeListener(listener) }
    }

    private fun TtsAudio.Pcm.bytesToWavCacheFile(): File {
        val dir = File(context.cacheDir, "tts").apply { mkdirs() }
        val out = File(dir, "pcm-${UUID.randomUUID()}.wav")
        val bitsPerSample = bitDepth
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val totalDataLen = bytes.size + 36
        out.outputStream().use { os ->
            os.write("RIFF".toByteArray())
            os.write(intLE(totalDataLen))
            os.write("WAVE".toByteArray())
            os.write("fmt ".toByteArray())
            os.write(intLE(16))
            os.write(shortLE(1))
            os.write(shortLE(channels))
            os.write(intLE(sampleRate))
            os.write(intLE(byteRate))
            os.write(shortLE(channels * bitsPerSample / 8))
            os.write(shortLE(bitsPerSample))
            os.write("data".toByteArray())
            os.write(intLE(bytes.size))
            os.write(bytes)
        }
        return out
    }

    private fun intLE(v: Int): ByteArray = byteArrayOf(
        (v and 0xff).toByte(),
        ((v shr 8) and 0xff).toByte(),
        ((v shr 16) and 0xff).toByte(),
        ((v shr 24) and 0xff).toByte()
    )
    private fun shortLE(v: Int): ByteArray = byteArrayOf(
        (v and 0xff).toByte(),
        ((v shr 8) and 0xff).toByte()
    )
}
