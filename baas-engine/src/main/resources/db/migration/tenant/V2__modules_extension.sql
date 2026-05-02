-- V2__modules_extension.sql
-- Adds ALL new module tables to the tenant schema.
-- Runs once per partner schema at provisioning time (production + sandbox).

-- ═══════════════════════════════════════════════════════════════
-- CHARGES (fee definitions — applied to loans/accounts)
-- ═══════════════════════════════════════════════════════════════
CREATE TABLE charges (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name             VARCHAR(200) NOT NULL,
    charge_type      VARCHAR(50) NOT NULL,
    calculation_type VARCHAR(50) NOT NULL DEFAULT 'FLAT',
    amount           NUMERIC(19,4) NOT NULL,
    currency_code    VARCHAR(3) NOT NULL DEFAULT 'NGN',
    active           BOOLEAN NOT NULL DEFAULT true,
    version          BIGINT NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ═══════════════════════════════════════════════════════════════
-- LOANS
-- ═══════════════════════════════════════════════════════════════
CREATE TABLE loans (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id              UUID NOT NULL REFERENCES customers(id),
    loan_product_id          UUID NOT NULL REFERENCES loan_products(id),
    loan_account_number      VARCHAR(20) UNIQUE NOT NULL,
    principal_amount         NUMERIC(19,4) NOT NULL,
    approved_principal       NUMERIC(19,4),
    outstanding_balance      NUMERIC(19,4) NOT NULL DEFAULT 0,
    interest_rate            NUMERIC(8,4) NOT NULL,
    number_of_repayments     INTEGER NOT NULL,
    repayment_every          INTEGER NOT NULL DEFAULT 1,
    repayment_frequency      VARCHAR(20) NOT NULL DEFAULT 'MONTHS',
    disbursement_date        DATE,
    expected_disbursement_date DATE,
    maturity_date            DATE,
    status                   VARCHAR(50) NOT NULL DEFAULT 'SUBMITTED',
    approved_by              VARCHAR(255),
    approved_on              TIMESTAMPTZ,
    rejected_on              TIMESTAMPTZ,
    rejection_reason         TEXT,
    disbursed_by             VARCHAR(255),
    disbursed_on             TIMESTAMPTZ,
    linked_account_id        UUID REFERENCES accounts(id),
    currency_code            VARCHAR(3) NOT NULL DEFAULT 'NGN',
    version                  BIGINT NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE loan_repayment_schedule (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    loan_id        UUID NOT NULL REFERENCES loans(id),
    installment_no INTEGER NOT NULL,
    due_date       DATE NOT NULL,
    principal_due  NUMERIC(19,4) NOT NULL DEFAULT 0,
    interest_due   NUMERIC(19,4) NOT NULL DEFAULT 0,
    total_due      NUMERIC(19,4) NOT NULL DEFAULT 0,
    principal_paid NUMERIC(19,4) NOT NULL DEFAULT 0,
    interest_paid  NUMERIC(19,4) NOT NULL DEFAULT 0,
    total_paid     NUMERIC(19,4) NOT NULL DEFAULT 0,
    status         VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    completed_on   DATE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE loan_charges (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    loan_id     UUID NOT NULL REFERENCES loans(id) ON DELETE CASCADE,
    charge_id   UUID NOT NULL REFERENCES charges(id),
    amount      NUMERIC(19,4) NOT NULL,
    amount_paid NUMERIC(19,4) NOT NULL DEFAULT 0,
    waived      BOOLEAN NOT NULL DEFAULT false,
    due_date    DATE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_loan_charge_paid CHECK (amount_paid <= amount)
);

-- ═══════════════════════════════════════════════════════════════
-- LOAN EXTENSIONS (guarantors, collateral, reschedule)
-- ═══════════════════════════════════════════════════════════════
CREATE TABLE loan_guarantors (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    loan_id        UUID NOT NULL REFERENCES loans(id) ON DELETE CASCADE,
    guarantor_type VARCHAR(50) NOT NULL DEFAULT 'EXISTING_CUSTOMER',
    customer_id    UUID REFERENCES customers(id),
    first_name     VARCHAR(200),
    last_name      VARCHAR(200),
    email          VARCHAR(255),
    phone          VARCHAR(50),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE loan_collaterals (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    loan_id       UUID NOT NULL REFERENCES loans(id) ON DELETE CASCADE,
    description   TEXT NOT NULL,
    value         NUMERIC(19,4) NOT NULL,
    currency_code VARCHAR(3) NOT NULL DEFAULT 'NGN',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE loan_reschedule_requests (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    loan_id              UUID NOT NULL REFERENCES loans(id) ON DELETE CASCADE,
    status               VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    reschedule_from_date DATE,
    new_interest_rate    NUMERIC(8,4),
    grace_on_principal   INTEGER NOT NULL DEFAULT 0,
    grace_on_interest    INTEGER NOT NULL DEFAULT 0,
    extra_terms          INTEGER NOT NULL DEFAULT 0,
    recalculate_interest BOOLEAN NOT NULL DEFAULT true,
    reason               TEXT,
    version              BIGINT NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ═══════════════════════════════════════════════════════════════
-- FIXED + RECURRING DEPOSITS
-- ═══════════════════════════════════════════════════════════════
CREATE TABLE fixed_deposit_accounts (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id       UUID NOT NULL REFERENCES customers(id),
    product_id        UUID NOT NULL REFERENCES deposit_products(id),
    account_number    VARCHAR(20) UNIQUE NOT NULL,
    deposit_amount    NUMERIC(19,4) NOT NULL,
    maturity_amount   NUMERIC(19,4),
    interest_rate     NUMERIC(8,4) NOT NULL,
    deposit_term      INTEGER NOT NULL,
    deposit_term_unit VARCHAR(20) NOT NULL DEFAULT 'MONTHS',
    deposit_date      DATE,
    maturity_date     DATE,
    status            VARCHAR(50) NOT NULL DEFAULT 'SUBMITTED',
    linked_account_id UUID REFERENCES accounts(id),
    currency_code     VARCHAR(3) NOT NULL DEFAULT 'NGN',
    version           BIGINT NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE recurring_deposit_accounts (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id           UUID NOT NULL REFERENCES customers(id),
    product_id            UUID NOT NULL REFERENCES deposit_products(id),
    account_number        VARCHAR(20) UNIQUE NOT NULL,
    mandatory_installment NUMERIC(19,4) NOT NULL,
    total_deposited       NUMERIC(19,4) NOT NULL DEFAULT 0,
    maturity_amount       NUMERIC(19,4),
    interest_rate         NUMERIC(8,4) NOT NULL,
    deposit_term          INTEGER NOT NULL,
    deposit_term_unit     VARCHAR(20) NOT NULL DEFAULT 'MONTHS',
    start_date            DATE,
    maturity_date         DATE,
    status                VARCHAR(50) NOT NULL DEFAULT 'SUBMITTED',
    linked_account_id     UUID REFERENCES accounts(id),
    currency_code         VARCHAR(3) NOT NULL DEFAULT 'NGN',
    version               BIGINT NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ═══════════════════════════════════════════════════════════════
-- SHARE PRODUCTS + ACCOUNTS
-- ═══════════════════════════════════════════════════════════════
CREATE TABLE share_products (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name           VARCHAR(200) NOT NULL,
    short_name     VARCHAR(10) UNIQUE NOT NULL,
    description    TEXT,
    total_shares   BIGINT NOT NULL DEFAULT 0,
    shares_issued  BIGINT NOT NULL DEFAULT 0,
    unit_price     NUMERIC(19,4) NOT NULL,
    minimum_shares INTEGER NOT NULL DEFAULT 1,
    maximum_shares INTEGER,
    currency_code  VARCHAR(3) NOT NULL DEFAULT 'NGN',
    active         BOOLEAN NOT NULL DEFAULT true,
    version        BIGINT NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE share_accounts (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id       UUID NOT NULL REFERENCES customers(id),
    product_id        UUID NOT NULL REFERENCES share_products(id),
    account_number    VARCHAR(20) UNIQUE NOT NULL,
    total_shares_held BIGINT NOT NULL DEFAULT 0,
    total_amount      NUMERIC(19,4) NOT NULL DEFAULT 0,
    status            VARCHAR(50) NOT NULL DEFAULT 'SUBMITTED',
    linked_account_id UUID REFERENCES accounts(id),
    currency_code     VARCHAR(3) NOT NULL DEFAULT 'NGN',
    version           BIGINT NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE share_transactions (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id       UUID NOT NULL REFERENCES share_accounts(id),
    transaction_type VARCHAR(50) NOT NULL,
    number_of_shares BIGINT NOT NULL,
    unit_price       NUMERIC(19,4) NOT NULL,
    total_amount     NUMERIC(19,4) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ═══════════════════════════════════════════════════════════════
-- GL / ACCOUNTING
-- ═══════════════════════════════════════════════════════════════
CREATE TABLE gl_accounts (
    id                             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                           VARCHAR(200) NOT NULL,
    gl_code                        VARCHAR(50) UNIQUE NOT NULL,
    account_type                   VARCHAR(50) NOT NULL,
    account_usage                  VARCHAR(50) NOT NULL DEFAULT 'DETAIL',
    parent_id                      UUID REFERENCES gl_accounts(id),
    manual_journal_entries_allowed BOOLEAN NOT NULL DEFAULT true,
    description                    TEXT,
    disabled                       BOOLEAN NOT NULL DEFAULT false,
    version                        BIGINT NOT NULL DEFAULT 0,
    created_at                     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE journal_entries (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entry_date    DATE NOT NULL,
    reference     VARCHAR(100),
    description   TEXT,
    entity_type   VARCHAR(100),
    entity_id     UUID,
    manual        BOOLEAN NOT NULL DEFAULT false,
    reversed      BOOLEAN NOT NULL DEFAULT false,
    reversed_by_id UUID REFERENCES journal_entries(id),
    created_by    VARCHAR(255),
    version       BIGINT NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE journal_entry_lines (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    journal_id    UUID NOT NULL REFERENCES journal_entries(id),
    gl_account_id UUID NOT NULL REFERENCES gl_accounts(id),
    entry_type    VARCHAR(10) NOT NULL,
    amount        NUMERIC(19,4) NOT NULL,
    currency_code VARCHAR(3) NOT NULL DEFAULT 'NGN'
);

CREATE TABLE gl_closures (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    closing_date DATE NOT NULL UNIQUE,
    description  TEXT,
    closed_by    VARCHAR(255),
    closed_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE financial_activity_accounts (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    activity_name VARCHAR(100) UNIQUE NOT NULL,
    gl_account_id UUID NOT NULL REFERENCES gl_accounts(id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ═══════════════════════════════════════════════════════════════
-- ACCOUNTING RULES + PROVISIONING CRITERIA
-- ═══════════════════════════════════════════════════════════════
CREATE TABLE accounting_rules (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                   VARCHAR(200) NOT NULL,
    debit_account_id       UUID REFERENCES gl_accounts(id),
    credit_account_id      UUID REFERENCES gl_accounts(id),
    allow_multiple_debits  BOOLEAN NOT NULL DEFAULT false,
    allow_multiple_credits BOOLEAN NOT NULL DEFAULT false,
    version                BIGINT NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE provisioning_criteria (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(200) UNIQUE NOT NULL,
    version    BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE provisioning_criteria_definitions (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    criteria_id          UUID NOT NULL REFERENCES provisioning_criteria(id) ON DELETE CASCADE,
    category_name        VARCHAR(100) NOT NULL,
    min_age              INTEGER NOT NULL,
    max_age              INTEGER NOT NULL,
    provision_percentage NUMERIC(5,2) NOT NULL,
    liability_account_id UUID REFERENCES gl_accounts(id),
    expense_account_id   UUID REFERENCES gl_accounts(id)
);

-- ═══════════════════════════════════════════════════════════════
-- TELLER / CASH MANAGEMENT
-- ═══════════════════════════════════════════════════════════════
CREATE TABLE tellers (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(200) NOT NULL,
    description TEXT,
    status      VARCHAR(50) NOT NULL DEFAULT 'INACTIVE',
    office_id   UUID,
    version     BIGINT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE cashiers (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    teller_id   UUID NOT NULL REFERENCES tellers(id),
    staff_id    UUID,
    description TEXT,
    is_full_day BOOLEAN NOT NULL DEFAULT true,
    start_time  TIME,
    end_time    TIME,
    version     BIGINT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE teller_sessions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    teller_id       UUID NOT NULL REFERENCES tellers(id),
    cashier_id      UUID NOT NULL REFERENCES cashiers(id),
    session_date    DATE NOT NULL,
    status          VARCHAR(50) NOT NULL DEFAULT 'OPEN',
    opening_balance NUMERIC(19,4) NOT NULL DEFAULT 0,
    closing_balance NUMERIC(19,4),
    actual_cash     NUMERIC(19,4),
    difference      NUMERIC(19,4),
    currency_code   VARCHAR(3) NOT NULL DEFAULT 'NGN',
    version         BIGINT NOT NULL DEFAULT 0,
    opened_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at       TIMESTAMPTZ,
    UNIQUE (cashier_id, session_date)
);

CREATE TABLE cash_transactions (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id       UUID NOT NULL REFERENCES teller_sessions(id),
    transaction_type VARCHAR(50) NOT NULL,
    amount           NUMERIC(19,4) NOT NULL,
    account_id       UUID REFERENCES accounts(id),
    description      VARCHAR(500),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ═══════════════════════════════════════════════════════════════
-- OFFICE + STAFF
-- ═══════════════════════════════════════════════════════════════
CREATE TABLE offices (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name         VARCHAR(200) NOT NULL,
    parent_id    UUID REFERENCES offices(id),
    hierarchy    VARCHAR(500),
    opening_date DATE,
    external_id  VARCHAR(100),
    version      BIGINT NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE staff (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    office_id       UUID REFERENCES offices(id),
    first_name      VARCHAR(200) NOT NULL,
    last_name       VARCHAR(200) NOT NULL,
    display_name    VARCHAR(400),
    is_loan_officer BOOLEAN NOT NULL DEFAULT false,
    external_id     VARCHAR(100),
    active          BOOLEAN NOT NULL DEFAULT true,
    joining_date    DATE,
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ═══════════════════════════════════════════════════════════════
-- GROUPS + CENTERS
-- ═══════════════════════════════════════════════════════════════
CREATE TABLE groups (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(200) NOT NULL,
    external_id     VARCHAR(100),
    status          VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    office_id       UUID REFERENCES offices(id),
    staff_id        UUID REFERENCES staff(id),
    activation_date DATE,
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE group_members (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id    UUID NOT NULL REFERENCES groups(id),
    customer_id UUID NOT NULL REFERENCES customers(id),
    joined_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (group_id, customer_id)
);

CREATE TABLE centers (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(200) NOT NULL,
    external_id     VARCHAR(100),
    status          VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    office_id       UUID REFERENCES offices(id),
    staff_id        UUID REFERENCES staff(id),
    activation_date DATE,
    meeting_time    VARCHAR(50),
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE center_groups (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    center_id UUID NOT NULL REFERENCES centers(id),
    group_id  UUID NOT NULL REFERENCES groups(id),
    UNIQUE (center_id, group_id)
);

-- ═══════════════════════════════════════════════════════════════
-- SYSTEM CONFIGURATION
-- ═══════════════════════════════════════════════════════════════
CREATE TABLE system_configurations (
    config_key  VARCHAR(100) PRIMARY KEY,
    value       TEXT,
    description TEXT,
    enabled     BOOLEAN NOT NULL DEFAULT true,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE codes (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name           VARCHAR(100) UNIQUE NOT NULL,
    system_defined BOOLEAN NOT NULL DEFAULT false,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE code_values (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code_id     UUID NOT NULL REFERENCES codes(id) ON DELETE CASCADE,
    value       VARCHAR(200) NOT NULL,
    description TEXT,
    position    INTEGER NOT NULL DEFAULT 0,
    active      BOOLEAN NOT NULL DEFAULT true,
    UNIQUE (code_id, value)
);

CREATE TABLE payment_types (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(100) NOT NULL,
    description     TEXT,
    is_cash_payment BOOLEAN NOT NULL DEFAULT false,
    system_defined  BOOLEAN NOT NULL DEFAULT false,
    position        INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE holidays (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                      VARCHAR(200) NOT NULL,
    from_date                 DATE NOT NULL,
    to_date                   DATE NOT NULL,
    repayment_scheduling_type VARCHAR(50) NOT NULL DEFAULT 'NEXT_WORKING_DAY',
    status                    VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    description               TEXT,
    version                   BIGINT NOT NULL DEFAULT 0,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ═══════════════════════════════════════════════════════════════
-- FLOATING RATES + TAXES
-- ═══════════════════════════════════════════════════════════════
CREATE TABLE floating_rates (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                 VARCHAR(200) NOT NULL,
    is_base_lending_rate BOOLEAN NOT NULL DEFAULT false,
    is_active            BOOLEAN NOT NULL DEFAULT true,
    version              BIGINT NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE floating_rate_periods (
    id                              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    floating_rate_id                UUID NOT NULL REFERENCES floating_rates(id) ON DELETE CASCADE,
    from_date                       DATE NOT NULL,
    interest_rate                   NUMERIC(8,4) NOT NULL,
    is_differential_to_base_lending BOOLEAN NOT NULL DEFAULT false
);

CREATE TABLE tax_components (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name              VARCHAR(200) NOT NULL,
    percentage        NUMERIC(19,6) NOT NULL,
    credit_account_id UUID REFERENCES gl_accounts(id),
    debit_account_id  UUID REFERENCES gl_accounts(id),
    start_date        DATE NOT NULL,
    version           BIGINT NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE tax_groups (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(200) NOT NULL,
    version    BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE tax_group_mappings (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tax_group_id     UUID NOT NULL REFERENCES tax_groups(id) ON DELETE CASCADE,
    tax_component_id UUID NOT NULL REFERENCES tax_components(id),
    start_date       DATE NOT NULL
);

-- ═══════════════════════════════════════════════════════════════
-- ROLES + PERMISSIONS
-- ═══════════════════════════════════════════════════════════════
CREATE TABLE roles (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    disabled    BOOLEAN NOT NULL DEFAULT false,
    version     BIGINT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE permissions (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    grouping          VARCHAR(100) NOT NULL,
    code              VARCHAR(200) UNIQUE NOT NULL,
    entity_name       VARCHAR(100),
    action_name       VARCHAR(100),
    can_maker_checker BOOLEAN NOT NULL DEFAULT false
);

CREATE TABLE role_permissions (
    role_id       UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission_id UUID NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE user_roles (
    user_id UUID NOT NULL,
    role_id UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

-- ═══════════════════════════════════════════════════════════════
-- CLIENT EXTENSIONS
-- ═══════════════════════════════════════════════════════════════
CREATE TABLE client_identifiers (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id   UUID NOT NULL REFERENCES customers(id),
    document_type VARCHAR(100) NOT NULL,
    document_key  VARCHAR(200) NOT NULL,
    description   TEXT,
    expiry_date   DATE,
    active        BOOLEAN NOT NULL DEFAULT true,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE client_addresses (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id    UUID NOT NULL REFERENCES customers(id),
    address_type   VARCHAR(50) NOT NULL DEFAULT 'HOME',
    street         VARCHAR(500),
    city           VARCHAR(200),
    state_province VARCHAR(200),
    country_code   VARCHAR(3),
    postal_code    VARCHAR(20),
    is_active      BOOLEAN NOT NULL DEFAULT true,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE client_images (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id     UUID UNIQUE NOT NULL REFERENCES customers(id),
    file_name       VARCHAR(255),
    content_type    VARCHAR(100) NOT NULL DEFAULT 'image/jpeg',
    file_size_bytes BIGINT,
    storage_path    TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ═══════════════════════════════════════════════════════════════
-- NOTES + DOCUMENTS (polymorphic — entity_type + entity_id)
-- ═══════════════════════════════════════════════════════════════
CREATE TABLE entity_notes (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_type VARCHAR(100) NOT NULL,
    entity_id   UUID NOT NULL,
    note        TEXT NOT NULL,
    created_by  VARCHAR(255),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE entity_documents (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_type     VARCHAR(100) NOT NULL,
    entity_id       UUID NOT NULL,
    file_name       VARCHAR(255) NOT NULL,
    content_type    VARCHAR(100),
    file_size_bytes BIGINT,
    storage_path    TEXT,
    description     TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ═══════════════════════════════════════════════════════════════
-- MAKER-CHECKER + DATATABLES
-- ═══════════════════════════════════════════════════════════════
CREATE TABLE maker_checker_requests (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_type        VARCHAR(100) NOT NULL,
    entity_id          UUID,
    action             VARCHAR(100) NOT NULL,
    command_as_json    TEXT NOT NULL,
    status             VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    made_by_user_id    UUID NOT NULL,
    checked_by_user_id UUID,
    rejection_reason   TEXT,
    version            BIGINT NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE data_table_registrations (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    registered_table_name  VARCHAR(200) UNIQUE NOT NULL,
    application_table_name VARCHAR(200) NOT NULL,
    allow_multiple_rows    BOOLEAN NOT NULL DEFAULT false,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ═══════════════════════════════════════════════════════════════
-- OPEN BANKING CONSENTS
-- ═══════════════════════════════════════════════════════════════
CREATE TABLE open_banking_consents (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id      UUID REFERENCES customers(id),
    tpp_client_id    VARCHAR(255) NOT NULL,
    tpp_name         VARCHAR(200),
    status           VARCHAR(50) NOT NULL DEFAULT 'AWAITING_AUTHORISATION',
    scopes           JSONB NOT NULL DEFAULT '[]',
    expiry_date      DATE,
    access_frequency VARCHAR(50),
    authorised_at    TIMESTAMPTZ,
    revoked_at       TIMESTAMPTZ,
    version          BIGINT NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ═══════════════════════════════════════════════════════════════
-- NOTIFICATIONS
-- ═══════════════════════════════════════════════════════════════
CREATE TABLE notification_events (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type    VARCHAR(100) NOT NULL,
    entity_type   VARCHAR(100),
    entity_id     UUID,
    channel       VARCHAR(50) NOT NULL DEFAULT 'EMAIL',
    recipient     VARCHAR(255),
    subject       VARCHAR(500),
    payload       JSONB,
    status        VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    error_message TEXT,
    sent_at       TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ═══════════════════════════════════════════════════════════════
-- SMS CAMPAIGNS + REPORT MAILING
-- ═══════════════════════════════════════════════════════════════
CREATE TABLE sms_campaigns (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name             VARCHAR(200) NOT NULL,
    campaign_type    VARCHAR(50) NOT NULL DEFAULT 'INDIVIDUAL',
    trigger_type     VARCHAR(50) NOT NULL DEFAULT 'DIRECT',
    message_template TEXT NOT NULL,
    recurrence       VARCHAR(200),
    status           VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    activation_date  DATE,
    version          BIGINT NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE sms_messages (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    campaign_id     UUID NOT NULL REFERENCES sms_campaigns(id),
    customer_id     UUID REFERENCES customers(id),
    phone_number    VARCHAR(50),
    message         TEXT NOT NULL,
    delivery_status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ
);

CREATE TABLE report_mailing_jobs (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                VARCHAR(200) NOT NULL,
    report_name         VARCHAR(200) NOT NULL,
    email_recipients    TEXT NOT NULL,
    output_type         VARCHAR(20) NOT NULL DEFAULT 'CSV',
    recurrence          VARCHAR(200),
    params              JSONB NOT NULL DEFAULT '{}',
    run_count           INTEGER NOT NULL DEFAULT 0,
    previous_run_status VARCHAR(50),
    previous_run_start  TIMESTAMPTZ,
    previous_run_end    TIMESTAMPTZ,
    active              BOOLEAN NOT NULL DEFAULT true,
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ═══════════════════════════════════════════════════════════════
-- STANDING INSTRUCTIONS + BENEFICIARIES
-- ═══════════════════════════════════════════════════════════════
CREATE TABLE standing_instructions (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id            UUID NOT NULL REFERENCES customers(id),
    source_account_id      UUID NOT NULL REFERENCES accounts(id),
    destination_account_id UUID NOT NULL REFERENCES accounts(id),
    name                   VARCHAR(200) NOT NULL,
    instruction_type       VARCHAR(50) NOT NULL DEFAULT 'FIXED',
    priority               VARCHAR(50) NOT NULL DEFAULT 'MEDIUM',
    status                 VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    amount                 NUMERIC(19,4),
    recurrence_frequency   VARCHAR(20) NOT NULL DEFAULT 'MONTHS',
    recurrence_interval    INTEGER NOT NULL DEFAULT 1,
    valid_from             DATE,
    valid_to               DATE,
    version                BIGINT NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE beneficiaries (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id    UUID NOT NULL REFERENCES customers(id),
    account_number VARCHAR(20) NOT NULL,
    account_name   VARCHAR(200),
    bank_code      VARCHAR(10),
    bank_name      VARCHAR(200),
    transfer_limit NUMERIC(19,4),
    active         BOOLEAN NOT NULL DEFAULT true,
    version        BIGINT NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ═══════════════════════════════════════════════════════════════
-- TWO-FACTOR AUTHENTICATION
-- ═══════════════════════════════════════════════════════════════
CREATE TABLE two_factor_tokens (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL,
    token_hash      VARCHAR(255) NOT NULL,
    delivery_method VARCHAR(20) NOT NULL DEFAULT 'EMAIL',
    recipient       VARCHAR(255) NOT NULL,
    verified        BOOLEAN NOT NULL DEFAULT false,
    expires_at      TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ═══════════════════════════════════════════════════════════════
-- CREDIT BUREAU + SURVEYS
-- ═══════════════════════════════════════════════════════════════
CREATE TABLE credit_bureau_integrations (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(200) NOT NULL,
    impl_class VARCHAR(500) NOT NULL,
    country    VARCHAR(3),
    active     BOOLEAN NOT NULL DEFAULT false,
    version    BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE credit_bureau_product_mappings (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    loan_product_id        UUID NOT NULL REFERENCES loan_products(id),
    credit_bureau_id       UUID NOT NULL REFERENCES credit_bureau_integrations(id),
    credit_check_mandatory BOOLEAN NOT NULL DEFAULT false,
    UNIQUE (loan_product_id, credit_bureau_id)
);

CREATE TABLE surveys (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    key          VARCHAR(100) UNIQUE NOT NULL,
    name         VARCHAR(200) NOT NULL,
    description  TEXT,
    country_code VARCHAR(3),
    active       BOOLEAN NOT NULL DEFAULT true,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE survey_questions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    survey_id   UUID NOT NULL REFERENCES surveys(id) ON DELETE CASCADE,
    question    TEXT NOT NULL,
    sequence_no INTEGER NOT NULL
);

CREATE TABLE survey_responses (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    question_id UUID NOT NULL REFERENCES survey_questions(id) ON DELETE CASCADE,
    response    VARCHAR(500) NOT NULL,
    value       INTEGER NOT NULL DEFAULT 0,
    sequence_no INTEGER NOT NULL
);

CREATE TABLE survey_scorecards (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    survey_id   UUID NOT NULL REFERENCES surveys(id),
    customer_id UUID NOT NULL REFERENCES customers(id),
    created_by  VARCHAR(255),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE survey_scorecard_scores (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scorecard_id UUID NOT NULL REFERENCES survey_scorecards(id) ON DELETE CASCADE,
    question_id  UUID NOT NULL,
    response_id  UUID NOT NULL,
    score        INTEGER NOT NULL DEFAULT 0
);

-- ═══════════════════════════════════════════════════════════════
-- COMPLIANCE
-- ═══════════════════════════════════════════════════════════════
CREATE TABLE sanctions_screening_log (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_type VARCHAR(50) NOT NULL,
    entity_id   UUID NOT NULL,
    screen_type VARCHAR(50) NOT NULL,
    result      VARCHAR(50) NOT NULL DEFAULT 'CLEAR',
    notes       TEXT,
    provider    VARCHAR(100) NOT NULL DEFAULT 'INTERNAL_STUB',
    screened_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ═══════════════════════════════════════════════════════════════
-- COB SCHEDULER
-- (cob_job_history lives in the public schema — system-wide, not per-tenant)
-- See: db/migration/public/V2__cob_job_history.sql
-- ═══════════════════════════════════════════════════════════════

-- ═══════════════════════════════════════════════════════════════
-- REPORTS
-- ═══════════════════════════════════════════════════════════════
CREATE TABLE reports (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(200) UNIQUE NOT NULL,
    description TEXT,
    report_sql  TEXT NOT NULL,
    category    VARCHAR(100),
    active      BOOLEAN NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE report_parameters (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    report_id     UUID NOT NULL REFERENCES reports(id) ON DELETE CASCADE,
    param_name    VARCHAR(100) NOT NULL,
    param_type    VARCHAR(50) NOT NULL DEFAULT 'STRING',
    required      BOOLEAN NOT NULL DEFAULT false,
    default_value VARCHAR(500)
);

-- ═══════════════════════════════════════════════════════════════
-- INDEXES
-- ═══════════════════════════════════════════════════════════════
CREATE INDEX idx_loans_customer        ON loans(customer_id);
CREATE INDEX idx_loans_status          ON loans(status);
CREATE INDEX idx_schedule_loan_due     ON loan_repayment_schedule(loan_id, due_date);
CREATE INDEX idx_loan_charges_loan     ON loan_charges(loan_id);
CREATE INDEX idx_fd_customer           ON fixed_deposit_accounts(customer_id);
CREATE INDEX idx_rd_customer           ON recurring_deposit_accounts(customer_id);
CREATE INDEX idx_share_accts_customer  ON share_accounts(customer_id);
CREATE INDEX idx_gl_code               ON gl_accounts(gl_code);
CREATE INDEX idx_je_date_entity        ON journal_entries(entry_date, entity_type, entity_id);
CREATE INDEX idx_jel_journal           ON journal_entry_lines(journal_id);
CREATE INDEX idx_teller_sess_date      ON teller_sessions(session_date);
CREATE INDEX idx_cash_txn_session      ON cash_transactions(session_id);
CREATE INDEX idx_ob_consents_tpp       ON open_banking_consents(tpp_client_id);
CREATE INDEX idx_ob_consents_customer  ON open_banking_consents(customer_id);
CREATE INDEX idx_notif_entity          ON notification_events(entity_type, entity_id, created_at DESC);
CREATE INDEX idx_standing_customer     ON standing_instructions(customer_id);
CREATE INDEX idx_beneficiary_customer  ON beneficiaries(customer_id);
CREATE INDEX idx_2fa_user_active       ON two_factor_tokens(user_id, verified, expires_at);
CREATE INDEX idx_client_identifiers    ON client_identifiers(customer_id);
CREATE INDEX idx_client_addresses      ON client_addresses(customer_id);
CREATE INDEX idx_notes_entity          ON entity_notes(entity_type, entity_id);
CREATE INDEX idx_docs_entity           ON entity_documents(entity_type, entity_id);
CREATE INDEX idx_sms_campaign          ON sms_messages(campaign_id);
CREATE INDEX idx_sanctions_entity      ON sanctions_screening_log(entity_type, entity_id);
CREATE INDEX idx_maker_checker_status  ON maker_checker_requests(status, created_at DESC);
CREATE INDEX idx_loans_product         ON loans(loan_product_id);
CREATE INDEX idx_loan_guarantors_loan  ON loan_guarantors(loan_id);
CREATE INDEX idx_loan_collaterals_loan ON loan_collaterals(loan_id);
CREATE INDEX idx_loan_reschedule_loan  ON loan_reschedule_requests(loan_id);
CREATE INDEX idx_jel_gl_account        ON journal_entry_lines(gl_account_id);
CREATE INDEX idx_si_source_account     ON standing_instructions(source_account_id);
CREATE INDEX idx_ob_consents_status    ON open_banking_consents(status);
CREATE INDEX idx_share_txns_account    ON share_transactions(account_id);

-- ═══════════════════════════════════════════════════════════════
-- SEED DATA
-- ═══════════════════════════════════════════════════════════════
INSERT INTO system_configurations (config_key, value, description) VALUES
  ('enforce-min-balance',     'false', 'Block withdrawals below minimum balance'),
  ('enforce-lockin-period',   'false', 'Block withdrawals during lockin period'),
  ('max-otp-attempts',        '3',     'Max failed OTP attempts before token locks'),
  ('otp-expiry-minutes',      '10',    'OTP validity window in minutes'),
  ('enable-maker-checker',    'false', 'Require maker-checker for write operations');

INSERT INTO payment_types (name, is_cash_payment, system_defined, position) VALUES
  ('Cash',            true,  true, 1),
  ('Cheque',          false, true, 2),
  ('Direct Transfer', false, true, 3),
  ('Mobile Transfer', false, true, 4),
  ('Bank Transfer',   false, true, 5);

INSERT INTO permissions (grouping, code, entity_name, action_name) VALUES
  ('customers', 'READ_CUSTOMER',    'CUSTOMER', 'READ'),
  ('customers', 'CREATE_CUSTOMER',  'CUSTOMER', 'CREATE'),
  ('customers', 'UPDATE_CUSTOMER',  'CUSTOMER', 'UPDATE'),
  ('accounts',  'READ_ACCOUNT',     'ACCOUNT',  'READ'),
  ('accounts',  'CREATE_ACCOUNT',   'ACCOUNT',  'CREATE'),
  ('accounts',  'DEPOSIT',          'ACCOUNT',  'DEPOSIT'),
  ('accounts',  'WITHDRAW',         'ACCOUNT',  'WITHDRAW'),
  ('loans',     'READ_LOAN',        'LOAN',     'READ'),
  ('loans',     'CREATE_LOAN',      'LOAN',     'CREATE'),
  ('loans',     'APPROVE_LOAN',     'LOAN',     'APPROVE'),
  ('loans',     'DISBURSE_LOAN',    'LOAN',     'DISBURSE'),
  ('payments',  'INITIATE_PAYMENT', 'PAYMENT',  'CREATE'),
  ('reports',   'RUN_REPORT',       'REPORT',   'READ');

INSERT INTO reports (name, description, category, report_sql) VALUES
  ('AccountSummary', 'All accounts with current balances', 'ACCOUNTS',
   'SELECT a.account_number, a.account_type_label, a.status, a.balance, a.currency_code, a.created_at
    FROM accounts a WHERE a.status != ''CLOSED'' ORDER BY a.created_at DESC LIMIT 1000'),
  ('LoanPortfolio', 'All active loans', 'LOANS',
   'SELECT l.loan_account_number, l.status, l.principal_amount, l.outstanding_balance, l.disbursement_date
    FROM loans l WHERE l.status NOT IN (''CLOSED_OBLIGATIONS_MET'',''REJECTED'') ORDER BY l.created_at DESC LIMIT 1000'),
  ('TransactionHistory', 'Transactions in a date range', 'TRANSACTIONS',
   'SELECT t.id, a.account_number, t.transaction_type, t.amount, t.running_balance, t.created_at
    FROM transactions t JOIN accounts a ON a.id = t.account_id
    WHERE t.created_at >= $${startDate}::timestamptz AND t.created_at < $${endDate}::timestamptz
    ORDER BY t.created_at DESC LIMIT 5000'),
  ('CustomerKycStatus', 'Customers by KYC level', 'CUSTOMERS',
   'SELECT kyc_status, kyc_level, COUNT(*) AS customer_count FROM customers GROUP BY kyc_status, kyc_level'),
  ('ArrearsReport', 'Loans in arrears', 'LOANS',
   'SELECT l.loan_account_number, SUM(s.total_due - s.total_paid) AS overdue_amount, MIN(s.due_date) AS oldest_due
    FROM loans l JOIN loan_repayment_schedule s ON s.loan_id = l.id
    WHERE l.status = ''IN_ARREARS'' AND s.status IN (''OVERDUE'',''PARTIALLY_PAID'')
    GROUP BY l.id, l.loan_account_number ORDER BY overdue_amount DESC LIMIT 500');
