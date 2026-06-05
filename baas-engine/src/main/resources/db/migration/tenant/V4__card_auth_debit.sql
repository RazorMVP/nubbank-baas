-- Stage 5 (DEF-1C-23/25): cross-service idempotency + reversal-locator for card authorizations.
-- The engine is the money-dedupe authority. auth_key = stan|terminalId|transmissionDateTime
-- (UNIQUE) — a repeat debit with the same key returns the stored outcome and moves no money.
CREATE TABLE card_auth_debit (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    auth_key        VARCHAR(120) NOT NULL UNIQUE,
    account_id      UUID NOT NULL,
    amount          NUMERIC(19,4) NOT NULL,
    currency_code   VARCHAR(3) NOT NULL,
    outcome         VARCHAR(20) NOT NULL,
    transaction_id  UUID,
    reversed        BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_card_auth_debit_created_at ON card_auth_debit (created_at);
