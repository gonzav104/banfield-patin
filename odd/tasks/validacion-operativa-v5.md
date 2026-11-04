# Validación operativa aislada de V5
Objetivo: comprobar respaldo/restauración, conservación de datos y atomicidad de V5 en PostgreSQL 17 local; estado actual: validación local completada con reservas, verificación funcional/independiente y RDD cerrados.
Base: `feat/documentos-privados` @ `c32f9f455cbf086d2ce414e33b2926f6b6f1795a`. Entrega: informe y evidencias locales, sin commit/push/merge/despliegue. Runner: Maven/JUnit, PostgreSQL 17/Testcontainers y Flyway original; comandos y evidencia en docs/validacion-operativa-v5.md.

## Specs

- **S1.** "Revisá el estado del repositorio, la documentación ODD y la implementación actual de V5." / "Identificá las tablas, restricciones, índices y relaciones afectados." / "Determiná qué pruebas existentes cubren V5 y qué aspectos operativos permanecen sin validar." / "Registrá un plan ODD específico antes de modificar archivos." / "Definí criterios objetivos de aceptación y recuperación."
- **S2.** "Utilizá PostgreSQL 17 en un entorno local aislado." / "No conectes ni modifiques bases de datos de producción." / "Prepará una base representativa de una instalación anterior a V5, con registros y relaciones existentes." / "Incluí casos válidos, límites e incompatibilidades previsibles." / "No utilices datos personales reales ni credenciales de producción."
- **S3.** "Generá un respaldo previo a la migración." / "Comprobá que el respaldo puede restaurarse correctamente en una base independiente." / "Verificá integridad referencial, conteos y datos relevantes después de restaurar." / "Documentá cómo recuperar el estado anterior si falla la migración." / "No consideres validado el respaldo solamente porque `pg_dump` terminó sin errores."
- **S4.** "Ejecutá la migración mediante el mecanismo real utilizado por el proyecto." / "Verificá los resultados sobre una base poblada." / "Comprobá que los registros existentes se conservan y que se respetan las restricciones." / "Evaluá qué sucede cuando existen datos incompatibles." / "No modifiques V5 para forzar el éxito de las pruebas."
- **S5.** "Detectá fallos parciales, bloqueos, conflictos de concurrencia y problemas transaccionales." / "Determiná si la migración permite reintentos seguros y bajo qué condiciones."
- **S6.** "Ausencia de efectos inesperados sobre módulos existentes." / "Condiciones de recuperación y posibles interrupciones de servicio." / "Registrá comandos, resultados y evidencias reproducibles."
- **S7.** "Si detectás defectos, separá los hallazgos de las correcciones propuestas." / "No implementes cambios de producción automáticamente." / "Para cualquier modificación necesaria, seguí el proceso ODD/RDD correspondiente y solicitá las autorizaciones que exija el flujo."
- **S8.** "Estado de validación de V5: aprobada, aprobada con reservas o bloqueada." / "Escenarios ejecutados y resultados." / "Evidencia de respaldo y restauración." / "Riesgos de migración pendientes." / "Procedimiento recomendado de ejecución y recuperación." / "Recomendación fundamentada sobre si PR #10 puede avanzar a Ready for Review."
- **S9.** "No modificar producción ni V5 sin autorización." / "No ejecutar operaciones destructivas fuera del entorno aislado." / "No realizar commit, push, merge ni despliegue." / "No cerrar riesgos sin evidencia." / "No declarar éxito si alguna validación crítica permanece pendiente."
- **S10 (excepción operativa autorizada, L3).** Respuesta textual del usuario: "autorizo" a la propuesta exclusiva para V5 de spawn_agent/wait_agent sin list_agents, gpt-6-luna para exploración y gpt-6.1-sol para verificación independiente; no constituye cumplimiento literal del protocolo habitual ni cambia RDD nativo.

### Inventario inicial y límites de lo inspeccionado

