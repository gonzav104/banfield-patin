-- B1: registro durable, no ejecuta cargas, escaneo ni reconciliación.
-- La versión es un token CAS, no prueba que un proceso o una petición externa hayan terminado.
CREATE TABLE gestion_patin.operacion_carga_documental (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    escuela_id uuid NOT NULL REFERENCES gestion_patin.escuela(id),
    solicitante_id uuid NOT NULL,
    deportista_id uuid,
    tutor_id uuid,
    tipo varchar(50) NOT NULL,
    temporada_id uuid,
    storage_bucket varchar(63) NOT NULL,
    objeto_id uuid NOT NULL DEFAULT gen_random_uuid(),
    idempotencia uuid NOT NULL,
    sha256_archivo bytea,
    mime_type varchar(100),
    tamanio_bytes bigint,
    estado varchar(20) NOT NULL DEFAULT 'RESERVADA',
    version bigint NOT NULL DEFAULT 0,
    reserva_hasta timestamptz,
    documento_id uuid,
    creado_en timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_operacion_idempotencia UNIQUE (escuela_id, solicitante_id, idempotencia),
    CONSTRAINT uq_operacion_destino UNIQUE (storage_bucket, objeto_id),
    CONSTRAINT fk_operacion_solicitante FOREIGN KEY (solicitante_id, escuela_id)
        REFERENCES gestion_patin.usuario(id, escuela_id),
    CONSTRAINT fk_operacion_deportista FOREIGN KEY (deportista_id, escuela_id)
        REFERENCES gestion_patin.deportista(id, escuela_id),
    CONSTRAINT fk_operacion_tutor FOREIGN KEY (tutor_id, escuela_id)
        REFERENCES gestion_patin.tutor(id, escuela_id),
    CONSTRAINT fk_operacion_temporada FOREIGN KEY (temporada_id, escuela_id)
        REFERENCES gestion_patin.temporada(id, escuela_id),
    CONSTRAINT fk_operacion_documento FOREIGN KEY (documento_id, escuela_id)
        REFERENCES gestion_patin.documento(id, escuela_id),
    CONSTRAINT ck_operacion_propietario CHECK (num_nonnulls(deportista_id, tutor_id) = 1),
    CONSTRAINT ck_operacion_tipo CHECK (
        (tipo IN ('DNI_DEPORTISTA_FRENTE','DNI_DEPORTISTA_DORSO','SOLICITUD_LICENCIA','CERTIFICADO_WADA') AND deportista_id IS NOT NULL)
        OR (tipo IN ('DNI_TUTOR_FRENTE','DNI_TUTOR_DORSO') AND tutor_id IS NOT NULL)
        OR tipo = 'OTRO'),
    CONSTRAINT ck_operacion_dni_temporada CHECK (
        tipo NOT IN ('DNI_DEPORTISTA_FRENTE','DNI_DEPORTISTA_DORSO','DNI_TUTOR_FRENTE','DNI_TUTOR_DORSO') OR temporada_id IS NULL),
    CONSTRAINT ck_operacion_destino CHECK (storage_bucket ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,62}$' AND objeto_id <> id),
    CONSTRAINT ck_operacion_manifiesto CHECK (num_nonnulls(sha256_archivo, mime_type, tamanio_bytes) IN (0,3)),
    CONSTRAINT ck_operacion_sha CHECK (sha256_archivo IS NULL OR octet_length(sha256_archivo) = 32),
    CONSTRAINT ck_operacion_mime CHECK (mime_type IS NULL OR mime_type IN ('application/pdf','image/jpeg','image/png')),
    CONSTRAINT ck_operacion_tamanio CHECK (tamanio_bytes IS NULL OR tamanio_bytes > 0),
    CONSTRAINT ck_operacion_estado CHECK (estado IN ('RESERVADA','TRANSFIRIENDO','ALMACENADA','PUBLICADA','RECONCILIANDO','CERRADA')),
    CONSTRAINT ck_operacion_estado_manifiesto CHECK (estado IN ('RESERVADA','CERRADA') OR sha256_archivo IS NOT NULL),
    CONSTRAINT ck_operacion_version CHECK (version >= 0),
    CONSTRAINT ck_operacion_tiempos CHECK (isfinite(creado_en) AND (reserva_hasta IS NULL OR isfinite(reserva_hasta))),
    CONSTRAINT ck_operacion_resultado CHECK ((estado = 'PUBLICADA') = (documento_id IS NOT NULL)),
    CONSTRAINT ck_operacion_documento_id CHECK (documento_id IS NULL OR documento_id = id),
    CONSTRAINT ck_operacion_terminal CHECK (estado NOT IN ('PUBLICADA','CERRADA') OR reserva_hasta IS NULL)
);

CREATE FUNCTION gestion_patin.proteger_operacion_carga() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, gestion_patin AS $$
DECLARE
    instante timestamptz := clock_timestamp();
    activa boolean;
    documento gestion_patin.documento%ROWTYPE;
