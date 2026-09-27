package com.fridgeprophet.app.ui.screens.plaza

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fridgeprophet.app.R
import com.fridgeprophet.app.data.remote.ApiClient
import com.fridgeprophet.app.data.remote.dto.PostOut
import com.fridgeprophet.app.ui.components.AvatarImage
import com.fridgeprophet.app.ui.components.RemoteImage
import com.fridgeprophet.app.ui.theme.SemanticColors
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 把后端的时间戳转成「3 小时前」这种人话。
 *
 * ## 为什么要兼容三种解析
 *
 * 后端用的是 `DateTime(timezone=True)`，但同一个字段在 SQLite 和 PostgreSQL 上
 * 序列化出来的字符串**不一样**：
 *   - PostgreSQL → `2026-09-24T09:00:00+00:00`（带偏移量）
 *   - SQLite     → `2026-09-24T09:00:00`（没时区，SQLite 不存时区）
 * 开发期用 SQLite、部署后用 PostgreSQL，只写一种解析方式的话，
 * 上线当天时间显示就会全变成「时间未知」。
 *
 * naive 时间按 **UTC** 解释：数据库里存的就是 UTC，SQLite 只是把 tzinfo 丢了。
 * 按本地时区解释会整体差 8 小时，表现为「刚发的动态显示 8 小时前」。
 */
fun relativeTime(iso: String): String {
    val instant = parseInstant(iso) ?: return ""
    val seconds = java.time.Duration.between(instant, Instant.now()).seconds

    return when {
        // 服务器和手机时钟有几秒偏差时可能算出负数，当成「刚刚」而不是「-3 秒前」
        seconds < 60 -> "刚刚"
        seconds < 3600 -> "${seconds / 60} 分钟前"
        seconds < 86_400 -> "${seconds / 3600} 小时前"
        seconds < 7 * 86_400 -> "${seconds / 86_400} 天前"
        else -> DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .format(instant.atZone(ZoneId.systemDefault()))
    }
}

private fun parseInstant(iso: String): Instant? {
    if (iso.isBlank()) return null
    return runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()
        ?: runCatching { ZonedDateTime.parse(iso).toInstant() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(iso).toInstant(java.time.ZoneOffset.UTC) }
            .getOrNull()
}

/**
 * 一条动态卡片。布局参照 X：头像在左，右侧一列是「昵称行 / 正文 / 配图 / 操作栏」。
 *
 * ## 为什么操作栏是「评论 · 分享 · 点赞」这个顺序
 *
 * 按**代价从低到高**排：评论最轻，分享次之，点赞最重（它是唯一一个
 * 会被别人看到的公开表态）。把点赞放最右是拇指最容易够到的位置。
 *
 * ## 为什么删除按钮只在 isMine 时出现
 *
 * 后端删别人的动态返回 404，所以显示出来也是点了报错。
 * 与其让用户点了才知道不行，不如根本不显示。
 */
@Composable
fun PostCard(
    post: PostOut,
    onLike: () -> Unit,
    onComment: () -> Unit,
    onShare: () -> Unit,
    onOpenAuthor: () -> Unit,
    onOpenPost: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onOpenRecipe: ((Int) -> Unit)? = null,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenPost),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        // 和 SectionCard 统一的发丝边框，全 App 卡片边界观感一致
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(modifier = Modifier.padding(14.dp)) {
            // 头像单独可点：点进作者主页是社交产品的肌肉记忆，
            // 不能要求用户必须点昵称那一小段文字
            AvatarImage(
                avatarUrl = post.author.avatarUrl,
                nickname = post.author.nickname,
                size = 42,
                modifier = Modifier.clickable(onClick = onOpenAuthor),
            )

            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                // ---------- 昵称行 ----------
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = post.author.nickname,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable(onClick = onOpenAuthor),
                    )
                    Text(
                        text = " · ${relativeTime(post.createdAt)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (post.content.isNotBlank()) {
                    Text(
                        text = post.content,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                // ---------- 成品图 ----------
                if (post.imageUrl != null) {
                    val absolute = ApiClient.absoluteUrl(post.imageUrl)
                    Box(
                        modifier = Modifier
                            .padding(top = 10.dp)
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        RemoteImage(
                            url = absolute.orEmpty(),
                            contentDescription = "成品图",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth(),
                            // 加载中和失败都留一块同色底，不出现跳变或裂图
                            loading = {},
                            error = {},
                        )
                    }
                }

                // ---------- 菜谱与做法 ----------
                if (post.recipeName != null) {
                    RecipeChip(
                        name = post.recipeName,
                        steps = post.steps,
                        recipeId = post.recipeId,
                        onOpenRecipe = onOpenRecipe,
                    )
                }

                if (post.tags.isNotEmpty()) {
                    Text(
                        text = post.tags.joinToString(" ") { "#$it" },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(top = 10.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )

                // ---------- 操作栏 ----------
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ActionButton(
                        iconRes = R.drawable.ic_action_comment,
                        count = post.commentCount,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = onComment,
                    )
                    ActionButton(
                        iconRes = R.drawable.ic_action_share,
                        count = post.shareCount,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = onShare,
                    )
                    ActionButton(
                        iconRes = if (post.likedByMe) R.drawable.ic_action_like_filled
                        else R.drawable.ic_action_like,
                        count = post.likeCount,
                        // 已赞用专门的爱心色，一眼能看出自己的表态
                        tint = if (post.likedByMe) SemanticColors.liked
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = onLike,
                    )
                    if (onDelete != null) {
                        ActionButton(
                            iconRes = R.drawable.ic_action_delete,
                            count = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            onClick = onDelete,
                        )
                    } else {
                        // 占位，让上面三个按钮的位置在有无删除键时保持一致
                        Spacer(Modifier.width(36.dp))
                    }
                }
            }
        }
    }
}

/**
 * 菜谱条：菜名 + 前两步做法。
 *
 * 只显示前两步是刻意的 —— 广场列表是**扫读**场景，
 * 把 8 步全铺出来会把一条动态撑到一屏高，后面的内容就没人看了。
 * 想看完整做法点进菜谱详情。
 */
@Composable
private fun RecipeChip(
    name: String,
    steps: List<String>,
    recipeId: Int?,
    onOpenRecipe: ((Int) -> Unit)?,
) {
    val clickable = recipeId != null && onOpenRecipe != null
    Column(
        modifier = Modifier
            .padding(top = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))
            .then(
                if (clickable) Modifier.clickable { onOpenRecipe?.invoke(recipeId!!) }
                else Modifier
            )
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_tab_recipe),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = "  $name",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        steps.take(2).forEachIndexed { index, step ->
            Text(
                text = "${index + 1}. $step",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (steps.size > 2) {
            Text(
                text = "还有 ${steps.size - 2} 步，点开看完整做法",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** 操作栏上的一个图标 + 计数。计数为 0 时**不显示数字**，只留图标，视觉更干净。 */
@Composable
private fun ActionButton(
    iconRes: Int,
    count: Int?,
    tint: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        if (count != null && count > 0) {
            Text(
                text = " $count",
                style = MaterialTheme.typography.labelMedium,
                color = tint,
            )
        }
    }
}
