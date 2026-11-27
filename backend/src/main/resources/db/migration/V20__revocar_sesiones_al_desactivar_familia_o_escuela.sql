-- U21-BIS (revocación por alcance): desactivar una familia o una escuela revoca las sesiones de las cuentas de su alcance, y
-- reactivarla no las revive.
--
-- La revalidación de cada solicitud ya rechaza a las cuentas de una familia o escuela inactiva, pero sin tocar su versión de
-- sesión (V19): al reactivar, un token anterior volvía a valer. Estos triggers incrementan version_sesion de las cuentas
-- afectadas EN LA MISMA TRANSACCIÓN que el cambio de estado, en cualquier camino que lo haga (servicio, SQL manual, pruebas).
--
-- Solo en la transición activa -> inactiva (WHEN): repetir la desactivación, reactivar o editar otros campos no incrementa nada.
-- No tocan usuario.activo: el estado individual de cada cuenta se conserva (una cuenta desactivada a mano sigue desactivada).
-- Bloqueo de las cuentas en orden de id (FOR NO KEY UPDATE) para que dos operaciones colectivas o una individual no se crucen.
--
-- Aditiva: solo agrega funciones y triggers; no toca datos ni migraciones anteriores.

CREATE FUNCTION gestion_patin.revocar_sesiones_de_familia() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, gestion_patin AS $$
BEGIN
    UPDATE gestion_patin.usuario SET version_sesion = version_sesion + 1
     WHERE id IN (SELECT id FROM gestion_patin.usuario
                   WHERE escuela_id = NEW.escuela_id AND familia_id = NEW.id
                   ORDER BY id FOR NO KEY UPDATE);
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_familia_revoca_sesiones AFTER UPDATE OF activa ON gestion_patin.familia
    FOR EACH ROW WHEN (OLD.activa AND NOT NEW.activa) EXECUTE FUNCTION gestion_patin.revocar_sesiones_de_familia();

CREATE FUNCTION gestion_patin.revocar_sesiones_de_escuela() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, gestion_patin AS $$
BEGIN
    UPDATE gestion_patin.usuario SET version_sesion = version_sesion + 1
     WHERE id IN (SELECT id FROM gestion_patin.usuario
                   WHERE escuela_id = NEW.id ORDER BY id FOR NO KEY UPDATE);
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_escuela_revoca_sesiones AFTER UPDATE OF activa ON gestion_patin.escuela
    FOR EACH ROW WHEN (OLD.activa AND NOT NEW.activa) EXECUTE FUNCTION gestion_patin.revocar_sesiones_de_escuela();

REVOKE ALL ON FUNCTION gestion_patin.revocar_sesiones_de_familia() FROM PUBLIC;
REVOKE ALL ON FUNCTION gestion_patin.revocar_sesiones_de_escuela() FROM PUBLIC;
