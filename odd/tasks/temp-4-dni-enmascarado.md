# TEMP-4 — DNI enmascarado en los listados ADMIN (O8)

Objetivo: que los listados ADMIN dejen de exponer el DNI completo, manteniendo el DNI completo en el detalle y sin afectar los procesos federativos que lo necesitan. Estado: **IMPLEMENTADO localmente (commits locales; sin push ni merge); a la espera de autorización de push/PR.**
Rama: `feat/temp-4-dni-enmascarado` (desde `main` = `da823be`). Entrega: `ask-on-risk`; sin push, merge ni migraciones. Runner: `./mvnw clean verify` y `./mvnw verify -Pdb-tests`.

## Specs

- **S1** — "Revisar qué endpoints ADMIN exponen DNI, definir el formato de enmascaramiento y comprobar que los procesos federativos que necesitan DNI completo no se vean afectados."
- **S2** — D6 (`docs/DECISIONES.md`): "DNI minimizado o enmascarado en listados ADMIN; completo solo en detalle cuando sea necesario. `DeportistaResumen` deja de exponer DNI completo. La búsqueda por prefijo de DNI se mantiene."
- **S3** — O8 (`docs/DECISIONES.md`): "A. Últimos 3 dígitos: `•••••322`. … Recomendación: **A**, con búsqueda por DNI completo permitida solo a ADMIN y DNI completo únicamente en el detalle." (aprobación pendiente de la recomendación)
- **S4** — "No implementar temp-4 todavía. No modificar migraciones, Supabase ni hacer push adicional sin autorización."

## Tasks

| ID | S# | Tarea | Ruta | Estado | Evidencia |
| --- | --- | --- | --- | --- | --- |
| T0 | S1 | Descubrimiento y plan | inline | hecho | este documento |
| T1 | S2, S3 | `EnmascaradoDni` (función pura) y DTO de listado de deportistas | inline | hecho | `d7b8f3a`; RED en L13 |
| T2 | S2 | Tutores: DNI enmascarado dentro del detalle de familia | inline | hecho | `d7b8f3a`; RED en L13 |
| T3 | S1 | Guarda: ningún DTO de listado expone un componente `dni`; el detalle no cambia | inline | hecho | `d7b8f3a`; `ContratoDniTest` + OpenAPI; mutación M5 (L14) |
| T4 | S1 | BD real: la búsqueda por prefijo y las consultas de DNI completo no cambian | inline | hecho | `d7b8f3a`; `DniEnmascaradoDbTest` 5 tests |
| T5 | — | Documentación, OpenAPI, cierre, Risk | inline | hecho | `docs/FAMILIAS-DEPORTISTAS.md` §11, `SEGURIDAD.md`, `DECISIONES.md` (O8); L14 |

Ruta: todo inline. Estimación: ~120 líneas de producción y ~350 de pruebas, un solo corte de commit más el documental.

## Log

### L1 — Pedido original (verbatim)

> **Siguiente objetivo:** `temp-4-dni-enmascarado` (O8).
>
> Por ahora, únicamente descubrimiento y planificación de temp-4. Revisar qué endpoints ADMIN exponen DNI, definir el formato de enmascaramiento y comprobar que los procesos federativos que necesitan DNI completo no se vean afectados.
>
> No implementar temp-4 todavía. No modificar migraciones, Supabase ni hacer push adicional sin autorización.
>
> Informar SHA del merge, estado de main y propuesta de temp-4.

### L2 — Dónde aparece hoy el DNI (inventario)

