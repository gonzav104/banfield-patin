# Familias, tutores, deportistas y vínculos — Banfield Patín Carrera

Contrato y reglas del módulo implementado en el cambio `familias-deportistas` (backend `backend/`, Spring Boot 4). Describe lo que el código hace hoy.
Todas las rutas devuelven JSON; los errores tienen siempre la forma `{codigo, mensaje, detalles}` y nunca incluyen trazas, SQL, nombres de restricciones ni
datos de otras personas. La estrategia de autenticación está en [SEGURIDAD.md](SEGURIDAD.md).

## 1. Convenciones comunes

- **Escuela e identidad:** salen siempre del JWT (`UsuarioAutenticado`). Ningún parámetro, cuerpo ni ruta puede cambiarlas: `escuelaId`, `familiaId`,
  `usuarioId`, `activo`, `estado`, `esPrincipal` y `autorizadoPor` enviados en un cuerpo se rechazan con `400 SOLICITUD_INVALIDA`, y en la query string se ignoran.
  Un id de otra escuela es indistinguible de uno inexistente (`404`).
- **Rutas `/api/admin/**`:** solo `ADMIN` con sesión completa (MFA). Toda ruta que escribe exige CSRF (`403 CSRF_INVALIDO` si falta).
- **Rutas `/api/familia/**`:** solo `FAMILIA`, **solo lectura**. No existe ninguna ruta que escriba; cualquier otro método responde `405 METODO_NO_PERMITIDO`
  (con CSRF) o `403 CSRF_INVALIDO` (sin CSRF).
- **Errores transversales de toda ruta autenticada:** `401 NO_AUTENTICADO` (sin sesión, o sesión no vigente: la cookie se borra), `403 ACCESO_DENEGADO`
  (rol equivocado), `503 SERVICIO_NO_DISPONIBLE` (la base no responde al revalidar; la cookie no se toca), `400 SOLICITUD_INVALIDA` (JSON ilegible,
  propiedad desconocida o prohibida, parámetro o id mal formado) y `400 VALIDACION` (campos inválidos; `detalles` lista campo y mensaje, nunca el valor).
- **Paginación:** `page` (desde 0; un valor negativo pasa a 0) y `size` (por defecto 20; se acota a 1..100). Nunca da `400` por tamaño. Respuesta
  `{contenido, pagina, tamanio, totalElementos}`.
- **Orden estable:** `lower(apellido), lower(nombre), id` para personas y `lower(nombreReferencia), id` para familias.
- **Estado en listados** (`estado`): `TODOS`, `ACTIVOS` o `INACTIVOS`; otro valor da `400 SOLICITUD_INVALIDA`.
- **Búsqueda** (`busqueda`): sin distinguir mayúsculas, hasta 100 caracteres, con `%`, `_` y `!` escapados; no ignora acentos.
- **Auditoría:** solo ante un cambio real, en la misma transacción. El `detalle` lleva ids, banderas y *nombres* de campos modificados; jamás DNI, CUIL, nombres,
  domicilios ni teléfonos. Las lecturas (incluido el portal de FAMILIA) no se auditan.

## 2. Tabla de rutas

La prueba `RutasDocumentadasTest` exige que cada ruta de los controladores de `familias` (salvo `familias.invitaciones`, documentada en
SEGURIDAD.md, sección 7) y `deportistas` figure en esta tabla, y que la tabla no contenga rutas inexistentes. Desde RNF-14 (sección 8) la fuente de verdad del contrato
es la especificación OpenAPI generada; esta tabla es su resumen legible y `OpenApiRutasTest` comprueba que sus 24 rutas estén en la especificación.

