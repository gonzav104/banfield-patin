# U21-BIS — Autoservicio y empadronamiento familiar (diseño, sin implementar)

Estado: **decisión funcional aprobada; diseño completo pendiente de aprobación**. Relevamiento de solo lectura sobre `main` @ `71e8c58`. No se implementó nada. Esta unidad va **antes de U22**.

## 0. Decisión aprobada (2026-11-27)

- **Alta de deportistas por solicitud familiar con verificación de ADMIN.** No hay creación directa de deportistas por la familia.
- La experiencia debe ser sencilla para la familia y minimizar el trabajo manual de ADMIN: **ADMIN no vuelve a tipear lo que la familia ya aportó**; aprueba, observa o rechaza.
- Requisitos que el diseño cubre (ver la tabla de escenarios de la sección 3): invitar a una familia sin datos previos; la familia declara datos de responsables y deportistas; una madre completa los datos del padre sin que este tenga cuenta; ambos padres con cuentas independientes en una familia; familias independientes vinculadas al mismo deportista; un único responsable; otros adultos responsables; varios deportistas por familia; sin duplicados por DNI; sin revelar por el formulario, los mensajes o la API si un DNI ya existe; sin acceso automático por declarar un parentesco o conocer un DNI; idempotencia y concurrencia.

## 1. Principio rector: cuatro planos que no se mezclan

> **Declarar no concede acceso.** La familia solo escribe en el plano 1 y *solicita* en el 3. El plano 4 lo escribe únicamente ADMIN.

| Plano | Qué es | Dónde vive | Quién escribe |
|---|---|---|---|
| 1. Datos declarados | Datos de personas (tutores, deportistas) con su estado de verificación | `tutor` (+columnas nuevas), `solicitud_familiar` | FAMILIA declara · ADMIN verifica |
| 2. Identidad autenticada | Quién inició sesión: cuenta, familia, rol | `usuario` (`UsuarioAutenticado`) | Registro por invitación de ADMIN |
| 3. Responsabilidad declarada | «X es MADRE/PADRE/TUTOR_LEGAL/OTRO de Y»; no acredita representación | `tutor_deportista` | FAMILIA solicita · ADMIN confirma |
| 4. Permisos efectivos | Qué familia ve a qué deportista | `familia_deportista` `ACTIVO` (+`autorizado_por`), rol | **Solo ADMIN** |

Garantías estructurales ya existentes que el diseño reutiliza: `ck_fd_activo_autorizado` (V4) impide un vínculo `ACTIVO` sin `autorizado_por/en`; `tutor_deportista` solo se escribe por funciones `SECURITY DEFINER`; el portal calcula el alcance en el `WHERE`.

## 2. Evidencia del estado actual (relevamiento)

