-- Segundo factor TOTP (RFC 6238) de las cuentas ADMIN (RNF-03).
-- Migracion ADITIVA: no modifica ni elimina ningun objeto de V1 ni de V2 y no toca datos existentes.
-- Flyway ejecuta cada migracion dentro de su propia transaccion: no usar BEGIN/COMMIT.
--
-- Decisiones:
--   * El secreto TOTP NUNCA se guarda en claro: secreto_cifrado contiene la salida de AES-256-GCM
--     (nonce de 12 bytes + texto cifrado + etiqueta de 16 bytes), con el id del usuario como AAD. La clave de
--     cifrado vive solo en el entorno del backend (MFA_CLAVE_CIFRADO), nunca en la base.
--   * confirmado_en NULL = enrolamiento iniciado pero no confirmado (todavia no cuenta como segundo factor).
--     usuario.mfa_habilitado (V1) lo actualiza la aplicacion en la misma transaccion que confirma o reinicia.
--   * ultimo_paso_usado guarda el ultimo paso de 30 s aceptado (anti-repeticion): un codigo de un paso menor o
--     igual se rechaza. Solo puede existir si el factor esta confirmado.
--   * La FK compuesta (usuario_id, escuela_id) impide asociar un factor a un usuario de otra escuela
--     (mismo patron que V1 y V2).
--   * Reiniciar el factor de un usuario es borrar su fila; no hay codigos de respaldo.

CREATE TABLE gestion_patin.usuario_mfa (
    usuario_id          uuid PRIMARY KEY,
    escuela_id          uuid NOT NULL,
    secreto_cifrado     bytea NOT NULL,
    confirmado_en       timestamptz,
    ultimo_paso_usado   bigint,
    creado_en           timestamptz NOT NULL DEFAULT now(),
    actualizado_en      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_usuario_mfa_secreto_longitud
        CHECK (octet_length(secreto_cifrado) > 28),
    CONSTRAINT ck_usuario_mfa_paso_no_negativo
        CHECK (ultimo_paso_usado IS NULL OR ultimo_paso_usado >= 0),
    CONSTRAINT ck_usuario_mfa_paso_solo_si_confirmado
        CHECK (ultimo_paso_usado IS NULL OR confirmado_en IS NOT NULL),
    CONSTRAINT fk_usuario_mfa_escuela
        FOREIGN KEY (escuela_id) REFERENCES gestion_patin.escuela(id),
    CONSTRAINT fk_usuario_mfa_usuario_misma_escuela
        FOREIGN KEY (usuario_id, escuela_id)
        REFERENCES gestion_patin.usuario(id, escuela_id)
);

-- Reutiliza la funcion de trigger definida en V1 (seccion 9).
CREATE TRIGGER trg_usuario_mfa_actualizado
BEFORE UPDATE ON gestion_patin.usuario_mfa
FOR EACH ROW EXECUTE FUNCTION gestion_patin.actualizar_timestamp_modificacion();
