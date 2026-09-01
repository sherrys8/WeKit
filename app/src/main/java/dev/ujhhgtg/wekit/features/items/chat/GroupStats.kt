package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.core.models.WeMessage
import dev.ujhhgtg.wekit.preferences.WePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/** 核心指标：今日发言人数 / 今日消息数 / 历史总消息 */
internal data class GroupCoreMetrics(
    val todaySpeakers: Int,
    val todayMessages: Int,
    val historyTotal: Int,
)

/** 单个成员的发言统计（含主发消息类型，供文本报告「用户画像」使用） */
internal data class SenderStat(val name: String, val count: Int, val mainType: String)

/** 群聊统计结果（结构化，供可视化卡片与文本报告共用） */
internal data class GroupStats(
    val periodStart: Long,
    val periodEnd: Long,
    val totalMessages: Int,
    val speakerCount: Int,
    val textCount: Int,
    val senders: List<SenderStat>,
    val hourly: List<Int>,
    val carriers: Map<String, Int>,
    val words: Map<String, Int>,
    val laughCount: Int,
    val exclamationCount: Int,
    val questionCount: Int,
    val tildeCount: Int,
    val speechlessCount: Int,
    val lengthDist: List<Int>,
)

/**
 * 群聊分析时间范围（对应 Hchat 的 days 滑窗：今日/昨日/本周/上周/本月/上月/全部
 * = 最近 1/2/7/14/30/60 天 + 全部历史，dayCount = 0 表示全部）。
 */
internal enum class GroupTimeRange(val labelRes: Int, val dayCount: Int) {
    TODAY(R.string.ui_group_range_today, 1),
    YESTERDAY(R.string.ui_group_range_yesterday, 2),
    THIS_WEEK(R.string.ui_group_range_this_week, 7),
    LAST_WEEK(R.string.ui_group_range_last_week, 14),
    THIS_MONTH(R.string.ui_group_range_this_month, 30),
    LAST_MONTH(R.string.ui_group_range_last_month, 60),
    ALL(R.string.ui_group_range_all, 0),
}

/** AI 上下文容量档位（token） */
internal enum class ModelCapacity(val tokens: Long, val label: String) {
    K128(128 * 1024L, "128K"),
    K256(256 * 1024L, "256K"),
    K512(512 * 1024L, "512K"),
    M1(1024 * 1024L, "1M"),
    M2(2048 * 1024L, "2M"),
}

/** AI 容量档位对应的自动提取消息条数上限（Hchat aiAutoMessageLimit） */
internal fun ModelCapacity.autoMessageLimit(): Int = when (this) {
    ModelCapacity.K128 -> 3000
    ModelCapacity.K256 -> 6000
    ModelCapacity.K512 -> 12000
    ModelCapacity.M1 -> 25000
    ModelCapacity.M2 -> 50000
}

/** 深度分析采样与词云设置（对应 Hchat ana_sample_limit / ana_word_count / ana_min_len） */
internal object GroupAnalyzePrefs {
    var sampleLimit by WePrefs.prefOption("ana_sample_limit", 500)
    var wordCount by WePrefs.prefOption("ana_word_count", 40)
    var minWordLength by WePrefs.prefOption("ana_min_len", 2)

    fun reportSampleLimit(): Int = sampleLimit.coerceIn(100, 50_000)
    fun reportWordCount(): Int = wordCount.coerceIn(10, 80)
    fun reportMinWordLength(): Int = minWordLength.coerceIn(2, 10)
}

/** 计算时间段 [start, end]（毫秒时间戳，与微信 message.createTime 单位一致） */
internal fun groupRangeStartEnd(range: GroupTimeRange): Pair<Long, Long> {
    val now = System.currentTimeMillis()
    if (range == GroupTimeRange.ALL) return 0L to now
    val start = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, -Math.max(0, range.dayCount - 1))
    }
    return start.timeInMillis to now
}

private val groupSenderRegex = Regex("""^([^\n:]+):\n(.+)""", setOf(RegexOption.DOT_MATCHES_ALL))

