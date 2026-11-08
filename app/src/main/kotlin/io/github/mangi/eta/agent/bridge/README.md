# agent/bridge —— Hermes 集成（骨架）

设计见 `docs/HERMES_BRIDGE.md`。本目录在实现前只放说明，不引入编译单元。

## 计划文件

| 文件 | 职责 |
|---|---|
| `HermesBridgeSettings.kt` | 远程模式开关、Hermes base URL、API key、`X-Hermes-Session-Key` / device_id、MCP 服务端口与 bridge token 的持久化（复用 `Prefs`）。 |
| `HermesRemoteMode.kt` | 组装 `AgentModelClient.ModelConfig`：provider 指向 Hermes、禁用本地工具目录与本地记忆注入、替换系统提示为设备上下文。挂接点在 `AgentModelClient` 的 tools 组装处（`toolsFor(...)`，`AgentModelClient.kt:150`）与 `AgentPromptBuilder.buildSystemMessages(...)`。 |
| `HermesSessionHeaders.kt` | 为请求注入 `X-Hermes-Session-*`；与 `CustomHeaderFilter` / `ProviderRequestHeaders` 的现有过滤规则对齐。 |
| `McpServerService.kt` | Android 前台服务，托管 MCP 监听端口；仅在远程模式开启时启动，可被用户开关。 |

## 与既有代码的接线点

- 工具声明：`agent/model/AgentToolCatalog.kt::build(...)`
- 工具执行：`AgentModelClient.ToolExecutor`；实现类 `agent/tool/AgentLocalTools.kt`、`agent/mcp/McpRunContext.kt`（`McpToolExecutor`、`RoutingToolExecutor`）
- 能力闸门：`AgentToolCapabilities.capture(context)`、`AgentRuntimePolicy.constrain(...)`
- GUI 串行化：`agent/runtime/GuiControlCoordinator.kt`
- 敏感工具判定：`agent/model/AgentSensitiveToolPolicy.kt`
- Provider 选择：`agent/model/ProviderClientFactory.kt`

## 约束

- 不暴露记忆工具（`memoryTools = false`），记忆只由 Hermes 持有。
- 工具名对外统一加 `eta_` 前缀，`tools/call` 时剥离。
- 不改动本地模式行为；远程模式必须是可开关的第二条路径。
