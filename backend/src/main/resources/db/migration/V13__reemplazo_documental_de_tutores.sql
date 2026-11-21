-- U19 / T1: el reemplazo de un DNI observado (U18) pasa a admitir también los DNI de un tutor.
-- Flyway ejecuta cada migración dentro de su propia transacción: no usar BEGIN/COMMIT.
-- Para operaciones de un deportista (tutor_id NULL en la operación y en el predecesor) el comportamiento es el de V12.
-- V5 (uq_documento_dni_tutor, FK de la cadena por tutor) y V6 (tutor_id y tipos DNI_TUTOR en la operación y en la
-- comparación al publicar) ya cubrían el esquema; solo faltaban el CHECK de tipo y el contexto del predecesor al reservar.
ALTER TABLE gestion_patin.operacion_carga_documental
    DROP CONSTRAINT ck_operacion_predecesor_tipo,
    ADD CONSTRAINT ck_operacion_predecesor_tipo CHECK (
        predecesor_id IS NULL
        OR tipo IN ('DNI_DEPORTISTA_FRENTE','DNI_DEPORTISTA_DORSO','DNI_TUTOR_FRENTE','DNI_TUTOR_DORSO'));

CREATE OR REPLACE FUNCTION gestion_patin.proteger_operacion_carga() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, gestion_patin AS $$
DECLARE
    instante timestamptz := clock_timestamp();
    activa boolean;
    documento gestion_patin.documento%ROWTYPE;
    anterior gestion_patin.documento%ROWTYPE;
    encontrado boolean;
BEGIN
    IF TG_OP = 'DELETE' THEN
        -- No hay política de purga aprobada: borrar la identidad permitiría olvidar un resultado incierto.
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='OPERACION_BORRADO_NO_HABILITADO';
    ELSIF TG_OP = 'INSERT' THEN
        IF NEW.estado <> 'RESERVADA' OR NEW.version <> 0 OR NEW.documento_id IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='OPERACION_INICIAL_INVALIDA';
        END IF;
        IF NEW.predecesor_id IS NOT NULL THEN
            SELECT * INTO anterior FROM gestion_patin.documento WHERE id=NEW.predecesor_id FOR KEY SHARE;
            IF NOT FOUND OR anterior.escuela_id IS DISTINCT FROM NEW.escuela_id
               OR anterior.deportista_id IS DISTINCT FROM NEW.deportista_id
               OR anterior.tutor_id IS DISTINCT FROM NEW.tutor_id
               OR anterior.tipo IS DISTINCT FROM NEW.tipo THEN
                RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='OPERACION_PREDECESOR_INCOMPATIBLE';
            END IF;
        END IF;
        RETURN NEW;
    END IF;

    IF ROW(NEW.id,NEW.escuela_id,NEW.solicitante_id,NEW.deportista_id,NEW.tutor_id,NEW.tipo,
           NEW.temporada_id,NEW.storage_bucket,NEW.objeto_id,NEW.idempotencia,NEW.creado_en,NEW.predecesor_id)
       IS DISTINCT FROM
       ROW(OLD.id,OLD.escuela_id,OLD.solicitante_id,OLD.deportista_id,OLD.tutor_id,OLD.tipo,
           OLD.temporada_id,OLD.storage_bucket,OLD.objeto_id,OLD.idempotencia,OLD.creado_en,OLD.predecesor_id) THEN
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
        encontrado := FOUND;
        IF NEW.predecesor_id IS NOT NULL THEN
            -- Reemplazo: el predecesor debe haber quedado OBSERVADO (nunca se revisa en ese estado) y no vigente.
            SELECT * INTO anterior FROM gestion_patin.documento WHERE id=NEW.predecesor_id FOR UPDATE;
            IF NOT FOUND OR anterior.vigente OR anterior.estado <> 'OBSERVADO' THEN
                RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='OPERACION_PREDECESOR_NO_REEMPLAZABLE';
            END IF;
        END IF;
        IF NOT encontrado OR ROW(documento.id,documento.escuela_id,documento.deportista_id,documento.tutor_id,
            documento.tipo,documento.temporada_id,documento.storage_bucket,documento.storage_key,
            documento.mime_type,documento.tamanio_bytes,documento.cargado_por,documento.serie_id,
            documento.documento_anterior_id,documento.estado,documento.vigente)
            IS DISTINCT FROM ROW(NEW.id,NEW.escuela_id,NEW.deportista_id,NEW.tutor_id,NEW.tipo,
            NEW.temporada_id,NEW.storage_bucket,NEW.objeto_id::text,NEW.mime_type,NEW.tamanio_bytes,
            NEW.solicitante_id,CASE WHEN NEW.predecesor_id IS NULL THEN NEW.id ELSE anterior.serie_id END,
            NEW.predecesor_id,'CARGADO'::varchar,true) THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='OPERACION_DOCUMENTO_INCOMPATIBLE';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
