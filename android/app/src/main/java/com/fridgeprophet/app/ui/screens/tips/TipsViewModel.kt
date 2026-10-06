package com.fridgeprophet.app.ui.screens.tips

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fridgeprophet.app.core.ApiResult
import com.fridgeprophet.app.data.remote.dto.FoodTipDetail
import com.fridgeprophet.app.data.remote.dto.FoodTipSummary
import com.fridgeprophet.app.data.repository.TipsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TipsUiState(
    val loading: Boolean = true,
    val items: List<FoodTipSummary> = emptyList(),
    val categories: List<String> = emptyList(),
    val verdicts: List<String> = emptyList(),
    /** 当前选中的分类，null 表示「全部」 */
    val category: String? = null,
    /** 当前选中的结论，null 表示「全部」 */
    val verdict: String? = null,
    /** 搜索关键词。**只在客户端过滤**，见 visibleItems() 的说明 */
    val query: String = "",
    val error: String? = null,
)

@HiltViewModel
class TipsViewModel @Inject constructor(
    private val tipsRepository: TipsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(TipsUiState())
    val state: StateFlow<TipsUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = tipsRepository.list(
                category = _state.value.category,
                verdict = _state.value.verdict,
            )
            when (result) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        loading = false,
                        items = result.data.items,
                        // 分类/结论清单用**不带筛选**的那次结果填充，
                        // 否则筛完之后标签栏会越筛越少
                        categories = result.data.categories.ifEmpty { it.categories },
                        verdicts = result.data.verdicts.ifEmpty { it.verdicts },
                    )
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(loading = false, error = result.message) }
            }
        }
    }

    fun selectCategory(category: String?) {
        _state.update { it.copy(category = if (it.category == category) null else category) }
        load()
    }

    fun selectVerdict(verdict: String?) {
        _state.update { it.copy(verdict = if (it.verdict == verdict) null else verdict) }
        load()
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    /**
     * 按关键词过滤当前列表。
     *
     * ## 为什么在客户端过滤，不发给后端
     *
     * 89 条贴士的标题 + 摘要只有几十 KB，**已经全在内存里了**。
     * 本地过滤是即时的、不依赖网络，打字时不会一卡一卡。
     * 发给后端反而要多等一次往返（生产库在孟买，单次约 169ms），
     * 输入框会明显发顿 —— 这是个只有几个字的查询，不值得一次网络请求。
     *
     * ## 为什么只搜标题和摘要，不搜正文
     *
     * 正文（detail）不在列表接口的返回里，搜它就得把 89 条正文全下下来
     *（几十倍的数据量）。而且用户搜索时的心理模型是「标题里有没有这个词」，
     * 命中正文反而会给出看起来不相关的结果。想找正文内容，
     * 点进详情页用浏览器/系统的页内查找更合适。
     *
     * ## 和分类/结论筛选的关系
     *
     * 关键词是在**当前筛选结果之上**再过滤，不是覆盖它。
     * 这样行为可预期：屏幕上显示的就是筛选后的集合。
     * 代价是「选了分类再搜索可能搜不到」——
     * 界面在 0 结果时会提示清掉筛选（见 TipsScreen）。
     */
    fun visibleItems(): List<FoodTipSummary> {
        val q = _state.value.query.trim()
        if (q.isEmpty()) return _state.value.items
        return _state.value.items.filter { tip ->
            tip.title.contains(q, ignoreCase = true) ||
                tip.summary.contains(q, ignoreCase = true)
        }
    }

    /** 当前有没有在搜索 —— 界面据此决定 0 结果时提示什么 */
    fun isSearching(): Boolean = _state.value.query.isNotBlank()

    /** 当前有没有选分类/结论 */
    fun hasFilter(): Boolean = _state.value.category != null || _state.value.verdict != null
}

data class TipDetailUiState(
    val loading: Boolean = true,
    val tip: FoodTipDetail? = null,
    val error: String? = null,
)

@HiltViewModel
class TipDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val tipsRepository: TipsRepository,
) : ViewModel() {

    // 贴士 id 是字符串（如 crab-with-tomato），不是数字
    private val tipId: String = savedStateHandle.get<String>("tipId").orEmpty()

    private val _state = MutableStateFlow(TipDetailUiState())
    val state: StateFlow<TipDetailUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        if (tipId.isBlank()) {
            _state.update { it.copy(loading = false, error = "贴士 ID 无效") }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = tipsRepository.detail(tipId)) {
                is ApiResult.Success ->
                    _state.update { it.copy(loading = false, tip = result.data) }
                is ApiResult.Failure ->
                    _state.update { it.copy(loading = false, error = result.message) }
            }
        }
    }
}
