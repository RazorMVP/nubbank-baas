-- V7__partner_rbac.sql — granular partner RBAC: role markers + built-in partner roles.

ALTER TABLE roles ADD COLUMN built_in     BOOLEAN     NOT NULL DEFAULT false;
ALTER TABLE roles ADD COLUMN role_scope   VARCHAR(20) NOT NULL DEFAULT 'OPERATOR';
ALTER TABLE roles ADD COLUMN is_superuser BOOLEAN     NOT NULL DEFAULT false;

-- The existing PARTNER_ADMIN (seeded in V3) becomes the partner superuser marker.
-- Its stale CROSS JOIN role_permissions grant is now irrelevant (the resolver short-circuits
-- on is_superuser); drop it so the marker is the single source of full authority.
UPDATE roles SET is_superuser = true, built_in = true, role_scope = 'SHARED'
 WHERE name = 'PARTNER_ADMIN';
DELETE FROM role_permissions
 WHERE role_id IN (SELECT id FROM roles WHERE name = 'PARTNER_ADMIN');

-- New management permissions.
INSERT INTO permissions (grouping, code, entity_name, action_name) VALUES
  ('admin', 'MANAGE_PARTNER_USERS', 'PARTNER_USER', 'MANAGE'),
  ('admin', 'MANAGE_ROLES',         'ROLE',         'MANAGE'),
  ('admin', 'MANAGE_API_KEYS',      'API_KEY',      'MANAGE');

-- Built-in PARTNER-scoped roles. PARTNER_APPROVER's APPROVE_* grants are added by the
-- maker-checker migration (Spec B); here it is read-only until then.
INSERT INTO roles (id, name, description, disabled, built_in, role_scope, is_superuser, version, created_at, updated_at) VALUES
  (gen_random_uuid(),'PARTNER_MAKER','Operate (create/transact), cannot approve',false,true,'PARTNER',false,0,now(),now()),
  (gen_random_uuid(),'PARTNER_APPROVER','Approve + read (four-eyes checker)',false,true,'PARTNER',false,0,now(),now()),
  (gen_random_uuid(),'PARTNER_VIEWER','Read-only',false,true,'PARTNER',false,0,now(),now());

-- PARTNER_MAKER: read everything + create/operate.
INSERT INTO role_permissions (role_id, permission_id)
  SELECT r.id, p.id FROM roles r JOIN permissions p
    ON p.code IN ('READ_CUSTOMER','CREATE_CUSTOMER','UPDATE_CUSTOMER',
                  'READ_ACCOUNT','CREATE_ACCOUNT','DEPOSIT','WITHDRAW',
                  'READ_LOAN','CREATE_LOAN','INITIATE_PAYMENT','RUN_REPORT')
   WHERE r.name = 'PARTNER_MAKER';

-- PARTNER_APPROVER: all READ_* (+ APPROVE_* later, Spec B).
INSERT INTO role_permissions (role_id, permission_id)
  SELECT r.id, p.id FROM roles r JOIN permissions p
    ON p.code IN ('READ_CUSTOMER','READ_ACCOUNT','READ_LOAN','RUN_REPORT')
   WHERE r.name = 'PARTNER_APPROVER';

-- PARTNER_VIEWER: read-only.
INSERT INTO role_permissions (role_id, permission_id)
  SELECT r.id, p.id FROM roles r JOIN permissions p
    ON p.code IN ('READ_CUSTOMER','READ_ACCOUNT','READ_LOAN','RUN_REPORT')
   WHERE r.name = 'PARTNER_VIEWER';
