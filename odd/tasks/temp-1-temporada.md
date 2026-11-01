# TEMP-1 — Temporada: alta, listado, detalle y activación

Objetivo: módulo ADMIN de `Temporada` sobre el esquema existente (sin migración). Estado: **CERRADA: validada remotamente y fusionada en `main` (PR #4, merge commit `660eb2868a6837a363ae27e49a2aee79071a41ed`)**.
Rama: `feat/temp-1-temporada` (desde `main` = `356b32c`). Entrega: `ask-on-risk`; sin commits, push ni merge sin autorización. Runner: `./mvnw clean verify` y `./mvnw verify -Pdb-tests`.

## Specs

- **S1** — "Adoptá provisionalmente la regla O10: puede haber cero o una temporada activa por escuela, y toda temporada nueva nace inactiva."
- **S2** — "Diseñá los contratos HTTP de alta, listado, detalle y activación."
- **S3** — "Definí cómo implementar la activación concurrente mediante bloqueo de la escuela con `SELECT ... FOR UPDATE`, sin afectar otras escuelas."
- **S4** — "Definí autorizaciones, aislamiento por escuela, auditoría, errores HTTP y comportamiento idempotente."
- **S5** — "Proponé pruebas unitarias, WebMvc e integración real con Testcontainers, incluyendo dos activaciones simultáneas."
- **S6** — "Separá el pendiente O8 de DNI en una unidad posterior." (queda en `temp-4-dni-enmascarado`)
- **S7** — "Verificá si realmente se necesita una migración nueva. No modificar V1–V4."
- **S8** — "No modificar código Java ni ejecutar migraciones. No hacer commits, push ni cambios en Supabase." (vigente hasta la aprobación del plan)
- Supuestos (no pedidos, marcados): sin edición de año, sin desactivación independiente y sin borrado (recomendaciones A de `docs/TEMPORADAS.md` §5).

## Tasks

| ID | S# | Tarea | Ruta | Estado | Evidencia |
| --- | --- | --- | --- | --- | --- |
| T0 | — | Descubrimiento y plan | inline | hecho | este documento |
| TD | D-A | Extraer `lock_timeout` a `compartido` sin alterar vínculos | inline | hecho | `f3cc252`; 766 normales + 43 DB de vínculos verdes |
| T1 | S1 | `Temporada` (entidad), `TemporadaRepository`, acciones de auditoría | inline | hecho | `8bf38bd`; `TemporadaTest` 3 tests |
| T2 | S2, S4 | `TemporadaAdminService`: alta, listado, detalle | inline | hecho | `8bf38bd`; `TemporadaAdminServiceTest` 14 tests |
| T3 | S2, S4 | `TemporadaAdminController` + DTOs + manejo de errores | inline | hecho | `8bf38bd`; `TemporadaAdminControllerWebMvcTest` 14 tests |
| T4 | S3 | Activación con bloqueo de escuela + idempotencia + auditoría | inline | hecho | `8bf38bd` (código) y `fe67638` (pruebas); ver L11 |
| T5 | S5 | Pruebas DB: mapeo, aislamiento, restricciones, auditoría, concurrencia | inline | hecho | `fe67638`; 17 tests DB nuevos; `-Pdb-tests` 341/0/0/0 |
| T6 | S2 | Docs del módulo, tabla de rutas y OpenAPI (RNF-14) | inline | hecho | `8bf38bd`; `docs/TEMPORADAS.md` §3 y §7; inventario y OpenAPI actualizados |
| T7 | — | Cierre: suites, diferencial de comportamiento, Risk | inline | hecho | normal 801/0/0/0; DB 341/0/0/0; ver L11 |

Ruta: todo inline (una sola unidad de comportamiento, sin partes independientes). Estimación: ~450–600 líneas autoradas con tests; se evalúa partir en dos commits (T1–T3 alta/lectura, T4–T5 activación). La activación toca concurrencia (alto riesgo, ítem 4): su commit va solo.

## Log

### L1 — Pedido original (verbatim)

> Vamos a comenzar la Fase 1 de Banfield Patín Carrera con Organic Driven Development (ODD).
>
> **Primera unidad: `temp-1-temporada`.**
>
> Por ahora, únicamente descubrimiento y planificación. No implementar todavía.
>
> 1. Confirmá que CI-3 está fusionado y actualizá `main` local mediante `git pull --ff-only origin main`, sin perder cambios locales.
> 2. Creá la rama de trabajo `feat/temp-1-temporada` desde `main` actualizado.
> 3. Leé `docs/TEMPORADAS.md`, `docs/DECISIONES.md`, `docs/ROADMAP.md`, `docs/ODD.md` y las instrucciones del backend.
> 4. Inspeccioná el DDL real de `temporada` en V1 y todas sus restricciones e índices.
> 5. Verificá si realmente se necesita una migración nueva. No modificar V1–V4.
> 6. Adoptá provisionalmente la regla O10: puede haber cero o una temporada activa por escuela, y toda temporada nueva nace inactiva.
> 7. Diseñá los contratos HTTP de alta, listado, detalle y activación.
> 8. Definí cómo implementar la activación concurrente mediante bloqueo de la escuela con `SELECT ... FOR UPDATE`, sin afectar otras escuelas.
> 9. Definí autorizaciones, aislamiento por escuela, auditoría, errores HTTP y comportamiento idempotente.
> 10. Proponé pruebas unitarias, WebMvc e integración real con Testcontainers, incluyendo dos activaciones simultáneas.
> 11. Separá el pendiente O8 de DNI en una unidad posterior.
> 12. Prepará el plan de trabajo conforme al protocolo ODD y registrá la planificación en los artefactos correspondientes cuando el contrato de persistencia lo exija.
>
> No modificar código Java ni ejecutar migraciones. No hacer commits, push ni cambios en Supabase.
>
> Esperá mi aprobación antes de comenzar la implementación.

### L2 — Esquema real (V1:186-201) y migración

`temporada(id uuid PK gen_random_uuid, escuela_id uuid NOT NULL FK→escuela, anio smallint NOT NULL CHECK 2020..2100, activa boolean NOT NULL DEFAULT false, creado_en, actualizado_en)`; `uq_temporada_escuela_anio UNIQUE(escuela_id, anio)`; `uq_temporada_id_escuela UNIQUE(id, escuela_id)` (FKs compuestas de otras 7 tablas); índice parcial único `uq_temporada_activa_por_escuela ON (escuela_id) WHERE activa = true` (**no diferible**); trigger `trg_temporada_actualizado` (actualiza `actualizado_en`). El nombre de las restricciones coincide con `docs/TEMPORADAS.md`.

**No hace falta migración**: cubre O10 (cero o una activa), "nace inactiva" (`DEFAULT false`), año único y rango. La auditoría (`accion varchar(80)`, sin CHECK) acepta acciones nuevas sin cambio de esquema. No existe código de temporada en `src/main` (solo referencias en DTOs de deportista).

### L3 — Decisiones técnicas

1. **Patrón del módulo**: igual que familias (`temporadas` como paquete nuevo): entidad con fábrica `crear` y mutadores que devuelven si hubo cambio real, repositorio con `findByIdAndEscuelaId`, servicio con `UsuarioAutenticado` (escuela y actor desde el JWT), controller bajo `/api/admin/temporadas` (la cadena de seguridad ya exige ADMIN en `/api/admin/**`).
2. **Orden de escrituras en la activación**: el índice parcial no es diferible. Hay que desactivar la anterior con `saveAndFlush` **antes** de activar la nueva; Hibernate no garantiza el orden de dos UPDATE si solo se hace flush al final.
3. **Bloqueo de la escuela**: nuevo `EscuelaRepository.findByIdParaActualizar` (el existente es por slug). Hallazgo: en PostgreSQL toda FK de otra tabla toma `FOR KEY SHARE` sobre la fila de `escuela` (incluida `auditoria`); `FOR UPDATE` choca con eso y pausaría breves altas de otras operaciones de la **misma** escuela, mientras que `FOR NO KEY UPDATE` serializa activaciones igual y no choca. Hay que verificar qué SQL emite Hibernate con `PESSIMISTIC_WRITE` y, si emite `FOR UPDATE`, usar consulta nativa con `FOR NO KEY UPDATE`. Ambos bloquean solo la fila de esa escuela.
4. **Espera acotada**: `LockTimeoutVinculos` está en `familias.vinculos` y atado a `VinculosPropiedades`. Recomendación: no duplicarlo ni acoplar temporadas a vinculos; extraer a `compartido` en un commit previo pequeño (decisión abierta D-A).
5. **Idempotencia sin bloqueo**: se lee la temporada (404 si no es de la escuela); si ya está activa, `200` sin lock, sin escrituras y sin auditoría. Si no, se bloquea la escuela, se **relee** la temporada (otro request pudo activarla) y se procede.
6. **Respuesta**: `{id, anio, activa}`. Sin `creadoEn`/`actualizadoEn`: el valor lo asigna la base/trigger y la entidad quedaría desactualizada tras el UPDATE.
7. **Conflicto de año**: consulta previa para dar el mensaje claro y, ante la carrera, `uq_temporada_escuela_anio` → mismo 409 (patrón `guardar` de vínculos con `RestriccionViolada`).
8. **Red de seguridad**: violación de `uq_temporada_activa_por_escuela` → `409 CONFLICTO_CONCURRENCIA` (código y mensaje ya existentes).

### L4 — Contratos HTTP (todos bajo `/api/admin/temporadas`, solo ADMIN)

| Método y ruta | Cuerpo | Éxito | Errores |
| --- | --- | --- | --- |
| `POST /api/admin/temporadas` | `{ "anio": 2027 }` | `201` + `Location`; `{id, anio, activa:false}` | `400 VALIDACION` (anio nulo, no numérico o fuera de 2020–2100); `409 TEMPORADA_ANIO_DUPLICADO` |
| `GET /api/admin/temporadas?page&size` | — | `200` `Pagina<{id, anio, activa}>`, orden `anio` desc | — (tamaños acotados como el resto) |
| `GET /api/admin/temporadas/{id}` | — | `200 {id, anio, activa}` | `404 TEMPORADA_NO_ENCONTRADA` (también para otra escuela) |
| `POST /api/admin/temporadas/{id}/activar` | — | `200 {id, anio, activa:true}` | `404 TEMPORADA_NO_ENCONTRADA`; `409 CONFLICTO_CONCURRENCIA` |

Transversal: `401` sin sesión; `403` para FAMILIA; `403` sin token CSRF en métodos que escriben. Sin PUT, DELETE ni desactivar. La escuela jamás viaja en la solicitud.

### L5 — Auditoría

Acciones nuevas al final del enum (se guardan por nombre): `TEMPORADA_CREADA` (detalle `{anio}`) y `TEMPORADA_ACTIVADA` (detalle `{anio, anteriorTemporadaId|null}`); recurso `TEMPORADA`. Se escribe en la misma transacción y solo con cambio real; la activación idempotente no audita. Un único evento por activación (la desactivación de la anterior queda en su detalle).

### L6 — Estrategia de concurrencia

```text
@Transactional (+ lock_timeout acotado)
  t = findByIdAndEscuelaId        -> 404 si no existe/otra escuela
  si t.activa                     -> 200 (sin lock, sin escrituras, sin auditoría)
  lock fila escuela (id del JWT)  -> serializa solo esa escuela
  t = relectura                   -> si ya está activa: 200 sin auditar
  anterior = activa de la escuela -> desactivar + saveAndFlush
  t.activar + saveAndFlush        -> índice parcial como red de seguridad
  auditoría TEMPORADA_ACTIVADA
```

Orden de bloqueo único (escuela primero) para evitar deadlocks; 55P03/40P01 ya se traducen a `409 CONFLICTO_CONCURRENCIA` en el manejador global.

### L7 — Pruebas propuestas

- **Unitarias** (`TemporadaTest`, `TemporadaAdminServiceTest`): nace inactiva; `activar`/`desactivar` devuelven cambio real; alta duplicada; 404 por otra escuela; idempotencia sin escrituras ni auditoría; orden desactivar→activar; mapeo de las dos restricciones a 409; escuela tomada del JWT.
- **WebMvc** (`TemporadaAdminControllerWebMvcTest`): 401, 403 FAMILIA, 403 sin CSRF; `400` para anio nulo, 2019, 2101 y texto; `201` con `Location`; `404`; `200` idempotente; paginación acotada; el cuerpo no acepta `escuelaId`.
- **DB real** (`@PruebaDb`): mapeo y orden del listado; aislamiento entre dos escuelas; unicidad de año; índice parcial (insertar dos activas por SQL falla); auditoría persistida; activación que desactiva la anterior; **dos activaciones simultáneas de temporadas distintas** (barrera de hilos) → ambas `200`, exactamente una activa, una auditoría por cada una; dos activaciones de la **misma** temporada → una sola auditoría; **activación en la escuela B no espera mientras una transacción de la escuela A retiene su lock**; alta de otra operación de la misma escuela no queda bloqueada por el lock (verifica el punto 3 de L3).
- **Ruta/OpenAPI** (RNF-14): rutas en `docs/TEMPORADAS.md` y pruebas de inventario (`InventarioRutasTest`, `RutasDocumentadasTest`, OpenAPI) a extender; hay que leerlas al implementar.

### L8 — Criterios de aceptación

1. Las cuatro rutas responden según L4 con los códigos indicados.
2. Nunca hay más de una temporada activa por escuela, ni siquiera con activaciones simultáneas.
3. Una temporada de otra escuela es indistinguible de una inexistente.
4. Activar la ya activa devuelve 200 sin escritura ni auditoría.
5. Cada alta y activación real deja exactamente un evento de auditoría, en la misma transacción.
6. Sin migración nueva ni cambios en V1–V4, Supabase o workflows.
7. `./mvnw clean verify` y `./mvnw verify -Pdb-tests` verdes; ambos jobs de CI verdes en el PR.

### L9 — Riesgos y decisiones abiertas

- **R1 (alto, ítem 4)**: concurrencia de activación. Mitigación: lock de escuela + índice parcial + prueba de hilos reales.
- **R2**: el tipo de bloqueo que emite Hibernate puede ser más fuerte que el necesario (ver L3.3).
- **R3**: módulos futuros que requieran "temporada actual" deben manejar el caso sin activa (O10-A).
- **R4**: `temp-1` no deja a la nueva temporada activa por defecto; un año nuevo requiere crear y activar.
- **D-A** (a decidir): ¿extraer el `lock_timeout` a `compartido` (recomendado) o prescindir de él en temporadas?
- **D-B** (a decidir): ¿exponer `GET /api/admin/temporadas/activa`? No se pidió; se omite salvo que se confirme.
- Fuera de alcance: `DeportistaTemporada` (temp-2), lectura FAMILIA (temp-3), DNI enmascarado O8 (temp-4), cierre (temp-5).

### L10 — Aprobación y ajustes (verbatim)

> Apruebo comenzar la implementación de `temp-1-temporada` siguiendo ODD.
>
> Decisiones:
> - D-A aprobada: extraer y reutilizar el mecanismo existente de `lock_timeout`, en un commit independiente, sin alterar el comportamiento de vínculos.
> - D-B rechazada por ahora: no agregar endpoint `/activa`.
> - O10 cerrada: cero o una temporada activa por escuela; nuevas temporadas inactivas.
> - O8 queda para otra unidad.
>
> Ajustes obligatorios:
> 1. Antes de implementar, leer íntegramente `REQUERIMIENTOS.md`, `InventarioRutasTest` y los tests de OpenAPI.
> 2. Utilizar bloqueo `FOR NO KEY UPDATE` sobre la escuela y verificar el SQL efectivo.
> 3. Comprobar nuevamente el estado activo después de adquirir el bloqueo, también para la idempotencia.
> 4. Desactivar la temporada anterior antes de activar la nueva, respetando el índice parcial único.
> 5. Mantener la auditoría dentro de la misma transacción y evitar eventos ante operaciones idempotentes.
> 6. Probar concurrencia real mediante Testcontainers, incluyendo activaciones simultáneas y ausencia de bloqueos innecesarios sobre operaciones con claves foráneas.
> 7. No crear migraciones si V1 ya satisface el contrato.
> 8. Mantener ODD local y Engram sincronizados.
> 9. Ejecutar las suites normal y DB antes de considerar cerrada la unidad.
> 10. Utilizar commits pequeños con mensajes en español.
>
> Autorizo implementar y crear commits locales de esta unidad. No autorizo push, merge ni modificaciones de datos de Supabase.

### L11 — Evidencia de implementación

**Commits locales** (rama `feat/temp-1-temporada`, sobre `main` = `356b32c`): `f3cc252` extracción del `lock_timeout`; `8bf38bd` módulo de temporadas, OpenAPI, inventario de rutas y docs; `fe67638` pruebas de BD.

**Suites:** `./mvnw clean verify` = 801 tests, 0 fallos/errores/omitidos (766 previos + 35 nuevos). `./mvnw verify -Pdb-tests` = 341 tests, 0/0/0 (324 previos + 17 nuevos). Sin migraciones; V1–V4 intactas.

**Ajustes obligatorios:**
1. Se leyeron íntegros `REQUERIMIENTOS.md`, `InventarioRutasTest`, `OpenApiRutasTest`, `BaseOpenApiWebMvc`, `OpenApiEstadosRealesTest`, `OpenApiValidezTest`, `OpenApiSinDatosSensiblesTest` y `RutasDocumentadasTest`; de `OpenApiExposicion*Test` se leyeron Dev y Prod y de SinDev solo se buscaron los conteos de rutas. Hallazgo: las rutas se cuentan a mano (39→43 rutas, 32→35 paths, 39→43 `operationId`) en cuatro tests, y `OpenApiConfig.RUTAS` exige una entrada por ruta.
2. `EscuelaRepository.bloquearParaActivarTemporada` es nativa con `for no key update`. SQL efectivo verificado en el servidor: mientras B espera, `pg_stat_activity.query` contiene `gestion_patin.escuela` y `for no key update`.
3 y 4. Releída tras el bloqueo (con lectura previa escalar `TemporadaVista` para no cachear la entidad); desactivar anterior + `flush` antes de activar la nueva.
5. Auditoría en la misma transacción; la activación idempotente (antes o después del bloqueo) no audita.
6. Concurrencia con PostgreSQL real: activaciones de temporadas distintas (determinista y 15 rondas de hilos libres), misma temporada (determinista y 15 rondas de 3 hilos), otra escuela no espera, `familia`/`temporada`/`crear` de la misma escuela no esperan (FK), control con `FOR UPDATE` que sí hace esperar, y `lock_timeout` de 0,3 s con 409 sin fuga.

**RED:** el código se escribió antes que las pruebas del módulo; no hay RED observado previo a la implementación. Como evidencia compensatoria se hicieron cuatro mutaciones del código y todas las detectaron las pruebas (restaurado y verificado con `cmp`): (M1) sin bloqueo de escuela → 4 pruebas fallan; (M2) `FOR UPDATE` en lugar de `FOR NO KEY UPDATE` → falla la aserción del SQL y la prueba de claves foráneas por timeout; (M3) sin releer tras el bloqueo → fallan las 2 pruebas de la misma temporada (doble auditoría); (M4) activar antes de desactivar → fallan pruebas funcionales y de concurrencia por el índice parcial.

**Diferencial:** el código compartido que se tocó es el `lock_timeout` de vínculos y el enum de auditoría (se agregó al final). Las pruebas de vínculos (766 normales y 43 DB, sin modificar) pasan igual que antes. No se tocaron los demás endpoints.

**Desviaciones respecto del plan:**
- El manejador global de `PessimisticLockingFailureException` dice "sobre el mismo deportista" (es de vínculos). Para no alterar vínculos, la activación captura esa excepción y responde `409 CONFLICTO_CONCURRENCIA` con un mensaje propio; el código es el mismo.
- `LockTimeoutTemporadas` y `TemporadasPropiedades` replican el rango de `VinculosPropiedades` (100 ms a 30 s) con su propio mensaje; la parte común es `LockTimeoutTransaccion`.
- Conservé el constructor `(JdbcClient, VinculosPropiedades)` de `LockTimeoutVinculos` para no tocar sus pruebas.

**Riesgos residuales:**
- Alto (ítem 4, concurrencia): cubierto con pruebas de BD reales y mutaciones, pero el volumen de hilos es bajo y el contenedor es uno; no hay prueba de carga.
- `anio` acepta cadenas numéricas y decimales que Jackson convierte (comportamiento general del proyecto, no específico de temporadas).
- Los números de rutas están duplicados a mano en cuatro tests.
- Pendiente: revisión nativa RDD si corresponde, push/PR y CI remoto (no autorizados).

Risk: item 4 (concurrencia: bloqueo de fila de la escuela, orden de escrituras con índice parcial no diferible y `lock_timeout`).

### L12 — Revisión nativa, validación remota y merge

**Revisión nativa RDD:** `gentle-ai review assess` dio riesgo medio y `review_due: false`; al escalar con el ítem 4 (concurrencia) pasó a `high_risk` y `review_due: true`. Se otorgó el consentimiento para tres lentes (riesgo, resiliencia, confiabilidad) sobre el rango completo de la rama (32 archivos, 2045 líneas). Resultado: **aprobada sin hallazgos**; el acuse exacto se ejecutó y la autoridad quedó consumida.

**PR [#4](https://github.com/gonzav104/banfield-patin/pull/4)** (`feat/temp-1-temporada` → `main`), HEAD `d146efe`, base `main` = `356b32c`. Estado de merge: `BLOCKED` mientras los checks corrían; `OPEN` / `MERGEABLE` / `CLEAN` al terminar.

| Run | Evento | Job | Tests | Fallos | Errores | Omitidos | Build |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `41345285962` | `push` | Suite normal | 801 | 0 | 0 | 0 | SUCCESS |
| `41345285962` | `push` | Suite db (PostgreSQL 17) | 341 | 0 | 0 | 0 | SUCCESS |
| `41345291044` | `pull_request` | Suite normal | 801 | 0 | 0 | 0 | SUCCESS |
| `41345291044` | `pull_request` | Suite db (PostgreSQL 17) | 341 | 0 | 0 | 0 | SUCCESS |

Los conteos remotos coinciden con los locales (801 y 341). Sin secretos ni Supabase.

**Merge:** autorizado por el usuario ("Sí, merge commit para el PR #4"); se verificó que el HEAD del PR seguía siendo `d146efe` y se fusionó con merge commit `660eb2868a6837a363ae27e49a2aee79071a41ed` el 2026-11-01 20:10Z. La unidad queda **validada remotamente y fusionada**.

**Sincronización Engram pendiente:** desde el PR #4 `mem_save` rechaza las escrituras sin `session_id` (`multiple active runtime sessions match the current project and directory`). Quedan sin reflejar en el espejo `odd/temp-1-temporada/tasks`: el PR #4, los runs `41345285962` y `41345291044`, el merge `660eb28` y esta cláusula L12. Este documento es la fuente durable hasta resolverlo (ver `odd/tasks/temp-2-deportista-temporada.md`, L3).

