# Familias, tutores, deportistas y vínculos — Banfield Patín Carrera

Contrato y reglas del módulo implementado en el cambio `familias-deportistas` (backend `backend/`, Spring Boot 4). Describe lo que el código hace hoy.
Todas las rutas devuelven JSON; los errores tienen siempre la forma `{codigo, mensaje, detalles}` y nunca incluyen trazas, SQL, nombres de restricciones ni
datos de otras personas. La estrategia de autenticación está en [SEGURIDAD.md](SEGURIDAD.md).

## 1. Convenciones comunes

- **Escuela e identidad:** salen siempre del JWT (`UsuarioAutenticado`). Ningún parámetro, cuerpo ni ruta puede cambiarlas: `escuelaId`, `familiaId`,
  `usuarioId`, `activo`, `estado`, `esPrincipal` y `autorizadoPor` enviados en un cuerpo se rechazan con `400 SOLICITUD_INVALIDA`, y en la query string se ignoran.
  Un id de otra escuela es indistinguible de uno inexistente (`404`).
- **Rutas `/api/admin/**`:** solo `ADMIN` con sesión completa (MFA). Toda ruta que escribe exige CSRF (`403 CSRF_INVALIDO` si falta).
- **Rutas `/api/familia/**`:** sólo `FAMILIA`. La primera escritura habilitable es `POST /api/familia/deportistas/{id}/documentos`, con CSRF por cabecera y habilitación explícita, apagada por defecto. No se habilitan otros workflows de escritura: métodos no implementados conservan `405 METODO_NO_PERMITIDO` con CSRF correcto o `403 CSRF_INVALIDO` sin él. Ver `DOCUMENTOS.md` para el alcance de la carga y sus límites.
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
es la especificación OpenAPI generada; esta tabla es su resumen legible y `OpenApiRutasTest` comprueba que las rutas de esta tabla estén en la especificación generada.

