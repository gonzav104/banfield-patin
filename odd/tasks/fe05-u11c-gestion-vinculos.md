# FE-05/U11-C — Gestión administrativa de vínculos
Rama: `feat/fe05-u11c-gestion-vinculos`; base: `dcb45a285001e20892a8e9753c518d220088c011`.
Cierre local: un único commit autorizado condicionalmente a las comprobaciones finales; sin push ni PR. Runner: Vitest y Playwright con backend/PostgreSQL descartables.

## Specs
- S1: “Retirá exclusivamente ese worktree sin forzar y eliminá su rama local mediante `git branch -d`.” Condiciones: PR #39 integrado, siete jobs correctos, árbol limpio; conservar remoto y respaldos.
- S2: “Implementar las acciones administrativas de revocar vínculos familiares y cambiar la familia principal desde el frontend.” POST existentes con `{versionEsperada}`; solo consulta del deportista, ficha familiar sin controles redundantes.
- S3: “Confirmación explícita.” “Explicar que retira el acceso familiar al deportista.” “Conservar el historial.” “No promover automáticamente otra familia principal.”
- S4: “Permitir seleccionar únicamente vínculos activos y familias habilitadas.” “Explicar que no se revocan otras familias ni se transfiere automáticamente al deportista.”
- S5: “Capturar `versionVinculos` al abrir la confirmación.” “No reemplazar silenciosamente la revisión capturada.” “Tratar `409 EDICION_DESACTUALIZADA` mediante consulta de datos vigentes y nueva confirmación.”
- S6: “Distinguir `CONFLICTO_CONCURRENCIA`, `VINCULO_NO_ACTIVO` y `FAMILIA_INACTIVA`.” “Ante resultados inciertos, no reintentar automáticamente ni afirmar que la operación falló.” “Mostrar éxito únicamente cuando el backend lo confirme.”
- S7: “Evitar dobles envíos.” “Proteger el aislamiento entre usuarios, deportistas y respuestas tardías.” No mostrar revisiones internas ni datos personales adicionales; actualizar consultas afectadas.
- S8: “Trabajar con RED → GREEN.” “Ejecutar typecheck, lint, Vitest, build y E2E reales pertinentes.” Confirmación/cancelación, elegibilidad, principal sin promoción, stale/nueva confirmación, incertidumbre/verificación, envíos simultáneos, respuestas tardías, cachés y accesibilidad.
- S9: “Si toda la verificación final es satisfactoria y RDD lo permite, autorizo crear **un único commit local** con los cambios propios de U11-C:” `feat(admin-vinculos): gestionar revocación y familia principal`. “No utilizar amend, squash ni rebase.” “No modificar backend, migraciones, dependencias ni workflows.” Sin push, PR, merge ni despliegue. Autorización anterior sin commits conservada como historia en L1 y L5.

- S10: “Ejecutá el shard completo con backend y PostgreSQL descartables.” “Verificá que se ejecuten también los escenarios preexistentes del shard, no solamente los siete nuevos.” “No repitas revisiones o mutaciones completas que ya están aprobadas, salvo que un cambio posterior lo requiera.” “Confirmá que solo incluya los once archivos previstos.”

## Tasks
- T1 [S1] inline, completada: merge verificado en GitHub, siete jobs success, árbol limpio, worktree retirado sin forzar y rama local eliminada con -d. Sin commit.
- T2 [S2-S7] inline, completada (pruebas enfocadas finales 242/242): implementación de ambas acciones y sus regresiones. Lectura preparada inline con mapa delegado; no existe motivo de escritor paralelo. Risk: items 2 y 4 (acceso familiar y concurrencia).
- T3 [S8,S10] completada: manifiesto válido, typecheck/lint/build correctos, Vitest completo 1290/1290 y shard 2 oficial completo 93/93 (86 previos + 7 nuevos). Runner delegado por duración; sin nueva revisión ni mutaciones.

