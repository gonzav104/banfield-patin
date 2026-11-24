-- Vigencias explícitas: no se derivan responsables de las familias ni de tutor.parentesco.
-- Las claves de estado están en tablas privadas, no en los padres. Así, UPDATE
-- de estado conserva NO KEY UPDATE y es compatible con KEY SHARE del código previo.

ALTER TABLE gestion_patin.tutor
    ADD CONSTRAINT uq_tutor_misma_familia UNIQUE (escuela_id, familia_id, id);
ALTER TABLE gestion_patin.familia_deportista
    ADD CONSTRAINT uq_fd_misma_escuela UNIQUE (escuela_id, familia_id, deportista_id);

CREATE SCHEMA gestion_patin_privado AUTHORIZATION CURRENT_USER;
REVOKE ALL ON SCHEMA gestion_patin_privado FROM PUBLIC;

CREATE TABLE gestion_patin_privado.marca_familia (
    escuela_id uuid NOT NULL,
    familia_id uuid NOT NULL,
    estado boolean NOT NULL,
    PRIMARY KEY (escuela_id, familia_id),
    UNIQUE (escuela_id, familia_id, estado),
    FOREIGN KEY (escuela_id, familia_id) REFERENCES gestion_patin.familia (escuela_id, id)
        ON UPDATE CASCADE ON DELETE CASCADE
);

CREATE TABLE gestion_patin_privado.marca_deportista (
    escuela_id uuid NOT NULL,
    deportista_id uuid NOT NULL,
    estado boolean NOT NULL,
    PRIMARY KEY (escuela_id, deportista_id),
    UNIQUE (escuela_id, deportista_id, estado),
    FOREIGN KEY (escuela_id, deportista_id) REFERENCES gestion_patin.deportista (escuela_id, id)
        ON UPDATE CASCADE ON DELETE CASCADE
);

CREATE TABLE gestion_patin_privado.marca_familia_deportista (
    escuela_id uuid NOT NULL,
    familia_id uuid NOT NULL,
    deportista_id uuid NOT NULL,
    estado varchar(20) NOT NULL,
    PRIMARY KEY (escuela_id, familia_id, deportista_id),
    UNIQUE (escuela_id, familia_id, deportista_id, estado),
    FOREIGN KEY (escuela_id, familia_id, deportista_id) REFERENCES gestion_patin.familia_deportista (escuela_id, familia_id, deportista_id)
        ON UPDATE CASCADE ON DELETE CASCADE
);

CREATE TABLE gestion_patin_privado.marca_tutor (
    escuela_id uuid NOT NULL,
    familia_id uuid NOT NULL,
    tutor_id uuid NOT NULL,
    estado boolean NOT NULL,
    PRIMARY KEY (escuela_id, familia_id, tutor_id),
    UNIQUE (escuela_id, familia_id, tutor_id, estado),
    FOREIGN KEY (escuela_id, familia_id, tutor_id) REFERENCES gestion_patin.tutor (escuela_id, familia_id, id)
        ON UPDATE CASCADE ON DELETE CASCADE
);

REVOKE ALL ON ALL TABLES IN SCHEMA gestion_patin_privado FROM PUBLIC;

