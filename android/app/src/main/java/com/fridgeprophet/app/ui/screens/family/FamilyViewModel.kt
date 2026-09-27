package com.fridgeprophet.app.ui.screens.family

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fridgeprophet.app.core.ApiResult
import com.fridgeprophet.app.core.DataRefreshBus
import com.fridgeprophet.app.data.remote.dto.FamilyOut
import com.fridgeprophet.app.data.repository.FamilyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FamilyUiState(
    val loading: Boolean = true,
    /** null = 还没加入任何家庭。这是「未加入」而不是「空家庭」，两者渲染完全不同。 */
    val family: FamilyOut? = null,
    val error: String? = null,
    val message: String? = null,
    /** 有请求在飞 —— 按钮要禁用，免得连点建出两个家庭 */
    val busy: Boolean = false,
    /** 加入家庭时输入框里的邀请码 */
    val joinCode: String = "",
)

@HiltViewModel
class FamilyViewModel @Inject constructor(
    private val repository: FamilyRepository,
    private val refreshBus: DataRefreshBus,
) : ViewModel() {

    private val _state = MutableStateFlow(FamilyUiState())
    val state: StateFlow<FamilyUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.my()) {
                is ApiResult.Success ->
                    _state.update { it.copy(loading = false, family = result.data) }
                is ApiResult.Failure ->
                    _state.update { it.copy(loading = false, error = result.message) }
            }
        }
    }

    /** 邀请码统一转大写：后端会容错，但输入框里看着一致更省心。 */
    fun setJoinCode(text: String) =
        _state.update { it.copy(joinCode = text.uppercase().take(12)) }

    fun createFamily(name: String) {
        submit { repository.create(name.trim().ifBlank { "我的家" }) }
    }

    fun join() {
        val code = _state.value.joinCode.trim()
        if (code.isEmpty()) {
            _state.update { it.copy(error = "先输入邀请码") }
            return
        }
        submit { repository.join(code) }
    }

    /** 换邀请码，顺便定新成员的身份。只有家庭主能调。 */
    fun regenerateCode(role: String) {
        submit { repository.regenerateCode(role) }
    }

    fun setRole(userId: Int, role: String) {
        submit { repository.setRole(userId, role) }
    }

    fun removeMember(userId: Int) {
        submit { repository.removeMember(userId) }
    }

    /**
     * 退出家庭。
     *
     * ⚠️ 家庭主退出 = **解散整个家庭**。调用方必须先弹确认框把这件事说清楚 ——
     * 不能让人点一下就把全家人的共享关系解除了。
     */
    fun leave() {
        _state.update { it.copy(busy = true, error = null, message = null) }
        viewModelScope.launch {
            when (val result = repository.leave()) {
                is ApiResult.Success -> {
                    _state.update {
                        it.copy(
                            busy = false,
                            family = null,
                            joinCode = "",
                            message = result.data.message,
                        )
                    }
                    notifySharedDataChanged()
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(busy = false, error = result.message) }
            }
        }
    }

    /** 建家庭 / 加入 / 换码 / 改身份 / 移出：都是「提交 → 拿回新家庭 → 刷新」。 */
    private fun submit(block: suspend () -> ApiResult<FamilyOut>) {
        _state.update { it.copy(busy = true, error = null, message = null) }
        viewModelScope.launch {
            when (val result = block()) {
                is ApiResult.Success -> {
                    _state.update {
                        it.copy(busy = false, family = result.data, joinCode = "")
                    }
                    notifySharedDataChanged()
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(busy = false, error = result.message) }
            }
        }
    }

    /**
     * 家庭成员一变，冰箱 / 菜谱 / 采购的可见范围也跟着变。
     *
     * 必须广播：不广播的话，刚加入家庭的人会看到「冰箱还是空的」，
     * 以为共享没生效 —— 其实只是没重新拉。
     */
    private fun notifySharedDataChanged() {
        refreshBus.notify(DataRefreshBus.Topic.ALL)
    }

    fun clearMessages() = _state.update { it.copy(error = null, message = null) }
}
