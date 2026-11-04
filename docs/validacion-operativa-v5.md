# Validación operativa local de V5

Estado: **aprobada con reservas para el ensayo local**. Verificación funcional, independiente y RDD completas, sin bloqueantes. No se detectó un defecto de V5; no constituye autorización de despliegue ni cambio automático del PR a Ready for Review.

Base: `c32f9f455cbf086d2ce414e33b2926f6b6f1795a`, rama `feat/documentos-privados`. Plan: [validacion-operativa-v5](../odd/tasks/validacion-operativa-v5.md). No se modifica V5, V1–V4, producción, dependencias, Supabase o workflow. Sin commit, push, merge o despliegue.

## Qué se valida y qué no

Flyway original desde V4 poblada a V5, PostgreSQL/pg_dump/pg_restore **17.11**, Flyway **12.4.0**; imagen local `postgres:17-alpine` identificada por Docker como `sha256:266d3c257594e195b309ae9d1805e54c788f1d7b375f56bf6c367aac6c917e7b`. Las versiones se registran nuevamente en cada ejecución, porque el tag no fija el parche para siempre.

Datos exclusivamente ficticios: 22 tablas inventariadas; 3 escuelas (la semilla original y 2 adicionales), 4 familias/tutores, 8 deportistas, 8 vínculos y filas deportivas, 2 temporadas y 76 documentos. Se incluyen cadenas de cuatro versiones, series independientes OTRO, DNI, licencia y WADA. Tablas de módulos sin registros se conservan vacías; no se simula que todos los módulos del MVP estén implementados. El caso de pérdida de escrituras agrega intencionalmente un documento número 77 después del respaldo.

El destino no es configurable desde DB_URL ni credenciales de la aplicación: contenedores propios, Docker Unix local y URL JDBC local validada. Restauración en **otro contenedor**, no sólo otra conexión. Destrucción y limpieza sólo de recursos propios; los respaldos quedan fuera de Git.

No se han inspeccionado datos, roles ni volumen de producción. No se valida Storage ni archivos binarios. Ninguna medida local garantiza un SLA o duración para una base compartida.

## Matriz de escenarios

| Escenario | Evidencia requerida y resultado propio | Límite |
| --- | --- | --- |
| V4 poblada → V5 | Sólo una migración pendiente; todas las filas/columnas V4 conservadas; series según raíz, versión cero y catálogo válido | 11 casos operativos aprobados en suite independiente definitiva |
| Backup y restore | Dump custom completo; restauración en otro contenedor; datos, catálogo lógico e historial Flyway iguales | Roles de propietario ficticio ya existentes en ambos servidores; no inventario de roles real |
| Archivo corrupto | pg_restore rechaza el archivo y conserva el destino poblado intacto | Control negativo; no se presenta como simulación de todos los fallos de disco/red |
| Recuperación V4 | Se restaura nuevamente el dump V4 después de aplicar V5 en el origen y se recupera el estado previo exacto | La escritura posterior de prueba queda fuera del backup: ventana sin escrituras o RPO aprobado indispensable |
| Idempotencia | Segunda ejecución Flyway sobre V5 ejecuta cero migraciones, sin cambiar datos/catálogo/historia | No equivale a ejecutar el SQL de V5 manualmente otra vez |
| Siete incompatibilidades | Propietario, DNI/temporada, DNI vigente duplicado, contexto cruzado, bifurcación, ciclo y dos vigentes por cadena: SQLSTATE 23514 en dos intentos, estado completo sin cambios | Casos adicionales escuela/tipo/temporada y concurrencia de escrituras se conservan en pruebas V5 existentes |
| Fallo DDL tardío | Rol ficticio sin CREATE de esquema: SQLSTATE 42501 en ALTER TABLE/ADD CONSTRAINT, después de columnas/backfill; rollback exacto de datos, catálogo e historial; reintento exitoso tras GRANT sólo local | No se afirmó que el fallo ocurriera en CREATE FUNCTION; la hipótesis inicial fue refutada |
| Lectura anterior a V5 | pg_blocking_pids observa espera real de Flyway; lock_timeout=2 s genera 55P03; rollback intacto y éxito al liberar la lectura | El timeout se configura en la conexión del ensayo, no se agrega a V5 |
| Lectura/escritura durante V5 | Flyway real pausado tras primer ALTER; ambas consultas bloqueadas por su PID; statement_timeout=1.2 s produce 57014; finaliza correctamente al liberar | Event trigger sólo de instrumentación en contenedor privado; no es una pausa productiva ni un benchmark |
| Regresión de módulos | Conservación de filas en todas las tablas y catálogo; pruebas normales, D1, V5 y DB requeridas en O4 | 903 normales y 451 DB aprobadas en scratch independiente, incluidos D1/V5 |

