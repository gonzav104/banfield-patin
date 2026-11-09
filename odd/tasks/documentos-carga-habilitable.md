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
- S21 (U5): «Apruebo la **alternativa A para U5: reconciliación conservadora sin publicación de transferencias inciertas**.»; «TRANSFIRIENDO vencida podrá pasar a RECONCILIANDO mediante CAS.»; «RECONCILIANDO vencida podrá renovar su reserva mediante CAS.»
- S22 (U5): «Si Storage confirma existencia o ausencia, la operación podrá pasar a CERRADA, sin publicar el objeto. La existencia no demuestra integridad.»; «Si Storage devuelve un resultado incierto, conservar RECONCILIANDO y responder 503 reintentable.»
- S23 (U5): «Una operación ALMACENADA con transferencia confirmada debe conservar ese estado cuando falle transitoriamente la publicación, para permitir la recuperación mediante U4.»; «Los conflictos permanentes de negocio deben manejarse sin crear un ciclo de reintentos interminables.»; «Una operación cerrada nunca debe reutilizar su clave de objeto. Documentar el reintento con una nueva clave de idempotencia.»; «Mantener la validación de duración de reserva frente a los timeouts de Storage.»
- S24 (U5): «Analizar y probar el escenario de POST tardío, incluyendo la creación de objetos huérfanos después del cierre.»; «No asumir que HEAD 404 garantiza que una transferencia anterior terminó.»; «Conservar las garantías de aislamiento, CAS e idempotencia de U4.»; «Incorporar las pruebas de regresión correspondientes a los hallazgos R4/R3.»; «Incorporar la prueba pendiente del rollback de RESERVADA con DNI en conflicto, si puede hacerse sin ampliar el alcance funcional.»
- S25 (U5): «No incorporar descargas, verificaciones SHA simuladas ni mecanismos que den una falsa garantía de integridad.»; «No implementar todavía la purga automática de objetos o archivos huérfanos.»; «No modificar migraciones ni ampliar el contrato de Storage.»; «Si surge una limitación que impida cumplir estas garantías, detenerse antes de implementar una solución alternativa.»
- S26 (U6): «Autorizo exclusivamente **U6 — Permiso de carga después del spool**, conforme a la hoja de ruta ODD.»; «Evitar que clientes con cargas HTTP lentas agoten los permisos destinados al procesamiento documental.»
- S27 (U6): «Mantener la preautorización de seguridad en `PreautorizacionCargaFiltro`, antes de recibir el cuerpo de la solicitud.»; «Trasladar la adquisición del permiso de procesamiento a `CargaDocumentalService`, una vez recibido el archivo.»; «Garantizar que el permiso se libere exactamente una vez, incluso ante errores, excepciones y cancelaciones.»; «Mantener el límite actual de concurrencia y el rechazo inmediato cuando no exista capacidad. No introducir colas de espera ilimitadas.»
- S28 (U6): «Conservar las garantías de autenticación, autorización, validación de documentos e idempotencia.»; «Evitar que una solicitud rechazada deje archivos temporales innecesarios.»; «No modificar los contratos HTTP ni las transiciones persistidas salvo que sea estrictamente necesario para cumplir U6.»; «Diferenciá qué límites protege U6 y cuáles dependen de infraestructura.»; «Si encontrás una vulnerabilidad relevante que requiera ampliar la arquitectura o el alcance, detenete y presentá alternativas.»
- S29 (U6): «Dos clientes lentos no deben ocupar los permisos de procesamiento antes de completar la recepción.»; «Una carga válida debe poder procesarse mientras esos clientes siguen enviando datos, siempre que haya recursos HTTP disponibles.»; «Cuando los permisos de procesamiento estén agotados, una nueva carga completa debe rechazarse sin quedar encolada.»; «El permiso debe liberarse ante éxito, rechazo y excepción.»; «No deben producirse fugas de permisos ni de archivos temporales en los caminos cubiertos.»; «Verificar que las protecciones de seguridad del filtro siguen ejecutándose antes de recibir el cuerpo.»; «Priorizá pruebas deterministas y evitá depender exclusivamente de pausas temporizadas.»
- S9 (hoja de ruta): «Apruebo el plan general U1–U8 como hoja de ruta, pero autorizo únicamente la implementación de **U1 — Limpieza best-effort**.»

