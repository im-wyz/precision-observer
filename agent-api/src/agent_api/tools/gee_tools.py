"""
Google Earth Engine：按区域与时间计算 NDCI，导出到本地或 MinIO，返回 TiTiler 可读的 HTTP URL。

初级阶段：不建 GCS 桶，用 getDownloadURL 下载 GeoTIFF。
数据量大时：配置 GEE_EXPORT_BUCKET，走 Cloud Storage 异步导出（中转，需后续 GDAL/同步到 MinIO）。
"""

from __future__ import annotations

import json
import os
import time
import uuid
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Sequence
from urllib.request import Request, urlopen

_AGENT_API_ROOT = Path(__file__).resolve().parents[3]
_REPO_ROOT = _AGENT_API_ROOT.parent
_dotenv_loaded = False


def _ensure_dotenv() -> None:
    global _dotenv_loaded
    if _dotenv_loaded:
        return
    try:
        from dotenv import load_dotenv

        load_dotenv(_AGENT_API_ROOT / ".env")
    except ImportError:
        pass
    _dotenv_loaded = True

RegionCoords = Sequence[tuple[float, float]] | Sequence[float]


@dataclass
class NdciResult:
    """GEE 栅格导出结果。"""

    ok: bool
    cog_path: str
    download_url: str
    message: str
    meta: dict[str, Any]


def _credentials_path() -> str:
    _ensure_dotenv()
    raw = (os.getenv("GEE_CREDENTIALS_PATH") or "").strip()
    candidates: list[Path] = []
    if raw:
        p = Path(raw)
        candidates.append(p if p.is_absolute() else (_AGENT_API_ROOT / p).resolve())
    candidates.extend(
        [
            _REPO_ROOT / "secrets" / "gee-service-account.json",
            _AGENT_API_ROOT / "secrets" / "gee-service-account.json",
        ]
    )
    for c in candidates:
        if c.is_file():
            return str(c)
    return raw


def _output_dir() -> str:
    d = (os.getenv("GEE_OUTPUT_DIR") or "./data/gee_exports").strip()
    path = Path(d)
    resolved = path if path.is_absolute() else (_AGENT_API_ROOT / path).resolve()
    os.makedirs(resolved, exist_ok=True)
    return str(resolved)


def _region_summary(region: RegionCoords) -> str:
    if len(region) >= 4 and all(isinstance(x, (int, float)) for x in region[:4]):
        return f"bbox_{region[0]}_{region[1]}_{region[2]}_{region[3]}"
    return "polygon"


def _parse_dates(start: str, end: str) -> tuple[str, str]:
    for raw in (start, end):
        datetime.strptime(raw[:10], "%Y-%m-%d")
    return start[:10], end[:10]


def _ee_geometry(region: RegionCoords) -> Any:
    import ee  # type: ignore

    if len(region) >= 4 and all(isinstance(x, (int, float)) for x in region[:4]):
        min_lng, min_lat, max_lng, max_lat = [float(region[i]) for i in range(4)]
        return ee.Geometry.Rectangle([min_lng, min_lat, max_lng, max_lat])
    coords = [[float(lng), float(lat)] for lng, lat in region]  # type: ignore[misc]
    if coords[0] != coords[-1]:
        coords.append(coords[0])
    return ee.Geometry.Polygon([coords])


_ee_initialized = False


def _is_retryable_gee_network_error(exc: BaseException) -> bool:
    text = str(exc).lower()
    markers = (
        "ssl",
        "unexpected_eof",
        "connection",
        "timeout",
        "oauth2.googleapis.com",
        "max retries exceeded",
        "temporary failure",
        "connection reset",
    )
    return any(m in text for m in markers)