- **Varios usuarios por familia:** `usuario.familia_id` no es único; solo `lower(email)` por escuela (`uq_usuario_email_escuela`). Registro solo por invitación de ADMIN (`POST /api/admin/invitaciones`; `RegistroPorInvitacionService`); una invitación = un usuario. La invitación ya tiene `deportista_id` y un gancho `vinculacion.alRegistrar` cuya implementación por defecto es **no-op**: no se usa para dar acceso y no se debe usar.
- **Cuenta ↔ tutor:** `tutor.usuario_id` existe, es opcional y único (`uq_tutor_usuario`), **pero no se mapea ni se gestiona**. La identidad autenticada no se relaciona hoy con ningún tutor.
- **Tutores:** los crea/edita solo ADMIN; DNI opcional y **no único**; sin cuenta por defecto; cero, uno o varios por familia, sin mínimo.
- **Deportistas:** los crea/edita solo ADMIN. DNI (y CUIL) **únicos por escuela**, en texto plano normalizado por la aplicación (`DocumentoIdentidad`). Un alta duplicada responde `DNI_DUPLICADO` / `DNI_RESERVADO_POR_INACTIVO`: **sería un oráculo si se lo mostráramos a una familia**.
- **Vínculos:** varias familias pueden estar `ACTIVO` en el mismo deportista (un solo principal); `PENDIENTE`/`RECHAZADO` existen en la base y ningún flujo los produce; solo ADMIN vincula/revoca.
- **Carga documental:** exige `familia_deportista ACTIVO` de la familia de la sesión sobre el deportista (`OperacionesCargaJdbc.autorizar`, con bloqueo `FOR SHARE`). Sin vínculo `ACTIVO` no hay carga.
- **Concurrencia y patrones reutilizables:** `version` optimista (`409 EDICION_DESACTUALIZADA`) en familia, tutor, deportista, vínculos y documentos; idempotencia por `solicitud_id` + `huella_solicitud` (`tutor_deportista`) y por `Idempotency-Key` (cargas); `deportistas.bloquearParaVincular` para ordenar bloqueos; bandeja y revisión con CAS (`documentos/pendientes`, `RevisionDocumentoService`).
- **Banderas:** `@ConfigurationProperties` + `503` cuando está apagada (`banfield.responsables.alta-habilitada`) y `@ConditionalOnProperty` (`banfield.documentos.carga.habilitada`).
- **Límites de frecuencia:** solo existe `LimitadorIntentosLogin` (en memoria, por IP/correo; lo usan login, MFA y validar invitación) y topes de concurrencia de carga. **No hay limitador genérico** ni por familia.
- **Permisos de base:** las concesiones del rol `banfield_runtime` viven **fuera de Flyway** (`backend/ops/postgresql/autorizar-runtime-*.sql`); cada tabla nueva necesita su script de concesión.
- **Cuentas:** ADMIN no puede listar ni desactivar usuarios FAMILIA (`usuario.activo` existe sin endpoint).

## 3. Escenarios

| # | Escenario | Resolución |
|---|---|---|
| 1 | **Familia nueva** | ADMIN invita (correo, sin más datos); la persona se registra; onboarding «Mi familia» (4.1). |
| 2 | **Segundo usuario en familia existente** | ADMIN invita con `familiaId`; el nuevo usuario declara «soy este tutor» o crea su perfil (4.7). |
| 3 | **La madre carga al padre, sin cuenta** | Crea un `tutor` sin `usuario_id` en su familia, origen `FAMILIA`, estado `DECLARADO` (4.3). El padre no necesita cuenta ni ve nada. |
| 4 | **Ambos padres con cuenta, misma familia** | Dos usuarios + dos tutores con `usuario_id` propio. El acceso sigue siendo **por familia**, no por tutor. |
| 5 | **Padres separados, familias independientes** | Dos familias `ACTIVO` sobre el mismo deportista (el modelo ya lo admite). Cada una ve y declara **solo lo suyo** (4.8). |
| 6 | **Un solo adulto responsable** | Se mantiene: no se exige madre y padre. |
| 7 | **Abuelos / tutores legales / otros** | Tutor declarado; tipo de responsabilidad por solicitud (C4). No acredita representación. |
| 8 | **Varios deportistas por familia** | Una solicitud por deportista; mismo alcance por familia. |
| 9 | **Carga y actualización de datos personales** | 4.4. |
| 10 | **Responsabilidades por deportista** | La familia solicita; ADMIN confirma (C4). |
| 11 | **Duplicados** | 4.5. |
| 12 | **Verificación administrativa** | `DECLARADO → VERIFICADO / OBSERVADO` y bandeja de solicitudes (4.9). |
| 13 | **Concurrencia en una familia** | 4.10. |
| 14 | **Protección de otras familias** | 4.8 y sección 6. |

## 4. Diseño

### 4.1 Flujo de incorporación de una familia nueva (punto 1)

1. **ADMIN invita** con el correo (el nombre de referencia de la familia es opcional; si falta, se completa con el apellido de quien se registra). No hace falta ningún dato previo del deportista ni de los padres. *(A confirmar en C1: hoy `nuevaFamilia` pide un nombre de referencia.)*
2. La persona se registra con el token (flujo actual, sin cambios) y entra a **«Mi familia»**: un asistente con cuatro pasos y estado visible — **Mis datos → Otros adultos → Deportistas → Responsabilidades**. Ningún paso es obligatorio ni bloquea el resto.
3. **Mis datos:** crea su perfil de tutor (`origen=FAMILIA`, `usuario_id` = su cuenta, vínculo `DECLARADO`).
4. **Otros adultos:** carga a la pareja, abuelos u otros responsables como tutores sin cuenta. Si el otro adulto quiere cuenta propia, ADMIN lo invita aparte (escenarios 2 y 4).
5. **Deportistas:** envía una **solicitud de alta** por cada deportista con todos sus datos y la relación declarada del solicitante (4.2).
6. ADMIN resuelve desde su bandeja; al **aprobar** nace el vínculo `ACTIVO` (acceso) y recién entonces la familia ve la ficha y puede cargar documentación (4.11).