## Tasks
Solo U1 está autorizada; las demás son hoja de ruta aprobada, sin autorización de implementación.
- [x] U1 · S1–S8 · inline · Limpieza best-effort del temporal. Riesgo: ninguno (sin contrato, permisos ni concurrencia modificados). Commit: el de esta unidad (`test(documentos)`/`fix(documentos)` de limpieza best-effort).
- [x] U2 · S10–S12 · inline · Observabilidad sin datos sensibles: `DiagnosticoCarga`, WARN con operación, fase, clase de causa y código saneado. Risk: none (solo agrega logs). Commit: el de esta unidad (`feat(documentos)` de diagnóstico).
- [x] U3 · S13–S15 · inline · Clasificación de timeout: el vencimiento de la JVM hija es `503 CARGA_NO_DISPONIBLE`; exit 42 sigue siendo `422`. Risk: none (cambia el código de error de una condición de fallo, dentro del contrato OpenAPI ya publicado que declara 503). Commit: el de esta unidad (`fix(documentos)` de timeout).
- [x] U4 · S16–S20 · inline · El reintento retoma por CAS operaciones RESERVADA/ALMACENADA vencidas. Risk: item 4 (concurrencia: reserva, CAS y carreras con el propietario anterior). Verificador independiente: CUMPLE S16–S20, sin bloqueantes. Commit: el de esta unidad (`feat(documentos)` de retoma por CAS); commit `a0da774`; revisión nativa aprobada y reconocida (lineage `review-aa2f2eb8464c2c52`).
- [x] U5 · S21–S25 · inline · Reconciliación conservadora con Storage (alternativa A, L14): nunca publica una transferencia incierta. Risk: items 1 y 4 (estado persistido de operaciones y concurrencia/CAS). Verificador independiente: CUMPLE S21–S25, sin bloqueantes. Commit `dbabd28`; revisión nativa aprobada y reconocida (lineage `review-4ce366f6ceb97133`).
- [ ] U6 · S26–S29 · inline · Permiso de procesamiento después del spool. Risk: item 4 (concurrencia: permiso compartido entre solicitudes). Verificador independiente: CUMPLE S26–S29, sin bloqueantes. Commit: el de esta unidad (`fix(documentos)` de permiso tras el spool); revisión nativa según `review assess` del commit.
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

### L11 — Revisión nativa de U4, 2026-11-09
- `review assess` de `9ff5bf0..a0da774` (`--committed-only`, ítem 4 escalado): risk=high, `review_due=true` (`high_risk`). Consentimiento del usuario: `granted`.
- Lineage `review-aa2f2eb8464c2c52`; target `sha256:d20581ad427c978f68bd7232e6a2bd12e64d1fc76069f2395b2b8c8488f2ae59`; árboles base `c778061563c088f303fac3b5deaa0b782ce3c7f8` y candidato `ddf523a88d490d3933ffb253af893464f8c99b4f`; 6 archivos, 512 líneas; presupuesto de corrección 200, sin uso.
- Lentes `review-risk` (0 hallazgos), `review-resilience` (1) y `review-reliability` (2): las tres admitidas, 6 rutas inspeccionadas, 0 bloqueantes; cierre `approved`.
- Reconocimiento (`gentle-ai.review-acknowledged/v1`): consumed revision `sha256:29c672ef82bfdbedae1c1138d7d9bd20f9b07f1e15c1d5c84a7c107e252b7bf3`, `authority: burned`. Evidencia con `SHA256SUMS` en `.git/codex-evidence/carga-habilitable/U4/`.
- Hallazgos informativos (no abren corrección; trabajo posterior):
  - R4-almacenada-retake-reconciliando (WARNING): si falla la publicación de una ALMACENADA retomada (conflicto de DNI o error temporal de base), la operación pasa a RECONCILIANDO, que U4 no retoma: queda pendiente hasta U5.
  - R3-almacenada-dni-reconciliando (WARNING): mismo caso con DNI vigente; coincide con el advisory 1 del verificador.
  - R3-reservada-dni-rollback-sin-prueba (SUGGESTION): falta una prueba HTTP que fije el rollback de la retoma cuando una RESERVADA de DNI choca con un DNI vigente (el verificador lo comprobó con su sonda P5; no quedó en el repositorio).