def _init_ee() -> tuple[Any, str]:
    """初始化 Earth Engine（进程内只初始化一次；网络抖动时自动重试）。"""
    global _ee_initialized
    import ee  # type: ignore

    cred = _credentials_path()
    if not cred or not os.path.isfile(cred):
        raise FileNotFoundError(f"未找到 GEE 凭据：{cred or '(未设置 GEE_CREDENTIALS_PATH)'}")
    with open(cred, encoding="utf-8") as f:
        sa = json.load(f)
    email = sa.get("client_email")
    if not email:
        raise ValueError("GEE JSON 缺少 client_email")

    if _ee_initialized:
        return ee, email

    last_err: BaseException | None = None
    for attempt in range(3):
        try:
            ee.Initialize(ee.ServiceAccountCredentials(email, key_file=cred))
            _ee_initialized = True
            return ee, email
        except Exception as e:
            last_err = e
            if attempt < 2 and _is_retryable_gee_network_error(e):
                time.sleep(2**attempt)
                continue
            raise
    if last_err:
        raise last_err
    raise RuntimeError("GEE 初始化失败")


def _sentinel2_collection(region_coords: RegionCoords, start: str, end: str, cloud_max: float = 30) -> tuple[Any, Any, Any]:
    ee, _ = _init_ee()
    geom = _ee_geometry(region_coords)
    collection = (
        ee.ImageCollection("COPERNICUS/S2_SR_HARMONIZED")
        .filterBounds(geom)
        .filterDate(start, end)
        .filter(ee.Filter.lt("CLOUDY_PIXEL_PERCENTAGE", cloud_max))
    )
    return ee, geom, collection


def _surface_water_mask(ee: Any, geom: Any) -> Any:
    occurrence_min = float(os.getenv("GEE_WATER_OCCURRENCE_MIN", "35"))
    occurrence = ee.Image("JRC/GSW1_4/GlobalSurfaceWater").select("occurrence")
    return occurrence.gte(occurrence_min).clip(geom)


def _polygon_mask(ee: Any, mask_coords: RegionCoords, geom: Any) -> Any:
    mask_geom = _ee_geometry(mask_coords)
    return ee.Image.constant(1).clip(mask_geom).selfMask().clip(geom)


def _build_ndci_image(
    region_coords: RegionCoords,
    start: str,
    end: str,
    mask_coords: RegionCoords | None = None,
) -> tuple[Any, Any, Any]:
    ee, geom, collection = _sentinel2_collection(region_coords, start, end, 30)
    water_mask = _surface_water_mask(ee, geom)
    if mask_coords:
        water_mask = water_mask.updateMask(_polygon_mask(ee, mask_coords, geom))

    def add_ndci(img: Any) -> Any:
        red_edge = img.select("B5").multiply(0.0001)
        red = img.select("B4").multiply(0.0001)
        ndci = red_edge.subtract(red).divide(red_edge.add(red)).rename("NDCI")
        return img.addBands(ndci)

    with_ndci = collection.map(add_ndci)
    median = with_ndci.select("NDCI").median().updateMask(water_mask).clip(geom)
    return ee, median, water_mask


def _build_true_color_image(region_coords: RegionCoords, start: str, end: str) -> tuple[Any, Any, Any, int]:
    cloud_max = float(os.getenv("GEE_TRUE_COLOR_CLOUD_MAX", "60"))
    ee, geom, collection = _sentinel2_collection(region_coords, start, end, cloud_max)
    image_count = int(collection.size().getInfo() or 0)
    if image_count <= 0:
        raise RuntimeError("当前时间范围内 GEE 未检索到 Sentinel-2 影像")
    image = (
        collection.sort("CLOUDY_PIXEL_PERCENTAGE")
        .limit(int(os.getenv("GEE_TRUE_COLOR_MAX_SCENES", "8")))
        .median()
        .select(["B4", "B3", "B2"])
        .visualize(min=0, max=int(os.getenv("GEE_TRUE_COLOR_MAX_VALUE", "3000")), gamma=1.2)
        .clip(geom)
    )
    return ee, image, geom, image_count


