#!/usr/bin/env python3
"""Eta tool bridge.

Hermes can reach the phone, so the phone reaches Hermes instead. Eta keeps one
outbound WebSocket to this process; this process speaks MCP (Streamable HTTP)
to Hermes on loopback and relays ``tools/list`` / ``tools/call`` over that
socket.

    Eta ──WSS──► nginx ──► bridge.py ──HTTP──► Hermes (mcp client, 127.0.0.1)

Wire protocol (JSON text frames, one object per frame):

    Eta  → {"type":"hello","protocol":1,"app":"eta","version":"3.4.0"}
    Srv  → {"type":"ready","protocol":1,"server":"eta-bridge"}
    Srv  → {"type":"tools.list","id":"tl-1"}
    Eta  → {"type":"tools.list.result","id":"tl-1","tools":[{"name","description","inputSchema"}]}
    Srv  → {"type":"tool.call","id":"tc-1","name":"device_info","arguments":{}}
    Eta  → {"type":"tool.call.result","id":"tc-1","result":{"content":[...],"isError":false}}
    Eta  → {"type":"tool.call.result","id":"tc-1","error":{"message":"..."}}
    both → {"type":"ping"} / {"type":"pong"}

The bridge caches the last tool list so ``tools/list`` keeps answering while
the phone is offline; a call made while offline is reported as a failed tool
result rather than a transport error, so the agent can adapt.
"""

from __future__ import annotations

import argparse
import asyncio
import hmac
import json
import logging
import os
import tempfile
import time
import uuid
from pathlib import Path
from typing import Any, Optional

from aiohttp import WSMsgType, web

LOG = logging.getLogger("eta-bridge")

BRIDGE_PROTOCOL = 1
DEFAULT_MCP_PROTOCOL = "2025-11-25"
KNOWN_MCP_PROTOCOLS = ("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25")

MAX_TOOL_RESULT_BYTES = 8 * 1024 * 1024


# --------------------------------------------------------------------------- #
# tool cache
# --------------------------------------------------------------------------- #

class ToolCache:
    """Last tool list Eta advertised, persisted across restarts."""

    def __init__(self, path: Path) -> None:
        self.path = path
        self.tools: list[dict[str, Any]] = []
        self.revision = ""
        self._load()

    def _load(self) -> None:
        try:
            raw = json.loads(self.path.read_text(encoding="utf-8"))
        except FileNotFoundError:
            return
        except (OSError, json.JSONDecodeError) as exc:
            LOG.warning("tool cache unreadable (%s); starting empty", exc)
            return
        tools = raw.get("tools")
        if isinstance(tools, list):
            self.tools = [t for t in tools if _valid_tool(t)]
            self.revision = str(raw.get("revision") or "")

    def replace(self, tools: list[dict[str, Any]]) -> bool:
        if tools == self.tools:
            return False
        self.tools = tools
        self.revision = uuid.uuid4().hex[:12]
        payload = json.dumps(
            {"revision": self.revision, "saved_at": int(time.time()), "tools": tools},
            ensure_ascii=False,
            indent=1,
        )
        try:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            fd, tmp = tempfile.mkstemp(dir=str(self.path.parent), prefix=".tools-", suffix=".tmp")
            with os.fdopen(fd, "w", encoding="utf-8") as fh:
                fh.write(payload)
            os.replace(tmp, self.path)
        except OSError as exc:
            LOG.warning("could not persist tool cache: %s", exc)
        return True

    def tools_for(self, group: str | None) -> list[dict[str, Any]]:
        if not group:
            return list(self.tools)
        return [t for t in self.tools if t.get("group") == group]

    def groups(self) -> list[str]:
        return sorted({t["group"] for t in self.tools if t.get("group")})


def _valid_tool(tool: Any) -> bool:
    return (
        isinstance(tool, dict)
        and isinstance(tool.get("name"), str)
        and 0 < len(tool["name"]) <= 128
        and isinstance(tool.get("inputSchema", {"type": "object"}), dict)
    )


def _normalize_tools(raw: Any) -> list[dict[str, Any]]:
    """Accept either MCP tools or Eta's OpenAI function schemas.

    Eta already ships ``AgentToolCatalog`` in OpenAI shape
    (``{"type":"function","function":{name,description,parameters}}``), so the
    phone can forward that array verbatim and add an optional ``group``.
    """
    out: list[dict[str, Any]] = []
    seen: set[str] = set()
    for item in raw if isinstance(raw, list) else []:
        if not isinstance(item, dict):
            continue
        source = item
        if item.get("type") == "function" and isinstance(item.get("function"), dict):
            source = item["function"]
            schema = source.get("parameters")
        else:
            schema = item.get("inputSchema")
        name = source.get("name")
        if not isinstance(name, str) or not name or len(name) > 128 or name in seen:
            continue
        if not isinstance(schema, dict):
            schema = {"type": "object", "properties": {}}
        seen.add(name)
        out.append(
            {
                "name": name,
                "description": str(source.get("description") or ""),
                "inputSchema": schema,
                "group": str(item.get("group") or source.get("group") or ""),
            }
        )
    return out


