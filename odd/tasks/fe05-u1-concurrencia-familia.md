# FE-05 / U1 — Concurrencia optimista de Familia
Objetivo: proteger solo el detalle, reemplazo y estado de Familia. Base: `019620ffae90add62fc18c4781d2c2506689cd08`; rama `feat/fe05-concurrencia-familia`.
Entrega: ask-on-risk, sin commits hasta autorización. Runner: Maven/JUnit, WebMvc y PostgreSQL Testcontainers (`-Pdb-tests`).

## Specs
- S1: «Implementar exclusivamente **FE-05 / U1: protección optimista de Familia en el backend**». «No implementar todavía las demás unidades de FE-05.»
- S2: «Exponer `version` en la representación de detalle.» «Exigir `versionEsperada` en las operaciones de modificación y cambio de estado.» «Representar las revisiones en JSON como cadenas decimales no negativas.»
- S3: «Rechazar revisiones ausentes o inválidas con HTTP 400.» «Rechazar revisiones obsoletas con HTTP 409 y código `EDICION_DESACTUALIZADA`.» «No devolver datos personales ni versiones vigentes en los errores de edición obsoleta.»
- S4: «Una revisión obsoleta debe rechazarse incluso si el contenido solicitado coincide con el estado vigente.» «Cuando la revisión sea válida y la operación no produzca ningún cambio real, preservar la idempotencia sin incrementar la revisión ni generar auditoría innecesaria.» «La verificación y la escritura deben ser atómicas.»
- S5: «Utilizar una migración Flyway nueva, posterior a las existentes.» «Garantizar rollback completo ante conflictos.» «También adaptar la creación de familias para que los registros nuevos tengan una revisión válida.»
- S6: «Preservar estrictamente:» autorización ADMIN, MFA completo, escuelas, CSRF, cookies/sesiones, validaciones, auditoría transaccional y contratos minimizados FAMILIA (lista literal completa en L1). «Crear una familia no debe crear automáticamente una cuenta de usuario.» «Desactivar una familia no debe provocar desactivaciones en cascada.»
- S7: «Revisar los consumidores actuales de los endpoints familiares, incluidos los flujos administrativos y de invitaciones.» «Usar datos sintéticos y bases de prueba descartables.» «Ejecutar primero las pruebas focalizadas y después las regresiones pertinentes.»
- S8: «Toda implementación, documentación y prueba nueva debe realizarse dentro del worktree de U1.» «Antes de hacer commits locales, presentar el estado de las verificaciones y solicitar mi autorización.»

## Tasks
- T1 [S1–S8] inline — completado: contratos/consumidores y RED; sin motivo Writer (un único comportamiento).
- T2 [S2–S7] inline — completado con pruebas focales y regresiones: migración V7, versión JPA, comandos protegidos, compatibilidad y documentación.
- T3 [S3–S8] delegado para verificación independiente — verificación independiente aprobada; RDD pendiente de consentimiento: pruebas focales/regresiones, revisión aislada por riesgos 1/2/3/4; RDD según consentimiento. Sin commit.

## Log
### L1 — Pedido original (2026-11-14)
Estamos continuando el proyecto **Patín Carrera Banfield San Pedro**, repositorio `~/banfield-patin`.

Trabajamos con ODD como metodología predeterminada, Engram para preservar contexto y RDD según la configuración vigente.

## Objetivo

Implementar exclusivamente **FE-05 / U1: protección optimista de Familia en el backend**, siguiendo el diseño técnico de FE-05 previamente aprobado.

Esta unidad debe establecer un mecanismo seguro y verificable para impedir que dos administradores sobrescriban cambios concurrentes de una misma familia.

No implementar todavía las demás unidades de FE-05.

## 1. Preparación y aislamiento

La última integración confirmada es el PR #19, merge commit:

`019620ffae90add62fc18c4781d2c2506689cd08`

Antes de modificar archivos:

1. Inspeccionar las referencias Git disponibles, el estado de los worktrees y la documentación relevante.
2. Confirmar que `origin/main` local apunta al commit esperado. No ejecutar fetch automáticamente.
3. Crear un worktree independiente, basado en ese commit, utilizando preferentemente:
   - Rama: `feat/fe05-concurrencia-familia`
   - Worktree: `~/banfield-patin-worktrees/fe05-concurrencia-familia`
4. Si la rama o el directorio ya existen, inspeccionarlos y detenerte ante cualquier riesgo de sobrescritura. No utilizar opciones destructivas.
5. Preservar completamente el checkout original, sus cambios sin confirmar y los demás worktrees.

