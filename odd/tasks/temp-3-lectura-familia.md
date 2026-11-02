# TEMP-3 — Lectura FAMILIA de los datos deportivos anuales

Objetivo: que FAMILIA consulte, solo en lectura, los datos deportivos anuales de sus deportistas con vínculo ACTIVO. Estado: **IMPLEMENTADO localmente (commits locales; sin push ni merge); revisión nativa RDD en curso.**
Rama: `feat/temp-3-lectura-familia` (desde `main` = `549fa65`). Entrega: `ask-on-risk`; sin push, merge ni migraciones. Runner: `./mvnw clean verify` y `./mvnw verify -Pdb-tests`.

## Specs

- **S1** — "permitir que FAMILIA consulte la información deportiva anual de sus deportistas vinculados, reutilizando los contratos y las reglas de seguridad existentes."
- **S2** — "Autorizar únicamente deportistas con vínculo familiar ACTIVO, dentro de la misma escuela."
- **S3** — "Respetar la revalidación existente de sesión y familia activa."
- **S4** — "Mantener visible un deportista globalmente inactivo cuando conserva un vínculo familiar ACTIVO, según el contrato actual."
- **S5** — "Devolver 404 uniforme para UUID inexistentes, ajenos, de otra escuela o vínculos revocados."
- **S6** — "Evitar filtraciones mediante listados, filtros, respuestas de error o diferencias de comportamiento."
- **S7** — "No crear endpoints de escritura para FAMILIA."
- **S8** — "Mantener separado el estado global del deportista de su estado deportivo anual."
- **S9** — "Diseñar consultas eficientes, sin N+1." / "Definir un ciclo TDD real con RED antes de implementar."
- **S10** — "Por ahora solo descubrimiento y planificación. No implementar código Java, no modificar migraciones ni Supabase, no hacer push ni merge."

## Tasks

| ID | S# | Tarea | Ruta | Estado | Evidencia |
| --- | --- | --- | --- | --- | --- |
| T0 | — | Preparación y descubrimiento | inline | hecho | `3ebd304` (evidencia de temp-2); este documento |
| T1 | S2, S5 | Separar `ALCANCE_FAMILIA` en tramos (mismo texto) con prueba de equivalencia | inline | hecho | commit del refactor; RED/GREEN en L14 |
| T2 | S1, S8 | DTO de solo lectura y consultas con alcance de FAMILIA | inline | hecho | `aa0d1b5`; RED en L15 |
| T3 | S1–S6 | Servicio del portal: historial y detalle por temporada | inline | hecho | `aa0d1b5`; RED en L15 |
| T4 | S7 | Controlador, `OpenApiConfig.RUTAS`, inventario, docs | inline | hecho | `aa0d1b5`; RED en L15 |
| T5 | S2–S6 | Pruebas de aislamiento y de sesión en BD (HTTP real) | inline | hecho | `bede9f7`; RED en L15; 6 mutaciones detectadas (L16) |
| T6 | — | Cierre: suites, diferencial, Risk, revisión nativa | inline | en curso | suites verdes (L16); revisión nativa pendiente de consentimiento |

Ruta: todo inline. Estimación: ~250 líneas de producción y ~700 de pruebas. Cortes de commit (≈400 líneas): **C1** T1 (refactor de seguridad, solo), **C2** T2–T4 (lectura), **C3** T5 (aislamiento en BD), **C4** documentación ODD. T1 va solo por tocar una guarda (ver R1).

## Log

### L1 — Pedido original (verbatim)

> Vamos a comenzar el descubrimiento de la unidad ODD `temp-3-lectura-familia` de Banfield Patín Carrera.
>
> **Objetivo:** permitir que FAMILIA consulte la información deportiva anual de sus deportistas vinculados, reutilizando los contratos y las reglas de seguridad existentes.
>
> **Seguridad obligatoria**
> - Autorizar únicamente deportistas con vínculo familiar ACTIVO, dentro de la misma escuela.
> - Respetar la revalidación existente de sesión y familia activa.
> - Mantener visible un deportista globalmente inactivo cuando conserva un vínculo familiar ACTIVO, según el contrato actual.
> - Devolver 404 uniforme para UUID inexistentes, ajenos, de otra escuela o vínculos revocados.
> - Evitar filtraciones mediante listados, filtros, respuestas de error o diferencias de comportamiento.
> - No crear endpoints de escritura para FAMILIA.
>
> **Restricciones**
> Por ahora solo descubrimiento y planificación. No implementar código Java, no modificar migraciones ni Supabase, no hacer push ni merge.
>
> Entregar el contrato HTTP propuesto, reglas de autorización, matriz de pruebas, dependencias, riesgos y decisiones pendientes de aprobación.

