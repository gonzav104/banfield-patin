-- U21-BIS C1: perfil propio y vínculo declarativo cuenta <-> tutor.
--
-- La familia declara su propio perfil de tutor y puede reclamar a la persona que ya figura en su familia (declaración, no
-- acreditación: no concede permisos). Estos datos solo registran QUIÉN declaró y EN QUÉ ESTADO está cada dato; la
-- autorización sigue siendo por familia (familia_deportista ACTIVO + rol).
--
--  * origen / verificacion: lo existente lo cargó ADMIN y queda VERIFICADO; lo declarado por la familia nace DECLARADO.
--  * vinculo_cuenta: cuenta asociada al tutor (usuario_id, ya único), DECLARADO por la propia persona o VERIFICADO si ADMIN
--    invitó a ese tutor. Nulo si el tutor no tiene cuenta (y viceversa).
--  * La cuenta pertenece a la MISMA familia que el tutor (la pertenencia de usuario es inmutable desde V18).
--  * invitacion.tutor_id: ADMIN invita a un tutor concreto de la familia; la cuenta nace vinculada y VERIFICADA.
--
-- Aditiva: columnas con valores por defecto; no toca datos ni migraciones anteriores.

ALTER TABLE gestion_patin.tutor
    ADD COLUMN origen         varchar(10) NOT NULL DEFAULT 'ADMIN',
    ADD COLUMN verificacion   varchar(12) NOT NULL DEFAULT 'VERIFICADO',
    ADD COLUMN vinculo_cuenta varchar(12),
    ADD COLUMN declarado_por  uuid,
    ADD CONSTRAINT ck_tutor_origen CHECK (origen IN ('ADMIN', 'FAMILIA')),
    ADD CONSTRAINT ck_tutor_verificacion CHECK (verificacion IN ('DECLARADO', 'VERIFICADO', 'OBSERVADO')),
    ADD CONSTRAINT ck_tutor_vinculo_cuenta CHECK (vinculo_cuenta IN ('DECLARADO', 'VERIFICADO'));

-- Cuentas que ya estuvieran asociadas a un tutor (ningún flujo de la aplicación lo hacía): las dejó ADMIN, verificadas.
UPDATE gestion_patin.tutor SET vinculo_cuenta = 'VERIFICADO' WHERE usuario_id IS NOT NULL;

ALTER TABLE gestion_patin.tutor
    ADD CONSTRAINT ck_tutor_cuenta_vinculo CHECK ((usuario_id IS NULL) = (vinculo_cuenta IS NULL)),
    ADD CONSTRAINT fk_tutor_declarado_por FOREIGN KEY (declarado_por, escuela_id)
        REFERENCES gestion_patin.usuario(id, escuela_id);

-- La cuenta del tutor es de la misma familia (FK compuesta; MATCH SIMPLE: sin cuenta no se comprueba).
ALTER TABLE gestion_patin.usuario
    ADD CONSTRAINT uq_usuario_id_familia UNIQUE (id, familia_id);
ALTER TABLE gestion_patin.tutor
    ADD CONSTRAINT fk_tutor_cuenta_misma_familia FOREIGN KEY (usuario_id, familia_id)
        REFERENCES gestion_patin.usuario(id, familia_id);

ALTER TABLE gestion_patin.invitacion
    ADD COLUMN tutor_id uuid,
    ADD CONSTRAINT fk_invitacion_tutor_misma_familia FOREIGN KEY (escuela_id, familia_id, tutor_id)
        REFERENCES gestion_patin.tutor(escuela_id, familia_id, id);
