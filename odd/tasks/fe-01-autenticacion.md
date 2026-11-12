# FE-01 — Autenticación y sesiones
Objetivo: frontend funcional contra el backend existente. Copia aislada de 2f5b9a2 en /tmp/banfield-fe01-kN8KcL; sin commits ni publicación.
Entrega local; estimación 1200–1800 líneas (sin dividir artificialmente). Runner: Vitest/Testing Library, tsc/Vite y Playwright; integración real separada de mocks.

## Specs
- S1: «Antes de modificar archivos»: leer instrucciones y documentación, revisar Git/worktrees, contratos y pruebas backend, ODD/Engram y servicios locales. «Si encontrás cambios locales de otra tarea, preservalos. Trabajá de manera aislada sin tocar los prototipos.»
- S2: «React + TypeScript estricto + Vite», «React Router», «Tailwind CSS y shadcn/ui», «TanStack Query», «React Hook Form + Zod», «Axios centralizado», «Vitest + Testing Library». Estructura modular sin dependencias innecesarias.
- S3: «Ruta: `/login`». Correo, contraseña, validaciones, errores/carga. «Integrá `POST /api/auth/login` y `GET /api/auth/me`». Redirigir a `/familia`; sin registro ni selección de roles.
- S4: «Ruta: `/admin/login`». «Utilizá `POST /api/auth/admin/login`». Enrolamiento, confirmación y verificación TOTP reales. «Una contraseña válida con MFA pendiente no debe permitir ingresar al panel administrativo.» Sin navegación pública administrativa.
- S5: «Cookies con `withCredentials`». CSRF antes de escrituras, encabezado correcto, renovación tras login/logout/MFA, restauración `/me`, logout real, 401/403/conexión. «Tratamiento diferenciado de 503 sin cerrar una sesión cuyo estado es desconocido». Protección según rol/MFA. «No guardes JWT ni secretos de autenticación en localStorage.»
- S6: «Creá las rutas protegidas `/familia` y `/admin` con layouts iniciales de calidad profesional». Bienvenida de identidad real y logout. «No inventes información de deportistas, competencias o familias.»
- S7: «verde, negro y blanco». Familia mobile-first y administración responsive. Claridad, velocidad, accesibilidad, foco visible; sin efectos de landing.
- S8: Pruebas de login correcto/incorrecto, ADMIN/MFA pendiente, roles, recarga, logout, errores/CSRF/formularios. «Probá el recorrido en navegador con Playwright cuando el entorno lo permita». Diferenciar mocks de integración real.
- S9: «No modificar `prototypes/`», «No modificar la landing», «Mantener deshabilitadas las funcionalidades de carga documental», «No alterar otras aplicaciones o servicios Docker», «No ejecutar revisiones nativas de riesgo alto sin mi consentimiento», «No hacer commits, push, PR ni merge sin autorización». Sin nuevos módulos ni cambios de autenticación backend sin incompatibilidad concreta.
- S10: Entregar resultados, instrucciones exactas frontend/backend, limitaciones y siguientes pasos.

## Tasks
- T1 S1/S9 — inline + exploración delegada previa: contratos backend verificados (AutenticacionController, MfaController, SeguridadConfig, DTOs); hecho; sin commit.
- T2 S2/S5/S8 — inline: `lib/http.ts`, `lib/api-error.ts`, `features/auth/{api,session}.ts`, tests de cliente (13) con backend simulado; hecho; sin commit.
- T3 S3/S4/S6/S7/S8 — inline: `/login`, `/admin/login`, `/admin/mfa`, guards, layouts, 31 tests de flujos; hecho; sin commit.
- T4 S2/S8/S10 — inline: lint/tsc/build, README, E2E Playwright real (8 tests) contra backend + PostgreSQL; hecho; sin commit.
- Ruta: todo inline (sin razón de writer: una sola unidad de comportamiento acoplada). Evidencia: 45 Vitest, tsc, eslint, vite build y 8 E2E reales observados.
- Risk: item 2 (seguridad: guards/sesión cliente; el backend sigue autorizando) e item 5 (dependencias nuevas). Verificador independiente y revisión nativa pendientes de consentimiento del usuario.

## Log
### L1 — Pedido original
Vamos a iniciar el frontend funcional de **Patín Carrera Banfield San Pedro**.

La landing queda en pausa. Ahora necesitamos implementar el acceso real al sistema, aprovechando el backend existente.

## Objetivo: FE-01 — Autenticación y sesiones

Implementá una primera unidad funcional y verificable que incluya:

1. Inicialización de `frontend/` con el stack definido en `frontend/AGENTS.md`.
2. Login de familias en `/login`.
3. Login administrativo en `/admin/login`.
4. Flujo MFA/TOTP del administrador, incluido el enrolamiento inicial.
5. Restauración de sesión mediante `/api/auth/me`.
6. Rutas protegidas `/familia` y `/admin`, con layouts iniciales.
7. Cierre de sesión funcional.
8. Manejo correcto de errores, estados de carga y permisos.
9. Pruebas automatizadas de los flujos principales.

