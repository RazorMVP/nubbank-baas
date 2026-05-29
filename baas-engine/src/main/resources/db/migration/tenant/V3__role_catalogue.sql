-- Phase 1C — 30-role partner catalogue (spec §14). One row per role; core-role grants below.
INSERT INTO roles (id, name, description, disabled, version, created_at, updated_at) VALUES
  (gen_random_uuid(),'PARTNER_ADMIN','Tenant super-admin',false,0,now(),now()),
  (gen_random_uuid(),'BRANCH_MANAGER','Branch oversight + approvals',false,0,now(),now()),
  (gen_random_uuid(),'OPERATIONS_MANAGER','Back-office oversight + approvals',false,0,now(),now()),
  (gen_random_uuid(),'PRODUCT_MANAGER','Product catalogue config',false,0,now(),now()),
  (gen_random_uuid(),'SYSTEM_CONFIGURATOR','System configuration',false,0,now(),now()),
  (gen_random_uuid(),'CUSTOMER_SERVICE_OFFICER','Account opening + customer profile/KYC capture',false,0,now(),now()),
  (gen_random_uuid(),'RELATIONSHIP_MANAGER','Customer relationship/portfolio',false,0,now(),now()),
  (gen_random_uuid(),'TELLER','Cash transactions + teller session',false,0,now(),now()),
  (gen_random_uuid(),'HEAD_TELLER','Vault custody + teller settlement',false,0,now(),now()),
  (gen_random_uuid(),'CUSTOMER_SUPPORT','Support tickets + enquiries',false,0,now(),now()),
  (gen_random_uuid(),'ACCOUNT_OFFICER','Account maintenance',false,0,now(),now()),
  (gen_random_uuid(),'KYC_OFFICER','KYC review/approval',false,0,now(),now()),
  (gen_random_uuid(),'LOAN_OFFICER','Loan origination + servicing',false,0,now(),now()),
  (gen_random_uuid(),'CREDIT_ANALYST','Underwriting + credit assessment',false,0,now(),now()),
  (gen_random_uuid(),'CREDIT_APPROVER','Loan/credit approval authority',false,0,now(),now()),
  (gen_random_uuid(),'COLLECTIONS_OFFICER','Arrears + recovery',false,0,now(),now()),
  (gen_random_uuid(),'LOAN_OPERATIONS_OFFICER','Disbursement + repayment posting',false,0,now(),now()),
  (gen_random_uuid(),'PAYMENTS_OFFICER','Transfers + standing instructions',false,0,now(),now()),
  (gen_random_uuid(),'REMITTANCE_OFFICER','Cross-border / diaspora remittances',false,0,now(),now()),
  (gen_random_uuid(),'TREASURY_OFFICER','Liquidity + placements + FX',false,0,now(),now()),
  (gen_random_uuid(),'CARD_OPERATIONS_OFFICER','Card issuance/lifecycle/limits',false,0,now(),now()),
  (gen_random_uuid(),'RECONCILIATION_OFFICER','Settlement + GL reconciliation',false,0,now(),now()),
  (gen_random_uuid(),'COMPLIANCE_OFFICER','Compliance + sanctions screening',false,0,now(),now()),
  (gen_random_uuid(),'AML_ANALYST','Transaction monitoring + SAR/STR',false,0,now(),now()),
  (gen_random_uuid(),'FRAUD_ANALYST','Fraud alerts + case management',false,0,now(),now()),
  (gen_random_uuid(),'RISK_OFFICER','Operational + credit risk policy',false,0,now(),now()),
  (gen_random_uuid(),'FINANCE_OFFICER','GL + journal entries',false,0,now(),now()),
  (gen_random_uuid(),'FINANCIAL_CONTROLLER','Accounting oversight + closures',false,0,now(),now()),
  (gen_random_uuid(),'INTERNAL_AUDITOR','Read-only audit across modules',false,0,now(),now()),
  (gen_random_uuid(),'AUDITOR_READONLY','Pure read-only viewer',false,0,now(),now());

-- Maker-checker flag on the credit-approval permission.
UPDATE permissions SET can_maker_checker = true WHERE code = 'APPROVE_LOAN';

-- Core-role grants (drawn from the 13 permissions seeded by V2). Other roles seeded empty here
-- and granted as controllers are annotated per module (DEF-1C-16).
-- PARTNER_ADMIN -> all permissions.
INSERT INTO role_permissions (role_id, permission_id)
  SELECT r.id, p.id FROM roles r CROSS JOIN permissions p WHERE r.name = 'PARTNER_ADMIN';

-- TELLER -> cash + account read.
INSERT INTO role_permissions (role_id, permission_id)
  SELECT r.id, p.id FROM roles r JOIN permissions p
    ON p.code IN ('READ_ACCOUNT','DEPOSIT','WITHDRAW') WHERE r.name = 'TELLER';

-- CUSTOMER_SERVICE_OFFICER -> customer create/read/update.
INSERT INTO role_permissions (role_id, permission_id)
  SELECT r.id, p.id FROM roles r JOIN permissions p
    ON p.code IN ('READ_CUSTOMER','CREATE_CUSTOMER','UPDATE_CUSTOMER')
   WHERE r.name = 'CUSTOMER_SERVICE_OFFICER';

-- LOAN_OFFICER -> loan read/create.
INSERT INTO role_permissions (role_id, permission_id)
  SELECT r.id, p.id FROM roles r JOIN permissions p
    ON p.code IN ('READ_LOAN','CREATE_LOAN') WHERE r.name = 'LOAN_OFFICER';

-- CREDIT_APPROVER -> loan approve/disburse.
INSERT INTO role_permissions (role_id, permission_id)
  SELECT r.id, p.id FROM roles r JOIN permissions p
    ON p.code IN ('APPROVE_LOAN','DISBURSE_LOAN') WHERE r.name = 'CREDIT_APPROVER';

-- PAYMENTS_OFFICER -> initiate payment.
INSERT INTO role_permissions (role_id, permission_id)
  SELECT r.id, p.id FROM roles r JOIN permissions p
    ON p.code = 'INITIATE_PAYMENT' WHERE r.name = 'PAYMENTS_OFFICER';

-- Read-only roles -> all READ_* + RUN_REPORT.
INSERT INTO role_permissions (role_id, permission_id)
  SELECT r.id, p.id FROM roles r JOIN permissions p
    ON p.code IN ('READ_CUSTOMER','READ_ACCOUNT','READ_LOAN','RUN_REPORT')
   WHERE r.name IN ('AUDITOR_READONLY','INTERNAL_AUDITOR','CUSTOMER_SUPPORT');
