"""本地工具版四智能体节点：Director、Analyst、Engineer、Inspector。"""

from __future__ import annotations

import json
import os
from typing import Any

from openai import OpenAI

from .redis_progress import publish_progress
from .tools.analysis_runner import ANALYSIS_LABELS, run_remote_sensing_analysis
from .tools.intent import detect_analysis_intent


def _llm_api_key() -> str:
    return (os.getenv("OPENAI_API_KEY") or os.getenv("DASHSCOPE_API_KEY") or "").strip()


def _llm_base_url() -> str:
    return os.getenv("OPENAI_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1")


def _llm_model() -> str:
    return os.getenv("OPENAI_MODEL", os.getenv("QWEN_MODEL", "qwen-plus"))


MAX_ANALYST_ROUNDS = int(os.getenv("MAX_ANALYST_ROUNDS", "5"))


def get_openai_client() -> OpenAI:
    """构造 OpenAI 兼容客户端，默认连接通义千问兼容接口。"""
    key = _llm_api_key()
    if not key:
        raise RuntimeError("未配置 LLM API Key：请在 agent-api/.env 填写 DASHSCOPE_API_KEY 或 OPENAI_API_KEY")
    return OpenAI(api_key=key, base_url=_llm_base_url())


def _chat(system: str, user: str) -> str:
    client = get_openai_client()
    resp = client.chat.completions.create(
        model=_llm_model(),
        messages=[
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ],
        temperature=0.2,
    )
    return (resp.choices[0].message.content or "").strip()


def _state_snapshot(state: dict[str, Any]) -> str:
    """生成给 LLM 阅读的精简状态，避免把 Redis 全量快照塞进上下文。"""
    keep = {
        k: state.get(k)
        for k in (
            "user_message",
            "region_coords",
            "start_date",
            "end_date",
            "analysis_intent",
            "director_output",
            "analyst_output",
            "engineer_output",
            "inspector_output",
            "engineer_ok",
            "cog_path",
            "download_url",
            "tile_url",
            "metrics",
            "analyst_round",
        )
        if state.get(k) is not None
    }
    return json.dumps(keep, ensure_ascii=False)[:6000]


def director_node(state: dict[str, Any]) -> dict[str, Any]:
    """Director：理解用户目标，给出执行纲要。"""
    task_id = state["task_id"]
    intent = state.get("analysis_intent") or detect_analysis_intent(str(state.get("user_message") or ""))
    intent_label = ANALYSIS_LABELS.get(intent, intent)
    msg = "Director：解析用户目标并制定执行纲要"
    print(f"[{task_id}] {msg}")
    publish_progress(task_id, "Director", msg, {"status": "running", "analysis_type": intent})

    system = (
        "你是遥感分析项目的 Director。请用 3-5 条中文要点说明总体目标、"
        "需要的数据、区域与时间条件，以及 Analyst/Engineer/Inspector 的分工。"
        f"已识别分析类型：{intent_label}。"
    )
    user = (
        f"用户请求：{state.get('user_message', '')}\n"
        f"分析类型：{intent_label}\n"
        f"区域：{state.get('region_coords')}\n"
        f"时间：{state.get('start_date')} ~ {state.get('end_date')}"
    )
    output = _chat(system, user)

    publish_progress(
        task_id,
        "Director",
        "Director 已完成",
        {"director_output": output, "analysis_intent": intent, "analysis_type": intent},
    )
    return {"director_output": output, "analysis_intent": intent, "status": "director_done"}


def analyst_node(state: dict[str, Any]) -> dict[str, Any]:
    """Analyst：细化可执行方案；收到下游反馈后修订方案。"""
    task_id = state["task_id"]
    round_no = int(state.get("analyst_round") or 0) + 1
    msg = f"Analyst：第 {round_no} 轮分析与技术方案"
    print(f"[{task_id}] {msg}")
    publish_progress(task_id, "Analyst", msg, {"analyst_round": round_no})

    feedback = ""
    if state.get("engineer_output"):
        feedback += f"\nEngineer 反馈：{state.get('engineer_output')}"
    if state.get("inspector_output"):
        feedback += f"\nInspector 反馈：{state.get('inspector_output')}"

    intent = state.get("analysis_intent") or "general"
    intent_label = ANALYSIS_LABELS.get(intent, intent)
    system = (
        "你是遥感 Analyst。请根据 Director 纲要与用户请求，输出可执行技术方案："
        "数据集、指标、阈值、导出方式、质量控制点都要说清楚。"
        f"本任务类型为「{intent_label}」。若上一环节失败，请说明如何修正。"
    )
    output = _chat(system, f"状态：{_state_snapshot(state)}{feedback}")

    publish_progress(task_id, "Analyst", f"Analyst 第 {round_no} 轮完成", {"analyst_output": output})
    return {
        "analyst_output": output,
        "analyst_round": round_no,
        "engineer_ok": False,
        "inspector_pass": False,
        "status": "analyst_done",
    }


