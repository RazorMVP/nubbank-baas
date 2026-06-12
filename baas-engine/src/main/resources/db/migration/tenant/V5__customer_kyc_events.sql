-- Customers track: KYC lifecycle history + searchable-name blind index.

ALTER TABLE customers ADD COLUMN name_search_tokens TEXT[] NOT NULL DEFAULT '{}';
CREATE INDEX idx_customers_name_search_tokens ON customers USING GIN (name_search_tokens);

-- Append-only audit table: rows are only ever INSERTed, never UPDATEd or DELETEd.
-- No `version` column is needed because optimistic locking does not apply here.
CREATE TABLE customer_kyc_events (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id UUID NOT NULL REFERENCES customers(id),
    from_status VARCHAR(50) NOT NULL,
    to_status   VARCHAR(50) NOT NULL,
    reason      TEXT NOT NULL,
    changed_by  VARCHAR(255),
    changed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_customer_kyc_events_customer
    ON customer_kyc_events (customer_id, changed_at DESC);