### L12 — Autorización de U5 (resumen de condiciones; texto completo en el mensaje del usuario del 2026-11-09)
Autorización de **U5 — Reconciliación con Storage** con análisis previo obligatorio: contratos reales de `SupabaseAlmacenDocumentos`; «Determiná si es posible verificar de manera confiable la identidad e integridad de un objeto recuperado. Tamaño y MIME, por sí solos, no son suficientes.»; carreras entre vencimiento, inspección y transferencia tardía; hallazgos R4/R3 de U4. Límite: «Si la reconciliación segura requiere una modificación de arquitectura, migración o ampliación significativa del contrato de Storage que no esté contemplada, detenete y presentá alternativas antes de implementarla. No improvises una verificación de integridad.»

### L13 — Análisis previo de U5 (sin cambios de código), 2026-11-09
- Contrato real del adaptador: `crear` hace POST a `/object/{bucket}/{uuid}` con `x-upsert=false` y confirma el `Key` exacto; `inspeccionar` hace HEAD autenticado y solo proyecta `Content-Length` y `Content-Type` (EXISTENCIA_CONFIRMADA, AUSENCIA_OBSERVADA o INCIERTA). No hay SHA-256, ETag ni descarga; la documentación vigente declara que «No se interpreta un 404 como fin de una transferencia pendiente ni ETag como SHA-256» y que la reconciliación y la ausencia de publicación corresponden a B6/B8 de forma supervisada.
- Identidad: fuerte. La clave es un UUID aleatorio único por operación (`uq_operacion_destino`), sin sobrescritura (`x-upsert=false`), y la creación de un objeto es atómica. Integridad: **no verificable** con el puerto actual; tamaño y MIME no bastan (S de L12). Conclusión: publicar desde TRANSFIRIENDO/RECONCILIANDO exige ampliar el contrato de Storage (descarga y SHA-256) o no publicar nunca una transferencia incierta.
- Carreras: (1) un POST tardío del propietario anterior solo puede crear el objeto en la misma clave; si la reconciliación nunca publica, un objeto tardío queda huérfano y sin referencia, nunca inconsistente. (2) La reserva (3 min) supera ampliamente el POST (3 s + 15 s), pero un servidor podría completar una escritura tras el corte del cliente: la ausencia observada no prueba el fin del escritor. (3) Retoma frente a otro reintento: mismo CAS de U4 bajo `FOR UPDATE`.
- R4/R3 de U4: V6 no registra si una RECONCILIANDO vino de un objeto confirmado (ALMACENADA) o de una transferencia incierta (TRANSFIRIENDO). Distinguirlo requiere migración, o bien dejar de pasar ALMACENADA a RECONCILIANDO ante un fallo temporal de publicación (U4 ya la retoma).
- Estado: U5 detenida antes de escribir código; se presentan alternativas al usuario. L11 de U4 sigue sin commitear.

### L14 — Aprobación de la alternativa A para U5 (resumen literal de decisiones), 2026-11-09
«Apruebo la **alternativa A para U5: reconciliación conservadora sin publicación de transferencias inciertas**. Autorizo su implementación conforme al análisis registrado en ODD y Engram #879.» Decisiones 1–8 y garantías adicionales citadas textualmente en S21–S25. Verificación: «Ejecutar pruebas de integración con PostgreSQL 17 y dobles HTTP de Storage. Cubrir existencia, ausencia, incertidumbre, conflictos de negocio, concurrencia, pérdida de CAS y respuestas tardías.» Flujo: ODD, Engram y RDD; conservar L11–L12; commit independiente en español sin Co-Authored-By; `review assess` y consentimiento si corresponde; sin push, PR ni merge; no avanzar a U6; sin migraciones, ampliación del contrato de Storage, servicios ni credenciales reales; `DOCUMENTOS_CARGA_HABILITADA=false`.

