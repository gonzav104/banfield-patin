-- U21-BIS C2: otros adultos declarados por la familia y verificación administrativa.
--
--  * verificado_por / verificado_en: quién y cuándo resolvió un dato declarado (lo verificó u observó). Lo existente
--    (origen ADMIN, VERIFICADO desde V21) queda sin resolución registrada: lo cargó el club.
--  * motivo_observacion: lo que el club le pide corregir a la familia; existe si y solo si el tutor está OBSERVADO.
--  * idempotencia / huella_alta: el alta familiar repetida con la misma clave devuelve el mismo tutor; con otros datos
--    se rechaza. Única por cuenta que declaró.
--
-- Aditiva: columnas nulas, sin tocar datos ni migraciones anteriores. Los permisos del rol de runtime son por tabla
-- (autorizar-runtime-c1.sql), así que no hace falta un script de concesiones nuevo.

ALTER TABLE gestion_patin.tutor
    ADD COLUMN verificado_por     uuid,
    ADD COLUMN verificado_en      timestamptz,
    ADD COLUMN motivo_observacion varchar(500),
    ADD COLUMN idempotencia       uuid,
    ADD COLUMN huella_alta        varchar(64),
    ADD CONSTRAINT fk_tutor_verificado_por FOREIGN KEY (verificado_por, escuela_id)
        REFERENCES gestion_patin.usuario(id, escuela_id),
    ADD CONSTRAINT ck_tutor_resolucion_completa CHECK ((verificado_por IS NULL) = (verificado_en IS NULL)),
    ADD CONSTRAINT ck_tutor_declarado_sin_resolucion CHECK (verificacion <> 'DECLARADO' OR verificado_por IS NULL),
    ADD CONSTRAINT ck_tutor_observacion_con_motivo CHECK ((verificacion = 'OBSERVADO') = (motivo_observacion IS NOT NULL)),
    ADD CONSTRAINT ck_tutor_observado_resuelto CHECK (verificacion <> 'OBSERVADO' OR verificado_por IS NOT NULL),
    ADD CONSTRAINT ck_tutor_motivo_no_vacio CHECK (motivo_observacion IS NULL OR btrim(motivo_observacion) <> ''),
    ADD CONSTRAINT ck_tutor_idempotencia_completa CHECK ((idempotencia IS NULL) = (huella_alta IS NULL)),
    ADD CONSTRAINT ck_tutor_idempotencia_declarada CHECK (idempotencia IS NULL OR declarado_por IS NOT NULL);

CREATE UNIQUE INDEX uq_tutor_idempotencia
    ON gestion_patin.tutor (escuela_id, declarado_por, idempotencia)
    WHERE idempotencia IS NOT NULL;

-- Bandeja de ADMIN: tutores activos pendientes u observados, del más antiguo al más reciente.
CREATE INDEX ix_tutor_por_verificar
    ON gestion_patin.tutor (escuela_id, verificacion, creado_en, id)
    WHERE activo AND verificacion <> 'VERIFICADO';