- Estado inicial verificado: rama y HEAD anteriores, árbol limpio; no se contactó ningún remoto ni base de datos para esta tarea.
- V5 original: `backend/src/main/resources/db/migration/V5__reforzar_integridad_documental.sql`, SHA-256 `fb9971ff8cab0210b973bae9a0c01cc406e62048c1166a775ef6dd8ea05a875f`. Se conserva inmutable.
- Afecta directamente `gestion_patin.documento`: lock ACCESS EXCLUSIVE; prevalidación; columnas serie_id/version; backfill por raíz; comprobaciones de contexto/propietario/temporada; FKs compuestas a predecesores; índices únicos de serie vigente, sucesor y DNI vigente; función/trigger de contexto. Deshabilita temporalmente y vuelve a habilitar trg_documento_actualizado durante el backfill dentro del script transaccional.
- Deben comprobarse las relaciones existentes con escuela, deportista, tutor, temporada y usuario, además de Flyway y los módulos previos. No se presume inspeccionada la semántica completa de V1–V4 en esta primera lectura.
- Inventario de pruebas localizado: V5IntegridadDocumentalDbTest (21 ejecuciones en CI previo) y V5CasosLimiteDbTest (4 en la verificación anterior); usan Flyway y fixtures PostgreSQL. Los nombres cubren backfill, prechecks, cardinalidad, historial, concurrencia y operaciones masivas; el mapa detallado de assertions queda pendiente de lectura delegada.
- No consta evidencia operativa de pg_dump + restauración independiente, recuperación completa, cancelación tardía de migración ni presupuesto de indisponibilidad. El CI anterior verde no sustituye esas pruebas.
- Documento de referencia: `odd/tasks/documentos-privados.md`, D2/L8–L16. No se reabren D1, D2 ni la revisión de código idéntico.
- Datos y volumen serán ficticios, con semilla/IDs y conteos reproducibles. No se extrapolará una medición local a volumen, carga o datos de una instalación compartida desconocida.

### Criterios objetivos de aceptación y recuperación

| ID | Prueba requerida | Evidencia mínima para aceptar |
| --- | --- | --- |
| A1 | Aislamiento | PostgreSQL 17 creado para el ensayo, identificación inequívoca de recursos propios; sin URL, credencial o conexión de producción; operaciones destructivas sólo sobre esos recursos |
| A2 | Base anterior poblada | Migraciones originales hasta V4 mediante Flyway, historial/checksums correctos y fixtures con propietarios, escuelas, temporadas y cadenas; manifiesto de contenido, conteos y relaciones antes de V5 |
| A3 | Respaldo restaurable | Respaldo V4, restauración en una base independiente y comparación por contenido canónico/conteos, referencias, esquema e historial Flyway; verificar requisitos de roles/extensiones/privilegios que apliquen, no sólo exit code de pg_dump |
| A4 | Actualización válida | Flyway aplica sólo V5 pendiente; todas las columnas V4 y relaciones conservan su contenido; cada cadena obtiene la misma serie raíz y versión inicial esperada; constraints/índices/triggers activos y válidos |
| A5 | Incompatibilidades | Casos V4 admisibles pero incompatibles con V5 son rechazados sin reparar: contexto cruzado, DNI/propietario/temporada incompatible, bifurcación/ciclo y vigencias duplicadas; datos, esquema y Flyway quedan en estado anterior |
| A6 | Atomicidad y errores | Fallo controlado después de iniciar cambios, si puede instrumentarse sin editar V5; comparar datos y catálogo completos, estado de triggers e historial. Registrar SQLSTATE y demostrar rollback, no inferirlo sólo del mensaje de error |
| A7 | Bloqueos/concurrencia | Conexiones distintas y sincronización verificable por pg_locks/pg_blocking_pids; observar espera/timeout y liberación sin corrupción, sin sleeps como prueba de concurrencia; medidas y límites locales explícitos |
| A8 | Reintento y recuperación | Volver a ejecutar Flyway después de liberación/rollback controlado sin duplicar cambios; ejecución repetida sobre V5 válida no modifica datos; restauración completa al estado V4 comprobada. No usar repair/edición de historial o reparación de datos automáticamente |
| A9 | Regresión y límites | Pruebas V5 y consultas/autorización D1 preservadas; suites aplicables normales/DB y verificación independiente. Medir interrupción local y señalar que restaurar un respaldo anterior pierde escrituras posteriores si no hubo ventana sin escrituras |
| A10 | Informe verificable | Cada escenario con comando, versión, estado previo/posterior y resultado; distinción aprobado/reserva/bloqueante. Cualquier prueba crítica sin ejecutar mantiene la validación pendiente/bloqueada, no aprobada por hipótesis |

