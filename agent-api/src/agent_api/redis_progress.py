"""Redis 任务进度发布（环境变量配置连接）。"""

from __future__ import annotations

import json
import os
from typing import Any

import redis

REDIS_URL = os.getenv("REDIS_URL", "redis://127.0.0.1:6379/0")
REDIS_KEY_PREFIX = os.getenv("REDIS_KEY_PREFIX", "agent:task:")
REDIS_PUBSUB_CHANNEL_PREFIX = os.getenv("REDIS_PUBSUB_CHANNEL_PREFIX", "task:")
REDIS_TTL_SECONDS = int(os.getenv("REDIS_TTL_SECONDS", "86400"))


def pubsub_channel(task_id: str) -> str:
    """Spring RedisMessageSubscriber 订阅 task:* 的频道名。"""
    return f"{REDIS_PUBSUB_CHANNEL_PREFIX}{task_id}"


def task_key(task_id: str) -> str:
    return f"{REDIS_KEY_PREFIX}{task_id}"


def _client() -> redis.Redis:
    return redis.from_url(REDIS_URL, decode_responses=True)


def publish_progress(task_id: str, node: str, message: str, extra: dict[str, Any] | None = None) -> None:
    """合并写入任务 JSON，并追加 progress 轨迹；同时 PUBLISH 到 task:{id} 供 Spring 订阅。"""
    r = _client()
    raw = r.get(task_key(task_id))
    doc: dict[str, Any] = json.loads(raw) if raw else {"task_id": task_id}
    trail = doc.get("progress") or []
    if not isinstance(trail, list):
        trail = []
    entry = {"node": node, "message": message}
    if extra:
        entry.update(extra)
    trail.append(entry)
    doc["progress"] = trail
    doc["current_node"] = node
    doc["last_message"] = message
    if extra:
        doc.update({k: v for k, v in extra.items() if k not in ("progress",)})
    _save_and_publish(task_id, doc)


def _save_and_publish(task_id: str, doc: dict[str, Any]) -> None:
    payload = json.dumps(doc, ensure_ascii=False)
    r = _client()
    r.set(task_key(task_id), payload, ex=REDIS_TTL_SECONDS)
    r.publish(pubsub_channel(task_id), payload)


def init_task(task_id: str, payload: dict[str, Any]) -> None:
    _save_and_publish(task_id, payload)


def read_task(task_id: str) -> dict[str, Any] | None:
    raw = _client().get(task_key(task_id))
    if not raw:
        return None
    return json.loads(raw)


def finalize_task(task_id: str, **fields: Any) -> None:
    doc = read_task(task_id) or {"task_id": task_id}
    doc.update(fields)
    _save_and_publish(task_id, doc)
