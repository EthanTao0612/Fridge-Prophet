package com.fridgeprophet.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/* ------------------------------------------------------------------
 *  与后端 app/schemas 一一对应。字段名保持 snake_case，
 *  用 @SerialName 映射成 Kotlin 的驼峰命名。
 * ------------------------------------------------------------------ */

// ---------- 鉴权 ----------

@Serializable
data class SendCodeRequest(
    val email: String,
)

/**
 * 发验证码的响应。
 *
 * ⚠️ 后端**故意不区分**「邮箱已注册」和「未注册」——
 * 两种情况返回的结构完全一样，防止有人拿这个公开接口
 * 批量探测「某个邮箱在不在这个平台上」。
 * 所以这里也别去猜，按 sent 走就行。
 */
@Serializable
data class SendCodeResponse(
    val sent: Boolean = false,
    @SerialName("cooldown_seconds") val cooldownSeconds: Int = 60,
    val message: String = "",
)

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    val nickname: String = "",
    /** 邮箱验证码。必填 —— 后端没有它直接 422。 */
    val code: String,
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
)

@Serializable
data class UserBrief(
    val id: Int,
    val email: String,
    val nickname: String = "",
    val onboarded: Boolean = false,
)

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "bearer",
    @SerialName("expires_in") val expiresIn: Int = 0,
    val user: UserBrief,
)

// ---------- 用户画像 ----------

