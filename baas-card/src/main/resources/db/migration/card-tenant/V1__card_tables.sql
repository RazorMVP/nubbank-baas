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

-- Task 4 — issued cards. TENANT table (NO partner_id; the schema is the boundary).
-- Columns match the Card entity exactly (snake_case).
--
-- PAN SAFETY: the full PAN lives ONLY in pan_encrypted (AES-GCM via FieldEncryptor).
-- pan_hash is the deterministic HMAC-SHA256(app.encryption.key, full PAN) hex
-- fingerprint — UNIQUE within this schema (prevents duplicate cards) and the lookup
-- key Task 6's authorize resolves a card by. The UNIQUE constraint already creates
-- the index used by findByPanHash, so no separate index is needed.
CREATE TABLE IF NOT EXISTS cards (
    id            UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id    UUID          NOT NULL,
    customer_ref  VARCHAR(100),
    pan_encrypted VARCHAR(500)  NOT NULL,
    pan_hash      VARCHAR(64)   NOT NULL UNIQUE,
    pan_last4     VARCHAR(4)    NOT NULL,
    bin           VARCHAR(8)    NOT NULL,
    expiry_ym     VARCHAR(4)    NOT NULL,
    status        VARCHAR(20)   NOT NULL,
    virtual       BOOLEAN       NOT NULL,
    version       BIGINT        NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);
