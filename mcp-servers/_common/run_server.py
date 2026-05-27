"""统一启动 FastMCP SSE 服务。"""
from __future__ import annotations

import os
import sys


def run(mcp_app) -> None:
    host = os.getenv("MCP_HOST", "0.0.0.0")
    port = int(os.getenv("MCP_PORT", "8101"))
    print(f"[mcp] {mcp_app.name} SSE http://{host}:{port}/sse", flush=True)
    mcp_app.run(transport="sse", host=host, port=port)
