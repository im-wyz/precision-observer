"""LangGraph 工作流定义：Director -> Analyst -> Engineer -> Inspector。"""

from __future__ import annotations

import os
from typing import Any, Literal, TypedDict

from langgraph.graph import END, StateGraph

if os.getenv("MCP_ENABLED", "false").strip().lower() in ("1", "true", "yes"):
    from .agents_mcp import MAX_ANALYST_ROUNDS, analyst_node, director_node, engineer_node, inspector_node
else:
    from .agents import MAX_ANALYST_ROUNDS, analyst_node, director_node, engineer_node, inspector_node


class AnalysisState(TypedDict, total=False):
    """贯穿整个多智能体流程的共享状态。"""

    task_id: str
    user_message: str
    region_coords: list[Any]
    start_date: str
    end_date: str
    analysis_intent: str
    director_output: str
    analyst_output: str
    engineer_output: str
    inspector_output: str
    engineer_ok: bool
    inspector_pass: bool
    analyst_round: int
    cog_path: str
    download_url: str
    tile_url: str
    report_title: str
    report_summary: str
    metrics: dict[str, Any]
    final_answer: str
    message: str
    status: str


def _is_gee_infrastructure_failure(state: AnalysisState) -> bool:
    """凭据、依赖或网络类故障通常无法靠重新规划解决，直接结束避免空转。"""
    msg = str(state.get("message") or state.get("engineer_output") or "").lower()
    retryless_markers = (
        "oauth2.googleapis.com",
        "ssl",
        "unexpected_eof",
        "max retries exceeded",
        "connection",
        "timeout",
        "未找到 gee 凭据",
        "gee json 缺少",
        "未安装 earthengine-api",
    )
    return any(marker in msg for marker in retryless_markers)


def route_after_engineer(state: AnalysisState) -> Literal["inspector", "analyst", "__end__"]:
    """Engineer 成功则验收；失败则有限次回到 Analyst 修订方案。"""
    if state.get("engineer_ok"):
        return "inspector"
    if _is_gee_infrastructure_failure(state):
        return "__end__"
    if int(state.get("analyst_round") or 0) >= MAX_ANALYST_ROUNDS:
        return "__end__"
    return "analyst"


def route_after_inspector(state: AnalysisState) -> Literal["__end__", "analyst"]:
    """Inspector 通过则结束；不通过则在轮次上限内回到 Analyst。"""
    if state.get("inspector_pass"):
        return "__end__"
    if int(state.get("analyst_round") or 0) >= MAX_ANALYST_ROUNDS:
        return "__end__"
    return "analyst"


def build_analysis_graph():
    """编译遥感分析工作流图。"""
    graph = StateGraph(AnalysisState)
    graph.add_node("director", director_node)
    graph.add_node("analyst", analyst_node)
    graph.add_node("engineer", engineer_node)
    graph.add_node("inspector", inspector_node)

    graph.set_entry_point("director")
    graph.add_edge("director", "analyst")
    graph.add_edge("analyst", "engineer")
    graph.add_conditional_edges(
        "engineer",
        route_after_engineer,
        {"inspector": "inspector", "analyst": "analyst", "__end__": END},
    )
    graph.add_conditional_edges(
        "inspector",
        route_after_inspector,
        {"__end__": END, "analyst": "analyst"},
    )
    return graph.compile()


_compiled_graph = None


def run_analysis_workflow(initial: AnalysisState) -> AnalysisState:
    """执行完整工作流，并设置递归上限防止反复退回导致死循环。"""
    global _compiled_graph
    if _compiled_graph is None:
        _compiled_graph = build_analysis_graph()
    max_steps = max(20, MAX_ANALYST_ROUNDS * 6 + 8)
    return _compiled_graph.invoke(initial, config={"recursion_limit": max_steps})
