-- C2-A sólo cierra vigencias. No agrega DML directo al runtime ni modifica V14.
CREATE FUNCTION gestion_patin.cerrar_vigencias_tutor(
    p_escuela uuid, p_actor uuid, p_causa text, p_objetivo uuid, p_familia uuid
) RETURNS SETOF uuid
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    -- El login de aplicación es compartido: Java aporta el actor autenticado. La base sólo
    -- corrobora el rol y la escuela de ese actor; no puede autenticar una identidad HTTP.
    IF NOT EXISTS (
        SELECT FROM gestion_patin.usuario u
        WHERE u.id = p_actor AND u.escuela_id = p_escuela AND u.rol = 'ADMIN' AND u.activo
    ) THEN
        RAISE insufficient_privilege USING MESSAGE = 'Actor de cierre no autorizado';
    END IF;

    IF p_objetivo IS NULL OR p_causa IS NULL THEN
        RAISE invalid_parameter_value USING MESSAGE = 'Alcance de cierre inválido';
    END IF;

    -- Primero el padre (en revocación, deportista antes de familia_deportista).
    -- NO KEY UPDATE no bloquea las referencias permanentes KEY SHARE del alta SQL.
    -- Después la marca exacta: FOR UPDATE serializa el cierre con las FKs de altas,
    -- sin tocar ni bloquear en exclusiva una clave de estado en la fila padre.
    CASE p_causa
    WHEN 'FAMILIA_INACTIVA' THEN
        IF p_familia IS NOT NULL THEN
            RAISE invalid_parameter_value USING MESSAGE = 'Alcance de cierre inválido';
        END IF;
        PERFORM FROM gestion_patin.familia f
            WHERE f.escuela_id = p_escuela AND f.id = p_objetivo FOR NO KEY UPDATE;
        IF NOT FOUND THEN RETURN; END IF;
        PERFORM FROM gestion_patin_privado.marca_familia m
            WHERE m.escuela_id = p_escuela AND m.familia_id = p_objetivo AND m.estado FOR UPDATE;
    WHEN 'DEPORTISTA_INACTIVO' THEN
        IF p_familia IS NOT NULL THEN
            RAISE invalid_parameter_value USING MESSAGE = 'Alcance de cierre inválido';
        END IF;
        PERFORM FROM gestion_patin.deportista d
            WHERE d.escuela_id = p_escuela AND d.id = p_objetivo FOR NO KEY UPDATE;
        IF NOT FOUND THEN RETURN; END IF;
        PERFORM FROM gestion_patin_privado.marca_deportista m
            WHERE m.escuela_id = p_escuela AND m.deportista_id = p_objetivo AND m.estado FOR UPDATE;
    WHEN 'TUTOR_INACTIVO' THEN
        IF p_familia IS NULL THEN
            RAISE invalid_parameter_value USING MESSAGE = 'Alcance de cierre inválido';
        END IF;
        PERFORM FROM gestion_patin.tutor t
            WHERE t.escuela_id = p_escuela AND t.familia_id = p_familia
              AND t.id = p_objetivo FOR NO KEY UPDATE;
        IF NOT FOUND THEN RETURN; END IF;
        PERFORM FROM gestion_patin_privado.marca_tutor m
            WHERE m.escuela_id = p_escuela AND m.familia_id = p_familia
              AND m.tutor_id = p_objetivo AND m.estado FOR UPDATE;
    WHEN 'FAMILIA_DEPORTISTA_REVOCADA' THEN
        IF p_familia IS NULL THEN
            RAISE invalid_parameter_value USING MESSAGE = 'Alcance de cierre inválido';
        END IF;
        PERFORM FROM gestion_patin.deportista d
            WHERE d.escuela_id = p_escuela AND d.id = p_objetivo FOR NO KEY UPDATE;
        IF NOT FOUND THEN RETURN; END IF;
        PERFORM FROM gestion_patin.familia_deportista fd
            WHERE fd.escuela_id = p_escuela AND fd.familia_id = p_familia
              AND fd.deportista_id = p_objetivo FOR NO KEY UPDATE;
        IF NOT FOUND THEN RETURN; END IF;
        PERFORM FROM gestion_patin_privado.marca_familia_deportista m
            WHERE m.escuela_id = p_escuela AND m.familia_id = p_familia
              AND m.deportista_id = p_objetivo AND m.estado = 'ACTIVO' FOR UPDATE;
    ELSE
        RAISE invalid_parameter_value USING MESSAGE = 'Causa de cierre no autorizada';
    END CASE;
    IF NOT FOUND THEN RETURN; END IF;

    -- Los cierres por padres distintos pueden alcanzar las mismas vigencias.
    -- Bloquear por ID antes del UPDATE da a todos los cierres el mismo orden.
    RETURN QUERY
        WITH objetivo AS MATERIALIZED (
            SELECT td.id
            FROM gestion_patin.tutor_deportista td
            WHERE td.escuela_id = p_escuela AND td.cerrado_en IS NULL
              AND CASE p_causa
                  WHEN 'FAMILIA_INACTIVA' THEN td.familia_id = p_objetivo
                  WHEN 'DEPORTISTA_INACTIVO' THEN td.deportista_id = p_objetivo
                  WHEN 'TUTOR_INACTIVO' THEN td.familia_id = p_familia AND td.tutor_id = p_objetivo
                  WHEN 'FAMILIA_DEPORTISTA_REVOCADA'
                      THEN td.familia_id = p_familia AND td.deportista_id = p_objetivo
                  ELSE false
              END
            ORDER BY td.id
            FOR NO KEY UPDATE
        )
        UPDATE gestion_patin.tutor_deportista td
        SET cerrado_en = greatest(clock_timestamp(), td.creado_en),
            cerrado_por = p_actor, causa_cierre = p_causa, version = td.version + 1
        FROM objetivo o
        WHERE td.id = o.id AND td.escuela_id = p_escuela AND td.cerrado_en IS NULL
        RETURNING td.id;
