package com.fridgeprophet.app.ui.screens.profile

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fridgeprophet.app.data.remote.dto.FamilyMemberIn
import com.fridgeprophet.app.data.remote.dto.FamilyMemberOut
import com.fridgeprophet.app.data.remote.dto.HealthPreferenceIn
import com.fridgeprophet.app.data.remote.dto.HealthPreferenceOut
import com.fridgeprophet.app.data.remote.dto.OptionGroup
import com.fridgeprophet.app.data.remote.dto.PrivacySettingIn
import com.fridgeprophet.app.data.remote.dto.PrivacySettingOut
import com.fridgeprophet.app.data.remote.dto.ProfileOut
import com.fridgeprophet.app.data.remote.dto.UserOptions
import com.fridgeprophet.app.data.remote.dto.UserPreferenceOut
import com.fridgeprophet.app.ui.components.AvatarImage
import com.fridgeprophet.app.ui.components.CollapsibleCard
import com.fridgeprophet.app.ui.components.EmptyState
import com.fridgeprophet.app.ui.components.LabeledRow
import com.fridgeprophet.app.ui.components.LoadingBox
import com.fridgeprophet.app.ui.components.Pill
import com.fridgeprophet.app.ui.components.uriToCacheFile
import com.fridgeprophet.app.ui.components.SectionCard
import com.fridgeprophet.app.ui.theme.SemanticColors
import java.io.File
import java.io.IOException

private val CUISINES = listOf("家常菜", "川菜", "粤菜", "日式", "西式", "不限")
private val TASTES = listOf("清淡", "正常", "偏重")
private val DIET_GOALS = listOf("正常饮食", "减脂", "增肌", "控糖", "控压")
private val ACTIVITY_LEVELS = listOf("久坐", "轻度活动", "中度活动", "高强度")

