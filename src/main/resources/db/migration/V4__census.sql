-- V4 : recensement des citoyens (prenom, nom, age ; la profession vient de l'entreprise, sauf profession fixee par un admin)
CREATE TABLE census (
    player_uuid  TEXT PRIMARY KEY REFERENCES players(uuid),
    first_name   TEXT NOT NULL DEFAULT '',
    last_name    TEXT NOT NULL DEFAULT '',
    age          INTEGER NOT NULL DEFAULT 0 CHECK (age >= 0 AND age <= 999),
    profession   TEXT NOT NULL DEFAULT '',
    validated    INTEGER NOT NULL DEFAULT 0 CHECK (validated IN (0,1)),
    validated_at INTEGER,
    updated_at   INTEGER NOT NULL
)
