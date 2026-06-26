-- V8__maker_checker_tasks.sql — command-first maker-checker framework (Spec B).
-- Distinct from the legacy passive social.maker_checker_requests (V2): this one
-- actually guards real command paths (ACCOUNT_OPEN first) and replays on approve.

CREATE TABLE maker_checker_tasks (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    command_type  VARCHAR(100) NOT NULL,
    payload       JSONB        NOT NULL,
    made_by       UUID         NOT NULL,
    made_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    checked_by    UUID,
    checked_at    TIMESTAMPTZ,
    reject_reason TEXT,
    result_id     UUID,
    expires_at    TIMESTAMPTZ,            -- reserved TTL seam, unused in v1
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- Inbox/poll lookups filter by status, sometimes also by command_type — lead with status;
-- a separate command_type index serves command_type-only listing.
CREATE INDEX idx_maker_checker_tasks_status       ON maker_checker_tasks(status, command_type);
CREATE INDEX idx_maker_checker_tasks_command_type ON maker_checker_tasks(command_type);

-- Per-command opt-in switch — the source of truth for guarding. This SUPERSEDES the
-- legacy global system_configurations('enable-maker-checker') flag (V2); the
-- command-first framework does NOT read that flag.
CREATE TABLE maker_checker_config (
    command_type VARCHAR(100) PRIMARY KEY,
    enabled      BOOLEAN     NOT NULL DEFAULT false,
    version      BIGINT      NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Default config row: ACCOUNT_OPEN present but OFF (opt-in, back-compatible).
INSERT INTO maker_checker_config (command_type, enabled) VALUES ('ACCOUNT_OPEN', false);

-- New permissions (one APPROVE_* per guarded command, plus the config gate).
INSERT INTO permissions (grouping, code, entity_name, action_name, can_maker_checker) VALUES
  ('accounts','APPROVE_ACCOUNT',     'ACCOUNT',       'APPROVE', true),
  ('admin',   'MANAGE_MAKER_CHECKER','MAKER_CHECKER', 'MANAGE',  false);

-- CREATE_ACCOUNT now has a deferred counterpart.
UPDATE permissions SET can_maker_checker = true WHERE code = 'CREATE_ACCOUNT';

-- PARTNER_APPROVER (Spec A) gains the four-eyes checker authority for account-open.
INSERT INTO role_permissions (role_id, permission_id)
  SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code = 'APPROVE_ACCOUNT'
   WHERE r.name = 'PARTNER_APPROVER';
