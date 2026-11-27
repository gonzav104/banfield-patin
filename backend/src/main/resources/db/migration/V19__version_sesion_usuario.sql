-- U21-BIS C0: quitar el acceso a una cuenta revoca DEFINITIVAMENTE sus sesiones; reactivarla no las revive.
--
-- Cada solicitud autenticada revalida en la base que la cuenta sigue activa: desactivarla corta en la siguiente
-- solicitud las sesiones ya iniciadas. Sin más, un JWT emitido antes de la desactivación (vida de hasta 8 h) volvería a
-- valer si la cuenta se reactiva antes de que venza. version_sesion es una generación por cuenta: cada JWT lleva la
-- versión vigente al emitirse (claim version_sesion) y la revalidación exige que siga siendo la de la fila. Quitar el
-- acceso la incrementa EN LA MISMA SENTENCIA que pone activo = false, así que todo token anterior queda revocado para
-- siempre, sin depender de la hora ni de la precisión del iat. Devolver el acceso no la toca.
--
-- Nunca retrocede (trigger): volver a un valor anterior revivería las sesiones revocadas. Valor 0 = cuenta nunca
-- revocada; los JWT emitidos por un backend anterior a este contrato no traen el claim y se leen como 0, de modo que
-- dejan de valer con la primera revocación de la cuenta.
--
-- Aditiva: columna con valor por defecto; no toca datos existentes.
ALTER TABLE gestion_patin.usuario
    ADD COLUMN version_sesion bigint NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_usuario_version_sesion CHECK (version_sesion >= 0);

COMMENT ON COLUMN gestion_patin.usuario.version_sesion IS
    'Generacion de sesiones: los JWT con otra version_sesion ya no son validos. Solo crece.';

CREATE FUNCTION gestion_patin.proteger_version_sesion() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, gestion_patin AS $$
BEGIN
    IF NEW.version_sesion < OLD.version_sesion THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'USUARIO_VERSION_SESION_NO_RETROCEDE';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_usuario_version_sesion BEFORE UPDATE ON gestion_patin.usuario
    FOR EACH ROW EXECUTE FUNCTION gestion_patin.proteger_version_sesion();

REVOKE ALL ON FUNCTION gestion_patin.proteger_version_sesion() FROM PUBLIC;