### Cómo se compara la información

Se comparan filas JSONB ordenadas de **todas** las tablas del esquema, no sólo documento. Al comparar V4 con V5 se excluyen exclusivamente las dos columnas nuevas del documento y la nueva fila version=5 de Flyway; la fila SCHEMA con version=NULL se conserva. Los casos de fallo comparan el estado íntegro sin esas exclusiones.

Catálogo lógico: columnas/defaults/nullabilidad, todas las constraints/validación, índices válidos/listos, triggers de usuario y su estado, funciones, propietarios/ACL de tablas y esquema, y secuencias. Los triggers internos de FK tienen identificadores físicos dependientes de OID: se verifica la definición/validación de sus constraints, no la igualdad de nombres de OID entre servidores.

pg_dump/restore puede reparsear un CHECK y distribuir casts de un ARRAY. Para restauración se lleva la definición a un punto fijo del **parser PostgreSQL** en una tabla temporal con tipos/columnas idénticos, rollback por paso y límite de cinco pasos; no se borran casts por regex ni se omiten constraints. Si no converge, la prueba falla. En el rollback de una migración se compara el catálogo crudo exacto, sin normalizarlo.

## Evidencia propia observada

- Primer intento: error de compilación en una llamada del fixture nuevo, corregido sin tocar helpers existentes ni V5.
- Primera suite operacional: 11 casos, 7 aprobados y 4 fallos del harness. Se demostraron sus causas (fila SCHEMA nula, representación CHECK al restaurar y supuesto incorrecto sobre el punto de error de permisos) antes de corregirlas. No se registran como fallos de la migración.
- Recheck limitado a los cuatro afectados: **4/4 aprobados**, sin errores ni omitidos. No se presenta la suma 7+4 como una ejecución completa del candidato final.
- Log propio: `/tmp/v5-operativa-parent-recheck.log`.
- Evidencia propia: `/tmp/banfield-v5-operativa-15361117856826883557/`.
- Dump `v4-d45226e7-4b80-491e-b8ec-55c3314929d5.dump`, SHA-256 `3b8e625f418b682b7c6e15c28828775a4ec5e6c25fa8f3235380a76b63969836`.
- Huella lógica V4 y restaurada: ambas `aa2393f471473085e7f2b445f31eaa665f8352347c2a7c6934c419591bb785a4`.
- Espera/fallo por lock: 2521 ms medidos, lock_timeout configurado en 2000 ms; incluye sobrecarga de Flyway.
- Escenario de interrupción **artificialmente instrumentado**: 1358 ms, consultas limitadas a 1200 ms. No usar como estimación de duración normal o productiva.

Los JSON por caso registran versiones, conteos, SQLSTATE, huellas y controles negativos; el dump conserva todos los datos ficticios para auditoría local. Son artefactos temporales, no una política de retención productiva. Los nombres/IDs de actores generados por DatosDb pueden variar entre ejecuciones; cada backup debe igualar su propia restauración, no otro dataset nuevo.

## Reproducción local

Requisitos: Java 21/Maven del proyecto, Docker local por `/var/run/docker.sock`, imagen PostgreSQL 17 y dependencias ya disponibles para modo offline. Sin configuración de Supabase ni DB_URL.

Desde `backend/`:

```bash
DOCKER_HOST=unix:///var/run/docker.sock ./mvnw -o -B -Pdb-tests -Dtest=V5OperacionDbTest test
DOCKER_HOST=unix:///var/run/docker.sock ./mvnw -o -B clean verify
DOCKER_HOST=unix:///var/run/docker.sock ./mvnw -o -B verify -Pdb-tests
```