| Ruta | Rol | Solicitud | Respuesta | Errores |
|---|---|---|---|---|
| `GET /api/admin/deportistas/{id}/documentos` | ADMIN | `page`, `size` | `200` `Pagina<DocumentoAdminMetadatos>`; `version` decimal como cadena; solo vigentes y alcance del propietario; ver `DOCUMENTOS.md` | `404 DEPORTISTA_NO_ENCONTRADO` |
| `POST /api/admin/deportistas/{deportistaId}/documentos/{documentoId}/revision` | ADMIN con MFA | CSRF de escritura; `estado`, `versionEsperada` como cadena y `motivoObservacion` al observar | `200` `DocumentoAdminMetadatos` actualizado; solo DNI frente/dorso vigente | `400` entrada inválida, `401` sin sesión, `403` acceso o CSRF, `404 DOCUMENTO_NO_ENCONTRADO`, `409` conflicto de revisión; ver `DOCUMENTOS.md` |
| `POST /api/admin/tutores/{tutorId}/documentos/{documentoId}/revision` | ADMIN con MFA | CSRF de escritura; `estado`, `versionEsperada` como cadena y `motivoObservacion` al observar | `200` `DocumentoAdminMetadatos` actualizado; solo DNI frente/dorso vigente del tutor (U19) | `400` entrada inválida, `401` sin sesión, `403` acceso o CSRF, `404 DOCUMENTO_NO_ENCONTRADO`, `409` conflicto de revisión; ver `DOCUMENTOS.md` |
| `GET /api/admin/deportistas/{deportistaId}/documentos/{documentoId}/archivo` | ADMIN | cabecera `X-XSRF-TOKEN` obligatoria (D2) | `200` archivo binario (PDF, JPEG o PNG) verificado por tamaño y SHA-256, `attachment` con nombre neutro; ver `DOCUMENTOS.md` | `403 CSRF_INVALIDO`, `404 DOCUMENTO_NO_ENCONTRADO`, `409 INTEGRIDAD_NO_VERIFICABLE`, `429 DESCARGA_OCUPADA`, `502 INTEGRIDAD_FALLIDA`, `503 DESCARGA_NO_DISPONIBLE` |
| `GET /api/admin/tutores/{tutorId}/documentos/{documentoId}/archivo` | ADMIN | cabecera `X-XSRF-TOKEN` obligatoria (D2) | `200` archivo binario (PDF, JPEG o PNG) verificado por tamaño y SHA-256, `attachment` con nombre neutro; ver `DOCUMENTOS.md` | `403 CSRF_INVALIDO`, `404 DOCUMENTO_NO_ENCONTRADO`, `409 INTEGRIDAD_NO_VERIFICABLE`, `429 DESCARGA_OCUPADA`, `502 INTEGRIDAD_FALLIDA`, `503 DESCARGA_NO_DISPONIBLE` |
| `GET /api/admin/tutores/{id}/documentos` | ADMIN | `page`, `size` | `200` `Pagina<DocumentoAdminMetadatos>` (con `version` como cadena decimal, U19); sólo vigentes y alcance del propietario; ver `DOCUMENTOS.md` | `404 TUTOR_NO_ENCONTRADO` |
| `GET /api/admin/documentos/pendientes` | ADMIN | `titular` (opcional: `DEPORTISTA` o `TUTOR`), `page`, `size` | `200` `Pagina<DocumentoPendienteAdmin>`: documentos vigentes en estado `CARGADO` de la escuela de la sesión; ver `DOCUMENTOS.md` | `400` si `titular` no es del contrato |
| `GET /api/admin/deportistas/{id}/checklist-documental` | ADMIN | — | `200` `ChecklistDocumental` (parcial), el mismo cálculo; ver `DOCUMENTOS.md` | `404 DEPORTISTA_NO_ENCONTRADO` |
| `GET /api/familia/deportistas/{id}/documentos` | FAMILIA | `page`, `size` | `200` `Pagina<DocumentoMetadatos>`; sólo vigentes y alcance del propietario; ver `DOCUMENTOS.md` | `404 DEPORTISTA_NO_ENCONTRADO` |
| `GET /api/familia/deportistas/{id}/checklist-documental` | FAMILIA | — | `200` `ChecklistDocumental` (parcial: los dos lados del DNI del deportista); ver `DOCUMENTOS.md` | `404 DEPORTISTA_NO_ENCONTRADO` |
| `POST /api/familia/deportistas/{id}/documentos` | FAMILIA | multipart: archivo, tipo, temporadaId opcional; Idempotency-Key UUID | `201` DocumentoMetadatos; `200` repetición confirmada; habilitación explícita, ver `DOCUMENTOS.md` | `404`, `409`, `413`, `422`, `429`, `503`; CSRF obligatorio |
| `POST /api/familia/deportistas/{id}/documentos/{documentoId}/reemplazo` | FAMILIA | multipart: únicamente `archivo`; Idempotency-Key UUID; `documentoId` = DNI vigente y OBSERVADO del deportista | `201` DocumentoMetadatos del sucesor; `200` repetición confirmada; habilitación explícita, ver `DOCUMENTOS.md` | `404 DEPORTISTA_NO_ENCONTRADO o DOCUMENTO_NO_ENCONTRADO`, `409 OPERACION_NO_REPETIBLE o OPERACION_EN_CURSO`, `413`, `422`, `429`, `503`; CSRF obligatorio |
| `POST /api/familia/tutores/{id}/documentos` | FAMILIA | multipart: `archivo` y `tipo` (`DNI_TUTOR_FRENTE` o `DNI_TUTOR_DORSO`); Idempotency-Key UUID; el tutor debe ser activo y de la familia del usuario | `201` DocumentoMetadatos; `200` repetición confirmada; habilitación explícita, ver `DOCUMENTOS.md` | `404 TUTOR_NO_ENCONTRADO`, `409`, `413`, `422`, `429`, `503`; CSRF obligatorio |
| `POST /api/familia/tutores/{id}/documentos/{documentoId}/reemplazo` | FAMILIA | multipart: únicamente `archivo`; Idempotency-Key UUID; `documentoId` = DNI vigente y OBSERVADO del tutor | `201` DocumentoMetadatos del sucesor; `200` repetición confirmada; habilitación explícita, ver `DOCUMENTOS.md` | `404 TUTOR_NO_ENCONTRADO o DOCUMENTO_NO_ENCONTRADO`, `409 OPERACION_NO_REPETIBLE o OPERACION_EN_CURSO`, `413`, `422`, `429`, `503`; CSRF obligatorio |
| `GET /api/familia/tutores/{id}/documentos` | FAMILIA | `page`, `size` | `200` `Pagina<DocumentoMetadatos>`; sólo vigentes y alcance del propietario; ver `DOCUMENTOS.md` | `404 TUTOR_NO_ENCONTRADO` |
| `GET /api/familia/documentos/disponibilidad` | FAMILIA | — | `200` `{habilitada, maxBytes}`; refleja la configuración de la carga (no la salud del escáner ni de Storage); `maxBytes` es `null` si no está habilitada; ver `DOCUMENTOS.md` | — |
| `GET /api/admin/familias` | ADMIN | `page`, `size`, `busqueda` | `200` `Pagina<FamiliaResumen>` (solo activas; **ruta heredada, sin cambios**) | — |
| `GET /api/admin/familias/listado` | ADMIN | `estado` (por defecto `TODOS`), `busqueda`, `page`, `size` | `200` `Pagina<{id, nombreReferencia, activa, cantidadTutores, cantidadDeportistasActivos}>` | `400 SOLICITUD_INVALIDA` (estado desconocido) |
| `POST /api/admin/familias` | ADMIN | `{nombreReferencia}` (1..150) | `201` `FamiliaDetalle` + `Location` | `400 VALIDACION` |
| `GET /api/admin/familias/{id}` | ADMIN | — | `200` `{id, nombreReferencia, activa, tutores, version}` con el DNI de cada tutor **enmascarado** (`dniEnmascarado`; también de una familia inactiva) | `404 FAMILIA_NO_ENCONTRADA` |
| `PUT /api/admin/familias/{id}` | ADMIN | `{nombreReferencia, versionEsperada}` (reemplazo completo; permitido en una familia inactiva) | `200` `FamiliaDetalle` | `400`, `404 FAMILIA_NO_ENCONTRADA`, `409 EDICION_DESACTUALIZADA` o `CONFLICTO_CONCURRENCIA` |
| `POST /api/admin/familias/{id}/activar` | ADMIN | `{versionEsperada}` | `200` `FamiliaDetalle` (idempotente) | `400`, `404 FAMILIA_NO_ENCONTRADA`, `409 EDICION_DESACTUALIZADA` o `CONFLICTO_CONCURRENCIA` |
| `POST /api/admin/familias/{id}/desactivar` | ADMIN | `{versionEsperada}` | `200` `FamiliaDetalle` (idempotente; sin cascada) | `400`, `404 FAMILIA_NO_ENCONTRADA`, `409 EDICION_DESACTUALIZADA` o `CONFLICTO_CONCURRENCIA` |
| `POST /api/admin/familias/{familiaId}/tutores` | ADMIN | `{nombre, apellido, dni?, telefono?, email?, parentesco?, versionFamiliaEsperada}` | `201` `TutorRespuesta` + `Location` | `400`, `404 FAMILIA_NO_ENCONTRADA`, `409 FAMILIA_INACTIVA`, `409 EDICION_DESACTUALIZADA` o `CONFLICTO_CONCURRENCIA` |
| `GET /api/admin/tutores/{id}` | ADMIN | — | `200` `TutorRespuesta` con `version` (también de una familia inactiva) | `404 TUTOR_NO_ENCONTRADO` |
| `PUT /api/admin/tutores/{id}` | ADMIN | `{nombre, apellido, dni?, telefono?, email?, parentesco?, versionEsperada, versionFamiliaEsperada}` (reemplazo completo; la familia no cambia) | `200` `TutorRespuesta` | `400`, `404 TUTOR_NO_ENCONTRADO`, `409 FAMILIA_INACTIVA`, `409 EDICION_DESACTUALIZADA` o `CONFLICTO_CONCURRENCIA` |
| `POST /api/admin/tutores/{id}/activar` | ADMIN | `{versionEsperada}` | `200` `TutorRespuesta` (idempotente; no reabre vigencias) | `400`, `404 TUTOR_NO_ENCONTRADO`, `409 EDICION_DESACTUALIZADA` o `CONFLICTO_CONCURRENCIA` |
| `POST /api/admin/tutores/{id}/desactivar` | ADMIN | `{versionEsperada}` | `200` `TutorRespuesta` (idempotente; cierra vigencias) | `400`, `404 TUTOR_NO_ENCONTRADO`, `409 EDICION_DESACTUALIZADA` o `CONFLICTO_CONCURRENCIA` |
| `GET /api/admin/deportistas` | ADMIN | `estado` (por defecto `ACTIVOS`), `busqueda` (apellido, nombre, ambos órdenes o prefijo de DNI), `page`, `size` | `200` `Pagina<{id, nombre, apellido, dniEnmascarado, fechaNacimiento, activo}>` (el DNI completo **no** viaja en el listado; ver sección 11) | `400 SOLICITUD_INVALIDA` |
| `POST /api/admin/deportistas` | ADMIN | `{dni, nombre, apellido, cuil?, fechaNacimiento?, nacionalidad?, domicilio?, otrosDatosDomicilio?, localidad?, partido?, codigoPostal?, telefonoContacto?, emailFederativo?}` | `201` `DeportistaDetalle` + `Location` | `400 VALIDACION`, `409 DNI_DUPLICADO`, `409 DNI_RESERVADO_POR_INACTIVO`, `409 CUIL_DUPLICADO` |
| `GET /api/admin/deportistas/{id}` | ADMIN | — | `200` `DeportistaDetalle` (datos permanentes, `activo` y `version`) | `400` (id no UUID), `404 DEPORTISTA_NO_ENCONTRADO` |
| `PUT /api/admin/deportistas/{id}` | ADMIN | los mismos campos que el alta + `versionEsperada` (reemplazo completo; la revisión del detalle que se observó) | `200` `DeportistaDetalle` | `400 VALIDACION` (revisión ausente), `400 SOLICITUD_INVALIDA` (formato), `404 DEPORTISTA_NO_ENCONTRADO`, `409 EDICION_DESACTUALIZADA` o `CONFLICTO_CONCURRENCIA`, `409` (los tres de arriba) |
| `POST /api/admin/deportistas/{id}/activar` | ADMIN | `{versionEsperada}` | `200` `DeportistaDetalle` (idempotente; no toca vínculos) | `400`, `404 DEPORTISTA_NO_ENCONTRADO`, `409 EDICION_DESACTUALIZADA` o `CONFLICTO_CONCURRENCIA` |
| `POST /api/admin/deportistas/{id}/desactivar` | ADMIN | `{versionEsperada}` | `200` `DeportistaDetalle` (idempotente; no toca vínculos) | `400`, `404 DEPORTISTA_NO_ENCONTRADO`, `409 EDICION_DESACTUALIZADA` o `CONFLICTO_CONCURRENCIA` |
| `GET /api/admin/familias/{familiaId}/deportistas` | ADMIN | — | `200` `List<VinculoRespuesta>` (todos los estados) | `404 FAMILIA_NO_ENCONTRADA` |
| `GET /api/admin/deportistas/{deportistaId}/familias` | ADMIN | — | `200` `List<VinculoRespuesta>` (todos los estados) | `404 DEPORTISTA_NO_ENCONTRADO` |
| `GET /api/admin/deportistas/{deportistaId}/vinculos` | ADMIN | — | `200` `{deportistaId, versionVinculos, vinculos: [VinculoRespuesta]}` (todos los estados, mismo orden que `.../familias`; también sin vínculos o con el deportista inactivo; sección 14) | `400` (id no UUID), `404 DEPORTISTA_NO_ENCONTRADO` (uniforme) |
| `GET /api/admin/deportistas/{deportistaId}/vinculos-tutores` | ADMIN | `historial`, `page`, `size` | `200` `Pagina<VinculoTutorRespuesta>`; vigencias explícitas sin permisos nuevos, ver [VINCULOS-TUTORES.md](VINCULOS-TUTORES.md) | `400` (id no UUID), `404 DEPORTISTA_NO_ENCONTRADO` (uniforme) |
| `POST /api/admin/deportistas/{deportistaId}/vinculos-tutores` | ADMIN | cabecera `Idempotency-Key` (UUID); `tutorId`, `tipo` opcional, `versionFamilia`, `versionDeportista`, `versionTutor`, `versionVinculos` | `201` `VinculoTutorRespuesta` (la creada o, ante una repetición de la clave, la original aunque esté cerrada); `404 DEPORTISTA_NO_ENCONTRADO` o `TUTOR_NO_ENCONTRADO`; `409 EDICION_DESACTUALIZADA`, `CONFLICTO_CONCURRENCIA`, `ESTADO_INCOMPATIBLE`, `RESPONSABLE_YA_VIGENTE` o `IDEMPOTENCY_KEY_REUTILIZADA`; `503 ALTA_RESPONSABLES_DESHABILITADA` (bandera apagada por defecto); ver [VINCULOS-TUTORES.md](VINCULOS-TUTORES.md) |
| `PUT /api/admin/deportistas/{deportistaId}/vinculos-tutores/{vigenciaId}` | ADMIN | `tipo` (`null` lo deja sin declarar), `versionEsperada` | `200` `VinculoTutorRespuesta`; `404 RESPONSABLE_NO_ENCONTRADO`; `409 EDICION_DESACTUALIZADA`, `CONFLICTO_CONCURRENCIA` o `ESTADO_INCOMPATIBLE` (vigencia cerrada); sin cambio efectivo no escribe, audita ni mueve la revisión |
| `POST /api/admin/deportistas/{deportistaId}/vinculos-tutores/{vigenciaId}/revocar` | ADMIN | `versionEsperada` | `200` `VinculoTutorRespuesta` cerrada con causa `REVOCACION_ADMIN`; irreversible; `404 RESPONSABLE_NO_ENCONTRADO`; `409 EDICION_DESACTUALIZADA`, `CONFLICTO_CONCURRENCIA` o `ESTADO_INCOMPATIBLE` |
| `POST /api/admin/familias/{familiaId}/deportistas` | ADMIN | `{deportistas: [{deportistaId, versionEsperada}]}` (1..50, se quitan repetidos; todo o nada; sección 14) | `200` `{resultados: [{vinculo, resultado: CREADO\|REACTIVADO\|SIN_CAMBIOS}]}` en el orden pedido | `400`, `404 FAMILIA_NO_ENCONTRADA`, `409 FAMILIA_INACTIVA`, `404 DEPORTISTA_NO_ENCONTRADO`, `409 EDICION_DESACTUALIZADA` y `409 DEPORTISTA_INACTIVO` (con `detalles` `deportistas[i]`), `409 VINCULO_PRINCIPAL_EN_CONFLICTO`, `409 CONFLICTO_CONCURRENCIA` |
| `POST /api/admin/familias/{familiaId}/deportistas/{deportistaId}/revocar` | ADMIN | `{versionEsperada}` | `200` `VinculoRespuesta` (idempotente; permitido con familia inactiva) | `400`, `404 VINCULO_NO_ENCONTRADO`, `409 EDICION_DESACTUALIZADA`, `409 VINCULO_NO_ACTIVO` (PENDIENTE o RECHAZADO), `409 CONFLICTO_CONCURRENCIA` |
| `POST /api/admin/familias/{familiaId}/deportistas/{deportistaId}/principal` | ADMIN | `{versionEsperada}` | `200` `VinculoRespuesta` (idempotente) | `400`, `404 FAMILIA_NO_ENCONTRADA`, `409 FAMILIA_INACTIVA`, `404 VINCULO_NO_ENCONTRADO`, `409 EDICION_DESACTUALIZADA`, `409 VINCULO_NO_ACTIVO`, `409 VINCULO_PRINCIPAL_EN_CONFLICTO`, `409 CONFLICTO_CONCURRENCIA` |
| `GET /api/familia/mi-familia` | FAMILIA | — | `200` `{id, nombreReferencia, tutores: [{id, nombre, apellido, parentesco, telefono, email}]}` (solo tutores activos) | `404 FAMILIA_NO_ENCONTRADA` |
| `GET /api/familia/deportistas` | FAMILIA | `page`, `size` | `200` `Pagina<{id, nombre, apellido, edad, activo}>`; `edad` (entero o `null`) la calcula el servidor con la **misma regla que la ficha**; la fecha de nacimiento **no se envía** en esta respuesta (sigue almacenada y disponible para ADMIN) | — |
| `GET /api/familia/deportistas/{id}` | FAMILIA | — | `200` `{id, nombre, apellido, dni, cuil, fechaNacimiento, nacionalidad, domicilio, otrosDatosDomicilio, localidad, partido, codigoPostal, telefonoContacto, emailFederativo, activo}` | `400` (id no UUID), `404 DEPORTISTA_NO_ENCONTRADO` (uniforme) |
| `GET /api/familia/deportistas/{deportistaId}/temporadas` | FAMILIA | — | `200` `List<{temporadaId, anio, temporadaActiva, categoria, rama, divisional, numeroCorredor, estadoDeportivo}>` de las temporadas **visibles** (año descendente, sin paginar; `[]` si el deportista es propio y no tiene ninguna visible) | `400` (id no UUID), `404 DEPORTISTA_NO_ENCONTRADO` (uniforme) |
| `GET /api/familia/deportistas/{deportistaId}/temporadas/{temporadaId}` | FAMILIA | — | `200` `{temporadaId, anio, temporadaActiva, categoria, rama, divisional, numeroCorredor, estadoDeportivo}` | `400` (id no UUID), `404 DEPORTISTA_TEMPORADA_NO_ENCONTRADA` (uniforme: una sola respuesta para cualquier causa) |
| `GET /api/familia/deportistas/{deportistaId}/ficha` | FAMILIA | — | `200` `{nombre, apellido, edad, activo, temporadaVigente}`; `edad` entero o `null`; `temporadaVigente` es `{anio, categoria, rama, divisional, numeroCorredor, estadoDeportivo}` o `null` (lista cerrada: sin id, DNI, CUIL, fecha de nacimiento, nacionalidad, domicilio, teléfono ni correo) | `400` (id no UUID), `404 DEPORTISTA_NO_ENCONTRADO` (uniforme: el mismo cuerpo byte a byte que el de `/deportistas/{id}`) |

