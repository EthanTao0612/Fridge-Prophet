package com.fridgeprophet.app.data.repository

import com.fridgeprophet.app.core.ApiResult
import com.fridgeprophet.app.core.TokenStore
import com.fridgeprophet.app.core.safeApiCall
import com.fridgeprophet.app.data.remote.FridgeApi
import com.fridgeprophet.app.data.remote.dto.LoginRequest
import com.fridgeprophet.app.data.remote.dto.RegisterRequest
import com.fridgeprophet.app.data.remote.dto.SendCodeRequest
import com.fridgeprophet.app.data.remote.dto.SendCodeResponse
import com.fridgeprophet.app.data.remote.dto.TokenResponse
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val api: FridgeApi,
    private val tokenStore: TokenStore,
) {

    /**
     * 请求发送注册验证码。
     *
     * 返回的 `cooldownSeconds` 用来做按钮倒计时 —— **用服务端给的值**，
     * 不要在客户端写死 60。两边不一致的话，客户端倒计时结束了但服务端
     * 还在冷却，用户点了会莫名其妙收到「发送太频繁」。
     */
    suspend fun sendCode(email: String): ApiResult<SendCodeResponse> =
        safeApiCall { api.sendCode(SendCodeRequest(email)) }

    suspend fun register(
        email: String,
        password: String,
        nickname: String,
        code: String,
    ): ApiResult<TokenResponse> {
        val result = safeApiCall { api.register(RegisterRequest(email, password, nickname, code)) }
        if (result is ApiResult.Success) {
            tokenStore.save(
                token = result.data.accessToken,
                email = result.data.user.email,
                nickname = result.data.user.nickname,
                onboarded = result.data.user.onboarded,
            )
        }
        return result
    }

    suspend fun login(email: String, password: String): ApiResult<TokenResponse> {
        val result = safeApiCall { api.login(LoginRequest(email, password)) }
        if (result is ApiResult.Success) {
            tokenStore.save(
                token = result.data.accessToken,
                email = result.data.user.email,
                nickname = result.data.user.nickname,
                onboarded = result.data.user.onboarded,
            )
        }
        return result
    }

    suspend fun logout() {
        tokenStore.clear()
    }

    /** 启动时判断该去登录页还是首页 */
    suspend fun hasToken(): Boolean = !tokenStore.tokenBlocking().isNullOrBlank()
}
