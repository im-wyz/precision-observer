"""调用 Spring 内部遥感分析服务的工具函数。"""

from __future__ import annotations

import calendar
import json
import os
import re
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


def spring_base_url() -> str:
    return os.getenv("SPRING_BASE_URL", os.getenv("RASTER_API_BASE_URL", "http://127.0.0.1:8080")).rstrip("/")


def run_spring_spectral_index_analysis(state: dict[str, Any]) -> dict[str, Any]:
    message = str(state.get("user_message") or "")
    payload = {
        "message": message,
        "place": _extract_place(message),
        "startDate": str(state.get("start_date") or ""),
        "endDate": str(state.get("end_date") or ""),
        "indexKey": str(state.get("index_key") or _extract_index_key(message) or "NDVI"),
        "preferLocal": True,
    }
    data = _post_json(f"{spring_base_url()}/api/internal/analysis/index", payload, timeout_sec=_timeout())
    return _normalize_internal_response(data, "spectral_index")


def run_spring_basic_analysis(state: dict[str, Any], analysis_type: str) -> dict[str, Any]:
    message = str(state.get("user_message") or "")
    payload = {
        "message": message,
        "place": _extract_place(message),
        "startDate": str(state.get("start_date") or ""),
        "endDate": str(state.get("end_date") or ""),
        "compareStartDate": str(state.get("compare_start_date") or ""),
        "compareEndDate": str(state.get("compare_end_date") or ""),
        "analysisType": analysis_type,
        "preferLocal": True,
    }
    data = _post_json(f"{spring_base_url()}/api/internal/analysis/basic", payload, timeout_sec=_timeout())
    return _normalize_internal_response(data, analysis_type)


def run_spring_cropland_analysis(state: dict[str, Any]) -> dict[str, Any]:
    message = str(state.get("user_message") or "")
    data = _post_json(f"{spring_base_url()}/api/agent/chat", {"message": message}, timeout_sec=_timeout())
    answer = str(data.get("answer") or data.get("message") or "")
    chart = data.get("chartOption") or data.get("option")
    cropland_data = data.get("data") if isinstance(data.get("data"), dict) else {}
    metrics = {
        "source_kind": "local-cog",
        "chart_available": bool(chart),
        "start_area_km2": cropland_data.get("startAreaKm2"),
        "end_area_km2": cropland_data.get("endAreaKm2"),
        "delta_area_km2": cropland_data.get("deltaAreaKm2"),
        "delta_percent": cropland_data.get("deltaPercent"),
    }
    change_layers = _build_cropland_change_layers(cropland_data) if cropland_data else None
    return {
        "ok": bool(answer),
        "analysis_type": "cropland_change",
        "source_kind": "local-cog",
        "download_url": "",
        "tile_url": "",
        "metrics": metrics,
        "report_title": "耕地面积变化分析报告",
        "report_summary": answer,
        "message": "耕地面积变化分析完成" if answer else "耕地面积变化分析未返回结果",
        "chartOption": chart,
        "cropland_data": cropland_data,
        "change_layers": change_layers,
        "meta": {"chartOption": chart, "cropland_data": cropland_data, "change_layers": change_layers, "spring_response": data},
    }


def _normalize_internal_response(data: dict[str, Any], analysis_type: str) -> dict[str, Any]:
    metrics = data.get("metrics") if isinstance(data.get("metrics"), dict) else {}
    extra = data.get("extra") if isinstance(data.get("extra"), dict) else {}
    meta = {
        "extent": data.get("extent"),
        "boundaries": data.get("boundaries"),
        "warnings": data.get("warnings"),
        **extra,
    }
    out = {
        "ok": bool(data.get("ok")),
        "analysis_type": data.get("analysisType") or analysis_type,
        "source_kind": data.get("sourceKind"),
        "source_scene_id": data.get("sourceSceneId"),
        "index_key": data.get("indexKey"),
        "band_map": data.get("bandMap"),
        "download_url": data.get("downloadUrl") or "",
        "tile_url": data.get("tileTemplateUrl") or "",
        "tileTemplateUrl": data.get("tileTemplateUrl") or "",
        "boundaries": data.get("boundaries") or [],
        "metrics": metrics,
        "report_title": data.get("reportTitle") or "",
        "report_summary": data.get("reportSummary") or "",
        "warnings": data.get("warnings") or [],
        "message": data.get("message") or data.get("reportSummary") or "",
        "meta": meta,
    }
    for key in ("chartOption", "cropland_data", "change_layers", "preprocess_steps", "gdal_commands"):
        if key in extra:
            out[key] = extra[key]
    return out


