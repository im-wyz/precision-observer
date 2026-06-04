"""MCP 工具版四智能体节点：通过 function calling 调用外部工具服务。"""

from __future__ import annotations

import json
import os
import time
from typing import Any

from openai import OpenAI

from .mcp_bridge.bridge import call_tool, get_openai_tools_for_role
from .mcp_bridge.roles import ANALYST_TOOLS, DIRECTOR_TOOLS, INSPECTOR_TOOLS
from .redis_progress import publish_progress
from .rules.engine import evaluate_inspector_rules
from .tools.analysis_runner import run_remote_sensing_analysis
from .tools.intent import detect_analysis_intent
from .tools.spring_tools import run_spring_basic_analysis, run_spring_cropland_analysis, run_spring_spectral_index_analysis


def _llm_api_key() -> str:
    return (os.getenv("OPENAI_API_KEY") or os.getenv("DASHSCOPE_API_KEY") or "").strip()


def _llm_base_url() -> str:
    return os.getenv("OPENAI_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1")


def _llm_model() -> str:
    return os.getenv("OPENAI_MODEL", os.getenv("QWEN_MODEL", "qwen3.6-plus"))


MAX_ANALYST_ROUNDS = int(os.getenv("MAX_ANALYST_ROUNDS", "5"))
MAX_TOOL_ROUNDS = int(os.getenv("MCP_MAX_TOOL_ROUNDS", "8"))


def get_openai_client() -> OpenAI:
    key = _llm_api_key()
    if not key:
        raise RuntimeError("未配置 LLM API Key：请在 agent-api/.env 填写 DASHSCOPE_API_KEY 或 OPENAI_API_KEY")
    return OpenAI(api_key=key, base_url=_llm_base_url())


def _state_snapshot(state: dict[str, Any]) -> str:
    """生成给 LLM 与工具规划使用的精简状态。"""
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


def _chat_with_tools(
    role: str,
    system: str,
    user: str,
    tool_prefixes: list[str],
    task_id: str,
) -> tuple[str, list[dict[str, Any]]]:
    """执行 Qwen/OpenAI function calling 循环，返回最终文本和工具调用日志。"""
    client = get_openai_client()
    if not tool_prefixes:
        resp = client.chat.completions.create(
            model=_llm_model(),
            messages=[{"role": "system", "content": system}, {"role": "user", "content": user}],
            temperature=0.2,
        )
        return (resp.choices[0].message.content or "").strip(), []

    tools = get_openai_tools_for_role(tool_prefixes)
    if not tools:
        resp = client.chat.completions.create(
            model=_llm_model(),
            messages=[{"role": "system", "content": system}, {"role": "user", "content": user}],
            temperature=0.2,
        )
        return (resp.choices[0].message.content or "").strip(), []

    messages: list[dict[str, Any]] = [
        {"role": "system", "content": system},
        {"role": "user", "content": user},
    ]
    tool_log: list[dict[str, Any]] = []

    for _ in range(MAX_TOOL_ROUNDS):
        resp = client.chat.completions.create(
            model=_llm_model(),
            messages=messages,
            tools=tools,
            tool_choice="auto",
            temperature=0.2,
        )
        msg = resp.choices[0].message
        assistant_entry: dict[str, Any] = {"role": "assistant", "content": msg.content or ""}
        if msg.tool_calls:
            assistant_entry["tool_calls"] = [
                {
                    "id": tc.id,
                    "type": "function",
                    "function": {"name": tc.function.name, "arguments": tc.function.arguments},
                }
                for tc in msg.tool_calls
            ]
        messages.append(assistant_entry)

        if not msg.tool_calls:
            return (msg.content or "").strip(), tool_log

        for tc in msg.tool_calls:
            tool_name = tc.function.name
            try:
                args = json.loads(tc.function.arguments or "{}")
            except json.JSONDecodeError:
                args = {}
            publish_progress(task_id, role, f"调用工具 {tool_name}", {"tool": tool_name, "args_preview": str(args)[:200]})
            result = call_tool(tool_name, args)
            tool_log.append({"tool": tool_name, "args": args, "result": result})
            messages.append({"role": "tool", "tool_call_id": tc.id, "content": result[:12000]})

    return "工具调用轮次已达上限，请根据已有结果总结。", tool_log


