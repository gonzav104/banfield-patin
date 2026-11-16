-- Revision de escritura de Deportista. No altera datos personales ni relaciones existentes.
ALTER TABLE gestion_patin.deportista
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_deportista_version_no_negativa CHECK (version >= 0);
