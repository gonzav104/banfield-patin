# Seguridad — Banfield Patín Carrera

Estrategia de autenticación y control de acceso implementada en el backend (`backend/`, Spring Boot 4 / Spring Security 7).
Este documento describe lo que el código hace hoy; los puntos abiertos están en la sección 13.

## 1. Resumen de la estrategia

- Roles: `ADMIN` y `FAMILIA`. Sin Supabase Auth: la autenticación es propia del backend.
- Sesión: un único JWT HS256 emitido por el backend, entregado **solo** en una cookie `HttpOnly`. Sin refresh token.
  El token nunca viaja en el cuerpo de una respuesta ni se guarda en `localStorage`.
- Protección CSRF activa para todo `POST/PUT/PATCH/DELETE`, incluidos login y registro.
- Las cuentas ADMIN no se crean por HTTP: solo con el bootstrap por entorno (sección 8).
- Las cuentas ADMIN exigen segundo factor TOTP (RNF-03, sección 9): la contraseña sola no abre una sesión de ADMIN.
- Las cuentas FAMILIA solo se crean consumiendo una invitación emitida por un ADMIN (sección 7). No hay registro libre.
- La escuela nunca la envía el cliente: sale de la configuración (login), de la invitación (registro) o del JWT (resto).

## 2. JWT y cookie de sesión

| Aspecto | Valor |
|---|---|
| Algoritmo | HS256 con un secreto compartido (`JWT_SECRET`, base64, mínimo 32 bytes decodificados) |
| Claims | `iss`, `sub` (id de usuario), `iat`, `exp`, `jti`, `rol`, `escuela_id`, `familia_id` (solo FAMILIA) y `mfa` (solo ADMIN: `PENDIENTE` o `COMPLETADA`) y `mfa_renovado` (solo en el token pendiente renovado al enrolar, sección 9). Sin email ni datos personales |
| Duración | `JWT_DURACION`, por defecto `PT8H` (se valida entre `PT5M` y `PT24H`). El token de un ADMIN con MFA pendiente dura `MFA_DURACION_PENDIENTE`, por defecto `PT5M` (entre `PT1M` y `PT15M`) |
| Cookie | Nombre `BP_SESION` (configurable), `HttpOnly`, `Path=/`, `Max-Age` igual a la duración del JWT |
| `Secure` | `AUTH_COOKIE_SECURE`; el valor por defecto de la aplicación es `true`. Solo el perfil `dev` y los ejemplos locales lo desactivan |
| `SameSite` | `AUTH_COOKIE_SAME_SITE`: `Lax` por defecto; `Strict` o `None`. `None` exige `Secure=true` (si no, la aplicación no arranca) |
| Lectura | Solo desde la cookie. Un `Authorization: Bearer` se ignora. Las rutas públicas de autenticación ignoran la cookie para que una cookie vencida no impida iniciar sesión |

Un token vencido, manipulado, firmado con otra clave, con `alg=none` o con un claim faltante produce `401`. Un token de ADMIN
sin claim `mfa` válido (`PENDIENTE` o `COMPLETADA`), por ejemplo uno emitido antes de existir el MFA, también produce `401`.

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
| `/api/admin/**` | solo `ADMIN` con sesión completa (`mfa=COMPLETADA`); un ADMIN con MFA pendiente recibe `403` |
| `/api/familia/**` | solo `FAMILIA` |
| `/api/auth/me`, `/api/auth/logout` | autenticado (también un ADMIN con MFA pendiente) |
| `/api/auth/admin/mfa/**` | solo un ADMIN con MFA pendiente (autoridad `MFA_PENDIENTE`); un ADMIN con sesión completa o FAMILIA reciben `403` |
| `/api/auth/csrf`, `login`, `admin/login`, `invitaciones/validar`, `registro/invitacion` | públicas (las de `POST` siguen exigiendo CSRF) |
| Cualquier otra ruta | exige `ADMIN` o `FAMILIA` con sesión completa; denegada por defecto (`401` si es anónimo, `403` con MFA pendiente) |

Todos los errores son JSON `{codigo, mensaje, detalles}`; nunca HTML ni trazas de pila.

## 5. Login y protección contra enumeración

