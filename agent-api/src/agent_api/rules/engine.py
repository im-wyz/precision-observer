"""轻量 JSON 规则引擎（Inspector 辅助）。"""
from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any


def _load_rules() -> list[dict[str, Any]]:
    path = Path(__file__).with_name("inspector_rules.json")
    if not path.is_file():
        return []
    with open(path, encoding="utf-8") as f:
        doc = json.load(f)
    return doc.get("rules") or []


def evaluate_inspector_rules(state: dict[str, Any]) -> dict[str, Any]:
    """
    返回 {pass: bool, messages: list[str], warnings: list[str]}
    不替代 LLM，仅硬规则。
    """
    messages: list[str] = []
    warnings: list[str] = []
    hard_fail = False

    for rule in _load_rules():
        cond = rule.get("when") or {}
        rid = rule.get("id", "")
        msg = str(rule.get("message") or rid)

        if cond.get("engineer_ok") is False and not state.get("engineer_ok"):
            if rule.get("fail"):
                hard_fail = True
                messages.append(msg)
            else:
                warnings.append(msg)

        missing = cond.get("missing_fields") or []
        for field in missing:
            if not state.get(field):
                if rule.get("fail"):
                    hard_fail = True
                    messages.append(msg)
                else:
                    warnings.append(msg)
                break

        substr = cond.get("download_url_contains")
        if substr:
            url = str(state.get("download_url") or "")
            if substr in url:
                if rule.get("fail"):
                    hard_fail = True
                    messages.append(msg)
                else:
                    warnings.append(msg)

    if hard_fail:
        return {"pass": False, "messages": messages, "warnings": warnings}
    return {"pass": True, "messages": messages, "warnings": warnings}