def _spectral_index_spec(index_key: str) -> tuple[str, list[str], str]:
    key = (index_key or "NDVI").upper()
    specs = {
        "NDVI": ("NDVI", ["B8", "B4"], "植被指数"),
        "NDWI": ("NDWI", ["B3", "B8"], "水体指数"),
        "NDBI": ("NDBI", ["B11", "B8"], "建筑指数"),
        "NBR": ("NBR", ["B8", "B12"], "火烧指数"),
        "SAVI": ("SAVI", ["B8", "B4"], "土壤调节植被指数"),
    }
    return specs.get(key, specs["NDVI"])


def _build_spectral_index_image(region_coords: RegionCoords, start: str, end: str, index_key: str) -> tuple[Any, Any, Any, int, str, str]:
    ee, geom, collection = _sentinel2_collection(region_coords, start, end, 60)
    image_count = int(collection.size().getInfo() or 0)
    if image_count <= 0:
        raise RuntimeError("当前时间范围内 GEE 未检索到 Sentinel-2 影像")
    key, bands, label = _spectral_index_spec(index_key)
    median = collection.median().clip(geom)
    if key == "SAVI":
        nir = median.select("B8").multiply(0.0001)
        red = median.select("B4").multiply(0.0001)
        image = nir.subtract(red).multiply(1.5).divide(nir.add(red).add(0.5)).rename(key)
    else:
        image = median.normalizedDifference(bands).rename(key)
    return ee, image.clip(geom), geom, image_count, key, label


def _compute_region_stats(median: Any, geom: Any, water_mask: Any, scale: int = 10) -> dict[str, Any]:
    """在 GEE 上统计 NDCI 均值与藻华像元面积（无需先下载栅格）。"""
    import ee  # type: ignore

    mean_ndci = float(
        median.reduceRegion(reducer=ee.Reducer.mean(), geometry=geom, scale=scale, maxPixels=1e13)
        .get("NDCI")
        .getInfo()
        or 0
    )
    bloom_thr = float(os.getenv("GEE_BLOOM_NDCI_THRESHOLD", "0.25"))
    bloom_mask = median.gt(bloom_thr)
    pixel_area = ee.Image.pixelArea()
    bloom_m2 = float(
        bloom_mask.multiply(pixel_area)
        .reduceRegion(reducer=ee.Reducer.sum(), geometry=geom, scale=scale, maxPixels=1e13)
        .get("NDCI")
        .getInfo()
        or 0
    )
    water_m2 = float(
        pixel_area.updateMask(water_mask).reduceRegion(reducer=ee.Reducer.sum(), geometry=geom, scale=scale, maxPixels=1e13)
        .get("area")
        .getInfo()
        or 1
    )
    study_km2 = round(water_m2 / 1e6, 2)
    bloom_km2 = round(bloom_m2 / 1e6, 2)
    ratio = round(100 * bloom_m2 / water_m2, 1) if water_m2 > 0 else 0.0
    return {
        "mean_ndci": round(mean_ndci, 4),
        "study_area_km2": study_km2,
        "estimated_bloom_area_km2": bloom_km2,
        "bloom_ratio_percent": ratio,
        "bloom_ndci_threshold": bloom_thr,
        "mask": "JRC Global Surface Water occurrence",
        "water_occurrence_min_percent": float(os.getenv("GEE_WATER_OCCURRENCE_MIN", "35")),
    }


def _compute_generic_index_stats(image: Any, geom: Any, band_name: str, scale: int = 10) -> dict[str, Any]:
    import ee  # type: ignore

    reducer = ee.Reducer.minMax().combine(ee.Reducer.mean(), sharedInputs=True)
    stats = image.reduceRegion(reducer=reducer, geometry=geom, scale=scale, maxPixels=1e13).getInfo() or {}
    return {
        "min": round(float(stats.get(f"{band_name}_min") or 0), 4),
        "max": round(float(stats.get(f"{band_name}_max") or 0), 4),
        "mean": round(float(stats.get(f"{band_name}_mean") or 0), 4),
        "valid_pixel_percent": 100.0,
        "data_source": "Sentinel-2 SR + GEE",
    }


