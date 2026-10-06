package com.fridgeprophet.app.data.repository

import com.fridgeprophet.app.core.ApiResult
import com.fridgeprophet.app.core.safeApiCall
import com.fridgeprophet.app.data.remote.FridgeApi
import com.fridgeprophet.app.data.remote.dto.AiStatus
import com.fridgeprophet.app.data.remote.dto.ExpiringItem
import com.fridgeprophet.app.data.remote.dto.FoodCategoryCreate
import com.fridgeprophet.app.data.remote.dto.FoodCategoryItems
import com.fridgeprophet.app.data.remote.dto.FoodCategoryOut
import com.fridgeprophet.app.data.remote.dto.FoodCategoryUpdate
import com.fridgeprophet.app.data.remote.dto.InventoryCreate
import com.fridgeprophet.app.data.remote.dto.InventoryOut
import com.fridgeprophet.app.data.remote.dto.InventoryStats
import com.fridgeprophet.app.data.remote.dto.InventoryUpdate
import com.fridgeprophet.app.data.remote.dto.RecognizedFood
import com.fridgeprophet.app.data.remote.dto.ScanConfirmRequest
import com.fridgeprophet.app.data.remote.dto.ScanResult
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InventoryRepository @Inject constructor(private val api: FridgeApi) {

    suspend fun list(storageLocation: String? = null, keyword: String? = null): ApiResult<List<InventoryOut>> =
        safeApiCall { api.listInventory(storageLocation = storageLocation, keyword = keyword) }

    suspend fun expiring(withinDays: Int = 3): ApiResult<List<ExpiringItem>> =
        safeApiCall { api.listExpiring(withinDays) }

    suspend fun stats(): ApiResult<InventoryStats> = safeApiCall { api.inventoryStats() }

    suspend fun add(body: InventoryCreate): ApiResult<InventoryOut> =
        safeApiCall { api.createInventory(body) }

    suspend fun update(id: Int, body: InventoryUpdate): ApiResult<InventoryOut> =
        safeApiCall { api.updateInventory(id, body) }

    suspend fun remove(id: Int): ApiResult<Unit> = safeApiCall { api.deleteInventory(id); Unit }

    /** 上传照片做 AI 识别。注意：只返回候选，不入库。 */
    suspend fun scan(imageFile: File): ApiResult<ScanResult> = safeApiCall {
        val body = imageFile.asRequestBody("image/jpeg".toMediaType())
        val part = MultipartBody.Part.createFormData("file", imageFile.name, body)
        api.scanFridge(part)
    }

    /** 用户确认后才真正写入冰箱 */
    suspend fun confirmScan(foods: List<RecognizedFood>): ApiResult<List<InventoryOut>> =
        safeApiCall { api.confirmScan(ScanConfirmRequest(foods)) }

    suspend fun aiStatus(): ApiResult<AiStatus> = safeApiCall { api.aiStatus() }

    // ---------- 自定义折叠箱 ----------

    suspend fun categories(): ApiResult<List<FoodCategoryOut>> =
        safeApiCall { api.foodCategories() }

    suspend fun createCategory(name: String, inventoryIds: List<Int> = emptyList()):
        ApiResult<FoodCategoryOut> =
        safeApiCall { api.createFoodCategory(FoodCategoryCreate(name, inventoryIds)) }

    suspend fun renameCategory(id: Int, name: String): ApiResult<FoodCategoryOut> =
        safeApiCall { api.updateFoodCategory(id, FoodCategoryUpdate(name = name)) }

    suspend fun deleteCategory(id: Int): ApiResult<Unit> =
        safeApiCall { api.deleteFoodCategory(id); Unit }

    suspend fun addToCategory(id: Int, inventoryIds: List<Int>): ApiResult<FoodCategoryOut> =
        safeApiCall { api.addFoodCategoryItems(id, FoodCategoryItems(inventoryIds)) }

    /**
     * 把食材移出折叠箱。
     *
     * ⚠️ **不删食材** —— 只是解除归组，食材留在冰箱里。
     */
    suspend fun removeFromCategory(id: Int, inventoryId: Int): ApiResult<FoodCategoryOut> =
        safeApiCall { api.removeFoodCategoryItem(id, inventoryId) }
}