### Política de ejecución

Pruebas/fixtures/herramientas reproducibles sólo locales, sin modificar migraciones, producción, dependencias o workflow preventivamente. La superficie exacta de tests y evidencias se derivará del mapa antes de implementar. Respaldos y artefactos temporales fuera del repositorio; el informe sólo contiene datos ficticios y evidencia sanitizada. No se captura ninguna sesión remota. La limpieza identifica recursos propios; los agentes no eliminan scratch, informa sus rutas para limpieza por el padre. No se publica ni marca Ready for Review el PR.

Cuando haya una prueba determinista y resultado claro, observar RED antes de cambiar el harness y luego GREEN; no modificar V5 para satisfacerla. Una avería real de V5 es un hallazgo, no autorización de corrección. Si no hay RED significativo para un procedimiento documental o una comprobación ya satisfecha, registrar esa excepción y su evidencia; nunca inventar RED. Normalizar cualquier fuente antes de congelar el candidato y aplicar assess/RDD sólo al candidato nuevo cuando corresponda; una revisión no reemplaza las verificaciones funcionales.

## Tasks

| ID | Specs | Unidad | Ruta y motivo | Estado / evidencia / commit |
| --- | --- | --- | --- | --- |
| O1 | S1,S4,S5 | Mapa de V1–V5, Flyway/helpers/tests y huecos operativos; cerrar superficie del ensayo | delegated read-only odd-explorer: el entendimiento operativo excede el lote inicial; no sustituir este mapa con exploración inline ilimitada | COMPLETADA S1/S4/S5; agente Curie, wait_agent terminal completed, mapa en L4 |
| O2 | S2,S3 | Fixture V4 poblada, snapshot, respaldo y restauración independiente reproducible | inline, padre; no hay motivo de escritura paralela ni backstop de contexto | COMPLETADA; respaldo/restauración/control negativo y huellas verificados independientemente; L8/L10 |
| O3 | S4,S5,S6 | Ensayo Flyway de éxito, incompatibilidad, atomicidad, bloqueo y reintento sin editar V5 | inline, padre; mismo comportamiento/harness con sus pruebas y documentación, sin dividir por capas | COMPLETADA; once casos operativos aprobados en suite independiente definitiva, 451 DB; L8/L10 |
| O4 | S3–S6,S9 | Verificación independiente y suites aplicables desde scratch fresco | delegated odd-verify, distinto del implementador; riesgos 1 (datos), 4 (concurrencia), 5 (entorno operativo) | COMPLETADA; wait_agent completed/PASS; 903 normales/451 DB, once casos operativos; L8 |
| O5 | S7,S8,S9 | Informe, procedimiento de ejecución/recuperación, recomendación Ready for Review y assess/RDD aplicable | inline; candidato sólo de validación/documentación, consentimiento nativo si corresponde | COMPLETADA CON RESERVAS; RDD aprobado/reconocido en L10, informe final; sin commit/push/despliegue |

Este documento registra cinco tareas y no autoriza excepciones de herramientas/modelos ni cambios productivos. Previsión inicial: ensayo, fixtures e informe pueden requerir varios cientos de líneas; el tamaño exacto se revisará después del mapa, sin recortes cosméticos. La entrega actual no incluye commit ni publicación.

## Log

### L1 — Solicitud original del usuario (2026-11-03), textual

Continuemos con el proyecto Banfield Patín Carrera siguiendo estrictamente el flujo ODD/RDD.

## Contexto

- Rama: `feat/documentos-privados`.
- Último commit: `c32f9f455cbf086d2ce414e33b2926f6b6f1795a`.
- PR #10 permanece en Draft.
- CI remoto aprobado: 903 pruebas normales y 440 PostgreSQL 17.
- RDD anterior cerrado sin hallazgos.
- V5 permanece intacta.
- Está pendiente validar operativamente V5 sobre una base de datos existente.

