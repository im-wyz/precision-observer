# Precision Observer — 智能遥感分析架构

## 产品目标

用户用**自然语言**描述遥感需求（如「分析太湖蓝藻面积」），浏览器经 Spring Boot 网关进入任务系统，后台 **多智能体**（LangGraph + Qwen）通过 **MCP 统一工具协议**调用 Google Earth Engine、GDAL、TiTiler、推理服务与存储系统；前端实时展示：

1. **协作日志**（Director / Analyst / Engineer / Inspector）
2. **交互式地图**（Cesium + TiTiler 瓦片 + GeoJSON 边界）
3. **结果报告**（指标卡片 + Markdown 摘要）

## 数据流

**MCP_ENABLED=true（完整形态，见 [MCP_SETUP.md](./MCP_SETUP.md)）：**

```
浏览器 HTTP + WebSocket
  → Spring Boot（网关、会话、上传、任务库、STOMP）
  → agent-api（FastAPI + LangGraph）
      ├─ Director  = LLM + MCP：规划、拆任务、读知识库
      ├─ Analyst   = LLM + MCP：选指数、参数、质检标准
      ├─ Engineer  = LLM + MCP：GEE/GDAL/导出/推理
      └─ Inspector = LLM + 规则引擎 + MCP：验收、报告
  → MCP SSE: gee / gdal / raster / inference / storage
  → GEE / GDAL / GPU 或 Roboflow / MinIO(S3) / PostgreSQL / TiTiler
  → Redis Pub/Sub + 对象存储 + PG 任务资产
  → Spring STOMP → 前端
```

**MCP_ENABLED=false（仅本地调试兜底）：**

```
浏览器 → Spring → agent-api → 内嵌 agent_api/tools → Redis → STOMP → 前端
```

正式链路以 MCP 为工具边界；本地兜底只用于排查 GEE 凭据、网络或 MCP 服务启动问题。

## 目录

| 路径 | 说明 |
|------|------|
| `front/src/views/AIWorkspace.tsx` | 主工作区：左侧对话（多智能体思考过程）+ 右侧 Cesium 地图 |
| `front/src/hooks/useMultiAgentTaskRunner.ts` | `/api/tasks` + STOMP 进度，更新对话气泡 |
| `back/raster-api/` | Spring：`TaskController`、`TaskService`、WebSocket |
| `agent-api/src/agent_api/` | Python：`main.py`、`graph.py`、`agents.py`、`tools/` |
| `secrets/gee-service-account.json` | GEE 凭据（本地，勿提交） |

## 智能体分工

| 角色 | 职责 |
|------|------|
| **Director** | 理解用户意图（蓝藻/NDCI/水体等），制定纲要 |
| **Analyst** | 技术方案；被驳回后修订 |
| **Engineer** | 通过 `gee__run_analysis`、`gdal__*`、`raster__*`、`inference__*` 执行分析，生成 COG、`tile_url`、报告 |
| **Inspector** | 使用规则引擎 + storage/raster/gee 工具验收成果，通过后写入终态 `report_summary` |

## 分析类型（意图识别）

`agent_api/tools/intent.py`：关键词 → `cyanobacteria` | `ndci` | `water` | `general`  

`agent_api/tools/region_catalog.py`：太湖、巢湖等地名 → WGS84 bbox（未传坐标时从文案推断）。

## 环境变量

**agent-api**（`.env`）：

- `OPENAI_API_KEY` / `DASHSCOPE_API_KEY` — Qwen
- `REDIS_URL` — 进度与 Pub/Sub
- `GEE_CREDENTIALS_PATH` — 指向 `secrets/gee-service-account.json`
- `GEE_CREDENTIALS_PATH` — 服务账号 JSON（必需才有真实栅格）
- `GEE_OUTPUT_DIR` / `GEE_LOCAL_HTTP_BASE` — 本地下载与 agent-api 静态访问
- `GEE_UPLOAD_MINIO` + `MINIO_*` — 导出后上传 MinIO（推荐与 Docker 栈一致）
- `TITILER_COG_FETCH_BASE` — TiTiler 进程能访问的 COG 基址（Docker 时常用 `host.docker.internal`）
- `GEE_FORCE_MCP` — MCP 模式下默认 `true`，Engineer 通过 gee-mcp 执行 GEE
- `ENGINEER_ALLOW_LOCAL_GEE_FALLBACK` — MCP 不可用时是否允许本机 GEE 工具兜底，生产建议 `false`
- `GEE_EXPORT_BUCKET` — **可选**，数据量大时用 GCS 异步中转，完成后需同步到 MinIO/本地
- `TITILER_BASE_URL` — 生成 `tile_url`（默认 `http://localhost:8000`）

**Spring** — `agent.base-url`、`spring.data.redis.*`

**前端** — `VITE_RASTER_API_URL`、`VITE_TITILER_URL`；开发环境 WebSocket 默认同源代理 `/ws`

## 本地启动顺序

1. Docker Compose：Redis、PostgreSQL、MinIO、TiTiler、MCP
2. `agent-api`：`uvicorn agent_api.main:app --port 8001`
3. `raster-api`（Spring `:8080`）
4. TiTiler `:8000`（由 Docker Compose 提供地图瓦片）
5. `front`：`npm run dev` → Workspace → **智能遥感分析**

## GEE 导出策略

1. **默认（初级阶段）**：`getDownloadURL` → `GEE_OUTPUT_DIR` → 可选 MinIO → TiTiler 切片；无需单独建 GCS 桶。
2. **大体量（可选）**：设置 `GEE_EXPORT_BUCKET`，提交 GCS 异步任务，完成后用 GDAL/脚本同步到 MinIO 或本地再切片。
3. 未配置 GEE 凭据时分析失败并提示安装 `earthengine-api` 与放置凭据，不再返回 mock COG。

## 安全

- 切勿将 GEE service account 私钥提交 Git 或贴在聊天中；泄露须轮换密钥。