`DELETE` y `PATCH` no existen en ninguna ruta de este módulo (`405 METODO_NO_PERMITIDO`): una familia, un deportista o un vínculo se desactivan o revocan, nunca se
borran. `VinculoRespuesta` es `{vinculoId, familiaId, familiaNombre, deportistaId, deportistaNombre, deportistaApellido, deportistaActivo, estado, esPrincipal,
autorizadoEn, versionVinculos}`; no incluye quién autorizó ni el DNI. `versionVinculos` (cadena decimal) es la revisión de los vínculos del deportista de esa fila;
en las respuestas de las escrituras es la revisión posterior a la operación (sección 14).

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
| `EDICION_DESACTUALIZADA` | 409 | Vincular, revocar o cambiar el principal con una `versionEsperada` que no es la revisión vigente de los vínculos del deportista, aunque la operación fuera idempotente (sección 14). Mensaje fijo, sin ids, revisiones ni datos personales; en el lote, `detalles` lista las posiciones `deportistas[i]` |
| `CONFLICTO_CONCURRENCIA` | 409 | Otra transacción retuvo el deportista más tiempo que `banfield.vinculos.lock-timeout` (o PostgreSQL eligió esta transacción como víctima de un *deadlock*). No se escribió ni auditó nada. Mensaje fijo: «Otro cambio sobre el mismo deportista esta en curso. Reintentá en unos segundos.»; sin SQL, ids ni trazas. Solo las rutas de vínculos que bloquean (vincular, revocar, principal) pueden devolverlo |

