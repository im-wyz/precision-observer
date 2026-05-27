"""Storage MCP：MinIO 对象存储与 PostgreSQL 任务元数据。"""
from __future__ import annotations

import io
import json
import os
from datetime import timedelta
from typing import Any

from minio import Minio
from mcp.server.fastmcp import FastMCP

_mcp_host = os.getenv("MCP_HOST", "0.0.0.0")
_mcp_port = int(os.getenv("MCP_PORT", "8104"))
mcp = FastMCP("storage-mcp", host=_mcp_host, port=_mcp_port)

ENDPOINT = os.getenv("MINIO_ENDPOINT", "minio:9000")
ACCESS = os.getenv("MINIO_ACCESS_KEY", "minioadmin")
SECRET = os.getenv("MINIO_SECRET_KEY", "minioadmin")
BUCKET = os.getenv("MINIO_BUCKET", "precision-observer")
SECURE = os.getenv("MINIO_SECURE", "false").lower() in ("1", "true", "yes")
PUBLIC_BASE = (os.getenv("MINIO_PUBLIC_BASE") or "http://127.0.0.1:9000").rstrip("/")
POSTGRES_HOST = os.getenv("POSTGRES_HOST", "postgres")
POSTGRES_PORT = int(os.getenv("POSTGRES_PORT", "5432"))
POSTGRES_DB = os.getenv("POSTGRES_DB", "gis_db")
POSTGRES_USER = os.getenv("POSTGRES_USER", "postgres")
POSTGRES_PASSWORD = os.getenv("POSTGRES_PASSWORD", "123456")


def _client() -> Minio:
    return Minio(ENDPOINT, access_key=ACCESS, secret_key=SECRET, secure=SECURE)


def _json_safe(value: Any) -> Any:
    """把数据库返回值转成 JSON 可序列化对象。"""
    if hasattr(value, "isoformat"):
        return value.isoformat()
    return value


def _pg_connect():
    try:
        import psycopg
        from psycopg.rows import dict_row
    except ImportError as exc:
        raise RuntimeError("storage-mcp 缺少 psycopg，请重新构建 MCP 镜像") from exc

    return psycopg.connect(
        host=POSTGRES_HOST,
        port=POSTGRES_PORT,
        dbname=POSTGRES_DB,
        user=POSTGRES_USER,
        password=POSTGRES_PASSWORD,
        row_factory=dict_row,
        connect_timeout=5,
    )


def _query_pg(sql: str, params: tuple[Any, ...]) -> list[dict[str, Any]]:
    with _pg_connect() as conn:
        with conn.cursor() as cur:
            cur.execute(sql, params)
            rows = cur.fetchall()
    return [{k: _json_safe(v) for k, v in row.items()} for row in rows]


@mcp.tool()
def put_json(object_key: str, data_json: str) -> str:
    """写入 JSON 对象到 MinIO，键如 tasks/{task_id}/report.json。"""
    client = _client()
    if not client.bucket_exists(BUCKET):
        client.make_bucket(BUCKET)
    payload = data_json.encode("utf-8")
    client.put_object(BUCKET, object_key, io.BytesIO(payload), len(payload), content_type="application/json")
    url = f"{PUBLIC_BASE}/{BUCKET}/{object_key}"
    return json.dumps({"ok": True, "object_key": object_key, "url": url}, ensure_ascii=False)


@mcp.tool()
def get_json(object_key: str) -> str:
    """读取 MinIO 上的 JSON 对象。"""
    client = _client()
    try:
        resp = client.get_object(BUCKET, object_key)
        data = resp.read().decode("utf-8")
        resp.close()
        return json.dumps({"ok": True, "data": json.loads(data)}, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"ok": False, "error": str(e)}, ensure_ascii=False)


@mcp.tool()
def presigned_get_url(object_key: str, expires_hours: int = 24) -> str:
    """生成预签名下载 URL（本机演示用）。"""
    client = _client()
    url = client.presigned_get_object(BUCKET, object_key, expires=timedelta(hours=expires_hours))
    return json.dumps({"ok": True, "url": url}, ensure_ascii=False)


@mcp.tool()
def get_task_metadata(task_id: str) -> str:
    """从 PostgreSQL analysis_task 表读取单个任务元数据。"""
    if not task_id or not task_id.strip():
        return json.dumps({"ok": False, "error": "task_id 不能为空"}, ensure_ascii=False)
    try:
        rows = _query_pg(
            """
            SELECT id, message, region_coords, start_date, end_date, status,
                   answer, cog_path, download_url, error_message, created_at, updated_at
            FROM analysis_task
            WHERE id = %s
            """,
            (task_id.strip(),),
        )
        if not rows:
            return json.dumps({"ok": False, "error": "任务不存在", "task_id": task_id}, ensure_ascii=False)
        return json.dumps({"ok": True, "task": rows[0]}, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"ok": False, "error": str(e)}, ensure_ascii=False)


@mcp.tool()
def list_recent_tasks(limit: int = 20, status: str = "") -> str:
    """从 PostgreSQL 读取最近任务，供 Director/Inspector 查询任务库。"""
    safe_limit = max(1, min(int(limit or 20), 100))
    try:
        if status and status.strip():
            rows = _query_pg(
                """
                SELECT id, message, status, download_url, created_at, updated_at
                FROM analysis_task
                WHERE status = %s
                ORDER BY updated_at DESC
                LIMIT %s
                """,
                (status.strip(), safe_limit),
            )
        else:
            rows = _query_pg(
                """
                SELECT id, message, status, download_url, created_at, updated_at
                FROM analysis_task
                ORDER BY updated_at DESC
                LIMIT %s
                """,
                (safe_limit,),
            )
        return json.dumps({"ok": True, "tasks": rows}, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"ok": False, "error": str(e)}, ensure_ascii=False)


@mcp.tool()
def get_scenario_catalog() -> str:
    """知识库：优先场景说明。"""
    return json.dumps(
        {
            "scenarios": [
                {"priority": 1, "id": "cyanobacteria", "tools": ["gee.run_analysis"], "indices": ["NDCI"]},
                {"priority": 2, "id": "cropland", "note": "可扩展为 gee/gdal/inference 专用 MCP 工具"},
                {"priority": 3, "id": "features", "tools": ["inference.run_roboflow_workflow"]},
            ]
        },
        ensure_ascii=False,
    )


if __name__ == "__main__":
    import sys

    sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
    from _common.run_server import run

    run(mcp)
