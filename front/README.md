# 前端

React + Vite 地图工作区，用于展示对话流、Cesium 地图、遥感分析结果和任务进度。

## 启动

从仓库根目录启动：

```powershell
.\scripts\dev.cmd -Only web
```

手动启动：

```powershell
cd front
copy .env.example .env
npm install
npm run dev
```

默认访问地址：`http://localhost:3000`。

## 重要环境变量

| 变量 | 默认值 | 用途 |
| --- | --- | --- |
| `VITE_RASTER_API_URL` | `http://localhost:8080` | Spring Boot API 基础地址 |
| `VITE_TITILER_URL` | `http://localhost:8000` | TiTiler 基础地址 |
| `VITE_TITILER_USE_PROXY` | 未设为 `false` 时启用 | 是否通过 Vite 代理请求瓦片 |
| `VITE_WS_URL` | `http://localhost:8080` | 不使用代理时的 WebSocket 后端地址 |