(El pedido completo, con el resto de la preparación y los lineamientos de calidad, está en la conversación de origen; aquí se citan las cláusulas que fijan los requisitos.)

### L2 — Descubrimiento: lo que ya existe y se reutiliza

- **Portal actual** (`/api/familia/**`, `FamiliaPortalController` + `FamiliaPortalService`): 3 rutas `GET` (`mi-familia`, `deportistas`, `deportistas/{id}`). La identidad sale del JWT; una cuenta sin `familiaId` no consulta la base; los DTO omiten `escuelaId`, `esPrincipal`, datos del vínculo y marcas de tiempo; las lecturas no se auditan (`docs/SEGURIDAD.md` "Aislamiento de FAMILIA", `docs/FAMILIAS-DEPORTISTAS.md` §6).
- **Autorización = el `WHERE` de una consulta** (`FamiliaDeportistaRepository.ALCANCE_FAMILIA`): une vínculo, familia y deportista por id **y** escuela y exige vínculo `ACTIVO` y `f.activa = true`. No hay predicado sobre `d.activo`: un deportista inactivo con vínculo ACTIVO sigue visible.
- **Revalidación central de sesión** (usuario, escuela y familia activos, rol y familia coherentes, sin caché) antes de cualquier controlador: una familia inactiva, un usuario desactivado o un vínculo revocado rigen desde la siguiente solicitud con el mismo token.
- **Pruebas de aislamiento existentes** (modelo a seguir): `FamiliaPortalAislamientoDbTest` (matriz de familias, escuelas y estados de vínculo), `FamiliaPortalHttpDbTest` (404 con cuerpo idéntico byte a byte, matriz de JWT viejo, base caída, sin escrituras ni auditoría), `FamiliaPortalControllerWebMvcTest` y `FamiliaPortalServiceTest`.
- **Datos disponibles** (`temp-2`): `deportista_temporada` con `categoria`, `rama`, `divisional`, `numero_corredor`, `estado_deportivo`; la temporada aporta `anio` y `activa`. **No hace falta migración.**
- **Restricción de V1 que se aprovecha:** `uq_temporada_activa_por_escuela` garantiza a lo sumo una activa por escuela, así que unir contra "la temporada activa" no multiplica filas.

### L3 — Diferencias y hallazgos

1. **`ALCANCE_FAMILIA` no se puede extender tal cual:** es un texto `from … join … where …` y una consulta nueva necesita más `join` **antes** del `where`. Hay que partirlo en tramos (`from/join` y `where`) conservando el texto resultante idéntico. Es un cambio sobre una **guarda de seguridad** (ítem 2 de la tabla de riesgo): va en su commit, con una prueba que fija `ALCANCE_FAMILIA == JOINS + WHERE` y con la batería de aislamiento existente sin modificar.
2. **El historial vacío es ambiguo si es una sola consulta:** "deportista ajeno" y "propio sin temporadas visibles" devuelven ambos cero filas. Para responder `404` al primero y `200 []` al segundo sin oráculo, la consulta del historial lleva el alcance completo y, **solo si vino vacía**, una segunda consulta con alcance comprueba el vínculo (a lo sumo 2 sentencias; 1 en el caso normal).
3. **Las temporadas "de borrador":** `temp-2` permite cargar datos en una temporada inactiva "para preparar el año siguiente". Si FAMILIA viera toda fila existente, vería esos datos antes de que el ADMIN los publique. Una temporada inactiva puede ser pasada o futura; solo `activa` y `anio` permiten distinguirlas (D1).
4. **Estados de la familia y del deportista son planos distintos:** el portal ya expone `activo` (global) en el deportista; el estado deportivo anual (`ACTIVO/INACTIVO/SUSPENDIDO`) vive en la fila de temporada y **no** se mezcla con aquel (S8).
5. **Pruebas de conteo de rutas:** agregar rutas bajo `/api/familia` obliga a actualizar `InventarioRutasTest` (el portal pasa de 3 a 5 rutas), `OpenApiRutasTest` (rutas 48→50 y documentadas 24→26), `OpenApiValidezTest` (paths 38→40, `operationId` 48→50), `OpenApiArranqueDbTest` (48→50), `OpenApiConfig.RUTAS`, `OpenApiSinDatosSensiblesTest` (lista de campos prohibidos del portal) y la tabla de rutas de `docs/FAMILIAS-DEPORTISTAS.md` (la exige `RutasDocumentadasTest` para controladores del paquete `familias.portal`).