## Objetivo

Realizar una validación integral, reproducible y segura de la migración V5 sobre PostgreSQL 17, verificando respaldo, recuperación, compatibilidad de datos, integridad y comportamiento transaccional.

No busco desarrollar nuevas funcionalidades ni modificar la migración preventivamente.

## Instrucciones

### 1. Inspección y planificación ODD

- Revisá el estado del repositorio, la documentación ODD y la implementación actual de V5.
- Identificá las tablas, restricciones, índices y relaciones afectados.
- Determiná qué pruebas existentes cubren V5 y qué aspectos operativos permanecen sin validar.
- Registrá un plan ODD específico antes de modificar archivos.
- Definí criterios objetivos de aceptación y recuperación.

### 2. Preparación del entorno

- Utilizá PostgreSQL 17 en un entorno local aislado.
- No conectes ni modifiques bases de datos de producción.
- Prepará una base representativa de una instalación anterior a V5, con registros y relaciones existentes.
- Incluí casos válidos, límites e incompatibilidades previsibles.
- No utilices datos personales reales ni credenciales de producción.

### 3. Respaldo y recuperación

- Generá un respaldo previo a la migración.
- Comprobá que el respaldo puede restaurarse correctamente en una base independiente.
- Verificá integridad referencial, conteos y datos relevantes después de restaurar.
- Documentá cómo recuperar el estado anterior si falla la migración.
- No consideres validado el respaldo solamente porque `pg_dump` terminó sin errores.

### 4. Ejecución de V5

- Ejecutá la migración mediante el mecanismo real utilizado por el proyecto.
- Verificá los resultados sobre una base poblada.
- Comprobá que los registros existentes se conservan y que se respetan las restricciones.
- Evaluá qué sucede cuando existen datos incompatibles.
- Detectá fallos parciales, bloqueos, conflictos de concurrencia y problemas transaccionales.
- Determiná si la migración permite reintentos seguros y bajo qué condiciones.

No modifiques V5 para forzar el éxito de las pruebas.

### 5. Pruebas y evidencias

Ejecutá las verificaciones necesarias para demostrar:

- Migración correcta sobre datos existentes.
- Conservación e integridad de información.
- Funcionamiento de respaldos y restauraciones.
- Comportamiento ante errores.
- Ausencia de efectos inesperados sobre módulos existentes.
- Condiciones de recuperación y posibles interrupciones de servicio.

Registrá comandos, resultados y evidencias reproducibles.

### 6. Evaluación ODD/RDD

Si detectás defectos, separá los hallazgos de las correcciones propuestas.

No implementes cambios de producción automáticamente.

Para cualquier modificación necesaria, seguí el proceso ODD/RDD correspondiente y solicitá las autorizaciones que exija el flujo.

### 7. Entrega

Al finalizar, presentá:

1. Estado de validación de V5: aprobada, aprobada con reservas o bloqueada.
2. Escenarios ejecutados y resultados.
3. Evidencia de respaldo y restauración.
4. Riesgos de migración pendientes.
5. Procedimiento recomendado de ejecución y recuperación.
6. Recomendación fundamentada sobre si PR #10 puede avanzar a Ready for Review.

## Restricciones

- No modificar producción ni V5 sin autorización.
- No ejecutar operaciones destructivas fuera del entorno aislado.
- No realizar commit, push, merge ni despliegue.
- No cerrar riesgos sin evidencia.
- No declarar éxito si alguna validación crítica permanece pendiente.

Trabajá con autonomía dentro de estas restricciones y entregá un diagnóstico verificable, priorizando la integridad de datos y la seguridad operativa.

### L2 — Inspección inicial y bloqueo operativo (2026-11-03)

Se verificó HEAD/rama/árbol y el SHA de V5. No se ejecutó Docker, PostgreSQL, pg_dump, pg_restore, Flyway ni suite nueva; no se modificaron fuentes existentes. Este plan es el primer archivo nuevo y se registra antes de implementar el ensayo.