- `POST /api/auth/login` autentica solo `FAMILIA`; `POST /api/auth/admin/login` autentica solo `ADMIN`. La ruta de ADMIN no
  se publica ni se enlaza desde la interfaz pública. Un login de ADMIN correcto **no** devuelve una sesión completa: emite un
  token con `mfa=PENDIENTE` (sección 9).
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
- Las contraseñas, los tokens, los códigos TOTP y los secretos MFA no se escriben en logs, auditoría, errores ni respuestas.

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
- Las migraciones `V1` y `V2` están aplicadas y son inmutables (cualquier cambio posterior va en una migración nueva); un test compara el SHA-256
  de ambas. `V3__crear_usuario_mfa.sql` (secreto TOTP cifrado, sección 9) es aditiva.

## 8. Bootstrap del primer ADMIN

Ninguna ruta HTTP crea administradores. La primera cuenta se crea al arrancar con una variable de entorno explícita. El proceso es idempotente.

1. Definir en el entorno del backend (nunca en Git): `BOOTSTRAP_ADMIN_HABILITADO=true`, `BOOTSTRAP_ADMIN_EMAIL`,
   `BOOTSTRAP_ADMIN_NOMBRE`, `BOOTSTRAP_ADMIN_APELLIDO` y `BOOTSTRAP_ADMIN_PASSWORD` (cumple la política de la sección 6).
2. Arrancar la aplicación una vez. Si la configuración es inválida el arranque falla sin imprimir la contraseña.
3. Verificar el acceso en `/api/auth/admin/login`.
4. Volver a `BOOTSTRAP_ADMIN_HABILITADO=false` y **borrar** `BOOTSTRAP_ADMIN_PASSWORD` del entorno.

Comportamiento: si el email ya existe en la escuela no hace nada (no cambia la contraseña); si ya hay `max_administradores`
(2 en Banfield) administradores activos, no crea otro, avisa en el log y el arranque continúa. Registra el evento `ADMIN_BOOTSTRAP`.
Por defecto está desactivado y no corre en el perfil de pruebas. La cuenta se crea con `mfa_habilitado=false`: en su primer login
enrola el segundo factor (sección 9). Conviene hacerlo de inmediato (ver el riesgo de la ventana de enrolamiento en la sección 13).

## 9. Segundo factor (MFA TOTP) de ADMIN

Cumple RNF-03. Todo ADMIN necesita contraseña **y** un código TOTP de su aplicación autenticadora (Google Authenticator, Authy,
1Password, etc.). FAMILIA no usa MFA y su login no cambia. Decisión: TOTP ahora, solo ADMIN, sin códigos de respaldo.

### Implementación

- RFC 6238 solo con el JDK, sin librería nueva: HMAC-SHA1, 6 dígitos, paso de 30 s, Base32 de RFC 4648 propio. Se acepta el
  paso actual y uno de margen a cada lado (±1, es decir ±30 s de desfase de reloj).
- Anti-repetición: se guarda `ultimo_paso_usado` y un código de un paso menor o igual se rechaza. La actualización es un `UPDATE`
  condicional atómico: dos peticiones simultáneas con el mismo código dejan pasar solo a una.
- Comparación de tiempo constante; el formato del código es exactamente 6 dígitos ASCII (otro formato da `400 VALIDACION`).
- Un código incorrecto, vencido, repetido o bloqueado por intentos devuelve **siempre** `401 CODIGO_MFA_INVALIDO` con el mismo
  cuerpo. El motivo real (`CODIGO`, `REPETIDO`, `BLOQUEO`) solo queda en auditoría (`MFA_FALLO`).
- Limitador de intentos por usuario (en memoria, instancia propia, mismos parámetros `LOGIN_MAX_INTENTOS`, `LOGIN_VENTANA`, `LOGIN_BLOQUEO`;
  clave = id de usuario). El código se evalúa igual con el usuario bloqueado, de modo que el bloqueo no se distingue por la respuesta.
- Secreto en reposo: 20 bytes aleatorios cifrados con **AES-256-GCM** (nonce aleatorio de 12 bytes, AAD = id del usuario) en
  `gestion_patin.usuario_mfa.secreto_cifrado` (migración `V3`). La clave sale de `MFA_CLAVE_CIFRADO` (base64 de exactamente 32 bytes,
  sin valor por defecto; si falta o es inválida la aplicación no arranca, igual que `JWT_SECRET`). El secreto en claro, el código y la
  URI `otpauth` nunca se escriben en logs ni en auditoría; solo se devuelven una vez al enrolar (`Cache-Control: no-store`).