END;
$$;

ALTER FUNCTION gestion_patin.cerrar_vigencias_tutor(uuid, uuid, text, uuid, uuid) OWNER TO CURRENT_USER;
REVOKE ALL ON FUNCTION gestion_patin.cerrar_vigencias_tutor(uuid, uuid, text, uuid, uuid) FROM PUBLIC;

-- Las FKs de V14 exigen cerrar antes de cambiar el padre. La guarda diferida exige
-- la otra mitad al confirmar y registra la auditoría en la misma transacción.
CREATE FUNCTION gestion_patin_privado.confirmar_cierre_por_baja() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    baja_confirmada boolean;
BEGIN
    baja_confirmada := CASE NEW.causa_cierre
        WHEN 'FAMILIA_INACTIVA' THEN EXISTS (
            SELECT FROM gestion_patin_privado.marca_familia m
            WHERE m.escuela_id = NEW.escuela_id AND m.familia_id = NEW.familia_id AND NOT m.estado)
        WHEN 'DEPORTISTA_INACTIVO' THEN EXISTS (
            SELECT FROM gestion_patin_privado.marca_deportista m
            WHERE m.escuela_id = NEW.escuela_id AND m.deportista_id = NEW.deportista_id AND NOT m.estado)
        WHEN 'TUTOR_INACTIVO' THEN EXISTS (
            SELECT FROM gestion_patin_privado.marca_tutor m
            WHERE m.escuela_id = NEW.escuela_id AND m.familia_id = NEW.familia_id
              AND m.tutor_id = NEW.tutor_id AND NOT m.estado)
        WHEN 'FAMILIA_DEPORTISTA_REVOCADA' THEN EXISTS (
            SELECT FROM gestion_patin_privado.marca_familia_deportista m
            WHERE m.escuela_id = NEW.escuela_id AND m.familia_id = NEW.familia_id
              AND m.deportista_id = NEW.deportista_id AND m.estado = 'REVOCADO')
        ELSE false
    END;
    IF NOT baja_confirmada THEN
        RAISE check_violation USING MESSAGE = 'El cierre exige confirmar la baja o revocación correspondiente';
    END IF;
    INSERT INTO gestion_patin.auditoria (escuela_id, usuario_id, accion, recurso_tipo, recurso_id, detalle)
    VALUES (NEW.escuela_id, NEW.cerrado_por, 'VIGENCIA_TUTOR_CERRADA', 'TUTOR_DEPORTISTA', NEW.id,
            jsonb_build_object('causa', NEW.causa_cierre));
    RETURN NULL;
END;
$$;

ALTER FUNCTION gestion_patin_privado.confirmar_cierre_por_baja() OWNER TO CURRENT_USER;
REVOKE ALL ON FUNCTION gestion_patin_privado.confirmar_cierre_por_baja() FROM PUBLIC;

CREATE CONSTRAINT TRIGGER trg_td_confirmar_cierre_por_baja
    AFTER UPDATE ON gestion_patin.tutor_deportista
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    WHEN (OLD.cerrado_en IS NULL AND NEW.cerrado_en IS NOT NULL AND NEW.causa_cierre <> 'REVOCACION_ADMIN')
    EXECUTE FUNCTION gestion_patin_privado.confirmar_cierre_por_baja();

-- Metadatos internos de la transacción superior. No se proyectan en los contratos HTTP.
-- El instante distingue transacciones de otros clústeres tras una restauración lógica.
ALTER TABLE gestion_patin.tutor_deportista
    ADD COLUMN cierre_xid xid8,
    ADD COLUMN cierre_tx_inicio timestamptz;

CREATE INDEX ix_td_cierre_transaccion ON gestion_patin.tutor_deportista (cierre_xid)
    WHERE cierre_xid IS NOT NULL;

