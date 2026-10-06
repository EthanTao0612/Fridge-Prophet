package com.fridgeprophet.app.ui.screens.fridge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fridgeprophet.app.core.ApiResult
import com.fridgeprophet.app.core.DataRefreshBus
import com.fridgeprophet.app.data.remote.dto.InventoryCreate
import com.fridgeprophet.app.data.remote.dto.InventoryOut
import com.fridgeprophet.app.data.remote.dto.InventoryUpdate
import com.fridgeprophet.app.data.remote.dto.FoodCategoryOut
import com.fridgeprophet.app.data.repository.InventoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * 冰箱页的一个分组。要么是默认分类，要么是用户自定义的折叠箱。
 *
 * `boxId` 非空 = 自定义折叠箱。
 */
data class FridgeGroup(
    val title: String,
    val items: List<InventoryOut>,
    val boxId: Int? = null,
) {
    /** 折叠状态的 key。默认分类和折叠箱可能重名，所以加前缀区分 */
    val key: String get() = if (boxId != null) "box:$boxId" else "cat:$title"
}

data class FridgeUiState(
    val loading: Boolean = true,
    val items: List<InventoryOut> = emptyList(),
    /** 分类的**显示顺序**，由后端给（`GET /inventory/categories`） */
    val categoryOrder: List<String> = emptyList(),
    /** 用户自定义的折叠箱 */
    val boxes: List<FoodCategoryOut> = emptyList(),
    /** 收起来的分组 key。默认**全部展开** —— 新用户第一次进来看到空的会以为坏了 */
    val collapsed: Set<String> = emptySet(),
    val locationFilter: String? = null,
    val keyword: String = "",
    val error: String? = null,
    val busy: Boolean = false,
) {
    /** 按分类分好组的食材。顺序跟 `categoryOrder`，空分类不显示。 */
    val groups: List<FridgeGroup>
        get() {
            if (items.isEmpty()) return emptyList()
            val byCategory = items.groupBy { it.category.ifBlank { "其他" } }
            // 先按后端给的顺序，再兜底处理不在列表里的分类
            //（后端加了新分类但客户端还没更新时会走到这里）
            val ordered = categoryOrder.filter { byCategory.containsKey(it) } +
                byCategory.keys.filter { it !in categoryOrder }.sorted()
            return ordered.map { FridgeGroup(it, byCategory.getValue(it)) }
        }

    /**
     * 自定义折叠箱。
     *
     * ⚠️ 食材**同时**出现在默认分类和折叠箱里 —— 这是用户确认过的行为：
     * 默认分类按「属性」分（肉类），折叠箱按「用途」分（火锅材料），
     * 两个维度不冲突。
     */
    val boxGroups: List<FridgeGroup>
        get() {
            if (boxes.isEmpty()) return emptyList()
            val byId = items.associateBy { it.id }
            return boxes.mapNotNull { box ->
                val members = box.inventoryIds.mapNotNull { byId[it] }
                // 空箱子也显示 —— 用户刚建完就看到它消失会以为没建成
                FridgeGroup(box.name, members, boxId = box.id)
            }
        }

    val hasAnyGroup: Boolean get() = groups.isNotEmpty() || boxGroups.isNotEmpty()
}

