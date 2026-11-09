package io.github.mangi.eta.agent.hermes

import android.content.Context
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.app.AgentConversationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Hermes 后端模式下的会话来源。
 *
 * 后端配置好以后，会话的唯一真源是 Hermes：本地 AI 会话不再出现在列表里，
 * 列表、标题、历史全部来自 `session.list` / `session.resume`。
 */
internal object HermesConversationMirror {

    private val IMAGE_REF = Regex("""@image:(\S+)""")

    /** 客户端发附件时附加的独立标签（`[screenshot]` 之类），不是用户写的内容。 */
    private val ATTACHMENT_BADGE = Regex(
        """\[(?:screenshot|image|photo|picture|attachment|file|附件|截图)\]""",
        RegexOption.IGNORE_CASE,
    )

    /** 是否由 Hermes 接管会话列表。 */
    fun isActive(context: Context): Boolean =
        HermesSettings.isChatConfigured(context) && HermesSettings.deviceToken(context).isNotBlank()

    /**
     * 哪些会话该出现在手机上。
     *
     * 只显示手机自己发起（`source = "eta"`）的会话：桌面端、QQ、定时任务、终端会话
     * 都不进入手机列表。
     */
    private fun isMirrorable(row: JSONObject): Boolean =
        row.optString("id").isNotBlank() && row.optString("source") == "eta"

    private fun connectedRpc(context: Context): HermesRpc? {
        val baseUrl = HermesSettings.baseUrl(context)
        val token = HermesSettings.deviceToken(context)
        if (baseUrl.isBlank() || token.isBlank()) return null
        val rpc = HermesRpcHolder.link(context)
        if (!rpc.isConnected && !rpc.connect(baseUrl, token)) return null
        return rpc
    }

    /** 侧栏快照：只带标题与时间，正文等点开再拉。 */
    suspend fun snapshot(context: Context): AgentConversationStore.Snapshot? = withContext(Dispatchers.IO) {
        val rpc = connectedRpc(context) ?: return@withContext null
        val rows = runCatching { rpc.listSessions(300) }.onFailure {
            AndroidAgentLogger.warn("Hermes session.list failed: ${it.message}")
        }.getOrNull() ?: return@withContext null

        val states = linkedMapOf<String, AgentChatHomeUiState>()
        val titles = mutableMapOf<String, String>()
        val updatedAt = mutableMapOf<String, Long>()
        rows.filter(::isMirrorable).forEach { row ->
            val id = row.optString("id")
            if (id.isBlank()) return@forEach
            states[id] = AgentChatHomeUiState(
                messages = emptyList(),
                input = "",
                isStreaming = false,
                thinkingEnabled = true,
                isConversationLoaded = false,
            )
            titles[id] = row.optString("title").ifBlank { row.optString("preview").take(40) }
            updatedAt[id] = (row.optDouble("started_at", 0.0) * 1000).toLong()
        }
        AgentConversationStore.Snapshot(
            selectedConversationId = states.keys.firstOrNull(),
            conversationsById = states,
            titles = titles,
            updatedAt = updatedAt,
            pinnedIds = emptySet(),
        )
    }

    /** 打开会话：resume 拿运行时 id，再用 history 铺开消息。 */
    suspend fun loadConversation(context: Context, conversationId: String): AgentChatHomeUiState? =
        withContext(Dispatchers.IO) {
            val rpc = connectedRpc(context) ?: return@withContext null
            val opened = runCatching { rpc.openSession(conversationId) }.onFailure {
                AndroidAgentLogger.warn("Hermes resume failed for $conversationId: ${it.message}")
            }.getOrNull()
            if (opened == null) {
                // 会话正忙（或暂时 resume 不了）时不要判定整页失败：退回只读历史。
                // 注意 history 只认运行时 id：拿 stored id 查会返回空数组而不是报错。
                val rows = runCatching { rpc.historyMessages(conversationId) }.getOrNull()
                if (rows == null || rows.length() == 0) return@withContext null
                return@withContext AgentChatHomeUiState(
                    messages = hydrate(rpc, conversationId, historyMessages(rows)),
                    input = "",
                    isStreaming = false,
                    thinkingEnabled = true,
                    isConversationLoaded = true,
                    messageStartIndex = 0,
                    hasEarlierMessages = false,
                )
            }

            val live = opened.optString("session_id").ifBlank { conversationId }
            // 记住 运行时 id ↔ stored id，之后发送走运行时 id。
            HermesRpcHolder.link(context).adopt(conversationId, live)

            val messages = hydrate(rpc, live, historyMessages(opened.optJSONArray("messages")))
            AgentChatHomeUiState(
                messages = messages,
                input = "",
                isStreaming = false,
                thinkingEnabled = true,
                isConversationLoaded = true,
                messageStartIndex = 0,
                hasEarlierMessages = opened.optBoolean("messages_omitted", false),
            )
        }

