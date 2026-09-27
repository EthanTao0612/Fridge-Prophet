package com.fridgeprophet.app.data.remote

import com.fridgeprophet.app.data.remote.dto.FamilyJoinIn
import com.fridgeprophet.app.data.remote.dto.FamilyOut
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.http.GET

/**
 * 家庭组接口的契约测试。
 *
 * ## 这个文件要守住什么
 *
 * 「我有没有加入家庭」这个状态，后端是靠 `GET /api/v1/family` 返回的
 * **`joined` 字段**表达的，不是靠「响应体是不是 null」。
 *
 * ## 为什么不能返回裸 null（重要）
 *
 * 最初的设计是「没加入家庭时返回 `null`」，接口签名写成
 * `suspend fun getFamily(): FamilyOut?`。看着很干净，但**根本跑不通**：
 *
 * Retrofit 从返回类型推出序列化器时，**Kotlin 的可空标记在 Java 的 `Type` 里丢掉了**，
 * 拿到的是非空的 `FamilyOut.serializer()`。给它喂一个 JSON 字面量 `null`，
 * kotlinx-serialization 直接抛 `JsonDecodingException`。
 *
 * 后果：**没加入家庭的用户打开家庭页，看到的是「出错了」，
 * 而不是本该显示的创建/加入入口** —— 新用户第一次点进来就是这个样子。
 *
 * 这个 bug 只在真机上才暴露，而当时手机连不上，是靠这个文件
 * （用 MockWebServer 跑真实 HTTP 栈）才抓出来的。
 * `control_bareNullBodyBreaksNullableReturnType` 把「裸 null 不行」
 * 单独固定成反例，免得下一个人又觉得「返回 null 更干净」。
 *
 * ⚠️ 方法名一律 ASCII。中文方法名会生成含全角字符的内嵌类文件名，
 * Windows 上加载不到，整个测试类报 ClassNotFoundException。
 */
class FamilyContractTest {

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

    private fun enqueueJson(body: String, code: Int = 200) {
        server.enqueue(
            MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody(body),
        )
    }

    /** 后端「没加入家庭」时的真实响应。 */
    private fun enqueueNotJoined() {
        enqueueJson(
            """
            {"joined":false,"id":0,"name":"","my_role":"","members":[],
             "member_count":0,"invite_code":null}
            """.trimIndent(),
        )
    }

    // ---------------------------------------------------------------
    // 核心契约
    // ---------------------------------------------------------------

    @Test
    fun getFamily_parsesNotJoinedResponse() {
        enqueueNotJoined()
        val family = runBlocking { api.getFamily() }
        assertFalse("没加入家庭时 joined 应为 false", family.joined)
        assertEquals(0, family.id)
        assertTrue("此时不该有成员", family.members.isEmpty())
    }

    @Test
    fun getFamily_parsesJoinedResponse() {
        enqueueJson(
            """
            {
              "joined": true,
              "id": 7,
              "name": "陶家",
              "my_role": "owner",
              "members": [
                {
                  "user_id": 16, "nickname": "ATao", "email": "a@b.com",
                  "avatar_url": null, "role": "owner",
                  "joined_at": "2026-09-27T10:00:00Z", "is_me": true
                }
              ],
              "member_count": 1,
              "invite_code": "K7M2QP"
            }
            """.trimIndent(),
        )

        val family = runBlocking { api.getFamily() }
        assertTrue("加入家庭后 joined 应为 true", family.joined)
        assertEquals(7, family.id)
        assertEquals("陶家", family.name)
        assertEquals("owner", family.myRole)
        assertEquals(1, family.memberCount)
        assertEquals("K7M2QP", family.inviteCode)
        assertEquals(1, family.members.size)
        assertTrue("自己是本人那一行", family.members[0].isMe)
    }

    /** 只读成员拿不到邀请码，后端给 null —— 字段类型必须可空，否则解析直接炸。 */
    @Test
    fun getFamily_viewerHasNullInviteCode() {
        enqueueJson(
            """
            {"joined":true,"id":1,"name":"x","my_role":"viewer","members":[],
             "member_count":1,"invite_code":null}
            """.trimIndent(),
        )
        val family = runBlocking { api.getFamily() }
        assertNull("只读成员的 invite_code 应为 null", family.inviteCode)
    }

    /**
     * 加入失败的 404 必须走异常通道。
     *
     * 如果哪天有人把返回类型改成可空、又吞掉异常，404 会悄悄变成 null，
     * 界面就会把「邀请码错了」显示成「你还没加入家庭」——
     * 用户会以为自己没点成功，反复重试。
     */
    @Test
    fun joinFamily_404BecomesExceptionNotSilentNull() {
        enqueueJson("""{"detail":"邀请码无效"}""", code = 404)
        try {
            runBlocking { api.joinFamily(FamilyJoinIn("ZZZZZZ")) }
            fail("404 应该抛 HttpException，而不是悄悄返回")
        } catch (e: Throwable) {
            assertTrue(
                "应抛 HttpException，实际是 ${e::class.simpleName}：${e.message}",
                e is HttpException,
            )
        }
    }

    // ---------------------------------------------------------------
    // 反例：证明上面那些测试**真的有验证能力**
    //
    // 如果哪天 Retrofit 或 kotlinx-serialization 改了行为，
    // 这个反例不再抛异常，说明契约变了，本文件需要重新审视 ——
    // 而不是默默变成一堆永远为真的空断言。
    // ---------------------------------------------------------------

    /** ❌ 错误写法：返回裸 null，客户端用可空类型接。 */
    private interface BareNullApi {
        @GET("api/v1/family")
        suspend fun getFamily(): FamilyOut?
    }

    @Test
    fun control_bareNullBodyBreaksNullableReturnType() {
        enqueueJson("null")
        val buggy = retrofit().create(BareNullApi::class.java)

        try {
            runBlocking { buggy.getFamily() }
            fail(
                "裸 null 配 FamilyOut? 本应抛序列化异常。它没抛 —— " +
                    "说明 Retrofit / kotlinx-serialization 的行为变了，" +
                    "本文件的结论需要重新验证。",
            )
        } catch (e: Throwable) {
            assertTrue(
                "应抛 SerializationException（JsonDecodingException），" +
                    "实际是 ${e::class.simpleName}：${e.message}",
                e is SerializationException,
            )
        }
    }
}