CREATE FUNCTION gestion_patin_privado.fijar_transaccion_de_cierre() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        NEW.cierre_xid := NULL;
        NEW.cierre_tx_inicio := NULL;
    ELSIF OLD.cerrado_en IS NULL AND NEW.cerrado_en IS NOT NULL
          AND NEW.causa_cierre IN ('TUTOR_INACTIVO', 'FAMILIA_INACTIVA', 'DEPORTISTA_INACTIVO',
                                 'FAMILIA_DEPORTISTA_REVOCADA') THEN
        NEW.cierre_xid := pg_current_xact_id();
        NEW.cierre_tx_inicio := transaction_timestamp();
    ELSE
        NEW.cierre_xid := OLD.cierre_xid;
        NEW.cierre_tx_inicio := OLD.cierre_tx_inicio;
    END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION gestion_patin_privado.fijar_transaccion_de_cierre() FROM PUBLIC;
CREATE TRIGGER trg_td_fijar_transaccion_de_cierre
    BEFORE INSERT OR UPDATE ON gestion_patin.tutor_deportista
    FOR EACH ROW EXECUTE FUNCTION gestion_patin_privado.fijar_transaccion_de_cierre();

-- El padre ya está bloqueado por UPDATE; la consulta no toma locks de fila adicionales.
-- Un trigger ordinario no se puede adelantar ni posponer con SET CONSTRAINTS.
CREATE FUNCTION gestion_patin_privado.impedir_reactivacion_en_transaccion_de_baja() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_xid xid8 := pg_current_xact_id_if_assigned();
    v_causa text;
    v_familia uuid;
    v_deportista uuid;
    v_tutor uuid;
BEGIN
    IF v_xid IS NULL THEN
        RETURN NEW;
    END IF;

    CASE TG_RELID
        WHEN 'gestion_patin.familia'::regclass THEN
            v_causa := 'FAMILIA_INACTIVA';
            v_familia := OLD.id;
        WHEN 'gestion_patin.deportista'::regclass THEN
            v_causa := 'DEPORTISTA_INACTIVO';
            v_deportista := OLD.id;
        WHEN 'gestion_patin.tutor'::regclass THEN
            v_causa := 'TUTOR_INACTIVO';
            v_familia := OLD.familia_id;
            v_tutor := OLD.id;
        WHEN 'gestion_patin.familia_deportista'::regclass THEN
            v_causa := 'FAMILIA_DEPORTISTA_REVOCADA';
            v_familia := OLD.familia_id;
            v_deportista := OLD.deportista_id;
        ELSE
            RAISE invalid_parameter_value USING MESSAGE = 'Origen de reactivación no autorizado';
    END CASE;

    IF EXISTS (
        SELECT FROM gestion_patin.tutor_deportista td
        WHERE td.cierre_xid = v_xid AND td.cierre_tx_inicio = transaction_timestamp()
          AND td.escuela_id = OLD.escuela_id AND td.causa_cierre = v_causa
          AND (v_familia IS NULL OR td.familia_id = v_familia)
          AND (v_deportista IS NULL OR td.deportista_id = v_deportista)
          AND (v_tutor IS NULL OR td.tutor_id = v_tutor)
    ) THEN
        RAISE check_violation USING MESSAGE = 'No se puede reactivar en la transacción que cerró vigencias por baja';
    END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION gestion_patin_privado.impedir_reactivacion_en_transaccion_de_baja() FROM PUBLIC;

CREATE TRIGGER trg_familia_guardar_reactivacion
    BEFORE UPDATE ON gestion_patin.familia
    FOR EACH ROW WHEN (NOT OLD.activa AND NEW.activa)
    EXECUTE FUNCTION gestion_patin_privado.impedir_reactivacion_en_transaccion_de_baja();
CREATE TRIGGER trg_deportista_guardar_reactivacion
    BEFORE UPDATE ON gestion_patin.deportista
    FOR EACH ROW WHEN (NOT OLD.activo AND NEW.activo)
    EXECUTE FUNCTION gestion_patin_privado.impedir_reactivacion_en_transaccion_de_baja();
CREATE TRIGGER trg_tutor_guardar_reactivacion
    BEFORE UPDATE ON gestion_patin.tutor
    FOR EACH ROW WHEN (NOT OLD.activo AND NEW.activo)
    EXECUTE FUNCTION gestion_patin_privado.impedir_reactivacion_en_transaccion_de_baja();
CREATE TRIGGER trg_fd_guardar_reactivacion
    BEFORE UPDATE ON gestion_patin.familia_deportista
    FOR EACH ROW WHEN (OLD.estado IS DISTINCT FROM 'ACTIVO' AND NEW.estado = 'ACTIVO')
    EXECUTE FUNCTION gestion_patin_privado.impedir_reactivacion_en_transaccion_de_baja();
