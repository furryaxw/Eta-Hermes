# Eta × Hermes Gateway 集成设计

状态：设计稿（未实现）。实现工作转交 DSH。

## 1. 目标

Eta（Android 系统级 Agent）不再自己维护第二套「大脑」，改为：

- 记忆、用户偏好、skills、persona 全部由 Hermes gateway 持有（唯一真源）；
- 手机仍然是执行端，Hermes 能调用 Eta 的设备工具去操作这台手机；
- Eta 的 UI 保留，作为这台手机的对话入口与结果展示层。

非目标：

- 不改 Hermes 核心代码（Hermes 侧只做配置 + 一个 MCP server 条目）；
- 不做多手机/多租户；先支持一台设备、一个 Hermes profile（`default`）；
- 不搬运 Eta 的对话历史到 Hermes（历史各自保留，记忆统一）。

## 2. 事实基线（已核对源码）

Hermes 侧：

- HTTP 服务是 `api_server` 适配器（`gateway/platforms/api_server.py`），提供 `/v1/chat/completions`、`/v1/responses`、`/v1/models`、`/v1/toolsets`、`/api/sessions` 等。
- 每个请求跑完整 Hermes agent：同一 profile 的 `MEMORY.md`、`USER.md`、skills 与工具；**记忆天然统一**。
- `gateway/platforms/api_server_openai_routes.py::_parse_chat_messages` 只取 `system`/`user`/`assistant` 的文本；请求体里的 `tools`、`tool_choice` 只参与幂等指纹，`role=tool` 消息直接丢弃。→ **客户端传进来的工具定义不会被使用**。
- 会话头：`X-Hermes-Session-Key`（按频道隔离长记忆）、`X-Hermes-Session-Id`（续接具体 transcript，需配置 API key）。
- 平台工具集：`hermes_cli/tools_config.py::_get_platform_tools(config, platform)`；每个平台读 `platform_toolsets.<platform>`。
- MCP 白名单：`_merge_mcp_servers` —— 平台列表里点名了某个 MCP server，就**只装载点名的**；一个都没点名时默认装载所有全局启用的 MCP server；`no_mcp` 全关。
- `api_server` 的平台工具集默认是 `hermes-api-server`（`toolsets.py`），可用 `platform_toolsets.api_server` 覆盖，键名是 `api_server`。
- 启用 api_server 需要 `API_SERVER_KEY`（≥16 字符）+ `API_SERVER_PORT`，见 `gateway/config_env.py::_api_server`。

Eta 侧：

- provider 层支持 OpenAI-compatible Chat Completions（`agent/model/OpenAiChatCompletionsProvider.kt`），可自定义 base URL、请求头、SSE，工具调用走本地循环。
- 工具声明由 `agent/model/AgentToolCatalog.kt::build(...)` 生成；执行入口是 `AgentModelClient.ToolExecutor.execute(toolCall)`（`AgentLocalTools`、`McpToolExecutor`、`RoutingToolExecutor` 都实现它）。
- 工具能力由 `AgentToolCapabilities.capture(context)` + `AgentRuntimePolicy.constrain(...)` 决定当前哪些工具可见。
- 记忆是单个 `MEMORY.md`（`data/repository/AgentMemoryRepository.kt`），与 Hermes 的两份文件互不相干——这正是要消除的第二套。
- 已有 MCP **客户端**（`agent/mcp/McpHttpClient.kt`、`McpServerManager.kt`），没有 MCP 服务端。
- 许可证 PolyForm Noncommercial 1.0.0；`minSdk 33`、`targetSdk 37`。

## 3. 架构

```
┌─────────────── 手机 (Eta) ───────────────┐        ┌──────────── Hermes (Server1) ────────────┐
│  UI  ──POST /v1/chat/completions─────────┼───────▶│  api_server 适配器                        │
│                                           │◀──SSE──│  └─ AIAgent：记忆/偏好/skills/工具循环     │
│                                           │        │                                           │
│  MCP server (tools/list, tools/call) ◀────┼────────│  mcp_servers.eta = http://手机/mcp        │
│  └─ AgentLocalTools / toolExecutor        │        │  platform_toolsets.api_server 含 eta      │
└───────────────────────────────────────────┘        └───────────────────────────────────────────┘
```

