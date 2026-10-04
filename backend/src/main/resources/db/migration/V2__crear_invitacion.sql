-- Invitaciones de registro FAMILIA (solo un ADMIN las emite).
-- Migracion ADITIVA: no modifica ni elimina ningun objeto de V1 ni toca datos existentes.
-- Flyway ejecuta cada migracion dentro de su propia transaccion: no usar BEGIN/COMMIT.
--
-- Decisiones:
--   * El token en claro NUNCA se guarda: solo su hash SHA-256 en hexadecimal minuscula.
--   * El estado (PENDIENTE / USADA / REVOCADA / EXPIRADA) se deriva en la aplicacion; no hay columna de estado.
--   * Todas las FK hacia tablas con escuela_id son compuestas (id, escuela_id) para impedir referencias
--     entre escuelas distintas (mismo patron que V1).
--   * deportista_id es opcional y no lo completa ninguna API todavia (punto de extension de RF-04).
--   * Las FK compuestas con columnas NULL (deportista_id, usuario_id, revocada_por) usan MATCH SIMPLE:
--     si esa columna es NULL no se verifica la FK, que es lo buscado.

CREATE TABLE gestion_patin.invitacion (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    escuela_id          uuid NOT NULL,
    familia_id          uuid NOT NULL,
    deportista_id       uuid,
    token_hash          varchar(64) NOT NULL,
    email_sugerido      varchar(180),
    expira_en           timestamptz NOT NULL,
    creada_por          uuid NOT NULL,
    usado_en            timestamptz,
    usuario_id          uuid,
    revocada_en         timestamptz,
    revocada_por        uuid,
    creado_en           timestamptz NOT NULL DEFAULT now(),
    actualizado_en      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_invitacion_token_hash_formato
        CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_invitacion_expiracion
        CHECK (expira_en > creado_en),
    CONSTRAINT ck_invitacion_uso_coherente
        CHECK ((usado_en IS NULL) = (usuario_id IS NULL)),
    CONSTRAINT ck_invitacion_revocacion_coherente
        CHECK ((revocada_en IS NULL) = (revocada_por IS NULL)),
    CONSTRAINT ck_invitacion_usada_o_revocada
        CHECK (usado_en IS NULL OR revocada_en IS NULL),
    CONSTRAINT ck_invitacion_email_sugerido
        CHECK (email_sugerido IS NULL OR length(trim(email_sugerido)) > 0),
    CONSTRAINT fk_invitacion_escuela
        FOREIGN KEY (escuela_id) REFERENCES gestion_patin.escuela(id),
    CONSTRAINT fk_invitacion_familia_misma_escuela
        FOREIGN KEY (familia_id, escuela_id)
        REFERENCES gestion_patin.familia(id, escuela_id),
    CONSTRAINT fk_invitacion_deportista_misma_escuela
        FOREIGN KEY (deportista_id, escuela_id)
        REFERENCES gestion_patin.deportista(id, escuela_id),
    CONSTRAINT fk_invitacion_creada_por_misma_escuela
        FOREIGN KEY (creada_por, escuela_id)
        REFERENCES gestion_patin.usuario(id, escuela_id),
    CONSTRAINT fk_invitacion_usuario_misma_escuela
        FOREIGN KEY (usuario_id, escuela_id)
        REFERENCES gestion_patin.usuario(id, escuela_id),
    CONSTRAINT fk_invitacion_revocada_por_misma_escuela
        FOREIGN KEY (revocada_por, escuela_id)
        REFERENCES gestion_patin.usuario(id, escuela_id),
    CONSTRAINT uq_invitacion_id_escuela UNIQUE (id, escuela_id)
);

-- El hash del token es unico: es la clave de busqueda del registro y de la validacion publica.
CREATE UNIQUE INDEX uq_invitacion_token_hash
    ON gestion_patin.invitacion (token_hash);

-- Un usuario registrado proviene como maximo de una invitacion.
CREATE UNIQUE INDEX uq_invitacion_usuario
    ON gestion_patin.invitacion (usuario_id)
    WHERE usuario_id IS NOT NULL;

CREATE INDEX ix_invitacion_escuela_fecha
    ON gestion_patin.invitacion (escuela_id, creado_en DESC);

CREATE INDEX ix_invitacion_familia
    ON gestion_patin.invitacion (escuela_id, familia_id);

-- Reutiliza la funcion de trigger definida en V1 (seccion 9).
CREATE TRIGGER trg_invitacion_actualizado
BEFORE UPDATE ON gestion_patin.invitacion
FOR EACH ROW EXECUTE FUNCTION gestion_patin.actualizar_timestamp_modificacion();