### 4.2 Solicitudes: estados y ciclo de vida (punto 2)

Tabla `solicitud_familiar` (sobre común) con `tipo` ∈ `ALTA_DEPORTISTA` (C3), `RESPONSABILIDAD` (C4), `CORRECCION_IDENTIDAD` (C5).

| Estado | Significado | Quién lo produce | Salidas |
|---|---|---|---|
| `PENDIENTE` | Enviada, a la espera de ADMIN | FAMILIA (al enviar o reenviar) | `APROBADA`, `RECHAZADA`, `OBSERVADA`, `CANCELADA` |
| `OBSERVADA` | ADMIN pide una corrección; devuelve un **motivo** | ADMIN | `PENDIENTE` (la familia corrige y reenvía), `CANCELADA` |
| `APROBADA` | Resuelta con efecto (terminal) | ADMIN | — |
| `RECHAZADA` | Resuelta sin efecto, con motivo codificado (terminal) | ADMIN | — |
| `CANCELADA` | La familia la retira (terminal) | FAMILIA | — |

- Toda transición es un **CAS** sobre `version` y `estado` (`UPDATE … WHERE version = :v AND estado = :origen`), igual que la revisión de documentos.
- **ADMIN resuelve enviando la `versionEsperada` que vio:** si la familia editó mientras tanto, la resolución falla con `409 EDICION_DESACTUALIZADA` y ADMIN revisa lo nuevo. Nunca se aprueba un dato que ADMIN no vio.
- La familia puede **editar una solicitud abierta** (`PENDIENTE`/`OBSERVADA`): sube la `version` y vuelve a `PENDIENTE`. Una vez terminal no se modifica.
- `APROBADA` guarda el **resultado** (`deportista_id`, `vinculo` creado) para trazabilidad; la familia ve «Aprobada» y el deportista recién aparece en su lista **porque ADMIN creó el vínculo**, no por la solicitud.
- **Motivos codificados** (lista cerrada con texto neutro, p. ej. `DATOS_INCOMPLETOS`, `NO_PUDIMOS_VERIFICAR`, `CONTACTE_AL_CLUB`) más un detalle libre **solo visible para la familia solicitante**. El motivo nunca puede aludir a otra familia ni a la existencia de un DNI; ADMIN ve una advertencia al escribirlo.

### 4.3 Qué puede declarar o modificar la familia (punto 3)

| Dato | FAMILIA | Condición |
|---|---|---|
| Tutor **propio** (nombre, apellido, DNI, teléfono, correo, domicilio) | Declara y edita directo | `version` + auditoría de campos; queda `DECLARADO` hasta que ADMIN verifica |
| Tutores sin cuenta de **su familia** (pareja, abuelos) | Declara y edita directo | Ídem; máximo por familia (4.5) |
| Contacto/domicilio de un deportista **solo suyo** | Edita directo (C5) | Teléfono, correo, domicilio, localidad, partido, código postal |
| **Alta de un deportista** | **Solo solicitud** (C3) | Todos los datos en la solicitud |
| Contacto/domicilio de un deportista **compartido** (≥2 familias `ACTIVO`) | Solo solicitud | Evita que una familia pise a la otra |
| Responsabilidad (tipo declarado) | Solo solicitud (C4) | |
| Nunca | Estado activo/inactivo, vínculos, temporadas, datos deportivos, cuentas, datos de otras familias | |

### 4.4 Qué requiere verificación administrativa (punto 4)

