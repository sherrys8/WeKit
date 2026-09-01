package dev.ujhhgtg.wekit.features.items.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.fs.createDirsSafe
import java.io.FileOutputStream
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.io.path.div
/**
 * 将群聊分析结果渲染为 Hchat 风格卡片截图（Canvas 渐变底 + 圆角白卡）。
 * 用于 AI 总结截图与活跃发言排行截图，生成后选择目标群聊发送。
 */
internal object GroupScreenshotRenderer {

    private fun cacheDir(): Path = KnownPaths.moduleCache / "analysis_screenshots"

    private fun fitLine(value: String, paint: Paint, maxWidth: Float): String {
        var text = value.ifEmpty { "" }
        if (paint.measureText(text) <= maxWidth) return text
        while (text.isNotEmpty() && paint.measureText(text + "…") > maxWidth) {
            text = text.dropLast(1)
        }
        return text + "…"
    }

    private fun buildBodyLayout(summary: String, paint: TextPaint, width: Int): StaticLayout {
        val text = summary.orEmpty()
        val styled = SpannableStringBuilder(text)
        val lines = text.split("\n")
        var offset = 0
        val red = Color.rgb(198, 40, 40)
        val numerals = "一二三四五六七八九十"
        lines.forEachIndexed { _, line ->
            val trimmed = line.trim()
            val start = offset + (line.length - line.replaceFirst(Regex("^\\s+"), "").length)
            val end = offset + line.length
            val numberedTitle = trimmed.length >= 2 &&
                numerals.indexOf(trimmed.substring(0, 1)) >= 0 &&
                (trimmed[1] == '、' || trimmed[1] == '，')
            val sectionTitle = numberedTitle ||
                trimmed == "重点人物与群像" || trimmed == "整体氛围" || trimmed == "有趣的点"
            when {
                trimmed.startsWith("群聊总结：") || sectionTitle || trimmed.startsWith("结论：") -> {
                    styled.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    styled.setSpan(ForegroundColorSpan(red), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                trimmed.startsWith("内容概览：") -> {
                    val labelEnd = minOf(end, start + "内容概览：".length)
                    styled.setSpan(StyleSpan(Typeface.BOLD), start, labelEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            offset += line.length + 1
        }
        return StaticLayout(styled, paint, width, Layout.Alignment.ALIGN_NORMAL, 1.28f, 0.0f, false)
    }

    private fun dateRange(reportDays: Int): String {
        if (reportDays < 0) return "全部历史消息"
        val f = SimpleDateFormat("yyyy/MM/dd", Locale.CHINA)
        val start = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            add(java.util.Calendar.DAY_OF_YEAR, -Math.max(0, reportDays - 1))
        }
        return "${f.format(start.time)} ~ ${f.format(Date())}"
    }

    private fun writeBitmap(bitmap: Bitmap, prefix: String): Path {
        val dir = cacheDir()
        dir.createDirsSafe()
        val file = dir / "${prefix}_${System.currentTimeMillis()}.png"
        FileOutputStream(file.toFile()).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        check(file.toFile().exists() && file.toFile().length() > 0) { "截图文件生成失败" }
        return file
    }

    /** AI 总结卡片截图：渐变背景 + 标题头 + 圆角白卡 + 正文 */
    fun renderAiSummaryScreenshot(
        groupName: String,
        summary: String,
        reportDays: Int,
        memberCount: Int,
        speakerCount: Int,
        sampleCount: Int,
    ): Path {
        val width = 900
        val cardMargin = Math.max(1, Math.round(10.0f * 1f))
        val contentPadding = 52
        val bodyWidth = width - (cardMargin + contentPadding) * 2
        val headerHeight = 276
        val bodyTop = 316
        val footerHeight = 104
        val maxHeight = 7000

        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(36, 43, 55)
            textSize = 31.0f
        }
        var bodyLayout = buildBodyLayout(summary, bodyPaint, bodyWidth)
        while (bodyLayout.height + bodyTop + footerHeight > maxHeight && bodyPaint.textSize > 20.0f) {
            bodyPaint.textSize -= 1.0f
            bodyLayout = buildBodyLayout(summary, bodyPaint, bodyWidth)
        }
        val height = maxOf(720, bodyLayout.height + bodyTop + footerHeight)
        check(height <= maxHeight) { "总结内容过长，无法生成单张截图" }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val backgroundColors = intArrayOf(
            Color.rgb(230, 239, 255),
            Color.rgb(224, 251, 247),
            Color.rgb(250, 247, 239),
            Color.rgb(255, 235, 241),
        )
        val backgroundStops = floatArrayOf(0.0f, 0.34f, 0.72f, 1.0f)
        paint.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), backgroundColors, backgroundStops, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null

        val card = RectF(cardMargin.toFloat(), headerHeight.toFloat(), (width - cardMargin).toFloat(), (height - 38).toFloat())
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(22, 54, 83, 110)
        canvas.drawRoundRect(RectF(card.left + 3, card.top + 8, card.right + 3, card.bottom + 10), 10f, 10f, paint)
        paint.color = Color.WHITE
        canvas.drawRoundRect(card, 10f, 10f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3.0f
        paint.color = Color.argb(110, 220, 45, 60)
        canvas.drawRoundRect(card, 10f, 10f, paint)
        paint.style = Paint.Style.FILL

        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.rgb(34, 74, 121)
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 50.0f
        canvas.drawText("群聊分析报告", width / 2.0f, 76f, paint)
        paint.color = Color.rgb(65, 100, 139)
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 25.0f
        canvas.drawText(fitLine(dateRange(reportDays), paint, width - 136.0f), width / 2.0f, 119f, paint)
        paint.color = Color.rgb(73, 91, 112)
        paint.typeface = Typeface.DEFAULT
        paint.textSize = 22.0f
        canvas.drawText(fitLine("分析群聊：$groupName", paint, width - 136.0f), width / 2.0f, 158f, paint)
        paint.color = Color.rgb(34, 74, 121)
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val reportStats = "群员总数 $memberCount 人   ·   发言人数 $speakerCount 人   ·   抽样消息 $sampleCount 条"
        canvas.drawText(fitLine(reportStats, paint, width - 136.0f), width / 2.0f, 198f, paint)
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        paint.color = Color.rgb(105, 116, 130)
        paint.typeface = Typeface.DEFAULT
        paint.textSize = 20.0f
        canvas.drawText("生成时间：$time", width / 2.0f, 236f, paint)

        paint.textAlign = Paint.Align.LEFT
        canvas.save()
        canvas.translate((cardMargin + contentPadding).toFloat(), bodyTop.toFloat())
        bodyLayout.draw(canvas)
        canvas.restore()
        paint.color = Color.rgb(121, 132, 146)
        paint.textSize = 20.0f
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText("一字一字手搓的报告🖍", (width - cardMargin - contentPadding).toFloat(), (height - 68).toFloat(), paint)

        return writeBitmap(bitmap, "summary")
    }

    /** 活跃发言排行截图：橙色标题头 + 名次圆标 + 进度条 */
    fun renderRankingScreenshot(
        groupName: String,
        periodLabel: String,
        entries: List<Pair<String, Int>>,
        total: Int,
    ): Path {
        val width = 900
        val height = 330 + entries.size * 112
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = Color.rgb(255, 248, 240)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        paint.color = Color.rgb(245, 124, 0)
        canvas.drawRoundRect(RectF(36f, 34f, (width - 36).toFloat(), 210f), 28f, 28f, paint)
        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.WHITE
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 48.0f
        canvas.drawText("活跃发言排行榜", width / 2.0f, 102f, paint)
        paint.textSize = 25.0f
        canvas.drawText(fitLine(groupName, paint, width - 150.0f), width / 2.0f, 148f, paint)
        paint.typeface = Typeface.DEFAULT
        paint.textSize = 21.0f
        canvas.drawText("$periodLabel  ·  统计消息 $total 条", width / 2.0f, 184f, paint)

        val max = entries.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
        entries.forEachIndexed { index, (name, count) ->
            val top = 244 + index * 112
            paint.color = Color.WHITE
            canvas.drawRoundRect(RectF(44f, top.toFloat(), (width - 44).toFloat(), (top + 88).toFloat()), 18f, 18f, paint)
            paint.color = when (index) {
                0 -> Color.rgb(255, 193, 7)
                1 -> Color.rgb(158, 158, 158)
                2 -> Color.rgb(188, 118, 64)
                else -> Color.rgb(245, 124, 0)
            }
            canvas.drawCircle(88f, top + 35f, 25f, paint)
            paint.textAlign = Paint.Align.CENTER
            paint.color = Color.WHITE
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textSize = 23.0f
            canvas.drawText("${index + 1}", 88f, top + 43f, paint)
            paint.textAlign = Paint.Align.LEFT
            paint.color = Color.rgb(38, 36, 42)
            paint.textSize = 27.0f
            canvas.drawText(fitLine(name, paint, 480f), 132f, top + 39f, paint)
            paint.textAlign = Paint.Align.RIGHT
            paint.color = Color.rgb(230, 92, 0)
            paint.textSize = 25.0f
            canvas.drawText("$count 条", (width - 76).toFloat(), top + 39f, paint)
            paint.color = Color.rgb(255, 224, 189)
            canvas.drawRoundRect(RectF(132f, top + 58f, (width - 76).toFloat(), (top + 68).toFloat()), 5f, 5f, paint)
            paint.color = Color.rgb(245, 124, 0)
            val progressRight = 132f + (width - 208) * count / max.toFloat()
            canvas.drawRoundRect(RectF(132f, top + 58f, progressRight, top + 68f), 5f, 5f, paint)
        }

        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT
        paint.color = Color.rgb(110, 106, 116)
        paint.textSize = 19.0f
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        canvas.drawText("生成时间：$time", width / 2.0f, (height - 34).toFloat(), paint)

        return writeBitmap(bitmap, "ranking")
    }
}
