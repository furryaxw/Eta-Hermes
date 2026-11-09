package io.github.mangi.eta.agent.hermes

import io.github.mangi.eta.core.AndroidAgentLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 配对/登录换回来的设备凭据。 */
internal data class HermesAccountSession(
    val deviceId: String,
    val token: String,
    val label: String,
)

/**
 * Hermes 设备登录。
 *
 * 两种方式都只向 `/eta/pair` 或 `/eta/login` 发一次请求，换回一个长期设备令牌；之后所有
 * 流量（对话 + 工具）都用这个令牌走同一条 WebSocket。手机端不保存密码，也不存在 API Key。
 */
internal object HermesAccountApi {

    /** 配对码登录：服务器 `hermes eta pair` 打印的 8 位码，单次有效。 */
    fun pair(
        baseUrl: String,
        code: String,
        deviceName: String,
        trustSelfSigned: Boolean = false,
    ): HermesAccountSession = call(
        baseUrl = baseUrl,
        path = "pair",
        body = JSONObject()
            .put("code", normalizeCode(code))
            .put("device_name", deviceName),
        trustSelfSigned = trustSelfSigned,
        failureLabel = "配对码",
    )

    /** 密码登录：复用 Hermes 仪表盘的 `dashboard.basic_auth` 账号。 */
    fun login(
        baseUrl: String,
        username: String,
        password: String,
        deviceName: String,
        trustSelfSigned: Boolean = false,
    ): HermesAccountSession {
        require(username.isNotBlank()) { "请填写用户名" }
        require(password.isNotBlank()) { "请填写密码" }
        return call(
            baseUrl = baseUrl,
            path = "login",
            body = JSONObject()
                .put("username", username.trim())
                .put("password", password)
                .put("device_name", deviceName),
            trustSelfSigned = trustSelfSigned,
            failureLabel = "账号或密码",
        )
    }

    private fun call(
        baseUrl: String,
        path: String,
        body: JSONObject,
        trustSelfSigned: Boolean,
        failureLabel: String,
    ): HermesAccountSession {
        val base = HermesSettings.normalizeBaseUrl(baseUrl)
        require(base.isNotBlank()) { "缺少服务地址" }
        val serialized = body.toString()
        val request = Request.Builder()
            .url("$base/$path")
            .post(serialized.toRequestBody(JSON))
            .build()
        val client = HermesTransport.clientBuilder(trustSelfSigned)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val raw = runCatching { response.body.string() }.getOrDefault("")
                AndroidAgentLogger.warn("Hermes $path -> HTTP ${response.code}")
                if (response.isSuccessful) {
                    val json = runCatching { JSONObject(raw) }.getOrNull()
                        ?: throw IOException("登录响应无法解析")
                    val token = json.optString("token")
                    if (token.isBlank()) throw IOException("登录响应里没有设备令牌")
                    val deviceId = json.optString("device_id")
                    return HermesAccountSession(
                        deviceId = deviceId,
                        token = token,
                        label = body.optString("device_name").ifBlank { deviceId },
                    )
                }
                throw IOException(errorText(response.code, raw, failureLabel))
            }
        } catch (throwable: Throwable) {
            AndroidAgentLogger.error(
                "Hermes $path failed: ${throwable.javaClass.simpleName}: ${throwable.message}",
                throwable,
            )
            throw throwable
        }
    }

    /** 配对码允许带分隔符（ABCD-1234）和小写。 */
    private fun normalizeCode(code: String): String =
        code.trim().replace("-", "").replace(" ", "").uppercase()

    private fun errorText(status: Int, raw: String, label: String): String {
        val detail = runCatching {
            val json = JSONObject(raw)
            json.optString("message").ifBlank { json.optString("error") }
        }.getOrDefault("")
        return when (status) {
            401 -> if (detail.isNotBlank()) detail else "$label 不正确"
            404 -> detail.ifBlank { "服务端没有开启密码登录，请改用配对码" }
            429 -> "尝试太频繁，稍后再试"
            else -> "HTTP $status ${detail.ifBlank { raw.replace(Regex("\\s+"), " ").take(120) }}".trim()
        }
    }

    private val JSON = "application/json; charset=utf-8".toMediaType()
}
