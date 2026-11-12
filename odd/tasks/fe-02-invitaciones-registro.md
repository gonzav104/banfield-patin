# FE-02 — Invitaciones y registro de familias
Objetivo: flujo completo ADMIN (listar/crear/copiar enlace/revocar invitaciones) + registro FAMILIA por enlace, sobre la auth de FE-01. Rama actual `test/carga-documental-ci` (cambios ajenos sin commitear preservados); sin commits ni publicación.
Runner: Vitest/Testing Library (backend simulado), Playwright real contra Spring + PostgreSQL. Entrega local.

## Specs
- S1: «No implementar registro público.» «No permitir seleccionar el rol.» «No cambiar la autenticación de FE-01.» «Reutilizar el cliente HTTP y la gestión CSRF existentes.»
- S2: Panel ADMIN: «Listar invitaciones y visualizar sus estados.» «Crear invitaciones para familias existentes o nuevas, según el contrato del backend.» «Mostrar el enlace de invitación para copiarlo y compartirlo.» «Revocar invitaciones cuando corresponda.» «Manejar estados de carga, validaciones y errores.»
- S3: «Mostrar el enlace de invitación al ADMIN en el momento de su creación, sin inventar una opción para recuperarlo posteriormente si el backend no la permite.»
- S4: Registro FAMILIA: «Abrir el enlace generado por el backend.» «Validar la invitación.» «Mostrar el formulario de nombre, apellido, correo y contraseña.» «Completar el registro utilizando el endpoint existente.»
- S5: «Informar claramente si una invitación es inválida, venció, fue revocada o ya se utilizó, respetando los mensajes y las restricciones de seguridad del backend.» (Conflicto: el backend responde siempre 400 INVITACION_NO_DISPONIBLE, por diseño; ver Log L2.)
- S6: «Redirigir al login después del registro exitoso, sin iniciar sesión automáticamente.»
- S7: «No almacenar tokens de invitación en localStorage ni registrarlos en logs.» «No modificar el backend salvo que exista una incompatibilidad demostrada y autorizada.»
- S8: «Diseño profesional, sencillo y mobile-first.» Pruebas: «invitación válida, inválida, vencida, revocada y utilizada; registro correcto, errores, permisos ADMIN y navegación.» «Probá el flujo completo contra el backend real cuando el entorno lo permita y diferenciá los resultados obtenidos con mocks.»
- S9: «No toques los prototipos, no habilites cargas documentales y no hagas commits, push, PR ni merge sin autorización.»

## Tasks
- T1 S1-S7 — inline: contratos backend verificados (InvitacionAdminController/Service, RegistroController, ValidarInvitacionService, DTOs, SEGURIDAD §7); hecho.
- T2 S2/S3 — inline: `features/invitations/*` + `/admin/invitaciones` (listado, filtro, paginación, crear nueva/existente, enlace único, revocar con confirmación); hecho; sin commit.
- T3 S4/S5/S6 — inline: `features/registration/*` + `/registro/invitacion/:token?`, `/registro`; token fuera de la URL, mensaje uniforme, redirección a /login sin sesión; hecho; sin commit.
- T4 S8 — inline: 44 tests nuevos (backend simulado) + `e2e/invitations.real.spec.ts` (7 reales); hecho.
- Ruta: inline (una sola unidad acoplada, sin razón de writer). Evidencia: 89 Vitest, tsc, eslint, vite build, 15 Playwright (8 FE-01 + 7 FE-02) contra Spring + PostgreSQL.
- Risk: item 2 (registro/guards en cliente; el backend autoriza) — verificador independiente y revisión nativa pendientes de consentimiento.

## Log
### L1 — Pedido original
FE-02 — Invitaciones y registro de familias: el pedido completo está en la conversación de la sesión (texto citado en S1–S9, verbatim). Pidió además: revisar Git y verificar que FE-01 siga funcionando; preservar cambios sin commitear sin mezclar tareas; inspeccionar contratos reales (AGENTS.md, frontend/AGENTS.md, docs/REQUERIMIENTOS.md, docs/SEGURIDAD.md); entregar resumen del flujo, pruebas, limitaciones e instrucciones de prueba manual.
### L2 — 2026-11-12 (contratos)
- Enlace: `{FRONTEND_URL_BASE}/registro/invitacion/{token}` (token en la RUTA); `enlaceRegistro` es null si FRONTEND_URL_BASE está vacío.
- Validar: `POST /api/auth/invitaciones/validar {token}` → `{valida, escuelaNombre, emailSugerido, expiraEn}`; cualquier no-utilizable → `400 INVITACION_NO_DISPONIBLE` uniforme (desconocida/vencida/revocada/usada/formato/escuela o familia inactiva), también el bloqueo por IP (sin 429). ⇒ la UI NO puede distinguir las causas; mensaje único.
- Registrar: `POST /api/auth/registro/invitacion {token,nombre,apellido,email,password}` → 201 sin cookie; 409 EMAIL_YA_REGISTRADO; contraseña ≥10 caracteres y ≤72 bytes UTF-8; 400 INVITACION_NO_DISPONIBLE.
- Admin: `GET /api/admin/invitaciones?estado&page&size` → `{contenido,pagina,tamanio,totalElementos}`; `POST` `{familiaId | nuevaFamilia:{nombreReferencia}, emailSugerido?, diasVigencia?}` (propiedades desconocidas rechazadas) → 201 con `token` y `enlaceRegistro` una sola vez; `POST /{id}/revocar` (USADA → 409 INVITACION_NO_REVOCABLE; REVOCADA idempotente); `GET /api/admin/familias?busqueda` para elegir familia existente.
### L3 — 2026-11-12 (implementación)
- Conflicto S5 vs backend: no se pueden distinguir inválida/vencida/revocada/usada (400 INVITACION_NO_DISPONIBLE uniforme, a propósito). La UI muestra un único mensaje que enumera las causas posibles; no se modificó el backend.
- Token en la ruta del enlace: se lee a memoria y se quita de la barra con `navigate(replace)`; `<meta name="referrer" content="no-referrer">`; validación con `useQuery` (gcTime 0, una sola llamada aunque StrictMode monte dos veces); POST de validar/registrar lleva el token en el cuerpo.
- El enlace sale de `enlaceRegistro` (FRONTEND_URL_BASE); si es null se arma con `window.location.origin` y se avisa.
- Real: el limitador del backend cuenta validaciones fallidas por IP (5/15 min, en memoria): reiniciar backend entre corridas E2E.
- Verificación: `vitest` 89/89; `tsc`, `eslint`, `vite build` OK; `playwright` 8/8 FE-01 + 7/7 FE-02 reales (vencimiento forzado por SQL en contenedor descartable).
