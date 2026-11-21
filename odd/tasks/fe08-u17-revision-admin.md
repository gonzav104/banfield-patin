# FE-07 / U17 — Revisión administrativa de documentos
Rama: `feat/fe07-u17-revision-documental` · Base: `origin/main` verificada en GitHub, `96a689b`.
Entrega local en tres commits revisados incrementalmente sobre una única rama de integración, sin PR por corte. Pronóstico: 800–1200 líneas; sin publicación. Pruebas: Maven, PostgreSQL/Testcontainers, Vitest, Playwright.

## Specs
- S1: «Implementar exclusivamente la revisión administrativa del DNI frente y dorso del deportista.» Transiciones: `CARGADO → APROBADO`, `CARGADO → OBSERVADO`, `APROBADO → OBSERVADO`. «Las observaciones requieren motivo obligatorio.» No vigentes, otros tipos, U18 y pendiente U15 quedan fuera.
- S2: «Crear un worktree nuevo desde la referencia remota actualizada.» Preservar checkout principal; la base debe incluir U16.
- S3: `POST /api/admin/deportistas/{deportistaId}/documentos/{documentoId}/revision`. «Utilizar `versionEsperada` como cadena decimal, conservando la precisión de `bigint`.» ADMIN/MFA/CSRF, aislamiento escuela/deportista/tipo, bloqueo acotado + CAS, incremento único, errores 400/401/403/404/409, auditoría obligatoria atómica y sin motivo ni datos sensibles. Storage, familia y pipeline no cambian.
- S4: «Ante un 409, actualizar los metadatos y exigir una nueva decisión administrativa.» Acciones permitidas en ficha existente, motivo, confirmación de revocación, no duplicados, invalidar resultados obsoletos por contexto, sin reemplazo.
- S5: «Organizar los cambios en commits locales revisables, sin integrar unidades incompletas en main.» Backend completo, PostgreSQL, UI/typecheck/lint/build, E2E reales, regresiones U15/U16, dos administradores, reversión de auditoría y permisos/aislamiento. Revisiones independientes y nativas según reglas. No push, PR, merge, despliegue ni habilitación de carga.

- S6: «Creá una única rama `feat/fe07-u17-revision-documental` para contener los tres cortes.» «No quiero cortes apilados hacia main ni PR independientes para cada parte de U17.» «Si requieren consentimiento, solicitámelo antes de ejecutarlas.»

## Tasks
- [x] T1 · S2/S3/S5/S6 · inline · versión administrativa y pruebas · d223192; revisión nativa aprobada y reconocida.
- [ ] T2 · S1/S3/S5 · inline, verificador independiente · endpoint transaccional con pruebas HTTP/PostgreSQL · riesgo 2 y 4 · commit pendiente.
- [ ] T3 · S4/S5 · inline, verificación sensible al cierre · UI y E2E/documentación · depende de T1/T2 · commit pendiente.

Los cortes son secuenciales: la escritura y la UI dependen de la versión administrativa; no hay escritor delegado por paralelismo.

## Log
### L1 — Autorización de implementación

Apruebo el plan ejecutable de **U17 — Revisión administrativa de documentos** y autorizo comenzar su implementación local.

El relevamiento técnico está completo y no considero necesario reabrir las decisiones ya aprobadas.

**Modelo: `gpt-6.2-sol`**
**Reasoning effort: `high`**

### 1. Alcance autorizado

Implementar exclusivamente la revisión administrativa del DNI frente y dorso del deportista.

Transiciones permitidas:

- `CARGADO → APROBADO`
- `CARGADO → OBSERVADO`
- `APROBADO → OBSERVADO`

Las observaciones requieren motivo obligatorio.

No permitir `OBSERVADO → APROBADO`, revisión de documentos no vigentes ni documentos de tipos distintos de los autorizados.

Mantener fuera del alcance U18, documentación de tutores, WADA, aptos físicos y la corrección pendiente del 409 de U15.

### 2. Entorno de trabajo

