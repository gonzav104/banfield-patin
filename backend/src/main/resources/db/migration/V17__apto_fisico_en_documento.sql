-- U21: el apto físico anual se guarda en `documento` (una versión por carga) y en la operación de carga que lo publica.
-- No usa `apto_fisico` (reservada para U22: presentación). Aditiva: no modifica V14-V16 ni crea documentos ni asociaciones.
-- Flyway ejecuta cada migración dentro de su propia transacción: no usar BEGIN/COMMIT.
-- Un APTO_FISICO ya existente (hoy no hay forma de cargarlo) haría fallar la validación de los CHECK: se corrige a mano, nunca se rellena.

-- 1) Metadatos propios de cada versión documental, exclusivos del apto.
ALTER TABLE gestion_patin.documento
    ADD COLUMN fecha_emision date,
    ADD COLUMN medico_nombre varchar(150),
    ADD COLUMN medico_matricula varchar(50),
    ADD CONSTRAINT ck_documento_apto_metadatos CHECK (
        (tipo = 'APTO_FISICO' AND num_nonnulls(fecha_emision, medico_nombre, medico_matricula) = 3)
        OR (tipo <> 'APTO_FISICO' AND num_nonnulls(fecha_emision, medico_nombre, medico_matricula) = 0)),
    ADD CONSTRAINT ck_documento_apto_formato CHECK (
        tipo <> 'APTO_FISICO' OR (
            fecha_emision >= DATE '2000-01-01'
            AND medico_nombre = btrim(medico_nombre) AND char_length(medico_nombre) BETWEEN 1 AND 150
            AND medico_nombre !~ '[[:cntrl:]]'
            AND medico_matricula = btrim(medico_matricula) AND char_length(medico_matricula) BETWEEN 1 AND 50
            AND medico_matricula !~ '[[:cntrl:]]')),
    -- Un apto es del deportista y de una temporada de su escuela (la FK compuesta de V1 ya fija la escuela).
    ADD CONSTRAINT ck_documento_apto_contexto CHECK (
        tipo <> 'APTO_FISICO' OR (deportista_id IS NOT NULL AND temporada_id IS NOT NULL));

-- Una sola serie vigente por deportista y temporada. Las versiones históricas (vigente = false) no cuentan.
CREATE UNIQUE INDEX uq_documento_apto_vigente ON gestion_patin.documento(escuela_id, deportista_id, temporada_id)
    WHERE vigente AND tipo = 'APTO_FISICO';

-- Los metadatos de una versión solo cambian mientras es la vigente y siguen pendientes de revisión: una corrección
-- de un apto APROBADO retira su aprobación en la misma sentencia (nunca queda aprobado con datos distintos a los revisados).
CREATE FUNCTION gestion_patin.proteger_metadatos_apto() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, gestion_patin AS $$
BEGIN
    IF ROW(NEW.fecha_emision, NEW.medico_nombre, NEW.medico_matricula)
       IS DISTINCT FROM ROW(OLD.fecha_emision, OLD.medico_nombre, OLD.medico_matricula) THEN
        IF NOT OLD.vigente OR NOT NEW.vigente OR OLD.estado NOT IN ('CARGADO', 'APROBADO') OR NEW.estado <> 'CARGADO' THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'APTO_METADATOS_NO_EDITABLES';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_documento_apto_metadatos BEFORE UPDATE ON gestion_patin.documento
    FOR EACH ROW EXECUTE FUNCTION gestion_patin.proteger_metadatos_apto();

