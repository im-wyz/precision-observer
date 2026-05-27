"""Agent API 入口：接收遥感分析任务，后台运行 LangGraph，并通过 Redis 发布进度。"""

from __future__ import annotations

import os
import uuid
from pathlib import Path
from typing import Any

from dotenv import load_dotenv

AGENT_ROOT = Path(__file__).resolve().parents[2]

# 必须先加载 .env，再导入会读取环境变量的 agent 模块。
load_dotenv(AGENT_ROOT / ".env")

from fastapi import BackgroundTasks, FastAPI, HTTPException
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from .agents import MAX_ANALYST_ROUNDS
from .graph import AnalysisState, run_analysis_workflow
from .redis_progress import finalize_task, init_task, publish_progress, read_task
from .tools.intent import detect_analysis_intent
from .tools.region_catalog import resolve_region_coords


def _agent_path(raw: str | None, default: str) -> Path:
    """把 agent-api/.env 中的相对路径解析到 agent-api 根目录下。"""
    value = (raw or default).strip()
    path = Path(value)
    return path if path.is_absolute() else (AGENT_ROOT / path).resolve()


GEE_OUTPUT_DIR = _agent_path(os.getenv("GEE_OUTPUT_DIR"), "data/gee_exports")
GEE_OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

app = FastAPI(
    title="遥感分析智能体服务",
    description="Director -> Analyst -> Engineer -> Inspector 多智能体工作流",
    version="1.0.0",
)

app.mount("/files/gee_exports", StaticFiles(directory=str(GEE_OUTPUT_DIR)), name="gee_exports")


class AnalyzeRequest(BaseModel):
    """前端或 Spring 服务提交的分析请求。"""

    task_id: str | None = Field(default=None, description="可选；不传则自动生成")
    message: str = Field(..., min_length=1, description="用户自然语言需求")
    region_coords: list[Any] = Field(
        default_factory=list,
        description="区域坐标：bbox [min_lng,min_lat,max_lng,max_lat] 或多边形点列",
    )
    start_date: str = Field(..., description="起始日期 YYYY-MM-DD")
    end_date: str = Field(..., description="结束日期 YYYY-MM-DD")


class AnalyzeResponse(BaseModel):
    task_id: str
    status: str


def _build_initial_state(task_id: str, body: AnalyzeRequest) -> AnalysisState:
    region = resolve_region_coords(body.message, body.region_coords)
    intent = detect_analysis_intent(body.message)
    return {
        "task_id": task_id,
        "user_message": body.message.strip(),
        "region_coords": region,
        "start_date": body.start_date,
        "end_date": body.end_date,
        "analysis_intent": intent,
        "analyst_round": 0,
        "engineer_ok": False,
        "inspector_pass": False,
        "status": "queued",
    }


def _final_status(final: AnalysisState) -> str:
    """根据图执行结果归一化任务终态。"""
    if final.get("inspector_pass"):
        return "completed"
    if not final.get("engineer_ok"):
        if int(final.get("analyst_round") or 0) >= MAX_ANALYST_ROUNDS:
            return "engineer_failed"
        return "completed_with_warnings"
    if int(final.get("analyst_round") or 0) >= MAX_ANALYST_ROUNDS:
        return "completed_with_warnings"
    return str(final.get("status") or "completed_with_warnings")


def _answer_from_final(final: AnalysisState) -> str:
    answer = (
        final.get("final_answer")
        or final.get("report_summary")
        or final.get("inspector_output")
        or final.get("analyst_output")
        or ""
    )
    if answer:
        return str(answer)
    if final.get("message"):
        return f"GEE/Engineer 未成功：{final.get('message')}"
    return "流程已结束，但 Engineer 未产出可交付栅格或报告；请查看任务 progress 与 snapshot。"


def _run_workflow(task_id: str, body: AnalyzeRequest) -> None:
    """后台执行 LangGraph，并把最终结果写回 Redis。"""
    initial = _build_initial_state(task_id, body)
    try:
        publish_progress(
            task_id,
            "api",
            "LangGraph 工作流已启动",
            {"status": "running", "analysis_intent": initial["analysis_intent"]},
        )
        final = run_analysis_workflow(initial)
        finalize_task(
            task_id,
            status=_final_status(final),
            answer=_answer_from_final(final),
            region_coords=initial["region_coords"],
            analysis_intent=initial["analysis_intent"],
            analysis_type=final.get("analysis_intent") or initial["analysis_intent"],
            director_output=final.get("director_output"),
            analyst_output=final.get("analyst_output"),
            engineer_output=final.get("engineer_output"),
            inspector_output=final.get("inspector_output"),
            engineer_ok=final.get("engineer_ok"),
            inspector_pass=final.get("inspector_pass"),
            cog_path=final.get("cog_path"),
            download_url=final.get("download_url"),
            tile_url=final.get("tile_url"),
            tileUrl=final.get("tile_url"),
            report_title=final.get("report_title"),
            report_summary=final.get("report_summary"),
            metrics=final.get("metrics"),
            message=final.get("message"),
        )
    except Exception as exc:
        finalize_task(task_id, status="failed", answer=f"工作流异常：{exc}", error=str(exc))


@app.get("/health")
def health() -> dict[str, str]:
    from .agents_mcp import _llm_api_key

    return {
        "status": "ok",
        "llm_key_configured": str(bool(_llm_api_key())).lower(),
    }


@app.post("/analyze", response_model=AnalyzeResponse)
def analyze(body: AnalyzeRequest, background: BackgroundTasks) -> AnalyzeResponse:
    """提交异步遥感分析任务；客户端随后轮询 GET /tasks/{task_id}。"""
    task_id = (body.task_id or "").strip() or str(uuid.uuid4())
    initial = _build_initial_state(task_id, body)
    init_task(
        task_id,
        {
            **initial,
            "analysis_type": initial["analysis_intent"],
            "progress": [{"node": "api", "message": "任务已入队，智能体即将开始协作"}],
        },
    )
    background.add_task(_run_workflow, task_id, body)
    return AnalyzeResponse(task_id=task_id, status="queued")


@app.get("/tasks/{task_id}")
def get_task(task_id: str) -> dict[str, Any]:
    """查询任务状态与进度，内容与 Redis 中的任务快照一致。"""
    data = read_task(task_id)
    if not data:
        raise HTTPException(status_code=404, detail="任务不存在或已过期")
    return data


def run() -> None:
    """本地直接 python -m agent_api.main 时使用。"""
    import uvicorn

    host = os.getenv("AGENT_API_HOST", "0.0.0.0")
    port = int(os.getenv("AGENT_API_PORT", "8001"))
    uvicorn.run("agent_api.main:app", host=host, port=port, reload=True)


if __name__ == "__main__":
    run()