Los `409` de DNI y CUIL, `EDICION_DESACTUALIZADA`, `VINCULO_PRINCIPAL_EN_CONFLICTO` y `CONFLICTO_CONCURRENCIA` nunca incluyen el nombre de la restricción, el id en conflicto ni el valor.

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
| F9 | Solo ADMIN puede activar o desactivar un tutor por comando versionado (C2-A). |
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
3. Cambiar el principal (`POST .../principal`) es la única vía: familia `404` → familia inactiva `409` → bloqueo → vínculo `404` → revisión `409 EDICION_DESACTUALIZADA`
   → `409 VINCULO_NO_ACTIVO` → si ya es el principal, `200` sin escribir → se baja el principal actual y se sube el elegido (en ese orden, cada uno con `flush`) →
   auditoría → revisión +1 (una sola vez).
4. Revocar (`POST .../revocar`): bloqueo → vínculo `404` → revisión `409 EDICION_DESACTUALIZADA` → PENDIENTE/RECHAZADO `409 VINCULO_NO_ACTIVO` → REVOCADO `200` sin
   escribir, auditar ni mover la revisión → ACTIVO: revoca (deja de ser principal, sin promover a otro), auditoría y revisión +1. No lee la familia.

| Estado actual \ acción | Vincular (ADMIN) | Revocar | Marcar principal |
|---|---|---|---|
| (sin fila) | crea ACTIVO (`CREADO`) | `404 VINCULO_NO_ENCONTRADO` | `404 VINCULO_NO_ENCONTRADO` |
| ACTIVO | sin cambios (`SIN_CAMBIOS`) | pasa a REVOCADO y deja de ser principal | promueve y baja al anterior (idempotente) |
| REVOCADO | reutiliza la fila → ACTIVO (`REACTIVADO`) | `200` idempotente, sin auditoría | `409 VINCULO_NO_ACTIVO` |
| PENDIENTE / RECHAZADO (esta API no los produce) | reutiliza la fila → ACTIVO (`REACTIVADO`) | `409 VINCULO_NO_ACTIVO` | `409 VINCULO_NO_ACTIVO` |

Orden de verificación al vincular: familia `404` → familia inactiva `409 FAMILIA_INACTIVA` (primero, incluso si el lote sería todo `SIN_CAMBIOS`) → bloqueo de las filas de
los deportistas → algún id inexistente o de otra escuela `404` → alguna revisión obsoleta `409 EDICION_DESACTUALIZADA` (aunque ese deportista diera `SIN_CAMBIOS`) →
algún deportista inactivo `409` → escritura → revisión +1 de cada deportista `CREADO` o `REACTIVADO`. Cualquier fallo no deja nada escrito, auditado ni ninguna revisión movida.
Todas las verificaciones de revisión usan la sección 14.

**Concurrencia:** todas las operaciones que cambian vínculos toman primero el bloqueo de fila del deportista (`FOR UPDATE`, en orden de id, de modo que lotes
superpuestos en orden inverso no se bloquean entre sí). Esa espera está acotada: las tres transacciones ejecutan `SET LOCAL lock_timeout` (propiedad
`banfield.vinculos.lock-timeout`, por defecto `PT3S`, válida entre 100 ms y 30 s; fuera de rango la aplicación no arranca) y, si vence, responden `409 CONFLICTO_CONCURRENCIA`.
`SET LOCAL` vale solo hasta el fin de esa transacción: no cambia nada del pool, de la sesión ni de otros servicios. El índice parcial único `uq_fd_principal_activo`
(V4) es la última red de seguridad. Desde U11-A la revisión de vínculos se compara **bajo ese mismo bloqueo** y se incrementa en la misma transacción (sección 14); no
hay bloqueos nuevos ni cambia el orden. Si el plazo vence, el `409 CONFLICTO_CONCURRENCIA` no mueve la revisión ni deja auditoría.

## 6. Portal de FAMILIA (solo lectura) y resumen de N1

- La FAMILIA ve su familia, sus tutores **activos** y únicamente los deportistas con vínculo ACTIVO (incluidos los inactivos, con `activo=false`).
- Cada lectura es **una sola sentencia** que une vínculo, familia y deportista por id **y** escuela y exige vínculo ACTIVO y familia activa; no se carga nada para
  comparar después. Un deportista de otra familia, de otra escuela, con vínculo revocado/pendiente/rechazado o inexistente produce exactamente el mismo
  `404 DEPORTISTA_NO_ENCONTRADO` (cuerpo idéntico byte a byte).
- Los DTO del portal omiten `escuelaId`, `esPrincipal`, `autorizadoPor/En`, marcas de tiempo, ids de vínculo, el DNI del tutor y el estado del tutor.
- **N1:** una familia inactiva, un usuario inactivo o inexistente o una escuela inactiva no llegan nunca al portal: la revalidación central responde `401 NO_AUTENTICADO`
  y borra la cookie en **todas** las rutas (incluida `/api/auth/me`); reactivar restablece la sesión sin nuevo login. Si la base no responde, `503` y la cookie queda
  intacta. El predicado `f.activa = true` se conserva en la consulta del portal como defensa en profundidad y se prueba solo a nivel de repositorio.
