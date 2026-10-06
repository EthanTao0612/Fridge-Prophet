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
import kotlinx.coroutines.async
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
    /**
     * 折叠箱的增删改正在请求中。
     *
     * 为什么不复用 `busy`：`busy` 是「食材」那一套操作（改数量、删食材）用的，
     * 两者会同时发生（用户在箱子里删食材、同时又在改名）。
     * 共用一个标志会让互不相关的按钮一起变灰 —— 和铁律 13 里
     * 「全局 busy 导致同卡开关一起闪烁」是同一类问题。
     */
    val boxBusy: Boolean = false,
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
            // ⚠️ **三个请求必须并发，不能串行。**
            //
            // 生产库在 Supabase（孟买），单次往返实测 169ms。
            // 串行发这三个就是 169+338+169 ≈ 680ms，加上手机到电脑的 WiFi 延迟，
            // 用户感觉就是「点一下卡一秒」。
            // 并发之后总耗时 = 最慢的那个，直接省掉一大半。
            //
            // 三个请求互不依赖，天然可以并发。
            val needCategories = s.categoryOrder.isEmpty()

            val categoriesDeferred = if (needCategories) {
                async { repository.categoryOrder() }
            } else null
            val boxesDeferred = async { repository.categories() }
            val listDeferred = async {
                repository.list(
                    storageLocation = s.locationFilter,
                    keyword = s.keyword.ifBlank { null },
                )
            }

            // 先收「食材列表」—— 这是用户真正在等的东西，先渲染它
            when (val result = listDeferred.await()) {
                is ApiResult.Success ->
                    _state.update { it.copy(loading = false, items = result.data, error = null) }
                is ApiResult.Failure ->
                    _state.update { it.copy(loading = false, error = result.message) }
            }

            // 分类顺序和折叠箱是次要信息，到了再更新界面（不阻塞食材显示）
            categoriesDeferred?.let { d ->
                (d.await() as? ApiResult.Success)?.let { ok ->
                    _state.update { st -> st.copy(categoryOrder = ok.data) }
                }
            }
            (boxesDeferred.await() as? ApiResult.Success)?.let { ok ->
                _state.update { st -> st.copy(boxes = ok.data) }
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
    //
    // ## 为什么都带一个 onSuccess 回调
    //
    // 这三个操作都是**从对话框里发起**的。对话框要不要关，取决于服务端认不认 ——
    // 「新建一个重名的箱子」后端会返回 400，这时把对话框关掉、
    // 只在页面顶部留一行红字，用户会以为操作成功了。
    // 所以：成功才关（回调），失败不关（对话框留着让用户改名重试），
    // 错误信息走 `state.error` 显示在页面上。
    //
    // ## 为什么用 boxBusy 而不是乐观更新
    //
    // 铁律 13 说开关类设置要乐观更新 —— 但那是**单个布尔值**的场景
    //（勾了就变，失败再回滚，回滚也不突兀）。
    // 这里是「新建/删除一个实体」，乐观插入一行假的箱子再撤掉，
    // 用户会看到列表闪一下。老老实实等回包 + 转圈更稳。

    fun createBox(name: String, onSuccess: () -> Unit = {}) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || _state.value.boxBusy) return
        _state.update { it.copy(boxBusy = true, error = null) }
        viewModelScope.launch {
            when (val r = repository.createCategory(trimmed)) {
                is ApiResult.Success -> {
                    // 追加到末尾，和后端 `order_by(sort_order, id)` 的顺序一致
                    _state.update {
                        it.copy(boxes = it.boxes + r.data, boxBusy = false, error = null)
                    }
                    onSuccess()
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(boxBusy = false, error = r.message) }
            }
        }
    }

    fun renameBox(id: Int, name: String, onSuccess: () -> Unit = {}) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || _state.value.boxBusy) return
        _state.update { it.copy(boxBusy = true, error = null) }
        viewModelScope.launch {
            when (val r = repository.renameCategory(id, trimmed)) {
                is ApiResult.Success -> {
                    _state.update { st ->
                        st.copy(
                            boxes = st.boxes.map { if (it.id == id) r.data else it },
                            boxBusy = false,
                            error = null,
                        )
                    }
                    onSuccess()
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(boxBusy = false, error = r.message) }
            }
        }
    }

    /**
     * 删除折叠箱。
     *
     * ⚠️ **不删食材** —— 后端只解除归组，食材留在冰箱里。
     * 客户端这边同理：只把箱子从列表里去掉，不动 items。
     * 界面上必须把这一点说清楚（确认框里写了），否则用户不敢点。
     */
    fun deleteBox(id: Int, onSuccess: () -> Unit = {}) {
        if (_state.value.boxBusy) return
        _state.update { it.copy(boxBusy = true, error = null) }
        viewModelScope.launch {
            when (val r = repository.deleteCategory(id)) {
                is ApiResult.Success -> {
                    _state.update { st ->
                        st.copy(
                            boxes = st.boxes.filter { it.id != id },
                            collapsed = st.collapsed - "box:$id",
                            boxBusy = false,
                            error = null,
                        )
                    }
                    onSuccess()
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(boxBusy = false, error = r.message) }
            }
        }
    }

    /**
     * 把一批食材加进折叠箱。
     *
     * 后端**幂等**（已在箱子里的会被跳过），所以这里不用先算差集 ——
     * 客户端算差集反而容易和「家人同时也在加」产生竞态。
     */
    fun addToBox(boxId: Int, inventoryIds: List<Int>, onSuccess: () -> Unit = {}) {
        if (inventoryIds.isEmpty() || _state.value.boxBusy) return
        _state.update { it.copy(boxBusy = true, error = null) }
        viewModelScope.launch {
            when (val r = repository.addToCategory(boxId, inventoryIds)) {
                is ApiResult.Success -> {
                    _state.update { st ->
                        st.copy(
                            boxes = st.boxes.map { if (it.id == boxId) r.data else it },
                            boxBusy = false,
                            error = null,
                        )
                    }
                    onSuccess()
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(boxBusy = false, error = r.message) }
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

    /**
     * 把折叠箱上移 / 下移一格。
     *
     * `delta` 传 -1（上移）或 +1（下移）。
     *
     * ## 为什么这里**可以**乐观更新，而新建/删除不行
     *
     * 上面三个操作（新建 / 重命名 / 删除）是**增删实体**：
     * 乐观插一行假的箱子再撤掉，用户会看到列表闪一下，比等回包更糟。
     *
     * 排序不一样 —— 它是**纯顺序变化**，不增不删，
     * 乐观更新只是把两行换个位置，视觉上是平滑的；
     * 而等一次往返（生产库在孟买，约 169ms）再动，手感就是「点一下卡一下」。
     *
     * ## 为什么不用 boxBusy 挡连点
     *
     * 每次请求发的都是**完整的期望顺序**，所以是幂等的：
     * 连点两次 = 连发两个完整顺序，**后到的那个赢**，
     * 最终状态一定和本地显示一致。没有「两次请求各改一半」的问题。
     * 挡连点反而会让快速连点丢操作。
     *
     * 失败才退回服务端的真实顺序（`load(silent = true)`）——
     * 不能留着错的乐观结果，否则用户看到的顺序和库里不一致，
     * 下次进来会「莫名其妙变回去」。
     */
    fun moveBox(boxId: Int, delta: Int) {
        val boxes = _state.value.boxes
        val from = boxes.indexOfFirst { it.id == boxId }
        if (from < 0) return
        val to = from + delta
        if (to !in boxes.indices) return

        val reordered = boxes.toMutableList().apply { add(to, removeAt(from)) }
        _state.update { it.copy(boxes = reordered, error = null) }

        viewModelScope.launch {
            when (val r = repository.reorderCategories(reordered.map { it.id })) {
                // 用服务端返回的完整列表覆盖 —— 它可能包含本地没有的箱子
                //（家人刚建的），也能纠正任何本地偏差
                is ApiResult.Success -> _state.update {
                    it.copy(boxes = r.data, error = null)
                }
                is ApiResult.Failure -> {
                    _state.update { it.copy(error = r.message) }
                    load(silent = true)
                }
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