Toda implementación, documentación y prueba nueva debe realizarse dentro del worktree de U1.

Si las referencias o condiciones de aislamiento no coinciden con lo esperado, detenerte e informarme antes de modificar archivos.

## 2. Alcance funcional

Implementar protección optimista para las operaciones existentes:

- `GET /api/admin/familias/{id}`
- `PUT /api/admin/familias/{id}`
- `POST /api/admin/familias/{id}/activar`
- `POST /api/admin/familias/{id}/desactivar`

También adaptar la creación de familias para que los registros nuevos tengan una revisión válida.

### Contrato de concurrencia

Seguir el diseño aprobado:

- Incorporar una versión persistida para Familia.
- Exponer `version` en la representación de detalle.
- Exigir `versionEsperada` en las operaciones de modificación y cambio de estado.
- Representar las revisiones en JSON como cadenas decimales no negativas.
- Rechazar revisiones ausentes o inválidas con HTTP 400.
- Rechazar revisiones obsoletas con HTTP 409 y código `EDICION_DESACTUALIZADA`.
- Conservar la distinción respecto de `CONFLICTO_CONCURRENCIA` y los conflictos de negocio existentes.
- No devolver datos personales ni versiones vigentes en los errores de edición obsoleta.
- No permitir guardados forzados ni escrituras administrativas que eludan el control.

Una revisión obsoleta debe rechazarse incluso si el contenido solicitado coincide con el estado vigente.

Cuando la revisión sea válida y la operación no produzca ningún cambio real, preservar la idempotencia sin incrementar la revisión ni generar auditoría innecesaria.

No utilizar una simple comparación previa en memoria como garantía de concurrencia. La verificación y la escritura deben ser atómicas.

## 3. Persistencia y compatibilidad

- Utilizar una migración Flyway nueva, posterior a las existentes. Verificar primero la última migración real.
- No editar migraciones aplicadas.
- Utilizar una columna de versión compatible con registros existentes, inicializada sin alterar información personal o relaciones.
- Evaluar y aplicar `@Version` según el modelo real de persistencia.
- Garantizar que la auditoría y los incrementos de versión ocurran dentro de la misma transacción.
- Garantizar rollback completo ante conflictos.
- Revisar los consumidores actuales de los endpoints familiares, incluidos los flujos administrativos y de invitaciones.
- Preservar la compatibilidad de lectura y adaptar los consumidores conocidos antes de exigir el nuevo contrato de escritura.
- No incorporar infraestructura adicional ni mecanismos alternativos de versionado.

Si identificás un consumidor incompatible que no pueda adaptarse dentro del alcance de U1, detenerte y documentar el bloqueo.

## 4. Seguridad y comportamiento existente

Preservar estrictamente:

- Autorización ADMIN.
- MFA administrativo completo.
- Separación entre escuelas.
- Protección CSRF.
- Cookies y sesiones existentes.
- Validaciones y restricciones de negocio.
- Auditoría transaccional.
- Contratos minimizados del portal FAMILIA.

Crear una familia no debe crear automáticamente una cuenta de usuario.

Desactivar una familia no debe provocar desactivaciones en cascada.

No modificar los flujos familiares de FE-01 a FE-04 salvo que una adaptación estrictamente necesaria de compatibilidad lo exija.

## 5. Metodología y pruebas

Aplicar ODD, sin introducir SDD.

Seguir RED → GREEN cuando existan pruebas deterministas aplicables.

Implementar pruebas focalizadas que cubran:

1. Lectura de la versión de Familia.
2. Actualización con revisión vigente.
3. Rechazo de revisión ausente, inválida u obsoleta.
4. Dos escrituras concurrentes con la misma versión: solo una puede confirmar el cambio.
5. Ausencia de escrituras, incrementos y auditoría residual ante conflictos.
6. Operaciones sin cambios efectivos.
7. Activación y desactivación concurrentes.
8. Preservación de permisos ADMIN, MFA, CSRF y aislamiento entre escuelas.
9. Compatibilidad con consumidores existentes.
10. Migración y comportamiento con PostgreSQL real.

Usar datos sintéticos y bases de prueba descartables.

No modificar ni reiniciar contenedores, bases de datos o servicios pertenecientes a otros proyectos.

Ejecutar primero las pruebas focalizadas y después las regresiones pertinentes. Informar explícitamente qué suites se ejecutaron y cuáles no.

