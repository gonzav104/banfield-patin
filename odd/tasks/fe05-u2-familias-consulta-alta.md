# FE-05 / U2 — Consulta y alta administrativa de familias

Objetivo: pantallas ADMIN para consultar y dar de alta familias, solo frontend, sobre contratos backend existentes.
Rama: `feat/fe05-u2-familias-consulta-alta`; base `d2fd5089a8a38b7a78502d270a759eeada77c599` (`origin/main`).
Entrega: dos PR funcionales y secuenciales a `main` (A: listado; B: detalle y alta). B parte de `main` actualizado tras integrar A.
Runner: `npm run typecheck`, `npm run lint`, `npm test` (Vitest), `npm run build`, E2E reales con `bash .github/scripts/frontend-e2e.sh`.

## Specs

- S1: «Navegación administrativa de familias.» Ítem «Familias» en `AdminLayout` y ruta `/admin/familias`, solo para ADMIN con MFA completada (guards existentes).
- S2: «Listado paginado.» Usa `GET /api/admin/familias/listado` (`FamiliaAdminResumen`: `id`, `nombreReferencia`, `activa`, `cantidadTutores`, `cantidadDeportistasActivos`); página de 20; el resumen no contiene DNI ni datos de contacto.
- S3: «Búsqueda y filtros por estado.» Estado `TODOS|ACTIVOS|INACTIVOS` (parámetro `estado`); búsqueda por nombre (parámetro `busqueda`, máximo 100 caracteres) con espera (debounce) y reinicio a la primera página.
- S4: «Mantener el término de búsqueda fuera de la URL de navegación.» Estado y página sí pueden ir en la URL; el término solo vive en memoria del componente. «No registrar búsquedas personales en logs ni artefactos»: sin `console`, sin storage, y los E2E usan nombres sintéticos.
- S5: «Estados de carga, vacío y error.» Carga con `role=status`; vacío sin familias, vacío con filtros (con acción «Limpiar filtros») y página fuera de rango; error con mensaje seguro y «Reintentar».
- S6: «Diseño responsive y accesible.» 360 px sin desplazamiento horizontal, controles etiquetados, objetivos táctiles de 44 px, estado no solo por color, resultados anunciados.
- S7: «Autorización ADMIN, MFA, aislamiento escolar y seguridad existentes.» Sin cambios de backend; anónimo → login admin; FAMILIA sin acceso y sin pedir el listado; MFA pendiente → segundo factor.
- S8 (PR B): «Detalle administrativo de familia.» `GET /api/admin/familias/{id}`; tutores con DNI enmascarado; solo lectura; 404 uniforme.
- S9 (PR B): «Alta de familias.» `POST /api/admin/familias` con `{nombreReferencia}` (máximo 150, sin espacios sobrantes); 201 → detalle; validaciones y errores.
- S10: «No incluir edición, activación/desactivación ni gestión de tutores.» Quedan para unidades posteriores. Sin U3.
- S11: «Evitar refactorizaciones amplias de componentes familiares. Extraer componentes compartidos solamente cuando exista reutilización real.» No se modifica FE-01–FE-04.
- S12: «Corregir el error documental `400, 400` únicamente si corresponde a la documentación tocada por esta unidad.» (U2 no toca `docs/FAMILIAS-DEPORTISTAS.md` en el PR A.)

Criterios de aceptación PR A (S1–S7, S10–S11): lista, filtra, busca y pagina contra el contrato real; todos los estados se muestran y se prueban; guards intactos; `typecheck`, `lint`, `test`, `build` y E2E reales en verde; ningún término de búsqueda en URL, storage ni artefactos.

## Tasks

- T1 [S2–S5, S7] (commit 7164540) Capa de datos del listado (`schemas`, `api`, `hooks`) + extensión de `FakeBackend` — RED 27 fallos → GREEN 30/30 — route: inline — hecho
- T2 [S1–S6] (commit 7164540) Página `AdminFamiliasPage`, ruta y navegación — route: inline — hecho
- T3 [S2–S7] E2E real del listado (`e2e/familias-listado.real.spec.ts`) — 8 pruebas reales en verde — route: inline — hecho
- T4 [S6] Verificación responsive/accesible y regresión — typecheck, lint, 353 pruebas, build y 56 E2E reales en verde — route: inline — hecho
- T5 (PR B) Detalle y alta — fuera del alcance autorizado hasta nueva orden.

Ruta: inline (una unidad de comportamiento, edit surfaces compartidas; sin razón de escritor delegado).

## Log

### L1 — Autorización original (2026-11-14, verbatim)

