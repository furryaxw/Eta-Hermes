package io.github.mangi.eta.agent.hermes

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRuntimeRunExecutor
import io.github.mangi.eta.agent.runtime.AgentRuntimeSession
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.core.AndroidAgentLogger
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hermes 后端的单次 run 执行器。
 *
 * 与本地执行器互斥：不加载模型、记忆、Skills、子代理与压缩器，只把 Hermes 的 SSE 翻译成
 * Eta 前端已经在消费的 [AgentEvent]。工具由 Hermes 侧调用手机 MCP 桥执行，因此这里不持有
 * 本地工具执行器。
 */
internal class HermesRunExecutor(
    private val client: HermesApiClient,
    private val sessionId: String,
    private val snapshotRequest: (AgentRuntimeWire.RunRequest) -> AgentRuntimeWire.RunRequest,
    private val persistArtifacts: (
        AgentRuntimeWire.RunRequest,
        AgentRuntimeWire.RunResult,
        List<AgentEvent>,
    ) -> Unit,
) {
    fun execute(
        session: AgentRuntimeSession,
        request: AgentRuntimeWire.RunRequest,
    ): AgentRuntimeRunExecutor.Outcome {
        val controller = session.controller
        val events = mutableListOf<AgentEvent>()
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val toolIds = mutableMapOf<String, ArrayDeque<String>>()
        val toolSeq = AtomicInteger(0)
        var round = 1
        var textIndex = 0
        var textOpen = false
        var thinkingOpen = false
        var thinkingIndex = 0
        var newRoundOnText = false
        var runId: String? = null
        var supplementIndex = 0
        var cancelled = false
        var terminalError: String? = null
        var finalContent = ""

        fun accept(event: AgentEvent) {
            if (event !is AgentEvent.GuiControlChanged) events += event
            session.emit(event)
        }

        fun openText() {
            if (textOpen) return
            if (thinkingOpen) {
                accept(
                    AgentEvent.AssistantBlockEnd(
                        round = round,
                        kind = AgentEvent.AssistantBlockKind.THINKING,
                        index = thinkingIndex,
                        contentChars = reasoning.length,
                    ),
                )
                thinkingOpen = false
                thinkingIndex += 1
            }
            if (newRoundOnText) {
                round += 1
                textIndex = 0
                newRoundOnText = false
            }
            accept(AgentEvent.AssistantBlockStart(round, AgentEvent.AssistantBlockKind.TEXT, textIndex))
            textOpen = true
        }

        fun closeText(nextRound: Boolean) {
            if (!textOpen) return
            accept(
                AgentEvent.AssistantBlockEnd(
                    round = round,
                    kind = AgentEvent.AssistantBlockKind.TEXT,
                    index = textIndex,
                    contentChars = text.length,
                ),
            )
            textOpen = false
            textIndex += 1
            if (nextRound) newRoundOnText = true
        }

        fun openThinking() {
            if (thinkingOpen) return
            // 每条助手消息重新计数：前缀去重只在本条消息内有效。
            if (textOpen) closeText(nextRound = false)
            // 每一段推理都是一个独立块：Hermes 在一轮里会思考→调工具→再思考，
            // 复用同一个 index 会让界面把两段合并成一块，并打断与工具的先后关系。
            reasoning.setLength(0)
            accept(AgentEvent.AssistantBlockStart(round, AgentEvent.AssistantBlockKind.THINKING, thinkingIndex))
            thinkingOpen = true
        }

        fun closeThinking() {
            if (!thinkingOpen) return
            accept(
                AgentEvent.AssistantBlockEnd(
                    round = round,
                    kind = AgentEvent.AssistantBlockKind.THINKING,
                    index = thinkingIndex,
                    contentChars = reasoning.length,
                ),
            )
            thinkingOpen = false
            thinkingIndex += 1
        }

        fun toolId(name: String, remoteId: String = ""): String {
            // 优先用 Hermes 的 tool_call_id：并行调用同名工具时，自己编号会配错结果。
            val id = remoteId.takeIf { it.isNotBlank() } ?: "hermes-${toolSeq.incrementAndGet()}"
            toolIds.getOrPut(name) { ArrayDeque() }.addLast(id)
            return id
        }

        fun popToolId(name: String, remoteId: String = ""): String {
            val queue = toolIds[name]
            if (remoteId.isNotBlank()) {
                // 按 Hermes 的 id 精确配对，避免并行同名工具的结果落到别的卡片上。
                queue?.remove(remoteId)
                return remoteId
            }
            return queue?.pollFirst() ?: "hermes-unknown-${toolSeq.incrementAndGet()}"
        }

        fun steerIfNeeded() {
            val pending = controller.pollSteeringMessage() ?: return
            // platform 模式下没有 run_id：会话 id 就是导向，服务端按 busy 语义并入当前轮。
            runCatching { client.steer(runId ?: sessionId, pending) }
            accept(AgentEvent.UserSupplementReceived(supplementIndex++, pending))
        }

        val binding = controller.register { client.cancel() }
        // "重试"交给 Hermes 自己处理：它知道上一轮是什么，会原地重跑，而不是把用户的话再发一遍。
        val retrying = request.operation == AgentRuntimeWire.OP_REWRITE_REPLY
        val result = try {
            client.ensureSession(sessionId, request.prompt.take(60))
            accept(
                AgentEvent.RunStarted(
                    initialImages = request.images.size,
                    initialImageBytes = request.images.sumOf { it.bytes },
                    toolCount = 0,
                    terminalTools = false,
                ),
            )
            val prompt = buildString {
                if (request.assistantScreenContext.isNotBlank()) {
                    append(request.assistantScreenContext.trim())
                    append("\n\n")
                }
                append(request.prompt)
            }
            val outbound = if (retrying) RETRY_COMMAND else prompt
            val outboundImages = if (retrying) emptyList() else request.images
            // 编辑用户消息：rewriteTargetMessageId 承载 "user:<序号>"，走截断重发。
            val rewindOrdinal = request.rewriteTargetMessageId
                ?.takeIf { it.startsWith(USER_REWIND_PREFIX) }
                ?.removePrefix(USER_REWIND_PREFIX)
                ?.toIntOrNull()
            client.streamChat(sessionId, outbound, outboundImages, rewindOrdinal) { event ->
                controller.throwIfCancelled()
                steerIfNeeded()
                val data = event.data
                data.optString("run_id").takeIf { it.isNotBlank() }?.let { runId = it }
                when (event.name) {
                    "assistant.reset" -> {
                        // The phone is rebuilding this block from scratch (an edit that rewrote
                        // the text rather than appending to it).
                        closeText(nextRound = false)
                        text.setLength(0)
                    }

                    "assistant.delta" -> {
                        val delta = data.optString("delta")
                        if (delta.isNotEmpty()) {
                            openText()
                            text.append(delta)
                            accept(
                                AgentEvent.AssistantBlockDelta(
                                    round, AgentEvent.AssistantBlockKind.TEXT, textIndex,
                                    delta.length, delta,
                                ),
                            )
                        }
                    }

                    "assistant.commentary" -> {
                        val delta = data.optString("text")
                        if (delta.isNotEmpty()) {
                            openText()
                            text.append(delta)
                            accept(
                                AgentEvent.AssistantBlockDelta(
                                    round, AgentEvent.AssistantBlockKind.TEXT, textIndex,
                                    delta.length, delta,
                                ),
                            )
                        }
                    }

                    "tool.progress" -> {
                        val name = data.optString("tool_name")
                        val delta = data.optString("delta")
                        if (name == "_thinking" && delta.isNotEmpty()) {
                            openThinking()
                            reasoning.append(delta)
                            accept(
                                AgentEvent.AssistantBlockDelta(
                                    round, AgentEvent.AssistantBlockKind.THINKING, thinkingIndex,
                                    delta.length, delta,
                                ),
                            )
                        }
                    }

                    "tool.started" -> {
                        closeThinking()
                        closeText(nextRound = true)
                        val name = data.optString("tool_name")
                        val args = data.optJSONObject("args")
                        accept(
                            AgentEvent.ToolStarted(
                                round = round,
                                toolCallId = toolId(name, data.optString("tool_id")),
                                name = name,
                                argsPreview = args?.toString().orEmpty(),
                                command = args?.optString("command")?.takeIf { it.isNotBlank() },
                            ),
                        )
                    }

                    "tool.completed", "tool.failed" -> {
                        val name = data.optString("tool_name")
                        accept(
                            AgentEvent.ToolFinished(
                                round = round,
                                toolCallId = popToolId(name, data.optString("tool_id")),
                                name = name,
                                resultSummary = data.optString("preview"),
                                imageCount = 0,
                                imageBytes = 0,
                                success = event.name == "tool.completed",
                            ),
                        )
                    }

                    "approval.request" -> {
                        // 第一版没有审批 UI：显式拒绝，避免 Hermes 侧等到超时。
                        runId?.let { id -> runCatching { client.approve(id, "deny") } }
                        terminalError = "Hermes 请求执行需要审批的命令，当前版本尚未接入审批界面"
                    }

                    "assistant.completed" -> {
                        val content = data.optString("content")
                        if (content.isNotBlank()) {
                            finalContent = content
                            if (text.isEmpty()) {
                                openText()
                                text.append(content)
                                accept(
                                    AgentEvent.AssistantBlockDelta(
                                        round, AgentEvent.AssistantBlockKind.TEXT, textIndex,
                                        content.length, content,
                                    ),
                                )
                            }
                        }
                        data.optJSONObject("usage")?.let { emitUsage(it, round, ::accept) }
                    }

                    "run.completed" -> {
                        data.optJSONObject("usage")?.let { emitUsage(it, round, ::accept) }
                        if (finalContent.isBlank()) finalContent = data.optString("output")
                    }

                    "run.failed" -> {
                        terminalError = data.optString("error").ifBlank {
                            data.optString("turn_exit_reason").ifBlank { "Hermes 运行失败" }
                        }
                    }

                    "run.cancelled" -> {
                        terminalError = null
                        cancelled = true
                    }

                    "error" -> terminalError = data.optString("message").ifBlank { "Hermes 返回错误" }
                }
                terminalError == null && !cancelled
            }
            closeThinking()
            closeText(nextRound = false)
            if (cancelled) throw AgentRunCancelledException()
            terminalError?.let { throw IllegalStateException(it) }
            val content = finalContent.ifBlank { text.toString() }
            AgentRuntimeWire.RunResult(
                runId = request.runId,
                ok = true,
                content = content,
                reasoningContent = reasoning.toString(),
                transcript = buildTranscript(request.prompt, content, reasoning.toString()),
                operation = request.operation,
                rewriteTargetMessageId = request.rewriteTargetMessageId,
            )
        } catch (throwable: Throwable) {
            cancelled = cancelled || controller.isCancelled || throwable is AgentRunCancelledException
            terminalError = terminalError ?: throwable.message
            if (!cancelled) {
                val message = terminalError ?: "Hermes 运行失败"
                AndroidAgentLogger.error("Hermes run failed: ${throwable.javaClass.simpleName}: $message", throwable)
                closeThinking()
                closeText(nextRound = false)
                runCatching { accept(AgentEvent.RunFailed(message)) }
            }
            AgentRuntimeWire.RunResult(
                runId = request.runId,
                ok = false,
                content = "",
                error = if (cancelled) "已停止" else (terminalError ?: "Hermes 运行失败"),
                reasoningContent = reasoning.toString(),
                operation = request.operation,
                rewriteTargetMessageId = request.rewriteTargetMessageId,
            )
        } finally {
            binding.close()
        }

        if (cancelled && session.isTerminal) {
            runCatching { persistArtifacts(snapshotRequest(request), result, events) }
            return AgentRuntimeRunExecutor.Outcome(
                result = result,
                entrySurfaceGuard = null,
                shouldUpdateHost = true,
            )
        }

        val completedRequest = runCatching { snapshotRequest(request) }.getOrDefault(request)
        val committed = session.complete(result) {
            runCatching { persistArtifacts(completedRequest, result, events) }
        }
        return AgentRuntimeRunExecutor.Outcome(
            result = result,
            entrySurfaceGuard = null,
            completedRequest = completedRequest.takeIf { committed },
            response = AgentModelClient.ModelResponse.Text(
                content = result.content,
                reasoningContent = result.reasoningContent,
                transcript = result.transcript,
            ).takeIf { committed },
            shouldUpdateHost = committed,
        )
    }

    private companion object {
        /** Hermes 自己的重跑命令：它按会话历史重发上一轮，而不是重复用户这句话。 */
        const val RETRY_COMMAND = "/retry"

        /** rewriteTargetMessageId 以它开头表示"截断到第 N 个用户消息并改发新文本"。 */
        const val USER_REWIND_PREFIX = "user:"
    }

    private fun emitUsage(
        usage: JSONObject,
        round: Int,
        accept: (AgentEvent) -> Unit,
    ) {
        fun int(vararg keys: String): Int? {
            keys.forEach { key ->
                if (usage.has(key) && !usage.isNull(key)) {
                    val value = usage.optInt(key, -1)
                    if (value >= 0) return value
                }
            }
            return null
        }
        val payload = AgentTokenUsage(
            contextTokens = int("context_tokens", "prompt_tokens", "input_tokens"),
            inputTokens = int("input_tokens", "prompt_tokens"),
            outputTokens = int("output_tokens", "completion_tokens"),
            reasoningTokens = int("reasoning_tokens"),
            cachedTokens = int("cached_tokens", "cache_read_input_tokens"),
        )
        if (!payload.isEmpty) accept(AgentEvent.UsageReceived(round, payload))
    }

    private fun buildTranscript(
        prompt: String,
        content: String,
        reasoning: String,
    ): List<AgentModelClient.ConversationMessage> = listOf(
        AgentModelClient.ConversationMessage(role = "user", content = prompt),
        AgentModelClient.ConversationMessage(
            role = "assistant",
            content = content,
            reasoningContent = reasoning,
        ),
    )
}