### L15 — Diseño, carreras y evidencia de U5, 2026-11-09
- Transiciones (todas ya admitidas por V6, sin migración): `retomar` adquiere por CAS (versión, estado y `reserva_hasta <= clock_timestamp()`, bajo el `FOR UPDATE` de `reservar` y después del manifiesto) también TRANSFIRIENDO vencida (→RECONCILIANDO) y RECONCILIANDO vencida (renovación). El servicio `reconciliar` llama a `almacen.inspeccionar`: EXISTENCIA_CONFIRMADA (cualquier tamaño) o AUSENCIA_OBSERVADA → `cerrarReconciliada` (RECONCILIANDO→CERRADA por CAS) y 409; INCIERTA (o excepción de inspección) → `liberar` (misma fase, reserva liberada por CAS) y 503 `RESULTADO_CARGA_INCIERTO`. Nunca publica desde la reconciliación.
- Compensación por fase (`compensar`): antes de transferir → `cerrarAntesDeTransferir`; transferencia incierta → `incierta` (RECONCILIANDO); al publicar una ALMACENADA: conflicto permanente (403/404/409) → `cerrarConfirmada` (ALMACENADA→RECONCILIANDO→CERRADA en una transacción); transitorio (401, 5xx, base, auditoría) → `liberar` y queda ALMACENADA para U4. La reconciliación no se compensa.
- Validación: `CargaConfiguracion.validarReserva` rechaza al iniciar `reserva <= scanner-plazo` o, con Storage habilitado, `reserva <= 2 × (conexión + respuesta)` (`StoragePropiedades.plazoMaximoPorOperacion`, getter de configuración; el puerto `AlmacenDocumentos` y el HTTP no cambian).
- Carreras: (1) reintentos concurrentes: el `FOR UPDATE` serializa y solo uno inspecciona; (2) pérdida de CAS durante la inspección (otro actor cambia la versión): el cierre falla con 409 y la operación sigue RECONCILIANDO; (3) POST tardío: el cliente corta a los 2 s, la operación queda RECONCILIANDO, vence, un reintento ve 404 y cierra; el POST termina después y deja un objeto huérfano; CERRADA es terminal y nunca lo publica ni lo reabre; (4) un HEAD 404 no se toma como fin de la transferencia: cerrar es seguro porque nunca publica; (5) propietario tardío tras la reconciliación: su `almacenada`/`publicar`/`incierta` fallan el CAS.
- Cambios intencionales de comportamiento: revocación al publicar → CERRADA (antes RECONCILIANDO); auditoría caída al publicar → ALMACENADA con la reserva libre (antes RECONCILIANDO); TRANSFIRIENDO/RECONCILIANDO vencidas → reconciliación (antes 409).
- Pruebas (PostgreSQL 17 real, doble HTTP con HEAD controlable): reconciliación con existencia, ausencia y objeto de tamaño incompatible desde TRANSFIRIENDO y RECONCILIANDO (cierre, 409, sin POST ni documento, terminal; otra clave crea otro objeto); incierto y respuesta tardía (503, RECONCILIANDO, reserva libre, reintentable); reintentos concurrentes (un solo HEAD); pérdida de CAS durante la inspección; POST tardío después del cierre (huérfano, nunca publicado); ALMACENADA con fallo transitorio recuperada con la misma clave (sin repetir POST); ALMACENADA con DNI vigente cerrada sin ciclo (R4/R3); RESERVADA con DNI vigente revierte la retoma (pendiente de U4); reserva activa en los cuatro estados retomables; CERRADA nunca se retoma. `ReservaPlazosTest` (3) para la validación.
- RED en la base `a0da774` (copia aislada, solo el archivo de pruebas): 13 fallos, exactamente las pruebas de U5 y las 2 adaptadas; las de conservación ya pasaban. GREEN: `CargaDocumentalHttpDbTest` 50/50; carreras repetidas 3 veces (7/7 cada vez) tras quitar una aserción intermedia dependiente de tiempos; focalizada con el adaptador de Storage 196/196.

