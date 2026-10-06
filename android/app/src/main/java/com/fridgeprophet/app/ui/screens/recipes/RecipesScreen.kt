package com.fridgeprophet.app.ui.screens.recipes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fridgeprophet.app.data.remote.dto.DishRecommendation
import com.fridgeprophet.app.data.remote.dto.RecipeOut
import com.fridgeprophet.app.ui.components.EmptyState
import com.fridgeprophet.app.ui.components.FoodImage
import com.fridgeprophet.app.ui.components.LoadingBox
import com.fridgeprophet.app.ui.components.Pill
import com.fridgeprophet.app.ui.components.SectionCard
import com.fridgeprophet.app.ui.theme.SemanticColors

@Composable
fun RecipesScreen(
    onOpenRecipe: (Int) -> Unit,
    viewModel: RecipesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<RecipeOut?>(null) }
    val visible = viewModel.visibleRecipes()

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text = "菜谱", style = MaterialTheme.typography.headlineSmall)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                viewModel.filters.forEach { filter ->
                    FilterChip(
                        selected = state.filter == filter,
                        onClick = { viewModel.setFilter(filter) },
                        label = { Text(filter) },
                    )
                }
            }

            Button(
                onClick = viewModel::generate,
                enabled = !state.generating,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                if (state.generating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    // ⚠️ 这里**不要再写「AI 正在配菜，约需 10~20 秒」**。
                    // 后端现在是从菜品库按食材挑（查表，不调 AI），毫秒级。
                    // 写「约需 20 秒」会让人以为卡住了 —— 实际上早就好了。
                    Text(
                        text = "  正在从菜品库挑菜…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    // 文案跟着实现走：以前是「让 AI 编新菜」，
                    // 现在是「按你冰箱里的食材，从菜品库里挑能做的」
                    Text("看看现在能做什么菜")
                }
            }
        }

        (state.error ?: state.info)?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.error != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.clearMessages() }
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        when {
            // ①「推荐」标签：显示内置菜品库（按冰箱现有食材匹配出来的）
            //
            // 放在最前面并作为默认标签：打开菜谱页最常见的诉求是
            // 「我今天能做什么」—— 这个问题**查库就能立刻回答**
            //（毫秒级、不花钱、菜品有专属配图）。AI 生成要等 20 秒，
            // 不该是用户看到的第一屏。
            viewModel.isRecommendTab() -> {
                val recs = viewModel.visibleRecommendations()
                when {
                    state.loadingRecommend -> LoadingBox()
                    recs.isEmpty() -> EmptyState(
                        title = "还没有能推荐的菜",
                        description = "先去冰箱页加几样食材 —— 这里会立刻告诉你能做什么，不用等 AI",
                    )
                    else -> LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item {
                            Text(
                                text = "根据你冰箱里的食材，有 ${state.readyCount} 道现在就能做 · 点任意一道看做法",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                            )
                        }
                        items(items = recs, key = { it.name }) { dish ->
                            DishCard(
                                dish = dish,
                                materializing = state.materializing == dish.name,
                                // 生成期间把其它卡片一并置灰：不是禁用功能，
                                // 是让用户看见「正在忙，别连点」。点上去没反应
                                // 比灰掉更让人烦躁。
                                enabled = state.materializing == null,
                                onClick = { viewModel.materialize(dish, onOpenRecipe) },
                            )
                        }
                        item { Box(Modifier.height(24.dp)) }
                    }
                }
            }

            state.loading -> LoadingBox()
            visible.isEmpty() -> EmptyState(
                title = if (state.recipes.isEmpty()) "还没有菜谱" else "这个筛选下没有菜谱",
                description = if (state.recipes.isEmpty()) {
                    "点上面的按钮，让 AI 根据你冰箱里的食材推荐几道菜"
                } else {
                    "换个筛选条件试试"
                },
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 这里**刻意不做「已备齐的菜沉底 + 降透明度」**。
                //
                // 曾经做过，但那是把需求理解偏了：用户要弱化的是
                // 「采购清单里已勾选（已买完）的条目」，不是菜谱。
                // 菜谱列表一暗，看着像「这些菜坏了/失效了」，反而让人不敢点。
                // 现在只保留一个「已备齐」小标签做提示，卡片本身正常显示。
                items(
                    items = visible,
                    key = { recipe -> recipe.id ?: recipe.name.hashCode() },
                ) { recipe ->
                    RecipeCard(
                        recipe = recipe,
                        onClick = { recipe.id?.let(onOpenRecipe) },
                        // 系统内置菜谱（菜品库那 192 道）**不显示删除按钮**：
                        // 它是所有用户共用的一条记录，后端会拒绝删除（403）。
                        // 显示一个点了必然报错的按钮，比不显示更糟。
                        onDelete = if (recipe.isBuiltin) null else ({ deleting = recipe }),
                    )
                }
                item { Box(Modifier.height(24.dp)) }
            }
        }
    }

    deleting?.let { recipe ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除菜谱") },
            text = { Text("确定删除「${recipe.name}」吗？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(recipe)
                    deleting = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            },
        )
    }
}

