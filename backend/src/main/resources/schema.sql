-- Phase 1 metadata store. A versioned migration tool will replace this before public deployment.
CREATE TABLE IF NOT EXISTS metric_snapshots (
    id BIGSERIAL PRIMARY KEY,
    target_id VARCHAR(100) NOT NULL,
    collected_at TIMESTAMPTZ NOT NULL,
    active_requests BIGINT NOT NULL,
    blocked_requests BIGINT NOT NULL,
    long_running_requests BIGINT NOT NULL,
    max_elapsed_ms BIGINT NOT NULL,
    total_cpu_ms BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_metric_snapshots_target_time
  ON metric_snapshots (target_id, collected_at DESC);

-- Phase 2: sampled Query Store summaries; these do not contain SQL text or parameter values.
CREATE TABLE IF NOT EXISTS query_performance_snapshots (
    id BIGSERIAL PRIMARY KEY,
    target_id VARCHAR(100) NOT NULL,
    collected_at TIMESTAMPTZ NOT NULL,
    query_id BIGINT NOT NULL,
    plan_id BIGINT NOT NULL,
    execution_count BIGINT NOT NULL,
    avg_duration_ms DOUBLE PRECISION NOT NULL,
    avg_cpu_ms DOUBLE PRECISION NOT NULL,
    avg_logical_reads_pages DOUBLE PRECISION NOT NULL,
    last_executed_at TIMESTAMPTZ NOT NULL,
    diagnosis_code VARCHAR(32) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_query_perf_target_time
  ON query_performance_snapshots (target_id, collected_at DESC);

-- Phase 3: persistent, sanitized incidents with at most one OPEN episode per signal.
CREATE TABLE IF NOT EXISTS incidents (
    id BIGSERIAL PRIMARY KEY,
    target_id VARCHAR(100) NOT NULL,
    type VARCHAR(32) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('OPEN', 'RESOLVED')),
    started_at TIMESTAMPTZ NOT NULL,
    last_observed_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    observed_value BIGINT NOT NULL DEFAULT 0,
    summary VARCHAR(255) NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_incidents_one_open
    ON incidents (target_id, type) WHERE status = 'OPEN';
CREATE INDEX IF NOT EXISTS idx_incidents_target_recent
    ON incidents (target_id, last_observed_at DESC);
