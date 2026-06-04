"""统一启动 FastMCP SSE 服务。"""
from __future__ import annotations

import os
import sys


def run(mcp_app) -> None:
    host = os.getenv("MCP_HOST", "0.0.0.0")
    port = int(os.getenv("MCP_PORT", "8101"))
    print(f"[mcp] {mcp_app.name} SSE http://{host}:{port}/sse", flush=True)
    # host/port 已在各 server.py 的 FastMCP(...) 构造函数中设置。
    # 新版 mcp 的 FastMCP.run() 只接收 transport，继续传 host/port 会直接启动失败。
    mcp_app.run(transport="sse")
