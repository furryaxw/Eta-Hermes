package io.github.mangi.eta.agent.hermes

import android.content.Context
import android.content.SharedPreferences

/** Hermes 后端的首次登录方式。 */
internal enum class HermesAuthMethod(val persistedValue: String) {
    /** 服务器 `hermes eta pair` 打印的一次性配对码。 */
    PAIRING("pairing"),

    /** 复用 Hermes 仪表盘账号密码（`dashboard.basic_auth`）。 */
    PASSWORD("password"),
    ;

    companion object {
        fun fromPersisted(value: String?): HermesAuthMethod = when (value) {
            PASSWORD.persistedValue, "api_key" -> PASSWORD
            else -> PAIRING
        }
    }
}

/**
 * Hermes 后端配置。
 *
 * 全局开关：开启后 Agent Runtime 不再运行本地 Agent Loop，模型、记忆、Skills、子代理与
 * 上下文压缩全部由 Hermes 承担；Eta 只保留前端与设备工具。
 *
 * 登录一次即换到设备令牌，之后对话和手机工具共用同一条 WebSocket。非机密项存普通偏好，
 * 设备令牌走 [HermesSecretStore]（Android Keystore）。
 */
internal object HermesSettings {

    private const val PREFS_NAME = "eta_hermes_prefs"

    const val KEY_ENABLED = "hermes_enabled"
    const val KEY_BASE_URL = "hermes_base_url"
    const val KEY_DEVICE_ID = "hermes_device_id"
    const val KEY_DEVICE_NAME = "hermes_device_name"
    const val KEY_TOOL_GROUPS = "hermes_tool_groups"
    const val KEY_AUTH_METHOD = "hermes_auth_method"
    const val KEY_ACCOUNT_USERNAME = "hermes_account_username"
    const val KEY_TRUST_SELF_SIGNED = "hermes_trust_self_signed"
    const val KEY_RPC_SESSIONS = "hermes_rpc_sessions"

    private const val SECRET_DEVICE_TOKEN = "hermes_device_token"

    const val DEFAULT_TOOL_GROUPS = "device,phone,gui,personal,shell"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** 服务器地址，含 `/eta` 前缀，例如 `https://raw.example.com:9119/eta`。 */
    fun baseUrl(context: Context): String =
        prefs(context).getString(KEY_BASE_URL, "").orEmpty().trim()

    fun setBaseUrl(context: Context, value: String) {
        prefs(context).edit().putString(KEY_BASE_URL, normalizeBaseUrl(value)).apply()
    }

    fun deviceId(context: Context): String =
        prefs(context).getString(KEY_DEVICE_ID, "").orEmpty().trim()

    fun setDeviceId(context: Context, value: String) {
        prefs(context).edit().putString(KEY_DEVICE_ID, value.trim()).apply()
    }

    /** 本机在 Hermes 侧显示的名字（默认机型）。 */
    fun deviceName(context: Context): String =
        prefs(context).getString(KEY_DEVICE_NAME, "").orEmpty().trim()
            .ifBlank { android.os.Build.MODEL.orEmpty().ifBlank { "Eta" } }

    fun setDeviceName(context: Context, value: String) {
        prefs(context).edit().putString(KEY_DEVICE_NAME, value.trim()).apply()
    }

    fun toolGroups(context: Context): List<String> =
        prefs(context).getString(KEY_TOOL_GROUPS, DEFAULT_TOOL_GROUPS).orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.all { ch -> ch.isLetterOrDigit() || ch == '_' || ch == '-' } }
            .distinct()

    fun setToolGroups(context: Context, groups: List<String>) {
        prefs(context).edit().putString(KEY_TOOL_GROUPS, groups.joinToString(",")).apply()
    }

    fun authMethod(context: Context): HermesAuthMethod =
        HermesAuthMethod.fromPersisted(prefs(context).getString(KEY_AUTH_METHOD, null))

    fun setAuthMethod(context: Context, method: HermesAuthMethod) {
        prefs(context).edit().putString(KEY_AUTH_METHOD, method.persistedValue).apply()
    }

    fun deviceToken(context: Context): String =
        HermesSecretStore(context).read(SECRET_DEVICE_TOKEN).orEmpty()

    fun setDeviceToken(context: Context, value: String) =
        HermesSecretStore(context).write(SECRET_DEVICE_TOKEN, value)

    fun clearDeviceToken(context: Context) = HermesSecretStore(context).clear(SECRET_DEVICE_TOKEN)

    fun accountUsername(context: Context): String =
        prefs(context).getString(KEY_ACCOUNT_USERNAME, "").orEmpty().trim()

    /** Eta 会话 → Hermes 会话 id 的映射（按 Eta 侧会话 id 存）。 */
    fun rpcSessionId(context: Context, etaSessionId: String): String =
        prefs(context).getString("$KEY_RPC_SESSIONS.$etaSessionId", "").orEmpty().trim()

    fun setRpcSessionId(context: Context, etaSessionId: String, hermesSessionId: String) {
        prefs(context).edit().putString("$KEY_RPC_SESSIONS.$etaSessionId", hermesSessionId.trim()).apply()
    }

    fun clearRpcSessionId(context: Context, etaSessionId: String) {
        prefs(context).edit().remove("$KEY_RPC_SESSIONS.$etaSessionId").apply()
    }

    fun setAccountUsername(context: Context, value: String) {
        prefs(context).edit().putString(KEY_ACCOUNT_USERNAME, value.trim()).apply()
    }

    /** 已配对：设备令牌存在。 */
    fun isSignedIn(context: Context): Boolean = deviceToken(context).isNotBlank()

    /** 自签名或私有 CA 证书的 Hermes 实例需要显式放开证书链校验。 */
    fun isTrustingSelfSigned(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TRUST_SELF_SIGNED, false)

    fun setTrustSelfSigned(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_TRUST_SELF_SIGNED, value).apply()
    }

    /** 当前登录持有的设备令牌。 */
    fun authToken(context: Context): String = deviceToken(context)

    /** 地址与令牌齐备才允许发消息。 */
    fun isChatConfigured(context: Context): Boolean =
        baseUrl(context).isNotBlank() && deviceToken(context).isNotBlank()

    /** 配置了但没有协议前缀时补 https；本机调试允许显式 http。 */
    fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }
}