def _public_tool(tool: dict[str, Any]) -> dict[str, Any]:
    """Strip bridge-only fields before handing a tool to MCP."""
    return {k: tool[k] for k in ("name", "description", "inputSchema")}


# --------------------------------------------------------------------------- #
# phone link
# --------------------------------------------------------------------------- #

class LinkDown(RuntimeError):
    pass


class CallTimeout(RuntimeError):
    pass


class EtaLink:
    def __init__(self, cache: ToolCache, call_timeout: float) -> None:
        self.cache = cache
        self.call_timeout = call_timeout
        self._ws: Optional[web.WebSocketResponse] = None
        self._pending: dict[str, asyncio.Future] = {}
        self.client_info: dict[str, Any] = {}
        self.connected_at = 0.0
        self._refresh_lock = asyncio.Lock()

    @property
    def connected(self) -> bool:
        return self._ws is not None and not self._ws.closed

    def status(self) -> dict[str, Any]:
        return {
            "eta_connected": self.connected,
            "connected_at": self.connected_at,
            "client": self.client_info,
            "tools": len(self.cache.tools),
            "revision": self.cache.revision,
        }

    async def attach(self, ws: web.WebSocketResponse, info: dict[str, Any]) -> None:
        previous = self._ws
        self._ws = ws
        self.client_info = info
        self.connected_at = time.time()
        if previous is not None and not previous.closed:
            try:
                await previous.close(code=4000, message=b"replaced by newer connection")
            except ConnectionError:
                pass
        LOG.info("eta connected client=%s", {k: info.get(k) for k in ("app", "version", "device")})
        asyncio.create_task(self.refresh_tools())

    def detach(self, ws: web.WebSocketResponse) -> None:
        if self._ws is ws:
            self._ws = None
            self.connected_at = 0.0
            self._fail_pending("Eta disconnected")

    def _fail_pending(self, reason: str) -> None:
        pending, self._pending = self._pending, {}
        for fut in pending.values():
            if not fut.done():
                fut.set_exception(LinkDown(reason))

    def _resolve(self, msg: dict[str, Any]) -> None:
        rid = str(msg.get("id") or "")
        fut = self._pending.pop(rid, None)
        if fut is not None and not fut.done():
            fut.set_result(msg)

    async def _request(self, payload: dict[str, Any], timeout: float) -> dict[str, Any]:
        ws = self._ws
        if ws is None or ws.closed:
            raise LinkDown("Eta is not connected")
        rid = str(payload["id"])
        fut: asyncio.Future = asyncio.get_running_loop().create_future()
        self._pending[rid] = fut
        try:
            await ws.send_json(payload)
            return await asyncio.wait_for(fut, timeout)
        except asyncio.TimeoutError as exc:
            raise CallTimeout(f"Eta did not answer {payload['type']} within {timeout:.0f}s") from exc
        finally:
            self._pending.pop(rid, None)

    async def refresh_tools(self, timeout: float = 15.0) -> None:
        async with self._refresh_lock:
            if not self.connected:
                return
            try:
                reply = await self._request({"type": "tools.list", "id": f"tl-{uuid.uuid4().hex[:8]}"}, timeout)
            except (LinkDown, CallTimeout) as exc:
                LOG.warning("tool refresh failed: %s", exc)
                return
            tools = _normalize_tools(reply.get("tools"))
            if self.cache.replace(tools):
                LOG.info("tool list updated: %d tools revision=%s", len(tools), self.cache.revision)

    async def call_tool(self, name: str, arguments: dict[str, Any]) -> dict[str, Any]:
        try:
            reply = await self._request(
                {
                    "type": "tool.call",
                    "id": f"tc-{uuid.uuid4().hex[:12]}",
                    "name": name,
                    "arguments": arguments,
                },
                self.call_timeout,
            )
        except LinkDown:
            return _error_result("Eta is not connected. The phone must keep the bridge WebSocket open.")
        except CallTimeout as exc:
            return _error_result(str(exc))
        error = reply.get("error")
        if isinstance(error, dict):
            return _error_result(str(error.get("message") or "tool failed"))
        result = reply.get("result")
        if not isinstance(result, dict):
            return _error_result("Eta returned a malformed tool result")
        return _sanitize_result(result)


