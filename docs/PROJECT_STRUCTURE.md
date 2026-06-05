# Project Structure

This repo now follows a standard agent-app layout: product surfaces live at the repo root, while the Python agent runtime is packaged under `src/`.

```text
precision-observer/
  README.md
  .gitignore
  .editorconfig                 # 团队统一编码、缩进和换行
  scripts/
    check.ps1                    # 本地工具与端口检查
    dev.ps1                      # 一键启动本地开发环境
    clean.ps1                    # 清理缓存与构建产物
  docs/
    ARCHITECTURE_V2.md
    MCP_SETUP.md
    PROJECT_STRUCTURE.md
  front/                         # React/Vite 前端
  back/raster-api/               # Spring Boot API
  agent-api/
    pyproject.toml               # Python 包元信息
    requirements.txt             # 运行依赖
    main.py                      # 旧启动方式兼容入口
    src/agent_api/               # 标准 Python 包
      main.py                    # FastAPI 入口
      graph.py                   # LangGraph 编排
      agents.py                  # 本地工具版角色
      agents_mcp.py              # MCP 工具版角色
      redis_progress.py          # Redis 任务进度
      tools/                     # 遥感工具、意图识别、区域解析
      mcp_bridge/                # MCP SSE 桥接
      rules/                     # Inspector 规则
  mcp-servers/                   # 独立 MCP 工具服务
  docker/                        # PostgreSQL、Redis、MinIO、MCP Compose
  secrets/                       # 本地凭据示例与真实凭据存放处
```

## Ownership Rules

- 浏览器 UI 放在 `front/src`。
- Spring API、数据库、Redis 订阅、上传和 WebSocket 放在 `back/raster-api/src`。
- Agent 编排、角色、状态模型和本地 Python 工具放在 `agent-api/src/agent_api`。
- 对外部能力的 MCP 封装放在 `mcp-servers`。
- 可复用启动、检查、清理命令放在 `scripts`。
- `.env`、`.venv`、`node_modules`、`target`、`dist`、`__pycache__`、导出的栅格和真实凭据不要提交。
- 前端包管理统一使用 `npm`；只维护 `front/package-lock.json`。
- IDE 项目文件、编译后的 `.class` 文件、临时瓦片图片和本地原生库只留在开发机，不进入 Git。

## Agent Package Rules

- 新增智能体节点放在 `agent_api/agents.py` 或 `agent_api/agents_mcp.py`，并在 `graph.py` 中接入。
- 新增纯 Python 工具放在 `agent_api/tools`，MCP 服务只做薄封装复用这些工具。
- 新增质检硬规则放在 `agent_api/rules/inspector_rules.json`，规则执行逻辑保持在 `rules/engine.py`。
- 对外启动入口统一使用 `agent_api.main:app`；根目录 `agent-api/main.py` 只做兼容。
