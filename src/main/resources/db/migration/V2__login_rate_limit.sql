-- Durable per-IP + normalized-username window, shared by all User instances.
CREATE TABLE login_attempt_windows (
    bucket_hash VARCHAR(64) PRIMARY KEY CHECK (bucket_hash ~ '^[0-9a-f]{64}$'),
    window_started_at TIMESTAMPTZ NOT NULL,
    failure_count INTEGER NOT NULL DEFAULT 0 CHECK (failure_count BETWEEN 0 AND 10)
);
CREATE INDEX login_window_cleanup_idx ON login_attempt_windows (window_started_at);
