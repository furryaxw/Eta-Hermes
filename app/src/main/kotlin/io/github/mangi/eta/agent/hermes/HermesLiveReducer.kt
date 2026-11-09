package io.github.mangi.eta.agent.hermes

import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import org.json.JSONObject

/**
 * 编辑/重试期间的实时消息投影。
 *
 * 这两条路径绕开了 Eta 的 run/事件管线（那边会按消息 id 去改写本地历史），
 * 所以需要一个小小的 reducer 把 Hermes 的事件流翻成消息列表，供界面边说边显示。
 */
internal class HermesLiveReducer {

    private val done = mutableListOf<AgentChatMessageUi>()
    private var thinkingId: String? = null
    private var thinkingText = StringBuilder()
    private var textId: String? = null
    private var textText = StringBuilder()
    private var counter = 0

    @Synchronized
    fun on(kind: String, data: JSONObject) {
        when (kind) {
            // 思考：一段连续推理归一个块，遇到工具或正文就收尾。
            "tool.progress" -> {
                val delta = data.optString("delta")
                if (delta.isEmpty()) return
                closeText()
                if (thinkingId == null) {
                    thinkingId = "live-think-${counter++}"
                    thinkingText = StringBuilder()
                }
                thinkingText.append(delta)
            }

            "tool.started" -> {
                closeThinking()
                closeText()
                val name = data.optString("tool_name")
                if (name.isBlank()) return
                val args = data.optJSONObject("args")?.toString()?.takeIf { it != "{}" }.orEmpty()
                done += ToolActivityMessageUi(
                    id = data.optString("tool_id").ifBlank { "live-tool-${counter++}" },
                    toolName = name,
                    status = ToolActivityStatusUi.Running,
                    argumentsSummary = args,
                )
            }

            "tool.completed" -> {
                val id = data.optString("tool_id")
                val index = if (id.isBlank()) {
                    done.indexOfLast { it is ToolActivityMessageUi }
                } else {
                    done.indexOfLast { it is ToolActivityMessageUi && it.id == id }
                }
                if (index >= 0) {
                    val card = done[index] as ToolActivityMessageUi
                    done[index] = card.copy(
                        status = ToolActivityStatusUi.Success,
                        resultSummary = data.optString("preview").takeIf { it.isNotBlank() },
                    )
                }
            }

            "assistant.delta" -> {
                closeThinking()
                if (textId == null) {
                    textId = "live-text-${counter++}"
                    textText = StringBuilder()
                }
                textText.append(data.optString("delta"))
            }
        }
    }

    /** 当前完整消息列表（含仍在流式的那一段）。 */
    @Synchronized
    fun snapshot(): List<AgentChatMessageUi> = buildList {
        addAll(done)
        thinkingId?.let { add(ThinkingMessageUi(it, thinkingText.toString(), isStreaming = true)) }
        textId?.let { add(AgentMessageUi(it, textText.toString(), isStreaming = true)) }
    }

    private fun closeThinking() {
        val id = thinkingId ?: return
        done += ThinkingMessageUi(id, thinkingText.toString(), isStreaming = false, collapsed = true)
        thinkingId = null
    }

    private fun closeText() {
        val id = textId ?: return
        done += AgentMessageUi(id, textText.toString())
        textId = null
    }
}