-- 2) La operación de carga transporta los metadatos hasta la publicación (forman parte de su identidad idempotente).
ALTER TABLE gestion_patin.operacion_carga_documental
    ADD COLUMN fecha_emision date,
    ADD COLUMN medico_nombre varchar(150),
    ADD COLUMN medico_matricula varchar(50),
    DROP CONSTRAINT ck_operacion_tipo,
    ADD CONSTRAINT ck_operacion_tipo CHECK (
        (tipo IN ('DNI_DEPORTISTA_FRENTE','DNI_DEPORTISTA_DORSO','SOLICITUD_LICENCIA','CERTIFICADO_WADA','APTO_FISICO')
            AND deportista_id IS NOT NULL)
        OR (tipo IN ('DNI_TUTOR_FRENTE','DNI_TUTOR_DORSO') AND tutor_id IS NOT NULL)
        OR tipo = 'OTRO'),
    DROP CONSTRAINT ck_operacion_predecesor_tipo,
    ADD CONSTRAINT ck_operacion_predecesor_tipo CHECK (
        predecesor_id IS NULL
        OR tipo IN ('DNI_DEPORTISTA_FRENTE','DNI_DEPORTISTA_DORSO','DNI_TUTOR_FRENTE','DNI_TUTOR_DORSO','APTO_FISICO')),
    ADD CONSTRAINT ck_operacion_apto_metadatos CHECK (
        (tipo = 'APTO_FISICO' AND temporada_id IS NOT NULL AND num_nonnulls(fecha_emision, medico_nombre, medico_matricula) = 3)
        OR (tipo <> 'APTO_FISICO' AND num_nonnulls(fecha_emision, medico_nombre, medico_matricula) = 0)),
    ADD CONSTRAINT ck_operacion_apto_formato CHECK (
        tipo <> 'APTO_FISICO' OR (
            fecha_emision >= DATE '2000-01-01'
            AND medico_nombre = btrim(medico_nombre) AND char_length(medico_nombre) BETWEEN 1 AND 150
            AND medico_nombre !~ '[[:cntrl:]]'
            AND medico_matricula = btrim(medico_matricula) AND char_length(medico_matricula) BETWEEN 1 AND 50
            AND medico_matricula !~ '[[:cntrl:]]'));

-- 3) Misma protección que V13, ampliada: los metadatos y la temporada del predecesor forman parte del contexto
--    inmutable y deben coincidir con el documento publicado. Para todo tipo que no sea APTO_FISICO los metadatos son
--    NULL en ambos lados: el comportamiento de V13 no cambia.
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
               OR anterior.tipo IS DISTINCT FROM NEW.tipo
               OR anterior.temporada_id IS DISTINCT FROM NEW.temporada_id THEN
                RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='OPERACION_PREDECESOR_INCOMPATIBLE';
            END IF;
        END IF;
        RETURN NEW;
    END IF;

    IF ROW(NEW.id,NEW.escuela_id,NEW.solicitante_id,NEW.deportista_id,NEW.tutor_id,NEW.tipo,
           NEW.temporada_id,NEW.storage_bucket,NEW.objeto_id,NEW.idempotencia,NEW.creado_en,NEW.predecesor_id,
           NEW.fecha_emision,NEW.medico_nombre,NEW.medico_matricula)
       IS DISTINCT FROM
       ROW(OLD.id,OLD.escuela_id,OLD.solicitante_id,OLD.deportista_id,OLD.tutor_id,OLD.tipo,
           OLD.temporada_id,OLD.storage_bucket,OLD.objeto_id,OLD.idempotencia,OLD.creado_en,OLD.predecesor_id,
           OLD.fecha_emision,OLD.medico_nombre,OLD.medico_matricula) THEN
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
            documento.documento_anterior_id,documento.estado,documento.vigente,
            documento.fecha_emision,documento.medico_nombre,documento.medico_matricula)
            IS DISTINCT FROM ROW(NEW.id,NEW.escuela_id,NEW.deportista_id,NEW.tutor_id,NEW.tipo,
            NEW.temporada_id,NEW.storage_bucket,NEW.objeto_id::text,NEW.mime_type,NEW.tamanio_bytes,
            NEW.solicitante_id,CASE WHEN NEW.predecesor_id IS NULL THEN NEW.id ELSE anterior.serie_id END,
            NEW.predecesor_id,'CARGADO'::varchar,true,
            NEW.fecha_emision,NEW.medico_nombre,NEW.medico_matricula) THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='OPERACION_DOCUMENTO_INCOMPATIBLE';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
