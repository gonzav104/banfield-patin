# FE-05/U11-D — Vinculación y reactivación de deportistas
Rama: `feat/fe05-u11d-vinculacion-deportistas`; base: `5ad2776f014d6e71919a79a88506dc8db3438827`.
Entrega local, sin commits ni publicaciones; propuesta al cierre. Runners: Vitest y Playwright con backend/PostgreSQL descartables.

## Specs
- S1: “Confirmar que el commit `04583fecd50d38f366325408bde137e3aaec187b` es ancestro de main, que los siete controles posteriores están verdes y que el worktree está limpio.” Retirar únicamente U11-C sin forzar y rama con `git branch -d`; conservar remotos, respaldos y los demás worktrees.
- S2: “desde el detalle administrativo de una familia, seleccionar deportistas existentes para vincularlos o reactivar vínculos previamente revocados.” Varias familias por deportista, máximo una relación activa principal; no existe tutor principal.
- S3: “`POST /api/admin/familias/{familiaId}/deportistas`”, cuerpo “`{deportistas: [{deportistaId, versionEsperada}]}`”; cada revisión desde “`GET /api/admin/deportistas/{deportistaId}/vinculos`”.
- S4: “Respetar la atomicidad del lote, el límite de 50, la validación de revisiones y los resultados CREADO, REACTIVADO y SIN_CAMBIOS.” Validar estrictamente la correspondencia completa por deportista antes de éxito.
- S5: “Ofrecer búsqueda y selección paginada de deportistas ya registrados, reutilizando el listado ADMIN.” “Mostrar únicamente los datos necesarios para identificarlos; no cargar fichas con DNI/CUIL completos.”
- S6: “Diferenciar vínculo inexistente, activo y revocado.” “No permitir vincular deportistas inactivos ni familias inactivas.”
- S7: “Confirmar explícitamente antes de crear o reactivar vínculos, explicando que la familia obtendrá acceso a información del deportista.” “No sugerir que vincular implica transferirlo de otra familia.” “No prometer que el nuevo vínculo será principal: lo decide el backend.”
- S8: “Capturar las revisiones observadas al preparar la confirmación.” “No reemplazarlas silenciosamente por versiones más recientes.” “Ante `EDICION_DESACTUALIZADA`, conservar la selección, consultar datos vigentes y exigir nueva confirmación.”
- S9: “Ante conflictos o errores de estado, mostrar mensajes adecuados.” “Ante red, timeout o 5xx, no reintentar automáticamente ni afirmar éxito o fracaso.” “Bloquear doble envío, incluso si se desmonta y vuelve a montar la pantalla.”
- S10: “Invalidar las consultas afectadas sin mezclar identidades.” Aislar preparación, envío, navegación y respuestas tardías por identidad completa/familia.
- S11: “Aplicar RED → GREEN.” Cobertura de creación, reactivación, existente, múltiples, atomicidad, conflictos, incertidumbre, navegación, privacidad e identidad. Typecheck, lint, Vitest, build y E2E reales; RDD temprano y verificación independiente proporcional.
- S12: “Autorizo solo implementación y pruebas locales.” “No commits, push, PR, merge ni despliegues.” “No modificar backend, migraciones, dependencias ni workflows.” “No comenzar U12, U13 ni U14.” No resolver avisos anteriores.

## Tasks
- T1 [S1] inline, completada: condiciones verificadas, archivo adicional de artefactos ignorados, retiro sin forzar y rama -d; remotos/respaldos/status original sin cambios. Sin commit.
- T2 [S3-S4,S8,S10] inline, completada: preparación y contrato de lote, validación estricta de respuesta y guard opt-in de identidad tras CSRF, con RED/GREEN. Risk: items 2, 3 y 4 (acceso, helper HTTP compartido y concurrencia).
- T3 [S2,S5-S10] inline, completada: selector inline en familia, estados/confirmación/recuperación, fake atómico y pruebas. Una unidad dependiente, sin motivo para escritores paralelos.
- T4 [S11-S12] inline para E2E/docs; comprobaciones largas y verificador independiente delegados por riesgo/duración, completados; RDD pendiente. RDD nativo según transición y consentimiento. Sin commit.