def _error_result(message: str) -> dict[str, Any]:
    return {"content": [{"type": "text", "text": message}], "isError": True}


def _sanitize_result(result: dict[str, Any]) -> dict[str, Any]:
    content = result.get("content")
    clean: list[dict[str, Any]] = []
    if isinstance(content, list):
        for block in content:
            if not isinstance(block, dict):
                continue
            kind = block.get("type")
            if kind == "text" and isinstance(block.get("text"), str):
                clean.append({"type": "text", "text": block["text"][:MAX_TOOL_RESULT_BYTES]})
            elif kind == "image" and isinstance(block.get("data"), str) and isinstance(block.get("mimeType"), str):
                clean.append({"type": "image", "data": block["data"], "mimeType": block["mimeType"]})
    if not clean:
        clean = [{"type": "text", "text": json.dumps(result, ensure_ascii=False)[:MAX_TOOL_RESULT_BYTES]}]
    return {"content": clean, "isError": bool(result.get("isError"))}


# --------------------------------------------------------------------------- #
# MCP (streamable HTTP, handshake era)
# --------------------------------------------------------------------------- #

def _rpc_ok(rid: Any, result: dict[str, Any]) -> dict[str, Any]:
    return {"jsonrpc": "2.0", "id": rid, "result": result}


def _rpc_err(rid: Any, code: int, message: str) -> dict[str, Any]:
    return {"jsonrpc": "2.0", "id": rid, "error": {"code": code, "message": message}}


class McpEndpoint:
    def __init__(self, link: EtaLink) -> None:
        self.link = link

    async def handle(self, request: web.Request, group: str | None = None) -> web.StreamResponse:
        if request.method == "GET":
            # No server-initiated stream. 405 is the spec's "not offered" answer.
            return web.Response(status=405, headers={"Allow": "POST, DELETE"})
        if request.method == "DELETE":
            return web.Response(status=204)
        try:
            payload = await request.json()
        except (json.JSONDecodeError, ValueError):
            return web.json_response(
                _rpc_err(None, -32700, "Parse error"), status=400,
                headers={"mcp-session-id": str(uuid.uuid4())},
            )
        if isinstance(payload, list):
            replies = [
                r for r in (await asyncio.gather(*(self._one(item, group) for item in payload)) if payload else [])
                if r is not None
            ]
            return web.json_response(replies or [], headers={"mcp-session-id": str(uuid.uuid4())})
        reply = await self._one(payload, group)
        if reply is None:
            return web.Response(status=202, headers={"mcp-session-id": str(uuid.uuid4())})
        return web.json_response(reply, headers={"mcp-session-id": str(uuid.uuid4())})

    async def _one(self, payload: Any, group: str | None) -> Optional[dict[str, Any]]:
        if not isinstance(payload, dict) or payload.get("jsonrpc") != "2.0":
            return _rpc_err(None, -32600, "Invalid Request")
        method = payload.get("method")
        rid = payload.get("id")
        params = payload.get("params") or {}
        if not isinstance(params, dict):
            return _rpc_err(rid, -32602, "Invalid params")

        if rid is None:
            # Notification (notifications/initialized, cancelled, ...): no reply.
            return None

        if method == "initialize":
            offered = params.get("protocolVersion")
            version = offered if offered in KNOWN_MCP_PROTOCOLS else DEFAULT_MCP_PROTOCOL
            LOG.info(
                "mcp initialize group=%s client=%s offered=%s -> %s",
                group or "all", (params.get("clientInfo") or {}).get("name"), offered, version,
            )
            return _rpc_ok(rid, {
                "protocolVersion": version,
                "capabilities": {"tools": {"listChanged": False}},
                "serverInfo": {"name": f"eta-bridge-{group}" if group else "eta-bridge", "version": "1.0.0"},
            })

        if method == "ping":
            return _rpc_ok(rid, {})

        if method == "tools/list":
            if self.link.connected:
                await self.link.refresh_tools(timeout=10.0)
            tools = [_public_tool(t) for t in self.link.cache.tools_for(group)]
            return _rpc_ok(rid, {"tools": tools})

        if method == "tools/call":
            name = params.get("name")
            arguments = params.get("arguments") or {}
            if not isinstance(name, str) or not name:
                return _rpc_err(rid, -32602, "Missing tool name")
            if not isinstance(arguments, dict):
                return _rpc_err(rid, -32602, "arguments must be an object")
            if group and name not in {t["name"] for t in self.link.cache.tools_for(group)}:
                return _rpc_err(rid, -32602, f"Unknown tool in group {group!r}: {name}")
            LOG.info("tool.call group=%s name=%s", group or "all", name)
            return _rpc_ok(rid, await self.link.call_tool(name, arguments))

        return _rpc_err(rid, -32601, f"Method not found: {method}")


