package com.fridgeprophet.app.ui.screens.fridge

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fridgeprophet.app.data.remote.dto.FoodCategoryOut
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

    // ---------- 折叠箱管理 ----------
    //
    // 四个对话框各自独立一个状态，而不是合成一个 sealed class。
    // 理由：它们不会同时出现（都是模态的），合成一个反而要写一堆 when 分支，
    // 每个分支还得处理「另一个字段是 null」的情况。
    var showCreateBox by remember { mutableStateOf(false) }
    var renamingBox by remember { mutableStateOf<FoodCategoryOut?>(null) }
    var deletingBox by remember { mutableStateOf<FoodCategoryOut?>(null) }
    var addingToBox by remember { mutableStateOf<FoodCategoryOut?>(null) }

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

            // ⚠️ 这里的条件不能只判 items。
            //
            // 原来写的是 `state.items.isEmpty() -> EmptyState(...)`，
            // 结果是：**只要列表为空，整个 LazyColumn 就不渲染** ——
            // 于是「新建 / 管理折叠箱」的入口也跟着消失了。
            // 两个会踩到的场景：
            //   ① 搜索关键词没匹配到任何食材 → 想顺手改个箱子，发现入口没了
            //   ② 冰箱真的空但已经建过箱子 → 连删都删不掉
            //
            // 所以加上 `&& state.boxes.isEmpty()`：只有「真空 + 没箱子」
            // 才给整页空状态。其它情况都渲染列表，保证折叠箱始终可管理。
            state.items.isEmpty() && state.boxes.isEmpty() -> EmptyState(
                title = "这里还空着",
                description = "去首页扫描一次冰箱，或者手动添加食材",
            )

            else -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // ① 折叠箱放最上面 —— 用户自己建的，优先级最高。
                //
                // **这一节永远显示**，即使一个箱子都没有：
                // 否则用户第一次进来根本找不到「新建」的入口。
                // 空的时候用一行说明代替列表，顺便教会用户折叠箱是干什么的。
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "我的折叠箱",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { showCreateBox = true }) { Text("+ 新建") }
                    }
                }

                if (state.boxGroups.isEmpty()) {
                    item {
                        Text(
                            text = "折叠箱是你自己定的分组，比如「火锅材料」「早餐」。"
                                + "和下面的「按分类」不冲突 —— 一样东西可以同时属于两边。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                        )
                    }
                } else {
                    // 用 forEachIndexed：排序按钮要知道自己是不是第一个/最后一个，
                    // 到头了就把「上移」「下移」显示成禁用
                    state.boxGroups.forEachIndexed { index, group ->
                        // 从 group 反查回 FoodCategoryOut：group 里只有 id 和名字，
                        // 而对话框需要 inventoryIds 才能标出「哪些已经在箱子里」
                        val box = state.boxes.find { it.id == group.boxId }
                        groupSection(
                            group = group,
                            collapsed = group.key in state.collapsed,
                            onToggle = { viewModel.toggleCollapse(group.key) },
                            onEdit = { editing = it },
                            onDelete = { deleting = it },
                            onRemoveFromBox = { viewModel.removeFromBox(group.boxId!!, it.id) },
                            onAddItems = { box?.let { addingToBox = it } },
                            onRenameBox = { box?.let { renamingBox = it } },
                            onDeleteBox = { box?.let { deletingBox = it } },
                            onMoveUp = { group.boxId?.let { viewModel.moveBox(it, -1) } },
                            onMoveDown = { group.boxId?.let { viewModel.moveBox(it, +1) } },
                            canMoveUp = index > 0,
                            canMoveDown = index < state.boxGroups.lastIndex,
                        )
                    }
                }

                // ② 默认分类
                if (state.groups.isNotEmpty()) {
                    item {
                        Text(
                            text = "按分类",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                        )
                    }
                    state.groups.forEach { group ->
                        groupSection(
                            group = group,
                            collapsed = group.key in state.collapsed,
                            onToggle = { viewModel.toggleCollapse(group.key) },
                            onEdit = { editing = it },
                            onDelete = { deleting = it },
                        )
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

    // ---------- 折叠箱：新建 / 重命名 / 删除 / 加食材 ----------

    if (showCreateBox) {
        BoxNameDialog(
            title = "新建折叠箱",
            hint = "比如「火锅材料」「早餐」「给猫的」—— 名字只有你自己看得到。",
            initialName = "",
            confirmText = "创建",
            busy = state.boxBusy,
            onConfirm = { name -> viewModel.createBox(name) { showCreateBox = false } },
            onDismiss = { showCreateBox = false },
        )
    }

    renamingBox?.let { box ->
        BoxNameDialog(
            title = "重命名折叠箱",
            hint = null,
            initialName = box.name,
            confirmText = "保存",
            busy = state.boxBusy,
            onConfirm = { name -> viewModel.renameBox(box.id, name) { renamingBox = null } },
            onDismiss = { renamingBox = null },
        )
    }

    deletingBox?.let { box ->
        AlertDialog(
            onDismissRequest = { deletingBox = null },
            title = { Text("删除折叠箱") },
            // ⚠️ 必须写清「不删食材」。用户看到「删除」第一反应是
            // 「箱子里的东西会不会一起没了」—— 不说清楚他就不敢点。
            //
            // 注意这里**不能**用 Markdown 的 ** 加粗：
            // AlertDialog 的 text 是普通 Text，星号会原样显示出来。
            text = {
                Text(
                    "确定删除「${box.name}」吗？\n\n"
                        + "箱子里的 ${box.inventoryIds.size} 样食材不会被删掉，"
                        + "只是不再归到这个箱子里 —— 它们还在冰箱里，也还在按分类里。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.deleteBox(box.id) { deletingBox = null } },
                    enabled = !state.boxBusy,
                ) {
                    Text(
                        if (state.boxBusy) "删除中…" else "删除箱子",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingBox = null }) { Text("取消") }
            },
        )
    }

    addingToBox?.let { box ->
        AddItemsToBoxDialog(
            box = box,
            items = state.items,
            busy = state.boxBusy,
            onConfirm = { ids -> viewModel.addToBox(box.id, ids) { addingToBox = null } },
            onDismiss = { addingToBox = null },
        )
    }
}

/**
 * 折叠箱的「起名 / 改名」对话框。
 *
 * 新建和重命名共用一个：两者只差标题、按钮文案和初始值，
 * 分成两个函数会把「名字不能为空」「回车即确认」这些校验写两遍。
 */
@Composable
private fun BoxNameDialog(
    title: String,
    hint: String?,
    initialName: String,
    confirmText: String,
    busy: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    val canSubmit = name.isNotBlank() && !busy

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("箱子名字") },
                    singleLine = true,
                    // 名字是给人看的短标签，不是备注。限长能防住
                    // 「粘一大段文字进去」把标题行撑爆。
                    isError = name.length > MAX_BOX_NAME,
                    supportingText = {
                        if (name.length > MAX_BOX_NAME) {
                            Text("最多 ${MAX_BOX_NAME} 个字")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                hint?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = canSubmit && name.length <= MAX_BOX_NAME,
            ) { Text(if (busy) "处理中…" else confirmText) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        },
    )
}

/** 箱子名字的长度上限。和后端不强制，但界面得防住超长标题把布局撑坏。 */
private const val MAX_BOX_NAME = 12

/**
 * 「往箱子里加食材」对话框。
 *
 * ## 为什么只加不减
 *
 * 移出食材走的是**食材行上的「移出」按钮**（已经做好了）。
 * 这里不做「取消勾选 = 移出」，有两个原因：
 *
 * 1. 后端的移出接口是**单个**的（`DELETE /{id}/items/{itemId}`），
 *    取消勾选 3 个就要连发 3 个请求，中间失败还会留下不一致的状态
 * 2. 语义上更清楚 —— 「把东西放进箱子」和「把东西拿出来」
 *    是两个不同的意图，放在同一个确认按钮里容易误操作
 *
 * 已经在箱子里的食材显示为**勾选且不可点**，这样用户能看清
 * 「箱子里现在有什么」，而不是只看到一个待选清单。
 */
@Composable
private fun AddItemsToBoxDialog(
    box: FoodCategoryOut,
    items: List<InventoryOut>,
    busy: Boolean,
    onConfirm: (List<Int>) -> Unit,
    onDismiss: () -> Unit,
) {
    val alreadyIn = remember(box.id, box.inventoryIds) { box.inventoryIds.toSet() }
    // 只记「这次新勾的」。已在箱子里的不算 —— 它们由 alreadyIn 表示，
    // 混在一起提交虽然后端幂等能兜住，但按钮上的数字会虚高。
    var picked by remember(box.id) { mutableStateOf(emptySet<Int>()) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("往「${box.name}」里加食材") },
        text = {
            if (items.isEmpty()) {
                Text("冰箱里还没有食材，先去添加或扫描一次。")
            } else {
                Column(
                    // 限制高度，否则食材多了对话框会长到屏幕外，
                    // 底下的确认按钮点不到
                    modifier = Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    items.forEach { item ->
                        val isIn = item.id in alreadyIn
                        val checked = isIn || item.id in picked
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isIn && !busy) {
                                    picked = if (item.id in picked) {
                                        picked - item.id
                                    } else {
                                        picked + item.id
                                    }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                // 已在箱子里的传 null onCheckedChange = 只读勾选框，
                                // 避免用户以为点它能移出
                                onCheckedChange = if (isIn) null else {
                                    { picked = if (item.id in picked) picked - item.id else picked + item.id }
                                },
                                enabled = !isIn && !busy,
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.foodName,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = if (isIn) {
                                        "已在箱子里"
                                    } else {
                                        "${item.category} · ${formatQuantity(item.quantity, item.unit)}"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(picked.toList()) },
                enabled = picked.isNotEmpty() && !busy,
            ) {
                Text(
                    when {
                        busy -> "加入中…"
                        picked.isEmpty() -> "加入"
                        else -> "加入 ${picked.size} 样"
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        },
    )
}

/**
 * 一个分组：标题行 + 成员列表。
 *
 * 整组放在**一张卡片**里，而不是每样食材一张卡 ——
 * 否则「分组」在视觉上体现不出来，看起来还是平铺列表。
 */
// ⚠️ 这个函数**不能**标 @Composable —— LazyListScope 的扩展函数是
// 在 LazyColumn 的 content lambda（非 composable 上下文）里调用的。
// 真正需要 composable 上下文的是 item {} 的 lambda，它自己带。
private fun LazyListScope.groupSection(
    group: FridgeGroup,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onEdit: (InventoryOut) -> Unit,
    onDelete: (InventoryOut) -> Unit,
    onRemoveFromBox: ((InventoryOut) -> Unit)? = null,
    onAddItems: (() -> Unit)? = null,
    onRenameBox: (() -> Unit)? = null,
    onDeleteBox: (() -> Unit)? = null,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    canMoveUp: Boolean = true,
    canMoveDown: Boolean = true,
) {
    item(key = "group-${group.key}") {
        SectionCard {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onToggle),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (collapsed) "▸" else "▾",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = group.title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${group.items.size} 样",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (group.boxId != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "自定义",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    // 折叠箱的管理入口。
                    //
                    // 放在**标题行内**而不是做成整页的「管理」页面：
                    // 用户想改的是「这个箱子」，动作应该就在这个箱子旁边。
                    // 而且标题行本来就点一下展开/收起，
                    // 加一个独立的「⋯」按钮比长按更好发现（长按没有任何视觉提示）。
                    if (onAddItems != null || onRenameBox != null || onDeleteBox != null) {
                        Box {
                            var menuOpen by remember { mutableStateOf(false) }
                            IconButton(onClick = { menuOpen = true }) {
                                Text("⋯", style = MaterialTheme.typography.titleMedium)
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                            ) {
                                // 上移 / 下移。
                                //
                                // 到头了就**显示成禁用**，而不是藏起来 ——
                                // 藏起来的话用户会以为「这个箱子不能排序」，
                                // 而实际上是「已经是最上面了」。
                                onMoveUp?.let { action ->
                                    DropdownMenuItem(
                                        text = { Text("上移") },
                                        onClick = { menuOpen = false; action() },
                                        enabled = canMoveUp,
                                    )
                                }
                                onMoveDown?.let { action ->
                                    DropdownMenuItem(
                                        text = { Text("下移") },
                                        onClick = { menuOpen = false; action() },
                                        enabled = canMoveDown,
                                    )
                                }
                                onAddItems?.let { action ->
                                    DropdownMenuItem(
                                        text = { Text("加食材") },
                                        onClick = { menuOpen = false; action() },
                                    )
                                }
                                onRenameBox?.let { action ->
                                    DropdownMenuItem(
                                        text = { Text("重命名") },
                                        onClick = { menuOpen = false; action() },
                                    )
                                }
                                onDeleteBox?.let { action ->
                                    DropdownMenuItem(
                                        text = {
                                            Text("删除箱子", color = MaterialTheme.colorScheme.error)
                                        },
                                        onClick = { menuOpen = false; action() },
                                    )
                                }
                            }
                        }
                    }
                }

                // 空箱子也要显示 —— 用户刚建完就看到它消失会以为没建成
                if (!collapsed && group.items.isEmpty()) {
                    Text(
                        text = "还没有放东西进来",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, start = 22.dp),
                    )
                }

                if (!collapsed) {
                    group.items.forEach { item ->
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        FridgeItemRow(
                            item = item,
                            onEdit = { onEdit(item) },
                            onDelete = { onDelete(item) },
                            onRemoveFromBox = onRemoveFromBox?.let { f -> { f(item) } },
                        )
                    }
                }
            }
        }
    }
}

/** 单样食材那一行。分组和折叠箱共用，所以抽出来。 */
@Composable
private fun FridgeItemRow(
    item: InventoryOut,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRemoveFromBox: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FoodImage(
            imageUrl = item.imageUrl,
            name = item.foodName,
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(14.dp)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.foodName,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "${item.storageLocation} · ${formatQuantity(item.quantity, item.unit)}",
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
            if (onRemoveFromBox != null) {
                // 在折叠箱里给的是「移出」而不是「删除」——
                // 用户想的是「这东西不放这个箱子里了」，不是「我把它扔了」
                RowAction("移出", MaterialTheme.colorScheme.primary, onRemoveFromBox)
            }
            RowAction("改", MaterialTheme.colorScheme.onSurfaceVariant, onEdit)
            RowAction("删", MaterialTheme.colorScheme.error, onDelete)
        }
    }
}

