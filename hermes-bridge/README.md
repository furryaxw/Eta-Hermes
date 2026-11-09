# Eta tool bridge — Hermes side

让手机在没有公网入口的前提下，仍能把设备工具提供给 Hermes。

```
Eta ──WSS──► nginx ──► bridge.py ──HTTP(loopback)──► Hermes gateway
                              │                        └─ mcp client: mcp_servers.eta
                              └─ 缓存 tools/list，手机离线时仍能应答
```

对话链路不经过这里：Eta 直接调用 nginx 暴露的 Hermes API server（`/api/sessions/.../chat/stream`）。
本服务只解决**反向工具调用**：Hermes 不能主动连手机，所以手机主动连出，桥在 Hermes 本机把这条长连接伪装成一个本地 MCP server。

## 组成

| 文件 | 作用 |
| --- | --- |
| `bridge.py` | 单文件服务：`/eta/bridge`（手机 WebSocket）+ `/mcp`（Hermes 的 MCP Streamable HTTP）+ `/healthz` |
| `test_bridge.py` | 用真实 MCP SDK 客户端 + 假 Eta 跑端到端 |
| `nginx-eta.conf.example` | 公网分流：`/hermes/` → API server，`/eta/bridge` → 桥 |
| `hermes-config.example.yaml` | Hermes 侧 `mcp_servers` 配置 |

依赖只有 `aiohttp`（Hermes 的 venv 里已有）。

## 快速开始

```bash
export ETA_BRIDGE_TOKEN="$(openssl rand -hex 32)"
python3 bridge.py --host 127.0.0.1 --port 8765 \
  --tool-cache ~/.eta-bridge/tools.json --call-timeout 300
```

Hermes 侧：

```yaml
mcp_servers:
  eta_device:
    url: "http://127.0.0.1:8765/mcp/device"
    timeout: 300
    connect_timeout: 20
  eta_phone:
    url: "http://127.0.0.1:8765/mcp/phone"
    timeout: 300
    connect_timeout: 20
  eta_gui:
    url: "http://127.0.0.1:8765/mcp/gui"
    timeout: 300
    connect_timeout: 20
  eta_personal:
    url: "http://127.0.0.1:8765/mcp/personal"
    timeout: 300
    connect_timeout: 20
```

重启 gateway 后，工具以 `mcp_eta_device_<name>` 这类名字注册，每台子服务器对应一个独立 toolset，可用 `hermes tools` 单独启停。用 `GET /v1/toolsets` 或直接让模型列举工具确认。

验证：

```bash
python3 test_bridge.py
```

## 手机↔桥线路协议

JSON 文本帧，一问一答带 `id`：

| 方向 | 帧 |
| --- | --- |
| Eta → | `{"type":"hello","protocol":1,"app":"eta","version":"3.4.0","device":"..."}` |
| 桥 → | `{"type":"ready","protocol":1,"server":"eta-bridge"}` |
| 桥 → | `{"type":"tools.list","id":"tl-1"}` |
| Eta → | `{"type":"tools.list.result","id":"tl-1","tools":[{...}]}` |

`tools` 里每一项可以是 MCP 形状 `{name,description,inputSchema}`，也可以直接是 Eta `AgentToolCatalog` 的 OpenAI 形状 `{"type":"function","function":{name,description,parameters}}`，再加一个可选的 `group`。桥会归一化；`group` 决定它出现在哪个 `/mcp/<group>` 下。

| 桥 → | `{"type":"tool.call","id":"tc-1","name":"device_info","arguments":{}}` |
| Eta → | `{"type":"tool.call.result","id":"tc-1","result":{"content":[{"type":"text","text":"..."}],"isError":false}}` |
| Eta → | `{"type":"tool.call.result","id":"tc-1","error":{"message":"..."}}` |
| 双向 | `{"type":"ping"}` / `{"type":"pong"}` |

- 握手鉴权：`Authorization: Bearer <token>`，由 nginx 原样透传，桥做常数时间比较。
- 新连接会顶掉旧连接（手机切网重连），旧连接上的未完成调用立即失败。
- `tools/call` 超时由 `--call-timeout` 和 Hermes 侧 `timeout` 双重约束。
- 工具列表落盘缓存；手机离线时 `tools/list` 仍返回上次结果，调用返回 `isError: true` 的文本，让模型自己降级。

## Hermes 侧行为（已核对 v0.22.5 源码）

- `_get_platform_tools()` → `_merge_mcp_servers()` 默认把**所有已启用的 MCP server** 并入每个平台的 toolset（`hermes_cli/tools_config.py:636`），因此 `api_server` 平台不需要额外 `platform_toolsets` 配置就能拿到 `mcp_eta_*`。
- Hermes 的 MCP 客户端在 `auto` 模式下先走 `initialize`（2025-11-25 握手），失败才回退 `server/discover`；桥只实现握手时代，实测通过。
- `tools/list` 在网关启动时拉取；`tools/call` 期间 Hermes 会按 `timeout` 等待。

## 工具分组与取舍

Eta 目前的工具目录共 95 个声明。传给 Hermes 的是**手机专有能力**，Hermes 自带的（记忆、技能）不传：

| 组 | 工具数 | 内容 | 处理 |
|---|---|---|---|
| `device` | 25 | 设备状态、通知、短信验证码、健康、定位、闹钟/计时器、音量/媒体、应用检查、设置读写、logcat | 传 |
| `phone` | 23 | 手电筒、亮度、勿扰、热点、日程、便签、系统开关 | 传 |
| `gui` | 23 | 屏幕观察、应用启动/跳转、点击/长按/滑动/滚动、输入、剪贴板、按键、等待、系统面板 | 传 |
| `personal` | 2 | 个人条目读取、账单汇总 | 传 |
| — | 2 | `memory_get` / `memory_write` | **不传**（Hermes 有记忆） |
| — | 6 | `skills_*` | **不传**（Hermes 有 skills） |
| `shell` | 11 | `terminal*`、`read_file/write_file/edit_file/stat_file/list_directory/glob_files/grep_files`、`read_image` | 传（手机本地执行） |
| — | 3 | `web_search` / `fetch_url` / `browser_use` | 默认不传（Hermes 有网页与浏览器工具） |

`/mcp`（不带分组）返回全部已上报工具，调试用；正式接入用 `/mcp/<group>`，让 Hermes 按 toolset 粒度启停，避免一次把 90 多个 schema 全塞进 prompt。

## 安全边界

- 桥只绑定 `127.0.0.1`，公网唯一入口是 nginx 的 `/eta/bridge`，必须有 TLS。
- 这条链路等于把手机工具的执行权交给 Hermes：GUI 点击、读写日历、读通知等。token 泄露 = 设备控制权泄露。token 放 Eta 的 Keystore 加密配置，不要进日志。
- 建议在 Eta 侧用 `tools.include` 等价的白名单先只放只读工具，确认链路稳定后再加写操作。
- 桥不记录工具参数与结果（只记工具名），避免通知/剪贴板内容进日志。

## 限制

- 每个桥只服务一台手机（一个活动连接）。多设备需要多实例或按设备路由。
- 只有请求/响应，没有工具执行进度流；长任务期间 Hermes 只能等。
- 审批语义在 Hermes 侧不适用于 MCP 工具：Eta 自己决定是否执行敏感动作（建议在 Eta 内做确认 UI）。
- 手机离线时模型仍会看到工具（缓存），调用失败后需要自行改道。
