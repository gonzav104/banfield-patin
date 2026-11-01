# TEMP-2 — DeportistaTemporada: alta, edición y consulta

Objetivo: datos deportivos anuales por deportista y temporada (categoría, rama, divisional, número de corredor, estado deportivo) para ADMIN, sobre el esquema existente. Estado: **APROBADO; en implementación (commits locales; sin push ni merge).**
Rama: `feat/temp-2-deportista-temporada` (desde `main` = `660eb28`). Entrega: `ask-on-risk`; solo commits locales de documentación autorizados hasta la aprobación; sin push, merge ni Supabase. Runner: `./mvnw clean verify` y `./mvnw verify -Pdb-tests`.

## Specs

- **S1** — "Definí alta, edición y consulta de categoría, rama, divisional, número de corredor y estado deportivo."
- **S2** — "Analizá unicidad, autorización, aislamiento por escuela, concurrencia y cambios entre temporadas."
- **S3** — "Proponé el contrato HTTP y las pruebas necesarias."
- **S4** — "Priorizá TDD real: RED antes de implementar."
- **S5** — "Inspeccioná el DDL existente de `deportista_temporada`." / "Leé los requerimientos completos y los contratos existentes de Deportista y Temporada."
- **S6** — "No implementar todavía `temp-2`, no hacer push, merge ni modificar Supabase." (vigente hasta la aprobación)
- Supuestos (no pedidos, marcados): sin DELETE (los datos históricos no se borran, `docs/TEMPORADAS.md` §2.6); lectura FAMILIA queda en `temp-3`; DNI enmascarado (O8) queda en `temp-4`.

## Tasks

| ID | S# | Tarea | Ruta | Estado | Evidencia |
| --- | --- | --- | --- | --- | --- |
| T0 | S5 | Descubrimiento y plan | inline | hecho | este documento |
| T1 | S1 | `DeportistaTemporada` (entidad), `EstadoDeportivo`, repositorio | inline | pendiente | RED: `DeportistaTemporadaTest` |
| T2 | S1, S3 | DTOs de solicitud/respuesta con validación y normalización | inline | pendiente | RED: validación en WebMvc |
| T3 | S1, S2 | Servicio: alta y consulta (historial por deportista, detalle, plantel por temporada) | inline | pendiente | RED: `DeportistaTemporadaServiceTest` + DB |
| T4 | S3 | Controller, `OpenApiConfig.RUTAS`, inventario de rutas, docs | inline | pendiente | RED: WebMvc + tests de inventario/OpenAPI |
| T5 | S1, S2 | Edición (PUT), unicidad de número de corredor, auditoría de cambios | inline | pendiente | RED: servicio + DB |
| T6 | S2 | Pruebas DB de concurrencia, aislamiento y cambios entre temporadas | inline | pendiente | RED/GREEN + mutaciones |
| T7 | — | Cierre: suites, diferencial, Risk | inline | pendiente | — |

Ruta: todo inline (una sola unidad de comportamiento). Estimación: ~450 líneas de producción y ~800 de pruebas. Cortes de commit (≈400 líneas autoradas cada uno): **C1** T1–T4 (alta y consulta), **C2** T5 (edición y número de corredor), **C3** T6 (concurrencia en BD), **C4** documentación ODD.

## Log

### L1 — Pedido original (verbatim)

> Vamos a cerrar documentalmente `temp-1-temporada` y comenzar el descubrimiento de `temp-2-deportista-temporada`, siguiendo ODD.
>
> **Paso 4 — Descubrimiento de temp-2**
> - Inspeccioná el DDL existente de `deportista_temporada`.
> - Leé los requerimientos completos y los contratos existentes de Deportista y Temporada.
> - Definí alta, edición y consulta de categoría, rama, divisional, número de corredor y estado deportivo.
> - Analizá unicidad, autorización, aislamiento por escuela, concurrencia y cambios entre temporadas.
> - Proponé el contrato HTTP y las pruebas necesarias.
> - Priorizá TDD real: RED antes de implementar.
>
> Por ahora autorizo planificación, documentación y commits locales de documentación. No implementar todavía `temp-2`, no hacer push, merge ni modificar Supabase.
>
> Entregá el plan de temp-2, los riesgos y las decisiones que necesiten aprobación antes de escribir código.

