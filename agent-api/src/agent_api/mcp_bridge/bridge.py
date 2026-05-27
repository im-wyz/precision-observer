"""
连接 Docker MCP 服务（SSE），供 LangGraph 各角色 function calling 使用。
工具全名格式：{server}__{tool_name}，例如 gee__run_analysis

注意：本包名为 mcp_bridge，避免与 PyPI 的 mcp 库同名导致 import 冲突。
"""
from __future__ import annotations

import asyncio
import json
import os
from typing import Any

# OpenAI 工具定义缓存
_TOOL_CACHE: list[dict[str, Any]] | None = None

MCP_CONNECT_TIMEOUT_SEC = float(os.getenv("MCP_CONNECT_TIMEOUT_SEC", "8"))
MCP_GEE_TOOL_TIMEOUT_SEC = float(os.getenv("MCP_GEE_TOOL_TIMEOUT_SEC", "600"))


def _format_call_error(e: BaseException) -> str:
    """展开 asyncio TaskGroup / ExceptionGroup，便于排查 MCP SSE 超时。"""
    parts: list[str] = [str(e)]
    sub = getattr(e, "exceptions", None)
    if sub:
        for i, ex in enumerate(sub):
            parts.append(f"  [{i}] {type(ex).__name__}: {ex}")
    return "; ".join(parts)

SERVER_URLS: dict[str, str] = {
    "gee": os.getenv("MCP_GEE_URL", "http://127.0.0.1:8101/sse"),
    "gdal": os.getenv("MCP_GDAL_URL", "http://127.0.0.1:8102/sse"),
    "raster": os.getenv("MCP_RASTER_URL", "http://127.0.0.1:8103/sse"),
    "storage": os.getenv("MCP_STORAGE_URL", "http://127.0.0.1:8104/sse"),
    "inference": os.getenv("MCP_INFERENCE_URL", "http://127.0.0.1:8105/sse"),
}


def mcp_enabled() -> bool:
    return os.getenv("MCP_ENABLED", "false").strip().lower() in ("1", "true", "yes")


def parse_tool_name(full_name: str) -> tuple[str, str]:
    if "__" not in full_name:
        raise ValueError(f"工具名须为 server__tool，收到: {full_name}")
    server, tool = full_name.split("__", 1)
    return server.strip(), tool.strip()


async def _with_session(server: str, fn, timeout_sec: float | None = None):
    from mcp import ClientSession
    from mcp.client.sse import sse_client

    url = SERVER_URLS.get(server)
    if not url:
        raise ValueError(f"未知 MCP 服务: {server}")
    limit = timeout_sec if timeout_sec is not None else MCP_CONNECT_TIMEOUT_SEC

    async def _run() -> Any:
        async with sse_client(url) as streams:
            async with ClientSession(streams[0], streams[1]) as session:
                await session.initialize()
                return await fn(session)

    return await asyncio.wait_for(_run(), timeout=limit)


async def _call_tool_async(server: str, tool: str, arguments: dict[str, Any]) -> str:
    async def _do(session):
        result = await session.call_tool(tool, arguments=arguments)
        texts: list[str] = []
        for block in result.content:
            if hasattr(block, "text") and block.text:
                texts.append(block.text)
        return "\n".join(texts) if texts else json.dumps({"ok": True})

    timeout = MCP_GEE_TOOL_TIMEOUT_SEC if server == "gee" and tool == "run_analysis" else MCP_CONNECT_TIMEOUT_SEC
    return await _with_session(server, _do, timeout_sec=timeout)


def call_tool(full_name: str, arguments: dict[str, Any] | None = None) -> str:
    """同步调用 MCP 工具（供 LangGraph 节点使用）。"""
    server, tool = parse_tool_name(full_name)
    args = arguments or {}
    try:
        return asyncio.run(_call_tool_async(server, tool, args))
    except Exception as e:
        return json.dumps({"ok": False, "error": _format_call_error(e)}, ensure_ascii=False)


async def _list_tools_async() -> list[dict[str, Any]]:
    from mcp import ClientSession
    from mcp.client.sse import sse_client

    openai_tools: list[dict[str, Any]] = []
    for server, url in SERVER_URLS.items():
        try:

            async def _list_one() -> None:
                async with sse_client(url) as streams:
                    async with ClientSession(streams[0], streams[1]) as session:
                        await session.initialize()
                        listed = await session.list_tools()
                        for t in listed.tools:
                            full = f"{server}__{t.name}"
                            schema = (
                                t.inputSchema
                                if hasattr(t, "inputSchema")
                                else {"type": "object", "properties": {}}
                            )
                            openai_tools.append(
                                {
                                    "type": "function",
                                    "function": {
                                        "name": full,
                                        "description": t.description or f"{server} {t.name}",
                                        "parameters": schema,
                                    },
                                }
                            )

            await asyncio.wait_for(_list_one(), timeout=MCP_CONNECT_TIMEOUT_SEC)
        except Exception as e:
            print(f"[mcp] 跳过 {server}: {e}")
    return openai_tools


def get_openai_tools() -> list[dict[str, Any]]:
    global _TOOL_CACHE
    if _TOOL_CACHE is None:
        _TOOL_CACHE = asyncio.run(_list_tools_async())
    return _TOOL_CACHE


def refresh_openai_tools() -> list[dict[str, Any]]:
    """重新发现 MCP 工具；用于服务启动顺序导致首次发现为空时恢复。"""
    global _TOOL_CACHE
    _TOOL_CACHE = asyncio.run(_list_tools_async())
    return _TOOL_CACHE


def get_openai_tools_for_role(allowed_prefixes: list[str]) -> list[dict[str, Any]]:
    """按角色过滤工具，allowed_prefixes 如 ['gee__', 'storage__']。"""
    all_tools = get_openai_tools()
    if not all_tools:
        all_tools = refresh_openai_tools()
    out = []
    for t in all_tools:
        name = t.get("function", {}).get("name", "")
        if any(name.startswith(p) for p in allowed_prefixes):
            out.append(t)
    if not out:
        all_tools = refresh_openai_tools()
        for t in all_tools:
            name = t.get("function", {}).get("name", "")
            if any(name.startswith(p) for p in allowed_prefixes):
                out.append(t)
    return out
