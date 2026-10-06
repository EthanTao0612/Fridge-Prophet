package com.fridgeprophet.app.ui.screens.shopping

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fridgeprophet.app.core.ApiResult
import com.fridgeprophet.app.core.DataRefreshBus
import com.fridgeprophet.app.data.remote.dto.RecipeOut
import com.fridgeprophet.app.data.remote.dto.ShoppingItemOut
import com.fridgeprophet.app.data.remote.dto.ShoppingListOut
import com.fridgeprophet.app.data.repository.RecipeRepository
import com.fridgeprophet.app.data.repository.ShoppingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ShoppingUiState(
    val loading: Boolean = true,
    val lists: List<ShoppingListOut> = emptyList(),
    /** 供「按菜谱生成清单」时挑选 */
    val recipes: List<RecipeOut> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

@HiltViewModel
class ShoppingViewModel @Inject constructor(
    private val shoppingRepository: ShoppingRepository,
    private val recipeRepository: RecipeRepository,
    private val refreshBus: DataRefreshBus,
) : ViewModel() {

    private val _state = MutableStateFlow(ShoppingUiState())
    val state: StateFlow<ShoppingUiState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            refreshBus.events.collect { topic ->
                if (topic == DataRefreshBus.Topic.ALL || topic == DataRefreshBus.Topic.SHOPPING) {
                    load(silent = true)
                }
            }
        }
    }

    fun load(silent: Boolean = false) {
        if (!silent) _state.update { it.copy(loading = true, error = null) }

        viewModelScope.launch {
            // ⚠️ 两个请求**并发**发，不要串行。
            // 生产库在 Supabase（孟买），单次往返 169ms ——
            // 串行就白多花一次往返。
            val listsDeferred = async { shoppingRepository.list() }
            // 菜谱列表只为「新建清单」服务，拉失败不影响主流程
            val recipesDeferred = async { recipeRepository.list(limit = 30) }
            val listsResult = listsDeferred.await()
            val recipesResult = recipesDeferred.await()

            val failure = listsResult as? ApiResult.Failure

            _state.update {
                it.copy(
                    loading = false,
                    lists = (listsResult as? ApiResult.Success)?.data ?: it.lists,
                    recipes = (recipesResult as? ApiResult.Success)?.data ?: it.recipes,
                    error = failure?.message,
                )
            }
        }
    }

    /**
     * 勾选 / 取消勾选。
     *
     * 这里做乐观更新：先在本地翻转，再发请求。勾选是个高频动作，
     * 每次都等一个网络往返会让界面发顿；失败了再拉一次回滚。
     */
    fun toggleItem(item: ShoppingItemOut) {
        val target = !item.checked
        patchItem(item.id) { it.copy(checked = target) }

        viewModelScope.launch {
            when (val result = shoppingRepository.updateItem(item.id, checked = target)) {
                is ApiResult.Success -> Unit
                is ApiResult.Failure -> {
                    _state.update { it.copy(error = result.message) }
                    load(silent = true)
                }
            }
        }
    }

    fun updateItemQuantity(item: ShoppingItemOut, quantity: Double) {
        if (quantity <= 0) return
        patchItem(item.id) { it.copy(quantity = quantity) }

        viewModelScope.launch {
            when (val result = shoppingRepository.updateItem(item.id, quantity = quantity)) {
                is ApiResult.Success -> Unit
                is ApiResult.Failure -> {
                    _state.update { it.copy(error = result.message) }
                    load(silent = true)
                }
            }
        }
    }

    fun deleteItem(item: ShoppingItemOut) {
        _state.update { st ->
            st.copy(lists = st.lists.map { l ->
                l.copy(items = l.items.filterNot { it.id == item.id })
            })
        }

        viewModelScope.launch {
            when (val result = shoppingRepository.deleteItem(item.id)) {
                is ApiResult.Success -> {
                    refreshBus.notify(DataRefreshBus.Topic.SHOPPING)
                    load(silent = true)
                }
                is ApiResult.Failure -> {
                    _state.update { it.copy(error = result.message) }
                    load(silent = true)
                }
            }
        }
    }

    /** 买完了：把清单里的东西写回冰箱库存，同时更新保质期 */
    /**
     * 买完了 → 把勾选的条目写回冰箱库存。
     *
     * ## 为什么先改本地、再发请求（乐观更新）
     *
     * 写回冰箱是**逐条**改库存（每条还要跨家庭查同名合并），
     * 生产库在 Supabase（孟买），**一次往返 169ms** —— 10 项就是一两秒。
     *
     * 原来的写法是「等服务器回来 → 再 load() 一次 → 卡片才变暗」，
     * 用户看到的是：点了按钮、转圈、一两秒后界面才动。
     * Ethan 反馈的就是这个：「完成采购之后采购项变暗响应太慢」。
     *
     * 现在：
     *   ① 立刻把勾选的条目标成「已入库」、整单标成完成（变暗）
     *   ② 再发请求
     *   ③ 成功 → 用**服务端返回的那份**替换本地（它是权威数据，
     *      而且省掉一次多余的 `load()`）
     *   ④ 失败 → 报错 + 重新加载（回滚）
     *
     * ⚠️ 判定「整单完成」的规则要和后端一致：
     * 后端是 `if all(i.checked for i in row.items): row.status = "done"`。
     * 规则改了这里也要改，否则会出现「界面变暗了但服务器还是 pending」
     *（下次刷新又弹回来）。
     */
    fun applyToList(list: ShoppingListOut, onlyChecked: Boolean = true) {
        val targets = list.items.filter { (it.checked || !onlyChecked) && !it.appliedToInventory }
        if (targets.isEmpty()) {
            _state.update {
                it.copy(message = "没有可入库的条目。先勾选你买到的东西，再点一次")
            }
            return
        }

        val targetIds = targets.map { it.id }.toSet()
        val willAllBeChecked = list.items.all { it.checked || it.id in targetIds }

        _state.update { st ->
            st.copy(
                busy = true,
                error = null,
                message = null,
                lists = st.lists.map { l ->
                    if (l.id != list.id) {
                        l
                    } else {
                        l.copy(
                            status = if (willAllBeChecked) "done" else l.status,
                            items = l.items.map { item ->
                                if (item.id in targetIds) {
                                    item.copy(appliedToInventory = true, checked = true)
                                } else {
                                    item
                                }
                            },
                        )
                    }
                },
            )
        }

        viewModelScope.launch {
            when (val result = shoppingRepository.applyToList(list.id, onlyChecked)) {
                is ApiResult.Success -> {
                    val updated = result.data
                    val applied = updated.items.count { it.appliedToInventory }
                    _state.update { st ->
                        st.copy(
                            busy = false,
                            message = "已把 $applied 样食材写回冰箱，保质期按默认天数估算",
                            // 服务端返回的就是最新状态，直接替换本地那份
                            lists = st.lists.map { if (it.id == updated.id) updated else it },
                        )
                    }
                    // 冰箱页要跟着变。**不要** notify(SHOPPING) ——
                    // 本 ViewModel 就订阅了那个 topic，那等于自己触发一次
                    // 全量 reload，白多一次往返。
                    refreshBus.notify(DataRefreshBus.Topic.INVENTORY)
                }
                is ApiResult.Failure -> {
                    _state.update { it.copy(busy = false, error = result.message) }
                    // 乐观更新要回滚：拉一次真实状态覆盖掉本地那份
                    load(silent = true)
                }
            }
        }
    }

    fun deleteList(list: ShoppingListOut) {
        _state.update { it.copy(busy = true) }

        viewModelScope.launch {
            when (val result = shoppingRepository.deleteList(list.id)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(busy = false) }
                    refreshBus.notify(DataRefreshBus.Topic.SHOPPING)
                    load(silent = true)
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(busy = false, error = result.message) }
            }
        }
    }

    /**
     * 按选中的菜谱生成清单。
     *
     * 「需要多少 − 冰箱里有多少 = 要买多少」这个差集完全由后端算，
     * 客户端只负责把菜谱 id 传过去。这是刻意的：算术不该经过模型。
     */
    fun buildFrom(recipeIds: List<Int>, title: String?) {
        if (recipeIds.isEmpty()) {
            _state.update { it.copy(error = "先选至少一道菜") }
            return
        }
        _state.update { it.copy(busy = true, error = null, message = null) }

        viewModelScope.launch {
            when (val result = shoppingRepository.build(
                recipeIds = recipeIds,
                title = title ?: "按 ${recipeIds.size} 道菜采购",
            )) {
                is ApiResult.Success -> {
                    val count = result.data.items.size
                    _state.update {
                        it.copy(
                            busy = false,
                            message = if (count == 0) {
                                "这些菜的食材你冰箱里都有了，不用买"
                            } else {
                                "已生成清单：${count} 项，预计 ¥%.1f".format(result.data.estimatedTotal)
                            },
                        )
                    }
                    refreshBus.notify(DataRefreshBus.Topic.SHOPPING)
                    load(silent = true)
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(busy = false, error = result.message) }
            }
        }
    }

    fun clearMessages() = _state.update { it.copy(error = null, message = null) }

    /** 在本地把所有清单里 id 匹配的条目改掉（乐观更新的落地） */
    private fun patchItem(itemId: Int, transform: (ShoppingItemOut) -> ShoppingItemOut) {
        _state.update { st ->
            st.copy(lists = st.lists.map { list ->
                list.copy(items = list.items.map { if (it.id == itemId) transform(it) else it })
            })
        }
    }
}