| Ruta | Rol | Solicitud | Respuesta | Errores |
|---|---|---|---|---|
| `GET /api/admin/familias` | ADMIN | `page`, `size`, `busqueda` | `200` `Pagina<FamiliaResumen>` (solo activas; **ruta heredada, sin cambios**) | — |
| `GET /api/admin/familias/listado` | ADMIN | `estado` (por defecto `TODOS`), `busqueda`, `page`, `size` | `200` `Pagina<{id, nombreReferencia, activa, cantidadTutores, cantidadDeportistasActivos}>` | `400 SOLICITUD_INVALIDA` (estado desconocido) |
| `POST /api/admin/familias` | ADMIN | `{nombreReferencia}` (1..150) | `201` `FamiliaDetalle` + `Location` | `400 VALIDACION` |
| `GET /api/admin/familias/{id}` | ADMIN | — | `200` `{id, nombreReferencia, activa, tutores}` (también de una familia inactiva) | `404 FAMILIA_NO_ENCONTRADA` |
| `PUT /api/admin/familias/{id}` | ADMIN | `{nombreReferencia}` (reemplazo completo; permitido en una familia inactiva) | `200` `FamiliaDetalle` | `400`, `404 FAMILIA_NO_ENCONTRADA` |
| `POST /api/admin/familias/{id}/activar` | ADMIN | — | `200` `FamiliaDetalle` (idempotente) | `404 FAMILIA_NO_ENCONTRADA` |
| `POST /api/admin/familias/{id}/desactivar` | ADMIN | — | `200` `FamiliaDetalle` (idempotente; sin cascada) | `404 FAMILIA_NO_ENCONTRADA` |
| `POST /api/admin/familias/{familiaId}/tutores` | ADMIN | `{nombre, apellido, dni?, telefono?, email?, parentesco?}` | `201` `TutorRespuesta` + `Location` | `400`, `404 FAMILIA_NO_ENCONTRADA`, `409 FAMILIA_INACTIVA` |
| `GET /api/admin/tutores/{id}` | ADMIN | — | `200` `TutorRespuesta` (también de una familia inactiva) | `404 TUTOR_NO_ENCONTRADO` |
| `PUT /api/admin/tutores/{id}` | ADMIN | igual que el alta (reemplazo completo; la familia no cambia) | `200` `TutorRespuesta` | `400`, `404 TUTOR_NO_ENCONTRADO`, `409 FAMILIA_INACTIVA` |
| `GET /api/admin/deportistas` | ADMIN | `estado` (por defecto `ACTIVOS`), `busqueda` (apellido, nombre, ambos órdenes o prefijo de DNI), `page`, `size` | `200` `Pagina<{id, nombre, apellido, dni, fechaNacimiento, activo}>` | `400 SOLICITUD_INVALIDA` |
| `POST /api/admin/deportistas` | ADMIN | `{dni, nombre, apellido, cuil?, fechaNacimiento?, nacionalidad?, domicilio?, otrosDatosDomicilio?, localidad?, partido?, codigoPostal?, telefonoContacto?, emailFederativo?}` | `201` `DeportistaDetalle` + `Location` | `400 VALIDACION`, `409 DNI_DUPLICADO`, `409 DNI_RESERVADO_POR_INACTIVO`, `409 CUIL_DUPLICADO` |
| `GET /api/admin/deportistas/{id}` | ADMIN | — | `200` `DeportistaDetalle` (datos permanentes y `activo`) | `400` (id no UUID), `404 DEPORTISTA_NO_ENCONTRADO` |
| `PUT /api/admin/deportistas/{id}` | ADMIN | igual que el alta (reemplazo completo) | `200` `DeportistaDetalle` | `400`, `404 DEPORTISTA_NO_ENCONTRADO`, `409` (los tres de arriba) |
| `POST /api/admin/deportistas/{id}/activar` | ADMIN | — | `200` `DeportistaDetalle` (idempotente; no toca vínculos) | `404 DEPORTISTA_NO_ENCONTRADO` |
| `POST /api/admin/deportistas/{id}/desactivar` | ADMIN | — | `200` `DeportistaDetalle` (idempotente; no toca vínculos) | `404 DEPORTISTA_NO_ENCONTRADO` |
| `GET /api/admin/familias/{familiaId}/deportistas` | ADMIN | — | `200` `List<VinculoRespuesta>` (todos los estados) | `404 FAMILIA_NO_ENCONTRADA` |
| `GET /api/admin/deportistas/{deportistaId}/familias` | ADMIN | — | `200` `List<VinculoRespuesta>` (todos los estados) | `404 DEPORTISTA_NO_ENCONTRADO` |
| `POST /api/admin/familias/{familiaId}/deportistas` | ADMIN | `{deportistaIds: [uuid]}` (1..50, se quitan repetidos; todo o nada) | `200` `{resultados: [{vinculo, resultado: CREADO\|REACTIVADO\|SIN_CAMBIOS}]}` en el orden pedido | `400`, `404 FAMILIA_NO_ENCONTRADA`, `409 FAMILIA_INACTIVA`, `404 DEPORTISTA_NO_ENCONTRADO` y `409 DEPORTISTA_INACTIVO` (con `detalles` `deportistaIds[i]`), `409 VINCULO_PRINCIPAL_EN_CONFLICTO`, `409 CONFLICTO_CONCURRENCIA` |
| `POST /api/admin/familias/{familiaId}/deportistas/{deportistaId}/revocar` | ADMIN | — | `200` `VinculoRespuesta` (idempotente; permitido con familia inactiva) | `404 VINCULO_NO_ENCONTRADO`, `409 VINCULO_NO_ACTIVO` (PENDIENTE o RECHAZADO), `409 CONFLICTO_CONCURRENCIA` |
| `POST /api/admin/familias/{familiaId}/deportistas/{deportistaId}/principal` | ADMIN | — | `200` `VinculoRespuesta` (idempotente) | `404 FAMILIA_NO_ENCONTRADA`, `409 FAMILIA_INACTIVA`, `404 VINCULO_NO_ENCONTRADO`, `409 VINCULO_NO_ACTIVO`, `409 VINCULO_PRINCIPAL_EN_CONFLICTO`, `409 CONFLICTO_CONCURRENCIA` |
| `GET /api/familia/mi-familia` | FAMILIA | — | `200` `{id, nombreReferencia, tutores: [{id, nombre, apellido, parentesco, telefono, email}]}` (solo tutores activos) | `404 FAMILIA_NO_ENCONTRADA` |
| `GET /api/familia/deportistas` | FAMILIA | `page`, `size` | `200` `Pagina<{id, nombre, apellido, fechaNacimiento, activo}>` | — |
| `GET /api/familia/deportistas/{id}` | FAMILIA | — | `200` `{id, nombre, apellido, dni, cuil, fechaNacimiento, nacionalidad, domicilio, otrosDatosDomicilio, localidad, partido, codigoPostal, telefonoContacto, emailFederativo, activo}` | `400` (id no UUID), `404 DEPORTISTA_NO_ENCONTRADO` (uniforme) |