### L16 — Verificación independiente de U5, 2026-11-09
- Verificador independiente (copia aislada, PostgreSQL 17 real, 14 sondas propias): S21–S25 CUMPLE, **ningún bloqueante**, sin regresión de U4. Corridas: `CargaDocumentalHttpDbTest` 50/50; focalizada con el adaptador 196/196; sondas de arranque 4/4 (la validación de la reserva rechaza 10 s con Storage 3 s + 2 s y acepta 11 s; el `ObjectProvider<StoragePropiedades>` resuelve el bean); sondas de base 10/10 (HEAD 401/403/500 inciertos y reintentables; manifiesto distinto y reserva activa sin HEAD; propietario vivo en el POST con la reserva realmente vencida pierde el CAS en `almacenada` tras el cierre; `liberar`, `cerrarConfirmada` y `cerrarReconciliada` con versión obsoleta no modifican la fila, ni sobre una PUBLICADA; una clave nueva usa otro `objeto_id`). Logs sin datos sensibles.
- Observación atendida: el texto que afirmaba que una reserva vencida implica el agotamiento del plazo del cliente no estaba comprobado para el envío del cuerpo; se reformuló como cobertura de los plazos configurados, sin afirmar el fin de una escritura del servidor.
- Advisories registrados (no corregidos): (1) si `cerrarReconciliada` o `liberar` fallan por error de base, la reserva queda tomada hasta vencer (3 min): conservador, sin pérdida; (2) un 404 por vínculo revocado cierra, mientras un 401 por entidad desactivada libera: un vínculo reactivado requiere nueva clave (intencional, L15); (3) `cerrarConfirmada` puede cerrar una ALMACENADA con la reserva vencida si nadie la retomó (equivale a un reintento); (4) una RESERVADA con DNI en conflicto nunca se cierra: cada reintento revierte y responde 409, sin objeto ni ciclo automático (comportamiento de U4).
- Scratch del verificador eliminado por el padre.

### L17 — Revisión nativa de U5, 2026-11-09
- `review assess` de `a0da774..dbabd28` (`--committed-only`, ítem 4 escalado): risk=high, `review_due=true`. Consentimiento del usuario: `granted`.
- Lineage `review-4ce366f6ceb97133`; target `sha256:fb6c120603d536ee2027ce503ecaf96f1f329bd20b563f0aced02e76c1e62394`; árboles base `ddf523a88d490d3933ffb253af893464f8c99b4f` y candidato `b2ee57289d5d4800e914a8ae1a66c9b831236abb`; 9 archivos, 632 líneas; presupuesto de corrección 200, sin uso.
- Lentes `review-risk` (0 hallazgos), `review-resilience` (1) y `review-reliability` (1): las tres admitidas, 9 rutas inspeccionadas, 0 bloqueantes; cierre `approved`.
- Reconocimiento (`gentle-ai.review-acknowledged/v1`): ver `.git/codex-evidence/carga-habilitable/U5/ack.json` (`authority: burned`), con `SHA256SUMS`.
- Hallazgos informativos (no abren corrección):
  - R4-reconciliar-cierre-db-reserva-retenida (WARNING): si `cerrarReconciliada` falla por un error transitorio de base, se responde 503 sin liberar la reserva renovada; los reintentos reciben 409 hasta que vence (3 min). Coincide con el advisory 1 de L16.
  - R3-reserva-wiring-sin-prueba (SUGGESTION): la validación de la reserva solo se prueba llamando a `validarReserva`; ninguna prueba del repositorio fija que el contexto resuelva `ObjectProvider<StoragePropiedades>` y rechace una reserva inválida al arrancar (las sondas de arranque del verificador lo comprobaron pero no quedaron en el repositorio).

### L18 — Autorización de U6 (citas literales en S26–S29), 2026-11-09
«U5 queda aceptada como completada localmente, con su revisión nativa aprobada y reconocida.» Autorización exclusiva de U6 con las condiciones de S26–S29. Flujo: ODD, Engram y RDD; «Incorporar la trazabilidad L17 pendiente de U5.»; «Registrar los hallazgos residuales de U5 sin corregirlos fuera del alcance autorizado.»; commit independiente en español sin Co-Authored-By; `review assess` y consentimiento si corresponde; sin push, PR ni merge; no avanzar a U7; sin servicios ni credenciales reales; `DOCUMENTOS_CARGA_HABILITADA=false`.
- Residuales de U5 registrados, sin corregir: R4-reconciliar-cierre-db-reserva-retenida (si `cerrarReconciliada` falla por error transitorio de base, la reserva queda tomada hasta vencer); R3-reserva-wiring-sin-prueba (falta una prueba de contexto que fije la validación de la reserva al arrancar con Storage habilitado).

