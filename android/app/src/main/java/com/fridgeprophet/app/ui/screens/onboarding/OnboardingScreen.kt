package com.fridgeprophet.app.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val total = OnboardingOptions.TOTAL_STEPS
    val isLast = state.step == total - 1

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
    ) {
        // 进度指示
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(total) { index ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            if (index <= state.step) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                )
            }
        }

        Text(
            text = "第 ${state.step + 1} / $total 步",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (state.step) {
                0 -> ChoiceStep(
                    title = "你平时更喜欢做什么菜？",
                    hint = "这会决定推荐的菜谱风格",
                    options = OnboardingOptions.cuisines,
                    selected = state.cuisine,
                    onSelect = viewModel::setCuisine,
                )

                1 -> ChoiceStep(
                    title = "口味偏好？",
                    hint = "影响调味和用油量的建议",
                    options = OnboardingOptions.tastes,
                    selected = state.taste,
                    onSelect = viewModel::setTaste,
                )

                2 -> ChoiceStep(
                    title = "一顿饭愿意花多久？",
                    hint = "超过这个时间的菜谱不会被推荐",
                    options = OnboardingOptions.cookTimes.map { it.second },
                    selected = OnboardingOptions.cookTimes
                        .firstOrNull { it.first == state.cookTimeMax }?.second
                        ?: "20-40 分钟",
                    onSelect = { label ->
                        OnboardingOptions.cookTimes
                            .firstOrNull { it.second == label }
                            ?.let { viewModel.setCookTime(it.first) }
                    },
                )

                3 -> {
                    ChoiceStep(
                        title = "你的饮食目标？",
                        hint = "我会按这个筛菜品",
                        options = OnboardingOptions.dietGoals,
                        selected = state.dietGoal,
                        onSelect = viewModel::setDietGoal,
                    )
                    Text(
                        text = "健康管理模式（可选，多选）",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    FlowChips(
                        options = listOf(
                            "控制碳水" to state.lowCarb,
                            "控制钠盐" to state.lowSodium,
                            "控制脂肪" to state.lowFat,
                            "高蛋白" to state.highProtein,
                            "增加纤维" to state.highFiber,
                            "素食" to state.vegetarian,
                        ),
                        onToggle = { label, value ->
                            viewModel.setHealth(
                                lowCarb = if (label == "控制碳水") value else state.lowCarb,
                                lowSodium = if (label == "控制钠盐") value else state.lowSodium,
                                lowFat = if (label == "控制脂肪") value else state.lowFat,
                                highProtein = if (label == "高蛋白") value else state.highProtein,
                                highFiber = if (label == "增加纤维") value else state.highFiber,
                                vegetarian = if (label == "素食") value else state.vegetarian,
                            )
                        },
                    )
                    Text(
                        text = "以上仅用于生成饮食建议，不构成医疗意见。如患有糖尿病、高血压等疾病，请遵循医生或注册营养师的指导。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    MultiChoiceStep(
                        title = "有不吃的东西吗？",
                        hint = "这些食材永远不会出现在推荐里",
                        options = OnboardingOptions.disliked,
                        selected = state.dislikedFoods,
                        onToggle = viewModel::toggleDisliked,
                    )
                    MultiChoiceStep(
                        title = "有过敏的食材吗？",
                        hint = "过敏原属于硬性红线，会被严格排除",
                        options = OnboardingOptions.allergens,
                        selected = state.allergies,
                        onToggle = viewModel::toggleAllergy,
                    )
                }
            }

            state.error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.step > 0) {
                TextButton(
                    onClick = viewModel::back,
                    modifier = Modifier.height(52.dp),
                ) { Text("上一步") }
            }
            Button(
                onClick = { if (isLast) viewModel.save(onDone) else viewModel.next() },
                enabled = !state.saving,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
            ) {
                if (state.saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(if (isLast) "完成，开始使用" else "下一步")
                }
            }
        }
    }
}

@Composable
private fun ChoiceStep(
    title: String,
    hint: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Text(text = title, style = MaterialTheme.typography.headlineSmall)
    Text(
        text = hint,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowChips(
        options = options.map { it to (it == selected) },
        onToggle = { label, _ -> onSelect(label) },
        singleChoice = true,
    )
}

@Composable
private fun MultiChoiceStep(
    title: String,
    hint: String,
    options: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.padding(top = 8.dp),
    )
    Text(
        text = hint,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowChips(
        options = options.map { it to (it in selected) },
        onToggle = { label, _ -> onToggle(label) },
    )
}

/** 自适应换行的选择标签。用 Column + Row 手动分行，避免引入实验性 FlowRow。 */
@Composable
private fun FlowChips(
    options: List<Pair<String, Boolean>>,
    onToggle: (String, Boolean) -> Unit,
    singleChoice: Boolean = false,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        options.chunked(2).forEach { rowItems ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                rowItems.forEach { (label, selected) ->
                    FilterChip(
                        selected = selected,
                        onClick = {
                            // 单选模式下点已选中的项不做取消，避免出现「一个都没选」的状态
                            if (!singleChoice || !selected) onToggle(label, !selected)
                        },
                        label = {
                            Text(
                                text = label,
                                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                // 补齐空格，保证最后一行对齐
                if (rowItems.size == 1) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
