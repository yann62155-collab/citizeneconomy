-- Citizen Economy : schema initial (V1)
-- Montants : entiers dans la plus petite unite monetaire. Dates : millisecondes epoch UTC.
-- Taux : points de base (1000 = 10,00 %).

CREATE TABLE players (
    uuid       TEXT PRIMARY KEY,
    name       TEXT NOT NULL,
    first_seen INTEGER NOT NULL,
    last_seen  INTEGER NOT NULL
);

CREATE TABLE settings (
    key        TEXT PRIMARY KEY,
    value      TEXT NOT NULL,
    updated_at INTEGER NOT NULL,
    updated_by TEXT
);

CREATE TABLE companies (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    uid          TEXT NOT NULL UNIQUE,
    name         TEXT NOT NULL UNIQUE COLLATE NOCASE,
    emblem       TEXT,
    description  TEXT NOT NULL DEFAULT '',
    sector       TEXT NOT NULL,
    created_at   INTEGER NOT NULL,
    updated_at   INTEGER NOT NULL,
    owner_uuid   TEXT NOT NULL REFERENCES players(uuid),
    manager_uuid TEXT REFERENCES players(uuid),
    status       TEXT NOT NULL CHECK (status IN ('PENDING','ACTIVE','SUSPENDED','CLOSED')),
    status_reason TEXT,
    announcements_suspended INTEGER NOT NULL DEFAULT 0 CHECK (announcements_suspended IN (0,1))
);

CREATE TABLE company_requests (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    applicant_uuid TEXT NOT NULL REFERENCES players(uuid),
    name           TEXT NOT NULL,
    description    TEXT NOT NULL DEFAULT '',
    sector         TEXT NOT NULL,
    emblem         TEXT,
    status         TEXT NOT NULL CHECK (status IN ('PENDING','ACCEPTED','REFUSED','CHANGES_REQUESTED')),
    admin_note     TEXT,
    reviewed_by    TEXT,
    created_at     INTEGER NOT NULL,
    reviewed_at    INTEGER,
    company_id     INTEGER REFERENCES companies(id)
);

CREATE TABLE employees (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    company_id  INTEGER NOT NULL REFERENCES companies(id),
    player_uuid TEXT NOT NULL REFERENCES players(uuid),
    role        TEXT NOT NULL CHECK (role IN ('OWNER','MANAGER','EMPLOYEE')),
    grade       TEXT NOT NULL DEFAULT '',
    salary      INTEGER NOT NULL DEFAULT 0 CHECK (salary >= 0),
    permissions INTEGER NOT NULL DEFAULT 0,
    hired_at    INTEGER NOT NULL,
    fired_at    INTEGER
);

CREATE UNIQUE INDEX ux_employees_active ON employees(company_id, player_uuid) WHERE fired_at IS NULL;
CREATE INDEX ix_employees_player ON employees(player_uuid);

CREATE TABLE employee_salary_history (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    employee_id INTEGER NOT NULL REFERENCES employees(id),
    old_salary  INTEGER NOT NULL,
    new_salary  INTEGER NOT NULL CHECK (new_salary >= 0),
    changed_by  TEXT NOT NULL,
    changed_at  INTEGER NOT NULL
);

CREATE TABLE personal_accounts (
    player_uuid TEXT PRIMARY KEY REFERENCES players(uuid),
    balance     INTEGER NOT NULL DEFAULT 0 CHECK (balance >= 0),
    created_at  INTEGER NOT NULL
);

CREATE TABLE business_accounts (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    company_id INTEGER NOT NULL UNIQUE REFERENCES companies(id),
    balance    INTEGER NOT NULL DEFAULT 0 CHECK (balance >= 0),
    created_at INTEGER NOT NULL
);

CREATE TABLE treasury (
    id         INTEGER PRIMARY KEY CHECK (id = 1),
    balance    INTEGER NOT NULL DEFAULT 0 CHECK (balance >= 0),
    updated_at INTEGER NOT NULL
);

INSERT INTO treasury(id, balance, updated_at) VALUES (1, 0, 0);

CREATE TABLE transactions (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    tx_uuid        TEXT NOT NULL UNIQUE,
    type           TEXT NOT NULL CHECK (type IN ('DEPOSIT','WITHDRAWAL','TRANSFER','SALARY','INVOICE_PAYMENT','TAX','ROYAL_EXPENSE','ROYAL_REVENUE','ADJUSTMENT')),
    taxable        INTEGER NOT NULL DEFAULT 0 CHECK (taxable IN (0,1)),
    amount         INTEGER NOT NULL CHECK (amount > 0),
    source_type    TEXT CHECK (source_type IN ('PERSONAL','BUSINESS','TREASURY','CASH')),
    source_id      TEXT,
    target_type    TEXT CHECK (target_type IN ('PERSONAL','BUSINESS','TREASURY','CASH')),
    target_id      TEXT,
    description    TEXT NOT NULL DEFAULT '',
    initiator_uuid TEXT,
    invoice_id     INTEGER,
    created_at     INTEGER NOT NULL,
    CHECK (source_type IS NOT NULL OR target_type IS NOT NULL)
);

CREATE INDEX ix_tx_source ON transactions(source_type, source_id, created_at);
CREATE INDEX ix_tx_target ON transactions(target_type, target_id, created_at);
CREATE INDEX ix_tx_created ON transactions(created_at);

-- Grand livre : une ligne par compte et par transaction (double ecriture, soldes verifiables)
CREATE TABLE ledger_entries (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    tx_id         INTEGER NOT NULL REFERENCES transactions(id),
    account_type  TEXT NOT NULL CHECK (account_type IN ('PERSONAL','BUSINESS','TREASURY')),
    account_id    TEXT NOT NULL,
    delta         INTEGER NOT NULL CHECK (delta <> 0),
    balance_after INTEGER NOT NULL CHECK (balance_after >= 0),
    created_at    INTEGER NOT NULL
);