### L2 — Esquema real (V1:203-231) y diferencias con los requisitos

`deportista_temporada(id uuid PK, escuela_id NOT NULL, deportista_id NOT NULL, temporada_id NOT NULL, categoria varchar(80), rama varchar(40), divisional varchar(80), numero_corredor varchar(30), estado_deportivo varchar(20) NOT NULL DEFAULT 'ACTIVO', creado_en, actualizado_en)`.

Restricciones: `ck_deportista_temporada_estado CHECK IN ('ACTIVO','INACTIVO','SUSPENDIDO')`; FK compuestas `(deportista_id, escuela_id)` → `deportista(id, escuela_id)` y `(temporada_id, escuela_id)` → `temporada(id, escuela_id)` (la base misma impide cruzar escuelas); `uq_deportista_temporada UNIQUE(deportista_id, temporada_id)`; `uq_dt_id_escuela UNIQUE(id, escuela_id)` (para FKs futuras); índice único parcial `uq_numero_corredor_por_temporada ON (escuela_id, temporada_id, numero_corredor) WHERE numero_corredor IS NOT NULL`; trigger `trg_deportista_temporada_actualizado`.

**No hace falta migración:** todo lo pedido cabe en las columnas existentes y las unicidades ya están. Un índice por `(escuela_id, temporada_id)` para el plantel no se justifica con 20–500 deportistas por escuela (RNF-19); queda como observación para el hardening (Fase 10).

Diferencias con `REQUERIMIENTOS.md`:
- **RF-07** incluye "número/tipo de licencia" entre los datos por temporada, pero el esquema lo separa en la tabla `licencia` (una por deportista y temporada). Queda fuera de `temp-2` y llega con la Fase 5.
- **RF-05**: "un deportista inactivo no aparecerá por defecto en nuevas carreras" se apoya en `estado_deportivo` y `deportista.activo` (ver D2); no cambia `temp-2`.
- **RF-07** (criterio): "cambiar los datos de 2027 no modifica 2026" se verifica con una prueba de BD (cada año es una fila).
- `docs/TEMPORADAS.md` §4 distingue `deportista.activo` (dejó la escuela) de `estado_deportivo` (no compite esa temporada / suspendido).

### L3 — Engram: diagnóstico de las sesiones duplicadas (sin forzar nada)

Síntoma: `mem_save` falla con `multiple active runtime sessions match the current project and directory` desde el PR #4. `mem_doctor` (solo lectura) informa **3 sesiones activas** para `banfield-patin` en `/home/el_lito/banfield-patin`: `788ffa74-9b79-411d-a78c-899a2d7c8733`, `c3a604c8-d2db-4413-8b98-805d1b40c0ac` y `df8a4f79-c6c9-4ab8-9e11-d2172f993c80`. Los tres arranques de sesión del hook quedaron con 0 observaciones y a segundos de diferencia (2026-11-01 17:07:15–17:07:22): la causa más probable es que la reanudación de la sesión de Claude Code registró tres sesiones; es una inferencia, no una comprobación. Una escritura sin `session_id` falla cerrada, por diseño, cuando hay más de una candidata.

No se terminó ninguna sesión, no se borró nada y no se usó la vía `engram save` por CLI (escribe en una sesión manual independiente). Opciones, a decidir por el usuario: (a) terminar con `mem_session_end` solo las sesiones que se confirmen obsoletas, o (b) pasar `session_id` explícito en las escrituras.

Hallazgo aparte, sin relación con este proyecto: `mem_doctor` marca 6 destinos de sync ajenos (`caa_sp`, `caa_sp_front` y rutas de otras máquinas) con mutaciones sin entregar. No se tocó.

