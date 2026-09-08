package dev.ujhhgtg.wekit.features.items.chat_input_bar_menu

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Chevron_right
import com.composables.icons.materialsymbols.outlined.Keyboard_arrow_down
import com.composables.icons.materialsymbols.outlined.Keyboard_arrow_up
import com.composables.icons.materialsymbols.outlined.Video_file
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.ui.WeChatInputBarMenuApi
import dev.ujhhgtg.wekit.features.api.ui.WeCurrentConversationApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.ContactsSelector
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.readTextFromClipboard
import dev.ujhhgtg.wekit.utils.android.showToast
import kotlin.io.path.absolutePathString
import kotlin.io.path.div
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Collections
import java.util.LinkedHashSet
import java.util.UUID
import java.util.concurrent.TimeUnit

object ParseVideo : ClickableFeature() {

    override val technicalId = "短视频解析"
    override val nameRes = R.string.feature_parse_video_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_parse_video_description

    private const val TAG = "ParseVideo"

    /** 主解析线路：dy.51web.eu.org（抖音多清晰度无水印，token=dyyy）。 */
    private const val PARSE_API_PRIMARY = "https://dy.51web.eu.org/api/parse"
    private const val PARSE_API_PRIMARY_TOKEN = "dyyy"

    /** 备用解析线路：kit9 聚合解析（主线路失败时自动切换）。 */
    private const val PARSE_API = "https://apis.kit9.cn/api/aggregate_videos/api.php"

    /** 小红书解析线路：dovis（识别到小红书链接时直接使用，不经前两条线路）。 */
    private const val PARSE_API_XHS = "http://api.dovis.work/api/xhs.php?url="
    private const val DEFAULT_BUFFER_SIZE = 8192

    private val urlRegex = Regex("""https?://[\w\-._~:/?#\[\]@!$&'()*+,;=%]+""")

    /** 抖音分享链接（v.douyin.com 短链 / www.douyin.com / iesdouyin 等）。 */
    private val douyinUrlRegex = Regex(
        """https?://(?:[\w\-.]*douyin\.com|v\.douyin\.com|iesdouyin\.com)/[\w\-._~:/?#\[\]@!$&'()*+,;=%]+""",
        RegexOption.IGNORE_CASE,
    )

    /** 小红书分享链接（xiaohongshu.com / xhslink.com / xhslink.cn 短链）。 */
    private val xhsUrlRegex = Regex(
        """https?://(?:[\w\-.]*xiaohongshu\.com|xhslink\.com|xhslink\.cn)/[\w\-._~:/?#\[\]@!$&'()*+,;=%]+""",
        RegexOption.IGNORE_CASE,
    )

    private var saveDir by prefOption("parse_video_save_dir", "")
    private var autoReply by prefOption("parse_video_auto_reply", false)

    /** 自动解析白名单：空 = 所有群聊生效；非空 = 仅选中的会话（群聊或私聊用户）生效。 */
    private var autoReplyWhitelist by prefOption("parse_video_whitelist", emptySet<String>())

    private fun defaultSaveDir(): String =
        (KnownPaths.downloads / "ParseVideo").absolutePathString()

    private fun currentSaveDir(): String =
        saveDir.ifBlank { defaultSaveDir() }

    private fun ensureSaveDir(): java.io.File =
        java.io.File(currentSaveDir()).apply { mkdirs() }

    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val provider = WeChatInputBarMenuApi.IActionItemsProvider {
        listOf(
            WeChatInputBarMenuApi.ActionItem(
                id = "parse_video",
                icon = MaterialSymbols.Outlined.Video_file,
                label = localizedChatInputString(R.string.feature_parse_video_name),
                onClick = { context, _ ->
                    showParseDialog(context)
                }
            )
        )
    }

    override fun onEnable() {
        WeChatInputBarMenuApi.addProvider(provider)
        WeMessageApi.methodMsgInfoHandleApiInsertMessage.hookBefore {
            if (!autoReply) return@hookBefore
            val msgInfo = MessageInfo(args[0]!!)
            handleAutoReply(msgInfo)
        }
    }

    override fun onDisable() {
        WeChatInputBarMenuApi.removeProvider(provider)
    }

