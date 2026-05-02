-- V2__cob_job_history.sql
-- CoB job history is system-wide (tracks job runs across all tenants),
-- so it lives in the public schema, not per-tenant.

CREATE TABLE cob_job_history (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_name          VARCHAR(100) NOT NULL,
    status            VARCHAR(50) NOT NULL DEFAULT 'RUNNING',
    started_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at      TIMESTAMPTZ,
    records_processed INTEGER NOT NULL DEFAULT 0,
    error_message     TEXT
);

CREATE INDEX idx_cob_history_job ON cob_job_history(job_name, started_at DESC);
