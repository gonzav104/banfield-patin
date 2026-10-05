-- Limpieza de los datos creados por backend/scripts/smoke-familias.sh (una corrida identificada por su run id).
--
-- Uso (desde la raiz del repo; $URL_POSTGRES es la URL de conexion de psql, no la JDBC):
--   1) VISTA PREVIA (por defecto; no escribe nada, la transaccion es de solo lectura y termina en ROLLBACK):
--        psql "$URL_POSTGRES" -X -v run_id=SMOKE_20261005153000_ab12 -f backend/scripts/limpiar-smoke-familias.sql
--   2) LIMPIEZA SUAVE (una sola transaccion; es idempotente: repetirla no cambia nada):
--        psql "$URL_POSTGRES" -X -v run_id=SMOKE_20261005153000_ab12 -v aplicar=si -f backend/scripts/limpiar-smoke-familias.sql
--
-- Parametros: run_id (obligatorio; el que imprime el smoke, formato SMOKE_<AAAAMMDDhhmmss>_<4 caracteres>) y aplicar (opcional: "si" aplica; cualquier
-- otro valor distinto de "no" aborta; sin el parametro es vista previa).
--
-- QUE HACE la limpieza suave (nunca se borra un deportista: se desactiva, igual que en el dominio):
--   * familia_deportista de las familias/deportistas del smoke -> REVOCADO y es_principal = false (los ACTIVO/PENDIENTE; los ya revocados no se tocan);
--   * invitacion del smoke sin usar ni revocar -> revocada (el smoke usa la suya, asi que normalmente no hay ninguna);
--   * usuario FAMILIA registrado por el smoke -> activo = false (el ADMIN nunca se toca);
--   * deportista del smoke -> activo = false;
--   * familia del smoke -> activa = false.
-- NO toca: tutor (el modulo no desactiva tutores; queda colgado de una familia inactiva), auditoria (queda como evidencia), el ADMIN, ni ninguna fila sin el prefijo.
--
-- COMO IDENTIFICA LAS FILAS (todo lleva el run id; el run id termina en 4 caracteres aleatorios, por lo que un run id nunca es prefijo de otro):
--   familia.nombre_referencia  empieza con <run_id>_        deportista.nombre  empieza con <run_id>_ (y apellido = <run_id>)
--   usuario.email = smoke_<run_id sin "SMOKE_">@example.test (solo rol FAMILIA)
--   tutor / invitacion / familia_deportista: por pertenecer a una familia o deportista del smoke.
--
-- SEGURIDAD: se aborta (y no se escribe nada) si el run_id no tiene el formato esperado, si alguna fila tocada no cumple el prefijo o el cruce esperado (por
-- ejemplo un vinculo de un deportista del smoke con una familia SIN prefijo) o si coincide una cantidad anormal de filas (mas de 10 familias, 30 deportistas, 100
-- vinculos, 20 tutores, 20 invitaciones o 5 usuarios).
--
-- Las tablas se nombran con el esquema (gestion_patin): no depende del search_path de la conexion (Supabase no lo incluye).
--
-- ORDEN respetando las claves foraneas (usado solo por la seccion OPCIONAL de borrado fisico del final; la limpieza suave no borra nada):
--   auditoria (usuario_id) -> invitacion -> tutor -> familia_deportista -> usuario -> deportista -> familia

\set ON_ERROR_STOP on

-- 1. Parametros -------------------------------------------------------------------------------------------------------------------------------------
\if :{?run_id}
\else
  \echo 'ERROR: falta el parametro run_id. Ejemplo: psql "$URL_POSTGRES" -X -v run_id=SMOKE_20261005153000_ab12 -f backend/scripts/limpiar-smoke-familias.sql'
  DO $$ BEGIN RAISE EXCEPTION 'Falta -v run_id=<run id del smoke>; no se hizo nada'; END $$;
\endif

\if :{?aplicar}
\else
  \set aplicar no
\endif

SELECT (:'run_id' ~ '^SMOKE_[0-9]{14}_[a-z0-9]{4}$') AS run_ok, (:'aplicar' = 'si') AS aplicar_si, (:'aplicar' IN ('si', 'no')) AS aplicar_ok \gset

\if :run_ok
\else
  \echo 'ERROR: run_id invalido (se espera SMOKE_<AAAAMMDDhhmmss>_<4 caracteres minusculas/digitos>, el que imprime el smoke).'
  DO $$ BEGIN RAISE EXCEPTION 'run_id invalido; no se hizo nada'; END $$;
\endif
\if :aplicar_ok
\else
  \echo 'ERROR: aplicar solo admite "si" (o se omite para la vista previa).'
  DO $$ BEGIN RAISE EXCEPTION 'valor de aplicar invalido; no se hizo nada'; END $$;
\endif

\echo
\if :aplicar_si
  \echo '== MODO APLICAR (limpieza suave, una sola transaccion) para el run id' :run_id
