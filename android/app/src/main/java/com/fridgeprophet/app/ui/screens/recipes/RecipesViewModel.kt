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
                    val got = result.data.recipes.size
                    _state.update {
                        it.copy(
                            generating = false,
                            info = if (got == 0) {
                                // 空结果有几种可能，但用户只关心「怎么办」
                                "冰箱里现有的食材还做不了菜品库里的菜。先去冰箱页加几样，或者扫描一次。"
                            } else {
                                // ⚠️ 这里**不再写「模型：xxx」**。
                                // 后端现在是从菜品库按食材挑（不调 AI），
                                // 说「模型」会让人以为又去调 AI 了。
                                "已从菜品库挑了 $got 道你现在能做的菜"
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
     * 点开一道推荐菜 → 取它的完整做法，成功后跳到详情页。
     *
     * ## 现在**不调 AI 了**，所以是秒开
     *
     * 菜品库那 192 道菜的步骤、营养、配料，已经**离线生成一次**
     * 并作为系统内置菜谱写进数据库（`user_id` 为 NULL，所有用户共用）。
     * 后端 `POST /recipes/materialize` 现在的实际行为是「按菜名查库返回」，
     * 毫秒级，也不消耗 AI 额度。
     *
     * 之前是「点开才让 AI 现写」，第一次点要等十几秒 ——
     * Ethan 明确否掉了那个设计。
     *
     * ## 为什么仍然保留「一次只允许一道」和 loading 态
     *
     * 即使现在是毫秒级，请求也还是有一次网络往返（公网约 30-60ms）。
     * 没有 loading 态的话，用户点下去到页面切换之间会有一段
     * 「什么都没发生」的空白，手感是「点了没反应」。
     * 转圈 + 压暗其它卡片能让这几十毫秒变得可感知。
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
