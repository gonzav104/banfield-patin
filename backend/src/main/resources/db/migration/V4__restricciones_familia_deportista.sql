-- Restricciones del modulo Familias/Deportistas.
-- Migracion ADITIVA: no modifica ni elimina objetos de V1, V2 ni V3 y no reescribe datos.
-- Flyway ejecuta cada migracion dentro de su propia transaccion: no usar BEGIN/COMMIT.
--
-- Decisiones:
--   * ix_tutor_familia: los tutores se listan por (escuela, familia); V1 no tiene indice.
--   * ck_fd_activo_autorizado: un vinculo ACTIVO (unico estado que da acceso a la FAMILIA) registra quien lo
--     autorizo y cuando. PENDIENTE/RECHAZADO/REVOCADO no lo exigen.
--   * uq_fd_principal_activo: a lo sumo un vinculo ACTIVO principal por deportista (puede estar vinculado a
--     varias familias). Los vinculos no activos no cuentan.
--   * Sin CHECK de formato de DNI/CUIL: se valida en la aplicacion.

CREATE INDEX ix_tutor_familia
    ON gestion_patin.tutor (escuela_id, familia_id);

ALTER TABLE gestion_patin.familia_deportista
    ADD CONSTRAINT ck_fd_activo_autorizado
    CHECK (estado <> 'ACTIVO' OR (autorizado_por IS NOT NULL AND autorizado_en IS NOT NULL));

CREATE UNIQUE INDEX uq_fd_principal_activo
    ON gestion_patin.familia_deportista (deportista_id)
    WHERE estado = 'ACTIVO' AND es_principal;