CREATE INDEX ix_ledger_account ON ledger_entries(account_type, account_id, id);
CREATE INDEX ix_ledger_tx ON ledger_entries(tx_id);

-- Taux fiscaux versionnes : on AJOUTE une ligne a chaque changement, on ne modifie jamais l'ancienne.
CREATE TABLE taxes (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    kind           TEXT NOT NULL CHECK (kind IN ('BUSINESS_INCOME','PERSONAL_INCOME','SALARY')),
    base           TEXT NOT NULL CHECK (base IN ('REVENUE','PROFIT','SELECTED_INCOME','SALARY')),
    rate_bp        INTEGER NOT NULL CHECK (rate_bp >= 0 AND rate_bp <= 10000),
    enabled        INTEGER NOT NULL CHECK (enabled IN (0,1)),
    mode           TEXT NOT NULL CHECK (mode IN ('AUTO','DECLARATION')),
    period_minutes INTEGER NOT NULL CHECK (period_minutes > 0),
    effective_from INTEGER NOT NULL,
    created_by     TEXT NOT NULL,
    created_at     INTEGER NOT NULL
);

CREATE INDEX ix_taxes_kind ON taxes(kind, effective_from);

CREATE TABLE tax_exemptions (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    subject_type TEXT NOT NULL CHECK (subject_type IN ('PLAYER','COMPANY')),
    subject_id   TEXT NOT NULL,
    tax_kind     TEXT NOT NULL CHECK (tax_kind IN ('BUSINESS_INCOME','PERSONAL_INCOME','SALARY')),
    created_by   TEXT NOT NULL,
    created_at   INTEGER NOT NULL,
    revoked_at   INTEGER
);

CREATE UNIQUE INDEX ux_tax_exemption_active ON tax_exemptions(subject_type, subject_id, tax_kind) WHERE revoked_at IS NULL;

CREATE TABLE tax_history (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    tax_id         INTEGER NOT NULL REFERENCES taxes(id),
    tax_kind       TEXT NOT NULL,
    account_type   TEXT NOT NULL CHECK (account_type IN ('PERSONAL','BUSINESS')),
    account_id     TEXT NOT NULL,
    base_amount    INTEGER NOT NULL CHECK (base_amount >= 0),
    rate_bp        INTEGER NOT NULL,
    amount         INTEGER NOT NULL CHECK (amount >= 0),
    period_start   INTEGER NOT NULL,
    period_end     INTEGER NOT NULL,
    status         TEXT NOT NULL CHECK (status IN ('DUE','PAID','WAIVED')),
    recipient_type TEXT NOT NULL DEFAULT 'TREASURY',
    tx_id          INTEGER REFERENCES transactions(id),
    created_at     INTEGER NOT NULL,
    UNIQUE (tax_id, account_type, account_id, period_start, period_end)
);

CREATE INDEX ix_tax_history_account ON tax_history(account_type, account_id, period_end);

CREATE TABLE invoices (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    number            TEXT NOT NULL UNIQUE,
    issuer_company_id INTEGER NOT NULL REFERENCES companies(id),
    recipient_type    TEXT NOT NULL CHECK (recipient_type IN ('PLAYER','COMPANY')),
    recipient_id      TEXT NOT NULL,
    description       TEXT NOT NULL DEFAULT '',
    amount            INTEGER NOT NULL CHECK (amount > 0),
    issued_at         INTEGER NOT NULL,
    due_at            INTEGER,
    status            TEXT NOT NULL CHECK (status IN ('PENDING','PAID','REFUSED','CANCELLED','OVERDUE')),
    paid_tx_id        INTEGER REFERENCES transactions(id),
    updated_at        INTEGER NOT NULL
);

CREATE INDEX ix_invoices_recipient ON invoices(recipient_type, recipient_id, status);
CREATE INDEX ix_invoices_issuer ON invoices(issuer_company_id, status);

CREATE TABLE announcements (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    company_id   INTEGER NOT NULL REFERENCES companies(id),
    author_uuid  TEXT NOT NULL REFERENCES players(uuid),
    title        TEXT NOT NULL,
    body         TEXT NOT NULL,
    category     TEXT NOT NULL,
    visibility   TEXT NOT NULL CHECK (visibility IN ('INTERNAL','PUBLIC')),
    status       TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REMOVED')),
    created_at   INTEGER NOT NULL,
    updated_at   INTEGER NOT NULL,
    expires_at   INTEGER,
    moderated_by TEXT
);

CREATE INDEX ix_announcements_company ON announcements(company_id, status);
CREATE INDEX ix_announcements_public ON announcements(visibility, status, created_at);

CREATE TABLE notifications (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    player_uuid TEXT NOT NULL REFERENCES players(uuid),
    type        TEXT NOT NULL,
    message     TEXT NOT NULL,
    created_at  INTEGER NOT NULL,
    read_at     INTEGER
);

CREATE INDEX ix_notifications_player ON notifications(player_uuid, created_at);

CREATE TABLE audit_logs (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    actor_uuid  TEXT,
    action      TEXT NOT NULL,
    target_type TEXT,
    target_id   TEXT,
    details     TEXT NOT NULL DEFAULT '',
    created_at  INTEGER NOT NULL
);

CREATE INDEX ix_audit_created ON audit_logs(created_at);
CREATE INDEX ix_audit_actor ON audit_logs(actor_uuid, created_at)
