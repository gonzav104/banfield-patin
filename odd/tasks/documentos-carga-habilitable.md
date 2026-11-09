# Carga documental lista para habilitar
Objetivo: resolver los defectos y riesgos conocidos de la carga documental integrada en main (PR #14) para que pueda habilitarse con documentos reales. Rama `feat/carga-documental-habilitable` (base `main` b4a1d0b). Entrega: `ask-on-risk`, una unidad por commit. Runner: Maven/JUnit (`./mvnw -o`).

## Specs
- S1: «Garantizá que un fallo de limpieza no modifique una respuesta exitosa ni reemplace el error original.»
- S2: «No expongas rutas, nombres de archivos ni información sensible en los logs.»
- S3: «Conservá las protecciones de los archivos temporales. Documentá el riesgo residual de archivos huérfanos y su futura eliminación.»
- S4: «Implementá exclusivamente U1, incluyendo las pruebas de éxito y de error.»
- S5: «Ejecutá las pruebas correspondientes y respetá el flujo `review assess` del commit. No inicies una revisión nativa que requiera consentimiento sin solicitarlo.»
- S6: «Creá un commit en español, sin `Co-Authored-By`.»
- S7: «No avances automáticamente a U2 ni a otras unidades.»
- S8: «Mantené `DOCUMENTOS_CARGA_HABILITADA=false`. No modifiques migraciones ni utilices servicios o credenciales reales.»
- S10 (U2): «Autorizo exclusivamente **U2 — Observabilidad sin datos sensibles**, siguiendo el plan ODD registrado en `odd/tasks/documentos-carga-habilitable.md`.»
- S11 (U2): «Registrar solamente `operacionId`, fase, clase de excepción y código saneado cuando corresponda.»; «No registrar mensajes de excepciones, rutas, nombres, SHA ni identificadores personales.»
- S12 (U2): «Incorporar pruebas que verifiquen la presencia de los campos permitidos y la ausencia de información sensible.»; «Mantener el alcance estrictamente limitado a U2.»; «No avanzar a U3 sin autorización.»
- S9 (hoja de ruta): «Apruebo el plan general U1–U8 como hoja de ruta, pero autorizo únicamente la implementación de **U1 — Limpieza best-effort**.»

## Tasks
Solo U1 está autorizada; las demás son hoja de ruta aprobada, sin autorización de implementación.
- [x] U1 · S1–S8 · inline · Limpieza best-effort del temporal. Riesgo: ninguno (sin contrato, permisos ni concurrencia modificados). Commit: el de esta unidad (`test(documentos)`/`fix(documentos)` de limpieza best-effort).
- [x] U2 · S10–S12 · inline · Observabilidad sin datos sensibles: `DiagnosticoCarga`, WARN con operación, fase, clase de causa y código saneado. Risk: none (solo agrega logs). Commit: el de esta unidad (`feat(documentos)` de diagnóstico).
- [ ] U3 · Clasificación de timeout de la inspección (503 en vez de 422). Pendiente de autorización.
- [ ] U4 · El reintento retoma operaciones RESERVADA/ALMACENADA vencidas. Ítem de riesgo 4. Pendiente de autorización.
- [ ] U5 · Reconciliación con Storage (TRANSFIRIENDO/RECONCILIANDO) y guarda de configuración de la reserva. Ítems 1 y 4. Pendiente de autorización.
- [ ] U6 · Permiso de carga después del spool del cuerpo. Ítem 4. Pendiente de autorización.
- [ ] U7 · Verificación real de ClamAV y Supabase Storage (opt-in, fuera de CI). Requiere autorización de servicios y credenciales.
- [ ] U8 · Delimitar B1-C (lectura del historial de CI; sin código previsto).

## Log
### L1 — Solicitud original de la etapa (textual), 2026-11-07
El PR #14 de Banfield Patín Carrera fue integrado correctamente en main.

**Datos confirmados:**
- PR: https://github.com/gonzav104/banfield-patin/pull/14
- Commit de merge: b4a1d0b51491a43e15667ad07b5db99d94f169d0
- CI aprobada: 1087/1087 pruebas normales y 534/534 PostgreSQL 17.
- Todas las revisiones RDD exigidas quedaron reconocidas.
- La carga documental permanece deshabilitada por defecto.
- No se realizó despliegue.

## 1. Sincronizar el proyecto

Verificá el estado local y remoto de Git.

Actualizá main mediante fast-forward únicamente si puede hacerse sin perder modificaciones locales. Si el worktree tiene cambios o está ocupado, utilizá un worktree independiente.

No borres ramas, worktrees, evidencias RDD, entradas de Engram ni los cinco archivos protegidos.

## 2. Cerrar la etapa anterior

Considerá finalizada la integración de la carga documental.

No repitas auditorías, revisiones RDD ni pruebas sobre código idéntico sin una justificación concreta.

Conservá la evidencia existente.

## 3. Preparar la siguiente etapa: carga documental lista para habilitar

Necesitamos resolver los problemas conocidos, priorizando:

1. Idempotencia y reconciliación: impedir que una carga quede bloqueada indefinidamente en estados intermedios.
2. Consistencia de publicación: evitar que el cliente reciba un 503 cuando el documento ya fue publicado correctamente.
3. Observabilidad: registrar errores de recuperación sin exponer documentos ni información sensible.
4. Control de concurrencia: impedir que clientes lentos agoten los permisos de carga.
5. Clasificación de errores: distinguir archivos inválidos de timeouts y fallos internos.
6. Verificación real de Supabase Storage y ClamAV.
7. Resolver o delimitar B1-C antes de la habilitación.

## 4. Cómo quiero trabajar

No quiero una nueva implementación enorme ni otro ciclo interminable de revisiones.

Analizá el código que ya existe en main y proponé unidades de trabajo pequeñas, funcionales y verificables.

Para cada unidad indicá:
- Problema concreto.
- Solución mínima.
- Archivos que habría que modificar.
- Pruebas necesarias.
- Riesgo.
- Dependencias con otras unidades.

Elegí cuál debería implementarse primero, justificando su prioridad.

Aplicá el flujo ODD/Gentle AI que corresponda, sin generar revisiones innecesarias ni eludir las obligatorias.

## Restricciones

- No implementar todavía: primero quiero aprobar el plan.
- No modificar migraciones sin autorización.
- No habilitar la carga documental ni Storage.
- No conectar servicios reales todavía.
- No hacer commits, push, PR ni merge.
- Commits futuros con descripción en español y sin Co-Authored-By.

## Entregable

Quiero un plan concreto y acotado para convertir la carga documental ya integrada en una funcionalidad apta para habilitación real.

No rediseñes el backend ni propongas funcionalidades nuevas. Trabajemos sobre los defectos y riesgos que ya conocemos.

### L2 — Aprobación de la hoja de ruta y autorización de U1 (textual), 2026-11-08
Apruebo el plan general U1–U8 como hoja de ruta, pero autorizo únicamente la implementación de **U1 — Limpieza best-effort**.

Procedé con las siguientes condiciones:

1. Registrá el plan mediante el flujo ODD acordado y su espejo en Engram.
2. Trabajá desde la `main` sincronizada, respetando los worktrees, las ramas y la evidencia RDD existentes.
3. Implementá exclusivamente U1, incluyendo las pruebas de éxito y de error.
4. Garantizá que un fallo de limpieza no modifique una respuesta exitosa ni reemplace el error original.
5. No expongas rutas, nombres de archivos ni información sensible en los logs.
6. Conservá las protecciones de los archivos temporales. Documentá el riesgo residual de archivos huérfanos y su futura eliminación.
7. Ejecutá las pruebas correspondientes y respetá el flujo `review assess` del commit. No inicies una revisión nativa que requiera consentimiento sin solicitarlo.
8. Creá un commit en español, sin `Co-Authored-By`.

**No avances automáticamente a U2 ni a otras unidades.** Al terminar U1, presentá los cambios, las pruebas ejecutadas, los resultados y el estado de revisión antes de continuar.

Mantené `DOCUMENTOS_CARGA_HABILITADA=false`. No modifiques migraciones ni utilices servicios o credenciales reales.

### L3 — Hallazgos de análisis y ejecución de U1, 2026-11-08
- V6 ya permite las transiciones de reconciliación (`RESERVADA`→`TRANSFIRIENDO|CERRADA`, `TRANSFIRIENDO`→`ALMACENADA|RECONCILIANDO`, `ALMACENADA`→`PUBLICADA|RECONCILIANDO`, `RECONCILIANDO`→`ALMACENADA|CERRADA`; renovación de reserva en el mismo estado salvo `TRANSFIRIENDO`): U4/U5 no necesitan migración. La reserva (3 min) supera el POST a Storage (3 s + 15 s).
- Entorno: los worktrees temporales de `/tmp` desaparecieron (git los marca `prunable`); no se ejecutó `git worktree prune`. El trabajo se realiza en `/home/el_lito/banfield-patin-worktrees/habilitable`. Commits y evidencia RDD (`.git/codex-evidence`) intactos.
- U1, RED: `LimpiezaTemporalesCargaTest` falló 3/4 (cierre con fallo lanzaba 503; el rechazo 422 se reemplazaba por 503; una publicación confirmada respondía 503). GREEN tras `ArchivoPreparado.eliminar`, helper compartido de mejor esfuerzo que no lanza y registra una advertencia con solo la clase de la causa.
- Riesgo residual documentado en `docs/DOCUMENTOS.md`: temporales huérfanos hasta contar con purga (barrido acotado, trabajo futuro, no autorizado aún).
- Verificación de U1 (una ejecución cada una, sin repetir): `./mvnw -o -Dtest='*CargaTest,PreparadorDocumentoTest,ClamAvPrivadoTest,ServicioCargaFalloTest,CargaDocumentalSeguridadWebMvcTest,CargaDeshabilitadaWebMvcTest' test` → 73/73; `-Pdb-tests -Dtest=CargaDocumentalHttpDbTest test` (PostgreSQL 17 y HTTP) → 26/26. `DOCUMENTOS_CARGA_HABILITADA=false`; sin migraciones, servicios ni credenciales reales. Suite completa: la valida CI al publicar.

### L4 — Autorización de U2 (textual), 2026-11-09
U1 queda aceptada como completada localmente.

Autorizo exclusivamente **U2 — Observabilidad sin datos sensibles**, siguiendo el plan ODD registrado en `odd/tasks/documentos-carga-habilitable.md`.

Condiciones:
- Implementar los logs WARN previstos para errores de recuperación.
- Registrar solamente `operacionId`, fase, clase de excepción y código saneado cuando corresponda.
- No registrar mensajes de excepciones, rutas, nombres, SHA ni identificadores personales.
- Incorporar pruebas que verifiquen la presencia de los campos permitidos y la ausencia de información sensible.
- Mantener el alcance estrictamente limitado a U2.
- Ejecutar las pruebas pertinentes y `review assess`, respetando los requisitos de consentimiento.
- Registrar la evidencia en ODD y Engram sin sobrescribir entradas anteriores.
- Crear un commit independiente, en español y sin `Co-Authored-By`.

No realizar push, PR ni merge. No avanzar a U3 sin autorización.

Al finalizar, informar los cambios, pruebas, riesgos residuales y resultado de revisión.

### L5 — Ejecución de U2, 2026-11-09
- RED: `DiagnosticoCargaTest` falló 4 de 6 (los que exigen log; los dos que exigen silencio ya pasaban). GREEN con `DiagnosticoCarga` y las fases en `CargaDocumentalService`/`OperacionesCargaJdbc` (compensaciones `cierre` y `reconciliacion`, antes `catch` vacíos). Se añadió el registro de 409 con operación ya reservada (pérdida de CAS) y su prueba (7 pruebas).
- Verificación (una ejecución cada una): focalizada 80/80 (incluye las 7 nuevas); `CargaDocumentalHttpDbTest` 26/26. En esa corrida real se emitieron 8 advertencias, todas con solo UUID de operación, fase, clase y código. `DOCUMENTOS_CARGA_HABILITADA=false`; sin migraciones, servicios ni credenciales reales.
- Residual: los componentes inferiores ya descartan la causa (`ErrorCarga.indisponible()`), por lo que el log indica fase y código genérico, no el detalle interno; sin métricas (el proyecto no tiene Micrometer); sin alerta ni agregación.