internal fun extractSenderId(msg: WeMessage, membersMap: Map<String, String>): String {
    if (msg.isSend != 0) return "我"
    // 发送者 wxid 必须是群内真实成员，避免特殊消息格式导致误切分
    val match = groupSenderRegex.find(msg.content)
    val rawSender = match?.groupValues?.get(1) ?: return "<未知>"
    if (membersMap.containsKey(rawSender)) return rawSender
    // 微信部分消息中发送者可能带后缀（如 xxxx:xxx）或被截断，尝试模糊匹配已知成员
    return membersMap.keys.firstOrNull { rawSender.startsWith(it) } ?: rawSender
}

internal fun resolveSenderName(senderId: String, membersMap: Map<String, String>): String =
    membersMap[senderId] ?: senderId

internal fun extractTextContent(msg: WeMessage, membersMap: Map<String, String>): String {
    if (msg.isSend != 0) return msg.content
    val match = groupSenderRegex.find(msg.content)
    return match?.groupValues?.get(2) ?: msg.content
}

private const val WORD_STOP_WORDS =
    "我们你们他们这个那个什么怎么可以就是不是没有一个现在然后因为所以已经还是感觉知道真哈哈呵呵好的收到表情图片视频语音消息"

private fun <K> MutableMap<K, Int>.mergeCount(key: K, value: Int, op: (Int, Int) -> Int) {
    this[key] = op(this.getOrDefault(key, 0), value)
}

/** 消息载体分类（对应 Hchat carrierName） */
internal fun carrierName(rawCode: Int): String = when (rawCode) {
    1 -> "文本"
    3 -> "图片"
    34 -> "语音"
    43, 62 -> "视频"
    47 -> "表情包"
    48 -> "位置"
    49 -> "卡片/文件"
    10000 -> "系统消息"
    else -> "其他"
}

/** 文本清洗（对应 Hchat cleanMessageText）：截断、剥离发送者前缀与 XML 标签 */
internal fun cleanMessageText(raw: String): String {
    var value = raw
    if (value.length > 2000) value = value.substring(0, 2000)
    val split = value.indexOf(":\n")
    if (split > 0 && split < 80) value = value.substring(split + 2)
    value = value.replace(Regex("<[^>]+>"), " ")
    value = value.trim()
    return if (value.length > 600) value.substring(0, 600) else value
}