Capacidad observada: features.multi_agent=true; spawn_agent y wait_agent presentes, list_agents ausente. La ruta habitual requiere las tres. Las excepciones previas son exclusivamente D2 y corrección CI, no se extienden a esta validación. Además odd-explorer exige gpt-6.1-luna en la asignación vigente, pero ese modelo no figura entre los disponibles en spawn_agent; gpt-6-luna sí figura. No se intentó un lanzamiento que ya se sabe incompatible ni se simularon resultados.

O1 requiere delegación de mapa y O4 verificación independiente real. Se detiene la ejecución de esas tareas hasta habilitar la ruta habitual o recibir una excepción específica para esta validación (spawn/wait sin list_agents y sustitución explícita del modelo de exploración). La propuesta de usar gpt-6-luna para exploración y mantener gpt-6.1-sol para verificación todavía NO está autorizada. RDD nativo no tiene excepción. Estado de V5: no validada operativamente en esta tarea; no se atribuye un defecto a la migración por la falta de herramientas.


### L3 — Excepción operativa específica autorizada (2026-11-03)

Propuesta presentada: «¿Autorizás, exclusivamente para esta validación V5, usar spawn_agent/wait_agent sin list_agents, con gpt-6-luna para exploración y gpt-6.1-sol para verificación independiente, manteniendo RDD nativo completo?» Respuesta del usuario, textual: "autorizo".

Se aplica sólo a esta validación, no por herencia de D2/CI; no constituye cumplimiento literal de la ruta habitual. Se conservan verificación independiente real con resultado terminal de wait_agent, registro de identificadores/tareas/conclusiones y RDD nativo completo, con consentimiento específico cuando corresponda. No habilita modificaciones productivas, V5, commits, publicación ni bases compartidas. Las frases de L2 describen el bloqueo histórico, ahora resuelto por esta autorización.


### L4 — Mapa independiente recibido y superficie del ensayo (2026-11-03)

Agente explorador Curie `01a1a39e-8f79-78b0-a646-2feacdb73757`, tarea `v5_operativa_map`, gpt-6-luna/high, skill_resolution=paths-injected; wait_agent devolvió completed. Conclusión: reutilizar Flyway original, PostgresDescartable y DatosDb. Cobertura existente no demuestra pg_dump/restore independiente, rollback después de DDL ni contención de la migración (los locks actuales prueban escrituras documentales). Aserciones actuales de rollback inspeccionan documento, no toda la base/catálogo. La inyección de permisos para fallo tardío es una hipótesis que debe comprobarse, no un hallazgo de V5.

Superficie prevista: sólo nuevas clases de pruebas `backend/src/test/java/com/banfieldpatin/backend/db/V5OperacionDbTest.java` y `V5OperacionSupport.java`, informe `docs/validacion-operativa-v5.md` y este plan. No editar helpers existentes ni dependencias, V1–V5 o código productivo. Escritura inline por el padre, sin worker escritor; verificador independiente distinto para O4. Docker local 29.9.2 e imagen postgres:17-alpine disponible verificados sin tocar contenedores existentes. Bases/dumps propios de Testcontainers; dumps sólo en /tmp. No se hará commit aunque se cierre la validación.

### L5 — Construcción del ensayo y disciplina de pruebas (2026-11-03)

Se agregan únicamente las dos clases de ensayo previstas; no se cambia comportamiento productivo. Excepción de RED: son pruebas de caracterización sobre una migración existente que no se modifica, por lo que no se fabrica un RED mediante stubs. Controles negativos: archivo de respaldo corrupto debe fallar sin cambiar el destino; SQLSTATE esperados para incompatibilidad, permiso tardío y timeout. La primera compilación detectó un argumento omitido al reutilizar DatosDb.datosDeportivos en el nuevo fixture; corregido sólo en la clase nueva, no es un defecto de V5 ni evidencia de RED funcional. Se comparan filas de todas las tablas, columnas/constraints/índices/triggers de usuario/funciones/propietarios/ACL/secuencias del esquema e historial Flyway. Los triggers internos de FK tienen nombres físicos dependientes de OID y se representan por las definiciones/validación de sus constraints, no por sus nombres variables al restaurar.

