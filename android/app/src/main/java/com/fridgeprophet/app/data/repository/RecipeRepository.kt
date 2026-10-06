package com.fridgeprophet.app.data.repository

import com.fridgeprophet.app.core.ApiResult
import com.fridgeprophet.app.core.safeApiCall
import com.fridgeprophet.app.data.remote.FridgeApi
import com.fridgeprophet.app.data.remote.dto.CookDeduction
import com.fridgeprophet.app.data.remote.dto.CookPlan
import com.fridgeprophet.app.data.remote.dto.CookRequest
import com.fridgeprophet.app.data.remote.dto.CookResult
import com.fridgeprophet.app.data.remote.dto.MealActionRequest
import com.fridgeprophet.app.data.remote.dto.RecipeGenerateRequest
import com.fridgeprophet.app.data.remote.dto.RecipeGenerateResponse
import com.fridgeprophet.app.data.remote.dto.RecommendResponse
import com.fridgeprophet.app.data.remote.dto.RecipeOut
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecipeRepository @Inject constructor(private val api: FridgeApi) {

    /** 核心接口：后端拿库存 + 画像去生成菜谱 */
    suspend fun generate(
        count: Int = 3,
        maxTimeMinutes: Int? = null,
        prioritizeExpiring: Boolean = true,
        extraNotes: String? = null,
    ): ApiResult<RecipeGenerateResponse> = safeApiCall {
        api.generateRecipes(
            RecipeGenerateRequest(
                count = count,
                maxTimeMinutes = maxTimeMinutes,
                prioritizeExpiring = prioritizeExpiring,
                extraNotes = extraNotes,
            )
        )
    }

    suspend fun list(limit: Int = 20): ApiResult<List<RecipeOut>> =
        safeApiCall { api.listRecipes(limit) }

    suspend fun detail(id: Int): ApiResult<RecipeOut> = safeApiCall { api.getRecipe(id) }

    suspend fun remove(id: Int): ApiResult<Unit> = safeApiCall { api.deleteRecipe(id); Unit }

    /** 上报收藏 / 做过 / 跳过 / 评分，用于逐步修正用户画像 */
    suspend fun feedback(recipeId: Int, action: String, rating: Int? = null): ApiResult<Unit> =
        safeApiCall { api.submitFeedback(MealActionRequest(recipeId, action, rating)); Unit }

    /** 做菜前的预览：会用掉冰箱里什么、各多少。只算不扣。 */
    suspend fun cookPlan(id: Int): ApiResult<CookPlan> = safeApiCall { api.getCookPlan(id) }

    /**
     * 确认做菜，扣减库存。
     *
     * [deductions] 传 null = 按后端估算扣；传空列表 = 只记「做过」、不动库存。
     */
    suspend fun cook(
        id: Int,
        deductions: List<CookDeduction>? = null,
    ): ApiResult<CookResult> = safeApiCall {
        api.cookRecipe(id, CookRequest(deductions = deductions))
    }

    /** 按冰箱现有食材推荐能做的菜。查库，毫秒级、不花钱。 */
    suspend fun recommend(limit: Int = 40): ApiResult<RecommendResponse> =
        safeApiCall { api.recommendRecipes(limit = limit) }
}
