package com.fridgeprophet.app.ui.screens.recipes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fridgeprophet.app.core.ApiResult
import com.fridgeprophet.app.core.DataRefreshBus
import com.fridgeprophet.app.data.remote.dto.DishRecommendation
import com.fridgeprophet.app.data.remote.dto.RecipeOut
import com.fridgeprophet.app.data.repository.RecipeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RecipesUiState(
    val loading: Boolean = true,
    val recipes: List<RecipeOut> = emptyList(),
    /** 内置菜品库的推荐（按冰箱现有食材匹配，毫秒级、不花钱） */
    val recommendations: List<DishRecommendation> = emptyList(),
    val readyCount: Int = 0,
    val loadingRecommend: Boolean = false,
    /** 正在「落实」成详细做法的那道推荐菜（菜名）。null = 没有在生成。 */
    val materializing: String? = null,
    val generating: Boolean = false,
    val error: String? = null,
    val info: String? = null,
    /** 快捷筛选，见 filters */
    val filter: String = "推荐",
)

@HiltViewModel
class RecipesViewModel @Inject constructor(
    private val repository: RecipeRepository,
    private val refreshBus: DataRefreshBus,
) : ViewModel() {

    private val _state = MutableStateFlow(RecipesUiState())
    val state: StateFlow<RecipesUiState> = _state.asStateFlow()

    /**
     * 「推荐」放第一个并作为默认。
     *
     * 理由：打开菜谱页最常见的诉求是「我今天能做什么」——
     * 这个问题**查库就能立刻回答**（毫秒级、不花钱、菜品有专属配图）。
     * 而 AI 生成要等 20 秒，不该是用户看到的第一屏。
     * 想看 AI 编的新花样，点「全部」或「AI 生成」就行。
     */
    val filters = listOf("推荐", "全部", "待采购", "现在能做", "15 分钟内", "消耗临期")

    init {
        load()
        viewModelScope.launch {
            refreshBus.events.collect { topic ->
                if (topic == DataRefreshBus.Topic.ALL || topic == DataRefreshBus.Topic.RECIPES) {
                    load(silent = true)
                }
            }
        }
    }

    fun load(silent: Boolean = false) {
        if (!silent) _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            // 推荐和已生成菜谱**并发拉**。
            // 生产库在孟买（单次往返 169ms），串行就是白白多等一次。
            val recDeferred = async {
                _state.update { it.copy(loadingRecommend = true) }
                repository.recommend()
            }

            when (val result = repository.list()) {
                is ApiResult.Success ->
                    _state.update { it.copy(loading = false, recipes = result.data, error = null) }
                is ApiResult.Failure ->
                    _state.update { it.copy(loading = false, error = result.message) }
            }

            // 推荐是次要信息，到了再更新（不阻塞已生成菜谱的显示）
            when (val rec = recDeferred.await()) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        loadingRecommend = false,
                        recommendations = rec.data.dishes,
                        readyCount = rec.data.readyCount,
                    )
                }
                // 推荐拉失败不影响看已生成的菜谱 —— 只在界面上留空
                is ApiResult.Failure -> _state.update { it.copy(loadingRecommend = false) }
            }
        }
    }

    fun setFilter(filter: String) = _state.update { it.copy(filter = filter) }

    fun generate() {
        _state.update { it.copy(generating = true, error = null, info = null) }
        viewModelScope.launch {
            when (val result = repository.generate(count = 3)) {
                is ApiResult.Success -> {
                    _state.update {
                        it.copy(
                            generating = false,
                            info = if (result.data.recipes.isEmpty()) {
                                "没有生成出菜谱。冰箱为空时无法推荐，先去扫描一次。"
                            } else {
                                "已生成 ${result.data.recipes.size} 道菜（模型：${result.data.model}）"
                            },
                        )
                    }
                    load(silent = true)
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(generating = false, error = result.message) }
            }
        }
    }

    /**
     * 点开一道推荐菜 → 生成它的详细做法，成功后跳到详情页。
     *
     * ## 为什么不预先给 192 道菜都生成步骤
     *
     * 那要跑 192 次 AI 调用（慢且贵），而用户实际只会点开其中几道。
     * 改成按需生成，后端还做了**幂等** —— 同一道菜反复点只会调一次 AI。
     *
     * ## 为什么一次只允许生成一道
     *
     * `materializing` 是单值。用户在生成期间点别的卡片，说明他改主意了，
     * 但此刻放行就会并发打两次 AI：既慢，又容易撞上后端限流，
     * 最后两道都失败。所以生成期间直接忽略后续点击（卡片会转圈，
     * 用户看得见「正在忙」）。
     */
    fun materialize(dish: DishRecommendation, onReady: (Int) -> Unit) {
        if (_state.value.materializing != null) return
        _state.update { it.copy(materializing = dish.name, error = null, info = null) }

        viewModelScope.launch {
            when (val result = repository.materialize(dish.name)) {
                is ApiResult.Success -> {
                    val recipe = result.data
                    _state.update { s ->
                        // 顺手把新生成的菜插进本地列表。
                        // 不这么做的话，用户从详情页返回时「全部」标签里还是旧的，
                        // 得再跑一次网络请求才看得到 —— 那是一次白等的等待。
                        val rest = s.recipes.filterNot { it.id == recipe.id }
                        s.copy(materializing = null, recipes = listOf(recipe) + rest)
                    }
                    recipe.id?.let(onReady)
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(materializing = null, error = result.message) }
            }
        }
    }

    fun delete(recipe: RecipeOut) {
        val id = recipe.id ?: return
        viewModelScope.launch {
            when (val result = repository.remove(id)) {
                is ApiResult.Success -> load(silent = true)
                is ApiResult.Failure -> _state.update { it.copy(error = result.message) }
            }
        }
    }

    fun clearMessages() = _state.update { it.copy(error = null, info = null) }

    /**
     * 按当前筛选条件过滤。
     *
     * ⚠️ 这里**刻意不排序**。曾经把「已备齐」的菜排到最后，理由是
     * 「买完写回冰箱后它不该继续占最显眼的位置」——但那是把需求理解偏了：
     * 用户要弱化的是**采购清单里已勾选的条目**，不是菜谱。
     * 菜谱列表一沉底 + 降透明度，看着像「这些菜失效了」，反而不敢点。
     *
     * 现在保持接口返回的原始顺序（后端按创建时间倒序，最新在前），
     * 用卡片上的「已备齐 / 缺 N 样」标签做区分就够了。
     */
    fun visibleRecipes(): List<RecipeOut> = when (_state.value.filter) {
        // 「推荐」显示的是菜品库，不是已生成的菜谱 —— 由 isRecommendTab 分流
        "推荐" -> emptyList()
        // 「待采购」= 还缺东西的
        "待采购" -> _state.value.recipes.filter { !it.ready }
        // 「现在能做」= 食材已备齐，打开冰箱就能开火
        "现在能做" -> _state.value.recipes.filter { it.ready }
        "15 分钟内" -> _state.value.recipes.filter { it.timeMinutes <= 15 }
        "消耗临期" -> _state.value.recipes.filter { it.usesExpiring.isNotEmpty() }
        else -> _state.value.recipes
    }

    /** 当前是不是「推荐」标签 —— 界面据此决定渲染推荐卡片还是菜谱卡片 */
    fun isRecommendTab(): Boolean = _state.value.filter == "推荐"

    fun visibleRecommendations(): List<DishRecommendation> = _state.value.recommendations
}
