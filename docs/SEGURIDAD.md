# Seguridad — Banfield Patín Carrera

Estrategia de autenticación y control de acceso implementada en el backend (`backend/`, Spring Boot 4 / Spring Security 7).
Este documento describe lo que el código hace hoy; los puntos abiertos están en la sección 12.

## 1. Resumen de la estrategia

- Roles: `ADMIN` y `FAMILIA`. Sin Supabase Auth: la autenticación es propia del backend.
- Sesión: un único JWT HS256 emitido por el backend, entregado **solo** en una cookie `HttpOnly`. Sin refresh token.
  El token nunca viaja en el cuerpo de una respuesta ni se guarda en `localStorage`.
- Protección CSRF activa para todo `POST/PUT/PATCH/DELETE`, incluidos login y registro.
- Las cuentas ADMIN no se crean por HTTP: solo con el bootstrap por entorno (sección 8).
- Las cuentas FAMILIA solo se crean consumiendo una invitación emitida por un ADMIN (sección 7). No hay registro libre.
- La escuela nunca la envía el cliente: sale de la configuración (login), de la invitación (registro) o del JWT (resto).

## 2. JWT y cookie de sesión

| Aspecto | Valor |
|---|---|
| Algoritmo | HS256 con un secreto compartido (`JWT_SECRET`, base64, mínimo 32 bytes decodificados) |
| Claims | `iss`, `sub` (id de usuario), `iat`, `exp`, `jti`, `rol`, `escuela_id` y `familia_id` (solo FAMILIA). Sin email ni datos personales |
| Duración | `JWT_DURACION`, por defecto `PT8H`; se valida entre `PT5M` y `PT24H` |
| Cookie | Nombre `BP_SESION` (configurable), `HttpOnly`, `Path=/`, `Max-Age` igual a la duración del JWT |
| `Secure` | `AUTH_COOKIE_SECURE`; el valor por defecto de la aplicación es `true`. Solo el perfil `dev` y los ejemplos locales lo desactivan |
| `SameSite` | `AUTH_COOKIE_SAME_SITE`: `Lax` por defecto; `Strict` o `None`. `None` exige `Secure=true` (si no, la aplicación no arranca) |
| Lectura | Solo desde la cookie. Un `Authorization: Bearer` se ignora. Las rutas públicas de autenticación ignoran la cookie para que una cookie vencida no impida iniciar sesión |

Un token vencido, manipulado, firmado con otra clave, con `alg=none` o con un claim faltante produce `401`.

## 3. CSRF, SameSite y CORS

- CSRF con el soporte SPA de Spring Security: cookie `XSRF-TOKEN` legible por el frontend y header `X-XSRF-TOKEN`.
  El frontend obtiene la cookie con `GET /api/auth/csrf` antes del primer `POST`. Un `POST` sin token válido recibe `403 CSRF_INVALIDO`.
- Tras iniciar o cerrar sesión se descarta el token CSRF anterior; el frontend vuelve a pedir `/api/auth/csrf`.
- `SameSite=Lax` es la postura por defecto y es suficiente si el frontend y la API comparten sitio. Si se despliegan en sitios
  distintos hará falta `SameSite=None; Secure` y CORS explícito; CSRF sigue siendo la defensa principal en ese caso.
- Despliegue preferido: mismo origen detrás de un reverse proxy. Entonces `CORS_ORIGENES_PERMITIDOS` queda vacío y CORS
  permanece desactivado.
- Si se configura CORS: orígenes explícitos (nunca comodín), `allowCredentials=true`, métodos `GET/POST/PUT/PATCH/DELETE`
  y solo los headers `Content-Type` y `X-XSRF-TOKEN`.

## 4. Matriz 401 / 403

| Situación | Respuesta |
|---|---|
| Sin sesión (o sesión inválida) en una ruta privada | `401 NO_AUTENTICADO` |
| Autenticado con el rol equivocado | `403 ACCESO_DENEGADO` |
| Falta o no coincide el token CSRF | `403 CSRF_INVALIDO` |
| `/api/admin/**` | solo `ADMIN` |
| `/api/familia/**` | solo `FAMILIA` |
| `/api/auth/me`, `/api/auth/logout` | autenticado |
| `/api/auth/csrf`, `login`, `admin/login`, `invitaciones/validar`, `registro/invitacion` | públicas (las de `POST` siguen exigiendo CSRF) |
| Cualquier otra ruta | denegada por defecto (`401` si es anónimo) |