`DELETE` y `PATCH` no existen en ninguna ruta de este módulo (`405 METODO_NO_PERMITIDO`): una familia, un deportista o un vínculo se desactivan o revocan, nunca se
borran. `VinculoRespuesta` es `{vinculoId, familiaId, familiaNombre, deportistaId, deportistaNombre, deportistaApellido, deportistaActivo, estado, esPrincipal,
autorizadoEn}`; no incluye quién autorizó ni el DNI.

### Códigos de error propios del módulo

| Código | Estado | Cuándo |
|---|---|---|
| `FAMILIA_NO_ENCONTRADA`, `TUTOR_NO_ENCONTRADO`, `DEPORTISTA_NO_ENCONTRADO`, `VINCULO_NO_ENCONTRADO` | 404 | El recurso no existe **o es de otra escuela** (o, en el portal, de otra familia): mismo código, mensaje fijo y cuerpo |
| `DNI_DUPLICADO` | 409 | Ya hay un deportista **activo** con ese DNI en la escuela |
| `DNI_RESERVADO_POR_INACTIVO` | 409 | Ya hay un deportista **inactivo** con ese DNI: se reactiva en lugar de crear otro (sección 4) |
| `CUIL_DUPLICADO` | 409 | Ya hay un deportista con ese CUIL en la escuela |
| `FAMILIA_INACTIVA` | 409 | Vincular, cambiar el principal o crear/editar un tutor en una familia inactiva |
| `DEPORTISTA_INACTIVO` | 409 | Vincular un deportista inactivo |
| `VINCULO_NO_ACTIVO` | 409 | Revocar o marcar principal un vínculo PENDIENTE/RECHAZADO (o principal sobre uno REVOCADO) |
| `VINCULO_PRINCIPAL_EN_CONFLICTO` | 409 | La base arbitró una carrera de principal o de par (red de seguridad de V4); la transacción se revirtió. Reintentar |
| `CONFLICTO_CONCURRENCIA` | 409 | Otra transacción retuvo el deportista más tiempo que `banfield.vinculos.lock-timeout` (o PostgreSQL eligió esta transacción como víctima de un *deadlock*). No se escribió ni auditó nada. Mensaje fijo: «Otro cambio sobre el mismo deportista esta en curso. Reintentá en unos segundos.»; sin SQL, ids ni trazas. Solo las rutas de vínculos que bloquean (vincular, revocar, principal) pueden devolverlo |

