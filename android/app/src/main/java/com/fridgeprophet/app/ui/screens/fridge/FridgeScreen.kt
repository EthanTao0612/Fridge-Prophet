package com.fridgeprophet.app.ui.screens.fridge

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fridgeprophet.app.data.remote.dto.InventoryOut
import com.fridgeprophet.app.ui.components.DateField
import com.fridgeprophet.app.ui.components.EmptyState
import com.fridgeprophet.app.ui.components.FoodImage
import com.fridgeprophet.app.ui.components.FreshnessPill
import com.fridgeprophet.app.ui.components.LoadingBox
import com.fridgeprophet.app.ui.components.SectionCard
import com.fridgeprophet.app.ui.components.toLocalDateOrNull
import com.fridgeprophet.app.ui.screens.home.formatQuantity
import com.fridgeprophet.app.ui.theme.SemanticColors
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private val LOCATIONS = listOf(null, "冷藏", "冷冻", "常温")

@Composable
fun FridgeScreen(viewModel: FridgeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<InventoryOut?>(null) }
    var deleting by remember { mutableStateOf<InventoryOut?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        // ---------- 搜索 + 位置筛选 ----------
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = state.keyword,
                onValueChange = viewModel::setKeyword,
                label = { Text("搜索食材") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                LOCATIONS.forEach { location ->
                    val label = location ?: "全部"
                    FilterChip(
                        selected = state.locationFilter == location,
                        onClick = { viewModel.setLocationFilter(location) },
                        label = { Text(label) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "共 ${state.items.size} 种食材",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { showAddDialog = true }) { Text("+ 手动添加") }
            }
        }

        state.error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.clearError() }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        when {
            state.loading -> LoadingBox()
            state.items.isEmpty() -> EmptyState(
                title = "这里还空着",
                description = "去首页扫描一次冰箱，或者手动添加食材",
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.items, key = { it.id }) { item ->
                    SectionCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FoodImage(
                                imageUrl = item.imageUrl,
                                name = item.foodName,
                                modifier = Modifier
                                    .size(58.dp)
                                    .clip(RoundedCornerShape(16.dp)),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.foodName,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = buildString {
                                        append(item.storageLocation)
                                        append(" · ")
                                        append(formatQuantity(item.quantity, item.unit))
                                        if (item.category != "其他") {
                                            append(" · ")
                                            append(item.category)
                                        }
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = expiryLine(item.daysLeft, item.expiryDate),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = SemanticColors.forFreshness(item.freshness),
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                FreshnessPill(item.freshness)
                                IconButton(onClick = { editing = item }) {
                                    Text("改", style = MaterialTheme.typography.labelLarge)
                                }
                                IconButton(onClick = { deleting = item }) {
                                    Text(
                                        "删",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
                item { Box(Modifier.height(24.dp)) }
            }
        }
    }

    if (showAddDialog) {
        AddItemDialog(
            busy = state.busy,
            onDismiss = { showAddDialog = false },
            onConfirm = { name, qty, unit, location, purchaseDate, shelfLifeDays ->
                viewModel.addItem(name, qty, unit, location, purchaseDate, shelfLifeDays)
                showAddDialog = false
            },
        )
    }

    editing?.let { item ->
        EditItemDialog(
            item = item,
            onDismiss = { editing = null },
            onConfirm = { qty, purchaseDate, expiryDate ->
                viewModel.updateItem(item, qty, purchaseDate, expiryDate)
                editing = null
            },
        )
    }

    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除食材") },
            text = { Text("确定把「${item.foodName}」从冰箱里移除吗？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(item)
                    deleting = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            },
        )
    }
}

private fun expiryLine(daysLeft: Int?, expiryDate: String?): String {
    if (expiryDate == null) return "无保质期信息"
    val day = when {
        daysLeft == null -> ""
        daysLeft < 0 -> "已过期 ${-daysLeft} 天"
        daysLeft == 0 -> "今天到期"
        daysLeft == 1 -> "明天到期"
        else -> "还剩 $daysLeft 天"
    }
    return "$expiryDate · $day"
}

/**
 * 常用单位快捷项。
 *
 * 保留自由输入是为了「半把」「1 提」这种没法枚举的量；
 * 但「克 / 千克 / 毫升」这些高频单位点一下比手打快，
 * 也不容易打成「kg」「G」这种后端认不出的写法 ——
 * 单位对不上会被当成「大概率有」，采购清单就会漏东西。
 */
private val UNIT_PRESETS = listOf(
    "个", "克", "千克", "毫升", "升", "斤", "盒", "袋", "把", "颗",
)

/** 保质期快捷选项。null = 交给后端按食材名估算。 */
private val SHELF_LIFE_PRESETS = listOf(
    null to "系统估算",
    3 to "3 天",
    7 to "7 天",
    15 to "15 天",
    30 to "30 天",
    90 to "90 天",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddItemDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, Double, String, String, LocalDate?, Int?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("1") }
    var unit by remember { mutableStateOf("个") }
    var location by remember { mutableStateOf("冷藏") }
    // 购买日期默认今天 —— 绝大多数情况下用户就是刚买回来才录入
    var purchaseDate by remember { mutableStateOf(LocalDate.now()) }
    var shelfLifeDays by remember { mutableStateOf<Int?>(null) }
    // 手填的天数。和 shelfLifeDays 是同一个值的两种表达：
    // 输入框里保留原文（方便继续改），shelfLifeDays 才是提交给后端的那个。
    var customDays by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手动添加食材") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称，如 鸡蛋") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = quantity,
                        onValueChange = { quantity = it },
                        label = { Text("数量") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = unit,
                        onValueChange = { unit = it },
                        label = { Text("单位") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UNIT_PRESETS.forEach { u ->
                        FilterChip(
                            selected = unit == u,
                            onClick = { unit = u },
                            label = { Text(u) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("冷藏", "冷冻", "常温").forEach { loc ->
                        FilterChip(
                            selected = location == loc,
                            onClick = { location = loc },
                            label = { Text(loc) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                DateField(
                    label = "购买日期",
                    value = purchaseDate,
                    onValueChange = { purchaseDate = it },
                    supportingText = "留空按今天算。买回来放了两天才录入的话，改成实际购买那天更准。",
                )

                Text(
                    text = "保质期",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SHELF_LIFE_PRESETS.forEach { (days, label) ->
                        FilterChip(
                            // 手填了天数就以手填的为准，快捷项取消高亮
                            selected = customDays.isBlank() && shelfLifeDays == days,
                            onClick = {
                                shelfLifeDays = days
                                customDays = ""
                            },
                            label = { Text(label) },
                        )
                    }
                }
                // 包装上印的是「保质期 45 天」这种不在预设里的值，必须能手填。
                // 和快捷项是互斥的：填了就以手填的为准。
                OutlinedTextField(
                    value = customDays,
                    onValueChange = { text ->
                        customDays = text.filter { it.isDigit() }.take(4)
                        shelfLifeDays = customDays.toIntOrNull()
                    },
                    label = { Text("或自己填天数") },
                    placeholder = { Text("比如 45") },
                    suffix = { Text("天") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "不确定就选「系统估算」，会按食材名查内置的默认保质期。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && !busy,
                onClick = {
                    onConfirm(
                        name,
                        quantity.toDoubleOrNull() ?: 1.0,
                        unit.ifBlank { "个" },
                        location,
                        purchaseDate,
                        shelfLifeDays,
                    )
                },
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 修改已有食材：数量 + 购买日期 + （过期日期 ⟷ 剩余天数）。
 *
 * ## 为什么购买日期和过期日期是两个独立输入
 *
 * 用户手上可能只有其中一个信息：牛奶盒上印着到期日，但购买日期是自己记的。
 * 两个都填时后端会校验先后顺序，填反了会被拒绝并给出提示。
 *
 * ## 「剩余天数」和「过期日期」是同一个信息的两种输入方式
 *
 * 有人看着包装上印的「保质期至 2026-10-05」填日期，
 * 也有人脑子里只有「还能放 3 天」。两种都要支持，所以两个输入框**双向联动**：
 * 填了天数自动算出到期日，选了到期日自动回填天数。
 *
 * ⚠️ 联动的坑：不能两边互相监听对方的派生值，否则会形成回环 ——
 * 「输入 30 → 算出日期 → 日期反过来把天数框重写成 30」，
 * 用户正在输入的字符会被回写覆盖掉，表现为「打字打不进去」。
 * 现在的做法是**只在下游回写一次**：天数框改 → 更新日期；
 * 日期框改 → 更新天数框。两个方向都由「用户动作」触发，不由状态变化触发，
 * 所以不会自己转起来。
 */
@Composable
private fun EditItemDialog(
    item: InventoryOut,
    onDismiss: () -> Unit,
    onConfirm: (Double, LocalDate?, LocalDate?) -> Unit,
) {
    var value by remember {
        mutableStateOf(
            if (item.quantity % 1.0 == 0.0) item.quantity.toInt().toString()
            else item.quantity.toString()
        )
    }
    // 「今天」只算一次。如果每次重组都调 LocalDate.now()，
    // 用户在午夜前后编辑时，算出来的到期日会在两个值之间跳。
    val today = remember { LocalDate.now() }

    var purchaseDate by remember { mutableStateOf(item.purchaseDate.toLocalDateOrNull()) }
    var expiryDate by remember { mutableStateOf(item.expiryDate.toLocalDateOrNull()) }

    // 天数框的文本状态。初始值由已有的到期日反推 ——
    // 这样打开弹窗时两个框显示的是同一个事实，而不是一个有一个空。
    var daysText by remember {
        mutableStateOf(
            item.expiryDate.toLocalDateOrNull()
                ?.let { ChronoUnit.DAYS.between(today, it).toString() }
                .orEmpty()
        )
    }

    // 先取成局部 val：`by remember` 是委托属性，Kotlin 不允许对它做智能转换，
    // 直接写 `expiryDate < purchaseDate` 会编译报错（Smart cast is impossible）。
    val purchase = purchaseDate
    val expiry = expiryDate
    val invalidOrder = purchase != null && expiry != null && expiry < purchase

    // 已经过期或今天到期时给个提示色，避免用户以为「0 天」是没填
    val daysValue = daysText.toIntOrNull()
    val daysHint: String? = when {
        invalidOrder -> "过期日期不能早于购买日期"
        daysValue == null -> null
        daysValue < 0 -> "这个日期已经过去了"
        daysValue == 0 -> "今天到期"
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改「${item.foodName}」") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("剩余数量（${item.unit}）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                DateField(
                    label = "购买日期",
                    value = purchaseDate,
                    onValueChange = { purchaseDate = it },
                )

                OutlinedTextField(
                    value = daysText,
                    onValueChange = { raw ->
                        // 只留数字和一个开头的负号，挡住「3天」「约5」这类输入。
                        // 允许负数是故意的：东西已经过期了也应该能如实记下来，
                        // 界面上会用「这个日期已经过去了」提示，而不是拒绝输入。
                        val cleaned = raw.filterIndexed { index, c ->
                            c.isDigit() || (c == '-' && index == 0)
                        }
                        daysText = cleaned
                        // 清空 = 「没有保质期信息」，和日期框的「清除」是同一个语义
                        expiryDate = cleaned.toIntOrNull()?.let { today.plusDays(it.toLong()) }
                    },
                    label = { Text("剩余天数") },
                    placeholder = { Text("填天数会自动算到期日") },
                    singleLine = true,
                    isError = invalidOrder,
                    supportingText = daysHint?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )

                DateField(
                    label = "过期日期",
                    value = expiryDate,
                    onValueChange = { picked ->
                        expiryDate = picked
                        daysText = picked
                            ?.let { ChronoUnit.DAYS.between(today, it).toString() }
                            .orEmpty()
                    },
                    supportingText = if (invalidOrder) "过期日期不能早于购买日期" else null,
                )

                Text(
                    text = "「剩余天数」和「过期日期」填哪个都行，会自动换算。" +
                        "清空表示「没有保质期信息」，系统不会再提醒。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !invalidOrder,
                onClick = {
                    onConfirm(
                        value.toDoubleOrNull() ?: item.quantity,
                        purchaseDate,
                        expiryDate,
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
