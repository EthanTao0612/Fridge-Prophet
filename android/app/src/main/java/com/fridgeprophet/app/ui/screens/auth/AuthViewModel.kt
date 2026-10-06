package com.fridgeprophet.app.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fridgeprophet.app.core.ApiResult
import com.fridgeprophet.app.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthUiState(
    val isRegisterMode: Boolean = false,
    val email: String = "",
    val password: String = "",
    val nickname: String = "",
    val code: String = "",
    val loading: Boolean = false,
    /** 发验证码的请求进行中（和登录/注册的 loading 分开，按钮互不影响） */
    val sendingCode: Boolean = false,
    /** 倒计时剩余秒数。> 0 时「获取验证码」按钮禁用并显示它 */
    val cooldown: Int = 0,
    val error: String? = null,
    /** 发码成功的提示（绿色显示，和 error 分开） */
    val notice: String? = null,
) {
    val canSubmit: Boolean
        get() = email.contains("@") &&
            password.length >= 6 &&
            (!isRegisterMode || code.length >= 4) &&
            !loading

    /** 邮箱看起来像个地址时才让点「获取验证码」，省得用户点了才发现填错 */
    val canSendCode: Boolean
        get() = email.contains("@") && email.contains(".") && cooldown == 0 && !sendingCode
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    /** 倒计时任务。切换模式或重新发码时要先取消，否则会有两个计时器同时倒数 */
    private var cooldownJob: Job? = null

    fun toggleMode() {
        // 切换模式时清掉倒计时 —— 登录不需要验证码，
        // 留着的话下次切回注册会看到一个莫名奇妙的倒计时
        cooldownJob?.cancel()
        _state.update {
            it.copy(isRegisterMode = !it.isRegisterMode, error = null, notice = null, cooldown = 0)
        }
    }

    fun onEmailChange(value: String) =
        _state.update { it.copy(email = value.trim(), error = null) }

    fun onPasswordChange(value: String) =
        _state.update { it.copy(password = value, error = null) }

    fun onNicknameChange(value: String) =
        _state.update { it.copy(nickname = value, error = null) }

    fun onCodeChange(value: String) =
        // 只留数字：用户从邮件里复制容易带上空格或别的字符
        _state.update { it.copy(code = value.filter { c -> c.isDigit() }.take(6), error = null) }

    fun sendCode() {
        val email = _state.value.email
        if (!_state.value.canSendCode) return

        _state.update { it.copy(sendingCode = true, error = null, notice = null) }
        viewModelScope.launch {
            when (val result = authRepository.sendCode(email)) {
                is ApiResult.Success -> {
                    _state.update {
                        it.copy(
                            sendingCode = false,
                            notice = result.data.message.ifBlank { "验证码已发送，请查收邮件" },
                        )
                    }
                    startCooldown(result.data.cooldownSeconds)
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(sendingCode = false, error = result.message) }
            }
        }
    }

    /**
     * 开始倒计时。
     *
     * 秒数**用服务端返回的**，不写死 60 —— 两边不一致的话，
     * 客户端倒计时结束了但服务端还在冷却，用户点了会莫名其妙收到「发送太频繁」。
     */
    private fun startCooldown(seconds: Int) {
        cooldownJob?.cancel()
        cooldownJob = viewModelScope.launch {
            var left = seconds.coerceAtLeast(1)
            while (left > 0) {
                _state.update { it.copy(cooldown = left) }
                delay(1000)
                left -= 1
            }
            _state.update { it.copy(cooldown = 0) }
        }
    }

    fun submit(onSuccess: (needsOnboarding: Boolean) -> Unit) {
        val current = _state.value
        if (!current.canSubmit) {
            val hint = when {
                !current.email.contains("@") -> "请填写邮箱"
                current.password.length < 6 -> "密码至少 6 位"
                current.isRegisterMode && current.code.length < 4 -> "请填写邮件里的验证码"
                else -> "请检查填写的内容"
            }
            _state.update { it.copy(error = hint) }
            return
        }
        _state.update { it.copy(loading = true, error = null, notice = null) }

        viewModelScope.launch {
            val result = if (current.isRegisterMode) {
                authRepository.register(
                    email = current.email,
                    password = current.password,
                    nickname = current.nickname,
                    code = current.code,
                )
            } else {
                authRepository.login(current.email, current.password)
            }

            when (result) {
                is ApiResult.Success -> {
                    _state.update { it.copy(loading = false) }
                    onSuccess(!result.data.user.onboarded)
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(loading = false, error = result.message) }
            }
        }
    }

    override fun onCleared() {
        cooldownJob?.cancel()
        super.onCleared()
    }
}
