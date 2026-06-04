# Precision Observer

Precision Observer 是一个面向遥感分析的多智能体项目。仓库按可运行模块拆分：

| 路径 | 作用 | 默认端口 |
| --- | --- | --- |
| `front/` | React + Vite 地图工作区 | `3000` |
| `back/raster-api/` | Spring Boot 网关、上传、任务库、WebSocket | `8080` |
| `agent-api/` | FastAPI + LangGraph 多智能体服务（`src/agent_api`） | `8001` |
| `mcp-servers/` | GEE、GDAL、栅格、存储、推理等 MCP 工具服务 | `8101-8105` |
| `docker/` | 本地 PostgreSQL、Redis、MinIO、TiTiler 和 MCP 编排 | `5432`、`6379`、`8000`、`9000`、`9001`、`8101-8105` |

## 快速启动

在仓库根目录执行：

```powershell
.\scripts\check.cmd
.\scripts\dev.cmd
```

`dev.cmd` 会复制缺失的 `.env.example`、按需安装依赖、启动 Docker 基础设施，并分别打开以下服务终端：

- `agent-api`：`http://localhost:8001`
- `raster-api`：`http://localhost:8080`
- `front`：`http://localhost:3000`

Spring 服务需要 `mvn` 可用。先运行 `.\scripts\check.cmd`；如果看到 `[missing] mvn`，请安装 Maven 或把 Maven 加入 `PATH`。

如果依赖已经安装好，只想启动服务：

```powershell
.\scripts\dev.cmd -SkipInstall
```

## 本地服务

系统运行时需要这些服务可访问：

| 服务 | 用途 |
| --- | --- |
| PostgreSQL `localhost:5432/gis_db` | Spring Boot 元数据与任务表，由 `docker/docker-compose.yml` 启动 |
| Redis `localhost:6379` | 智能体进度与 Spring 任务快照，由 `docker/docker-compose.yml` 启动 |
| TiTiler `localhost:8000` | COG 元数据与动态瓦片服务，由 `docker/docker-compose.yml` 启动 |
| Docker Desktop / Docker Compose | 启动 PostgreSQL、Redis、MinIO、TiTiler 与 MCP 服务 |

密钥不提交 Git。只在本地复制并填写：

```powershell
copy agent-api\.env.example agent-api\.env
copy front\.env.example front\.env
```

GEE 服务账号 JSON 放在：

```text
secrets/gee-service-account.json
```

## 常用命令

```powershell
# 检查本地工具链和环境文件
.\scripts\check.cmd

# 启动全部服务
.\scripts\dev.cmd

# 只启动指定模块
.\scripts\dev.cmd -Only infra
.\scripts\dev.cmd -Only agent
.\scripts\dev.cmd -Only api
.\scripts\dev.cmd -Only web

# 清理缓存和构建产物
.\scripts\clean.cmd
```

## 项目结构

标准项目结构与目录职责见 `docs/PROJECT_STRUCTURE.md`。
