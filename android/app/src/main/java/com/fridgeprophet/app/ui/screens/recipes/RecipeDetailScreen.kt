package com.fridgeprophet.app.ui.screens.recipes

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fridgeprophet.app.R
import com.fridgeprophet.app.data.remote.dto.CookPlan
import com.fridgeprophet.app.data.remote.dto.NutritionEstimate
import com.fridgeprophet.app.data.remote.dto.RecipeIngredientOut
import com.fridgeprophet.app.data.remote.dto.RecipeOut
import com.fridgeprophet.app.ui.components.EmptyState
import com.fridgeprophet.app.ui.components.FoodImage
import com.fridgeprophet.app.ui.components.LabeledRow
import com.fridgeprophet.app.ui.components.LoadingBox
import com.fridgeprophet.app.ui.components.Pill
import com.fridgeprophet.app.ui.components.SectionCard
import com.fridgeprophet.app.ui.components.approx
import com.fridgeprophet.app.ui.screens.home.formatQuantity
import com.fridgeprophet.app.ui.theme.SemanticColors

private const val DEFAULT_NUTRITION_DISCLAIMER =
    "营养数据由 AI 按食材和用量估算，不是称重实测值，仅供参考。" +
        "有慢性病、孕期或特殊饮食需求时，请遵医嘱，不要仅凭这里的数字做决定。"