一次对话：

1. Eta 把用户消息 POST 到 Hermes `/v1/chat/completions`（带 `X-Hermes-Session-Key: eta:<device_id>`）。
2. Hermes 的 agent 决定要操作手机时，调用 `mcp_servers.eta` 暴露的工具。
3. Hermes 以 MCP `tools/call` 请求手机的 MCP server；Eta 用现有 `AgentLocalTools` 执行，返回结构化结果。
4. Hermes 继续循环，最终文本经 SSE 回到 Eta；Eta 只渲染。

关键点：**方向是双向的**——文本走 手机→Hermes，工具调用走 Hermes→手机。所以手机必须有一个 Server1 可达的地址（家宽内网 IP，或 Headscale/Tailscale 地址）。

### 3.1 为什么不用「Eta 直接把 tools 传给 Hermes」

Hermes 的 `/v1/chat/completions` 会丢弃 `tools`（见 2 节）。传了也不会执行，模型只会看到自己那套 Server1 工具，手机操作全部失效。

### 3.2 为什么用 MCP，而不是自定义 Hermes 插件工具

- MCP 是 Hermes 与 Eta **双方都已实现**的协议：Hermes 有 `mcp_servers`（含 `url` HTTP 传输），Eta 有成熟的 MCP 客户端；服务端只需按协议实现 `initialize` / `tools/list` / `tools/call`。
- 工具集天然可开关，便于按平台做白名单。

## 4. Eta 侧改动

### 4.1 远程模式（Remote Agent Mode）

新增一个运行模式，位于 `agent/bridge/`：

- provider 指向 Hermes：base URL `https://<hermes>/v1`、Bearer `API_SERVER_KEY`、model 任意字符串（Hermes 以 `model` 别名路由，实际模型由 Hermes 配置决定）。
- **不发送本地 `tools`**，不注入 `AgentToolCatalog` 的系统提示段，不注入 Eta 的 `MEMORY.md`。
- 系统提示只保留：设备信息（机型/系统/时间）、"所有操作通过工具完成，工具由后端下发"、"用中文回答"。
- 请求头：`X-Hermes-Session-Key: eta:<device_id>`；如需严格续接可加 `X-Hermes-Session-Id`。
- 流式：解析标准 OpenAI SSE 的 `delta.content`；`hermes.tool.progress` 这类自定义事件可忽略或映射为 UI 状态行。

### 4.2 MCP 服务端

新增 `agent/mcp/server/`：

- 传输：Streamable HTTP（Eta 客户端已实现的同一协议版本，`McpProtocolMode.AUTO/LATEST`），JSON-RPC 2.0 over POST；`GET` 用于 SSE 可选。
- 绑定：`0.0.0.0:<port>`，默认端口待定（建议 8765），仅在内网/Headscale 可达时监听；绑定地址与 token 放在设置页。
- 鉴权：`Authorization: Bearer <bridge_token>`，与 `API_SERVER_KEY` 不同的独立随机串，常量时间比较。
- `tools/list`：调用 `AgentToolCatalog.build(...)`，参数与 `AgentModelClient` 里一致（`terminalTools` / `browserTools` / `deviceDirectTools` / 敏感读/敏感写 / `memoryTools=false`），再用 `AgentToolCapabilities.capture()` + `AgentRuntimePolicy.constrain()` 过滤；工具名加前缀 `eta_` 以避免与 Hermes 自带工具重名（例如 `eta_observe_screen`），`tools/call` 时去掉前缀再分发。
- `tools/call`：构造一次性的 `AgentLocalTools`（或复用 runtime 里的 `RoutingToolExecutor` 的本地分支），调用 `execute(ToolCall(id, name, argumentsJson))`，把 `ToolResult.content` 原样返回；`ToolResult.images` 以 MCP `image` content block（base64）返回。
- **状态**：`observation_id` / GUI 租约依赖 run 内状态，MCP 会话必须维护一个长期存活、串行化的执行上下文（复用 `GuiControlCoordinator`），并在 `tools/list` 里声明 `readOnlyHint` / `destructiveHint` 供 Hermes 侧审批策略参考。
- 记忆工具不暴露（`memoryTools=false`）；记忆由 Hermes 独占。

