package com.fridgeprophet.app.data.repository

import com.fridgeprophet.app.core.ApiResult
import com.fridgeprophet.app.core.safeApiCall
import com.fridgeprophet.app.data.remote.FridgeApi
import com.fridgeprophet.app.data.remote.dto.FamilyCreateIn
import com.fridgeprophet.app.data.remote.dto.FamilyInviteCodeIn
import com.fridgeprophet.app.data.remote.dto.FamilyJoinIn
import com.fridgeprophet.app.data.remote.dto.FamilyLeaveOut
import com.fridgeprophet.app.data.remote.dto.FamilyOut
import com.fridgeprophet.app.data.remote.dto.FamilyRoleUpdateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 家庭组：邀请码加入、成员管理。
 *
 * ⚠️ **共享数据的读取不在这里**。冰箱 / 菜谱 / 采购的接口本来就会返回
 * 全家可见的数据（后端按 `family_service.visible_user_ids()` 过滤），
 * 客户端不需要为「看家人的东西」做任何特殊处理。
 * 这个 Repository 只管「谁在家庭里、什么身份」。
 */
@Singleton
class FamilyRepository @Inject constructor(private val api: FridgeApi) {

    /**
     * 我的家庭。没加入时返回 `null`。
     *
     * 后端其实永远返回对象（`joined=false` 表示没加入），
     * 这里把它翻译成 `null` —— 上层用 `FamilyOut?` 表达「有没有家庭」更自然，
     * 也不用每处都记得去看 `joined`。
     */
    suspend fun my(): ApiResult<FamilyOut?> =
        safeApiCall { api.getFamily().takeIf { it.joined } }

    suspend fun create(name: String): ApiResult<FamilyOut> =
        safeApiCall { api.createFamily(FamilyCreateIn(name)) }

    /** 用邀请码加入。大小写和空格由后端容错，这里不用预处理。 */
    suspend fun join(code: String): ApiResult<FamilyOut> =
        safeApiCall { api.joinFamily(FamilyJoinIn(code)) }

    /** 换邀请码，顺便定新成员的身份（member / viewer）。 */
    suspend fun regenerateCode(role: String): ApiResult<FamilyOut> =
        safeApiCall { api.regenerateInviteCode(FamilyInviteCodeIn(role)) }

    suspend fun setRole(userId: Int, role: String): ApiResult<FamilyOut> =
        safeApiCall { api.updateFamilyMemberRole(userId, FamilyRoleUpdateIn(role)) }

    suspend fun removeMember(userId: Int): ApiResult<FamilyOut> =
        safeApiCall { api.removeFamilyMember(userId) }

    /** 退出家庭。家庭主调用会**解散**整个家庭，返回里 dissolved=true。 */
    suspend fun leave(): ApiResult<FamilyLeaveOut> = safeApiCall { api.leaveFamily() }
}