Todos los errores son JSON `{codigo, mensaje, detalles}`; nunca HTML ni trazas de pila.

## 5. Login y protección contra enumeración

- `POST /api/auth/login` autentica solo `FAMILIA`; `POST /api/auth/admin/login` autentica solo `ADMIN`. La ruta de ADMIN no
  se publica ni se enlaza desde la interfaz pública.
- Email y contraseña incorrectos, usuario inexistente, rol equivocado para esa ruta, usuario/familia/escuela inactivos y
  bloqueo por intentos devuelven **la misma** respuesta `401 CREDENCIALES_INVALIDAS`. El motivo real solo queda en auditoría.
- Cuando el email no existe (o la clave está bloqueada) se ejecuta igualmente una comparación contra un hash ficticio para
  igualar tiempos.
- Un `404`, `409` o mensaje distinto nunca revela si un email existe. La única excepción es el registro con invitación válida:
  un email repetido en esa escuela responde `409 EMAIL_YA_REGISTRADO`, y solo quien posee la invitación llega a ese punto.

### Limitación de intentos (en memoria)

- Clave `ip|email`; por defecto 5 fallos en una ventana de 15 minutos bloquean la clave 15 minutos
  (`LOGIN_MAX_INTENTOS`, `LOGIN_VENTANA`, `LOGIN_BLOQUEO`). Un éxito reinicia el contador. Máximo 10.000 claves en memoria.
- Un intento bloqueado recibe la misma respuesta `401` que una credencial inválida, aunque la contraseña sea correcta. No hay `429`
  ni `Retry-After`, para no revelar el estado de la cuenta.
- El estado vive en la memoria de cada instancia: se pierde al reiniciar y no se comparte entre instancias. Es una mitigación,
  no un control de tipo WAF.
- `POST /api/auth/invitaciones/validar` usa su propia instancia del limitador, por IP; el bloqueo responde el error uniforme
  de invitación (`400 INVITACION_NO_DISPONIBLE`).
- La IP es `getRemoteAddr()`. Detrás de un proxy hay que configurar `server.forward-headers-strategy` para que sea la IP real;
  el backend no confía en un `X-Forwarded-For` crudo.

## 6. Contraseñas

- Hash con `DelegatingPasswordEncoder` (bcrypt por defecto, prefijo `{bcrypt}`); se re-hashea al iniciar sesión si el
  algoritmo queda desactualizado.
- Política: mínimo 10 caracteres, máximo 72 bytes UTF-8 (límite de bcrypt) y no vacía. Un valor que no cumple da `400` con el campo
  `password`, sin repetir el valor.
- Las contraseñas y los tokens no se escriben en logs, auditoría, errores ni respuestas.

## 7. Invitaciones de registro FAMILIA

Un ADMIN emite la invitación (a una familia existente o creando una familia nueva en la misma operación). La persona
responsable la consume en `POST /api/auth/registro/invitacion`.

- Token de 256 bits (`SecureRandom`, Base64 URL sin relleno). Solo se guarda su SHA-256; el texto en claro se devuelve **una sola vez**
  en la respuesta de creación (`Cache-Control: no-store`) y nunca aparece en listados, logs ni auditoría.
- El token viaja en el cuerpo (`POST`), nunca en la URL, para que no quede en logs de acceso.
- Vigencia por defecto 7 días, máximo 30 (`banfield.invitaciones.vigencia-por-defecto` / `vigencia-maxima`; también por variable
  de entorno con el nombre del *relaxed binding*, por ejemplo `BANFIELD_INVITACIONES_VIGENCIA_MAXIMA`).
- Estado derivado, no almacenado: `USADA` > `REVOCADA` > `EXPIRADA` > `PENDIENTE`. El ADMIN puede revocar una invitación pendiente
  o vencida; revocar una ya revocada es idempotente; una usada da `409 INVITACION_NO_REVOCABLE`.
- Una invitación desconocida, vencida, revocada, usada, con formato inválido o con escuela/familia inactivas produce siempre
  `400 INVITACION_NO_DISPONIBLE`, con el mismo cuerpo, tanto al validar como al registrar.