### L4 — Contrato HTTP propuesto (solo `GET`, rol FAMILIA, `/api/familia/**`)

La familia y la escuela salen del JWT; `familiaId`, `escuelaId` o `usuarioId` en la solicitud se ignoran.

| Ruta | Éxito | Errores |
| --- | --- | --- |
| `GET /api/familia/deportistas/{deportistaId}/temporadas` | `200` lista de temporadas **visibles** con datos cargados, del año más nuevo al más viejo (acotada: a lo sumo 81); `[]` si el deportista es propio y no tiene ninguna visible | `404 DEPORTISTA_NO_ENCONTRADO` (inexistente, ajeno, de otra escuela, vínculo revocado/pendiente/rechazado, familia sin alcance) |
| `GET /api/familia/deportistas/{deportistaId}/temporadas/{temporadaId}` | `200` | `404 DEPORTISTA_NO_ENCONTRADO` (igual que arriba); `404 DEPORTISTA_TEMPORADA_NO_ENCONTRADA` (deportista propio y temporada inexistente, de otra escuela, sin fila para ese deportista o no visible) |

Transversales: `401 NO_AUTENTICADO` sin sesión o con sesión revalidada como no vigente (cookie borrada), `403 ACCESO_DENEGADO` para ADMIN, `400 SOLICITUD_INVALIDA` para un UUID mal formado (sin llegar al servicio), `405`/`403 CSRF_INVALIDO` para cualquier método que escriba. Sin paginación (bounded), sin filtros ni parámetros de búsqueda: menos superficie para filtrar información.

Respuesta de una temporada: `{temporadaId, anio, temporadaActiva, categoria, rama, divisional, numeroCorredor, estadoDeportivo}`.
- **Se muestran:** el año, si es la temporada vigente (`temporadaActiva`) y los cinco datos deportivos.
- **Permanecen ocultos:** el `id` de la fila, `escuelaId`, `deportistaId` (ya está en la ruta), marcas de tiempo, datos de auditoría, y cualquier dato de otro deportista o familia. **No** se repite `deportista.activo` (S8: ese indicador sigue en `GET /api/familia/deportistas[/{id}]`).
- El `toString` del DTO no imprime datos (como los demás DTO del portal).

### L5 — Reglas de autorización

1. **El `WHERE` es la autorización.** Cada consulta une vínculo `ACTIVO` + familia `activa` + deportista por id y escuela (los mismos predicados de `ALCANCE_FAMILIA`) y además la fila de temporada y la regla de visibilidad; nada se carga para comparar después.
2. **Visibilidad de temporadas (D1, recomendada B):** una fila es visible si `t.anio <= anio de la temporada activa de la escuela`; **sin temporada activa no se ve ninguna** (falla cerrado). Se resuelve en la misma consulta con `join Temporada ta on ta.escuelaId = fd.escuelaId and ta.activa = true and ta.anio >= t.anio` (a lo sumo una fila por `uq_temporada_activa_por_escuela`). Como `temp-1` no tiene "desactivar", no tener activa solo ocurre antes de la primera activación de una escuela.
3. **Deportista inactivo con vínculo ACTIVO:** su historial se ve (igual que su ficha). Con vínculo revocado, no.
4. **Misma respuesta para lo ajeno:** el `404` de deportista y el de temporada tienen mensaje fijo y cuerpo idéntico byte a byte; nunca se diagnostica qué falta (a diferencia del ADMIN). La segunda consulta del historial vacío no cambia el resultado observable.
5. **Sin escritura ni auditoría:** ninguna ruta muta; las lecturas no escriben `auditoria`.
6. **Sin acoplar permisos:** las consultas del portal viven aparte de las del ADMIN (no se reutiliza el servicio ADMIN ni sus 404 diagnósticos); lo único compartido es el alcance de FAMILIA y las entidades.

