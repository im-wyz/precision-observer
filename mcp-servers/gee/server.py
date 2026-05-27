"""GEE MCP：区域解析、蓝藻/NDCI 分析（复用 agent-api 包内工具）。"""
from __future__ import annotations

import json
import os
import sys

_HERE = os.path.abspath(os.path.dirname(__file__))
for _candidate in (
    os.path.join(_HERE, "agent-api", "src"),  # Docker 内路径：/app/agent-api/src
    os.path.abspath(os.path.join(_HERE, "..", "..", "agent-api", "src")),  # 本地源码树
):
    if os.path.isdir(_candidate) and _candidate not in sys.path:
        sys.path.insert(0, _candidate)
        break

from mcp.server.fastmcp import FastMCP

from agent_api.tools.analysis_runner import run_remote_sensing_analysis
from agent_api.tools.intent import detect_analysis_intent
from agent_api.tools.region_catalog import resolve_region_coords

_mcp_host = os.getenv("MCP_HOST", "0.0.0.0")
_mcp_port = int(os.getenv("MCP_PORT", "8101"))
mcp = FastMCP("gee-mcp", host=_mcp_host, port=_mcp_port)


@mcp.tool()
def resolve_region(user_message: str, region_coords_json: str = "[]") -> str:
    """从用户文案或 bbox JSON 解析 WGS84 范围 [min_lng, min_lat, max_lng, max_lat]。"""
    try:
        coords = json.loads(region_coords_json) if region_coords_json else []
    except json.JSONDecodeError:
        coords = []
    bbox = resolve_region_coords(user_message, coords)
    return json.dumps({"bbox": bbox, "place_hint": user_message[:80]}, ensure_ascii=False)


@mcp.tool()
def detect_intent(user_message: str) -> str:
    """识别分析类型：cyanobacteria | ndci | water | general。"""
    intent = detect_analysis_intent(user_message)
    return json.dumps({"analysis_type": intent}, ensure_ascii=False)


@mcp.tool()
def run_analysis(
    user_message: str,
    region_coords_json: str,
    start_date: str,
    end_date: str,
    analysis_type: str = "",
) -> str:
    """
    执行遥感分析（GEE 或演示模拟）。region_coords_json 为 bbox 数组 JSON。
    返回 report_title、report_summary、metrics、cog_path、download_url、tile_url 等。
    """
    try:
        region = json.loads(region_coords_json)
    except json.JSONDecodeError:
        region = resolve_region_coords(user_message, [])
    atype = analysis_type.strip() or detect_analysis_intent(user_message)
    result = run_remote_sensing_analysis(user_message, region, start_date, end_date, analysis_type=atype)
    return json.dumps(
        {
            "ok": result.ok,
            "analysis_type": result.analysis_type,
            "message": result.message,
            "cog_path": result.cog_path,
            "download_url": result.download_url,
            "tile_url": result.tile_url,
            "report_title": result.report_title,
            "report_summary": result.report_summary,
            "metrics": result.metrics,
            "meta": result.meta,
        },
        ensure_ascii=False,
    )


@mcp.tool()
def list_supported_scenarios() -> str:
    """返回优先支持的场景列表（蓝藻、耕地、地物）。"""
    return json.dumps(
        {
            "priority": [
                {"id": "cyanobacteria", "label": "蓝藻/藻华", "example": "分析太湖蓝藻面积"},
                {"id": "cropland", "label": "耕地变化", "note": "耕地场景可走 Spring /api/agent/chat 或后续 cropland 工具"},
                {"id": "features", "label": "地物提取", "note": "通过 inference-mcp / Roboflow"},
            ]
        },
        ensure_ascii=False,
    )


if __name__ == "__main__":
    sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
    from _common.run_server import run

    run(mcp)