def _is_transient_gee_network_error(message: str) -> bool:
    text = (message or "").lower()
    return any(
        marker in text
        for marker in (
            "oauth2.googleapis.com",
            "ssl",
            "unexpected_eof",
            "max retries exceeded",
            "connection",
            "timeout",
        )
    )


def _run_gee_analysis_local(state: dict[str, Any], intent: str, task_id: str | None = None) -> dict[str, Any]:
    """GEE 导出可能超过 MCP SSE 超时，默认在 agent 进程内直连本地工具。"""

    def _once() -> dict[str, Any]:
        result = run_remote_sensing_analysis(
            str(state.get("user_message") or ""),
            state.get("region_coords") or [],
            str(state.get("start_date") or ""),
            str(state.get("end_date") or ""),
            analysis_type=intent,
        )
        return {
            "ok": result.ok,
            "analysis_type": result.analysis_type,
            "message": result.message,
            "cog_path": result.cog_path,
            "download_url": result.download_url,
            "tile_url": result.tile_url,
            "geojson": result.meta.get("geojson"),
            "vector_boundary": result.meta.get("geojson"),
            "report_title": result.report_title,
            "report_summary": result.report_summary,
            "metrics": result.metrics,
            "meta": result.meta,
            "source": "local_tools",
        }

    data = _once()
    if not data.get("ok") and _is_transient_gee_network_error(str(data.get("message") or "")):
        if task_id:
            publish_progress(task_id, "Engineer", "GEE OAuth 网络抖动，5 秒后自动重试", {})
        time.sleep(5)
        data = _once()
        data["retried"] = True
    return data


def director_node(state: dict[str, Any]) -> dict[str, Any]:
    task_id = state["task_id"]
    intent = state.get("analysis_intent") or detect_analysis_intent(str(state.get("user_message") or ""))
    if intent in ("spectral_index", "cropland_change", "composite", "threshold", "change_detection", "catalog_check", "preprocess"):
        output = (
            "1. 统一通过 Agent 任务链路执行；\n"
            "2. Engineer 优先调用 Spring 内部分析服务，先查本地多波段 COG；\n"
            "3. 本地缺影像或缺波段时再尝试 GEE 兜底；\n"
            "4. 输出瓦片、边界、指标、预处理命令和 Markdown 报告。"
        )
        publish_progress(task_id, "Director", "Director：已采用本地优先的确定性规划", {"status": "running", "analysis_type": intent})
        publish_progress(task_id, "Director", "Director 已完成", {"director_output": output, "tool_calls": []})
        return {"director_output": output, "analysis_intent": intent, "status": "director_done"}

    publish_progress(task_id, "Director", "Director：通过 MCP 规划任务", {"status": "running", "analysis_type": intent})

    system = (
        "你是 Director。请用中文简要规划遥感分析任务（3-5 条）。"
        "可调用 storage__get_scenario_catalog、gee__resolve_region、gee__detect_intent、gee__list_supported_scenarios。"
        "规划完成后必须给出文字总结。"
    )
    user = (
        f"用户需求：{state.get('user_message')}\n"
        f"时间：{state.get('start_date')} ~ {state.get('end_date')}\n"
        f"区域坐标：{json.dumps(state.get('region_coords'), ensure_ascii=False)}"
    )
    output, tool_log = _chat_with_tools("Director", system, user, DIRECTOR_TOOLS, task_id)
    publish_progress(task_id, "Director", "Director 已完成", {"director_output": output, "tool_calls": tool_log})
    return {"director_output": output, "analysis_intent": intent, "status": "director_done"}


