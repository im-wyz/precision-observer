"""Inference MCP：Roboflow 工作流代理（合并进 MCP，与 Spring 并存）。"""
from __future__ import annotations

import json
import os

import httpx
from mcp.server.fastmcp import FastMCP

_mcp_host = os.getenv("MCP_HOST", "0.0.0.0")
_mcp_port = int(os.getenv("MCP_PORT", "8105"))
mcp = FastMCP("inference-mcp", host=_mcp_host, port=_mcp_port)
SPRING = (os.getenv("SPRING_API_BASE_URL") or "http://host.docker.internal:8080").rstrip("/")


@mcp.tool()
def run_roboflow_workflow(instruction: str, cog_http_url: str, model_id: str = "") -> str:
    """
    调用 Spring POST /api/roboflow/workflow（需已上传 COG）。
    与前端 AI 工作区 Roboflow 路径并存。
    """
    body: dict = {"instruction": instruction, "cogHttpUrl": cog_http_url}
    if model_id:
        body["modelId"] = model_id
    url = f"{SPRING}/api/roboflow/workflow"
    try:
        with httpx.Client(timeout=300.0) as client:
            r = client.post(url, json=body)
        if not r.is_success:
            return json.dumps(
                {"ok": False, "status": r.status_code, "message": r.text[:1000]},
                ensure_ascii=False,
            )
        return json.dumps({"ok": True, "result_preview": r.text[:2000]}, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"ok": False, "error": str(e)}, ensure_ascii=False)


@mcp.tool()
def inference_status() -> str:
    """检查 Spring Roboflow 端点是否配置。"""
    try:
        with httpx.Client(timeout=8.0) as client:
            r = client.get(f"{SPRING}/api/roboflow/diagnostics", params={"cogHttpUrl": "http://127.0.0.1/test"})
        return json.dumps(
            {"spring_base": SPRING, "reachable": True, "note": "diagnostics 已响应", "status": r.status_code},
            ensure_ascii=False,
        )
    except Exception as e:
        return json.dumps({"spring_base": SPRING, "reachable": False, "error": str(e)}, ensure_ascii=False)


if __name__ == "__main__":
    import sys

    sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
    from _common.run_server import run

    run(mcp)