| Superficie | Respuesta | DNI hoy | Propuesta |
| --- | --- | --- | --- |
| `GET /api/admin/deportistas` (**listado**) | `DeportistaResumen` | **completo** | **enmascarado** |
| `GET/POST/PUT /api/admin/deportistas/{id}`, `…/activar`, `…/desactivar` (**detalle**) | `DeportistaDetalle` | completo (y CUIL) | sin cambios (D4) |
| `GET /api/admin/familias/{id}` y las escrituras de familia que lo devuelven (**tutores anidados**) | `FamiliaDetalle.tutores[]` = `TutorRespuesta` | **completo** (el DNI del tutor es opcional) | **enmascarado** (D3) |
| `GET/POST/PUT /api/admin/tutores…` (**detalle del tutor**) | `TutorRespuesta` | completo | sin cambios |
| `GET /api/admin/temporadas/{temporadaId}/deportistas` (plantel) y datos deportivos | `PlantelRespuesta`, `DeportistaTemporadaRespuesta` | **ninguno** | sin cambios |
| Vínculos (`/api/admin/familias/{id}/deportistas`, `…/deportistas/{id}/familias`) | `VinculoRespuesta` | **ninguno** | sin cambios |
| Portal FAMILIA | `DeportistaDeFamilia` / `TemporadaDeFamilia` / `TutorDeFamilia` | ninguno | sin cambios |
| Portal FAMILIA, detalle de **su** deportista | `DeportistaDeFamiliaDetalle` | completo (y CUIL: son datos de su hijo/a) | sin cambios (D7) |
| Auditoría y logs | — | ninguno (ya se audita sin DNI ni CUIL) | sin cambios |
| `409 DNI_DUPLICADO` / `DNI_RESERVADO_POR_INACTIVO` | errores | ninguno (mensaje fijo) | sin cambios |

No existe hoy ningún proceso federativo en el código: ni exportación LBF, ni empadronamiento, ni ZIP de documentos, ni carga de aptos (`grep` de `src/main` sin módulos de ese tipo); llegan en las Fases 2, 3, 5 y 7 del ROADMAP. El DNI completo vive en la entidad `Deportista` (`getDni()`), en `DeportistaDetalle` y en las consultas por DNI (`activoPorDni`, `activoPorDniDeOtro`, `buscar`), y **nada de eso cambia**: el enmascarado se aplica solo al armar el DTO de listado.

### L3 — Hallazgos

1. **Contradicción entre D6 y O8 sobre la búsqueda:** D6 dice "la búsqueda por prefijo de DNI se mantiene"; la recomendación de O8 habla de "búsqueda por DNI completo". Hoy una búsqueda de solo dígitos se resuelve por **prefijo** (`Busqueda.digitos` + `like concat(:dni, '%')`). Se propone mantener el prefijo (D5): nada se pierde y no hay consumidores que dependan de otra cosa.
2. **Al enmascarar los primeros dígitos aparece una incoherencia de uso:** el listado mostrará los **últimos** 3 dígitos (`•••••322`) pero la búsqueda es por **prefijo**; el administrador no puede buscar tipeando lo que ve. Se deja explícito en D5 (alternativa: agregar búsqueda por sufijo, más costosa y fuera de alcance).
3. **El enmascarado no protege contra el ADMIN:** el ADMIN puede ver el DNI completo en el detalle y buscar por prefijo. El objetivo (D6) es **minimizar la exposición** en pantallas, capturas, exportaciones accidentales y respuestas voluminosas, no ocultárselo a quien administra.
4. **El DNI del tutor también viaja en un listado:** el detalle de familia incluye `tutores[]` con `dni` completo; es una colección, no un detalle de cada tutor (D3).
5. **Sin consumidores externos:** `frontend/` solo tiene documentación (sin código); `backend/scripts/smoke-familias.sh` crea deportistas con DNI propios y comprueba `409`, pero **no lee** el DNI de ninguna respuesta de listado. Renombrar el campo no rompe nada existente.
6. **Largo del DNI:** F2 admite 7 a 9 dígitos; el ejemplo de O8 corresponde a 8.

### L4 — Formato de enmascaramiento propuesto (D1, D2)

- **Regla:** se muestran los **últimos 3 dígitos** y el resto se reemplaza por `•` (U+2022), **conservando el largo**: `30111322` → `•••••322`; 7 dígitos → `••••322`; 9 dígitos → `••••••322`. `null` → `null` (el DNI del tutor es opcional). Un valor de 3 dígitos o menos se enmascara completo (la función es total y nunca devuelve más de lo que debe).
- **Entrada:** el DNI ya está normalizado (solo dígitos) en la base; la función no valida ni normaliza, solo enmascara.
- **Forma:** función pura `EnmascaradoDni.de(String)` en el módulo de deportistas, usada solo por los mapeadores de DTO de listado. No hay forma de "desenmascarar".
- **Campo:** en `DeportistaResumen` el componente pasa de `dni` a **`dniEnmascarado`**, para que ningún cliente lo confunda con el DNI real (D2). En `TutorRespuesta` anidado se usa la misma convención.
- **Codificación:** JSON UTF-8; el `•` no necesita escape. Alternativa ASCII (`*`) si se prevé consumo por CSV o sistemas que no manejen UTF-8 (D1).

