-- Stock, caisse et comptabilite des entreprises (V7)

CREATE TABLE products (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    company_id INTEGER NOT NULL REFERENCES companies(id),
    sku        TEXT NOT NULL,
    name       TEXT NOT NULL,
    price      INTEGER NOT NULL CHECK (price >= 0),
    cost       INTEGER NOT NULL DEFAULT 0 CHECK (cost >= 0),
    margin_bp  INTEGER CHECK (margin_bp IS NULL OR (margin_bp >= 0 AND margin_bp <= 1000000)),
    buyback    INTEGER NOT NULL DEFAULT 0 CHECK (buyback >= 0),
    stock      INTEGER NOT NULL DEFAULT 0 CHECK (stock >= 0),
    min_stock  INTEGER NOT NULL DEFAULT 0 CHECK (min_stock >= 0),
    active     INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0,1)),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
CREATE UNIQUE INDEX ux_products_sku ON products(company_id, sku COLLATE NOCASE);

CREATE TABLE stock_movements (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    company_id INTEGER NOT NULL REFERENCES companies(id),
    product_id INTEGER NOT NULL REFERENCES products(id),
    delta      INTEGER NOT NULL CHECK (delta <> 0),
    reason     TEXT NOT NULL CHECK (reason IN ('RESTOCK','SALE','RETURN','ADJUST','LOSS')),
    unit_cost  INTEGER NOT NULL DEFAULT 0,
    note       TEXT NOT NULL DEFAULT '',
    ref_type   TEXT,
    ref_id     INTEGER,
    actor_uuid TEXT,
    created_at INTEGER NOT NULL
);
CREATE INDEX ix_stockmv_product ON stock_movements(product_id, id);
CREATE INDEX ix_stockmv_company ON stock_movements(company_id, created_at);

-- Rachats : l'entreprise paie un joueur qui lui apporte de la marchandise (minerais...) au tarif fixe de l'article.
-- La ligne sert de facture de rachat ; l'argent part de la banque de l'entreprise, le stock entre au cout du rachat.
CREATE TABLE buybacks (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    number       TEXT NOT NULL DEFAULT '',
    company_id   INTEGER NOT NULL REFERENCES companies(id),
    product_id   INTEGER NOT NULL REFERENCES products(id),
    product_name TEXT NOT NULL,
    seller_uuid  TEXT NOT NULL REFERENCES players(uuid),
    qty          INTEGER NOT NULL CHECK (qty > 0),
    unit_price   INTEGER NOT NULL CHECK (unit_price > 0),
    total        INTEGER NOT NULL CHECK (total > 0),
    created_by   TEXT NOT NULL,
    created_at   INTEGER NOT NULL
);
CREATE INDEX ix_buybacks_company ON buybacks(company_id, id);

CREATE TABLE sales (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    number         TEXT NOT NULL DEFAULT '',
    company_id     INTEGER NOT NULL REFERENCES companies(id),
    cashier_uuid   TEXT NOT NULL,
    customer_type  TEXT NOT NULL CHECK (customer_type IN ('NONE','PLAYER','COMPANY')),
    customer_id    TEXT,
    customer_label TEXT NOT NULL DEFAULT '',
    payment        TEXT NOT NULL CHECK (payment IN ('CASH','INVOICE')),
    status         TEXT NOT NULL CHECK (status IN ('PENDING','PAID','CANCELLED')),
    total          INTEGER NOT NULL CHECK (total >= 0),
    cost_total     INTEGER NOT NULL DEFAULT 0 CHECK (cost_total >= 0),
    invoice_id     INTEGER REFERENCES invoices(id),
    created_at     INTEGER NOT NULL,
    paid_at        INTEGER,
    closed_at      INTEGER
);
CREATE INDEX ix_sales_company ON sales(company_id, id);
CREATE INDEX ix_sales_invoice ON sales(invoice_id);

CREATE TABLE sale_lines (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    sale_id    INTEGER NOT NULL REFERENCES sales(id),
    product_id INTEGER NOT NULL REFERENCES products(id),
    name       TEXT NOT NULL,
    qty        INTEGER NOT NULL CHECK (qty > 0),
    unit_price INTEGER NOT NULL CHECK (unit_price >= 0),
    unit_cost  INTEGER NOT NULL DEFAULT 0 CHECK (unit_cost >= 0)
);
CREATE INDEX ix_salelines_sale ON sale_lines(sale_id);

-- Ecritures saisies a la main (loyer, subventions...) : elles ne deplacent jamais d'argent en banque.
CREATE TABLE manual_entries (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    company_id INTEGER NOT NULL REFERENCES companies(id),
    kind       TEXT NOT NULL CHECK (kind IN ('INCOME','EXPENSE')),
    category   TEXT NOT NULL,
    label      TEXT NOT NULL,
    amount     INTEGER NOT NULL CHECK (amount > 0),
    entry_at   INTEGER NOT NULL,
    created_by TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    deleted_at INTEGER
);
CREATE INDEX ix_manual_company ON manual_entries(company_id, entry_at);

-- Les gerants existants recoivent les trois nouveaux droits (caisse, stock, comptabilite) : bits 8, 9 et 10.
UPDATE employees SET permissions = permissions | 1792 WHERE role = 'MANAGER';