- T4 [S9-S10] inline, preparado para el único commit local autorizado: código y pruebas revisados sin cambios; revisión final del índice antes de ejecutar Git. El SHA y la confirmación de esta tarea se registran en Engram tras observar el resultado, sin amend ni un segundo commit para autorreferenciar este documento.

## Log
### L1 — Solicitud vigente literal
Cambio de herramienta: quiero realizar este trabajo íntegramente con **Codex**, no con Claude Code, porque estoy cerca del límite semanal de Claude.

Usá GPT-6 con reasoning effort High.

## Fase A — Limpieza local de U11-B

1. Verificá que el PR #39 esté integrado en main mediante `dcb45a285001e20892a8e9753c518d220088c011`.
2. Confirmá que los siete controles posteriores terminaron correctamente.
3. Comprobá que el worktree de U11-B está limpio.
4. Retirá exclusivamente ese worktree sin forzar y eliminá su rama local mediante `git branch -d`.
5. Conservá la rama remota y todos los respaldos.

No modifiques el checkout original ni otros worktrees.

## Fase B — Implementar FE-05/U11-C

Crear una rama y un worktree independientes:

- Rama: `feat/fe05-u11c-gestion-vinculos`
- Worktree: `~/banfield-patin-worktrees/fe05-u11c-gestion-vinculos`
- Base: `dcb45a285001e20892a8e9753c518d220088c011`

### Objetivo

Implementar las acciones administrativas de revocar vínculos familiares y cambiar la familia principal desde el frontend.

**Revocación:**
- Confirmación explícita.
- Explicar que retira el acceso familiar al deportista.
- Conservar el historial.
- No promover automáticamente otra familia principal.

**Cambio de principal:**
- Permitir seleccionar únicamente vínculos activos y familias habilitadas.
- Explicar que no se revocan otras familias ni se transfiere automáticamente al deportista.

Consumir los endpoints existentes con `{versionEsperada}`.

### Concurrencia y privacidad

- Capturar `versionVinculos` al abrir la confirmación.
- No reemplazar silenciosamente la revisión capturada.
- Tratar `409 EDICION_DESACTUALIZADA` mediante consulta de datos vigentes y nueva confirmación.
- Distinguir `CONFLICTO_CONCURRENCIA`, `VINCULO_NO_ACTIVO` y `FAMILIA_INACTIVA`.
- Ante resultados inciertos, no reintentar automáticamente ni afirmar que la operación falló.
- Evitar dobles envíos.
- Mostrar éxito únicamente cuando el backend lo confirme.
- Proteger el aislamiento entre usuarios, deportistas y respuestas tardías.

### Verificación

Aplicar gentle-ai/ODD y Engram; no SDD.

Trabajar con RED → GREEN.

Ejecutar typecheck, lint, Vitest, build y E2E reales pertinentes.

Evaluar RDD tempranamente y realizar una verificación independiente. Si requiere consentimiento específico, solicitámelo.

### Restricciones

- Autorizo implementación y pruebas locales.
- No autorizo commits, push, PR, merge ni despliegues.
- No modificar backend, migraciones, dependencias ni workflows.
- No comenzar U11-D, U12, U13 ni U14.
- No ejecutar Claude Code ni otros agentes externos.
- No ampliar el alcance con mantenimientos no relacionados.

### Entregable

Informame:

1. Resultado de la limpieza de U11-B.
2. Funcionalidades implementadas.
3. Pruebas y E2E.
4. RDD y verificación independiente.
5. Archivos modificados.
6. Riesgos residuales.
7. Estado Git.
8. Propuesta de commits.

Detenete antes de crear commits.