**Sincronización pendiente en Engram:** el espejo `odd/temp-1-temporada/tasks` quedó en "implementado localmente"; faltan el PR #4, los runs `41345285962` y `41345291044` y el merge `660eb28` (registrados en `odd/tasks/temp-1-temporada.md`, L12). Falta también la decisión de merge de `temp-1` y el espejo `odd/temp-2-deportista-temporada/tasks`. Este documento y el de `temp-1` son la fuente durable.

### L4 — Contrato HTTP propuesto (solo ADMIN, `/api/admin/**`; escuela y actor del JWT)

Identidad por el par natural `(deportistaId, temporadaId)`, como en vínculos; el `id` propio de la fila se devuelve pero no se usa en rutas.

| Ruta | Éxito | Errores propios |
| --- | --- | --- |
| `GET /api/admin/deportistas/{deportistaId}/temporadas` | `200` lista del historial, año descendente (acotada: a lo sumo 81 años por el CHECK) | `404 DEPORTISTA_NO_ENCONTRADO` |
| `POST /api/admin/deportistas/{deportistaId}/temporadas` | `201` + `Location`; cuerpo `{temporadaId, categoria?, rama?, divisional?, numeroCorredor?, estadoDeportivo?}` (estado por defecto `ACTIVO`) | `400 VALIDACION`/`SOLICITUD_INVALIDA`; `404 DEPORTISTA_NO_ENCONTRADO`/`TEMPORADA_NO_ENCONTRADA`; `409 DEPORTISTA_TEMPORADA_DUPLICADA`, `NUMERO_CORREDOR_DUPLICADO`, `DEPORTISTA_INACTIVO` (D2) |
| `GET /api/admin/deportistas/{deportistaId}/temporadas/{temporadaId}` | `200` | `404 DEPORTISTA_NO_ENCONTRADO`/`TEMPORADA_NO_ENCONTRADA`/`DEPORTISTA_TEMPORADA_NO_ENCONTRADA` |
| `PUT /api/admin/deportistas/{deportistaId}/temporadas/{temporadaId}` | `200` reemplazo completo `{categoria?, rama?, divisional?, numeroCorredor?, estadoDeportivo}` | `400`; los `404` de arriba; `409 NUMERO_CORREDOR_DUPLICADO`, `CONFLICTO_CONCURRENCIA` (D6) |
| `GET /api/admin/temporadas/{temporadaId}/deportistas?estadoDeportivo&page&size` | `200` página del plantel, por apellido y nombre | `404 TEMPORADA_NO_ENCONTRADA` |

Respuesta de una fila: `{id, deportistaId, temporadaId, anio, categoria, rama, divisional, numeroCorredor, estadoDeportivo}` (sin `escuelaId` ni marcas de tiempo). El plantel agrega `nombre` y `apellido` del deportista y **no** incluye DNI (el enmascarado es O8/`temp-4`). Sin DELETE ni PATCH. Los controladores van en el paquete `temporadas`, fuera de los que escanea `RutasDocumentadasTest`.

### L5 — Reglas, unicidad y concurrencia

- **Normalización:** se recorta; en blanco pasa a null; largos 80/40/80/30 como la base (`400 VALIDACION` si se exceden); `estadoDeportivo` es un enum cerrado (valor desconocido: `400 SOLICITUD_INVALIDA`, sin eco). Cualquier propiedad que decide el servidor (`escuelaId`, `deportistaId` en el cuerpo del PUT, `id`) se rechaza como en deportistas.
- **Aislamiento:** toda consulta lleva `escuelaId`; deportista, temporada o fila de otra escuela dan el mismo `404` que una inexistente. La FK compuesta es la segunda barrera.
- **Una fila por deportista y temporada:** consulta previa para el `409` amable y la restricción `uq_deportista_temporada` arbitra la carrera (mismo `409`).
- **Número de corredor único por temporada:** consulta previa (ignorando la propia fila) y `uq_numero_corredor_por_temporada` arbitra; el valor es opaco y distingue `7` de `07` (D3). Nulo no choca.
- **Temporada activa o no:** se permite cargar datos en cualquier temporada, también inactiva (`docs/TEMPORADAS.md` §2.9). No se necesita el bloqueo de la escuela de `temp-1`: el alta toma solo `FOR KEY SHARE` por las FKs, compatible con `FOR NO KEY UPDATE` (probado en `temp-1`); se agrega una prueba de que una activación retenida no frena un alta de `deportista_temporada`.
- **Edición concurrente de la misma fila:** el PUT es reemplazo completo; sin bloqueo, dos ediciones simultáneas son "gana la última" y la auditoría de campos modificados podría ser inexacta. Recomendado: bloquear la fila con `FOR NO KEY UPDATE` y `lock_timeout` (reutiliza `LockTimeoutTransaccion`) (D6).
- **Cambios entre temporadas:** cada año es una fila; editar 2027 no toca 2026 (prueba de BD). No hay copia automática de un año a otro (D7) ni DELETE.
- **Auditoría** (misma transacción, solo con cambio real, solo ids y nombres de campo): `DEPORTISTA_TEMPORADA_CREADA` (`{temporadaId, anio}`) y `DEPORTISTA_TEMPORADA_ACTUALIZADA` (`{temporadaId, camposModificados}`; si cambia el estado deportivo, también `{estadoAnterior, estadoNuevo}`, valores de un enum cerrado). Sin números de corredor ni categorías en la auditoría ni en los logs.

