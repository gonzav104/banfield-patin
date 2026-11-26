-- Ejecutar como banfield_schema_owner, después de V16 y de autorizar-runtime-c2a.sql.
-- Solo concede EXECUTE sobre las tres funciones de C2-B. No otorga escritura directa sobre vigencias,
-- acceso a marcas ni opción de delegar EXECUTE. El alta sigue deshabilitada por la bandera del backend.
BEGIN;
SET LOCAL search_path = pg_catalog, pg_temp;

REVOKE ALL ON FUNCTION gestion_patin.crear_vigencia_tutor(uuid, uuid, uuid, uuid, text, uuid, text) FROM PUBLIC, banfield_runtime;
REVOKE ALL ON FUNCTION gestion_patin.actualizar_tipo_vigencia_tutor(uuid, uuid, uuid, bigint, text) FROM PUBLIC, banfield_runtime;
REVOKE ALL ON FUNCTION gestion_patin.revocar_vigencia_tutor(uuid, uuid, uuid, bigint) FROM PUBLIC, banfield_runtime;
GRANT EXECUTE ON FUNCTION gestion_patin.crear_vigencia_tutor(uuid, uuid, uuid, uuid, text, uuid, text) TO banfield_runtime;
GRANT EXECUTE ON FUNCTION gestion_patin.actualizar_tipo_vigencia_tutor(uuid, uuid, uuid, bigint, text) TO banfield_runtime;
GRANT EXECUTE ON FUNCTION gestion_patin.revocar_vigencia_tutor(uuid, uuid, uuid, bigint) TO banfield_runtime;
DO $$
DECLARE
    f regprocedure;
BEGIN
    IF current_user <> 'banfield_schema_owner' THEN
        RAISE EXCEPTION 'Privilegios efectivos de runtime incompatibles con C2-B';
    END IF;
    FOREACH f IN ARRAY ARRAY[
        'gestion_patin.crear_vigencia_tutor(uuid,uuid,uuid,uuid,text,uuid,text)'::regprocedure,
        'gestion_patin.actualizar_tipo_vigencia_tutor(uuid,uuid,uuid,bigint,text)'::regprocedure,
        'gestion_patin.revocar_vigencia_tutor(uuid,uuid,uuid,bigint)'::regprocedure]
    LOOP
        IF NOT has_function_privilege('banfield_runtime', f, 'EXECUTE')
           OR has_function_privilege('banfield_runtime', f, 'EXECUTE WITH GRANT OPTION')
           OR NOT EXISTS (
               SELECT FROM pg_proc p WHERE p.oid = f AND p.proowner = 'banfield_schema_owner'::regrole
                 AND p.prosecdef AND p.proconfig = ARRAY['search_path=pg_catalog, pg_temp']
           )
           OR EXISTS (
               SELECT FROM pg_proc p, aclexplode(p.proacl) acl
               WHERE p.oid = f AND acl.grantee NOT IN ('banfield_schema_owner'::regrole, 'banfield_runtime'::regrole)
           ) THEN
            RAISE EXCEPTION 'Privilegios efectivos de runtime incompatibles con C2-B';
        END IF;
    END LOOP;
    IF has_any_column_privilege('banfield_runtime', 'gestion_patin.tutor_deportista', 'INSERT, UPDATE, REFERENCES')
       OR has_table_privilege('banfield_runtime', 'gestion_patin.tutor_deportista',
                              'INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER')
       OR has_schema_privilege('banfield_runtime', 'gestion_patin_privado', 'USAGE, CREATE')
       OR pg_has_role('banfield_runtime', 'banfield_schema_owner', 'MEMBER') THEN
        RAISE EXCEPTION 'Privilegios efectivos de runtime incompatibles con C2-B';
    END IF;
END;
$$;
COMMIT;