def engineer_node(state: dict[str, Any]) -> dict[str, Any]:
    """Engineer：调用本地 GEE 工具完成分析，产出 COG、瓦片 URL 与报告字段。"""
    task_id = state["task_id"]
    intent = state.get("analysis_intent") or detect_analysis_intent(str(state.get("user_message") or ""))
    intent_label = ANALYSIS_LABELS.get(intent, intent)
    msg = f"Engineer：调用 Google Earth Engine 执行「{intent_label}」"
    print(f"[{task_id}] {msg}")
    publish_progress(task_id, "Engineer", msg, {"analysis_type": intent})

    result = run_remote_sensing_analysis(
        str(state.get("user_message") or ""),
        state.get("region_coords") or [],
        str(state.get("start_date") or "2024-01-01"),
        str(state.get("end_date") or "2024-01-31"),
        analysis_type=intent,
    )
    engineer_ok = bool(result.ok and result.cog_path)
    summary = f"{result.message}\ncog_path={result.cog_path}\ndownload_url={result.download_url}"

    publish_progress(
        task_id,
        "Engineer",
        "Engineer 执行结束，报告字段已生成",
        {
            "engineer_ok": engineer_ok,
            "engineer_output": summary,
            "cog_path": result.cog_path,
            "download_url": result.download_url,
            "tile_url": result.tile_url,
            "tileUrl": result.tile_url,
            "report_title": result.report_title,
            "report_summary": result.report_summary,
            "metrics": result.metrics,
            "analysis_type": result.analysis_type,
            "message": result.message,
        },
    )
    return {
        "engineer_output": summary,
        "engineer_ok": engineer_ok,
        "cog_path": result.cog_path,
        "download_url": result.download_url,
        "tile_url": result.tile_url,
        "report_title": result.report_title,
        "report_summary": result.report_summary,
        "metrics": result.metrics,
        "analysis_intent": result.analysis_type,
        "message": result.message,
        "status": "engineer_done" if engineer_ok else "engineer_failed",
    }


def inspector_node(state: dict[str, Any]) -> dict[str, Any]:
    """Inspector：验收 Engineer 产出是否满足任务目标。"""
    task_id = state["task_id"]
    msg = "Inspector：验收 COG、下载链接与报告内容"
    print(f"[{task_id}] {msg}")
    publish_progress(task_id, "Inspector", msg)

    system = (
        "你是遥感质检 Inspector。请根据任务状态判断 Engineer 产出是否可交付。"
        "仅回复一行 JSON：{\"pass\": true/false, \"reason\": \"中文说明\"}。"
        "若缺少 cog_path/download_url，或用户要求真实 GEE 但结果明显是模拟，应 pass=false。"
    )
    raw = _chat(system, f"状态：{_state_snapshot(state)}")

    inspector_pass = False
    reason = raw
    try:
        start = raw.find("{")
        end = raw.rfind("}") + 1
        if start >= 0 and end > start:
            obj = json.loads(raw[start:end])
            inspector_pass = bool(obj.get("pass"))
            reason = str(obj.get("reason") or raw)
    except json.JSONDecodeError:
        inspector_pass = "pass" in raw.lower() and "false" not in raw.lower()

    report_title = str(state.get("report_title") or "遥感分析报告")
    report_summary = str(state.get("report_summary") or "")
    final_answer = ""
    if inspector_pass:
        final_answer = report_summary or (
            f"## {report_title}\n\n分析已完成。\n"
            f"- COG：{state.get('cog_path')}\n"
            f"- 下载：{state.get('download_url')}\n"
            f"- 说明：{reason}"
        )

    publish_progress(
        task_id,
        "Inspector",
        "Inspector 验收完成",
        {"inspector_pass": inspector_pass, "inspector_output": reason},
    )
    return {
        "inspector_output": reason,
        "inspector_pass": inspector_pass,
        "final_answer": final_answer,
        "status": "completed" if inspector_pass else "inspector_rejected",
    }
