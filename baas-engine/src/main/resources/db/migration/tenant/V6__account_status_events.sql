-- Accounts track: lifecycle history (freeze/unfreeze/close) + UPDATE_ACCOUNT permission.

-- Append-only audit table: rows are only ever INSERTed, never UPDATEd or DELETEd.
-- No `version` column is needed because optimistic locking does not apply here.
CREATE TABLE account_status_events (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id  UUID NOT NULL REFERENCES accounts(id),
    from_status VARCHAR(50) NOT NULL,
    to_status   VARCHAR(50) NOT NULL,
    reason      TEXT NOT NULL,
    changed_by  VARCHAR(255),
    changed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_account_status_events_account
    ON account_status_events (account_id, changed_at DESC);

-- New permission: one code for all three lifecycle commands (mirrors single UPDATE_CUSTOMER).
INSERT INTO permissions (grouping, code, entity_name, action_name) VALUES
  ('accounts', 'UPDATE_ACCOUNT', 'ACCOUNT', 'UPDATE');

-- V3's PARTNER_ADMIN cross-join was bounded to the V1/V2 permission codes and does NOT
-- auto-extend (see DEF-1C-16). Grant the new code to PARTNER_ADMIN explicitly here.
INSERT INTO role_permissions (role_id, permission_id)
  SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code = 'UPDATE_ACCOUNT'
   WHERE r.name = 'PARTNER_ADMIN';

-- ACCOUNT_OFFICER -> account maintenance gets the lifecycle permission too.
INSERT INTO role_permissions (role_id, permission_id)
  SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code = 'UPDATE_ACCOUNT'
   WHERE r.name = 'ACCOUNT_OFFICER';