/**
 * 菜谱详情。
 *
 * 页面的信息层级刻意按「做饭时的实际顺序」排：
 * 先看缺什么 → 再看怎么做 → 最后才看营养。营养放最后，因为它是参考信息而非行动指引。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeDetailScreen(
    recipeId: Int,
    onBack: () -> Unit,
    viewModel: RecipeDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.recipe?.name ?: "菜谱详情",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = "返回",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val recipe = state.recipe
            when {
                state.loading -> LoadingBox(text = "正在取菜谱…")

                recipe == null -> EmptyState(
                    title = "没找到这道菜",
                    description = state.error ?: "可能已经被删除了",
                    actionText = "返回",
                    onAction = onBack,
                )

                else -> RecipeDetailContent(
                    recipe = recipe,
                    buildingShopping = state.buildingShopping,
                    message = state.message,
                    error = state.error,
                    feedbackSent = state.feedbackSent,
                    loadingPlan = state.loadingPlan,
                    onDismissMessage = viewModel::clearMessages,
                    onBuildShopping = { viewModel.buildShoppingList(onSuccess = {}) },
                    onFeedback = viewModel::sendFeedback,
                    onCook = viewModel::openCookDialog,
                )
            }

            // 做菜确认弹窗。放在 Box 这一层而不是 content 内部 ——
            // 它是覆盖层，不该跟着内容一起滚。
            state.cookPlan?.let { plan ->
                CookDialog(
                    plan = plan,
                    amounts = state.cookAmounts,
                    cooking = state.cooking,
                    onAmountChange = viewModel::setCookAmount,
                    onConfirm = viewModel::confirmCook,
                    onDismiss = viewModel::dismissCookDialog,
                )
            }
        }
    }
}

@Composable
private fun RecipeDetailContent(
    recipe: RecipeOut,
    buildingShopping: Boolean,
    message: String?,
    error: String?,
    feedbackSent: String?,
    loadingPlan: Boolean,
    onDismissMessage: () -> Unit,
    onBuildShopping: () -> Unit,
    onFeedback: (String) -> Unit,
    onCook: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---------- 配图 ----------
        // 放在最顶上做「主视觉」，让用户先对成品有个印象再看细节。
        // 没有配图时 FoodImage 会自动画一块按菜名配色的占位图，不会留空白。
        FoodImage(
            imageUrl = recipe.imageUrl,
            name = recipe.name,
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(18.dp)),
        )

        // ---------- 头部概览 ----------
        SectionCard {
            Text(text = recipe.name, style = MaterialTheme.typography.headlineSmall)

            if (recipe.description.isNotBlank()) {
                Text(
                    text = recipe.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Row(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Pill(text = "${recipe.timeMinutes} 分钟", color = MaterialTheme.colorScheme.primary)
                Pill(text = difficultyLabel(recipe.difficulty), color = SemanticColors.normal)
                if (recipe.usesExpiring.isNotEmpty()) {
                    Pill(text = "消耗临期食材", color = SemanticColors.soon)
                }
            }

            if (recipe.tags.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    recipe.tags.forEach { tag ->
                        Pill(text = tag, color = SemanticColors.neutral)
                    }
                }
            }

            if (recipe.usesExpiring.isNotEmpty()) {
                Text(
                    text = "这道菜会用到你冰箱里快过期的：" +
                        recipe.usesExpiring.joinToString("、"),
                    style = MaterialTheme.typography.labelMedium,
                    color = SemanticColors.soon,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }

        // ---------- 提示条 ----------
        (error ?: message)?.let { text ->
            Surface(
                color = if (error != null) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                onClick = onDismissMessage,
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (error != null) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                    modifier = Modifier.padding(14.dp),
                )
            }
        }

        // ---------- 食材清单 ----------
        SectionCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "需要什么", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "共 ${recipe.ingredients.size} 样",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "打勾的 = 冰箱里已经有了",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )

            Column(modifier = Modifier.padding(top = 8.dp)) {
                recipe.ingredients.forEach { ingredient ->
                    IngredientRow(ingredient)
                }
            }

            if (recipe.missingIngredients.isNotEmpty()) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 10.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                Text(
                    text = "还差这几样",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Column(modifier = Modifier.padding(top = 6.dp)) {
                    recipe.missingIngredients.forEach { missing ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = missing.name,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = buildString {
                                    append(formatQuantity(missing.quantity, missing.unit))
                                    missing.estimatedPrice?.let { append(" · 约 ¥%.1f".format(it)) }
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // ---------- 烹饪步骤 ----------
        if (recipe.steps.isNotEmpty()) {
            SectionCard {
                Text(text = "怎么做", style = MaterialTheme.typography.titleMedium)
                Column(
                    modifier = Modifier.padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    recipe.steps.forEachIndexed { index, step ->
                        Row(verticalAlignment = Alignment.Top) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "${index + 1}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Text(
                                text = step,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 12.dp),
                            )
                        }
                    }
                }
            }
        }

        // ---------- 营养估算 ----------
        SectionCard {
            Text(text = "营养估算（每份）", style = MaterialTheme.typography.titleMedium)
            NutritionBlock(recipe.nutrition)
        }

        // ---------- 行为反馈 ----------
        SectionCard {
            Text(text = "这道菜怎么样？", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "你的选择会用来修正推荐偏好。跳过次数多了，这道菜就不会再出现。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onFeedback("favorite") },
                    modifier = Modifier.weight(1f),
                ) { Text("收藏") }

                OutlinedButton(
                    onClick = { onFeedback("skip") },
                    modifier = Modifier.weight(1f),
                ) { Text("不想吃") }
            }

            feedbackSent?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = SemanticColors.fresh,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }

        // ---------- 我做这道菜了 ----------
        // 「做完一道菜」的正规入口：先给一份扣减预览，用户能改数再确认。
        //
        // 原来那排按钮里的「做过」已经并到这里了 —— 两个按钮都表示
        // 「我做了这道菜」会让人不知道该点哪个，而弹窗里那个
        // 「同时更新冰箱库存」的勾选框正好覆盖了「只记录、不动库存」的旧用法。
        Button(
            onClick = onCook,
            enabled = !loadingPlan,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            if (loadingPlan) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(text = "我做这道菜了", style = MaterialTheme.typography.titleMedium)
            }
        }

        // ---------- 采购入口 ----------
        if (recipe.missingIngredients.isNotEmpty()) {
            Button(
                onClick = onBuildShopping,
                enabled = !buildingShopping,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ),
            ) {
                if (buildingShopping) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSecondary,
                    )
                } else {
                    Text(
                        text = "把缺的 ${recipe.missingIngredients.size} 样加进采购清单",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun IngredientRow(ingredient: RecipeIngredientOut) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(
                    if (ingredient.available) {
                        SemanticColors.fresh.copy(alpha = 0.16f)
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (ingredient.available) "✓" else "缺",
                style = MaterialTheme.typography.labelMedium,
                color = if (ingredient.available) {
                    SemanticColors.fresh
                } else {
                    MaterialTheme.colorScheme.error
                },
                fontWeight = FontWeight.SemiBold,
            )
        }

        Text(
            text = ingredient.name,
            style = MaterialTheme.typography.bodyLarge,
            color = if (ingredient.available) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
        )

        if (ingredient.optional) {
            Pill(
                text = "可选",
                color = SemanticColors.neutral,
                modifier = Modifier.padding(end = 8.dp),
            )
        }

        Text(
            text = formatQuantity(ingredient.quantity, ingredient.unit),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NutritionBlock(nutrition: NutritionEstimate) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        LabeledRow("热量", approx(nutrition.caloriesKcal, " 千卡"))
        Spacer(Modifier.height(6.dp))
        LabeledRow("蛋白质", approx(nutrition.proteinG, " g"))
        Spacer(Modifier.height(6.dp))
        LabeledRow("碳水", approx(nutrition.carbsG, " g"))
        Spacer(Modifier.height(6.dp))
        LabeledRow("脂肪", approx(nutrition.fatG, " g"))
        Spacer(Modifier.height(6.dp))
        LabeledRow("膳食纤维", approx(nutrition.fiberG, " g"))
        Spacer(Modifier.height(6.dp))
        LabeledRow("钠", approx(nutrition.sodiumMg, " mg"))

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 10.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Text(
            text = nutrition.disclaimer.ifBlank { DEFAULT_NUTRITION_DISCLAIMER },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun difficultyLabel(difficulty: String): String = when (difficulty) {
    "easy" -> "简单"
    "medium" -> "中等"
    else -> "较难"
}

/** 2.0 显示成「2」、1.5 显示成「1.5」，别让人看见「2.0」这种尾巴。 */
private fun fmtQty(v: Double?): String {
    if (v == null) return "0"
    return if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()
}