    /** history 行 → Eta 的消息模型；thinking 单独成块，保持与工具/正文的先后。 */
    private fun historyMessages(rows: org.json.JSONArray?): List<io.github.mangi.eta.ui.model.AgentChatMessageUi> {
        if (rows == null) return emptyList()
        val out = mutableListOf<io.github.mangi.eta.ui.model.AgentChatMessageUi>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val id = row.optString("row_id").ifBlank { "hermes-$index" }
            val text = row.optString("text")
            when (row.optString("role")) {
                "user" -> {
                    // history 只把附件写成 `@image:<path>`：先收路径，稍后换成 data URL 再显示。
                    val attached = IMAGE_REF.findAll(text).map { it.groupValues[1] }.toList()
                    out += io.github.mangi.eta.ui.model.UserMessageUi(
                        id = id,
                        content = cleanUserText(text),
                        images = attached,
                    )
                }

                "assistant" -> {
                    // 落盘历史用 reasoning_content，实时行用 reasoning —— 两个都认。
                    val reasoning = row.optString("reasoning_content")
                        .ifBlank { row.optString("reasoning") }
                    if (reasoning.isNotBlank()) {
                        out += io.github.mangi.eta.ui.model.ThinkingMessageUi(
                            id = "$id-thinking",
                            content = reasoning,
                            isStreaming = false,
                            collapsed = true,
                        )
                    }
                    if (text.isNotBlank()) {
                        out += io.github.mangi.eta.ui.model.AgentMessageUi(id = id, content = text)
                    }
                }

                // 历史里的工具行只有调用记录（没有结果）；参数用完整 JSON，和流式卡片保持一致。
                "tool" -> {
                    val name = row.optString("name")
                    if (name.isNotBlank()) {
                        val args = row.optJSONObject("args")
                        val summary = args?.toString()?.takeIf { it.isNotBlank() && it != "{}" }
                            ?: row.optString("context")
                        out += io.github.mangi.eta.ui.model.ToolActivityMessageUi(
                            id = row.optString("tool_call_id").ifBlank { id },
                            toolName = name,
                            status = io.github.mangi.eta.ui.model.ToolActivityStatusUi.Success,
                            argumentsSummary = summary,
                            command = args?.optString("command")?.takeIf { it.isNotBlank() },
                        )
                    }
                }
            }
        }
        return out
    }

    /** 把历史行转成消息；[maxRowId] 给定时丢弃该行及之后的内容（删除操作的服务端残留）。 */
    fun messagesFrom(rows: org.json.JSONArray?, maxRowId: Long? = null): List<io.github.mangi.eta.ui.model.AgentChatMessageUi> {
        if (rows == null) return emptyList()
        if (maxRowId == null) return historyMessages(rows)
        val kept = org.json.JSONArray()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val rowId = row.optLong("row_id", 0L)
            // 目标行本身也要丢：删除是"整组"下线。
            if (rowId > 0L && rowId >= maxRowId) continue
            kept.put(row)
        }
        return historyMessages(kept)
    }

    /**
     * 补齐历史里缺的两样东西：`@image:<path>` 换成 data URL，工具结果按 `tool_call_id` 取回来。
     */
    private fun hydrate(
        rpc: HermesRpc,
        sessionId: String,
        messages: List<io.github.mangi.eta.ui.model.AgentChatMessageUi>,
    ): List<io.github.mangi.eta.ui.model.AgentChatMessageUi> {
        val toolIds = messages.filterIsInstance<io.github.mangi.eta.ui.model.ToolActivityMessageUi>()
            .map { it.id }
            .filter { it.isNotBlank() }
        val results = if (toolIds.isEmpty()) {
            emptyMap()
        } else {
            runCatching { rpc.toolResults(sessionId, toolIds) }.getOrDefault(emptyMap())
        }
        return messages.map { message ->
            when (message) {
                is io.github.mangi.eta.ui.model.UserMessageUi -> {
                    if (message.images.isEmpty()) {
                        message
                    } else {
                        val urls = message.images.mapNotNull { reference ->
                            reference.takeIf { it.startsWith("data:") }
                                ?: runCatching { rpc.readImage(reference) }.getOrNull()
                        }
                        message.copy(images = urls)
                    }
                }

                is io.github.mangi.eta.ui.model.ToolActivityMessageUi -> {
                    results[message.id]?.let { output ->
                        message.copy(
                            status = io.github.mangi.eta.ui.model.ToolActivityStatusUi.Success,
                            resultSummary = output,
                        )
                    } ?: message
                }

                else -> message
            }
        }
    }

    /**
     * 去掉附件引用：手机本地路径服务端读不到，回读时只会显示成噪音。
     * `@file:` / `@image:` 可能出现在行中间，按模式替换；Eta 自己的文件段整段丢弃。
     */
    private fun cleanUserText(raw: String): String {
        val body = runCatching {
            io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec.parse(raw).request
        }.getOrDefault(raw)
        return body
            .replace(Regex("""@(?:file|image):\S+"""), "")
            .replace(ATTACHMENT_BADGE, "")
            .lines()
            .filterNot { it.trimStart().startsWith("[User attached image:") }
            .joinToString("\n")
            .replace(Regex("""[ \t]+\n"""), "\n")
            .trim()
    }

    /** 把 Hermes 侧的会话标题写回侧栏（重命名时用）。 */
    fun titleOf(row: JSONObject): String =
        row.optString("title").ifBlank { row.optString("preview").take(40) }
}
