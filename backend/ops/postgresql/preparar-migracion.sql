-- Ejecutar como administrador en la base de destino, con los tres roles ya aprovisionados.
-- No otorga privilegios de propiedad a la cuenta usada por Spring Boot.
BEGIN;
SET LOCAL search_path = pg_catalog, pg_temp;

GRANT banfield_schema_owner TO banfield_migrator WITH INHERIT FALSE, SET TRUE;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles
               WHERE rolname IN ('banfield_schema_owner', 'banfield_migrator', 'banfield_runtime')
                 AND (rolsuper OR rolcreaterole OR rolcreatedb OR rolreplication OR rolbypassrls))
       OR EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'banfield_schema_owner' AND rolcanlogin)
       OR EXISTS (SELECT 1 FROM pg_auth_members WHERE member = 'banfield_runtime'::regrole)
       OR EXISTS (SELECT 1 FROM pg_roles WHERE rolname IN ('banfield_migrator', 'banfield_runtime') AND NOT rolcanlogin)
       OR has_database_privilege('banfield_runtime', current_database(), 'CREATE')
       OR pg_has_role('banfield_migrator', 'banfield_schema_owner', 'USAGE')
       OR has_parameter_privilege('banfield_runtime', 'session_replication_role', 'SET') THEN
        RAISE EXCEPTION 'Separación incorrecta entre propietario, migrador y runtime';
    END IF;

    -- Crear el esquema de marcas en V14 requiere CREATE en la base.
    EXECUTE format('GRANT CONNECT, CREATE ON DATABASE %I TO banfield_schema_owner', current_database());
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO banfield_migrator, banfield_runtime', current_database());
END;
$$;

COMMIT;