/**
 * 做菜确认弹窗。
 *
 * 扣库存**不可逆**（扣完那行食材就没了），所以每一步都要摊开给用户看：
 *   - 会用掉什么、冰箱里现在有多少
 *   - 每项扣多少，可以改；清空或填 0 就是不扣这项
 *   - 单位对不上的项**留空**，等用户自己填（后端不猜，见 cook-plan 的说明）
 *   - 一个勾选框决定到底动不动库存
 *
 * 取消 = 什么都不发生：库存不扣，也不记「做过」。
 */
@Composable
private fun CookDialog(
    plan: CookPlan,
    amounts: Map<Int, String>,
    cooking: Boolean,
    onAmountChange: (Int, String) -> Unit,
    onConfirm: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    // 默认勾上：多数人点「我做这道菜了」就是想同步库存。
    // key 用 recipeId，换一道菜时重置回默认值。
    var updateStock by remember(plan.recipeId) { mutableStateOf(true) }

    AlertDialog(
        // 提交过程中别让点外面关掉，否则请求还在飞、界面已经没了
        onDismissRequest = { if (!cooking) onDismiss() },
        title = { Text("做「${plan.recipeName}」") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "确认后按这些数量扣。数字能改，留空就不动它",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                plan.items.forEach { item ->
                    val id = item.stockItemId ?: return@forEach
                    val typed = amounts[id].orEmpty()

                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(text = item.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "冰箱还有 ${fmtQty(item.stockQuantity)}${item.stockUnit.orEmpty()}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        OutlinedTextField(
                            value = typed,
                            onValueChange = { onAmountChange(id, it) },
                            label = { Text(if (item.unitMatched) "扣减量" else "需要你填") },
                            suffix = { Text(item.stockUnit.orEmpty()) },
                            singleLine = true,
                            // 单位对不上又没填 → 标红提醒，但不拦着提交
                            // （用户可能就是想让这项不动）
                            isError = !item.unitMatched && typed.isBlank(),
                            supportingText = {
                                Text(
                                    text = if (item.unitMatched) {
                                        val need = "菜谱需要 ${fmtQty(item.needQuantity)}${item.needUnit}"
                                        if (item.willEmpty) "$need —— 按这个量扣完就用光了" else need
                                    } else {
                                        "库存记的是「${item.stockUnit}」，菜谱要「${item.needUnit}」，" +
                                            "没法自动换算，请按冰箱里的单位自己填"
                                    },
                                )
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                if (plan.missing.isNotEmpty()) {
                    Text(
                        text = "冰箱里没有（不参与扣减）：" +
                            plan.missing.joinToString("、") {
                                "${it.name} ${fmtQty(it.quantity)}${it.unit}"
                            },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = updateStock, onCheckedChange = { updateStock = it })
                    Text(text = "同时更新冰箱库存", style = MaterialTheme.typography.bodyMedium)
                }
                if (!updateStock) {
                    Text(
                        text = "不勾选就只记一笔「做过」，冰箱里的东西一样都不动。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !cooking,
                onClick = { onConfirm(updateStock) },
            ) { Text(if (cooking) "处理中…" else "确认") }
        },
        dismissButton = {
            TextButton(enabled = !cooking, onClick = onDismiss) { Text("取消") }
        },
    )
}
