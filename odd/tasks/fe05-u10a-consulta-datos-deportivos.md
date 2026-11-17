# FE-05 / U10-A — Consulta e historial administrativo de datos deportivos por temporada

Objetivo: pantallas de **solo lectura** para que el administrador consulte el historial deportivo anual de un deportista y el detalle de una temporada. Solo frontend, sobre contratos existentes (integrados hasta U9).
Rama: `feat/fe05-u10a-consulta-datos-deportivos`; base `9dd8288cbea34d416de29f6384201eab8899bbb5` (`origin/main`, merge del PR #33).
Entrega: tres commits **locales** (autorizados); **sin push, PR ni merge** (no autorizados). Runner: `npm run typecheck`, `npm run lint`, `npm test` (Vitest), `npm run build`, E2E reales con `bash .github/scripts/frontend-e2e.sh` (Docker y PostgreSQL descartable).

## Specs

- S1 (alcance): «Autorizo comenzar exclusivamente **FE-05/U10-A: consulta e historial administrativo de datos deportivos por temporada**. La autorización incluye implementación local y pruebas, pero no commits, push, PR ni merge.» Implementar «únicamente la consulta de datos deportivos existentes».
- S2 (historial): ruta `/admin/deportistas/:id/temporadas`. Consultar el historial deportivo; «Mostrar temporadas en orden descendente»; «año, estado deportivo y datos relevantes existentes»; «Distinguir el estado deportivo anual del estado global del deportista»; «Acceder al detalle de una temporada con datos cargados»; «Volver al detalle del deportista».
- S3 (detalle): ruta `/admin/deportistas/:id/temporadas/:temporadaId`. Mostrar año, categoría, rama, divisional, número de corredor y «Estado deportivo de ese año», con el GET individual del backend de U9. «La revisión `version` debe validarse en el contrato frontend, pero no mostrarse como dato de negocio ni utilizarse todavía para editar.»
- S4 (integración): «Agregar una sección o acceso claramente identificado como «Datos deportivos». No modificar los formularios ni los controles del estado global del deportista.»
- S5 (estados vacíos): solo APIs de lectura. Distinguir: «No existe ninguna temporada en la escuela. Existen temporadas, pero el deportista no tiene datos deportivos cargados. Existe historial deportivo. La temporada solicitada no existe. La temporada existe, pero el deportista no tiene una fila deportiva para ella. No se encuentra el deportista. Error transitorio o fallo de conexión.» Sin información sensible innecesaria. «La creación y activación de temporadas pertenecen a U14. No añadir enlaces de creación de temporadas ni simular esas funcionalidades.»
- S6 (contratos): consumir `GET /api/admin/temporadas`, `GET /api/admin/deportistas/{deportistaId}/temporadas` y `GET /api/admin/deportistas/{deportistaId}/temporadas/{temporadaId}`. «Confirmar la paginación real del listado de temporadas. No asumir que la primera página contiene todas si la API puede devolver más.» Tipos y esquemas Zod coherentes; sin endpoints nuevos ni cambios de backend.
- S7 (seguridad y privacidad): solo ADMIN autenticado con MFA completo; aislamiento entre escuelas según el backend; «Validar los identificadores utilizados en rutas antes de solicitar recursos»; sin términos ni datos privados en URL, almacenamiento persistente ni consola, salvo los UUID de navegación; «No incluir DNI ni CUIL en los nuevos listados deportivos»; proteger estados locales al cambiar de administrador o de deportista; «Evitar que una respuesta tardía de otro recurso o identidad repueble la pantalla»; datos privados en memoria el mínimo necesario. No modificar la autenticación.
- S8 (interfaz): componentes visuales existentes (LoadingBlock, Alert, enlaces); accesibilidad, etiquetas claras, pantallas pequeñas; «No implementar botones inactivos de alta o edición»; «Usar «Datos deportivos» como nombre de sección y «Estado deportivo de [año]» para distinguirlo del estado global».
- S9 (pruebas): RED → GREEN. Vitest: 1 historial ordenado y datos; 2 detalle por deportista y temporada; 3 carga y errores transitorios; 4 ausencia de temporadas; 5 deportista sin historial; 6 distintos 404 y navegación segura; 7 rutas inválidas; 8 cambio de identidad durante consultas; 9 cambio de deportista con respuestas tardías; 10 no filtración de información personal en navegación, almacenamiento ni consola; 11 regresión del detalle administrativo existente. E2E reales de lectura e historial registrados en `frontend/e2e/shards.json`, reutilizando helpers y fixtures («No ampliar el seed de manera innecesaria»). Ejecutar typecheck, lint, Vitest y build; E2E dirigidos si hay Docker. No reducir ni relajar pruebas existentes.
- S10 (ODD/RDD/Engram): documento ODD independiente; RDD sobre el alcance real; revisión independiente si hay riesgos de privacidad, identidad o respuestas asíncronas; Engram con proyecto `banfield-patin`; no resolver conflictos históricos ajenos.
- S11 (publicación futura): «U10-A debe poder integrarse mediante un PR independiente, sin necesitar código de U10-B o U10-C.» Cualquier `size:exception` requiere autorización separada.
- S12 (restricciones): «No realizar commits, push, PR ni merge. No desplegar. No modificar backend, migraciones ni workflows. No implementar alta ni edición deportiva. No comenzar U10-B o U10-C. No implementar U14. No modificar el smoke de familias. No refactorizar formularios antiguos. No modificar otros worktrees ni el checkout original.» «No realizar operaciones remotas.»

## Contratos reales (verificados en `main`)

- `GET /api/admin/temporadas?page&size` → `Pagina<{id, anio, activa}>` = `{contenido, pagina, tamanio, totalElementos}`; tamaño por defecto 20, **máximo 100** (se recorta); del año más nuevo al más viejo. El año válido va de 2020 a 2100 (≤ 81 temporadas), pero **no se asume** una sola página.
- `GET /api/admin/deportistas/{id}/temporadas` → arreglo **sin paginar** de `{id, deportistaId, temporadaId, anio, categoria, rama, divisional, numeroCorredor, estadoDeportivo, version}`, del año más nuevo al más viejo. `404 DEPORTISTA_NO_ENCONTRADO` (también de otra escuela).
- `GET /api/admin/deportistas/{id}/temporadas/{temporadaId}` → la misma fila. Orden de 404: `DEPORTISTA_NO_ENCONTRADO` → `TEMPORADA_NO_ENCONTRADA` → `DEPORTISTA_TEMPORADA_NO_ENCONTRADA`.
- `version` es una **cadena decimal** (`[0-9]{1,19}`); `estadoDeportivo` ∈ {`ACTIVO`,`INACTIVO`,`SUSPENDIDO`}; categoría ≤ 80, rama ≤ 40, divisional ≤ 80, número de corredor ≤ 30, todos opcionales (`null`).
- `GET /api/admin/deportistas/{id}` (U7) trae el DNI y el CUIL completos: para el encabezado del historial se lee **solo** nombre, apellido y estado global (el esquema descarta el resto, así el DNI/CUIL no queda en la caché de esta pantalla).
- `deportista.activo` (global) y `estadoDeportivo` (por temporada) son planos distintos (`docs/TEMPORADAS.md` §4).

## Diseño

- Carpeta nueva `frontend/src/features/admin-datos-deportivos/` (`schemas.ts`, `api.ts`, `hooks.ts`, `messages.ts`, dos páginas y pruebas); se reutilizan `isSafeAthleteId`, `AthleteStatusBadge`, `BackToListLink`, `LoadingBlock`, `Alert`, `Button`, `StatusBadge`, `commonErrorMessage` y las etiquetas de estado deportivo del portal.
- Rutas nuevas, antes del comodín `/admin/*`: `/admin/deportistas/:id/temporadas` y `/admin/deportistas/:id/temporadas/:temporadaId`.
- Claves de consulta bajo `['admin', …]` (privadas: se descartan al cambiar de identidad) y `gcTime: 0` para el historial, la fila y el encabezado. Las pantallas se montan con `key={identidad:id}` como el detalle de U7.
- Las temporadas solo se consultan **cuando el historial está vacío** (para distinguir «ninguna temporada» de «hay temporadas sin datos»); se recorren todas las páginas por `totalElementos`.
- El detalle del deportista agrega una sección «Datos deportivos» con un enlace; sin botones de alta ni edición.

## Tasks

- [x] T1 (S6, S9) Rutas de lectura en `FakeBackend` que reflejan el contrato real (orden descendente, página máxima 100, orden exacto de los 404) — inline.
- [x] T2 (S9) Pruebas Vitest en rojo (RED): ver «RED → GREEN» — inline.
- [x] T3 (S6, S7) Esquemas, API y hooks (`schemas.ts`, `api.ts`, `hooks.ts`, `messages.ts`, `navigation.ts`) — inline.
- [x] T4 (S2, S5, S8) Página de historial y estados — inline.
- [x] T5 (S3, S5, S8) Página de detalle de la temporada — inline.
- [x] T6 (S4) Sección «Datos deportivos» en el detalle del deportista y rutas — inline.
- [x] T7 (S9) E2E reales de lectura, `shards.json` (144 = 88 + 56), README — inline.
- [x] T8 (S9, S10) Verificador independiente, commits locales, cierre ODD y Engram — inline.

Ruta de todas las tareas: **inline** (una sola unidad de comportamiento, sin unidades paralelas independientes ni trigger de escritor).

## Commits locales

| # | SHA | Mensaje | Líneas |
| --- | --- | --- | --- |
| 1 | `f86970a8cb936e4298143c8c13b43e55ce672472` | `feat(deportistas): incorporar consulta e historial deportivo por temporada` | 17 archivos, +1 522 −0 (producción +495; `FakeBackend` +87; Vitest +940) |
| 2 | `55971c1e0253e398ea84170664596ebad06fcd15` | `test(e2e): cubrir consulta e historial deportivo con backend real` | 2 archivos, +261 −0 (spec +260; `shards.json` +1) |
| 3 | `HEAD` | `docs(deportistas): documentar consulta e historial deportivo` | `frontend/README.md` (+12 −2) y este documento |

Sin amend, squash ni rebase y sin `Co-Authored-By`. El commit 1 es verificable por sí solo: no contiene el spec E2E ni la línea de `shards.json` (su manifiesto es el de antes, 133 = 88 + 45). Ninguno depende de U10-B, U10-C ni U14.

### Verificación por commit (export limpio con `git archive`, `npm ci --offline`)

- **Commit 1**: typecheck, lint y build sin errores; Vitest **868 de 868** (37 archivos); `shards.ts check`: 133 = 88 + 45.
- **Commit 2**: typecheck, lint y build sin errores; Vitest **868 de 868**; `shards.ts check`: **144 = 88 + 56**; `playwright test --list`: **144 pruebas en 12 archivos**. El E2E real (56 de 56 en el shard 2) corrió sobre el worktree cuyos `src/` y `e2e/` son **idénticos** a los del commit 2 (`diff -rq` sin diferencias; mismas dependencias).
- **Commit 3**: solo documentación; se verifica el árbol limpio y que no difiera del candidato verificado salvo README y este documento.
- **CI**: los 144 E2E deben confirmarse en CI antes de cualquier merge (el shard 1, con 88, no contiene archivos modificados y no se ejecutó localmente).

## Archivos

Nuevos: `frontend/src/features/admin-datos-deportivos/` (`schemas.ts`, `api.ts`, `hooks.ts`, `messages.ts`, `navigation.ts`, `SportsUi.tsx`, `SportsAccess.tsx`, `AdminDatosDeportivosHistorialPage.tsx`, `AdminDatosDeportivosDetallePage.tsx` y cuatro archivos de pruebas), `frontend/e2e/deportistas-datos-deportivos.real.spec.ts` y este documento. Modificados: `frontend/src/app/routes.tsx` (2 rutas), `frontend/src/features/admin-deportistas/AdminDeportistaDetallePage.tsx` (la sección «Datos deportivos»; sin tocar formularios ni el control del estado global), `frontend/src/features/admin-deportistas/deportista-detalle.test.tsx` (solo se **agregan** 3 pruebas), `frontend/src/test/fakeBackend.ts` (rutas de lectura), `frontend/e2e/shards.json` y `frontend/README.md`. Sin cambios en backend, migraciones ni workflows.

## RED → GREEN (observado)

- **RED** (antes de implementar): 4 archivos de pruebas con **63 pruebas fallando y 27 pasando**, más `sports-schemas.test.ts` que **no podía importar `./schemas`** (0 pruebas coleccionadas). Las 27 que pasaban eran guardas negativas ya ciertas sin implementar (anónimo al login, FAMILIA fuera, etc.). Después de esa corrida se agregaron 3 pruebas de integridad (respuesta que no corresponde al deportista o temporada pedidos); no se observaron en rojo por separado: la mutación M3 muestra que muerden.
- **GREEN, primera pasada**: 127 de 135. Los 8 fallos: **1** era una prueba **existente** de U7 («does not show family, season or sports data»), que prohíbe el texto `familia|temporada|categor` en el detalle; la sección nueva usaba esas palabras en su descripción, y se **cambió el texto de la sección** (no la prueba). Los otros **7 eran defectos de mis pruebas nuevas, no del producto**: `role="status"` ambiguo (el layout trae el suyo), botones propios del layout (hubo que acotar a `main`), lectura de la ubicación sin esperar la navegación y un clic sobre el enlace «Volver» de la pantalla de carga, ya reemplazado. Se corrigieron sin quitar ninguna aserción. Una vez corregido: **135/135**.
- **Lint**: 7 errores en mi helper de navegación de pruebas (`await` sobre un valor no promesa; `async` sin `await`); corregidos.
- **E2E real, primera corrida del shard 2**: **54 pasaron, 1 falló y 1 no corrió** (modo serial). El fallo fue un **defecto real de producto** que jsdom no puede ver: a 360 px una categoría de 79 caracteres sin espacios desbordaba horizontalmente (`break-words` no baja el ancho mínimo de un ítem flex). Corregido con `min-w-0 [overflow-wrap:anywhere]` en el valor de `Row`. **Segunda corrida: 56 de 56**.

## Verificación observada

- `npm run typecheck`, `npm run lint` y `npm run build`: sin errores.
- Vitest completo: **868 pruebas, 37 archivos, 0 fallos** (754 de la base + 114 nuevas: historial 28, detalle 27, riesgos 14, esquemas 42 y 3 de regresión del detalle del deportista; ninguna prueba existente se modificó ni relajó).
- `node e2e/support/shards.ts check`: **144 escenarios = shard 1: 88 + shard 2: 56**, sin omitir ni duplicar.
- E2E reales (`E2E_SHARD=2 bash .github/scripts/frontend-e2e.sh`, PostgreSQL 17 descartable en Docker, backend Spring y Vite reales): **56 de 56 aprobados en 15,0 min**, incluidos los 11 nuevos y la verificación de artefactos sensibles. El shard 1 (88) no se ejecutó localmente: no contiene ningún archivo modificado; lo ejecutará CI.
- **Mutaciones** (15, sobre el código de producción; fuente restaurada tras cada una): 14 **detectadas** (sin orden descendente; sin validar ids; sin guarda de fila ajena; encabezado que conserva DNI/CUIL; detalle que muestra la revisión; conteo por largo de página en lugar de `totalElementos`; sin `gcTime: 0`; todo 404 como temporada inexistente; sin la sección en el detalle; sin «Estado en la escuela»; temporadas consultadas aunque haya historial; sin conservar filtro y página; enlace de retorno roto) y **1 sobreviviente documentada**: quitar `key={identidad:id}` del historial. Esas pantallas no tienen estado local y la protección real la dan las claves de consulta privadas (`['admin', …]`, descartadas al cambiar la identidad) más `gcTime: 0`; la `key` es defensa en profundidad para cuando las unidades de alta y edición agreguen estado local. Una mutación inicial (M11) estaba mal diseñada y se rehízo (M11b).

## RDD y riesgo

- `gentle-ai review assess` sobre el alcance real (21 rutas, 1 852 líneas incluyendo pruebas, README y este documento): **riesgo medio, `review_due: false` (`under_budget`)**; no hay revisión nativa ni consentimiento pendientes.
- **Risk: ítem 4 (orden asíncrono: respuestas tardías y cambio de identidad), mitigado**: claves de consulta privadas, `gcTime: 0`, pantallas con `key` por identidad y ids, guardas contra filas de otro deportista o temporada, y pruebas con respuestas retenidas (`hold`) tanto de otro deportista como de otra identidad. No cambia contratos ni datos persistentes ni autenticación. `shards.json` solo suma un archivo de pruebas al reparto.
- Por la indicación del usuario sobre identidad, privacidad y respuestas asíncronas, se pidió **una verificación independiente acotada** en copia aislada (`rsync` sin `node_modules`, `npm ci --offline`, sin tocar el worktree). **Resultado: sin bloqueantes.** Revisó la privacidad (todas las claves nuevas son privadas; el encabezado descarta DNI/CUIL; nada personal en URL ni almacenamiento), la fidelidad del contrato contra el backend real (orden descendente, `version` como cadena, página máxima 100, orden de los 404), el alcance (solo se **agregan** líneas en `deportista-detalle.test.tsx`, `fakeBackend.ts`, `routes.tsx` y el detalle del deportista; sin alta, edición ni U14) y la calidad de las pruebas; escribió **19 sondas propias** (cambio de identidad con el conteo de temporadas en vuelo, A → B → A con respuestas retenidas, filas duplicadas o ajenas, ids con unicode o barras codificadas, jerarquía de encabezados y nombres de enlaces únicos), todas verdes; y en la copia, `typecheck`, `lint` y Vitest completo dieron **868 de 868**. Confirmó que quitar la `key` por identidad no rompe nada (es redundante en pantallas sin estado local).
- **Advisories** (no bloquean, no se ampliaron): (1) tras un refresco por foco de ventana que recibe un 404, los datos previos quedan en pantalla: es la convención ya vigente en U7 («un refresco fallido no quita los datos») y el cambio real de identidad lo limpian la verificación de sesión y la señal entre pestañas; (2) con ids de temporada repetidos en el historial React avisa una `key` duplicada (sin datos personales); el backend tiene una fila por temporada, así que es solo endurecimiento posible; (3) el verificador no pudo reproducir con `grep` los 88 escenarios del shard 1 (da 86–89 según la expresión); la cifra sale de `playwright test --list` y `node e2e/support/shards.ts check` (144 = 88 + 56).
- **Aviso para U10-B/U10-C:** con React Router un segmento fijo (`temporadas/nueva`, `temporadas/:temporadaId/editar`) gana a `:temporadaId`, así que las rutas nuevas se agregan sin reordenar nada; hoy `nueva` o `editar` como id de temporada se piden al backend (que responde 400, tratado como «temporada no encontrada»).

## Log

- L1 (2026-11-17, usuario): pedido original — «Continuamos con Patín Carrera Banfield San Pedro. El diseño general de FE-05/U10 quedó aprobado. Autorizo comenzar exclusivamente **FE-05/U10-A: consulta e historial administrativo de datos deportivos por temporada**. La autorización incluye implementación local y pruebas, pero no commits, push, PR ni merge.» (el texto completo de las secciones 1–11 está resumido en S1–S12).
- Preparación: `origin/main` (referencia local, sin operaciones remotas) contiene `9dd8288`; rama y worktree creados desde `origin/main`, sin *upstream*; dependencias instaladas con `npm ci --offline`. Línea base: typecheck y lint en verde.
- Hallazgo de diseño: el listado de temporadas está paginado (máximo 100 por página, `Pagina.TAMANIO_MAXIMO`). Como U10-A solo necesita saber **si la escuela tiene alguna temporada**, se pide una sola fila (`?page=0&size=1`) y se lee `totalElementos`; el largo de una página nunca se toma como el total, y esa consulta solo ocurre cuando el historial está vacío.
- Incidente de proceso: ninguno remoto. Todo lo remoto se limitó a la referencia local de `origin/main` (sin `fetch`).
- L2 (2026-11-17, usuario): «Autorizo preparar y crear los tres commits locales de **FE-05/U10-A — Consulta e historial administrativo de datos deportivos**. No autorizo todavía push, apertura de PR, merge ni despliegues.» Se crearon los tres commits (ver «Commits locales»); el commit de documentación es este.
- Mutación **informativa** no detectada: quitar `key={identidad:id}` del historial (ver «Verificación observada»). No es una laguna de pruebas: la pantalla no tiene estado local y la protección la dan las claves de consulta privadas; la `key` queda como defensa para U10-B y U10-C.

## Riesgos residuales

- Los **144 E2E** (shard 1 con 88 y shard 2 con 56) deben confirmarse en CI antes de cualquier merge; localmente solo se ejecutó el shard 2 completo.
- Un refresco por foco de ventana que recibe un 404 conserva los datos previos en pantalla (convención vigente de U7).
- Con ids de temporada repetidos en el historial React avisa una `key` duplicada; el backend tiene una fila por temporada.
- Sin U14 las temporadas solo se crean por API: la pantalla lo explica con un estado vacío y no ofrece atajos.
- U10-B y U10-C agregarán rutas fijas (`temporadas/nueva`, `.../editar`) que ganan a `:temporadaId` en React Router sin reordenar nada.
- El aviso de chunks de más de 500 kB del `build` ya existía.

## Publicación (propuesta; **no se publicó nada**)

- Tamaño real: **1 881 líneas** (`additions + deletions`) en 21 archivos: producción 495, `FakeBackend` 87, Vitest 940, E2E 261 y documentación 98 (más de un 60 % son pruebas). El «400» es una referencia de planificación (`docs/ODD.md`) y una regla de la skill `chained-pr` («dividir salvo `size:exception` aceptado»), no un control automático del repositorio.
- Una pasada de división: no hay corte cohesivo que deje cada porción en ≤ 400 (solo la producción ya son 495, y las pruebas de cada pantalla comparten el `FakeBackend` y los esquemas). Dividir por pantalla obligaría a repetir infraestructura y a pedir una revisión nueva por porción sin ganar legibilidad.
- **Propuesta:** un único PR con los tres commits (un historial legible: código con sus pruebas, E2E, documentación) y una solicitud explícita de `size:exception`, que **requiere autorización separada**. El PR es independiente: no necesita código de U10-B, U10-C ni U14.
