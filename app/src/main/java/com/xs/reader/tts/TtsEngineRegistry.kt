package com.xs.reader.tts

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TtsEngineRegistry @Inject constructor(
    private val system: SystemTtsEngine,
    private val xunfei: XunfeiTtsEngine,
    private val xunfeiSuper: XunfeiSuperTtsEngine,
    private val matcha: MatchaTtsEngine
) {
    val all: List<TtsEngine> = listOf(system, xunfei, xunfeiSuper, matcha)

    fun byId(id: String): TtsEngine = all.firstOrNull { it.id == id } ?: system
}
