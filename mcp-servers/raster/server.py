"""Raster MCP：TiTiler 瓦片模板、COG 可达性检查。"""
from __future__ import annotations

import json
import os
from urllib.parse import quote

import httpx
from mcp.server.fastmcp import FastMCP

_mcp_host = os.getenv("MCP_HOST", "0.0.0.0")
_mcp_port = int(os.getenv("MCP_PORT", "8103"))
mcp = FastMCP("raster-mcp", host=_mcp_host, port=_mcp_port)
TITILER_PUBLIC = (
    os.getenv("TITILER_PUBLIC_BASE_URL")
    or os.getenv("TITILER_BASE_URL")
    or "http://127.0.0.1:8000"
).rstrip("/")
TITILER_INTERNAL = (
    os.getenv("TITILER_INTERNAL_URL")
    or os.getenv("TITILER_BASE_URL")
    or "http://host.docker.internal:8000"
).rstrip("/")


@mcp.tool()
def build_tile_template(cog_http_url: str) -> str:
    """由 COG HTTP URL 生成 TiTiler WebMercatorQuad 瓦片模板。"""
    if not cog_http_url.startswith("http"):
        return json.dumps({"ok": False, "message": "需要 http(s) COG URL"}, ensure_ascii=False)
    if "mock-cog" in cog_http_url.lower():
        return json.dumps({"ok": False, "message": "mock-cog 无法被 TiTiler 读取", "tile_url": ""}, ensure_ascii=False)
    enc = quote(cog_http_url, safe="")
    tpl = f"{TITILER_PUBLIC}/cog/tiles/WebMercatorQuad/{{z}}/{{x}}/{{y}}?url={enc}&rescale=-0.5,0.9&colormap_name=viridis"
    return json.dumps({"ok": True, "tile_url": tpl, "titiler_base": TITILER_PUBLIC}, ensure_ascii=False)


@mcp.tool()
def check_cog_reachable(cog_http_url: str) -> str:
    """请求 TiTiler /cog/info 检查 COG 是否可读。"""
    if "mock-cog" in cog_http_url.lower():
        return json.dumps({"ok": False, "reachable": False, "reason": "mock URL"}, ensure_ascii=False)
    url = f"{TITILER_INTERNAL}/cog/info"
    try:
        with httpx.Client(timeout=60.0) as client:
            r = client.get(url, params={"url": cog_http_url})
        return json.dumps(
            {
                "ok": r.is_success,
                "status": r.status_code,
                "titiler_internal": TITILER_INTERNAL,
                "body_preview": r.text[:500],
            },
            ensure_ascii=False,
        )
    except Exception as e:
        return json.dumps({"ok": False, "error": str(e)}, ensure_ascii=False)


if __name__ == "__main__":
    import sys

    sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
    from _common.run_server import run

    run(mcp)