### L5 — Contrato propuesto (solo cambia el listado y los tutores anidados)

- `GET /api/admin/deportistas` → `Pagina<{id, nombre, apellido, dniEnmascarado, fechaNacimiento, activo}>`. La búsqueda `busqueda` no cambia (apellido, nombre, ambos órdenes o prefijo de DNI de solo dígitos); orden, filtros y paginación idénticos.
- `GET /api/admin/familias/{id}` (y las respuestas `FamiliaDetalle`) → `tutores[]` con `dniEnmascarado` en lugar de `dni`.
- Sin cambios: `DeportistaDetalle` (`dni` completo), `TutorRespuesta` de las rutas de tutor, el plantel, los vínculos y todo el portal FAMILIA.

### L6 — Procesos federativos: cómo se garantiza que no se afecten

1. **El enmascarado vive solo en el DTO de listado.** La entidad, el detalle y las consultas por DNI completo no se tocan: las pruebas existentes de `DeportistaTest`, `DeportistaAdminServiceTest`, `DeportistaServiceDbTest`, `MapeoDeportistaDbTest` y `DocumentoIdentidadTest` siguen sin modificar su lógica.
2. **Regla para las fases futuras (documentada):** los procesos que necesiten el DNI completo (exportación LBF, empadronamiento, ZIP, spike federativo) lo leen **en el servidor** desde la entidad o el repositorio, **nunca** desde un DTO de listado ni desde una respuesta HTTP al navegador.
3. **Prueba de guarda (T3):** ningún record `*Resumen` ni DTO de listado del paquete de DTO de ADMIN tiene un componente llamado `dni`; `DeportistaDetalle` conserva `dni` y `cuil`. Un nuevo listado que exponga `dni` hace fallar la prueba.
4. **Prueba de BD (T4):** una búsqueda por prefijo de DNI sigue encontrando al deportista y ordenándolo igual; `activoPorDni` y el conflicto `409` siguen funcionando con el DNI completo.

### L7 — Matriz de pruebas (TDD con RED antes de GREEN)

Esqueleto: `EnmascaradoDni.de` lanza `UnsupportedOperationException`, el record `DeportistaResumen` aún con `dni`; ninguna capa se escribe completa antes de su RED.

- **Unitarias:** `EnmascaradoDniTest` (7, 8 y 9 dígitos, `null`, vacío, 1 a 3 dígitos, solo los últimos 3 visibles, largo conservado, ningún dígito oculto reaparece); `DeportistaResumenTest` y mapeo de `TutorRespuesta` anidado (`toString` sin datos).
- **WebMvc:** el listado devuelve `dniEnmascarado` y **no** `dni`; el detalle sigue devolviendo `dni` y `cuil` completos; `GET /api/admin/familias/{id}` devuelve tutores con `dniEnmascarado`; `GET /api/admin/tutores/{id}` conserva `dni`; sin cambios de autorización (401/403/CSRF).
- **OpenAPI:** `OpenApiSinDatosSensiblesTest` pasa a exigir que el esquema del listado tenga `dniEnmascarado` y no `dni`.
- **Guarda:** reflexión sobre los records de respuesta de listado (T3).
- **BD real:** búsqueda por prefijo (con y sin puntos), orden, conteo de sentencias del listado sin cambios, detalle con DNI completo; respuestas HTTP sin el DNI completo en el listado.
- **Mutaciones:** devolver el DNI completo, ocultar de más (cambia el largo) y exponer el componente `dni` deben hacer fallar al menos una prueba.

### L8 — Riesgos