## Log
### L1 — Solicitud literal
Continuamos `banfield-patin` con **FE-05/U11-D: vinculación y reactivación de deportistas desde el panel ADMIN**.

Usar GPT-6 con reasoning effort High, gentle-ai/ODD y Engram. No SDD.

### Fase A — Cierre local de U11-C

Confirmar que el commit `04583fecd50d38f366325408bde137e3aaec187b` es ancestro de main, que los siete controles posteriores están verdes y que el worktree está limpio.

Si se cumplen esas condiciones, autorizo retirar únicamente ese worktree y borrar su rama local con `git branch -d`.

Conservar las ramas remotas y los respaldos. No tocar el checkout original ni otros worktrees.

### Fase B — Implementación U11-D

Base exacta:

`5ad2776f014d6e71919a79a88506dc8db3438827`

Crear:

- Rama: `feat/fe05-u11d-vinculacion-deportistas`
- Worktree: `~/banfield-patin-worktrees/fe05-u11d-vinculacion-deportistas`

**Objetivo:** desde el detalle administrativo de una familia, seleccionar deportistas existentes para vincularlos o reactivar vínculos previamente revocados.

Mantener el modelo actual: un deportista puede tener varias familias vinculadas, pero como máximo una relación activa principal. No crear un concepto de tutor principal.

### Contrato backend

Utilizar el endpoint existente:

`POST /api/admin/familias/{familiaId}/deportistas`

Cuerpo:

`{deportistas: [{deportistaId, versionEsperada}]}`

La revisión corresponde a cada deportista y debe obtenerse de:

`GET /api/admin/deportistas/{deportistaId}/vinculos`

Respetar la atomicidad del lote, el límite de 50, la validación de revisiones y los resultados CREADO, REACTIVADO y SIN_CAMBIOS.

No modificar el backend.

### Interfaz

- Integrar el acceso desde el detalle de familia.
- Ofrecer búsqueda y selección paginada de deportistas ya registrados, reutilizando el listado ADMIN.
- Mostrar únicamente los datos necesarios para identificarlos; no cargar fichas con DNI/CUIL completos.
- Diferenciar vínculo inexistente, activo y revocado.
- No permitir vincular deportistas inactivos ni familias inactivas.
- Confirmar explícitamente antes de crear o reactivar vínculos, explicando que la familia obtendrá acceso a información del deportista.
- No sugerir que vincular implica transferirlo de otra familia.
- No prometer que el nuevo vínculo será principal: lo decide el backend.

### Concurrencia y resultados inciertos

- Capturar las revisiones observadas al preparar la confirmación.
- No reemplazarlas silenciosamente por versiones más recientes.
- Ante `EDICION_DESACTUALIZADA`, conservar la selección, consultar datos vigentes y exigir nueva confirmación.
- Ante conflictos o errores de estado, mostrar mensajes adecuados.
- Ante red, timeout o 5xx, no reintentar automáticamente ni afirmar éxito o fracaso.
- Bloquear doble envío, incluso si se desmonta y vuelve a montar la pantalla.
- Validar estrictamente el resultado por deportista antes de mostrar confirmación de éxito.
- Invalidar las consultas afectadas sin mezclar identidades.

### Pruebas y revisión

Aplicar RED → GREEN.

Cubrir creación, reactivación, vínculo existente, múltiples seleccionados, lote atómico, conflictos, resultado incierto, navegación, privacidad y cambio de identidad.

Ejecutar typecheck, lint, Vitest, build y E2E reales pertinentes con PostgreSQL descartable.

Evaluar RDD tempranamente y realizar verificación independiente proporcional al riesgo. Solicitar consentimiento específico si el protocolo lo requiere.

