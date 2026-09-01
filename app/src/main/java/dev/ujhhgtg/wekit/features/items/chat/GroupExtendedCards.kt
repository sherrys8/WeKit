package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Expand_less
import com.composables.icons.materialsymbols.outlined.Expand_more
import com.composables.icons.materialsymbols.outlined.Groups
import com.composables.icons.materialsymbols.outlined.Leaderboard
import com.composables.icons.materialsymbols.outlined.Photo_library
import com.composables.icons.materialsymbols.outlined.Psychology_alt
import dev.ujhhgtg.wekit.ui.content.Button
import kotlin.math.roundToInt

/** 群聊活跃检测：周期滑块 + 活跃/总成员 + 低活跃成员弹窗入口 */
@Composable
internal fun GroupActivityChartCard(
    talker: String,
    onShowLowActivity: (List<LowActivityMember>) -> Unit,
) {
    var collapsed by remember { mutableStateOf(true) }
    var days by remember { mutableIntStateOf(7) }
    var result by remember { mutableStateOf<ActivityResult?>(null) }

    LaunchedEffect(talker, days) {
        result = loadActivityResult(talker, days)
    }

    ExtendedStatCard(MaterialSymbols.Outlined.Groups, R.string.ui_group_activity_title, collapsed, { collapsed = !collapsed }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.ui_group_activity_range_label),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${days}天",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = days.toFloat(),
            onValueChange = { days = it.roundToInt() },
            valueRange = 1f..90f,
            steps = 88,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            ActivityMetric(
                value = result?.activeMembers ?: 0,
                labelRes = R.string.ui_group_activity_active,
                modifier = Modifier.weight(1f),
            )
            ActivityMetric(
                value = result?.totalMembers ?: 0,
                labelRes = R.string.ui_group_activity_total,
                modifier = Modifier.weight(1f),
            )
            ActivityMetric(
                value = (result?.totalMembers ?: 0) - (result?.activeMembers ?: 0),
                labelRes = R.string.ui_group_activity_inactive,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(12.dp))
        val members = result?.members.orEmpty()
        Button(
            onClick = { onShowLowActivity(members) },
            enabled = members.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(40.dp),
        ) {
            Text(stringResource(R.string.ui_group_activity_show_inactive))
        }
    }
}

@Composable
private fun ActivityMetric(value: Int, labelRes: Int, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$value", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** 活跃发言排行：7 时段 Tab + top10 列表 + 生成截图 */
@Composable
internal fun GroupRankingChartCard(
    talker: String,
    onGenerateRankingScreenshot: (String, List<Pair<String, Int>>, Int) -> Unit,
) {
    var collapsed by remember { mutableStateOf(true) }
    var period by remember { mutableStateOf(RankingPeriod.TODAY) }
    var result by remember { mutableStateOf<RankingResult?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    LaunchedEffect(talker, period) {
        isLoading = true
        result = loadRanking(talker, period)
        isLoading = false
    }

    ExtendedStatCard(MaterialSymbols.Outlined.Leaderboard, R.string.ui_group_ranking_title, collapsed, { collapsed = !collapsed }) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RankingPeriod.entries.forEach { range ->
                val selected = period == range
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background,
                ) {
                    Text(
                        text = stringResource(range.labelRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clickable { period = range }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        val periodLabel = stringResource(period.labelRes)
        if (isLoading) {
            Text(
                text = stringResource(R.string.ui_group_ranking_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val entries = result?.counts.orEmpty().entries
                .sortedByDescending { it.value }
                .take(10)
                .map { it.key to it.value }
            if (entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.ui_group_ranking_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val total = result?.total ?: 0
                val maxCount = entries.first().second.coerceAtLeast(1)
                var nameMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
                LaunchedEffect(talker) {
                    nameMap = loadGroupMembersMap(talker)
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    entries.forEachIndexed { index, (wxid, count) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .size(22.dp)
                                    .background(rankBadgeColor(index), CircleShape),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = nameMap[wxid] ?: wxid,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "$count ${stringResource(R.string.ui_group_ranking_msgs)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .background(MaterialTheme.colorScheme.background, RoundedCornerShape(3.dp)),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(count / maxCount.toFloat())
                                    .height(5.dp)
                                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        onGenerateRankingScreenshot(periodLabel, entries, total)
                    },
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                ) {
                    Icon(MaterialSymbols.Outlined.Photo_library, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ui_group_ranking_screenshot))
                }
            }
        }
    }
}

private fun rankBadgeColor(index: Int): Color = when (index) {
    0 -> Color(0xFFFFC107)
    1 -> Color(0xFFBDBDBD)
    2 -> Color(0xFFCD8A45)
    else -> Color(0xFFF57C00)
}

/** 多维属性探测：6 条 0-100 分数 */
@Composable
internal fun GroupDimensionChartCard(scores: DimensionScores) {
    var collapsed by remember { mutableStateOf(true) }

    ExtendedStatCard(MaterialSymbols.Outlined.Psychology_alt, R.string.ui_group_dim_title, collapsed, { collapsed = !collapsed }) {
        val items = listOf(
            scores.activity to R.string.ui_group_dim_activity,
            scores.interaction to R.string.ui_group_dim_interaction,
            scores.expression to R.string.ui_group_dim_expression,
            scores.owl to R.string.ui_group_dim_owl,
            scores.value to R.string.ui_group_dim_value,
            scores.burst to R.string.ui_group_dim_burst,
        )
        items.forEach { (score, labelRes) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(labelRes),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(72.dp),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp)
                        .background(MaterialTheme.colorScheme.background, RoundedCornerShape(4.dp)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(score / 100f)
                            .height(8.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "$score",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(32.dp),
                    textAlign = TextAlign.End,
                )
            }
            Spacer(Modifier.height(10.dp))
        }
        Text(
            text = stringResource(R.string.ui_group_dim_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 折叠卡片外壳（与 GroupStatsCharts.StatCard 一致） */
@Composable
private fun ExtendedStatCard(
    icon: ImageVector,
    titleRes: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(24.dp))
            .animateContentSize(animationSpec = tween(220))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onToggle) {
                Icon(
                    if (collapsed) MaterialSymbols.Outlined.Expand_more else MaterialSymbols.Outlined.Expand_less,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AnimatedVisibility(
            visible = !collapsed,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(150)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}