### L19 — Límites, diseño y evidencia de U6, 2026-11-09
- Límites efectivos leídos de los artefactos (Spring Boot 4.1.1, Tomcat 11.0.24; sin `server.tomcat.*`): 200 hilos, 8192 conexiones, cola 100, `max-part-count` 50, `max-swallow-size` 2 MB; `soTimeout` 20 s por lectura y `disableUploadTimeout=true` (sin plazo total ni tasa mínima del cuerpo). Multipart de la aplicación: 5 MB por archivo, 5 MB + 64 KiB por solicitud, umbral 0 (spool a `temporales`), `resolve-lazily`.
- U6 protege los permisos de procesamiento; los hilos de Tomcat, las conexiones y el disco del spool dependen de infraestructura (plazo total y tasa mínima del cuerpo en el proxy, límite de conexiones por cliente, volumen dedicado con cuota). Consecuencia registrada: el 429 ahora ocurre tras recibir el cuerpo, por lo que las cargas en recepción ya no están acotadas a 2 sino por los hilos de Tomcat (hasta unos 1 GB de spool simultáneo); el agotamiento de hilos por clientes lentos ya era posible en cualquier endpoint con cuerpo, incluso sin autenticación. No requiere ampliar la arquitectura para cumplir U6; una cuota de recepción propia queda como alternativa no implementada.
- Diseño: el filtro conserva toda la seguridad previa al cuerpo y deja de tomar el permiso; `CargaDocumentalService.cargar` toma el permiso con `tryAcquire` (sin espera ni cola), responde 429 `CARGA_OCUPADA` antes de preparar temporales y libera en un `finally` alrededor de `procesar` (exactamente una vez). Sin cambios de contrato HTTP ni de transiciones persistidas.
- RED: `PermisoCargaTest` falló el caso de agotamiento (el servicio no tomaba permiso); `clientesLentosNoOcupanLosPermisos…` falló con «expected: 2 but was: 0» (dos clientes lentos todavía enviando ocupaban los dos permisos). Antes, dos fallos de la prueba eran defectos del arnés (cabecera CSRF tomada del cuerpo y prefijo menor que el búfer del parser), corregidos.
- Pruebas: unitarias 3 (agotamiento → 429 inmediato sin temporal; liberación ante 422, excepción de base y corte del cliente; carga repetida); HTTP con Tomcat y PostgreSQL 4 (dos clientes lentos detectados por su spool en disco no toman permisos y una carga completa da 201 mientras siguen enviando; al abandonar no quedan permisos, spool ni temporales; la preautorización responde 404 y 401 con solo las cabeceras; con los permisos agotados una carga completa da 429 sin encolarse ni crear su temporal; el permiso vuelve tras 201, 422, virus, scanner caído y Storage 503). GREEN: `CargaDocumentalHttpDbTest` 54/54; U6 y concurrencia repetidas 3 veces, 9/9; focalizada 199/199.

### L20 — Verificación independiente de U6, 2026-11-09
- Verificador independiente (copia aislada, Tomcat y PostgreSQL 17 reales, 66 sondas propias): S26–S29 CUMPLE, **ningún bloqueante**. Corridas: `CargaDocumentalHttpDbTest` 54/54; focalizada 199/199; sondas unitarias (400 llamadas en 16 hilos, nunca más de 2 dentro; `StackOverflowError` libera el permiso); sondas HTTP 60/60 con un `@AfterEach` que exige 2 permisos libres tras cada prueba (incluidas las de U4 y U5), ráfaga mixta de 24 solicitudes con máximo de 2 libres, tres clientes lentos, cliente que corta durante el procesamiento, seguridad con solo cabeceras (403, 401, 404, 403, 404). En la baseline `dbabd28` fallan exactamente las tres sondas de clientes lentos.
- Observaciones atendidas: (A1) `docs/DOCUMENTOS.md` todavía decía que el filtro adquiere capacidad antes del multipart; corregido. (A2) el 429 conserva estado, código, mensaje y `no-store`, pero ahora lo escribe el manejador global, con las mismas cabeceras de caché que los demás errores del endpoint; aceptado como consecuencia de S27 y documentado.
- Advisories registrados: (A3) frente a la baseline, una FAMILIA autenticada puede retener hasta unos 200 hilos y 1 GB de spool en este endpoint: el plazo total y la tasa mínima del cuerpo en el proxy, el límite de conexiones por cliente y la cuota del volumen de `temporales` son **requisitos previos a la habilitación**; (A4) con permisos agotados, la seguridad y los errores de multipart (404, 400, 413) se responden antes que el 429; (A5) un 429 llega tras subir el cuerpo completo (costo de ancho de banda).
- Scratch del verificador eliminado por el padre.