@Serializable
data class UserOut(
    val id: Int,
    val email: String,
    val nickname: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    /** 个性简介，最长 200 字。为空表示没填。 */
    val bio: String? = null,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class UserPreferenceOut(
    val cuisine: String = "家常菜",
    val taste: String = "正常",
    @SerialName("cook_time_max") val cookTimeMax: Int = 30,
    @SerialName("diet_goal") val dietGoal: String = "正常饮食",
    @SerialName("disliked_foods") val dislikedFoods: List<String> = emptyList(),
    val allergies: List<String> = emptyList(),
    val onboarded: Boolean = false,
)

@Serializable
data class UserPreferenceIn(
    val cuisine: String,
    val taste: String,
    @SerialName("cook_time_max") val cookTimeMax: Int,
    @SerialName("diet_goal") val dietGoal: String,
    @SerialName("disliked_foods") val dislikedFoods: List<String>,
    val allergies: List<String>,
)

@Serializable
data class HealthPreferenceOut(
    @SerialName("low_carb") val lowCarb: Boolean = false,
    @SerialName("low_sodium") val lowSodium: Boolean = false,
    @SerialName("low_fat") val lowFat: Boolean = false,
    @SerialName("high_protein") val highProtein: Boolean = false,
    @SerialName("high_fiber") val highFiber: Boolean = false,
    val vegetarian: Boolean = false,
    // ---- 扩充项（2026-09 新增）----
    @SerialName("low_sugar") val lowSugar: Boolean = false,
    @SerialName("high_calcium") val highCalcium: Boolean = false,
    @SerialName("high_iron") val highIron: Boolean = false,
    @SerialName("low_purine") val lowPurine: Boolean = false,
    @SerialName("no_raw_food") val noRawFood: Boolean = false,
    @SerialName("height_cm") val heightCm: Double? = null,
    @SerialName("weight_kg") val weightKg: Double? = null,
    val age: Int? = null,
    @SerialName("activity_level") val activityLevel: String? = null,
)

@Serializable
data class HealthPreferenceIn(
    @SerialName("low_carb") val lowCarb: Boolean,
    @SerialName("low_sodium") val lowSodium: Boolean,
    @SerialName("low_fat") val lowFat: Boolean,
    @SerialName("high_protein") val highProtein: Boolean,
    @SerialName("high_fiber") val highFiber: Boolean,
    val vegetarian: Boolean,
    // ---- 扩充项（2026-09 新增）----
    @SerialName("low_sugar") val lowSugar: Boolean = false,
    @SerialName("high_calcium") val highCalcium: Boolean = false,
    @SerialName("high_iron") val highIron: Boolean = false,
    @SerialName("low_purine") val lowPurine: Boolean = false,
    @SerialName("no_raw_food") val noRawFood: Boolean = false,
    @SerialName("height_cm") val heightCm: Double? = null,
    @SerialName("weight_kg") val weightKg: Double? = null,
    val age: Int? = null,
    @SerialName("activity_level") val activityLevel: String? = null,
)

@Serializable
data class FamilyMemberOut(
    val id: Int,
    val name: String,
    val relation: String = "",
    @SerialName("diet_goal") val dietGoal: String = "正常饮食",
    val taste: String = "正常",
    @SerialName("disliked_foods") val dislikedFoods: List<String> = emptyList(),
    val allergies: List<String> = emptyList(),
    val note: String? = null,
)

@Serializable
data class FamilyMemberIn(
    val name: String,
    val relation: String = "",
    @SerialName("diet_goal") val dietGoal: String = "正常饮食",
    val taste: String = "正常",
    @SerialName("disliked_foods") val dislikedFoods: List<String> = emptyList(),
    val allergies: List<String> = emptyList(),
    val note: String? = null,
)

// ---------- 家庭组（账号关联）----------
//
// ⚠️ 别和上面的 FamilyMemberIn/Out 搞混：
//    FamilyMember* 是「忌口档案」（爷爷不吃辣），对方不用注册；
//    下面这些是「真实账号」（妈妈有自己的 App 账号），共享的是冰箱和菜谱。

@Serializable
data class FamilyAccountMember(
    @SerialName("user_id") val userId: Int,
    val nickname: String = "",
    val email: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    /** owner / member / viewer */
    val role: String = "member",
    @SerialName("joined_at") val joinedAt: String = "",
    @SerialName("is_me") val isMe: Boolean = false,
)

/**
 * 我的家庭。
 *
 * ⚠️ **后端永远返回这个对象**，没加入家庭时 `joined=false`、其余字段为空。
 *
 * 不要改回「后端返回裸 null、客户端用 `FamilyOut?` 接」—— 那样看着干净，
 * 但**根本跑不通**：Retrofit + kotlinx-serialization 拿到的序列化器是非空的
 * （Kotlin 的可空标记在 Java 的 `Type` 里丢了），喂一个字面量 `null` 会抛
 * `JsonDecodingException`。结果是「没加入家庭的用户打开家庭页看到『出错了』」。
 * 这个坑由 `FamilyContractTest` 用 MockWebServer 固定住。
 */
@Serializable
data class FamilyOut(
    /** false = 还没加入任何家庭。其余字段此时都是空值，不要拿它们判断。 */
    val joined: Boolean = false,
    val id: Int = 0,
    val name: String = "",
    @SerialName("my_role") val myRole: String = "member",
    val members: List<FamilyAccountMember> = emptyList(),
    @SerialName("member_count") val memberCount: Int = 0,
    /** 只读成员拿不到邀请码（后端给 null）。 */
    @SerialName("invite_code") val inviteCode: String? = null,
)

@Serializable
data class FamilyCreateIn(val name: String = "我的家")

@Serializable
data class FamilyJoinIn(val code: String)

@Serializable
data class FamilyRoleUpdateIn(val role: String)

@Serializable
data class FamilyInviteCodeIn(val role: String = "member")

@Serializable
data class FamilyLeaveOut(
    val dissolved: Boolean = false,
    val message: String = "",
)

/**
 * 「别人在广场点开我主页时能看到什么」的开关。
 *
 * 全部默认 false（不公开）。健康偏好、身体数据属于敏感信息，
 * 默认公开是不可接受的 —— 用户什么都没选，信息就已经全站可见了。
 */
@Serializable
data class PrivacySettingOut(
    @SerialName("share_preference") val sharePreference: Boolean = false,
    @SerialName("share_health") val shareHealth: Boolean = false,
    @SerialName("share_body") val shareBody: Boolean = false,
    @SerialName("share_family") val shareFamily: Boolean = false,
    @SerialName("share_stats") val shareStats: Boolean = false,
)

@Serializable
data class PrivacySettingIn(
    @SerialName("share_preference") val sharePreference: Boolean,
    @SerialName("share_health") val shareHealth: Boolean,
    @SerialName("share_body") val shareBody: Boolean,
    @SerialName("share_family") val shareFamily: Boolean,
    @SerialName("share_stats") val shareStats: Boolean,
)

@Serializable
data class ProfileOut(
    val user: UserOut,
    val preference: UserPreferenceOut,
    val health: HealthPreferenceOut,
    @SerialName("family_members") val familyMembers: List<FamilyMemberOut> = emptyList(),
    /** 老版本后端不带这个字段，所以给默认值，避免升级过程中解析失败。 */
    val privacy: PrivacySettingOut = PrivacySettingOut(),
)

@Serializable
data class ProfileUpdate(
    val nickname: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    /** 传空字符串可以清空简介；传 null 表示「不改这一项」。 */
    val bio: String? = null,
)

// ---------- 可选项清单（过敏原 / 忌口） ----------

@Serializable
data class OptionGroup(
    val group: String,
    val items: List<String> = emptyList(),
)

@Serializable
data class UserOptions(
    val allergens: List<OptionGroup> = emptyList(),
    @SerialName("disliked_foods") val dislikedFoods: List<OptionGroup> = emptyList(),
    val disclaimer: String = "",
)

// ---------- 食品安全小贴士 ----------

@Serializable
data class FoodTipSummary(
    val id: String,
    val title: String,
    val category: String = "",
    /** 谣言 / 部分属实 / 属实 / 注意 */
    val verdict: String = "",
    val summary: String = "",
)

@Serializable
data class FoodTipDetail(
    val id: String,
    val title: String,
    val category: String = "",
    val verdict: String = "",
    val summary: String = "",
    /** 原因与原理正文 */
    val detail: String = "",
    /** 依据来源，权威机构名称 */
    val source: String = "",
)

@Serializable
data class FoodTipList(
    val total: Int = 0,
    val count: Int = 0,
    val categories: List<String> = emptyList(),
    val verdicts: List<String> = emptyList(),
    val items: List<FoodTipSummary> = emptyList(),
)

@Serializable
data class FoodTipRandom(
    val count: Int = 0,
    val items: List<FoodTipSummary> = emptyList(),
)

// ---------- 冰箱库存 ----------

@Serializable
data class InventoryOut(
    val id: Int,
    @SerialName("food_name") val foodName: String,
    val category: String = "其他",
    val quantity: Double = 0.0,
    val unit: String = "个",
    @SerialName("purchase_date") val purchaseDate: String? = null,
    @SerialName("expiry_date") val expiryDate: String? = null,
    @SerialName("storage_location") val storageLocation: String = "冷藏",
    val note: String? = null,
    val freshness: String = "正常",
    val confidence: Double? = null,
    val source: String = "manual",
    @SerialName("days_left") val daysLeft: Int? = null,
    @SerialName("image_url") val imageUrl: String? = null,
)

@Serializable
data class InventoryCreate(
    @SerialName("food_name") val foodName: String,
    val category: String = "其他",
    val quantity: Double = 1.0,
    val unit: String = "个",
    /** 购买日期，ISO 格式 yyyy-MM-dd。留空后端按今天算。 */
    @SerialName("purchase_date") val purchaseDate: String? = null,
    @SerialName("expiry_date") val expiryDate: String? = null,
    /** 只写了保质期天数、没写具体过期日期时用这个推算。 */
    @SerialName("shelf_life_days") val shelfLifeDays: Int? = null,
    @SerialName("storage_location") val storageLocation: String = "冷藏",
    val note: String? = null,
)

@Serializable
data class InventoryUpdate(
    val quantity: Double? = null,
    val unit: String? = null,
    @SerialName("purchase_date") val purchaseDate: String? = null,
    @SerialName("expiry_date") val expiryDate: String? = null,
    @SerialName("storage_location") val storageLocation: String? = null,
    val note: String? = null,
)

@Serializable
data class ExpiringItem(
    val id: Int,
    @SerialName("food_name") val foodName: String,
    val quantity: Double,
    val unit: String,
    @SerialName("expiry_date") val expiryDate: String? = null,
    @SerialName("days_left") val daysLeft: Int? = null,
    val freshness: String = "正常",
)

@Serializable
data class InventoryStats(
    @SerialName("total_kinds") val totalKinds: Int = 0,
    @SerialName("by_location") val byLocation: Map<String, Int> = emptyMap(),
    @SerialName("expiring_soon") val expiringSoon: Int = 0,
    val expired: Int = 0,
    @SerialName("freshness_breakdown") val freshnessBreakdown: Map<String, Int> = emptyMap(),
)

// ---------- AI 识别 ----------

@Serializable
data class RecognizedFood(
    val name: String,
    val quantity: Double = 1.0,
    val unit: String = "个",
    val confidence: Double = 0.5,
    val category: String = "其他",
    @SerialName("storage_location") val storageLocation: String = "冷藏",
    @SerialName("shelf_life_days") val shelfLifeDays: Int? = null,
    /**
     * 用户在确认页手动指定的日期，优先级高于 AI 猜的 shelf_life_days。
     * AI 只能猜「大概能放几天」，但用户手里这盒牛奶可能已经买了 3 天。
     */
    @SerialName("purchase_date") val purchaseDate: String? = null,
    @SerialName("expiry_date") val expiryDate: String? = null,
)

@Serializable
data class ScanResult(
    @SerialName("scan_id") val scanId: String,
    val foods: List<RecognizedFood> = emptyList(),
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("needs_review") val needsReview: Boolean = false,
    val model: String = "mock",
    val message: String = "",
)

@Serializable
data class ScanConfirmRequest(
    val foods: List<RecognizedFood>,
    @SerialName("default_storage_location") val defaultStorageLocation: String = "冷藏",
)

@Serializable
data class AiStatus(
    @SerialName("ai_enabled") val aiEnabled: Boolean = false,
    @SerialName("vision_model") val visionModel: String = "mock",
    @SerialName("text_model") val textModel: String = "mock",
    val storage: String = "local",
    val note: String = "",
)

// ---------- 菜谱 ----------

@Serializable
data class RecipeIngredientOut(
    val name: String,
    val quantity: Double = 0.0,
    val unit: String = "g",
    val available: Boolean = false,
    val optional: Boolean = false,
)

@Serializable
data class MissingIngredient(
    val name: String,
    val quantity: Double = 0.0,
    val unit: String = "个",
    @SerialName("estimated_price") val estimatedPrice: Double? = null,
)

@Serializable
data class NutritionEstimate(
    @SerialName("calories_kcal") val caloriesKcal: Double? = null,
    @SerialName("protein_g") val proteinG: Double? = null,
    @SerialName("carbs_g") val carbsG: Double? = null,
    @SerialName("fat_g") val fatG: Double? = null,
    @SerialName("fiber_g") val fiberG: Double? = null,
    @SerialName("sodium_mg") val sodiumMg: Double? = null,
    val disclaimer: String = "",
)

@Serializable
data class RecipeOut(
    val id: Int? = null,
    val name: String,
    val description: String = "",
    @SerialName("time_minutes") val timeMinutes: Int = 30,
    val difficulty: String = "easy",
    val ingredients: List<RecipeIngredientOut> = emptyList(),
    @SerialName("missing_ingredients") val missingIngredients: List<MissingIngredient> = emptyList(),
    val steps: List<String> = emptyList(),
    val nutrition: NutritionEstimate = NutritionEstimate(),
    val tags: List<String> = emptyList(),
    @SerialName("uses_expiring") val usesExpiring: List<String> = emptyList(),
    /**
     * 食材已备齐（配料表非空且一样不缺）。
     * 后端算好的标记 —— 别自己拿 missingIngredients.isEmpty() 去猜，
     * 配料表为空的坏数据也会「空」。
     */
    val ready: Boolean = false,
    /**
     * 菜品配图，**相对路径**（如 /static/recipes/tomato-egg.jpg）。
     * 需要自己拼上服务器域名才能加载；为 null 表示没有配图，显示占位样式。
     * 拼接统一走 ApiClient.absoluteUrl()。
     */
    @SerialName("image_url") val imageUrl: String? = null,
    /**
     * 是不是**系统内置**菜谱（菜品库那 192 道，所有用户共用一条记录）。
     *
     * 界面拿它决定要不要显示「删除」：内置的删不掉（后端给 403），
     * 显示一个点了必然报错的按钮比不显示更糟。
     */
    @SerialName("is_builtin") val isBuiltin: Boolean = false,
)

@Serializable
data class RecipeGenerateRequest(
    val count: Int = 3,
    @SerialName("max_time_minutes") val maxTimeMinutes: Int? = null,
    @SerialName("prioritize_expiring") val prioritizeExpiring: Boolean = true,
    @SerialName("extra_notes") val extraNotes: String? = null,
    val save: Boolean = true,
)

@Serializable
data class RecipeGenerateResponse(
    val recipes: List<RecipeOut> = emptyList(),
    val model: String = "mock",
    @SerialName("used_ingredients") val usedIngredients: List<String> = emptyList(),
    @SerialName("expiring_used") val expiringUsed: List<String> = emptyList(),
)

@Serializable
data class MealActionRequest(
    @SerialName("recipe_id") val recipeId: Int,
    val action: String,
    val rating: Int? = null,
)

// ---------- 做菜扣库存 ----------

/**
 * 扣减预览里的一项。
 *
 * [suggestedDeduct] 为 null 表示**单位对不上**（库存记「1 盒豆腐」、菜谱要「300 g」），
 * 后端不猜，前端要渲染成空输入框让用户自己填。
 * 别把它当 0 处理 —— 那会让人以为「扣了 0」，其实是什么都没发生。
 */
@Serializable
data class CookPlanItem(
    val name: String,
    @SerialName("need_quantity") val needQuantity: Double = 0.0,
    @SerialName("need_unit") val needUnit: String = "g",
    @SerialName("stock_item_id") val stockItemId: Int? = null,
    @SerialName("stock_quantity") val stockQuantity: Double? = null,
    @SerialName("stock_unit") val stockUnit: String? = null,
    @SerialName("suggested_deduct") val suggestedDeduct: Double? = null,
    @SerialName("unit_matched") val unitMatched: Boolean = false,
    @SerialName("will_empty") val willEmpty: Boolean = false,
    val optional: Boolean = false,
)

@Serializable
data class CookPlan(
    @SerialName("recipe_id") val recipeId: Int = 0,
    @SerialName("recipe_name") val recipeName: String = "",
    val items: List<CookPlanItem> = emptyList(),
    val missing: List<MissingIngredient> = emptyList(),
)

@Serializable
data class CookDeduction(
    @SerialName("item_id") val itemId: Int,
    val quantity: Double = 0.0,
)

@Serializable
data class CookRequest(
    /** null = 按系统估算扣；空列表 = 只记行为、不动库存。 */
    val deductions: List<CookDeduction>? = null,
    @SerialName("record_history") val recordHistory: Boolean = true,
)

@Serializable
data class CookDeducted(
    val name: String,
    val quantity: Double = 0.0,
    val unit: String = "g",
    val remaining: Double = 0.0,
    val emptied: Boolean = false,
)

@Serializable
data class CookResult(
    @SerialName("recipe_id") val recipeId: Int = 0,
    @SerialName("recipe_name") val recipeName: String = "",
    val deducted: List<CookDeducted> = emptyList(),
    val skipped: List<String> = emptyList(),
    val note: String = "",
)

@Serializable
data class PreferenceInsights(
    @SerialName("total_interactions") val totalInteractions: Int = 0,
    @SerialName("frequently_cooked") val frequentlyCooked: List<String> = emptyList(),
    @SerialName("frequently_skipped") val frequentlySkipped: List<String> = emptyList(),
    val note: String = "",
)

// ---------- 采购 ----------

@Serializable
data class ShoppingItemOut(
    val id: Int,
    @SerialName("food_name") val foodName: String,
    val quantity: Double,
    val unit: String,
    val category: String = "其他",
    @SerialName("estimated_price") val estimatedPrice: Double? = null,
    val checked: Boolean = false,
    @SerialName("applied_to_inventory") val appliedToInventory: Boolean = false,
)

@Serializable
data class ShoppingListOut(
    val id: Int,
    val title: String,
    val status: String = "pending",
    @SerialName("source_recipes") val sourceRecipes: String? = null,
    @SerialName("planned_date") val plannedDate: String? = null,
    val items: List<ShoppingItemOut> = emptyList(),
    @SerialName("estimated_total") val estimatedTotal: Double = 0.0,
)

@Serializable
data class ShoppingBuildRequest(
    @SerialName("recipe_ids") val recipeIds: List<Int> = emptyList(),
    val days: Int = 1,
    val title: String? = null,
)

@Serializable
data class ShoppingItemUpdate(
    val quantity: Double? = null,
    val checked: Boolean? = null,
)

@Serializable
data class ShoppingApplyRequest(
    @SerialName("only_checked") val onlyChecked: Boolean = true,
    @SerialName("shelf_life_days") val shelfLifeDays: Int = 5,
)

// ---------- 通用错误体 ----------

@Serializable
data class ApiError(
    val detail: String = "未知错误",
)

/* ==================================================================
 *  广场（社区）
 *
 *  字段命名对齐后端 app/schemas/social.py。
 *  所有「相对于我」的字段（likedByMe / isMine / followedByMe）都是
 *  后端按当前登录者算好返回的，客户端**不要自己推导** ——
 *  客户端推导意味着每处调用点都要再写一遍「我是谁」，迟早有一处漏掉。
 * ================================================================== */

/**
 * 广场里露出的作者信息。
 *
 * ⚠️ 和上面那个 [UserBrief] **不是一回事**，别混用：
 *   - [UserBrief] 是登录/注册的返回体，带 email —— 只能给「本人」看；
 *   - 这个是广场里给别人看的，**故意不含 email**。
 *     广场是所有人可见的，把邮箱塞进每条动态里等于群发登录凭据。
 */
@Serializable
data class SocialUserBrief(
    val id: Int,
    val nickname: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val bio: String? = null,
)

@Serializable
data class PostOut(
    val id: Int,
    val author: SocialUserBrief,
    val content: String = "",
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("recipe_id") val recipeId: Int? = null,
    @SerialName("recipe_name") val recipeName: String? = null,
    val steps: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    @SerialName("like_count") val likeCount: Int = 0,
    @SerialName("comment_count") val commentCount: Int = 0,
    @SerialName("share_count") val shareCount: Int = 0,
    @SerialName("liked_by_me") val likedByMe: Boolean = false,
    @SerialName("is_mine") val isMine: Boolean = false,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class SortOption(
    val value: String,
    val label: String,
)

@Serializable
data class FeedOut(
    val items: List<PostOut> = emptyList(),
    val total: Int = 0,
    val offset: Int = 0,
    val limit: Int = 20,
    @SerialName("has_more") val hasMore: Boolean = false,
    val sort: String = "composite",
    @SerialName("sort_label") val sortLabel: String = "综合",
    /** 排序选项由后端给，客户端不写死，加一种排序不用发版 */
    @SerialName("sort_options") val sortOptions: List<SortOption> = emptyList(),
)

@Serializable
data class PostCreateRequest(
    val content: String = "",
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("recipe_id") val recipeId: Int? = null,
    val tags: List<String> = emptyList(),
)

@Serializable
data class CommentOut(
    val id: Int,
    val author: SocialUserBrief,
    val content: String,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("is_mine") val isMine: Boolean = false,
)

@Serializable
data class CommentListOut(
    val items: List<CommentOut> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class CommentCreateRequest(val content: String)

@Serializable
data class LikeResultOut(
    @SerialName("post_id") val postId: Int,
    val liked: Boolean,
    @SerialName("like_count") val likeCount: Int,
)

@Serializable
data class ShareResultOut(
    @SerialName("post_id") val postId: Int,
    @SerialName("share_count") val shareCount: Int,
    /** 交给系统分享面板的文案，服务端不主动外发 */
    @SerialName("share_text") val shareText: String = "",
)

@Serializable
data class FollowResultOut(
    @SerialName("user_id") val userId: Int,
    val following: Boolean,
    @SerialName("follower_count") val followerCount: Int,
)

@Serializable
data class FollowUserOut(
    val user: SocialUserBrief,
    @SerialName("followed_by_me") val followedByMe: Boolean = false,
    @SerialName("followed_at") val followedAt: String? = null,
    @SerialName("post_count") val postCount: Int = 0,
)

@Serializable
data class PublicPreference(
    val cuisine: String = "",
    val taste: String = "",
    @SerialName("cook_time_max") val cookTimeMax: Int = 0,
    @SerialName("diet_goal") val dietGoal: String = "",
    @SerialName("disliked_foods") val dislikedFoods: List<String> = emptyList(),
    val allergies: List<String> = emptyList(),
)

/** 健康目标用「key + 中文标签」返回，标签由后端给，客户端不写死。 */
@Serializable
data class PublicHealthGoal(val key: String, val label: String)

@Serializable
data class PublicBody(
    @SerialName("height_cm") val heightCm: Double? = null,
    @SerialName("weight_kg") val weightKg: Double? = null,
    val age: Int? = null,
    @SerialName("activity_level") val activityLevel: String? = null,
)

@Serializable
data class PublicHealth(
    val goals: List<PublicHealthGoal> = emptyList(),
    /** 未公开身体数据时为 null，此时界面上整块不显示 */
    val body: PublicBody? = null,
    val disclaimer: String = "",
)

@Serializable
data class PublicFamilyMember(
    val name: String,
    val relation: String = "",
    @SerialName("diet_goal") val dietGoal: String = "",
    val taste: String = "",
)

/**
 * 别人在广场点开我主页看到的内容。
 *
 * ⚠️ `preference` / `health` / `familyMembers` 为 **null 表示「对方未公开」**，
 * 不是「没填」。界面上必须显示成「TA 未公开」，不能当空数据静默跳过 ——
 * 否则用户会以为是加载失败，而且会把「没公开」误读成「没设置」。
 */
@Serializable
data class PublicProfileOut(
    val user: SocialUserBrief,
    @SerialName("post_count") val postCount: Int = 0,
    @SerialName("follower_count") val followerCount: Int = 0,
    @SerialName("following_count") val followingCount: Int = 0,
    @SerialName("followed_by_me") val followedByMe: Boolean = false,
    @SerialName("is_me") val isMe: Boolean = false,
    val preference: PublicPreference? = null,
    val health: PublicHealth? = null,
    @SerialName("family_members") val familyMembers: List<PublicFamilyMember>? = null,
    @SerialName("cooked_count") val cookedCount: Int? = null,
    val visibility: Map<String, Boolean> = emptyMap(),
)

@Serializable
data class ImageUploadOut(val url: String)

// ---------- 自定义折叠箱 ----------

/**
 * 一个用户自定义的折叠箱。
 *
 * `inventoryIds` 是箱子里的食材 id。客户端拿它和冰箱列表做交集，
 * 比再查一次接口简单，也不会出现两份数据不同步。
 */
@Serializable
data class FoodCategoryOut(
    val id: Int,
    val name: String,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("inventory_ids") val inventoryIds: List<Int> = emptyList(),
)

@Serializable
data class FoodCategoryCreate(
    val name: String,
    @SerialName("inventory_ids") val inventoryIds: List<Int> = emptyList(),
)

@Serializable
data class FoodCategoryUpdate(
    val name: String? = null,
    @SerialName("sort_order") val sortOrder: Int? = null,
)

@Serializable
data class FoodCategoryItems(
    @SerialName("inventory_ids") val inventoryIds: List<Int>,
)

/**
 * 「整批调整折叠箱顺序」的请求。
 *
 * ⚠️ 传的是**完整的、期望的顺序**，不是「把 A 挪到 B 前面」。
 * 后端据此给每个箱子重新编号，是幂等的（见后端 `reorder_categories` 的说明）。
 *
 * 客户端这边有个额外好处：**不用知道服务端当前的 sort_order 是多少**。
 * 本地列表的顺序就是真相，直接把它发上去。
 */
@Serializable
data class FoodCategoryOrder(
    val ids: List<Int>,
)

/**
 * 分类顺序的响应。
 *
 * 顺序（蔬菜在前、「其他」垫底）只在后端 `ingredient_category.CATEGORIES`
 * 里定义一次，客户端通过这个接口拿 —— 自己写一份迟早会不一致。
 */
@Serializable
data class CategoryCount(
    val name: String,
    val count: Int = 0,
)

@Serializable
data class CategoryListResponse(
    val categories: List<CategoryCount> = emptyList(),
)

// ---------- 内置菜品库推荐 ----------

/**
 * 一道推荐菜。
 *
 * `ready` = 必需食材全有；`missing` 是还缺哪些（**中文名**，直接显示给用户）。
 */
@Serializable
data class DishRecommendation(
    val name: String,
    val category: String = "",
    @SerialName("image_url") val imageUrl: String? = null,
    val ready: Boolean = false,
    val matched: List<String> = emptyList(),
    val missing: List<String> = emptyList(),
)

@Serializable
data class RecommendResponse(
    val total: Int = 0,
    @SerialName("ready_count") val readyCount: Int = 0,
    val dishes: List<DishRecommendation> = emptyList(),
)

/** 「照着菜品库里的某道菜生成详细做法」的请求。只传菜名。 */
@Serializable
data class MaterializeRequest(
    val name: String,
)
