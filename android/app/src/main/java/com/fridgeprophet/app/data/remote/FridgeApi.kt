package com.fridgeprophet.app.data.remote

import com.fridgeprophet.app.data.remote.dto.AiStatus
import com.fridgeprophet.app.data.remote.dto.CommentCreateRequest
import com.fridgeprophet.app.data.remote.dto.CommentListOut
import com.fridgeprophet.app.data.remote.dto.CommentOut
import com.fridgeprophet.app.data.remote.dto.CookPlan
import com.fridgeprophet.app.data.remote.dto.CookRequest
import com.fridgeprophet.app.data.remote.dto.CookResult
import com.fridgeprophet.app.data.remote.dto.FeedOut
import com.fridgeprophet.app.data.remote.dto.FollowResultOut
import com.fridgeprophet.app.data.remote.dto.FollowUserOut
import com.fridgeprophet.app.data.remote.dto.ImageUploadOut
import com.fridgeprophet.app.data.remote.dto.LikeResultOut
import com.fridgeprophet.app.data.remote.dto.PostCreateRequest
import com.fridgeprophet.app.data.remote.dto.PostOut
import com.fridgeprophet.app.data.remote.dto.PublicProfileOut
import com.fridgeprophet.app.data.remote.dto.ShareResultOut
import com.fridgeprophet.app.data.remote.dto.ExpiringItem
import com.fridgeprophet.app.data.remote.dto.FamilyCreateIn
import com.fridgeprophet.app.data.remote.dto.FamilyInviteCodeIn
import com.fridgeprophet.app.data.remote.dto.FamilyJoinIn
import com.fridgeprophet.app.data.remote.dto.FamilyLeaveOut
import com.fridgeprophet.app.data.remote.dto.FamilyMemberIn
import com.fridgeprophet.app.data.remote.dto.FamilyMemberOut
import com.fridgeprophet.app.data.remote.dto.FamilyOut
import com.fridgeprophet.app.data.remote.dto.FamilyRoleUpdateIn
import com.fridgeprophet.app.data.remote.dto.FoodTipDetail
import com.fridgeprophet.app.data.remote.dto.FoodTipList
import com.fridgeprophet.app.data.remote.dto.FoodTipRandom
import com.fridgeprophet.app.data.remote.dto.HealthPreferenceIn
import com.fridgeprophet.app.data.remote.dto.HealthPreferenceOut
import com.fridgeprophet.app.data.remote.dto.InventoryCreate
import com.fridgeprophet.app.data.remote.dto.InventoryOut
import com.fridgeprophet.app.data.remote.dto.InventoryStats
import com.fridgeprophet.app.data.remote.dto.InventoryUpdate
import com.fridgeprophet.app.data.remote.dto.CategoryListResponse
import com.fridgeprophet.app.data.remote.dto.FoodCategoryCreate
import com.fridgeprophet.app.data.remote.dto.RecommendResponse
import com.fridgeprophet.app.data.remote.dto.FoodCategoryItems
import com.fridgeprophet.app.data.remote.dto.FoodCategoryOut
import com.fridgeprophet.app.data.remote.dto.FoodCategoryUpdate
import com.fridgeprophet.app.data.remote.dto.LoginRequest
import com.fridgeprophet.app.data.remote.dto.MealActionRequest
import com.fridgeprophet.app.data.remote.dto.PreferenceInsights
import com.fridgeprophet.app.data.remote.dto.PrivacySettingIn
import com.fridgeprophet.app.data.remote.dto.PrivacySettingOut
import com.fridgeprophet.app.data.remote.dto.ProfileOut
import com.fridgeprophet.app.data.remote.dto.ProfileUpdate
import com.fridgeprophet.app.data.remote.dto.RecipeGenerateRequest
import com.fridgeprophet.app.data.remote.dto.RecipeGenerateResponse
import com.fridgeprophet.app.data.remote.dto.RecipeOut
import com.fridgeprophet.app.data.remote.dto.RegisterRequest
import com.fridgeprophet.app.data.remote.dto.SendCodeRequest
import com.fridgeprophet.app.data.remote.dto.SendCodeResponse
import com.fridgeprophet.app.data.remote.dto.ScanConfirmRequest
import com.fridgeprophet.app.data.remote.dto.ScanResult
import com.fridgeprophet.app.data.remote.dto.ShoppingApplyRequest
import com.fridgeprophet.app.data.remote.dto.ShoppingBuildRequest
import com.fridgeprophet.app.data.remote.dto.ShoppingItemOut
import com.fridgeprophet.app.data.remote.dto.ShoppingItemUpdate
import com.fridgeprophet.app.data.remote.dto.ShoppingListOut
import com.fridgeprophet.app.data.remote.dto.TokenResponse
import com.fridgeprophet.app.data.remote.dto.UserOptions
import com.fridgeprophet.app.data.remote.dto.UserOut
import com.fridgeprophet.app.data.remote.dto.UserPreferenceIn
import com.fridgeprophet.app.data.remote.dto.UserPreferenceOut
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 后端接口定义。与 backend/app/api/v1 下的路由一一对应。
 *
 * 注意：路径不带前导斜杠，因为 BASE_URL 已经以 "/" 结尾。
 *
 * ⚠️ 所有 DELETE 接口**不写返回类型**（即返回 `Unit`）。
 * 后端删除成功时返回 204 No Content，响应体是空的。
 *
 * 这里踩过两次坑，而且第一次的结论是错的，记录清楚：
 *
 * Retrofit 的 suspend 适配器会在响应体为空时做非空校验，不满足就抛
 *   "Response from FridgeApi.xxx was null but response body type was declared as non-null"
 * 表现是「所有删除功能全部失败」。
 *
 * ❌ 写成 `ResponseBody?` 加问号**不管用**。Retrofit 判断可空性靠的是
 *    Kotlin 编译器有没有在类型参数上留下 `@Nullable` 类型注解，
 *    这套组合（Retrofit 2.12 + Kotlin 2.2.21 + JVM 17）下它拿不到，
 *    于是照样当成非空抛异常 —— 加问号只是看起来对，运行时一模一样地炸。
 *
 * ✅ 正确做法：返回 `Unit`（也就是干脆不写返回类型）。
 *    Retrofit 对 `Unit` 有专门的短路分支，直接跳过响应体解析，
 *    根本不会走到那个非空校验。
 *
 * 这条约束由 app/src/test/.../DeleteContractTest.kt 看着，
 * 里面还留了一个 `ResponseBody?` 的反例，防止有人再「顺手加个问号」。
 */