### L6 — Diseño técnico propuesto

- `FamiliaDeportistaRepository`: `ALCANCE_FAMILIA` se parte en `ALCANCE_FAMILIA_JOINS` y `ALCANCE_FAMILIA_FILTRO`; `ALCANCE_FAMILIA` queda igual a la concatenación (las tres consultas actuales no cambian). Dos consultas nuevas: `temporadasDelPortal(familiaId, escuelaId, deportistaId)` y `temporadaDelPortal(familiaId, escuelaId, deportistaId, temporadaId)`, ambas `select new … TemporadaDeFamilia(...)`, y `deportistaEnAlcance(...)` (existencia, solo para el historial vacío).
- Nuevo paquete `familias.portal`: `FamiliaPortalTemporadasService` y `FamiliaPortalTemporadasController` (mismo prefijo `/api/familia`) y `dto/TemporadaDeFamilia`. No se toca `FamiliaPortalService` ni `FamiliaPortalController`.
- Techos de sentencias de Hibernate: historial ≤ 2 (1 en el caso normal), detalle ≤ 1, con cualquier cantidad de filas.
- Índices: la consulta recorre el vínculo por `(familia, deportista)` y la fila por `uq_deportista_temporada`; con 20–500 deportistas por escuela no hace falta un índice nuevo.

### L7 — Matriz de pruebas

**Unitarias** (`FamiliaPortalTemporadasServiceTest`, `TemporadaDeFamiliaTest`, prueba de equivalencia de `ALCANCE_FAMILIA`): cuenta sin `familiaId` ⇒ 404 sin tocar la base; historial con filas ⇒ solo mapea; historial vacío ⇒ consulta de alcance y distingue `404` de `200 []`; detalle ausente ⇒ `404 DEPORTISTA_TEMPORADA_NO_ENCONTRADA` pero solo si el deportista está en alcance (si no, `404 DEPORTISTA_NO_ENCONTRADO`); el `toString` no imprime datos; `ALCANCE_FAMILIA == JOINS + FILTRO`.

**WebMvc** (`FamiliaPortalTemporadasControllerWebMvcTest`): `200` con los ocho campos exactos y ninguno más (sin `id`, `escuelaId`, `deportistaId`, `activo`, marcas de tiempo); la identidad sale del JWT y se ignoran `familiaId`/`escuelaId`/`usuarioId` en la solicitud; `400` para UUID mal formado sin llegar al servicio; `401` sin sesión; `403` para ADMIN sin tocar el servicio; cualquier método que escribe da `405` con CSRF y `403 CSRF_INVALIDO` sin él; cuerpo del `404` idéntico para los casos ajenos.

**BD real** (modelo `FamiliaPortalAislamientoDbTest`; sin tocar `FamiliaPortalAislamientoDbTest`): matriz de dos familias en dos escuelas con deportistas compartidos, propios, inactivos y con vínculo `ACTIVO/REVOCADO/PENDIENTE/RECHAZADO`; cada caso ajeno da el mismo `404`; un deportista **globalmente inactivo con vínculo ACTIVO** sigue mostrando su historial y uno con vínculo revocado, no; una familia **no ve** filas de otro deportista de la misma escuela ni de otra escuela; una temporada de otra escuela con el mismo UUID de la ruta no se ve; **temporadas futuras (borrador) y la ausencia de temporada activa** según D1; datos de 2026 y 2027 por separado; el estado deportivo anual `SUSPENDIDO` no cambia `activo` del deportista y viceversa; consultas: conteo de sentencias constante con 1 y con 7 temporadas.

**HTTP real** (modelo `FamiliaPortalHttpDbTest`): sesión real de FAMILIA ⇒ `200`; cuerpo de `404` idéntico byte a byte (y mismos encabezados) para inexistente/ajeno/otra escuela/revocado; revocar el vínculo por JDBC lo oculta en la siguiente solicitud con el mismo token; familia inactiva ⇒ `401` y cookie borrada en las dos rutas, reactivar restablece; usuario inactivo ⇒ `401`; token de una escuela con la familia de otra ⇒ nada; base caída ⇒ `503` sin borrar la cookie; ningún método de escritura funciona ni deja rastro; **las lecturas no escriben auditoría**.

