package com.fridgeprophet.app.ui.screens.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fridgeprophet.app.core.ApiResult
import com.fridgeprophet.app.core.DataRefreshBus
import com.fridgeprophet.app.data.remote.dto.RecognizedFood
import com.fridgeprophet.app.data.repository.InventoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import javax.inject.Inject

/** 可编辑的识别结果。AI 给的结果一律允许用户改，改完才入库。 */
data class EditableFood(
    val name: String,
    val quantityText: String,
    val unit: String,
    val confidence: Double,
    val storageLocation: String,
    val shelfLifeDays: Int?,
    val included: Boolean = true,
    /**
     * 用户手选的日期，优先级高于 AI 猜的 shelfLifeDays。
     * AI 只能猜「大概能放几天」，但用户手里这盒牛奶可能已经买了 3 天。
     * 两个都留空时，后端按「今天购买 + 按食材名查到的默认保质期」处理。
     */
    val purchaseDate: LocalDate? = null,
    val expiryDate: LocalDate? = null,
) {
    val lowConfidence: Boolean get() = confidence < 0.75

    /** 日期填反了 —— 这个必须拦在提交之前，否则后端会 422，用户看不懂 */
    val hasInvalidDates: Boolean
        get() = purchaseDate != null && expiryDate != null && expiryDate < purchaseDate

    fun toRecognizedFood(): RecognizedFood? {
        val qty = quantityText.toDoubleOrNull() ?: return null
        if (name.isBlank() || qty < 0) return null
        return RecognizedFood(
            name = name.trim(),
            quantity = qty,
            unit = unit.ifBlank { "个" },
            confidence = confidence,
            storageLocation = storageLocation,
            shelfLifeDays = shelfLifeDays,
            purchaseDate = purchaseDate?.toString(),
            expiryDate = expiryDate?.toString(),
        )
    }
}

enum class ScanPhase { CAMERA, ANALYZING, RESULT }