interface FridgeApi {

    // ---------- 鉴权 ----------

    @POST("api/v1/auth/send-code")
    suspend fun sendCode(@Body body: SendCodeRequest): SendCodeResponse

    @POST("api/v1/auth/register")
    suspend fun register(@Body body: RegisterRequest): TokenResponse

    @POST("api/v1/auth/login")
    suspend fun login(@Body body: LoginRequest): TokenResponse

    @GET("api/v1/auth/me")
    suspend fun me(): UserOut

    // ---------- 自定义折叠箱 ----------

    @GET("api/v1/food-categories")
    suspend fun foodCategories(): List<FoodCategoryOut>

    @POST("api/v1/food-categories")
    suspend fun createFoodCategory(@Body body: FoodCategoryCreate): FoodCategoryOut

    @PATCH("api/v1/food-categories/{id}")
    suspend fun updateFoodCategory(
        @Path("id") id: Int,
        @Body body: FoodCategoryUpdate,
    ): FoodCategoryOut

    @DELETE("api/v1/food-categories/{id}")
    suspend fun deleteFoodCategory(@Path("id") id: Int)

    @POST("api/v1/food-categories/{id}/items")
    suspend fun addFoodCategoryItems(
        @Path("id") id: Int,
        @Body body: FoodCategoryItems,
    ): FoodCategoryOut

