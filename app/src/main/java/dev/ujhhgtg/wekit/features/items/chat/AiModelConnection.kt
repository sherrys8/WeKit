package dev.ujhhgtg.wekit.features.items.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/** AI 配置连接测试与模型列表获取（Hchat 第 2456-2660 行对应实现）。 */
internal object AiModelConnection {

    /** 测试连接：POST {base}/chat/completions，HTTP 200 且响应含「OK」才算成功。 */
    suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val base = AiModelConfig.resolvedBaseUrl().trimEnd('/')
            check(base.isNotEmpty()) { "未配置 API 地址" }
            check(AiModelConfig.apiKey.isNotBlank()) { "未配置 API Key" }
            check(AiModelConfig.modelId.isNotBlank()) { "未配置模型名称" }

            // 与 OpenAiChatCompletionsClient 一致：apiPath 已含完整端点时不再重复拼接
            val endpoint = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"

            val body = JSONObject()
                .put("model", AiModelConfig.modelId.trim())
                .put("stream", false)
                .put(
                    "messages",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", "仅回复 OK"),
                    ),
                )
                .toString()

            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer ${AiModelConfig.apiKey.trim()}")
                doOutput = true
            }
            try {
                conn.outputStream.use { out: OutputStream ->
                    out.write(body.toByteArray(Charsets.UTF_8))
                }
                val code = conn.responseCode
                val content = if (code in 200..299) {
                    conn.inputStream.use { readAll(it) }
                } else {
                    (conn.errorStream?.use { readAll(it) })?.ifBlank { "HTTP $code" } ?: "HTTP $code"
                }
                check(code in 200..299) { content.ifBlank { "HTTP $code" } }
                check(content.contains("OK")) { "响应异常：${content.take(120)}" }
                "OK"
            } finally {
                conn.disconnect()
            }
        }
    }

    /** 获取模型列表：GET {base}/models，从 data[].id 提取。 */
    suspend fun fetchModels(): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val base = AiModelConfig.resolvedBaseUrl().trimEnd('/')
            check(base.isNotEmpty()) { "未配置 API 地址" }
            check(AiModelConfig.apiKey.isNotBlank()) { "未配置 API Key" }

            val endpoint = when {
                base.endsWith("/chat/completions") -> base.removeSuffix("/chat/completions").trimEnd('/') + "/models"
                else -> "$base/models"
            }

            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer ${AiModelConfig.apiKey.trim()}")
            }
            try {
                val code = conn.responseCode
                val content = if (code in 200..299) {
                    conn.inputStream.use { readAll(it) }
                } else {
                    (conn.errorStream?.use { readAll(it) })?.ifBlank { "HTTP $code" } ?: "HTTP $code"
                }
                check(code in 200..299) { content.ifBlank { "HTTP $code" } }
                val json = JSONObject(content)
                val data = json.optJSONArray("data") ?: throw IllegalStateException("响应缺少 data 字段")
                val ids = mutableListOf<String>()
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    item.optString("id").takeIf { it.isNotBlank() }?.let { ids.add(it) }
                }
                check(ids.isNotEmpty()) { "模型列表为空" }
                ids
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun readAll(input: InputStream): String =
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
}
