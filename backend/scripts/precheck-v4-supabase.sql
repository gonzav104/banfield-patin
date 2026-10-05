-- =====================================================================================================
-- Prechecks de SOLO LECTURA previos a aplicar V4 (V4__restricciones_familia_deportista.sql).
--
-- Compuerta G1 (humana): V4 llega a Supabase cuando la aplicacion arranca con Flyway desde una rama que
-- contiene V4. Este script se ejecuta ANTES de ese primer arranque y NO modifica nada.
--
-- Como ejecutarlo (desde la raiz del repositorio, con la variable de conexion ya cargada en el entorno,
-- sin imprimir nunca la URL ni la clave):
--
--     psql "$URL" -X -f backend/scripts/precheck-v4-supabase.sql
--
-- Garantias:
--   * Es de solo lectura: la primera sentencia deja la sesion en modo solo lectura y todo lo demas son SELECT.
--   * Cada consulta devuelve una columna "detector". Las consultas de deteccion deben devolver 0 filas
--     (salida "(0 rows)"). Las que empiezan con INFO_ son informativas y siempre devuelven filas.
--   * Si alguna deteccion devuelve filas, NO arrancar la aplicacion: V4 fallaria o chocaria con objetos
--     existentes. Revisar los datos con el responsable antes de continuar.
--
-- El archivo se puede ejecutar tambien sentencia por sentencia (JDBC): no hay punto y coma dentro de
-- comentarios ni de literales, y las lineas que empiezan con barra invertida son meta-comandos de psql.
-- =====================================================================================================

\set ON_ERROR_STOP on

set default_transaction_read_only = on;

-- -----------------------------------------------------------------------------------------------------
-- (a) Historial de Flyway: deben estar las versiones 1, 2 y 3 aplicadas con exito y NO existir la 4.
-- Esperado: 0 filas. Si devuelve una fila, el historial no es el esperado.
-- -----------------------------------------------------------------------------------------------------
select 'A_HISTORIAL_INESPERADO' as detector,
       (select coalesce(string_agg(version || case when success then ':ok' else ':FALLIDA' end, ', ' order by installed_rank), '(vacio)')
          from gestion_patin.flyway_schema_history
         where version is not null) as versiones_registradas
 where (select count(*) from gestion_patin.flyway_schema_history
         where success and version in ('1', '2', '3')) <> 3
    or exists (select 1 from gestion_patin.flyway_schema_history where version = '4')
    or exists (select 1 from gestion_patin.flyway_schema_history where not success);

-- -----------------------------------------------------------------------------------------------------
-- (a, informativo) Historial registrado (esperado: 1, 2 y 3 con success = true).
-- -----------------------------------------------------------------------------------------------------
select 'INFO_HISTORIAL' as detector, installed_rank, version, description, success
  from gestion_patin.flyway_schema_history
 where version is not null
 order by installed_rank;

-- -----------------------------------------------------------------------------------------------------
-- (b) Vinculos ACTIVO sin autorizado_por o sin autorizado_en (violarian ck_fd_activo_autorizado).
-- Esperado: 0 filas. Muestra a lo sumo 50 ids y el total en la columna total.
-- -----------------------------------------------------------------------------------------------------
select 'B_ACTIVO_SIN_AUTORIZACION' as detector, id as vinculo_id, familia_id, deportista_id,
       (autorizado_por is null) as falta_autorizado_por, (autorizado_en is null) as falta_autorizado_en,
       count(*) over () as total
  from gestion_patin.familia_deportista
 where estado = 'ACTIVO' and (autorizado_por is null or autorizado_en is null)
 order by id
 limit 50;

-- -----------------------------------------------------------------------------------------------------
-- (c) Deportistas con mas de un vinculo ACTIVO principal (violarian uq_fd_principal_activo).
-- Esperado: 0 filas.
-- -----------------------------------------------------------------------------------------------------
select 'C_DEPORTISTA_CON_VARIOS_PRINCIPALES' as detector, deportista_id, count(*) as principales_activos
  from gestion_patin.familia_deportista
 where estado = 'ACTIVO' and es_principal
 group by deportista_id
