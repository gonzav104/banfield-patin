-- C2-B: alta idempotente, edición del tipo declarado y revocación individual de vigencias.
-- No modifica V14 ni V15 ni concede DML directo al runtime. El login de aplicación es compartido:
-- Java aporta el actor autenticado y la base solo corrobora su rol y su escuela; no autentica identidades HTTP.
-- SQLSTATE propios (clase BP): BP404 no encontrado, BP405 clave de idempotencia reutilizada con otro contenido,
-- BP412 estado incompatible, BP413 responsable ya vigente, BP414 revisión desactualizada.

CREATE FUNCTION gestion_patin.crear_vigencia_tutor(
    p_escuela uuid, p_actor uuid, p_deportista uuid, p_tutor uuid, p_tipo text, p_solicitud uuid, p_huella text
) RETURNS TABLE (vigencia_id uuid, creada boolean)
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_familia uuid;
    v_previa gestion_patin.tutor_deportista;
    v_estado_fd varchar(20);
    v_activo boolean;
    v_id uuid;
    v_restriccion text;
BEGIN
    IF NOT EXISTS (
        SELECT FROM gestion_patin.usuario u
        WHERE u.id = p_actor AND u.escuela_id = p_escuela AND u.rol = 'ADMIN' AND u.activo
    ) THEN
        RAISE insufficient_privilege USING MESSAGE = 'Actor de alta no autorizado';
    END IF;

    IF p_escuela IS NULL OR p_deportista IS NULL OR p_tutor IS NULL OR p_solicitud IS NULL
       OR p_huella IS NULL OR p_huella !~ '^[0-9a-f]{64}$'
       OR (p_tipo IS NOT NULL AND p_tipo NOT IN ('MADRE', 'PADRE', 'TUTOR_LEGAL', 'OTRO_RESPONSABLE', 'SIN_DETERMINAR')) THEN
        RAISE invalid_parameter_value USING MESSAGE = 'Alta de vigencia inválida';
    END IF;

    -- La clave pertenece a la escuela: la misma clave en otra escuela es independiente y nunca se revela.
    SELECT * INTO v_previa FROM gestion_patin.tutor_deportista td
    WHERE td.escuela_id = p_escuela AND td.solicitud_id = p_solicitud;
    IF FOUND THEN
        IF v_previa.huella_solicitud <> p_huella THEN
            RAISE SQLSTATE 'BP405' USING MESSAGE = 'La clave de idempotencia ya se usó con otro contenido';
        END IF;
        RETURN QUERY SELECT v_previa.id, false;
        RETURN;
    END IF;

    SELECT t.familia_id INTO v_familia FROM gestion_patin.tutor t
    WHERE t.escuela_id = p_escuela AND t.id = p_tutor;
    IF NOT FOUND OR NOT EXISTS (
        SELECT FROM gestion_patin.deportista d WHERE d.escuela_id = p_escuela AND d.id = p_deportista
    ) THEN
        RAISE SQLSTATE 'BP404' USING MESSAGE = 'Recurso no encontrado';
    END IF;

    -- Los cuatro estados se leen con el mismo bloqueo que toman las FKs de V14 (KEY SHARE sobre la marca):
    -- un cierre concurrente espera a esta transacción y la baja posterior ve la vigencia. Orden fijo.
    SELECT m.estado INTO v_activo FROM gestion_patin_privado.marca_familia m
    WHERE m.escuela_id = p_escuela AND m.familia_id = v_familia FOR KEY SHARE;
    IF NOT FOUND OR NOT v_activo THEN
        RAISE SQLSTATE 'BP412' USING MESSAGE = 'La familia no está activa';
    END IF;
    SELECT m.estado INTO v_activo FROM gestion_patin_privado.marca_deportista m
    WHERE m.escuela_id = p_escuela AND m.deportista_id = p_deportista FOR KEY SHARE;
    IF NOT FOUND OR NOT v_activo THEN
        RAISE SQLSTATE 'BP412' USING MESSAGE = 'El deportista no está activo';
    END IF;
    SELECT m.estado INTO v_estado_fd FROM gestion_patin_privado.marca_familia_deportista m
    WHERE m.escuela_id = p_escuela AND m.familia_id = v_familia AND m.deportista_id = p_deportista FOR KEY SHARE;
    IF NOT FOUND OR v_estado_fd <> 'ACTIVO' THEN
        RAISE SQLSTATE 'BP412' USING MESSAGE = 'El deportista no tiene un vínculo familiar activo con la familia del tutor';
    END IF;
    SELECT m.estado INTO v_activo FROM gestion_patin_privado.marca_tutor m
    WHERE m.escuela_id = p_escuela AND m.familia_id = v_familia AND m.tutor_id = p_tutor FOR KEY SHARE;
    IF NOT FOUND OR NOT v_activo THEN
        RAISE SQLSTATE 'BP412' USING MESSAGE = 'El tutor no está activo';
    END IF;

    IF EXISTS (
        SELECT FROM gestion_patin.tutor_deportista td
        WHERE td.escuela_id = p_escuela AND td.tutor_id = p_tutor AND td.deportista_id = p_deportista
          AND td.cerrado_en IS NULL
    ) THEN
        RAISE SQLSTATE 'BP413' USING MESSAGE = 'El responsable ya tiene una vigencia abierta';
    END IF;

    BEGIN
        INSERT INTO gestion_patin.tutor_deportista (
            escuela_id, familia_id, deportista_id, tutor_id, tipo, solicitud_id, huella_solicitud, creado_por)
        VALUES (p_escuela, v_familia, p_deportista, p_tutor, p_tipo, p_solicitud, p_huella, p_actor)
        RETURNING id INTO v_id;
    EXCEPTION WHEN unique_violation THEN
        GET STACKED DIAGNOSTICS v_restriccion = CONSTRAINT_NAME;
        -- PostgreSQL informa el primer índice que detecta: una repetición concurrente de la misma clave puede llegar
        -- por el índice del par. La clave se resuelve siempre primero; solo después se decide si es un duplicado.
        SELECT * INTO v_previa FROM gestion_patin.tutor_deportista td
        WHERE td.escuela_id = p_escuela AND td.solicitud_id = p_solicitud;
        IF FOUND THEN
            IF v_previa.huella_solicitud <> p_huella THEN
                RAISE SQLSTATE 'BP405' USING MESSAGE = 'La clave de idempotencia ya se usó con otro contenido';
            END IF;
            RETURN QUERY SELECT v_previa.id, false;
            RETURN;
        END IF;
        -- Con un snapshot que no ve la fila concurrente no se puede distinguir un duplicado de una repetición:
        -- se pide repetir la solicitud en una transacción nueva, que la resuelve sin ambigüedad.
        IF v_restriccion = 'uq_td_par_vigente'
           AND current_setting('transaction_isolation') = 'read committed' THEN
            RAISE SQLSTATE 'BP413' USING MESSAGE = 'El responsable ya tiene una vigencia abierta';
        END IF;
        RAISE serialization_failure USING MESSAGE = 'Conflicto de concurrencia en el alta; repetir la solicitud';
    END;

    INSERT INTO gestion_patin.auditoria (escuela_id, usuario_id, accion, recurso_tipo, recurso_id, detalle)
    VALUES (p_escuela, p_actor, 'VIGENCIA_TUTOR_CREADA', 'TUTOR_DEPORTISTA', v_id,
            jsonb_build_object('familiaId', v_familia, 'deportistaId', p_deportista, 'tutorId', p_tutor));
    RETURN QUERY SELECT v_id, true;
