package com.fridgeprophet.app.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fridgeprophet.app.core.ApiResult
import com.fridgeprophet.app.core.DataRefreshBus
import com.fridgeprophet.app.data.remote.dto.ExpiringItem
import com.fridgeprophet.app.data.remote.dto.FoodTipSummary
import com.fridgeprophet.app.data.remote.dto.InventoryOut
import com.fridgeprophet.app.data.remote.dto.InventoryStats
import com.fridgeprophet.app.data.remote.dto.RecipeOut
import com.fridgeprophet.app.data.repository.InventoryRepository
import com.fridgeprophet.app.data.repository.RecipeRepository
import com.fridgeprophet.app.data.repository.TipsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val loading: Boolean = true,
    val stats: InventoryStats? = null,
    val items: List<InventoryOut> = emptyList(),
    val expiring: List<ExpiringItem> = emptyList(),
    val recommendations: List<RecipeOut> = emptyList(),
    val generating: Boolean = false,
    /** 首页标语位的那条食品安全贴士。为 null 时整块不渲染。 */
    val tip: FoodTipSummary? = null,
    val error: String? = null,
    val infoMessage: String? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val inventoryRepository: InventoryRepository,
    private val recipeRepository: RecipeRepository,
    private val tipsRepository: TipsRepository,
    private val refreshBus: DataRefreshBus,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        refresh()
        // ⚠️ 这里**故意不调 loadTip()**。
        // 贴士要「每次切换界面都换一条」，触发点必须是「界面进入」这个事件，
        // 而 init 只在 ViewModel 首次创建时跑一次 —— ViewModel 活得比界面久，
        // 切回首页时它不会重建，贴在 init 里就只会在冷启动时换一次。
        // 现在的触发点是 HomeScreen 的 LaunchedEffect，见那边的说明。
        // 扫描页确认入库后会广播，首页据此刷新
        viewModelScope.launch {
            refreshBus.events.collect { topic ->
                if (topic == DataRefreshBus.Topic.ALL ||
                    topic == DataRefreshBus.Topic.INVENTORY
                ) {
                    refresh(silent = true)
                }
            }
        }
    }

    /**
     * 取一条随机贴士放标语位。
     *
     * 由 `HomeScreen` 在**每次进入界面**时调用（切 Tab 回来也算），
     * 所以这里要把当前这条的 id 传下去当 `exclude`，
     * 保证换到的是另一条而不是原地不动。
     *
     * 失败**不写 error**：贴士是装饰性内容，网络不好时整块不显示就行，
     * 没必要为此在首页顶一条红色错误提示去干扰用户。
     * 而且 TipsRepository 内部有缓存兜底，能拿到就还是显示。
     */
    fun loadTip() {
        val currentId = _state.value.tip?.id
        viewModelScope.launch {
            val result = tipsRepository.random(count = 1, excludeId = currentId)
            if (result is ApiResult.Success) {
                // 兜底路径理论上不会返回同一条，但万一返回了就别更新 ——
                // 显示「同一条」和「没反应」在用户眼里是一回事，
                // 不如保留原样，至少不会闪一下。
                val next = result.data.items.firstOrNull()
                if (next != null && next.id != currentId) {
                    _state.update { it.copy(tip = next) }
                } else if (_state.value.tip == null && next != null) {
                    _state.update { it.copy(tip = next) }
                }
            }
        }
    }

    fun refresh(silent: Boolean = false) {
        if (!silent) _state.update { it.copy(loading = true, error = null) }

        viewModelScope.launch {
            // ⚠️ **并发**发这三个请求，不要串行。
            //
            // 生产库在 Supabase（孟买），**单次往返实测 169ms**。
            // 串行就是 3 × 169 ≈ 510ms，并发只要 ~170ms ——
            // 而首页是打开 App 的第一屏，这 340ms 用户能明显感觉到
            //（实测过：冰箱页做同样的改动后 850ms → 169ms）。
            //
            // 三个请求之间没有依赖（都是只读），并发是安全的。
            val statsDeferred = async { inventoryRepository.stats() }
            val listDeferred = async { inventoryRepository.list() }
            val expiringDeferred = async { inventoryRepository.expiring(3) }

            val statsResult = statsDeferred.await()
            val listResult = listDeferred.await()
            val expiringResult = expiringDeferred.await()

            val failure = listOf(statsResult, listResult, expiringResult)
                .filterIsInstance<ApiResult.Failure>()
                .firstOrNull()

            _state.update {
                it.copy(
                    loading = false,
                    stats = (statsResult as? ApiResult.Success)?.data ?: it.stats,
                    items = (listResult as? ApiResult.Success)?.data ?: it.items,
                    expiring = (expiringResult as? ApiResult.Success)?.data ?: it.expiring,
                    error = failure?.message,
                )
            }
        }
    }

    fun clearMessages() = _state.update { it.copy(error = null, infoMessage = null) }

    /** 首页的「今天吃什么」：直接调后端生成菜谱 */
    fun generateRecipes() {
        if (_state.value.items.isEmpty()) {
            _state.update { it.copy(infoMessage = "冰箱还是空的，先扫描一次吧") }
            return
        }
        _state.update { it.copy(generating = true, error = null, infoMessage = null) }

        viewModelScope.launch {
            when (val result = recipeRepository.generate(count = 3)) {
                is ApiResult.Success -> {
                    val recipes = result.data.recipes
                    _state.update {
                        it.copy(
                            generating = false,
                            recommendations = recipes,
                            infoMessage = if (recipes.isEmpty()) {
                                "没有生成出菜谱，请检查冰箱库存"
                            } else {
                                "已根据你的库存和偏好生成 ${recipes.size} 道菜"
                            },
                        )
                    }
                    refreshBus.notify(DataRefreshBus.Topic.RECIPES)
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(generating = false, error = result.message) }
            }
        }
    }
}
