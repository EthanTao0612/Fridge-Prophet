package com.fridgeprophet.app.ui.screens.shopping

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fridgeprophet.app.data.remote.dto.RecipeOut
import com.fridgeprophet.app.data.remote.dto.ShoppingItemOut
import com.fridgeprophet.app.data.remote.dto.ShoppingListOut
import com.fridgeprophet.app.ui.components.EmptyState
import com.fridgeprophet.app.ui.components.LoadingBox
import com.fridgeprophet.app.ui.components.Pill
import com.fridgeprophet.app.ui.components.SectionCard
import com.fridgeprophet.app.ui.screens.home.formatQuantity
import com.fridgeprophet.app.ui.theme.SemanticColors

@Composable
fun ShoppingScreen(viewModel: ShoppingViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }
    var deletingList by remember { mutableStateOf<ShoppingListOut?>(null) }
    var deletingItem by remember { mutableStateOf<ShoppingItemOut?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "采购", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = { showPicker = true }) { Text("+ 按菜谱生成") }
            }

            Text(
                text = "只列你缺的，已经有的不会重复买",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        (state.error ?: state.message)?.let { text ->
            Surface(
                color = if (state.error != null) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                onClick = viewModel::clearMessages,
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.error != null) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                    modifier = Modifier.padding(14.dp),
                )
            }
        }

        when {
            state.loading -> LoadingBox()

            state.lists.isEmpty() -> EmptyState(
                title = "还没有采购清单",
                description = "去「菜谱」页挑几道菜，缺的料会自动进清单" +
                    "也可以点右上角按已有菜谱生成。",
                actionText = "按菜谱生成清单",
                onAction = { showPicker = true },
            )

            else -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 待采购的排前面，已完成的沉到最下面 —— 和菜谱页同一套逻辑。
                // 用户买完东西不该再被这单占着视线，也不该逼他手动删掉。
                val ordered = state.lists.sortedBy { it.status != "pending" }
                val firstDoneIndex = ordered.indexOfFirst { it.status != "pending" }

                itemsIndexed(
                    items = ordered,
                    key = { _, list -> list.id },
                ) { index, list ->
                    if (index == firstDoneIndex && index > 0) {
                        DoneDivider(count = ordered.size - index)
                    }
                    ShoppingListCard(
                        list = list,
                        busy = state.busy,
                        onToggleItem = viewModel::toggleItem,
                        onDeleteItem = { deletingItem = it },
                        onApply = { viewModel.applyToList(list) },
                        onDeleteList = { deletingList = list },
                    )
                }
                item { Box(Modifier.height(24.dp)) }
            }
        }
    }

    if (showPicker) {
        RecipePickerDialog(
            recipes = state.recipes,
            busy = state.busy,
            onDismiss = { showPicker = false },
            onConfirm = { ids ->
                viewModel.buildFrom(ids, title = null)
                showPicker = false
            },
        )
    }

    deletingList?.let { list ->
        AlertDialog(
            onDismissRequest = { deletingList = null },
            title = { Text("删除清单") },
            text = { Text("确定删除「${list.title}」吗？已写回冰箱的食材不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteList(list)
                    deletingList = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deletingList = null }) { Text("取消") }
            },
        )
    }

    deletingItem?.let { item ->
        AlertDialog(
            onDismissRequest = { deletingItem = null },
            title = { Text("移除这一项") },
            text = { Text("把「${item.foodName}」从清单里去掉？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteItem(item)
                    deletingItem = null
                }) { Text("移除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deletingItem = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ShoppingListCard(
    list: ShoppingListOut,
    busy: Boolean,
    onToggleItem: (ShoppingItemOut) -> Unit,
    onDeleteItem: (ShoppingItemOut) -> Unit,
    onApply: () -> Unit,
    onDeleteList: () -> Unit,
) {
    val checkedCount = list.items.count { it.checked }
    val pendingCount = list.items.count { !it.appliedToInventory }
    // 「已完成」= 后端把 status 置成 done（所有项都勾选并写回冰箱了）。
    // 这类单子整体弱化 + 明细默认收起，只留一句摘要和删除入口。
    val isDone = list.status != "pending"
    var showDetail by remember(list.id) { mutableStateOf(!isDone) }

    SectionCard(modifier = Modifier.alpha(if (isDone) 0.62f else 1f)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = list.title, style = MaterialTheme.typography.titleMedium)
                list.sourceRecipes?.takeIf { it.isNotBlank() }?.let { source ->
                    Text(
                        text = "来源：" + source.take(40),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Pill(
                text = statusLabel(list.status),
                color = if (isDone) {
                    SemanticColors.fresh
                } else {
                    MaterialTheme.colorScheme.secondary
                },
            )
        }

        // 已完成时先给一句「做完了什么」，明细要点开才看
        if (isDone) {
            Text(
                text = "这单的 ${list.items.size} 项已经写回冰箱，不用再管它了。" +
                    "想核对明细可以展开。",
                style = MaterialTheme.typography.labelMedium,
                color = SemanticColors.fresh,
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { showDetail = !showDetail },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                ) { Text(if (showDetail) "收起明细" else "展开明细") }

                TextButton(onClick = onDeleteList) {
                    Text("删除清单", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        if (showDetail) {
            if (list.items.isEmpty()) {
                Text(
                    text = "食材都齐了，不用买",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            } else {
                Column(modifier = Modifier.padding(top = 6.dp)) {
                    list.items.forEach { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = item.checked,
                                onCheckedChange = { onToggleItem(item) },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.foodName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (item.checked) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                                val sub = buildString {
                                    append(formatQuantity(item.quantity, item.unit))
                                    item.estimatedPrice?.let { append(" · 约 ¥%.1f".format(it)) }
                                    if (item.appliedToInventory) append(" · 已入库")
                                }
                                Text(
                                    text = sub,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (item.appliedToInventory) {
                                        SemanticColors.fresh
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                            TextButton(onClick = { onDeleteItem(item) }) {
                                Text(
                                    "移除",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "已勾选 $checkedCount / ${list.items.size} 项",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "预计 ¥%.1f".format(list.estimatedTotal),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                if (!isDone) {
                    Button(
                        onClick = onApply,
                        enabled = !busy && checkedCount > 0,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text("买好了，写回冰箱（$checkedCount 项）")
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
                            text = if (pendingCount == 0) {
                                "全部已入库"
                            } else {
                                "还有 $pendingCount 项没入库"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onDeleteList) {
                            Text("删除清单", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 「已完成」分组的说明条。
 *
 * 和菜谱页的 ReadyDivider 一个道理：得让用户明白
 * 「下面这些为什么变淡了、跑到底下去了」，否则看着像界面出错。
 */
@Composable
private fun DoneDivider(count: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text = "以下 $count 张已完成",
            style = MaterialTheme.typography.labelMedium,
            color = SemanticColors.fresh,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = "已经写回冰箱，不需要再操作。想清理可以展开后删除。",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RecipePickerDialog(
    recipes: List<RecipeOut>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (List<Int>) -> Unit,
) {
    val selected = remember { mutableStateListOf<Int>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选菜谱生成清单") },
        text = {
            Column {
                Text(
                    text = "选几道想做的，缺的料我合成一张单子",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (recipes.isEmpty()) {
                    Text(
                        text = "还没有菜谱。先去「菜谱」页生成几道。",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "共 ${recipes.size} 道",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = {
                            val allIds = recipes.mapNotNull { it.id }
                            if (selected.size == allIds.size) {
                                selected.clear()
                            } else {
                                selected.clear()
                                selected.addAll(allIds)
                            }
                        }) {
                            Text(if (selected.size == recipes.count { it.id != null }) "全不选" else "全选")
                        }
                    }

                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(recipes, key = { it.id ?: it.name.hashCode() }) { recipe ->
                            val id = recipe.id ?: return@items
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (selected.contains(id)) selected.remove(id) else selected.add(id)
                                    }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = selected.contains(id),
                                    onCheckedChange = {
                                        if (selected.contains(id)) selected.remove(id) else selected.add(id)
                                    },
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = recipe.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    Text(
                                        text = buildString {
                                            append("${recipe.timeMinutes} 分钟")
                                            if (recipe.missingIngredients.isNotEmpty()) {
                                                append(" · 缺 ${recipe.missingIngredients.size} 样")
                                            } else {
                                                append(" · 食材齐全")
                                            }
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected.isNotEmpty() && !busy,
                onClick = { onConfirm(selected.toList()) },
            ) { Text("生成清单（${selected.size}）") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun statusLabel(status: String): String = when (status) {
    "pending" -> "待采购"
    "completed", "done", "finished" -> "已完成"
    else -> status
}