    /**
     * 把食材移出折叠箱。
     *
     * ⚠️ 这是「移出箱子」，**不是删食材** —— 食材本身留在冰箱里。
     * 后端就是这么实现的，客户端别搞混。
     */
    @DELETE("api/v1/food-categories/{id}/items/{itemId}")
    suspend fun removeFoodCategoryItem(
        @Path("id") id: Int,
        @Path("itemId") itemId: Int,
    ): FoodCategoryOut

    // ---------- 用户画像 ----------

    @GET("api/v1/users/profile")
    suspend fun getProfile(): ProfileOut

    @PATCH("api/v1/users/profile")
    suspend fun updateProfile(@Body body: ProfileUpdate): UserOut

    /** 上传头像。后端会把文件存到 Supabase Storage（未配置则落本地磁盘）并返回新的 UserOut。 */
    @Multipart
    @POST("api/v1/users/avatar")
    suspend fun uploadAvatar(@Part file: MultipartBody.Part): UserOut

    /** 过敏原 / 忌口的可选项清单。放后端是为了以后扩充时不用发版。 */
    @GET("api/v1/users/options")
    suspend fun getUserOptions(): UserOptions

    @PUT("api/v1/users/preference")
    suspend fun savePreference(@Body body: UserPreferenceIn): UserPreferenceOut

    @PUT("api/v1/users/health")
    suspend fun saveHealth(@Body body: HealthPreferenceIn): HealthPreferenceOut

    @GET("api/v1/users/family")
    suspend fun listFamily(): List<FamilyMemberOut>

    @POST("api/v1/users/family")
    suspend fun addFamily(@Body body: FamilyMemberIn): FamilyMemberOut

    @DELETE("api/v1/users/family/{id}")
    suspend fun deleteFamily(@Path("id") id: Int)

    // ---------- 家庭组（账号关联）----------
    //
    // ⚠️ 和上面那三个 /users/family 不是一回事：
    //    /users/family  = 家人的**忌口档案**（对方不用注册，只影响菜谱推荐）
    //    /family        = 我的**家庭组**（真实账号关联，共享冰箱和菜谱）
    // 名字像，但用途完全不同。别把两者合并。

    /**
     * 我的家庭。**没加入家庭时也返回对象**，看 `joined` 字段区分。
     *
     * ⚠️ 别把返回类型改成 `FamilyOut?` 去接一个裸 null ——
     * Retrofit + kotlinx-serialization 吃不下，会抛 JsonDecodingException。
     * 详见 `FamilyContractTest`。
     */
    @GET("api/v1/family")
    suspend fun getFamily(): FamilyOut

    @POST("api/v1/family")
    suspend fun createFamily(@Body body: FamilyCreateIn): FamilyOut

    @POST("api/v1/family/join")
    suspend fun joinFamily(@Body body: FamilyJoinIn): FamilyOut

    /** 换邀请码，顺便定「拿这个码加入的人算什么身份」。只有家庭主能调。 */
    @POST("api/v1/family/invite-code")
    suspend fun regenerateInviteCode(@Body body: FamilyInviteCodeIn): FamilyOut

    @PATCH("api/v1/family/members/{id}")
    suspend fun updateFamilyMemberRole(
        @Path("id") id: Int,
        @Body body: FamilyRoleUpdateIn,
    ): FamilyOut

    @DELETE("api/v1/family/members/{id}")
    suspend fun removeFamilyMember(@Path("id") id: Int): FamilyOut

    @POST("api/v1/family/leave")
    suspend fun leaveFamily(): FamilyLeaveOut

    // ---------- 冰箱库存 ----------

    @GET("api/v1/inventory")
    suspend fun listInventory(
        @Query("storage_location") storageLocation: String? = null,
        @Query("category") category: String? = null,
        @Query("keyword") keyword: String? = null,
    ): List<InventoryOut>

