-- V3 : matricule unique par entreprise (E-0001...), applique aussi aux entreprises deja existantes
ALTER TABLE companies ADD COLUMN matricule TEXT;

UPDATE companies SET matricule = 'E-' || printf('%04d', id) WHERE matricule IS NULL;

CREATE UNIQUE INDEX ux_companies_matricule ON companies(matricule)
