package io.github.mangi.eta.agent.hermes

import io.github.mangi.eta.agent.runtime.AgentConversationIdentity
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire

/**
 * Hermes 会话键。
 *
 * 这个键必须和 Eta 侧该 run 的会话身份完全一致：界面按会话身份展示，服务端按这个键
 * 找会话。两者一旦分叉，同一段对话在 Hermes 侧每轮都会新建会话——界面看着连续，
 * 服务端每轮都从空历史开始。
 */
internal fun hermesSessionKey(request: AgentRuntimeWire.RunRequest): String =
    AgentConversationIdentity.fromHandoff(request.handoff, request.runId)