\else
  \echo '== VISTA PREVIA (no se escribe nada) para el run id' :run_id '-- para aplicar agregar -v aplicar=si'
\endif

-- 2. Transaccion unica ------------------------------------------------------------------------------------------------------------------------------
BEGIN;
\if :aplicar_si
\else
  SET TRANSACTION READ ONLY;
\endif

-- 3. Conteos y guardas (solo lectura). n_* = filas que coinciden; mod_* = filas que la limpieza suave modificaria; mal_* = filas que violan el cruce esperado.
WITH f AS (
  SELECT id, activa FROM gestion_patin.familia WHERE starts_with(nombre_referencia, :'run_id' || '_')
), d AS (
  SELECT id, activo, apellido FROM gestion_patin.deportista WHERE starts_with(nombre, :'run_id' || '_')
), u AS (
  SELECT id, activo, familia_id, nombre FROM gestion_patin.usuario
   WHERE rol = 'FAMILIA' AND lower(email) = lower('smoke_' || substr(:'run_id', 7) || '@example.test')
), v AS (
  SELECT fd.id, fd.estado, fd.es_principal, fd.familia_id, fd.deportista_id FROM gestion_patin.familia_deportista fd
   WHERE fd.familia_id IN (SELECT id FROM f) OR fd.deportista_id IN (SELECT id FROM d)
), t AS (
  SELECT id FROM gestion_patin.tutor WHERE familia_id IN (SELECT id FROM f)
), i AS (
  SELECT id, usado_en, revocada_en FROM gestion_patin.invitacion WHERE familia_id IN (SELECT id FROM f)
), a AS (
  SELECT count(*) AS n FROM gestion_patin.auditoria WHERE usuario_id IN (SELECT id FROM u)
)
SELECT
  (SELECT count(*) FROM f) AS n_fam, (SELECT count(*) FROM f WHERE activa) AS mod_fam,
  (SELECT count(*) FROM d) AS n_dep, (SELECT count(*) FROM d WHERE activo) AS mod_dep,
  (SELECT count(*) FROM u) AS n_usu, (SELECT count(*) FROM u WHERE activo) AS mod_usu,
  (SELECT count(*) FROM v) AS n_vin, (SELECT count(*) FROM v WHERE estado IN ('ACTIVO', 'PENDIENTE') OR es_principal) AS mod_vin,
  (SELECT count(*) FROM t) AS n_tut,
  (SELECT count(*) FROM i) AS n_inv, (SELECT count(*) FROM i WHERE usado_en IS NULL AND revocada_en IS NULL) AS mod_inv,
  (SELECT n FROM a) AS n_aud,
  (SELECT count(*) FROM d WHERE apellido <> :'run_id') AS mal_dep,
  (SELECT count(*) FROM u WHERE familia_id NOT IN (SELECT id FROM f) OR NOT starts_with(nombre, :'run_id')) AS mal_usu,
  (SELECT count(*) FROM v WHERE familia_id NOT IN (SELECT id FROM f) OR deportista_id NOT IN (SELECT id FROM d)) AS mal_vin
\gset

SELECT (:n_fam > 10 OR :n_dep > 30 OR :n_vin > 100 OR :n_tut > 20 OR :n_inv > 20 OR :n_usu > 5) AS demasiadas,
       (:mal_dep > 0 OR :mal_usu > 0 OR :mal_vin > 0) AS cruce_invalido \gset

\if :demasiadas
  \echo 'ERROR: coinciden mas filas de las esperadas para un smoke (maximos: 10 familias, 30 deportistas, 100 vinculos, 20 tutores, 20 invitaciones, 5 usuarios). Se aborta sin escribir.'
  DO $$ BEGIN RAISE EXCEPTION 'guarda de cantidad: se aborta sin escribir'; END $$;
\endif
\if :cruce_invalido
  \echo 'ERROR: hay filas tocadas que no cumplen el prefijo/cruce SMOKE_ (deportista con otro apellido, usuario de otra familia o vinculo con una familia/deportista sin prefijo). Se aborta sin escribir.'
  DO $$ BEGIN RAISE EXCEPTION 'guarda de prefijo: se aborta sin escribir'; END $$;
\endif

-- 4. Vista previa: que se tocaria
\echo
\echo 'Filas que coinciden con el run id y las que la limpieza suave modificaria (si se_modifican es 0 en todas, ya esta limpio):'
SELECT * FROM (VALUES
  ('familia',            :n_fam, :mod_fam, 'activa = false'),
  ('deportista',         :n_dep, :mod_dep, 'activo = false (nunca se borra)'),
  ('usuario (FAMILIA)',  :n_usu, :mod_usu, 'activo = false'),
  ('familia_deportista', :n_vin, :mod_vin, 'estado = REVOCADO, es_principal = false'),
  ('invitacion',         :n_inv, :mod_inv, 'revocar si seguia pendiente'),
  ('tutor',              :n_tut, 0,        'no se toca'),
  ('auditoria (del usuario FAMILIA del smoke)', :n_aud, 0, 'no se toca (evidencia)')
) AS t(tabla, coinciden, se_modifican, accion);