Las rutas de evidencia se imprimen como `V5_OPERATIVA ... evidencias=/tmp/banfield-v5-operativa-*`. El test crea sus contenedores y los cierra; no detiene, borra ni consulta bases existentes. Los comandos reales internos son pg_dump custom y pg_restore con `--exit-on-error --single-transaction --clean --if-exists`, **exclusivamente contra el contenedor destino creado por el test**. No copiar ese restore contra una base compartida o con datos que deban preservarse.

V5 se ejecuta por Flyway (`classpath:db/migration`, esquema/default schema gestion_patin), igual motor y configuración de migraciones del proyecto; no se usa psql para aplicar manualmente el archivo ni se altera su checksum. El ensayo no arranca toda la aplicación con credenciales reales. La regresión de D1/contextos reales corresponde a las suites existentes.

## Procedimiento recomendado para una ejecución futura autorizada

Esta sección no autoriza ni ejecuta un despliegue.

1. Inventariar versión PostgreSQL, migraciones V1–V4/checksums, rol migrador, propiedad y privilegios necesarios (incluido CREATE de esquema), roles/extensiones y volumen reales. Obtener autorización y comprobar la ventana operativa.
2. Verificar datos incompatibles **antes** de la ventana larga; V5 vuelve a comprobar bajo lock para evitar carreras. No corregir registros automáticamente ni saltar validaciones.
3. Establecer ventana sin escrituras y drenar transacciones largas. ACCESS EXCLUSIVE bloquea lecturas/escrituras de documento; en una cola puede afectar consultas posteriores incluso mientras espera. Definir y aprobar presupuestos de lock/statement timeout y su aplicación a las conexiones Flyway antes de ejecutar; V5 no fija esos límites por sí misma.
4. Tomar backup consistente, preservar roles/privilegios requeridos por el entorno y probar restore en destino independiente, comparando datos, referencias, catálogo e historial. Guardar el backup fuera del árbol Git con protección, acceso y retención aprobados antes de usar datos reales.
5. Aplicar sólo la migración pendiente con Flyway original. Supervisar bloqueos, duración, estado transaccional, versión/checksum de Flyway, constraints e índices válidos, triggers habilitados y conservación de datos. No habilitar tráfico si faltan comprobaciones críticas.
6. Si falla por incompatibilidad: detener, verificar rollback y obtener decisión humana sobre los datos. Si falla por lock: liberar de forma autorizada la causa, verificar rollback y reintentar dentro de la ventana. Si falla por permisos: corregir sólo con autorización y repetir los checks. No usar flyway repair o editar checksums/historia para forzar continuidad.
7. Si hay un resultado ambiguo por desconexión: consultar versión/checksum, estado de transacción y esquema desde una sesión autorizada antes de decidir el reintento. El ensayo probó errores SQL controlados, no todo fallo de infraestructura o pérdida de disco.

## Recuperación

- **Fallo transaccional antes de confirmar V5:** las pruebas exigen volver exactamente al estado V4 en datos/catálogo/historia. Comprobar ese estado y resolver la causa autorizadamente antes de volver a invocar Flyway. No confiar únicamente en el exit code del cliente.
- **V5 confirmada y necesidad de volver a V4:** no hay migración down; revertir Git no revierte PostgreSQL. Restaurar el respaldo probado en una base independiente, validar manifiestos, coordinar la versión anterior de la aplicación y una conmutación autorizada. Este ensayo no realizó conmutaciones ni despliegues.
- **Escrituras posteriores:** restaurar el respaldo anterior no las recupera (control demostrado de una fila). RPO cero requiere ventana sin escrituras o estrategia adicional explícitamente aprobada. No prometer pérdida cero si el tráfico se reanudó.

## Verificación independiente del candidato final

Agente `01a1a3ad-01a8-7f70-9adc-6852f17fdabe`, tarea `v5_operativa_verify`, gpt-6.1-sol/medium, distinto del implementador. wait_agent confirmó completed/PASS, sin bloqueantes causados por el candidato. O4 satisface S1–S7/S9 y A1–A9 para el ensayo local; S8/A10 se completan con este informe y el cierre nativo registrado abajo.