- `usuario.mfa_habilitado` (V1) pasa a `true` al confirmar y a `false` al reiniciar, en la misma transacción que la fila de `usuario_mfa`.

### Estados de sesión de un ADMIN

| Estado | Cómo se llega | Qué puede hacer |
|---|---|---|
| Sin sesión | — | login (`POST /api/auth/admin/login`) |
| **MFA pendiente** (`mfa=PENDIENTE`, vida `MFA_DURACION_PENDIENTE`, 5 min por defecto) | contraseña correcta | `GET /api/auth/me` (devuelve `mfaPendiente=true` y `mfaEnrolado`), `POST /api/auth/logout` y `POST /api/auth/admin/mfa/**`. Todo lo demás `403`; no tiene `ROLE_ADMIN` |
| **Sesión completa** (`mfa=COMPLETADA`, vida `JWT_DURACION`) | confirmar o verificar el código | `/api/admin/**` y el resto de rutas de ADMIN |

### Endpoints (todos `POST`, con CSRF)

| Ruta | Requiere | Efecto |
|---|---|---|
| `/api/auth/admin/mfa/enrolar` | token pendiente, MFA aún no confirmado | Genera el secreto, lo guarda cifrado sin confirmar y devuelve `{otpauthUri, secretoBase32}` una sola vez. Repetirlo antes de confirmar reemplaza el secreto sin confirmar. Si termina bien y el token no es ya una renovación, responde además con una **cookie `BP_SESION` renovada** (ver «Renovación del token pendiente»). Ya confirmado: `409 MFA_ESTADO_INVALIDO`, sin cookie |
| `/api/auth/admin/mfa/confirmar` `{codigo}` | token pendiente y enrolamiento sin confirmar | Verifica el primer código, activa el MFA y emite la **sesión completa** (cookie nueva) |
| `/api/auth/admin/mfa/verificar` `{codigo}` | token pendiente y MFA confirmado | Verifica el código y emite la **sesión completa** |
| `/api/admin/usuarios/{id}/mfa/reiniciar` | sesión completa de ADMIN | Reinicia el MFA de **otro** ADMIN de la misma escuela (`204`). Un `id` propio da `403 MFA_AUTOREINICIO_NO_PERMITIDO`; de otra escuela, de FAMILIA o inexistente, `404 USUARIO_NO_ENCONTRADO` |

Secuencia del frontend: `GET /api/auth/csrf` → `POST /api/auth/admin/login` → `GET /api/auth/csrf` (el login descarta el token CSRF anterior)
→ según `mfaEnrolado` de la respuesta: `POST .../mfa/enrolar` y `POST .../mfa/confirmar`, o `POST .../mfa/verificar` → `GET /api/auth/csrf`
otra vez (la sesión completa también rota el CSRF). El `secretoBase32` o la URI `otpauth` se muestran como QR y se descartan.

### Renovación del token pendiente al enrolar

Instalar y escanear la app autenticadora puede llevar más que `MFA_DURACION_PENDIENTE` (5 min por defecto), así que el token emitido en el login
podía vencer antes de `confirmar` (`401 NO_AUTENTICADO`, que ni llega a registrar `MFA_FALLO`). Para darle al enrolamiento su propia ventana:

- Un `POST /api/auth/admin/mfa/enrolar` **exitoso** responde con una `Set-Cookie: BP_SESION` nueva: mismo usuario y escuela, `mfa=PENDIENTE`,
  `mfa_renovado=true`, `iat` = ahora y `exp` = ahora + `MFA_DURACION_PENDIENTE` (mismos atributos de cookie de siempre, `Max-Age` = esa duración).
  La ventana por defecto del login (5 min) **no se ensanchó**; se agrega una segunda, que empieza al enrolar.
- El token renovado sigue siendo **solo pendiente**: autoridad `MFA_PENDIENTE`, sin `ROLE_ADMIN` (`/api/admin/**` da `403`); el validador sigue rechazando un
  token de ADMIN sin `mfa` válido. `confirmar` lo canjea por la sesión completa (`mfa=COMPLETADA`, sin `mfa_renovado`) igual que antes; `verificar` no cambia.
- La cookie se arma **después** de que el servicio vuelve (y con él se confirma la transacción). Con `409`, `401`, `403`, `4xx/5xx` o si falla la
  auditoría/base (rollback), la respuesta **no trae** `Set-Cookie`.