END;
$$;

ALTER FUNCTION gestion_patin.crear_vigencia_tutor(uuid, uuid, uuid, uuid, text, uuid, text) OWNER TO CURRENT_USER;
REVOKE ALL ON FUNCTION gestion_patin.crear_vigencia_tutor(uuid, uuid, uuid, uuid, text, uuid, text) FROM PUBLIC;

-- Solo cambia el tipo declarado de una vigencia abierta. Sin cambio efectivo no escribe, no audita ni mueve la revisión.
CREATE FUNCTION gestion_patin.actualizar_tipo_vigencia_tutor(
    p_escuela uuid, p_actor uuid, p_vigencia uuid, p_version bigint, p_tipo text
) RETURNS TABLE (version_nueva bigint, cambio boolean)
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v gestion_patin.tutor_deportista;
BEGIN
    IF NOT EXISTS (
        SELECT FROM gestion_patin.usuario u
        WHERE u.id = p_actor AND u.escuela_id = p_escuela AND u.rol = 'ADMIN' AND u.activo
    ) THEN
        RAISE insufficient_privilege USING MESSAGE = 'Actor de edición no autorizado';
    END IF;
    IF p_vigencia IS NULL OR p_version IS NULL OR p_version < 0
       OR (p_tipo IS NOT NULL AND p_tipo NOT IN ('MADRE', 'PADRE', 'TUTOR_LEGAL', 'OTRO_RESPONSABLE', 'SIN_DETERMINAR')) THEN
        RAISE invalid_parameter_value USING MESSAGE = 'Edición de vigencia inválida';
    END IF;

    SELECT * INTO v FROM gestion_patin.tutor_deportista td
    WHERE td.escuela_id = p_escuela AND td.id = p_vigencia FOR NO KEY UPDATE;
    IF NOT FOUND THEN
        RAISE SQLSTATE 'BP404' USING MESSAGE = 'Recurso no encontrado';
    END IF;
    IF v.version <> p_version THEN
        RAISE SQLSTATE 'BP414' USING MESSAGE = 'La revisión de la vigencia cambió';
    END IF;
    IF v.cerrado_en IS NOT NULL THEN
        RAISE SQLSTATE 'BP412' USING MESSAGE = 'La vigencia está cerrada';
    END IF;
    IF v.tipo IS NOT DISTINCT FROM p_tipo THEN
        RETURN QUERY SELECT v.version, false;
        RETURN;
    END IF;

    UPDATE gestion_patin.tutor_deportista td SET tipo = p_tipo, version = td.version + 1
    WHERE td.escuela_id = p_escuela AND td.id = p_vigencia;
    INSERT INTO gestion_patin.auditoria (escuela_id, usuario_id, accion, recurso_tipo, recurso_id, detalle)
    VALUES (p_escuela, p_actor, 'VIGENCIA_TUTOR_TIPO_ACTUALIZADA', 'TUTOR_DEPORTISTA', p_vigencia,
            jsonb_build_object('campos', jsonb_build_array('tipo')));
    RETURN QUERY SELECT v.version + 1, true;
