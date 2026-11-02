# TEMP-5 — Cierre integral de la Fase 1 (Temporadas y DeportistaTemporada)

Objetivo: verificar que la Fase 1 está completa, corregir solo lo realmente necesario y declararla cerrada con criterios objetivos. Estado: **IMPLEMENTADO localmente (commits locales; sin push ni merge); a la espera de autorización de push/PR.**
Rama: `feat/temp-5-cierre` (desde `main` = `d12afe9`, PR #7 fusionado). Entrega: `ask-on-risk`; sin push, merge, migraciones ni Supabase. Alcance: **solo Fase 1**; no se amplía hacia Documentos, Aptos, Licencias ni Competencias.

## Specs

- **S1** — "verificar el cierre integral de la Fase 1 — Temporadas y DeportistaTemporada de Banfield Patín Carrera."
- **S2** — Auditoría funcional y técnica (temporadas, datos deportivos anuales, concurrencia, FAMILIA, aislamiento, DNI, OpenAPI, conteos de rutas, auditoría, validaciones, N+1, V1–V4, documentación).
- **S3** — "Clasificar hallazgos en bloqueantes, mejoras recomendadas y deuda técnica aceptada."
- **S4** — "Proponer criterios objetivos para declarar terminada la Fase 1."
- **S5** — "Evaluar si corresponde un smoke controlado contra Supabase, sin ejecutarlo ni modificar datos sin autorización."
- **S6** — "Por ahora, solamente descubrimiento y planificación ODD. No modificar código Java, workflows ni migraciones. No ejecutar operaciones contra Supabase, push ni merge."

## Tasks

| ID | S# | Tarea | Ruta | Estado | Evidencia |
| --- | --- | --- | --- | --- | --- |
| T0 | S1–S6 | Descubrimiento, auditoría y plan | inline | hecho | este documento |
| T1 | S2 | Evidencia remota de `temp-4` y cierre documental de `temp-1`…`temp-4` | inline | hecho | `df2e24a` |
| T2 | S2 | Corregir la documentación desactualizada o contradictoria (H1–H5) | inline | hecho | `05a9dc2` |
| T3 | S2 | Conteos de rutas sostenibles (derivarlos de la fuente única y agregar una guarda de completitud) | inline | hecho | `59692d5` |
| T4 | S2 | Entrada estricta del año (`anio`): rechazar decimales y cadenas numéricas (si se aprueba D4) | inline | hecho | `03e1673`; reproducción y RED en L13 |
| T5 | S2 | Pruebas faltantes para completar la evidencia (listado ADMIN de temporadas sin N+1; códigos de error documentados existen) | inline | hecho | `89b15a8` |
| T6 | S4 | Informe de cierre de la Fase 1 (matriz de requisitos, criterios, hallazgos) | inline | hecho | `odd/tasks/fase-1-cierre.md` |
| T7 | S4 | `./mvnw clean verify` y `./mvnw verify -Pdb-tests`; CI remoto; RDD según protocolo | inline | parcial | local 891/408 y `assess` hechos; CI remoto pendiente de push |

Ruta: todo inline. Estimación: ~40 líneas de producción (T4) y ~250 de pruebas; el resto es documentación.

## Log

### L1 — Pedido original (verbatim, cláusulas que fijan los requisitos)

> Vamos a comenzar el descubrimiento de `temp-5-cierre`, siguiendo Organic Driven Development (ODD) de Gentle AI.
>
> **Objetivo:** verificar el cierre integral de la Fase 1 — Temporadas y DeportistaTemporada de Banfield Patín Carrera.
>
> **Restricciones**
> Por ahora, solamente descubrimiento y planificación ODD. No modificar código Java, workflows ni migraciones. No ejecutar operaciones contra Supabase, push ni merge.
>
> No ampliar el alcance hacia Documentos, Aptos, Licencias o Competencias.
>
> Entregá el diagnóstico de completitud, los riesgos, las correcciones realmente necesarias, el plan ODD y los criterios de cierre. Esperá aprobación antes de implementar.

### L2 — Preparación

PR #7 `MERGED` (`d12afe970e1f39ae416e2f4659ef447a539c5c73`); `main` local = `origin/main`; árbol limpio; rama `feat/temp-5-cierre` creada desde `main`. Artefactos ODD revisados: `temp-1`…`temp-4` (cada uno con su plan, aprobación, RED/GREEN, mutaciones, revisión y merge), más `ci-1`…`ci-3` y `fase-0-decisiones`. Engram (14 observaciones de `temp-*`): hay estado/PR/merge de `temp-2`, `temp-3` y `temp-4`; los espejos `odd/temp-1-temporada/tasks` (obs. 583) y `odd/temp-2-deportista-temporada/tasks` están **desactualizados o ausentes**, y todas las escrituras recientes quedaron con una revisión de conflictos pendiente que no se resolvió (deuda de proceso aceptada). **Evidencia remota pendiente de `temp-4`** (PR #7, HEAD `4c8f313`, runs `41371185021` y `41371191641`, 885/403 tests, merge `d12afe9`): se incorpora en T1 como commit documental propio dentro de esta rama, **sin PR exclusivo**.

### L3 — Diagnóstico de completitud (Fase 1)

**Veredicto: la funcionalidad de la Fase 1 está completa y verificada. No se encontró ningún defecto funcional ni de seguridad bloqueante.** Lo que falta para declararla cerrada son correcciones documentales, una guarda de sostenibilidad de las pruebas y una validación de entrada.

| Pedido de la Fase 1 (`docs/TEMPORADAS.md` §6, ROADMAP) | Estado | Evidencia |
| --- | --- | --- |
| Temporada: alta, listado, detalle, activación única | **Completo** | `temp-1` (4 rutas ADMIN); activación con `FOR NO KEY UPDATE`, relectura, índice parcial; 14+14 unitarias/WebMvc, 10+8+3 BD |
| Activación concurrente respeta unicidad | **Completo** | 15 rondas de hilos libres, retenciones deterministas, SQL efectivo verificado, 4 mutaciones |
| DeportistaTemporada: alta, edición, consulta, historial, plantel | **Completo** | `temp-2` (5 rutas ADMIN); 21+11 unitarias/WebMvc, 12+7+9+3 BD; D6 (bloqueo de fila + relectura) con limitación aceptada |
| Lectura de FAMILIA solo de sus deportistas vinculados | **Completo** | `temp-3` (2 rutas `GET`); vínculo ACTIVO, familia activa, misma escuela, visibilidad por año; 404 uniforme por endpoint byte a byte; 31 pruebas de aislamiento; 6 mutaciones |
| Aislamiento entre escuelas y familias | **Completo** | FK compuestas de V1 + `WHERE` único; matrices de BD y HTTP |
| DNI enmascarado en listados | **Completo** | `temp-4`; `ContratoDniTest` + JSON serializado + OpenAPI; 5 mutaciones |
| Auditoría de creación, activación y cambios | **Completo** | 4 acciones (`TEMPORADA_CREADA/ACTIVADA`, `DEPORTISTA_TEMPORADA_CREADA/ACTUALIZADA`), solo ids, año y nombres de campo (más estado anterior/nuevo, enum cerrado); el servicio de auditoría además descarta claves `password/token/hash/secret` |
| Sin migración nueva | **Cumplido** | `db/migration` = V1–V4; la última modificación es el commit de V4 (`8381ebf`); `V4MigracionEstaticaTest` fija su hash |
| Sin rutas de escritura para FAMILIA | **Cumplido** | `InventarioRutasTest` (portal = 5 rutas, todas `GET`) |

Total de la Fase 1: **11 rutas** (9 ADMIN, 2 FAMILIA), todas en `OpenApiConfig.RUTAS`, en el inventario de rutas y en las tablas de `docs/`.

### L4 — Trazabilidad con requisitos y decisiones

| Requisito / decisión | Resultado |
| --- | --- |
| **RF-07** (datos deportivos por temporada separados de los permanentes; cambiar 2027 no modifica 2026) | Categoría, rama, divisional, número de corredor y estado deportivo: **cumplido** y probado (`cambiarDatosDeUnAnioNoTocaLosDeOtro`, edición con la fila de 2026 intacta). **"Número/tipo de licencia": fuera de la Fase 1** — vive en la tabla `licencia` (una por deportista y temporada, Fase 5); brecha conocida y aceptada |
| RNF-01 (autorización en backend, 401/403) | Cumplido en las 11 rutas |
| RNF-05 (auditoría de acciones sensibles) | Cumplido para temporadas y datos deportivos (sin consulta de auditoría: Fase 9) |
| RNF-11 (integridad por restricciones y transacciones) | Cumplido (índices de V1 como red de seguridad + mapeo a 409) |
| RNF-13 (pruebas de flujos críticos) | Cumplido: 885 normales y 403 de BD |
| RNF-14 (OpenAPI coherente con las rutas) | Cumplido y verificado por pruebas; ver H6 |
| RNF-18 (multi-escuela) | Cumplido |
| **D1** (FAMILIA no escribe fuera de workflows explícitos) | La Fase 1 no agrega escritura de FAMILIA |
| **D6** (DNI enmascarado en listados) | Cumplido (`temp-4`) |
| D2–D5, D7–D8, D11 | No aplican a la Fase 1 |
| D9 (ODD) / D10 (CI temprano) | Cumplidos |
| **O8** | Aprobada e implementada |
| **O10** (cero o una temporada activa; nueva = inactiva) | Implementada; **`docs/DECISIONES.md` todavía la lista como abierta** (H3) |
| O13 (CI con dos jobs obligatorios) | Implementada (`ci-1`…`ci-3`); **sigue listada como abierta** (H3) |

### L5 — Hallazgos

**Bloqueantes para declarar la Fase 1 cerrada** (ninguno es un defecto funcional; son consistencia de la documentación y trazabilidad, exigidas por el criterio C4/C9):

| ID | Hallazgo | Evidencia | Corrección |
| --- | --- | --- | --- |
| H1 | **`docs/SEGURIDAD.md` contradice el código:** "Espera de bloqueos acotada solo en vínculos… no existe un límite global" | `SEGURIDAD.md:138-140`; hoy hay dos propiedades (`banfield.vinculos.lock-timeout`, `banfield.temporadas.lock-timeout`) y un mecanismo común | Reescribir el párrafo: dos módulos, mismo mecanismo `SET LOCAL`, ningún límite global |
| H2 | `docs/FAMILIAS-DEPORTISTAS.md` §9 sigue diciendo "`deportista_temporada` fuera de alcance… llegan después" | `FAMILIAS-DEPORTISTAS.md:202` | Actualizar con remisión a `TEMPORADAS.md` |
| H3 | `docs/DECISIONES.md` lista como abiertas decisiones ya tomadas (O10, O13, y el encabezado "Estado a 2026-10-31") | `DECISIONES.md:3,119,146` | Marcar O10 y O13 (y O14 si corresponde) como aprobadas con la unidad que las implementó |
| H4 | `docs/ROADMAP.md` no refleja el avance (ni estados por unidad ni cierre de CI-0/Fase 1) | `ROADMAP.md` | Agregar columna/nota de estado con las unidades fusionadas |
| H5 | Evidencia remota de `temp-4` y espejos de Engram de `temp-1`/`temp-2` sin sincronizar | L2 | T1 (commit documental) + `fase-1-cierre.md`; el espejo de Engram se actualiza con `project` explícito, sin forzar |

**Mejoras recomendadas** (conviene hacerlas en esta unidad; se aprueban una por una):

| ID | Mejora | Fundamento |
| --- | --- | --- |
| H6 | **Conteos de rutas sostenibles.** Hay 5 literales y 3 listas escritas a mano: `OpenApiRutasTest` (50 rutas, 26 documentadas), `OpenApiValidezTest` (40 paths, 50 `operationId`), `OpenApiArranqueDbTest` (50), `BaseOpenApiWebMvc` (lista de controladores), `OpenApiRutasTest.CREAN/SIN_CONTENIDO` y las listas explícitas de `InventarioRutasTest` (78 rutas, **a propósito**: es el candado de seguridad) | En 4 unidades seguidas hubo que tocar estos números; los literales son redundantes porque `OpenApiConfig.RUTAS` ya se compara 1:1 con las rutas mapeadas |
| H7 | **Entrada del año:** `{"anio": 2027.9}` se acepta como 2027 y `{"anio":"2027"}` como 2027 (Jackson 3 por defecto); `{"anio":"2027.9"}`, `true` y `1e3` se rechazan o se salen de rango | Probe con el `JsonMapper` por defecto de Jackson 3 (`2027.9→2027`, `"2027"→2027`, `1e3→1000`); hay que confirmarlo con el mapper real de la aplicación en un test WebMvc |
| H8 | Falta una prueba de sentencias del **listado ADMIN de temporadas** (los demás listados sí la tienen) | `TemporadaServiceDbTest` cubre orden y paginación, no el conteo |
| H9 | No hay una prueba que verifique que **cada código de error documentado en `OpenApiConfig.RUTAS` existe en el código** | Un código documentado y jamás lanzado pasaría inadvertido |

**Deuda técnica aceptada** (se registra, no se corrige en la Fase 1):

| ID | Deuda | Motivo |
| --- | --- | --- |
| A1 | Un `PUT` con datos viejos pisa un cambio posterior (gana la última escritura) | D6: no hay versión por fila; agregarla exige migración. Probado y documentado |
| A2 | Reactivar una temporada anterior oculta años posteriores a las familias | Regla de visibilidad aprobada (condición 1 de `temp-3`); documentada |
| A3 | `estadoDeportivo = SUSPENDIDO` visible a la familia sin motivo | El esquema no guarda motivo |
| A4 | Búsqueda por sufijo del DNI | Mejora futura documentada |
| A5 | CI: `push` y `pull_request` duplican ejecuciones; `suite-db` no sube reportes si falla; los checks se identifican por nombre | Mejoras de `ci-2`/`ci-3` ya registradas |
| A6 | Categoría, rama y divisional como texto libre | No hay catálogo oficial; se validan al guardar (longitud, espacios, control) |
| A7 | Sin prueba de carga ni de rendimiento | Fase 10; 20–500 deportistas por escuela |
| A8 | Conflictos de Engram pendientes y espejos desactualizados | Deuda de proceso; el ODD local es la fuente durable |
| A9 | `RF-07` "número/tipo de licencia" | Fase 5 |
| A10 | La respuesta de un dato deportivo de ADMIN no indica si su temporada es la vigente | Consultable en `GET /api/admin/temporadas`; fuera de la Fase 1 (ficha, Fase 9) |

### L6 — Auditoría técnica: resultados

- **OpenAPI contra rutas reales:** coincide 1:1 (50 rutas mapeadas = 50 entradas de `RUTAS` = 50 operaciones); códigos de éxito y de error de las 11 rutas de la Fase 1 revisados contra los servicios; los 400 se agregan por parámetros o cuerpo. Falta solo la guarda H9.
- **Auditoría y datos sensibles:** `detalle` solo con ids, año, nombres de campo y valores de enum cerrado; ninguna acción guarda DNI, CUIL, números de corredor, categorías ni nombres. Probado en BD (`laAuditoriaDelAltaTieneSoloIdsYAnio…`, `…TieneNombresDeCamposYElCambioDeEstado…`).
- **Validaciones y errores:** largos y formato de los textos deportivos, rango del año (2020–2100), enum cerrado, propiedades desconocidas rechazadas (`SOLICITUD_INVALIDA`), UUID malformado = 400, 6 códigos propios de la Fase 1 más `CONFLICTO_CONCURRENCIA` con mensajes propios. Excepción: H7.
- **N+1:** historial y plantel ADMIN y portal FAMILIA con techos de sentencias probados; falta el listado ADMIN de temporadas (H8).
- **Concurrencia:** activación (`FOR NO KEY UPDATE` sobre la escuela) y edición (sobre la fila), con `lock_timeout` acotado, verificada contra PostgreSQL real, con controles que demuestran que `FOR UPDATE` frenaría operaciones con claves foráneas; 13 mutaciones en total detectadas en las cuatro unidades.
- **Migraciones:** V1–V4 intactas; no hacen falta nuevas. Los índices existentes cubren los accesos de la Fase 1 con el volumen previsto (20–500 deportistas por escuela).
- **Sostenibilidad del CI:** 885 + 403 pruebas en ~1 min cada job; sin flakiness conocida (la única sugerencia de la revisión, subcadenas con UUID, ya se corrigió).

### L7 — Plan ODD

1. **T1 (documental, commit propio):** evidencia remota de `temp-4` y registro del cierre de `temp-1`…`temp-4`.
2. **T2 (documental):** corregir H1–H4. Sin código.
3. **T3 (solo pruebas):** quitar los literales de `OpenApiRutasTest`/`OpenApiValidezTest`/`OpenApiArranqueDbTest` y derivarlos de `OpenApiConfig.RUTAS` (rutas, paths distintos, `operationId`) y de la tabla de `docs/` (documentadas); agregar una **guarda de completitud**: todo `@RestController` de `src/main` figura en `BaseOpenApiWebMvc` (con mensaje que dice qué agregar). Se conserva `InventarioRutasTest` explícito. RED: la guarda se prueba omitiendo un controlador en una copia temporal (mutación), no en el repositorio.
4. **T4 (producción mínima, si D4 se aprueba):** `anio` estricto. RED: WebMvc con el mapper real y BD.
5. **T5 (solo pruebas):** conteo de sentencias del listado de temporadas; guarda de códigos documentados.
6. **T6:** `odd/tasks/fase-1-cierre.md` con la matriz de requisitos (L4), los criterios de cierre (L8) con su evidencia y la lista de deuda aceptada.
7. **T7:** `./mvnw clean verify`, `./mvnw verify -Pdb-tests`, `gentle-ai review assess` (T4 toca producción: se evalúa; el resto es de bajo riesgo), PR y CI remoto, con la evidencia en Engram sin forzar y sin commits repetitivos.

Commits previstos (≈400 líneas cada uno): **C1** T1; **C2** T2; **C3** T3+T5 (pruebas); **C4** T4 (solo si D4); **C5** T6/T7 (evidencia).

### L8 — Criterios objetivos de cierre de la Fase 1

La Fase 1 se declara **terminada** cuando se cumplen **todos**:

1. **C1 — Suites:** `./mvnw clean verify` y `./mvnw verify -Pdb-tests` con 0 fallos, 0 errores y 0 omitidos, localmente y en CI (ambos checks obligatorios en el PR de cierre), con los conteos registrados.
2. **C2 — Esquema:** `db/migration` contiene solo V1–V4, ninguna modificada después de su commit original, y `V4MigracionEstaticaTest` en verde.
3. **C3 — Contrato:** las 11 rutas de la Fase 1 coinciden entre código, `OpenApiConfig.RUTAS`, inventario de rutas, tablas de `docs/` y especificación OpenAPI (pruebas en verde, sin literales manuales de conteo tras T3).
4. **C4 — Documentación sin contradicciones:** H1–H4 corregidos; ninguna decisión ya tomada figura como abierta; `docs/` y `odd/tasks/` coinciden con el código.
5. **C5 — Seguridad:** matriz de aislamiento de FAMILIA y de escuelas en verde; ninguna ruta de escritura bajo `/api/familia`; `ContratoDniTest` y las pruebas del JSON serializado en verde; ningún dato sensible en la auditoría.
6. **C6 — Concurrencia:** pruebas de activación y de edición en verde y mutaciones registradas.
7. **C7 — Requisitos:** matriz RF-07/RNF/D/O completa en `fase-1-cierre.md`, con la única brecha (`licencia`) aceptada y diferida a la Fase 5.
8. **C8 — Trazabilidad ODD:** artefactos `temp-1`…`temp-5` con su evidencia remota; Engram con el estado final por `project` explícito y la deuda de conflictos declarada.
9. **C9 — RDD:** `assess` ejecutado sobre el rango de la unidad y, si corresponde, revisión nativa aprobada.
10. **C10 — Hallazgos:** cero bloqueantes abiertos; mejoras recomendadas aplicadas o diferidas explícitamente por el usuario; deuda aceptada listada.

### L9 — Smoke controlado contra Supabase (S5)

**Recomendación: no es necesario para cerrar la Fase 1 y, en su forma completa, no es seguro.** Razones:
- No hay migraciones nuevas: las tablas `temporada` y `deportista_temporada` existen desde V1 y Flyway 1–4 ya está aplicado y verificado en Supabase (`FAMILIAS-DEPORTISTAS.md` §7).
- Todo el comportamiento riesgoso (activación, visibilidad, concurrencia, aislamiento) se prueba con PostgreSQL 17 real en Testcontainers, que ejercita las mismas restricciones.
- **Activar una temporada en la base compartida desactivaría la temporada real y publicaría u ocultaría datos a las familias reales**; un smoke que verifique la visibilidad de FAMILIA exige activar. No hay "escuela de pruebas" separada en esa base.
- Si el usuario lo quisiera igual, existe una variante acotada y reversible, **solo con autorización explícita**: crear una temporada de un año lejano (p. ej. 2099) **sin activarla**, un deportista del smoke, su dato deportivo, editarlo, ver el plantel y el listado con el DNI enmascarado y confirmar la codificación UTF-8 del `•` por HTTP real, y luego limpiar con un SQL análogo a `limpiar-smoke-familias.sql`. No verifica la lectura de FAMILIA (que requiere activación). Sería un `smoke-temporadas.sh` nuevo (fuera de esta unidad si no se aprueba).

### L10 — Riesgos de la unidad

- **R1 (bajo):** T3 toca pruebas de seguridad estructural; mitigación: se conserva el inventario explícito y se agrega una guarda; no se debilita ninguna comprobación.
- **R2 (bajo):** T4 endurece una entrada (rechaza decimales y cadenas numéricas en `anio`); es un cambio de contrato menor sin consumidores.
- **R3 (proceso):** la documentación de la Fase 1 está repartida en 4 archivos (`TEMPORADAS.md`, `FAMILIAS-DEPORTISTAS.md`, `SEGURIDAD.md`, `DECISIONES.md`); `fase-1-cierre.md` será el índice.
- **R4 (proceso):** Engram sigue rechazando la resolución automática de conflictos históricos; no se fuerza.
- Clasificación esperada: ningún ítem de alto riesgo (T4 puede tocar un contrato de entrada, no hay migración ni autorización nueva).

### L11 — Decisiones que necesitan aprobación

| ID | Decisión | Recomendación |
| --- | --- | --- |
| D1 | Alcance de `temp-5` | H1–H5 (documentación y trazabilidad) obligatorias; H6, H8 y H9 (pruebas) recomendadas; H7 según D4 |
| D2 | Conteos de rutas (H6) | Derivar los literales de `OpenApiConfig.RUTAS` y de la tabla de `docs/`, más la guarda de completitud de controladores; conservar `InventarioRutasTest` explícito |
| D3 | Hoja de cierre | Crear `odd/tasks/fase-1-cierre.md` como índice y matriz de requisitos |
| D4 | Endurecer `anio` (rechazar `2027.9` y `"2027"`) | Sí, con prueba WebMvc con el mapper real; confirmar primero que el mapper de la aplicación se comporta como el probe |
| D5 | Smoke contra Supabase | No ejecutarlo para cerrar; ofrecer la variante acotada solo si el usuario la pide expresamente |
| D6 | Estado de las decisiones en `DECISIONES.md` | Marcar O10 y O13 como aprobadas e implementadas; dejar O1–O7, O9, O11–O12 abiertas |
| D7 | Cierre de la Fase 1 | Declararla cerrada con los criterios C1–C10 y abrir la Fase 2 (spike federativo) solo cuando se cierre este PR; Fase 2 sigue bloqueada por O12 |

### L12 — Aprobación y condiciones (verbatim)

> Apruebo implementar `temp-5-cierre` siguiendo ODD.
>
> **Decisiones D1–D7 aprobadas**, con estas condiciones:
>
> 1. Corregir las contradicciones documentales H1–H4.
> 2. Incorporar la evidencia remota pendiente de temp-4.
> 3. Reducir los conteos manuales de rutas sin debilitar el inventario explícito de seguridad ni la validación OpenAPI.
> 4. Agregar las pruebas de consultas ADMIN y coherencia de códigos de error, manteniéndolas acotadas al alcance de Temporadas.
> 5. Para H7, reproducir primero la coerción de `anio` mediante el endpoint HTTP y la configuración real de la aplicación. Si se confirma, implementar validación estricta para aceptar solo enteros JSON entre 2020 y 2100. Rechazar decimales y cadenas numéricas con HTTP 400. Evitar modificaciones globales de Jackson.
> 6. Crear `odd/tasks/fase-1-cierre.md` con la matriz de requisitos, cobertura, evidencia y deuda aceptada.
> 7. Marcar O10 y O13 como implementadas únicamente después de verificar su evidencia.
> 8. No ejecutar smoke ni modificar datos de Supabase.
> 9. No modificar migraciones V1–V4.
> 10. Ejecutar ambas suites completas y aplicar el protocolo RDD correspondiente.
> 11. Mantener los artefactos ODD locales y sincronizarlos con Engram cuando sea posible, sin forzar ni reescribir memorias históricas.
>
> Los conflictos antiguos de Engram no bloquean el cierre si los artefactos locales están íntegros y la sincronización pendiente queda documentada.
>
> Autorizo implementación y commits locales. No autorizo push ni merge todavía.
>
> Al terminar, presentar cambios realizados, pruebas, evidencia de H7, criterios C1–C10, commits y riesgos residuales.

### L13 — Evidencia de implementación

**Commits locales** (rama `feat/temp-5-cierre`, sobre `main` = `d12afe9`): `b8c9eb1` plan y aprobación; `df2e24a` evidencia remota de `temp-4`; `03e1673` año estricto (H7); `59692d5` conteos de rutas derivados y guarda de controladores (H6); `89b15a8` coherencia de códigos de error y sentencias del listado (H9, H8); `05a9dc2` corrección de la documentación (H1–H4); y el commit de esta evidencia con `fase-1-cierre.md`.

**Suites:** `./mvnw clean verify` = **891 tests, 0 fallos/errores/omitidos** (885 + 6). `./mvnw verify -Pdb-tests` = **408 tests, 0/0/0** (403 + 5). Sin migraciones; V1–V4 sin tocar.

**H7: reproducción y corrección.** Reproducción antes de tocar el código, por HTTP real (cadena de seguridad completa, PostgreSQL 17 descartable y el mapeador **real** de la aplicación), `TemporadaEntradaHttpDbTest`:
- `{"anio":2027.9}` → **`201`** (creó la temporada 2027); a continuación `{"anio":2027.0}`, `{"anio":2.027e3}`, `{"anio":"2027"}` y `{"anio":" 2027 "}` → **`409`** (coaccionados a 2027 y chocaron con la temporada ya creada) y `{"anio":1e3}` → `400 VALIDACION` (coaccionado a 1000, fuera de rango). Es decir, la aplicación real **sí** convertía en silencio decimales y cadenas.
- RED también en `TemporadaAdminControllerWebMvcTest` (el servicio recibía el año 2027 en lugar de un `400`).

Corrección (`03e1673`): un `ValueDeserializer` **solo para ese campo** (`AnioEstricto`, aplicado con `@JsonDeserialize` en `TemporadaSolicitud`), sin tocar la configuración global de Jackson ni otros endpoints: acepta únicamente `VALUE_NUMBER_INT` que entre en un `int`; el resto es cuerpo ilegible → `400 SOLICITUD_INVALIDA` sin eco del valor; `null`/ausente/fuera de rango → `400 VALIDACION`. Después: 16/16 en WebMvc y 3/3 por HTTP real; la especificación OpenAPI sigue describiendo `anio` como entero.

**Mutaciones de las guardas nuevas** (código restaurado y verificado): (a) un código documentado que deja de existir → falla; (b) cambia el estado HTTP con el que se lanza → falla; (c) se lanza un código de la Fase 1 sin documentar → falla; (d) un controlador sale del contexto de OpenAPI → `ControladoresDeLaEspecificacionTest` falla con el mensaje que indica qué agregar. **Incidente de proceso:** al restaurar con `git checkout -- <directorio>` revertí por error la edición sin commitear de `TemporadaSolicitud`; lo detecté en el `git status`, la reapliqué y volví a ejecutar las pruebas de H7 (16/16 y 3/3) antes de commitear. Las mutaciones (a) a (c) corrieron sin ese cambio y no se ven afectadas.

**Condiciones del usuario (L12):** (1) H1–H4 corregidos; (2) evidencia de `temp-4` incorporada; (3) conteos derivados sin tocar `InventarioRutasTest` ni la comparación 1:1 con la especificación; (4) pruebas de consultas ADMIN y de códigos de error acotadas a Temporadas; (5) H7 reproducido por HTTP real y corregido a nivel de campo; (6) `fase-1-cierre.md` creado; (7) O10 y O13 marcadas **tras verificar su evidencia** (workflow, protección de `main` leída por API, pruebas); (8) sin smoke ni Supabase; (9) V1–V4 intactas; (10) ambas suites y `assess`; (11) artefactos locales íntegros, Engram sincronizable con `project` explícito.

**RDD:** `gentle-ai review assess` sobre el rango (base `d12afe9`, 18 archivos, 731 líneas): riesgo **medio**, `review_due: false` (`under_budget`). La única línea de producción nueva es la lectura estricta de un campo de entrada, un cambio pedido y sin consumidores; sin ítem de alto riesgo. No se inició revisión. `Risk: none`.

**Riesgos residuales:** ver `fase-1-cierre.md` §5 (A1–A12). Nuevos de esta unidad: ninguno; el endurecimiento de `anio` rechaza entradas que antes se aceptaban en silencio (sin consumidores). Pendiente: CI remoto del PR de `temp-5` (criterio C1) y sincronización de los espejos de Engram de `temp-1`/`temp-2`.