- **Tope: una sola renovación por inicio de sesión.** Un token que ya tiene `mfa_renovado=true` puede volver a llamar a `enrolar` (el re-enrolamiento sigue siendo
  idempotente y reemplaza el secreto sin confirmar) pero la respuesta **no extiende** la ventana: no hay `Set-Cookie`. Así un token pendiente robado no se
  puede mantener vivo repitiendo `enrolar`.
- **Vida pendiente máxima desde el login:** 2 × `MFA_DURACION_PENDIENTE` (10 min por defecto; el caso extremo es enrolar justo antes de vencer el token del login),
  más el margen de 60 s del validador JWT sobre el vencimiento de cada token (en la práctica hasta ~11 min). Pasado eso: `401 NO_AUTENTICADO`; el ADMIN vuelve a
  iniciar sesión y a enrolar (el nuevo `enrolar` reemplaza el secreto sin confirmar, así que hay que escanear de nuevo el QR nuevo).
- Sin estado en servidor ni migración: el tope viaja en el claim firmado `mfa_renovado`.
- Riesgo residual: durante esa ventana ampliada quien robe la cookie pendiente (HttpOnly, `Secure`) y conozca la contraseña ya la tenía; con ella solo podría enrolar su
  propio secreto **antes** de la confirmación legítima. Es el mismo riesgo del «Ventana de enrolamiento» (sección 13), acotado a ≤ 2 × TTL.

### Reinicio y recuperación (sin códigos de respaldo)

- **Un ADMIN pierde su dispositivo:** el otro ADMIN llama a `/api/admin/usuarios/{id}/mfa/reiniciar`. Se borra la fila de `usuario_mfa`,
  `mfa_habilitado=false` y se levanta el bloqueo por intentos; en su próximo login el ADMIN vuelve a enrolar. Queda el evento `MFA_REINICIADO`
  con el actor. Un ADMIN **no puede** reiniciar su propio MFA: con una sesión robada permitiría saltarse el segundo factor.
  El reinicio no cierra las sesiones ya emitidas del afectado (su JWT sigue válido hasta vencer, hasta 8 h).
- **Se pierden los dispositivos de todos los ADMIN (o hay un único ADMIN):** intervención manual en la base por quien tenga acceso a ella
  (SQL de Supabase o `psql`), **sin ningún secreto ni código**; reemplazar `<UUID_DEL_ADMIN>` por el id del usuario:

  ```sql
  BEGIN;
  DELETE FROM gestion_patin.usuario_mfa WHERE usuario_id = '<UUID_DEL_ADMIN>';
  UPDATE gestion_patin.usuario SET mfa_habilitado = false WHERE id = '<UUID_DEL_ADMIN>';
  COMMIT;
  ```

  Es una operación fuera de banda y **no genera evento de auditoría**: debe dejarse constancia aparte. Después, el ADMIN inicia sesión y enrola de nuevo.
- **Se pierde o rota `MFA_CLAVE_CIFRADO`:** los secretos guardados dejan de descifrar (la verificación falla con error interno, nunca acepta un código).
  Hay que reiniciar el MFA de todos los ADMIN con el SQL anterior (sin el `WHERE`, para todos los de la escuela). No hay rotación de clave sin reenrolar.
- Un secreto copiado a la fila de otro usuario no sirve para saltarse el MFA: el id del usuario es el AAD del cifrado, así que no descifra.

## 10. Auditoría

Tabla `auditoria` (escritura por `JdbcClient`). Eventos: `LOGIN_EXITOSO`, `LOGIN_FALLIDO`, `LOGOUT`, `ADMIN_BOOTSTRAP`,
`FAMILIA_CREADA`, `INVITACION_CREADA`, `INVITACION_REVOCADA`, `REGISTRO_POR_INVITACION`, `REGISTRO_FALLIDO` y los de MFA:
`MFA_ENROLADO`, `MFA_CONFIRMADO`, `MFA_VERIFICADO`, `MFA_FALLO` (con `etapa` y `motivo`, nunca el código) y `MFA_REINICIADO` (actor = el ADMIN que reinicia,
recurso = el ADMIN afectado). Un `LOGIN_EXITOSO` de ADMIN solo prueba la contraseña: la sesión completa queda marcada por `MFA_CONFIRMADO` o `MFA_VERIFICADO`.

