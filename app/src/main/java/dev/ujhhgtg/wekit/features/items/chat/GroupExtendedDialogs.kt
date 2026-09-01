package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Person_remove
import com.composables.icons.materialsymbols.outlined.Search
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeGroupApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.utils.android.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

/** 带遮罩与过渡动画的弹窗容器（与 GroupChatSummary.AnimatedDialog 一致，供扩展弹窗复用） */
@Composable
internal fun GroupExtendedDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable(onClick = onDismiss),
            )
        }
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.92f, animationSpec = tween(220)) + slideInVertically(animationSpec = tween(220)) { it / 8 },
            exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.92f, animationSpec = tween(180)) + slideOutVertically(animationSpec = tween(180)) { it / 8 },
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 16.dp),
        ) {
            content()
        }
    }
}

/**
 * 低活跃成员弹窗：按发言数升序展示成员，支持搜索昵称/微信号、勾选后批量移出群聊。
 * @param talker 当前群聊 ID
 * @param members 低活跃成员（含 0 条发言），来自 [loadActivityResult]
 */
@Composable
internal fun LowActivityMembersDialog(
    talker: String,
    members: List<LowActivityMember>,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() }
    val selfWxId = WeApi.selfWxId
    val inactiveCount = members.count { it.count == 0 }
    val scope = rememberCoroutineScope()

    val filtered = remember(query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) members
        else members.filter { it.name.lowercase().contains(q) || it.wxid.lowercase().contains(q) }
    }

    AlertDialogContent(
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.ui_group_low_members_title, members.size),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) {
                    Icon(MaterialSymbols.Outlined.Close, null, modifier = Modifier.size(16.dp))
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(480.dp),
            ) {
                Text(
                    text = stringResource(R.string.ui_group_low_members_tip, inactiveCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.ui_group_low_members_search_hint)) },
                    singleLine = true,
                    leadingIcon = { Icon(MaterialSymbols.Outlined.Search, null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    filtered.forEach { member ->
                        val isSelf = member.wxid == selfWxId
                        val checked = member.wxid in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isSelf) {
                                    if (checked) selected.remove(member.wxid) else selected.add(member.wxid)
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (checked) MaterialTheme.colorScheme.primary else Color.Transparent,
                                border = if (checked) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                Box(
                                    modifier = Modifier.size(20.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (checked) {
                                        Text("✓", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isSelf) {
                                        stringResource(R.string.ui_group_low_members_self, member.name)
                                    } else {
                                        member.name
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (member.count == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${member.wxid}  ·  " + if (member.count == 0) {
                                        stringResource(R.string.ui_group_low_members_never)
                                    } else {
                                        stringResource(R.string.ui_group_low_members_msg_count, member.count)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                    if (filtered.isEmpty()) {
                        Text(
                            text = stringResource(R.string.ui_group_low_members_no_match),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 32.dp).fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            val removedToast = stringResource(R.string.ui_group_low_members_removed, selected.size)
            Button(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        withContext(Dispatchers.IO) {
                            WeGroupApi.delMembers(talker, selected.toList())
                        }
                        withContext(Dispatchers.Main) {
                            showToast(removedToast)
                            onDismiss()
                        }
                    }
                },
                enabled = selected.isNotEmpty(),
            ) {
                Icon(MaterialSymbols.Outlined.Person_remove, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.ui_group_low_members_remove, selected.size))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}

/**
 * 截图发送目标群聊选择弹窗：生成截图后选择接收群聊。
 * 群列表按显示名忽略大小写排序，当前群置顶（对应 Hchat 截图群选择）。
 * @param contentName 内容名（「总结」/「排行」），用于文案
 */
@Composable
internal fun GroupChoiceScreenshotDialog(
    screenshotPath: Path,
    contentName: String,
    talker: String,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var groups by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        groups = withContext(Dispatchers.IO) {
            WeDatabaseApi.getGroups()
                .map { it.wxId to (it.displayName.ifEmpty { it.nickname }.ifEmpty { it.wxId }) }
                .sortedWith(
                    compareByDescending<Pair<String, String>> { it.first == talker }
                        .thenBy { it.second.lowercase() },
                )
        }
    }

    val filtered = remember(query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) groups
        else groups.filter { it.second.lowercase().contains(q) || it.first.lowercase().contains(q) }
    }

    fun cleanupAndDismiss() {
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { java.nio.file.Files.deleteIfExists(screenshotPath) }
            }
            onDismiss()
        }
    }

    AlertDialogContent(
        title = {
            Text(
                text = stringResource(R.string.ui_group_screenshot_send_title, contentName),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(440.dp),
            ) {
                Text(
                    text = stringResource(R.string.ui_group_screenshot_send_tip, groups.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.ui_group_screenshot_search_hint)) },
                    singleLine = true,
                    leadingIcon = { Icon(MaterialSymbols.Outlined.Search, null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    val failedToast = stringResource(R.string.ui_group_screenshot_send_failed, contentName)
                    filtered.forEachIndexed { index, (wxid, name) ->
                        val sentToast = stringResource(R.string.ui_group_screenshot_sent, contentName, name)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val target = wxid
                                    scope.launch {
                                        val sent = withContext(Dispatchers.IO) {
                                            WeMessageApi.sendImage(target, screenshotPath.toFile().absolutePath)
                                        }
                                        if (sent) {
                                            showToast(sentToast)
                                        } else {
                                            showToast(failedToast)
                                        }
                                        // 发送成功后 60 秒删除临时截图（对应 Hchat）
                                        scope.launch {
                                            delay(60_000)
                                            withContext(Dispatchers.IO) {
                                                runCatching { java.nio.file.Files.deleteIfExists(screenshotPath) }
                                            }
                                        }
                                        onDismiss()
                                    }
                                }
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    if (filtered.isEmpty()) {
                        Text(
                            text = stringResource(R.string.ui_group_screenshot_no_match),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 32.dp).fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = null,
        dismissButton = {
            TextButton(onClick = { cleanupAndDismiss() }) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}
