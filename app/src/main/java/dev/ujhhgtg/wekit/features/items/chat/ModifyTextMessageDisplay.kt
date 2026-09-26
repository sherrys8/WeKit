package dev.ujhhgtg.wekit.features.items.chat

import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.view.View
import android.widget.AdapterView
import android.widget.TextView
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Edit
import dev.ujhhgtg.reflekt.reflekt
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
import dev.ujhhgtg.wekit.ui.utils.allViews
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.copyToClipboard
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import java.lang.reflect.Field
import java.lang.reflect.Modifier as JavaModifier

object ModifyTextMessageDisplay : SwitchFeature(),
    WeChatMessageContextMenuApi.IMenuItemsProvider,
    WeChatMessageViewApi.ICreateViewListener {

    override val technicalId = "修改文本消息显示"
    override val nameRes = R.string.feature_modify_text_message_display_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_modify_text_message_display_description

    private const val TAG = "ModifyTextMessageDisplay"
    private const val MENU_ITEM_ID = 777002
    private const val OVERRIDES_KEY = "modify_text_message_display_overrides"

    /** 每条被改消息存一份「原文 -> 替换文本」，超过这个条数就丢最旧的。 */
    private const val MAX_OVERRIDDEN_MESSAGES = 200

    private var overridesPref by prefOption(OVERRIDES_KEY, "{}")

    /** key: 消息标识; value: 原文 -> 替换文本（插入序即修改序，用于淘汰最旧条目） */
    private val overrides = LinkedHashMap<String, MutableMap<String, String>>()
    private var loaded = false

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
        val dump = buildSubtreeDump(view)
        WeLogger.i(TAG, dump)

        val key = messageKey(message)
        val saved = overrides[key].orEmpty()
        val rows = editableRows(collectTargets(view), saved)

        val context = view.context
        if (rows.isEmpty()) {
            showToast(context, localizedChatString(R.string.chat_modify_text_no_editable))
            return
        }

        showComposeDialog(context) {
            EditDialog(key, rows, dump, onDismiss)
        }
    }

    /**
     * 每个有文本的目标一行；已被本功能改过的目标按原文归位，
     * 否则重进弹窗会把替换文本当成原文，导致改写叠加。
     */
    private fun editableRows(
        targets: List<TextTarget>,
        saved: Map<String, String>,
    ): List<EditableRow> {
        val grouped = LinkedHashMap<String, MutableList<TextTarget>>()
        val displayed = LinkedHashMap<String, String>()
        targets.forEach { target ->
            val current = target.current
            if (current.isBlank()) return@forEach
            val original = saved.entries.firstOrNull { it.value == current }?.key ?: current
            grouped.getOrPut(original) { mutableListOf() }.add(target)
            displayed.putIfAbsent(original, current)
        }
        return grouped.map { (original, group) ->
            EditableRow(group, original, displayed[original].orEmpty())
        }
    }

    @Composable
    private fun EditDialog(
        key: String,
        rows: List<EditableRow>,
        dump: String,
        onDismiss: () -> Unit,
    ) {
        var inputs by remember { mutableStateOf(rows.map { it.current }) }
        val platformContext = LocalContext.current

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
                        Text(
                            text = row.targets.first().label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                        TextField(
                            value = inputs[index],
                            onValueChange = { value ->
                                inputs = inputs.toMutableList().also { it[index] = value }
                            },
                            label = { Text(stringResource(R.string.chat_modify_text_content)) },
                            maxLines = 4,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                        )
                    }
                    // 临时诊断：定位文件卡片标题这类没有 TextView 宿主的文本行
                    Text(
                        text = stringResource(R.string.chat_modify_text_diag),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        text = dump,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable {
                            copyToClipboard(platformContext, dump)
                        },
                    )
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
            if (input.isNotBlank() && input != row.current) row.targets.forEach { it.write(input) }
        }

        showToast(
            rows.first().targets.first().hostView.context,
            localizedChatString(R.string.chat_modify_text_saved),
        )
    }

    private fun revert(key: String, rows: List<EditableRow>) {
        overrides.remove(key)
        persistOverrides()
        rows.forEach { row -> row.targets.forEach { it.write(row.original) } }
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
        collectTargets(view).forEach { target ->
            val replacement = saved[target.current] ?: return@forEach
            target.write(replacement)
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

    /** 真机上报：红包、转账、文件卡片等自定义气泡的文本宿主各不相同，这里统一成一个写入口。 */
    private interface TextTarget {
        val hostView: View
        val current: String
        val label: String
        fun write(value: String)
    }

    private class TextViewTarget(override val hostView: TextView) : TextTarget {
        override val current get() = hostView.text.toString()
        override val label get() = hostView.javaClass.simpleName + "#" + entryName(hostView)
        override fun write(value: String) {
            hostView.text = value
        }
    }

    /**
     * 微信把 `TextView` 的 T 换成 7 造出的自绘文本宿主（文件卡片名、引用块正文），
     * 不是 TextView 子类，只能按字段逐个改写。
     */
    private class FieldValueTarget(
        override val hostView: View,
        private val field: Field,
    ) : TextTarget {
        override val current get() = field.get(hostView)?.toString().orEmpty()
        override val label get() = hostView.javaClass.simpleName + "#" + entryName(hostView) + "." + field.name
        override fun write(value: String) {
            coerceToField(field, value)?.let { field.set(hostView, it) }
            hostView.invalidate()
        }
    }

    /**
     * 纯文本、拍一拍这类气泡的正文不在子 TextView 上，而是条目 View 自己的
     * CharSequence 字段 + 同名 setter（旧版实现通路），保留为兜底。
     */
    private class HostFieldTarget(override val hostView: View) : TextTarget {
        private val field = hostView.reflekt().firstFieldOrNull {
            type = CharSequence::class
            superclass()
        }

        override val current get() = field?.get()?.toString().orEmpty()
        override val label get() = "field:" + hostView.javaClass.simpleName
        override fun write(value: String) {
            hostView.reflekt().firstMethod {
                parameters(CharSequence::class)
            }.invoke(value)
        }
    }

    private class EditableRow(
        val targets: List<TextTarget>,
        val original: String,
        val current: String,
    )

    /**
     * 真机确认过的文本宿主 id，其余 View（昵称、时间、平台自带字段等）不再进弹窗。
     * 只作用于整行扫描；菜单给的那个 View 自身的「字段 + setter」通路不受白名单限制，
     * 否则纯文本、拍一拍会再次退化。
     */
    private val editableViewIds = setOf("a44", "a48", "a46", "bkl", "bjp", "bju", "bj2")

    private fun collectTargets(menuView: View): List<TextTarget> {
        val targets = mutableListOf<TextTarget>()
        editRootOf(menuView).allViews.forEach { child ->
            if (entryName(child) !in editableViewIds) return@forEach
            when {
                child is TextView ->
                    if (child.text?.isNotBlank() == true) targets += TextViewTarget(child)

                isTextHost(child) ->
                    settableTextFields(child).forEach { field ->
                        FieldValueTarget(child, field)
                            .takeIf { it.current.isNotBlank() }
                            ?.let { targets += it }
                    }
            }
        }

        // 菜单交给我们的往往就是正文 View 本身，它的「首个 CharSequence 字段 + 同名 setter」
        // 是纯文本、拍一拍已验证可用的通路，始终保留；与字段行原文相同时会被并成同一行
        HostFieldTarget(menuView).takeIf { it.current.isNotBlank() }?.let { targets += it }
        return targets
    }

    /** 每次打开弹窗记录一次消息行子树，用于定位没有 TextView 宿主的文本行。 */
    private fun buildSubtreeDump(menuView: View): String = buildString {
        val root = editRootOf(menuView)
        append("menu=").append(menuView.javaClass.name)
            .append("\nrow=").append(root.javaClass.name)
        root.allViews.take(40).forEach { child ->
            append('\n').append(child.javaClass.simpleName)
                .append(" id=").append(entryName(child))
                .append(" vis=").append(child.visibility)
            when {
                child is TextView -> append(" text=").append(child.text.take(40))
                isTextHost(child) -> append(" fields=")
                    .append(settableTextFields(child).joinToString(",", "[", "]") { field ->
                        field.name + "=" + field.get(child)?.toString()?.take(24).orEmpty()
                    })
            }
        }
    }
}

private fun entryName(view: View): String {
    if (view.id == View.NO_ID) return "none"
    return runCatching { view.resources.getResourceEntryName(view.id) }
        .getOrDefault(view.id.toString())
}

/** 自绘文本宿主：类名去掉数字混淆位后含 `extView`（如 MMNeat7extView）。 */
private fun isTextHost(view: View): Boolean =
    view.javaClass.simpleName.filterNot { it.isDigit() }.contains("extView")

private fun acceptsTextField(field: Field): Boolean =
    field.type == String::class.java || field.type == CharSequence::class.java ||
            SpannableString::class.java.isAssignableFrom(field.type) ||
            SpannableStringBuilder::class.java.isAssignableFrom(field.type)

/** 宿主自绘文本时可能缓存 Spannable 变体，按字段声明类型构造对应实现。 */
private fun coerceToField(field: Field, value: String): Any? = when {
    field.type == String::class.java || field.type == CharSequence::class.java -> value
    SpannableStringBuilder::class.java.isAssignableFrom(field.type) -> SpannableStringBuilder(value)
    SpannableString::class.java.isAssignableFrom(field.type) -> SpannableString(value)
    else -> null
}

private fun settableTextFields(host: View): List<Field> {
    val fields = mutableListOf<Field>()
    var clazz: Class<*>? = host.javaClass
    while (clazz != null && !clazz.name.startsWith("android.") && !clazz.name.startsWith("androidx.")) {
        clazz.declaredFields.forEach { field ->
            if (!JavaModifier.isStatic(field.modifiers) && acceptsTextField(field)) {
                field.isAccessible = true
                fields += field
            }
        }
        clazz = clazz.superclass
    }
    return fields
}

/**
 * 引用块的正文不在长按菜单给出的那个正文 View 子树里，而是它的兄弟 View，
 * 因此把编辑范围上溯到整条消息行；父级是列表宿主时即为本行根节点。
 */
private fun editRootOf(view: View): View {
    var node = view
    var hops = 0
    while (node.parent is View && hops < 6) {
        val parent = node.parent as View
        if (parent is AdapterView<*> || parent.javaClass.name.contains("RecyclerView")) break
        node = parent
        hops++
    }
    return node
}
