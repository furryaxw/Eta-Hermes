# agent/mcp/server —— MCP 服务端（骨架）

把 Eta 的设备工具以 MCP over HTTP 暴露给 Hermes。设计见 `docs/HERMES_BRIDGE.md`。

## 计划文件

| 文件 | 职责 |
|---|---|
| `McpServerEndpoint.kt` | HTTP 路由与 JSON-RPC 分发：`initialize`、`tools/list`、`tools/call`、`ping`。鉴权 `Authorization: Bearer <bridge_token>`。 |
| `McpServerToolCatalog.kt` | 由 `AgentToolCatalog.build(...)` 生成工具清单，经 `AgentToolCapabilities` / `AgentRuntimePolicy` 过滤，工具名加 `eta_` 前缀，并补齐 `readOnlyHint` / `destructiveHint` / `idempotentHint`。 |
| `McpServerExecutor.kt` | `tools/call` 落地：构造并复用 `AgentLocalTools`，调用 `AgentModelClient.ToolExecutor.execute(...)`；`ToolResult.images` 转 MCP `image` content block。 |
| `McpServerRuntime.kt` | 长生命周期执行上下文：GUI 租约串行化、observation 状态、并发上限、审计记录。 |

## 协议约束

- JSON-RPC 2.0；协议版本与 Eta 客户端 `McpProtocolMode` 对齐（`AUTO` 协商，支持 `2026-07-28` 与 `2025-11-25`）。
- `tools/list` 结果按调用时的实时能力计算，缓存需带能力版本号。
- 错误一律结构化：`{ok:false, code, message}`，沿用现有错误码（`ACCESSIBILITY_UNAVAILABLE`、`ACTION_OUTCOME_UNKNOWN`、`TOOL_NOT_AVAILABLE` 等）。
- 不提供 resources / prompts / sampling；只做 tools。