- Verificar que `origin/main` contenga la integración U16 (`96a689b`).
- Crear un worktree nuevo desde la referencia remota actualizada.
- No modificar, limpiar ni sobrescribir los cambios existentes en el checkout principal.
- No desarrollar sobre la rama `main` local atrasada.
- Si la base remota presenta cambios incompatibles respecto del plan, informar antes de continuar.

### 3. Backend

Implementar el contrato aprobado:

`POST /api/admin/deportistas/{deportistaId}/documentos/{documentoId}/revision`

Utilizar `versionEsperada` como cadena decimal, conservando la precisión de `bigint`.

Garantizar:

- Autenticación ADMIN y MFA completo.
- CSRF de escritura.
- Aislamiento por escuela, deportista y tipo documental.
- Bloqueo acotado y actualización condicional mediante CAS.
- Incremento único de versión por transición exitosa.
- Respuestas 400, 401, 403, 404 y 409 según el contrato.
- Auditoría obligatoria dentro de la misma transacción.
- Ausencia de datos personales sensibles y motivos libres en auditoría y logs.

No modificar Storage, los contratos familiares ni el pipeline documental existente.

### 4. Frontend

Incorporar las acciones de aprobación y observación en la ficha administrativa existente.

- Mostrar únicamente acciones permitidas.
- Solicitar motivo al observar.
- Confirmar explícitamente la revocación de documentos aprobados.
- Evitar envíos duplicados.
- Ante un 409, actualizar los metadatos y exigir una nueva decisión administrativa.
- Evitar resultados obsoletos cuando cambien la sesión o el deportista.

No incorporar controles de reemplazo documental, que corresponden a U18.

### 5. Implementación incremental

Respetar los tres cortes del plan:

1. Exposición de versión administrativa y pruebas de contrato.
2. Servicio y endpoint transaccional, autorización, CAS, auditoría y pruebas PostgreSQL.
3. Interfaz administrativa, pruebas UI, E2E y documentación.

Organizar los cambios en commits locales revisables, sin integrar unidades incompletas en main.

### 6. Pruebas y revisiones

Ejecutar las verificaciones pertinentes:

- Backend completo.
- PostgreSQL real.
- Frontend: pruebas, typecheck, lint y build.
- E2E reales.
- Regresiones de U15 y U16.
- Pruebas de concurrencia con dos administradores.
- Reversión transaccional ante fallos de auditoría.
- Matriz de permisos y aislamiento entre escuelas.

Realizar verificación independiente de las partes sensibles y utilizar las revisiones nativas de Gentle AI conforme a las reglas del repositorio.

No omitir revisiones obligatorias ni declarar verificaciones que no se ejecutaron.

### 7. Límites de autorización

**Autorizo:**
- Implementación local.
- Pruebas.
- Revisiones.
- Commits locales.

**No autorizo todavía:**
- Push.
- Apertura de PR.
- Merge.
- Despliegue.
- Habilitación de carga documental en producción.

Si aparece una incompatibilidad que requiera ampliar el alcance o alterar decisiones aprobadas, presentá el problema y las alternativas antes de modificarlo.

### 8. Informe final

Al finalizar, entregame en español:

1. Resumen de lo implementado.
2. Commits realizados.
3. Resultados de las pruebas.
4. Hallazgos de las revisiones.
5. Desviaciones respecto del plan.
6. Riesgos pendientes.
7. Estado del worktree.
8. Recomendación fundamentada sobre si U17 está lista para publicar.

No hace falta volver a solicitar autorización para cada corte si permanece dentro del alcance aprobado. Continuá hasta completar la implementación local y las verificaciones correspondientes.

### L2 — Base
Fetch anónimo autorizado de GitHub por HTTPS: `origin/main` sigue en `96a689b`. Worktree nuevo creado sin tocar el checkout principal. RDD efectivo: habilitado por defecto.