- **Datos deportivos anuales (`temp-3`):** `GET /api/familia/deportistas/{deportistaId}/temporadas[/{temporadaId}]` (solo lectura, regla de visibilidad y `404` uniforme por endpoint) se documenta en [TEMPORADAS.md](TEMPORADAS.md) sección 9; el historial ≤ 1 sentencia (2 si vino vacío) y el detalle ≤ 1.
- **Ficha mínima (`FE-04`):** `GET /api/familia/deportistas/{deportistaId}/ficha` reúne en una respuesta de lista cerrada el nombre, el apellido, la **edad calculada en el servidor**, `activo`
  (estado en la escuela) y la temporada **vigente** (la activa de la escuela; `estadoDeportivo` es el de la temporada y es otro plano que `activo`). La fecha de nacimiento
  **no sale del servidor**: la edad se cuenta a la fecha de hoy en `America/Argentina/Buenos_Aires`, el 29/2 cumple el 1/3 en los años sin 29/2 y es `null` si la fecha falta,
  es futura o supera 120 años. `temporadaVigente` es `null` (con `200`) si la escuela no tiene temporada activa o el deportista no tiene fila en ella, sin decir por qué; un
  deportista que no es de la familia da el `404` uniforme. Son **dos sentencias**, **cada una con el alcance completo de la familia** (vínculo ACTIVO, familia activa, misma familia
  y escuela del JWT; la visibilidad de temporadas reutiliza los fragmentos del historial), dentro de **una transacción de solo lectura `REPEATABLE_READ`**: ambas leen la misma
  instantánea. Consecuencia exacta: un cambio confirmado **antes** de la primera sentencia se ve (una revocación da `404`); uno confirmado **después** no se ve en esa solicitud
  (la respuesta es la de la instantánea, coherente, sin mezclar instantes) y la **solicitud siguiente** ya lo nota. Techo: ≤ 2 sentencias (1 si el resultado es `404`).
- **Edad en el listado (`FE-04`):** cada fila del listado trae `edad`, calculada en el servidor con la **misma regla única** que la ficha (`EdadDeportista`: hoy en
  `America/Argentina/Buenos_Aires`, el 29/2 cumple el 1/3 en los años sin 29/2, `null` si la fecha falta, es futura o supera 120 años) y con **una sola fecha de referencia por
  página**, de modo que el mismo deportista tiene la misma edad en el listado y en la ficha. La **fecha de nacimiento ya no sale** en esta respuesta (antes se enviaba junto con `edad`
  durante la transición, y se retiró cuando el cliente pasó a leer `edad`): la familia solo necesita la edad. La fecha **sigue siendo un dato esencial del dominio**
  (empadronamiento, licencias, categorías, Listas de Buena Fe): continúa almacenada, sin cambios, y disponible para ADMIN (`DeportistaDetalle`, `DeportistaSolicitud`); la
  minimización es solo de las respuestas del portal. Paginación, orden, alcance y autorización no cambiaron. Si una futura pantalla de consulta, verificación o actualización
  de datos justifica mostrar la fecha exacta a la familia autorizada, se evaluará entonces con su propio contrato mínimo.
- **Riesgo residual conocido:** `GET /api/familia/deportistas/{id}` (15 campos, con DNI, CUIL, fecha de nacimiento y domicilio) sigue existiendo, **no lo usa ninguna pantalla** y no
  se modificó. La ficha mínima no lo reemplaza ni lo restringe; cuando se diseñe una pantalla de consulta, verificación o actualización de datos se definirá un contrato mínimo propio
  por pantalla. La fecha de nacimiento sigue siendo un dato esencial del dominio (empadronamiento, licencias, categorías, Listas de Buena Fe): la minimización es solo de las
  respuestas del portal.
- **Cierre de `FE-04` (flujo, alcance y despliegue).** Flujo de la familia: login → `/familia` → listado de deportistas → ficha (`/familia/deportistas/:deportistaId`) → volver; la
  pantalla está descrita en el [README del frontend](../frontend/README.md). **Alcance:** solo lectura de la ficha mínima y la temporada vigente; no incluye licencia, empadronamiento,
  aptos físicos, historial de temporadas, edición ni la fecha exacta de nacimiento. **Evidencia automatizada:** pruebas de unidad, de integración con PostgreSQL real y 22 E2E reales
  de la ficha (contrato real, 404 uniforme, aislamiento, sesión vencida, móvil y un barrido de privacidad **limitado a las respuestas que esos E2E observaron**: ficha, listado y resumen de familia; el detalle de 15 campos no se prueba ni se minimiza); los E2E no prueban cookies cambiadas desde **otra pestaña** (riesgo
  residual documentado). **Compatibilidad de despliegue:** la transición sin degradación visual exige la secuencia **backend aditivo (ficha y `edad` con `fechaNacimiento`) →
  frontend nuevo (lee `edad`) → backend final (sin `fechaNacimiento` en el listado)**; con otro orden el listado muestra «Edad no informada» hasta completar la secuencia. No hay una
  estrategia de despliegue verificada que evite ese orden y **ningún despliegue productivo fue validado**.
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

- **Sin bloqueo optimista (histórico):** una edición (`PUT`) concurrente sobre el mismo registro gana la última escritura. Familia, tutor y deportista ya tienen revisión (sección 13) y, desde U11-A, los vínculos tienen la revisión agregada por deportista (sección 14). Los vínculos además se serializan por el bloqueo del deportista; los datos deportivos anuales, por el bloqueo de su fila (con la misma limitación: un `PUT` con datos viejos pisa un cambio posterior, ver TEMPORADAS.md sección 8).
- **Sin `unaccent`:** la búsqueda distingue acentos (no mayúsculas).
- **Datos de temporada fuera de este documento:** este documento cubre los datos permanentes del deportista, las familias, los tutores y los vínculos. Las temporadas, los datos deportivos anuales (`deportista_temporada`) y su lectura por FAMILIA se documentan en [TEMPORADAS.md](TEMPORADAS.md) (Fase 1, implementada); las licencias llegan en la Fase 5.
- **RF-06 (completitud de los datos del deportista) diferido:** el alta exige solo DNI, nombre y apellido; la validación de completitud para federar queda para el módulo de exportación.
- **Sin documentos, aptos físicos, competencias ni frontend** en este cambio.

## 11. DNI enmascarado en los listados (O8, `temp-4`)

El DNI completo solo viaja en los **detalles individuales** autorizados; los listados y los tutores anidados lo exponen **enmascarado**.

| Respuesta | Campo | Valor |
| --- | --- | --- |
| `GET /api/admin/deportistas` (`DeportistaResumen`) | `dniEnmascarado` | enmascarado |
| `tutores[]` dentro de `GET /api/admin/familias/{id}` y de las demás respuestas `FamiliaDetalle` (`TutorResumen`) | `dniEnmascarado` (`null` si el tutor no tiene DNI) | enmascarado |
| `GET/POST/PUT /api/admin/deportistas/{id}`, `…/activar`, `…/desactivar` (`DeportistaDetalle`) | `dni` | **completo** |
| `GET/POST/PUT /api/admin/tutores…` (`TutorRespuesta`) | `dni` | **completo** |
| Portal FAMILIA, detalle de **su** deportista (`DeportistaDeFamiliaDetalle`) | `dni` | **completo** (sin cambios) |
| Plantel, historial, vínculos, datos deportivos | — | no llevan DNI |

