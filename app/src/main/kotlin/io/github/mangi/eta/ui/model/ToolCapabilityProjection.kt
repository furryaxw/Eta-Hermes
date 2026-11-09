package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.agent.tool.LocalToolRequirement
import io.github.mangi.eta.agent.tool.RootRequirement

/** 浏览器的几个说明卡展示同一工具的不同操作，要求仍来自实际执行工具。 */
internal fun toolCardRequirement(id: String): LocalToolRequirement {
    val toolName = actualToolName(id)
    return requireNotNull(AgentToolRequirements.find(toolName)) { "Unknown tool card: $id" }
}

internal fun visibleOnCurrentDevice(id: String, rootGranted: Boolean, colorOs: Boolean): Boolean {
    val requirement = toolCardRequirement(id)
    return (rootGranted || requirement.rootRequirement != RootRequirement.REQUIRED) &&
        (colorOs || !requirement.colorOs)
}

internal fun actualToolName(id: String): String = when (id) {
    "browser_read", "browser_interact", "browser_screenshot" -> "browser_use"
    else -> id
}

/** Hermes 自带网页与记忆能力，Hermes 模式下本机不再提供这两组工具。 */
private val HERMES_PROVIDED_GROUP_IDS = setOf("web", "memory")

internal fun projectToolGroups(
    groups: List<ToolGroupUi>,
    showAll: Boolean,
    rootGranted: Boolean,
    colorOs: Boolean,
    hermesMode: Boolean = false,
): List<ToolGroupUi> =
    groups.asSequence()
        .filter { !hermesMode || it.id !in HERMES_PROVIDED_GROUP_IDS }
        .map { group ->
            group.copy(tools = group.tools.filter { showAll || visibleOnCurrentDevice(it.id, rootGranted, colorOs) })
        }
        .filter { it.tools.isNotEmpty() }
        .toList()

/** 普通权限的缺失优先给出可执行的授权入口，查看增强说明本身不会请求 Root。 */
internal fun toolCardAction(id: String, capabilities: AgentToolCapabilities): AgentToolsAction? {
    val requirement = toolCardRequirement(id)
    return when (capabilities.unavailableCode(actualToolName(id))) {
        "ROOT_REQUIRED", "DEVICE_UNSUPPORTED" -> AgentToolsAction.OpenEnhancements
        null -> when {
            id == "web_search" || id == "fetch_url" -> AgentToolsAction.OpenWebToolsSettings
            id.startsWith("browser_") -> AgentToolsAction.OpenBrowser
            !capabilities.rootAvailable && requirement.rootRequirement == RootRequirement.PARTIAL ->
                AgentToolsAction.OpenEnhancements
            else -> null
        }
        else -> AgentToolsAction.OpenPermissions
    }
}