BEGIN
    IF TG_OP = 'DELETE' THEN
        -- No hay política de purga aprobada: borrar la identidad permitiría olvidar un resultado incierto.
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='OPERACION_BORRADO_NO_HABILITADO';
    ELSIF TG_OP = 'INSERT' THEN
        IF NEW.estado <> 'RESERVADA' OR NEW.version <> 0 OR NEW.documento_id IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='OPERACION_INICIAL_INVALIDA';
        END IF;
        RETURN NEW;
    END IF;

    IF ROW(NEW.id,NEW.escuela_id,NEW.solicitante_id,NEW.deportista_id,NEW.tutor_id,NEW.tipo,
           NEW.temporada_id,NEW.storage_bucket,NEW.objeto_id,NEW.idempotencia,NEW.creado_en)
       IS DISTINCT FROM
       ROW(OLD.id,OLD.escuela_id,OLD.solicitante_id,OLD.deportista_id,OLD.tutor_id,OLD.tipo,
           OLD.temporada_id,OLD.storage_bucket,OLD.objeto_id,OLD.idempotencia,OLD.creado_en) THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='OPERACION_CONTEXTO_INMUTABLE';
    END IF;
    IF OLD.estado IN ('PUBLICADA','CERRADA') OR NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='OPERACION_VERSION_O_TERMINAL_INVALIDA';
    END IF;
    activa := OLD.reserva_hasta IS NOT NULL AND OLD.reserva_hasta > instante;
    IF ROW(NEW.sha256_archivo,NEW.mime_type,NEW.tamanio_bytes)
       IS DISTINCT FROM ROW(OLD.sha256_archivo,OLD.mime_type,OLD.tamanio_bytes) THEN
        IF OLD.sha256_archivo IS NOT NULL OR OLD.estado <> 'RESERVADA' OR NEW.estado <> 'RESERVADA'
           OR NOT activa OR NEW.reserva_hasta IS DISTINCT FROM OLD.reserva_hasta THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='OPERACION_MANIFIESTO_INMUTABLE';
        END IF;
    END IF;

    IF NEW.estado = OLD.estado THEN
        -- Sólo el titular con versión vigente puede renovar o renunciar a su reserva activa.
        -- Una renuncia puede dejar TRANSFIRIENDO vencida: no significa fin del POST remoto.
        IF NOT activa AND (OLD.estado = 'TRANSFIRIENDO' OR NEW.reserva_hasta IS NULL OR NEW.reserva_hasta <= instante) THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='OPERACION_RESERVA_NO_ADQUIRIBLE';
        END IF;
    ELSE
        IF NOT ((OLD.estado='RESERVADA' AND NEW.estado IN ('TRANSFIRIENDO','CERRADA'))
            OR (OLD.estado='TRANSFIRIENDO' AND NEW.estado IN ('ALMACENADA','RECONCILIANDO'))
            OR (OLD.estado='ALMACENADA' AND NEW.estado IN ('PUBLICADA','RECONCILIANDO'))
            OR (OLD.estado='RECONCILIANDO' AND NEW.estado IN ('ALMACENADA','CERRADA'))) THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='OPERACION_TRANSICION_INVALIDA';
        END IF;
        IF NOT activa AND NEW.estado <> 'RECONCILIANDO' THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='OPERACION_RESERVA_VENCIDA';
        END IF;
        IF NEW.estado NOT IN ('PUBLICADA','CERRADA') AND (NEW.reserva_hasta IS NULL OR NEW.reserva_hasta <= instante) THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='OPERACION_RESERVA_REQUERIDA';
        END IF;
    END IF;

    IF NEW.estado = 'PUBLICADA' THEN
        SELECT * INTO documento FROM gestion_patin.documento WHERE id=NEW.documento_id FOR UPDATE;
        IF NOT FOUND OR ROW(documento.id,documento.escuela_id,documento.deportista_id,documento.tutor_id,
            documento.tipo,documento.temporada_id,documento.storage_bucket,documento.storage_key,
            documento.mime_type,documento.tamanio_bytes,documento.cargado_por,documento.serie_id,
            documento.documento_anterior_id,documento.estado,documento.vigente)
            IS DISTINCT FROM ROW(NEW.id,NEW.escuela_id,NEW.deportista_id,NEW.tutor_id,NEW.tipo,
            NEW.temporada_id,NEW.storage_bucket,NEW.objeto_id::text,NEW.mime_type,NEW.tamanio_bytes,
            NEW.solicitante_id,NEW.id,NULL::uuid,'CARGADO'::varchar,true) THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='OPERACION_DOCUMENTO_INCOMPATIBLE';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_proteger_operacion_carga BEFORE INSERT OR UPDATE OR DELETE
ON gestion_patin.operacion_carga_documental FOR EACH ROW
EXECUTE FUNCTION gestion_patin.proteger_operacion_carga();