**Formato:** se muestran los **últimos tres dígitos** y el resto se reemplaza por `•` (U+2022), conservando el largo (`30111322` → `•••••322`; 7 dígitos → `••••322`; 9 dígitos → `••••••322`). Un valor de tres caracteres o menos se enmascara completo y `null` sigue siendo `null`. Nunca conviven `dni` y `dniEnmascarado` en un mismo objeto.

**No cambia:** la entidad `Deportista`, el almacenamiento (el DNI se guarda completo y normalizado), las validaciones (`DocumentoIdentidad`), la unicidad (`DNI_DUPLICADO`, `DNI_RESERVADO_POR_INACTIVO`) ni la **búsqueda**: un `busqueda` de solo dígitos (con o sin puntos) sigue resolviéndose por **prefijo** del DNI completo, también con el DNI entero.

**Límite conocido y mejora futura:** como lo que se ve son los últimos tres dígitos y la búsqueda es por prefijo, el administrador no puede buscar tipeando lo que ve (`322` no encuentra `30111322`). Una **búsqueda por sufijo** queda documentada como mejora futura; no se implementó (costo de índice y fuera del alcance de esta unidad).

**Regla para los procesos federativos (Fases 2, 3, 5 y 7: spike, documentos, empadronamiento, LBF):** necesitan el DNI completo y lo **leen en el servidor** desde la entidad `Deportista` o el repositorio, **nunca** desde un DTO de listado ni desde una respuesta HTTP al navegador. `DeportistaResumen` y `TutorResumen` existen para mostrar listas, no para alimentar procesos. Guardas: `ContratoDniTest` (solo una lista cerrada de DTO puede tener un componente `dni`; ningún `*Resumen` lo tiene; ningún DTO tiene `dni` y `dniEnmascarado` a la vez), las pruebas del JSON serializado (`JsonDeContrato`: sin clave `dni` y sin valor de texto que contenga el DNI completo, salvo UUID) y el esquema OpenAPI.

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

## 13. Revision optimista de Familia (FE-05 / U1)

`version` es la revision de escritura del nombre/estado de Familia, no de sus tutores ni vinculos.
El detalle ADMIN y las respuestas de alta/edicion/estado la devuelven como cadena decimal. No se agrega a listados,
resumenes de invitaciones ni DTO del portal FAMILIA. Los tutores anidados conservan el DNI enmascarado.

- Alta: `POST /api/admin/familias` conserva `{nombreReferencia}`; la revision inicial es `"0"`.
- Edicion: `PUT /api/admin/familias/{id}` exige `{nombreReferencia, versionEsperada}`.
- Estado: activar/desactivar exige `{versionEsperada}`. No hay cascadas.
- `versionEsperada` debe ser una cadena de 1 a 19 digitos ASCII, sin signo ni espacios, dentro de 0 a
  9223372036854775807. No se aceptan numeros JSON, decimales, exponentes, booleanos ni propiedades adicionales.
  Ausencia/nulo: `400 VALIDACION`; formato/tipo/rango invalido: `400 SOLICITUD_INVALIDA`.
- Revision vieja: `409 EDICION_DESACTUALIZADA`, incluso si el nombre o estado solicitado ya coincide. El error
  tiene mensaje fijo, sin datos personales, id ni revision vigente. No hay guardado forzado.
- Revision vigente y sin cambios: `200`, misma revision, sin UPDATE ni auditoria de exito adicional.
- Cambio real: revision incrementada por JPA y auditoria en la misma transaccion. `@Version` condiciona el UPDATE;
  `LockModeType.OPTIMISTIC` verifica tambien la version al terminar una transaccion sin cambios.
  Un conflicto de JPA al flush/commit produce el mismo 409 y revierte todos los efectos de esa transaccion.
  La contencion pesimista conserva el codigo distinto `CONFLICTO_CONCURRENCIA`.

La migracion V7 agrega `version BIGINT NOT NULL DEFAULT 0` y un CHECK no negativo. Inicializa registros anteriores
sin cambiar sus datos ni relaciones. Invitaciones sigue creando mediante `Familia.crear`, tambien con revision cero;
no crea automaticamente un usuario. V1–V6 permanecen intactas.

### Revisión optimista de Tutor (FE-05 / U4)

`TutorRespuesta` (GET, alta y edición individuales de un tutor) devuelve `version`, la revisión de escritura de los
datos del tutor, como cadena decimal. No se agrega al resumen del tutor dentro del detalle de familia (que conserva el DNI
enmascarado) ni a ningún DTO del portal FAMILIA. El estado del tutor se cambia solo mediante los comandos ADMIN de C2-A; la baja cierra sus vigencias y la reactivación no reabre el historial.

- Alta: `POST /api/admin/familias/{familiaId}/tutores` exige además `versionFamiliaEsperada`, la revisión de la familia que se observó. La revisión inicial del tutor es `"0"`.
- Edición: `PUT /api/admin/tutores/{id}` exige `versionEsperada` (del tutor) y `versionFamiliaEsperada` (de su familia).
- Estado: los comandos `activar` y `desactivar` exigen solo `versionEsperada` del tutor como cadena decimal. No incrementan la revisión de la familia ni cambian sus vínculos.
- Ambas son cadenas de 1 a 19 dígitos ASCII, como en Familia. Ausencia o nulo: `400 VALIDACION`; formato, tipo o rango inválido: `400 SOLICITUD_INVALIDA`. No se aceptan propiedades adicionales.
- Dos revisiones **independientes**: la del tutor avanza solo cuando cambian sus datos; la de la familia sigue siendo la de nombre y estado. Crear o editar un tutor **no** incrementa la de la familia.
- Orden de comprobaciones: `404` (familia o tutor, mismo `404` para otra escuela) → `409 FAMILIA_INACTIVA` (se conserva; va antes que la revisión, así una familia inactiva rechaza todo, incluso con revisiones viejas) → revisión de la familia → revisión del tutor → escritura. Revisión vieja: `409 EDICION_DESACTUALIZADA`, incluso si los datos coinciden; mensaje fijo, sin ids ni datos personales.
- Revisiones vigentes y sin cambios: `200`, misma revisión, sin `UPDATE` ni auditoría.
- Atomicidad: la comprobación de la revisión de la familia se acompaña de un bloqueo `PESSIMISTIC_READ` (`FOR SHARE`) sobre su fila, con el predicado de versión de Hibernate. Una desactivación o un renombre concurrentes no pueden confirmarse mientras se escribe el tutor y, si ya se confirmaron, la escritura se rechaza con `409`; sin ese bloqueo quedaría una ventana en la que la familia se desactiva entre la lectura y el `INSERT`. Varias altas o ediciones en la misma familia no se serializan entre sí. El tutor se protege con `@Version` (un único ganador) y `LockModeType.OPTIMISTIC` (un «sin cambios» también falla si el tutor cambió antes del commit). Un conflicto de JPA revierte datos, versiones y auditoría de la transacción. La contención de base conserva `409 CONFLICTO_CONCURRENCIA`. No se agrega `lock_timeout`: una espera no acotada solo ocurriría si otra transacción retuviera la fila de la familia sin terminar (riesgo residual, igual que en Familia).

