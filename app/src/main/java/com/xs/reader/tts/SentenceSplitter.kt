package com.xs.reader.tts

data class Sentence(val text: String, val range: IntRange)

object SentenceSplitter {
    private val ENDERS = charArrayOf('。', '！', '？', '；', '!', '?', ';', '\n')

    fun split(text: String, fromOffset: Int = 0): List<Sentence> {
        if (fromOffset >= text.length) return emptyList()
        val result = mutableListOf<Sentence>()
        var start = fromOffset
        var i = fromOffset
        while (i < text.length) {
            val c = text[i]
            if (c in ENDERS) {
                while (i + 1 < text.length && (text[i + 1] in ENDERS || text[i + 1] in setOf('"', '”', '」', '』', ')'))) {
                    i++
                }
                val end = i + 1
                val piece = text.substring(start, end).trim()
                if (piece.isNotEmpty()) {
                    result += Sentence(piece, start until end)
                }
                start = end
            }
            i++
        }
        if (start < text.length) {
            val piece = text.substring(start, text.length).trim()
            if (piece.isNotEmpty()) {
                result += Sentence(piece, start until text.length)
            }
        }
        return mergeShort(result)
    }

    /** 连续过短的句子合并,减少音频片段数量。 */
    private fun mergeShort(list: List<Sentence>, minLen: Int = 8): List<Sentence> {
        if (list.size <= 1) return list
        val out = mutableListOf<Sentence>()
        var pending: Sentence? = null
        for (s in list) {
            if (pending == null) {
                pending = s
            } else if (pending.text.length < minLen) {
                pending = Sentence(
                    text = pending.text + s.text,
                    range = pending.range.first..s.range.last
                )
            } else {
                out += pending
                pending = s
            }
        }
        pending?.let { out += it }
        return out
    }
}