/**
 * 菜谱卡片：**左侧文字、右侧小图**。
 *
 * 为什么图不放顶部通栏大图：一张大图就占 140dp，一屏只能看一道菜。
 * 换成右侧 88dp 小图后，一眼能扫四五道，翻找效率高得多。
 * 图放右侧是因为扫读动线是「先读菜名、再看图确认」，图在文字之后出现更顺。
 *
 * 注意这里**没有 dimmed 参数**。曾经按「已备齐就降透明度并沉底」做过，
 * 但那是把需求理解偏了：用户要弱化的是**采购清单里已勾选的条目**，不是菜谱。
 * 菜谱一暗看着像「这些菜失效了」，反而让人不敢点。现在只留一个「已备齐」标签提示。
 */
@Composable
fun RecipeCard(
    recipe: RecipeOut,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        // 和 SectionCard 统一的发丝边框，全 App 卡片边界观感一致
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
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
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }

                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // 内置菜品库的标记。列表里现在同时有「你自己的菜谱」和
                    // 「菜品库那 192 道」，不区分的话用户会以为
                    // 「我什么时候生成过这么多菜」。
                    if (recipe.isBuiltin) {
                        Pill(text = "菜品库", color = MaterialTheme.colorScheme.tertiary)
                    }
                    if (recipe.usesExpiring.isNotEmpty()) {
                        Pill(text = "消耗临期", color = SemanticColors.soon)
                    }
                    if (recipe.ready) {
                        Pill(text = "已备齐", color = SemanticColors.fresh)
                    } else {
                        Pill(
                            text = "缺 ${recipe.missingIngredients.size} 样",
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    recipe.tags.take(2).forEach { tag ->
                        Pill(text = tag, color = MaterialTheme.colorScheme.primary)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = buildString {
                            append("难度 ")
                            append(
                                when (recipe.difficulty) {
                                    "easy" -> "简单"
                                    "medium" -> "中等"
                                    else -> "较难"
                                }
                            )
                            recipe.nutrition.caloriesKcal?.let {
                                append(" · 约 ${it.toInt()} 千卡/份")
                            }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (onDelete != null) {
                        TextButton(onClick = onDelete) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                if (recipe.missingIngredients.isNotEmpty()) {
                    Text(
                        text = "缺：" + recipe.missingIngredients.joinToString("、") { it.name },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Normal,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            // 右侧小图。圆角裁剪是必要的 —— 不裁的话方角图片压在圆角卡片里会很突兀。
            FoodImage(
                imageUrl = recipe.imageUrl,
                name = recipe.name,
                modifier = Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
        }
    }
}

/**
 * 推荐卡片：**左侧文字、右侧小图**（和 RecipeCard 同一套视觉语言）。
 *
 * 和 `RecipeCard` 分开写而不是加参数复用：两者数据源完全不同
 *（一个是数据库里的 RecipeOut、一个是菜品库的 DishRecommendation），
 * 字段和交互都不一样。硬塞进一个 composable 会让两边都变复杂。
 *
 * ## 点击行为
 *
 * 菜品库里只有「菜名 + 必需食材 + 配图」，**没有步骤和营养** ——
 * 但**这些已经离线生成好、作为系统内置菜谱存在数据库里了**
 *（见 `tools/generate-dish-recipes.py`）。所以点击就是
 * 「按菜名查库、拿回完整菜谱、跳详情页」，毫秒级。
 *
 * 之前是「点开才让 AI 现写」，第一次点要等十几秒。现在不是了，
 * 所以卡片上的等待文案也从「约需 10~20 秒」改成了「正在打开做法…」。
 */
@Composable
private fun DishCard(
    dish: DishRecommendation,
    materializing: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    SectionCard(
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            // 生成期间其它卡片轻微压暗，把注意力集中到正在转圈的那张
            .alpha(if (enabled) 1f else 0.55f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = dish.name,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    // 能做的用主色、差一点的用次要色 —— 一眼能扫出哪些马上能开火
                    Text(
                        text = if (dish.ready) "现在能做" else "缺 ${dish.missing.size} 样",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (dish.ready) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Spacer(Modifier.height(2.dp))
                if (materializing) {
                    Text(
                        // ⚠️ 这里**不要**再写「约需 10~20 秒」。
                        // 菜品库那 192 道菜的做法已经离线生成好、存在库里了，
                        // 点开是直接查库返回（毫秒级）。写十几秒会让人以为卡住，
                        // 实际上早就好了。
                        text = "正在打开做法…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Text(
                        text = dish.category,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (dish.missing.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "还缺：" + dish.missing.joinToString("、"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // 生成中把配图换成转圈 —— 位置不变，卡片不会跳高
            if (materializing) {
                Box(
                    modifier = Modifier.size(88.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                    )
                }
            } else {
                FoodImage(
                    imageUrl = dish.imageUrl,
                    name = dish.name,
                    modifier = Modifier
                        .size(88.dp)
                        .clip(RoundedCornerShape(14.dp)),
                )
            }
        }
    }
}
