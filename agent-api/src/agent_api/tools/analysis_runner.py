"""
统一遥感分析入口：按意图调用 GEE（或演示模拟），生成报告与地图所需字段。
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Any
from urllib.parse import quote

from .gee_tools import NdciResult, RegionCoords, compute_ndci, compute_spectral_index
from .intent import detect_analysis_intent, extract_index_key
from .region_catalog import resolve_mask_polygon, resolve_region_name

TITILER_BASE = (os.getenv("TITILER_BASE_URL") or "http://localhost:8000").rstrip("/")

ANALYSIS_LABELS = {
    "cyanobacteria": "蓝藻/藻华",
    "spectral_index": "光谱指数",
    "cropland_change": "耕地面积变化",
    "water_area_change": "\u6c34\u4f53\u9762\u79ef\u53d8\u5316",
    "ndci": "NDCI 植被-水体指数",
    "water": "水体监测",
    "general": "综合遥感分析",
}


@dataclass
class AnalysisResult:
    ok: bool
    analysis_type: str
    cog_path: str
    download_url: str
    tile_url: str
    message: str
    report_title: str
    report_summary: str
    metrics: dict[str, Any]
    meta: dict[str, Any]


def _polygon_geojson(name: str, coords: RegionCoords | None) -> dict[str, Any] | None:
    if not coords:
        return None
    ring = [[float(lng), float(lat)] for lng, lat in coords]  # type: ignore[misc]
    if ring and ring[0] != ring[-1]:
        ring.append(ring[0])
    return {
        "type": "Feature",
        "properties": {"name": name, "kind": "analysis_mask"},
        "geometry": {"type": "Polygon", "coordinates": [ring]},
    }


def _use_named_lake_mask() -> bool:
    return (os.getenv("GEE_USE_NAMED_LAKE_MASK") or "false").strip().lower() in ("1", "true", "yes", "on")


def _bbox_area_km2(region: RegionCoords) -> float:
    if len(region) >= 4 and all(isinstance(x, (int, float)) for x in region[:4]):
        min_lng, min_lat, max_lng, max_lat = [float(region[i]) for i in range(4)]
        w = (max_lng - min_lng) * 96.0
        h = (max_lat - min_lat) * 111.0
        return round(max(w * h, 1.0), 1)
    return 120.0


def _build_tile_url(download_url: str) -> str:
    if not download_url or not download_url.startswith("http"):
        return ""
    if download_url.lower().startswith("gs://"):
        return ""
    # NDCI 是 float 单波段；TiTiler 默认按 float64 类型范围拉伸会几乎不可见。
    # 固定可视化范围让前端第一次加载就能看到分析影像。
    params = "rescale=-0.5,0.9&colormap_name=viridis"
    return f"{TITILER_BASE}/cog/tiles/WebMercatorQuad/{{z}}/{{x}}/{{y}}?url={quote(download_url, safe='')}&{params}"


def _metrics_from_gee_meta(meta: dict[str, Any], region: RegionCoords, start: str, end: str) -> dict[str, Any]:
    """从 GEE 服务端统计字段组装报告指标。"""
    study = meta.get("study_area_km2")
    if study is None:
        study = _bbox_area_km2(region)
    return {
        "study_area_km2": study,
        "estimated_bloom_area_km2": meta.get("estimated_bloom_area_km2"),
        "bloom_ratio_percent": meta.get("bloom_ratio_percent"),
        "mean_ndci": meta.get("mean_ndci"),
        "period": f"{start} ~ {end}",
        "data_source": "Sentinel-2 SR + GEE",
        "mask": meta.get("mask"),
        "mask_polygon": meta.get("mask_polygon"),
        "water_occurrence_min_percent": meta.get("water_occurrence_min_percent"),
        "simulated": False,
        "storage": meta.get("storage"),
        "export_mode": meta.get("export_mode"),
    }


def _index_threshold(index_key: str) -> tuple[str, float, bool]:
    spec = {
        "NDVI": (">= 0.3", 0.3, True),
        "SAVI": (">= 0.3", 0.3, True),
        "NDWI": (">= 0.2", 0.2, True),
        "NDBI": (">= 0.1", 0.1, True),
        "NBR": ("<= 0.1", 0.1, False),
    }
    return spec.get((index_key or "NDVI").upper(), spec["NDVI"])


def _metrics_from_index_meta(meta: dict[str, Any], region: RegionCoords, start: str, end: str, index_key: str) -> dict[str, Any]:
    threshold_label, _, _ = _index_threshold(index_key)
    return {
        "min": meta.get("min"),
        "max": meta.get("max"),
        "mean": meta.get("mean"),
        "valid_pixel_percent": meta.get("valid_pixel_percent"),
        "study_area_km2": _bbox_area_km2(region),
        "threshold": threshold_label,
        "period": f"{start} ~ {end}",
        "data_source": "Sentinel-2 SR + GEE",
        "source_kind": "gee",
        "resolution_m": meta.get("export_scale_m"),
        "image_count": meta.get("image_count"),
        "index_key": index_key,
        "simulated": False,
    }


def _format_spectral_index_report(
    place_hint: str,
    start: str,
    end: str,
    metrics: dict[str, Any],
    engineer_message: str,
    index_key: str,
) -> tuple[str, str]:
    label = {
        "NDVI": "植被指数",
        "NDWI": "水体指数",
        "NDBI": "建筑指数",
        "NBR": "火烧指数",
        "SAVI": "土壤调节植被指数",
    }.get(index_key, "光谱指数")
    title = f"{place_hint}{index_key}{label}分析报告"
    body = f"""## 摘要

基于 {metrics.get('period', f'{start} ~ {end}')} 的 Sentinel-2 SR 影像，对 **{place_hint}** 执行 **{index_key}（{label}）** 分析。

