CREATE TABLE IF NOT EXISTS public.card_bin_ranges (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bin_start    VARCHAR(8)  NOT NULL,
    bin_end      VARCHAR(8)  NOT NULL,
    partner_id   UUID        NOT NULL,
    schema_name  VARCHAR(63) NOT NULL,
    scheme       VARCHAR(20),
    active       BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    version      BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT bin_range_order CHECK (bin_start <= bin_end)
);
CREATE INDEX IF NOT EXISTS idx_card_bin_ranges_lookup ON public.card_bin_ranges (bin_start, bin_end) WHERE active = TRUE;
CREATE INDEX IF NOT EXISTS idx_card_bin_ranges_partner ON public.card_bin_ranges (partner_id);
