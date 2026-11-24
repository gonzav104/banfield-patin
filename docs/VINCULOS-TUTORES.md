# Vínculos explícitos entre tutores y deportistas

Una fila de `tutor_deportista` representa una vigencia. El tipo declarado no acredita representación legal ni modifica el acceso a deportistas o documentos. V14 crea la tabla vacía: ni `tutor.parentesco` ni la cantidad de deportistas de una familia generan asociaciones.

## Consulta ADMIN

`GET /api/admin/deportistas/{deportistaId}/vinculos-tutores` lista solo vigentes por defecto. `historial=true` incluye las vigencias cerradas. `page` y `size` siguen la paginación administrativa (0 y 20 por defecto; máximo 100). Orden: alta más reciente primero, con el ID como desempate.

- Deportista sin vínculos: `200` y `contenido: []`. Deportista inexistente o de otra escuela: `404 DEPORTISTA_NO_ENCONTRADO` uniforme.
- Campos: ID de la vigencia, IDs de tutor y familia, nombre y apellido del tutor, tipo declarado nullable, fecha de alta, fecha y causa de cierre si corresponden, y revisión decimal como cadena. No se incluyen DNI, contactos, cuentas ni datos de idempotencia.
- El acceso requiere el rol ADMIN. No se agrega acceso familiar ni se cambian los permisos documentales en C1.

## Integridad y secuencia de instalación

Las FKs permanentes mantienen la referencia histórica y obligan a que tutor y deportista coincidan con la misma familia y escuela. Las FKs de estado exigen padres activos solo para una vigencia abierta. El índice parcial permite varios responsables, pero solo una vigencia abierta para cada par tutor–deportista. Ni la reactivación de un padre ni un cambio del tipo declarado pueden reabrir un historial.

1. **Instalar V14 antes del backend nuevo.** Mantener las altas de responsables deshabilitadas. No modificar V1–V13 ni crear asociaciones a partir de datos históricos.
2. Antes de habilitar altas en un futuro despliegue autorizado, verificar que todas las instancias y procesos capaces de escribir usen el backend que cierra vigencias antes de desactivar un padre o revocar `familia_deportista`. Drenar también las transacciones antiguas. Con vigencias abiertas la FK rechaza las escrituras antiguas incompatibles: preserva los datos, pero no preserva la disponibilidad de esas operaciones.
3. Si existen vigencias abiertas, **no volver a un escritor incompatible**. Deshabilitar nuevas altas no elimina las vigencias existentes. Para recuperar el servicio, conservar una versión compatible o corregir hacia adelante.

La instalación de V14 no habilita altas en producción ni modifica los valores por defecto de `DOCUMENTOS_CARGA_HABILITADA` y `DOCUMENTOS_STORAGE_HABILITADO`.

## Separar migraciones y backend antes de desplegar

En el perfil `prod`, Spring Boot no ejecuta Flyway y Hibernate conserva `ddl-auto=validate`. No entregar al proceso web las credenciales de administración ni de migración.

| Identidad PostgreSQL 17 | Uso |
| --- | --- |
| `banfield_schema_owner` | Propietario `NOLOGIN` de los esquemas, tablas y funciones de la aplicación. |
| `banfield_migrator` | Login exclusivo del job Flyway. Asume el propietario explícitamente, sin heredar sus permisos por defecto. |
| `banfield_runtime` | Login de Spring Boot, sin membresías en otros roles. Solo recibe los privilegios de datos de C1; en `tutor_deportista`, únicamente `SELECT`. |

1. **Preflight administrativo:** comprobar `gestion_patin.flyway_schema_history` y sus checksums, propietarios y permisos efectivos. Si se registró otra V14, detenerse: no editarla ni usar `repair` para ocultar el checksum. Para una base existente con objetos bajo otro propietario, transferir explícitamente el esquema gestionado y sus objetos (incluidos historial Flyway, tablas, secuencias y funciones) bajo una operación revisada; no reasignar objetos de Supabase u otros esquemas. Revocar permisos previos incompatibles y auditar membresías directas e indirectas.
2. Un administrador aprovisiona los tres roles dedicados sin atributos elevados y con credenciales externas para los dos logins; ejecuta `backend/ops/postgresql/preparar-migracion.sql` con parada ante errores. No reutilizar un login de administrador ni uno anterior con privilegios amplios.
3. El job se conecta con `banfield_migrator` a través de una conexión directa o en modo sesión, no al pool de transacciones. Usa las migraciones de la revisión a desplegar, esquema inicial y por defecto `gestion_patin`, limpieza deshabilitada e inicialización de cada conexión Flyway con `initSql=SET ROLE banfield_schema_owner`. Hacer `info`, `migrate` con validación habilitada y finalmente `validate`; si falla, no desplegar. El esquema privado lo crea V14 bajo el propietario.
4. Tras V14, ejecutar `backend/ops/postgresql/autorizar-runtime-c1.sql` como propietario. Verificar que el runtime no es dueño de los objetos ni miembro del propietario/migrador; no puede fijar `session_replication_role`, usar/escribir el esquema privado, ejecutar sus funciones directamente, manipular los triggers ni escribir el historial Flyway. El único permiso de escritura del runtime sobre `escuela` es a nivel de columna (`actualizado_en`) para que pueda adquirir los locks de fila de temporadas, bootstrap y carga sin editar el estado de la escuela. Solo entonces arrancar el backend con el perfil `prod` y el login runtime. La suite PostgreSQL 17 de CI verifica este contrato con logins de prueba y los mismos SQL de aprovisionamiento; no conecta con Supabase.

La protección no pretende restringir al administrador o al propietario. V14 copia únicamente el estado de los padres en sus marcas privadas; no crea asociaciones. Los triggers crean la marca al insertar un padre y la sincronizan cuando cambia su estado. El alta de una vigencia comprueba las marcas mediante FKs inmediatas sin crearlas ni escribirlas; un conflicto de solicitud que termina en `DO NOTHING` no comprueba una fila descartada. Las FKs rechazan una baja con una vigencia abierta.