internal fun computeGroupStats(messages: List<WeMessage>, membersMap: Map<String, String>): GroupStats {
    val totalCount = messages.size

    val carriers = LinkedHashMap<String, Int>()
    val senderCounts = mutableMapOf<String, MutableList<WeMessage>>()
    val hourly = MutableList(24) { 0 }
    var laughCount = 0
    var questionCount = 0
    var exclamationCount = 0
    var tildeCount = 0
    var speechlessCount = 0
    var textCount = 0
    val lengthDist = MutableList(4) { 0 }
    val words = HashMap<String, Int>()
    val minWordLength = GroupAnalyzePrefs.reportMinWordLength()

    val cal = Calendar.getInstance()
    for (msg in messages) {
        carriers.mergeCount(carrierName(msg.typeCode), 1, Int::plus)

        val senderId = extractSenderId(msg, membersMap)
        senderCounts.getOrPut(senderId) { mutableListOf() }.add(msg)

        cal.timeInMillis = msg.createTime
        hourly[cal.get(Calendar.HOUR_OF_DAY)]++

        if (msg.typeCode != 1) continue
        val textContent = cleanMessageText(extractTextContent(msg, membersMap))
        if (textContent.isEmpty()) continue
        textCount++

        // 废话长度：去除空白后计数（对应 Hchat length）
        val length = textContent.replace(Regex("\\s+"), "").length
        when {
            length <= 5 -> lengthDist[0]++
            length <= 20 -> lengthDist[1]++
            length <= 50 -> lengthDist[2]++
            else -> lengthDist[3]++
        }

        // 情绪指纹（对应 Hchat analyzeDeepRows）
        if (textContent.indexOf("哈") >= 0 || textContent.indexOf("笑") >= 0) laughCount++
        if (textContent.indexOf("?") >= 0 || textContent.indexOf("？") >= 0 || textContent.endsWith("吗")) questionCount++
        if (textContent.indexOf("!") >= 0 || textContent.indexOf("！") >= 0) exclamationCount++
        if (textContent.contains("~") || textContent.contains("～")) tildeCount++
        if (textContent.contains("无语") || textContent.contains("...") || textContent.contains("。。。")) speechlessCount++

        // 高频语义词频（对应 Hchat 1383-1397）
        val normalized = textContent.replace(Regex("[^一-龥]+"), " ")
        for (piece in normalized.split(Regex("\\s+"))) {
            val word = piece.trim()
            if (word.length < minWordLength) continue
            if (word.length <= 8) {
                words.mergeCount(word, 1, Int::plus)
            } else {
                for (w in 0..(word.length - minWordLength)) {
                    val part = word.substring(w, w + minWordLength)
                    if (minWordLength == 2 && WORD_STOP_WORDS.contains(part)) continue
                    words.mergeCount(part, 1, Int::plus)
                }
            }
        }
    }

    val senders = senderCounts.entries
        .sortedByDescending { it.value.size }
        .take(10)
        .map { (senderId, msgs) ->
            val mainType = msgs.groupBy { m -> carrierName(m.typeCode) }
                .maxByOrNull { it.value.size }?.key ?: "文本"
            SenderStat(resolveSenderName(senderId, membersMap), msgs.size, mainType)
        }

    return GroupStats(
        periodStart = messages.firstOrNull()?.createTime ?: 0,
        periodEnd = messages.lastOrNull()?.createTime ?: 0,
        totalMessages = totalCount,
        speakerCount = senderCounts.size,
        textCount = textCount,
        senders = senders,
        hourly = hourly,
        carriers = carriers,
        words = words,
        laughCount = laughCount,
        exclamationCount = exclamationCount,
        questionCount = questionCount,
        tildeCount = tildeCount,
        speechlessCount = speechlessCount,
        lengthDist = lengthDist,
    )
}

internal suspend fun loadGroupMembersMap(talker: String): Map<String, String> =
    withContext(Dispatchers.IO) {
        val contacts = WeDatabaseApi.getGroupMembers(talker).associate { m ->
            m.wxId to (m.remarkName.takeUnless { it.isBlank() }?.let { "$it (${m.nickname})" } ?: m.nickname)
        }
        // 群内昵称优先，其次联系人备注/昵称
        val nicknameMap = WeDatabaseApi.getGroupNicknameMap(talker)
        contacts.toMutableMap().apply {
            nicknameMap.forEach { (wxId, nick) -> this[wxId] = nick }
        }
    }

internal suspend fun loadCoreMetrics(talker: String): GroupCoreMetrics =
    withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        // 排除系统消息（type=10000），避免把入群/改群名等系统提示计入今日数据
        val todayMessages = WeDatabaseApi.getMessagesInRange(talker, todayStart, now)
            .filter { it.typeCode != 10000 }
        val membersMap = loadGroupMembersMap(talker)
        GroupCoreMetrics(
            todaySpeakers = todayMessages.map { extractSenderId(it, membersMap) }.distinct().size,
            todayMessages = todayMessages.size,
            historyTotal = WeDatabaseApi.getMessageCountInRange(talker, 0, now),
        )
    }

internal suspend fun loadGroupStats(talker: String, range: GroupTimeRange): GroupStats =
    withContext(Dispatchers.IO) {
        val membersMap = loadGroupMembersMap(talker)
        val (start, end) = groupRangeStartEnd(range)
        // 深度统计采样最近 N 条（对应 Hchat analyzeDeepRows 的 ORDER BY createTime DESC LIMIT）
        val messages = WeDatabaseApi.getMessagesInRangeDesc(talker, start, end, GroupAnalyzePrefs.reportSampleLimit())
        computeGroupStats(messages, membersMap)
    }