**Cableado:** `InventarioRutasTest` (portal = 5 rutas, todas `GET`), `OpenApiRutasTest`/`OpenApiValidezTest`/`OpenApiArranqueDbTest`/`OpenApiSinDatosSensiblesTest`, `RutasDocumentadasTest` y `docs/FAMILIAS-DEPORTISTAS.md`.

**Mutaciones** (como en `temp-1` y `temp-2`): quitar `ae.estado = ACTIVO`, quitar `f.activa`, quitar el predicado de escuela de la fila, quitar la regla de visibilidad, devolver `404` distintos según el motivo, y comprobar que cada una hace fallar al menos una prueba.

### L8 — TDD real (RED antes de GREEN)

A diferencia de `temp-2` (donde DTO y controlador quedaron completos antes de sus pruebas), en esta unidad **ninguna capa se escribe completa en el esqueleto**: el esqueleto solo compila (DTO vacío o con campos sin normalizar, controlador sin lógica, servicio y consultas que lanzan `UnsupportedOperationException`). Orden: T1 (refactor con la batería existente y la prueba de equivalencia en RED) → T2–T4 (pruebas en RED por capa; se registra comando y resultado en este Log) → implementar → GREEN → mutaciones.

### L9 — Dependencias

`temp-1` y `temp-2` (fusionadas), `ALCANCE_FAMILIA` y la revalidación central de sesión (existentes), `docs/FAMILIAS-DEPORTISTAS.md` y `docs/SEGURIDAD.md` (se actualizan). Independiente de `temp-4` (DNI enmascarado): el DNI **no** aparece en estas respuestas. Sin migración.

### L10 — Riesgos

- **R1 (alto, ítem 2 – seguridad):** partir `ALCANCE_FAMILIA` toca la guarda de todo el portal. Mitigación: commit propio, prueba de equivalencia textual, batería de aislamiento existente sin modificar y mutaciones; al terminar se escala la evaluación nativa con el ítem 2 (revisión independiente + revisión nativa).
- **R2 (medio):** filtración por diferencias entre los dos `404` o por la segunda consulta del historial vacío. Mitigación: mensajes fijos, cuerpo y encabezados idénticos probados byte a byte, sin diagnóstico de qué falta.
- **R3 (medio):** mostrar a la familia datos aún no publicados (D1-A). Mitigación recomendada D1-B.
- **R4 (bajo):** `estadoDeportivo = SUSPENDIDO` visible para la familia del propio deportista; no hay motivo ni observación en el esquema.
- **R5 (bajo):** el acoplamiento de las consultas del portal con `Temporada` y `DeportistaTemporada`; las entidades no se exponen.
- **R6 (proceso):** Engram — los espejos `odd/temp-1-temporada/tasks` y `odd/temp-2-deportista-temporada/tasks` siguen sin sincronizar (sesiones duplicadas); este documento y los anteriores son la fuente durable. Las escrituras con `project` explícito sí funcionan (observaciones 585 y 586, cada una con una revisión de conflictos pendiente que no se resolvió).

### L11 — Decisiones que necesitan aprobación

| ID | Decisión | Recomendación |
| --- | --- | --- |
| D1 | ¿Qué temporadas ve la FAMILIA? **A:** todas las que tengan fila. **B:** solo las de año menor o igual al de la temporada activa; sin activa, ninguna | **B**: lo que el ADMIN prepara en una temporada futura no se publica hasta activarla |
| D2 | Campos visibles: `temporadaId, anio, temporadaActiva, categoria, rama, divisional, numeroCorredor, estadoDeportivo` (incluye `SUSPENDIDO`, sin motivo) | Sí; sin `id` de fila, `escuelaId`, `deportistaId` ni `deportista.activo` |
| D3 | Forma del historial | Lista sin paginar (≤ 81 por el `CHECK` del año), sin filtros |
| D4 | Códigos de `404` | `DEPORTISTA_NO_ENCONTRADO` (deportista fuera de alcance) y `DEPORTISTA_TEMPORADA_NO_ENCONTRADA` (propio, sin fila visible); sin diagnóstico |
| D5 | Reutilizar el alcance existente | Partir `ALCANCE_FAMILIA` en tramos con prueba de equivalencia, en un commit aparte (R1); alternativa descartada: duplicar el texto (riesgo de que diverja) |
| D6 | Ubicación | Servicio y controlador nuevos en `familias.portal`; rutas `/api/familia/deportistas/{deportistaId}/temporadas[/{temporadaId}]`; contrato en `docs/FAMILIAS-DEPORTISTAS.md` |
| D7 | Sin auditoría de lecturas y sin rutas de escritura | Sí, igual que el resto del portal |

