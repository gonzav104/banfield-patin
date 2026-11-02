# Fase 1 — Temporadas y DeportistaTemporada: informe de cierre

Estado: **CERRADA.** `temp-5-cierre` se fusionó en `main` (PR #8, merge `d367137`, 2026-11-02) con los dos checks en verde.
Alcance: `temp-1-temporada`, `temp-2-deportista-temporada`, `temp-3-lectura-familia`, `temp-4-dni-enmascarado` y `temp-5-cierre`, sobre `CI-0` (`ci-1`…`ci-3`). No incluye Documentos, Aptos, Licencias ni Competencias.
Este documento es el **índice** de la Fase 1: remite a los artefactos de cada unidad (`odd/tasks/temp-N-*.md`) y al contrato técnico (`docs/TEMPORADAS.md`, `docs/FAMILIAS-DEPORTISTAS.md`, `docs/SEGURIDAD.md`, `docs/DECISIONES.md`).

## 1. Unidades y evidencia remota

| Unidad | PR | Merge en `main` | Suites en CI (normal / db) | Artefacto |
| --- | --- | --- | --- | --- |
| `ci-1-suite-normal` | #1 | `dacbdbe` | 766 / — | `ci-1-suite-normal.md` |
| `ci-2-suite-db` | #2 | `4c98d48` | 766 / 324 | `ci-2-suite-db.md` |
| `ci-3-proteccion` | #3 | `356b32c` | 766 / 324 | `ci-3-proteccion.md` |
| `temp-1-temporada` | #4 | `660eb28` | 801 / 341 | `temp-1-temporada.md` |
| `temp-2-deportista-temporada` | #5 | `549fa65` | 847 / 367 | `temp-2-deportista-temporada.md` |
| `temp-3-lectura-familia` | #6 | `da823be` | 865 / 398 | `temp-3-lectura-familia.md` |
| `temp-4-dni-enmascarado` | #7 | `d12afe9` | 885 / 403 | `temp-4-dni-enmascarado.md` |
| `temp-5-cierre` | #8 | `d367137` | 891 / 408 | `temp-5-cierre.md` |

Todas las ejecuciones remotas terminaron con 0 fallos, 0 errores y 0 omitidos. `main` está protegida con PR obligatorio y los checks `Suite normal` y `Suite db (PostgreSQL 17)` obligatorios (comprobado por la API de GitHub el 2026-11-02).

## 2. Contrato de la Fase 1: 11 rutas

| Ruta | Rol | Unidad |
| --- | --- | --- |
| `GET /api/admin/temporadas` | ADMIN | `temp-1` |
| `POST /api/admin/temporadas` | ADMIN | `temp-1` (y año estricto en `temp-5`) |
| `GET /api/admin/temporadas/{id}` | ADMIN | `temp-1` |
| `POST /api/admin/temporadas/{id}/activar` | ADMIN | `temp-1` |
| `GET /api/admin/deportistas/{deportistaId}/temporadas` | ADMIN | `temp-2` |
| `POST /api/admin/deportistas/{deportistaId}/temporadas` | ADMIN | `temp-2` |
| `GET /api/admin/deportistas/{deportistaId}/temporadas/{temporadaId}` | ADMIN | `temp-2` |
| `PUT /api/admin/deportistas/{deportistaId}/temporadas/{temporadaId}` | ADMIN | `temp-2` |
| `GET /api/admin/temporadas/{temporadaId}/deportistas` | ADMIN | `temp-2` |
| `GET /api/familia/deportistas/{deportistaId}/temporadas` | FAMILIA | `temp-3` |
| `GET /api/familia/deportistas/{deportistaId}/temporadas/{temporadaId}` | FAMILIA | `temp-3` |

Cambios de contrato en rutas existentes: `GET /api/admin/deportistas` (`DeportistaResumen.dni` → `dniEnmascarado`) y los tutores anidados en `FamiliaDetalle` (`TutorResumen.dniEnmascarado`), ambos en `temp-4`. Sin migraciones: `db/migration` = V1–V4, sin modificar desde el commit de V4 (`8381ebf`).

## 3. Matriz de requisitos, cobertura y evidencia

| Requisito | Resultado | Cobertura (pruebas) | Evidencia |
| --- | --- | --- | --- |
| **RF-07** categoría, rama, divisional, número de corredor y estado deportivo por temporada; cambiar 2027 no modifica 2026 | Cumplido | Unitarias/WebMvc 44, BD 28 (`cambiarDatosDeUnAnioNoTocaLosDeOtro`, edición con 2026 intacta) | `temp-2` L12 |
| RF-07 **número/tipo de licencia** | **Brecha aceptada, diferida a la Fase 5** (vive en la tabla `licencia`) | — | `temp-5` L5 (A9) |
| **O10** cero o una activa por escuela; nueva = inactiva | Cumplida | Temporadas: unitarias/WebMvc 35, BD 27 (índice parcial, activación concurrente, sin activa) | `DECISIONES.md` O10 |
| Activación concurrente respeta la unicidad | Cumplido | 15 rondas de hilos libres, retenciones deterministas, SQL efectivo (`FOR NO KEY UPDATE`), `lock_timeout` | `temp-1` L11; 4 mutaciones |
| Edición concurrente de datos deportivos | Cumplido (con limitación **A1**) | BD 28 (rondas, bloqueo de fila, otra fila no se frena, activación retenida no frena) | `temp-2` L12; 4 mutaciones |
| FAMILIA solo ve temporadas visibles de deportistas autorizados | Cumplido | FAMILIA: unitarias/WebMvc 18, BD 32 (matriz de familias, escuelas y estados de vínculo; HTTP real) | `temp-3` L16; 6 mutaciones |
| Aislamiento entre escuelas y familias | Cumplido | FK compuestas de V1 + `WHERE` único; matrices de BD y HTTP | `temp-1`…`temp-3` |
| Visibilidad por año de la temporada activa; sin activa, nada | Cumplido | `activarUnaTemporadaHaceVisibles…`, `sinTemporadaActivaNoHayNingunaVisible…` | `temp-3`; `TEMPORADAS.md` §9 |
| **D6 / O8** DNI enmascarado en listados; completo en detalles autorizados | Cumplido | DNI: 23 pruebas (reflexión, JSON serializado, OpenAPI, BD) | `temp-4` L14; 5 mutaciones |
| **D1** FAMILIA no escribe fuera de workflows explícitos | Cumplido: la Fase 1 no agrega escritura de FAMILIA | `InventarioRutasTest` (portal = 5 rutas `GET`) | `temp-3` |
| **RNF-05** auditoría de acciones sensibles | Cumplido: 4 acciones, solo ids, año y nombres de campo | Auditoría persistida y sin valores deportivos | `temp-1`, `temp-2` |
| **RNF-11** integridad por restricciones y transacciones | Cumplido | Índices de V1 como red de seguridad + mapeo a 409 | pruebas de BD |
| **RNF-13** pruebas de flujos críticos | Cumplido | 891 normales y 408 de BD | CI de los PR #1 a #7 |
| **RNF-14** OpenAPI coherente con las rutas | Cumplido | Rutas mapeadas = `OpenApiConfig.RUTAS` = especificación; conteos derivados; guarda de controladores | `temp-5` L13 |
| **RNF-18** multi-escuela | Cumplido | Todas las consultas llevan la escuela; FK compuestas | `temp-1`…`temp-3` |
| **O13** CI de dos jobs obligatorios | Cumplida | Workflow + protección de `main` + 7 PR | `DECISIONES.md` O13 |

## 4. Criterios de cierre C1–C10

| Criterio | Estado | Evidencia |
| --- | --- | --- |
| **C1** Suites en verde, local y en CI | ✅ Local 891/408; CI remoto del PR #8 (HEAD `64a5714`): runs `41374996542` (push) y `41375001632` (pull_request), ambos 891/408 sin fallos; `main` tras el merge: run `41376178593` verde | `temp-5` L14 |
| **C2** Solo V1–V4, sin modificar | ✅ | `git log -- db/migration` (V4 `8381ebf` es el último); `V4MigracionEstaticaTest` |
| **C3** Contrato coincidente (código, `RUTAS`, inventario, `docs/`, OpenAPI) | ✅ | `OpenApiRutasTest`, `RutasDocumentadasTest`, `InventarioRutasTest`, `ControladoresDeLaEspecificacionTest` |
| **C4** Documentación sin contradicciones | ✅ | H1–H4 corregidos (commit `05a9dc2`); ver §6 |
| **C5** Seguridad (aislamiento, sin escritura de FAMILIA, DNI, auditoría) | ✅ | matrices de aislamiento; `ContratoDniTest`; auditoría sin datos sensibles |
| **C6** Concurrencia con mutaciones registradas | ✅ | 19 mutaciones detectadas en `temp-1`…`temp-4` (4 + 4 + 6 + 5) |
| **C7** Matriz de requisitos con brecha aceptada | ✅ | §3 de este documento (única brecha: licencia → Fase 5) |
| **C8** Trazabilidad ODD y Engram | ✅ parcial | Artefactos `temp-1`…`temp-5` con evidencia remota; **Engram:** estado, PR y merge de `temp-2`…`temp-4` guardados con `project` explícito; espejos `odd/temp-1-temporada/tasks` y `odd/temp-2-deportista-temporada/tasks` desactualizados; conflictos históricos sin resolver (deuda aceptada) |
| **C9** RDD | ✅ | `assess` del rango de `temp-5`: riesgo medio, `review_due: false`; revisiones nativas aprobadas en `temp-1` (concurrencia) y `temp-3` (autorización) |
| **C10** Hallazgos | ✅ | 0 bloqueantes abiertos; H6–H9 aplicados; deuda aceptada en §5 |

## 5. Deuda técnica aceptada

| ID | Deuda | Motivo |
| --- | --- | --- |
| A1 | Un `PUT` con datos viejos pisa un cambio posterior (gana la última escritura) | No hay versión por fila; agregarla exige una migración. Probado y documentado (D6) |
| A2 | Reactivar una temporada anterior oculta años posteriores a las familias | Regla de visibilidad aprobada; documentada |
| A3 | `SUSPENDIDO` visible a la familia sin motivo | El esquema no guarda motivo |
| A4 | Búsqueda por sufijo del DNI | Mejora futura documentada |
| A5 | CI: ejecuciones duplicadas `push`/`pull_request`, `suite-db` sin reportes si falla, checks identificados por nombre | Mejoras de `ci-2`/`ci-3` ya registradas |
| A6 | Categoría, rama y divisional como texto libre | No hay catálogo oficial; se validan al guardar |
| A7 | Sin prueba de carga ni de rendimiento | Fase 10 |
| A8 | Conflictos de Engram sin resolver y espejos de `temp-1`/`temp-2` desactualizados | Deuda de proceso; el ODD local es la fuente durable |
| A9 | RF-07 número/tipo de licencia | Fase 5 |
| A10 | La respuesta de un dato deportivo de ADMIN no indica si su temporada es la vigente | Consultable en `GET /api/admin/temporadas`; ficha en la Fase 9 |
| A11 | `H8`: sin mutación que induzca un N+1 en el listado de temporadas | Inducirlo exigiría alterar la producción solo para la prueba; el conteo exacto (1 sentencia) lo vigila |
| A12 | `REVISION-DOCUMENTAL.md` cita líneas de pruebas por número (p. ej. `InventarioRutasTest:192`) | Es un registro histórico de la revisión de la Fase 0; no se reescribe |

## 6. Hallazgos de `temp-5` y su resolución

| ID | Hallazgo | Resolución |
| --- | --- | --- |
| H1 | `SEGURIDAD.md` decía que el `lock_timeout` era solo de vínculos y que no había límite global | Reescrito: dos propiedades, un mecanismo, sin límite global (`05a9dc2`) |
| H2 | `FAMILIAS-DEPORTISTAS.md` §9 decía que `deportista_temporada` estaba fuera de alcance | Actualizado con remisión a `TEMPORADAS.md` (`05a9dc2`) |
| H3 | `DECISIONES.md` listaba O10 y O13 como abiertas | Marcadas aprobadas e implementadas **tras verificar su evidencia** (workflow, protección de `main`, pruebas) (`05a9dc2`) |
| H4 | `ROADMAP.md` sin estados; mencionaba `FOR UPDATE` en lugar de `FOR NO KEY UPDATE` | Columna de estado con los PR; corrección del bloqueo (`05a9dc2`) |
| H5 | Evidencia remota de `temp-4` y trazabilidad | Registrada en `temp-4-dni-enmascarado.md` L15 (`df2e24a`) y en este índice |
| H6 | Conteos de rutas escritos a mano | Derivados de `OpenApiConfig.RUTAS` y de las rutas del código; guarda de completitud de controladores; el inventario explícito de rutas mutantes **no cambió** (`59692d5`) |
| H7 | El año aceptaba `2027.9`, `"2027"`, `2027.0`, `1e3` | **Reproducido por HTTP real con la configuración real** (`2027.9` → `201`; `2027.0`, `2.027e3`, `"2027"` → coaccionados a 2027 y chocaron con `409`; `1e3` → 1000): ahora solo un entero JSON; el resto `400` (`03e1673`) |
| H8 | Sin prueba de sentencias del listado de temporadas | `TemporadaListadoDbTest` (`89b15a8`) |
| H9 | Sin coherencia entre códigos de error documentados y el código | `CodigosDeErrorDeTemporadasTest` (`89b15a8`), con 3 mutaciones detectadas; la guarda de controladores del H6, con 1 |
