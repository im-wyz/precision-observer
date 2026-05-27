# Precision Observer

Precision Observer is a remote-sensing agent project. The repo is intentionally split into four runnable parts:

| Path | Role | Default port |
| --- | --- | --- |
| `front/` | React + Vite map workspace | `3000` |
| `back/raster-api/` | Spring Boot API, upload, task bridge, WebSocket | `8080` |
| `agent-api/` | FastAPI + LangGraph multi-agent service (`src/agent_api`) | `8001` |
| `mcp-servers/` | MCP tool services for GEE, GDAL, raster, storage, inference | `8101-8105` |
| `docker/` | Local PostgreSQL, Redis, MinIO, TiTiler + MCP compose stack | `5432`, `6379`, `8000`, `9000`, `9001`, `8101-8105` |

## Quick Start

From the repo root:

```powershell
.\scripts\check.cmd
.\scripts\dev.cmd
```

`dev.cmd` copies missing `.env.example` files, optionally installs dependencies, starts Docker infra, and opens separate terminals for:

- `agent-api` on `http://localhost:8001`
- `raster-api` on `http://localhost:8080`
- `front` on `http://localhost:3000`

The Spring service needs Maven available as `mvn`. Run `.\scripts\check.cmd` first; if it reports `[missing] mvn`, install Maven or add it to `PATH`.

If dependencies are already installed and you only want to launch services:

```powershell
.\scripts\dev.cmd -SkipInstall
```

## Required Local Services

The app expects these services to be reachable:

| Service | Why it is needed |
| --- | --- |
| PostgreSQL `localhost:5432/gis_db` | Spring Boot metadata and task tables. Started by `docker/docker-compose.yml`. |
| Redis `localhost:6379` | Agent progress and Spring task snapshots. Started by `docker/docker-compose.yml`. |
| TiTiler `localhost:8000` | Raster tile and COG metadata service. Started by `docker/docker-compose.yml`. |
| Docker Desktop / Docker Compose | PostgreSQL, Redis, MinIO, TiTiler, and MCP services in `docker/docker-compose.yml` |

Secrets stay out of Git. Copy and fill only local files:

```powershell
copy agent-api\.env.example agent-api\.env
copy front\.env.example front\.env
```

For GEE, place the service account JSON at:

```text
secrets/gee-service-account.json
```

## Daily Commands

```powershell
# Check local tooling and env files
.\scripts\check.cmd

# Start everything
.\scripts\dev.cmd

# Start only selected parts
.\scripts\dev.cmd -Only infra
.\scripts\dev.cmd -Only agent
.\scripts\dev.cmd -Only api
.\scripts\dev.cmd -Only web

# Clean generated caches/build output
.\scripts\clean.cmd
```

## Project Layout

See `docs/PROJECT_STRUCTURE.md` for the standard project map and what belongs in each folder.