CREATE TABLE gestion_patin.tutor_deportista (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    escuela_id         uuid NOT NULL,
    familia_id         uuid NOT NULL,
    deportista_id      uuid NOT NULL,
    tutor_id           uuid NOT NULL,
    tipo               varchar(24),
    version            bigint NOT NULL DEFAULT 0,
    solicitud_id       uuid NOT NULL,
    huella_solicitud    varchar(64) NOT NULL,
    creado_en          timestamptz NOT NULL DEFAULT clock_timestamp(),
    creado_por         uuid NOT NULL,
    cerrado_en         timestamptz,
    cerrado_por        uuid,
    causa_cierre       varchar(40),

    -- MATCH SIMPLE sólo omite las FKs de estado cuando se cierra la vigencia.
    requiere_activo boolean GENERATED ALWAYS AS
        (CASE WHEN cerrado_en IS NULL THEN true END) STORED,
    requiere_estado_fd varchar(20) GENERATED ALWAYS AS
        (CASE WHEN cerrado_en IS NULL THEN 'ACTIVO'::varchar(20) END) STORED,

    CONSTRAINT ck_td_version CHECK (version >= 0),
    CONSTRAINT ck_td_tipo CHECK (tipo IN ('MADRE', 'PADRE', 'TUTOR_LEGAL', 'OTRO_RESPONSABLE', 'SIN_DETERMINAR')),
    CONSTRAINT ck_td_huella_solicitud CHECK (huella_solicitud ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_td_cierre CHECK (
        (cerrado_en IS NULL) = (cerrado_por IS NULL)
        AND (cerrado_en IS NULL) = (causa_cierre IS NULL)
        AND (cerrado_en IS NULL OR cerrado_en >= creado_en)
    ),
    CONSTRAINT ck_td_causa CHECK (causa_cierre IN (
        'REVOCACION_ADMIN', 'TUTOR_INACTIVO', 'FAMILIA_INACTIVA', 'DEPORTISTA_INACTIVO', 'FAMILIA_DEPORTISTA_REVOCADA'
    )),
    CONSTRAINT uq_td_solicitud UNIQUE (escuela_id, solicitud_id),
    CONSTRAINT fk_td_tutor_misma_familia
        FOREIGN KEY (escuela_id, familia_id, tutor_id)
        REFERENCES gestion_patin.tutor (escuela_id, familia_id, id),
    CONSTRAINT fk_td_fd_misma_familia
        FOREIGN KEY (escuela_id, familia_id, deportista_id)
        REFERENCES gestion_patin.familia_deportista (escuela_id, familia_id, deportista_id),
    CONSTRAINT fk_td_tutor_activo
        FOREIGN KEY (escuela_id, familia_id, tutor_id, requiere_activo)
        REFERENCES gestion_patin_privado.marca_tutor (escuela_id, familia_id, tutor_id, estado),
    CONSTRAINT fk_td_fd_activo
        FOREIGN KEY (escuela_id, familia_id, deportista_id, requiere_estado_fd)
        REFERENCES gestion_patin_privado.marca_familia_deportista (escuela_id, familia_id, deportista_id, estado),
    CONSTRAINT fk_td_familia_activa
        FOREIGN KEY (escuela_id, familia_id, requiere_activo)
        REFERENCES gestion_patin_privado.marca_familia (escuela_id, familia_id, estado),
    CONSTRAINT fk_td_deportista_activo
        FOREIGN KEY (escuela_id, deportista_id, requiere_activo)
        REFERENCES gestion_patin_privado.marca_deportista (escuela_id, deportista_id, estado),
    CONSTRAINT fk_td_creado_por_misma_escuela
        FOREIGN KEY (creado_por, escuela_id) REFERENCES gestion_patin.usuario (id, escuela_id),
    CONSTRAINT fk_td_cerrado_por_misma_escuela
        FOREIGN KEY (cerrado_por, escuela_id) REFERENCES gestion_patin.usuario (id, escuela_id)
);

CREATE UNIQUE INDEX uq_td_par_vigente
    ON gestion_patin.tutor_deportista (escuela_id, tutor_id, deportista_id) WHERE cerrado_en IS NULL;
CREATE INDEX ix_td_tutor ON gestion_patin.tutor_deportista (escuela_id, familia_id, tutor_id);
CREATE INDEX ix_td_familia_deportista ON gestion_patin.tutor_deportista (escuela_id, familia_id, deportista_id);
CREATE INDEX ix_td_historial_deportista
    ON gestion_patin.tutor_deportista (escuela_id, deportista_id, creado_en DESC, id DESC);

-- Esta guarda sólo protege la identidad y el historial de una fila. No sustituye las FKs ni el orden de bloqueos.
CREATE FUNCTION gestion_patin.proteger_vigencia_tutor_deportista() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE check_violation USING MESSAGE = 'Las vigencias no se eliminan';
    ELSIF TG_OP = 'INSERT' THEN
        IF NEW.cerrado_en IS NOT NULL THEN
            RAISE check_violation USING MESSAGE = 'Una vigencia nueva debe estar abierta';
        END IF;
    ELSIF OLD.cerrado_en IS NOT NULL OR
          ROW(OLD.id, OLD.escuela_id, OLD.familia_id, OLD.deportista_id, OLD.tutor_id,
              OLD.solicitud_id, OLD.huella_solicitud, OLD.creado_en, OLD.creado_por)
          IS DISTINCT FROM
          ROW(NEW.id, NEW.escuela_id, NEW.familia_id, NEW.deportista_id, NEW.tutor_id,
              NEW.solicitud_id, NEW.huella_solicitud, NEW.creado_en, NEW.creado_por) THEN
        RAISE check_violation USING MESSAGE = 'El historial y la identidad de la vigencia son inmutables';
    END IF;
    RETURN NEW;
END;
$$;

ALTER FUNCTION gestion_patin.proteger_vigencia_tutor_deportista() OWNER TO CURRENT_USER;
REVOKE ALL ON FUNCTION gestion_patin.proteger_vigencia_tutor_deportista() FROM PUBLIC;

CREATE TRIGGER trg_td_proteger_vigencia
    BEFORE INSERT OR UPDATE OR DELETE ON gestion_patin.tutor_deportista
    FOR EACH ROW EXECUTE FUNCTION gestion_patin.proteger_vigencia_tutor_deportista();

-- Cada padre tiene una marca antes de que pueda referenciarla una vigencia.
-- Las altas de vigencias solo leen las marcas mediante FKs inmediatas: no las crean
-- ni las bloquean en modo exclusivo antes de resolver ON CONFLICT.
CREATE FUNCTION gestion_patin_privado.sincronizar_marca_vigencia() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    CASE TG_RELID
    WHEN 'gestion_patin.familia'::regclass THEN
        INSERT INTO gestion_patin_privado.marca_familia (escuela_id, familia_id, estado)
        VALUES (NEW.escuela_id, NEW.id, NEW.activa)
        ON CONFLICT (escuela_id, familia_id) DO UPDATE SET estado = EXCLUDED.estado;
    WHEN 'gestion_patin.deportista'::regclass THEN
        INSERT INTO gestion_patin_privado.marca_deportista (escuela_id, deportista_id, estado)
        VALUES (NEW.escuela_id, NEW.id, NEW.activo)
        ON CONFLICT (escuela_id, deportista_id) DO UPDATE SET estado = EXCLUDED.estado;
    WHEN 'gestion_patin.familia_deportista'::regclass THEN
        INSERT INTO gestion_patin_privado.marca_familia_deportista (escuela_id, familia_id, deportista_id, estado)
        VALUES (NEW.escuela_id, NEW.familia_id, NEW.deportista_id, NEW.estado)
        ON CONFLICT (escuela_id, familia_id, deportista_id) DO UPDATE SET estado = EXCLUDED.estado;
    WHEN 'gestion_patin.tutor'::regclass THEN
        INSERT INTO gestion_patin_privado.marca_tutor (escuela_id, familia_id, tutor_id, estado)
        VALUES (NEW.escuela_id, NEW.familia_id, NEW.id, NEW.activo)
        ON CONFLICT (escuela_id, familia_id, tutor_id) DO UPDATE SET estado = EXCLUDED.estado;
    ELSE
        RAISE invalid_parameter_value USING MESSAGE = 'Origen no autorizado para la marca de vigencia';
    END CASE;
    RETURN NULL;
END;
$$;

ALTER FUNCTION gestion_patin_privado.sincronizar_marca_vigencia() OWNER TO CURRENT_USER;
REVOKE ALL ON FUNCTION gestion_patin_privado.sincronizar_marca_vigencia() FROM PUBLIC;

CREATE TRIGGER trg_marca_vigencia_alta AFTER INSERT ON gestion_patin.familia
    FOR EACH ROW EXECUTE FUNCTION gestion_patin_privado.sincronizar_marca_vigencia();

CREATE TRIGGER trg_marca_vigencia AFTER UPDATE OF activa ON gestion_patin.familia
    FOR EACH ROW WHEN (OLD.activa IS DISTINCT FROM NEW.activa)
    EXECUTE FUNCTION gestion_patin_privado.sincronizar_marca_vigencia();

CREATE TRIGGER trg_marca_vigencia_alta AFTER INSERT ON gestion_patin.deportista
    FOR EACH ROW EXECUTE FUNCTION gestion_patin_privado.sincronizar_marca_vigencia();

CREATE TRIGGER trg_marca_vigencia AFTER UPDATE OF activo ON gestion_patin.deportista
    FOR EACH ROW WHEN (OLD.activo IS DISTINCT FROM NEW.activo)
    EXECUTE FUNCTION gestion_patin_privado.sincronizar_marca_vigencia();

CREATE TRIGGER trg_marca_vigencia_alta AFTER INSERT ON gestion_patin.familia_deportista
    FOR EACH ROW EXECUTE FUNCTION gestion_patin_privado.sincronizar_marca_vigencia();

CREATE TRIGGER trg_marca_vigencia AFTER UPDATE OF estado ON gestion_patin.familia_deportista
    FOR EACH ROW WHEN (OLD.estado IS DISTINCT FROM NEW.estado)
    EXECUTE FUNCTION gestion_patin_privado.sincronizar_marca_vigencia();

CREATE TRIGGER trg_marca_vigencia_alta AFTER INSERT ON gestion_patin.tutor
    FOR EACH ROW EXECUTE FUNCTION gestion_patin_privado.sincronizar_marca_vigencia();

CREATE TRIGGER trg_marca_vigencia AFTER UPDATE OF activo ON gestion_patin.tutor
    FOR EACH ROW WHEN (OLD.activo IS DISTINCT FROM NEW.activo)
    EXECUTE FUNCTION gestion_patin_privado.sincronizar_marca_vigencia();


-- Crear los triggers antes de copiar las marcas deja bloqueados los escritores
-- de los cuatro padres hasta el commit de Flyway. No se crean asociaciones.
INSERT INTO gestion_patin_privado.marca_familia (escuela_id, familia_id, estado)
SELECT escuela_id, id, activa FROM gestion_patin.familia;
INSERT INTO gestion_patin_privado.marca_deportista (escuela_id, deportista_id, estado)
SELECT escuela_id, id, activo FROM gestion_patin.deportista;
INSERT INTO gestion_patin_privado.marca_familia_deportista (escuela_id, familia_id, deportista_id, estado)
SELECT escuela_id, familia_id, deportista_id, estado FROM gestion_patin.familia_deportista;
INSERT INTO gestion_patin_privado.marca_tutor (escuela_id, familia_id, tutor_id, estado)
SELECT escuela_id, familia_id, id, activo FROM gestion_patin.tutor;