- Uso único y atómico: en una sola transacción se bloquea la fila de la invitación (`SELECT ... FOR UPDATE`), se inserta el usuario y
  luego un `UPDATE` condicional marca la invitación como usada y debe afectar exactamente una fila. Un email duplicado en la escuela
  responde `409` y revierte todo: la invitación sigue pendiente. La base lo respalda con un `CHECK` (`usado_en` y `usuario_id` van juntos).
- El rol `FAMILIA` lo fija el servidor; `familia_id` y `escuela_id` salen de la invitación. Campos `rol`, `escuelaId`, `familiaId` o
  `activo` enviados por el cliente se ignoran. El email sugerido en la invitación no se impone.
- El registro responde `201` **sin** iniciar sesión (sin cookie): la persona entra luego por `/api/auth/login`. No se verifica el correo
  en el MVP.
- Punto de extensión `VinculacionPorInvitacion` (sin efecto por defecto) para el vínculo con deportistas de RF-04; este cambio no
  escribe `familia_deportista` ni acepta `deportistaId`.
- La migración `V2__crear_invitacion.sql` es inmutable una vez aplicada (cualquier cambio posterior va en `V3`+). La migración `V1`
  está fijada por un test que compara su SHA-256.

## 8. Bootstrap del primer ADMIN

Ninguna ruta HTTP crea administradores. La primera cuenta se crea al arrancar con una variable de entorno explícita. El proceso es idempotente.

1. Definir en el entorno del backend (nunca en Git): `BOOTSTRAP_ADMIN_HABILITADO=true`, `BOOTSTRAP_ADMIN_EMAIL`,
   `BOOTSTRAP_ADMIN_NOMBRE`, `BOOTSTRAP_ADMIN_APELLIDO` y `BOOTSTRAP_ADMIN_PASSWORD` (cumple la política de la sección 6).
2. Arrancar la aplicación una vez. Si la configuración es inválida el arranque falla sin imprimir la contraseña.
3. Verificar el acceso en `/api/auth/admin/login`.
4. Volver a `BOOTSTRAP_ADMIN_HABILITADO=false` y **borrar** `BOOTSTRAP_ADMIN_PASSWORD` del entorno.

Comportamiento: si el email ya existe en la escuela no hace nada (no cambia la contraseña); si ya hay `max_administradores`
(2 en Banfield) administradores activos, no crea otro, avisa en el log y el arranque continúa. Registra el evento `ADMIN_BOOTSTRAP`.
Por defecto está desactivado y no corre en el perfil de pruebas.

## 9. Auditoría

Tabla `auditoria` (escritura por `JdbcClient`). Eventos: `LOGIN_EXITOSO`, `LOGIN_FALLIDO`, `LOGOUT`, `ADMIN_BOOTSTRAP`,
`FAMILIA_CREADA`, `INVITACION_CREADA`, `INVITACION_REVOCADA`, `REGISTRO_POR_INVITACION` y `REGISTRO_FALLIDO`.

- Los eventos de éxito comparten la transacción del cambio de negocio; los de fallo usan una transacción propia y su error
  de escritura se ignora, de modo que una falla de auditoría no cambia la respuesta genérica.
- `LOGOUT` es de mejor esfuerzo: si la auditoría falla, el cierre de sesión igualmente expira la cookie.
- El detalle nunca incluye contraseñas, tokens ni hashes (las claves con esas palabras se descartan). Los emails de intentos
  fallidos se guardan enmascarados; para cuentas desconocidas `usuario_id` queda nulo.

## 10. Variables de entorno

Los valores reales van solo en `backend/.env` (ignorado por Git) o en los secretos de la infraestructura. `backend/.env.example`
contiene únicamente valores ficticios.