| Comando en scratch nuevo, DOCKER_HOST Unix local | Resultado observado |
| --- | --- |
| ./mvnw -o -B clean verify | 903/903, cero fallos/errores/omitidos |
| ./mvnw -o -B verify -Pdb-tests | 451/451, incluidos los 11 operativos, cero fallos/errores/omitidos |

Los intentos iniciales dentro del sandbox fallaron por adjunción Mockito y acceso Docker denegado; se repitió cada comando una vez con el permiso de entorno correspondiente. No se mezclan sus XML residuales ni sus errores con el resultado final. No se descargaron dependencias ni imágenes en las ejecuciones exitosas. La CI ya publicada de c32f9f4 conserva 903/440; los 11 casos nuevos sólo están validados localmente, sin publicación ni reejecución remota.

Sonda independiente adicional: restore del dump en un tercer contenedor con `--network none --pull=never`; 76 documentos, 52 raíces, 8 deportistas, 4 tutores, 8 vínculos, cero predecesores huérfanos/constraints sin validar e historial exitoso V1–V4. Propietario ficticio banfield_test presente en ambos servidores. Otra sonda rechaza DOCKER_HOST no Unix antes de iniciar el contenedor.

Evidencia independiente primaria:

- Raíz de logs/manifiestos/sondas: `/tmp/v5-independent-6fx3gb/`; normal-reports, db-reports, evidence-manifest.json e independent-dump-proof.json.
- Dump: `/tmp/banfield-v5-operativa-18093436874597137285/v4-90aad94c-e915-4693-975c-8788ce016c44.dump`, 106029 bytes.
- SHA-256 dump: `d696e1472df938a3d1b2d89b84ad02290dda946c6166ce8573e6ad1b525e4cc8`.
- Huellas lógica original/restaurada idénticas: `a5a60ce221f12afd2485c7ba92db9c1cbff1d0da1dc4297674a2fd2ef6ec138e`.
- PostgreSQL/clientes 17.11, Flyway 12.4.0. Lock timeout: 2535 ms observados con límite 2000 ms; intervalo de bloqueo artificial 1351 ms con consultas a 1200 ms. No son presupuestos de producción.
- El padre verificó directamente hashes de fuentes y dump frente al resultado independiente. El spot-check posterior del comando normal se registra en el plan ODD, sin cambiar las fuentes verificadas.

## Reservas y recomendación de integración

**Recomendación: PR #10 puede avanzar a Ready for Review por decisión humana**, al quedar satisfechas las verificaciones operativas locales y el RDD sin bloqueantes. No se cambia el estado del PR, ni se autoriza merge o despliegue. Estos cuatro archivos locales no están publicados; su incorporación/publicación requiere una decisión separada, sin commits o push en esta tarea.

Aun con todos los ensayos locales aprobados: datos/roles/volumen reales, presupuesto operativo, respaldo/restore real y plan de recuperación/conmutación siguen siendo requisitos de **despliegue**, no algo demostrado por CI o por estos datos ficticios. V5 y producción permanecen intactas; cualquier defecto demostrado exige una propuesta y autorización nueva, no una corrección preventiva.


## Cierre RDD

Consentimiento específico "granted", revisión nativa `review-1bd0996d659e42a6` sobre el candidato `sha256:7ebbd69fe290a319266e51a194fc249a234f31b0a002fbdf4163c2715c411e0f`: riesgo, resiliencia, legibilidad y confiabilidad aprobadas, sin bloqueantes. Reconocimiento nativo confirmado y autoridad consumida. Una advertencia informativa R2-001 señaló estados documentales anteriores; se concilian en el cierre ODD. No se abre una corrección ni se repite RDD del candidato. Las fuentes ejecutables siguen idénticas a las verificadas y aprobadas; el delta documental de cierre se evalúa por separado, sin extender automáticamente la aprobación a nuevos bytes. No se repiten pruebas por este cambio exclusivamente documental.

El assess separado del cierre documental devolvió passive/non_executable_only y review_due=false; no exige nueva revisión.
