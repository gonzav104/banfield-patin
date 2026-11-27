# U21-BIS · C0 — Gestión de cuentas por ADMIN
Rama `feat/u21-bis-c0-cuentas` desde `main` 71e8c58 (worktree `~/worktrees/banfield-u21bis`), con los commits del diseño (`docs/U21-BIS-AUTOSERVICIO-FAMILIAR.md`, origen 828731d y 6d5c92e). Solo commits locales: sin push, PR, merge ni deploy; sin Supabase; sin C1–C5 ni U22.
Runner: Maven (`./mvnw`, `-Pdb-tests` PostgreSQL 17 Testcontainers), Vitest + FakeBackend (`npm test`), Playwright E2E real. Delivery: `ask-on-risk`.

## Specs
- S1 (alcance C0): «Implementar el alcance de C0 definido en el documento.» — ADMIN lista y desactiva/reactiva usuarios de una familia.
- S2 (atención): «Varios usuarios dentro de una misma familia. Credenciales independientes. Invitaciones a familias nuevas y existentes. Activación y desactivación de cuentas. Efectividad de la revocación sobre sesiones ya iniciadas, no solo futuros inicios de sesión. Aislamiento entre escuelas y familias. Protección frente a modificaciones de permisos no autorizadas. Auditoría. Revisiones optimistas cuando correspondan. Experiencia ADMIN sencilla y coherente con el panel existente.»
- S3 (migración autorizada): «Autorizo endurecer usuario.familia_id mediante una migración aditiva antes de C2. La pertenencia familiar de un usuario no debe poder cambiar retroactivamente la procedencia de documentos previamente cargados. Verificar la restricción utilizando el login runtime real y contemplar los usuarios ADMIN sin familia. No modificar migraciones históricas. No introducir mecanismos de reasignación entre familias sin aprobación expresa.»
- S4 (UX): «Priorizar una interfaz sencilla para padres con pocos conocimientos tecnológicos. No exponer conceptos técnicos internos como CAS, vigencias, revisiones o unidades de acceso cuando puedan traducirse a acciones comprensibles.»
- S5 (proceso): «Incorporar el documento de diseño 6d5c92e al primer PR de implementación, preservando su trazabilidad.» «Explicitar las cinco decisiones menores pendientes antes de implementar las funcionalidades afectadas.» Pruebas focalizadas y suites completas con recuentos limpios; Gentle AI; esperas acotadas; ODD y Engram.
- S6 (restricciones): «No hacer push, PR, merge ni deploy todavía. No acceder a Supabase. No modificar migraciones históricas. No habilitar escrituras en producción. No comenzar C1–C5 hasta cerrar C0. No comenzar U22. No incorporar deuda técnica ajena. Preservar el checkout principal.» «No implementar funcionalidades adicionales ajenas al alcance definido de C0.»

- S7 (2026-11-27, corrección): «Un JWT emitido antes de quitar el acceso no debe recuperar validez después de reactivar la cuenta, aunque todas las operaciones ocurran dentro del mismo segundo.» «No acepto documentar esa ventana como comportamiento definitivo.» Garantías: «Revocación definitiva de tokens anteriores. Independencia de la precisión temporal. Invalidación atómica con la modificación efectiva del estado de la cuenta. Reactivación sin recuperación de sesiones anteriores. Nuevo inicio de sesión válido. Ausencia de impacto sobre otras cuentas de la misma familia. Repeticiones sin cambios innecesarios de generación ni auditorías duplicadas. Comportamiento seguro ante emisión concurrente de tokens. Tratamiento seguro y documentado de tokens emitidos por versiones anteriores del backend. Compatibilidad con los mecanismos de autenticación, renovación y MFA existentes.» «V19 todavía no fue publicada. Autorizo ajustar esa migración dentro del candidato local, conservando V18 y todas las migraciones históricas integradas.»
- S8 (carga documental): «Revisar específicamente vigenteSinComprobarEmision y sus consumidores.» «La reactivación no debe permitir que una autorización revocada recupere validez.» «Conservar las garantías de idempotencia, reconciliación, limpieza y consistencia del motor documental.» «No introducir bloqueos prolongados de base de datos durante operaciones externas.»

