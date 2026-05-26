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

        /**
         * 讯飞「在线语音合成 (普通版)」内置基础发音人。
         * 来自讯飞控制台「在线语音合成 → 发音人授权管理 → 基础发音人」默认开通的免费列表。
         * 用户在自己账号下默认就能用这些 vcn,无需在控制台另行领取。
         */
        val BUILTIN_XUNFEI: List<XunfeiVoicePreset> = listOf(
            XunfeiVoicePreset(
                engineId = "xunfei",
                vcn = "x4_xiaoyan",
                displayName = "讯飞·小燕",
                gender = "Female"
            ),
            XunfeiVoicePreset(
                engineId = "xunfei",
                vcn = "x4_yezi",
                displayName = "讯飞·小露 (叶子)",
                gender = "Female"
            ),
            XunfeiVoicePreset(
                engineId = "xunfei",
                vcn = "aisjiuxu",
                displayName = "讯飞·许久",
                gender = "Male"
            ),
            XunfeiVoicePreset(
                engineId = "xunfei",
                vcn = "aisjinger",
                displayName = "讯飞·小婧",
                gender = "Female"
            ),
            XunfeiVoicePreset(
                engineId = "xunfei",
                vcn = "aisbabyxu",
                displayName = "讯飞·许小宝",
                gender = "Male",
                style = "童音"
            )
        )

        /**
         * 讯飞「超拟人语音合成」内置发音人。
         * 来自控制台「超拟人语音合成 → 发音人授权管理」默认免费开通的"聆"系列发音人。
         */
        val BUILTIN_XUNFEI_SUPER: List<XunfeiVoicePreset> = listOf(
            XunfeiVoicePreset(
                engineId = "xunfei_super",
                vcn = "x6_lingfeiyi_pro",
                displayName = "聆飞逸 (男)",
                gender = "Male"
            ),
            XunfeiVoicePreset(
                engineId = "xunfei_super",
                vcn = "x6_lingxiaoxuan_pro",
                displayName = "聆小璇 (女)",
                gender = "Female"
            ),
            XunfeiVoicePreset(
                engineId = "xunfei_super",
                vcn = "x5_lingyuzhao_flow",
                displayName = "聆玉昭 (女·流式)",
                gender = "Female"
            ),
            XunfeiVoicePreset(
                engineId = "xunfei_super",
                vcn = "x6_lingxiaoyue_pro",
                displayName = "聆小玥 (女)",
                gender = "Female"
            ),
            XunfeiVoicePreset(
                engineId = "xunfei_super",
                vcn = "x6_lingyuyan_pro",
                displayName = "聆玉言 (女)",
                gender = "Female"
            )
        )

        /** 按 engineId 取该引擎的所有内置发音人。 */
        fun builtinFor(engineId: String): List<XunfeiVoicePreset> = when (engineId) {
            "xunfei" -> BUILTIN_XUNFEI
            "xunfei_super" -> BUILTIN_XUNFEI_SUPER
            else -> emptyList()
        }

        /**
         * 把内置发音人和用户自定义发音人合并后返回 `TtsVoice` 列表。
         * 同 vcn 的用户预设优先 (覆盖内置), 顺序: 内置在前, 用户在后追加。
         */
        fun mergedVoicesFor(
            engineId: String,
            userPresets: List<XunfeiVoicePreset>
        ): List<TtsVoice> {
            val userOnEngine = userPresets.filter { it.engineId == engineId }
            val userVcns = userOnEngine.map { it.vcn }.toSet()
            val builtinFiltered = builtinFor(engineId).filter { it.vcn !in userVcns }
            return (builtinFiltered + userOnEngine).map { it.toTtsVoice() }
        }
    }
}