data class ScanUiState(
    val phase: ScanPhase = ScanPhase.CAMERA,
    val foods: List<EditableFood> = emptyList(),
    val modelName: String = "",
    val message: String = "",
    val aiEnabled: Boolean = true,
    /**
     * 今天还剩几次 AI 调用。`-1` = 还不知道（状态接口没拿到）。
     *
     * ⚠️ 默认值必须是 -1 而不是 0：0 会让界面以为「额度用完了」，
     * 于是接口一失败就把拍照按钮灰掉 —— 把网络问题显示成额度问题，
     * 用户会以为今天不能用了。
     */
    val quotaLeft: Int = -1,
    val quotaLow: Boolean = false,
    val error: String? = null,
    val confirming: Boolean = false,
    val confirmedCount: Int = 0,
) {
    /** 额度确实用完了（而不是「还不知道」）。界面据此禁用拍照。 */
    val quotaExhausted: Boolean get() = quotaLeft == 0
}

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val repository: InventoryRepository,
    private val refreshBus: DataRefreshBus,
) : ViewModel() {

    private val _state = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            when (val result = repository.aiStatus()) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        aiEnabled = result.data.aiEnabled,
                        message = result.data.note,
                        quotaLeft = result.data.aiQuotaLeft,
                        quotaLow = result.data.aiQuotaLow,
                    )
                }
                // 状态接口失败不影响使用 —— 配额保持 -1（「还不知道」），
                // 界面就不会误判成「额度用完」
                is ApiResult.Failure -> Unit
            }
        }
    }

    /**
     * 重新拉一次状态。
     *
     * 用在**识别完之后** —— 刚花掉一次额度，屏幕上的「还剩 N 次」得跟着变，
     * 否则用户会以为额度没动。不做成实时轮询：额度一天才变几十次，
     * 每次识别后刷一次足够了。
     */
    fun refreshStatus() {
        viewModelScope.launch {
            when (val result = repository.aiStatus()) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        quotaLeft = result.data.aiQuotaLeft,
                        quotaLow = result.data.aiQuotaLow,
                    )
                }
                is ApiResult.Failure -> Unit
            }
        }
    }

    /** 拍照后调用。图片先上传识别，不直接入库。 */
    fun analyze(imageFile: File) {
        _state.update { it.copy(phase = ScanPhase.ANALYZING, error = null) }

        viewModelScope.launch {
            when (val result = repository.scan(imageFile)) {
                is ApiResult.Success -> {
                    val scan = result.data
                    _state.update {
                        it.copy(
                            phase = ScanPhase.RESULT,
                            modelName = scan.model,
                            message = scan.message,
                            foods = scan.foods.map { food ->
                                EditableFood(
                                    name = food.name,
                                    quantityText = if (food.quantity % 1.0 == 0.0) {
                                        food.quantity.toInt().toString()
                                    } else {
                                        food.quantity.toString()
                                    },
                                    unit = food.unit,
                                    confidence = food.confidence,
                                    storageLocation = food.storageLocation,
                                    shelfLifeDays = food.shelfLifeDays,
                                )
                            },
                            error = if (scan.foods.isEmpty()) "没有识别到食材，换一张更清晰的照片试试" else null,
                        )
                    }
                    // 刚花掉一次额度，把「还剩 N 次」刷成新的
                    refreshStatus()
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(phase = ScanPhase.CAMERA, error = result.message) }
            }
            imageFile.delete()
        }
    }

    fun toggleInclude(index: Int) = updateFood(index) { it.copy(included = !it.included) }

    fun rename(index: Int, name: String) = updateFood(index) { it.copy(name = name) }

    fun setQuantity(index: Int, text: String) = updateFood(index) { it.copy(quantityText = text) }

    fun setUnit(index: Int, unit: String) = updateFood(index) { it.copy(unit = unit) }

    fun setLocation(index: Int, location: String) =
        updateFood(index) { it.copy(storageLocation = location) }

    fun setPurchaseDate(index: Int, date: LocalDate?) =
        updateFood(index) { it.copy(purchaseDate = date) }

    fun setExpiryDate(index: Int, date: LocalDate?) =
        updateFood(index) { it.copy(expiryDate = date) }

    /**
     * 把所有勾选项的购买日期一次性设成同一天。
     *
     * 为什么需要：一次扫描往往都是同一次采购回来的（今天买的菜、今天买的肉），
     * 挨个设一遍很烦。想单独调的再展开那一项改。
     */
    fun setPurchaseDateForAll(date: LocalDate?) {
        _state.update { current ->
            current.copy(
                foods = current.foods.map { food ->
                    if (food.included) food.copy(purchaseDate = date) else food
                }
            )
        }
    }

    fun removeFood(index: Int) {
        _state.update { current ->
            current.copy(foods = current.foods.filterIndexed { i, _ -> i != index })
        }
    }

    /** 手动补一条 AI 没认出来的 */
    fun addManualFood() {
        _state.update {
            it.copy(
                foods = it.foods + EditableFood(
                    name = "",
                    quantityText = "1",
                    unit = "个",
                    confidence = 1.0,
                    storageLocation = "冷藏",
                    shelfLifeDays = null,
                )
            )
        }
    }

    private fun updateFood(index: Int, transform: (EditableFood) -> EditableFood) {
        _state.update { current ->
            current.copy(
                foods = current.foods.mapIndexed { i, food ->
                    if (i == index) transform(food) else food
                }
            )
        }
    }

    fun backToCamera() {
        _state.update { it.copy(phase = ScanPhase.CAMERA, foods = emptyList(), error = null) }
    }

    /** 用户点「确认加入冰箱」——这一步才真正写库 */
    fun confirm(onDone: () -> Unit) {
        val included = _state.value.foods.filter { it.included }

        // 先拦日期填反的：后端也会校验，但让它提前在本地报错，
        // 用户能立刻看到是哪一项有问题，而不是笼统的「数据格式不正确」
        val badDate = included.firstOrNull { it.hasInvalidDates }
        if (badDate != null) {
            _state.update {
                it.copy(error = "「${badDate.name}」的过期日期早于购买日期，请改一下")
            }
            return
        }

        val selected = included.mapNotNull { it.toRecognizedFood() }
        if (selected.isEmpty()) {
            _state.update { it.copy(error = "至少勾选一样食材，并确认名称和数量都填对了") }
            return
        }
        _state.update { it.copy(confirming = true, error = null) }

        viewModelScope.launch {
            when (val result = repository.confirmScan(selected)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(confirming = false, confirmedCount = result.data.size) }
                    refreshBus.notify(DataRefreshBus.Topic.INVENTORY)
                    onDone()
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(confirming = false, error = result.message) }
            }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }
}
