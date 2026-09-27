package com.fridgeprophet.app.ui.screens.recipes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fridgeprophet.app.core.ApiResult
import com.fridgeprophet.app.core.DataRefreshBus
import com.fridgeprophet.app.data.remote.dto.CookDeduction
import com.fridgeprophet.app.data.remote.dto.CookPlan
import com.fridgeprophet.app.data.remote.dto.RecipeOut
import com.fridgeprophet.app.data.repository.RecipeRepository
import com.fridgeprophet.app.data.repository.ShoppingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RecipeDetailUiState(
    val loading: Boolean = true,
    val recipe: RecipeOut? = null,
    val error: String? = null,
    val message: String? = null,
    val buildingShopping: Boolean = false,
    val feedbackSent: String? = null,

    // ---- 做菜扣库存 ----
    /** 正在拉取扣减预览 */
    val loadingPlan: Boolean = false,
    /** 非 null 时展示确认弹窗 */
    val cookPlan: CookPlan? = null,
    /**
     * 用户可编辑的扣减量，key = 库存行 id，value = 输入框原文。
     *
     * 存 String 而不是 Double：用户打字过程中会出现「」「1.」这种中间态，
     * 转成 Double 会立刻变成 null 把输入框清空，没法正常输入。
     */
    val cookAmounts: Map<Int, String> = emptyMap(),
    val cooking: Boolean = false,
)

/** 把 2.0 显示成「2」、1.5 显示成「1.5」，别让用户看见「2.0」这种尾巴。 */
private fun fmtAmount(v: Double): String =
    if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()

@HiltViewModel
class RecipeDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val recipeRepository: RecipeRepository,
    private val shoppingRepository: ShoppingRepository,
    private val refreshBus: DataRefreshBus,
) : ViewModel() {

    private val recipeId: Int = savedStateHandle.get<Int>("recipeId") ?: 0

    private val _state = MutableStateFlow(RecipeDetailUiState())
    val state: StateFlow<RecipeDetailUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        if (recipeId == 0) {
            _state.update { it.copy(loading = false, error = "菜谱 ID 无效") }
            return
        }
        _state.update { it.copy(loading = true, error = null) }

        viewModelScope.launch {
            when (val result = recipeRepository.detail(recipeId)) {
                is ApiResult.Success ->
                    _state.update { it.copy(loading = false, recipe = result.data) }
                is ApiResult.Failure ->
                    _state.update { it.copy(loading = false, error = result.message) }
            }
        }
    }

    /** 用这道菜的缺料生成采购清单 */
    fun buildShoppingList(onSuccess: () -> Unit) {
        _state.update { it.copy(buildingShopping = true, error = null, message = null) }

        viewModelScope.launch {
            when (val result = shoppingRepository.build(
                recipeIds = listOf(recipeId),
                title = "为「${_state.value.recipe?.name ?: "这道菜"}」采购",
            )) {
                is ApiResult.Success -> {
                    _state.update {
                        it.copy(
                            buildingShopping = false,
                            message = "已生成采购清单，共 ${result.data.items.size} 项，预计 ¥${result.data.estimatedTotal}",
                        )
                    }
                    refreshBus.notify(DataRefreshBus.Topic.SHOPPING)
                    onSuccess()
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(buildingShopping = false, error = result.message) }
            }
        }
    }

    fun sendFeedback(action: String) {
        viewModelScope.launch {
            when (val result = recipeRepository.feedback(recipeId, action)) {
                is ApiResult.Success -> {
                    val label = when (action) {
                        "favorite" -> "已收藏，之后会优先推荐类似的菜"
                        "cook" -> "已记录「做过」。系统会据此逐步了解你的口味"
                        "skip" -> "已记录「不想吃」。连续跳过同一道菜会把它排除"
                        else -> "已记录"
                    }
                    _state.update { it.copy(feedbackSent = label, message = label) }
                    refreshBus.notify(DataRefreshBus.Topic.RECIPES)
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(error = result.message) }
            }
        }
    }

    // ---------- 做菜扣库存 ----------

    /**
     * 点「我做这道菜了」：先拉一份扣减预览，让用户过目、能改数，再决定。
     *
     * 扣库存是不可逆的（扣完那行食材就没了），所以不能点一下闷头扣。
     */
    fun openCookDialog() {
        if (recipeId == 0) return
        _state.update { it.copy(loadingPlan = true, error = null, message = null) }

        viewModelScope.launch {
            when (val result = recipeRepository.cookPlan(recipeId)) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        loadingPlan = false,
                        cookPlan = result.data,
                        cookAmounts = result.data.items.mapNotNull { item ->
                            // 单位对不上的项 suggestedDeduct 是 null → 留空让用户自己填。
                            // 刻意不填 0：0 看着像「系统说不用扣」，空着才是「等你决定」。
                            item.stockItemId?.let { id ->
                                id to (item.suggestedDeduct?.let(::fmtAmount) ?: "")
                            }
                        }.toMap(),
                    )
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(loadingPlan = false, error = result.message) }
            }
        }
    }

    fun setCookAmount(itemId: Int, text: String) =
        _state.update { it.copy(cookAmounts = it.cookAmounts + (itemId to text)) }

    /** 取消：什么都不动。库存不扣，也不记「做过」。 */
    fun dismissCookDialog() = _state.update {
        it.copy(cookPlan = null, cookAmounts = emptyMap(), cooking = false)
    }

    /**
     * 确认做菜：按弹窗里最终的量扣库存，并记一笔「做过」。
     *
     * [updateStock] = false 时传**空列表**给后端 —— 那是明确的
     * 「只记一笔，别动库存」，后端会回「按你的选择没有改动库存」，
     * 而不是甩锅给缺料。
     */
    fun confirmCook(updateStock: Boolean = true) {
        val plan = _state.value.cookPlan ?: return
        val amounts = _state.value.cookAmounts

        val deductions = if (!updateStock) {
            emptyList()
        } else {
            // 输入框空着或填 0 的项不提交 —— 用户就是在说「这样别扣」
            plan.items.mapNotNull { item ->
                val id = item.stockItemId ?: return@mapNotNull null
                val qty = amounts[id]?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
                if (qty <= 0) null else CookDeduction(id, qty)
            }
        }

        _state.update { it.copy(cooking = true, error = null) }
        viewModelScope.launch {
            when (val result = recipeRepository.cook(recipeId, deductions)) {
                is ApiResult.Success -> {
                    _state.update {
                        it.copy(
                            cooking = false,
                            cookPlan = null,
                            cookAmounts = emptyMap(),
                            message = result.data.note,
                        )
                    }
                    // 库存变了：首页数字、冰箱列表、采购清单的缺料都要重算
                    refreshBus.notify(DataRefreshBus.Topic.ALL)
                    load()
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(cooking = false, error = result.message) }
            }
        }
    }

    fun clearMessages() = _state.update { it.copy(error = null, message = null) }
}
