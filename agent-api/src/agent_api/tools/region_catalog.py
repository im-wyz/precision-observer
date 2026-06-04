"""地名 → 分析范围 bbox [min_lng, min_lat, max_lng, max_lat]（WGS84）。

GEE 导出必须优先保证覆盖完整目标区域；湖体精细分割不在这里裁剪影像。
"""

from __future__ import annotations

from typing import Any

RegionShape = list[float]
RegionPolygon = list[list[float]]

# 常用水体/城市示意范围，可按业务扩展；水体 bbox 故意留出边缘，避免导出影像漏覆盖。
NAMED_BBOXES: dict[str, list[float]] = {
    "太湖": [119.70, 30.85, 120.70, 31.65],
    "巢湖": [117.55, 31.35, 118.05, 31.85],
    "鄱阳湖": [115.80, 28.50, 117.00, 29.90],
    "滇池": [102.55, 24.70, 102.85, 25.10],
    "洪泽湖": [118.20, 33.10, 118.90, 33.60],
    "南京": [118.40, 31.90, 119.10, 32.25],
    "上海": [121.10, 30.90, 121.90, 31.50],
    "杭州": [119.90, 30.00, 120.50, 30.50],
}

NAMED_MASK_POLYGONS: dict[str, RegionPolygon] = {
    "太湖": [
        [120.075, 31.535],
        [120.185, 31.525],
        [120.315, 31.475],
        [120.430, 31.390],
        [120.535, 31.255],
        [120.585, 31.120],
        [120.550, 30.995],
        [120.445, 30.915],
        [120.300, 30.880],
        [120.145, 30.905],
        [120.010, 30.980],
        [119.905, 31.105],
        [119.845, 31.250],
        [119.865, 31.385],
        [119.960, 31.490],
        [120.075, 31.535],
    ],
}


def resolve_region_name(user_message: str, region_coords: list[Any] | None = None) -> str | None:
    text = (user_message or "").strip()
    for name in NAMED_BBOXES:
        if name in text:
            return name

    if region_coords:
        coords = list(region_coords)
        bbox = [float(coords[i]) for i in range(4)] if _is_bbox(coords) else _points_to_bbox(coords)
        if bbox:
            for name, named_bbox in NAMED_BBOXES.items():
                if all(abs(float(bbox[i]) - float(named_bbox[i])) < 1e-6 for i in range(4)):
                    return name

    if any(k in text for k in ("湖", "藻", "水体", "水域", "水库")):
        return "太湖"
    return None


def resolve_mask_polygon(user_message: str, region_coords: list[Any] | None = None) -> tuple[str | None, RegionPolygon | None]:
    name = resolve_region_name(user_message, region_coords)
    if not name:
        return None, None
    return name, NAMED_MASK_POLYGONS.get(name)


def _is_bbox(coords: list[Any]) -> bool:
    return len(coords) >= 4 and all(isinstance(x, (int, float)) for x in coords[:4])


def _points_to_bbox(coords: list[Any]) -> list[float] | None:
    """把前端点列或湖体边界归一化为外接 bbox，避免 GEE 导出范围漏覆盖。"""
    points: list[tuple[float, float]] = []
    for item in coords:
        if not isinstance(item, (list, tuple)) or len(item) < 2:
            continue
        lng, lat = item[0], item[1]
        if isinstance(lng, (int, float)) and isinstance(lat, (int, float)):
            points.append((float(lng), float(lat)))
    if not points:
        return None
    lngs = [p[0] for p in points]
    lats = [p[1] for p in points]
    return [min(lngs), min(lats), max(lngs), max(lats)]


def resolve_region_coords(user_message: str, region_coords: list[Any] | None) -> RegionShape:
    """
    若请求未带坐标，从自然语言中匹配地名；默认太湖外接 bbox。
    """
    if region_coords:
        coords = list(region_coords)
        if _is_bbox(coords):
            return [float(coords[i]) for i in range(4)]
        bbox = _points_to_bbox(coords)
        if bbox:
            return bbox

    text = (user_message or "").strip()
    name = resolve_region_name(text, None)
    if name and name in NAMED_BBOXES:
        return NAMED_BBOXES[name]

    for name, bbox in NAMED_BBOXES.items():
        if name in text:
            return bbox

    if any(k in text for k in ("湖", "藻", "水体", "水域", "水库")):
        return NAMED_BBOXES["太湖"]

    return NAMED_BBOXES["太湖"]