Los `409` de DNI y CUIL, `VINCULO_PRINCIPAL_EN_CONFLICTO` y `CONFLICTO_CONCURRENCIA` nunca incluyen el nombre de la restricción, el id en conflicto ni el valor.

## 3. Decisiones del módulo

**F1–F12** (valores por defecto del equipo; F1 y F2 confirmados por el responsable):

| | Decisión |
|---|---|
| F1 | Un deportista puede tener varias familias, pero a lo sumo **un** vínculo ACTIVO principal (índice parcial único de V4) |
| F2 | DNI: 7 a 9 dígitos (se quitan puntos y espacios); CUIL: 11 dígitos con dígito verificador (se quitan guiones). Sin CHECK en la base: se valida en la aplicación |
| F3 | Solo el estado ACTIVO da acceso a la FAMILIA; los vínculos creados por ADMIN nacen ACTIVOS con `autorizado_por/en` |
| F4 | Desactivar una familia o un deportista no cascada: no toca vínculos ni tutores |
| F5 | FAMILIA es de solo lectura |
| F6 | `tutor.usuario_id` no se mapea en este cambio |
| F7 | `tutor.familia_id` es inmutable |
| F8 | El DNI del tutor es opcional y no es único |
| F9 | Los tutores no se desactivan |
| F10 | Un deportista exige solo DNI, nombre y apellido |
| F11 | El DNI es editable por ADMIN, con unicidad |
| F12 | Rutas `/api/admin/{familias,tutores,deportistas}` y `/api/familia/{mi-familia,deportistas}`; el listado administrativo nuevo es `GET /api/admin/familias/listado` y el heredado no cambia |

**U1–U5** (decisiones del responsable, 2026-10-05):

| | Decisión |
|---|---|
| U1 | Un deportista **inactivo** con vínculo ACTIVO sigue visible para su FAMILIA con `activo=false` (la inactividad deportiva no es una revocación de acceso) |
| U2 | Vincular con una familia inactiva se rechaza con `409 FAMILIA_INACTIVA`; revocar siempre se permite |
| U3 | El principal cambia solo con la operación atómica explícita (`.../principal`), nunca revocando y recreando |
| U4 | La vigencia de la sesión (usuario, escuela, familia) se revalida de forma central en cada solicitud, sin cache (sección 6) |
| U5 | V4 aprobada tal como se diseñó, sujeta a los *prechecks* de solo lectura (sección 7) |

**N1–N3 y P1:** N1, revalidación sin cache (`401` y cookie borrada; `503` si la base cae); N2, `cantidadDeportistasActivos` cuenta vínculos ACTIVOS cuyo
deportista está activo; N3, crear o editar tutores de una familia inactiva da `409 FAMILIA_INACTIVA` (leer sí se permite); P1, el principal solo se cambia entre
vínculos ACTIVOS y con la familia destino activa. Editar `nombreReferencia` de una familia inactiva **sí** se permite (se corrige antes de reactivarla).

## 4. Regla de reserva del DNI

