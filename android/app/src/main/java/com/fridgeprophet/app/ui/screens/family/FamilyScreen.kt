package com.fridgeprophet.app.ui.screens.family

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fridgeprophet.app.R
import com.fridgeprophet.app.data.remote.dto.FamilyAccountMember
import com.fridgeprophet.app.data.remote.dto.FamilyOut
import com.fridgeprophet.app.ui.components.LoadingBox
import com.fridgeprophet.app.ui.components.Pill
import com.fridgeprophet.app.ui.components.SectionCard
import com.fridgeprophet.app.ui.theme.SemanticColors

/**
 * 家庭组：邀请家人一起管理冰箱。
 *
 * ⚠️ 这个页面管的是**账号关联**（谁和我共享冰箱），
 * 和「我的 → 家庭成员」那张卡（家人忌口档案）不是一回事。
 * 两者并存：爷爷奶奶没账号就记忌口，爸妈有账号就邀请进来。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilyScreen(
    onBack: () -> Unit,
    viewModel: FamilyViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    var showCreateDialog by remember { mutableStateOf(false) }
    var confirmingLeave by remember { mutableStateOf(false) }
    var memberToManage by remember { mutableStateOf<FamilyAccountMember?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.family?.name?.takeIf { it.isNotBlank() } ?: "我的家庭",
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
            when {
                state.loading -> LoadingBox(text = "正在读取家庭信息…")

                state.family == null -> NotJoinedContent(
                    joinCode = state.joinCode,
                    busy = state.busy,
                    message = state.message,
                    error = state.error,
                    onJoinCodeChange = viewModel::setJoinCode,
                    onJoin = viewModel::join,
                    onCreate = { showCreateDialog = true },
                    onDismissMessage = viewModel::clearMessages,
                )

                else -> JoinedContent(
                    family = state.family!!,
                    busy = state.busy,
                    message = state.message,
                    error = state.error,
                    onRegenerate = viewModel::regenerateCode,
                    onManageMember = { memberToManage = it },
                    onLeave = { confirmingLeave = true },
                    onDismissMessage = viewModel::clearMessages,
                )
            }
        }
    }

    if (showCreateDialog) {
        CreateFamilyDialog(
            busy = state.busy,
            onConfirm = {
                viewModel.createFamily(it)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
    }

    memberToManage?.let { member ->
        MemberActionDialog(
            member = member,
            busy = state.busy,
            onSetRole = { role ->
                viewModel.setRole(member.userId, role)
                memberToManage = null
            },
            onRemove = {
                viewModel.removeMember(member.userId)
                memberToManage = null
            },
            onDismiss = { memberToManage = null },
        )
    }

    if (confirmingLeave) {
        val isOwner = state.family?.myRole == "owner"
        AlertDialog(
            onDismissRequest = { confirmingLeave = false },
            title = { Text(if (isOwner) "解散家庭？" else "退出家庭？") },
            text = {
                Text(
                    if (isOwner) {
                        "你是家庭主，退出会**解散整个家庭**：其他成员都会失去共享，" +
                            "但各自加的食材和菜谱都还在，不会被删。\n\n" +
                            "如果只是想暂时不共享，可以先不改。"
                    } else {
                        "退出后就看不到家人的冰箱和菜谱了，你自己加的东西都还在。"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.leave()
                    confirmingLeave = false
                }) {
                    Text(if (isOwner) "解散" else "退出", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingLeave = false }) { Text("取消") }
            },
        )
    }
}

/* ------------------------------------------------------------------
 *  还没加入家庭
 * ------------------------------------------------------------------ */

