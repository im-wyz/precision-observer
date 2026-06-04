# MCP 架构本机搭建（你的选型）

## 架构

```text
浏览器 → Spring(8080) → agent-api(8001) LangGraph + qwen3.6-plus function calling
                              ↓ MCP SSE
         mcp-gee(8101) mcp-gdal(8102) mcp-raster(8103) mcp-storage(8104) mcp-inference(8105)
                              ↓
         GEE / GDAL(Docker) / TiTiler / MinIO(9000) / PostgreSQL / Spring Roboflow
```

## 1. 启动 Docker 栈

```powershell
cd C:\Users\Administrator\Desktop\precision-observer
docker compose -f docker/docker-compose.yml up -d --build
```

| 服务 | 端口 | 说明 |
|------|------|------|
| MinIO API | 9000 | 对象存储 |
| MinIO 控制台 | 9001 | minioadmin / minioadmin |
| TiTiler | 8000 | COG 动态瓦片 |
| mcp-gee | 8101 | GEE / 蓝藻 / NDCI |
| mcp-gdal | 8102 | GDAL 信息 |
| mcp-raster | 8103 | TiTiler 瓦片模板 |
| mcp-storage | 8104 | MinIO 对象 + PostgreSQL 任务元数据 |
| mcp-inference | 8105 | Roboflow 代理（Spring） |

GEE 凭据：将真实 JSON 放到 `secrets/gee-service-account.json`（勿提交 Git）。

**导出路径（无需 GCS 桶）**：GEE → 下载到 `GEE_OUTPUT_DIR` → 自动上传 MinIO（`GEE_UPLOAD_MINIO=auto`）→ TiTiler。  
TiTiler 若在 Docker 内，在 `.env` 设置 `TITILER_COG_FETCH_BASE=http://host.docker.internal:9000`（与 `MINIO_PUBLIC_BASE` 一致）。  
数据量大时再配置 `GEE_EXPORT_BUCKET=gee-export-bucket` 作 GCS 中转。

## 2. 配置 agent-api

```powershell
cd agent-api
copy .env.example .env
# 填写 DASHSCOPE_API_KEY，确认 MCP_ENABLED=true
pip install -r requirements.txt
pip install -e .
uvicorn agent_api.main:app --host 0.0.0.0 --port 8001 --reload
```

## 3. 其余服务

- Redis `6379`
- PostgreSQL `gis_db`
- Spring `8080`
- TiTiler `8000`（可选，真实瓦片）
- 前端 `npm run dev`

## 4. 关闭 MCP（回退旧节点）

`.env` 设置 `MCP_ENABLED=false`，重启 agent-api，使用 `agent_api/agents.py` 直连工具。

## 5. 角色与工具

| 角色 | MCP 前缀 |
|------|----------|
| Director | gee__, storage__ |
| Analyst | gee__, raster__, storage__, gdal__ |
| Engineer | gee__, gdal__, raster__, storage__ |
| Inspector | storage__, raster__, gee__ + `agent_api/rules/inspector_rules.json` |

Roboflow：经 `inference__run_roboflow_workflow` 调 Spring，与前端经典工作区并存。

## 6. 优先场景

1. **蓝藻**：`gee__run_analysis` + `analysis_type=cyanobacteria`
2. **耕地**：可扩展为 gee/gdal/inference 专用 MCP 工具
3. **地物**：`inference__run_roboflow_workflow`（需已上传 COG）