`uq_deportista_dni_escuela` **no es parcial**: un deportista inactivo conserva su DNI. Por eso crear con el DNI de uno inactivo responde
`409 DNI_RESERVADO_POR_INACTIVO` («reactivalo en lugar de crear uno nuevo») y con el de uno activo `409 DNI_DUPLICADO`. El mismo DNI en otra escuela es válido. Si dos
altas simultáneas chocan, la base arbitra: una gana (`201`) y la otra recibe `409`, nunca `500`.

## 5. Principal y máquina de estados del vínculo

Reglas del principal:

1. Al vincular o reactivar, `es_principal = true` si y solo si el deportista no tiene otro vínculo ACTIVO principal (decidido con el deportista bloqueado).
2. Revocar pone `es_principal = false` y **no** promueve a otro vínculo.
3. Cambiar el principal (`POST .../principal`) es la única vía: familia `404` → familia inactiva `409` → bloqueo → vínculo `404`/`409 VINCULO_NO_ACTIVO` → si ya es el
   principal, `200` sin escribir → se baja el principal actual y se sube el elegido (en ese orden, cada uno con `flush`) → auditoría.

| Estado actual \ acción | Vincular (ADMIN) | Revocar | Marcar principal |
|---|---|---|---|
| (sin fila) | crea ACTIVO (`CREADO`) | `404 VINCULO_NO_ENCONTRADO` | `404 VINCULO_NO_ENCONTRADO` |
| ACTIVO | sin cambios (`SIN_CAMBIOS`) | pasa a REVOCADO y deja de ser principal | promueve y baja al anterior (idempotente) |
| REVOCADO | reutiliza la fila → ACTIVO (`REACTIVADO`) | `200` idempotente, sin auditoría | `409 VINCULO_NO_ACTIVO` |
| PENDIENTE / RECHAZADO (esta API no los produce) | reutiliza la fila → ACTIVO (`REACTIVADO`) | `409 VINCULO_NO_ACTIVO` | `409 VINCULO_NO_ACTIVO` |

Orden de verificación al vincular: familia `404` → familia inactiva `409 FAMILIA_INACTIVA` (primero, incluso si el lote sería todo `SIN_CAMBIOS`) → bloqueo de las filas de
los deportistas → algún id inexistente o de otra escuela `404` → algún deportista inactivo `409` → escritura. Cualquier fallo no deja nada escrito ni auditado.

**Concurrencia:** todas las operaciones que cambian vínculos toman primero el bloqueo de fila del deportista (`FOR UPDATE`, en orden de id, de modo que lotes
superpuestos en orden inverso no se bloquean entre sí). Esa espera está acotada: las tres transacciones ejecutan `SET LOCAL lock_timeout` (propiedad
`banfield.vinculos.lock-timeout`, por defecto `PT3S`, válida entre 100 ms y 30 s; fuera de rango la aplicación no arranca) y, si vence, responden `409 CONFLICTO_CONCURRENCIA`.
`SET LOCAL` vale solo hasta el fin de esa transacción: no cambia nada del pool, de la sesión ni de otros servicios. El índice parcial único `uq_fd_principal_activo`
(V4) es la última red de seguridad.

## 6. Portal de FAMILIA (solo lectura) y resumen de N1

- La FAMILIA ve su familia, sus tutores **activos** y únicamente los deportistas con vínculo ACTIVO (incluidos los inactivos, con `activo=false`).
- Cada lectura es **una sola sentencia** que une vínculo, familia y deportista por id **y** escuela y exige vínculo ACTIVO y familia activa; no se carga nada para
  comparar después. Un deportista de otra familia, de otra escuela, con vínculo revocado/pendiente/rechazado o inexistente produce exactamente el mismo
  `404 DEPORTISTA_NO_ENCONTRADO` (cuerpo idéntico byte a byte).
- Los DTO del portal omiten `escuelaId`, `esPrincipal`, `autorizadoPor/En`, marcas de tiempo, ids de vínculo, el DNI del tutor y el estado del tutor.
- **N1:** una familia inactiva, un usuario inactivo o inexistente o una escuela inactiva no llegan nunca al portal: la revalidación central responde `401 NO_AUTENTICADO`
  y borra la cookie en **todas** las rutas (incluida `/api/auth/me`); reactivar restablece la sesión sin nuevo login. Si la base no responde, `503` y la cookie queda
  intacta. El predicado `f.activa = true` se conserva en la consulta del portal como defensa en profundidad y se prueba solo a nivel de repositorio.
