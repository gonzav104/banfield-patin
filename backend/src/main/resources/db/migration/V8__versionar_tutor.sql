-- Revision de escritura de Tutor. No altera datos personales ni relaciones existentes.
ALTER TABLE gestion_patin.tutor
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_tutor_version_no_negativa CHECK (version >= 0);