## Decisiones de diseño de C0
- **Revocación efectiva.** Hoy cada solicitud revalida `usuario.activo` (`SesionVigenteJdbc`): desactivar ya corta la sesión abierta en la siguiente solicitud. Brecha: al **reactivar**, un JWT emitido antes de la desactivación (hasta 8 h) volvería a ser válido. V19 agrega `usuario.sesiones_validas_desde`: reactivar lo fija y la revalidación rechaza tokens con `iat` anterior (truncado al segundo, porque `iat` tiene resolución de segundos).
- **Sin versión optimista en la cuenta:** las acciones expresan el estado final («desactivar», «reactivar»), son idempotentes y se aplican con un `UPDATE` condicional (`… AND activo = <estado contrario>`); dos ADMIN concurrentes no pueden producir un resultado que ninguno pidió. Repetir la acción responde el estado actual sin auditar de nuevo.
- **Superficie mínima:** solo cuentas FAMILIA de una familia de la escuela del ADMIN; la ruta exige familia **y** usuario (sin IDOR); `404` uniforme; nunca ADMIN; sin reasignación de familia.
- **Bandera** `banfield.cuentas.gestion-habilitada` (apagada por defecto) para las escrituras; el listado informa si está habilitada para que la pantalla no ofrezca acciones que fallarían.
- **Invitaciones:** se reutiliza `GET /api/admin/invitaciones` con un filtro opcional `familiaId` para mostrarlas junto a las cuentas.

## Cinco decisiones menores pendientes (afectan C1–C5, no C0; se explicitan antes de implementarlas)
1. Solicitudes con tablas tipadas (aprobado: «Solicitudes con columnas tipadas»).
2. Topes por familia en la base (valores a validar con el club) — antes de C2/C3.
3. Endurecer `usuario.familia_id` antes de C2 — **autorizado**; se implementa en C0 (V18) como unidad propia.
4. Sin adjuntar documentos a solicitudes pendientes (aprobado: «Carga documental únicamente después de obtener el acceso autorizado»).
5. Confirmar la responsabilidad en la misma acción de aprobar si la bandera de responsables está encendida — antes de C3/C4.

## Tasks
- [x] T1 · S3 · inline · V18: pertenencia inmutable de `usuario` (familia, escuela, rol, id) y procedencia inmutable de `documento.cargado_por`; prueba con el login runtime real · commit be2fcc8 · RED (V18 vacía: 4 fallos) → GREEN 6/6
- [x] T2 · S1 S2 · inline · Backend C0: V19 `sesiones_validas_desde`, revalidación por `iat`, listado y desactivar/reactivar, filtro `familiaId` de invitaciones, auditoría, bandera, pruebas · commit 4542320 · mutación: sin la comparación por `iat` fallan 2 pruebas (token viejo 200)
- [x] T3 · S1 S2 S4 · inline · Frontend C0: sección «Cuentas de acceso» en el detalle de familia, Vitest/FakeBackend, E2E real · commit 1be1341 (+ df1123c)
- [x] T4 · S5 · inline · docs, suites completas, Gentle AI, commits, Engram, informe
- [x] T5 · S7 S8 · inline · Revocación definitiva por versión de sesión (V19 reescrita), carga documental en curso, login concurrente sin pérdida de actualización · commits 61f4757, 6b3bb87 · commits c8a7d7f, df1123c y este registro

Route: todo inline (una cadena dependiente: V18 → backend → frontend; sin unidades paralelas independientes ⇒ sin Writer trigger).

## Log
### L1
Pedido del usuario (2026-11-27): aprobación del enfoque de U21-BIS y autorización de implementar C0 localmente; ver `S1`–`S6`. Precisiones para cortes posteriores (no C0): edición de tutores sin alterar identidad verificada; contacto vs identificatorio; «Soy este tutor» es declaración sin permisos; exclusividad comprobada al escribir; coincidencia de DNI nunca autoriza; ADMIN aprovecha lo declarado.

### L2 — avance 2026-11-27
- V18 rompió dos pruebas que dependían de poder mover una cuenta de familia (`FamiliaPortalHttpDbTest` paso 4) y de que V17 fuera la última versión (`V16EscriturasVigenciasDbTest`): la primera ahora prueba que la base lo rechaza y que un token con otra familia da 401; la segunda se fija en V17.
- Rutas: `/api/admin/familias/{familiaId}/cuentas` (en lugar de `/usuarios` del diseño) para exigir familia y cuenta juntas. Se ampliaron a conciencia los inventarios de rutas, OpenAPI y la lista de migraciones.
- `VerificadorSesionVigente` gana `vigente(..., Instant emitidoEn)`; la variante sin emisión queda para la revalidación dentro de la carga documental (ya autenticada al empezar la solicitud). Riesgo aceptado: si desactivación, reactivación y emisión del token viejo caen en el mismo segundo, ese token sobrevive (resolución de `iat`).
- Suites backend sobre 4542320 (antes del frontend): normal 1536/1536, PostgreSQL 1074/1074. Frontend: typecheck y lint limpios; Vitest 76 archivos, 1701 pruebas (1 fallo esperado de `family-athletes` por los dos pedidos nuevos de la ficha; se amplió su lista permitida y pasa 25/25).