### L12 — Aprobación y condiciones (verbatim)

> Apruebo implementar `temp-3-lectura-familia` siguiendo Organic Driven Development (ODD).
>
> Las decisiones D1–D7 quedan aprobadas con estas condiciones:
>
> 1. **Visibilidad:** FAMILIA solo consulta temporadas cuyo año sea menor o igual al año de la temporada activa de su escuela. Si no hay temporada activa, no existen temporadas visibles para FAMILIA.
> 2. Documentar que activar una temporada hace visibles sus datos deportivos y que reactivar una temporada anterior puede ocultar años posteriores.
> 3. Mostrar exclusivamente los campos deportivos definidos en el plan. No exponer DNI, escuelaId, identificadores internos de la relación ni auditoría.
> 4. Mantener historial descendente sin paginación.
> 5. Garantizar 404 uniforme dentro de cada endpoint, incluyendo UUID ajenos, inexistentes, vínculos revocados y temporadas no visibles.
> 6. Un deportista autorizado sin temporadas visibles devuelve `200 []`.
> 7. Refactorizar `ALCANCE_FAMILIA` en un commit independiente, con pruebas que demuestren equivalencia y sin cambiar el comportamiento del portal existente.
> 8. Implementar controlador y servicio específicos dentro de `familias.portal`.
> 9. Mantener los endpoints exclusivamente de lectura, sin auditoría de consultas.
> 10. Implementar con TDD real, registrando RED antes de GREEN.
> 11. Probar aislamiento entre familias y escuelas, deportistas globalmente inactivos con vínculo activo, revalidación de sesión y ausencia de N+1.
> 12. Ejecutar ambas suites completas y solicitar la revisión RDD que corresponda al riesgo de autorización.
>
> Autorizo implementar y crear commits locales siguiendo ODD.
>
> No autorizo push, merge, migraciones ni cambios en Supabase.
>
> Conservar sincronizados el documento ODD local y Engram, sin forzar conflictos históricos.
>
> Al finalizar, informar resultados de pruebas, evidencia de aislamiento, commits y riesgos residuales.

### L13 — Ajuste del plan por la condición 5

La condición 5 pide un `404` **uniforme dentro de cada endpoint**. El plan (D4) distinguía en el detalle dos códigos (deportista fuera de alcance / temporada no visible), lo que dejaba un oráculo mínimo ("este UUID de deportista es de mi familia"). Se adopta la lectura más estricta:
- `GET …/temporadas` (historial): un único `404 DEPORTISTA_NO_ENCONTRADO` para todo lo que no sea un deportista propio con vínculo ACTIVO; `200 []` si es propio y no tiene temporadas visibles (condición 6).
- `GET …/temporadas/{temporadaId}` (detalle): un único `404 DEPORTISTA_TEMPORADA_NO_ENCONTRADA` para **todas** las causas (deportista ajeno o inexistente, otra escuela, vínculo revocado, temporada inexistente, de otra escuela, sin fila o no visible). El detalle necesita una sola consulta y no diagnostica nada.

La visibilidad (condición 1) se resuelve con `join Temporada ta on ta.escuelaId = fd.escuelaId and ta.activa = true and ta.anio >= t.anio`; no hay filtro por `estado_deportivo`.

### L14 — T1: refactor de `ALCANCE_FAMILIA` (guarda de seguridad), RED y GREEN

Esqueleto: `ALCANCE_FAMILIA_JOINS = ""` y `ALCANCE_FAMILIA_FILTRO = ""` agregados sin tocar `ALCANCE_FAMILIA`.