### L6 — Pruebas propuestas (TDD con RED observado)

Procedimiento por tarea: (1) esqueleto mínimo que compila (clases y métodos que lanzan `UnsupportedOperationException`); (2) pruebas; (3) ejecutar y **registrar en este Log el RED** (comando, pruebas que fallan y por qué fallan); (4) implementar; (5) GREEN; (6) mutaciones sobre las reglas críticas como en `temp-1`. Los esqueletos no se commitean antes de su GREEN.

- **Unitarias:** `DeportistaTemporadaTest` (nace con estado `ACTIVO`, `reemplazar` devuelve los nombres de campos que cambiaron en orden, sin cambios no informa nada); `DeportistaTemporadaServiceTest` (404 de deportista/temporada/fila, `409` de par y de número, deportista inactivo, sin cambios no audita, auditoría con ids y nombres, mapeo de cada restricción por nombre, relanzar otras violaciones).
- **WebMvc:** `401`, `403` FAMILIA y `403` sin CSRF en las cinco rutas; `400` por largo, enum inválido, UUID inválido, propiedades extra, cuerpo ilegible; `201` con `Location`; `PUT` con `404`; paginación acotada; no hay DELETE ni PATCH.
- **Inventario y OpenAPI:** `InventarioRutasTest` (2 rutas mutantes nuevas, `POST` y `PUT`; además `losDeportistasSoloSeCrean...` filtra por `/api/admin/deportistas` y se ampliará a conciencia), `OpenApiRutasTest` (43→48 rutas; `POST` nuevo en `CREAN`), `OpenApiValidezTest` (35→38 paths, 43→48 `operationId`), `OpenApiEstadosRealesTest` (nuevo 201 con `Location`), `OpenApiArranqueDbTest` (43→48), `OpenApiConfig.RUTAS` (5 entradas) y `BaseOpenApiWebMvc` (controlador y servicio simulado).
- **BD real (Testcontainers):** mapeo y orden; aislamiento entre dos escuelas; FK compuesta (insertar con temporada de otra escuela falla); `uq_deportista_temporada`, `uq_numero_corredor_por_temporada` (nulo repetido sí se permite) y CHECK de estado; auditoría persistida; editar 2027 no cambia 2026; **dos altas simultáneas del mismo par** (determinista y rondas de hilos libres) → una `201` y una `409`, una fila; **dos altas con el mismo número de corredor** en la misma temporada → una gana; el mismo número en otra temporada o escuela sí se permite; **dos ediciones simultáneas de la misma fila** (D6); **una activación de temporada retenida no frena el alta** de `deportista_temporada`.

### L7 — Riesgos

