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
- S13 (U3): «Un timeout de inspección debe devolver `503 CARGA_NO_DISPONIBLE`, por tratarse de un fallo temporal de infraestructura, no de un documento inválido.»; «Mantener `422` para los documentos realmente inválidos, incluyendo el comportamiento del exit code 42.»
- S14 (U3): «Garantizar que la JVM hija sea terminada correctamente cuando ocurra un timeout, sin dejar procesos vivos.»; «Comprobar que los recursos temporales se limpien con las garantías establecidas en U1.»
- S15 (U3): «Utilizar la observabilidad incorporada en U2, sin registrar datos sensibles.»; «Implementar pruebas de regresión que distingan timeout, documento inválido y funcionamiento normal.»; «No avanzar a U4 ni realizar cambios fuera del alcance de U3.»
- S16 (U4): «Implementá la recuperación de reservas vencidas en los estados RESERVADA y ALMACENADA, sin modificar todavía el comportamiento de TRANSFIRIENDO y RECONCILIANDO, que corresponde a U5.»
- S17 (U4): «Una reserva activa debe seguir impidiendo que otra solicitud se apropie de la operación.»; «Una reserva vencida debe poder retomarse mediante una transición atómica y condicionada (CAS).»; «Ante solicitudes concurrentes, solamente una debe adquirir la reserva. Las demás deben recibir el resultado previsto sin duplicar operaciones.»
- S18 (U4): «RESERVADA vencida debe poder continuar el proceso de carga.»; «ALMACENADA vencida debe poder continuar su publicación sin repetir innecesariamente la transferencia a Storage.»; «Preservar las garantías existentes de idempotencia, aislamiento y consistencia de datos.»
- S19 (U4): «Analizar explícitamente las carreras entre vencimiento, reintento y ejecución tardía del propietario anterior. No asumir que una reserva vencida implica que el proceso anterior terminó.»; «Verificar que un reintento no permita asociar una clave de idempotencia existente con un documento u operación incompatible.»
- S20 (U4): «Implementá pruebas de integración con PostgreSQL real, incluyendo reservas activas, vencidas, reintentos concurrentes, pérdida de CAS y recuperación después de una interrupción simulada.»; «Verificá que los cambios no introduzcan regresiones en U1, U2 y U3.»; «No agregues migraciones salvo que encuentres una necesidad imprescindible; en ese caso, informala antes de implementarla.»; «No avances a U5.»
- S9 (hoja de ruta): «Apruebo el plan general U1–U8 como hoja de ruta, pero autorizo únicamente la implementación de **U1 — Limpieza best-effort**.»

## Tasks
Solo U1 está autorizada; las demás son hoja de ruta aprobada, sin autorización de implementación.
- [x] U1 · S1–S8 · inline · Limpieza best-effort del temporal. Riesgo: ninguno (sin contrato, permisos ni concurrencia modificados). Commit: el de esta unidad (`test(documentos)`/`fix(documentos)` de limpieza best-effort).
- [x] U2 · S10–S12 · inline · Observabilidad sin datos sensibles: `DiagnosticoCarga`, WARN con operación, fase, clase de causa y código saneado. Risk: none (solo agrega logs). Commit: el de esta unidad (`feat(documentos)` de diagnóstico).
- [x] U3 · S13–S15 · inline · Clasificación de timeout: el vencimiento de la JVM hija es `503 CARGA_NO_DISPONIBLE`; exit 42 sigue siendo `422`. Risk: none (cambia el código de error de una condición de fallo, dentro del contrato OpenAPI ya publicado que declara 503). Commit: el de esta unidad (`fix(documentos)` de timeout).
- [ ] U4 · S16–S20 · inline · El reintento retoma por CAS operaciones RESERVADA/ALMACENADA vencidas. Risk: item 4 (concurrencia: reserva, CAS y carreras con el propietario anterior). Verificador independiente: CUMPLE S16–S20, sin bloqueantes. Commit: el de esta unidad (`feat(documentos)` de retoma por CAS); revisión nativa según `review assess` del commit.
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

### L6 — Autorización de U3 (textual), 2026-11-09
U2 queda aceptada como completada localmente.

Autorizo exclusivamente **U3 — Clasificación de timeout**, según el plan ODD registrado.

Objetivo: corregir la clasificación del timeout de la JVM hija utilizada durante la inspección documental.

Condiciones:

1. Un timeout de inspección debe devolver `503 CARGA_NO_DISPONIBLE`, por tratarse de un fallo temporal de infraestructura, no de un documento inválido.
2. Mantener `422` para los documentos realmente inválidos, incluyendo el comportamiento del exit code 42.
3. Garantizar que la JVM hija sea terminada correctamente cuando ocurra un timeout, sin dejar procesos vivos.
4. Comprobar que los recursos temporales se limpien con las garantías establecidas en U1.
5. Utilizar la observabilidad incorporada en U2, sin registrar datos sensibles.
6. Implementar pruebas de regresión que distingan timeout, documento inválido y funcionamiento normal.
7. Ejecutar pruebas focalizadas y respetar `review assess`, sin iniciar revisiones nativas que requieran autorización.
8. Actualizar ODD y Engram, preservando los registros anteriores.