### Restricciones

Autorizo solo implementación y pruebas locales.

No commits, push, PR, merge ni despliegues.

No modificar backend, migraciones, dependencias ni workflows.

No comenzar U12, U13 ni U14.

No abrir mantenimientos ni resolver observaciones no bloqueantes de unidades anteriores.

### Entregable

Informar funcionalidades, contratos, pruebas, E2E, mutaciones relevantes, RDD, revisión independiente, archivos modificados, riesgos residuales y propuesta de commits.

Detenerse antes de commitear.

### L2 — Cierre U11-C y preparación
- GitHub main = 5ad2776; compare 04583...main: ahead 1 / behind 0 y merge_base 04583. Siete jobs de runs 44567093045 y 44567093347 completed/success en ese SHA; worktree U11-C limpio.
- Se preservaron todos sus artefactos ignorados en /tmp/fe05-u11c-preservado.lJ1zEX/artefactos-ignorados.tar.gz; worktree retirado sin forzar y rama local con -d. Rama remota conservada.
- Commit base no estaba en el almacén local: fetch por SHA exacto, sin tags ni submódulos, sin cambiar ramas locales ni refs remotas. Ascendencia verificada también localmente después de fetch.
- Nuevo worktree/rama en base exacta solicitada. Status completo original (muy voluminoso y ajeno a U11-D) y refs backup/remotas comparados iguales; no se tocan esos archivos.
- Mapa delegado Codex gpt-6-astra/high, solo lectura; no se usaron Claude ni agentes externos. Contrato backend actual: respuesta envuelve resultados, índices de error se refieren al orden del lote y todas las validaciones preceden a las escrituras.

### L3 — Ruta y decisiones de implementación
- Selector inline dentro de la sección de deportistas de la ficha familiar; no se añaden rutas ni se refactoriza el detalle completo. Apertura explícita, búsqueda/lista paginada reutilizadas; selección estable entre páginas.
- Los vínculos activos pueden incluirse como SIN_CAMBIOS previsto, con etiqueta inequívoca. PENDIENTE/RECHAZADO quedan fuera de la selección autorizada, sin ampliar funcionalidad.
- Preparación obtiene cada revisión de GET vínculos y estado familiar vigente. No solicita GET individual de deportista; las fichas completas de DNI/CUIL no intervienen.
- Snapshot independiente del caché vivo; consultar y revisar nueva confirmación son pasos separados. Mensajes de error indexados se mapean contra el snapshot enviado, nunca contra selección mutable ni mensajes arbitrarios del backend.
- Mutación/pending compartidos por identidad completa + familia, reutilizando el patrón U11-C. Guard sincrónico y bloqueo tras remontaje; respuestas tardías no invalidan otra identidad.
- Extensión opt-in mínima de apiWrite: guard ejecutado después de obtener CSRF y antes del envío. Solo lo utiliza el nuevo flujo para impedir enviar intención vieja si cambia la identidad durante CSRF; llamadas existentes conservan su comportamiento. No se corrige ni amplía U11-C.
- RDD on/default. Evaluación temprana sin cambios: unassessable/no pending changes; nunca se interpreta como riesgo bajo. Evaluar candidato final normalizado y pedir consentimiento si lo exige.
- Previsión orientativa: 1100–1700 líneas con pruebas y docs; entrega local sin commits. La propuesta de commits se realiza al cierre, sin publicar ni decidir cadenas ahora.

### L4 — RED/GREEN del contrato
API RED observado: 25 fallos y 1 prueba de regresión HTTP verde por funciones aún inexistentes. GREEN: 47/47 entre contrato nuevo y HTTP existente. El fake valida el lote completo antes de escribir. Preparación sin fichas personales y guard opt-in tras CSRF; respuesta completa validada antes de éxito. Pruebas UI agregadas antes de implementar el selector.