@HiltViewModel
class FridgeViewModel @Inject constructor(
    private val repository: InventoryRepository,
    private val refreshBus: DataRefreshBus,
) : ViewModel() {

    private val _state = MutableStateFlow(FridgeUiState())
    val state: StateFlow<FridgeUiState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            refreshBus.events.collect { topic ->
                if (topic == DataRefreshBus.Topic.ALL ||
                    topic == DataRefreshBus.Topic.INVENTORY
                ) {
                    load(silent = true)
                }
            }
        }
    }

    fun load(silent: Boolean = false) {
        if (!silent) _state.update { it.copy(loading = true, error = null) }
        val s = _state.value

        viewModelScope.launch {
            // 分类顺序和折叠箱一起拉。两个都是小请求，串行比并发好读。
            //
            // 分类顺序从后端拿而不是客户端写死：顺序（蔬菜在前、「其他」垫底）
            // 只在后端定义了一次，客户端抄一份迟早会不一致。
            if (s.categoryOrder.isEmpty()) {
                (repository.categoryOrder() as? ApiResult.Success)?.let {
                    _state.update { st -> st.copy(categoryOrder = it.data) }
                }
            }
            when (val boxes = repository.categories()) {
                is ApiResult.Success -> _state.update { it.copy(boxes = boxes.data) }
                is ApiResult.Failure -> Unit  // 折叠箱拉失败不影响看食材
            }

            when (val result = repository.list(
                storageLocation = s.locationFilter,
                keyword = s.keyword.ifBlank { null },
            )) {
                is ApiResult.Success ->
                    _state.update { it.copy(loading = false, items = result.data, error = null) }
                is ApiResult.Failure ->
                    _state.update { it.copy(loading = false, error = result.message) }
            }
        }
    }

    /** 展开/收起一个分组。 */
    fun toggleCollapse(key: String) = _state.update {
        val next = it.collapsed.toMutableSet()
        if (!next.add(key)) next.remove(key)
        it.copy(collapsed = next)
    }

    // ---------- 自定义折叠箱 ----------

    fun createBox(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            when (val r = repository.createCategory(name.trim())) {
                is ApiResult.Success -> {
                    _state.update { it.copy(boxes = it.boxes + r.data, error = null) }
                    // 新建的箱子默认展开，用户能立刻看到自己刚建的东西
                }
                is ApiResult.Failure -> _state.update { it.copy(error = r.message) }
            }
        }
    }

    fun renameBox(id: Int, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            when (val r = repository.renameCategory(id, name.trim())) {
                is ApiResult.Success -> _state.update { st ->
                    st.copy(boxes = st.boxes.map { if (it.id == id) r.data else it }, error = null)
                }
                is ApiResult.Failure -> _state.update { it.copy(error = r.message) }
            }
        }
    }

    /**
     * 删除折叠箱。
     *
     * ⚠️ **不删食材** —— 后端只解除归组，食材留在冰箱里。
     * 客户端这边同理：只把箱子从列表里去掉，不动 items。
     */
    fun deleteBox(id: Int) {
        viewModelScope.launch {
            when (val r = repository.deleteCategory(id)) {
                is ApiResult.Success -> _state.update { st ->
                    st.copy(
                        boxes = st.boxes.filter { it.id != id },
                        collapsed = st.collapsed - "box:$id",
                        error = null,
                    )
                }
                is ApiResult.Failure -> _state.update { it.copy(error = r.message) }
            }
        }
    }

    fun addToBox(boxId: Int, inventoryIds: List<Int>) {
        if (inventoryIds.isEmpty()) return
        viewModelScope.launch {
            when (val r = repository.addToCategory(boxId, inventoryIds)) {
                is ApiResult.Success -> _state.update { st ->
                    st.copy(boxes = st.boxes.map { if (it.id == boxId) r.data else it }, error = null)
                }
                is ApiResult.Failure -> _state.update { it.copy(error = r.message) }
            }
        }
    }

    /** 把食材移出折叠箱。**不删食材**，只是解除归组。 */
    fun removeFromBox(boxId: Int, inventoryId: Int) {
        viewModelScope.launch {
            when (val r = repository.removeFromCategory(boxId, inventoryId)) {
                is ApiResult.Success -> _state.update { st ->
                    st.copy(boxes = st.boxes.map { if (it.id == boxId) r.data else it }, error = null)
                }
                is ApiResult.Failure -> _state.update { it.copy(error = r.message) }
            }
        }
    }

    fun setLocationFilter(location: String?) {
        _state.update { it.copy(locationFilter = location) }
        load()
    }

    fun setKeyword(keyword: String) {
        _state.update { it.copy(keyword = keyword) }
        load()
    }

    /**
     * 手动添加食材。
     *
     * 购买日期和保质期**都是可选的**，不是必填：
     *   - purchaseDate 留空 → 后端按今天算
     *   - shelfLifeDays 留空 → 后端按食材名查默认保质期（比如叶菜 3 天、冷冻肉 90 天）
     * 用户只知道「这盒牛奶买了 3 天了」时，就填购买日期、不填保质期，
     * 后端会拿 3 天前的日期 + 默认保质期算出过期日。
     */
    fun addItem(
        name: String,
        quantity: Double,
        unit: String,
        location: String,
        purchaseDate: LocalDate? = null,
        shelfLifeDays: Int? = null,
    ) {
        if (name.isBlank()) {
            _state.update { it.copy(error = "请填写食材名称") }
            return
        }
        _state.update { it.copy(busy = true) }

        viewModelScope.launch {
            val result = repository.add(
                InventoryCreate(
                    foodName = name.trim(),
                    quantity = quantity,
                    unit = unit,
                    purchaseDate = purchaseDate?.toString(),
                    shelfLifeDays = shelfLifeDays,
                    storageLocation = location,
                )
            )
            _state.update { it.copy(busy = false) }
            when (result) {
                is ApiResult.Success -> {
                    refreshBus.notify(DataRefreshBus.Topic.INVENTORY)
                    load(silent = true)
                }
                is ApiResult.Failure -> _state.update { it.copy(error = result.message) }
            }
        }
    }

    fun updateQuantity(item: InventoryOut, newQuantity: Double) {
        if (newQuantity < 0) return
        _state.update { it.copy(busy = true) }

        viewModelScope.launch {
            val result = repository.update(item.id, InventoryUpdate(quantity = newQuantity))
            _state.update { it.copy(busy = false) }
            when (result) {
                is ApiResult.Success -> load(silent = true)
                is ApiResult.Failure -> _state.update { it.copy(error = result.message) }
            }
        }
    }

    /**
     * 修改已有食材：数量 + 购买日期 + 过期日期。
     *
     * 日期传 null 表示**清空这一项**（后端会把字段置空），
     * 所以调用方要传用户真正想要的最终状态，而不是「不改的项」。
     */
    fun updateItem(
        item: InventoryOut,
        quantity: Double,
        purchaseDate: LocalDate?,
        expiryDate: LocalDate?,
    ) {
        if (quantity < 0) return
        if (purchaseDate != null && expiryDate != null && expiryDate < purchaseDate) {
            _state.update { it.copy(error = "过期日期不能早于购买日期") }
            return
        }
        _state.update { it.copy(busy = true) }

        viewModelScope.launch {
            val result = repository.update(
                item.id,
                InventoryUpdate(
                    quantity = quantity,
                    purchaseDate = purchaseDate?.toString(),
                    expiryDate = expiryDate?.toString(),
                ),
            )
            _state.update { it.copy(busy = false) }
            when (result) {
                is ApiResult.Success -> {
                    refreshBus.notify(DataRefreshBus.Topic.INVENTORY)
                    load(silent = true)
                }
                is ApiResult.Failure -> _state.update { it.copy(error = result.message) }
            }
        }
    }

    fun delete(item: InventoryOut) {
        _state.update { it.copy(busy = true) }

        viewModelScope.launch {
            val result = repository.remove(item.id)
            _state.update { it.copy(busy = false) }
            when (result) {
                is ApiResult.Success -> {
                    refreshBus.notify(DataRefreshBus.Topic.INVENTORY)
                    load(silent = true)
                }
                is ApiResult.Failure -> _state.update { it.copy(error = result.message) }
            }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }
}