def _post_json(url: str, payload: dict[str, Any], timeout_sec: int) -> dict[str, Any]:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = Request(url, data=body, headers={"Content-Type": "application/json", "Accept": "application/json"}, method="POST")
    try:
        with urlopen(req, timeout=timeout_sec) as resp:
            raw = resp.read().decode("utf-8", errors="replace")
            return json.loads(raw) if raw else {}
    except HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace") if exc.fp else ""
        raise RuntimeError(f"Spring 内部接口失败 {exc.code}: {raw[:1000]}") from exc
    except URLError as exc:
        raise RuntimeError(f"Spring 内部接口不可达：{exc}") from exc


def _get_json(url: str, timeout_sec: int) -> dict[str, Any]:
    req = Request(url, headers={"Accept": "application/json"}, method="GET")
    try:
        with urlopen(req, timeout=timeout_sec) as resp:
            raw = resp.read().decode("utf-8", errors="replace")
            return json.loads(raw) if raw else {}
    except HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace") if exc.fp else ""
        raise RuntimeError(f"Spring 内部接口失败 {exc.code}: {raw[:1000]}") from exc
    except URLError as exc:
        raise RuntimeError(f"Spring 内部接口不可达：{exc}") from exc


def _build_cropland_change_layers(cropland_data: dict[str, Any]) -> dict[str, Any] | None:
    place = str(cropland_data.get("place") or "").strip()
    try:
        sy = int(cropland_data.get("startYear"))
        sm = int(cropland_data.get("startMonth"))
        ey = int(cropland_data.get("endYear"))
        em = int(cropland_data.get("endMonth"))
    except (TypeError, ValueError):
        return None
    if not place or not (1 <= sm <= 12 and 1 <= em <= 12):
        return None
    return {"mode": "split", "left": _query_live_layer(place, sy, sm), "right": _query_live_layer(place, ey, em)}


def _query_live_layer(place: str, year: int, month: int) -> dict[str, Any]:
    start = f"{year:04d}-{month:02d}-01"
    end = f"{year:04d}-{month:02d}-{calendar.monthrange(year, month)[1]:02d}"
    params = urlencode({"place": place, "start": start, "end": end, "provider": "local_gee"})
    data = _get_json(f"{spring_base_url()}/api/live-imagery/by-place?{params}", timeout_sec=_timeout())
    tile = str(data.get("tileTemplateUrl") or "")
    if tile.startswith("/"):
        tile = spring_base_url() + tile
    return {
        "tile_url": tile,
        "extent": {"minLng": data.get("minLng"), "minLat": data.get("minLat"), "maxLng": data.get("maxLng"), "maxLat": data.get("maxLat")},
        "boundaries": data.get("boundaries") or [],
        "scene_id": ((data.get("selectedScenes") or [{}])[0] or {}).get("itemId", ""),
        "start_date": start,
        "end_date": end,
    }


def _extract_index_key(message: str) -> str | None:
    upper = message.upper()
    for key in ("NDVI", "NDWI", "NDBI", "NBR", "SAVI"):
        if key in upper:
            return key
    if "水体" in message:
        return "NDWI"
    if "建筑" in message:
        return "NDBI"
    if "火烧" in message or "燃烧" in message:
        return "NBR"
    if "土壤调节" in message:
        return "SAVI"
    if "植被" in message or "绿度" in message:
        return "NDVI"
    return None


def _extract_place(message: str) -> str:
    for place in ("南京", "太湖", "巢湖", "鄱阳湖", "苏州", "无锡", "上海", "北京"):
        if place in message:
            return place
    m = re.search(r"([\u4e00-\u9fa5]{2,8})(?:市|区|县)?", message)
    return m.group(0) if m else ""


def _timeout() -> int:
    return int(os.getenv("SPRING_INTERNAL_TIMEOUT_SEC", "180"))
