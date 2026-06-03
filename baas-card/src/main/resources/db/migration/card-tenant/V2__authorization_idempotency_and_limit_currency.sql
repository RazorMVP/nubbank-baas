-- V2 — Card/FEP seam hardening (Session 11).
-- Runs on every partner_{uuid} and sandbox_{uuid} schema (card-tenant location),
-- tracked by flyway_schema_history_card.

-- F2: per-card limits gain a currency. A limit with a non-null amount but a
-- currency that does not match the transaction currency is NOT comparable and the
-- authorization is declined (fail-safe). Nullable so existing all-null limit rows
-- (no amounts) remain valid; the service requires it when any amount is set.
ALTER TABLE card_limits ADD COLUMN IF NOT EXISTS currency_code VARCHAR(3);

-- F3: authorization idempotency. ISO 8583 terminals retransmit on timeout; the
-- composite key (STAN | terminalId | transmissionDateTime) dedups a retransmit so it
-- returns the cached decision instead of re-deciding (critical once Phase 2 adds real
-- balance holds). TENANT table (NO partner_id; the schema is the boundary).
-- 'reversed' is flipped by the reversal endpoint (F6). Retention is enforced by a
-- daily purge job (rows older than 24h); lookup is by idem_key alone so lookup and
-- the UNIQUE constraint agree on exactly one row per key.
CREATE TABLE IF NOT EXISTS authorization_idempotency (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    idem_key      VARCHAR(120) NOT NULL UNIQUE,
    decision      VARCHAR(10)  NOT NULL,
    response_code VARCHAR(2)   NOT NULL,
    message       VARCHAR(255),
    reversed      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Purge job filters on created_at; index it.
CREATE INDEX IF NOT EXISTS idx_authz_idem_created_at
    ON authorization_idempotency (created_at);
