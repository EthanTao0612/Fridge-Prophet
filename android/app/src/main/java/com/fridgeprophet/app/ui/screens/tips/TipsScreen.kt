package com.fridgeprophet.app.ui.screens.tips

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fridgeprophet.app.R
import com.fridgeprophet.app.data.remote.dto.FoodTipSummary
import com.fridgeprophet.app.ui.components.EmptyState
import com.fridgeprophet.app.ui.components.LoadingBox
import com.fridgeprophet.app.ui.components.SectionCard
import com.fridgeprophet.app.ui.components.VerdictChip
import com.fridgeprophet.app.ui.components.verdictColor

/**
 * 食品安全小贴士列表。
 *
 * 顶部两排筛选标签，分别按「分类」和「结论」筛。
 * 分类是内容维度（储存安全 / 烹饪安全…），结论是可信度维度（谣言 / 属实…），
 * 两个维度正交，所以给了两排而不是一排混在一起。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TipsScreen(
    onBack: () -> Unit,
    onOpenTip: (String) -> Unit,
    viewModel: TipsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("食品安全小贴士", style = MaterialTheme.typography.titleLarge) },
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
            when {
                state.loading && state.items.isEmpty() -> LoadingBox(text = "正在取贴士…")

                state.items.isEmpty() -> EmptyState(
                    title = "暂时没有内容",
                    description = state.error ?: "换个筛选条件试试",
                )

                else -> {
                    val visible = viewModel.visibleItems()
                    Column(modifier = Modifier.fillMaxSize()) {
                        // 搜索框**固定在顶部**，不跟着列表滚。
                        // 89 条贴士滚到一半想换个词搜，还要滑回顶部就很烦。
                        TipSearchBar(
                            query = state.query,
                            onQueryChange = viewModel::setQuery,
                            modifier = Modifier.padding(
                                start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp,
                            ),
                        )

                        if (visible.isEmpty()) {
                            // 区分两种「没结果」：搜不到，还是被筛掉了。
                            // 后者是用户自己忘了还开着分类筛选，提示他清掉就行 ——
                            // 不这么做用户会以为「这个应用里没有这条」。
                            EmptyState(
                                title = "没找到相关贴士",
                                description = if (viewModel.hasFilter()) {
                                    "当前筛选条件下没有匹配。点上面「分类」和「结论」里的「全部」清掉筛选再试。"
                                } else {
                                    "换个词试试，比如「冰箱」「发芽」「亚硝酸盐」「痛风」"
                                },
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                item {
                                    FilterSection(
                                        categories = state.categories,
                                        verdicts = state.verdicts,
                                        selectedCategory = state.category,
                                        selectedVerdict = state.verdict,
                                        onSelectCategory = viewModel::selectCategory,
                                        onSelectVerdict = viewModel::selectVerdict,
                                    )
                                }

                                item {
                                    Text(
                                        text = if (viewModel.isSearching()) {
                                            "找到 ${visible.size} 条"
                                        } else {
                                            "共 ${visible.size} 条"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }

                                items(visible, key = { it.id }) { tip ->
                                    TipListCard(tip = tip, onClick = { onOpenTip(tip.id) })
                                }

                                item { Box(Modifier.padding(bottom = 24.dp)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 贴士搜索框。
 *
 * 用 `OutlinedTextField` 而不是自绘：输入法、光标、长按选择这些
 * 交给系统组件处理，自己写容易在中文输入法上出问题（拼音未上屏时
 * 就被当成删除、光标跳到开头之类）。
 *
 * ⚠️ 过滤是**即时的**（每敲一个字就重算），没有防抖。
 * 因为这是纯内存过滤，89 条算一次是微秒级；
 * 加防抖反而会让输入和结果不同步，感觉更迟钝。
 * 如果以后贴士涨到几千条，再考虑防抖或改后端搜索。
 */
@Composable
private fun TipSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text("搜一搜，比如「发芽」「隔夜」「孕妇」") },
        leadingIcon = {
            Icon(
                painter = painterResource(R.drawable.ic_action_search),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingIcon = {
            // 有内容才显示「清除」，空的时候显示它是噪音
            if (query.isNotEmpty()) {
                TextButton(onClick = { onQueryChange("") }) { Text("清除") }
            }
        },
        shape = RoundedCornerShape(14.dp),
        textStyle = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun FilterSection(
    categories: List<String>,
    verdicts: List<String>,
    selectedCategory: String?,
    selectedVerdict: String?,
    onSelectCategory: (String?) -> Unit,
    onSelectVerdict: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterRow(
            label = "分类",
            options = categories,
            selected = selectedCategory,
            onSelect = onSelectCategory,
        )
        FilterRow(
            label = "结论",
            options = verdicts,
            selected = selectedVerdict,
            onSelect = onSelectVerdict,
        )
    }
}

@Composable
private fun FilterRow(
    label: String,
    options: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 2.dp),
        )
        FilterChipItem(
            text = "全部",
            active = selected == null,
            onClick = { onSelect(null) },
        )
        options.forEach { option ->
            FilterChipItem(
                text = option,
                active = selected == option,
                onClick = { onSelect(option) },
            )
        }
    }
}

@Composable
private fun FilterChipItem(text: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (active) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = RoundedCornerShape(50),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun TipListCard(tip: FoodTipSummary, onClick: () -> Unit) {
    SectionCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = tip.title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            VerdictChip(tip.verdict, modifier = Modifier.padding(start = 8.dp))
        }

        if (tip.summary.isNotBlank()) {
            Text(
                text = tip.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = tip.category,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "看原理 ›",
                style = MaterialTheme.typography.labelMedium,
                color = verdictColor(tip.verdict),
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
