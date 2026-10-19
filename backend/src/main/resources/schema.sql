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
