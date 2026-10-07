package com.fridgeprophet.app.ui.screens.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.fridgeprophet.app.ui.components.DateField
import com.fridgeprophet.app.ui.components.Pill
import com.fridgeprophet.app.ui.components.SectionCard
import com.fridgeprophet.app.ui.theme.SemanticColors
import java.time.LocalDate

/**
 * 阶段二：识别结果确认。
 *
 * 策划书里反复强调的一点——AI 的结果不能直接入库，
 * 必须让用户核对、修改、勾选后才写入。这个界面就是那道闸门。
 */
@Composable
fun ResultPhase(
    state: ScanUiState,
    onBack: () -> Unit,
    onRetake: () -> Unit,
    onToggle: (Int) -> Unit,
    onRename: (Int, String) -> Unit,
    onQuantity: (Int, String) -> Unit,
    onUnit: (Int, String) -> Unit,
    onLocation: (Int, String) -> Unit,
    onPurchaseDate: (Int, java.time.LocalDate?) -> Unit,
    onExpiryDate: (Int, java.time.LocalDate?) -> Unit,
    onRemove: (Int) -> Unit,
    onCategory: (Int, String) -> Unit,
    onAddManual: () -> Unit,
    onConfirm: () -> Unit,
    onDismissError: () -> Unit,
) {
    val includedCount = state.foods.count { it.included }
    val lowConfidenceCount = state.foods.count { it.included && it.lowConfidence }
    val invalidDateCount = state.foods.count { it.included && it.hasInvalidDates }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        // ---------- 顶部 ----------
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "AI 发现 ${state.foods.size} 种食材",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "请核对名称和数量，确认无误后再加入冰箱。可以直接修改。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (state.modelName == "mock") {
                    Pill(text = "演示数据", color = SemanticColors.soon)
                }
                if (lowConfidenceCount > 0) {
                    Pill(text = "$lowConfidenceCount 项把握不大，请重点核对", color = SemanticColors.soon)
                }
                if (invalidDateCount > 0) {
                    Pill(text = "$invalidDateCount 项日期填反了", color = SemanticColors.expired)
                }
            }
        }

        state.error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        // ---------- 可编辑列表 ----------
        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(state.foods) { index, food ->
                    FoodEditCard(
                        food = food,
                        categories = state.categoryNames,
                        onCategory = { onCategory(index, it) },
                        onToggle = { onToggle(index) },
                        onRename = { onRename(index, it) },
                        onQuantity = { onQuantity(index, it) },
                        onUnit = { onUnit(index, it) },
                        onLocation = { onLocation(index, it) },
                        onPurchaseDate = { onPurchaseDate(index, it) },
                        onExpiryDate = { onExpiryDate(index, it) },
                        onRemove = { onRemove(index) },
                    )
                }

                item {
                    TextButton(
                        onClick = onAddManual,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("+ 手动补充一条 AI 漏掉的") }
                }

                item { Box(Modifier.height(8.dp)) }
            }
        }

        // ---------- 底部操作 ----------
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onRetake,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp),
                ) { Text("重新拍摄") }

                Button(
                    onClick = onConfirm,
                    enabled = includedCount > 0 && !state.confirming,
                    modifier = Modifier
                        .weight(2f)
                        .height(50.dp),
                ) {
                    if (state.confirming) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text("确认加入冰箱（$includedCount）")
                    }
                }
            }
            Text(
                text = "加进来之后，我帮你盯着新鲜度",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FoodEditCard(
    food: EditableFood,
    onToggle: () -> Unit,
    onRename: (String) -> Unit,
    onQuantity: (String) -> Unit,
    onUnit: (String) -> Unit,
    onLocation: (String) -> Unit,
    onPurchaseDate: (LocalDate?) -> Unit,
    onExpiryDate: (LocalDate?) -> Unit,
    onRemove: () -> Unit,
    categories: List<String>,
    onCategory: (String) -> Unit,
) {
    // 日期区默认收起：一次扫描常有十几种食材，全展开会让页面长得没法看。
    // 只在用户真的想调日期时才展开那一项。
    var showDates by remember(food.name) { mutableStateOf(food.hasInvalidDates) }

    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = food.included, onCheckedChange = { onToggle() })

            Column(modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                    value = food.name,
                    onValueChange = onRename,
                    label = { Text("食材名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = food.quantityText,
                onValueChange = onQuantity,
                label = { Text("数量") },
                singleLine = true,
                modifier = Modifier.weight(1.4f),
            )
            OutlinedTextField(
                value = food.unit,
                onValueChange = onUnit,
                label = { Text("单位") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }

        // 类别选择：**点开折叠、选完自动收回**。
        //
        // 为什么要有这个（Ethan 2026-10-07 提的）：像「AD钙奶」「果粒奶优」
        // 这种具体商品没法一个个加进词库，而名字里的关键词还会把它们归错
        //（「奶」→ 蛋奶、「果」→ 水果）。让用户点一下类别，后端就用
        // 该类别的万能图 —— 长尾商品靠用户自己归一次类就够了。
        if (categories.isNotEmpty()) {
            var categoryOpen by remember { mutableStateOf(false) }
            Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                OutlinedButton(
                    onClick = { categoryOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("类别：${food.category}")
                    Spacer(Modifier.weight(1f))
                    Text("▾")
                }
                DropdownMenu(
                    expanded = categoryOpen,
                    onDismissRequest = { categoryOpen = false },
                ) {
                    categories.forEach { name ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = {
                                onCategory(name)
                                categoryOpen = false
                            },
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("冷藏", "冷冻", "常温").forEach { loc ->
                FilterChip(
                    selected = food.storageLocation == loc,
                    onClick = { onLocation(loc) },
                    label = { Text(loc) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // ---------- 购买日期 / 保质期 ----------
        TextButton(
            onClick = { showDates = !showDates },
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
        ) {
            Text(
                text = dateSummary(food) + if (showDates) "  ▲" else "  ▼",
                style = MaterialTheme.typography.labelMedium,
                color = if (food.hasInvalidDates) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }

        if (showDates) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DateField(
                    label = "购买日期",
                    value = food.purchaseDate,
                    onValueChange = onPurchaseDate,
                    placeholder = "留空按今天算",
                )
                DateField(
                    label = "过期日期",
                    value = food.expiryDate,
                    onValueChange = onExpiryDate,
                    placeholder = "留空按食材估算",
                    supportingText = if (food.hasInvalidDates) "过期日期不能早于购买日期" else null,
                )
                Text(
                    text = "留空也行，我按食材估个大概" +
                        "手上这盒已经买了几天的话，把购买日期改成实际那天更准。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val confidenceText = "识别把握 ${(food.confidence * 100).toInt()}%"
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        (if (food.lowConfidence) SemanticColors.soon else SemanticColors.fresh)
                            .copy(alpha = 0.12f)
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = confidenceText,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (food.lowConfidence) SemanticColors.soon else SemanticColors.fresh,
                    fontWeight = FontWeight.Medium,
                )
            }
            TextButton(onClick = onRemove) {
                Text("移除", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** 收起状态下也要能一眼看出日期被改过，否则用户不知道自己动过什么 */
private fun dateSummary(food: EditableFood): String {
    val parts = buildList {
        food.purchaseDate?.let { add("购买 ${it.monthValue}月${it.dayOfMonth}日") }
        food.expiryDate?.let { add("到期 ${it.monthValue}月${it.dayOfMonth}日") }
    }
    return if (parts.isEmpty()) "购买日期 / 保质期（默认按今天 + 系统估算）" else parts.joinToString(" · ")
}
