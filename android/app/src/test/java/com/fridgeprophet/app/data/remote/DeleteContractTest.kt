package com.fridgeprophet.app.data.remote

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.http.DELETE
import retrofit2.http.Path

/**
 * 删除接口的契约测试。
 *
 * ## 这个文件要守住什么
 *
 * 后端删除成功返回 **204 No Content**，响应体是空的。
 * Retrofit 的 suspend 适配器对空响应体做非空校验，不满足就抛
 *   `Response from FridgeApi.xxx was null but response body type was declared as non-null`
 * 而**这个异常会让所有删除功能一起失效** —— 用户看到的就是「点了删除没反应」。
 *
 * 这个 bug 已经复发两次。第一次的修复是「给返回类型加个问号」，
 * 而**那个修复是错的**：加了问号照样炸，只是错误被推迟到运行时，
 * 源码上看起来已经修好了。所以第二次复发时排查方向完全跑偏。
 *
 * 正确写法是**不写返回类型**（返回 `Unit`）。本文件用真实 HTTP 栈证明这一点，
 * 并且把「加问号也不行」单独做成一个反例测试固定下来 ——
 * 免得下一个人又「顺手加个问号」。
 *
 * ## ⚠️ 方法名必须用 ASCII
 *
 * 用中文方法名会生成 `DeleteContractTest$删除食材：204空响应体$1.class` 这类
 * 内嵌类文件名，其中含**全角冒号**，Windows 上加载不到，整个测试类报
 * `ClassNotFoundException`。所以：**方法名一律 ASCII，中文解释写在注释里。**
 */
class DeleteContractTest {

    private lateinit var server: MockWebServer
    private lateinit var api: FridgeApi

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private fun retrofit(): Retrofit = Retrofit.Builder()
        .baseUrl(server.url("/"))
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = retrofit().create(FridgeApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** 后端删除成功时的真实响应：204，且**没有响应体**。 */
    private fun enqueueNoContent() {
        server.enqueue(MockResponse().setResponseCode(204))
    }

    // ---------------------------------------------------------------
    // 真实接口：六个 DELETE 都必须能吞下 204 空响应体
    //
    // ⚠️ 加新 DELETE 接口时**必须**在这里补一行。
    // 漏掉的话不会有任何提示 —— 那个功能只在真机上「点了没反应」，
    // 而这正是这个文件存在的理由。
    //（`deleteFoodCategory` 就是补上来的：折叠箱的删除按钮接好之后
    //   才发现这个文件里没有它。）
    // ---------------------------------------------------------------

    @Test
    fun deleteInventory_acceptsEmptyBody() {
        enqueueNoContent()
        runBlocking { api.deleteInventory(1) }
    }

    @Test
    fun deleteRecipe_acceptsEmptyBody() {
        enqueueNoContent()
        runBlocking { api.deleteRecipe(1) }
    }

    @Test
    fun deleteFamily_acceptsEmptyBody() {
        enqueueNoContent()
        runBlocking { api.deleteFamily(1) }
    }

    @Test
    fun deleteShoppingItem_acceptsEmptyBody() {
        enqueueNoContent()
        runBlocking { api.deleteShoppingItem(1) }
    }

    @Test
    fun deleteShoppingList_acceptsEmptyBody() {
        enqueueNoContent()
        runBlocking { api.deleteShoppingList(1) }
    }

    /** 删除折叠箱。**不删食材**，只是解除归组（后端行为）。 */
    @Test
    fun deleteFoodCategory_acceptsEmptyBody() {
        enqueueNoContent()
        runBlocking { api.deleteFoodCategory(1) }
    }

    /**
     * 把食材移出折叠箱。
     *
     * 注意它**不是 204** —— 后端返回的是更新后的箱子（200 + 箱子 JSON），
     * 因为界面要立刻刷新「箱子里还剩什么」。
     * 所以这里断言的是「能正常解析出箱子」，而不是「能吞下空响应体」。
     */
    @Test
    fun removeFoodCategoryItem_returnsUpdatedBox() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"id":1,"name":"火锅材料","sort_order":0,"inventory_ids":[7,9]}"""
                )
        )
        val box = runBlocking { api.removeFoodCategoryItem(1, 8) }
        assertEquals(1, box.id)
        assertEquals(listOf(7, 9), box.inventoryIds)
    }

    @Test
    fun deleteSendsDeleteRequestWithId() {
        enqueueNoContent()
        runBlocking { api.deleteInventory(42) }

        val recorded = server.takeRequest()
        assertEquals("DELETE", recorded.method)
        assertTrue(
            "URL 里应带上 id，实际是 ${recorded.path}",
            recorded.path.orEmpty().endsWith("/42"),
        )
    }

    // ---------------------------------------------------------------
    // 反例：这两种写法都会炸，测试必须能抓住
    //
    // 反例的价值在于证明上面的测试**真的有验证能力**。
    // 如果哪天 Retrofit 改了行为，反例不再抛异常，说明契约变了，
    // 这个文件需要重新审视，而不是默默变成一堆永远为真的空断言。
    // ---------------------------------------------------------------

    /** ❌ 反例一：加问号。看着像修好了，运行时一模一样地炸。 */
    private interface NullableBodyApi {
        @DELETE("api/v1/inventory/{id}")
        suspend fun deleteInventory(@Path("id") id: Int): ResponseBody?
    }

    /** ❌ 反例二：不加问号。最直白的错误写法。 */
    private interface NonNullBodyApi {
        @DELETE("api/v1/inventory/{id}")
        suspend fun deleteInventory(@Path("id") id: Int): ResponseBody
    }

    private fun assertThrowsNullBodyError(block: suspend () -> Unit, label: String) {
        try {
            runBlocking { block() }
            fail("$label 本应在 204 时抛异常。它没抛，说明这个测试文件失去了验证能力")
        } catch (e: Throwable) {
            val message = e.message.orEmpty()
            assertTrue(
                "$label 抛的异常不对，实际是：$message",
                message.contains("was null but response body type was declared as non-null"),
            )
        }
    }

    @Test
    fun control_nullableResponseBodyMustAlsoFail() {
        enqueueNoContent()
        val buggy = retrofit().create(NullableBodyApi::class.java)
        assertThrowsNullBodyError(
            { buggy.deleteInventory(1) },
            "ResponseBody? 这种写法",
        )
    }

    @Test
    fun control_nonNullResponseBodyMustFail() {
        enqueueNoContent()
        val buggy = retrofit().create(NonNullBodyApi::class.java)
        assertThrowsNullBodyError(
            { buggy.deleteInventory(1) },
            "ResponseBody 这种写法",
        )
    }
}
