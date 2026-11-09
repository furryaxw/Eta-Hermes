package io.github.mangi.eta.agent.hermes

import android.content.Context
import android.util.Base64
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityKeeper
import io.github.mangi.eta.agent.model.AgentFileToolCatalog
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentPhoneToolCatalog
import io.github.mangi.eta.agent.model.AgentTerminalToolCatalog
import io.github.mangi.eta.agent.model.AgentToolCatalog
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.GuiControlCoordinator
import io.github.mangi.eta.agent.tool.AgentLocalTools
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.agent.tool.ToolExecutionDecision
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * 手机侧工具宿主：构建工具目录并把 Hermes 发来的 `tool.call` 落到本机工具上。
 *
 * 不再自己开 WebSocket —— [HermesLink] 是一条同时承载消息与工具的长连接，工具帧由它转进来。
 * 只上报手机专有工具：设备、手机系统、GUI、个人上下文、终端与文件。记忆与 Skills 属于
 * Hermes 自己的领域，不在这里出现。
 */
internal class HermesToolHost(context: Context) : AutoCloseable {

    private val appContext = context.applicationContext
    private val guiControl = GuiControlCoordinator()
    private val bridgeController = AgentRunController()
    private val guiRun = guiControl.openRun(BRIDGE_RUN_ID, bridgeController)
    private val tools = AgentLocalTools(
        context = appContext,
        logger = AndroidAgentLogger,
        guiControl = guiRun,
        beforeToolExecution = { toolName ->
            if (!AgentToolRequirements.requiresAccessibility(toolName)) {
                ToolExecutionDecision.Allow
            } else {
                val accessibility = AgentAccessibilityKeeper.ensureEnabledForGuiOperation(appContext)
                if (!accessibility.available) {
                    ToolExecutionDecision.Reject(accessibility.code, accessibility.message)
                } else {
                    ToolExecutionDecision.Allow
                }
            }
        },
    )

    fun buildTools(): JSONArray {
        val catalog = AgentToolCatalog.build(
            terminalTools = Prefs.isEnabled(Prefs.Keys.AGENT_TERMINAL_TOOLS),
            browserTools = false,
            deviceDirectTools = Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS),
            deviceSensitiveReadTools = Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS),
            deviceSensitiveActionTools = Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS),
            skillGitHubDiscovery = false,
            skillGitHubInstall = false,
            memoryTools = false,
            capabilities = AgentToolCapabilities.capture(appContext),
            localWebSearch = false,
        )
        val allowedGroups = HermesSettings.toolGroups(appContext).toSet()
        val output = JSONArray()
        for (index in 0 until catalog.length()) {
            val item = catalog.optJSONObject(index) ?: continue
            val function = item.optJSONObject("function") ?: continue
            val name = function.optString("name")
            if (name.isEmpty()) continue
            val group = toolGroup(name)
            if (group !in allowedGroups) continue
            output.put(
                JSONObject()
                    .put("type", "function")
                    .put("group", group)
                    .put("function", function),
            )
        }
        return output
    }

    /** 执行一次工具调用，返回 MCP `tools/call` 结果形状（`content` + `isError`）。 */
    fun call(name: String, arguments: JSONObject): JSONObject {
        val result = tools.execute(
            AgentModelClient.ToolCall(
                id = "hermes",
                name = name,
                argumentsJson = arguments.toString(),
            ),
        )
        val content = JSONArray()
        val text = result.content
        if (text.isNotBlank()) {
            content.put(JSONObject().put("type", "text").put("text", text))
        }
        result.images.forEach { image ->
            imageContent(image)?.let(content::put)
        }
        if (content.length() == 0) {
            content.put(JSONObject().put("type", "text").put("text", ""))
        }
        val isError = runCatching {
            val json = JSONObject(text)
            (json.has("ok") && !json.optBoolean("ok", true)) || json.has("error")
        }.getOrDefault(false)
        return JSONObject().put("content", content).put("isError", isError)
    }

    /** MCP image content 必须是内联 base64；远程 URL 或本地路径在这里降级成文本说明。 */
    private fun imageContent(image: AgentModelClient.ModelImage): JSONObject? {
        val reference = image.reference
        val marker = ";base64,"
        val index = reference.indexOf(marker)
        if (reference.startsWith("data:") && index > 0) {
            val mime = reference.substring(5, index)
            val data = reference.substring(index + marker.length)
            val valid = runCatching { Base64.decode(data, Base64.DEFAULT) }.isSuccess
            if (valid) {
                return JSONObject().put("type", "image").put("data", data).put("mimeType", mime)
            }
        }
        return JSONObject()
            .put("type", "text")
            .put("text", "[image] ${reference.take(300)}")
    }

    /**
     * 工具分组决定 Hermes 侧的 toolset 粒度。GUI 名单与三个 schema 目录保持一致；
     * 新增 GUI 工具时必须同步这里，否则会落到 device 组。
     */
    private fun toolGroup(name: String): String = when {
        name in GUI_NAMES -> "gui"
        name in PERSONAL_NAMES -> "personal"
        name in SHELL_NAMES -> "shell"
        name in PHONE_NAMES -> "phone"
        else -> "device"
    }

    override fun close() {
        runCatching { guiRun.close() }
        bridgeController.cancel()
        runCatching { tools.close() }
    }

    private companion object {
        const val BRIDGE_RUN_ID = "hermes-bridge"
        val GUI_NAMES = setOf(
            "get_current_context", "search_apps", "launch_app", "present_to_user", "open_uri",
            "observe_screen", "release_gui",
            "tap", "tap_area", "tap_element", "long_press", "long_press_element", "swipe",
            "scroll", "scroll_element",
            "type_text", "set_clipboard", "get_clipboard", "press_key", "wait", "wait_for_text",
            "wait_for_package", "open_system_panel",
        )
        val PERSONAL_NAMES = setOf("read_personal_item", "summarize_bills")
        val SHELL_NAMES = AgentTerminalToolCatalog.names + AgentFileToolCatalog.names + "read_image"
        val PHONE_NAMES = AgentPhoneToolCatalog.entries.mapTo(mutableSetOf()) { it.name }
    }
}