def analyst_node(state: dict[str, Any]) -> dict[str, Any]:
    task_id = state["task_id"]
    round_no = int(state.get("analyst_round") or 0) + 1
    publish_progress(task_id, "Analyst", f"Analyst：第 {round_no} 轮 MCP 技术方案", {"analyst_round": round_no})

    feedback = ""
    if state.get("engineer_output"):
        feedback += f"\nEngineer：{state.get('engineer_output')}"
    if state.get("inspector_output"):
        feedback += f"\nInspector：{state.get('inspector_output')}"

    intent = state.get("analysis_intent") or "general"
    if intent in ("spectral_index", "cropland_change", "composite", "threshold", "change_detection", "catalog_check", "preprocess"):
        if intent == "spectral_index":
            output = (
                "数据源策略：优先 E:/yaogandata 本地 Sentinel-2 SR 多波段 COG，命名优先 *_s2_sr_multiband_median.tif；"
                "波段检查：按 b1=B4, b2=B8, b4=B3, b9=B11, b10=B12 映射计算 NDVI/NDWI/NDBI/NBR/SAVI；"
                "统计：返回 min/max/mean、有效像素比例、研究区面积和默认阈值面积；"
                "可视化：使用 TiTiler expression 动态切片，并输出边界和报告。"
            )
        elif intent == "cropland_change":
            output = "耕地变化继续复用 Spring 内部耕地分析服务，由 Agent 负责编排进度、报告和兼容返回。"
        elif intent == "preprocess":
            output = (
                "影像预处理采用本地 COG 优先策略：先选 E:/yaogandata 影像并读取 TiTiler 元数据；"
                "按用户需求生成云掩膜、裁剪、重采样、重投影、COG 转换步骤；"
                "GDAL 可用且配置允许时执行，否则返回可复现 GDAL 命令和诊断报告。"
            )
        else:
            output = (
                "基础遥感处理任务采用本地 COG 优先策略：由 Spring 内部服务执行影像合成、阈值分割、"
                "两期变化检测或本地目录质量检查；输出统一包含瓦片、边界、统计指标和 Markdown 报告。"
            )
        publish_progress(
            task_id,
            "Analyst",
            f"Analyst 第 {round_no} 轮完成",
            {"analyst_output": output, "tool_calls": []},
        )
        return {
            "analyst_output": output,
            "analyst_round": round_no,
            "engineer_ok": False,
            "inspector_pass": False,
            "status": "analyst_done",
        }

    system = (
        f"你是 Analyst。任务类型是 {intent}。请给出可执行技术方案：数据集、指标、阈值、导出方式、质检点。"
        "可用 gee__、raster__、storage__、gdal__ 前缀工具查询或记录方案。最后输出简洁中文方案。"
    )
    output, tool_log = _chat_with_tools("Analyst", system, f"状态：{_state_snapshot(state)}{feedback}", ANALYST_TOOLS, task_id)
    publish_progress(
        task_id,
        "Analyst",
        f"Analyst 第 {round_no} 轮完成",
        {"analyst_output": output, "tool_calls": tool_log},
    )
    return {
        "analyst_output": output,
        "analyst_round": round_no,
        "engineer_ok": False,
        "inspector_pass": False,
        "status": "analyst_done",
    }


