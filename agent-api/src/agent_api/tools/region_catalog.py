"""地名 → 分析范围 bbox [min_lng, min_lat, max_lng, max_lat]（WGS84）。"""

from __future__ import annotations

from typing import Any

# 常用水体/城市示意范围，可按业务扩展
NAMED_REGIONS: dict[str, list[float]] = {
    "太湖": [119.80, 30.95, 120.55, 31.55],
    "巢湖": [117.55, 31.35, 118.05, 31.85],
    "鄱阳湖": [115.80, 28.50, 117.00, 29.90],
    "滇池": [102.55, 24.70, 102.85, 25.10],
    "洪泽湖": [118.20, 33.10, 118.90, 33.60],
    "南京": [118.40, 31.90, 119.10, 32.25],
    "上海": [121.10, 30.90, 121.90, 31.50],
    "杭州": [119.90, 30.00, 120.50, 30.50],
}


def _is_bbox(coords: list[Any]) -> bool:
    return len(coords) >= 4 and all(isinstance(x, (int, float)) for x in coords[:4])


def resolve_region_coords(user_message: str, region_coords: list[Any] | None) -> list[float]:
    """
    若请求未带坐标，从自然语言中匹配地名；默认太湖（本系统示范场景）。
    """
    if region_coords and _is_bbox(list(region_coords)):
        return [float(region_coords[i]) for i in range(4)]

    text = (user_message or "").strip()
    for name, bbox in NAMED_REGIONS.items():
        if name in text:
            return bbox

    if any(k in text for k in ("湖", "藻", "水体", "水域", "水库")):
        return NAMED_REGIONS["太湖"]

    return NAMED_REGIONS["太湖"]
