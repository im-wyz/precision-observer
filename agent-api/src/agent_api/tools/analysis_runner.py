"""
统一遥感分析入口：按意图调用 GEE（或演示模拟），生成报告与地图所需字段。
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Any
from urllib.parse import quote

from .gee_tools import NdciResult, RegionCoords, compute_ndci
from .intent import detect_analysis_intent

TITILER_BASE = (os.getenv("TITILER_BASE_URL") or "http://localhost:8000").rstrip("/")

ANALYSIS_LABELS = {
    "cyanobacteria": "蓝藻/藻华",
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
    return f"{TITILER_BASE}/cog/tiles/WebMercatorQuad/{{z}}/{{x}}/{{y}}?url={quote(download_url, safe='')}"


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
        "simulated": False,
        "storage": meta.get("storage"),
        "export_mode": meta.get("export_mode"),
    }


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

- 使用水体敏感指数 **NDCI**（NIR 与 Red 归一化差）识别高叶绿素/藻华像元；
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
    place = "目标区域"
    for name in ("太湖", "巢湖", "鄱阳湖", "滇池", "洪泽湖", "南京", "上海", "杭州"):
        if name in (user_message or ""):
            place = name
            break

    ndci: NdciResult = compute_ndci(region_coords, start_date, end_date)
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
        meta={**ndci.meta, "analysis_type": intent, "place": place},
    )