def _is_retryable_download_error(exc: BaseException | str) -> bool:
    text = str(exc).lower()
    markers = (
        "remote end closed",
        "ssl",
        "unexpected_eof",
        "eof occurred",
        "connection reset",
        "connection aborted",
        "incomplete read",
        "timed out",
        "timeout",
        "temporarily unavailable",
        "bad gateway",
        "502",
        "503",
        "504",
    )
    return any(m in text for m in markers)


def _should_try_next_export_scale(exc: BaseException | str) -> bool:
    text = str(exc).lower()
    size_markers = ("50331648", "request size", "too large", "user memory limit")
    return any(m in text for m in size_markers) or _is_retryable_download_error(text)


def _download_geotiff(ee_url: str, dest_path: str, timeout_sec: int = 600) -> None:
    """从 GEE getDownloadURL 拉取 GeoTIFF 到本地。"""
    last_err: BaseException | None = None
    for attempt in range(3):
        try:
            req = Request(ee_url, headers={"User-Agent": "precision-observer-gee-export/1.0"})
            with urlopen(req, timeout=timeout_sec) as resp:
                data = resp.read()
            if len(data) < 1024:
                raise RuntimeError(f"下载体积异常偏小（{len(data)} bytes），可能导出失败")
            with open(dest_path, "wb") as f:
                f.write(data)
            return
        except Exception as e:
            last_err = e
            if attempt < 2 and _is_retryable_download_error(e):
                time.sleep(2**attempt)
                continue
            raise
    if last_err:
        raise last_err


def _should_upload_minio() -> bool:
    v = (os.getenv("GEE_UPLOAD_MINIO") or "auto").strip().lower()
    if v in ("0", "false", "no", "off"):
        return False
    if v in ("1", "true", "yes", "on"):
        return True
    return bool((os.getenv("MINIO_ENDPOINT") or "").strip())


def _upload_to_minio(local_path: str, object_key: str) -> str:
    """上传至 MinIO，返回 TiTiler/浏览器可访问的 HTTP URL。"""
    from minio import Minio

    endpoint = (os.getenv("MINIO_ENDPOINT") or "127.0.0.1:9000").strip()
    access = os.getenv("MINIO_ACCESS_KEY", "minioadmin")
    secret = os.getenv("MINIO_SECRET_KEY", "minioadmin")
    bucket = (os.getenv("MINIO_BUCKET") or "precision-observer").strip()
    secure = (os.getenv("MINIO_SECURE") or "false").lower() in ("1", "true", "yes")
    public_base = (os.getenv("MINIO_PUBLIC_BASE") or "http://127.0.0.1:9000").rstrip("/")

    client = Minio(endpoint, access_key=access, secret_key=secret, secure=secure)
    if not client.bucket_exists(bucket):
        client.make_bucket(bucket)
    size = os.path.getsize(local_path)
    client.fput_object(bucket, object_key, local_path, content_type="image/tiff")
    return f"{public_base}/{bucket}/{object_key}"


def _local_http_url(filename: str) -> str:
    base = (os.getenv("GEE_LOCAL_HTTP_BASE") or "http://127.0.0.1:8001/files/gee_exports").rstrip("/")
    return f"{base}/{filename}"


def _finish_download_export(cog_path: str, out_name: str, start: str, end: str, meta: dict[str, Any]) -> NdciResult:
    storage = "local"
    download_url = _local_http_url(out_name)

    if _should_upload_minio():
        try:
            key = f"gee/{out_name}"
            download_url = _upload_to_minio(cog_path, key)
            storage = "minio"
        except Exception as e:
            return NdciResult(
                False,
                cog_path,
                download_url,
                f"栅格已落盘但上传 MinIO 失败：{e}；本地 URL 仍可用",
                {**meta, "start": start, "end": end, "storage": "local"},
            )

    titiler_base = (os.getenv("TITILER_COG_FETCH_BASE") or "").strip()
    if titiler_base:
        if storage == "minio":
            bucket = (os.getenv("MINIO_BUCKET") or "precision-observer").strip()
            download_url = f"{titiler_base.rstrip('/')}/{bucket}/gee/{out_name}"
        else:
            download_url = f"{titiler_base.rstrip('/')}/{out_name}"

    return NdciResult(
        ok=True,
        cog_path=cog_path,
        download_url=download_url,
        message=f"GEE 导出完成（{storage}），可由 TiTiler 切片",
        meta={
            **meta,
            "start": start,
            "end": end,
            "storage": storage,
            "simulated": False,
            "export_mode": "download",
        },
    )


