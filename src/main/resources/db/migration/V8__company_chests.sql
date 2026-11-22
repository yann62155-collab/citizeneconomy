-- Coffres d'entreprise : un coffre pose par le patron n'est ouvrable que par les membres de l'entreprise.
CREATE TABLE company_chests (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    company_id  INTEGER NOT NULL REFERENCES companies(id),
    dimension   TEXT NOT NULL,
    x           INTEGER NOT NULL,
    y           INTEGER NOT NULL,
    z           INTEGER NOT NULL,
    placed_by   TEXT NOT NULL,
    created_at  INTEGER NOT NULL
);
CREATE UNIQUE INDEX ux_company_chests_pos ON company_chests(dimension, x, y, z);
CREATE INDEX ix_company_chests_company ON company_chests(company_id);
