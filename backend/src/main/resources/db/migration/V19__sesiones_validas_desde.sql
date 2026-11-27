-- U21-BIS C0: reactivar una cuenta no revive sus sesiones anteriores.
--
-- Cada solicitud autenticada revalida en la base que la cuenta sigue activa: desactivarla corta en la siguiente
-- solicitud las sesiones ya iniciadas. Pero un JWT emitido antes de la desactivación (vida de hasta 8 h) volvería a ser
-- válido si la cuenta se reactiva antes de que venza. sesiones_validas_desde marca el momento de la última reactivación:
-- la revalidación rechaza los tokens emitidos antes (comparando su iat, que tiene resolución de segundos, con este
-- instante truncado al segundo). NULL = cuenta nunca reactivada (sin restricción adicional).
--
-- Aditiva: columna nullable sin valor por defecto; no toca datos existentes.
ALTER TABLE gestion_patin.usuario ADD COLUMN sesiones_validas_desde timestamptz;

COMMENT ON COLUMN gestion_patin.usuario.sesiones_validas_desde IS
    'Ultima reactivacion de la cuenta: los JWT con iat anterior (truncado al segundo) ya no son validos.';