    /** 食材分类的显示顺序（由后端定义，客户端不要自己写一份） */
    @GET("api/v1/inventory/categories")
    suspend fun inventoryCategories(): CategoryListResponse

    @GET("api/v1/inventory/expiring")
    suspend fun listExpiring(@Query("within_days") withinDays: Int = 3): List<ExpiringItem>

    @GET("api/v1/inventory/stats")
    suspend fun inventoryStats(): InventoryStats

    @POST("api/v1/inventory")
    suspend fun createInventory(@Body body: InventoryCreate): InventoryOut

    @PATCH("api/v1/inventory/{id}")
    suspend fun updateInventory(
        @Path("id") id: Int,
        @Body body: InventoryUpdate,
    ): InventoryOut

    @DELETE("api/v1/inventory/{id}")
    suspend fun deleteInventory(@Path("id") id: Int)

    /** 用户确认 AI 识别结果后写入库存 */
    @POST("api/v1/inventory/confirm")
    suspend fun confirmScan(@Body body: ScanConfirmRequest): List<InventoryOut>

    // ---------- AI 识别 ----------

    @Multipart
    @POST("api/v1/vision/scan")
    suspend fun scanFridge(@Part file: MultipartBody.Part): ScanResult

    @GET("api/v1/vision/status")
    suspend fun aiStatus(): AiStatus

    // ---------- 菜谱 ----------

    @POST("api/v1/recipes/generate")
    suspend fun generateRecipes(@Body body: RecipeGenerateRequest): RecipeGenerateResponse

    @GET("api/v1/recipes")
    suspend fun listRecipes(@Query("limit") limit: Int = 20): List<RecipeOut>

    /** 按冰箱现有食材推荐能做的菜（查库，毫秒级、不花钱） */
    @GET("api/v1/recipes/recommend")
    suspend fun recommendRecipes(
        @Query("limit") limit: Int = 30,
        @Query("category") category: String? = null,
    ): RecommendResponse

    @GET("api/v1/recipes/{id}")
    suspend fun getRecipe(@Path("id") id: Int): RecipeOut

    @DELETE("api/v1/recipes/{id}")
    suspend fun deleteRecipe(@Path("id") id: Int)

    @POST("api/v1/recipes/feedback")
    suspend fun submitFeedback(@Body body: MealActionRequest): ResponseBody

    /** 做这道菜会扣掉冰箱里什么（只算不扣，供确认弹窗展示）。 */
    @GET("api/v1/recipes/{id}/cook-plan")
    suspend fun getCookPlan(@Path("id") id: Int): CookPlan

    /** 确认做菜：按实际用量扣减库存。 */
    @POST("api/v1/recipes/{id}/cook")
    suspend fun cookRecipe(@Path("id") id: Int, @Body body: CookRequest): CookResult

    @GET("api/v1/recipes/insights/preference")
    suspend fun preferenceInsights(): PreferenceInsights

    // ---------- 采购 ----------

    @POST("api/v1/shopping/build")
    suspend fun buildShoppingList(@Body body: ShoppingBuildRequest): ShoppingListOut

    @GET("api/v1/shopping")
    suspend fun listShopping(): List<ShoppingListOut>

    @PATCH("api/v1/shopping/items/{id}")
    suspend fun updateShoppingItem(
        @Path("id") id: Int,
        @Body body: ShoppingItemUpdate,
    ): ShoppingItemOut

    @DELETE("api/v1/shopping/items/{id}")
    suspend fun deleteShoppingItem(@Path("id") id: Int)

    @POST("api/v1/shopping/{id}/apply")
    suspend fun applyShoppingList(
        @Path("id") id: Int,
        @Body body: ShoppingApplyRequest,
    ): ShoppingListOut

    @DELETE("api/v1/shopping/{id}")
    suspend fun deleteShoppingList(@Path("id") id: Int)

    // ---------- 食品安全小贴士 ----------
    // 这三个接口**不需要登录**，后端刻意没加鉴权：
    // 内容是静态公共知识，不含用户数据，且登录页也要能展示一条。