def _bbox_max_deg(region: RegionCoords) -> float:
    if len(region) >= 4 and all(isinstance(x, (int, float)) for x in region[:4]):
        return max(abs(float(region[2]) - float(region[0])), abs(float(region[3]) - float(region[1])))
    return 0.5


def _export_scales_to_try(region: RegionCoords) -> list[int]:
    """getDownloadURL 单次约 48MB 上限；大湖（如太湖）需更粗分辨率。"""
    env_scale = int(os.getenv("GEE_EXPORT_SCALE", "10"))
    max_scale = int(os.getenv("GEE_EXPORT_MAX_SCALE", "250"))
    deg = _bbox_max_deg(region)
    if deg >= 0.45:
        candidates = [60, 100, 150, max_scale]
    elif deg >= 0.15:
        candidates = [30, 60, 100, max_scale]
    else:
        candidates = [env_scale, 30, 60, 100, max_scale]
    out: list[int] = []
    for s in candidates:
        s = max(10, min(s, max_scale))
        if s not in out:
            out.append(s)
    return out


def _export_via_download(
    median: Any,
    geom: Any,
    region_coords: RegionCoords,
    start: str,
    end: str,
    water_mask: Any | None = None,
) -> NdciResult:
    """无 GCS 桶：getDownloadURL → 本地文件 → 可选 MinIO → HTTP URL。"""
    import ee  # type: ignore

    out_name = f"ndci_{start}_{end}_{uuid.uuid4().hex[:8]}.tif"
    cog_path = os.path.join(_output_dir(), out_name)

    last_err = ""
    export_scale = int(os.getenv("GEE_EXPORT_SCALE", "10"))
    for scale in _export_scales_to_try(region_coords):
        export_scale = scale
        try:
            ee_url = median.getDownloadURL(
                {
                    "scale": scale,
                    "crs": "EPSG:4326",
                    "region": geom,
                    "format": "GEO_TIFF",
                }
            )
            _download_geotiff(ee_url, cog_path)
            last_err = ""
            break
        except Exception as e:
            last_err = str(e)
            if not _should_try_next_export_scale(e):
                return NdciResult(False, "", "", f"GEE 本地下载失败：{e}", {"start": start, "end": end})
    if last_err:
        return NdciResult(
            False,
            "",
            "",
            f"GEE 本地下载失败（已重试并尝试至 {export_scale}m，可能是 GEE 临时断连或导出体量过大）：{last_err}",
            {"start": start, "end": end, "export_scale": export_scale},
        )

    if water_mask is None:
        import ee  # type: ignore

        water_mask = ee.Image(1).clip(geom)
    stats = _compute_region_stats(median, geom, water_mask, scale=export_scale)
    return _finish_download_export(cog_path, out_name, start, end, {**stats, "export_scale_m": export_scale})


def _export_true_color_via_download(
    image: Any,
    geom: Any,
    region_coords: RegionCoords,
    start: str,
    end: str,
    image_count: int,
) -> NdciResult:
    out_name = f"true_color_{start}_{end}_{uuid.uuid4().hex[:8]}.tif"
    cog_path = os.path.join(_output_dir(), out_name)

    last_err = ""
    export_scale = int(os.getenv("GEE_TRUE_COLOR_EXPORT_SCALE", os.getenv("GEE_EXPORT_SCALE", "10")))
    for scale in _export_scales_to_try(region_coords):
        export_scale = scale
        try:
            ee_url = image.getDownloadURL(
                {
                    "scale": scale,
                    "crs": "EPSG:4326",
                    "region": geom,
                    "format": "GEO_TIFF",
                }
            )
            _download_geotiff(ee_url, cog_path)
            last_err = ""
            break
        except Exception as e:
            last_err = str(e)
            if not _should_try_next_export_scale(e):
                return NdciResult(False, "", "", f"GEE 真彩色导出失败：{e}", {"start": start, "end": end})
    if last_err:
        return NdciResult(
            False,
            "",
            "",
            f"GEE 真彩色导出失败（已重试并尝试至 {export_scale}m，可能是 GEE 临时断连或导出体量过大）：{last_err}",
            {"start": start, "end": end, "export_scale": export_scale},
        )

    return _finish_download_export(
        cog_path,
        out_name,
        start,
        end,
        {
            "data_source": "Sentinel-2 SR + GEE",
            "image_count": image_count,
            "export_scale_m": export_scale,
            "visualization": "true_color",
        },
    )


