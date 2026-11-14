-- Revision de escritura de Familia. No altera datos personales ni relaciones existentes.
ALTER TABLE gestion_patin.familia
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_familia_version_no_negativa CHECK (version >= 0);
