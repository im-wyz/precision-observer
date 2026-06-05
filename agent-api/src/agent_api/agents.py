"""本地工具版四智能体节点：Director、Analyst、Engineer、Inspector。"""

from __future__ import annotations

import json
import os
from typing import Any

from openai import OpenAI

from .redis_progress import publish_progress
from .tools.analysis_runner import ANALYSIS_LABELS, run_remote_sensing_analysis
from .tools.intent import detect_analysis_intent
from .tools.spring_tools import run_spring_basic_analysis, run_spring_cropland_analysis, run_spring_spectral_index_analysis


def _llm_api_key() -> str:
    return (os.getenv("OPENAI_API_KEY") or os.getenv("DASHSCOPE_API_KEY") or "").strip()


def _llm_base_url() -> str:
    return os.getenv("OPENAI_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1")


def _llm_model() -> str:
    return os.getenv("OPENAI_MODEL", os.getenv("QWEN_MODEL", "qwen3.6-plus"))


MAX_ANALYST_ROUNDS = int(os.getenv("MAX_ANALYST_ROUNDS", "5"))
LOCAL_SPRING_INTENTS = {"spectral_index", "cropland_change", "water_area_change", "composite", "threshold", "change_detection", "catalog_check", "preprocess"}


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

    if intent in LOCAL_SPRING_INTENTS:
        output = (
            "1. 通过 Agent 任务链路接收自然语言请求；\n"
            "2. 优先选择 E:/yaogandata 本地 Sentinel-2 COG；\n"
            "3. 由 Spring 内部分析服务执行本地基础遥感能力；\n"
            "4. 返回地图瓦片、边界、指标、命令或 Markdown 报告。"
        )
        publish_progress(
            task_id,
            "Director",
            "Director 已完成",
            {"director_output": output, "analysis_intent": intent, "analysis_type": intent},
        )
        return {"director_output": output, "analysis_intent": intent, "status": "director_done"}

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
    if intent in LOCAL_SPRING_INTENTS:
        if intent == "spectral_index":
            output = (
                "本地优先执行光谱指数分析：检查多波段 COG，按 Sentinel-2 SR 波段映射计算指数，"
                "返回统计指标、阈值面积、瓦片和报告。"
            )
        elif intent == "preprocess":
            output = (
                "本地优先执行影像预处理：选择 COG 后生成云掩膜、裁剪、重采样、重投影、COG 转换步骤；"
                "GDAL 可用且配置允许时执行，否则返回标准命令和诊断提示。"
            )
        elif intent == "cropland_change":
            output = "耕地变化复用 Spring 内部耕地分析服务，Agent 负责任务编排、进度和结果快照。"
        else:
            output = "基础遥感处理由 Spring 内部服务执行，返回可展示瓦片、边界、指标和 Markdown 报告。"
        publish_progress(task_id, "Analyst", f"Analyst 第 {round_no} 轮完成", {"analyst_output": output})
        return {
            "analyst_output": output,
            "analyst_round": round_no,
            "engineer_ok": False,
            "inspector_pass": False,
            "status": "analyst_done",
        }

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
    if intent in LOCAL_SPRING_INTENTS:
        return _engineer_node_spring_internal(state, intent)

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
            "geojson": result.meta.get("geojson"),
            "vector_boundary": result.meta.get("geojson"),
            "report_title": result.report_title,
            "report_summary": result.report_summary,
            "metrics": result.metrics,
            "meta": result.meta,
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
        "geojson": result.meta.get("geojson"),
        "vector_boundary": result.meta.get("geojson"),
        "report_title": result.report_title,
        "report_summary": result.report_summary,
        "metrics": result.metrics,
        "meta": result.meta,
        "analysis_intent": result.analysis_type,
        "message": result.message,
        "status": "engineer_done" if engineer_ok else "engineer_failed",
    }