- **Siempre ADMIN:** crear o vincular un deportista, confirmar una responsabilidad, corregir **identidad** ya verificada (nombre, apellido, DNI, CUIL, fecha de nacimiento), vincular/revocar, activar/desactivar, cuentas.
- **Declarado sin verificación previa, pero marcado:** el tutor que carga la familia. No concede acceso ni responsabilidad; solo es información visible para ADMIN con su estado. ADMIN lo marca `VERIFICADO` u `OBSERVADO` (con motivo) desde la bandeja, sin tipear.
- **Verificar es opcional para operar:** un tutor `DECLARADO` aparece en el portal de su familia y puede ser el objeto de una solicitud de responsabilidad; la confirmación de ADMIN sobre la solicitud es el control real.
- Lo que hace ADMIN nace `VERIFICADO` (`origen=ADMIN`).

### 4.5 Prevención de duplicados sin oráculo (punto 6)

**Regla:** la familia **nunca consulta** si un DNI existe. Enviar una solicitud **no ejecuta ninguna consulta contra `deportista`**: el camino de código, el cuerpo y la cantidad de sentencias SQL son los mismos exista o no el DNI. La coincidencia se calcula **al abrir la solicitud ADMIN**, no al enviarla.

Capas, de la más barata a la más fuerte:

1. **Validación de forma** (`@Dni`, normalización con `DocumentoIdentidad`): independiente de la base.
2. **Idempotencia de la solicitud:** `Idempotency-Key` (uuid) con `uq_solicitud_idempotencia(escuela_id, solicitante_id, idempotencia)` y `huella` calculada por el servidor. Misma clave y mismos datos → mismo resultado; misma clave con otros datos → `409 OPERACION_NO_REPETIBLE` (patrón de `tutor_deportista` y de las cargas).
3. **Una solicitud abierta por DNI y familia:** índice parcial `uq_solicitud_abierta_familia_dni(familia_id, dni_normalizado) WHERE estado IN ('PENDIENTE','OBSERVADA')`. Si la familia reenvía el mismo DNI con otra clave, se devuelve **su propia** solicitud abierta (es información suya: no hay fuga).
4. **Entre familias no hay restricción:** dos familias pueden solicitar el mismo DNI (padres separados). Cada una recibe la misma respuesta uniforme («Recibimos tu solicitud»).
5. **Resolución de ADMIN:** ve la coincidencia (DNI normalizado igual a un deportista existente de la escuela; otras familias vinculadas; otras solicitudes abiertas con el mismo DNI) y elige **crear y vincular**, **vincular al existente**, **observar** o **rechazar**. La unicidad `uq_deportista_dni_escuela` (y la de CUIL) es el cierre duro: si dos ADMIN aprueban a la vez la creación del mismo DNI, el segundo recibe un error controlado «ya existe: vinculá al existente» **solo para ADMIN**.
6. **Después de crear**, las demás solicitudes abiertas del mismo DNI se marcan «coincide con un deportista existente» para ADMIN; **nunca se vinculan solas**.
7. **Topes** configurables, aplicados bajo el bloqueo de la fila de la familia (`FOR UPDATE` con `lock_timeout`, sin estado en memoria que no escala): máximo de solicitudes abiertas y de tutores declarados por familia. Valores iniciales a validar con el club. El limitador en memoria se suma solo como defensa de ráfagas por IP/usuario y se documenta como limitado a una instancia.
8. **Deportista con DNI reservado por un inactivo** (`DNI_RESERVADO_POR_INACTIVO`): lo ve únicamente ADMIN en la coincidencia; la familia recibe la respuesta uniforme.
9. **Tutores:** el DNI no es único. Se avisa a la familia solo sobre duplicados **de su propia familia** (mismo DNI o correo); las coincidencias entre familias las ve solo ADMIN.

### 4.6 Modelo de datos y migraciones propuestas (punto 5)

Todas **aditivas**; V1–V17 no se tocan. Cada migración trae su script de concesiones del rol de runtime en `backend/ops/postgresql/` (las concesiones no están en Flyway). Las escrituras que cambian estado crítico pasan por funciones `SECURITY DEFINER` con `search_path` fijo, como `crear_vigencia_tutor`.