### L3 — Corte 1: evidencia local
- RED observado: el GET de deportista ADMIN no devolvía la versión (el campo `version` no existía); WebMvc falló con una aserción y sin errores de entorno al usar el agente de Mockito explícito. El intento inicial de auto-adjuntar Mockito estaba bloqueado por el entorno de ejecución.
- GREEN: 13 pruebas focales MVC/servicio y 8 de PostgreSQL real; sin fallos ni omisiones. OpenAPI expone `version` como cadena, se probó `Long.MAX_VALUE`, y familia/tutor mantienen sus campos.
- Riesgo 3: aunque el cambio de contrato es aditivo, el servicio/proyección de consulta comparte caminos con las lecturas anteriores. Verificación independiente y decisión de estrategia antes del commit; no publicar.
- Verificación independiente T1: diferencial base 12/12, candidato 13/13, y sondas de compatibilidad adicionales sin diferencias no autorizadas. Sin bloqueantes; PostgreSQL lo ejecutó el padre. Spot check focal repetido: 13/13. Check de whitespace correcto; scratch de verificación eliminado.
- Pendiente antes del primer commit: selección de estrategia al superar la previsión de 400 líneas; los cambios siguen locales y sin preparar para publicación. No se inició una revisión nativa de candidato.


### L4 — Estrategia de entrega autorizada

Confirmo la estrategia de **una única rama de integración para U17**, con los tres cortes implementados y revisados de forma incremental.

No quiero cortes apilados hacia main ni PR independientes para cada parte de U17.

Condiciones:

1. Utilizá el worktree aislado existente, basado en `origin/main` (`96a689b`), sin modificar el checkout principal.
2. Creá una única rama `feat/fe07-u17-revision-documental` para contener los tres cortes.
3. Conservá los cambios ya implementados y verificados del corte 1.
4. Organizá cada corte en un commit local independiente, siguiendo las reglas del repositorio.
5. Ejecutá las evaluaciones de riesgo y revisiones nativas de Gentle AI que correspondan a cada commit. Si requieren consentimiento, solicitámelo antes de ejecutarlas.
6. No eludas el presupuesto de revisión ni declares aprobada una revisión que no se haya completado.
7. Continuá con los cortes 2 y 3 respetando el alcance y las decisiones previamente aprobadas.
8. Ejecutá las pruebas unitarias, de integración, PostgreSQL y E2E correspondientes, además de las regresiones de U15 y U16.
9. No incorpores cambios funcionales ajenos a U17.

**Autorizo la creación de la rama local, los commits y la continuación de la implementación.**

No autorizo push, apertura de PR, merge, despliegue ni habilitación de carga documental en producción.

Cuando completes los tres cortes y sus verificaciones, presentame el estado de U17, los resultados y los riesgos pendientes.

Respondé íntegramente en español.

La rama de preparación se renombró en el mismo worktree: no se creó una segunda rama y el candidato del corte 1 no cambió. Límite de revisión inicial: `96a689b`.

### L5 — Cierre del corte 1 e inicio del corte 2
- `d223192`: verificación independiente sin bloqueantes y Gentle AI aprobado/reconocido (`review-639478696357074e`); el acuse confirmó autoridad consumida. La advertencia no bloqueante R2-001 se atiende en el corte 2 comprobando la referencia de la respuesta GET en OpenAPI; no se reabrió el candidato.
- Corte 2: RED MVC observado (el POST ausente respondió 404 frente a 400 por observación sin motivo). PostgreSQL también reprodujo que `JdbcClient` entrega SQLSTATE 55P03 encapsulado en `UncategorizedSQLException`; se acotó la traducción al nuevo comando, sin tocar el manejador global.
- Verificación funcional del corte 2: 52 pruebas focales y 85 PostgreSQL real (5 revisión, 8 consulta, 58 U15 y 14 U16), todas verdes después de corregir el timeout. El diferencial independiente para listados y regresiones U15/U16 preservó cuerpos, estados y cabeceras.
- La suite independiente encontró dos regresiones de esquema (ejemplo OpenAPI prohibido y guard genérico de estado). Se eliminó el ejemplo y se acotó la excepción al esquema de revisión, con campos y destinos exactos. Recheck independiente y spot check: 5/5 en ambos. No se repitió la suite completa tras esta corrección; queda como comprobación final.
- El corte transaccional supera el objetivo orientativo de 400 líneas por las pruebas de aislamiento, concurrencia y reversión. Se mantiene un solo corte cohesivo, según la estrategia local autorizada: no se recortan pruebas ni se prescinde del presupuesto nativo.
