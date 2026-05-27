CREATE TABLE IF NOT EXISTS analysis_task (
    id              VARCHAR(36)  PRIMARY KEY,
    message         TEXT         NOT NULL,
    region_coords   TEXT,
    start_date      DATE         NOT NULL,
    end_date        DATE         NOT NULL,
    status          VARCHAR(32)  NOT NULL,
    answer          TEXT,
    cog_path        VARCHAR(1024),
    download_url    VARCHAR(2048),
    error_message   TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_analysis_task_status ON analysis_task (status);
CREATE INDEX IF NOT EXISTS idx_analysis_task_updated_at ON analysis_task (updated_at DESC);
