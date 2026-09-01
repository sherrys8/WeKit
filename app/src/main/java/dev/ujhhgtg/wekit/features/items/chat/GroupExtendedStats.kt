package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/** 排行榜时段（对应 Hchat 的 rankingPeriodLabels 7 档） */
internal enum class RankingPeriod(val labelRes: Int) {
    TODAY(R.string.ui_group_ranking_today),
    YESTERDAY(R.string.ui_group_ranking_yesterday),
    THIS_WEEK(R.string.ui_group_ranking_this_week),
    LAST_WEEK(R.string.ui_group_ranking_last_week),
    THIS_MONTH(R.string.ui_group_ranking_this_month),
    LAST_MONTH(R.string.ui_group_ranking_last_month),
    ALL(R.string.ui_group_ranking_all),
}

/** 排行统计结果：发送者 -> 消息数 */
internal data class RankingResult(val counts: Map<String, Int>, val total: Int)

/** 低活跃成员（发言条数，含 0 条） */
internal data class LowActivityMember(val wxid: String, val name: String, val count: Int)

/** 活跃检测结果 */
internal data class ActivityResult(
    val totalMembers: Int,
    val activeMembers: Int,
    val members: List<LowActivityMember>,
)

/** 多维属性探测分数（0-100） */
internal data class DimensionScores(
    val activity: Int,
    val interaction: Int,
    val expression: Int,
    val owl: Int,
    val value: Int,
    val burst: Int,
)

/** 群聊消息发送者分隔符前的 wxid（与 GroupStats.kt 的 groupSenderRegex 语义一致） */
private const val SENDER_SQL =
    "CASE WHEN isSend = 1 THEN ? " +
        "WHEN instr(content, ':' || char(10)) > 0 THEN substr(content, 1, instr(content, ':' || char(10)) - 1) " +
        "WHEN instr(content, ':') > 0 THEN substr(content, 1, instr(content, ':') - 1) ELSE NULL END"

/** 计算排行时段 [start, end)，周一对齐；ALL 返回 (-1, -1) 表示不按时间过滤 */
internal fun rankingPeriodRange(period: RankingPeriod): Pair<Long, Long> {
    if (period == RankingPeriod.ALL) return -1L to -1L
    val start = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val end = start.clone() as Calendar
    when (period) {
        RankingPeriod.TODAY -> end.add(Calendar.DAY_OF_YEAR, 1)
        RankingPeriod.YESTERDAY -> start.add(Calendar.DAY_OF_YEAR, -1)
        RankingPeriod.THIS_WEEK, RankingPeriod.LAST_WEEK -> {
            val offset = (start.get(Calendar.DAY_OF_WEEK) + 5) % 7
            start.add(Calendar.DAY_OF_YEAR, -offset)
            end.timeInMillis = start.timeInMillis
            if (period == RankingPeriod.THIS_WEEK) end.add(Calendar.DAY_OF_YEAR, 7)
            else start.add(Calendar.DAY_OF_YEAR, -7)
        }
        RankingPeriod.THIS_MONTH, RankingPeriod.LAST_MONTH -> {
            start.set(Calendar.DAY_OF_MONTH, 1)
            end.timeInMillis = start.timeInMillis
            if (period == RankingPeriod.THIS_MONTH) end.add(Calendar.MONTH, 1)
            else start.add(Calendar.MONTH, -1)
        }
        RankingPeriod.ALL -> {}
    }
    return start.timeInMillis to end.timeInMillis
}

/** 按时段聚合群内发言人数（发送者来自 content 前缀，排除系统消息与群公告） */
private fun queryRankingRows(
    talker: String,
    start: Long,
    end: Long,
    limit: Int = 50,
    asc: Boolean = false,
): Map<String, Int> {
    val sql = StringBuilder()
        .append("SELECT ").append(SENDER_SQL).append(" AS sender, COUNT(*) AS cnt ")
        .append("FROM message WHERE talker = ? AND type NOT IN (10000) ")
    val args = mutableListOf<Any>(WeApi.selfWxId, talker)
    if (start >= 0 && end >= 0) {
        sql.append("AND createTime >= ? AND createTime < ? ")
        args += start
        args += end
    }
    sql.append("GROUP BY sender ORDER BY cnt ").append(if (asc) "ASC" else "DESC").append(" LIMIT ").append(limit)
    val counts = LinkedHashMap<String, Int>()
    WeDatabaseApi.executeQuery(sql.toString(), args.toTypedArray()).forEach { row ->
        val sender = row["sender"]?.toString()?.trim().orEmpty()
        if (sender.isEmpty() || sender.isGroupChatWxId) return@forEach
        counts[sender] = (row["cnt"] as? Number)?.toInt() ?: 0
    }
    return counts
}

/** 活跃发言排行 top50 */
internal suspend fun loadRanking(talker: String, period: RankingPeriod): RankingResult =
    withContext(Dispatchers.IO) {
        val (start, end) = rankingPeriodRange(period)
        val counts = queryRankingRows(talker, start, end)
        RankingResult(counts, counts.values.sum())
    }

/** 活跃检测：统计窗口内每个成员的发言条数（升序，含 0 条） */
internal suspend fun loadActivityResult(talker: String, days: Int): ActivityResult =
    withContext(Dispatchers.IO) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -Math.max(0, days - 1))
        }
        val start = cal.timeInMillis
        val end = System.currentTimeMillis()
        val counts = queryRankingRows(talker, start, end, limit = 1000, asc = true)

        val membersMap = loadGroupMembersMap(talker)
        val selfWxId = WeApi.selfWxId
        val members = membersMap.keys
            .filter { !it.isGroupChatWxId && it != selfWxId }
            .map { wxid ->
                LowActivityMember(wxid, membersMap[wxid] ?: wxid, counts[wxid] ?: 0)
            }
            .sortedWith(compareBy<LowActivityMember> { it.count }.thenBy { it.name })
        val active = members.count { it.count > 0 }
        ActivityResult(members.size, active, members)
    }

/** 多维属性探测：6 个 0-100 分数（算法对应 Hchat 第 1535-1550 行） */
internal fun computeDimensionScores(stats: GroupStats, days: Int, isAll: Boolean): DimensionScores {
    val sampleTotal = stats.totalMessages.coerceAtLeast(1)
    val textTotal = stats.textCount.coerceAtLeast(1)
    val activityDays = if (isAll) 60 else days.coerceAtLeast(1)
    val medium = stats.lengthDist.getOrElse(2) { 0 }
    val longCount = stats.lengthDist.getOrElse(3) { 0 }
    val peak = stats.hourly.maxOrNull() ?: 0
    val owl = stats.hourly.getOrElse(23) { 0 } + stats.hourly.subList(0, 5).sum()
    return DimensionScores(
        activity = minOf(100, sampleTotal * 100 / (activityDays * 80).coerceAtLeast(1)),
        interaction = minOf(100, stats.questionCount * 300 / textTotal),
        expression = minOf(100, (medium + longCount) * 220 / textTotal),
        owl = minOf(100, owl * 300 / sampleTotal),
        value = minOf(100, (medium + longCount) * 180 / textTotal),
        burst = minOf(100, peak * 500 / sampleTotal),
    )
}
