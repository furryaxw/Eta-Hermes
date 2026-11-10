package io.github.mangi.eta.agent.hermes

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class HermesPromptParserTest {

    private fun request(method: String, params: JSONObject) =
        HermesServerRequest(id = 7, method = method, params = params)

    @Test
    fun approvalWithoutOptionsOffersApproveAndDeny() {
        val prompt = HermesPromptParser.parse(
            request(
                HermesPrompt.APPROVAL_METHOD,
                JSONObject().put("prompt_id", "p-1").put("prompt", "要执行 rm -rf /tmp/x 吗？"),
            ),
        )
        assertEquals("p-1", prompt.promptId)
        assertEquals("要执行 rm -rf /tmp/x 吗？", prompt.body)
        assertTrue(prompt.isApproval)
        assertFalse(prompt.allowsFreeText)
        assertEquals(listOf("approve", HermesRpc.DENY_CHOICE), prompt.choices.map { it.value })
    }

    @Test
    fun clarificationWithoutOptionsAsksForFreeText() {
        val prompt = HermesPromptParser.parse(
            request("clarify", JSONObject().put("prompt_id", "c-1").put("question", "哪个设备？")),
        )
        assertEquals("哪个设备？", prompt.body)
        assertFalse(prompt.isApproval)
        assertTrue(prompt.allowsFreeText)
    }

    @Test
    fun stringOptionsBecomeBothLabelAndValue() {
        val prompt = HermesPromptParser.parse(
            request(
                "clarify",
                JSONObject()
                    .put("options", JSONArray().put("客厅").put("卧室")),
            ),
        )
        assertEquals(listOf("客厅" to "客厅", "卧室" to "卧室"), prompt.choices.map { it.label to it.value })
        assertFalse(prompt.allowsFreeText)
    }

    @Test
    fun objectOptionsKeepLabelSeparateFromTheWireValue() {
        val prompt = HermesPromptParser.parse(
            request(
                HermesPrompt.APPROVAL_METHOD,
                JSONObject()
                    .put("prompt_id", "p-2")
                    .put("title", "确认删除")
                    .put(
                        "choices",
                        JSONArray()
                            .put(JSONObject().put("label", "允许一次").put("value", "approve"))
                            .put(JSONObject().put("label", "拒绝").put("value", "deny")),
                    ),
            ),
        )
        assertEquals("确认删除", prompt.title)
        assertEquals(listOf("允许一次" to "approve", "拒绝" to "deny"), prompt.choices.map { it.label to it.value })
    }

    @Test
    fun missingParamsLeaveBodyEmptyAndTitleForTheUi() {
        val prompt = HermesPromptParser.parse(request(HermesPrompt.APPROVAL_METHOD, JSONObject()))
        assertEquals("", prompt.body)
        assertEquals("", prompt.title)
        assertEquals("", prompt.promptId)
        assertEquals(7, prompt.requestId)
    }
}