### L5 — RED/GREEN de interfaz y E2E
RED observado: 18/18 pruebas UI fallaban sin selector. Implementados selector y recuperación; corregido el uso del contrato paginado español existente (`contenido`, `totalElementos`) y foco de disparador sin cambiar Button compartido. GREEN focalizado: 43/43 (18 nuevas +25 sección familiar). Typecheck correcto. Manifiesto válido: 201 E2E, shard1 101 y shard2 100, incluyendo 7 nuevos. Pendientes suites completas, E2E real, mutaciones y revisión independiente/RDD.

### L6 — Verificación final observada
- Typecheck, lint, build y manifiesto correctos; Vitest completo 1337/1337 en 54 archivos. Shard E2E 2 completo: 100/100, nueve specs, incluye los siete nuevos; sin omisiones/reintentos. PostgreSQL 17 descartable; runner y puertos liberados. Evidencia `/tmp/u11d-final-V5YSZ1`.
- Tres mutaciones dirigidas detectadas en copias temporales: quitar guard posterior a CSRF, aceptar respuesta incompleta y quitar latch sincrónico de envío. Sin alteración del candidato real.
- Verificación independiente Codex GPT-6/high: PASS por S1-S12, sin bloqueantes nuevos. 68/68 pruebas focalizadas; tres probes independientes verdes preservan hash tras rechazo y principal ajena. Diferencial HTTP candidato/base idéntico en POST/PUT/PATCH/DELETE, errores503, CSRF y estado familiar persistido. Evidencia `/tmp/u11d-independent-aldijM`. Primer arranque scratch necesitó caché Vite local por EROFS; no fue defecto del candidato. El intento anterior quedó sin resultado por límite de uso y se reanudó tras «segui».
- Spot check del padre: tres probes independientes 3/3. `git diff --check` limpio. Sin commits propios, backend/migraciones/dependencias/workflows sin cambios. No se ampliaron avisos anteriores. RDD sigue on/default; evaluación final requiere selección explícita de los cinco archivos nuevos antes de congelar candidato y solicitar consentimiento nativo.
- Avisos no bloqueantes observados de herramientas: anotaciones Rollup/Zod, chunk principal736.49kB, variables de color y spec tutores lento. No se corrigen en esta unidad.

Risk: items 2, 3 y 4 (acceso familiar, guard HTTP opt-in compartido y concurrencia/identidad). Verificación funcional e independiente realizadas; aprobación RDD aún pendiente. Ningún commit autorizado.

### L7 — Corrección nativa R4
RDD consentido (`granted`), cuatro lentes sobre el candidato congelado. Hallazgo único corroborado `R4-stale-after-lost-response`: la consulta de recuperación no invalidaba las lecturas tras una respuesta perdida. RED observado: un fallo reproducible al faltar el deportista en la sección familiar después de consultar/cancelar; aislamiento de otra identidad verde. Corrección acotada: reutilizar invalidaciones bajo identidad capturada también en recuperación, incluyendo familias observadas antes/después; no confirma éxito ni reenvía. Pruebas UI y E2E ampliadas específicamente. Plan nativo hasta120 líneas, dentro del presupuesto200. Validación funcional y nativa de la corrección pendientes; no commits.

### L8 — Congelación de evidencia corregida
- GREEN R4: 2/2 pruebas focalizadas. Verificación final posterior: typecheck/lint/build/manifiesto correctos; Vitest1339/1339; shard2 real completo100/100, incluidos los siete nuevos y el escenario consulta/cancelación tras commit con respuesta perdida. Sin omisiones ni reintentos. Evidencia `/tmp/u11d-r4-final-8wzdIk`.
- El código y los documentos quedan congelados para la validación nativa limitada a R4; no se repiten lentes, mutaciones ni barridos independientes. Al congelar, la validación/acuso nativos aún están pendientes; su cierre terminal se conserva fuera del candidato en Engram y en la evidencia nativa. Sin commits ni publicación.
