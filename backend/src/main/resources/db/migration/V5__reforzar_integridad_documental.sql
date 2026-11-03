-- D2: integridad documental; no incorpora mutadores, Storage ni anualidad de WADA.
-- Flyway ejecuta este archivo en una transacción. El lock evita carreras entre precheck y backfill.
LOCK TABLE gestion_patin.documento IN ACCESS EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM gestion_patin.documento WHERE
        (tipo IN ('DNI_DEPORTISTA_FRENTE','DNI_DEPORTISTA_DORSO','SOLICITUD_LICENCIA','CERTIFICADO_WADA') AND deportista_id IS NULL)
        OR (tipo IN ('DNI_TUTOR_FRENTE','DNI_TUTOR_DORSO') AND tutor_id IS NULL)
        OR (tipo IN ('DNI_DEPORTISTA_FRENTE','DNI_DEPORTISTA_DORSO','DNI_TUTOR_FRENTE','DNI_TUTOR_DORSO') AND temporada_id IS NOT NULL)) THEN
        RAISE EXCEPTION 'D2: tipo/propietario/temporada incompatible; requiere resolución manual' USING ERRCODE='23514';
    END IF;
    IF EXISTS (SELECT 1 FROM gestion_patin.documento d JOIN gestion_patin.documento a ON a.id=d.documento_anterior_id
        WHERE ROW(d.escuela_id,d.deportista_id,d.tutor_id,d.tipo,d.temporada_id)
            IS DISTINCT FROM ROW(a.escuela_id,a.deportista_id,a.tutor_id,a.tipo,a.temporada_id)) THEN
        RAISE EXCEPTION 'D2: cadena con contexto incompatible; requiere resolución manual' USING ERRCODE='23514';
    END IF;
    IF EXISTS (SELECT 1 FROM gestion_patin.documento WHERE documento_anterior_id IS NOT NULL
        GROUP BY documento_anterior_id HAVING count(*)>1) THEN
        RAISE EXCEPTION 'D2: historial bifurcado; requiere resolución manual' USING ERRCODE='23514';
    END IF;
    IF EXISTS (SELECT 1 FROM gestion_patin.documento WHERE vigente
        AND tipo IN ('DNI_DEPORTISTA_FRENTE','DNI_DEPORTISTA_DORSO','DNI_TUTOR_FRENTE','DNI_TUTOR_DORSO')
        GROUP BY escuela_id,deportista_id,tutor_id,tipo HAVING count(*)>1) THEN
        RAISE EXCEPTION 'D2: DNI vigente duplicado; requiere resolución manual' USING ERRCODE='23514';
    END IF;
    -- Cada nodo tiene un solo padre: los ciclos no alcanzables desde una raíz quedan fuera del recorrido.
    IF (WITH RECURSIVE cadenas AS (
        SELECT id FROM gestion_patin.documento WHERE documento_anterior_id IS NULL
        UNION ALL SELECT d.id FROM gestion_patin.documento d JOIN cadenas c ON d.documento_anterior_id=c.id
    ) SELECT count(*) FROM cadenas) <> (SELECT count(*) FROM gestion_patin.documento) THEN
        RAISE EXCEPTION 'D2: historial sin raíz o cíclico; requiere resolución manual' USING ERRCODE='23514';
    END IF;
    IF EXISTS (WITH RECURSIVE cadenas AS (
        SELECT id,id AS raiz,vigente FROM gestion_patin.documento WHERE documento_anterior_id IS NULL
        UNION ALL SELECT d.id,c.raiz,d.vigente FROM gestion_patin.documento d JOIN cadenas c ON d.documento_anterior_id=c.id
    ) SELECT 1 FROM cadenas GROUP BY raiz HAVING count(*) FILTER (WHERE vigente)>1) THEN
        RAISE EXCEPTION 'D2: múltiples vigentes en una cadena; requiere resolución manual' USING ERRCODE='23514';
    END IF;
END $$;

ALTER TABLE gestion_patin.documento ADD COLUMN serie_id uuid,
    ADD COLUMN version bigint NOT NULL DEFAULT 0;

-- El backfill sólo agrega identidad de serie; preserva los timestamps y todos los valores V4.
ALTER TABLE gestion_patin.documento DISABLE TRIGGER trg_documento_actualizado;
WITH RECURSIVE cadenas AS (
    SELECT id,id AS raiz FROM gestion_patin.documento WHERE documento_anterior_id IS NULL
    UNION ALL SELECT d.id,c.raiz FROM gestion_patin.documento d JOIN cadenas c ON d.documento_anterior_id=c.id
)
UPDATE gestion_patin.documento d SET serie_id=c.raiz FROM cadenas c WHERE d.id=c.id;
ALTER TABLE gestion_patin.documento ENABLE TRIGGER trg_documento_actualizado;

