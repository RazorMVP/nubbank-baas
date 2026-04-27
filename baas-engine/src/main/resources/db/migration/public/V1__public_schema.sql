-- V1__public_schema.sql
-- Runs once at baas-engine startup on the public schema.
-- Contains platform-level tables shared across all partners.

SET search_path TO public;

-- Partner organisations
CREATE TABLE partner_organizations (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name              VARCHAR(200) NOT NULL,
    status            VARCHAR(50)  NOT NULL DEFAULT 'SANDBOX',
    tier              VARCHAR(50)  NOT NULL DEFAULT 'SANDBOX',
    environment       VARCHAR(50)  NOT NULL DEFAULT 'SANDBOX',
    schema_name       VARCHAR(100) UNIQUE NOT NULL,
    website           VARCHAR(500),
    contact_email     VARCHAR(255),
    approved_by       VARCHAR(255),
    approved_at       TIMESTAMPTZ,
    version           BIGINT NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Partner portal users (log into baas-portal and baas-backoffice)
CREATE TABLE partner_users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id        UUID NOT NULL REFERENCES partner_organizations(id) ON DELETE CASCADE,
    email         VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(50)  NOT NULL DEFAULT 'PARTNER_ADMIN',
    active        BOOLEAN      NOT NULL DEFAULT true,
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- API keys (machine-to-machine authentication)
CREATE TABLE partner_api_keys (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      UUID NOT NULL REFERENCES partner_organizations(id) ON DELETE CASCADE,
    key_hash    VARCHAR(255) UNIQUE NOT NULL,
    key_prefix  VARCHAR(20)  NOT NULL,
    name        VARCHAR(100),
    scopes      JSONB        NOT NULL DEFAULT '[]',
    tier        VARCHAR(50)  NOT NULL DEFAULT 'SANDBOX',
    environment VARCHAR(50)  NOT NULL DEFAULT 'SANDBOX',
    active      BOOLEAN      NOT NULL DEFAULT true,
    last_used_at TIMESTAMPTZ,
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- NUBAN virtual account pool (pre-allocated numbers, assigned at account creation)
CREATE TABLE virtual_account_pool (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_number       VARCHAR(20) UNIQUE NOT NULL,
    bank_code            VARCHAR(3)  NOT NULL,
    assigned             BOOLEAN     NOT NULL DEFAULT false,
    assigned_to_schema   VARCHAR(100),
    assigned_at          TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Audit trail for schema provisioning
CREATE TABLE schema_provision_log (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    partner_id    UUID NOT NULL REFERENCES partner_organizations(id),
    schema_name   VARCHAR(100) NOT NULL,
    status        VARCHAR(50)  NOT NULL DEFAULT 'PENDING',
    flyway_version VARCHAR(50),
    error_message TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at  TIMESTAMPTZ
);

-- Per-API-call billing events (one row per call, for invoice generation)
CREATE TABLE billing_events (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    partner_id  UUID NOT NULL REFERENCES partner_organizations(id),
    endpoint    VARCHAR(200) NOT NULL,
    method      VARCHAR(10)  NOT NULL,
    environment VARCHAR(50)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Idempotency key cache (24-hour window)
CREATE TABLE idempotency_keys (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    key_value    VARCHAR(255) UNIQUE NOT NULL,
    partner_id   UUID        NOT NULL,
    endpoint     VARCHAR(200) NOT NULL,
    response_body TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL
);

-- Webhooks
CREATE TABLE partner_webhooks (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id       UUID NOT NULL REFERENCES partner_organizations(id) ON DELETE CASCADE,
    name         VARCHAR(100) NOT NULL,
    callback_url TEXT NOT NULL,
    secret       VARCHAR(255) NOT NULL,
    events       JSONB NOT NULL DEFAULT '[]',
    active       BOOLEAN NOT NULL DEFAULT true,
    version      BIGINT NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Webhook delivery log
CREATE TABLE webhook_deliveries (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    webhook_id    UUID NOT NULL REFERENCES partner_webhooks(id) ON DELETE CASCADE,
    event_type    VARCHAR(100) NOT NULL,
    delivery_uuid UUID NOT NULL,
    payload       JSONB,
    http_status   INTEGER,
    status        VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Indexes
CREATE INDEX idx_partner_users_org      ON partner_users(org_id);
CREATE INDEX idx_api_keys_org           ON partner_api_keys(org_id);
CREATE INDEX idx_api_keys_hash          ON partner_api_keys(key_hash) WHERE active = true;
CREATE INDEX idx_billing_partner_date   ON billing_events(partner_id, created_at);
CREATE INDEX idx_idempotency_expires    ON idempotency_keys(expires_at);
CREATE INDEX idx_vpool_unassigned       ON virtual_account_pool(assigned) WHERE assigned = false;
CREATE INDEX idx_webhooks_org           ON partner_webhooks(org_id) WHERE active = true;
CREATE INDEX idx_deliveries_retry       ON webhook_deliveries(status, next_retry_at)
    WHERE status = 'PENDING' OR status = 'FAILED';

-- Seed virtual account pool with 10,000 NUBAN numbers for dev/test
-- Bank code 058 (GTBank used as demo)
-- NUBAN check digit formula: weights {3,7,3,3,7,3,3,7,3} over bank_code(3) + serial(6), then (10 - sum%10) % 10
INSERT INTO virtual_account_pool (account_number, bank_code)
SELECT
    '058' ||
    LPAD(gs::text, 6, '0') ||
    (((10 - (
        (3 * SUBSTRING(LPAD(gs::text, 6, '0'), 1, 1)::INT +
         7 * SUBSTRING(LPAD(gs::text, 6, '0'), 2, 1)::INT +
         3 * SUBSTRING(LPAD(gs::text, 6, '0'), 3, 1)::INT +
         3 * SUBSTRING(LPAD(gs::text, 6, '0'), 4, 1)::INT +
         7 * SUBSTRING(LPAD(gs::text, 6, '0'), 5, 1)::INT +
         3 * SUBSTRING(LPAD(gs::text, 6, '0'), 6, 1)::INT) % 10
    ) % 10))::TEXT),
    '058'
FROM generate_series(100000, 109999) gs;
