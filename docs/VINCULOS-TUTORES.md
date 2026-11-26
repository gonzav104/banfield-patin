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
4. Tras V14, desde `backend/ops/postgresql`, ejecutar `psql -v ON_ERROR_STOP=1 -f autorizar-runtime-c1.sql` con las opciones de conexión directa o en modo sesión del paso anterior. Si la conexión usa `banfield_migrator`, añadir `-c 'SET ROLE banfield_schema_owner'` antes de `-f` para ejecutar el script como propietario en esa misma sesión. Si `psql` devuelve un código distinto de cero, no arrancar el backend. Verificar que el runtime no es dueño de los objetos ni miembro del propietario/migrador; no puede fijar `session_replication_role`, usar/escribir el esquema privado, ejecutar sus funciones directamente, manipular los triggers ni escribir el historial Flyway. El único permiso de escritura del runtime sobre `escuela` es a nivel de columna (`actualizado_en`) para que pueda adquirir los locks de fila de temporadas, bootstrap y carga sin editar el estado de la escuela. Solo entonces arrancar el backend con el perfil `prod` y el login runtime. La suite PostgreSQL 17 de CI verifica este contrato con logins de prueba y los mismos SQL de aprovisionamiento; no conecta con Supabase.

La protección no pretende restringir al administrador o al propietario. V14 copia únicamente el estado de los padres en sus marcas privadas; no crea asociaciones. Los triggers crean la marca al insertar un padre y la sincronizan cuando cambia su estado. El alta de una vigencia comprueba las marcas mediante FKs inmediatas sin crearlas ni escribirlas; un conflicto de solicitud que termina en `DO NOTHING` no comprueba una fila descartada. Las FKs rechazan una baja con una vigencia abierta.

## Cierres de vigencia (C2-A)

La migración **V15** agrega `gestion_patin.cerrar_vigencias_tutor` sin cambiar V14. Ejecutar primero Flyway con el propietario y el procedimiento de autorización de C1. Después, con las opciones de conexión y el rol del paso 4 de C1, ejecutar `psql -v ON_ERROR_STOP=1 -f autorizar-runtime-c2a.sql` antes de arrancar el backend C2-A. Si se conecta como migrador, anteponer `-c 'SET ROLE banfield_schema_owner'` en esa misma invocación. Si no se puede aplicar o validar el permiso, no arrancarlo.

- El runtime tiene `EXECUTE` sobre el cierre, nunca escritura directa en `tutor_deportista`, acceso a las marcas ni permisos de administración. Cada causa fija un alcance: tutor y familia, familia, deportista, o par familia–deportista. En todos los casos se filtra por escuela y solo se afectan vigencias abiertas.
- **Límite de autorización:** PostgreSQL verifica que el actor indicado sea un ADMIN activo de la escuela indicada, pero el login compartido `banfield_runtime` no identifica al usuario HTTP. Un cliente directo que ya controle ese login puede elegir una combinación válida de actor y escuela. El servicio debe tomar ambos valores de la sesión autenticada, no del cuerpo ni de parámetros del cliente, y cargar el padre por escuela antes de llamar a la función. No exponer esta conexión a clientes.
- La función toma el padre (deportista antes de la relación al revocar), la marca activa y las vigencias abiertas en orden de ID. Una guarda diferida exige que al confirmar conste la baja o revocación correspondiente y registra un evento por vigencia cerrada; confirma ambos cambios y la auditoría en la misma transacción, o revierte todo. Instalar V15 por sí sola no cambia las operaciones del backend. C2-A2 invoca el cierre desde las bajas ADMIN de familia y deportista y desde la revocación familiar, en sus transacciones existentes; Java audita la transición del padre y V15 audita las vigencias. C2-A3 agrega los comandos ADMIN de activar y desactivar tutor, con la revisión del propio tutor y sin alterar su familia. La baja cierra sus vigencias; la reactivación posterior no las reabre. Repetir un cierre no altera filas cerradas. Reactivar un padre no reabre el historial.
- La autorización de V15 no habilita altas, cambios del tipo declarado, revocaciones individuales ni escrituras documentales adicionales (eso es V16, más abajo). Se mantienen las condiciones de compatibilidad de escritores y de rollback de V14.

La reactivación del mismo padre exige una transacción posterior si su baja cerró alguna vigencia. Un trigger inmediato rechaza el `UPDATE` de reactivación incluso si se ejecutó `SET CONSTRAINTS`. La comparación usa evidencia generada por PostgreSQL en el cierre (ID e inicio de la transacción superior), no parámetros del llamador ni configuración de sesión. Si un rollback o savepoint revierte el cierre, revierte también su evidencia y auditoría. Repetir una operación sin cambiar el estado no dispara esta protección.

Los contratos HTTP de esos tres comandos no cambian. Si vence el bloqueo o una transacción con `REPEATABLE READ` no ve un alta concurrente que mantiene referenciada la marca, la operación se revierte y responde `409 CONFLICTO_CONCURRENCIA`. Se debe reintentar el comando completo en una nueva transacción; el backend no reintenta automáticamente una escritura administrativa.

## Altas, tipo declarado y revocación individual (C2-B)

La migración **V16** agrega tres funciones `SECURITY DEFINER` (propietario `banfield_schema_owner`, `search_path` fijo, sin `EXECUTE` para `PUBLIC`): `crear_vigencia_tutor`, `actualizar_tipo_vigencia_tutor` y `revocar_vigencia_tutor`. No modifica V14 ni V15 ni las operaciones permitidas por `cerrar_vigencias_tutor`. Después de Flyway y de `autorizar-runtime-c1.sql` y `autorizar-runtime-c2a.sql`, ejecutar `autorizar-runtime-c2b.sql` con parada ante errores y como propietario (mismas opciones que C2-A). El script concede solo `EXECUTE` a `banfield_runtime` y falla si el runtime obtuviera `INSERT`, `UPDATE`, `DELETE` o `TRUNCATE` directos sobre `tutor_deportista`, acceso al esquema privado o membresía del propietario.

