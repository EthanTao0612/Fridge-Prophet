package com.fridgeprophet.app.data.remote

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

/**
 * `/vision/status` 的契约测试。
 *
 * ## 为什么单独为这个接口写一个
 *
 * 2026-10-06 给这个接口加了两个字段（`ai_quota_left` / `ai_quota_low`），
 * 用来在扫描页显示「今日还剩 N 次 AI 识别」。
 *
 * 加字段的风险**不是崩溃** —— 客户端配了 `ignoreUnknownKeys`，
 * 后端加字段不会让解析失败。真正的风险是**名字对不上**：
 * 后端写 `ai_quota_left`、客户端写成 `aiQuotaLeft` 而漏了 `@SerialName`，
 * 解析出来就是**默认值** —— 不报错，但功能静默失效。
 *
 * 这种问题在真机上表现为「额度提示永远不显示」，很难联想到是字段名的问题。
 * 所以这里拿**照抄后端字段名的 JSON** 跑一遍真实 HTTP 栈。
 *
 * ⚠️ 方法名必须 ASCII —— 中文方法名会生成含全角字符的内嵌类文件名，
 * Windows 上加载不到，整个测试类报 ClassNotFoundException。
 */
class AiStatusContractTest {

    private lateinit var server: MockWebServer
    private lateinit var api: FridgeApi

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(FridgeApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueue(body: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body)
        )
    }

    /** 后端当前的真实响应（字段名照抄 `api/v1/vision.py` 的 `ai_status`）。 */
    @Test
    fun status_parsesQuotaFields() {
        enqueue(
            """
            {"ai_enabled":true,"vision_model":"qwen-vl-max","text_model":"qwen-plus",
             "storage":"supabase","ai_quota_left":27,"ai_quota_low":false,
             "note":"已接入通义千问"}
            """.trimIndent()
        )

        val s = runBlocking { api.aiStatus() }

        assertTrue("aiEnabled 没解析出来", s.aiEnabled)
        assertEquals("qwen-vl-max", s.visionModel)
        assertEquals("supabase", s.storage)
        assertEquals("额度字段没解析出来（检查 @SerialName）", 27, s.aiQuotaLeft)
        assertFalse(s.aiQuotaLow)
    }

    @Test
    fun status_parsesLowQuotaFlag() {
        enqueue(
            """
            {"ai_enabled":true,"ai_quota_left":2,"ai_quota_low":true}
            """.trimIndent()
        )

        val s = runBlocking { api.aiStatus() }
        assertEquals(2, s.aiQuotaLeft)
        assertTrue("ai_quota_low 没解析出来", s.aiQuotaLow)
    }

    /**
     * ⚠️ 老版本后端（没有这两个字段）时，`aiQuotaLeft` 必须是 **-1**。
     *
     * 为什么这条值得单独测：界面把 `0` 当成「额度用完了」，
     * 会把拍照按钮灰掉。如果这里的默认值不小心写成 0，
     * **后端一升级失败、或者客户端连的是旧服务**，
     * 用户就会看到「今天不能用了」—— 把「不知道」显示成了「用完了」。
     */
    @Test
    fun status_missingQuotaFieldsFallBackToUnknownNotZero() {
        enqueue("""{"ai_enabled":true,"note":"老版本后端"}""")

        val s = runBlocking { api.aiStatus() }
        assertEquals("缺失时必须是 -1（未知），不能是 0（用完）", -1, s.aiQuotaLeft)
        assertFalse(s.aiQuotaLow)
    }

    @Test
    fun status_zeroQuotaParsesAsZero() {
        enqueue("""{"ai_enabled":true,"ai_quota_left":0,"ai_quota_low":true}""")

        val s = runBlocking { api.aiStatus() }
        assertEquals("真的用完时必须解析成 0", 0, s.aiQuotaLeft)
    }

    @Test
    fun status_hitsTheRightPath() {
        enqueue("""{"ai_enabled":true}""")
        runBlocking { api.aiStatus() }

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/v1/vision/status", recorded.path)
    }
}
