# Frontend — Banfield Patín Carrera

React + TypeScript estricto + Vite + React Router + TanStack Query + Tailwind v4 + React Hook Form + Zod + Axios.
Stack y reglas: ver [`AGENTS.md`](./AGENTS.md). Estado actual: **FE-01 — autenticación y sesiones**
(login de familias, login ADMIN con MFA/TOTP, rutas protegidas, logout) y **FE-02 — invitaciones y registro de familias**
(panel ADMIN de invitaciones + registro por enlace). No incluye deportistas, competencias, rifas ni carga documental.

## Ejecutar localmente

Requisitos: Node 22+ (probado con 24), Java 21 y una base PostgreSQL vacía para el backend.

### 1. Backend

Desde `backend/`, con estas variables de entorno (valores de ejemplo; **generá los tuyos**, no los versiones):

```bash
export SPRING_PROFILES_ACTIVE=dev
export DB_URL=jdbc:postgresql://127.0.0.1:5432/NOMBRE_DB
export DB_USERNAME=...
export DB_PASSWORD=...
export JWT_SECRET="$(openssl rand -base64 48)"
export MFA_CLAVE_CIFRADO="$(openssl rand -base64 32)"
export AUTH_COOKIE_SECURE=false            # solo desarrollo local (http)
export BOOTSTRAP_ADMIN_HABILITADO=true     # crea el primer ADMIN al arrancar (idempotente)
export BOOTSTRAP_ADMIN_EMAIL=admin@example.test
export BOOTSTRAP_ADMIN_NOMBRE=Admin
export BOOTSTRAP_ADMIN_APELLIDO=Pruebas
export BOOTSTRAP_ADMIN_PASSWORD="elegí-una-de-10+-caracteres"
export FRONTEND_URL_BASE=http://127.0.0.1:5173   # el backend arma enlaceRegistro = {base}/registro/invitacion#{token}
./mvnw spring-boot:run
```

La carga documental y el storage quedan **deshabilitados** por defecto (`DOCUMENTOS_CARGA_HABILITADA=false`).
Flyway crea el esquema en el primer arranque.

### 2. Frontend

```bash
cd frontend
npm install
npm run dev        # http://127.0.0.1:5173  (proxy de /api -> http://127.0.0.1:8080)
```

Para apuntar a otro backend: `BACKEND_URL=http://host:puerto npm run dev`.

### 3. Recorrido manual

| Quién | URL | Qué esperar |
| --- | --- | --- |
| Administrador (primera vez) | `/admin/login` | contraseña → pantalla de enrolamiento: escanear el QR (o cargar la clave) en una app autenticadora → código de 6 dígitos → `/admin` |
| Administrador (siguientes) | `/admin/login` | contraseña → código de 6 dígitos → `/admin` |
| Familia | `/login` | correo + contraseña → `/familia` |

No hay registro público: las cuentas de familia se crean con una invitación emitida por un ADMIN (FE-02, abajo).
El acceso ADMIN no está enlazado desde ninguna pantalla pública.

### 4. Invitaciones y registro de familias (FE-02)

1. Ingresá como ADMIN (paso 3) y abrí **Invitaciones** en el menú (`/admin/invitaciones`).
2. **Nueva invitación**: elegí *Familia nueva* (nombre de referencia) o *Familia existente* (búsqueda), y opcionalmente un
   correo sugerido y los días de vigencia (vacío = valor por defecto del servidor).
3. Se muestra el **enlace una sola vez**: copialo con *Copiar enlace*. El backend no permite recuperarlo después; si se
   pierde, revocá la invitación y creá otra. Si el backend no tiene `FRONTEND_URL_BASE`, el enlace se arma con la
   dirección actual de la página (y se avisa).
4. Abrí el enlace en otro navegador o ventana privada: `/registro/invitacion#{token}`. Se valida la invitación, se
   completa nombre, apellido, correo y contraseña (≥ 10 caracteres) y se redirige a `/login` **sin iniciar sesión**.
   Los enlaces del formato anterior (`/registro/invitacion/{token}`) todavía se aceptan temporalmente; ver el riesgo
   residual en `docs/SEGURIDAD.md` §7.
5. En el listado podés filtrar por estado (Pendiente, Usada, Revocada, Vencida) y **revocar** invitaciones pendientes.

El backend responde siempre el mismo `400 INVITACION_NO_DISPONIBLE` para una invitación inexistente, vencida, revocada
o ya usada (decisión de seguridad, `docs/SEGURIDAD.md` §7), por lo que el registro muestra un único mensaje: "Esta
invitación no está disponible". El token va en el fragmento del enlace (el navegador no lo envía al servidor), viaja
solo en el cuerpo de las peticiones, se quita de la barra de direcciones al abrir el enlace y nunca se guarda en el navegador.

## Pruebas

```bash
npm run typecheck   # tsc --noEmit (incluye src/ y e2e/)
npm run lint        # eslint
npm test            # Vitest + Testing Library — con backend SIMULADO (ver abajo)
npm run build       # tsc + vite build
npm run test:e2e    # Playwright — contra backend + PostgreSQL REALES
```