No afirmar cobertura que no haya sido comprobada.

## 6. Disciplina de implementación

- Mantener el cambio acotado a U1.
- Reutilizar patrones y componentes existentes.
- Evitar refactorizaciones no relacionadas.
- Documentar los contratos modificados y los criterios de concurrencia.
- Mantener las evidencias y documentación ODD correspondientes.
- Respetar RDD, sus evaluaciones de riesgo y los consentimientos requeridos.
- No desactivar controles de revisión o seguridad para facilitar la implementación.
- No incluir secretos, DNI reales ni datos personales en logs, documentación o artefactos de pruebas.
- Mantener mensajes de commit en español y sin `Co-Authored-By`.

No hacer push, abrir PR, realizar merge ni alterar protecciones de ramas.

No ejecutar operaciones destructivas. Antes de hacer commits locales, presentar el estado de las verificaciones y solicitar mi autorización.

## 7. Entregable

Al finalizar la implementación, informarme:

1. Rama, worktree y commit base utilizados.
2. Archivos modificados y finalidad de los cambios.
3. Contratos HTTP definitivos.
4. Migración creada y compatibilidad de datos existentes.
5. Pruebas ejecutadas, resultados y evidencia relevante.
6. Resultado de las comprobaciones de seguridad.
7. Riesgos residuales y limitaciones conocidas.
8. Estado de ODD y RDD.
9. Resultado de `git status`.
10. Recomendación sobre si U1 está lista para revisión e integración.

No comenzar U2 ni ninguna otra unidad de FE-05.

**Objetivo final:** completar U1 con una implementación pequeña, segura, probada y revisable, sin modificar el checkout original ni ejecutar operaciones remotas.

### L2 — Preparación
`origin/main` local coincide con la base. Rama y directorio no existían. Worktree creado con autorización de sandbox; original y otros worktrees conservados. Última migración V6. RDD on por defecto. Consumo productivo de escritura familiar: solo controller; frontend no usa PUT ni estado de Familia. Invitaciones crean mediante Familia.crear; listado legacy intacto.
Diseño: @Version para Familia y validación optimista también en no-op, sin bloquear durante interacción humana. JSON estricto local, DTO de alta conservado y DTOs separados de edición/estado. Estimación preliminar 600–900 líneas autoradas; resolver división de entrega antes de cualquier commit, no pedir excepción anticipada.
Risk: items 1 (migración), 2 (autorización preservada), 3 (contrato de escritura), 4 (concurrencia).

### L3 — RED observado
Dos pruebas WebMvc fallaron antes de implementar: detalle sin $.version y PUT sin version devolvía 200 en lugar de 400. Log externo /tmp/fe05-u1-red.log.

### L4 — Implementación y primeras pruebas
46 pruebas focales API/servicio/OpenAPI verdes (fe05-u1-api2.log). V7 agrega solo version de Familia. Alta/invitaciones siguen sin versionEsperada y los listados/portal no cambian. No hay consumidores frontend afectados.
Prueba focal DB intentada: 24 errores de arranque antes de ejecutar escenarios porque no existe /var/run/docker.sock; el ejecutable Docker indica integración WSL no disponible. No se cambia entorno ni se toca ningún contenedor. Pruebas PostgreSQL/migración/concurrencia NO demostradas. Nueva prueba de estados opuestos añadida después, también pendiente.
Mecanismo: @Version + lock OPTIMISTIC (también no-op); no mecanismo de versionado alternativo ni guardado forzado. Riesgo de contrato para clientes API externos: deben enviar versionEsperada; no mezclar escritores antiguos/nuevos en despliegue.

### L5 — Regresión normal y corrección acotada
Primera suite normal: 1220 pruebas, 2 fallos, 0 errores. Fallos propios: inventario global V2MigracionEstaticaTest todavía enumeraba V1–V6; ejemplos OpenAPI nuevos violaban la política sin ejemplos. Se amplía solo inventario a V7 (sin cambiar tests de migraciones históricas con target) y se quitan ejemplos de ambos DTO. Pendiente reejecución focal y suite. Compilación pasada, paquete no alcanzado en esa corrida.
RDD: modo on. assess inicial high/unassessable por archivos nuevos sin selección. STATUS preflight solo inventarió archivos; no START, linaje ni revisión iniciada. Próximo paso nativo pendiente: selección de no rastreados. No se consiente nada en nombre del usuario.