    // ---------- 广场（社区）----------
    // 全部需要登录：返回体里带 liked_by_me / is_mine 这类「相对于我」的字段，
    // 没有身份就算不出来。

    @GET("api/v1/social/feed")
    suspend fun getFeed(
        @Query("sort") sort: String = "composite",
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
        @Query("only_following") onlyFollowing: Boolean = false,
    ): FeedOut

    @POST("api/v1/social/posts")
    suspend fun createPost(@Body body: PostCreateRequest): PostOut

    @GET("api/v1/social/posts/{id}")
    suspend fun getPost(@Path("id") id: Int): PostOut

    @DELETE("api/v1/social/posts/{id}")
    suspend fun deletePost(@Path("id") id: Int)

    @POST("api/v1/social/posts/{id}/like")
    suspend fun likePost(@Path("id") id: Int): LikeResultOut

    @DELETE("api/v1/social/posts/{id}/like")
    suspend fun unlikePost(@Path("id") id: Int): LikeResultOut

    @POST("api/v1/social/posts/{id}/share")
    suspend fun sharePost(@Path("id") id: Int): ShareResultOut

    @GET("api/v1/social/posts/{id}/comments")
    suspend fun listComments(@Path("id") id: Int): CommentListOut

    @POST("api/v1/social/posts/{id}/comments")
    suspend fun addComment(@Path("id") id: Int, @Body body: CommentCreateRequest): CommentOut

    @DELETE("api/v1/social/comments/{id}")
    suspend fun deleteComment(@Path("id") id: Int)

    @Multipart
    @POST("api/v1/social/image")
    suspend fun uploadPostImage(@Part file: MultipartBody.Part): ImageUploadOut

    @GET("api/v1/social/users/search")
    suspend fun searchUsers(@Query("q") keyword: String): List<FollowUserOut>

    @GET("api/v1/social/me/following-ids")
    suspend fun myFollowingIds(): List<Int>

    @GET("api/v1/social/users/{id}")
    suspend fun getPublicProfile(@Path("id") id: Int): PublicProfileOut

    @POST("api/v1/social/users/{id}/follow")
    suspend fun followUser(@Path("id") id: Int): FollowResultOut

    @DELETE("api/v1/social/users/{id}/follow")
    suspend fun unfollowUser(@Path("id") id: Int): FollowResultOut

    @GET("api/v1/social/users/{id}/followers")
    suspend fun listFollowers(@Path("id") id: Int): List<FollowUserOut>

    @GET("api/v1/social/users/{id}/following")
    suspend fun listFollowing(@Path("id") id: Int): List<FollowUserOut>

    /** 读取「我的主页在广场里公开什么」。 */
    @GET("api/v1/users/privacy")
    suspend fun getPrivacy(): PrivacySettingOut

    /**
     * 保存公开性设置。
     *
     * ⚠️ 后端**只认当前登录者**，请求体里没有 user_id。
     * 所以这里传的永远是「我自己的」开关，不存在改到别人头上的可能。
     */
    @PUT("api/v1/users/privacy")
    suspend fun savePrivacy(@Body body: PrivacySettingIn): PrivacySettingOut

    @GET("api/v1/tips")
    suspend fun listTips(
        @Query("category") category: String? = null,
        @Query("verdict") verdict: String? = null,
        @Query("limit") limit: Int? = null,
    ): FoodTipList

    /** 首页标语位用：每次进来随机换一条。 */
    /**
     * `exclude` 传当前正在显示的那条 id，保证拿回来的是**另一条**。
     * 不传就是纯随机，有概率抽到同一条（见后端 tips.py 的说明）。
     */
    @GET("api/v1/tips/random")
    suspend fun randomTips(
        @Query("count") count: Int = 1,
        @Query("exclude") exclude: String? = null,
    ): FoodTipRandom

    @GET("api/v1/tips/{id}")
    suspend fun getTip(@Path("id") id: String): FoodTipDetail
}
