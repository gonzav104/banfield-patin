# FE-05 / U10-B — Alta administrativa de datos deportivos por temporada

Objetivo: el administrador carga los datos deportivos de un deportista en una temporada existente que todavía no los tiene. Solo frontend, sobre contratos existentes (backend integrado hasta U9 y lectura de U10-A).
Rama: `feat/fe05-u10b-alta-datos-deportivos`; base `2e9c65deac8b37b3babe7d3c0f96098b6c20e69c` (`origin/main`, merge del PR #34).
Entrega: tres commits **locales** (autorizados); **sin push, PR, merge ni `size:exception`** (no autorizados). Runner: `npm run typecheck`, `npm run lint`, `npm test` (Vitest), `npm run build`, E2E reales con `E2E_SHARD=<n> bash .github/scripts/frontend-e2e.sh` (Docker y PostgreSQL descartable).

## Specs

- S1 (alcance): «Autorizo limpiar los recursos locales de U10-A e implementar localmente FE-05/U10-B — Alta administrativa de datos deportivos por temporada. No autorizo commits, push, PR, merge ni despliegues.» Implementar `/admin/deportistas/:id/temporadas/nueva`; «Acceder desde el historial deportivo»; «Elegir una temporada existente que todavía no tenga datos para ese deportista»; «Identificar visualmente cuál es la temporada activa»; completar categoría, rama, divisional, número de corredor y estado deportivo; «Crear la fila con el POST existente»; «Acceder al detalle después de un `201` confirmado». «El estado deportivo inicial será `ACTIVO`». «El número de corredor es opcional y debe conservar los ceros iniciales.» «No implementar edición de filas existentes ni resolución de conflictos optimistas: eso pertenece a U10-C.»
- S2 (selector): `GET /api/admin/temporadas`; «Verificar la paginación real y recuperar todas las temporadas necesarias, sin asumir que la primera página es siempre suficiente.» «Cruzar el listado con el historial del deportista para ofrecer solamente temporadas sin fila deportiva.» Contemplar: «Ninguna temporada creada. Todas las temporadas ya utilizadas. Temporada activa. Temporadas inactivas, que también permiten cargar datos. Error al consultar temporadas. Cambio de disponibilidad entre selección y envío.» «No permitir crear ni activar temporadas; esas funciones pertenecen a U14.»
- S3 (validación): categoría ≤ 80, rama ≤ 40, divisional ≤ 80, número de corredor ≤ 30 «como texto», estado deportivo «enum válido», «Normalización compatible con los DTO existentes», «Campos opcionales representados correctamente». «No inventar catálogos oficiales ni restricciones de obligatoriedad.» Errores comprensibles sin detalles internos.
- S4 (solicitud e incertidumbre): «Deshabilitar campos mientras el POST está pendiente. Guarda síncrona con `useRef` contra envíos múltiples. Capturar exactamente el payload enviado. Conservar el borrador exclusivamente en memoria. Evitar reenvíos automáticos. Descartar efectos de respuestas tardías si cambió la identidad o el recurso.» Tratar `409 DEPORTISTA_TEMPORADA_DUPLICADA`, `409 NUMERO_CORREDOR_DUPLICADO`, `409 DEPORTISTA_INACTIVO`, validación, autenticación/autorización e incertidumbre por red, servidor o respuesta ilegible. Para lo incierto: «Ofrecer una verificación explícita mediante GET de la fila seleccionada. Si la fila existe, mostrar su estado confirmado sin atribuir automáticamente su creación al POST original. Si no existe, explicar que esa lectura no descarta una confirmación posterior de la petición original. No habilitar reintentos automáticos ni generar afirmaciones de éxito sin evidencia. Permitir una decisión informada del administrador, respetando los conflictos de unicidad que resuelve el backend.»
- S5 (privacidad): ADMIN con MFA; aislamiento entre escuelas; UUID como identificadores de navegación; «Ausencia de DNI/CUIL en el nuevo formulario, caché y eventos»; reinicio ante cambios de identidad o deportista; «Ningún borrador en URL, localStorage o sessionStorage. Ninguna información personal en logs.»
- S6 (pruebas): RED → GREEN. Vitest: 1 selector y exclusión de filas existentes; 2 temporada activa e inactiva; 3 ausencia de temporadas; 4 todas utilizadas; 5 validaciones y normalización; 6 alta exitosa y navegación; 7 número duplicado; 8 deportista inactivo; 9 solicitudes simultáneas; 10 bloqueo de campos durante el envío; 11 resultado incierto y verificación; 12 cambio de identidad con POST pendiente; 13 respuesta tardía y privacidad; 14 errores y estados vacíos. E2E reales dirigidos al alta, «reutilizando backend PostgreSQL descartable, fixtures y helpers existentes»; actualizar `frontend/e2e/shards.json` «sin omitir ni duplicar escenarios. La base actual es de 144 E2E». Typecheck, lint, Vitest completo y build; E2E dirigidos si hay Docker.
- S7 (ODD/RDD/Engram): ODD específico; RDD temprano y sobre el árbol definitivo (si exige consentimiento, detenerse); revisión independiente focalizada en envíos duplicados, privacidad, respuestas asíncronas y resultados inciertos; Engram `banfield-patin`; no resolver conflictos históricos ajenos.
- S8 (restricciones): «Sin commits, push, PR ni merge. Sin despliegues ni migraciones remotas. Sin modificar backend, migraciones ni workflows. Sin comenzar U10-C ni U14. Sin refactorizar formularios anteriores. Sin modificar el smoke de familias. Sin tocar el checkout original ni worktrees ajenos.» «No realizar operaciones remotas.»

## Contratos reales (verificados en `main`)

- `POST /api/admin/deportistas/{id}/temporadas` con `{temporadaId, categoria?, rama?, divisional?, numeroCorredor?, estadoDeportivo?}` → `201` con `DeportistaTemporadaRespuesta` (incluye `version`, que U10-B no usa) y `Location` de la fila. Cualquier otra propiedad → `400 SOLICITUD_INVALIDA`; el estado por defecto es `ACTIVO` si falta.
- Normalización del DTO: categoría, rama y divisional se recortan, colapsan los espacios internos y un valor en blanco pasa a `null`; el número de corredor se recorta (conserva ceros iniciales y espacios internos) y en blanco pasa a `null`. Sin caracteres de control (`400 VALIDACION`).
- Orden real de errores (`DeportistaTemporadaAdminService.crear`): `404 DEPORTISTA_NO_ENCONTRADO` → `404 TEMPORADA_NO_ENCONTRADA` → `409 DEPORTISTA_INACTIVO` → `409 DEPORTISTA_TEMPORADA_DUPLICADA` → `409 NUMERO_CORREDOR_DUPLICADO` (único por escuela y temporada, entre todos los deportistas). Una carrera la resuelve la restricción de la base con los mismos códigos. Un `409` de otra clase (`CONFLICTO_CONCURRENCIA`) significa que no se aplicó nada.
- `GET /api/admin/temporadas?page&size`: `Pagina<{id, anio, activa}>`, hasta 100 por página, del año más nuevo al más viejo. `GET .../{id}/temporadas` (historial) y `GET .../{id}/temporadas/{temporadaId}` (fila) ya integrados en U10-A.

## Diseño

- Ruta `/admin/deportistas/:id/temporadas/nueva` (el segmento fijo gana a `:temporadaId`); enlace «Cargar datos de una temporada» en el historial, que no se ofrece si la escuela no tiene temporadas ni si el deportista está inactivo (el backend lo rechazaría con `DEPORTISTA_INACTIVO`).
- Selector nativo de temporadas pendientes = `GET /api/admin/temporadas` recorrido **por todas las páginas** (se corta por `totalElementos`, nunca por el largo de una página) menos las del historial; la activa se marca en la propia etiqueta de la opción.
- El borrador vive solo en el estado del componente. El `POST` lleva `null` para cada opcional en blanco y el estado elegido; se captura el payload exacto enviado y, ante un resultado incierto, la verificación es un `GET` de la fila de la temporada elegida (sin caché), nunca un reintento.
- Tras el `201` se navega al detalle de la fila (que muestra el aviso «Datos deportivos cargados.»); el historial se refresca desde el servidor.

## Tasks

- [x] T1 (S6) Ruta `POST` del `FakeBackend` que refleja el contrato real (orden de errores, normalización, `400`) — inline.
- [x] T2 (S6) Pruebas Vitest en rojo (RED): ver «RED → GREEN» — inline.
- [x] T3 (S2, S3, S5) API (`listAllSeasons`, `createSportsRow`), esquemas, hooks, `sports-form.ts` y mensajes — inline.
- [x] T4 (S1, S2, S4) Página de alta, selector y estados — inline.
- [x] T5 (S1) Enlace desde el historial, aviso en el detalle y rutas — inline.
- [x] T6 (S6) E2E reales del alta, `shards.json`, README — inline.
- [x] T7 (S6, S7) Verificaciones completas, RDD, verificador independiente, cierre ODD y Engram — inline.

Ruta de todas las tareas: **inline** (una sola unidad de comportamiento, sin unidades paralelas independientes ni trigger de escritor).

## Commits locales

| # | SHA | Mensaje | Líneas |
| --- | --- | --- | --- |
| 1 | `be3e7c966424ebe6080c39f1cd86a59a7a3a10e7` | `feat(deportistas): incorporar el alta de datos deportivos por temporada` | 16 archivos, +1 944 −21 (producción +773 −14; `FakeBackend` +51 −4; Vitest +1 117 −2; aserción E2E de U10-A +3 −1) |
| 2 | `6196fd6cb723d6d37883a0887fde108483bca80a` | `test(e2e): cubrir el alta de datos deportivos con backend real` | 2 archivos, +359 −0 (spec +358; `shards.json` +1) |
| 3 | `HEAD` | `docs(deportistas): documentar el alta de datos deportivos` | `frontend/README.md` y este documento |

Sin amend, squash ni rebase y sin `Co-Authored-By`. **El ajuste de la aserción de U10-A que prohibía el enlace de carga está en el commit 1**, junto con la funcionalidad que lo introduce: no queda ninguna regresión conocida para el commit 2. Ninguno depende de U10-C ni de U14.

### Verificación por commit (export limpio con `git archive`, `npm ci --offline`)

- **Commit 1**: typecheck, lint y build sin errores; Vitest **953 de 953** (40 archivos); `shards.ts check`: 144 = 88 + 56 y `playwright test --list`: 144 en 12 archivos (aún sin el spec del alta).
- **Commit 2**: typecheck, lint y build sin errores; Vitest **953 de 953**; `shards.ts check`: **158 = 88 + 70** y `playwright test --list`: **158 en 13 archivos**; `src/` y `e2e/` **idénticos** (`diff -rq`) al worktree sobre el que corrió el E2E real definitivo (shard 2, **70 de 70** en 15,5 min, con la verificación de artefactos sensibles), con las mismas dependencias; por eso no se repitió el E2E sobre el export.
- **Commit 3**: solo documentación; se verifica el árbol limpio y que no difiera del candidato verificado salvo README y este documento.
- **CI**: los **158 E2E** (shard 1 con 88 y shard 2 con 70) deben confirmarse en CI antes de cualquier merge; el shard 1 no contiene archivos modificados y no se ejecutó localmente.

### Controles de integridad (evidencia)

| # | Control | Evidencia |
| --- | --- | --- |
| 1 | Una temporada que desaparece del selector no puede enviarse | Vitest «a season that disappears from the selector is not sent anymore» (corrección de A1, RED → GREEN): `current.temporadaId` es lo que el selector muestra |
| 2 | Una selección previa no se reutiliza en silencio al cambiar de temporada | tras un `409` duplicado o `404` de temporada se limpia la selección (pruebas «a season that got data meanwhile…» y «a season that no longer exists…», y E2E equivalente); «Elegir otra temporada» parte de cero |
| 3 | Con un resultado incierto siempre queda la verificación | Vitest «while an answer is unconfirmed, a list that leaves no season to offer does not hide the way to verify…» (corrección de A2) y las pruebas de los cuatro tipos de fallo |
| 4 | La verificación usa el deportista y la temporada del `POST` original | se lee `sent.temporadaId` (captura exacta del payload); Vitest y E2E comprueban la URL del `GET`; la mutación que usa la selección actual es **equivalente** (formulario bloqueado en `uncertain`) |
| 5 | Una respuesta tardía no modifica el estado de otra identidad | tres pruebas de identidad y de cambio de deportista con `hold`, incluida la que impide que la respuesta tardía **recargue** lo que mira la identidad nueva (agregada tras una mutación sobreviviente) |
| 6 | Sin envíos duplicados en el mismo ciclo de render | tres envíos en la misma tarea → **un** `POST` (Vitest y E2E con `clickCount: 3`); el verificador probó Enter dos veces, doble clic y doble envío síncrono con `StrictMode` |
| 7 | Sin reintentos automáticos | tras cada tipo de fallo incierto se espera y el `POST` sigue siendo uno solo; «Volver a intentar» es una decisión explícita y su cuerpo es idéntico al primero |
| 8 | Opcionales vacíos según el contrato | el `POST` lleva `null` (prueba de payload exacto, 6 claves); el backend real lo guarda como `null` (E2E) |
| 9 | El número conserva los ceros iniciales | Vitest `'0008'` hasta la fila guardada y E2E `'007…'` leído de la API real |
| 10 | Nada sensible en URL, storage ni consola | Vitest (URL, `localStorage`, `sessionStorage`, consola, cuerpos del `POST` y caché sin DNI/CUIL) y E2E (URLs, consola y almacenamiento) |

### Resultados inciertos (lo que la pantalla dice y no dice)

- Un `POST` con respuesta perdida **puede haberse aplicado**: el formulario queda cerrado y no se afirma éxito ni fracaso.
- Un `GET` que **encuentra** la fila no demuestra quién la creó: «Coinciden con lo que enviaste, pero no podemos saber si los cargó este envío u otro».
- Un `GET` que devuelve **404** no garantiza que el `POST` original nunca se confirme: «Esa lectura no descarta que el envío anterior se confirme más tarde».
- Un reintento manual que termina en conflicto de unicidad se trata sin duplicar nada y con la pista «el intento anterior no se pudo confirmar» (que no se arrastra a otra temporada).

## Archivos

Nuevos: `AdminDatosDeportivosAltaPage.tsx`, `sports-form.ts` y sus pruebas (`datos-deportivos-alta.test.tsx`, `datos-deportivos-alta-riesgos.test.tsx`, `sports-form.test.ts`) en `frontend/src/features/admin-datos-deportivos/`; `frontend/e2e/deportistas-datos-deportivos-alta.real.spec.ts`; este documento. Modificados: `api.ts` (`listAllSeasons`, `createSportsRow`), `hooks.ts` (`useAllSeasons`, `useCreateSportsRow`), `messages.ts`, `navigation.ts`, `schemas.ts` (`Season`), `AdminDatosDeportivosHistorialPage.tsx` (enlace de carga y nota para el inactivo), `AdminDatosDeportivosDetallePage.tsx` (aviso por *flag*), `frontend/src/app/routes.tsx`, `frontend/src/test/fakeBackend.ts` (`POST`), `frontend/e2e/shards.json` (+1), `frontend/README.md` y, por el límite de alcance que se movió, **dos** pruebas de U10-A: una de Vitest del historial («offers no way to create or edit…», que prohibía todo enlace de carga) y una aserción del spec E2E de U10-A que hacía lo mismo; ambas se **ajustaron** (ahora exigen el enlace de carga y siguen prohibiendo editar, crear o activar temporadas) y no se relajó nada más. Sin cambios en backend, migraciones, workflows ni seed.

## RED → GREEN (observado)

- **RED** (antes de implementar): **54 pruebas fallando** (historial 4 de 32; alta 44 de 51; riesgos 6 de 6) y `sports-form.test.ts` **sin poder importar `./sports-form`** (0 pruebas coleccionadas). Las que pasaban eran guardas negativas ya ciertas (p. ej. «no se ofrece el enlace sin temporadas»). Un primer intento de RED falló por una llave duplicada **mía** al editar el archivo de historial; se corrigió y se rehízo la medición.
- **GREEN**: 191 de 192 a la primera (toda la carpeta `admin-datos-deportivos`: las de U10-A más las nuevas); el único fallo era un defecto de **mi helper de prueba** (no renderizaba la sonda de ubicación) y se corrigió sin tocar aserciones. Una pasada de lint marcó dos errores en mi `FakeBackend` (una regex con caracteres de control y un `String()` sobre `unknown`), corregidos.
- **E2E real, primera corrida del shard 2**: 57 pasaron, **1 falló y 12 no corrieron** (modo serial). Fue una carrera de **mi spec** (leí las opciones del selector antes de que el formulario cargara; `allTextContents()` no espera); el producto ya había creado y mostrado la fila. Se cambió por un helper que espera el formulario. **Segunda corrida: 70 de 70** (los 14 del alta incluidos).
- **Correcciones por el verificador independiente (RED → GREEN con 3 pruebas nuevas, antes en rojo)**: A1 una temporada que desaparece del selector (otro administrador la cargó) seguía enviándose aunque el selector mostrara el marcador de posición; A2 durante una respuesta sin confirmar, si la lista no dejaba temporadas por ofrecer se ocultaba «Verificar la temporada» y aparecía «Ya hay datos en todas las temporadas»; A3 «Elegir otra temporada» no olvidaba el intento anterior y un duplicado en la otra temporada se achacaba a él.

## Verificación observada

- Sobre el árbol definitivo: `npm run typecheck`, `npm run lint` y `npm run build` sin errores; Vitest **953 de 953** (40 archivos): 868 de la base + 85 nuevas (formulario 20, alta 54, riesgos 7, historial 4). El aviso de chunks de más de 500 kB del `build` ya existía.
- **E2E reales** (`E2E_SHARD=2 bash .github/scripts/frontend-e2e.sh`, PostgreSQL 17 descartable en Docker, backend Spring y Vite reales), **sobre el árbol definitivo** (después de las correcciones del verificador): **70 de 70 aprobados en 15,5 min**, incluidos los 14 escenarios del alta y la verificación de artefactos sensibles. Dos escenarios dejan que el **backend real** aplique (o no) el `POST` mientras se corta la conexión del navegador (`page.route` con `route.fetch()` + `abort`, o solo `abort`), así que la verificación posterior lee la fila real; el único simulado de verdad es «la escuela sin ninguna temporada». El shard 1 (88) no contiene archivos modificados y no se ejecutó localmente: los **158 E2E deben confirmarse en CI** antes de cualquier merge.
- `node e2e/support/shards.ts check`: **158 escenarios = shard 1: 88 + shard 2: 70** (base: 144); `playwright test --list` coincide. El spec nuevo tiene 14.
- **Mutaciones** (19 sobre el código de producción; fuente restaurada tras cada una): 17 **detectadas** (temporada no obligatoria; opcionales en blanco como cadena vacía; número sin ceros iniciales; solo la primera página de temporadas; selector que no excluye las temporadas con fila; sin guarda sincrónica; campos sin bloquear con el `POST` pendiente; un 5xx o un `201` ilegible que deja de ser incierto; verificación que siempre dice «coincide»; duplicado sin refrescar el historial; aviso de carga siempre visible; enlace de carga para un inactivo; inactivo con formulario; borrador en `localStorage`; payload en la consola; `409` de número sin marcar el campo; y la **identidad de la respuesta sin comprobar**, que **sobrevivió en la primera pasada y llevó a agregar una prueba** nueva: una respuesta tardía de otra identidad no debe ni recargar lo que mira la identidad nueva) y **2 informativas no detectadas**: quitar `key={identidad:id}` de la página (el *gating* por datos desmonta el formulario ante cualquier cambio de identidad o deportista, así que la `key` es defensa en profundidad) y una verificación que usa `values.temporadaId || sent.temporadaId` (**mutante equivalente**: con el formulario bloqueado en `uncertain` los dos valores son siempre iguales).

## RDD y riesgo

- `gentle-ai review assess` sobre el alcance real (20 rutas, 2 318 líneas incluyendo pruebas y docs al evaluarlo): **riesgo medio, `review_due: false`** (`under_budget`); sin revisión nativa ni consentimiento pendientes.
- **Risk: ítem 4 (orden asíncrono: envíos duplicados, respuestas tardías y cambio de identidad), mitigado**: guarda sincrónica, campos bloqueados, identidad capturada al enviar, `key` e identidad en la pantalla, respuesta descartada si cambió la identidad, consultas privadas con `gcTime: 0`, verificación por `GET` sin caché y sin reenvíos automáticos. No cambia contratos, backend, migraciones ni autenticación; el `POST` solo crea una fila nueva (no modifica ni borra datos existentes).
- **Verificación independiente** (copia aislada con `rsync` sin `node_modules`, `npm ci --offline`, sin Docker ni E2E, sin tocar el worktree): **sin bloqueantes**. Revisó envíos duplicados, el flujo incierto (sin afirmaciones de éxito ni de atribución; la verificación apunta a la temporada **enviada**), identidad y asincronía, fidelidad al backend real (orden de `404`/`409`, normalización, longitud en unidades UTF-16 en ambos lados), alcance (solo las dos aserciones de U10-A ajustadas) y calidad de las pruebas; escribió **13 sondas propias** (Enter dos veces, doble clic, doble envío síncrono con `StrictMode`, `Volver a intentar` con cuerpo idéntico, verificación que responde después de navegar, `201` de otra temporada tratado como incierto, `201` con DNI/CUIL en el cuerpo sin llegar a ninguna caché, segunda página de temporadas que falla sin selector parcial…), todas verdes, y confirmó 950 de 950 en su copia (antes de las tres pruebas nuevas).
- **Advisories**: A1, A2 y A3 se **corrigieron** (ver arriba). Quedan **documentados, sin cambio**: A4 `maxLength` limita la entrada cruda a 80/40/30 unidades antes de normalizar (un texto pegado más largo pero válido tras colapsar espacios se recorta en silencio; es el mismo patrón que los formularios de U7/U8) y el cliente rechaza U+001C–U+001F en los extremos de un texto, donde `String.strip()` de Java los quitaría (fuera de dominio); A5 un fallo de red al obtener el token CSRF (antes del `POST`) se informa como incierto, lo cual es conservador y no causa daño.

## Log

- L1 (2026-11-17, usuario): pedido original — «Autorizo **limpiar los recursos locales de U10-A e implementar localmente FE-05/U10-B — Alta administrativa de datos deportivos por temporada**. No autorizo commits, push, PR, merge ni despliegues.» (el texto completo de las secciones 1–9 está resumido en S1–S8).
- Fase A: `origin/main` (referencia local) contiene `2e9c65d` y los tres commits de U10-A; el worktree estaba limpio; se retiró sin forzar y se borró su rama local con `git branch -d`; la remota se conserva. Rama y worktree de U10-B creados desde `2e9c65d`, sin *upstream*; `npm ci --offline`.
- Hallazgo de diseño: el listado de temporadas está paginado (máximo 100). A diferencia de U10-A (que solo necesitaba saber si hay alguna), el selector necesita **todas**: se recorre por páginas hasta alcanzar `totalElementos` y una página vacía antes de llegar, o no llegar nunca, es una respuesta mal formada (no se muestra un selector parcial).
- Hallazgo de diseño: la clave natural (deportista y temporada) hace **determinista** la verificación de una carga incierta con un `GET` de la fila, a diferencia del alta de deportistas (indicio por DNI). Aun así, una fila encontrada nunca se atribuye al envío original, y una fila ausente no descarta una confirmación posterior.
- Incidente de proceso: ninguno remoto. La verificación de `origin/main` y de la rama remota se hizo con referencias locales (más un `ls-remote` de solo lectura, que se declara).
- L2 (2026-11-17, usuario): «Autorizo preparar y crear los tres commits locales de **FE-05/U10-B — Alta administrativa de datos deportivos por temporada**. No autorizo push, PR, merge, despliegue ni `size:exception` todavía.» Se crearon los tres commits (ver «Commits locales»); el de documentación es este.
- Las dos mutaciones informativas no detectadas se conservan registradas (ver «Verificación observada»): 17 de 19 detectadas; la `key` por identidad es defensa en profundidad y la verificación con `values.temporadaId || sent.temporadaId` es un mutante equivalente.

## Riesgos residuales

- Los **158 E2E** deben confirmarse en CI antes de cualquier merge (localmente solo se ejecutó el shard 2 completo).
- **No existe un endpoint de borrado**: una carga equivocada solo podrá corregirse con la edición de U10-C.
- Sin U14 las temporadas solo se crean por API: la pantalla lo explica con un estado vacío y sin atajos.
- Advisories documentados, sin cambio: `maxLength` recorta en silencio un texto pegado más largo que el límite pero válido tras colapsar espacios (mismo patrón que U7/U8) y un fallo al pedir el token CSRF antes del `POST` se informa como incierto (conservador).
- El aviso de chunks de más de 500 kB del `build` ya existía.

## Publicación (propuesta; **no se publicó nada y `size:exception` no está autorizada**)

- Tamaño: unas **2 460 líneas** (`additions + deletions`) en 20 archivos (el número exacto sale de `git diff --shortstat <base> HEAD`): producción ≈ 787, `FakeBackend` ≈ 55, Vitest ≈ 1 119, E2E ≈ 363 y documentación ≈ 148; más del 60 % son pruebas. El «400» es una referencia de planificación (`docs/ODD.md`) y una regla de la skill `chained-pr` («dividir salvo `size:exception` aceptado»), no un control automático del repositorio.
- **Por qué los tres commits van juntos**: la funcionalidad es una unidad indivisible: el selector, el formulario, el `POST`, la verificación del resultado incierto y la navegación al detalle solo tienen sentido completos (partir el `POST` de su verificación dejaría un estado donde un resultado incierto no tiene salida, y partir el selector del formulario dejaría una pantalla sin acción). El segundo commit es el E2E real de esa misma unidad y el tercero su documentación. Una pasada de división no deja ninguna porción en ≤ 400 (la producción sola son ≈ 787 líneas) y dividir por pantalla obligaría a repetir `FakeBackend`, esquemas y hooks y a pedir una revisión nueva por porción sin ganar legibilidad.
- **Propuesta:** un único PR con los tres commits (código con sus pruebas, E2E, documentación) y una solicitud explícita de `size:exception`, que **requiere autorización separada**. Es independiente: no necesita U10-C ni U14. La excepción no exime del CI obligatorio ni de las revisiones exigidas.
