-- workspace_session：AI 工作区快照（V2：在 baseline 之后执行，兼容已有表的老库）
CREATE TABLE IF NOT EXISTS workspace_session (
    id VARCHAR(36) PRIMARY KEY,
    title VARCHAR(500) NOT NULL,
    state_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_workspace_session_updated ON workspace_session (updated_at DESC);