> Autorizo comenzar FE-05/U2, con los siguientes ajustes al plan.
> Prefiero dos PR funcionales y secuenciales hacia `main`, no dos PR simultáneos ni una separación artificial entre código y pruebas.
> PR A: Navegación administrativa de familias. Listado paginado. Búsqueda y filtros por estado. Estados de carga, vacío y error. Pruebas correspondientes, incluyendo E2E cuando sean aplicables.
> PR B: Detalle administrativo de familia. Alta de familias. Validaciones y manejo de errores. Pruebas correspondientes y E2E reales.
> La segunda entrega deberá partir de la versión actualizada de main después de integrar la primera. No implementar ambas entregas de una vez.
> Reutilizar los contratos backend existentes. Evitar refactorizaciones amplias de componentes familiares. Extraer componentes compartidos solamente cuando exista reutilización real. Conservar las garantías ADMIN, MFA, CSRF y aislamiento escolar. Mantener el término de búsqueda fuera de la URL de navegación. Considerar que los parámetros GET sí forman parte de la URL de la solicitud HTTP; no registrar búsquedas personales en logs ni artefactos. Mantener DNI enmascarado en los resúmenes. No modificar FE-01 a FE-04 fuera de adaptaciones estrictamente necesarias. Corregir el error documental `400, 400` únicamente si corresponde a la documentación tocada por esta unidad.
> Te autorizo a crear el worktree y comenzar la implementación del PR A. No autorizo todavía commits, push, apertura de PR, merge ni implementación del PR B. Trabajá autónomamente dentro de este alcance, sin detenerte por decisiones técnicas rutinarias.
> Cuando termines el PR A, presentame los cambios, pruebas, evidencias ODD/RDD, riesgos residuales y estado de Git para decidir la siguiente operación. No iniciar U3 ni otros módulos de FE-05.

### L2 — Preparación

`origin/main` remoto == local == `d2fd508`. Worktree `~/banfield-patin-worktrees/fe05-u2-familias-consulta-alta` y rama creados sin upstream (para que un push accidental no apunte a `main`). `npm ci` correcto. Contrato real verificado: listado ordenado por `lower(nombreReferencia), id`; `Busqueda` recorta a 100; `estado` desconocido → 400 `SOLICITUD_INVALIDA`; el backend no registra la query string (sin filtro de logging de requests). `Pagination` y `LoadingBlock` de `features/familia` son genéricos: se importan sin moverlos (no se toca FE-04); `ErrorBlock` usa mensajes familiares, por eso el listado tiene los suyos.

Risk previsto: none (frontend nuevo; sin backend, sin migraciones, sin cambio de contratos; `FakeBackend` es de pruebas).


### L3 — Implementación y verificación (2026-11-14)

RED observado: 27 de 30 pruebas nuevas fallaban antes de implementar (las 3 de guardas pasaban porque los guards ya existían). GREEN: 30/30. Correcciones propias: dos aserciones por `getByRole('status')` ambiguo (el `<output>` del `LocationProbe` de las pruebas también tiene ese rol) y un `unbound-method` de lint en la prueba de consola (se usa `vi.spyOn`).
Decisiones técnicas: `estado` y `pagina` (desde 1) en la URL; el término en memoria; al escribir con página > 1 se vuelve a la primera inmediatamente (evita una petición con término nuevo y página vieja); `Pagination`, `LoadingBlock`, `useDebouncedValue` y `pageSchema` se importan de FE-01–FE-04 sin modificarlos; errores propios sin eco del mensaje del backend; con `keepPreviousData` el error se muestra aunque haya datos previos (`isError` primero).
Verificación: `npm run typecheck`, `npm run lint`, `npm test` (17 archivos, 353 pruebas), `npm run build` en verde. E2E: `bash .github/scripts/frontend-e2e.sh` → 56 passed (6.0 min), exit 0, con la comprobación de artefactos del teardown. Cobertura E2E nueva: navegación, búsqueda + dos páginas reales (20+5) con el término fuera de URL/consola/storage, filtro por estado, conteos reales, vacío + limpiar, página fuera de rango, 360 px (sin scroll horizontal y controles ≥ 44 px), anónimo 401 y FAMILIA 403 sin pantalla. Aislamiento entre escuelas: cubierto por pruebas DB del backend (sin cambios de backend en esta unidad).
Risk: none (frontend nuevo; no toca backend, migraciones ni contratos; el único cambio compartido es el ítem de navegación y la ruta; `FakeBackend` y `seed.ts` son de pruebas y solo se extienden).

### L4 — Commits locales del PR A (2026-11-14)

Autorizados por el usuario solo para el PR A: «Autorizo crear los commits locales de FE-05/U2, exclusivamente para el PR A.» Sin push, PR, merge, PR B ni U3.
- Commit 1 `7164540` — `feat(admin): listar familias con búsqueda, filtro de estado y paginación`: capa de datos, pantalla, ruta, navegación, `FakeBackend` y pruebas unitarias (9 archivos). Las 353 pruebas, typecheck, lint y build se ejecutaron sobre estas mismas fuentes antes de commitear.
- Commit 2 (este documento, E2E real y `seed.ts`): su hash no puede constar aquí; se informa en el reporte de cierre.
RDD: `assess` medio, `review_due: false` (`under_budget`); no se inició revisión nativa ni se pidió consentimiento.
Limitación: Engram rechaza `mem_save` con «multiple active runtime sessions match the current project and directory». No se modificó su configuración; este documento es la evidencia principal.