**Pruebas con mocks (`npm test`)**: el cliente HTTP real corre sobre un adaptador de axios que simula al backend
(`src/test/fakeBackend.ts`) con las reglas del contrato (CSRF por cookie/encabezado, cookie de sesión, MFA pendiente,
401/403/409/503). Verifican cliente, guards, formularios y pantallas, **no** al servidor real.

**Pruebas E2E reales (`npm run test:e2e`)**: requieren el backend corriendo (paso 1) y

```bash
export E2E_ADMIN_EMAIL=...      # el BOOTSTRAP_ADMIN_EMAIL
export E2E_ADMIN_PASSWORD=...   # el BOOTSTRAP_ADMIN_PASSWORD
export E2E_DB_CONTAINER=...     # opcional: contenedor Docker PostgreSQL DESCARTABLE, para forzar el vencimiento por SQL
export E2E_DB_NAME=...          # opcional: base dentro de ese contenedor (por defecto, su POSTGRES_DB)
npx playwright install chromium # una vez
npm run test:e2e
```

El primer test enrola el TOTP del ADMIN por la UI y guarda el secreto en `e2e/.state.json` (ignorado por Git,
solo para bases descartables); luego crea una familia e invitación por la API real. **Si recreás la base, borrá
`e2e/.state.json`.** Dos escenarios (503 y sin conexión) se simulan con `page.route` y están rotulados como tales.
`invitations.real.spec.ts` recorre el ciclo completo contra el backend real: crear invitación → enlace único → registro →
no hay sesión automática → login; enlace usado, revocado y vencido (este último requiere `E2E_DB_CONTAINER`); permisos
de FAMILIA/anónimo sobre la API ADMIN. El backend limita por IP las validaciones **fallidas** (5 cada 15 min, en memoria):
esa spec provoca 3, así que **reiniciá el backend (o esperá 15 minutos) entre corridas**.
Las capturas quedan en `e2e/artifacts/` (ignorado por Git).

**Artefactos sin secretos (H-02):** Playwright no guarda trazas, videos ni capturas automáticas (registran cuerpos de
peticiones y el texto de la página). Las capturas de evidencia enmascaran el QR, el secreto TOTP y los enlaces de
invitación. Al terminar, el teardown redacta y verifica los artefactos de texto (`e2e/support/artifact-check.ts`) y
`npm run test:e2e:check-artifacts` repite la verificación. `e2e/.state.json` sí contiene el secreto TOTP y contraseñas de
prueba (necesarios entre corridas): es local, ignorado por Git, con permisos 600, y debe borrarse con la base descartable.

## Seguridad (cómo está resuelto)

- Sesión entre pestañas: el logout en una pestaña cierra la sesión en las demás, y un login en otra pestaña hace que
  esta limpie sus datos y revalide la identidad (`BroadcastChannel`, solo el tipo de evento, sin datos).
- Un 401 sin código en una petición autenticada (por ejemplo, de un proxy) revalida `/api/auth/me` en lugar de cerrar la
  sesión; un 503 o la falta de conexión nunca la cierran. Al iniciar sesión (o completar el MFA) se descarta todo dato
  privado cacheado de la identidad anterior.

- Sesión en cookie `HttpOnly` emitida por el backend; el JS nunca ve el JWT. Nada sensible en `localStorage`/`sessionStorage`.
- CSRF: antes de **cada** escritura se pide `GET /api/auth/csrf`, se lee la cookie `XSRF-TOKEN` y se envía en
  `X-XSRF-TOKEN`. No se cachea (login, logout y MFA descartan el token anterior en el servidor) y las escrituras
  nunca se reintentan automáticamente. La cookie solo es legible si SPA y API comparten origen: en desarrollo lo
  garantiza el proxy de Vite (`changeOrigin: false`); en producción debe haber un reverse proxy o el mismo dominio.
- ADMIN con solo contraseña queda con sesión de MFA pendiente: `/admin` y `/familia` lo envían a `/admin/mfa`.
- 401 `NO_AUTENTICADO` en cualquier request → se descarta la sesión y la caché; 503 o falta de conexión **no** cierran
  la sesión (estado desconocido): se conserva la identidad conocida y se avisa.
- Las variables de las mutaciones (contraseña, código TOTP, secreto de enrolamiento) no se retienen en la caché (`gcTime: 0`).
- La autorización real siempre la decide el backend; los guards del frontend solo ordenan la navegación.

## Limitaciones conocidas

- La landing no vive en esta app: `/` redirige a `/login`.
- El logo es una marca provisional (SVG); falta integrar el recurso oficial.
- E2E solo en Chromium (escritorio); móvil verificado por emulación de viewport, no en dispositivos reales.
- No se puede distinguir en pantalla *por qué* una invitación no está disponible (vencida, revocada, usada, inválida): el backend lo oculta a propósito.
- El enlace de invitación solo existe en la respuesta de creación; no hay forma de recuperarlo luego.
- El bloqueo por intentos fallidos del backend responde igual que unas credenciales incorrectas: la UI no puede distinguirlo.