| Migración | Corte | Contenido |
|---|---|---|
| **V18** (implementada en C0) | antes de C2 | Triggers que impiden cambiar familia, escuela, rol e id de `usuario` y `documento.cargado_por` tras la creación (**autorizado** el 2026-11-27). |
| **V19** (implementada en C0) | C0 | `usuario.version_sesion` (generación de sesiones, claim `version_sesion` del JWT, solo crece): quitar el acceso revoca para siempre los tokens anteriores; reactivar no los revive. |
| **V20** (implementada, fuera de C0) | — | Triggers que revocan las sesiones al dar de baja una familia o una escuela (`version_sesion`). |
| **V21** (C1, implementada) | C1 | `tutor`: `origen` (`ADMIN`/`FAMILIA`), `verificacion` (`DECLARADO`/`VERIFICADO`/`OBSERVADO`), `vinculo_cuenta` (`NULL`/`DECLARADO`/`VERIFICADO`), `declarado_por` (`verificado_por/en` y `motivo_observacion` pasaron a V22). `DEFAULT` que conserva los tutores existentes como `ADMIN/VERIFICADO`. CHECK: `vinculo_cuenta` ⇔ `usuario_id`; la cuenta es de la misma familia (las CHECK de verificación son de V22). |
| **V22** (C2, implementada) | C2 | `tutor`: `verificado_por`/`verificado_en`, `motivo_observacion`, `idempotencia`/`huella_alta`; CHECK que atan cada estado a sus columnas; índice único de la clave por cuenta e índice parcial para la bandeja. Ver `docs/SEGURIDAD.md` (C2). |
| **V23** | C3 | `solicitud_familiar` (sobre: `id`, `escuela_id`, `familia_id`, `solicitante_id`, `tipo`, `estado`, `idempotencia`, `huella`, `version`, `motivo_codigo`, `motivo_detalle`, `resuelta_por/en`, `resultado_deportista_id`, timestamps) + `solicitud_deportista` (datos **tipados** del deportista y relación declarada) con FK por escuela, CHECK de estados y de longitudes, `uq_solicitud_idempotencia`, `uq_solicitud_abierta_familia_dni`. |
| **V24** | C4 | `solicitud_responsabilidad` (tutor, deportista, tipo declarado) sobre el mismo sobre. |
| **V25** | C5 | `solicitud_correccion` (campos de identidad propuestos) sobre el mismo sobre. |

Por qué tablas **tipadas** por tipo y no un `payload jsonb`: la base puede imponer longitudes, formato y FK por escuela, las auditorías y pruebas de privacidad pueden asegurar columnas concretas, y no hay un campo libre donde se cuele un dato sensible.

Los datos de la solicitud viven **solo mientras sirven**: al ser terminal con efecto se copian al deportista/tutor y la solicitud conserva únicamente el resultado y los nombres de campos; las rechazadas/canceladas se depuran por una política de retención (a definir junto con la de documentación médica).

### 4.7 Asociación entre usuario y tutor (punto 7)

- Se **mapea `tutor.usuario_id`** (ya único) y se agrega `vinculo_cuenta`. Una cuenta **nunca se asume tutor ni responsable legal**; puede no tener tutor (un ADMIN, alguien que solo ayuda).
- «**Soy este tutor**» es una declaración (`DECLARADO`): la persona crea su perfil o **reclama un tutor sin cuenta de su familia** (por ejemplo, el que su pareja cargó antes). El reclamo es un `UPDATE … SET usuario_id = :yo WHERE id = :t AND usuario_id IS NULL AND familia_id = :mi_familia` (CAS): dos reclamos simultáneos del mismo tutor → uno gana, el otro recibe un conflicto neutro; una segunda cuenta para la misma persona en otro tutor no se impide, pero ADMIN la ve.
- ADMIN puede **invitar a un tutor concreto** (`invitacion.tutor_id`): la cuenta nace `VERIFICADO` para ese tutor.
- **No interviene en la autorización.** Sirve para «Mis datos», trazabilidad («quién declaró») y notificaciones futuras. Cambiar de tutor reclamado lo audita y lo puede revertir ADMIN.

### 4.8 Privacidad entre familias independientes (punto 8)

