"""Download local Sentinel-2 SR multiband imagery for Taihu water-area analysis."""

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
REGION = (119.70, 30.85, 120.70, 31.65)
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
            req = Request(url, headers={"User-Agent": "precision-observer-gee-taihu-download/1.0"})
            with urlopen(req, timeout=900) as resp:
                data = resp.read()
            if len(data) < 1024:
                raise RuntimeError(f"Downloaded file is unexpectedly small: {len(data)} bytes")
            tmp.write_bytes(data)
            tmp.replace(dest)
            return
        except Exception as exc:
            last_error = exc
            time.sleep(2 * (attempt + 1))
    raise RuntimeError(f"Download failed: {last_error}") from last_error


def export_multiband(
    ee,
    year: int,
    month: int,
    scale: int,
    start: str,
    end: str,
    cloud_max: int,
) -> Path:
    geom = ee.Geometry.Rectangle(REGION)
    collection = (
        ee.ImageCollection("COPERNICUS/S2_SR_HARMONIZED")
        .filterBounds(geom)
        .filterDate(start, end)
        .filter(ee.Filter.lt("CLOUDY_PIXEL_PERCENTAGE", cloud_max))
    )
    count = int(collection.size().getInfo())
    if count == 0:
        raise RuntimeError(f"No Sentinel-2 SR image found for {start} ~ {end}")

    image = collection.median().select(BANDS).unmask(0, False).toUint16().clip(geom)
    url = image.getDownloadURL(
        {
            "scale": scale,
            "crs": "EPSG:4326",
            "region": geom,
            "format": "GEO_TIFF",
        }
    )
    dest = OUT_DIR / f"taihu_{year}_{month:02d}_15_s2_sr_multiband_median.tif"
    download(url, dest)
    write_meta(dest, year, month, start, end, count, scale, cloud_max)
    print(f"taihu {year}-{month:02d}: {dest} ({dest.stat().st_size} bytes, scenes={count}, scale={scale}m)")
    return dest


def write_meta(dest: Path, year: int, month: int, start: str, end: str, count: int, scale: int, cloud_max: int) -> None:
    meta = {
        "place": "Taihu",
        "year": year,
        "month": month,
        "start": start,
        "end": end,
        "image_count": count,
        "composite": "median",
        "cloud_filter_percent": cloud_max,
        "scale_m": scale,
        "bands": list(BANDS),
        "band_order_note": "b1=B4(red), b2=B8(nir), b3=B2(blue), b4=B3(green), b9=B11, b10=B12",
        "region": REGION,
        "source": "COPERNICUS/S2_SR_HARMONIZED",
    }
    dest.with_name(dest.stem + "_meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")


def month_range(year: int, month: int) -> tuple[str, str]:
    if month == 12:
        return f"{year}-12-01", f"{year + 1}-01-01"
    return f"{year}-{month:02d}-01", f"{year}-{month + 1:02d}-01"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Download Taihu Sentinel-2 SR multiband imagery")
    parser.add_argument("--months", nargs="+", default=["2024-05", "2024-08"], help="Months to download, e.g. 2024-05 2024-08")
    parser.add_argument("--scale", type=int, default=int(os.getenv("TAIHU_MULTIBAND_SCALE", "150")), help="Export scale in meters")
    parser.add_argument("--cloud-max", type=int, default=80, help="CLOUDY_PIXEL_PERCENTAGE upper bound")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    ee = init_ee()
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for item in args.months:
        year_s, month_s = item.split("-", 1)
        year = int(year_s)
        month = int(month_s)
        start, end = month_range(year, month)
        export_multiband(ee, year, month, args.scale, start, end, args.cloud_max)


if __name__ == "__main__":
    main()