def engineer_node(state: dict[str, Any]) -> dict[str, Any]:
    task_id = state["task_id"]
    intent = state.get("analysis_intent") or detect_analysis_intent(str(state.get("user_message") or ""))
    if intent in ("spectral_index", "cropland_change", "composite", "threshold", "change_detection", "catalog_check", "preprocess"):
        return _engineer_node_spring_internal(state, intent)

    publish_progress(
        task_id,
        "Engineer",
        "Engineer：正在通过 gee-mcp 执行 GEE 分析（可能需要 1-3 分钟）",
        {"analysis_type": intent},
    )

    gee_args = {
        "user_message": state.get("user_message", ""),
        "region_coords_json": json.dumps(state.get("region_coords") or [], ensure_ascii=False),
        "start_date": state.get("start_date", ""),
        "end_date": state.get("end_date", ""),
        "analysis_type": intent,
    }
    force_mcp = os.getenv("GEE_FORCE_MCP", "true").strip().lower() in ("1", "true", "yes")
    if force_mcp:
        raw = call_tool("gee__run_analysis", gee_args)
        try:
            data: dict[str, Any] = json.loads(raw)
        except json.JSONDecodeError:
            data = {"ok": False, "message": raw[:2000]}
        allow_local_fallback = os.getenv("ENGINEER_ALLOW_LOCAL_GEE_FALLBACK", "false").strip().lower() in (
            "1",
            "true",
            "yes",
        )
        if allow_local_fallback and not data.get("ok"):
            publish_progress(task_id, "Engineer", "MCP GEE 调用失败，按配置回退到本机 GEE 工具", {"mcp_error": data})
            data = _run_gee_analysis_local(state, intent, task_id)
            raw = json.dumps(data, ensure_ascii=False)
    else:
        data = _run_gee_analysis_local(state, intent, task_id)
        raw = json.dumps(data, ensure_ascii=False)

    engineer_ok = bool(data.get("ok"))
    message = str(data.get("message") or data.get("error") or "").strip()
    summary_prompt = (
        f"GEE 结果 ok={engineer_ok}，message={message[:500]}，"
        f"metrics={json.dumps(data.get('metrics', {}), ensure_ascii=False)[:800]}"
    )
    llm_summary, _ = _chat_with_tools(
        "Engineer",
        "你是 Engineer。根据 GEE 工具返回用 2-4 句中文总结，不要编造未返回的指标。",
        summary_prompt,
        [],
        task_id,
    )
    output = llm_summary or message or "Engineer 执行结束"
    extra: dict[str, Any] = {
        "engineer_output": output,
        "engineer_ok": engineer_ok,
        "status": "engineer_done" if engineer_ok else "engineer_failed",
        "message": message,
        "cog_path": data.get("cog_path", ""),
        "download_url": data.get("download_url", ""),
        "tile_url": data.get("tile_url", ""),
        "geojson": data.get("geojson") or (data.get("meta") or {}).get("geojson"),
        "vector_boundary": data.get("vector_boundary") or data.get("geojson") or (data.get("meta") or {}).get("geojson"),
        "report_title": data.get("report_title", ""),
        "report_summary": data.get("report_summary", ""),
        "metrics": data.get("metrics", {}),
        "meta": data.get("meta", {}),
        "analysis_intent": data.get("analysis_type", intent),
    }

    try:
        call_tool(
            "storage__put_json",
            {
                "object_key": f"tasks/{task_id}/engineer_result.json",
                "data_json": json.dumps(data, ensure_ascii=False),
            },
        )
    except Exception:
        pass

    publish_payload = {
        **extra,
        "tool_calls": [
            {
                "tool": "gee__run_analysis",
                "args": gee_args,
                "result": raw[:12000],
                "via": "mcp" if force_mcp else "local_tools",
            }
        ],
        "status": "running",
    }
    if extra.get("tile_url"):
        publish_payload["tileUrl"] = extra["tile_url"]
    publish_progress(task_id, "Engineer", message or ("GEE 分析完成" if engineer_ok else "GEE 分析失败"), publish_payload)
    return extra


def _engineer_node_spring_internal(state: dict[str, Any], intent: str) -> dict[str, Any]:
    task_id = state["task_id"]
    is_index = intent == "spectral_index"
    is_cropland = intent == "cropland_change"
    tool_name = "spring_internal_index" if is_index else ("spring_agent_chat_cropland" if is_cropland else f"spring_internal_{intent}")
    publish_progress(
        task_id,
        "Engineer",
        "Engineer：正在选择本地多波段 COG 并检查指数波段" if is_index else (
            "Engineer：正在调用内部耕地变化分析服务" if is_cropland else (
                "Engineer：正在生成影像基础预处理链路" if intent == "preprocess" else "Engineer：正在调用基础遥感处理服务"
            )
        ),
        {"analysis_type": intent},
    )
    try:
        if is_index:
            data = run_spring_spectral_index_analysis(state)
        elif is_cropland:
            data = run_spring_cropland_analysis(state)
        else:
            data = run_spring_basic_analysis(state, intent)
    except Exception as exc:
        if not is_index:
            data = {"ok": False, "message": str(exc), "analysis_type": intent}
        else:
            publish_progress(
                task_id,
                "Engineer",
                "本地 COG 未命中或缺少波段，正在尝试 GEE 兜底",
                {"analysis_type": intent, "local_error": str(exc)},
            )
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
            tool_name = "spring_internal_index_then_gee"

    engineer_ok = bool(data.get("ok"))
    message = str(data.get("message") or data.get("error") or "").strip()
    output = data.get("report_summary") or message or "Engineer 执行结束"
    meta = data.get("meta") or {}
    extra: dict[str, Any] = {
        "engineer_output": output,
        "engineer_ok": engineer_ok,
        "status": "engineer_done" if engineer_ok else "engineer_failed",
        "message": message,
        "cog_path": data.get("cog_path", ""),
        "download_url": data.get("download_url", ""),
        "tile_url": data.get("tile_url", "") or data.get("tileTemplateUrl", ""),
        "tileTemplateUrl": data.get("tileTemplateUrl", "") or data.get("tile_url", ""),
        "geojson": data.get("geojson") or meta.get("geojson"),
        "vector_boundary": data.get("vector_boundary") or data.get("geojson") or meta.get("geojson"),
        "boundaries": data.get("boundaries") or meta.get("boundaries"),
        "report_title": data.get("report_title", ""),
        "report_summary": data.get("report_summary", ""),
        "metrics": data.get("metrics", {}),
        "chartOption": data.get("chartOption") or meta.get("chartOption"),
        "cropland_data": data.get("cropland_data") or meta.get("cropland_data"),
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
    }
    publish_payload = {
        **extra,
        "tileUrl": extra.get("tile_url", ""),
        "tool_calls": [{"tool": tool_name, "result": json.dumps(data, ensure_ascii=False)[:12000], "via": "spring"}],
        "status": "running",
    }
    publish_progress(task_id, "Engineer", message or ("指数分析完成" if engineer_ok else "指数分析失败"), publish_payload)
    return extra