- Techos de sentencias de Hibernate (medidos con más de 5 filas): `mi-familia` ≤ 2, listado ≤ 2 (con cualquier `page`/`size`), detalle ≤ 1; la revalidación central
  suma una sentencia JDBC aparte por solicitud autenticada.

## 7. Migración V4 (compuerta con la base compartida)

`V4__restricciones_familia_deportista.sql` es aditiva (índice `ix_tutor_familia`, `ck_fd_activo_autorizado` y `uq_fd_principal_activo`) y está probada contra
PostgreSQL 17 en Testcontainers, incluido sobre datos existentes. **Se aplicó a Supabase el 2026-10-05** (ver G1 y G2).

- **G1 (revisión humana y prechecks):** los *prechecks* de solo lectura se ejecutaron el **2026-10-05 contra Supabase y PASARON** (historial Flyway 1–3 correcto; 0 familias, 0
  tutores, 0 deportistas y 0 vínculos; ningún vínculo ACTIVO sin `autorizado_*`; ningún deportista con dos principales ACTIVOS; ninguno de los tres objetos de V4 existe).
  Se repitieron con `backend/scripts/precheck-v4-supabase.sql` justo antes del primer arranque y volvieron a salir limpios.
- **V4 aplicada en Supabase (2026-10-05):** Flyway la aplicó en el arranque normal de la aplicación (versión 4 con éxito, checksum de Flyway 2126611739, PostgreSQL 17.11).
  Se verificó en la base que existen `ix_tutor_familia`, `uq_fd_principal_activo` (índice único parcial) y `ck_fd_activo_autorizado`.
- **G2 (cerrada):** el SHA-256 de V4 (`8e449b9f812c2d354d08f1f73eb026bada353e1b173a69368c50d5460d43ba48`) quedó fijado en `V4MigracionEstaticaTest`; desde ahora V4 es
  inmutable y cualquier reversión va en `V5` (nunca editando V4). V1, V2 y V3 ya estaban fijadas por SHA-256.

## 8. Estado de RNF-14 (OpenAPI)

**RNF-14 está CUMPLIDO** para todas las rutas `/api/**` actuales (autenticación, MFA, invitaciones, familias, tutores, deportistas, vínculos y portal de FAMILIA: 39 operaciones
en 32 rutas). RNF-14 pide documentación OpenAPI coherente con los endpoints y una especificación que valide sin errores; **no exige Swagger UI**, y no se incluyó.

- **Cómo obtenerla:** `GET /v3/api-docs` (JSON, OpenAPI 3.1) o `/v3/api-docs.yaml`. En `dev` es pública; en cualquier otro perfil exige `ADMIN` con sesión completa; con el perfil `prod`
  no existe (política y pruebas en [SEGURIDAD.md](SEGURIDAD.md), sección 4.1). No hay interfaz gráfica: la especificación se abre con cualquier cliente OpenAPI.
- **Librería:** `springdoc-openapi-starter-webmvc-api:3.1.1` (sin UI), validada contra Spring Boot 4.1.1: compila, arranca la aplicación completa y sirve el documento
  (`OpenApiArranqueDbTest`, con PostgreSQL real, y el contexto sin base de datos de `BaseOpenApiWebMvc`). No requirió forzar versiones ni exclusiones.
- **Qué documenta:** cada operación con su código de éxito real (`201` con `Location` en las altas, `204` sin cuerpo en `logout` y reinicio de MFA), los errores propios de la ruta
  con sus `codigo`, los transversales (`400`, `401`, `403`, `503`) y el modelo de error compartido `ErrorRespuesta {codigo, mensaje, detalles}` en toda respuesta de error. Los
  DTO salen de los `record` con sus restricciones de Bean Validation (largos, obligatorios, formato). La cookie `BP_SESION` (`apiKey` en cookie) y el header `X-XSRF-TOKEN` (en
  toda ruta que escribe) son esquemas de seguridad. Los códigos de éxito y de error por ruta están en una tabla única (`OpenApiConfig.RUTAS`).
