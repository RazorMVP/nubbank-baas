-- V1__tenant_schema.sql
-- Runs once PER PARTNER SCHEMA at provisioning time.
-- search_path is set to the partner's schema before Flyway runs this.

-- Customers (end-users of the partner)
CREATE TABLE customers (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    external_reference   VARCHAR(100) UNIQUE,
    first_name_encrypted VARCHAR(500) NOT NULL,
    last_name_encrypted  VARCHAR(500) NOT NULL,
    email_encrypted      VARCHAR(500),
    phone_encrypted      VARCHAR(500),
    date_of_birth        DATE,
    gender               VARCHAR(20),
    kyc_status           VARCHAR(50) NOT NULL DEFAULT 'PENDING_KYC',
    kyc_level            VARCHAR(50) NOT NULL DEFAULT 'NONE',
    kyc_provider         VARCHAR(50),
    bvn_encrypted        VARCHAR(500),
    nin_encrypted        VARCHAR(500),
    version              BIGINT NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Savings / checking accounts
CREATE TABLE accounts (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id          UUID NOT NULL REFERENCES customers(id),
    account_number       VARCHAR(20) UNIQUE NOT NULL,
    virtual_account_ref  UUID,
    account_name         VARCHAR(200),
    account_type_label   VARCHAR(100),
    status               VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    balance              NUMERIC(19,4) NOT NULL DEFAULT 0,
    available_balance    NUMERIC(19,4) NOT NULL DEFAULT 0,
    currency_code        VARCHAR(3) NOT NULL DEFAULT 'NGN',
    minimum_balance      NUMERIC(19,4) NOT NULL DEFAULT 0,
    allow_overdraft      BOOLEAN NOT NULL DEFAULT false,
    overdraft_limit      NUMERIC(19,4),
    programmatic_open    BOOLEAN NOT NULL DEFAULT true,
    version              BIGINT NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Immutable transaction ledger
CREATE TABLE transactions (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id       UUID NOT NULL REFERENCES accounts(id),
    transaction_type VARCHAR(50) NOT NULL,
    amount           NUMERIC(19,4) NOT NULL,
    running_balance  NUMERIC(19,4) NOT NULL,
    currency_code    VARCHAR(3) NOT NULL DEFAULT 'NGN',
    reference        VARCHAR(100),
    description      VARCHAR(500),
    payment_id       UUID,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Payments
CREATE TABLE payments (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_account_id       UUID REFERENCES accounts(id),
    destination_account_id  UUID REFERENCES accounts(id),
    amount                  NUMERIC(19,4) NOT NULL,
    currency_code           VARCHAR(3) NOT NULL DEFAULT 'NGN',
    payment_type            VARCHAR(50) NOT NULL,
    status                  VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    reference               VARCHAR(100),
    description             VARCHAR(500),
    idempotency_key         VARCHAR(255),
    version                 BIGINT NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Exchange rates (per-tenant)
CREATE TABLE exchange_rates (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    from_currency   VARCHAR(3) NOT NULL,
    to_currency     VARCHAR(3) NOT NULL,
    rate            NUMERIC(19,8) NOT NULL,
    active          BOOLEAN NOT NULL DEFAULT true,
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (from_currency, to_currency)
);

-- Partner-defined loan products
CREATE TABLE loan_products (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                 VARCHAR(200) NOT NULL,
    short_name           VARCHAR(10) UNIQUE NOT NULL,
    description          TEXT,
    min_principal        NUMERIC(19,4) NOT NULL,
    max_principal        NUMERIC(19,4) NOT NULL,
    default_principal    NUMERIC(19,4) NOT NULL,
    nominal_interest_rate NUMERIC(8,4) NOT NULL,
    repayment_type       VARCHAR(50) NOT NULL DEFAULT 'ANNUITY',
    number_of_repayments INTEGER NOT NULL,
    repayment_every      INTEGER NOT NULL DEFAULT 1,
    repayment_frequency  VARCHAR(20) NOT NULL DEFAULT 'MONTHS',
    active               BOOLEAN NOT NULL DEFAULT true,
    version              BIGINT NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Partner-defined deposit products
CREATE TABLE deposit_products (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                 VARCHAR(200) NOT NULL,
    short_name           VARCHAR(10) UNIQUE NOT NULL,
    account_type         VARCHAR(50) NOT NULL DEFAULT 'SAVINGS',
    minimum_balance      NUMERIC(19,4) NOT NULL DEFAULT 0,
    nominal_interest_rate NUMERIC(8,4) NOT NULL DEFAULT 0,
    allow_overdraft      BOOLEAN NOT NULL DEFAULT false,
    overdraft_limit      NUMERIC(19,4),
    active               BOOLEAN NOT NULL DEFAULT true,
    version              BIGINT NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Audit log (per-tenant, append-only)
CREATE TABLE audit_log (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_type VARCHAR(100) NOT NULL,
    entity_id   UUID,
    action      VARCHAR(100) NOT NULL,
    changed_by  VARCHAR(255),
    old_values  TEXT,
    new_values  TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Indexes
CREATE INDEX idx_customers_ext_ref   ON customers(external_reference);
CREATE INDEX idx_customers_kyc       ON customers(kyc_status);
CREATE INDEX idx_accounts_customer   ON accounts(customer_id);
CREATE INDEX idx_accounts_number     ON accounts(account_number);
CREATE INDEX idx_transactions_acct   ON transactions(account_id, created_at DESC);
CREATE INDEX idx_payments_source     ON payments(source_account_id);
CREATE INDEX idx_payments_dest       ON payments(destination_account_id);
CREATE INDEX idx_audit_entity        ON audit_log(entity_type, entity_id, created_at DESC);