### 4.3 安全

- 敏感工具（`AgentSensitiveToolPolicy`）默认拒绝经 MCP 调用，或在 Eta 侧弹确认；确认策略在设置页可配。
- 设备锁屏 / 无障碍未就绪 / Root 未授权时，返回结构化错误码而不是失败文本（沿用现有 `ACCESSIBILITY_UNAVAILABLE` 等码）。
- 记录每次 MCP 调用的审计条目（工具名、耗时、结果码），不记录参数原文。

## 5. Hermes 侧配置（不写代码）

```
# ~/.hermes/.env
API_SERVER_KEY=<32+ 随机串>
API_SERVER_HOST=0.0.0.0
API_SERVER_PORT=8642

# ~/.hermes/config.yaml
mcp_servers:
  eta:
    url: http://<手机地址>:8765/mcp
    headers:
      Authorization: Bearer <bridge_token>
    timeout: 120
    connect_timeout: 15

platform_toolsets:
  api_server: [hermes-api-server, eta]            # 只有面向 Eta 的入口能看到手机工具
  hermes-qqbot: [browser, clarify, ..., fns, atlas, mcsm, msgraph-mail, homelab]
```

**注意（已核对 `_merge_mcp_servers`）**：平台列表里一个 MCP server 都不点名时，会默认装载**所有**启用的 MCP server。所以要让 QQ/CLI 看不到手机工具，必须把它们现有要用的 MCP server 名字显式列进去（形成白名单），而不是只往 `api_server` 加 `eta`。

网关重启：`hermes gateway restart`（属服务操作，需征得同意后执行）。

## 6. 失败模式

| 场景 | 行为 |
|---|---|
| 手机不可达 | Hermes 工具调用超时 → agent 收到工具错误，如实告知用户"手机当前离线" |
| MCP 调用中途断连 | Eta 侧执行继续但结果丢失；Hermes 视为失败，不自动重放破坏性工具 |
| 无障碍服务被杀 | 返回 `ACCESSIBILITY_UNAVAILABLE`，不落成普通异常文本 |
| GUI 并发 | 由 `GuiControlCoordinator` 串行化；Hermes 并行调用时排队并返回排队等待时间 |
| Hermes 侧记忆写入 | 仅 Hermes 可写；Eta 侧 `memoryTools=false` |

## 7. 分阶段实施

**P0 — 连通性验证（不改 Eta 代码）**
Eta 添加自定义 provider 指向 Hermes，确认 `/v1/models`、SSE、`X-Hermes-Session-Key`、记忆命中（问一个只在 Hermes 记忆里的事实）。验收：Eta 里问"我的主力机是哪台"能答对。

**P1 — Eta MCP 服务端**
只暴露 3 个只读工具（`eta_get_current_context`、`eta_observe_screen`、`eta_list_apps`），Hermes 侧 `hermes mcp add` 后能在任意会话里调用。验收：Hermes 能读到手机当前前台应用名。

**P2 — 写操作与 GUI**
`tap_element` / `type_text` / `launch_app` / `present_to_user`，含状态机与并发控制。验收：从 QQ 让 Hermes 在手机上打开指定 app 并读到页面文本。

**P3 — 远程模式收口**
Eta 停止发送本地工具与本地记忆；Eta UI 只做展示；旧的本地 agent 路径保留为可切换模式。

## 8. 开放问题

1. 手机从外网访问时的地址：Headscale 节点名还是公网反代？反代只能解决 手机→Hermes，解决不了 Hermes→手机。
2. `targetSdk 37` 在主力机编译是否需要预览版 SDK；CI 是否沿用上游 GitHub Actions。
3. 是否让 Hermes 完全接管 Eta 的对话历史（当前设计：不接管）。
4. 多设备时 `mcp_servers` 需要动态增删（当前设计：单台写死）。
