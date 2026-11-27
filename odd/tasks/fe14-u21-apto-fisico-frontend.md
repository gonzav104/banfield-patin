# FE-14 / U21 — Apto físico anual (frontend, segundo PR)
Rama `feat/u21-apto-fisico-frontend` desde `main` 3e51372 (backend de U21 integrado, CI posterior 8/8). Solo commits locales: no se publica sin autorización posterior; sin despliegue, sin Supabase, sin migraciones, sin U21-BIS ni U22.
Runner: Vitest + Testing Library (`npm test`), FakeBackend, Playwright E2E real (`frontend/e2e`, `.github/scripts/frontend-e2e.sh`), `npm run lint`/`typecheck`/`build`. Delivery: un PR de frontend (`size:exception` a decidir al publicar).

## Specs
- S1 (FAMILIA): «Sección de apto físico por deportista y temporada. Estado documental y vigencia. Carga del archivo, fecha de emisión, nombre del médico y matrícula. Reemplazo de aptos observados. Avisos sobre reglas reglamentarias pendientes de confirmación. Tratamiento de datosVisibles. Mensaje genérico para observaciones de documentos cargados por otra familia. Idempotencia y recuperación de resultados inciertos. Tratamiento de las banderas de carga deshabilitadas.»
- S2 (ADMIN): «Consulta del apto y sus metadatos. Aprobación y observación. Descarga auditada. Corrección de metadatos mediante revisión optimista. Explicación explícita de que corregir un apto aprobado retira su aprobación. Integración con la bandeja de pendientes.»
- S3 (privacidad): «Respetar estrictamente los contratos definitivos del backend. No intentar recuperar ni mostrar metadatos ocultos a otras familias. No introducir información médica en logs, cachés persistentes del navegador ni mensajes de error. No presentar la regla provisional de 2027 como requisito federativo confirmado.»
- S4 (pruebas): «Vitest y FakeBackend. Validaciones de formularios. Idempotencia y conflictos. Accesibilidad y comportamiento móvil. Playwright E2E real. Carga, revisión, corrección, observación y reemplazo. Privacidad entre dos familias. Revocación de acceso. Configuraciones aisladas con las banderas correspondientes. Obtener recuentos de reportes limpios. Completar Gentle AI según el riesgo.»
- S5 (proceso): Watcher de CI corregido (por workflow, rama, evento y headSha, con tope de espera): `~/worktrees/_tools/ci-watch.py`. Registrar U21-BIS (Autoservicio y empadronamiento familiar) como futura unidad previa a U22, sin implementarla.
- S6 (restricciones): «No publicar el frontend sin autorización posterior. No desplegar ni acceder a Supabase. No modificar migraciones históricas. No habilitar escrituras en producción. No comenzar U21-BIS ni U22. No corregir deudas técnicas ajenas. Preservar el checkout principal.»

## Contratos del backend que el frontend respeta (3e51372)
- `GET /api/{familia|admin}/deportistas/{id}/temporadas/{temporadaId}/apto-fisico` → `AptoFisicoTemporada` (`estado`, `bloqueante:false`, `vigencia{desde,hasta,confirmada,fuente}`, `avisos[]`, `documento?{id,estado,fechaEmision,medicoNombre,medicoMatricula,datosVisibles,motivoObservacion,cargadoEn,revision}`); para otra familia los datos y el motivo vienen en `null` y `datosVisibles=false`; `revision` solo para ADMIN.
- Carga: `POST /api/familia/deportistas/{id}/documentos` multipart `tipo=APTO_FISICO`, `temporadaId`, `fechaEmision`, `medicoNombre`, `medicoMatricula`, `archivo` + `Idempotency-Key`; reemplazo: `POST …/documentos/{documentoId}/reemplazo` con los tres datos (sin `tipo` ni `temporadaId`). Misma clave con otros datos → `409 OPERACION_NO_REPETIBLE`.
- ADMIN: revisión `POST …/documentos/{documentoId}/revision`, descarga `GET …/archivo` (auditada), corrección `PUT …/documentos/{documentoId}/datos-apto` con `versionEsperada` (cadena) y los tres datos; `409 APTO_NO_CORREGIBLE | DOCUMENTO_NO_VIGENTE | EDICION_DESACTUALIZADA | CONFLICTO_CONCURRENCIA`.
- Temporadas: FAMILIA elige entre las del deportista (`GET /api/familia/deportistas/{id}/temporadas`); ADMIN entre las de la escuela (`GET /api/admin/temporadas`).