### L3 — cierre local 2026-11-27
- E2E real shard 1 sobre 1be1341: 143/143 (incluye `cuentas-familia.real.spec.ts`: la sesión abierta de la persona sin acceso muere, la del otro adulto sigue, el login queda bloqueado y devolver el acceso exige ingresar de nuevo).
- Gentle AI sobre ff9f5f2..c8a7d7f (1.783 líneas, riesgo high por item 2), 4 lentes con consentimiento: **aprobada, 0 bloqueantes, acknowledged**. Aplicadas en df1123c: `vigenteSinComprobarEmision` (nombre explícito para la garantía más débil), permiso antes que bandera, aviso de invitaciones recortadas. No aplicadas: unificar la consulta JPQL de invitaciones (riesgo de tocar el listado existente; queda como mejora) y la ventana de un segundo del `iat` (riesgo aceptado y fijado por prueba).
- Suites finales sobre df1123c: backend normal 1536/1536; Vitest 76 archivos 1702/1702; typecheck y lint limpios; PostgreSQL 1074 con 2 errores en `V5OperacionDbTest` (`CannotGetJdbcConnection`), que también falla aislada sobre el árbol de `main` 71e8c58 (otro método): deuda abierta de Docker/WSL2, no regresión. Sobre 4542320 la suite PostgreSQL había dado 1074/1074.
- Pendiente: autorización de publicación (push + PR). Alcance futuro sin tocar: C1–C5, U22.

### L4 — corrección de la revocación (2026-11-27)
Pedido del usuario: ver S7 y S8 (C0 aprobado funcionalmente, publicación NO autorizada; resolver la ventana de un segundo).
- RED primero (las tres sobre 70033fa): (1) un token anterior con `iat` en el segundo de la reactivación respondía 200; (2) una carga que transfería mientras ADMIN quitaba y devolvía el acceso publicaba (201); (3) **defecto adicional encontrado**: un login concurrente con la desactivación la deshacía, porque Hibernate reescribía `activo = true` al guardar el último acceso (pérdida de actualización, orden forzado con `pg_blocking_pids`).
- Solución: V19 reescrita (no publicada): `usuario.version_sesion` (entero, solo crece, trigger `USUARIO_VERSION_SESION_NO_RETROCEDE`). Claim `version_sesion` en todo JWT; la revalidación exige igualdad. Quitar el acceso la incrementa en la misma sentencia que `activo = false`; devolverlo no la toca. Login: versión leída con `SELECT … WHERE activo FOR NO KEY UPDATE` en su transacción (no `FOR SHARE`: la revisión nativa detectó deadlock entre dos logins simultáneos, reproducido y corregido) (no se mapea en la entidad: las pruebas que arrancan la app real sobre esquemas V15/V16 siguen validando el mapeo de Hibernate); MFA renovado y sesión completa heredan la del token presentado. Sin claim = 0 (backend anterior). `Usuario.activo` y `version_sesion` con `updatable = false`. Se elimina `vigenteSinComprobarEmision`: `ContextoCarga` lleva la versión del token de su solicitud y la exige bajo el `FOR SHARE` de la cuenta en cada paso.
- Pendiente fuera de C0 (preexistente): desactivar y reactivar una FAMILIA o una ESCUELA revive los tokens de sus cuentas (no incrementa versiones). Se informa; no se corrige sin autorización.

### L5 — cierre de la corrección (2026-11-27)
- Revisión nativa de Gentle AI sobre 70033fa..61f4757 (4 lentes, consentimiento por candidato): hallazgo bloqueante corroborado por el refutador, `R4-login-share-lock-deadlock` (CRITICAL): con `FOR SHARE` dos logins simultáneos de la misma cuenta se bloqueaban mutuamente al escribir el último acceso (40P01). Reproducido con una prueba determinista que usa la sentencia exacta del repositorio (RED: `deadlock detected`), corregido con `FOR NO KEY UPDATE` (6b3bb87, 80 líneas, plan de corrección nativo), validación dirigida **approved**, acknowledged (autoridad quemada). Los demás hallazgos no bloqueantes no se extrajeron antes del acknowledge y sus artefactos se borraron al quemar la autoridad: no se pueden informar.
- La prueba concurrente borraba y recreaba la escuela vigente entre pruebas; `EscuelaActual` cachea su id: ahora limpia solo el contenido.
- Suites sobre 6b3bb87: backend normal 1541/1541, PostgreSQL 1083/1083, E2E real shard 1 143/143 (un intento previo no arrancó: PostgreSQL del arnés rechazó la conexión, infraestructura; un único reintento). Vitest sin cambios de frontend desde 1702/1702.
- Pendiente fuera de C0 (preexistente): desactivar y reactivar una FAMILIA o una ESCUELA revive los tokens de sus cuentas. Publicación de C0 pendiente de autorización.