- **R1 (medio):** pérdida de actualizaciones en ediciones simultáneas si se rechaza D6; la base no tiene versión por fila y no se puede agregar sin migración.
- **R2 (medio):** datos de menores. Mitigación: sin DNI en el plantel, auditoría con nombres de campo, ningún valor deportivo en logs.
- **R3 (bajo):** los conteos de rutas se mantienen a mano en cuatro pruebas más `OpenApiConfig.RUTAS`; hay que actualizarlos juntos.
- **R4 (bajo):** texto libre en categoría, rama y divisional puede generar variantes ("Sub 12" / "sub12"); se validará al exportar LBF (Fase 7).
- **R5 (bajo):** `numero_corredor` como texto opaco; si la federación exige numérico, se acotará después sin migración.
- **R6 (proceso):** Engram no admite escrituras hasta resolver las sesiones duplicadas; el ODD local es la fuente durable.
- Clasificación: ningún ítem de alto riesgo (sin migración, sin cambio de contratos existentes, sin concurrencia compleja más allá de las unicidades). No requeriría verificador independiente; la revisión nativa se decide con `gentle-ai review assess` al terminar.

### L8 — Decisiones que necesitan aprobación

| ID | Decisión | Recomendación |
| --- | --- | --- |
| D1 | Forma de las rutas: par natural en la ruta con `POST` para el alta y `PUT` para la edición (L4) | **A**. Alternativa: `PUT` como "crear o reemplazar" (menos rutas, pero 201/200 ambiguo) |
| D2 | Alta de `deportista_temporada` para un deportista **inactivo** | Rechazar con `409 DEPORTISTA_INACTIVO` en el alta; permitir leer y editar filas históricas |
| D3 | Formato de `numeroCorredor` | Texto opaco recortado, hasta 30 caracteres; no numérico |
| D4 | `categoria`, `rama`, `divisional` | Texto libre; validar contra la federación al exportar LBF |
| D5 | `estadoDeportivo` | Campo del cuerpo (default `ACTIVO` en el alta, obligatorio en el PUT), sin endpoints de acción |
| D6 | Edición concurrente de la misma fila | Bloqueo de fila `FOR NO KEY UPDATE` con `lock_timeout` (reutiliza `compartido`) |
| D7 | "Copiar los datos del año anterior" a una temporada nueva | Fuera de `temp-2`; unidad propia si se necesita |
| D8 | Incluir el plantel por temporada (`GET /api/admin/temporadas/{temporadaId}/deportistas`) | Sí: sin él no hay forma de consultar "por temporada" |

### L9 — Aprobación y decisiones (verbatim)

> Apruebo comenzar la implementación de `temp-2-deportista-temporada` siguiendo ODD.
>
> **Decisiones:**
>
> - D1, D2, D3, D5, D7 y D8 aprobadas.
> - D4 modificada: categoría, rama y divisional serán textos configurables, pero deben validarse al guardar (longitud, espacios, obligatoriedad y restricciones del esquema). No esperar hasta la LBF para detectar datos inválidos. No crear enums rígidos sin un catálogo oficial.
> - D6 aprobada con condición: bloquear la fila mediante `FOR NO KEY UPDATE` y releer dentro de la transacción. Documentar que el bloqueo serializa escrituras, pero no evita necesariamente que un PUT construido con datos antiguos sobrescriba cambios.
>
> **Reglas adicionales:**
>
> 1. Permitir cargar y editar datos de una temporada inactiva, para preparar el año siguiente.
> 2. Mantener el número de corredor opcional, como texto, preservando ceros iniciales.
> 3. Respetar las restricciones de unicidad de V1.
> 4. No modificar la ficha permanente del deportista al editar sus datos deportivos.
> 5. Mantener aislamiento por escuela y autorizaciones ADMIN.
> 6. No incluir DNI en el plantel por temporada.
> 7. Evitar consultas N+1 y probar la cantidad de consultas del listado.
> 8. Implementar con TDD real, registrando RED antes de GREEN.
> 9. Mantener auditoría de cambios sin datos personales innecesarios.
> 10. Ejecutar las suites normal y DB antes del cierre.
>
> Autorizo implementar y crear commits locales siguiendo las unidades ODD.
>
> No autorizo push, merge, migraciones contra Supabase ni modificaciones de las migraciones V1–V4.
>
> **Engram:** no forzar ni resolver unilateralmente los conflictos de sesiones. Mantener los artefactos ODD locales actualizados y señalar la sincronización pendiente.
>
> Al finalizar, informar contratos implementados, resultados de pruebas, commits y riesgos residuales.

