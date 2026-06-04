"""从 GEE 下载覆盖南京全域的 Sentinel-2 SR 多波段本地影像。

用途：
- 为耕地面积变化、NDVI/NDWI/NDBI/NBR/SAVI 等本地优先分析提供原始多波段 COG；
- 地图预览不再单独依赖 true_color 文件，而是从多波段 COG 的 B4/B3/B2 渲染；
- 输出文件名遵守本地影像库约定，Spring 会自动按地点、年份、月份命中。
"""

from __future__ import annotations

import argparse
import json
import os
import time
from pathlib import Path
from urllib.request import Request, urlopen

from dotenv import load_dotenv


ROOT = Path(__file__).resolve().parents[1]
AGENT_ROOT = ROOT / "agent-api"
OUT_DIR = Path("E:/yaogandata")
REGION = (118.35, 31.2, 119.3, 32.65)
BANDS = ("B4", "B8", "B2", "B3", "B5", "B6", "B7", "B8A", "B11", "B12")


def init_ee():
    load_dotenv(AGENT_ROOT / ".env")
    import ee

    cred_raw = os.getenv("GEE_CREDENTIALS_PATH", "../secrets/gee-service-account.json")
    cred = Path(cred_raw)
    if not cred.is_absolute():
        cred = (AGENT_ROOT / cred).resolve()

    with cred.open(encoding="utf-8") as f:
        service_account = json.load(f)
    email = service_account["client_email"]
    ee.Initialize(ee.ServiceAccountCredentials(email, key_file=str(cred)))
    return ee


def download(url: str, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".download")
    last_error: Exception | None = None
    for attempt in range(3):
        try:
            req = Request(url, headers={"User-Agent": "precision-observer-gee-local-download/1.0"})
            with urlopen(req, timeout=900) as resp:
                data = resp.read()
            if len(data) < 1024:
                raise RuntimeError(f"下载体积异常偏小：{len(data)} bytes")
            tmp.write_bytes(data)
            tmp.replace(dest)
            return
        except Exception as exc:
            last_error = exc
            time.sleep(2 * (attempt + 1))
    raise RuntimeError(f"下载失败：{last_error}") from last_error


def collection_for(ee, start: str, end: str):
    geom = ee.Geometry.Rectangle(REGION)
    return (
        ee.ImageCollection("COPERNICUS/S2_SR_HARMONIZED")
        .filterBounds(geom)
        .filterDate(start, end)
        .filter(ee.Filter.lt("CLOUDY_PIXEL_PERCENTAGE", 60))
    )


def export_multiband(ee, year: int, scale: int) -> Path:
    start = f"{year}-04-01"
    end = f"{year}-06-30"
    collection = collection_for(ee, start, end)
    geom = ee.Geometry.Rectangle(REGION)
    count = int(collection.size().getInfo())
    if count == 0:
        raise RuntimeError(f"{year} 年 4-6 月未检索到 Sentinel-2 SR 影像")

    image = collection.median().select(BANDS).unmask(0).toUint16().clip(geom)
    url = image.getDownloadURL(
        {
            "scale": scale,
            "crs": "EPSG:4326",
            "region": geom,
            "format": "GEO_TIFF",
        }
    )
    dest = OUT_DIR / f"nanjing_{year}_04_06_s2_sr_multiband_median.tif"
    download(url, dest)
    write_meta(dest, year, start, end, count, scale)
    print(f"multiband {year}: {dest} ({dest.stat().st_size} bytes, scenes={count}, scale={scale}m)")
    return dest


def write_meta(dest: Path, year: int, start: str, end: str, count: int, scale: int) -> None:
    meta = {
        "year": year,
        "start": start,
        "end": end,
        "image_count": count,
        "composite": "median",
        "cloud_filter_percent": 60,
        "scale_m": scale,
        "bands": list(BANDS),
        "band_order_note": "b1=B4(red), b2=B8(nir), b3=B2(blue), b4=B3(green), b9=B11, b10=B12",
        "region": REGION,
        "source": "COPERNICUS/S2_SR_HARMONIZED",
        "preview_note": "真彩色预览由该多波段 COG 的 bidx=1,4,3 渲染。",
    }
    meta_path = dest.with_name(dest.stem + "_meta.json")
    meta_path.write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="下载南京全域 Sentinel-2 SR 多波段影像")
    parser.add_argument("--years", nargs="+", type=int, default=[2018, 2019], help="需要下载的年份")
    parser.add_argument(
        "--scale",
        type=int,
        default=int(os.getenv("NANJING_FULL_MULTIBAND_SCALE", "180")),
        help="导出分辨率，单位米；默认 180，数值越小文件越大且越容易触发 GEE 下载限制",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    ee = init_ee()
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for year in args.years:
        export_multiband(ee, year, scale=args.scale)


if __name__ == "__main__":
    main()