-- 5. Limpieza suave (solo con -v aplicar=si)
\if :aplicar_si
  \echo
  \echo 'Aplicando la limpieza suave...'

  \echo '-- familia_deportista -> REVOCADO'
  UPDATE gestion_patin.familia_deportista fd
     SET estado = 'REVOCADO', es_principal = false
   WHERE (fd.familia_id IN (SELECT id FROM gestion_patin.familia WHERE starts_with(nombre_referencia, :'run_id' || '_'))
          OR fd.deportista_id IN (SELECT id FROM gestion_patin.deportista WHERE starts_with(nombre, :'run_id' || '_')))
     AND (fd.estado IN ('ACTIVO', 'PENDIENTE') OR fd.es_principal);

  \echo '-- invitacion pendiente -> revocada'
  UPDATE gestion_patin.invitacion
     SET revocada_en = now(), revocada_por = creada_por
   WHERE familia_id IN (SELECT id FROM gestion_patin.familia WHERE starts_with(nombre_referencia, :'run_id' || '_'))
     AND usado_en IS NULL AND revocada_en IS NULL;

  \echo '-- usuario FAMILIA -> inactivo'
  UPDATE gestion_patin.usuario
     SET activo = false
   WHERE rol = 'FAMILIA' AND lower(email) = lower('smoke_' || substr(:'run_id', 7) || '@example.test') AND activo;

  \echo '-- deportista -> inactivo'
  UPDATE gestion_patin.deportista
     SET activo = false
   WHERE starts_with(nombre, :'run_id' || '_') AND activo;

  \echo '-- familia -> inactiva'
  UPDATE gestion_patin.familia
     SET activa = false
   WHERE starts_with(nombre_referencia, :'run_id' || '_') AND activa;

  -- ===================================================================================================================================================
  -- OPCIONAL, DESTRUCTIVO E IRREVERSIBLE: BORRADO FISICO de los datos del smoke.            ESTA SECCION ESTA COMENTADA A PROPOSITO.
  --
  -- ADVERTENCIA: borra filas de verdad. En particular BORRA LAS FILAS DE AUDITORIA DEL USUARIO FAMILIA DEL SMOKE (login, logout, registro): se pierde esa
  -- evidencia. Hace falta porque auditoria tiene una clave foranea compuesta (usuario_id, escuela_id) hacia usuario: un usuario con filas de auditoria no
  -- se puede borrar sin borrar antes esas filas. Las filas de auditoria del ADMIN (alta de familias, deportistas, vinculos, etc.) NO se borran: tienen
  -- recurso_id sin clave foranea y quedan apuntando a filas que ya no existen (evidencia huerfana).
  -- Para habilitarla: sacar el "-- " de las sentencias de abajo (todas, en este orden) y ejecutar con -v aplicar=si. Si algo falla (una clave foranea de
  -- otra tabla, por ejemplo un documento), la transaccion entera se revierte. Probar siempre primero la vista previa.
  --
  -- DELETE FROM gestion_patin.auditoria WHERE usuario_id IN (SELECT id FROM gestion_patin.usuario WHERE rol = 'FAMILIA' AND lower(email) = lower('smoke_' || substr(:'run_id', 7) || '@example.test'));
  -- DELETE FROM gestion_patin.invitacion WHERE familia_id IN (SELECT id FROM gestion_patin.familia WHERE starts_with(nombre_referencia, :'run_id' || '_'));
  -- DELETE FROM gestion_patin.tutor WHERE familia_id IN (SELECT id FROM gestion_patin.familia WHERE starts_with(nombre_referencia, :'run_id' || '_'));
  -- DELETE FROM gestion_patin.familia_deportista WHERE familia_id IN (SELECT id FROM gestion_patin.familia WHERE starts_with(nombre_referencia, :'run_id' || '_')) OR deportista_id IN (SELECT id FROM gestion_patin.deportista WHERE starts_with(nombre, :'run_id' || '_'));
  -- DELETE FROM gestion_patin.usuario WHERE rol = 'FAMILIA' AND lower(email) = lower('smoke_' || substr(:'run_id', 7) || '@example.test');
  -- DELETE FROM gestion_patin.deportista WHERE starts_with(nombre, :'run_id' || '_');
  -- DELETE FROM gestion_patin.familia WHERE starts_with(nombre_referencia, :'run_id' || '_');
  -- ===================================================================================================================================================

  COMMIT;
  \echo
  \echo 'LISTO: limpieza suave aplicada y confirmada. Repetir la vista previa debe mostrar se_modifican = 0. Auditoria y tutores quedan como estaban.'
\else
  ROLLBACK;
  \echo
  \echo 'Vista previa terminada: no se escribio nada.'
\endif
