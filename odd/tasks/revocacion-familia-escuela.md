# Revocación de sesiones al desactivar una familia o una escuela
Rama `fix/revocacion-familia-escuela` desde `main` 8410bfc (worktree `~/worktrees/banfield-revocacion`). Solo commits locales: sin push/PR/merge/deploy hasta autorización; sin Supabase; sin migraciones históricas; sin C1–C5 ni U22.
Runner: Maven (`./mvnw`, `-Pdb-tests` PostgreSQL 17). Modelo previsto: claude-opus-5-5, effort High (la sesión se ejecutó con el modelo vigente en ese momento).

## Specs
- S1: «Desactivar una familia invalida las sesiones de todas sus cuentas afectadas. Desactivar una escuela invalida las sesiones de todas sus cuentas afectadas. Reactivar una familia o escuela no recupera la validez de ningún token anterior.»
- S2: «Mantener el estado individual de cada cuenta. Una cuenta desactivada individualmente no se reactiva al reactivar su familia o escuela. No afectar cuentas de otras familias o escuelas fuera del alcance. No desactivar globalmente un deportista compartido por desactivar una de sus familias. Las operaciones sin cambios no deben incrementar versiones ni duplicar auditorías.»
- S3: «Reutilizar el mecanismo version_sesion de C0, verificando previamente su compatibilidad con los caminos reales de desactivación. Evaluar atomicidad, orden de bloqueos, concurrencia entre desactivaciones individuales y colectivas, login y MFA concurrentes, creación de cuentas y aceptación de invitaciones concurrentes, operaciones documentales en curso, reactivaciones simultáneas, roles efectivos del runtime. No asumir que incrementar masivamente las versiones es suficiente sin comprobar las carreras correspondientes.»
- S4: «Reproducir los defectos actuales antes de corregirlos. Gentle AI y conservar los hallazgos y sus resoluciones antes de reconocer la revisión. Esperas acotadas.» «No publicar el nuevo PR sin autorización posterior.»

## Caminos reales de desactivación (verificados)
- Familia: `FamiliaAdminService.desactivar` (`POST /api/admin/familias/{id}/desactivar`), con revisión optimista JPA y cierre de vigencias de tutores. Único camino de la aplicación.
- Escuela: **no existe camino en la aplicación**; solo SQL de mantenimiento con el rol propietario (el runtime solo tiene `UPDATE(actualizado_en)` sobre `escuela`).
- Cuenta individual: `CuentasFamiliaAdminService` (C0). Deportista: la baja de una familia no lo toca.

## Decisión
Triggers en la base (V20) sobre `familia.activa` y `escuela.activa` (solo en la transición activa → inactiva) que incrementan `usuario.version_sesion` de las cuentas del alcance en la misma transacción: cubren el servicio, el SQL manual y las pruebas, y son atómicos con el cambio de estado y con la auditoría. Descartado incrementar en el servicio: la escuela no tiene servicio y un camino nuevo olvidaría la invalidación.
Carreras comprobadas (no se asumió que bastara el incremento masivo): (1) la baja de familia por el servicio toma las cuentas en orden de id antes de la familia, el orden de la carga documental (si no, deadlock con una carga en curso); (2) una cuenta creada tras la foto de la baja no recibe incremento: el login ahora toma escuela y familia `FOR SHARE` (orden escuela → cuenta → familia) y rechaza si dejaron de estar activas, así que no emite una sesión que sobreviva; (3) login/MFA/carga ya comparaban la versión (C0).

## Tasks
- [x] T1 · S1 S2 S3 · inline · V20 + orden de bloqueos del servicio + bloqueos del login · RED (familia y escuela revivían tokens) → GREEN
- [x] T2 · S1 S2 S4 · inline · pruebas deterministas, adaptación de las 8 pruebas que asumían la reviviscencia, carga documental en curso, runtime real
- [x] T3 · S4 · inline · suites completas, Gentle AI (hallazgos guardados antes del acknowledge), commits · 99f17b1, 7431349 y siguientes

Route: todo inline.

## Log
### L1
Pedido del usuario (2026-11-27): ver S1–S4 (continuación automática tras integrar C0, merge 8410bfc, CI posterior 8/8).
### L2
RED sobre 8410bfc: familia y escuela reactivadas devolvían 200 con el token anterior; el login de una cuenta nueva durante la baja no esperaba a la familia. Hallazgos al implementar: (a) la baja por SQL toma familia → cuentas, el reverso de la carga (documentado, no hay camino de aplicación así); (b) ocho pruebas existentes afirmaban que reactivar revivía el token anterior (contrato derogado a propósito): se actualizaron a «no revive; una sesión nueva funciona»; (c) la escuela compartida entre pruebas debe partir activa.

### L3 — revisión y cierre (2026-11-27)
Gentle AI (4 lentes, consentimiento por candidato, 8410bfc..7431349): **aprobada, 0 bloqueantes**, hallazgos guardados en Engram (`revocacion-familia-escuela-revision`) antes del acknowledge, que se hizo después (autoridad quemada). Resolución: Javadoc huérfano de `EscuelaRepository` corregido (commit de solo comentarios); duplicación del helper de tokens en pruebas, no aplicada (alcance), y la variante `orElse(0L)` es intencional (la prueba borra la cuenta). Suites sobre 7431349: backend normal 1541/1541; PostgreSQL con 1092 pruebas, tras corregir dos efectos laterales de los helpers de prueba (un contador de sentencias y una cuenta borrada) pasaron las dos clases afectadas; la suite PostgreSQL completa se repite una vez sobre el HEAD final.
Pendiente: publicación (requiere autorización posterior).