@Composable
fun ProfileScreen(
    onLogout: () -> Unit,
    onOpenTips: () -> Unit,
    onOpenFamily: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var editingNickname by remember { mutableStateOf(false) }
    var editingBio by remember { mutableStateOf(false) }
    var editingPreference by remember { mutableStateOf(false) }
    var editingBody by remember { mutableStateOf(false) }
    var addingFamily by remember { mutableStateOf(false) }
    var deletingFamily by remember { mutableStateOf<FamilyMemberOut?>(null) }
    var confirmingLogout by remember { mutableStateOf(false) }
    // 本地校验错误（比如选了 HEIC 图），不走 ViewModel 的 error，
    // 因为它是「选图阶段」的问题，跟服务端无关
    var localError by remember { mutableStateOf<String?>(null) }

    // 系统相册选图。用 PickVisualMedia 而不是老的 GetContent：
    // 它是 Android 13+ 的官方推荐方式，**不需要申请存储权限**，
    // 用户选中的那一张才会临时授权给 App。
    val pickAvatar = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        val prepared = uriToCacheFile(context, uri, "avatar")
        if (prepared == null) {
            localError = "读不到这张图片，换一张试试"
            return@rememberLauncherForActivityResult
        }

        val (file, mime) = prepared
        if (mime !in AVATAR_ALLOWED_MIME) {
            file.delete()
            localError = "这张图是 ${mime.removePrefix("image/").uppercase()} 格式，服务端只收 JPG / PNG / WEBP。" +
                "可以在相册里截图后再选，或用图片工具另存为 JPG。"
            return@rememberLauncherForActivityResult
        }

        localError = null
        viewModel.updateAvatar(file, mime)
    }

    val profile = state.profile

    when {
        state.loading -> LoadingBox()

        profile == null -> EmptyState(
            title = "资料加载失败",
            description = state.error ?: "请检查网络后重试",
            actionText = "重试",
            onAction = { viewModel.load() },
        )

        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ⚠️ 每个 item 都必须带 key。
            // LazyColumn 没有 key 时用「位置」当身份，而下面的提示条是条件渲染的 ——
            // 用户一编辑，提示条插进来，后面所有 item 的位置全部往后挪一位，
            // Compose 就认不出「还是原来那个折叠卡」了，rememberSaveable 里的
            // 展开状态随之被重置。表现出来就是「编辑一下折叠箱自己收回去」。
            // ---------- 账号：头像 + 昵称 + 个性简介 ----------
            item(key = "profile-header") {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 点头像直接换图，比「先点编辑再选图」少一步
                        AvatarImage(
                            avatarUrl = profile.user.avatarUrl,
                            nickname = profile.user.nickname.ifBlank { "?" },
                            size = 68,
                            modifier = Modifier.clickable(enabled = !state.busy) {
                                pickAvatar.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            },
                        )

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 14.dp),
                        ) {
                            Text(
                                text = profile.user.nickname.ifBlank { "还没起名字" },
                                style = MaterialTheme.typography.headlineSmall,
                            )
                            Text(
                                text = profile.user.email,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = if (state.busy) "处理中…" else "点头像可更换",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }

                        TextButton(onClick = { editingNickname = true }) { Text("改昵称") }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "个性简介",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        TextButton(onClick = { editingBio = true }) { Text("编辑") }
                    }
                    Text(
                        text = profile.user.bio?.takeIf { it.isNotBlank() }
                            ?: "还没写简介。写一句你的饮食习惯，方便自己回看。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (profile.user.bio.isNullOrBlank()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            // ---------- 提示条 ----------
            (localError ?: state.error ?: state.message)?.let { text ->
                val isError = localError != null || state.error != null
                // 这条提示是条件渲染的，正是它插进来把后面的 item 挤位移了
                item(key = "notice") {
                    Surface(
                        color = if (isError) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                localError = null
                                viewModel.clearMessages()
                            },
                    ) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isError) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            },
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }
            }

            // ---------- 我的家庭（账号关联）----------
            // ⚠️ 和下面「家人的忌口」那张卡不是一回事：
            //   这张 = 真实账号关联（谁和我共享冰箱、什么身份）
            //   那张 = 忌口档案（对方不用注册，只影响菜谱推荐）
            // 两者并存，不要合并 —— 合并会让「没有账号的家人」无处安放。
            item(key = "family-entry") {
                SectionCard(modifier = Modifier.clickable(onClick = onOpenFamily)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "我的家庭",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = "邀请家人一起管冰箱 —— 冰箱、菜谱、采购清单全家共享",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Text(
                            text = "›",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ---------- 食品安全小贴士入口 ----------
            item(key = "tips-entry") {
                SectionCard(modifier = Modifier.clickable(onClick = onOpenTips)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "食品安全小贴士",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = "哪些「食物相克」是谣言、哪些要当真，都写清楚了原因和依据",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Text(
                            text = "›",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ---------- 口味偏好 ----------
            item(key = "preference") {
                PreferenceCard(
                    preference = profile.preference,
                    onEdit = { editingPreference = true },
                )
            }

            // ---------- 健康偏好 ----------
            item(key = "health") {
                HealthCard(
                    health = profile.health,
                    busy = state.busy,
                    onToggle = { input -> viewModel.saveHealth(input) },
                    onEditBody = { editingBody = true },
                )
            }

            // ---------- 家人的忌口（档案，不是账号）----------
            // 标题从「家庭成员」改成「家人的忌口」：上面刚加了「我的家庭」，
            // 两张卡都叫「家庭」会让人以为重复了。实际一个管共享、一个管忌口。
            item(key = "family") {
                SectionCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = "家人的忌口", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { addingFamily = true }) { Text("+ 添加") }
                    }
                    Text(
                        text = "一起吃饭的人越多，口味冲突越难兼顾。把他们的忌口记下来，生成菜谱时会一起考虑。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (profile.familyMembers.isEmpty()) {
                        Text(
                            text = "还没有添加成员",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    } else {
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            profile.familyMembers.forEach { member ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = buildString {
                                                append(member.name)
                                                if (member.relation.isNotBlank()) {
                                                    append("（${member.relation}）")
                                                }
                                            },
                                            style = MaterialTheme.typography.bodyLarge,
                                        )
                                        Text(
                                            text = buildString {
                                                append(member.dietGoal)
                                                append(" · 口味")
                                                append(member.taste)
                                                if (member.dislikedFoods.isNotEmpty()) {
                                                    append(" · 忌 ")
                                                    append(member.dislikedFoods.joinToString("、"))
                                                }
                                                if (member.allergies.isNotEmpty()) {
                                                    append(" · 过敏 ")
                                                    append(member.allergies.joinToString("、"))
                                                }
                                            },
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    TextButton(onClick = { deletingFamily = member }) {
                                        Text(
                                            "移除",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ---------- 在广场公开什么 ----------
            // 放在「家庭成员」之后：它是关于「别人能看见我什么」的，
            // 紧跟在「我有什么」的信息之后，读起来是自然的一句话延续。
            item(key = "privacy") {
                PrivacyCard(
                    privacy = profile.privacy,
                    busy = state.busy,
                    onToggle = { input -> viewModel.savePrivacy(input) },
                )
            }

            // ---------- 口味洞察 ----------
            state.insights?.let { insights ->
                if (insights.totalInteractions > 0) {
                    item(key = "taste-insight") {
                        SectionCard {
                            Text(text = "你的口味画像", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "来自 ${insights.totalInteractions} 次互动，会用来逐步修正推荐",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (insights.frequentlyCooked.isNotEmpty()) {
                                Row(
                                    modifier = Modifier.padding(top = 10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Pill(text = "常做", color = SemanticColors.fresh)
                                    Text(
                                        text = insights.frequentlyCooked.joinToString("、"),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                            if (insights.frequentlySkipped.isNotEmpty()) {
                                Row(
                                    modifier = Modifier.padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Pill(text = "常跳过", color = SemanticColors.expired)
                                    Text(
                                        text = insights.frequentlySkipped.joinToString("、"),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ---------- 退出 ----------
            item(key = "logout") {
                OutlinedButton(
                    onClick = { confirmingLogout = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    Text("退出登录", color = MaterialTheme.colorScheme.error)
                }
            }

            item(key = "bottom-spacer") { Box(Modifier.height(24.dp)) }
        }
    }

    if (editingNickname && profile != null) {
        NicknameDialog(
            initial = profile.user.nickname,
            busy = state.busy,
            onDismiss = { editingNickname = false },
            onConfirm = {
                viewModel.updateNickname(it)
                editingNickname = false
            },
        )
    }

    if (editingBio && profile != null) {
        BioDialog(
            initial = profile.user.bio.orEmpty(),
            busy = state.busy,
            onDismiss = { editingBio = false },
            onConfirm = {
                viewModel.updateBio(it)
                editingBio = false
            },
        )
    }

    if (editingPreference && profile != null) {
        PreferenceDialog(
            initial = profile.preference,
            options = state.options,
            busy = state.busy,
            onDismiss = { editingPreference = false },
            onConfirm = { cuisine, taste, minutes, goal, disliked, allergies ->
                viewModel.savePreference(cuisine, taste, minutes, goal, disliked, allergies)
                editingPreference = false
            },
        )
    }

    if (editingBody && profile != null) {
        BodyDialog(
            initial = profile.health,
            busy = state.busy,
            onDismiss = { editingBody = false },
            onConfirm = { height, weight, age, activity ->
                viewModel.saveHealth(
                    profile.health.toInput(
                        heightCm = height,
                        weightKg = weight,
                        age = age,
                        activityLevel = activity,
                    )
                )
                editingBody = false
            },
        )
    }

    if (addingFamily) {
        FamilyDialog(
            busy = state.busy,
            onDismiss = { addingFamily = false },
            onConfirm = {
                viewModel.addFamily(it)
                addingFamily = false
            },
        )
    }

    deletingFamily?.let { member ->
        AlertDialog(
            onDismissRequest = { deletingFamily = null },
            title = { Text("移除成员") },
            text = { Text("把「${member.name}」从家庭成员里移除？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteFamily(member.id)
                    deletingFamily = null
                }) { Text("移除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deletingFamily = null }) { Text("取消") }
            },
        )
    }

    if (confirmingLogout) {
        AlertDialog(
            onDismissRequest = { confirmingLogout = false },
            title = { Text("退出登录") },
            text = { Text("退出后需要重新登录。冰箱库存和菜谱都存在服务器上，不会丢。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingLogout = false
                    viewModel.logout(onLogout)
                }) { Text("退出", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingLogout = false }) { Text("取消") }
            },
        )
    }
}

/* ------------------------------------------------------------------
 *  卡片
 * ------------------------------------------------------------------ */

/**
 * 折叠状态下的摘要。**必须回答「我设了什么」**，不能只说「点击展开」——
 * 后者等于把内容藏起来还顺带藏掉了状态，用户每次都得展开确认一遍。
 *
 * 顺序有讲究：**过敏排最前**。它是生成菜谱时的硬性红线，
 * 被折进去用户会不放心（「它到底记没记住我不能吃海鲜？」）。
 */
private fun UserPreferenceOut.summaryLine(): String {
    val parts = mutableListOf<String>()

    val allergy = allergies.filter { it.isNotBlank() }
    if (allergy.isNotEmpty()) parts += "过敏 ${allergy.joinToString("、")}"

    if (cuisine.isNotBlank()) parts += cuisine
    if (taste.isNotBlank()) parts += taste
    if (dietGoal.isNotBlank()) parts += dietGoal

    val disliked = dislikedFoods.filter { it.isNotBlank() }
    if (disliked.isNotEmpty()) parts += "忌口 ${disliked.joinToString("、")}"

    return parts.joinToString(" · ").ifBlank {
        "未设置 · 展开可填菜系、口味、忌口和过敏原"
    }
}

/**
 * 口味偏好。**默认折起来**，和健康偏好用同一套收集箱形式（用户明确要求）。
 *
 * 折叠之后卡片里只剩摘要，所以原来标题行右侧那个「编辑」按钮没地方放了 ——
 * 折叠卡的标题行整行都是展开/收起的热区，塞按钮进去会和它抢点击。
 * 所以编辑入口挪进展开区，放在内容末尾右对齐。
 */
@Composable
private fun PreferenceCard(
    preference: UserPreferenceOut,
    onEdit: () -> Unit,
) {
    CollapsibleCard(
        title = "口味偏好",
        summary = preference.summaryLine(),
    ) {
        Column(modifier = Modifier.padding(top = 6.dp)) {
            LabeledRow("菜系", preference.cuisine)
            Box(Modifier.height(6.dp))
            LabeledRow("口味", preference.taste)
            Box(Modifier.height(6.dp))
            LabeledRow("可接受的最长烹饪时间", "${preference.cookTimeMax} 分钟")
            Box(Modifier.height(6.dp))
            LabeledRow("饮食目标", preference.dietGoal)
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 10.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        Text(
            text = "不吃 / 忌口",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = preference.dislikedFoods.joinToString("、").ifBlank { "没有记录" },
            style = MaterialTheme.typography.bodyLarge,
        )

        Text(
            text = "过敏（生成菜谱时会硬性排除）",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = preference.allergies.joinToString("、").ifBlank { "没有记录" },
            style = MaterialTheme.typography.bodyLarge,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onEdit) { Text("编辑口味偏好") }
        }
    }
}

/**
 * 健康偏好。**默认折起来**（用户明确要求「收进一个收集箱」）。
 *
 * 折起来之后摘要就成了唯一可见的信息，所以它必须回答「我到底设了什么」，
 * 而不是一句「点击展开」—— 后者等于把 11 个开关藏起来还顺带藏掉了状态，
 * 用户每次都得展开确认一遍，比不折叠还烦。
 */
@Composable
private fun HealthCard(
    health: HealthPreferenceOut,
    busy: Boolean,
    onToggle: (HealthPreferenceIn) -> Unit,
    onEditBody: () -> Unit,
) {
    val goals = health.enabledGoals()
    CollapsibleCard(
        title = "健康偏好",
        summary = if (goals.isEmpty()) {
            "未设置 · 展开可添加低卡、低钠、控糖等目标"
        } else {
            "已选 ${goals.size} 项：${goals.joinToString("、")}"
        },
    ) {
        Text(
            text = "打开后会优先推荐对应方向的菜。这些只是筛选条件，不是医学建议。",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(modifier = Modifier.padding(top = 6.dp)) {
            SwitchRow("低卡", health.lowCarb, busy) { onToggle(health.toInput(lowCarb = it)) }
            SwitchRow("低钠", health.lowSodium, busy) { onToggle(health.toInput(lowSodium = it)) }
            SwitchRow("低脂", health.lowFat, busy) { onToggle(health.toInput(lowFat = it)) }
            SwitchRow("高蛋白", health.highProtein, busy) { onToggle(health.toInput(highProtein = it)) }
            SwitchRow("高纤维", health.highFiber, busy) { onToggle(health.toInput(highFiber = it)) }
            SwitchRow("素食", health.vegetarian, busy) { onToggle(health.toInput(vegetarian = it)) }

            // ---- 扩充项 ----
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            SwitchRow(
                label = "控糖",
                hint = "少用精制糖和高糖水果",
                checked = health.lowSugar,
                busy = busy,
            ) { onToggle(health.toInput(lowSugar = it)) }
            SwitchRow(
                label = "补钙",
                hint = "多推荐奶制品、豆制品、深绿蔬菜",
                checked = health.highCalcium,
                busy = busy,
            ) { onToggle(health.toInput(highCalcium = it)) }
            SwitchRow(
                label = "补铁",
                hint = "多推荐红肉、动物肝脏、血制品",
                checked = health.highIron,
                busy = busy,
            ) { onToggle(health.toInput(highIron = it)) }
            SwitchRow(
                label = "低嘌呤",
                hint = "痛风 / 高尿酸适用，避开内脏、浓汤、部分海鲜",
                checked = health.lowPurine,
                busy = busy,
            ) { onToggle(health.toInput(lowPurine = it)) }
            SwitchRow(
                label = "不吃生食",
                hint = "孕期、免疫力较低时建议打开",
                checked = health.noRawFood,
                busy = busy,
            ) { onToggle(health.toInput(noRawFood = it)) }
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 10.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = buildString {
                    append(health.heightCm?.let { "${it.toInt()}cm" } ?: "未填身高")
                    append(" · ")
                    append(health.weightKg?.let { "${it.toInt()}kg" } ?: "未填体重")
                    append(" · ")
                    append(health.age?.let { "${it}岁" } ?: "未填年龄")
                    append(" · ")
                    append(health.activityLevel ?: "未填活动量")
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onEditBody) { Text("编辑") }
        }
    }
}

/** 健康偏好里「已打开」的目标名。摘要和广场公开内容都用它。 */
private fun HealthPreferenceOut.enabledGoals(): List<String> = buildList {
    if (lowCarb) add("低卡")
    if (lowSodium) add("低钠")
    if (lowFat) add("低脂")
    if (highProtein) add("高蛋白")
    if (highFiber) add("高纤维")
    if (vegetarian) add("素食")
    if (lowSugar) add("控糖")
    if (highCalcium) add("补钙")
    if (highIron) add("补铁")
    if (lowPurine) add("低嘌呤")
    if (noRawFood) add("不吃生食")
}

/**
 * 「在广场公开什么」。同样默认折起来。
 *
 * ## 默认全关，而且是**每个区块单独一个开关**
 *
 * 饮食口味和身高体重不是一个量级的隐私。如果只有一个总开关，
 * 用户想晒口味就得连体重一起公开，最后的选择一定是全关 ——
 * 这个功能就等于不存在。拆开之后「公开口味、不公开身体数据」
 * 才是一个用户真的会选的组合。
 *
 * ## 为什么放在个人页而不是广场页
 *
 * 这类设置用户只在「想改隐私」时来找，而他的心理定位是「我的设置」，
 * 不是「广场的操作」。放广场里会让人以为改了只影响广场那一屏。
 */
@Composable
private fun PrivacyCard(
    privacy: PrivacySettingOut,
    busy: Boolean,
    onToggle: (PrivacySettingIn) -> Unit,
) {
    val opened = privacy.openedSections()
    CollapsibleCard(
        title = "在广场公开什么",
        summary = if (opened.isEmpty()) {
            "全部不公开 · 别人只能看到你的昵称、头像和发布的内容"
        } else {
            "已公开 ${opened.size} 项：${opened.joinToString("、")}"
        },
    ) {
        Text(
            text = "广场里别人点开你的主页时，只能看到你打开的这些。" +
                "默认全部关闭，想公开哪一项就打开哪一项。",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(modifier = Modifier.padding(top = 6.dp)) {
            SwitchRow(
                label = "饮食偏好",
                hint = "菜系、口味、忌口",
                checked = privacy.sharePreference,
                busy = busy,
            ) { onToggle(privacy.toInput(sharePreference = it)) }
            SwitchRow(
                label = "健康偏好",
                hint = "低卡、低钠这类饮食目标",
                checked = privacy.shareHealth,
                busy = busy,
            ) { onToggle(privacy.toInput(shareHealth = it)) }
            SwitchRow(
                label = "身体数据",
                hint = "身高、体重、年龄。比上面两项敏感，单独一个开关",
                checked = privacy.shareBody,
                busy = busy,
            ) { onToggle(privacy.toInput(shareBody = it)) }
            SwitchRow(
                label = "家庭成员",
                hint = "家人的称呼与忌口",
                checked = privacy.shareFamily,
                busy = busy,
            ) { onToggle(privacy.toInput(shareFamily = it)) }
            SwitchRow(
                label = "做菜统计",
                hint = "做过多少道菜",
                checked = privacy.shareStats,
                busy = busy,
            ) { onToggle(privacy.toInput(shareStats = it)) }
        }

        Text(
            text = "无论是否公开，你的过敏信息只用于生成菜谱时规避，不会展示给任何人。",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

private fun PrivacySettingOut.openedSections(): List<String> = buildList {
    if (sharePreference) add("饮食偏好")
    if (shareHealth) add("健康偏好")
    if (shareBody) add("身体数据")
    if (shareFamily) add("家庭成员")
    if (shareStats) add("做菜统计")
}

/**
 * 一行开关。hint 是可选的补充说明 —— 像「低嘌呤」这种词，
 * 不解释一句用户根本不知道是干什么用的，会直接跳过。
 */
@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    busy: Boolean,
    hint: String? = null,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = !busy)
    }
}

/* ------------------------------------------------------------------
 *  对话框
 * ------------------------------------------------------------------ */

/**
 * 个性简介编辑。
 *
 * 允许存空 —— 简介是可选信息，用户想清空就该能清空。
 * 所以保存按钮不校验非空，只在超长时拦住（后端限制 200 字）。
 */
@Composable
private fun BioDialog(
    initial: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    val overLimit = value.length > 200

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("个性简介") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { if (it.length <= 220) value = it },
                    label = { Text("写点什么") },
                    placeholder = { Text("如：爱吃辣，不吃香菜；在减脂，晚上吃得少") },
                    minLines = 3,
                    maxLines = 5,
                    isError = overLimit,
                )
                Text(
                    text = "${value.length} / 200",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (overLimit) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && !overLimit,
                onClick = { onConfirm(value) },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 忌口 / 过敏的快捷标签。
 *
 * 清单来自后端 `GET /users/options`，不是写死在 App 里 ——
 * 这样以后往库里加食材（比如新增一种过敏原）改后端就生效，不用发版。
 *
 * 点一下是**追加**到输入框，不是替换：用户往往既要选标签又要手写补充，
 * 比如「香菜、内脏」里只点得出香菜，内脏得自己打。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionQuickPick(
    label: String,
    groups: List<OptionGroup>?,
    selected: List<String>,
    onToggle: (String) -> Unit,
) {
    if (groups.isNullOrEmpty()) return

    Column(modifier = Modifier.padding(top = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        groups.forEach { group ->
            if (group.items.isEmpty()) return@forEach
            Text(
                text = group.group,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            FlowRow(
                modifier = Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                group.items.forEach { item ->
                    FilterChip(
                        selected = item in selected,
                        onClick = { onToggle(item) },
                        label = { Text(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NicknameDialog(
    initial: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改昵称") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("昵称") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                enabled = value.isNotBlank() && !busy,
                onClick = { onConfirm(value) },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreferenceDialog(
    initial: UserPreferenceOut,
    options: UserOptions?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String, Int, String, List<String>, List<String>) -> Unit,
) {
    var cuisine by remember { mutableStateOf(initial.cuisine) }
    var taste by remember { mutableStateOf(initial.taste) }
    var minutes by remember { mutableStateOf(initial.cookTimeMax.toString()) }
    var goal by remember { mutableStateOf(initial.dietGoal) }
    var disliked by remember { mutableStateOf(initial.dislikedFoods.joinToString("、")) }
    var allergies by remember { mutableStateOf(initial.allergies.joinToString("、")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑口味偏好") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ChipGroup(label = "菜系", options = CUISINES, selected = cuisine) { cuisine = it }
                ChipGroup(label = "口味", options = TASTES, selected = taste) { taste = it }
                ChipGroup(label = "饮食目标", options = DIET_GOALS, selected = goal) { goal = it }

                OutlinedTextField(
                    value = minutes,
                    onValueChange = { minutes = it.filter { ch -> ch.isDigit() } },
                    label = { Text("最长烹饪时间（分钟）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )

                OutlinedTextField(
                    value = disliked,
                    onValueChange = { disliked = it },
                    label = { Text("不吃 / 忌口，用「、」分隔") },
                    placeholder = { Text("如 香菜、内脏") },
                )
                OptionQuickPick(
                    label = "常见忌口（点一下加入）",
                    groups = options?.dislikedFoods,
                    selected = splitList(disliked),
                    onToggle = { disliked = toggleItem(disliked, it) },
                )

                OutlinedTextField(
                    value = allergies,
                    onValueChange = { allergies = it },
                    label = { Text("过敏食材，用「、」分隔") },
                    placeholder = { Text("如 花生、虾") },
                )
                OptionQuickPick(
                    label = "常见过敏原（点一下加入）",
                    groups = options?.allergens,
                    selected = splitList(allergies),
                    onToggle = { allergies = toggleItem(allergies, it) },
                )

                if (!options?.disclaimer.isNullOrBlank()) {
                    Text(
                        text = options.disclaimer,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    onConfirm(
                        cuisine,
                        taste,
                        minutes.toIntOrNull() ?: initial.cookTimeMax,
                        goal,
                        splitList(disliked),
                        splitList(allergies),
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipGroup(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = selected == option,
                    onClick = { onSelect(option) },
                    label = { Text(option) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BodyDialog(
    initial: HealthPreferenceOut,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Double?, Double?, Int?, String?) -> Unit,
) {
    var height by remember { mutableStateOf(initial.heightCm?.toInt()?.toString() ?: "") }
    var weight by remember { mutableStateOf(initial.weightKg?.toInt()?.toString() ?: "") }
    var age by remember { mutableStateOf(initial.age?.toString() ?: "") }
    var activity by remember { mutableStateOf(initial.activityLevel ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("身高体重与活动量") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = height,
                        onValueChange = { height = it.filter { ch -> ch.isDigit() } },
                        label = { Text("身高 cm") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = weight,
                        onValueChange = { weight = it.filter { ch -> ch.isDigit() } },
                        label = { Text("体重 kg") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }

                OutlinedTextField(
                    value = age,
                    onValueChange = { age = it.filter { ch -> ch.isDigit() } },
                    label = { Text("年龄") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )

                ChipGroup(
                    label = "日常活动量",
                    options = ACTIVITY_LEVELS,
                    selected = activity,
                    onSelect = { activity = it },
                )

                Text(
                    text = "这些数字只用来估算每日热量需求，不会上传给第三方，也不构成医学建议。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    onConfirm(
                        height.toDoubleOrNull(),
                        weight.toDoubleOrNull(),
                        age.toIntOrNull(),
                        activity.ifBlank { null },
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun FamilyDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (FamilyMemberIn) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var relation by remember { mutableStateOf("") }
    var goal by remember { mutableStateOf(DIET_GOALS.first()) }
    var taste by remember { mutableStateOf(TASTES[1]) }
    var disliked by remember { mutableStateOf("") }
    var allergies by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加家庭成员") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("称呼") },
                        placeholder = { Text("如 妈妈") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = relation,
                        onValueChange = { relation = it },
                        label = { Text("关系") },
                        placeholder = { Text("如 母亲") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                ChipGroup(label = "饮食目标", options = DIET_GOALS, selected = goal) { goal = it }
                ChipGroup(label = "口味", options = TASTES, selected = taste) { taste = it }

                OutlinedTextField(
                    value = disliked,
                    onValueChange = { disliked = it },
                    label = { Text("忌口，用「、」分隔") },
                    singleLine = true,
                )

                OutlinedTextField(
                    value = allergies,
                    onValueChange = { allergies = it },
                    label = { Text("过敏，用「、」分隔") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && !busy,
                onClick = {
                    onConfirm(
                        FamilyMemberIn(
                            name = name.trim(),
                            relation = relation.trim(),
                            dietGoal = goal,
                            taste = taste,
                            dislikedFoods = splitList(disliked),
                            allergies = splitList(allergies),
                        )
                    )
                },
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/* ------------------------------------------------------------------
 *  工具
 * ------------------------------------------------------------------ */

/** 把「香菜、内脏」「花生,虾」这类输入切成干净的列表 */
private fun splitList(raw: String): List<String> =
    raw.split('、', ',', '，', ';', '；')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

/** 在「、」分隔的字符串里加入或移除一项，返回新的字符串 */
private fun toggleItem(raw: String, item: String): String {
    val items = splitList(raw).toMutableList()
    if (!items.remove(item)) items.add(item)
    return items.joinToString("、")
}

/** 后端 `/users/avatar` 的 MIME 白名单，必须和 backend/app/api/v1/users.py 的 AVATAR_MIME 一致 */
private val AVATAR_ALLOWED_MIME = setOf("image/jpeg", "image/jpg", "image/png", "image/webp")


/**
 * 把「改一个开关」表达成完整的入参。
 *
 * 后端接口要求整个健康偏好对象，但界面上一次只动一个开关，
 * 所以用默认参数把其余字段原样带过去。
 *
 * ⚠️ 新增健康偏好字段时**必须同步加到这里**，否则会出静默数据丢失：
 * 用户打开「低糖」时，其余字段会按这里的默认值提交，
 * 漏掉的字段就会被后端覆盖成 false —— 表现为「开了低糖，高钙自己关了」。
 */
private fun HealthPreferenceOut.toInput(
    lowCarb: Boolean = this.lowCarb,
    lowSodium: Boolean = this.lowSodium,
    lowFat: Boolean = this.lowFat,
    highProtein: Boolean = this.highProtein,
    highFiber: Boolean = this.highFiber,
    vegetarian: Boolean = this.vegetarian,
    lowSugar: Boolean = this.lowSugar,
    highCalcium: Boolean = this.highCalcium,
    highIron: Boolean = this.highIron,
    lowPurine: Boolean = this.lowPurine,
    noRawFood: Boolean = this.noRawFood,
    heightCm: Double? = this.heightCm,
    weightKg: Double? = this.weightKg,
    age: Int? = this.age,
    activityLevel: String? = this.activityLevel,
): HealthPreferenceIn = HealthPreferenceIn(
    lowCarb = lowCarb,
    lowSodium = lowSodium,
    lowFat = lowFat,
    highProtein = highProtein,
    highFiber = highFiber,
    vegetarian = vegetarian,
    lowSugar = lowSugar,
    highCalcium = highCalcium,
    highIron = highIron,
    lowPurine = lowPurine,
    noRawFood = noRawFood,
    heightCm = heightCm,
    weightKg = weightKg,
    age = age,
    activityLevel = activityLevel,
)