| Variable | Obligatoria | Descripción |
|---|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | sí | Conexión a PostgreSQL (Supabase, pooler de sesión) |
| `JWT_SECRET` | sí, sin valor por defecto | Base64 de al menos 32 bytes (`openssl rand -base64 48`). Si falta, está en blanco, no es base64 o es corta, la aplicación no arranca |
| `JWT_DURACION` | no | Duración ISO-8601 de la sesión, `PT8H` por defecto |
| `AUTH_COOKIE_NOMBRE` | no | Nombre de la cookie de sesión, `BP_SESION` |
| `AUTH_COOKIE_SECURE` | no | `true` por defecto; `false` solo para desarrollo local sin HTTPS |
| `AUTH_COOKIE_SAME_SITE` | no | `Lax` (defecto), `Strict` o `None` (exige `Secure`) |
| `CORS_ORIGENES_PERMITIDOS` | no | Lista de orígenes separados por coma; vacío = CORS desactivado |
| `FRONTEND_URL_BASE` | no | Base para generar `enlaceRegistro` (`{base}/registro/invitacion/{token}`) |
| `ESCUELA_SLUG` | no | Escuela de esta instalación, `banfield-patin-san-pedro` |
| `LOGIN_MAX_INTENTOS`, `LOGIN_VENTANA`, `LOGIN_BLOQUEO` | no | Limitador de intentos: 5, `PT15M`, `PT15M` |
| `BOOTSTRAP_ADMIN_HABILITADO`, `BOOTSTRAP_ADMIN_EMAIL`, `BOOTSTRAP_ADMIN_NOMBRE`, `BOOTSTRAP_ADMIN_APELLIDO`, `BOOTSTRAP_ADMIN_PASSWORD` | no | Bootstrap del primer ADMIN (sección 8); desactivado por defecto |
| `DB_TEST_URL`, `DB_TEST_USER`, `DB_TEST_PASSWORD` | solo para pruebas `db` | Base PostgreSQL **local y descartable** (sección 11). No es la base de la aplicación |

## 11. Pruebas y convención de pruebas con base de datos

- `./mvnw clean verify` (desde `backend/`) no necesita base de datos ni Docker: no abre `DataSource`, no ejecuta Flyway y no usa
  `@SpringBootTest`. Incluye pruebas unitarias y *slices* `@WebMvcTest` con servicios simulados, entre ellas las matrices 401/403,
  CSRF, atributos de cookie y rol forzado.
- Testcontainers no está disponible en este entorno (no hay Docker). Convención adoptada: las pruebas que necesitan PostgreSQL real
  llevan la etiqueta JUnit `@Tag("db")` (a través de la anotación `@PruebaDb`), el `pom.xml` las excluye por defecto
  (`excludedGroups=db`) y solo corren con el perfil Maven `db-tests`:

  ```bash
  createdb banfield_test        # PostgreSQL local, base vacía y descartable (el nombre debe contener "test")
  DB_TEST_URL=jdbc:postgresql://localhost:5432/banfield_test \
  DB_TEST_USER=postgres DB_TEST_PASSWORD=... \
  ./mvnw verify -Pdb-tests
  dropdb banfield_test
  ```

- Salvaguardas: las pruebas `db` rechazan cualquier host que no sea `localhost`, `127.0.0.1` o `::1`, y cualquier base cuyo nombre no
  contenga `test`. **Nunca** se ejecutan contra Supabase ni contra una base compartida. Cubren: Flyway V1+V2, `ddl-auto=validate` de
  las entidades, restricciones de V2, consultas JPQL y consumo atómico concurrente de invitaciones.
- Un test sin base de datos verifica que el pom excluye el grupo `db` por defecto y que toda prueba que abre un contexto con
  base de datos esté etiquetada.

## 12. Riesgos aceptados y requisitos pendientes

- **MFA de ADMIN: bloqueante antes de producción (RNF-03).** No está implementado; hasta entonces las cuentas ADMIN solo tienen
  contraseña. Las cuentas se crean con `mfa_habilitado=false`.
- **Usuario desactivado conserva su JWT hasta su vencimiento (hasta 8 h por defecto): riesgo aceptado.** `GET /api/auth/me` vuelve a
  consultar la base y devuelve `401` si el usuario, su familia o su escuela están inactivos, y el login los rechaza; pero los demás
  endpoints confían en el JWT hasta que vence. Mitigar acortando `JWT_DURACION` o añadiendo después una revocación por `jti`
  o una verificación por solicitud.
- **Rotar `JWT_SECRET` cierra todas las sesiones** (todos los JWT emitidos dejan de validar). Es el procedimiento de emergencia.
- El limitador de intentos es por instancia y en memoria (sección 5).
- La IP real depende de la configuración del proxy (sección 5).
- **Revisión humana de la migración V2 antes de aplicarla en la base compartida (Supabase).** Hasta este momento V2 no se aplicó a
  ninguna base.
- Aún no existe auditoría consultable por ADMIN ni retención definida (RNF-05); este documento solo cubre los eventos de acceso e invitaciones.