def inspector_node(state: dict[str, Any]) -> dict[str, Any]:
    task_id = state["task_id"]
    intent = str(state.get("analysis_intent") or "")
    local_intents = {
        "spectral_index",
        "cropland_change",
        "composite",
        "threshold",
        "change_detection",
        "catalog_check",
        "preprocess",
    }
    if intent in local_intents:
        inspector_pass = bool(state.get("engineer_ok") and state.get("report_summary"))
        reason = "本地基础遥感任务已返回报告与可展示结果。" if inspector_pass else "本地基础遥感任务缺少报告或 Engineer 未成功。"
        final_answer = str(state.get("report_summary") or "") if inspector_pass else ""
        publish_progress(
            task_id,
            "Inspector",
            "Inspector 验收完成",
            {"inspector_pass": inspector_pass, "inspector_output": reason, "tool_calls": []},
        )
        return {
            "inspector_output": reason,
            "inspector_pass": inspector_pass,
            "final_answer": final_answer,
            "status": "completed" if inspector_pass else "inspector_rejected",
        }

    publish_progress(task_id, "Inspector", "Inspector：规则 + MCP + LLM 验收")

    rule_result = evaluate_inspector_rules(state)
    if not rule_result.get("pass"):
        reason = "；".join(rule_result.get("messages") or ["规则未通过"])
        publish_progress(
            task_id,
            "Inspector",
            "Inspector 规则未通过",
            {"inspector_pass": False, "inspector_output": reason},
        )
        return {
            "inspector_output": reason,
            "inspector_pass": False,
            "final_answer": state.get("report_summary") or reason,
            "status": "inspector_rejected",
        }

    system = (
        "你是 Inspector。可读取 storage__get_json 查询 tasks/{task_id}/engineer_result.json。"
        "结合规则结果判断是否通过。最后单独输出一行 JSON：{\"pass\": true/false, \"reason\": \"中文说明\"}。"
    )
    user = (
        f"task_id={task_id}\n"
        f"规则结果：{json.dumps(rule_result, ensure_ascii=False)}\n"
        f"状态：{_state_snapshot(state)}"
    )
    raw, tool_log = _chat_with_tools("Inspector", system, user, INSPECTOR_TOOLS, task_id)

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

    if rule_result.get("warnings"):
        reason += "\n" + "\n".join(rule_result["warnings"])

    final_answer = str(state.get("report_summary") or reason) if inspector_pass else ""
    publish_progress(
        task_id,
        "Inspector",
        "Inspector 验收完成",
        {"inspector_pass": inspector_pass, "inspector_output": reason, "tool_calls": tool_log},
    )
    return {
        "inspector_output": reason,
        "inspector_pass": inspector_pass,
        "final_answer": final_answer,
        "status": "completed" if inspector_pass else "inspector_rejected",
    }
