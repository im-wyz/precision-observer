# Frontend

React + Vite workspace for the Precision Observer map UI.

## Run

From the repo root:

```powershell
.\scripts\dev.cmd -Only web
```

Manual run:

```powershell
cd front
copy .env.example .env
npm install
npm run dev
```

The app runs at `http://localhost:3000`.

## Important Env

| Variable | Default | Purpose |
| --- | --- | --- |
| `VITE_RASTER_API_URL` | `http://localhost:8080` | Spring Boot API base URL |
| `VITE_TITILER_URL` | `http://localhost:8000` | TiTiler base URL |
| `VITE_TITILER_USE_PROXY` | enabled unless set to `false` | Use Vite proxy for tile requests |
| `VITE_WS_URL` | `http://localhost:8080` | WebSocket backend when not using proxy |