- **Procedencia, la regla de U21:** lo que una familia declara (tutores, solicitudes, domicilios de su hogar) pertenece a su `familia_id` y **no se muestra a la otra**, aunque compartan deportista.
- Cada consulta de la familia lleva el alcance en el `WHERE`; fuera de alcance, `404` uniforme; DTO cerrados; sin buscadores por DNI, nombre o correo; DNI enmascarado en pantalla.
- **Al vincular un deportista existente**, lo que la nueva familia declaró **no se mezcla** con el registro verificado: ADMIN ve las diferencias campo por campo y decide «usar el declarado» por campo (auditado, con nombres de campo). Lo no aceptado se descarta.
- **La aprobación es el control de identidad:** conocer un DNI y un nombre no basta. Vincular un deportista existente expone su ficha mínima a la familia nueva, así que ADMIN debe poder pedir más evidencia (`OBSERVADA`) y la solicitud muestra quién declara, con qué parentesco y desde qué cuenta.
- Auditoría con **nombres de campo, no valores**; sin datos personales en logs, errores ni cachés persistentes del navegador; mensajes fijos que nunca devuelven lo escrito.

### 4.9 Experiencia ADMIN: aprobar, observar o rechazar (punto 10)

Bandeja «**Solicitudes familiares**» (hermana de «Documentos pendientes»): filtros por tipo/estado/familia, orden por antigüedad, contador de pendientes. Cada solicitud es una **ficha** con lo que la familia declaró **ya listo**:

- **Aprobar** (un clic): *Crear y vincular* — crea el deportista con los datos declarados y el vínculo `ACTIVO` con `autorizado_por`; si la solicitud declaró una relación y la bandera de responsables está encendida, ofrece **confirmar también la responsabilidad** en la misma acción.
- **Vincular al existente:** muestra la coincidencia y las diferencias; crea el vínculo `ACTIVO` (opcionalmente `principal`).
- **Observar:** motivo codificado + detalle; vuelve a la familia.
- **Rechazar:** motivo codificado; neutro para la familia.
- Verificación de tutores declarados (`VERIFICADO`/`OBSERVADO`) desde la misma bandeja, en lote para los que no tienen observaciones.
- Todo exige `versionEsperada` y es idempotente ante un doble clic.

### 4.10 Resolución de modificaciones concurrentes (punto 9)

| Situación | Resolución |
|---|---|
| Dos personas de una familia editan el mismo tutor | `version`; la segunda recibe `409 EDICION_DESACTUALIZADA` («Otra persona de tu familia modificó esto») y ve los datos actuales. |
| Dos usuarios envían la misma solicitud o hacen doble envío | Idempotencia por clave/huella; el índice parcial devuelve **la propia** solicitud abierta. |
| La familia edita mientras ADMIN resuelve | La resolución lleva `versionEsperada`: si cambió, `409` y ADMIN revisa lo nuevo. |
| Dos familias piden el mismo DNI a la vez | Dos solicitudes independientes; ADMIN ve ambas; una sola creación por `uq_deportista_dni_escuela`. |
| Dos ADMIN resuelven la misma solicitud | CAS por `estado` y `version`: gana uno; el otro recibe el estado actual. |
| Aprobación ↔ otra revocación/vínculo del mismo deportista | Orden de bloqueo fijo: solicitud → `bloquearParaVincular(deportista)` → vínculo, igual que los servicios actuales, con `lock_timeout`. |
| Reclamo simultáneo de un tutor sin cuenta | CAS sobre `usuario_id IS NULL` (4.7). |

### 4.11 Cuándo se permite cargar documentación (punto 11)

**Solo después de la aprobación**, cuando existe `familia_deportista ACTIVO` (precondición ya vigente en el motor de carga). Una solicitud `PENDIENTE` **no** habilita ninguna carga: no hay `deportista_id` ni autorización, y no se almacena documentación de un menor sin verificar. La familia ve «Aprobada» y la carga documental (con sus banderas actuales apagadas por defecto) aparece en la ficha. Adjuntar documentos a una solicitud queda **fuera de alcance**; se reevalúa con la política de retención. Para tutores declarados, la carga de su DNI queda sujeta a las reglas vigentes de cada documento (confirmado en C2 contra el motor: la familia gestiona los DNI de sus tutores activos, declarados incluidos, y cargarlos no los verifica). Desde C2, un DNI vigente `APROBADO` respalda la identidad: la familia ya no puede cambiar nombre, apellido ni DNI de esa persona.