def _export_spectral_index_via_download(
    image: Any,
    geom: Any,
    region_coords: RegionCoords,
    start: str,
    end: str,
    image_count: int,
    index_key: str,
    index_label: str,
) -> NdciResult:
    out_name = f"{index_key.lower()}_{start}_{end}_{uuid.uuid4().hex[:8]}.tif"
    cog_path = os.path.join(_output_dir(), out_name)

    last_err = ""
    export_scale = int(os.getenv("GEE_EXPORT_SCALE", "10"))
    for scale in _export_scales_to_try(region_coords):
        export_scale = scale
        try:
            ee_url = image.getDownloadURL(
                {
                    "scale": scale,
                    "crs": "EPSG:4326",
                    "region": geom,
                    "format": "GEO_TIFF",
                }
            )
            _download_geotiff(ee_url, cog_path)
            last_err = ""
            break
        except Exception as e:
            last_err = str(e)
            if not _should_try_next_export_scale(e):
                return NdciResult(False, "", "", f"GEE {index_key} 导出失败：{e}", {"start": start, "end": end})
    if last_err:
        return NdciResult(
            False,
            "",
            "",
            f"GEE {index_key} 导出失败（已重试并尝试至 {export_scale}m）：{last_err}",
            {"start": start, "end": end, "export_scale": export_scale},
        )

    stats = _compute_generic_index_stats(image, geom, index_key, scale=export_scale)
    return _finish_download_export(
        cog_path,
        out_name,
        start,
        end,
        {
            **stats,
            "index_key": index_key,
            "index_label": index_label,
            "image_count": image_count,
            "export_scale_m": export_scale,
            "source_kind": "gee",
        },
    )


def _export_via_gcs_bucket(median: Any, geom: Any, start: str, end: str, bucket: str) -> NdciResult:
    """大数据量：提交 GCS 异步导出（需桶权限；瓦片需后续同步到 MinIO/本地）。"""
    import ee  # type: ignore

    out_name = f"ndci_{start}_{end}_{uuid.uuid4().hex[:8]}.tif"
    prefix = os.path.splitext(out_name)[0]
    cog_path = os.path.join(_output_dir(), out_name)
    task = ee.batch.Export.image.toCloudStorage(
        median,
        description="ndci_export",
        bucket=bucket,
        fileNamePrefix=prefix,
        region=geom,
        scale=int(os.getenv("GEE_EXPORT_SCALE", "10")),
        maxPixels=1e13,
    )
    task.start()
    gs_url = f"gs://{bucket}/{prefix}.tif"
    return NdciResult(
        ok=True,
        cog_path=cog_path,
        download_url=gs_url,
        message="GEE 已提交 Cloud Storage 异步导出；完成后请同步到 MinIO/本地再由 TiTiler 切片",
        meta={
            "start": start,
            "end": end,
            "simulated": False,
            "export_mode": "gcs_async",
            "task_id": task.id,
            "gcs_url": gs_url,
        },
    )


