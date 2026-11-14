# FE-05 / U2-B — Detalle administrativo y alta de familias

Objetivo: segunda entrega de U2 (la primera, listado, está en `main` por el PR #21). Solo frontend, sobre contratos existentes.
Rama: `feat/fe05-u2b-detalle-alta-familias`; base `c8abaab9e8fa136715b1f55d542612acb8240b35` (`origin/main`).
Entrega: un PR a `main` tras commits locales autorizados aparte. Runner: `npm run typecheck`, `npm run lint`, `npm test`, `npm run build`, E2E reales con `bash .github/scripts/frontend-e2e.sh`.

## Specs

- S1: «Ruta `/admin/familias/:id`. Consultar `GET /api/admin/familias/{id}`. Mostrar los datos familiares y tutores disponibles en el contrato existente. Respetar el enmascarado del DNI. Presentar los estados de carga, error y registro inexistente.»
- S2: «Mostrar la versión del registro solo cuando tenga utilidad funcional; no exponer detalles técnicos innecesarios al usuario.» Decisión: el detalle es de solo lectura, la versión no tiene uso funcional en esta unidad; se valida en el esquema (la usará U3) y no se muestra.
- S3: «Ruta `/admin/familias/nueva`. Formulario accesible para registrar el nombre de referencia. Reutilizar `POST /api/admin/familias`. Validar campos según el contrato backend real.» Contrato real: `nombreReferencia` `@NotBlank`, máximo 150, el backend recorta espacios; propiedades extra → 400.
- S4: «Después del alta exitosa, navegar al detalle correspondiente. Gestionar errores de validación, conflictos y respuestas inesperadas.»
- S5: «Ante timeout o resultado incierto, no reintentar automáticamente ni asumir que la creación falló.» Sin reintentos automáticos; el botón se deshabilita mientras la petición está en curso; ante red/5xx se explica que hay que revisar el listado antes de reintentar.
- S6: «Aclarar que crear una familia no genera una cuenta de usuario: el acceso sigue gestionándose mediante invitaciones.»
- S7: «No implementar edición, activación/desactivación ni gestión de tutores.»
- S8: «Preservar autorización ADMIN, MFA, CSRF y aislamiento escolar. No modificar contratos backend salvo un bloqueo real demostrado. No introducir dependencias innecesarias. Mantener los datos personales fuera de logs, URLs y artefactos de pruebas. Diseño responsive, accesible y compatible con pantallas de 360 px.»
- S9: «Incluir escenarios de alta, detalle, validaciones, errores, permisos y aislamiento.» (Pruebas unitarias y E2E reales.)
- S10: «Corregir el error documental `400, 400` si resulta apropiado dentro del alcance; de lo contrario, conservarlo como pendiente.» Decisión: la fila afectada es la del `PUT` (edición), fuera de este alcance → pendiente para U3.
- S11: «No comenzar U3.» «Te autorizo a implementar y verificar U2-B, pero todavía no a crear commits, hacer push, abrir PR ni merge.»

Criterios de aceptación: el detalle muestra familia y tutores con DNI enmascarado y sin versión; 404/inválido/error/carga cubiertos; el alta valida, evita doble envío, navega al detalle, no reintenta y explica el resultado incierto; la aclaración sobre cuentas/invitaciones es visible; el listado enlaza al detalle y a «Nueva familia»; typecheck, lint, pruebas, build y E2E reales en verde; ningún dato personal en URL, storage, consola ni artefactos.

## Tasks

- T1 [S1, S2, S8] (commit 859b1c3) Capa de datos del detalle + pantalla `AdminFamiliaDetallePage` + ruta + enlace desde el listado — route: inline — hecho
- T2 [S3–S6, S8] (commit 859b1c3) Formulario de alta `AdminFamiliaNuevaPage` + mutación + mensajes — route: inline — hecho
- T3 [S9] (commit 859b1c3) Extensión de `FakeBackend` + pruebas unitarias (RED primero) — route: inline — hecho
- T4 [S9] E2E reales (`e2e/familias-detalle-alta.real.spec.ts`) + seed de tutor — route: inline — hecho
- T5 [S8] Documentación del frontend (README) y regresión completa — route: inline — hecho

Ruta: inline (una unidad de comportamiento con edit surfaces compartidas; sin razón de escritor delegado).

## Log

### L1 — Autorización original (2026-11-14, verbatim)

> Autorizo cerrar U2-A y comenzar **FE-05/U2-B: detalle administrativo y alta de familias**.
> Detalle administrativo de familia: Ruta `/admin/familias/:id`. Consultar `GET /api/admin/familias/{id}`. Mostrar los datos familiares y tutores disponibles en el contrato existente. Respetar el enmascarado del DNI. Presentar los estados de carga, error y registro inexistente. Mostrar la versión del registro solo cuando tenga utilidad funcional; no exponer detalles técnicos innecesarios al usuario.
> Alta administrativa de familia: Ruta `/admin/familias/nueva`. Formulario accesible para registrar el nombre de referencia. Reutilizar `POST /api/admin/familias`. Validar campos según el contrato backend real. Después del alta exitosa, navegar al detalle correspondiente. Gestionar errores de validación, conflictos y respuestas inesperadas. Ante timeout o resultado incierto, no reintentar automáticamente ni asumir que la creación falló. Aclarar que crear una familia no genera una cuenta de usuario: el acceso sigue gestionándose mediante invitaciones.
> No implementar edición, activación/desactivación ni gestión de tutores. Reutilizar componentes y patrones existentes. Preservar autorización ADMIN, MFA, CSRF y aislamiento escolar. No modificar contratos backend salvo un bloqueo real demostrado. No introducir dependencias innecesarias. Mantener los datos personales fuera de logs, URLs y artefactos de pruebas. Diseño responsive, accesible y compatible con pantallas de 360 px.
> ODD por defecto, sin SDD. Seguir RED → GREEN cuando corresponda. Ejecutar pruebas unitarias, typecheck, lint, build y E2E reales con PostgreSQL descartable. Incluir escenarios de alta, detalle, validaciones, errores, permisos y aislamiento. Evaluar RDD con el mecanismo vigente. No forzar revisiones si no corresponden. Si Engram continúa fallando, conservar la evidencia en ODD sin alterar su configuración. Corregir el error documental `400, 400` si resulta apropiado dentro del alcance; de lo contrario, conservarlo como pendiente.
> Te autorizo a implementar y verificar U2-B, pero todavía no a crear commits, hacer push, abrir PR ni merge. No comenzar U3.

### L2 — Cierre de U2-A y preparación

PR #21 `MERGED` (`c8abaab`); `7164540` y `02ac6ea` son ancestros de `origin/main`; worktree de U2-A limpio (0 cambios, 0 stash). Retirados worktree y rama local sin forzar; rama remota conservada (`02ac6ea`). Worktree y rama de U2-B creados sobre `origin/main` actualizado, sin upstream. `npm ci` correcto.
Contratos verificados: `FamiliaDetalle {id, nombreReferencia, activa, tutores[TutorResumen], version(string)}`; `TutorResumen {id, familiaId, nombre, apellido, dniEnmascarado?, telefono, email, parentesco, activo}`; `POST` → 201 con `FamiliaDetalle`; 404 `FAMILIA_NO_ENCONTRADA`; 400 `VALIDACION` con `detalles`. Las mutaciones no reintentan (`retry: false`) y `apiWrite` nunca reintenta. Un timeout o corte de red de una escritura llega como `ApiError` kind `network`; 502/503/504 como `unavailable`; ≥ 500 como `server`: los tres se tratan como resultado incierto.
Risk previsto: none (frontend; sin backend, migraciones ni cambio de contratos). 

### L3 — Implementación y verificación (2026-11-14)

RED observado: 29 de 32 pruebas nuevas fallaban antes de implementar (las 3 de guards pasaban por los guards existentes). GREEN: 62/62 en `admin-familias`. Suite completa: 18 archivos, 385 pruebas. `typecheck`, `lint` y `build` en verde.
Decisiones técnicas: la versión se valida en el esquema (`^[0-9]{1,19}$`) y no se muestra (S2); 404 y 400 del detalle son una sola pantalla «No encontramos esta familia» y un id con forma peligrosa (`/^[A-Za-z0-9-]{1,64}$/`) ni siquiera se pide; el alta deshabilita el botón en curso y además ignora un segundo envío mientras hay uno pendiente; red/timeout, 502-504 y ≥ 500 (incluida una respuesta 2xx ilegible) se tratan como resultado incierto con enlace «Revisar el listado» y sin reintento; los mensajes del backend nunca se muestran; tras el alta se siembra el detalle en caché y se invalida el prefijo `['admin','families']` (listado y selector de invitaciones). El detalle usa una clave propia (`['admin','family-detail',id]`) para que esa invalidación no relance la petición recién sembrada. `FamilyRow` del listado ahora enlaza al detalle y el listado tiene «Nueva familia» (cambio mínimo sobre U2-A; los 30 tests de U2-A siguen verdes). `FamilyStatusBadge` se extrae porque ahora lo usan dos pantallas (reutilización real).
Corrección de una prueba propia: en el primer E2E fallaba una aserción (el backend real enmascara el DNI con puntos `••••••600`, no con asteriscos); la interfaz era correcta. Se corrigió la regex y el fake pasó al formato real. Como la spec es serial, el resto no había corrido: se reejecutó la suite completa.
E2E: `bash .github/scripts/frontend-e2e.sh` → 66 passed (10.7 min), exit 0, con la comprobación de artefactos del teardown. Nuevas (10): detalle con DNI enmascarado sin versión (la respuesta de la API ya no trae el DNI completo), familia sin tutores + enlace desde el listado, «no encontrado» uniforme (404 de un UUID inexistente y 400 de un id mal formado), alta desde el listado con familia guardada una sola vez, validación sin petición, doble clic = una familia, caída de red **simulada** sin reintento y con datos conservados, 360 px (sin scroll horizontal; controles ≥ 44 px), 401 anónimo y 403 de FAMILIA sobre APIs y pantallas sin crear nada.
Aislamiento entre escuelas: no hay segunda escuela en el E2E; lo cubren las pruebas DB del backend (sin cambios). El E2E demuestra el mismo 404 para un id inexistente.
Documentación: `frontend/README.md` (sección 6 y pruebas). `400, 400` de `docs/FAMILIAS-DEPORTISTAS.md:42` pertenece a la fila del `PUT` (edición): fuera de alcance, se conserva como pendiente para U3 (S10).
RDD: `assess` medio (`executable_change`), 15 rutas, 1084 líneas, `review_due: false` (`under_budget`): sin revisión nativa ni consentimiento. Risk: none.
Limitación: Engram sigue rechazando `mem_save` («multiple active runtime sessions…»); este documento es la evidencia principal. 

### L4 — Commits locales (2026-11-14)

Autorizados por el usuario: «Autorizo crear los dos commits locales de FE-05/U2-B en la rama `feat/fe05-u2b-detalle-alta-familias`.» Sin push, PR, merge, U3 ni cambios de backend; `400, 400` sigue pendiente para U3.
- Commit 1 `859b1c3` — `feat(admin): incorporar detalle y alta administrativa de familias`: páginas, capa de datos, mensajes, ruta, enlaces del listado, `FamilyStatusBadge`, `FakeBackend` y pruebas unitarias (11 archivos). Las 385 pruebas, typecheck, lint y build se ejecutaron sobre estas mismas fuentes.
- Commit 2 (este documento, E2E real, `seed.ts` y `frontend/README.md`): su hash no puede constar aquí. El README va con los E2E porque describe también las pruebas reales y las referencia.
RDD: sin cambios (`assess` medio, `review_due: false`, `under_budget`); no se pidió consentimiento. Engram sigue fallando por múltiples sesiones: este documento es la evidencia principal.
