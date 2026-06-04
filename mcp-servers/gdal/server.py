"""GDAL MCP：影像基础预处理、COG 信息与格式转换。"""
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
def build_preprocess_commands(
    source_path: str,
    output_path: str = "",
    operations_json: str = "",
    bbox_json: str = "",
    target_crs: str = "EPSG:4326",
    target_resolution: int = 10,
) -> str:
    """生成云掩膜、裁剪、重采样、重投影、COG 转换的标准 GDAL 命令。"""
    ops = _parse_ops(operations_json)
    if not ops:
        ops = ["cloud_mask", "clip", "resample", "reproject", "cog"]
    out = output_path or f"/tmp/preprocess_{uuid.uuid4().hex[:10]}.tif"
    bbox = _parse_bbox(bbox_json)
    commands = _build_commands(source_path, out, ops, bbox, target_crs, target_resolution)
    warnings = []
    if "cloud_mask" in ops:
        warnings.append("云掩膜需要 SCL/QA60 或云概率波段；GDAL 命令仅保留预处理链路，具体掩膜表达式需按数据集质量波段配置。")
    return json.dumps(
        {
            "ok": True,
            "operations": ops,
            "gdal_available": _gdal_available(),
            "commands": commands,
            "output_path": out,
            "warnings": warnings,
        },
        ensure_ascii=False,
    )


@mcp.tool()
def run_preprocess(
    source_path: str,
    output_path: str = "",
    operations_json: str = "",
    bbox_json: str = "",
    target_crs: str = "EPSG:4326",
    target_resolution: int = 10,
    execute: bool = False,
) -> str:
    """
    生成或执行基础预处理命令。
    execute=false 时只返回命令；execute=true 且容器内存在 GDAL 时才执行。
    """
    built = json.loads(build_preprocess_commands(source_path, output_path, operations_json, bbox_json, target_crs, target_resolution))
    if not execute:
        built["executed"] = False
        built["message"] = "已生成命令，未执行。"
        return json.dumps(built, ensure_ascii=False)
    if not _gdal_available():
        built.update({"ok": False, "executed": False, "message": "容器内未安装 GDAL，无法执行预处理。"})
        return json.dumps(built, ensure_ascii=False)
    try:
        logs = []
        for cmd in built["commands"]:
            completed = subprocess.run(cmd, capture_output=True, text=True, timeout=1800)
            logs.append((completed.stdout + completed.stderr)[-3000:])
            if completed.returncode != 0:
                built.update({"ok": False, "executed": True, "message": f"命令失败，退出码 {completed.returncode}", "logs": logs})
                return json.dumps(built, ensure_ascii=False)
        built.update({"ok": True, "executed": True, "message": "预处理完成", "logs": logs})
        return json.dumps(built, ensure_ascii=False)
    except Exception as exc:
        built.update({"ok": False, "executed": True, "message": str(exc)})
        return json.dumps(built, ensure_ascii=False)


@mcp.tool()
def health() -> str:
    return json.dumps({"gdal": _gdal_available()}, ensure_ascii=False)


def _parse_ops(raw: str) -> list[str]:
    if not raw:
        return []
    try:
        data = json.loads(raw)
        if isinstance(data, list):
            return [str(x) for x in data]
    except json.JSONDecodeError:
        pass
    return [x.strip() for x in raw.split(",") if x.strip()]


def _parse_bbox(raw: str) -> list[float] | None:
    if not raw:
        return None
    try:
        data = json.loads(raw)
        if isinstance(data, list) and len(data) == 4:
            return [float(x) for x in data]
        if isinstance(data, dict):
            return [float(data[k]) for k in ("minLng", "minLat", "maxLng", "maxLat")]
    except Exception:
        return None
    return None


def _build_commands(
    source_path: str,
    output_path: str,
    operations: list[str],
    bbox: list[float] | None,
    target_crs: str,
    target_resolution: int,
) -> list[list[str]]:
    needs_warp = any(op in operations for op in ("clip", "resample", "reproject"))
    commands: list[list[str]] = []
    needs_cog = "cog" in operations
    warp_output = output_path.replace(".tif", "_warp.tif") if needs_cog else output_path
    if needs_warp:
        cmd = ["gdalwarp", "-overwrite", "-multi", "-of", "GTiff"]
        if "clip" in operations and bbox:
            cmd += ["-te", *(str(x) for x in bbox), "-te_srs", "EPSG:4326"]
        if "reproject" in operations:
            cmd += ["-t_srs", target_crs or "EPSG:4326"]
        if "resample" in operations:
            tr_x, tr_y = _target_resolution_for_gdal(target_crs, target_resolution)
            cmd += ["-tr", _fmt_number(tr_x), _fmt_number(tr_y), "-r", "bilinear"]
        cmd += [source_path, warp_output]
        commands.append(cmd)
    if needs_cog:
        cog_input = warp_output if needs_warp else source_path
        commands.append(["gdal_translate", "-of", "COG", "-co", "COMPRESS=DEFLATE", "-co", "BIGTIFF=IF_SAFER", cog_input, output_path])
    return commands


def _target_resolution_for_gdal(target_crs: str, target_resolution: int) -> tuple[float, float]:
    meters = max(1, int(target_resolution or 10))
    crs = (target_crs or "EPSG:4326").upper()
    if crs == "EPSG:4326" or "WGS84" in crs:
        degrees = meters / 111_320.0
        return degrees, degrees
    return float(meters), float(meters)


def _fmt_number(value: float) -> str:
    if value.is_integer():
        return str(int(value))
    return f"{value:.10f}".rstrip("0").rstrip(".")


if __name__ == "__main__":
    import sys

    sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
    from _common.run_server import run

    run(mcp)