### L2 — Evidencia y planificación
- Fase A: GitHub PR #39 MERGED en dcb45a2; compare merge...main identical; ancestro 8debd68 → dcb45a2 verificado. Jobs de runs 44385703363 y 44385703433: 7/7 completed success.
- main local atrasado, no se alteró: la referencia vigente es origin/main = dcb45a2 y GitHub.
- Respaldo adicional de todos los artefactos ignorados de U11-B: /tmp/fe05-u11b-preservado-01a1f064/artefactos-ignorados.tar.gz. Remotos/refs backup y status original comparados sin cambios.
- Worktree nuevo creado en la base exacta solicitada. Exclusión expresa de commits prevalece sobre commits ODD automáticos.
- Previsión orientativa: 800–1200 líneas con pruebas, entrega local; no se planifica ni publica PR. Propuesta de unidades al cierre, sin ejecutar commits.
- RDD on (default). Evaluación temprana sin cambios: unassessable/no pending changes; no se interpreta como riesgo bajo. Repetir con candidato normalizado; solicitar consentimiento nativo si corresponde.
- Familia habilitada no viene en GET vínculos: consultar estado por GET familiar existente, sin mostrar datos personales adicionales. No confundir estado de deportista con estado familiar.
- Explorador Codex nativo gpt-6-astra/high; no se ejecutó Claude Code ni agentes externos.

### L3 — Implementación y evidencia local
- RED funcional: 17/17 pruebas de acciones fallaron por ausencia de controles. El primer intento detectó únicamente falta de showModal en jsdom; corregido el harness antes de aceptar RED.
- GREEN: 239/239 pruebas enfocadas de vínculos (8 archivos); typecheck y lint correctos.
- RED adicional observado al cerrar un resultado incierto: la prueba exigió consulta vigente antes de permitir otra escritura. Corregido con bloqueo persistente hasta consulta explícita.
- Ambas operaciones guardan la revisión al abrir la confirmación; el GET de fondo no la sustituye. Los errores invalidan la posibilidad de confirmar hasta consultar y abrir una nueva confirmación.
- Respuestas POST malformadas/mal dirigidas se tratan como inciertas; no existe reintento automático. El GET posterior no publica éxito. Invalidación de vínculos, familias afectadas, detalles y contadores después de POST confirmado.
- Elegibilidad familiar mediante consulta existente que solo cachea booleano, con gcTime 0. El fake ahora separa la revisión de vínculos de las ediciones personales.
- Diálogo nativo con nombre/descripción, foco inicial en cancelar, Escape cuando no se envía y devolución al disparador o encabezado.
- E2E nuevo registrado en shard 2, con backend real. Solo la entrega perdida se simula después de confirmar el commit real.
- Dos invocaciones npm desde la raíz fallaron con ENOENT; repetidas correctamente desde frontend. No constituyen prueba funcional.
- Sin commits, cambios de backend, migraciones, dependencias o workflows.

### L4 — Verificación observada y corrección de foco
- Primera suite completa paralela: 1277 pass / 10 fail en dos archivos existentes, iniciados por timeouts de 5 s y avisos act. Los mismos dos archivos pasan 15/15 tanto en base como en candidato; no se atribuye causa ni se declara que sean fallos preexistentes.
- Suite completa secuencial final, tras corrección: `npm test -- --maxWorkers=1`, 1288/1288, 52/52 archivos, exit 0. Typecheck, lint y build correctos.
- Primer shard 2 real: 86 pass, 1 fail de foco en el diálogo nuevo, 6 casos nuevos sin ejecutar. Se añadió regresión RED para Tab/Shift+Tab y se corrigió el ciclo explícito de foco. Suite enfocada final: 240/240.
- Nuevo E2E focalizado real: 7/7, exit 0. Runner /tmp/u11c-e2e-focused.sh es copia del runner existente; solo ROOT y selector final difieren. No se modificaron scripts/workflows del repositorio. No se volvió a ejecutar el shard completo tras la corrección.
- PostgreSQL/backend/frontend propios descartados; sin listeners en 55432/8080/5173 ni estado de enrolamiento al finalizar.
- Verificador independiente Codex gpt-6-astra/high: sin bloqueantes reproducidos, 240/240 suite enfocada en scratch y 4/4 probes (éxito/error tardío tras cambiar deportista, error tardío tras cambiar identidad, revisión decimal 9007199254740993 sin pérdida).
- Diferencial de GET existentes base/candidato: respuestas y requests idénticos (cmp exit 0); estados familiares/deportista sin cambios tras comandos rechazados.
- Mutantes relevantes exclusivamente en scratch: 3/3 detectados (revisión tomada del snapshot actual en vez de la confirmación, eliminación del guard sincrónico de envíos, éxito falso ante incertidumbre). Bytes originales restaurados; ningún mutante en el worktree.
- Spot check del padre: los 4 probes independientes volvieron a pasar (exit 0).
- Avisos de build: dos anotaciones Zod descartadas y bundle principal 723.36 kB (>500 kB). Sin ampliar alcance.
- Evidencia: /tmp/u11c-checks-kRVESn/, /tmp/u11c-checks-corrected-0J3aNO/ y /tmp/u11c-independent-evidence.tar.gz. Respaldo U11-B intacto en /tmp/fe05-u11b-preservado-01a1f064/.
- Evaluación RDD: on/default; riesgo alto por acceso y concurrencia. Requiere declarar los archivos nuevos explícitamente antes de congelar candidato. No hay aprobación ni recibo inventado.
- Propuesta de commit, NO ejecutada: `feat(admin-vinculos): gestionar revocación y familia principal`, junto a pruebas, documentación y ODD. Es una unidad coherente; su tamaño no se oculta ni se divide artificialmente.