| Paso | Comando | Resultado |
| --- | --- | --- |
| RED | `./mvnw test -Dtest='AlcanceFamiliaTest'` | 3 tests: **2 fallos** (los tramos no suman el alcance; el tramo de filtro no tiene el `where`); el tercero —el texto original fijado como literal del commit base— **pasa**, lo que demuestra que el literal es exacto |
| GREEN | `./mvnw test -Dtest='AlcanceFamiliaTest,FamiliaPortal*Test,InventarioRutasTest'` | 40 tests, 0 fallos |
| Equivalencia de comportamiento | `./mvnw verify -Pdb-tests -Dtest='FamiliaPortal*DbTest,ConsultasVinculosDbTest,SesionVigente*DbTest'` | **69 tests, 0 fallos, sin modificar ninguna de esas pruebas** (matriz de aislamiento, HTTP real, JWT viejo, sesión vigente, consultas del portal) |

`ALCANCE_FAMILIA` queda definida como `ALCANCE_FAMILIA_JOINS + ALCANCE_FAMILIA_FILTRO` y su texto es idéntico carácter por carácter al anterior; las tres consultas que la usan (`deportistasDelPortal`, `deportistaDelPortal` y su conteo) no cambiaron.

### L15 — RED de las unidades T2–T5, observado antes de implementar

Esqueleto (ninguna capa completa): `TemporadaDeFamilia` con sus campos y **sin** protección de datos; las tres consultas nuevas de `FamiliaDeportistaRepository` como métodos `default` que lanzan `UnsupportedOperationException("pendiente")`; `FamiliaPortalTemporadasService` con ambos métodos lanzando lo mismo; `FamiliaPortalTemporadasController` **sin rutas**; `OpenApiConfig.RUTAS` sin las dos rutas nuevas.

| Capa | Comando | Resultado RED |
| --- | --- | --- |
| DTO, servicio, WebMvc, inventario y OpenAPI | `./mvnw test -Dtest='TemporadaDeFamiliaTest,FamiliaPortalTemporadas*Test,InventarioRutasTest,OpenApi*Test,RutasDocumentadasTest'` | 85 tests: **15 fallos y 3 errores** (el `toString` del DTO imprime los datos; el servicio lanza; el controlador no tiene rutas: todo `404`; el portal sigue con 3 rutas; `OpenApiConfig.RUTAS` no las conoce) |
| BD real, servicio y consultas | `./mvnw verify -Pdb-tests -Dtest='FamiliaPortalTemporadasAislamientoDbTest'` | 16 tests: **7 fallos y 9 errores** |
| BD real, HTTP completo | `./mvnw verify -Pdb-tests -Dtest='FamiliaPortalTemporadasHttpDbTest'` | 15 tests: **13 fallos** (los 2 que pasan —un ADMIN recibe `403` y los métodos que escriben fallan— pasan porque la seguridad ya cubre `/api/familia/**` antes de que existan las rutas) |

`RutasDocumentadasTest` pasa en RED porque todavía no hay rutas; pasará a exigir la tabla de `docs/FAMILIAS-DEPORTISTAS.md` cuando existan.

### L16 — Evidencia de implementación

**Commits locales** (rama `feat/temp-3-lectura-familia`, sobre `main` = `549fa65`): `3ebd304` evidencia de `temp-2`; `295f126` plan y aprobación; `0f7e794` refactor de `ALCANCE_FAMILIA` (solo); `aa0d1b5` lectura de FAMILIA (código, pruebas unitarias/WebMvc/cableado y documentación); `bede9f7` pruebas de aislamiento en BD y HTTP; y el commit de esta evidencia.

**Suites:** `./mvnw clean verify` = **865 tests, 0 fallos/errores/omitidos** (847 previos + 18 nuevos). `./mvnw verify -Pdb-tests` = **398 tests, 0/0/0** (367 previos + 31 nuevos). Sin migraciones; V1–V4 intactas.

**Contratos implementados** (`GET`, rol FAMILIA): `/api/familia/deportistas/{deportistaId}/temporadas` (historial visible, año descendente, sin paginar; `200 []` si es propio sin temporadas visibles; `404 DEPORTISTA_NO_ENCONTRADO` único) y `/api/familia/deportistas/{deportistaId}/temporadas/{temporadaId}` (`404 DEPORTISTA_TEMPORADA_NO_ENCONTRADA` único para todas las causas). Campos: `temporadaId, anio, temporadaActiva, categoria, rama, divisional, numeroCorredor, estadoDeportivo`.

