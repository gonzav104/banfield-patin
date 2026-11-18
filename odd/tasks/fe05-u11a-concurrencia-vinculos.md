# FE-05 / U11-A — Protección de concurrencia de los vínculos familia-deportista

Objetivo: revisión optimista **agregada por deportista** (`deportista.version_vinculos`) que protege vincular, revocar y cambiar el principal contra operaciones preparadas sobre información desactualizada. Solo backend, más la adaptación de la semilla E2E que consume el contrato.
Rama: `feat/fe05-u11a-concurrencia-vinculos`; base `a0d7a16a7edac0734d5bee634cda7bb8da705c4f` (`main`, merge del PR #36). Entrega: **sin commits, push, PR, merge ni despliegues** (no autorizados).
Runner: `cd backend && ./mvnw -B -o clean verify` (suite normal) y `./mvnw -B -o verify -Pdb-tests` (PostgreSQL 17 descartable vía Testcontainers); E2E reales `E2E_SHARD=<1|2> bash .github/scripts/frontend-e2e.sh`.

## Specs

- S1 (alcance): «Implementar una revisión optimista agregada por deportista para proteger los vínculos familiares contra operaciones preparadas sobre información desactualizada.»
- S2 (migración): «Migración V11 con revisión independiente de la versión de datos permanentes del deportista.» «No ejecutar V11 sobre Supabase ni sobre bases reales.»
- S3 (lectura): «Contratos administrativos que permitan consultar la revisión, incluso cuando no existan vínculos.»
- S4 (revocar y principal): «Protección de revocación y cambio de principal mediante `versionEsperada`.»
- S5 (lote): «Protección de la vinculación por lote, incluyendo reactivaciones que restauran acceso familiar.»
- S6 (bajo el bloqueo): «Comparación de revisiones bajo los bloqueos existentes.» «Mantener el `FOR UPDATE` por deportista, el orden determinista de bloqueos y el `lock_timeout`.»
- S7 (cambio efectivo): «Incremento de revisión solo cuando haya cambios efectivos.»
- S8 (obsoleta): «Rechazo de revisiones obsoletas, aunque la operación solicitada sea idempotente.»
- S9 (invariantes): «Atomicidad del lote, aislamiento entre escuelas y preservación de las reglas de principal.» «No introducir un concepto de tutor principal. El principal sigue siendo una propiedad del vínculo familia-deportista.» «Preservar las respuestas 404 uniformes, la autorización ADMIN y la minimización de datos personales.»
- S10 (documentos): «Compatibilidad con las operaciones documentales concurrentes.»
- S11 (pruebas): «Pruebas unitarias, WebMvc, PostgreSQL real, concurrencia y mutaciones relevantes.» «Implementá con RED → GREEN y verificá con una revisión independiente.»
- S12 (proceso): «Evaluá RDD tempranamente.» «Si el protocolo exige consentimiento específico de riesgo alto, solicitámelo antes de ejecutar la etapa correspondiente.»
- S13 (límites): «No autorizo commits, push, PR, merge ni despliegues.» «No modificar el checkout original, otros worktrees, workflows ni dependencias.» «No comenzar U11-B, U11-C, U11-D, U12, U13 ni U14.» «No abras nuevos mantenimientos ni amplíes el alcance sin un defecto bloqueante demostrado.»

## Contrato y diseño (decisiones del parent; el writer las implementa)

- **C1 Migración V11** `V11__versionar_vinculos_deportista.sql` (patrón de V9): `ALTER TABLE gestion_patin.deportista ADD COLUMN version_vinculos BIGINT NOT NULL DEFAULT 0, ADD CONSTRAINT ck_deportista_version_vinculos_no_negativa CHECK (version_vinculos >= 0);`. No modifica V1–V10. `version` (datos permanentes, V9) y `version_vinculos` son independientes: una operación de vínculos **nunca** mueve `version`; editar, activar o desactivar el deportista **nunca** mueve `version_vinculos` (F4: no tocan vínculos).
- **C2 Lectura de la revisión (S3)**: nuevo `GET /api/admin/deportistas/{deportistaId}/vinculos` (ADMIN) → `200 {deportistaId, versionVinculos, vinculos: [VinculoRespuesta]}` con todos los estados y el mismo orden que `GET /deportistas/{id}/familias`. Revisión y lista salen de **la misma instantánea** (una sentencia o una transacción de solo lectura `REPEATABLE_READ`). Funciona con cero vínculos y con un deportista inactivo. `400` si el id no es UUID; `404 DEPORTISTA_NO_ENCONTRADO` uniforme (inexistente u otra escuela). Además `VinculoRespuesta` suma `versionVinculos` (cadena decimal: la revisión del deportista de esa fila), en los dos listados existentes y en las respuestas de las escrituras (revisión **posterior** a la operación). Cambio aditivo en los listados.
- **C3 Revocar (S4, S8)**: `POST /api/admin/familias/{familiaId}/deportistas/{deportistaId}/revocar` exige body `{versionEsperada}`. Orden: `lock_timeout` → bloqueo del deportista → vínculo `404 VINCULO_NO_ENCONTRADO` (uniforme) → revisión `409 EDICION_DESACTUALIZADA` → PENDIENTE/RECHAZADO `409 VINCULO_NO_ACTIVO` → REVOCADO: `200` sin escribir, sin auditoría y sin mover la revisión → ACTIVO: revoca (deja de ser principal, sin promover a otro), auditoría y revisión +1.
- **C4 Principal (S4, S8)**: `POST .../principal` exige body `{versionEsperada}`. Orden: familia `404` → familia inactiva `409 FAMILIA_INACTIVA` → bloqueo → vínculo `404` → revisión `409 EDICION_DESACTUALIZADA` → `409 VINCULO_NO_ACTIVO` → ya es el principal: `200` sin escribir ni mover → baja el actual, sube el elegido, auditoría y revisión **+1 una sola vez**.
- **C5 Lote (S5, S8, S9)**: `POST /api/admin/familias/{familiaId}/deportistas` pasa de `{deportistaIds: [uuid]}` a `{deportistas: [{deportistaId, versionEsperada}]}` (1..50 elementos, contados antes de quitar repetidos, como hoy). `deportistaIds` deja de aceptarse (`400 SOLICITUD_INVALIDA`, propiedad desconocida). Un id repetido con la misma `versionEsperada` se deduplica (queda la primera posición); con revisiones distintas es ambiguo → `400`. Orden: familia `404` → familia inactiva `409` → bloqueo de los deportistas en orden de id → alguno inexistente u otra escuela `404 DEPORTISTA_NO_ENCONTRADO` (`detalles` `deportistas[i]`) → alguna revisión obsoleta `409 EDICION_DESACTUALIZADA` (`detalles` `deportistas[i]`) **aunque ese deportista diera `SIN_CAMBIOS`** → alguno inactivo `409 DEPORTISTA_INACTIVO` → escritura. Revisión +1 una vez por deportista con `CREADO` o `REACTIVADO`; `SIN_CAMBIOS` no la mueve. Todo o nada: cualquier error no deja vínculos, revisiones ni auditoría.
- **C6 Bajo el bloqueo (S6)**: la revisión esperada se compara con el valor leído **de la fila bloqueada** (`FOR UPDATE`, orden de id, `SET LOCAL lock_timeout`; nunca un valor en caché de la sesión de persistencia), y el incremento es un `UPDATE` con `escuela_id` en la misma transacción, después de las escrituras efectivas. No hay bloqueos nuevos ni cambia el orden: el lote sigue bloqueando solo deportistas; revocar no lee la familia.
- **C7 Validación**: `versionEsperada` igual que `DeportistaVersionEsperada` (cadena decimal, 0..máximo de BIGINT; ausente, nula o malformada → los mismos `400` saneados y sin eco que el deportista). Propiedades inesperadas → `400 SOLICITUD_INVALIDA`. El `409 EDICION_DESACTUALIZADA` no lleva revisiones, ids, nombres ni datos personales.
- **C8 Sin cambios**: códigos existentes (`VINCULO_PRINCIPAL_EN_CONFLICTO`, `CONFLICTO_CONCURRENCIA`), reglas F1–F12, U1–U5, N1–N3 y P1, auditoría (mismas acciones, solo con cambio efectivo), portal FAMILIA y operaciones documentales (sin tocar `documentos/**`).
- **C9 Consumidor real (decisión del parent)**: `frontend/e2e/support/seed.ts` (`link` y `revoke`) usa el contrato anterior; se adapta para leer la revisión con C2 y enviarla, igual que U6 hizo con `deactivate`. Sin esa adaptación el CI obligatorio (E2E) se rompe. No es alcance nuevo: es el único consumidor del contrato que cambia (verificado: ninguna pantalla del frontend usa estas rutas).
- **C10 Documentación**: `docs/FAMILIAS-DEPORTISTAS.md` (tabla de rutas, máquina de estados y orden de verificación, concurrencia, nueva sección de la revisión de vínculos, compatibilidad y secuencia de despliegue: V11 primero; no mezclar instancias viejas que no incrementan la revisión con nuevas).
- **C11 Desbordamiento**: el contador no se maneja especialmente en `Long.MAX_VALUE` (inalcanzable en la práctica); queda documentado como riesgo aceptado.

## Tasks

- [x] T1 (S2, C1) Migración V11 y mapeo en `Deportista` (`insertable=false, updatable=false`, sin `@Version` nuevo) — delegated (writer); RED: Flyway sin versión 11.
- [x] T2 (S3, C2) Lectura `GET .../vinculos` (`REPEATABLE_READ`, solo lectura, 2 sentencias) y `versionVinculos` en `VinculoRespuesta` — delegated.
- [x] T3 (S4–S8, C3–C7) Revisión en revocar, principal y lote, comparada con una proyección escalar leída después del `FOR UPDATE`; incremento masivo con `escuela_id`, solo con cambio efectivo — delegated; RED: 15 fallos y 1 error en `VinculoAdminServiceTest` contra un stub.
- [x] T4 (S11) Pruebas: unitarias, WebMvc, OpenAPI/inventario de rutas y PostgreSQL real — delegated. Writer: `clean verify` 1 304 con 2 fallos en guardianes fuera de su superficie (`DeportistaTest`, `V2MigracionEstaticaTest`), corregidos por el parent (V11 en la lista de migraciones y `versionVinculos` en los campos permitidos, como hizo U9 con V10); `verify -Pdb-tests` 676/676. Parent: `clean verify` **1 304/1 304**.
- [x] T5 (C9) Semilla E2E adaptada (`link` y `revoke` leen `versionVinculos` con el GET nuevo y la envían; firmas sin cambios) — delegated; parent: `typecheck` y `lint` del frontend en verde; E2E reales en curso.
- [x] T6 (C10) Documentación (`docs/FAMILIAS-DEPORTISTAS.md`, sección 14) — delegated.
- [ ] T7 (S11, S12) Mutaciones (hecho), verificación independiente (hecho, sin bloqueantes), RDD: consentimiento otorgado, pero la revisión nativa se detuvo en `lens_context_budget_exceeded` (sin autoridad creada) — **Needs your decision** (ver L5) — parent.

## Commits (evidencia por tarea)

Cadena reconstruida (cada commit verde por sí solo en un export limpio y de unas 650 líneas como máximo, para que la revisión nativa lo pueda revisar aparte):

- `9bd9f00` `feat(vinculos): agregar la revisión agregada de vínculos y su lectura` — T1, T2: V11, mapeo, guardianes, `GET .../vinculos`, `versionVinculos` en `VinculoRespuesta`, OpenAPI y fila de la ruta en la documentación (18 archivos, 354 líneas).
- `d9c8565` `feat(vinculos): incrementar la revisión con cada cambio efectivo de vínculos` — T3 (incremento; escrituras sin protección todavía) (9 archivos, 234 líneas; 1 287 normales y 659 `db`).
- `1c6b3fe` `feat(vinculos): exigir la revisión al revocar y al cambiar el principal` — T3, T5 (semilla `revoke`) (19 archivos, 576 líneas; 1 295 y 659).
- `4ec3e18` `feat(vinculos): exigir la revisión en la vinculación por lote` — T3, T5 (semilla `link`) (20 archivos, 604 líneas; 1 304 y 659).
- `2036a13` `test(vinculos): cubrir la revisión de vínculos y su contrato HTTP con PostgreSQL real` — T4 (3 archivos, 608 líneas).
- `73a27c6` `test(vinculos): cubrir la concurrencia y las regresiones de la revisión con PostgreSQL real` — T4 (2 archivos, 589 líneas).
- Commit de documentación (`docs/FAMILIAS-DEPORTISTAS.md` y este documento) — T6; su propio hash no puede constar en el archivo que contiene.

Ruta: **delegated** a un writer acotado por el respaldo de contexto largo de la sesión del parent (una sola unidad de comportamiento; sin unidades paralelas).

## Log

- L1 (2026-11-17, pedido original, literal):

```text
Continuamos el desarrollo de `banfield-patin` con Claude Code.

Vamos a implementar **FE-05/U11-A: protección de concurrencia de vínculos familia-deportista**.

Usá gentle-ai/ODD y Engram. No utilizar SDD.

Base: `a0d7a16a7edac0734d5bee634cda7bb8da705c4f`.

Rama: `feat/fe05-u11a-concurrencia-vinculos`.

Worktree: `~/banfield-patin-worktrees/fe05-u11a-concurrencia-vinculos`.

## Objetivo

Implementar una revisión optimista agregada por deportista para proteger los vínculos familiares contra operaciones preparadas sobre información desactualizada.

El alcance incluye:

1. Migración V11 con revisión independiente de la versión de datos permanentes del deportista.
2. Contratos administrativos que permitan consultar la revisión, incluso cuando no existan vínculos.
3. Protección de revocación y cambio de principal mediante `versionEsperada`.
4. Protección de la vinculación por lote, incluyendo reactivaciones que restauran acceso familiar.
5. Comparación de revisiones bajo los bloqueos existentes.
6. Incremento de revisión solo cuando haya cambios efectivos.
7. Rechazo de revisiones obsoletas, aunque la operación solicitada sea idempotente.
8. Atomicidad del lote, aislamiento entre escuelas y preservación de las reglas de principal.
9. Compatibilidad con las operaciones documentales concurrentes.
10. Pruebas unitarias, WebMvc, PostgreSQL real, concurrencia y mutaciones relevantes.

No introducir un concepto de tutor principal. El principal sigue siendo una propiedad del vínculo familia-deportista.

Mantener el `FOR UPDATE` por deportista, el orden determinista de bloqueos y el `lock_timeout`.

Preservar las respuestas 404 uniformes, la autorización ADMIN y la minimización de datos personales.

## Proceso

Antes de implementar:

- Recuperá el contexto de ODD, Engram y los contratos reales.
- Verificá que la base y el worktree sean correctos.
- Evaluá RDD tempranamente.
- Si el protocolo exige consentimiento específico de riesgo alto, solicitámelo antes de ejecutar la etapa correspondiente.

Implementá con RED → GREEN y verificá con una revisión independiente.

No abras nuevos mantenimientos ni amplíes el alcance sin un defecto bloqueante demostrado.

## Restricciones

Autorizo análisis, implementación local y pruebas exclusivamente de U11-A.

No autorizo commits, push, PR, merge ni despliegues.

No modificar el checkout original, otros worktrees, workflows ni dependencias.

No comenzar U11-B, U11-C, U11-D, U12, U13 ni U14.

No ejecutar V11 sobre Supabase ni sobre bases reales.

## Entregable

Presentame los contratos implementados, migración V11, resultados de pruebas y PostgreSQL real, mutaciones, RDD, revisión independiente, riesgos residuales, archivos modificados, estado Git y propuesta de commits.

Detenete antes de commitear.
```

- L2 (2026-11-17): base y worktree verificados (`origin/main` = `a0d7a16`, rama nueva, árbol limpio, Docker 29.9, caché de Maven). Contrato real verificado (`docs/FAMILIAS-DEPORTISTAS.md`, mapa del backend en Engram #1211): la fila de vínculo no tiene revisión, las escrituras no la exigen, todas bloquean el deportista `FOR UPDATE` en orden de id. Hallazgo: la semilla E2E consume el lote y la revocación con el contrato anterior (C9). RDD temprano: el candidato toca migración, un contrato consumido, concurrencia y el otorgamiento de acceso a datos de menores (ítems 1–4 de alto riesgo); la evaluación nativa y su consentimiento se hacen cuando exista el diff, antes de la revisión nativa.
- L3 (2026-11-17): writer terminó en «partial» por dos guardianes fuera de su superficie; el parent los ajustó (precedente U9). Diff dentro de las superficies autorizadas; V1–V10 intactas. Desvíos del writer respecto de C1–C11: `detalles` del lote pasan de `deportistaIds[i]` a `deportistas[i]` también en los `404`/`409` existentes (consecuencia de C5) y el duplicado ambiguo responde `400 SOLICITUD_INVALIDA`. Consumidor manual no adaptado: `backend/scripts/smoke-familias.sh` (envía `deportistaIds`); queda documentado (el smoke de familias ya figuraba como mantenimiento aparte en unidades previas).
- L4 (2026-11-17): verificación del parent sobre el árbol definitivo: `clean verify` 1 304/1 304; `verify -Pdb-tests` **688/688**; frontend `typecheck` y `lint` en verde; E2E reales con la semilla adaptada **171/171** (shard 1: 101 en 15,1 min; shard 2: 70 en 15,2 min). Mutaciones del parent (16): 15 detectadas; M15 (incremento sin el predicado de escuela) sobrevivía y llevó a agregar `elIncrementoDeLaRevisionNuncaCruzaDeEscuelaAunqueRecibaElIdDeUnDeportistaAjeno` (ahora detectada); M16 (control `filas != cambiados.size()`) es equivalente: las filas ya están bloqueadas por la misma transacción. Verificación independiente (copia aislada, base `a0d7a16` aparte, diferencial contra la base): **sin bloqueantes**; 1 304 y 677 en verde; 12 mutaciones propias, 11 detectadas; M11 (comparar `Long` por identidad en el lote) sobrevivía porque ninguna prueba usaba revisiones mayores que 127 y la cierra su sonda P8. Diferencial: la única salida que cambia respecto de la base es `deportistaIds[i]` → `deportistas[i]` en los `detalles` del lote (pedido por C5). Sus sondas se incorporaron como pruebas de regresión (`VinculosRevisionRegresionDbTest`, 7; `VinculosRevisionHttpDbTest`, 4); la diferencial no, porque lee la copia de la base. M11 repetida en el worktree: detectada. Advisories: `backend/scripts/smoke-familias.sh` sigue con `deportistaIds` (manual, sin workflow); deduplicar con la primera posición solo lo fijan las pruebas unitarias y la regresión nueva; desbordamiento en `Long.MAX_VALUE` → 500 con rollback (C11).
- L5 (2026-11-17): RDD. `gentle-ai review assess` sobre el candidato sin commitear (37 rutas, 3 065 líneas, escalado por el ítem 1): **riesgo alto, `review_due: high_risk`**. Preflight con los 12 archivos sin seguimiento declarados y cuatro lentes (riesgo, resiliencia, legibilidad, fiabilidad); START devolvió el consentimiento v3 y el usuario eligió «Revisar este cambio». La invocación `granted` terminó en `lens_context_budget_exceeded`: la evidencia completa del candidato supera el presupuesto de contexto de los revisores, **no se creó autoridad** y reintentar el mismo candidato no puede funcionar; el contrato indica revisarlo como commits encadenados más chicos. Como los commits no están autorizados, queda una decisión del usuario. Tamaño por grupo: migración 5, producción 355, pruebas unitarias/WebMvc/guardianes 722, pruebas PostgreSQL 1 759 (adaptaciones de pruebas existentes ≈ 470 y clases nuevas ≈ 1 325), semilla 22, docs y ODD 202. Las adaptaciones de pruebas existentes compilan solo con el contrato nuevo, así que no pueden ir en un commit separado del código.
- L6 (2026-11-17): con los tres commits autorizados (`a454e1b`, `821d2ee`, `f6b22f0`) la revisión nativa rechazó también el commit 1 solo (1 595 líneas) por `lens_context_budget_exceeded`, y su export limpio falló 3 pruebas (`RutasDocumentadasTest` ×2, `OpenApiRutasTest`): la fila de la ruta nueva estaba en el commit de documentación. Error de partición del parent. El usuario eligió «Rehacer la cadena»: respaldo del estado final verificado en `refs/backup/u11a-final` (`f6b22f0`, árbol `34e2d0c`), `git reset --soft a0d7a16` (local, nada publicado) y reconstrucción en 7 commits por un writer acotado. El writer se cortó por el límite de uso del proveedor del modelo después de crear 6 commits; el parent verificó el sexto y creó el de documentación. El árbol final es idéntico al respaldo salvo esta sección y esta entrada del Log.