/**
 * 食材行右侧的小动作按钮。
 *
 * ## 为什么不用 `IconButton`
 *
 * `IconButton` 有 **48dp 的最小尺寸**（Material 的可点区域规范）。
 * 折叠箱里一行有三个动作（移出 / 改 / 删），就是 144dp ——
 * 加上 52dp 的图和右边那枚新鲜度标签，**食材名那一列只剩不到 40dp**。
 *
 * 实测后果（真机截图）：折叠箱里的一行被折成五行 ——
 *
 *     红椒
 *     冷藏 · 3
 *     个
 *     2026-10-1
 *     1 · 还剩 5
 *     天
 *
 * 这不是「挤一点」，是看起来坏了。
 *
 * ## 取舍
 *
 * 这里手动给一个紧凑的点击区域：横向 6dp、纵向 12dp。
 * 高度约 44dp（接近 48dp 的建议值，手指够点），
 * 宽度按文字自适应 —— 「移出」约 40dp、「改」约 26dp，三个合计约 92dp，
 * 比原来省下 50dp 左右，食材名那一列回到 90dp 以上。
 *
 * ⚠️ **不要再改回 IconButton**，除非同时改掉「一行三个动作」这个布局。
 */
@Composable
private fun RowAction(
    text: String,
    color: Color,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 12.dp),
    )
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
                    text = "拿不准就选「系统估算」，我按食材名猜一个",
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
                    text = "填剩余天数或过期日期都行，我自动换算" +
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