- Los eventos de éxito comparten la transacción del cambio de negocio; los de fallo usan una transacción propia y su error
  de escritura se ignora, de modo que una falla de auditoría no cambia la respuesta genérica.
- `LOGOUT` es de mejor esfuerzo: si la auditoría falla, el cierre de sesión igualmente expira la cookie.
- El detalle nunca incluye contraseñas, tokens ni hashes (las claves con esas palabras se descartan). Los emails de intentos
  fallidos se guardan enmascarados; para cuentas desconocidas `usuario_id` queda nulo.

## 11. Variables de entorno

Los valores reales van solo en `backend/.env` (ignorado por Git) o en los secretos de la infraestructura. `backend/.env.example`
contiene únicamente valores ficticios.

| Variable | Obligatoria | Descripción |
|---|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | sí | Conexión a PostgreSQL (Supabase, pooler de sesión) |
| `JWT_SECRET` | sí, sin valor por defecto | Base64 de al menos 32 bytes (`openssl rand -base64 48`). Si falta, está en blanco, no es base64 o es corta, la aplicación no arranca |
| `JWT_DURACION` | no | Duración ISO-8601 de la sesión, `PT8H` por defecto |
| `MFA_CLAVE_CIFRADO` | sí, sin valor por defecto | Base64 de **exactamente 32 bytes** (`openssl rand -base64 32`): clave AES-256 que cifra el secreto TOTP. Si falta, está en blanco, no es base64 o no mide 32 bytes, la aplicación no arranca. Se guarda como secreto de infraestructura, separada de la base de datos |
| `MFA_DURACION_PENDIENTE` | no | Vida del token de un ADMIN con MFA pendiente, `PT5M` por defecto (entre `PT1M` y `PT15M`) |
| `AUTH_COOKIE_NOMBRE` | no | Nombre de la cookie de sesión, `BP_SESION` |
| `AUTH_COOKIE_SECURE` | no | `true` por defecto; `false` solo para desarrollo local sin HTTPS |
| `AUTH_COOKIE_SAME_SITE` | no | `Lax` (defecto), `Strict` o `None` (exige `Secure`) |
| `CORS_ORIGENES_PERMITIDOS` | no | Lista de orígenes separados por coma; vacío = CORS desactivado |
| `FRONTEND_URL_BASE` | no | Base para generar `enlaceRegistro` (`{base}/registro/invitacion/{token}`) |
| `ESCUELA_SLUG` | no | Escuela de esta instalación, `banfield-patin-san-pedro` |
| `LOGIN_MAX_INTENTOS`, `LOGIN_VENTANA`, `LOGIN_BLOQUEO` | no | Limitador de intentos: 5, `PT15M`, `PT15M` |
| `BOOTSTRAP_ADMIN_HABILITADO`, `BOOTSTRAP_ADMIN_EMAIL`, `BOOTSTRAP_ADMIN_NOMBRE`, `BOOTSTRAP_ADMIN_APELLIDO`, `BOOTSTRAP_ADMIN_PASSWORD` | no | Bootstrap del primer ADMIN (sección 8); desactivado por defecto |

## 12. Pruebas y convención de pruebas con base de datos

- `./mvnw clean verify` (desde `backend/`) no necesita base de datos ni Docker: no abre `DataSource`, no ejecuta Flyway y no usa
  `@SpringBootTest`. Incluye pruebas unitarias y *slices* `@WebMvcTest` con servicios simulados, entre ellas las matrices 401/403,
  CSRF, atributos de cookie y rol forzado.
- Las pruebas que necesitan PostgreSQL real usan **Testcontainers** (contenedor `postgres:17-alpine` local y descartable, requiere
  Docker). Llevan la etiqueta JUnit `@Tag("db")` (a través de la anotación `@PruebaDb`), el `pom.xml` las excluye por defecto
  (`excludedGroups=db`) y solo corren con el perfil Maven `db-tests`:

  ```bash
  ./mvnw verify -Pdb-tests      # levanta el contenedor, aplica Flyway V1+V2+V3 y lo elimina al terminar
  ```

  No se necesita configurar variables de entorno ni crear ninguna base: el contenedor es único por ejecución y lo comparten todas
  las pruebas `db`.
