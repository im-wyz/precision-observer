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


def _llm_api_key() -> str:
    return (os.getenv("OPENAI_API_KEY") or os.getenv("DASHSCOPE_API_KEY") or "").strip()


def _llm_base_url() -> str:
    return os.getenv("OPENAI_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1")


def _llm_model() -> str:
    return os.getenv("OPENAI_MODEL", os.getenv("QWEN_MODEL", "qwen-plus"))


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
        "report_title": data.get("report_title", ""),
        "report_summary": data.get("report_summary", ""),
        "metrics": data.get("metrics", {}),
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


def inspector_node(state: dict[str, Any]) -> dict[str, Any]:
    task_id = state["task_id"]
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