Crear un commit independiente en español, sin `Co-Authored-By`.

No hacer push, PR ni merge. Mantener la carga documental deshabilitada.

No avanzar a U4 ni realizar cambios fuera del alcance de U3.

Al finalizar, presentar evidencia de implementación, pruebas y revisión.

### L7 — Ejecución de U3, 2026-11-09
- RED: `ClasificacionInspeccionTest` (3 pruebas) falló solo en el timeout (devolvía 422); inválido (422, exit 42) y válido ya pasaban. GREEN con el cambio de `PreparadorDocumento.inspeccionar`: el vencimiento del plazo registra `fase=inspeccionar causa=TimeoutException` (U2) y responde `ErrorCarga.indisponible()`. La terminación de la JVM hija (`destroyForcibly` + espera de 5 s en `finally`) no cambió: la prueba comprueba que no queda ningún hijo vivo y que el temporal se elimina (U1).
- Verificación (una ejecución cada una): focalizada 83/83; `CargaDocumentalHttpDbTest` 26/26 (ninguna línea `inspeccionar` en una corrida sana). `DOCUMENTOS_CARGA_HABILITADA=false`; sin migraciones ni servicios reales. Sin cambios en OpenAPI: el contrato ya declara `503 CARGA_NO_DISPONIBLE`.
- Residual: un archivo malicioso que consuma todo el plazo también devuelve 503 (reintentable), por lo que el semáforo de concurrencia sigue siendo la defensa contra el abuso (U6); salidas no nulas distintas de 42 (caída de la JVM hija) ya eran 503. El plazo de 10 s sigue siendo configurable (`procesamiento`) y debe dimensionarse para el arranque de la JVM bajo carga.

### L8 — Autorización de U4 (textual), 2026-11-09
U3 queda aceptada como completada localmente.

Autorizo exclusivamente **U4 — Reintento y recuperación de operaciones vencidas mediante CAS**, conforme al plan ODD.

Implementá la recuperación de reservas vencidas en los estados RESERVADA y ALMACENADA, sin modificar todavía el comportamiento de TRANSFIRIENDO y RECONCILIANDO, que corresponde a U5.

**Garantías obligatorias:**

1. Una reserva activa debe seguir impidiendo que otra solicitud se apropie de la operación.
2. Una reserva vencida debe poder retomarse mediante una transición atómica y condicionada (CAS).
3. Ante solicitudes concurrentes, solamente una debe adquirir la reserva. Las demás deben recibir el resultado previsto sin duplicar operaciones.
4. RESERVADA vencida debe poder continuar el proceso de carga.
5. ALMACENADA vencida debe poder continuar su publicación sin repetir innecesariamente la transferencia a Storage.
6. Preservar las garantías existentes de idempotencia, aislamiento y consistencia de datos.
7. Analizar explícitamente las carreras entre vencimiento, reintento y ejecución tardía del propietario anterior. No asumir que una reserva vencida implica que el proceso anterior terminó.
8. Verificar que un reintento no permita asociar una clave de idempotencia existente con un documento u operación incompatible.

**Pruebas y validaciones:**

Implementá pruebas de integración con PostgreSQL real, incluyendo reservas activas, vencidas, reintentos concurrentes, pérdida de CAS y recuperación después de una interrupción simulada.

Verificá que los cambios no introduzcan regresiones en U1, U2 y U3.

Utilizá la observabilidad incorporada en U2 sin exponer datos sensibles.

**Flujo de trabajo:**

Respetá ODD, Engram y RDD. Ejecutá `review assess` y, si requiere revisión nativa con consentimiento, detenete para solicitar autorización.

No agregues migraciones salvo que encuentres una necesidad imprescindible; en ese caso, informala antes de implementarla.

Creá un commit independiente en español, sin Co-Authored-By.

No hagas push, PR ni merge. No avances a U5.

Mantené DOCUMENTOS_CARGA_HABILITADA=false y no utilices servicios ni credenciales reales.

Al finalizar, presentá los cambios, pruebas, evidencia de concurrencia, estado de revisión y riesgos residuales.