**Reglas de validación que derivo de D4 (a confirmar con la implementación):** `categoria` (≤80), `rama` (≤40) y `divisional` (≤80) son opcionales porque el esquema los admite nulos; se recortan, las rachas de espacios internos se colapsan a uno, un valor en blanco pasa a nulo y se rechazan caracteres de control (`400 VALIDACION`). `numeroCorredor` (≤30) es opcional, texto opaco que conserva ceros iniciales y espacios internos, se recorta, en blanco pasa a nulo y se rechazan caracteres de control. Nada se valida contra un catálogo.

**D6 (aprobada con condición):** `FOR NO KEY UPDATE` sobre la fila y relectura dentro de la transacción: serializa las escrituras y hace exacta la lista de campos modificados de la auditoría, pero **no** evita que un `PUT` armado con datos viejos pise cambios posteriores (gana la última escritura; no hay columna de versión y no se agrega migración).

### L10 — RED de la unidad A (alta y consulta), observado antes de implementar

Esqueleto: entidad, repositorio con sus consultas, DTOs con su normalización y controlador completos; `DeportistaTemporadaAdminService` con todos sus métodos lanzando `UnsupportedOperationException("pendiente")`; `OpenApiConfig.RUTAS` sin las cinco rutas nuevas. Comando y resultado antes de escribir la lógica:

| Capa | Comando | Resultado RED |
| --- | --- | --- |
| Servicio (unitarias) | `./mvnw test -Dtest='DeportistaTemporadaAdminServiceTest'` | 14 tests: **11 fallos y 3 errores** (los métodos lanzan `UnsupportedOperationException` o no devuelven lo esperado) |
| Cableado de rutas y OpenAPI | `./mvnw test -Dtest='InventarioRutasTest,OpenApi*Test,DeportistaTemporadaAdminControllerWebMvcTest'` | **5 fallos**: falta la entrada de las rutas nuevas en `OpenApiConfig.RUTAS` (`todaRutaDeLaAplicacionTieneSuEntrada...`, éxito 201 y seguridad de la operación) y el `201` probado en `OpenApiEstadosRealesTest`; además `InventarioRutasTest` detectó que el filtro de `lasTemporadasSoloSeCrean...` ahora también captura el plantel (se corrigió el filtro, no el código) |
| BD real | `./mvnw verify -Pdb-tests -Dtest='DeportistaTemporadaDbTest'` | 11 tests: **1 fallo y 9 errores** (`UnsupportedOperationException: pendiente`); 1 pasa por naturaleza (comprueba las restricciones que ya existen en V1) |

Sin RED para la capa que ya estaba completa en el esqueleto: `DeportistaTemporadaTest` (2) y `DeportistaTemporadaSolicitudTest` (7) pasaron desde el primer intento, y los 13 tests de `DeportistaTemporadaAdminControllerWebMvcTest` también (el controlador y los DTO se escribieron completos antes que sus pruebas; el servicio va simulado). Es una desviación del TDD estricto que se compensa con mutaciones en la unidad T6.

### L11 — RED de la unidad B (edición), observado antes de implementar

Esqueleto: `DeportistaTemporada.reemplazar` y `DeportistaTemporadaAdminService.actualizar` lanzando `UnsupportedOperationException("pendiente")`.

| Capa | Comando | Resultado RED |
| --- | --- | --- |
| Entidad y servicio (unitarias) | `./mvnw test -Dtest='DeportistaTemporadaTest,DeportistaTemporadaAdminServiceTest'` | 26 tests: **3 fallos y 7 errores** |
| BD real | `./mvnw verify -Pdb-tests -Dtest='DeportistaTemporadaEdicionDbTest'` | 6 tests: **2 fallos y 4 errores** |

Un error de compilación previo (import faltante en mi propia prueba) se corrigió antes de observar este RED; no cuenta como evidencia.