END;
$$;

ALTER FUNCTION gestion_patin.actualizar_tipo_vigencia_tutor(uuid, uuid, uuid, bigint, text) OWNER TO CURRENT_USER;
REVOKE ALL ON FUNCTION gestion_patin.actualizar_tipo_vigencia_tutor(uuid, uuid, uuid, bigint, text) FROM PUBLIC;

-- Cierre individual e irreversible. No toca a los padres: solo la fila de la vigencia, por lo que no participa
-- del orden padre → marca → vigencias de los cierres por baja. La auditoría se escribe aquí porque la guarda
-- diferida de V15 excluye REVOCACION_ADMIN.
CREATE FUNCTION gestion_patin.revocar_vigencia_tutor(
    p_escuela uuid, p_actor uuid, p_vigencia uuid, p_version bigint
) RETURNS bigint
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v gestion_patin.tutor_deportista;
BEGIN
    IF NOT EXISTS (
        SELECT FROM gestion_patin.usuario u
        WHERE u.id = p_actor AND u.escuela_id = p_escuela AND u.rol = 'ADMIN' AND u.activo
    ) THEN
        RAISE insufficient_privilege USING MESSAGE = 'Actor de revocación no autorizado';
    END IF;
    IF p_vigencia IS NULL OR p_version IS NULL OR p_version < 0 THEN
        RAISE invalid_parameter_value USING MESSAGE = 'Revocación de vigencia inválida';
    END IF;

    SELECT * INTO v FROM gestion_patin.tutor_deportista td
    WHERE td.escuela_id = p_escuela AND td.id = p_vigencia FOR NO KEY UPDATE;
    IF NOT FOUND THEN
        RAISE SQLSTATE 'BP404' USING MESSAGE = 'Recurso no encontrado';
    END IF;
    IF v.version <> p_version THEN
        RAISE SQLSTATE 'BP414' USING MESSAGE = 'La revisión de la vigencia cambió';
    END IF;
    IF v.cerrado_en IS NOT NULL THEN
        RAISE SQLSTATE 'BP412' USING MESSAGE = 'La vigencia ya está cerrada';
    END IF;

    UPDATE gestion_patin.tutor_deportista td
    SET cerrado_en = greatest(clock_timestamp(), td.creado_en), cerrado_por = p_actor,
        causa_cierre = 'REVOCACION_ADMIN', version = td.version + 1
    WHERE td.escuela_id = p_escuela AND td.id = p_vigencia;
    INSERT INTO gestion_patin.auditoria (escuela_id, usuario_id, accion, recurso_tipo, recurso_id, detalle)
    VALUES (p_escuela, p_actor, 'VIGENCIA_TUTOR_CERRADA', 'TUTOR_DEPORTISTA', p_vigencia,
            jsonb_build_object('causa', 'REVOCACION_ADMIN'));
    RETURN v.version + 1;
END;
$$;

ALTER FUNCTION gestion_patin.revocar_vigencia_tutor(uuid, uuid, uuid, bigint) OWNER TO CURRENT_USER;
REVOKE ALL ON FUNCTION gestion_patin.revocar_vigencia_tutor(uuid, uuid, uuid, bigint) FROM PUBLIC;