@Composable
private fun NotJoinedContent(
    joinCode: String,
    busy: Boolean,
    message: String?,
    error: String?,
    onJoinCodeChange: (String) -> Unit,
    onJoin: () -> Unit,
    onCreate: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MessageBar(message, error, onDismissMessage)

        SectionCard {
            Text(text = "和家人共用一台冰箱", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "加入同一个家庭后，冰箱、菜谱、采购清单全家共享 —— " +
                    "谁买回来的东西，别人都看得见。\n" +
                    "个人画像和健康数据不会共享。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // ---------- 加入家庭 ----------
        SectionCard {
            Text(text = "加入家人的家庭", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "让家人把他的邀请码发给你，输进来就行。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            OutlinedTextField(
                value = joinCode,
                onValueChange = onJoinCodeChange,
                label = { Text("邀请码") },
                placeholder = { Text("6 位，比如 K7M2QP") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
            )
            Button(
                onClick = onJoin,
                enabled = !busy && joinCode.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .heightIn(min = 48.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("加入")
                }
            }
        }

        // ---------- 自己建一个 ----------
        SectionCard {
            Text(text = "或者自己建一个家庭", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "建好后你会是家庭主，可以生成邀请码邀请别人，" +
                    "也能决定对方是「成员」（能改）还是「只读」。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            OutlinedButton(
                onClick = onCreate,
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .heightIn(min = 48.dp),
            ) { Text("创建家庭") }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/* ------------------------------------------------------------------
 *  已加入家庭
 * ------------------------------------------------------------------ */

@Composable
private fun JoinedContent(
    family: FamilyOut,
    busy: Boolean,
    message: String?,
    error: String?,
    onRegenerate: (String) -> Unit,
    onManageMember: (FamilyAccountMember) -> Unit,
    onLeave: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    val isOwner = family.myRole == "owner"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MessageBar(message, error, onDismissMessage)

        // ---------- 邀请码 ----------
        if (family.inviteCode != null) {
            SectionCard {
                Text(text = "邀请码", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = family.inviteCode,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    text = "把这个码发给家人，让他们在自己 App 的「我的家庭」里输入。" +
                        "大小写都行，空格也不影响。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (isOwner) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 10.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Text(
                        text = "换一个码会让旧的立刻失效 —— 码不小心发错群了就用这个。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { onRegenerate("member") },
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) { Text("换成「成员」码") }

                        OutlinedButton(
                            onClick = { onRegenerate("viewer") },
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) { Text("换成「只读」码") }
                    }
                }
            }
        } else {
            SectionCard {
                Text(text = "你是只读成员", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "能看到全家的冰箱和菜谱，但不能修改。" +
                        "想帮忙改的话，让家庭主把你的身份调成「成员」。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        // ---------- 成员 ----------
        SectionCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "家庭成员", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${family.memberCount} 人",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            family.members.forEach { member ->
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 10.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = member.nickname.ifBlank { member.email },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (member.isMe) {
                                Text(
                                    text = "（我）",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            text = member.email,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Pill(
                        text = roleLabel(member.role),
                        color = if (member.role == "viewer") {
                            SemanticColors.neutral
                        } else {
                            SemanticColors.fresh
                        },
                    )

                    // 家庭主可以管别人，但管不了自己（改自己身份 = 换家庭主，本版本不支持）
                    if (isOwner && !member.isMe) {
                        TextButton(onClick = { onManageMember(member) }) { Text("管理") }
                    }
                }
            }
        }

        // ---------- 退出 ----------
        OutlinedButton(
            onClick = onLeave,
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text(
                text = if (isOwner) "解散家庭" else "退出家庭",
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

/* ------------------------------------------------------------------
 *  弹窗
 * ------------------------------------------------------------------ */

@Composable
private fun CreateFamilyDialog(
    busy: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("创建家庭") },
        text = {
            Column {
                Text(
                    text = "给家庭起个名字，方便家人确认加对了。之后可以随时换邀请码。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(20) },
                    label = { Text("家庭名") },
                    placeholder = { Text("比如：陶家") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = { onConfirm(name) }) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun MemberActionDialog(
    member: FamilyAccountMember,
    busy: Boolean,
    onSetRole: (String) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmingRemove by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(member.nickname.ifBlank { member.email }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "当前身份：${roleLabel(member.role)}",
                    style = MaterialTheme.typography.bodyMedium,
                )

                if (confirmingRemove) {
                    Text(
                        text = "确定把他移出家庭？\n" +
                            "只是解除共享 —— 他自己加的食材和菜谱都留着，不会被删。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        text = "「成员」能看能改；「只读」只能看，不能动冰箱和菜谱。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onSetRole("member") },
                            enabled = !busy && member.role != "member",
                            modifier = Modifier.weight(1f),
                        ) { Text("设为成员") }

                        OutlinedButton(
                            onClick = { onSetRole("viewer") },
                            enabled = !busy && member.role != "viewer",
                            modifier = Modifier.weight(1f),
                        ) { Text("设为只读") }
                    }
                }
            }
        },
        confirmButton = {
            if (confirmingRemove) {
                TextButton(enabled = !busy, onClick = onRemove) {
                    Text("移出", color = MaterialTheme.colorScheme.error)
                }
            } else {
                TextButton(onClick = { confirmingRemove = true }) {
                    Text("移出家庭", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = {
            if (confirmingRemove) {
                TextButton(onClick = { confirmingRemove = false }) { Text("返回") }
            } else {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

/* ------------------------------------------------------------------
 *  小工具
 * ------------------------------------------------------------------ */

@Composable
private fun MessageBar(message: String?, error: String?, onDismiss: () -> Unit) {
    val text = error ?: message ?: return
    val isError = error != null

    Surface(
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    }
}

private fun roleLabel(role: String): String = when (role) {
    "owner" -> "家庭主"
    "viewer" -> "只读"
    else -> "成员"
}