### L9 — Diseño, análisis de carreras y evidencia de U4, 2026-11-09
- Sin migración: V6 ya admite la renovación de una reserva vencida en el mismo estado (salvo TRANSFIRIENDO) y las transiciones RESERVADA→TRANSFIRIENDO y ALMACENADA→PUBLICADA exigen reserva activa.
- Diseño: en `OperacionesCargaJdbc.reservar`, dentro de su transacción y bajo `SELECT … FOR UPDATE`, **después** de validar el manifiesto, una clave existente en RESERVADA o ALMACENADA se retoma con `UPDATE … SET version=version+1, reserva_hasta=clock_timestamp()+reserva WHERE id AND version AND estado AND reserva_hasta <= clock_timestamp()` (CAS). 0 filas o reserva activa → 409 sin cambios. RESERVADA retomada vuelve a comprobar el DNI vigente antes de transferir. El servicio publica ALMACENADA retomada sin escanear ni transferir (marca `transferencia` para que un fallo de publicación vaya a RECONCILIANDO). `DiagnosticoCarga.retomada` deja una línea INFO con operación y estado.
- Carreras analizadas:
  1. Dos reintentos simultáneos: el `FOR UPDATE` serializa; el primero renueva la reserva y el segundo la ve activa → 409. La concurrencia de la carga (2 plazas) acota las llegadas paralelas; la decisión la toma PostgreSQL.
  2. Propietario anterior vivo con la reserva vencida (RESERVADA): tras la retoma su versión queda obsoleta; su `iniciar` falla el CAS → 409 y no transfiere; su `cerrarAntesDeTransferir` también falla el CAS (se registra `fase=cierre`) y no cierra la operación ajena. Si su reserva venció sin retoma, el trigger impide `iniciar` y `CERRADA` (reserva vencida) y la operación sigue retomable.
  3. Propietario anterior lento entre `almacenada` y `publicar` (ALMACENADA): si el reintento publicó primero, el INSERT del documento choca con la PK (id de operación) o el `UPDATE` del CAS no encuentra la versión → rollback completo, sin documento ni auditoría duplicados; su `incierta` también falla el CAS. Si publicó el propietario primero, el reintento ve PUBLICADA → 200.
  4. Retoma frente a `iniciar` del propietario en la misma ventana: ambos son transacciones sobre la misma fila; gana la primera y la otra ve otra versión o estado → 409.
  5. Un reintento solo es posible para el mismo solicitante (la clave es por escuela + solicitante), tras `autorizar` con su sesión vigente, y con manifiesto idéntico; cualquier diferencia → 409 sin renovar la reserva.
- Pruebas nuevas en `CargaDocumentalHttpDbTest` (PostgreSQL 17 real, 11 casos): reserva activa (RESERVADA y ALMACENADA) → 409 sin cambios; RESERVADA vencida tras interrupción → 201 y repetición 200; ALMACENADA vencida → 201 sin POST; dos reintentos concurrentes sobre RESERVADA vencida → un 201, 409 para el resto, un solo escaneo y un POST; dos concurrentes sobre ALMACENADA vencida → un único 201, sin POST, una auditoría; propietario tardío vivo pierde el CAS tras la retoma → 409 sin transferir; manifiesto o tipo distintos → 409 sin renovar; TRANSFIRIENDO, RECONCILIANDO y CERRADA vencidas siguen en 409 (U5).
- RED: fallaron las 5 de recuperación (409 permanente); las 6 de conservación ya pasaban. GREEN: `CargaDocumentalHttpDbTest` 37/37. Estabilidad: las 3 pruebas de carrera repetidas 3 veces más, 3/3 cada vez. Regresión U1–U3: focalizada 83/83.

### L10 — Verificación independiente de U4, 2026-11-09
- Verificador independiente (copia aislada `mktemp -d`, PostgreSQL 17 real, sondas propias P1–P9 además de las pruebas del autor): S16–S20 CUMPLE, **ningún bloqueante**. Corridas: `CargaDocumentalHttpDbTest` 37/37; focalizada 83/83; sondas 11/11; contraste en la baseline `9ff5bf0`. Confirmó que todas las transacciones de carga bloquean la fila del deportista (`PESSIMISTIC_WRITE`), por lo que la retoma solo compite con el propietario entre transacciones; que el `publicar` tardío de un propietario cae en `DuplicateKeyException` o en el CAS, con rollback y sin documento ni auditoría duplicados; que el DNI ya vigente revierte la retoma; y que otro usuario con la misma clave crea su propia operación sin tocar la ajena.
- Observación incorporada: la prueba del propietario tardío vencía la reserva con un aumento de versión (el trigger lo exige en cada UPDATE), lo que por sí solo hacía perder el CAS. Se agregó `propietarioTardioConReservaRealmenteVencidaPierdeElCasPorLaRetoma`, que vence la reserva sin cambiar la versión (`SET LOCAL session_replication_role = replica`, solo dentro de esa transacción de prueba): el propietario pierde el CAS por la retoma (`fase=iniciar` y `fase=cierre`). `CargaDocumentalHttpDbTest` 38/38.
- Advisories registrados (no bloquean, no corregidos en U4):
  1. ALMACENADA vencida de tipo DNI con otro DNI ya vigente: con U4 el reintento la retoma, `publicar` responde 409 y `incierta` la pasa a RECONCILIANDO; en la baseline quedaba ALMACENADA. Mismo código de respuesta, sin pérdida ni duplicación; el objeto queda para la reconciliación (U5).
  2. Un propietario cuya reserva venció sin retoma recibe 503 (trigger `RESERVA_VENCIDA`) en lugar de 409. Preexistente en la baseline.
  3. Un propietario tardío cuyo documento ya publicó el reintento recibe 503 `RESULTADO_CARGA_INCIERTO` aunque el documento exista. Preexistente; lo resuelve la repetición con la misma clave (200).
  4. No verificado por HTTP: pausar al propietario entre el commit de `almacenada` y `publicar` (sin punto de enganche); cubierto por invocación directa de las operaciones en las sondas.
- Scratch del verificador eliminado por el padre (`/tmp/tmp.tcTsURKhlV`).
