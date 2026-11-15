-- V5 : employes de la couronne (seuls autorises, avec les admins, a realiser le recensement), payes par la tresorerie royale
CREATE TABLE royal_staff (
    player_uuid TEXT PRIMARY KEY REFERENCES players(uuid),
    title       TEXT NOT NULL DEFAULT 'Agent de la couronne',
    salary      INTEGER NOT NULL DEFAULT 0 CHECK (salary >= 0 AND salary <= 1000000),
    hired_at    INTEGER NOT NULL
)