def _engineer_node_spring_internal(state: dict[str, Any], intent: str) -> dict[str, Any]:
    task_id = state["task_id"]
    is_index = intent == "spectral_index"
    is_cropland = intent in ("cropland_change", "water_area_change")
    msg = "Engineer：正在选择本地多波段 COG 并检查指数波段" if is_index else (
        "Engineer：正在调用内部耕地变化分析服务" if is_cropland else (
            "Engineer：正在生成影像基础预处理链路" if intent == "preprocess" else "Engineer：正在调用基础遥感处理服务"
        )
    )
    print(f"[{task_id}] {msg}")
    publish_progress(task_id, "Engineer", msg, {"analysis_type": intent})
    try:
        if is_index:
            data = run_spring_spectral_index_analysis(state)
        elif is_cropland:
            data = run_spring_cropland_analysis(state)
        else:
            data = run_spring_basic_analysis(state, intent)
    except Exception as exc:
        if not is_index:
            data = {"ok": False, "message": str(exc), "analysis_type": intent, "meta": {}}
        else:
            publish_progress(task_id, "Engineer", "本地 COG 未命中或缺少波段，正在尝试 GEE 兜底", {"local_error": str(exc)})
            result = run_remote_sensing_analysis(
                str(state.get("user_message") or ""),
                state.get("region_coords") or [],
                str(state.get("start_date") or "2024-01-01"),
                str(state.get("end_date") or "2024-01-31"),
                analysis_type=intent,
            )
            data = {
                "ok": bool(result.ok),
                "analysis_type": result.analysis_type,
                "source_kind": "gee",
                "download_url": result.download_url,
                "tile_url": result.tile_url,
                "cog_path": result.cog_path,
                "metrics": result.metrics,
                "report_title": result.report_title,
                "report_summary": result.report_summary,
                "message": result.message,
                "meta": {**result.meta, "local_error": str(exc), "source_kind": "gee"},
            }

    engineer_ok = bool(data.get("ok"))
    meta = data.get("meta") or {}
    tile_url = data.get("tile_url", "") or data.get("tileTemplateUrl", "")
    summary = data.get("report_summary") or data.get("message") or "Engineer 执行结束"
    publish_progress(
        task_id,
        "Engineer",
        str(data.get("message") or ("指数分析完成" if engineer_ok else "指数分析失败")),
        {
            "engineer_ok": engineer_ok,
            "engineer_output": summary,
            "cog_path": data.get("cog_path", ""),
            "download_url": data.get("download_url", ""),
            "tile_url": tile_url,
            "tileUrl": tile_url,
            "boundaries": data.get("boundaries") or meta.get("boundaries"),
            "report_title": data.get("report_title", ""),
            "report_summary": data.get("report_summary", ""),
            "metrics": data.get("metrics", {}),
            "chartOption": data.get("chartOption") or meta.get("chartOption"),
            "cropland_data": data.get("cropland_data") or meta.get("cropland_data"),
            "water_data": data.get("water_data") or meta.get("water_data"),
            "change_layers": data.get("change_layers") or meta.get("change_layers"),
            "preprocess_steps": data.get("preprocess_steps") or meta.get("preprocess_steps"),
            "gdal_commands": data.get("gdal_commands") or meta.get("gdal_commands"),
            "warnings": data.get("warnings") or meta.get("warnings"),
            "meta": meta,
            "analysis_type": data.get("analysis_type", intent),
            "index_key": data.get("index_key") or state.get("index_key"),
            "source_kind": data.get("source_kind") or meta.get("source_kind"),
            "source_scene_id": data.get("source_scene_id") or meta.get("source_scene_id"),
            "band_map": data.get("band_map") or meta.get("band_map"),
            "message": data.get("message", ""),
        },
    )
    return {
        "engineer_output": summary,
        "engineer_ok": engineer_ok,
        "cog_path": data.get("cog_path", ""),
        "download_url": data.get("download_url", ""),
        "tile_url": tile_url,
        "boundaries": data.get("boundaries") or meta.get("boundaries"),
        "report_title": data.get("report_title", ""),
        "report_summary": data.get("report_summary", ""),
        "metrics": data.get("metrics", {}),
        "chartOption": data.get("chartOption") or meta.get("chartOption"),
        "cropland_data": data.get("cropland_data") or meta.get("cropland_data"),
        "water_data": data.get("water_data") or meta.get("water_data"),
        "change_layers": data.get("change_layers") or meta.get("change_layers"),
        "preprocess_steps": data.get("preprocess_steps") or meta.get("preprocess_steps"),
        "gdal_commands": data.get("gdal_commands") or meta.get("gdal_commands"),
        "warnings": data.get("warnings") or meta.get("warnings"),
        "meta": meta,
        "analysis_intent": data.get("analysis_type", intent),
        "index_key": data.get("index_key") or state.get("index_key"),
        "source_kind": data.get("source_kind") or meta.get("source_kind"),
        "source_scene_id": data.get("source_scene_id") or meta.get("source_scene_id"),
        "band_map": data.get("band_map") or meta.get("band_map"),
        "message": data.get("message", ""),
        "status": "engineer_done" if engineer_ok else "engineer_failed",
    }


def inspector_node(state: dict[str, Any]) -> dict[str, Any]:
    """Inspector：验收 Engineer 产出是否满足任务目标。"""
    task_id = state["task_id"]
    msg = "Inspector：验收 COG、下载链接与报告内容"
    print(f"[{task_id}] {msg}")
    publish_progress(task_id, "Inspector", msg)

    intent = str(state.get("analysis_intent") or "")
    local_intents = {"spectral_index", "cropland_change", "water_area_change", "composite", "threshold", "change_detection", "catalog_check", "preprocess"}
    if intent in local_intents:
        inspector_pass = bool(state.get("engineer_ok") and state.get("report_summary"))
        reason = "本地基础遥感任务已返回报告与可展示结果。" if inspector_pass else "本地基础遥感任务缺少报告或 Engineer 未成功。"
        final_answer = str(state.get("report_summary") or "") if inspector_pass else ""
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