- **Cómo se mantiene coherente:** `OpenApiRutasTest` enumera las rutas `/api/**` desde `RequestMappingHandlerMapping` y exige que la especificación tenga exactamente esas
  operaciones (método y ruta), que la tabla de `OpenApiConfig` tenga una entrada por ruta, que los `201`/`204` coincidan con los controladores (`OpenApiEstadosRealesTest` los invoca)
  y que `ErrorRespuesta` esté referenciado. Una ruta nueva sin entrada hace fallar el build.
- **Validación sin errores y sus límites:** `ValidadorOpenApi` (solo Jackson, sin dependencias nuevas) verifica versión `3.0.x`/`3.1.x`, `info`, `paths`, que cada operación tenga
  `responses` con clave y `description` válidas, parámetros de ruta declarados, `operationId` sin repetir, **todos los `$ref` resueltos**, requisitos de seguridad declarados y
  `required` coherente con `properties`; se prueba con documentos rotos para que no sea un validador que nunca falla. **No** valida contra el meta-esquema oficial completo (tipos de cada
  campo, palabras de JSON Schema 2020-12, propiedades desconocidas): no se aprobó un validador completo (por ejemplo `swagger-parser`).
- **Qué no documenta bien (límites conocidos):** (1) un método HTTP no mapeado responde `405 METODO_NO_PERMITIDO`, pero OpenAPI no lo lista por operación (se menciona en `info.description`);
  (2) el parámetro `estado` de los listados es un `string` sin `enum` (el valor `TODOS|ACTIVOS|INACTIVOS` está solo en esta documentación; un valor desconocido da `400`); (3) los DTO que rechazan
  propiedades desconocidas (`@JsonAnySetter`) no declaran `additionalProperties: false`; (4) la tabla de errores por ruta es manual (la completitud de rutas se prueba; la exactitud de cada
  código de error depende de la revisión, como la tabla de la sección 2); (5) sin ejemplos, a propósito (ningún DNI, email ni token con apariencia real); (6) `springdoc` usa Jackson 2 internamente
  (`swagger-core`) junto al Jackson 3 de la aplicación: sin consecuencias hoy, pero conviene tenerlo presente al actualizar.

## 9. Límites abiertos conocidos

- **Sin bloqueo optimista:** una edición (`PUT`) concurrente sobre el mismo registro gana la última escritura. Los vínculos sí se serializan por el bloqueo del deportista.
- **Sin `unaccent`:** la búsqueda distingue acentos (no mayúsculas).
- **`deportista_temporada` fuera de alcance:** el cambio mapea solo los datos permanentes del deportista; licencias, temporadas y datos de temporada llegan después.
- **RF-06 (completitud de los datos del deportista) diferido:** el alta exige solo DNI, nombre y apellido; la validación de completitud para federar queda para el módulo de exportación.
- **Sin documentos, aptos físicos, competencias ni frontend** en este cambio.

## 10. Smoke manual contra Supabase

`backend/scripts/smoke-familias.sh` recorre este módulo contra una aplicación **ya levantada** usando la API real (curl + python3). Si `BASE_URL` apunta a una app
conectada a Supabase, **escribe datos reales** en esa base. Lo ejecuta una persona; no forma parte del build.

- **Requisitos:** la aplicación corriendo (por ejemplo `./mvnw spring-boot:run` desde `backend/` con el `.env` cargado), `curl`, `python3` y un ADMIN activo con su app
  autenticadora (TOTP). Usar **una sola instancia** de la aplicación contra el *session pooler* de Supabase (límite de 15 clientes, `EMAXCONNSESSION`): una segunda instancia
  o un IDE con la app abierta pueden agotarlo, y `psql` para la limpieza también ocupa un cupo.
