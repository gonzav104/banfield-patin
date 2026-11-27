-- U21-BIS (previo a C2): la pertenencia de una cuenta y la procedencia de un documento no cambian después de creadas.
--
-- La privacidad documental entre familias se decide por procedencia: los datos de un documento pertenecen a la familia
-- del usuario que lo cargó (documento.cargado_por -> usuario.familia_id). Si una cuenta pudiera cambiar de familia, de
-- escuela o de rol, o si un documento pudiera cambiar de autor, esa decisión se reescribiría hacia atrás sin dejar
-- rastro. Hasta ahora solo lo impedía la aplicación (la entidad mapea familia_id con updatable = false); desde V18 lo
-- impide la base, para cualquier rol que escriba (incluido el runtime, que tiene UPDATE a nivel de tabla sobre usuario).
--
-- Aditiva: no toca datos ni migraciones anteriores. No introduce ningún mecanismo de reasignación entre familias.
-- ADMIN: familia_id es NULL y debe seguir siéndolo (IS DISTINCT FROM compara NULL de forma segura).

CREATE FUNCTION gestion_patin.proteger_pertenencia_usuario() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, gestion_patin AS $$
BEGIN
    IF ROW(NEW.id, NEW.escuela_id, NEW.familia_id, NEW.rol)
       IS DISTINCT FROM ROW(OLD.id, OLD.escuela_id, OLD.familia_id, OLD.rol) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'USUARIO_PERTENENCIA_INMUTABLE';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_usuario_pertenencia BEFORE UPDATE ON gestion_patin.usuario
    FOR EACH ROW EXECUTE FUNCTION gestion_patin.proteger_pertenencia_usuario();

CREATE FUNCTION gestion_patin.proteger_procedencia_documento() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, gestion_patin AS $$
BEGIN
    IF NEW.cargado_por IS DISTINCT FROM OLD.cargado_por THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'DOCUMENTO_PROCEDENCIA_INMUTABLE';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_documento_procedencia BEFORE UPDATE ON gestion_patin.documento
    FOR EACH ROW EXECUTE FUNCTION gestion_patin.proteger_procedencia_documento();

REVOKE ALL ON FUNCTION gestion_patin.proteger_pertenencia_usuario() FROM PUBLIC;
REVOKE ALL ON FUNCTION gestion_patin.proteger_procedencia_documento() FROM PUBLIC;