### L5 — Consentimiento RDD y cierre local
- Respuesta literal del usuario: `granted`.
- Cuatro capturas nativas Codex concurrentes: riesgo, resiliencia, legibilidad y fiabilidad. Hallazgo bloqueante R4-001: el guard local de envíos se reiniciaba al abandonar y volver a la ruta mientras el primer POST todavía no llegaba al backend.
- Reproducción RED en ambas acciones: dos intentos POST observados. La prueba demora la invocación del adapter antes de que el fake procese el comando; `hold()` solo demoraba la respuesta después del cambio y no reproducía este caso.
- Única corrección acotada: estado pendiente compartido de TanStack Query mediante mutationKey por identidad completa/deportista y useIsMutating. Se conserva el guard sincrónico. Cambios únicamente en LinkActions.tsx, hooks.ts y link-actions.test.tsx; delta de 99 líneas, previsión aceptada de 130 y presupuesto nativo de 200.
- GREEN final: 242/242 pruebas del módulo (26 casos de acciones); typecheck/lint correctos; `npm test -- --maxWorkers=1` 1290/1290, 52 archivos; build correcto; 7/7 E2E reales de gestión. Evidencia final: /tmp/u11c-checks-rdd-TOAizQ/.
- El padre repitió los dos casos de desmontaje/remontaje: 2/2 correctos (los otros 24 se excluyeron expresamente por el filtro; la suite completa sí ejecutó todos).
- Validador nativo limitado a R4-001: corrección aceptada. Acuse exacto ejecutado una vez; sobre `gentle-ai.review-acknowledged/v1` confirmó `authority: burned`.
- Linaje: review-60ca110a0bf80791. Candidato aprobado: sha256:0a6b490c2f6af9bf56e1c6752b6f197f5a8c307817a6129b507eedf6589ea217. Árbol revisado: 93b182ad8b2e54da3f281d1182364343f80c0755.
- Antes del cierre, los 11 paths coincidían byte a byte con el árbol aprobado. Tras consumir el acuse se actualizó únicamente este registro pasivo de estado/evidencia; ningún archivo de implementación o pruebas cambió después de la validación.
- El primer shard completo sigue registrado como 86 pass / 1 fallo de foco corregido / 6 omitidos; los siete nuevos se verificaron después. No se declara una nueva ejecución completa del shard.
- Avisos finales de build: bundle principal 724.22 kB y anotaciones Zod/Rollup. La causa de los timeouts de la ejecución paralela inicial no quedó demostrada; ejecución secuencial completa correcta, sin avisos Vitest.
- Sin commits, staging, push, PR, merge ni despliegue. Backend, migraciones, dependencias y workflows sin cambios. Checkout original, otros worktrees, remotos y respaldos conservados.