    override fun onClick(context: androidx.activity.ComponentActivity) {
        showComposeDialog(context) {
            var autoReplyChecked by remember { mutableStateOf(autoReply) }
            var whitelistRevision by remember { mutableIntStateOf(0) }
            val whitelistCount = remember(whitelistRevision) { autoReplyWhitelist.size }
            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_parse_video_name)) },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        SwitchWidget(
                            title = stringResource(R.string.parse_video_auto_reply),
                            description = stringResource(R.string.parse_video_auto_reply_description),
                            checked = autoReplyChecked,
                            onCheckedChange = {
                                autoReplyChecked = it
                                autoReply = it
                            },
                        )
                        SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                            item {
                                BaseWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.parse_video_whitelist_conversations),
                                    description = if (whitelistCount == 0) {
                                        stringResource(R.string.parse_video_whitelist_empty)
                                    } else {
                                        pluralStringResource(
                                            R.plurals.parse_video_whitelist_count,
                                            whitelistCount,
                                            whitelistCount,
                                        )
                                    },
                                    onClick = { showWhitelistSelector(context) { whitelistRevision++ } },
                                    trailingContent = {
                                        Icon(
                                            MaterialSymbols.Outlined.Chevron_right,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                )
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.dialog_close))
                    }
                },
            )
        }
    }

    /** 白名单多选：好友 + 群聊一起列出，选中即生效（空集 = 全部群聊生效）。 */
    private fun showWhitelistSelector(context: android.content.Context, onUpdated: () -> Unit) {
        val contacts = runCatching {
            WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()
        }.getOrElse {
            WeLogger.e(TAG, "failed to load contacts for whitelist", it)
            emptyList()
        }
        showComposeDialog(context) {
            ContactsSelector(
                title = stringResource(R.string.parse_video_select_whitelist),
                contacts = contacts,
                initialSelectedWxIds = autoReplyWhitelist,
                onDismiss = onDismiss,
            ) { selected ->
                autoReplyWhitelist = selected
                onUpdated()
                onDismiss()
            }
        }
    }

    // ==================== 数据模型 ====================

    @Serializable
    private data class VideoParseResult(
        val code: Int,
        val msg: String,
        val data: JsonElement?,
        /** 主线路返回的多清晰度列表（备用线路为空）；(label, url) 按清晰度降序。 */
        val qualityList: List<Pair<String, String>> = emptyList(),
        /** 图集（图片列表，无视频时非空）。 */
        val imageList: List<String> = emptyList(),
    ) {
        /** 把 data(JsonElement) 解析成 VideoData 对象，兼容 data 为字符串(错误信息)的情况 */
        fun parsedData(): VideoData? {
            if (code != 200) return null
            val element = data ?: return null
            if (element !is kotlinx.serialization.json.JsonObject) return null
            return runCatching {
                json.decodeFromString<VideoData>(element.toString())
            }.getOrNull()
        }
    }

    @Serializable
    private data class VideoData(
        val video_id: String = "",
        val video_title: String = "",
        val video_time: Long = 0,
        val video_cover: String = "",
        val video_desc: String = "",
        val video_word: String = "",
        val video_link: String = "",
        val author: AuthorData? = null,
    )

    @Serializable
    private data class AuthorData(
        val user_id: String = "",
        val name: String = "",
        val avatar: String = "",
    )

    // ==================== 主线路（dy.51web.eu.org）数据模型 ====================

    /** 51web 解析响应：{code, msg, data:{title, video_list:[{url,level,isDisclaimer}], cover, images}}（music 字段已不使用，靠 ignoreUnknownKeys 忽略） */
    @Serializable
    private data class PrimaryParseResult(
        val code: Int = 0,
        val msg: String = "",
        val data: PrimaryParseData? = null,
    )

    @Serializable
    private data class PrimaryParseData(
        val title: String = "",
        val cover: String = "",
        val video_list: List<PrimaryVideoEntry> = emptyList(),
        val images: List<String> = emptyList(),
    )

    @Serializable
    private data class PrimaryVideoEntry(
        val url: String = "",
        val level: String = "",
        val isDisclaimer: Boolean = false,
    )

    // ==================== 小红书线路（dovis）数据模型 ====================

    /**
     * dovis 小红书解析响应：{code, msg, data:{author, authorID, title, desc, avatar, cover, url, imgurl}}。
     * 视频笔记：直链在 `url`；图集笔记：`url` 缺省，图片直链列表在 `imgurl`。
     */
    @Serializable
    private data class XhsParseResult(
        val code: Int = 0,
        val msg: String = "",
        val data: XhsParseData? = null,
    )

    @Serializable
    private data class XhsParseData(
        val author: String? = null,
        val title: String? = null,
        val desc: String? = null,
        val avatar: String? = null,
        val cover: String? = null,
        val url: String? = null,
        val imgurl: List<String> = emptyList(),
    )

    // ==================== 解析 + 下载 + 发送 ====================

    private val webUserAgent =
        "Mozilla/5.0 (Linux; Android 12; Pixel 5 Build/SQ3A.220705.003) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

    /**
     * 抖音等平台源站做了防盗链 (Referer) 校验, 缺头会返回 403。
     * 按视频直链所在域名推导对应的 Referer; 返回 null 时不注入 (走默认)。
     */
    private fun refererFor(url: String): String? = when {
        url.contains("douyinvod") || url.contains("douyin") || url.contains("iesdouyin") ->
            "https://www.douyin.com/"
        url.contains("upos-sz") || url.contains("bilivideo") || url.contains("hdslb.com") ->
            "https://www.bilibili.com/"
        url.contains("kuaishou") || url.contains("yjsub") ->
            "https://www.kuaishou.com/"
        url.contains("ixigua") ->
            "https://www.ixigua.com/"
        url.contains("qq.com") ->
            "https://weishi.qq.com/"
        else -> null
    }

    private fun buildRequest(url: String): Request {
        val builder = Request.Builder().url(url).get()
            .header("User-Agent", webUserAgent)
        refererFor(url)?.let { builder.header("Referer", it) }
        return builder.build()
    }

    private fun parseVideo(link: String): Result<VideoParseResult> = runCatching {
        // 小红书链接（xiaohongshu.com / xhslink 短链）直接走 dovis 小红书线路：
        // 前两条线路不支持小红书，逐级失败回退只会白等两轮超时
        if (xhsUrlRegex.containsMatchIn(link)) {
            return@runCatching parseByXhs(link).getOrElse { throw it }
        }
        // 主线路优先：dy.51web.eu.org（多清晰度无水印）；失败/无有效地址自动回退 kit9 聚合解析
        parseByPrimary(link).getOrElse { primaryError ->
            WeLogger.w(TAG, "primary parse failed, fallback to backup: ${primaryError.message}")
            parseByBackup(link).getOrElse { backupError ->
                WeLogger.w(TAG, "backup parse also failed: ${backupError.message}")
                throw backupError
            }
        }
    }

    /** 主线路：dy.51web.eu.org。结果映射成统一的 VideoParseResult 供 UI 层无感消费。 */
    private fun parseByPrimary(link: String): Result<VideoParseResult> = runCatching {
        val url = PARSE_API_PRIMARY +
            "?token=" + java.net.URLEncoder.encode(PARSE_API_PRIMARY_TOKEN, "UTF-8") +
            "&url=" + java.net.URLEncoder.encode(link, "UTF-8")
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("主线路请求失败: HTTP ${resp.code}")
            val body = resp.body?.string() ?: error("主线路响应为空")
            val result = json.decodeFromString<PrimaryParseResult>(body)
            require(result.code == 200) { result.msg.ifBlank { "主线路解析失败 (code=${result.code})" } }
            val data = result.data ?: error("主线路返回数据为空")
            // 图集（slides）帖子：images 非空时以图片为主，video_list 里可能是幻灯片合成视频（可空）
            val images = data.images.filter { it.startsWith("http") }
            val videoEntries = data.video_list.filter { !it.isDisclaimer && it.url.startsWith("http") }
            val videoUrl = videoEntries.firstOrNull()?.url
            if (videoUrl == null && images.isEmpty()) error("主线路未返回可用视频地址")
            WeLogger.i(TAG, "primary parse ok, levels=${data.video_list.map { it.level }}, images=${images.size}")
            val qualities = videoEntries
                .map { (it.level.ifBlank { "视频" }) to it.url }
            VideoParseResult(
                code = 200,
                msg = "success",
                data = json.parseToJsonElement(
                    json.encodeToString(
                        VideoData(
                            video_title = data.title,
                            video_cover = data.cover,
                            video_link = videoUrl ?: "",
                        ),
                    ),
                ),
                qualityList = qualities,
                imageList = images,
            )
        }
    }

    /** 备用线路：kit9 聚合解析。图集时 video_link 为 [{type:"image",url}] 数组，需归一化。 */
    private fun parseByBackup(link: String): Result<VideoParseResult> = runCatching {
        val url = PARSE_API + "?link=" + java.net.URLEncoder.encode(link, "UTF-8")
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("请求失败: HTTP ${resp.code}")
            val body = resp.body?.string() ?: error("响应为空")
            val result = json.decodeFromString<VideoParseResult>(body)
            if (result.code != 200) return@runCatching result
            val dataElement = result.data ?: return@runCatching result
            if (dataElement !is JsonObject) return@runCatching result
            val rawVideoLink = dataElement["video_link"]
            // 归一化：数组形式（图集/多地址）→ 取出每个 url；字符串形式 → 不动
            val normalized = when (rawVideoLink) {
                is JsonArray -> {
                    val urls = rawVideoLink.mapNotNull { entry ->
                        (entry as? JsonObject)?.get("url")?.let { (it as? JsonPrimitive)?.content }
                    }.filter { it.startsWith("http") }
                    val isImageGallery = urls.isNotEmpty() &&
                        rawVideoLink.all { (it as? JsonObject)?.get("type")?.let { t -> (t as? JsonPrimitive)?.content } == "image" }
                    VideoData(
                        video_title = (dataElement["video_title"] as? JsonPrimitive)?.content ?: "",
                        video_cover = (dataElement["video_cover"] as? JsonPrimitive)?.content ?: "",
                        video_link = if (isImageGallery) "" else urls.firstOrNull() ?: "",
                        author = (dataElement["author"] as? JsonObject)?.let {
                            json.decodeFromJsonElement(AuthorData.serializer(), it)
                        },
                    )
                }
                else -> json.decodeFromJsonElement(VideoData.serializer(), dataElement)
            }
            val galleryImages = if (normalized.video_link.isBlank()) {
                // 图集地址优先取 video_link 数组里的 url；kit9 部分响应也提供 image 字段（字符串或数组）作兜底
                val fromVideoLink = (rawVideoLink as? JsonArray)?.mapNotNull { entry ->
                    (entry as? JsonObject)?.get("url")?.let { (it as? JsonPrimitive)?.content }
                }?.filter { it.startsWith("http") } ?: emptyList()
                if (fromVideoLink.isNotEmpty()) fromVideoLink else when (val img = dataElement["image"]) {
                    is JsonPrimitive -> listOfNotNull(img.content.takeIf { it.startsWith("http") })
                    is JsonArray -> img.mapNotNull { entry ->
                        ((entry as? JsonPrimitive)?.content)
                            ?: ((entry as? JsonObject)?.get("url")?.let { (it as? JsonPrimitive)?.content })
                    }.filter { it.startsWith("http") }
                    else -> emptyList()
                }
            } else emptyList()
            VideoParseResult(
                code = result.code,
                msg = result.msg,
                data = json.parseToJsonElement(json.encodeToString(normalized)),
                qualityList = emptyList(),
                imageList = galleryImages,
            )
        }
    }

    /** 小红书解析线路：dovis（http 接口，视频直链无水印；支持 xiaohongshu.com / xhslink 短链）。 */
    private fun parseByXhs(link: String): Result<VideoParseResult> = runCatching {
        val url = PARSE_API_XHS + java.net.URLEncoder.encode(link, "UTF-8")
        // 必须携带浏览器 User-Agent：后端用请求方 UA 抓取小红书页面，默认 okhttp UA 会得到 502
        val request = Request.Builder().url(url).get()
            .header("User-Agent", webUserAgent)
            .build()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("小红书线路请求失败: HTTP ${resp.code}")
            val body = resp.body?.string() ?: error("小红书线路响应为空")
            val result = json.decodeFromString<XhsParseResult>(body)
            require(result.code == 200) { result.msg.ifBlank { "小红书线路解析失败 (code=${result.code})" } }
            val data = result.data ?: error("小红书线路返回数据为空")
            val videoUrl = data.url.orEmpty().takeIf { it.startsWith("http") }
            val images = data.imgurl.filter { it.startsWith("http") }
            if (videoUrl == null && images.isEmpty()) error("小红书线路未返回可用的视频/图片地址")
            WeLogger.i(TAG, "xhs parse ok, video=${videoUrl != null}, images=${images.size}")
            VideoParseResult(
                code = 200,
                msg = "success",
                data = json.parseToJsonElement(
                    json.encodeToString(
                        VideoData(
                            video_title = data.title.orEmpty().ifBlank { data.desc.orEmpty() },
                            video_cover = data.cover.orEmpty(),
                            video_link = videoUrl.orEmpty(),
                            author = data.author.takeIf { !it.isNullOrBlank() }?.let {
                                AuthorData(name = it, avatar = data.avatar.orEmpty())
                            },
                        ),
                    ),
                ),
                qualityList = emptyList(),
                imageList = images,
            )
        }
    }

    /**
     * 从粘贴的分享文案中提取首个 http(s) 链接。
     * 抖音/快手等平台复制出来的是整段分享文案（含口令、表情、说明文字），
     * 直接把整段文本交给解析 API 会因无法识别链接而失败。
     * 链接外层标注的双引号（含中文弯引号）一并去除。
     */
    private fun extractVideoUrl(raw: String): String {
        return urlRegex.find(raw)?.value?.trim('"', '“', '”') ?: ""
    }

    private fun downloadVideo(
        url: String,
        outPath: java.io.File,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<java.io.File> = runCatching {
        val request = buildRequest(url)
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("下载失败: HTTP ${resp.code}")
            val body = resp.body ?: error("下载内容为空")
            val totalBytes = body.contentLength()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            outPath.outputStream().use { output ->
                body.byteStream().use { input ->
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, totalBytes)
                    }
                }
            }
        }
        if (outPath.length() < 1024) error("下载文件过小，可能无效")
        outPath
    }

    // ==================== 群聊抖音链接自动解析回复 ====================

    /** 临时发送用目录 */
    private fun tempSendDir(context: android.content.Context): java.io.File {
        val base = context.cacheDir ?: context.filesDir
        return java.io.File(base, "parse_video_send")
    }

    private val autoReplyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 打开时信号量的灯以串行化下载，避免并发重复拉取 */
    private val autoReplyMutex = Mutex()

    /** 记录最近已处理的 (talker, link 摘要) 防止重复回复 */
    private val autoReplySeen = Collections.synchronizedSet(LinkedHashSet<String>())

    /**
     * 当某条新消息满足条件时自动触发:
     * 会话在白名单中 + 非自己发送 + 文本含抖音分享链接 -> 解析 -> 下载到临时目录 -> 发回该会话。
     * 白名单为空时全部不生效。由 hookBefore 在消息入库前调用, 不阻塞入库流程。
     */
    private fun handleAutoReply(msgInfo: MessageInfo) {
        try {
            // 白名单模式：仅选中的会话（群聊或私聊用户）生效；未选择则全部不生效
            if (autoReplyWhitelist.isEmpty() || msgInfo.talker !in autoReplyWhitelist) return
            if (msgInfo.type?.isText != true) return
            if (msgInfo.isSelfSender) return

            val link = extractDouyinUrl(msgInfo.humanReadableRepr)
            if (link.isEmpty()) return

            val dedupKey = "${msgInfo.talker}|$link"
            if (!autoReplySeen.add(dedupKey)) return

            val talker = msgInfo.talker
            autoReplyScope.launch {
                autoReplyMutex.withLock {
                    doAutoReply(talker, link)
                }
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "handleAutoReply failed", e)
        }
    }

    /** 从消息文本中提取抖音分享链接；非抖音链接返回空串。链接外层标注的双引号一并去除。 */
    private fun extractDouyinUrl(raw: String): String =
        douyinUrlRegex.find(raw)?.value?.trim('"', '“', '”') ?: ""

    /** 真正执行解析 + 下载 + 发送。所有流程在地线程执行, 调用方已持锁。 */
    private suspend fun doAutoReply(talker: String, link: String) {
        val context = HostInfo.application
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val parsed = parseVideo(link).getOrElse { throw it }
                if (parsed.code != 200) {
                    error("parse failed: ${parsed.msg}")
                }
                sendParseResult(talker, parsed, tempSendDir(context))
            }
        }
        if (result.isFailure) {
            WeLogger.e(TAG, "auto reply failed", result.exceptionOrNull() ?: error("auto reply failed"))
        }
    }

    /**
     * 下载并发送一条解析结果：video_link 有值 → 发视频；否则按 imageList 逐张发图片。
     * 返回发送的媒体数量，全部失败抛异常。
     */
    private fun sendParseResult(
        talker: String,
        parsed: VideoParseResult,
        dir: java.io.File,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): Int {
        val data = parsed.parsedData() ?: error("no video data")
        dir.mkdirs()
        var sentCount = 0
        if (data.video_link.isNotBlank()) {
            val out = java.io.File(dir, "auto-${UUID.randomUUID()}.mp4")
            downloadVideo(data.video_link, out, onProgress).getOrElse { throw it }
            val sent = WeMessageApi.sendVideo(talker, out.absolutePath)
            if (!sent) {
                out.delete()
                error("sendVideo failed")
            }
            sentCount = 1
        } else {
            for ((index, imgUrl) in parsed.imageList.withIndex()) {
                val out = java.io.File(dir, "auto-${UUID.randomUUID()}-$index.jpg")
                downloadVideo(imgUrl, out, onProgress).getOrElse { e ->
                    WeLogger.w(TAG, "image $index download failed: ${e.message}")
                    continue
                }
                val sent = WeMessageApi.sendImage(talker, out.absolutePath)
                if (sent) sentCount++ else out.delete()
            }
            if (sentCount == 0) error("no media sent")
        }
        return sentCount
    }

