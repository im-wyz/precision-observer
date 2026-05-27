"""GDAL MCP：COG 信息、格式转换占位（Docker 内 gdal 命令）。"""
from __future__ import annotations

import json
import os
import subprocess
import tempfile
import uuid

from mcp.server.fastmcp import FastMCP

_mcp_host = os.getenv("MCP_HOST", "0.0.0.0")
_mcp_port = int(os.getenv("MCP_PORT", "8102"))
mcp = FastMCP("gdal-mcp", host=_mcp_host, port=_mcp_port)


def _gdal_available() -> bool:
    try:
        subprocess.run(["gdalinfo", "--version"], capture_output=True, check=True, timeout=10)
        return True
    except (subprocess.CalledProcessError, FileNotFoundError, subprocess.TimeoutExpired):
        return False


@mcp.tool()
def gdal_info(source_path: str) -> str:
    """对本地或 /vsicurl/ 路径执行 gdalinfo，返回摘要。"""
    if not _gdal_available():
        return json.dumps({"ok": False, "message": "容器内未安装 gdalinfo"}, ensure_ascii=False)
    try:
        out = subprocess.check_output(["gdalinfo", source_path], stderr=subprocess.STDOUT, timeout=120, text=True)
        return json.dumps({"ok": True, "summary": out[:8000]}, ensure_ascii=False)
    except subprocess.CalledProcessError as e:
        return json.dumps({"ok": False, "message": str(e.output)[:2000]}, ensure_ascii=False)


@mcp.tool()
def translate_to_cog_placeholder(local_geotiff_path: str, output_basename: str = "") -> str:
    """
    演示：生成 COG 输出路径占位。真实环境应 gdal_translate -of COG 后写入 MinIO。
    GEE 默认已可下载到本地/MinIO；本工具用于后续 COG 优化或 GCS 中转文件落地。
    """
    name = output_basename or f"cog_{uuid.uuid4().hex[:12]}.tif"
    key = f"artifacts/{name}"
    cmd = f"gdal_translate -of COG -co COMPRESS=DEFLATE {local_geotiff_path} /tmp/{name}"
    return json.dumps(
        {
            "ok": True,
            "simulated": True,
            "minio_object_key": key,
            "suggested_command": cmd,
            "message": "未执行真实转换；配置 GDAL 数据路径后可扩展为真实 translate",
        },
        ensure_ascii=False,
    )


@mcp.tool()
def health() -> str:
    return json.dumps({"gdal": _gdal_available()}, ensure_ascii=False)


if __name__ == "__main__":
    import sys

    sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
    from _common.run_server import run

    run(mcp)
