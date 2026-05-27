# Agent API

FastAPI + LangGraph 多智能体运行时，负责接收遥感分析任务、编排智能体、调用 GEE/MCP 工具，并通过 Redis 推送任务进度。

## 目录结构

```text
agent-api/
  pyproject.toml
  requirements.txt
  .env.example
  main.py                         # 兼容旧命令 uvicorn main:app
  src/agent_api/
    main.py                       # FastAPI 入口
    graph.py                      # LangGraph 工作流
    agents.py                     # 本地工具版智能体
    agents_mcp.py                 # MCP 工具版智能体
    redis_progress.py             # Redis 任务快照与进度发布
    mcp_bridge/                   # MCP SSE 客户端与角色工具映射
    rules/                        # Inspector 规则
    tools/                        # GEE、意图识别、区域解析、报告组装
```

## 运行

从仓库根目录启动：

```powershell
.\scripts\dev.cmd -Only agent
```

手动启动：

```powershell
cd agent-api
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe -m pip install -e .
copy .env.example .env
.\.venv\Scripts\python.exe -m uvicorn agent_api.main:app --host 0.0.0.0 --port 8001 --reload
```

填写 `.env` 中的 `DASHSCOPE_API_KEY` 或 `OPENAI_API_KEY`。如果 `MCP_ENABLED=true`，请先启动 Docker MCP 栈：

```powershell
docker-compose -f ..\docker\docker-compose.yml up -d --build
```

## API

```http
GET /health
POST /analyze
GET /tasks/{task_id}
GET /files/gee_exports/{filename}
```

`region_coords` 支持 bbox `[min_lng, min_lat, max_lng, max_lat]`，也支持多边形点列 `[[lng, lat], ...]`。
