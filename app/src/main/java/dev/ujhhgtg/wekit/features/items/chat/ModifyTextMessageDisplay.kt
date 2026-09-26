package dev.ujhhgtg.wekit.features.items.chat

import android.view.View
import android.widget.TextView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Edit
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.EditIcon
import dev.ujhhgtg.wekit.ui.utils.findViewsWhich
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson

object ModifyTextMessageDisplay : SwitchFeature(),
    WeChatMessageContextMenuApi.IMenuItemsProvider,
    WeChatMessageViewApi.ICreateViewListener {

    override val technicalId = "修改文本消息显示"
    override val nameRes = R.string.feature_modify_text_message_display_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_modify_text_message_display_description

    private const val MENU_ITEM_ID = 777002
    private const val OVERRIDES_KEY = "modify_text_message_display_overrides"

    /** 每条被改消息存一份「原文 -> 替换文本」，超过这个条数就丢最旧的。 */
    private const val MAX_OVERRIDDEN_MESSAGES = 200

    private var overridesPref by prefOption(OVERRIDES_KEY, "{}")

    /** key: 消息标识; value: 原文 -> 替换文本（插入序即修改序，用于淘汰最旧条目） */
    private val overrides = LinkedHashMap<String, MutableMap<String, String>>()
    private var loaded = false

    /** 气泡内一行可编辑文本：承载它的 TextView 集合、它的原始文本、当前显示文本。 */
    private class EditableRow(
        val views: List<TextView>,
        val original: String,
        val current: String,
    )

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
        WeChatMessageViewApi.addListener(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
        WeChatMessageViewApi.removeListener(this)
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> = listOf(
        WeChatMessageContextMenuApi.MenuItem(
            MENU_ITEM_ID,
            localizedChatString(R.string.chat_modify_text_menu),
            EditIcon,
            MaterialSymbols.Outlined.Edit,
            { msgInfo -> isEditableBubble(msgInfo.type) },
            // operates on the single message's own View; can't apply to a batch
            multiSelect = WeChatMessageContextMenuApi.MultiSelectSupport.Unsupported
        ) { view, _, msgInfo ->
            openEditor(view, msgInfo)
        }
    )

    /** 只放有文本行的气泡；图片、语音、视频、表情等没有可改文本，不进菜单。 */
    private fun isEditableBubble(type: MessageType?): Boolean = type != null && (
            type.isText || type.isLink || type.isRedPacket || type.isSystem ||
                    type.isLocation || type.isVideoAccount ||
                    type == MessageType.TRANSFER ||
                    type == MessageType.RED_PACKET_COVER ||
                    type == MessageType.CARD ||
                    type == MessageType.FILE ||
                    type == MessageType.GROUP_NOTE ||
                    type == MessageType.PAT ||
                    type == MessageType.APP ||
                    type == MessageType.ACCOUNT_VIDEO
            )

    private fun openEditor(view: View, message: MessageInfo) {
        loadOverrides()
        val key = messageKey(message)
        val saved = overrides[key].orEmpty()
        val rows = editableRows(view, saved)

        val context = view.context
        if (rows.isEmpty()) {
            showToast(context, localizedChatString(R.string.chat_modify_text_no_editable))
            return
        }

        showComposeDialog(context) {
            EditDialog(key, rows, onDismiss)
        }
    }

    /**
     * 每个非空 TextView 一行；已被本功能改过的行按原文归位，
     * 否则重进弹窗会把替换文本当成原文，导致改写叠加。
     */
    private fun editableRows(root: View, saved: Map<String, String>): List<EditableRow> {
        val grouped = LinkedHashMap<String, MutableList<TextView>>()
        val displayed = LinkedHashMap<String, String>()
        root.findViewsWhich { it is TextView && it.text?.isNotBlank() == true }
            .forEach { child ->
                val label = child as TextView
                val current = label.text.toString()
                val original = saved.entries.firstOrNull { it.value == current }?.key ?: current
                grouped.getOrPut(original) { mutableListOf() }.add(label)
                displayed.putIfAbsent(original, current)
            }
        return grouped.map { (original, views) -> EditableRow(views, original, displayed[original]!!) }
    }

    @Composable
    private fun EditDialog(key: String, rows: List<EditableRow>, onDismiss: () -> Unit) {
        var inputs by remember { mutableStateOf(rows.map { it.current }) }

        AlertDialogContent(
            title = { Text(stringResource(R.string.chat_modify_text_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    rows.forEachIndexed { index, row ->
                        TextField(
                            value = inputs[index],
                            onValueChange = { value ->
                                inputs = inputs.toMutableList().also { it[index] = value }
                            },
                            label = { Text(stringResource(R.string.chat_modify_text_content)) },
                            textStyle = MaterialTheme.typography.bodyMedium,
                            maxLines = 4,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    revert(key, rows)
                    onDismiss()
                }) { Text(stringResource(R.string.chat_modify_text_revert)) }
            },
            confirmButton = {
                TextButton(onClick = {
                    applyEdits(key, rows, inputs)
                    onDismiss()
                }) { Text(stringResource(R.string.dialog_confirm)) }
            },
        )
    }

    private fun applyEdits(key: String, rows: List<EditableRow>, inputs: List<String>) {
        val edits = LinkedHashMap<String, String>()
        rows.forEachIndexed { index, row ->
            val input = inputs[index]
            if (input.isNotBlank() && input != row.original) edits[row.original] = input
        }

        if (edits.isEmpty()) overrides.remove(key) else overrides[key] = edits
        persistOverrides()

        rows.forEachIndexed { index, row ->
            val input = inputs[index]
            if (input.isNotBlank() && input != row.current) row.views.forEach { it.text = input }
        }

        val context = rows.first().views.first().context
        showToast(context, localizedChatString(R.string.chat_modify_text_saved))
    }

    private fun revert(key: String, rows: List<EditableRow>) {
        overrides.remove(key)
        persistOverrides()
        rows.forEach { row -> row.views.forEach { it.text = row.original } }
    }

    /** 微信重绑时会把文本刷回原文，这里在每次绑定后按原文匹配重新套上替换。 */
    override fun onCreateView(param: HookParam, view: View) {
        loadOverrides()
        if (overrides.isEmpty()) return

        val message = WeChatMessageViewApi.getMessageOfView(view) ?: return
        val saved = overrides[messageKey(message)] ?: return
        reapplyOverrides(view, saved)
    }

    private fun reapplyOverrides(view: View, saved: Map<String, String>) {
        view.findViewsWhich { it is TextView && it.text?.isNotBlank() == true }
            .forEach { text ->
                val target = text as TextView
                val current = target.text.toString()
                val replacement = saved[current] ?: return@forEach
                if (current != replacement) target.text = replacement
            }
    }

    /** 系统消息等场景 serverId 为 0，退回本地 msgId。 */
    private fun messageKey(message: MessageInfo): String {
        val id = message.serverId.takeIf { it != 0L } ?: message.id
        return message.talker + "|" + id
    }

    private fun loadOverrides() {
        if (loaded) return
        loaded = true
        runCatching {
            DefaultJson.decodeFromString<Map<String, Map<String, String>>>(overridesPref)
        }.getOrNull()?.forEach { (key, edits) -> overrides[key] = edits.toMutableMap() }
    }

    private fun persistOverrides() {
        while (overrides.size > MAX_OVERRIDDEN_MESSAGES) {
            overrides.remove(overrides.keys.first())
        }
        overridesPref = DefaultJson.encodeToString<Map<String, Map<String, String>>>(overrides)
    }
}