# --------------------------------------------------------------------------- #
# HTTP surface
# --------------------------------------------------------------------------- #

def _authorized(request: web.Request, token: str) -> bool:
    header = request.headers.get("Authorization", "")
    if not header.startswith("Bearer "):
        return False
    return hmac.compare_digest(header[7:].strip(), token)


def build_app(link: EtaLink, token: str) -> web.Application:
    mcp = McpEndpoint(link)

    async def bridge_ws(request: web.Request) -> web.WebSocketResponse:
        if not _authorized(request, token):
            ws = web.WebSocketResponse()
            await ws.prepare(request)
            await ws.close(code=4401, message=b"unauthorized")
            return ws

        ws = web.WebSocketResponse(heartbeat=25.0, max_msg_size=16 * 1024 * 1024)
        await ws.prepare(request)
        info: dict[str, Any] = {}
        try:
            async for msg in ws:
                if msg.type == WSMsgType.TEXT:
                    await _on_frame(ws, link, info, msg.data)
                elif msg.type == WSMsgType.ERROR:
                    LOG.warning("eta socket error: %s", ws.exception())
                    break
        finally:
            link.detach(ws)
        return ws

    async def health(request: web.Request) -> web.Response:
        return web.json_response({"ok": True, "server": "eta-bridge", "groups": link.cache.groups(), **link.status()})

    async def mcp_route(request: web.Request) -> web.StreamResponse:
        return await mcp.handle(request, request.match_info.get("group"))

    app = web.Application()
    app.router.add_get("/eta/bridge", bridge_ws)
    for path in ("/mcp", "/mcp/{group}"):
        app.router.add_post(path, mcp_route)
        app.router.add_get(path, mcp_route)
        app.router.add_delete(path, mcp_route)
    app.router.add_get("/healthz", health)
    return app


async def _on_frame(ws: web.WebSocketResponse, link: EtaLink, info: dict[str, Any], raw: str) -> None:
    try:
        msg = json.loads(raw)
    except json.JSONDecodeError:
        LOG.warning("eta sent non-JSON frame (%d bytes)", len(raw))
        return
    if not isinstance(msg, dict):
        return
    kind = msg.get("type")
    if kind == "hello":
        if int(msg.get("protocol") or 0) != BRIDGE_PROTOCOL:
            await ws.send_json({"type": "error", "error": f"unsupported bridge protocol {msg.get('protocol')}"})
            await ws.close(code=4400, message=b"protocol mismatch")
            return
        info.update({k: msg.get(k) for k in ("app", "version", "device")})
        await link.attach(ws, info)
        await ws.send_json({"type": "ready", "protocol": BRIDGE_PROTOCOL, "server": "eta-bridge"})
        return
    if kind == "ping":
        await ws.send_json({"type": "pong"})
        return
    if kind in ("tools.list.result", "tool.call.result"):
        link._resolve(msg)
        return
    LOG.debug("ignoring frame type=%s", kind)


# --------------------------------------------------------------------------- #
# entrypoint
# --------------------------------------------------------------------------- #

def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Eta tool bridge for Hermes")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--token-env", default="ETA_BRIDGE_TOKEN")
    parser.add_argument("--token-file", type=Path)
    parser.add_argument("--tool-cache", type=Path, default=Path.home() / ".eta-bridge" / "tools.json")
    parser.add_argument("--call-timeout", type=float, default=300.0)
    parser.add_argument("--log-level", default="INFO")
    args = parser.parse_args(argv)

    logging.basicConfig(
        level=getattr(logging, str(args.log_level).upper(), logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )

    token = os.environ.get(args.token_env, "").strip()
    if not token and args.token_file:
        token = args.token_file.read_text(encoding="utf-8").strip()
    if len(token) < 16:
        LOG.error("bridge token missing or too short; set %s (>=16 chars)", args.token_env)
        return 2

    link = EtaLink(ToolCache(args.tool_cache), args.call_timeout)
    app = build_app(link, token)
    LOG.info("listening host=%s port=%s mcp=/mcp bridge=/eta/bridge tools=%d",
             args.host, args.port, len(link.cache.tools))
    web.run_app(app, host=args.host, port=args.port, print=None, access_log=None)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
