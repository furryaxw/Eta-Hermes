#!/usr/bin/env python3
"""End-to-end check for bridge.py using the real MCP SDK client.

Run with the same interpreter that runs Hermes (it already has `mcp` and
`aiohttp`):

    python test_bridge.py
"""

from __future__ import annotations

import asyncio
import json
import os
import socket
import subprocess
import sys
import tempfile
import time
from pathlib import Path

import aiohttp
from aiohttp import WSMsgType

HERE = Path(__file__).resolve().parent
TOKEN = "test-token-0123456789abcdef"

FAKE_TOOLS = [
    {
        # Eta sends its AgentToolCatalog entries verbatim (OpenAI function shape).
        "type": "function",
        "group": "device",
        "function": {
            "name": "device_info",
            "description": "Return basic device state.",
            "parameters": {"type": "object", "properties": {}},
        },
    },
    {
        "name": "gui_tap",
        "description": "Tap a node on screen.",
        "group": "gui",
        "inputSchema": {
            "type": "object",
            "properties": {"x": {"type": "integer"}, "y": {"type": "integer"}},
            "required": ["x", "y"],
        },
    },
]


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


async def fake_eta(base: str) -> None:
    headers = {"Authorization": f"Bearer {TOKEN}"}
    async with aiohttp.ClientSession() as http:
        ws = await http.ws_connect(f"{base}/eta/bridge", headers=headers, heartbeat=10)
        try:
            await ws.send_json({"type": "hello", "protocol": 1, "app": "eta", "version": "test"})
            async for msg in ws:
                if msg.type != WSMsgType.TEXT:
                    continue
                frame = json.loads(msg.data)
                kind = frame.get("type")
                if kind == "ready":
                    print("[fake-eta] ready")
                elif kind == "tools.list":
                    await ws.send_json({"type": "tools.list.result", "id": frame["id"], "tools": FAKE_TOOLS})
                elif kind == "tool.call":
                    if frame["name"] == "device_info":
                        result = {"content": [{"type": "text", "text": "Android 16, 100%"}], "isError": False}
                    elif frame["name"] == "gui_tap":
                        result = {
                            "content": [{"type": "text", "text": f"tapped {frame['arguments']}"}],
                            "isError": False,
                        }
                    else:
                        await ws.send_json(
                            {"type": "tool.call.result", "id": frame["id"],
                             "error": {"message": f"unknown tool {frame['name']}"}})
                        continue
                    await ws.send_json({"type": "tool.call.result", "id": frame["id"], "result": result})
        finally:
            await ws.close()


async def wait_healthy(base: str, deadline: float = 15.0) -> None:
    end = time.time() + deadline
    async with aiohttp.ClientSession() as http:
        while time.time() < end:
            try:
                async with http.get(f"{base}/healthz") as resp:
                    if resp.status == 200:
                        return
            except aiohttp.ClientError:
                pass
            await asyncio.sleep(0.1)
    raise RuntimeError("bridge did not become healthy")


async def main() -> int:
    port = free_port()
    base = f"http://127.0.0.1:{port}"
    cache = Path(tempfile.mkdtemp(prefix="eta-bridge-test-")) / "tools.json"

    env = dict(os.environ)
    env["ETA_BRIDGE_TOKEN"] = TOKEN
    proc = subprocess.Popen(
        [sys.executable, str(HERE / "bridge.py"), "--port", str(port),
         "--tool-cache", str(cache), "--log-level", "DEBUG"],
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    try:
        await wait_healthy(base)
        eta_task = asyncio.create_task(fake_eta(base))
        await asyncio.sleep(0.3)

        from mcp import ClientSession
        from mcp.client.streamable_http import streamable_http_client

        async with streamable_http_client(f"{base}/mcp") as (read, write):
            async with ClientSession(read, write) as session:
                init = await asyncio.wait_for(session.initialize(), 10)
                print("[mcp] protocol:", init.protocol_version, "server:", init.server_info.name)
                assert init.protocol_version in ("2025-11-25", "2025-06-18", "2025-03-26"), init.protocol_version

                tools = await asyncio.wait_for(session.list_tools(), 10)
                names = [t.name for t in tools.tools]
                print("[mcp] tools:", names)
                assert names == ["device_info", "gui_tap"], names

                res = await asyncio.wait_for(session.call_tool("device_info", {}), 10)
                text = res.content[0].text
                print("[mcp] device_info ->", text)
                assert "Android 16" in text and not res.is_error

                res = await asyncio.wait_for(session.call_tool("gui_tap", {"x": 10, "y": 20}), 10)
                print("[mcp] gui_tap ->", res.content[0].text)
                assert "tapped" in res.content[0].text

                res = await asyncio.wait_for(session.call_tool("nope", {}), 10)
                print("[mcp] unknown tool ->", res.content[0].text, "isError=", res.is_error)
                assert res.is_error

        # Per-group endpoints: one MCP server per tool family, same socket.
        async with streamable_http_client(f"{base}/mcp/device") as (read, write):
            async with ClientSession(read, write) as session:
                init = await asyncio.wait_for(session.initialize(), 10)
                print("[mcp group] server:", init.server_info.name)
                tools = await asyncio.wait_for(session.list_tools(), 10)
                print("[mcp group device] tools:", [t.name for t in tools.tools])
                assert [t.name for t in tools.tools] == ["device_info"]
                res = await asyncio.wait_for(session.call_tool("device_info", {}), 10)
                assert "Android 16" in res.content[0].text

        async with streamable_http_client(f"{base}/mcp/gui") as (read, write):
            async with ClientSession(read, write) as session:
                await asyncio.wait_for(session.initialize(), 10)
                tools = await asyncio.wait_for(session.list_tools(), 10)
                print("[mcp group gui] tools:", [t.name for t in tools.tools])
                assert [t.name for t in tools.tools] == ["gui_tap"]

        eta_task.cancel()
        await asyncio.gather(eta_task, return_exceptions=True)
        await asyncio.sleep(0.2)

        # Phone gone: cached tools must still answer, calls must fail soft.
        async with streamable_http_client(f"{base}/mcp") as (read, write):
            async with ClientSession(read, write) as session:
                await asyncio.wait_for(session.initialize(), 10)
                tools = await asyncio.wait_for(session.list_tools(), 10)
                print("[mcp offline] tools:", [t.name for t in tools.tools])
                assert len(tools.tools) == 2
                res = await asyncio.wait_for(session.call_tool("device_info", {}), 10)
                print("[mcp offline] call ->", res.content[0].text, "isError=", res.is_error)
                assert res.is_error and "not connected" in res.content[0].text.lower()

        print("OK")
        return 0
    finally:
        proc.terminate()
        try:
            out, _ = proc.communicate(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()
            out, _ = proc.communicate()
        if out:
            print("---- bridge log (tail) ----")
            print("\n".join(out.splitlines()[-40:]))


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