- **Cómo correrlo:** desde la raíz del repo, `BASE_URL=http://localhost:8080 backend/scripts/smoke-familias.sh`. Pide por teclado el email y la password del ADMIN (la
  password y el código no se ven al escribir) y **un solo código TOTP**, justo después del login (paso 1). Si la cuenta todavía no tiene MFA, el script avisa, pide Enter y
  la **enrola**: es un cambio real de la cuenta, y en ese único caso imprime el secreto TOTP una vez para cargarlo en la app autenticadora; el código pedido confirma el
  enrolamiento. Si ya está enrolada usa la verificación normal. No hay que esperar otro paso TOTP (solo se usa un código por corrida); si se repite el smoke de inmediato, esperar
  a que el código de la app cambie (el servidor rechaza reutilizar uno ya usado).
- **Pasos:** 1 login ADMIN + MFA; 2 familia; 3 tutor; 4 deportistas (más un control de DNI repetido); 5 vínculo en lote; 6 un único principal ACTIVO; 7 cambio explícito
  del principal; 8 invitación; 9 registro de FAMILIA; 10 login de FAMILIA; 11 `mi-familia`; 12 listado y detalle del portal; 13 aislamiento (mismo `404` para un UUID inexistente y
  uno ajeno, `403` en rutas de ADMIN, `405` al escribir); 14 deportista inactivo (la FAMILIA lo sigue viendo con `activo=false`) y vínculo revocado (deja de verlo); 14b familia
  inactiva (`401` y, al reactivarla, la misma sesión vuelve a funcionar); 15 logout. Cada verificación muestra `[OK]` o `[FALLA]` con el estado HTTP y el `codigo`; en la primera falla
  termina con `SMOKE FALLO EN EL PASO N: ...` (exit 1) y, si todo sale bien, con `SMOKE OK` (exit 0).
- **Qué crea:** 2 familias, 1 tutor, 3 deportistas, vínculos, 1 invitación (usada) y 1 usuario FAMILIA, todos con el prefijo `SMOKE_<run id>` (el usuario, `smoke_<run id>@example.test`;
  los DNI son numéricos y no admiten prefijo). No existe ninguna ruta para borrar. Al terminar (también si falla, cuando ya escribió) imprime el run id y los comandos de limpieza.
- **Limpieza:** `psql "$URL_POSTGRES" -X -v run_id=<run id> -f backend/scripts/limpiar-smoke-familias.sql` es solo vista previa (no escribe nada); con `-v aplicar=si` aplica la
  limpieza suave en una transacción: revoca los vínculos, desactiva deportistas, familias y el usuario FAMILIA del smoke y revoca invitaciones pendientes. Es idempotente y aborta si
  alguna fila tocada no cumple el prefijo o si coinciden demasiadas filas. `$URL_POSTGRES` es la URL de `psql`, no la JDBC.
- **Qué queda tras la limpieza suave:** todas las filas, inactivas o revocadas (nunca se borra un deportista), el tutor, las invitaciones y **todas las filas de `auditoria`** como
  evidencia. El script contiene, comentada, una sección de **borrado físico** (destructiva: borra también las filas de auditoría del usuario FAMILIA del smoke, porque `auditoria` tiene una
  clave foránea compuesta hacia `usuario`); tras habilitarla solo quedan las filas de auditoría del ADMIN, que apuntan a lo ya borrado.
- **Qué no imprime ni guarda:** DNI y CUIL, passwords (la del ADMIN no se guarda; la de FAMILIA se genera al azar en memoria), códigos TOTP, secretos MFA (salvo el del primer
  enrolamiento), cookies, token CSRF ni token de invitación. Las cookies viven en archivos temporales con permiso 600 que se borran al terminar.
- **Resultado de la ejecución real (2026-10-05):** el smoke contra Supabase terminó en `SMOKE OK` (run id `SMOKE_20261005163410_pb3y`) y la limpieza suave se aplicó y se
  verificó: una segunda vista previa mostró 0 filas a modificar en todas las tablas. Estado comprobado después en la base (solo lectura): Flyway 1 a 4 con éxito; 2 familias, 3
  deportistas y 1 usuario FAMILIA del smoke, todos inactivos; 4 vínculos en `REVOCADO` y ninguno `ACTIVO`; el tutor del smoke sigue activo (la limpieza suave no lo toca); el ADMIN
  real sigue activo con MFA.
