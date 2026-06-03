-- TEST-ONLY stand-in for the engine-owned public tables that baas-card READS.
-- baas-card does NOT own these migrations — baas-engine creates them in production.
-- Numbered V0 so it runs BEFORE card-public/V1 and does not collide with card's
-- own V1 version (Flyway forbids two migrations sharing a version in one run).
-- Creates only the columns the card PartnerOrganization / PartnerApiKey entities
-- map (plus the NOT NULL columns those entities populate).

CREATE TABLE IF NOT EXISTS public.partner_organizations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(200) NOT NULL,
    status          VARCHAR(50)  NOT NULL DEFAULT 'SANDBOX',
    tier            VARCHAR(50)  NOT NULL DEFAULT 'SANDBOX',
    environment     VARCHAR(50)  NOT NULL DEFAULT 'SANDBOX',
    schema_name     VARCHAR(100) UNIQUE NOT NULL,
    website         VARCHAR(500),
    keycloak_issuer VARCHAR(500),
    contact_email   VARCHAR(255),
    approved_by     VARCHAR(255),
    approved_at     TIMESTAMPTZ,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.partner_api_keys (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id       UUID NOT NULL REFERENCES public.partner_organizations(id) ON DELETE CASCADE,
    key_hash     VARCHAR(255) UNIQUE NOT NULL,
    key_prefix   VARCHAR(20)  NOT NULL,
    name         VARCHAR(100),
    scopes       JSONB        NOT NULL DEFAULT '[]',
    tier         VARCHAR(50)  NOT NULL DEFAULT 'SANDBOX',
    environment  VARCHAR(50)  NOT NULL DEFAULT 'SANDBOX',
    active       BOOLEAN      NOT NULL DEFAULT true,
    last_used_at TIMESTAMPTZ,
    version      BIGINT       NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