fun showParseDialog(context: android.content.Context) {
        showComposeDialog(context, directlyDismissable = false) {
            var link by remember { mutableStateOf("") }
            var loading by remember { mutableStateOf(false) }
            var errorMsg by remember { mutableStateOf<String?>(null) }
            var parseResult by remember { mutableStateOf<VideoParseResult?>(null) }
            var downloadedFiles by remember { mutableStateOf<List<java.io.File>>(emptyList()) }
            // 多清晰度选择：主线路返回的档位列表 + 当前选中 URL（默认第一档=最高清）
            var qualityList by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
            var selectedQualityUrl by remember { mutableStateOf("") }
            // 主线路直出的封面地址（可一键保存）
            var directCoverUrl by remember { mutableStateOf("") }
            var savingCover by remember { mutableStateOf(false) }
            var downloading by remember { mutableStateOf(false) }
            var downloadProgress by remember { mutableFloatStateOf(0f) }
            var sending by remember { mutableStateOf(false) }
            var pendingSendAfterParse by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            val appContext = LocalContext.current.applicationContext

            // 自动读取剪贴板中的链接
            androidx.compose.runtime.LaunchedEffect(Unit) {
                val clip = readTextFromClipboard(appContext) ?: return@LaunchedEffect
                val extracted = extractVideoUrl(clip)
                if (extracted.isNotEmpty()) {
                    link = extracted
                }
            }

            fun downloadAndSend(result: VideoParseResult) {
                val data = result.parsedData()
                if (data == null || data.video_link.isBlank() && result.imageList.isEmpty()) {
                    pendingSendAfterParse = false
                    errorMsg = localizedChatInputString(R.string.parse_video_api_error)
                    return
                }
                val talker = WeCurrentConversationApi.value
                if (talker.isBlank()) {
                    pendingSendAfterParse = false
                    errorMsg = localizedChatInputString(R.string.parse_video_no_conversation)
                    return
                }
                if (sending) return
                sending = true
                downloadProgress = 0f
                errorMsg = null
                scope.launch {
                    val sendResult = withContext(Dispatchers.IO) {
                        runCatching {
                            val dir = java.io.File(appContext.cacheDir ?: appContext.filesDir, "parse_video_send")
                            sendParseResult(talker, result, dir) { downloaded, total ->
                                if (total > 0 && downloaded > 0) {
                                    downloadProgress = (downloaded.toFloat() / total).coerceIn(0f, 1f)
                                }
                            }
                        }
                    }
                    sending = false
                    sendResult.fold(
                        onSuccess = { count ->
                            showToast(localizedChatInputString(R.string.parse_video_sent))
                            onDismiss()
                        },
                        onFailure = { e ->
                            WeLogger.e(TAG, "send parse result failed", e)
                            errorMsg = localizedChatInputString(R.string.parse_video_send_failed)
                        },
                    )
                }
            }

            fun doParse() {
                val trimmed = extractVideoUrl(link)
                if (trimmed.isEmpty()) {
                    showToast(localizedChatInputString(R.string.parse_video_link_empty))
                    return
                }
                loading = true
                errorMsg = null
                parseResult = null
                downloadedFiles = emptyList()
                qualityList = emptyList()
                selectedQualityUrl = ""
                directCoverUrl = ""
                scope.launch {
                    val parsed = withContext(Dispatchers.IO) { parseVideo(trimmed) }
                    loading = false
                    parsed.fold(
                        onSuccess = { r ->
                            val hasVideo = r.parsedData()?.video_link?.isNotBlank() == true
                            if (r.code != 200 || (!hasVideo && r.imageList.isEmpty())) {
                                pendingSendAfterParse = false
                                errorMsg = r.msg.ifBlank { localizedChatInputString(R.string.parse_video_api_error) }
                                return@fold
                            }
                            parseResult = r
                            // 多清晰度与直出封面（仅主线路携带）
                            qualityList = r.qualityList
                            selectedQualityUrl = r.qualityList.firstOrNull()?.second
                                ?: r.parsedData()?.video_link.orEmpty()
                            runCatching {
                                val d = json.decodeFromString<PrimaryParseData>(
                                    r.data.toString(),
                                )
                                directCoverUrl = d.cover
                            }
                            if (pendingSendAfterParse) {
                                pendingSendAfterParse = false
                                downloadAndSend(r)
                            }
                        },
                        onFailure = { e ->
                            pendingSendAfterParse = false
                            WeLogger.e(TAG, "parse failed", e)
                            errorMsg = e.message ?: localizedChatInputString(R.string.parse_video_api_error)
                        },
                    )
                }
            }

            fun doConvert() {
                pendingSendAfterParse = false
                doParse()
            }

            fun doSendNow() {
                val r = parseResult
                if (r != null) {
                    val data = r.parsedData()
                    if ((data != null && data.video_link.isNotBlank()) || r.imageList.isNotEmpty()) {
                        downloadAndSend(r)
                        return
                    }
                }
                pendingSendAfterParse = true
                doParse()
            }

            fun doDownload() {
                val r = parseResult ?: return
                val data = r.parsedData() ?: return
                val isGallery = data.video_link.isBlank() && r.imageList.isNotEmpty()
                downloading = true
                downloadProgress = 0f
                errorMsg = null
                scope.launch {
                    val saveResult = withContext(Dispatchers.IO) {
                        runCatching {
                            val dir = ensureSaveDir()
                            if (isGallery) {
                                val files = mutableListOf<java.io.File>()
                                r.imageList.forEachIndexed { index, imgUrl ->
                                    val out = java.io.File(dir, "image-${UUID.randomUUID()}-$index.jpg")
                                    downloadVideo(imgUrl, out) { downloaded, total ->
                                        if (total > 0 && downloaded > 0) {
                                            downloadProgress = (downloaded.toFloat() / total).coerceIn(0f, 1f)
                                        }
                                    }.onSuccess { files += it }
                                }
                                if (files.isEmpty()) error("无图片下载成功")
                                files
                            } else {
                                val out = java.io.File(dir, "video-${UUID.randomUUID()}.mp4")
                                downloadVideo(data.video_link, out) { downloaded, total ->
                                    if (total > 0 && downloaded > 0) {
                                        downloadProgress = (downloaded.toFloat() / total).coerceIn(0f, 1f)
                                    }
                                }.getOrThrow()
                                listOf(out)
                            }
                        }
                    }
                    downloading = false
                    saveResult.fold(
                        onSuccess = { files ->
                            downloadedFiles = files
                            showToast(localizedChatInputString(R.string.parse_video_downloaded))
                        },
                        onFailure = { e ->
                            WeLogger.e(TAG, "download failed", e)
                            errorMsg = localizedChatInputString(R.string.parse_video_download_failed, e.message.orEmpty())
                        },
                    )
                }
            }

            fun sendDownloadedFiles() {
                val r = parseResult ?: return
                if (downloadedFiles.isEmpty()) return
                val talker = WeCurrentConversationApi.value
                if (talker.isBlank()) {
                    errorMsg = localizedChatInputString(R.string.parse_video_no_conversation)
                    return
                }
                scope.launch {
                    val sent = withContext(Dispatchers.IO) {
                        downloadedFiles.all { file ->
                            if (file.name.startsWith("image-")) {
                                WeMessageApi.sendImage(talker, file.absolutePath)
                            } else {
                                WeMessageApi.sendVideo(talker, file.absolutePath)
                            }
                        }
                    }
                    if (sent) {
                        showToast(localizedChatInputString(R.string.parse_video_sent))
                        onDismiss()
                    } else {
                        errorMsg = localizedChatInputString(R.string.parse_video_send_failed)
                    }
                }
            }

            fun deleteDownloadedFile() {
                downloadedFiles.forEach { file ->
                    runCatching { file.delete() }
                }
                downloadedFiles = emptyList()
                showToast(localizedChatInputString(R.string.parse_video_deleted))
            }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_parse_video_name)) },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        OutlinedTextField(
                            value = link,
                            onValueChange = { link = it },
                            enabled = !loading,
                            label = { Text(stringResource(R.string.parse_video_link_hint)) },
                            placeholder = {
                                Text(stringResource(R.string.parse_video_link_placeholder))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 4,
                        )

                        Spacer(Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = { doConvert() },
                                modifier = Modifier.weight(1f),
                                enabled = !loading && !downloading && !sending,
                            ) {
                                Text(stringResource(R.string.parse_video_convert))
                            }
                            Button(
                                onClick = { doSendNow() },
                                modifier = Modifier.weight(1f),
                                enabled = !loading && !downloading && !sending,
                            ) {
                                Text(stringResource(R.string.parse_video_send))
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        if (loading) {
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(end = 12.dp),
                                    strokeWidth = 3.dp,
                                )
                                Text(stringResource(R.string.parse_video_loading))
                            }
                        }

                        errorMsg?.let { err ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = err,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }

                        // ===== 解析结果卡片（封面 + 信息 + 清晰度 + 保存封面） =====
                        parseResult?.let { r ->
                            val data = r.parsedData() ?: return@let
                            Spacer(Modifier.height(8.dp))
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.Top) {
                                        if (directCoverUrl.isNotBlank()) {
                                            AsyncImage(
                                                model = directCoverUrl,
                                                contentDescription = null,
                                                modifier = Modifier
                                                    .size(width = 108.dp, height = 144.dp)
                                                    .clip(RoundedCornerShape(10.dp)),
                                                contentScale = ContentScale.Crop,
                                            )
                                            Spacer(Modifier.width(12.dp))
                                        }
                                        Column(Modifier.weight(1f)) {
                                            if (data.video_title.isNotBlank()) {
                                                Text(
                                                    text = data.video_title,
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 4,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            data.author?.let { author ->
                                                Spacer(Modifier.height(4.dp))
                                                Text(
                                                    text = author.name,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            if (qualityList.size > 1) {
                                                Spacer(Modifier.height(8.dp))
                                                var expanded by remember { mutableStateOf(false) }
                                                val selectedLabel = qualityList
                                                    .firstOrNull { it.second == selectedQualityUrl }?.first
                                                    ?: qualityList.firstOrNull()?.first
                                                    ?: "选择清晰度"
                                                Box {
                                                    OutlinedTextField(
                                                        value = selectedLabel,
                                                        onValueChange = {},
                                                        readOnly = true,
                                                        label = { Text(stringResource(R.string.parse_video_quality_label)) },
                                                        trailingIcon = {
                                                            IconButton(onClick = { expanded = !expanded }) {
                                                                Icon(
                                                                    if (expanded) MaterialSymbols.Outlined.Keyboard_arrow_up
                                                                    else MaterialSymbols.Outlined.Keyboard_arrow_down,
                                                                    null,
                                                                )
                                                            }
                                                        },
                                                        modifier = Modifier.fillMaxWidth(),
                                                    )
                                                    DropdownMenu(
                                                        expanded = expanded,
                                                        onDismissRequest = { expanded = false },
                                                    ) {
                                                        qualityList.forEach { (label, url) ->
                                                            DropdownMenuItem(
                                                                text = { Text(label) },
                                                                onClick = {
                                                                    selectedQualityUrl = url
                                                                    expanded = false
                                                                },
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    Spacer(Modifier.height(10.dp))

                                    if (directCoverUrl.isNotBlank()) {
                                        OutlinedButton(
                                            onClick = {
                                                savingCover = true
                                                scope.launch {
                                                    val result = withContext(Dispatchers.IO) {
                                                        runCatching {
                                                            val dir = ensureSaveDir()
                                                            val ext = if (directCoverUrl.contains(".png")) "png" else "jpg"
                                                            val out = java.io.File(dir, "cover-${UUID.randomUUID()}.$ext")
                                                            val req = Request.Builder().url(directCoverUrl).get().build()
                                                            httpClient.newCall(req).execute().use { resp ->
                                                                require(resp.isSuccessful) { "HTTP ${resp.code}" }
                                                                resp.body.byteStream().use { ins ->
                                                                    out.outputStream().use { ins.copyTo(it) }
                                                                }
                                                            }
                                                            out
                                                        }
                                                    }
                                                    savingCover = false
                                                    result.fold(
                                                        onSuccess = { file ->
                                                            showToast(
                                                                localizedChatInputString(R.string.parse_video_cover_saved) +
                                                                    " (${"%.1f".format(file.length() / 1024.0)}KB)",
                                                            )
                                                        },
                                                        onFailure = { e ->
                                                            WeLogger.e(TAG, "save cover failed", e)
                                                            errorMsg = e.message ?: "保存封面失败"
                                                        },
                                                    )
                                                }
                                            },
                                            enabled = !savingCover,
                                        ) {
                                            Text(
                                                if (savingCover) stringResource(R.string.parse_video_saving_cover)
                                                else stringResource(R.string.parse_video_save_cover),
                                            )
                                        }
                                        Spacer(Modifier.height(8.dp))
                                    }

                                    // ===== 图集预览（横滑缩略图） =====
                                    if (r.imageList.isNotEmpty()) {
                                        Spacer(Modifier.height(8.dp))
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                        ) {
                                            r.imageList.forEachIndexed { index, imgUrl ->
                                                Column {
                                                    AsyncImage(
                                                        model = imgUrl,
                                                        contentDescription = "$index",
                                                        modifier = Modifier
                                                            .size(width = 96.dp, height = 128.dp)
                                                            .clip(RoundedCornerShape(8.dp)),
                                                        contentScale = ContentScale.Crop,
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = localizedChatInputString(R.string.parse_video_gallery_detected, r.imageList.size),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }

                                Spacer(Modifier.height(8.dp))

                                // ===== 下载状态 =====

                                when {
                                    downloading || sending -> {
                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            val percent = (downloadProgress * 100).toInt()
                                            LinearProgressIndicator(
                                                progress = { downloadProgress.coerceIn(0f, 1f) },
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                text = if (sending) {
                                                    localizedChatInputString(R.string.parse_video_sending)
                                                } else if (percent >= 100) {
                                                    localizedChatInputString(R.string.parse_video_downloading)
                                                } else {
                                                    localizedChatInputString(R.string.parse_video_downloading) +
                                                        " $percent%"
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    }
                                    downloadedFiles.isNotEmpty() -> {
                                        val totalSize = downloadedFiles.sumOf { it.length() }
                                        Text(
                                            text = buildString {
                                                append(localizedChatInputString(R.string.parse_video_downloaded))
                                                if (downloadedFiles.size > 1) {
                                                    append(" ×")
                                                    append(downloadedFiles.size)
                                                }
                                                append(" (")
                                                append("%.1f".format(totalSize / 1024.0 / 1024.0))
                                                append("MB)")
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss, enabled = !loading && !sending) {
                        Text(stringResource(R.string.dialog_cancel))
                    }
                },
                confirmButton = {
                    // ===== 按钮组 =====
                    val r = parseResult
                    val data = r?.parsedData()
                    val hasVideo = data?.video_link?.isNotBlank() == true
                    if (r != null && downloadedFiles.isEmpty()) {
                        Column(horizontalAlignment = Alignment.End) {
                            if (hasVideo) {
                                Button(
                                    onClick = { doDownload() },
                                    enabled = !downloading && !sending,
                                ) {
                                    Text(stringResource(R.string.parse_video_download))
                                }
                            }
                            if (r.imageList.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Button(
                                    onClick = { doDownload() },
                                    enabled = !downloading && !sending,
                                ) {
                                    Text(
                                        if (downloading) stringResource(R.string.parse_video_downloading)
                                        else stringResource(R.string.parse_video_download_images),
                                    )
                                }
                            }
                        }
                    } else if (r != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = { sendDownloadedFiles() },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.parse_video_send_files))
                            }
                            OutlinedButton(
                                onClick = { deleteDownloadedFile() },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    stringResource(R.string.parse_video_delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                },
            )
        }
    }
}