La migración V8 agrega `version BIGINT NOT NULL DEFAULT 0` y un CHECK no negativo a `tutor`, sin cambiar datos ni relaciones. V1–V7 permanecen intactas. `Tutor` solo se escribe a través de este servicio (no hay otros escritores ni SQL nativo).

### Revisión optimista de Deportista (FE-05 / U6)

`DeportistaDetalle` (GET, alta, edición y cambios de estado) devuelve `version`, la revisión de escritura de los **datos
personales y el estado** del deportista, como cadena decimal. No se agrega al resumen del listado ni a ningún DTO del portal
FAMILIA. La revisión **no cubre** los vínculos con familias ni las filas de temporada, que tienen su propio ciclo de vida:
vincular, revocar o cargar documentos no la mueven.

- Alta: `POST /api/admin/deportistas` conserva su cuerpo (un deportista nuevo no tiene revisión que esperar); la revisión inicial es `"0"` y una `versionEsperada` en el alta se rechaza como propiedad no admitida.
- Edición: `PUT /api/admin/deportistas/{id}` exige los mismos campos que el alta más `versionEsperada`. Estado: activar y desactivar exigen `{versionEsperada}`. Desactivar sigue sin revocar vínculos ni borrar historial.
- `versionEsperada` es una cadena de 1 a 19 dígitos ASCII, como en Familia y Tutor. Ausencia o nulo: `400 VALIDACION`; formato, tipo o rango inválido: `400 SOLICITUD_INVALIDA`. No se aceptan propiedades adicionales.
- Orden de comprobaciones: `400` → `404` (otra escuela = mismo `404`, con cualquier revisión) → revisión (`409 EDICION_DESACTUALIZADA`, incluso si los datos o el estado ya coinciden; mensaje fijo, sin ids ni datos personales) → unicidad de DNI/CUIL (`409 DNI_DUPLICADO`, `DNI_RESERVADO_POR_INACTIVO`, `CUIL_DUPLICADO`) → escritura. La revisión va antes que la unicidad para que un formulario viejo no aprenda, por un `DNI_DUPLICADO`, que otro deportista tiene ese DNI.
- Revisión vigente y sin cambios: `200`, misma revisión, sin `UPDATE` ni auditoría.
- Concurrencia: `@Version` condiciona el `UPDATE` (dos escrituras sobre la misma revisión dejan un único ganador, también una edición contra una activación o desactivación) y `LockModeType.OPTIMISTIC` hace que un «sin cambios» también falle si el deportista cambió antes del commit. Un conflicto de JPA revierte datos, versión y auditoría de la transacción. La contención de base conserva `409 CONFLICTO_CONCURRENCIA`. Deportista no tiene un recurso padre que condicione sus escrituras: no hay bloqueo de otra fila ni revisión adicional. No se agrega `lock_timeout` (riesgo residual, igual que en Familia y Tutor).
- Interacción con vincular y la carga de documentos: esos flujos bloquean la fila del deportista `FOR UPDATE`; la carga la lee sin modificarla y, desde U11-A, una operación de vínculos con cambio efectivo solo actualiza `version_vinculos` (sección 14), nunca `version`. Una desactivación concurrente espera a que terminen y se aplica con la misma revisión, sin reescribir `version_vinculos`.

La migración V9 agrega `version BIGINT NOT NULL DEFAULT 0` y un CHECK no negativo a `deportista`, sin cambiar datos ni relaciones. V1–V8 permanecen intactas. `Deportista` solo se escribe a través de `DeportistaAdminService` (no hay otros escritores ni SQL nativo).

### Compatibilidad y despliegue

El campo del detalle es aditivo; los cuerpos de PUT y cambios de estado cambian intencionalmente. Los consumidores
conocidos de esas escrituras eran pruebas backend y se adaptan en U1. El frontend FE-01–FE-04 no las utiliza:
Invitaciones consulta el listado legacy y su alta no cambia. Desde FE-05/U3 el frontend ADMIN sí las utiliza (editar el
nombre, activar y desactivar desde el detalle): manda la revisión que vio al abrir la edición o la confirmación y ante un
`409 EDICION_DESACTUALIZADA` no la renueva sola ni fuerza el guardado. Los clientes API externos deben obtener el detalle y
mandar la revision observada. Nunca asumir que ausencia de revision autoriza una escritura.

No mezclar instancias antiguas que permitan escrituras sin revision con las nuevas durante el corte. Aplicar V7
antes de iniciar la version nueva; coordinar el retiro de escritores antiguos y la actualizacion de clientes antes de
habilitar el contrato obligatorio. Orden para el frontend con edición (FE-05/U3): V7, luego el backend con el contrato
de revisión y recién después ese frontend; contra un backend anterior, el cuerpo con `versionEsperada` se rechaza como
propiedad no admitida. No existe un modo permanente de compatibilidad sin version. Las escrituras SQL
manuales no quedan protegidas por JPA: no son un canal administrativo soportado.

Tutores (FE-05/U4): el contrato de escritura de tutores también cambia de forma intencional (revisiones obligatorias; ver arriba). Orden: aplicar V8 antes de iniciar la versión nueva (V7 ya es previa), luego el backend nuevo y recién después cualquier cliente que edite tutores (FE-05/U5). Contra un backend anterior, un cuerpo con `versionEsperada` o `versionFamiliaEsperada` se rechaza como propiedad no admitida; contra el nuevo, un cliente anterior sin revisiones recibe `400 VALIDACION`. No existe un modo de compatibilidad sin revisión; no mezclar instancias antiguas y nuevas escribiendo tutores. Los consumidores de lectura no cambian: `version` es un campo adicional de `TutorRespuesta`.

Deportistas (FE-05/U6): el contrato de escritura de deportistas también cambia de forma intencional (`versionEsperada` obligatoria al editar, activar y desactivar; ver arriba). Orden: aplicar V9 antes de iniciar la versión nueva (V8 ya es previa), luego el backend nuevo y recién después cualquier cliente que edite o cambie el estado de deportistas. Contra un backend anterior, un cuerpo con `versionEsperada` se rechaza como propiedad no admitida; contra el nuevo, un cliente anterior sin revisión recibe `400 VALIDACION` (editar) o `400 SOLICITUD_INVALIDA` (activar o desactivar sin cuerpo). No existe un modo de compatibilidad sin revisión; no mezclar instancias antiguas y nuevas escribiendo deportistas. Los consumidores de lectura y el alta no cambian: `version` es un campo adicional de `DeportistaDetalle`. Los consumidores conocidos de las escrituras que cambian son el seed de los E2E (`frontend/e2e/support/seed.ts`, adaptado) y el paso de desactivación del deportista de `backend/scripts/smoke-familias.sh`, que **no** se adaptó en esta unidad (queda con el paso 12 pendiente de su PR de mantenimiento independiente).

Ante timeout de escritura el resultado es incierto: consultar antes de decidir un nuevo intento. Ante conflicto,
recargar explicitamente y revisar los cambios; no sustituir automaticamente la revision de un formulario viejo.

