package io.github.mangi.eta.agent.hermes

import org.json.JSONArray
import org.json.JSONObject

/** 服务端发起的一次请求（审批 / 澄清）。 */
internal data class HermesServerRequest(
    val id: Int,
    val method: String,
    val params: JSONObject,
)

/** 提示里的一个选项：给用户看的文字与回给服务端的取值可以不同。 */
internal data class HermesPromptChoice(val label: String, val value: String)

/**
 * 待用户回答的服务端请求。
 *
 * [requestId] 是 JSON-RPC 请求帧的 id，应答必须原样带回；[promptId] 是业务侧的提示 id，
 * `approval.respond` 用它定位。
 */
internal data class HermesPrompt(
    val requestId: Int,
    val method: String,
    val promptId: String,
    val title: String,
    val body: String,
    val choices: List<HermesPromptChoice>,
) {
    val isApproval: Boolean get() = method == APPROVAL_METHOD

    /** 没有可选项时由用户自己输入内容。 */
    val allowsFreeText: Boolean get() = choices.isEmpty()

    companion object {
        const val APPROVAL_METHOD = "approval"

        /** 服务端没给选项时的审批取值；与聊天命令 `/approve`、`/deny` 一致。 */
        val DEFAULT_APPROVAL_CHOICES = listOf(
            HermesPromptChoice(label = "approve", value = "approve"),
            HermesPromptChoice(label = "deny", value = HermesRpc.DENY_CHOICE),
        )
    }
}

internal object HermesPromptParser {

    private val BODY_KEYS = listOf("prompt", "question", "message", "text", "summary", "reason")

    /** 从请求参数里取标题、正文与选项；缺什么就留空，由界面兜底。 */
    fun parse(request: HermesServerRequest): HermesPrompt {
        val params = request.params
        val body = BODY_KEYS.firstNotNullOfOrNull { key ->
            params.optString(key).takeIf { it.isNotBlank() }
        }.orEmpty()
        val choices = parseChoices(params)
            .ifEmpty { if (request.method == HermesPrompt.APPROVAL_METHOD) HermesPrompt.DEFAULT_APPROVAL_CHOICES else emptyList() }
        return HermesPrompt(
            requestId = request.id,
            method = request.method,
            promptId = params.optString("prompt_id"),
            title = params.optString("title"),
            body = body,
            choices = choices,
        )
    }

    private fun parseChoices(params: JSONObject): List<HermesPromptChoice> {
        val array: JSONArray = params.optJSONArray("options")
            ?: params.optJSONArray("choices")
            ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            when (val item = array.opt(index)) {
                is String -> item.takeIf { it.isNotBlank() }?.let { HermesPromptChoice(it, it) }
                is JSONObject -> {
                    val value = item.optString("value").ifBlank { item.optString("id") }
                    val label = item.optString("label").ifBlank { item.optString("text") }
                    val resolvedValue = value.ifBlank { label }
                    resolvedValue.takeIf { it.isNotBlank() }?.let {
                        HermesPromptChoice(label = label.ifBlank { it }, value = it)
                    }
                }
                else -> null
            }
        }
    }
}