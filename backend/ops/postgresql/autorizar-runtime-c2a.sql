-- Ejecutar como banfield_schema_owner, después de V15 y de autorizar-runtime-c1.sql.
-- No otorga escritura directa sobre vigencias, acceso a marcas ni opción de delegar EXECUTE.
BEGIN;
SET LOCAL search_path = pg_catalog, pg_temp;

REVOKE ALL ON FUNCTION gestion_patin.cerrar_vigencias_tutor(uuid, uuid, text, uuid, uuid) FROM PUBLIC, banfield_runtime;
GRANT EXECUTE ON FUNCTION gestion_patin.cerrar_vigencias_tutor(uuid, uuid, text, uuid, uuid) TO banfield_runtime;
DO $$
DECLARE
    cierre regprocedure := 'gestion_patin.cerrar_vigencias_tutor(uuid,uuid,text,uuid,uuid)'::regprocedure;
BEGIN
    IF current_user <> 'banfield_schema_owner'
       OR NOT has_function_privilege('banfield_runtime', cierre, 'EXECUTE')
       OR has_function_privilege('banfield_runtime', cierre, 'EXECUTE WITH GRANT OPTION')
       OR NOT EXISTS (
           SELECT FROM pg_proc p WHERE p.oid = cierre AND p.proowner = 'banfield_schema_owner'::regrole
             AND p.prosecdef AND p.proconfig = ARRAY['search_path=pg_catalog, pg_temp']
       )
       OR EXISTS (
           SELECT FROM pg_proc p, aclexplode(p.proacl) acl
           WHERE p.oid = cierre AND acl.grantee NOT IN ('banfield_schema_owner'::regrole, 'banfield_runtime'::regrole)
       )
       OR has_any_column_privilege('banfield_runtime', 'gestion_patin.tutor_deportista', 'INSERT, UPDATE, REFERENCES')
       OR has_table_privilege('banfield_runtime', 'gestion_patin.tutor_deportista',
                              'INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER')
       OR has_schema_privilege('banfield_runtime', 'gestion_patin_privado', 'USAGE, CREATE')
       OR pg_has_role('banfield_runtime', 'banfield_schema_owner', 'MEMBER') THEN
        RAISE EXCEPTION 'Privilegios efectivos de runtime incompatibles con C2-A';
    END IF;
END;
$$;
COMMIT;
