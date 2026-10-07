package com.fridgeprophet.app.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fridgeprophet.app.R
import com.fridgeprophet.app.ui.components.EmptyState
import com.fridgeprophet.app.ui.components.FoodImage
import com.fridgeprophet.app.ui.components.FreshnessPill
import com.fridgeprophet.app.ui.components.Pill
import com.fridgeprophet.app.ui.components.SectionCard
import com.fridgeprophet.app.ui.components.TipBar
import com.fridgeprophet.app.ui.theme.SemanticColors
import java.util.Calendar

@Composable
fun HomeScreen(
    onOpenScan: () -> Unit,
    onOpenRecipe: (Int) -> Unit,
    onGoShopping: () -> Unit,
    onOpenTips: () -> Unit,
    onOpenTip: (String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var messageText by remember { mutableStateOf<String?>(null) }

    // 标语位每次进首页都换一条。
    //
    // 为什么用 `LaunchedEffect(Unit)` 而不是 `ON_RESUME` 或放在 ViewModel 的 init：
    //   - MainScaffold 用 `when (selectedTab)` 切换页面，切走时首页会**离开组合**，
    //     切回来时重新进入组合 → `LaunchedEffect(Unit)` 会再跑一次，
    //     正好对上「每次切换界面都换一条」。
    //   - ViewModel 比界面活得久（切 Tab 不会重建它），放 init 只在冷启动换一次。
    //   - ON_RESUME 只在「回到前台」时触发，切 Tab 不触发，也不满足要求。
    LaunchedEffect(Unit) { viewModel.loadTip() }

    LaunchedEffect(state.error, state.infoMessage) {
        messageText = state.error ?: state.infoMessage
    }

    if (state.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ---------- 问候 + 扫描入口 ----------
        item {
            Column {
                Text(
                    text = greeting(),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = "拍一张冰箱，我帮你决定吃什么",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        item {
            Button(
                onClick = onOpenScan,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_camera),
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                )
                Text(
                    text = "  扫描我的冰箱",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        // ---------- 食品安全标语位 ----------
        // 位置刻意选在「扫描」主按钮**下面**：它是有趣的附加内容，
        // 不该抢走唯一核心动作（扫描冰箱）的视觉重心。
        // 没有数据时 TipBar 自己返回、不占位，所以这里不用再判断。
        state.tip?.let { tip ->
            item {
                TipBar(tip = tip, onClick = { onOpenTip(tip.id) })
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = onOpenTips,
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    ) {
                        Text("全部贴士 ›", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        // ---------- 提示条 ----------
        messageText?.let { text ->
            item {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { messageText = null; viewModel.clearMessages() },
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }
        }

        // ---------- 我的冰箱 ----------
        item {
            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "我的冰箱",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    state.stats?.let { stats ->
                        Text(
                            text = "共 ${stats.totalKinds} 种",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (state.items.isEmpty()) {
                    Text(
                        text = "还没有食材。点上面的按钮扫描一次，AI 会自动识别并建立库存。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                } else {
                    Column(
                        modifier = Modifier.padding(top = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        state.items.take(5).forEach { item ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = item.foodName,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = formatQuantity(item.quantity, item.unit),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    FreshnessPill(item.freshness)
                                }
                            }
                        }
                        if (state.items.size > 5) {
                            Text(
                                text = "还有 ${state.items.size - 5} 种食材，去「冰箱」页查看全部",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // ---------- 即将过期 ----------
        if (state.expiring.isNotEmpty()) {
            item {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "即将过期",
                            style = MaterialTheme.typography.titleMedium,
                            color = SemanticColors.soon,
                        )
                    }
                    Text(
                        text = "优先消耗这些，别浪费",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Column(
                        modifier = Modifier.padding(top = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.expiring.take(4).forEach { item ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = item.foodName,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = expiryText(item.daysLeft),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = SemanticColors.forFreshness(item.freshness),
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---------- 今天吃什么 ----------
        item {
            Button(
                onClick = viewModel::generateRecipes,
                enabled = !state.generating,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ),
            ) {
                if (state.generating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSecondary,
                    )
                } else {
                    Text("今天吃什么？", style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        // ---------- 推荐结果 ----------
        if (state.recommendations.isNotEmpty()) {
            item {
                Text(
                    text = "今天推荐",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            items(state.recommendations) { recipe ->
                SectionCard(modifier = Modifier.clickable { recipe.id?.let(onOpenRecipe) }) {
                    // 配图放左边做成方图，比整宽大图省纵向空间 ——
                    // 首页要同时塞下冰箱概览、临期提醒和 3 道菜，得省着点用。
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FoodImage(
                            imageUrl = recipe.imageUrl,
                            name = recipe.name,
                            modifier = Modifier
                                .size(92.dp)
                                .clip(RoundedCornerShape(14.dp)),
                        )

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = recipe.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = "${recipe.timeMinutes} 分钟",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (recipe.description.isNotBlank()) {
                                Text(
                                    text = recipe.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                    maxLines = 2,
                                )
                            }
                            Row(
                                modifier = Modifier.padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                if (recipe.usesExpiring.isNotEmpty()) {
                                    Pill(text = "消耗临期", color = SemanticColors.soon)
                                }
                                recipe.tags.take(1).forEach { tag ->
                                    Pill(text = tag, color = MaterialTheme.colorScheme.primary)
                                }
                                if (recipe.missingIngredients.isEmpty()) {
                                    Pill(text = "食材齐全", color = SemanticColors.fresh)
                                } else {
                                    Pill(
                                        text = "缺 ${recipe.missingIngredients.size} 样",
                                        color = MaterialTheme.colorScheme.secondary,
                                    )
                                }
                            }
                        }
                    }

                    if (recipe.missingIngredients.isNotEmpty()) {
                        Text(
                            text = "缺：" + recipe.missingIngredients.joinToString("、") { it.name },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        TextButton(
                            onClick = onGoShopping,
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                        ) {
                            Text("去生成采购清单 →")
                        }
                    }
                }
            }
        }

        if (state.items.isEmpty() && state.recommendations.isEmpty() && state.error == null) {
            item {
                // ⚠️ 这里**不要**套固定高度的 Box。
                // 原来写的是 `.height(80.dp)`，而 EmptyState 的内容
                //（32dp 上下内边距 + 标题 + 间距 + 描述）需要 110dp 左右 ——
                // 结果「从扫描开始」的标题下半截被裁掉，看起来像字体坏了。
                // 用 compact 让它自己在列表里撑开。
                EmptyState(
                    title = "冰箱还空着",
                    description = "先拍一张照片，我看看里面有什么",
                    compact = true,
                )
            }
        }

        item { Box(Modifier.height(24.dp)) }
    }
}

private fun greeting(): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when (hour) {
        in 5..10 -> "早上好，今天吃什么？"
        in 11..13 -> "中午好，今天吃什么？"
        in 14..17 -> "下午好，今天吃什么？"
        in 18..22 -> "晚上好，今天吃什么？"
        else -> "夜深了，明天吃什么？"
    }
}

private fun expiryText(daysLeft: Int?): String = when {
    daysLeft == null -> "无保质期信息"
    daysLeft < 0 -> "已过期 ${-daysLeft} 天"
    daysLeft == 0 -> "今天到期"
    daysLeft == 1 -> "预计明天过期"
    else -> "还剩 $daysLeft 天"
}

/** 数量显示去掉多余的小数点：6.0 → 6，450.0 → 450 */
fun formatQuantity(quantity: Double, unit: String): String {
    val value = if (quantity % 1.0 == 0.0) quantity.toInt().toString() else quantity.toString()
    return "$value$unit"
}

@Composable
fun TagRow(vararg tags: Pair<String, androidx.compose.ui.graphics.Color>) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tags.forEach { (text, color) -> Pill(text = text, color = color) }
    }
}