## Tasks
- [x] T1 · S1 S3 · inline · módulo `features/apto-fisico`: esquemas, validación de formulario, mensajes, API y hooks (sin persistencia del navegador) + Vitest
- [x] T2 · S1 S3 · inline · FAMILIA: sección en la ficha, estado/vigencia/avisos, `datosVisibles`, carga y reemplazo con idempotencia, banderas
- [x] T3 · S2 S3 · inline · ADMIN: sección en el detalle, revisión, descarga auditada, corrección con revisión optimista y advertencia, bandeja
- [x] T4 · S4 · inline · FakeBackend + pruebas de componentes (formularios, idempotencia y conflictos, accesibilidad/móvil, privacidad)
- [x] T5 · S4 · inline · E2E real (dos familias, revisión, corrección, observación, reemplazo, revocación, bandera apagada) + shards
- [x] T6 · S4 S5 · inline · docs, ODD, Engram, Gentle AI, commits, informe

Route: todo inline (módulo único y dependiente; sin unidades paralelas independientes ⇒ sin Writer trigger).

## Futura unidad registrada: U21-BIS — Autoservicio y empadronamiento familiar
Provisoria, **antes de U22**, sin implementar. Motivo (relevamiento del 2026-11-26): el modelo admite varios usuarios por familia, varios tutores y familias independientes, pero el portal no deja completar datos de tutores ni de deportistas (la única escritura familiar es la carga documental) ni vincula la identidad del usuario con la del tutor. Condiciones previas al despliegue ya registradas: retención/eliminación de documentación médica y endurecimiento de `usuario.familia_id` (V18, fuera de este ciclo).

## Log
### L1
Pedido del usuario (2026-11-26/27): ver `S1`–`S6` (citas literales). Orden de trabajo: merge del PR #57 con merge commit (3e51372, árbol idéntico al candidato 754e745), CI posterior 8/8 verificada por SHA exacto, y luego este frontend.

### L2 — avance 2026-11-27
Módulo `features/apto-fisico`, secciones familiar y ADMIN, FakeBackend, E2E y reparto de shards (commits 26b1125, 33603b5, 1caf05d). Hallazgos: (1) el portal solo lista temporadas hasta la **activa** (`ta.anio >= t.anio`): sin temporada activa o para una temporada posterior a la activa la familia no puede cargar el apto; es una regla del backend ya vigente y se respeta (limitación registrada, no se cambia el backend en este PR); (2) `portal-ficha` fijaba la lista exacta de títulos y los requests de la ficha: se actualizó con el apto; (3) un escenario de `portal-ficha` borraba las cookies con la sección nueva todavía cargando: ahora espera su estado. El apto sale del listado genérico de documentos del detalle ADMIN para no duplicar controles.

### L3 — cierre local 2026-11-27
Commits sobre 3e51372: 26b1125 (familia), 33603b5 (ADMIN), 1caf05d (E2E + reparto + banderas), 25e54a9 (docs, U21-BIS), abcfe88 (ajustes tras revisión) y este registro. Verificación: Vitest 75 archivos / 1695 tests, lint, tipos y build limpios; `shards.ts check` = 262 escenarios (142 + 116 + 4). E2E reales sobre 1caf05d: shard 1 142/142; shard 2 115/116 (el fallo, `vinculos-vinculacion` «doble envío», pasó 15/15 aislado: flake de temporización de una pantalla no tocada); shard 3 4/4. Tras abcfe88 se reverificaron el shard rápido (auth + apto + portal-ficha) 37/37 y el shard 3 4/4; los shards completos no se repitieron.
Gentle AI: cuatro candidatos por el presupuesto del revisor. 26b1125, 33603b5 y 1caf05d..25e54a9: aprobados y reconocidos, sin bloqueantes (se aplicaron los ajustes útiles: estado «pendiente» siempre consistente, invalidación de la bandeja, helper de revisión, tests deterministas, guarda de CI). abcfe88: el usuario eligió «Skip this time» (no hay registro de revisión; son 60 líneas de ajustes derivados de las revisiones y quedaron cubiertos por las pruebas anteriores).
Pendiente: publicación (requiere autorización), CI de GitHub, retención de documentación médica y evaluación de endurecer `usuario.familia_id` (condiciones previas al despliegue), U21-BIS antes de U22. Limitación conocida: el portal lista temporadas solo hasta la activa, por lo que sin temporada activa o para una posterior la familia no puede cargar el apto.