- Salvaguardas: aunque la base es un contenedor, las pruebas `db` rechazan cualquier host que no sea `localhost`, `127.0.0.1` o
  `::1` (por ejemplo, un `DOCKER_HOST` remoto) y cualquier base cuyo nombre no contenga `test`. **Nunca** se ejecutan contra
  Supabase ni contra una base compartida. Cubren: Flyway V1+V2+V3, `ddl-auto=validate` de las entidades, restricciones de V2 y V3,
  consultas JPQL, consumo atómico concurrente de invitaciones y el bootstrap del primer ADMIN (la auditoría exige que el usuario ya
  exista físicamente: `fk_auditoria_usuario_misma_escuela`, por eso el alta usa `saveAndFlush` antes de auditar),
  el repositorio y el servicio de MFA (upsert, `UPDATE` condicional concurrente, auditoría real) y el flujo completo de ADMIN (login pendiente → enrolar →
  confirmar con un código calculado por la prueba → sesión completa → `/me` → reinicio por otro ADMIN) con la aplicación entera (`@SpringBootTest` etiquetado `db`).
- Un test sin base de datos verifica que el pom excluye el grupo `db` por defecto y que toda prueba que abre un contexto con
  base de datos esté etiquetada.

## 13. Riesgos aceptados y requisitos pendientes

- **MFA de ADMIN (RNF-03) implementado con TOTP (sección 9).** Riesgos residuales aceptados:
  - **Ventana de enrolamiento:** una cuenta ADMIN nueva (por ejemplo la del bootstrap) no tiene segundo factor hasta que alguien confirma el primero; quien
    conozca su contraseña en ese intervalo puede enrolar **su** dispositivo. Mitigación operativa: el responsable enrola inmediatamente después del bootstrap.
    Una vez confirmado, el enrolamiento solo se reemplaza con un reinicio hecho por otro ADMIN.
  - **Token pendiente renovado:** `enrolar` renueva una vez la cookie pendiente; la vida pendiente total desde el login es ≤ 2 × `MFA_DURACION_PENDIENTE` (más 60 s de margen JWT por token). Una cookie pendiente robada
    permite, en ese lapso y conociendo la contraseña, enrolar un secreto propio antes de la confirmación legítima (sección 9).
  - **Sin códigos de respaldo:** perder el dispositivo exige que el otro ADMIN reinicie el MFA o una intervención manual en la base (sección 9).
  - **Bloqueo dirigido:** quien conozca la contraseña de un ADMIN puede provocar el bloqueo de sus intentos de MFA (por usuario, no por IP) durante la ventana
    de bloqueo; es una denegación de servicio temporal, no un acceso.
  - **Reinicio y sesiones vigentes:** reiniciar el MFA de un ADMIN no invalida su JWT ya emitido (hasta `JWT_DURACION`). Para cortarlo ya, rotar `JWT_SECRET`.
  - **Límites en memoria:** el limitador de MFA es por instancia (sección 5), y el secreto TOTP es simétrico: quien lea la base **y** `MFA_CLAVE_CIFRADO` puede generar códigos.
  - **Reloj:** el servidor debe tener la hora sincronizada (NTP); el margen es ±30 s.
- **Usuario desactivado conserva su JWT hasta su vencimiento (hasta 8 h por defecto): riesgo aceptado.** `GET /api/auth/me` vuelve a
  consultar la base y devuelve `401` si el usuario, su familia o su escuela están inactivos, y el login los rechaza; pero los demás
  endpoints confían en el JWT hasta que vence. Mitigar acortando `JWT_DURACION` o añadiendo después una revocación por `jti`
  o una verificación por solicitud.
- **Rotar `JWT_SECRET` cierra todas las sesiones** (todos los JWT emitidos dejan de validar). Es el procedimiento de emergencia.
- El limitador de intentos es por instancia y en memoria (sección 5).
- La IP real depende de la configuración del proxy (sección 5).
- **V1 y V2 ya están aplicadas en la base compartida (Supabase) y son inmutables** (un test fija el SHA-256 de ambas); cualquier cambio de
  esquema va en una migración nueva. **V3 (`usuario_mfa`) es aditiva y debe revisarse y probarse (humo controlado) antes de aplicarse en Supabase:
  Flyway la aplica sola en el primer arranque con esta versión, y la aplicación exige `MFA_CLAVE_CIFRADO` para arrancar.**
- Aún no existe auditoría consultable por ADMIN ni retención definida (RNF-05); este documento solo cubre los eventos de acceso e invitaciones.