#### Observaciones RDD no bloqueantes — no se amplía el alcance
- R1-001 (hooks.ts): se consulta detalle familiar para la familia ya principal aunque ese control no necesita elegibilidad. Solo se cachea el booleano y no se muestran nuevos datos, pero la lectura adicional existe.
- R2-001 (LinkActions.tsx): al cerrar un resultado incierto, el disparador puede seguir conectado pero deshabilitado; la devolución de foco no utiliza entonces el encabezado alternativo.
- R2-002 (AdminVinculosDeportistaPage.tsx): tras fallar una consulta y cerrar el diálogo pueden quedar dos botones con el mismo texto de consulta; solo el de gestión libera el bloqueo de verificación.
- R3-001 (LinkActions.tsx): la elegibilidad de una nueva confirmación se lee imperativamente del caché; un refresco familiar aislado puede dejar ese diálogo atrasado. El backend rechaza la familia inactiva y protege el estado persistido.
- R3-002 (fakeBackend.ts, preexistente): para deportistas sin vínculos inicializados el fake aún aproxima la revisión con la versión personal. No afecta a la persistencia real.
- Los cinco avisos son informativos: no reabren RDD, no autorizan trabajo adicional y no se corrigieron en esta unidad.

### L6 — Solicitud de cierre local (2026-11-19), literal
Continuamos `banfield-patin` con Codex.

Quiero cerrar **FE-05/U11-C: revocación de vínculos y cambio de familia principal**.

La unidad ya fue implementada y revisada por Codex en una sesión anterior. No rehagas el trabajo ni amplíes el alcance.

Usá gentle-ai/ODD y Engram; no SDD.

### Estado inicial

- Rama: `feat/fe05-u11c-gestion-vinculos`.
- Base: `dcb45a285001e20892a8e9753c518d220088c011`.
- Once archivos afectados, sin commits propios.
- Typecheck, lint y build correctos.
- Vitest: 1.290/1.290.
- Módulo de vínculos: 242/242.
- E2E nuevos: 7/7.
- Mutaciones dirigidas: 3/3 detectadas.
- Verificación independiente sin bloqueantes.
- RDD de riesgo alto aprobado y acusado para el candidato revisado.

La última sesión corrigió un defecto de foco detectado por Playwright, pero **no volvió a ejecutar el shard E2E completo** después de esa corrección.

### 1. Verificar el estado

Comprobá la rama, el HEAD, el worktree y el diff.

Preservá el checkout original `~/banfield-patin` y todos los demás worktrees.

Confirmá que el candidato corresponde a la implementación ya revisada.

### 2. Completar las pruebas pendientes

Identificá en `frontend/e2e/shards.json` el shard que contiene `vinculos-gestion.real.spec.ts`.

Ejecutá el shard completo con backend y PostgreSQL descartables.

Verificá que se ejecuten también los escenarios preexistentes del shard, no solamente los siete nuevos.

Ejecutá las verificaciones finales:

- Typecheck.
- Lint.
- Vitest completo.
- Build.
- Validación del manifiesto de shards.

Si alguna prueba falla, diagnosticá y corregí exclusivamente defectos demostrados de U11-C. Volvé a ejecutar las pruebas afectadas.

No modificar backend, migraciones, dependencias ni workflows.

### 3. RDD y revisión

Revisá la evidencia RDD y la verificación independiente existentes.

No repitas revisiones o mutaciones completas que ya están aprobadas, salvo que un cambio posterior lo requiera.

Si la creación del commit exige un nuevo consentimiento o una revisión nativa adicional, solicitámelo antes de continuar.

No consideres las observaciones no bloqueantes como tareas nuevas.

### 4. Crear el commit local

Si toda la verificación final es satisfactoria y RDD lo permite, autorizo crear **un único commit local** con los cambios propios de U11-C:

`feat(admin-vinculos): gestionar revocación y familia principal`

Antes de crearlo:

- Revisá el staged diff.
- Confirmá que solo incluya los once archivos previstos.
- Excluí temporales y secretos.
- Confirmá que no se modifica código ajeno.

