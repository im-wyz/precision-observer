"""从用户自然语言推断遥感分析类型。"""

from __future__ import annotations


def detect_analysis_intent(user_message: str) -> str:
    """
    返回分析类型键：cyanobacteria | ndci | water | general
    """
    text = (user_message or "").strip().lower()
    if not text:
        return "general"

    if any(
        k in user_message
        for k in ("蓝藻", "藻华", "水华", "藻类", "浮游", "cyanobacteria", "algal", "bloom")
    ):
        return "cyanobacteria"
    if "ndci" in text or "归一化差异" in user_message:
        return "ndci"
    if any(k in user_message for k in ("水体", "水域", "水质", "富营养")):
        return "water"
    return "general"