Pruebas focales: `FamiliaGestionAdminControllerWebMvcTest`, `FamiliaAdminServiceTest`, `OpenApiVersionFamiliaTest`,
`FamiliaConcurrenciaDbTest` y `V7VersionFamiliaDbTest`. Las pruebas concurrentes retienen una transaccion y comprueban
el bloqueo real en `pg_stat_activity` antes de liberar el commit; no dependen solo de temporizadores.

## 14. Revisión de los vínculos (FE-05 / U11-A)

`deportista.version_vinculos` es una revisión optimista **agregada por deportista**: cubre **todos** sus vínculos familia-deportista (con cualquier familia), no una fila.
Protege vincular, revocar y cambiar el principal contra operaciones preparadas sobre información desactualizada. Es independiente de `version` (datos permanentes y estado,
sección 13): una operación de vínculos **nunca** mueve `version`, y editar, activar o desactivar el deportista **nunca** mueve `version_vinculos` (F4: no tocan vínculos).
El principal sigue siendo una propiedad del vínculo; no existe un tutor principal.

- **Lectura:** `GET /api/admin/deportistas/{deportistaId}/vinculos` devuelve `{deportistaId, versionVinculos, vinculos}` con todos los estados, en el orden de
  `.../familias`. Funciona sin vínculos (`vinculos: []`) y con un deportista inactivo. Revisión y lista salen de la **misma instantánea**: dos sentencias dentro de una
  transacción de solo lectura `REPEATABLE_READ`. Otra escuela o inexistente: `404 DEPORTISTA_NO_ENCONTRADO` uniforme. Además, cada `VinculoRespuesta` (en los dos listados y
  en las respuestas de las escrituras) trae `versionVinculos`; en las escrituras es la revisión **posterior** a la operación.
- **Escrituras:** revocar y principal exigen `{versionEsperada}`; el lote exige `{deportistas: [{deportistaId, versionEsperada}]}` (1..50 elementos, contados antes de quitar
  repetidos). `deportistaIds` ya no se acepta (`400 SOLICITUD_INVALIDA`, propiedad desconocida). Un deportista repetido con la misma `versionEsperada` se deduplica (queda la
  primera posición); con revisiones distintas la solicitud es ambigua: `400 SOLICITUD_INVALIDA`, sin eco de valores.
- **Validación:** `versionEsperada` es una cadena de 1 a 19 dígitos ASCII dentro de 0..9223372036854775807, como en Familia, Tutor y Deportista. Ausente o nula: `400 VALIDACION`
  (en el lote, el campo es `deportistas[i].versionEsperada`); formato, tipo o rango inválido, o sin cuerpo en revocar o principal: `400 SOLICITUD_INVALIDA`. Sin propiedades adicionales.
- **Comparación bajo el bloqueo:** la revisión esperada se compara con la leída **después** de tomar el bloqueo de fila del deportista (`FOR UPDATE`, orden de id,
  `lock_timeout`), con una consulta escalar que va siempre a la base (nunca el valor de una entidad `Deportista` del contexto de persistencia). Con la fila bloqueada nadie puede
  cambiarla hasta el commit. La entidad mapea la columna como de solo lectura (`insertable = false, updatable = false`): un alta toma el valor por defecto y una edición o un cambio
  de estado no la reescriben con un valor viejo.
- **Obsoleta:** una revisión distinta de la vigente es `409 EDICION_DESACTUALIZADA` **aunque la operación sea idempotente** (revocar un REVOCADO, marcar al que ya es principal,
  un lote todo `SIN_CAMBIOS`). Mensaje fijo, sin ids, revisiones ni datos personales. Si el bloqueo no se obtiene a tiempo, el código sigue siendo `409 CONFLICTO_CONCURRENCIA`.
- **Incremento:** solo con un cambio efectivo, **+1 por deportista y por operación**, en la misma transacción, después de las escrituras y con el predicado de escuela: revocar un
  ACTIVO, cambiar el principal (una vez, aunque se escriban dos filas) y cada deportista `CREADO` o `REACTIVADO` del lote (una reactivación restaura el acceso de la familia).
  `SIN_CAMBIOS` y las repeticiones idempotentes con la revisión vigente responden `200` sin escribir, sin auditoría y sin mover la revisión. Cualquier error revierte vínculos,
  revisiones y auditoría.
- **Consecuencia esperada:** como la revisión es del deportista, dos operaciones preparadas a la vez sobre el mismo deportista (aunque toquen familias distintas) dejan un único
  ganador; la otra recibe `409 EDICION_DESACTUALIZADA` y el cliente debe volver a leer la revisión (`GET .../vinculos`) y decidir de nuevo. No hay reintento ni guardado forzado.
- **Documentos:** la carga de la FAMILIA no lee ni cambia la revisión. Una revocación con su revisión, confirmada durante la transferencia o el análisis de una carga, sigue
  impidiendo la publicación; los bloqueos y su orden no cambiaron.
- **Desbordamiento:** el contador no se trata especialmente en el máximo de BIGINT; es inalcanzable en la práctica (riesgo aceptado). Si se alcanzara, el CHECK y el tipo harían fallar
  la escritura sin dejar cambios.

**Migración V11** (`V11__versionar_vinculos_deportista.sql`): agrega `version_vinculos BIGINT NOT NULL DEFAULT 0` y el CHECK `ck_deportista_version_vinculos_no_negativa`
(`>= 0`). Los deportistas existentes quedan con `0`; no cambia datos, `version`, estados ni vínculos. V1–V10 permanecen intactas. Probada solo con PostgreSQL descartable
(Testcontainers); **no se ejecutó sobre Supabase ni sobre bases reales**.

**Compatibilidad y despliegue:** el contrato de escritura de los vínculos cambia de forma intencional; la lectura nueva y `versionVinculos` en `VinculoRespuesta` son aditivos.
Orden: aplicar V11 antes de iniciar la versión nueva, luego el backend nuevo y recién después cualquier cliente que escriba vínculos. Contra un backend anterior, los cuerpos nuevos se
rechazan (propiedad no admitida); contra el nuevo, un cliente anterior recibe `400`. **No mezclar** instancias viejas (que escriben vínculos sin incrementar la revisión) con las nuevas:
una escritura de una instancia vieja no movería la revisión y un formulario viejo podría confirmarse. No existe un modo de compatibilidad sin revisión. El único consumidor conocido de
estas escrituras fuera del backend es la semilla de los E2E (`frontend/e2e/support/seed.ts`), adaptada para leer la revisión con la ruta nueva; ninguna pantalla del frontend usa estas
rutas. El smoke manual `backend/scripts/smoke-familias.sh` se adaptó en un commit posterior de la unidad: sus pasos de vínculos (lote, cambio de principal y revocación) leen la revisión con `GET /api/admin/deportistas/{id}/vinculos` justo antes de cada escritura y no reutilizan una anterior. Su paso de desactivación de deportistas sigue con el cuerpo anterior desde U6 (rotura histórica, fuera de esta unidad). Las escrituras
SQL manuales sobre `familia_deportista` no mueven la revisión: no son un canal administrativo soportado.

Pruebas focales: `VinculoAdminServiceTest`, `VinculoAdminControllerWebMvcTest`, `OpenApiRutasTest`, `V11VersionVinculosDbTest`, `VinculosRevisionDbTest`,
`VinculosRevisionConcurrenciaDbTest` y las de concurrencia, bloqueo y carga documental existentes adaptadas al contrato.
