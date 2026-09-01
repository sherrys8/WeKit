package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.agent.data.entity.ModelProviderType
import dev.ujhhgtg.wekit.preferences.WePrefs

/**
 * AI 回复/群聊分析的独立模型配置（对应 Hchat 双方案：ai_api_*_1/2 + ai_profile）。
 * 任一字段被写入时，当前生效方案的值会同步回填旧单方案键（ai_api_address/ai_api_path/
 * ai_api_key/ai_model），兼容早期版本直接读取这些键的调用方。
 */
internal object AiModelConfig {
    var providerTypeName by WePrefs.prefOption(
        "ai_reply_provider_type",
        ModelProviderType.OPENAI_CHAT_COMPLETION.name,
    )

    /** 当前生效方案（1 或 2，对应 Hchat ai_profile） */
    var activeProfile by WePrefs.prefOption("ai_profile", 1)

    var profileName1 by WePrefs.prefOption("ai_profile_name_1", "")
    var baseUrl1 by WePrefs.prefOption("ai_api_address_1", "")
    var apiPath1 by WePrefs.prefOption("ai_api_path_1", "")
    var apiKey1 by WePrefs.prefOption("ai_api_key_1", "")
    var modelId1 by WePrefs.prefOption("ai_model_1", "")

    var profileName2 by WePrefs.prefOption("ai_profile_name_2", "")
    var baseUrl2 by WePrefs.prefOption("ai_api_address_2", "")
    var apiPath2 by WePrefs.prefOption("ai_api_path_2", "")
    var apiKey2 by WePrefs.prefOption("ai_api_key_2", "")
    var modelId2 by WePrefs.prefOption("ai_model_2", "")

    /** 旧单方案键（写入时由当前生效方案回填，保持旧调用方可用） */
    private var legacyBaseUrl by WePrefs.prefOption("ai_api_address", "")
    private var legacyApiPath by WePrefs.prefOption("ai_api_path", "")
    private var legacyApiKey by WePrefs.prefOption("ai_api_key", "")
    private var legacyModelId by WePrefs.prefOption("ai_model", "")

    /**
     * AI 分析提取消息数量上限；0 表示自动（按模型容量估算），否则取最近 N 条。
     * 上限会同时作用于默认主题与自定义主题的聊天片段。
     */
    var extractLimit by WePrefs.prefOption("ai_reply_extract_limit", 0)

    private val isProfileTwo: Boolean get() = activeProfile == 2

    var baseUrl: String
        get() = if (isProfileTwo) baseUrl2 else baseUrl1
        set(value) {
            if (isProfileTwo) baseUrl2 = value else baseUrl1 = value
            syncLegacy()
        }

    var apiPath: String
        get() = if (isProfileTwo) apiPath2 else apiPath1
        set(value) {
            if (isProfileTwo) apiPath2 = value else apiPath1 = value
            syncLegacy()
        }

    var apiKey: String
        get() = if (isProfileTwo) apiKey2 else apiKey1
        set(value) {
            if (isProfileTwo) apiKey2 = value else apiKey1 = value
            syncLegacy()
        }

    var modelId: String
        get() = if (isProfileTwo) modelId2 else modelId1
        set(value) {
            if (isProfileTwo) modelId2 = value else modelId1 = value
            syncLegacy()
        }

    /** 当前生效方案的显示名 */
    fun profileName(): String = (if (isProfileTwo) profileName2 else profileName1)
        .ifBlank { if (isProfileTwo) "方案 2" else "方案 1" }

    /** 指定方案的显示名（profile = 1 或 2） */
    fun profileName(profile: Int): String = (if (profile == 2) profileName2 else profileName1)
        .ifBlank { if (profile == 2) "方案 2" else "方案 1" }

    private fun syncLegacy() {
        legacyBaseUrl = baseUrl
        legacyApiPath = apiPath
        legacyApiKey = apiKey
        legacyModelId = modelId
    }

    /** 完整请求前缀 = baseUrl + apiPath；apiPath 可为前缀（/v1）或完整端点路径（/v1/chat/completions） */
    fun resolvedBaseUrl(): String {
        var base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return base
        // 用户可能在 baseUrl 里误填了完整端点（如 https://api.deepseek.com/v1/chat/completions），
        // 剥离掉尾部的 /chat/completions，统一由客户端拼接
        base = base.removeSuffix("/chat/completions").trimEnd('/')
        val path = apiPath.trim().trim('/')
        if (path.isEmpty()) return base
        // 防重复路径：baseUrl 已带该前缀（如 baseUrl=https://api.deepseek.com/v1 且 apiPath=/v1）
        // 时避免拼成 /v1/v1 导致 HTTP 404
        if (base.endsWith("/$path") || base == path) return base
        return "$base/$path"
    }

    fun providerType(): ModelProviderType =
        runCatching { ModelProviderType.valueOf(providerTypeName) }
            .getOrDefault(ModelProviderType.OPENAI_CHAT_COMPLETION)

    fun isConfigured(): Boolean =
        baseUrl.isNotBlank() && apiKey.isNotBlank() && modelId.isNotBlank()
}
