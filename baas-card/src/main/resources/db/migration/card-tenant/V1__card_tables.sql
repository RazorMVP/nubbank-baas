-- Card tenant schema (partner_{uuid} / sandbox_{uuid}). Owned by baas-card.
-- Runs under the card-specific Flyway history table (flyway_schema_history_card).
--
-- This single V1 migration is EXTENDED by later tasks with the full
-- cards and card_limits table definitions (Tasks 4-5).
-- It must remain a single V1 file — tenant migrations are re-run on every fresh
-- partner schema, so new tables are appended here rather than as a new version.

-- Task 3 — card products. TENANT table: it lives in the partner's schema, so it
-- carries NO partner_id column (the schema is the isolation boundary). Columns
-- match the CardProduct entity exactly (snake_case; money = NUMERIC(19,4)).
CREATE TABLE IF NOT EXISTS card_products (
    id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    name                VARCHAR(100)  NOT NULL UNIQUE,
    card_type           VARCHAR(20)   NOT NULL,
    currency            VARCHAR(3)    NOT NULL,
    bin_start           VARCHAR(8),
    default_daily_limit NUMERIC(19,4),
    active              BOOLEAN       NOT NULL DEFAULT TRUE,
    version             BIGINT        NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now()
);
