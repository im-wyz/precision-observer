"""从用户自然语言判断遥感分析类型。"""

from __future__ import annotations


def detect_analysis_intent(user_message: str) -> str:
    """
    返回分析类型：cyanobacteria | spectral_index | cropland_change | composite | threshold | change_detection | catalog_check | preprocess | ndci | water | general
    """
    text = (user_message or "").strip().lower()
    if not text:
        return "general"

    if any(
        k in user_message
        for k in ("蓝藻", "藻华", "水华", "藻类", "浮游", "cyanobacteria", "algal", "bloom")
    ):
        return "cyanobacteria"
    if any(k in user_message for k in ("耕地", "农田", "农作物", "种植面积", "耕地面积")) or any(
        k in text for k in ("cropland", "farmland")
    ):
        return "cropland_change"
    if any(k in user_message for k in ("本地影像目录", "影像目录", "数据目录", "质量检查", "覆盖率检查", "影像清单")) or (
        "目录" in user_message and any(k in user_message for k in ("影像", "质量", "覆盖率", "清单", "检查"))
    ):
        return "catalog_check"
    if any(
        k in user_message
        for k in ("云掩膜", "去云", "影像预处理", "基础预处理", "裁剪", "重采样", "重投影", "云优化", "COG 转换", "COG转换")
    ) or any(k in text for k in ("cloud mask", "preprocess", "resample", "reproject", "gdalwarp", "gdal_translate")):
        return "preprocess"
    if any(k in user_message for k in ("阈值分割", "阈值提取", "大于", "小于", "提取水体", "提取植被", "提取建筑")):
        return "threshold"
    if any(k in user_message for k in ("变化检测", "指数变化", "水体变化", "建筑变化", "植被变化", "对比变化")):
        return "change_detection"
    if any(k in user_message for k in ("真彩色", "假彩色", "农业假彩色", "合成影像", "彩色合成")) or any(
        k in text for k in ("true color", "false color", "composite")
    ):
        return "composite"
    if extract_index_key(user_message):
        return "spectral_index"
    if "ndci" in text or "归一化差异叶绿素" in user_message:
        return "ndci"
    if any(k in user_message for k in ("水体", "水域", "水质", "富营养")):
        return "water"
    return "general"


def extract_index_key(user_message: str) -> str | None:
    """提取一期支持的五类光谱指数。"""
    text = (user_message or "").strip()
    upper = text.upper()
    for key in ("NDVI", "NDWI", "NDBI", "NBR", "SAVI"):
        if key in upper:
            return key
    if any(k in text for k in ("植被指数", "植被覆盖", "绿度指数")):
        return "NDVI"
    if any(k in text for k in ("水体指数", "水域指数")):
        return "NDWI"
    if any(k in text for k in ("建筑指数", "建设用地指数", "城建指数")):
        return "NDBI"
    if any(k in text for k in ("火烧指数", "燃烧指数", "烧毁指数")):
        return "NBR"
    if any(k in text for k in ("土壤调节植被", "土壤调节指数")):
        return "SAVI"
    return None