- **R1 (medio):** cambio de contrato de respuesta (`dni` → `dniEnmascarado`) en un listado público de la API. Sin consumidores hoy (L3.5); se registra en OpenAPI y en las tablas de `docs/`. No es un ítem de alto riesgo por sí mismo: es un cambio pedido y sin consumidores.
- **R2 (bajo):** un administrador no puede buscar tipeando los últimos 3 dígitos que ve (L3.2).
- **R3 (bajo):** el enmascarado conserva el largo, así que revela si el DNI tiene 7, 8 o 9 dígitos.
- **R4 (proceso):** cuando se construyan las fases 2, 3, 5 y 7, alguien podría reutilizar `DeportistaResumen` para un proceso federativo y recibir el DNI enmascarado; mitigado por la regla de L6.2 y la prueba de guarda.
- **R5 (proceso):** Engram: los espejos de `temp-1` y `temp-2` siguen sin sincronizar; este documento es la fuente durable. La evidencia remota de `temp-3` (PR #6, runs `41368903823` y `41368908259`, merge `da823be`) queda pendiente de un commit documental de la próxima unidad.
- Clasificación: ningún ítem de alto riesgo (sin migración, sin seguridad de acceso, sin concurrencia). La evaluación nativa se decide con `gentle-ai review assess` al terminar.

### L9 — Dependencias

`temp-2` (plantel sin DNI, ya fusionada), `temp-3` (portal FAMILIA sin DNI, ya fusionada). Independiente de cualquier fase federativa futura; no requiere migración.

### L10 — Decisiones que necesitan aprobación

| ID | Decisión | Recomendación |
| --- | --- | --- |
| D1 | Formato: últimos 3 dígitos visibles, resto `•` conservando el largo (`•••••322`); alternativa ASCII `*` | `•` como en O8 |
| D2 | Nombre del campo en el listado | Renombrar a `dniEnmascarado` (sin consumidores hoy) |
| D3 | Tutores anidados en el detalle de familia | Enmascarar ahí; completo en `GET /api/admin/tutores/{id}` y en las escrituras de tutor |
| D4 | Respuestas de `activar` / `desactivar` / `PUT` de deportista | Siguen devolviendo `DeportistaDetalle` con DNI completo (es la representación de detalle) |
| D5 | Búsqueda | Mantener el prefijo (D6); sin búsqueda por sufijo en esta unidad |
| D6 | Guarda | Prueba de reflexión sobre los DTO de listado y regla documentada para los procesos federativos |
| D7 | Portal FAMILIA | Sin cambios: ve el DNI y el CUIL **de su propio** deportista en el detalle |

### L11 — Aprobación y condiciones (verbatim)

> Apruebo comenzar la implementación de `temp-4-dni-enmascarado` siguiendo ODD.
>
> **Decisiones D1–D7 aprobadas**, con las siguientes condiciones:
>
> 1. Utilizar el carácter `•` para ocultar todos los dígitos excepto los últimos tres, conservando la longitud original del DNI.
> 2. Renombrar el campo de los DTO de listado a `dniEnmascarado`. No incluir simultáneamente el campo `dni` completo.
> 3. Enmascarar también el DNI de tutores anidados en el detalle de familia. Mantener el DNI completo en los detalles individuales autorizados.
> 4. No modificar la entidad, el almacenamiento, las validaciones existentes ni la búsqueda por DNI completo.
> 5. Mantener la búsqueda actual por prefijo. Documentar como mejora futura la búsqueda por sufijo.
> 6. Agregar pruebas de contrato que verifiquen tanto la estructura de los DTO como el JSON serializado, comprobando que el DNI completo nunca aparezca en respuestas de listado.
> 7. Probar DNI de distintas longitudes y DNI opcional de tutores.
> 8. No modificar el portal FAMILIA ni los contratos que utilizarán los procesos federativos.
> 9. Utilizar TDD real, registrando RED antes de implementar.
> 10. Ejecutar las suites normal y DB y seguir el protocolo RDD correspondiente.
>
> Autorizo implementación y commits locales, con mensajes en español.
>
> No autorizo push, merge, migraciones ni modificaciones en Supabase.
>
> Mantener los artefactos ODD y Engram actualizados sin forzar conflictos históricos.
>
> Al finalizar, informar archivos modificados, pruebas, commits y riesgos residuales.

### L12 — Diseño concreto derivado de las condiciones

- **Dos DTO de tutor, nunca ambos campos a la vez (condición 2):** `TutorRespuesta` (detalle individual, conserva `dni`) y un nuevo `TutorResumen` (tutor dentro del detalle de familia, con `dniEnmascarado`). `FamiliaDetalle.tutores` pasa a `List<TutorResumen>`.
- **Guarda estructural (condición 6):** una prueba de reflexión recorre todos los records de los paquetes `dto` y exige que solo una lista cerrada tenga un componente `dni` (`DeportistaDetalle`, `TutorRespuesta`, `DeportistaDeFamiliaDetalle` y las solicitudes `DeportistaSolicitud`/`TutorSolicitud`): un DTO nuevo que exponga `dni` obliga a decidirlo a conciencia.
- **Guarda del JSON serializado (condición 6):** se recorre el árbol JSON de las respuestas de listado y se comprueba que ninguna clave se llame `dni` y que ningún valor de texto —salvo los UUID— contenga el DNI completo; no se buscan subcadenas en el texto crudo (los UUID aleatorios podrían contener 8 dígitos seguidos).
- **Sin tocar (condiciones 4 y 8):** entidad `Deportista`, `DocumentoIdentidad`, `DeportistaRepository` (incluida la búsqueda por prefijo y las consultas por DNI completo), `DeportistaDetalle`, portal FAMILIA y sus DTO.
- **Mejora futura documentada (condición 5):** búsqueda por sufijo (los últimos 3 dígitos visibles).

### L13 — RED, observado antes de implementar

Esqueleto (sin lógica de enmascarado): `EnmascaradoDni.de` lanza `UnsupportedOperationException("pendiente")`; `DeportistaResumen` con el componente renombrado a `dniEnmascarado` pero **con el valor sin enmascarar**; `TutorResumen` (nuevo) con `dniEnmascarado` sin enmascarar y **sin** `toString` protegido; `FamiliaDetalle.tutores` ya es `List<TutorResumen>`. Las pruebas existentes que miraban el DNI del listado se adaptaron al contrato nuevo (`DeportistaAdminServiceTest`, `DeportistaAdminControllerWebMvcTest`).

| Capa | Comando | Resultado RED |
| --- | --- | --- |
| Unitarias, WebMvc y OpenAPI | `./mvnw test -Dtest='EnmascaradoDniTest,DeportistaResumenTest,TutorResumenTest,ContratoDniTest,DeportistaAdminServiceTest,DeportistaAdminControllerWebMvcTest,FamiliaGestionAdminControllerWebMvcTest,OpenApiSinDatosSensiblesTest'` | 102 tests: **7 fallos y 6 errores** |
| BD real | `./mvnw verify -Pdb-tests -Dtest='DniEnmascaradoDbTest'` | 5 tests: **3 fallos** (los 2 que pasan —el detalle conserva el DNI completo y la entidad/almacenamiento no cambian— comprueban lo que no debe cambiar) |

`ContratoDniTest` (estructura) pasa en RED porque el esqueleto ya tiene la estructura final; su sensibilidad se comprueba con mutaciones. Las pruebas de JSON serializado y de contrato (`JsonDeContrato`) fallan en RED porque el valor aún no está enmascarado.

### L14 — Evidencia de implementación

**Commits locales** (rama `feat/temp-4-dni-enmascarado`, sobre `main` = `da823be`): `2845e83` evidencia remota y merge de `temp-3` (documental, separado); `1f8f27a` plan y aprobación; `d7b8f3a` enmascarado del DNI (código, pruebas y documentación); y el commit de esta evidencia.

**Suites:** `./mvnw clean verify` = **885 tests, 0 fallos/errores/omitidos** (865 previos + 20 nuevos). `./mvnw verify -Pdb-tests` = **403 tests, 0/0/0** (398 previos + 5 nuevos). Sin migraciones; V1–V4 intactas.

**Archivos de producción modificados o nuevos:** nuevo `deportistas/EnmascaradoDni`; `deportistas/dto/DeportistaResumen` (`dni` → `dniEnmascarado`); nuevo `familias/tutores/dto/TutorResumen`; `familias/dto/FamiliaDetalle` (`tutores` pasa a `List<TutorResumen>`); `familias/FamiliaAdminService` (usa `TutorResumen::de`). **No se tocaron:** la entidad `Deportista`, `DocumentoIdentidad`, `DeportistaRepository` (búsqueda por prefijo y consultas por DNI completo), `DeportistaDetalle`, `TutorRespuesta`, el portal FAMILIA ni la base.

**Condiciones del usuario (L11):** (1) `•` para todos los dígitos menos los últimos tres, mismo largo: `EnmascaradoDniTest` (7, 8 y 9 dígitos, vacío, 1 a 4 dígitos) y BD; (2) campo `dniEnmascarado` sin `dni` simultáneo: `ContratoDniTest` y el esquema OpenAPI; (3) tutores anidados enmascarados y detalles individuales completos: `TutorResumenTest`, WebMvc y `DniEnmascaradoDbTest`; (4) entidad, almacenamiento, validaciones y búsqueda por DNI completo sin cambios: pruebas previas sin modificar su lógica más `laEntidadYElAlmacenamientoConservanElDniCompleto`; (5) prefijo mantenido (con y sin puntos, y con el DNI entero); búsqueda por sufijo documentada como mejora futura en `docs/FAMILIAS-DEPORTISTAS.md` §11; (6) contrato: estructura de los DTO por reflexión, esquema OpenAPI y **JSON serializado** recorriendo el árbol (claves y valores, sin buscar subcadenas en el texto crudo por los UUID aleatorios); (7) DNI de 7, 8 y 9 dígitos y DNI opcional de tutores (`null`); (8) portal FAMILIA y contratos de procesos federativos intactos; (9) RED en L13; (10) ambas suites verdes y protocolo RDD (abajo).

**Mutaciones** (código restaurado y verificado con `cmp`): (M1) listado con el DNI completo → falla en BD (2) y en unitarias/WebMvc (3); (M2) cuatro dígitos visibles → falla en BD (3); (M3) largo fijo en lugar de conservar el largo → BD (2) y unitarias (4); (M4) tutor anidado con el DNI completo → BD (1) y unitarias (3); (M5) un DTO nuevo `*Resumen` con componente `dni` → `ContratoDniTest` falla en 2 pruebas. Todas detectadas.

**Protocolo RDD:** `gentle-ai review assess` sobre el rango (base `da823be`, 20 archivos, 798 líneas) = riesgo **medio**, `review_due: false` (`under_budget`). No hay ítem de alto riesgo: no hay migración, ni cambio de autorización, ni concurrencia, y el cambio de contrato (`dni` → `dniEnmascarado`) es pedido y no tiene consumidores (`frontend/` solo documentación; el script de smoke no lee el DNI de ningún listado). No se escaló ni se inició revisión. `Risk: none`.

**Desviaciones y honestidad:**
- El esqueleto ya tenía la estructura final de los DTO (el renombrado), por lo que `ContratoDniTest` y las pruebas de estructura pasaron en RED; el RED real está en el enmascarado de valores (7 fallos y 6 errores en unitarias/WebMvc/OpenAPI; 3 fallos en BD) y la sensibilidad de la guarda se comprobó con M5.
- `EnmascaradoDniTest` fija el comportamiento de valores de 4 dígitos o menos (`•234`, `•••`): no son DNI válidos (F2 exige 7 a 9) y solo garantizan que la función es total.

**Riesgos residuales:** el administrador no puede buscar tipeando los últimos 3 dígitos que ve (búsqueda por prefijo; sufijo como mejora futura); el enmascarado conserva el largo, así que revela si el DNI tiene 7, 8 o 9 dígitos; el DNI completo sigue visible para el ADMIN en el detalle y por búsqueda por prefijo (el objetivo es minimizar exposición, no ocultarlo a quien administra); cuando se construyan los procesos federativos hay que leer el DNI del servidor, no de `DeportistaResumen`/`TutorResumen` (regla y guardas documentadas); el CI remoto está pendiente (sin push).

**Engram:** el plan se guardó con `project` explícito (observación 591) y esta evidencia se sincronizará con la misma vía, sin forzar conflictos históricos. Los espejos de `temp-1` y `temp-2` siguen sin sincronizar.