### 4.12 Cuentas (prerrequisito, corte 0)

ADMIN necesita **ver los usuarios de cada familia y desactivar/reactivar** uno (hoy imposible). Sin eso no se puede cortar el acceso de un adulto tras una separación ni responder a una cuenta comprometida. Desactivar invalida sesiones y se audita.

## 5. Contratos (punto 12)

Todos detrás de banderas `banfield.autoservicio.<corte>.habilitado` (**apagadas por defecto**, `503` si están apagadas; las lecturas del propio perfil no se bloquean). Cuerpos cerrados, `Idempotency-Key` donde crea, `versionEsperada` donde modifica.

| Corte | FAMILIA | ADMIN |
|---|---|---|
| C0 | — | `GET /api/admin/familias/{id}/cuentas`, `POST /api/admin/familias/{id}/cuentas/{cuentaId}/desactivar`, `…/reactivar`; filtro `familiaId` en `GET /api/admin/invitaciones` |
| C1 | `GET/PUT /api/familia/mi-perfil`, `POST /api/familia/mi-perfil/reclamar-tutor` | `POST /api/admin/invitaciones` (campo opcional `tutorId`) |
| C2 | `POST/PUT /api/familia/tutores`, `GET /api/familia/tutores` | `GET /api/admin/tutores/por-verificar`, `POST /api/admin/tutores/{id}/verificacion` |
| C3 | `POST /api/familia/solicitudes/deportistas`, `GET/PUT/DELETE …/{id}` | `GET /api/admin/solicitudes-familiares`, `POST …/{id}/resolucion` |
| C4 | `POST /api/familia/solicitudes/responsabilidades` | misma bandeja y resolución |
| C5 | `PUT /api/familia/deportistas/{id}/contacto`, `POST /api/familia/solicitudes/correcciones` | misma bandeja y resolución |

Respuesta uniforme de alta: `201` con `{id, estado:"PENDIENTE"}` **sea cual sea el estado real del DNI**.

## 6. Permisos, privacidad y abuso

| Acción | FAMILIA | ADMIN |
|---|---|---|
| Declarar/editar tutor de su familia | Sí | Sí |
| Reclamar «soy este tutor» | Sí → `DECLARADO` | Verifica |
| Solicitar alta/vínculo de deportista | Sí (solicitud) | Resuelve |
| Editar contacto de deportista propio exclusivo | Sí | Sí |
| Corregir identidad verificada / deportista compartido | Solo solicitud | Resuelve |
| Solicitar responsabilidad | Sí | Confirma |
| Vincular/revocar, activar/desactivar, cuentas | **No** | Sí |
| Ver datos de otra familia | **Nunca** | Sí |

Riesgos de abuso: enumeración de DNI/correo, relleno de la bandeja, reclamo de un tutor ajeno, motivo que filtre información. Mitigación: respuestas y camino de código uniformes, topes por familia, CAS, advertencia al escribir motivos y motivos codificados.

## 7. Experiencia de usuario (portal, mobile-first)

«Mi familia» como centro con pasos y estado: **Mis datos → Otros adultos → Deportistas → Responsabilidades**. Cada ítem muestra `Declarado` / `En revisión` / `Verificado` / `Observado` (+ motivo del club). Guardado por sección, mensajes neutros («Recibimos tu solicitud»), aviso de conflicto de versión, formularios cortos y accesibles, **sin repetir lo ya cargado** (datos del tutor propio prellenan la solicitud). Sin temporizadores ni polling agresivos; estados inciertos de red se recuperan con la misma `Idempotency-Key`.

## 8. Validaciones

Backend como fuente de verdad (reutiliza `@Dni`, `@Cuil`, tamaños de columna, correo); normalización; fecha de nacimiento plausible; trim; sin caracteres de control; máximos por familia; mensajes fijos que nunca devuelven lo escrito. El frontend replica las reglas solo para guiar.

## 9. Pruebas (por corte)