**Límite de identidad.** Igual que en V15, PostgreSQL solo corrobora que el actor sea un ADMIN activo de la escuela indicada; el login compartido no autentica al usuario HTTP. Java toma escuela y actor siempre de la sesión autenticada, nunca del cuerpo ni de parámetros.

### Contratos HTTP (solo ADMIN, CSRF obligatorio)

| Operación | Ruta | Resultado |
| --- | --- | --- |
| Alta | `POST /api/admin/deportistas/{deportistaId}/vinculos-tutores` con `Idempotency-Key` (UUID, una sola cabecera) | `201` con la vigencia; sin `Location` |
| Tipo declarado | `PUT /api/admin/deportistas/{deportistaId}/vinculos-tutores/{vigenciaId}` con `tipo` (`null` permitido) y `versionEsperada` | `200`; sin cambio efectivo no escribe, audita ni mueve la revisión |
| Revocación | `POST .../{vigenciaId}/revocar` con `versionEsperada` | `200`, causa `REVOCACION_ADMIN`; irreversible |

Errores: `404 DEPORTISTA_NO_ENCONTRADO`, `TUTOR_NO_ENCONTRADO` o `RESPONSABLE_NO_ENCONTRADO` (otra escuela es indistinguible de inexistente); `409 EDICION_DESACTUALIZADA` (con el nombre del campo observado que cambió, nunca valores); `409 CONFLICTO_CONCURRENCIA` (plazo de bloqueo vencido o instantánea que no ve una baja confirmada: repetir el comando completo); `409 ESTADO_INCOMPATIBLE` (familia, deportista, tutor o vínculo familiar no activos, o vigencia cerrada); `409 RESPONSABLE_YA_VIGENTE`; `409 IDEMPOTENCY_KEY_REUTILIZADA`; `503 ALTA_RESPONSABLES_DESHABILITADA`. El cuerpo rechaza propiedades desconocidas (incluidos escuela, actor, familia o solicitud) y las revisiones son cadenas decimales no negativas. Solo se mapean los conflictos de concurrencia identificados; cualquier otra violación de integridad sigue siendo un `500` saneado.

### Alta: validaciones, revisiones y bloqueos

Orden: bandera → ADMIN → plazo de bloqueo → deportista `FOR UPDATE` (404) → tutor y familia del tutor (404) → revisiones observadas de familia, deportista, tutor y `versionVinculos` (409) → `FOR SHARE` de familia y tutor con el predicado de versión → función V16, que lee las cuatro marcas con `FOR KEY SHARE` (mismo bloqueo que las FKs de V14) y exige familia, deportista, tutor y `familia_deportista` activos. Orden global: deportista → familia → tutor → marcas → vigencias; edición y revocación solo toman la fila de la vigencia. Un cierre concurrente por baja espera al alta sin confirmar y cierra también la vigencia nueva; una baja confirmada antes termina el alta en `409`. Ninguna asociación se infiere: el tutor se indica expresamente y su familia debe tener un vínculo ACTIVO con el deportista. El alta no incrementa `versionVinculos`. `tipo` es opcional: ausente o `null` es distinto de `SIN_DETERMINAR`.

### Idempotencia

`Idempotency-Key` es el `solicitud_id` persistido en la vigencia y es único **por escuela** (`UNIQUE (escuela_id, solicitud_id)`): la misma clave en otra escuela es independiente y nunca revela datos ajenos. La huella es el SHA-256 que calcula el servidor sobre escuela, deportista, tutor y tipo; no incluye las revisiones, que cambian entre un intento y su repetición, ni nada enviado por el cliente. Misma clave y misma huella: se devuelve la vigencia original, también cerrada, sin crear otra ni auditar de nuevo y sin exigir revisiones vigentes (el rol ADMIN y el aislamiento por escuela se comprueban igual). Misma clave y otra huella: `409 IDEMPOTENCY_KEY_REUTILIZADA`. Una nueva responsabilidad tras una revocación exige confirmación explícita y una clave nueva. No hay caché ni memoria del proceso. Si dos solicitudes con la misma clave compiten, la segunda recibe la original; bajo `REPEATABLE READ`, si su instantánea no ve la fila concurrente, responde `409 CONFLICTO_CONCURRENCIA` y la repetición la resuelve.

### Habilitación

`banfield.responsables.alta-habilitada` (variable `BANFIELD_RESPONSABLES_ALTA_HABILITADA`) es `false` por defecto y es un control efectivo del backend: el alta responde `503 ALTA_RESPONSABLES_DESHABILITADA` antes de leer o bloquear nada. Las pruebas la habilitan solo en entornos aislados. Antes de habilitarla en un despliegue autorizado: V14, V15 y V16 aplicadas; roles Flyway y runtime separados con `autorizar-runtime-c1.sql`, `c2a.sql` y `c2b.sql` verificados; ninguna instancia ni transacción antigua capaz de desactivar un padre o revocar `familia_deportista` sin cerrar vigencias; y la decisión de no volver a un escritor incompatible mientras existan vigencias abiertas. Esta migración no habilita escrituras en producción ni cambia `DOCUMENTOS_CARGA_HABILITADA` ni `DOCUMENTOS_STORAGE_HABILITADO`.

### Historial y permisos

El historial es irreversible: un trigger rechaza `DELETE`, la reapertura y cualquier cambio de identidad de una vigencia; la reactivación de un padre nunca la reabre. Las vigencias no conceden acceso a deportistas ni documentos, y la carga documental no las consulta.