**Condiciones del usuario (L12):** (1) visibilidad `anio <= anio de la activa`, sin activa nada: `join Temporada ta … ta.activa = true and ta.anio >= t.anio`; (2) documentado en `docs/TEMPORADAS.md` §9 que activar publica y que reactivar una temporada anterior puede ocultar años posteriores; (3) solo los campos del plan (verificado por reflexión del record, por JSON del controlador y por el esquema OpenAPI); (4) historial descendente sin paginar; (5) un `404` por endpoint con cuerpo y encabezados idénticos byte a byte; (6) `200 []` para un deportista propio sin temporadas visibles (segunda consulta con alcance solo si vino vacío); (7) refactor en commit independiente; (8) servicio y controlador propios en `familias.portal`; (9) solo `GET`, sin auditoría (probado: `405`/`403 CSRF_INVALIDO` y cero filas de auditoría); (10) RED registrado (L14 y L15); (11) aislamiento, inactivos globales, revalidación y N+1 probados; (12) ambas suites verdes; revisión RDD solicitada (ver más abajo).

**Evidencia de aislamiento** (31 tests de BD nuevos): matriz de dos familias en una escuela y una en otra, con deportistas propios, compartidos, globalmente inactivos y con vínculo `ACTIVO`/`REVOCADO`/`PENDIENTE`/`RECHAZADO`; cada causa ajena da el mismo `404` (servicio y HTTP, byte a byte); la familia de una escuela con la escuela de otra no ve nada (y por HTTP el token se rechaza con `401`); temporada futura, de otra escuela, inexistente y sin fila indistinguibles; sin temporada activa no hay ninguna visible; activar/reactivar cambia la visibilidad en la siguiente solicitud con el mismo token; revocar el vínculo, desactivar la familia o el usuario cierra el acceso inmediatamente; base caída ⇒ `503` sin borrar la cookie; ningún método de escritura funciona ni deja rastro; las lecturas no escriben auditoría; ninguna respuesta contiene datos ni ids ajenos. **Sin N+1:** historial = 1 sentencia (2 si vino vacío) y detalle = 1, con 2 o 7 temporadas.

**Mutaciones** (código restaurado y verificado con `cmp`): (M1) sin `vínculo ACTIVO` → 5 fallos; (M2) sin `familia activa` → 1; (M3) sin la regla de visibilidad → 16; (M4) visibilidad contra cualquier temporada, sin `ta.activa` → 19; (M5) historial vacío siempre `200` (sin consulta de alcance) → 10; (M6) el detalle diagnostica con otro código → 5. Todas detectadas.

**Desviaciones y honestidad:**
- La condición 5 se leyó como "un solo `404` por endpoint": el detalle ya no distingue "deportista propio" de "ajeno" (L13).
- No hay mutación para quitar `dt.escuelaId = fd.escuelaId` y `t.escuelaId = fd.escuelaId`: son defensa en profundidad, porque las claves foráneas compuestas de V1 ya impiden cruzar escuelas; el cruce se prueba con datos de otra escuela.
- `ConfigFamiliasAdminDb` ganó un bean (`FamiliaPortalTemporadasService`) compartido por otras pruebas de BD, que siguen verdes.
- Se actualizaron a conciencia `InventarioRutasTest` (portal 3→5 rutas), `RutasDocumentadasTest`, `OpenApiRutasTest`/`OpenApiValidezTest`/`OpenApiArranqueDbTest` (48→50 rutas, 38→40 paths) y `OpenApiSinDatosSensiblesTest`.

**Riesgos residuales:** visibilidad por año: reactivar un año anterior oculta años posteriores (documentado); `estadoDeportivo = SUSPENDIDO` es visible para la familia sin motivo; textos libres; los conteos de rutas se mantienen a mano en seis lugares; sin prueba de carga; el CI remoto está pendiente (sin push).

**Engram:** el plan se guardó con `project` explícito (observación 587); esta evidencia se sincronizará con la misma vía, sin forzar los conflictos históricos. Los espejos de `temp-1` y `temp-2` siguen sin sincronizar.

Risk: item 2 (seguridad: se partió la guarda `ALCANCE_FAMILIA` y se agregaron consultas de autorización de FAMILIA; cubierto por equivalencia textual, la batería de aislamiento previa sin modificar, 31 pruebas nuevas de aislamiento y 6 mutaciones).

