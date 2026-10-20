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
-- Local additive migration; retains prior Query Store snapshots unchanged.
-- UNKNOWN is used for rows captured before coarse pattern classification.
ALTER TABLE query_performance_snapshots
    ADD COLUMN IF NOT EXISTS source_category VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN';
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

-- Phase 4: one snapshot per poll including zero-blocking samples, plus bounded edge detail.
-- Later production deployment will migrate this schema to versioned Flyway scripts.
CREATE TABLE IF NOT EXISTS blocking_chain_samples (
    id BIGSERIAL PRIMARY KEY,
    target_id VARCHAR(100) NOT NULL,
    collected_at TIMESTAMPTZ NOT NULL,
    edge_count INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_blocking_chain_sample_target_time
    ON blocking_chain_samples (target_id, collected_at DESC);
CREATE TABLE IF NOT EXISTS blocking_chain_edges (
    id BIGSERIAL PRIMARY KEY,
    sample_id BIGINT NOT NULL REFERENCES blocking_chain_samples(id) ON DELETE CASCADE,
    blocked_session_id INTEGER NOT NULL,
    blocking_session_id INTEGER NOT NULL,
    wait_type VARCHAR(128) NOT NULL,
    wait_ms BIGINT NOT NULL,
    elapsed_ms BIGINT NOT NULL,
    blocked_status VARCHAR(40) NOT NULL,
    blocker_status VARCHAR(40) NOT NULL,
    blocker_open_transactions INTEGER NOT NULL,
    root_candidate_session_id INTEGER,
    chain_depth INTEGER NOT NULL,
    cycle_detected BOOLEAN NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_blocking_chain_edges_sample
    ON blocking_chain_edges (sample_id);

-- Phase 5: per-database filtered system_health deadlock summaries.
-- Never store raw XE XML, SQL input buffers, server-side XEL file paths, logins or passwords.
CREATE TABLE IF NOT EXISTS deadlock_events (
    id BIGSERIAL PRIMARY KEY,
    target_id VARCHAR(100) NOT NULL,
    event_key CHAR(64) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    local_participant_count INTEGER NOT NULL,
    victim_session_id INTEGER,
    UNIQUE (target_id, event_key)
);
CREATE INDEX IF NOT EXISTS idx_deadlock_events_target_time
    ON deadlock_events (target_id, occurred_at DESC);
CREATE TABLE IF NOT EXISTS deadlock_participants (
    deadlock_event_id BIGINT NOT NULL REFERENCES deadlock_events(id) ON DELETE CASCADE,
    session_id INTEGER NOT NULL,
    was_victim BOOLEAN NOT NULL,
    PRIMARY KEY (deadlock_event_id, session_id)
);

-- Phase 7: bounded, sanitized wait observations per configured target database.
-- The unredacted wait_resource is classified inside MSSQL and is never transferred.
CREATE TABLE IF NOT EXISTS wait_samples (
    id BIGSERIAL PRIMARY KEY,
    target_id VARCHAR(100) NOT NULL,
    collected_at TIMESTAMPTZ NOT NULL,
    observed_count INTEGER NOT NULL,
    limit_reached BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS idx_wait_samples_target_time
    ON wait_samples (target_id, collected_at DESC);

CREATE TABLE IF NOT EXISTS wait_observations (
    id BIGSERIAL PRIMARY KEY,
    sample_id BIGINT NOT NULL REFERENCES wait_samples(id) ON DELETE CASCADE,
    session_id INTEGER NOT NULL,
    wait_type VARCHAR(128) NOT NULL,
    wait_category VARCHAR(32) NOT NULL,
    resource_kind VARCHAR(32) NOT NULL,
    wait_ms BIGINT NOT NULL,
    elapsed_ms BIGINT NOT NULL,
    request_status VARCHAR(40) NOT NULL,
    blocking_session_id INTEGER
);
CREATE INDEX IF NOT EXISTS idx_wait_observations_sample
    ON wait_observations (sample_id);