## 指标统计

| 指标 | 数值 |
|------|------|
| 数据源 | gee |
| 分辨率 | {metrics.get('resolution_m', '—')} m |
| 最小值 | {metrics.get('min', '—')} |
| 最大值 | {metrics.get('max', '—')} |
| 均值 | {metrics.get('mean', '—')} |
| 有效像素比例 | {metrics.get('valid_pixel_percent', '—')}% |
| 研究区面积 | {metrics.get('study_area_km2', '—')} km² |
| 阈值规则 | {metrics.get('threshold', '—')} |

## 工程说明

{engineer_message}
"""
    return title, body


def _format_report(
    intent: str,
    place_hint: str,
    start: str,
    end: str,
    metrics: dict[str, Any],
    engineer_message: str,
    simulated: bool,
) -> tuple[str, str]:
    label = ANALYSIS_LABELS.get(intent, intent)
    title = f"{place_hint}{label}分析报告"
    sim_note = "" if not simulated else "（GEE 未成功导出，以下为估算/占位指标）"

    if intent == "cyanobacteria":
        bloom = metrics.get("estimated_bloom_area_km2", "—")
        ratio = metrics.get("bloom_ratio_percent", "—")
        body = f"""## 摘要{sim_note}

基于 {metrics.get('period', f'{start} ~ {end}')} 的 Sentinel-2 影像，对**{place_hint}**开展蓝藻/藻华遥感监测。

## 主要结论

| 指标 | 数值 |
|------|------|
| 研究区面积 | {metrics.get('study_area_km2', '—')} km² |
| 估算藻华面积 | **{bloom} km²** |
| 藻华占比 | {ratio}% |
| 平均 NDCI | {metrics.get('mean_ndci', '—')} |

## 方法说明

- 使用 **NDCI**（Red-edge 与 Red 归一化差）识别高叶绿素/藻华像元；
- 先用 JRC Global Surface Water 水体掩膜限制水域，岸上植被不参与统计；
- 云量过滤后取时间中值合成，减少单景云污染；
- 矢量边界与瓦片图层可在右侧地图查看。

## 工程说明

{engineer_message}
"""
    else:
        body = f"""## 摘要{sim_note}

已完成 **{label}**（{metrics.get('period', f'{start} ~ {end}')}）。

## 指标

- 研究区面积约 {metrics.get('study_area_km2', '—')} km²
- 平均 NDCI：{metrics.get('mean_ndci', '—')}

## 工程说明

{engineer_message}
"""
    return title, body


def run_remote_sensing_analysis(
    user_message: str,
    region_coords: RegionCoords,
    start_date: str,
    end_date: str,
    analysis_type: str | None = None,
) -> AnalysisResult:
    """执行 GEE 分析并组装前端报告/地图字段。"""
    intent = analysis_type or detect_analysis_intent(user_message)
    place = resolve_region_name(user_message, list(region_coords) if isinstance(region_coords, list) else None) or "目标区域"
    mask_name, mask_polygon = resolve_mask_polygon(user_message, list(region_coords) if isinstance(region_coords, list) else None)
    if not _use_named_lake_mask():
        mask_name, mask_polygon = None, None

    if intent == "spectral_index":
        index_key = extract_index_key(user_message) or "NDVI"
        ndci = compute_spectral_index(region_coords, start_date, end_date, index_key)
        simulated = not ndci.ok or bool(ndci.meta.get("simulated"))
        metrics = _metrics_from_index_meta(ndci.meta, region_coords, start_date, end_date, index_key) if ndci.ok and not simulated else {
            "study_area_km2": _bbox_area_km2(region_coords),
            "period": f"{start_date} ~ {end_date}",
            "simulated": True,
            "data_source": "Sentinel-2 SR（GEE 导出失败或未配置凭据）",
            "index_key": index_key,
        }
        tile_url = _build_tile_url(ndci.download_url) if ndci.ok else ""
        title, summary = _format_spectral_index_report(place, start_date, end_date, metrics, ndci.message, index_key)
        return AnalysisResult(
            ok=ndci.ok,
            analysis_type=intent,
            cog_path=ndci.cog_path,
            download_url=ndci.download_url,
            tile_url=tile_url,
            message=ndci.message,
            report_title=title,
            report_summary=summary,
            metrics=metrics,
            meta={**ndci.meta, "analysis_type": intent, "place": place, "source_kind": "gee", "index_key": index_key},
        )

    ndci: NdciResult = compute_ndci(region_coords, start_date, end_date, mask_coords=mask_polygon, mask_label=mask_name)
    simulated = not ndci.ok or bool(ndci.meta.get("simulated"))

    if ndci.ok and not simulated:
        metrics = _metrics_from_gee_meta(ndci.meta, region_coords, start_date, end_date)
    else:
        metrics = {
            "study_area_km2": _bbox_area_km2(region_coords),
            "mean_ndci": None,
            "period": f"{start_date} ~ {end_date}",
            "simulated": True,
            "data_source": "Sentinel-2 SR（GEE 导出失败或未配置凭据）",
        }

    tile_url = _build_tile_url(ndci.download_url) if ndci.ok else ""
    title, summary = _format_report(
        intent,
        place,
        start_date,
        end_date,
        metrics,
        ndci.message,
        simulated,
    )

    return AnalysisResult(
        ok=ndci.ok,
        analysis_type=intent,
        cog_path=ndci.cog_path,
        download_url=ndci.download_url,
        tile_url=tile_url,
        message=ndci.message,
        report_title=title,
        report_summary=summary,
        metrics=metrics,
        meta={
            **ndci.meta,
            "analysis_type": intent,
            "place": place,
            "geojson": _polygon_geojson(f"{mask_name}湖体边界" if mask_name else place, mask_polygon),
        },
    )
