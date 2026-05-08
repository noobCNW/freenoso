package com.xs.reader.tts

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 用户自定义的讯飞发音人预设。
 *
 * 讯飞 WebAPI 没有「列出当前账号已开通发音人」的接口,所以 APP 不再硬编码音色列表,
 * 改成由用户在设置页手工把控制台「能力管理 → 我的发音人」里看到的 vcn 参数填进来。
 *
 * @param engineId   归属的引擎 id, 当前支持 "xunfei" / "xunfei_super"
 * @param vcn        实际请求时传给讯飞 WebAPI 的 vcn 参数, 例如 `x4_xiaoyan` / `aisjiuxu`
 * @param displayName UI 上显示的名字, 例如 "讯飞·小燕"
 * @param gender     可选: Female / Male / Neutral
 * @param style      可选: 自由文本备注, 如 "评书 / 直播 / 童音"
 */
@Serializable
data class XunfeiVoicePreset(
    val engineId: String,
    val vcn: String,
    val displayName: String,
    val gender: String? = null,
    val style: String? = null
) {
    fun toTtsVoice(): TtsVoice = TtsVoice(
        engineId = engineId,
        id = vcn,
        displayName = displayName,
        locale = "zh-CN",
        gender = gender,
        style = style
    )

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun listToJson(list: List<XunfeiVoicePreset>): String =
            json.encodeToString(kotlinx.serialization.builtins.ListSerializer(serializer()), list)

        fun listFromJson(s: String?): List<XunfeiVoicePreset> {
            if (s.isNullOrBlank()) return emptyList()
            return runCatching {
                json.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(serializer()),
                    s
                )
            }.getOrElse { emptyList() }
        }
    }
}
