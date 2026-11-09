package io.github.mangi.eta.agent.hermes

import android.app.Application
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentConversationIdentity
import io.github.mangi.eta.agent.runtime.AgentExternalArchivePayload
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.AgentUiHandoffPayload
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class HermesSessionKeyTest {

    /** 系统助理（语音）入口：同一个会话的每一轮都必须落在同一个 Hermes 会话上。 */
    @Test
    fun voiceEntryKeepsOneHermesSessionPerConversation() {
        val first = request(
            runId = "run-1",
            handoff = AgentRuntimeWire.EntryHandoff(
                id = "run-1",
                source = AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE,
                payload = AgentExternalArchivePayload("第一句", "eta_assistant_fixed", "会话").toJson(),
            ),
        )
        val second = first.copy(runId = "run-2", handoff = first.handoff?.copy(id = "run-2"))

        assertEquals(
            AgentConversationIdentity.archiveConversationId(
                AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE,
                "eta_assistant_fixed",
            ),
            hermesSessionKey(first),
        )
        assertEquals(hermesSessionKey(first), hermesSessionKey(second))
    }

    /** 小爱 / 小布这类外部入口同理：会话键跟着归档会话走，不跟 run。 */
    @Test
    fun externalEntriesStayRunIndependent() {
        for (source in listOf("xiaoai", "breeno")) {
            val handoff = AgentRuntimeWire.EntryHandoff(
                id = "run-1",
                source = source,
                payload = AgentExternalArchivePayload("请求", "dialog-42", "会话").toJson(),
            )
            val first = request("run-1", handoff)
            val second = request("run-2", handoff.copy(id = "run-2"))
            assertEquals(
                "source=$source",
                AgentConversationIdentity.fromHandoff(handoff, "run-1"),
                hermesSessionKey(first),
            )
            assertEquals("source=$source", hermesSessionKey(first), hermesSessionKey(second))
        }
    }

    @Test
    fun agentUiEntryUsesTheConversationPayload() {
        val handoff = AgentRuntimeWire.EntryHandoff(
            id = "run-1",
            source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
            payload = AgentUiHandoffPayload("conv-a").toJson(),
        )
        assertEquals("conv-a", hermesSessionKey(request("run-1", handoff)))
    }

    /** 没有持久会话的入口本来就以 run 为会话起点，与界面身份保持一致。 */
    @Test
    fun runsWithoutAConversationUseTheirRunScope() {
        assertEquals("run:run-a", hermesSessionKey(request("run-a", null)))
    }

    private fun request(runId: String, handoff: AgentRuntimeWire.EntryHandoff?) =
        AgentRuntimeWire.RunRequest(
            runId = runId,
            prompt = "你好",
            config = AgentModelClient.ModelConfig(
                baseUrl = "",
                apiKey = "",
                model = "test",
                systemPrompt = "",
            ),
            images = emptyList(),
            handoff = handoff,
        )
}