-- Stage 5 (DEF-1C-24): best-effort FEP authorization audit trail. Non-tenant `fep` schema.
-- PAN safety: stores ONLY bin (first 8) + pan_last4 — a truncated PAN, never the full DE2.
-- ANSI types + app-generated id so the same migration runs on Postgres (prod) and H2 (tests).
CREATE TABLE authorization_log (
    id              UUID PRIMARY KEY,
    received_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    mti             VARCHAR(4)  NOT NULL,
    stan            VARCHAR(6),
    terminal_id     VARCHAR(8),
    bin             VARCHAR(8),
    pan_last4       VARCHAR(4),
    partner_id      UUID,
    schema_name     VARCHAR(120),
    amount_minor    BIGINT,
    currency        VARCHAR(3),
    decision        VARCHAR(10),
    response_code   VARCHAR(2),
    reversal        BOOLEAN NOT NULL,
    latency_ms      INTEGER,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_fep_auth_log_received_at ON authorization_log (received_at);