Commit en español y sin `Co-Authored-By`.

No utilizar amend, squash ni rebase.

### 5. Restricciones

No autorizo todavía:

- Push.
- Crear PR.
- Merge.
- Force-push.
- Despliegues.
- Limpieza de ramas o respaldos.
- Comenzar U11-D.

No iniciar Claude Code ni otros agentes externos.

### 6. Entregable

Informame:

1. Resultado del shard E2E completo.
2. Resultado de las verificaciones finales.
3. Estado RDD.
4. SHA del commit local.
5. Archivos y líneas modificadas.
6. Estado Git.
7. Riesgos residuales.
8. Si U11-C está lista para publicar.

Detenete antes del push.

### L7 — Estado reconciliado antes de las comprobaciones
- Rama y HEAD exactos; once paths previstos, siete modificados y cuatro nuevos, nada staged. Diez paths de implementación/pruebas coinciden byte a byte con el árbol RDD 93b182ad8b2e54da3f281d1182364343f80c0755; solo este registro pasivo difiere.
- Acuse local gentle-ai.review-acknowledged/v1 verificado: authority burned, linaje review-60ca110a0bf80791. Verificación independiente y evidencia de mutaciones existentes leídas, sin repetirlas.
- Precisión del nivel RDD: assessment high; el START nativo congeló medium con cuatro perspectivas. Ambas evidencias se conservan, sin atribuir a START un nivel distinto.
- Shard 2 contiene ocho archivos, incluido vinculos-gestion.real.spec.ts. Se usará el runner oficial completo sin selector focalizado; previsión de duración basada en corrida anterior: unos 16 minutos.
- No existen hooks activos locales de commit. Las autorizaciones nativas de revisión no autorizan publicación; el usuario autoriza ahora exactamente un commit local.
- Única edición previa a las comprobaciones: actualización pasiva de autorización/estado de este documento, sin rehacer código ni ampliar observaciones no bloqueantes.

### L8 — Verificación completa de cierre (2026-11-19)
- Runner oficial, sin filtros: `E2E_SHARD=2 bash .github/scripts/frontend-e2e.sh`, exit 0, 93/93 escenarios en 16,5 minutos; 86 preexistentes y 7 nuevos, sin fallos, omitidos, casos sin ejecutar ni reintentos.
- Conteos por archivo: deportistas-consulta-alta 16; deportistas-datos-deportivos-alta 14; deportistas-datos-deportivos 11; familias-detalle-alta 10; familias-listado 8; familias-tutores 11; vinculos-consulta 16; vinculos-gestion 7.
- `node e2e/support/shards.ts check`: exit 0, 194 escenarios totales y 93 en shard 2. `patterns 2`: los ocho specs previstos.
- Typecheck, lint, build: exit 0. `npm test -- --maxWorkers=1`: 1290/1290, 52 archivos, exit 0. No se modificaron timeouts ni configuración para lograr el resultado.
- Evidencia íntegra: /tmp/u11c-final-6XnjRq/. Runner oficial y Git status sin cambios durante las pruebas; los diez paths de implementación/pruebas siguen coincidiendo con el árbol aprobado.
- Infraestructura descartable propia eliminada por el runner; sin estado de enrolamiento ni listeners en 55432/8080/5173. No se tocaron servidores ajenos, worktrees, ramas o respaldos.
- Avisos no bloqueantes: dos anotaciones Zod/Rollup, bundle principal 724.22 kB y conflicto de variables de color; el spec familias-tutores tardó unos cinco minutos. Se conservan sin ampliar alcance.
- RDD existente verificado: cuatro perspectivas, corrección R4-001 aceptada, acuse consumido. No se repiten revisión independiente ni mutaciones; los cinco avisos informativos de L5 siguen fuera de alcance.
- Se actualiza solamente este registro pasivo. Autorización de entrega: un commit local con el mensaje exacto solicitado; no push, PR, merge ni despliegue. La confirmación y SHA se agregan al cierre de Engram después de que Git termine, preservando un solo commit sin autorreferencia ni amend.
