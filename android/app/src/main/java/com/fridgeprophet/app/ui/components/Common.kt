package com.fridgeprophet.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fridgeprophet.app.R
import com.fridgeprophet.app.ui.theme.SemanticColors

/** 统一样式的卡片容器，各页面复用，保证视觉一致 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        // 描一道发丝边框。背景色差已经能把卡片分出来了，但再加一道细边，
        // 边界会更利落 —— 白色卡片叠在浅灰背景上又没阴影时，光靠色差边缘会发虚。
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

/**
 * 可折叠卡片：标题 + 一行摘要，点一下才展开内容。
 *
 * ## 为什么要有这个组件
 *
 * 「健康偏好」有 11 个开关。全部平铺在个人页上，用户想找下面的
 * 「退出登录」得先滑过一整屏开关，而绝大多数时候他并不需要改这些。
 * 折起来只留一行摘要（「已选 3 项：低盐、高蛋白、控糖」），
 * 一眼能看出设置过什么，要改再展开。
 *
 * 摘要由调用方传进来而不是自动生成：不同区块「什么信息最值得露在外面」
 * 不一样，让调用方决定比在这里猜更准。
 *
 * 展开状态用 `rememberSaveable` 记住，横竖屏切换、进程被系统回收后
 * 回到这个页面时，用户展开的那个箱子还是展开的。
 */
@Composable
fun CollapsibleCard(
    title: String,
    summary: String,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        // 和 SectionCard 保持一致，两种卡片混排时边界观感才统一
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(text = title, style = MaterialTheme.typography.titleMedium)
                    if (summary.isNotBlank()) {
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_down),
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(22.dp)
                        .rotate(if (expanded) 180f else 0f),
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 14.dp),
                    content = content,
                )
            }
        }
    }
}

/** 小标签，用于新鲜度、分类、菜系等 */
@Composable
fun Pill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 新鲜度徽标。颜色语义见 SemanticColors。 */
@Composable
fun FreshnessPill(freshness: String, modifier: Modifier = Modifier) {
    val color = SemanticColors.forFreshness(freshness)
    val label = when (freshness) {
        "新鲜" -> "新鲜"
        "正常" -> "正常"
        "尽快食用" -> "尽快吃"
        "已过期" -> "已过期"
        else -> freshness
    }
    Pill(text = label, color = color, modifier = modifier)
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier, text: String = "加载中…") {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 空状态 / 错误状态，带一个可选的操作按钮 */
@Composable
fun EmptyState(
    title: String,
    description: String = "",
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    /**
     * 紧凑模式：**嵌在列表里**用（不是整页空状态）时打开。
     *
     * 默认的 32dp 内边距是给「整页空状态」留的呼吸感；
     * 嵌在 LazyColumn 的一个 item 里时，32dp 上下 + 标题 + 间距 + 描述
     * 一共要 110dp 左右 —— 如果外面还套了个固定高度的 Box，**底部会被裁掉**。
     *
     * ⚠️ 2026-10-07 踩过：首页的「从扫描开始」被套在 `height(80.dp)` 里，
     * 结果标题下半截被切掉，看起来像字体渲染坏了。
     * **嵌在列表里的空状态一律用 compact = true，并且不要在外面套固定高度。**
     */
    compact: Boolean = false,
) {
    val pad = if (compact) 16.dp else 32.dp
    val gap = if (compact) 6.dp else 10.dp
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(pad),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            if (description.isNotBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (actionText != null && onAction != null) {
                Button(onClick = onAction) { Text(actionText) }
            }
        }
    }
}

/** 一行「标签 + 值」，用于详情页 */
@Composable
fun LabeledRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 营养数据统一显示为「约 XX」，并保留免责声明 */
fun approx(value: Double?, unit: String): String =
    if (value == null || value <= 0) "—" else "约 ${value.toInt()}$unit"