having count(*) > 1
 order by deportista_id
 limit 50;

-- -----------------------------------------------------------------------------------------------------
-- (d1) Conflicto de nombres: relaciones (indice, tabla, secuencia, vista) del esquema gestion_patin que ya
-- se llamen ix_tutor_familia o uq_fd_principal_activo (un indice comparte espacio de nombres con las tablas).
-- Esperado: 0 filas.
-- -----------------------------------------------------------------------------------------------------
select 'D1_RELACION_CON_NOMBRE_EN_CONFLICTO' as detector, c.relname as nombre, c.relkind as tipo
  from pg_class c
  join pg_namespace n on n.oid = c.relnamespace
 where n.nspname = 'gestion_patin'
   and c.relname in ('ix_tutor_familia', 'uq_fd_principal_activo')
 order by c.relname;

-- -----------------------------------------------------------------------------------------------------
-- (d2) Conflicto de nombres: restricciones del esquema gestion_patin que ya se llamen ck_fd_activo_autorizado.
-- Esperado: 0 filas.
-- -----------------------------------------------------------------------------------------------------
select 'D2_RESTRICCION_CON_NOMBRE_EN_CONFLICTO' as detector, c.conname as nombre, t.relname as tabla
  from pg_constraint c
  join pg_namespace n on n.oid = c.connamespace
  left join pg_class t on t.oid = c.conrelid
 where n.nspname = 'gestion_patin'
   and c.conname = 'ck_fd_activo_autorizado'
 order by t.relname;

-- -----------------------------------------------------------------------------------------------------
-- (d3) Tablas y columnas que V4 referencia (si falta una, el esquema no es el de V1).
-- Esperado: 0 filas.
-- -----------------------------------------------------------------------------------------------------
select 'D3_FALTA_COLUMNA' as detector, e.tabla, e.columna
  from (values ('tutor', 'escuela_id'),
               ('tutor', 'familia_id'),
               ('familia_deportista', 'deportista_id'),
               ('familia_deportista', 'estado'),
               ('familia_deportista', 'es_principal'),
               ('familia_deportista', 'autorizado_por'),
               ('familia_deportista', 'autorizado_en')) as e(tabla, columna)
 where not exists (select 1 from information_schema.columns c
                    where c.table_schema = 'gestion_patin'
                      and c.table_name = e.tabla
                      and c.column_name = e.columna)
 order by e.tabla, e.columna;

-- -----------------------------------------------------------------------------------------------------
-- (d4) Funcion de trigger de V1 (gestion_patin.actualizar_timestamp_modificacion).
-- Esperado: 0 filas. Si devuelve una fila, falta la funcion.
-- -----------------------------------------------------------------------------------------------------
select 'D4_FALTA_FUNCION_TRIGGER' as detector, 'actualizar_timestamp_modificacion' as funcion
 where not exists (select 1 from pg_proc p
                     join pg_namespace n on n.oid = p.pronamespace
                    where n.nspname = 'gestion_patin'
                      and p.proname = 'actualizar_timestamp_modificacion');

-- -----------------------------------------------------------------------------------------------------
-- (e, informativo) Cantidad de filas de las tablas involucradas. Siempre devuelve 1 fila.
-- Referencia 2026-10-05: 0 familias, 0 tutores, 0 deportistas, 0 vinculos y 1 usuario.
-- -----------------------------------------------------------------------------------------------------
select 'INFO_CONTEOS' as detector,
       (select count(*) from gestion_patin.familia) as familias,
       (select count(*) from gestion_patin.tutor) as tutores,
       (select count(*) from gestion_patin.deportista) as deportistas,
       (select count(*) from gestion_patin.familia_deportista) as vinculos,
       (select count(*) from gestion_patin.usuario) as usuarios;