def compute_ndci(
    region_coords: RegionCoords,
    start_date: str,
    end_date: str,
    mask_coords: RegionCoords | None = None,
    mask_label: str | None = None,
) -> NdciResult:
    """
    在 GEE 上对区域与时间范围计算 NDCI 并导出。

    - 默认：getDownloadURL → 本地（GEE_OUTPUT_DIR）→ 可选 MinIO → HTTP URL
    - 若设置 GEE_EXPORT_BUCKET：提交 GCS 异步任务（适合大体量，需后续同步）
    """
    start, end = _parse_dates(start_date, end_date)

    try:
        import ee  # type: ignore  # noqa: F401
    except ImportError:
        return NdciResult(
            False,
            "",
            "",
            "未安装 earthengine-api，请执行 pip install earthengine-api",
            {"simulated": False},
        )

    try:
        _, median, water_mask = _build_ndci_image(region_coords, start, end, mask_coords)
        geom = _ee_geometry(region_coords)
        bucket = (os.getenv("GEE_EXPORT_BUCKET") or "").strip()
        if bucket:
            return _export_via_gcs_bucket(median, geom, start, end, bucket)
        result = _export_via_download(median, geom, region_coords, start, end, water_mask)
        if result.ok and mask_label:
            result.meta["mask"] = f"{mask_label}湖体边界 + JRC Global Surface Water occurrence"
            result.meta["mask_polygon"] = mask_coords
        return result
    except FileNotFoundError as e:
        return NdciResult(False, "", "", str(e), {"simulated": False})
    except Exception as e:
        hint = ""
        if _is_retryable_gee_network_error(e):
            hint = "（网络无法稳定访问 Google OAuth，请检查代理/VPN 或在 .env 设置 HTTPS_PROXY 后重试）"
        return NdciResult(
            False,
            "",
            "",
            f"GEE NDCI 失败：{e}{hint}",
            {"simulated": False, "error_kind": "network" if _is_retryable_gee_network_error(e) else "gee"},
        )


def export_true_color(
    region_coords: RegionCoords,
    start_date: str,
    end_date: str,
) -> NdciResult:
    start, end = _parse_dates(start_date, end_date)

    try:
        import ee  # type: ignore  # noqa: F401
    except ImportError:
        return NdciResult(False, "", "", "未安装 earthengine-api，请执行 pip install earthengine-api", {"simulated": False})

    try:
        _, image, geom, image_count = _build_true_color_image(region_coords, start, end)
        return _export_true_color_via_download(image, geom, region_coords, start, end, image_count)
    except FileNotFoundError as e:
        return NdciResult(False, "", "", str(e), {"simulated": False})
    except Exception as e:
        hint = ""
        if _is_retryable_gee_network_error(e):
            hint = "（网络无法稳定访问 Google OAuth，请检查代理/VPN 或在 .env 设置 HTTPS_PROXY 后重试）"
        return NdciResult(
            False,
            "",
            "",
            f"GEE 真彩色失败：{e}{hint}",
            {"simulated": False, "error_kind": "network" if _is_retryable_gee_network_error(e) else "gee"},
        )


def compute_spectral_index(
    region_coords: RegionCoords,
    start_date: str,
    end_date: str,
    index_key: str,
) -> NdciResult:
    start, end = _parse_dates(start_date, end_date)

    try:
        import ee  # type: ignore  # noqa: F401
    except ImportError:
        return NdciResult(False, "", "", "未安装 earthengine-api，请执行 pip install earthengine-api", {"simulated": False})

    try:
        _, image, geom, image_count, key, label = _build_spectral_index_image(region_coords, start, end, index_key)
        return _export_spectral_index_via_download(image, geom, region_coords, start, end, image_count, key, label)
    except FileNotFoundError as e:
        return NdciResult(False, "", "", str(e), {"simulated": False})
    except Exception as e:
        hint = ""
        if _is_retryable_gee_network_error(e):
            hint = "（网络无法稳定访问 Google OAuth，请检查代理/VPN 或在 .env 设置 HTTPS_PROXY 后重试）"
        return NdciResult(
            False,
            "",
            "",
            f"GEE 光谱指数失败：{e}{hint}",
            {"simulated": False, "error_kind": "network" if _is_retryable_gee_network_error(e) else "gee"},
        )
