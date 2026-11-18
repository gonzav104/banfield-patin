-- Revision agregada de los vinculos familia-deportista, por deportista. Independiente de version (datos permanentes):
-- no altera datos personales, estados ni vinculos existentes.
ALTER TABLE gestion_patin.deportista
    ADD COLUMN version_vinculos BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_deportista_version_vinculos_no_negativa CHECK (version_vinculos >= 0);