ALTER TABLE gestion_patin.documento
    ALTER COLUMN serie_id SET NOT NULL,
    ADD CONSTRAINT ck_documento_version CHECK (version >= 0),
    ADD CONSTRAINT ck_documento_raiz_serie CHECK (documento_anterior_id IS NOT NULL OR serie_id=id),
    ADD CONSTRAINT ck_documento_no_autorreferencia CHECK (documento_anterior_id IS DISTINCT FROM id),
    ADD CONSTRAINT ck_documento_tipo_propietario CHECK (
        (tipo IN ('DNI_DEPORTISTA_FRENTE','DNI_DEPORTISTA_DORSO','SOLICITUD_LICENCIA','CERTIFICADO_WADA') AND deportista_id IS NOT NULL)
        OR (tipo IN ('DNI_TUTOR_FRENTE','DNI_TUTOR_DORSO') AND tutor_id IS NOT NULL)
        OR tipo IN ('OTRO','APTO_FISICO')),
    ADD CONSTRAINT ck_documento_dni_sin_temporada CHECK (
        tipo NOT IN ('DNI_DEPORTISTA_FRENTE','DNI_DEPORTISTA_DORSO','DNI_TUTOR_FRENTE','DNI_TUTOR_DORSO') OR temporada_id IS NULL),
    ADD CONSTRAINT uq_documento_contexto_deportista UNIQUE (id,escuela_id,deportista_id,tipo,serie_id),
    ADD CONSTRAINT uq_documento_contexto_tutor UNIQUE (id,escuela_id,tutor_id,tipo,serie_id),
    DROP CONSTRAINT fk_documento_anterior,
    -- MATCH SIMPLE es intencional: XOR de V1 garantiza que exactamente una rama tenga propietario no nulo.
    ADD CONSTRAINT fk_documento_anterior_deportista FOREIGN KEY (documento_anterior_id,escuela_id,deportista_id,tipo,serie_id)
        REFERENCES gestion_patin.documento(id,escuela_id,deportista_id,tipo,serie_id),
    ADD CONSTRAINT fk_documento_anterior_tutor FOREIGN KEY (documento_anterior_id,escuela_id,tutor_id,tipo,serie_id)
        REFERENCES gestion_patin.documento(id,escuela_id,tutor_id,tipo,serie_id);

CREATE UNIQUE INDEX uq_documento_vigente_serie ON gestion_patin.documento(escuela_id,serie_id) WHERE vigente;
CREATE UNIQUE INDEX uq_documento_sucesor ON gestion_patin.documento(documento_anterior_id) WHERE documento_anterior_id IS NOT NULL;
CREATE UNIQUE INDEX uq_documento_dni_deportista ON gestion_patin.documento(escuela_id,deportista_id,tipo)
    WHERE vigente AND tipo IN ('DNI_DEPORTISTA_FRENTE','DNI_DEPORTISTA_DORSO');
CREATE UNIQUE INDEX uq_documento_dni_tutor ON gestion_patin.documento(escuela_id,tutor_id,tipo)
    WHERE vigente AND tipo IN ('DNI_TUTOR_FRENTE','DNI_TUTOR_DORSO');

-- Sólo las invariantes no expresadas arriba: contexto inmutable y temporada nullable del predecesor.
-- Cada raíz usa su propio ID; un sucesor sólo apunta a un padre ya visible y conserva la serie.
-- Así no hay nuevas cadenas cíclicas ni cambios posteriores de sus aristas.
CREATE FUNCTION gestion_patin.validar_contexto_documento() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE anterior gestion_patin.documento%ROWTYPE;
BEGIN
    IF TG_OP='UPDATE' THEN
        IF ROW(NEW.id,NEW.escuela_id,NEW.deportista_id,NEW.tutor_id,NEW.tipo,NEW.temporada_id,NEW.serie_id,NEW.documento_anterior_id)
            IS DISTINCT FROM ROW(OLD.id,OLD.escuela_id,OLD.deportista_id,OLD.tutor_id,OLD.tipo,OLD.temporada_id,OLD.serie_id,OLD.documento_anterior_id) THEN
            RAISE EXCEPTION 'D2: contexto histórico inmutable' USING ERRCODE='23514';
        END IF;
    ELSIF NEW.documento_anterior_id IS NULL THEN
        NEW.serie_id := coalesce(NEW.serie_id,NEW.id);
    ELSE
        SELECT * INTO anterior FROM gestion_patin.documento WHERE id=NEW.documento_anterior_id FOR KEY SHARE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'D2: predecesor inexistente' USING ERRCODE='23503';
        END IF;
        IF NEW.temporada_id IS DISTINCT FROM anterior.temporada_id THEN
            RAISE EXCEPTION 'D2: temporada incompatible con predecesor' USING ERRCODE='23514';
        END IF;
        NEW.serie_id := coalesce(NEW.serie_id,anterior.serie_id);
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_documento_contexto BEFORE INSERT OR UPDATE ON gestion_patin.documento
    FOR EACH ROW EXECUTE FUNCTION gestion_patin.validar_contexto_documento();
