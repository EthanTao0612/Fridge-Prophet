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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
                    // 配上文字说明，别只转圈。
                    // 接上真实 AI 之后生成一道菜要好几秒（实测生成 3 道约 20 秒），
                    // 光转圈用户会以为卡死了，甚至反复点。
                    // 把「大概要等多久」说出来，等待就变得可以忍受。
                    Text(
                        text = "  AI 正在配菜，约需 10~20 秒",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text("根据冰箱库存生成新菜谱")
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
                        onDelete = { deleting = recipe },
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