Se usan dos escuelas, cuatro familias/tutores, ocho atletas, ocho vínculos/filas de temporada y cadenas documentales; estructura y documentos deterministas, IDs generados por helpers registrados en la huella de cada ejecución (no se exige el mismo UUID de actor entre servidores nuevos). Los dumps conservan sus IDs al restaurar. La pausa de DDL sólo existe como event trigger de instrumentación en un contenedor exclusivo; no es una modificación de la migración ni un benchmark de tiempos productivos.


### L6 — Resultados propios y correcciones del harness (2026-11-03)

Primera ejecución funcional: 11 casos, siete incompatibilidades aprobadas y cuatro fallos del harness, no de V5. Se verificaron sus causas antes de corregir: el filtro legado omitía la fila SCHEMA de Flyway por version=NULL (se usa IS DISTINCT FROM); pg_dump/pg_restore distribuye casts ARRAY en CHECK, deparsing distinto pero parser semántico equivalente; el fallo de permiso ocurre en ALTER TABLE/ADD CONSTRAINT tras columnas/backfill, no en CREATE FUNCTION como se había supuesto. No se modificó V5 ni se ignoraron constraints. Para restauración se comparan CHECKs en su punto fijo por el parser PostgreSQL sobre columnas temporales idénticas, con rollback y límite de cinco pasos, sin borrar casts por regex; los checks de rollback siguen comparando el catálogo crudo exacto.

Recheck sólo de los cuatro afectados: 4/4 sin fallos/errores/omitidos. Evidencias propias: /tmp/banfield-v5-operativa-15361117856826883557; log /tmp/v5-operativa-parent-recheck.log. Los siete incompatibles habían pasado en /tmp/banfield-v5-operativa-10589465783931550845; no se suman como una suite final íntegra de un único candidato. La suite completa del candidato definitivo la ejecutará el verificador independiente. Antes de crear contenedores se exige transporte Docker Unix y luego URL JDBC local, además de recursos con nombres propios y sin destino externo configurable. V5 conserva su SHA original.


### L7 — Verificación independiente iniciada (2026-11-03)

Agente `01a1a3ad-01a8-7f70-9adc-6852f17fdabe`, tarea `v5_operativa_verify`, gpt-6.1-sol/medium, distinto del implementador padre y del explorador. Debe comprobar seguridad del harness, ejecutar normal/DB en scratch nuevo desde c32f9f4, inspeccionar dump/manifiestos/resultados y entregar conclusión por S#/A#. Lanzamiento no equivale a PASS; esperar retorno terminal real antes del cierre.


### L8 — Resultado independiente y reservas (2026-11-03)

Agente Confucius `01a1a3ad-01a8-7f70-9adc-6852f17fdabe`, v5_operativa_verify, wait_agent completed: PASS con reservas operativas, sin bloqueantes del candidato. Skill_resolution=paths-injected. Scratch fresco `/tmp/v5-independent-6fx3gb`, baseline c32f9f4 + las dos fuentes nuevas; normal 903/903 y DB 451/451, cero fallos/errores/omitidos, incluidos once casos operativos. Primeros intentos sandbox fallaron por permisos Mockito/Docker; un escalamiento por comando resolvió el entorno, no un cambio de código. No se confunden XML antiguos con ejecuciones actuales.

Restauración independiente adicional a tercer contenedor sin red confirmó dump 106029 bytes SHA d696e1472df938a3d1b2d89b84ad02290dda946c6166ce8573e6ad1b525e4cc8, huella origen/restaurada a5a60ce221f12afd2485c7ba92db9c1cbff1d0da1dc4297674a2fd2ef6ec138e; 76 documentos/52 raíces/8 atletas/4 tutores/8 vínculos, sin huérfanos ni constraints inválidas, historial V1–V4 y propietario ficticio preparado. O4 pasa A1–A9 localmente; A10/S8 requieren informe y disposición RDD. SQLSTATE 23514/42501/55P03/57014 observados; reintentos sólo bajo las condiciones probadas. El padre cotejó directamente los hashes de fuentes/dump. Informe en docs/validacion-operativa-v5.md.

