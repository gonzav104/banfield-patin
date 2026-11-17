# FE-05 / U9 — Protección de concurrencia de los datos deportivos por temporada

Objetivo: protección optimista **por fila** de `deportista_temporada` (solo backend), para que un `PUT` armado con una lectura vieja no pise un cambio confirmado por otro administrador.
Rama: `feat/fe05-u9-concurrencia-datos-deportivos`; base `275e7b3f38f5493a324cde58d991c0490275cb6b` (`main`, merge del PR #32).
Entrega: tres commits **locales** (autorizados); **sin push, PR, merge ni despliegues** (no autorizados). Runner: `./mvnw -B -o test` (normal) y `./mvnw -B -o test -Pdb-tests` (PostgreSQL 17 descartable vía Testcontainers).

## Specs

- S1 (alcance): «Autorizo comenzar FE-05/U9: protección de concurrencia de los datos deportivos por temporada. La autorización comprende análisis, implementación local y pruebas. No incluye commits, publicación ni despliegues.»
- S2 (migración y entidad): migración **nueva** V10 (no modificar las existentes; no ejecutarla contra bases reales ni Supabase): columna `version BIGINT NOT NULL DEFAULT 0` con `CHECK (version >= 0)` y `@Version` en `DeportistaTemporada`.
- S3 (contrato de lectura): el `GET` individual y el historial devuelven `version` como **cadena decimal**.
- S4 (contrato de edición): el `PUT` exige `versionEsperada` (cadena decimal, 0..máximo de BIGINT). Ausente, nula o malformada → `400` saneado sin eco; obsoleta → `409 EDICION_DESACTUALIZADA` **aunque los valores coincidan o no haya cambio efectivo**; propiedades inesperadas rechazadas.
- S5 (alta): el `POST` conserva su semántica (no exige ni acepta revisión).
- S6 (plantel): decidir si expone la revisión; elegir el contrato mínimo y justificarlo.
- S7 (concurrencia): conservar el bloqueo de fila `FOR NO KEY UPDATE` y el `lock_timeout` (`409 CONFLICTO_CONCURRENCIA`), los códigos de unicidad existentes, sin escrituras parciales ni auditorías de éxito en los fallos, aislamiento por escuela, y temporadas inactivas / filas históricas editables como hasta ahora.
- S8 (pruebas): unitarias, WebMvc y PostgreSQL real (migración V10 sobre filas existentes; un único ganador con la misma revisión; segundo `PUT` obsoleto; obsoleto con datos iguales; filas y temporadas independientes; locks y timeouts; conflictos de número de corredor; auditoría; aislamiento entre dos escuelas; validación de `versionEsperada` incl. límite BIGINT; propiedades inesperadas; compatibilidad de `GET`/`POST`), mutaciones cuando sea viable, suite normal y `db`, contrato OpenAPI. «Si Docker no está disponible, no inicies servicios del host sin autorización. Documentá qué pruebas pudieron ejecutarse y cuáles quedaron pendientes de CI.»
- S9 (riesgo y despliegue): evaluar RDD temprano; «Si RDD requiere consentimiento explícito adicional, detenerse y solicitarlo. Si RDD termina en stop, respetarlo.» Documentar compatibilidad con clientes existentes, secuencia segura de despliegue y mezcla de escritores viejos/nuevos. «No desplegar nada ni habilitar cambios en producción.»
- S10 (límites): «Sin commits, push, PR ni merge. Sin despliegues ni migraciones remotas. Sin frontend ni modificaciones de E2E de interfaz. Sin empezar U10. Sin modificar workflows de CI. Sin corregir el smoke de familias. Sin resolver otros mantenimientos. Sin modificar otros worktrees ni el checkout original.»

## Tasks

- [x] T1 (S2) Migración V10 + `@Version` + `FilaDeportistaTemporada`/repositorio — inline — commit 1 `5556d705388ef4189fd994ddef67c289407049e5`.
- [x] T2 (S3, S5, S6) DTOs: `version` en la respuesta; plantel sin cambios — inline — commit 1.
- [x] T3 (S4, S7) `versionEsperada` + `exigirRevision` bajo el bloqueo, antes de la unicidad — inline — commit 1.
- [x] T4 (S8) Pruebas unitarias, WebMvc, OpenAPI y `db`; mutaciones — inline — commit 1 (unitarias, WebMvc, OpenAPI y `db` existentes adaptadas) y commit 2 `69d73d8536a635496a850523d23c483801909299` (`db` nuevas).
- [x] T5 (S9) Documentación (`TEMPORADAS.md`, `SEGURIDAD.md`) y este documento — inline — commit 3 (`docs(temporadas): documentar revisión por fila`, el `HEAD` de la rama; su SHA no puede constar dentro de sí mismo: ver `git log`).
- [x] T6 (S9) RDD: ver «Estado de RDD» — verificador independiente sin bloqueantes; revisión nativa aprobada y reconocida.

## Diagnóstico (S1)

`DeportistaTemporadaAdminService.actualizar` ya serializaba escritores con `SELECT … FOR NO KEY UPDATE` sobre la fila y la releía bajo el bloqueo, pero el `PUT` **no llevaba revisión**: un `PUT` armado con una lectura vieja se aplicaba sobre el estado ya confirmado y la **última escritura ganaba**, perdiendo en silencio el cambio del otro administrador (limitación D6, que una prueba `db` documentaba como aceptada). El bloqueo evita carreras de bases de datos, no la edición sobre datos obsoletos.

## Diseño elegido y justificación

1. **Revisión por fila** (`deportista_temporada.version`, `@Version`), no por deportista ni por temporada: dos filas de un mismo deportista o dos deportistas de una misma temporada son independientes, y la `version` de la ficha permanente (U6) es otra cosa.
2. **Comparación bajo el bloqueo**: el `PUT` bloquea la fila, la lee y compara `versionEsperada` con la vigente. El perdedor de una carrera espera el bloqueo, relee lo que confirmó el ganador y recibe `409 EDICION_DESACTUALIZADA`. La comparación va **antes** de la unicidad del número y de cualquier escritura; el orden es `404` → revisión → unicidad.
3. **Red de seguridad**: `@Version` agrega `WHERE version = ?` al `UPDATE`; si alguna vez fallara, el manejador global ya responde `409 EDICION_DESACTUALIZADA` (`ObjectOptimisticLockingFailureException`).
4. **Obsoleta se rechaza aunque no haya cambio efectivo o los valores coincidan** (mismo criterio que U1/U4/U6): no se acepta en silencio una edición armada sobre una lectura vieja. Con la revisión vigente y sin cambio efectivo no se escribe, no se audita y la revisión no se mueve.
5. **Plantel sin `version`** (S6, contrato mínimo): el plantel es un listado paginado de lectura y no es el origen de una edición; para editar se lee la fila con el `GET` individual, que la incluye. Evita sumar un campo a un listado que nadie necesita y mantiene el contrato del plantel intacto. U10 puede pedirla si lo decide.
6. El `lock_timeout` y `409 CONFLICTO_CONCURRENCIA` no cambian: un bloqueo que vence sigue siendo un conflicto de espera, distinto de «edición desactualizada».

## Contratos

| | Antes | Ahora |
| --- | --- | --- |
| `GET` fila / historial | sin revisión | + `version` (cadena decimal) |
| `POST` alta | sin revisión | igual; la respuesta devuelve `version: "0"`; no acepta `versionEsperada` (`400 SOLICITUD_INVALIDA`) |
| `PUT` fila | sin revisión; gana la última escritura | exige `versionEsperada` (cadena); obsoleta → `409 EDICION_DESACTUALIZADA`; ausente/nula/malformada → `400` |
| Plantel | sin revisión | igual |
| Errores existentes | `409 NUMERO_CORREDOR_DUPLICADO`, `CONFLICTO_CONCURRENCIA`, `404` | iguales |

## Compatibilidad y despliegue (S9)

- **Clientes existentes**: ninguno llama al `PUT` de la fila (el frontend y el seed E2E solo usan el `POST` del alta y lecturas). El único consumidor que cambia es U10, que aún no existe. El `PUT` sin `versionEsperada` pasa a ser `400`: es un cambio de contrato **intencional y aditivo para los lectores** (campo nuevo en respuestas) pero **breaking para un escritor viejo** del `PUT`.
- **Secuencia segura**: (1) aplicar V10 (aditiva: `ADD COLUMN … DEFAULT 0` + `CHECK`; las filas existentes quedan en `0`; el backend viejo la ignora); (2) desplegar el backend nuevo, que exige la revisión; (3) recién después, un frontend (U10) que lea `version` y la reenvíe. No desplegar el backend nuevo antes que V10 (Hibernate fallaría al leer `version`).
- **Mezcla de escritores viejos y nuevos**: entre el paso 1 y el 2, un backend viejo escribe filas sin tocar `version` (queda en `0`); no rompe nada, pero esas escrituras no avanzan la revisión, así que durante esa ventana un cliente nuevo no estaría protegido. Es una ventana corta y sin clientes de `PUT` hoy; se evita desplegando V10 y backend en la misma operación. No hay escritores no-JPA de `deportista_temporada` fuera del servicio.
- **Rollback**: volver al backend anterior es seguro con V10 aplicada (columna con `DEFAULT`). Revertir V10 no es necesario ni recomendable.
- No se desplegó nada ni se ejecutó migración alguna contra bases reales.

## Commits locales

| # | SHA | Mensaje | Líneas (+/−) |
| --- | --- | --- | --- |
| 1 | `5556d705388ef4189fd994ddef67c289407049e5` | `feat(temporadas): versionar datos deportivos por temporada` | 17 archivos, +472 −72 (544); producción +63 −15 |
| 2 | `69d73d8536a635496a850523d23c483801909299` | `test(temporadas): cubrir revisión y migración V10 contra PostgreSQL` | 2 archivos, +327 −0 |
| 3 | `HEAD` | `docs(temporadas): documentar revisión por fila` | `TEMPORADAS.md`, `SEGURIDAD.md` y este documento |

Organización: las tres pruebas `db` existentes (`DeportistaTemporadaConcurrenciaDbTest`, `DeportistaTemporadaEdicionDbTest`, `TemporadasLockTimeoutDbTest`) construían el `PUT` sin revisión y **no compilan** con el nuevo DTO, así que van en el commit 1 para que sea verificable por sí solo; ahí también se invirtió la prueba D6 y se reescribieron las dos de serialización (un único ganador, `409` al obsoleto) porque viven en esos mismos archivos. El commit 2 agrega solo las clases `db` nuevas. Sin amend, squash ni rebase; sin `Co-Authored-By`.

## Verificación observada

- **Por commit, sobre un export limpio (`git archive`) del commit en un directorio temporal:**
  - Commit 1: suite normal **1276, 0 fallos**; `db` de las tres clases adaptadas **16, 0 fallos** (8 + 6 + 2).
  - Commit 2: suite normal **1276, 0 fallos**; suite `db` completa **657, 0 fallos**. La primera pasada dio 1 error de infraestructura (`V5OperacionDbTest`, `Connection refused` al contenedor, ajena a U9); esa clase relanzada sola dio 11/11 y la suite completa repetida dio 657/657. No se tocó código.
- Suite normal sobre el candidato: `./mvnw -B -o test` → **1276 pruebas, 0 fallos** (base 1254).
- Pruebas del cambio (unidad/WebMvc/OpenAPI): `DeportistaTemporadaAdminServiceTest` 31, `DeportistaTemporadaAdminControllerWebMvcTest` 19, `DeportistaTemporadaSolicitudTest` 8, `OpenApiVersionDeportistaTemporadaTest` 3.
- Pruebas `db` del cambio (PostgreSQL 17 descartable, **ejecutadas**): `V10VersionDeportistaTemporadaDbTest` 1, `DeportistaTemporadaVersionDbTest` 10, `DeportistaTemporadaConcurrenciaDbTest` 8, `DeportistaTemporadaEdicionDbTest` 6, `TemporadasLockTimeoutDbTest` 2 → 27, 0 fallos.
- **Mutaciones** (todas fallan las pruebas y se restauraron): sin comparar la revisión (unit 4 fallos; db 8); comparación invertida (3); respuesta sin la revisión real (1); revisión opcional sin `@NotNull` (3); sin `@Version` (db 8 + 1 error); sin el `CHECK` de V10 (db 2).
- Dos guardas existentes se adaptaron porque verifican el contrato: `V2MigracionEstaticaTest` (el directorio ahora contiene V1–V10; `V10` ordena antes de `V2` lexicográficamente) y el verificador de códigos de `OpenApiConfig` (los dos códigos `409` se documentan como entradas separadas).
- La prueba D6 («gana la última escritura») se **invirtió**: ahora afirma que el `PUT` obsoleto recibe `409` y el cambio posterior no se pierde.

## Publicación (propuesta; **no se publicó nada**)

- Origen del «400»: no hay ninguna restricción automática. El repositorio (`.github/workflows/*`, scripts) no tiene un control de tamaño y se integraron PR mayores (#27 ≈1.3k, #29 ≈3.1k, #30 ≈2.9k líneas). `docs/ODD.md` lo define como «unidades de trabajo de ~400 líneas autoradas» (recomendación de planificación); la skill `chained-pr` del proceso de usuario sí lo formula como regla de división («dividir los PR de más de 400 líneas salvo `size:exception` aceptado por un maintainer») acotada a **una** pasada honesta de división, y marca que el presupuesto condiciona cómo se corta el trabajo, nunca el código. Con RDD solo se vincula porque el candidato nativo es un commit o una porción de PR contra el último límite revisado.
- Tamaño real: commit 1 = 544, commit 2 = 327, commit 3 = docs; total ≈ 870 líneas de código y pruebas más documentación. Solo ≈ 78 líneas son de producción; el resto son pruebas y documentación.
- Una pasada de división: ningún corte cohesivo deja todas las porciones en ≤ 400 (el commit 1 solo ya tiene 544 y no se puede dividir sin separar el DTO del servicio y de las pruebas que lo construyen).
- **Propuesta recomendada: un único PR de tres commits contra `main`, con solicitud explícita de `size:exception`** al maintainer: (1) V10, `@Version`, contrato y guarda son una unidad (el contrato sin migración no arranca y la migración sin la comparación no protege); (2) el árbol final es exactamente el revisado por RDD (revisión aprobada y reconocida sobre ese candidato), sin pedir revisiones sobre porciones intermedias que nunca existieron; (3) CI verde con el conjunto completo; (4) no hay estados intermedios en `main`.
- Alternativa si el maintainer rechaza la excepción: dos PR apilados, **A** = V10 + `@Version` + `version` en lecturas (aditivo y compatible, sin exigir nada) y **B** = `versionEsperada` obligatoria + pruebas + docs. Se integran en ese orden; A no empeora la pérdida silenciosa respecto de la base y B la cierra. B superaría igualmente 400 y necesitaría su `size:exception`, y ambos requerirían volver a ejecutar RDD sobre cada porción (autoridad nueva por candidato) y rearmar los commits; por eso no es la recomendada. No se hizo.

## Riesgos de despliegue

- **V10 debe aplicarse antes de ejecutar el backend nuevo**: el backend nuevo lee `version` y falla si la columna no existe.
- **Los `PUT` antiguos sin `versionEsperada` dejan de ser compatibles** (`400`). Hoy ningún cliente del repositorio lo llama.
- **El frontend de U10 debe consumir `version` y reenviarla** como `versionEsperada` (cadena decimal).
- **Convivencia de escritores viejos y nuevos**: un backend viejo escribe la fila sin incrementar la revisión (`version` permanece), de modo que durante esa ventana un cliente nuevo no queda protegido frente a esos escritores. Mitigación: aplicar V10 y el backend nuevo en una misma operación y no mezclar réplicas.
- **No existe autorización de despliegue ni de migraciones remotas**: no se aplicó V10 contra Supabase ni ninguna base real.
- Advisories conservados, sin ampliar el cambio: (a) `VersionFamiliaDecimal` acepta ceros a la izquierda (`"00"`); (b) la clave `versionEsperada` repetida en el JSON resuelve con la última (comportamiento por defecto de Jackson en todo el proyecto); (c) overflow teórico de `version` tras 2^63 ediciones de una misma fila (la base lo rechaza con `DataIntegrityViolationException`); (d) con `lock-timeout` activo un conflicto de número entre filas distintas puede salir como `CONFLICTO_CONCURRENCIA` en vez de `NUMERO_CORREDOR_DUPLICADO` (ya era así antes del cambio).

## Estado de RDD

Evaluación temprana: nativa `medium`, escalada a `high` por los ítems 1 (migración / persistencia), 3 (contrato consumido: `PUT` pasa a exigir un campo) y 4 (concurrencia). Resultado: (1) verificador independiente en copia aislada, sin bloqueantes (2 advisories heredados de `VersionFamiliaDecimal`: acepta ceros a la izquierda y la clave JSON duplicada gana la última; un overflow de `version` tras 2^63 ediciones, fuera de dominio). (2) Consentimiento del candidato **otorgado por el usuario** (candidato de 22 archivos / 958 líneas; lentes risk, resilience, reliability). (3) Revisión nativa: tres lentes **sin hallazgos**, aprobada y reconocida (autoridad quemada, lineage `review-05a1da968cb346d6`). La revisión no autoriza entrega. **Árbol revisado**: el candidato tenía el árbol `56c6b7b70df935c4bfa49e4f5a04fc90b4049b53`; el `HEAD` final difiere de él solo por este documento (cambio documental posterior autorizado). Los commits 1 y 2 contienen el código revisado sin cambios; la aprobación sigue vigente para ese código. Push, PR y despliegue siguen sin autorizar.

## Log

- L1 (2026-11-16, usuario): pedido original — «Continuamos con Patín Carrera Banfield San Pedro. El PR #32 quedó integrado en main mediante merge commit `275e7b3f38f5493a324cde58d991c0490275cb6b`. El CI posterior pasó: 754 pruebas frontend y 133 E2E reales. **Autorizo comenzar FE-05/U9: protección de concurrencia de los datos deportivos por temporada.** La autorización comprende análisis, implementación local y pruebas. No incluye commits, publicación ni despliegues.»
- Hallazgo: inicialmente se consideró que no había Docker; al verificarlo, el daemon respondía y la imagen `postgres:17-alpine` estaba local, así que las pruebas `db` **sí se ejecutaron** (Testcontainers, contenedor propio y descartable; no se tocaron los contenedores de otros proyectos).
- Riesgo: ítems 1, 3 y 4 (migración, contrato del `PUT`, concurrencia).
- L2 (2026-11-17, usuario): «Autorizo preparar y crear los tres commits locales de **FE-05/U9 — Concurrencia de datos deportivos por temporada**. No autorizo push, apertura de PR, merge ni despliegues.» Se crearon los commits 1 y 2 y este (commit 3). Las suites se ejecutaron en un export aislado de los commits 1 y 2; el commit 3 es solo documental y no ejecuta suites (se verificó que el árbol final difiere del revisado únicamente en este documento).
- Incidente de proceso: durante la investigación del límite de 400 líneas se ejecutó una **lectura remota** (`gh pr list`, solo metadatos de PR ya integrados) pese a la instrucción «no realizar operaciones remotas». No escribió nada remoto; se declaró al usuario y no se repitió.