- **Unitarias:** validación, estados y transiciones de la solicitud, huella.
- **WebMvc:** roles, CSRF, cuerpo cerrado, `503` con bandera apagada, respuestas uniformes.
- **DB con dos familias** (aislamiento, `404` uniforme) y **respuesta indistinguible**: un DNI existente y uno inexistente dan el mismo cuerpo, el mismo estado y **la misma cantidad de sentencias SQL**.
- **Concurrencia determinista** (sesión identificada por `pg_blocking_pids`, no por orden): doble envío, dos familias con el mismo DNI, dos ADMIN resolviendo, ADMIN contra edición familiar, reclamo simultáneo de un tutor.
- **Seguridad:** ningún camino de FAMILIA produce `familia_deportista ACTIVO`; aprobar crea el vínculo con `autorizado_por` del ADMIN; auditoría sin valores.
- **Frontend:** Vitest con FakeBackend (formularios, estados, conflictos, accesibilidad, móvil); **E2E reales** (familia A, familia B, ADMIN, revocación de cuenta, banderas apagadas en su propio shard).
- Mutación manual de privacidad (introducir una fuga y comprobar que la prueba la detecta), como se hizo en U21.

## 10. División en cortes y PR (punto 12)

Cada corte: backend, luego frontend, banderas apagadas, sin habilitar escrituras en producción. Tamaño estimado en líneas de producción más pruebas; si un PR supera ~400 líneas se justifica `size:exception` o se parte.

| Corte | PR | Contenido | Migración | Riesgo Gentle AI | Tamaño |
|---|---|---|---|---|---|
| **C0** | C0-BE, C0-FE | ADMIN lista y desactiva/reactiva usuarios de una familia | V18 + V19 | alto (item 2: cuentas y sesiones) | chico |
| **C1** | C1-BE, C1-FE | Perfil propio, vínculo cuenta↔tutor declarativo, onboarding «Mi familia», invitación con `tutorId` | V21 | medio-alto (identidad) | mediano |
| **C2** | C2-BE-a (tutores familiares), C2-BE-b (verificación y bandeja), C2-FE | Tutores declarados por la familia con estados, verificación ADMIN (uno o en lote), tope provisorio de 10 por familia y salvaguarda de identidad con DNI aprobado (**implementado**) | V22 | alto (item 2: nueva superficie de escritura familiar) | grande |
| **C3** | C3-BE-a (solicitud y duplicados sin oráculo), C3-BE-b (resolución ADMIN y vínculo), C3-FE | Solicitud de alta/vínculo, resolución | V23 + concesiones | **alto** (privacidad, concurrencia) | grande |
| **C4** | C4-BE, C4-FE | Solicitud y confirmación de responsabilidades (reutiliza `tutor_deportista`) | V24 | medio | mediano |
| **C5** | C5-BE, C5-FE | Edición directa de contacto, corrección de identidad, cierre y documentación | V25 | medio | mediano |

Orden recomendado: **C0 → C1 → C2 → C3 → C4 → C5**. C3 es el que entrega el valor principal (ADMIN deja de tipear deportistas); C0 y C1 son habilitantes de seguridad e identidad.

## 11. Riesgos y condiciones

- Enumeración de DNI/correo (mitigada por camino único, respuesta uniforme y topes).
- Relleno de la bandeja de ADMIN (topes por familia; la bandeja prioriza por antigüedad).
- Asociación errónea cuenta↔tutor (declarativa, verificable, sin permisos).
- Latencia de aprobación: la familia no puede cargar documentación hasta que ADMIN apruebe (decisión consciente, 4.11).
- Retención de datos personales de solicitudes terminales y de documentación médica (condición pendiente antes del despliegue).
- Endurecer `usuario.familia_id` y la procedencia documental: resuelto en C0 (V18).
- Deuda ajena abierta: conectividad **Docker/WSL2/Ryuk** en pruebas locales (ver `odd/tasks/u21-cierre.md`); no se corrige dentro de U21-BIS.

## 12. Decisiones menores que tomo por defecto salvo objeción

1. Solicitud con **tablas tipadas** (no `jsonb`).
2. Topes iniciales por familia en la base, con valores a validar con el club.
3. Hardening de `usuario.familia_id` como migración propia antes de C2: **autorizado e implementado en C0 (V18)**.
4. Sin adjuntar documentos a solicitudes pendientes.
5. Confirmar la responsabilidad en la misma acción de aprobar, solo cuando la bandera de responsables esté encendida.