### L6 — Estado de entrega parcial, sin commits
Corrección focal: 59/59 verdes. Suite normal final `./mvnw -o verify`: 1220 pruebas, 0 fallos, 0 errores, 0 omitidas; JAR generado (log externo /tmp/fe05-u1-normal-final.log).
Verificador independiente en copia nueva /tmp: 59/59 focales verdes con ejecución escalada (primer intento restringido falló por attach Mockito, no por assertions). Sin defectos nuevos reproducibles; S4/S5/S7 parciales por PostgreSQL no disponible. Logs preservados /tmp/fe05-u1-independent.log y /tmp/fe05-u1-independent-sandbox.log; copia temporal eliminada mediante aprobación explícita, sin tocar worktrees.
RDD on, assess válido con selección explícita de los 8 archivos nuevos: high, review_due=true/high_risk, 22 paths y 908 líneas en ese snapshot. Sin START, linaje, consentimiento ni recibo. La revisión queda pendiente después de restaurar la prueba funcional obligatoria; no se desactivó ningún control.
Prueba DB focal original falló antes de escenarios (24 errores de inicialización); no se repitió ni se ejecutó suite DB completa, E2E navegador, Supabase o ClamAV. Las nuevas pruebas DB se compilan, pero migración/atomicidad/rollback/escuelas en base real NO están comprobados.
Necesita intervención: Docker accesible desde esta distro WSL (socket local ausente). No instalar, reconfigurar ni reiniciar servicios automáticamente. Tras restaurarlo, ejecutar las pruebas DB focales, regresión pertinente y completar verificación/RDD con consentimiento. Revalidar candidato si cambia cualquier fuente.
Sin commits/staging/push/PR/merge. La estrategia de división de PR/commits se resolverá antes de pedir autorización para commit; no se asumió excepción de tamaño ni se inició U2. Original y demás worktrees no modificados.

### L7 — Reanudación con Docker (2026-11-14)
Mensajes del usuario: «docker ya esta habilitado»; «ya esta, fijate ahora». Primer reintento todavía sin socket; segundo: cliente y servidor Docker 29.9.3 accesibles desde la sesión escalada. No se cambia configuración ni se reinician servicios.
Focal PostgreSQL: 25/25 verdes, /tmp/fe05-u1-db-resumed.log. Se comprueban migración V7, carreras, no-op concurrente, rollback completo, compatibilidad de invitaciones y ausencia de cascada.
Regresión DB inicial: 616 pruebas, 2 fallos, 0 errores/omitidas; ambos en V6OperacionCargaDbTest (expected6/actual7). Su fixture y escenario limpio usaban target latest pero verifican V6. Se acotan esos3 targets a6, sin editar migraciones ni cambiar las aserciones históricas. Corrección estrictamente necesaria de compatibilidad con V7; pendiente reejecución focal y suite. Log /tmp/fe05-u1-db-regression.log.

### L8 — Prueba funcional completa tras restaurar Docker
Corrección de los2 tests históricos V6: 2/2 verdes (/tmp/fe05-u1-v6-correction.log). Regresión DB final `./mvnw -o verify -Pdb-tests`:616/0/0/0, exit0, JAR generado. Suite normal final reejecutada `./mvnw -o verify`:1220/0/0/0, exit0, JAR generado. Sin advertencias Maven [WARNING]. Logs /tmp/fe05-u1-db-regression-final.log y /tmp/fe05-u1-normal-resumed-final.log.
El bloqueo Docker queda resuelto. Pendiente recheck independiente limitado a S4/S5/S7 antes incompletos y al fixture histórico V6, luego consentimiento/revisión RDD. Ningún commit autorizado ni realizado; no U2. No se modificaron contenedores preexistentes.

### L9 — Recheck independiente PostgreSQL aprobado
Recheck limitado a la prueba pendiente S4/S5/S7 y preservación S6:27/0/0/0, exit0, en copia nueva del candidato fuera del worktree. Se comprueban predicado SQL por version, ganador único, no-op al commit, rollback/auditoría, V6→V7, compatibilidad, escuela y ausencia de cascadas. Log /tmp/fe05-u1-independent-db-recheck.log. Sin defectos nuevos ni cambios de fuentes.
Fuente final: solo Familia/U1, migraciones V1–V6 intactas; no frontend ni U2. Regresiones finales616 DB y1220 normales verdes. Verificación funcional completa, revisión RDD high pendiente de consentimiento para el candidato actual. Sin commit ni staging. Este registro se cierra antes de congelar el candidato de RDD; el resultado posterior se conservará en Engram sin alterar sus bytes durante la revisión.
