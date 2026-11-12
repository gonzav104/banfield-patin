# Frontend — Banfield Patín Carrera

React + TypeScript estricto + Vite + React Router + TanStack Query + Tailwind v4 + React Hook Form + Zod + Axios.
Stack y reglas: ver [`AGENTS.md`](./AGENTS.md). Estado actual: **FE-01 — autenticación y sesiones**
(login de familias, login ADMIN con MFA/TOTP, rutas protegidas, logout). No incluye deportistas, competencias,
rifas ni carga documental.

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

No hay registro público: las cuentas de familia se crean con una invitación emitida por un ADMIN
(`POST /api/admin/invitaciones` + `POST /api/auth/registro/invitacion`; ver `backend/scripts/smoke-familias.sh`
o el test E2E, que lo hace por API). El acceso ADMIN no está enlazado desde ninguna pantalla pública.

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
npx playwright install chromium # una vez
npm run test:e2e
```

El primer test enrola el TOTP del ADMIN por la UI y guarda el secreto en `e2e/.state.json` (ignorado por Git,
solo para bases descartables); luego crea una familia e invitación por la API real. **Si recreás la base, borrá
`e2e/.state.json`.** Dos escenarios (503 y sin conexión) se simulan con `page.route` y están rotulados como tales.
Las capturas quedan en `e2e/artifacts/` (ignorado por Git).

## Seguridad (cómo está resuelto)

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
- El bloqueo por intentos fallidos del backend responde igual que unas credenciales incorrectas: la UI no puede distinguirlo.