Reservas: no validación de roles/extensiones/datos/volumen/carga o SLA reales; restaurar V4 pierde escrituras posteriores si no se impidieron. Ninguna autorización para producción, Ready, commit o publicación se infiere del PASS. Risk: item 1 (respaldo/restore y DDL), item 4 (locks/concurrencia), item 5 (harness de entorno); verificación independiente satisfecha, RDD nativo aún pendiente.


### L9 — Candidato de validación preparado (2026-11-03)

Spot-check posterior del padre: DOCKER_HOST=unix:///var/run/docker.sock ./mvnw -o -B clean verify, 903/903 sin fallos/errores/omitidos; log /tmp/v5-operativa-parent-spotcheck.log. Fuentes de prueba idénticas a las verificadas independientemente (SHA V5OperacionDbTest 32d7461d647013a26d92456b6c0768961d1eeb752f80b58f70f3fd5000dead3c, Support f49a89e0c0378f8f201c027d5a42a44cff0a209e3da48cf4684787e2bccdf937). Informe consolidado; estado formal bloqueado sólo por cierre/consentimiento RDD pendiente, no por un defecto demostrado de V5. No se hereda la aprobación del candidato CI anterior. Sólo cuatro archivos nuevos de validación/documentación, sin tocar la migración ni HEAD.


### L10 — Cierre nativo RDD y validación con reservas (2026-11-03)

Respuesta específica del usuario: "granted". RDD `review-1bd0996d659e42a6`, candidato `sha256:7ebbd69fe290a319266e51a194fc249a234f31b0a002fbdf4163c2715c411e0f`, árbol `552608dfd32ee29733a5a92e7092584fb9dbf994`: cuatro capturas nativas concurrentes, riesgo/resiliencia/legibilidad/confiabilidad, resultado aprobado sin bloqueantes. Reconocimiento exacto confirmado `action=acknowledged`, `authority=burned`; no se abre corrección nativa ni se reejecutan revisores.

Advertencia informativa R2-001 (legibilidad, plan:2): encabezado y estados O2/O3 desactualizados. Se concilian ahora como registro documental de cierre, sin cambiar pruebas, migración, requisitos o procedimiento ejecutable. Este delta posterior no se atribuye automáticamente a la aprobación anterior: se evalúa separadamente. A1–A10/S1–S10 completados para el ensayo local con reservas, sin afirmar validación de producción. Propuesta técnica: PR #10 puede avanzar a revisión formal por decisión humana; no se modifica su estado ni se publica esta unidad. V5/HEAD intactos; no commit, push, merge o despliegue.

Assess del cierre exclusivamente documental (informe + plan, fuentes ejecutables ya aprobadas excluidas de esta selección): risk=passive, non_executable_only, review_due=false/reason=passive. Se conserva la aprobación del candidato ejecutable sin repetir revisión; no se atribuye aquella aprobación a bytes documentales nuevos.


### L11 — Publicación y excepción de tamaño autorizadas (2026-11-03)

El usuario autorizó inspeccionar/publicar exclusivamente estos cuatro archivos, commit en español, actualizar descripción del PR #10, push sin force y Ready for Review sólo después de CI verde sobre el nuevo HEAD; no merge ni despliegue. Ante la solicitud explícita de excepción de tamaño para esta unidad (4 archivos/837 líneas) y PR acumulado (32 archivos/2576 líneas), respondió textualmente "si autorizo". Se acepta size:exception para mantener juntos pruebas, soporte, informe y trazabilidad, más este registro documental mínimo; no se divide por capas ni amplía funcionalidad. Esta autorización de entrega posterior no modifica las restricciones del ensayo ni autoriza producción/Supabase. Los estados anteriores sin publicación son históricos. Fuentes ejecutables idénticas al RDD; este delta documental se evalúa antes del commit. Respaldos, JSON de ejecución, logs y XML permanecen fuera de Git; sólo fixtures ficticios en los tests. Incorporar la unidad al PR no autoriza aplicar V5 en una base compartida.
