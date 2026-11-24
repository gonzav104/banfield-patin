-- Ejecutar como banfield_schema_owner (SET ROLE desde la sesión de migraciones), después de V14.
-- No hay privilegios por defecto sobre futuros objetos ni acceso a flyway_schema_history.
BEGIN;
SET LOCAL search_path = pg_catalog, pg_temp;

REVOKE ALL ON SCHEMA gestion_patin, gestion_patin_privado FROM PUBLIC;
REVOKE ALL ON SCHEMA gestion_patin_privado FROM banfield_runtime;
REVOKE ALL ON SCHEMA gestion_patin FROM banfield_runtime;
GRANT USAGE ON SCHEMA gestion_patin TO banfield_runtime;
REVOKE ALL ON gestion_patin.flyway_schema_history, gestion_patin.tutor_deportista FROM banfield_runtime;

GRANT SELECT ON gestion_patin.escuela, gestion_patin.tutor_deportista TO banfield_runtime;
-- PostgreSQL exige UPDATE en alguna columna para tomar un lock de fila. No habilitar
-- cambios de estado, identidad ni configuración de escuela para bootstrap, temporadas y carga.
GRANT UPDATE (actualizado_en) ON gestion_patin.escuela TO banfield_runtime;
GRANT SELECT, INSERT, UPDATE ON
    gestion_patin.deportista, gestion_patin.deportista_temporada, gestion_patin.temporada,
    gestion_patin.usuario, gestion_patin.usuario_mfa, gestion_patin.familia, gestion_patin.invitacion,
    gestion_patin.tutor, gestion_patin.familia_deportista,
    gestion_patin.documento, gestion_patin.operacion_carga_documental
TO banfield_runtime;
GRANT DELETE ON gestion_patin.usuario_mfa TO banfield_runtime;
GRANT INSERT ON gestion_patin.auditoria TO banfield_runtime;
GRANT USAGE ON SEQUENCE gestion_patin.auditoria_id_seq TO banfield_runtime;

-- Verificar privilegios efectivos: revocar solo del login no elimina herencia o permisos de PUBLIC.
DO $$
BEGIN
    IF current_user <> 'banfield_schema_owner'
       OR EXISTS (SELECT 1 FROM pg_auth_members WHERE member = 'banfield_runtime'::regrole)
       OR has_database_privilege('banfield_runtime', current_database(), 'CREATE')
       OR has_parameter_privilege('banfield_runtime', 'session_replication_role', 'SET')
       OR has_schema_privilege('banfield_runtime', 'gestion_patin', 'CREATE')
       OR has_schema_privilege('banfield_runtime', 'gestion_patin_privado', 'USAGE, CREATE')
       OR has_table_privilege('banfield_runtime', 'gestion_patin.escuela',
                              'INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER')
       OR has_any_column_privilege('banfield_runtime', 'gestion_patin.escuela', 'INSERT, REFERENCES')
       OR EXISTS (
           SELECT FROM pg_attribute
           WHERE attrelid = 'gestion_patin.escuela'::regclass AND attnum > 0 AND NOT attisdropped
             AND attname <> 'actualizado_en'
             AND has_column_privilege('banfield_runtime', attrelid, attnum, 'UPDATE')
       )
       OR EXISTS (
           SELECT FROM pg_namespace
           WHERE nspname IN ('gestion_patin', 'gestion_patin_privado')
             AND nspowner <> 'banfield_schema_owner'::regrole
       )
       OR EXISTS (
           SELECT FROM pg_class
           WHERE relnamespace = 'gestion_patin'::regnamespace
             AND relname IN ('familia', 'deportista', 'tutor', 'familia_deportista', 'tutor_deportista', 'flyway_schema_history')
             AND (relowner <> 'banfield_schema_owner'::regrole
                  OR has_table_privilege('banfield_runtime', oid, 'TRUNCATE, TRIGGER'))
       )
       OR has_table_privilege('banfield_runtime', 'gestion_patin.flyway_schema_history',
                              'SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER')
       OR has_any_column_privilege('banfield_runtime', 'gestion_patin.flyway_schema_history',
                                    'SELECT, INSERT, UPDATE, REFERENCES')
       OR has_any_column_privilege('banfield_runtime', 'gestion_patin.tutor_deportista',
                                    'INSERT, UPDATE, REFERENCES')
       OR has_table_privilege('banfield_runtime', 'gestion_patin.tutor_deportista',
                              'INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER')
       OR EXISTS (
           SELECT FROM pg_class
           WHERE relnamespace = 'gestion_patin_privado'::regnamespace AND relkind = 'r'
             AND (relowner <> 'banfield_schema_owner'::regrole
                  OR has_table_privilege('banfield_runtime', oid,
                                         'SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER')
                  OR has_any_column_privilege('banfield_runtime', oid, 'SELECT, INSERT, UPDATE, REFERENCES'))
       )
       OR EXISTS (
           SELECT FROM pg_proc
           WHERE pronamespace = 'gestion_patin_privado'::regnamespace
             AND (proowner <> 'banfield_schema_owner'::regrole
                  OR has_function_privilege('banfield_runtime', oid, 'EXECUTE'))
       ) THEN
        RAISE EXCEPTION 'Privilegios efectivos de runtime incompatibles con C1';
    END IF;
END;
$$;

COMMIT;