### Inspección previa

Antes de implementar, revisá: `AGENTS.md` y `frontend/AGENTS.md`; `docs/REQUERIMIENTOS.md`; `docs/DECISIONES.md`; `docs/SEGURIDAD.md`; controladores, DTO y pruebas de autenticación del backend; estado de Git, worktrees y cambios locales. No inventes endpoints ni contratos.

### Arquitectura

Respetá el stack acordado: React, TypeScript estricto, Vite, React Router, TanStack Query, Tailwind, shadcn/ui, React Hook Form, Zod, cliente HTTP centralizado, Vitest y Testing Library. Mantené una estructura modular, sencilla y extensible.

### Seguridad

El backend ya implementa autenticación propia con JWT en cookies HttpOnly, CSRF y MFA obligatorio para ADMIN. Integrate con esa implementación. No agregues Supabase Auth ni otro sistema de autenticación.

Requisitos obligatorios: Cookies y `withCredentials`. Obtención y envío correcto del token CSRF. Renovación del CSRF después de los cambios de sesión. Protección de rutas según rol y estado MFA. Manejo diferenciado de 401, 403 y 503. Nunca almacenar JWT ni secretos en localStorage. No permitir registro público ni selección de roles. No exponer el acceso ADMIN en la navegación pública. No alteres las reglas de seguridad del backend para facilitar las pruebas.

### Diseño

Interfaces profesionales, modernas, sobrias y accesibles. El portal familiar debe priorizar dispositivos móviles. El administrativo debe funcionar bien en escritorio y adaptarse a otros tamaños. Identidad de Banfield: verde, blanco y negro. No copies las animaciones de la landing. Acá importan la claridad, los formularios bien diseñados y la facilidad de uso.

### Alcance

No implementes todavía deportistas, competencias, rifas ni carga documental. Los layouts `/familia` y `/admin` pueden ser mínimos, pero la identidad autenticada, navegación, protección de rutas y logout deben funcionar realmente.

### Verificación

Ejecutá build, lint y pruebas disponibles. Probá los recorridos en navegador con Playwright cuando el entorno lo permita. Diferenciá claramente las pruebas con mocks de las verificaciones reales contra el backend y PostgreSQL. No des por operativo un flujo que no pudiste comprobar.

### Forma de trabajo

Seguí ODD y utilizá Engram. No actives Agent Teams por defecto. No invoques skills redundantes. Trabajá de forma aislada y preservá los cambios existentes. No modifiques `prototypes/` ni la landing. No alteres servicios Docker de otros proyectos. Mantené deshabilitada la carga documental. No ejecutes revisiones de riesgo alto sin mi consentimiento. No hagas commits, push, PR ni merge sin autorización. Podés avanzar autónomamente dentro de FE-01, sin detenerte para aprobar cada implementación menor.

### Entrega esperada

Frontend inicializado. Login FAMILIA funcional. Login ADMIN y MFA funcionales. Protección de rutas y restauración de sesión. Logout. Resultados de las pruebas. Instrucciones exactas para ejecutar y probar el sistema localmente. Limitaciones reales y próximos pasos.

**Prioridad absoluta: integración correcta con el backend, seguridad, código mantenible y una experiencia de usuario profesional.**

(Nota: transcripción fiel del pedido salvo que los listados breves de «Inspección previa», «Seguridad», «Forma de trabajo» y «Entrega» se unieron en párrafos; el texto de cada ítem es el original.)

### L2 — 2026-11-12
Usuario: «autorizo» adaptar delegación a spawn_agent/wait_agent sin list_agents. Copia aislada desde HEAD local, sin modificar cambios ajenos. Inspección verificó DTO login: password no vacía, máximo 200 (no aplicar política de registro). Código MFA inválido no cierra sesión. CSRF desde cookie XSRF-TOKEN, no asumir token del body. GET csrf antes de cada escritura evita caché obsoleta; no reintentar escrituras por fallos ambiguos.
### L3 — 2026-11-12 (implementación)
- Hallazgo real contra el backend: el POST desde el dev server fallaba (403 CORS) porque el proxy de Vite reescribía `Host` (`changeOrigin: true`) y Spring trataba el `Origin` como cross-origin (dev solo permite `localhost:5173`). Solución: `changeOrigin: false` (mismo origen, como un reverse proxy de producción). No se tocó el backend.
- Hardening propio: las `variables` de mutaciones (contraseña, TOTP, secreto) se retenían 5 min en memoria → `gcTime: 0` + `enroll.reset()`; test agregado.
- `e2e/` no estaba en `tsconfig`: incluido.
- TOTP no reutilizable en la misma ventana de 30 s: el E2E espera al siguiente paso.
- Verificación: `npx vitest run` 45/45; `tsc --noEmit`, `eslint .`, `vite build` OK; `playwright test` 8/8 contra backend (Spring, PostgreSQL 17 en contenedor `banfield-fe01-postgres`, puerto 55439, propio de FE-01).
