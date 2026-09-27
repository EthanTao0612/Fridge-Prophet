package com.fridgeprophet.app.ui.theme

import androidx.compose.ui.graphics.Color

/* 品牌色：白 / 绿 / 橙（对应策划书的视觉要求）*/

// 绿 —— 主色，代表新鲜、健康
val GreenDark = Color(0xFF1F5C43)
val GreenPrimary = Color(0xFF2E7D5B)
val GreenLight = Color(0xFF6DB394)
val GreenContainer = Color(0xFFDCEFE4)

// 橙 —— 强调色，用于「即将过期」「采购」等需要提醒的地方
val OrangeDark = Color(0xFF9C4E12)
val OrangePrimary = Color(0xFFE8813A)
val OrangeLight = Color(0xFFF3B183)
val OrangeContainer = Color(0xFFFBE7D6)

// 语义色
val RedPrimary = Color(0xFFC7463B)
val RedContainer = Color(0xFFFADAD7)
val YellowContainer = Color(0xFFFBF0CE)
val YellowOn = Color(0xFF6B4E0B)

/* 中性色
 *
 * ⚠️ 这里的三个层级必须**肉眼可分**，否则卡片会糊在背景里。
 *
 * 原来的 `NeutralLightBg = #FFFDFB` 和 `NeutralSurface = #FFFFFF`
 * 只差 2~4 个色阶 —— 等于白底上放白卡，用户反馈的
 * 「各个功能块的界限不是特别明显」就是它造成的。
 *
 * 现在拉开成三层：
 *   background（浅暖灰） < surface（纯白卡片） > surfaceVariant（卡内嵌块）
 * 卡片是三者里最亮的，自然浮起来；嵌块比卡片暗，在卡片内部又有层次。
 */
val NeutralLightBg = Color(0xFFF4F2EC)
val NeutralLightSurface = Color(0xFFFFFFFF)
// 嵌块色也要跟着拉开。原来 #EFEDE9 和新背景 #F4F2EC 几乎一样，
// 引导页那根用 surfaceVariant 当「未完成」色的进度条会看着缺几段。
// 现在它同时区别于背景（卡外）和卡片（卡内），两处都站得住。
val NeutralLightVariant = Color(0xFFE9E6DF)
val NeutralOnLight = Color(0xFF1C1B19)
val NeutralOnLightVariant = Color(0xFF4A4844)
val OutlineLight = Color(0xFFD6D3CD)

// 深色同理：原来背景和卡片只差 9 个色阶，偏糊。
val NeutralDarkBg = Color(0xFF0E100C)
val NeutralDarkSurface = Color(0xFF1B1E18)
val NeutralDarkVariant = Color(0xFF2A2E26)
val NeutralOnDark = Color(0xFFF2F0EB)
val NeutralOnDarkVariant = Color(0xFFC6C3BA)
val OutlineDark = Color(0xFF3E4238)
