# U21-BIS — Autoservicio y empadronamiento familiar (diseño, sin implementar)

Estado: **propuesta para aprobación**. Relevamiento de solo lectura sobre `main` @ `fff7575`. No se implementó nada y esta unidad va **antes de U22**.

## 1. Objetivo y principio rector

Que ADMIN no cargue a mano los datos de todos los padres y deportistas: la familia **declara** sus datos y ADMIN **verifica**. El principio que ordena todo el diseño:

> **Declarar información personal no concede acceso.** Hay tres planos independientes y la familia solo escribe en el primero.

| Plano | Qué es | Quién escribe | Hoy |
|---|---|---|---|
| 1. Datos declarados | Datos de personas (tutor, deportista) con su estado de verificación | FAMILIA declara · ADMIN verifica | Solo ADMIN |
| 2. Acceso | `familia_deportista` `ACTIVO` + cuentas (`usuario`) | **Solo ADMIN** | Solo ADMIN |
| 3. Responsabilidad declarada | `tutor_deportista` (vigencias, tipo declarado; no acredita representación ni da acceso) | FAMILIA solicita · ADMIN confirma | Solo ADMIN (con bandera) |

## 2. Qué existe hoy (evidencia, relevamiento)

- **Varios usuarios por familia:** `usuario.familia_id` no es único; solo `lower(email)` es único por escuela (`uq_usuario_email_escuela`). Registro solo por invitación de ADMIN (`POST /api/admin/invitaciones`, `familiaId` existente o `nuevaFamilia`); una invitación = un usuario (`uq_invitacion_usuario`). **Frontend:** `CreateInvitationForm.tsx`.
- **Cuenta ↔ tutor:** `tutor.usuario_id` existe en la base, es opcional y único (`uq_tutor_usuario`), **pero no se mapea ni se gestiona** (`Tutor.java`: «no es parte de este módulo»). Hoy la identidad del usuario autenticado (`UsuarioAutenticado`: id, escuela, familia, rol) **no** se relaciona con ningún tutor.
- **Tutores:** los crea/edita solo ADMIN (`/api/admin/familias/{id}/tutores`, `PUT /api/admin/tutores/{id}`); DNI **opcional y no único**; sin cuenta por defecto (el front lo dice: «Crear un tutor no genera una cuenta»). Una familia puede tener cero, uno o varios; no hay mínimo de padre y madre.
- **Deportistas:** los crea/edita solo ADMIN. DNI y CUIL **únicos por escuela** (`uq_deportista_dni_escuela`); un alta duplicada responde `DNI_DUPLICADO` / `DNI_RESERVADO_POR_INACTIVO` (ver 4.4: **sería un oráculo de enumeración** si lo expusiéramos a familias).
- **Vínculos familia↔deportista:** varias familias pueden estar `ACTIVO` en el mismo deportista (un solo principal: `uq_fd_principal_activo`); `PENDIENTE` y `RECHAZADO` **existen en la base y ningún flujo los produce**; solo ADMIN vincula/revoca.
- **Responsabilidades:** `tutor_deportista` solo por ADMIN, idempotente (`Idempotency-Key`), con cuatro revisiones y bandera `alta-habilitada` apagada por defecto; tipos `MADRE`, `PADRE`, `TUTOR_LEGAL`, `OTRO_RESPONSABLE`, `SIN_DETERMINAR` («no acredita representación»).
- **Portal familiar:** solo lectura salvo la **carga documental** (DNI, apto). La familia lee ficha, temporadas, responsables y checklist. Ninguna pantalla permite completar datos de tutores ni de deportistas.
- **Cuentas:** ADMIN puede reiniciar el MFA de otro ADMIN, pero **no existe** listar ni desactivar/reactivar usuarios FAMILIA (`usuario.activo` existe sin endpoint). `email_verificado` nunca se completa.
- **Concurrencia:** `version` optimista en familia, tutor, deportista, vínculos y documentos (`409 EDICION_DESACTUALIZADA`); `Idempotency-Key` en altas de responsables y cargas; bloqueos acotados (`FOR UPDATE` con `lock_timeout`). Sin límites de frecuencia salvo el login.
- **Privacidad ya resuelta (patrón a reutilizar):** alcance en el `WHERE`, `404` uniforme, DTO cerrados, auditoría con nombres de campo, y la regla de U21 «los datos pertenecen a la familia que los cargó» (`cargado_por → usuario.familia_id`).

## 3. Escenarios y cómo se resuelven

| # | Escenario | Resolución propuesta |
|---|---|---|
| 1 | **Familia nueva** | ADMIN invita (`nuevaFamilia`, ya existe) → la persona se registra → onboarding «Completá tu perfil» (corte 1). |
| 2 | **Segundo usuario en familia existente** | ADMIN invita con `familiaId` (ya existe). El nuevo usuario declara «soy este tutor» o crea su perfil (corte 1). |
| 3 | **La madre carga al padre, sin cuenta** | Crea un `tutor` sin `usuario_id` en su familia (corte 2). El padre no necesita cuenta ni ve nada. |
| 4 | **Ambos padres con cuenta, misma familia** | Dos usuarios + dos tutores con `usuario_id` propio. El acceso sigue siendo **por familia**, no por tutor. |
| 5 | **Padres separados, familias independientes** | Dos familias `ACTIVO` en el mismo deportista (el modelo ya lo admite). Cada una declara y ve **solo lo suyo** (tutores, responsabilidades, documentos); ver 4.6. |
| 6 | **Un solo adulto responsable** | Soportado hoy y se mantiene: no se exige madre y padre. |
| 7 | **Abuelos u otros responsables** | Tutor con parentesco libre; tipo declarado `TUTOR_LEGAL` / `OTRO_RESPONSABLE` por solicitud (corte 4). No acredita representación. |
| 8 | **Varios deportistas** | Varias solicitudes/altas; mismo alcance por familia. |
| 9 | **Carga y actualización de datos personales** | Ver 4.3 (campos directos vs. de identidad). |
| 10 | **Responsabilidades por deportista** | La familia **solicita** «X es MADRE de Y»; ADMIN confirma y recién entonces se crea la vigencia (corte 4). |
| 11 | **Duplicados** | Ver 4.4. |
| 12 | **Verificación administrativa** | Estados `DECLARADO → VERIFICADO / OBSERVADO` y una **bandeja** como la de documentos pendientes. |
| 13 | **Concurrencia en una familia** | `version` optimista + `Idempotency-Key`; «Otra persona de tu familia modificó esto». |
| 14 | **Protección de otras familias** | Ver 5 (sin buscadores, sin oráculos, alcance en el `WHERE`). |

## 4. Diseño

### 4.1 Cuenta ↔ tutor: vínculo explícito y declarativo
- Se **mapea `tutor.usuario_id`** (ya existe y es único) y se agrega `vinculo_cuenta` = `DECLARADO` | `VERIFICADO`. La cuenta **nunca** se asume tutor ni responsable legal.
- Sirve para la experiencia («Mis datos»), la trazabilidad («quién declaró») y como base de futuras notificaciones. **No interviene en la autorización**, que sigue siendo por familia. Una cuenta puede no tener tutor (ej.: un ADMIN no, una persona que solo quiere ver).
- Variante más fuerte, sin costo de diseño: ADMIN invita **a un tutor concreto** (`invitacion.tutor_id`): la cuenta nace `VERIFICADA` para ese tutor.

### 4.2 Verificación: columnas para altas, tabla para cambios
- **Altas** que hace la familia (tutor sin cuenta): columnas `origen` (`ADMIN`/`FAMILIA`), `verificacion` (`DECLARADO`/`VERIFICADO`/`OBSERVADO`), `declarado_por`, `verificado_por/en`, `motivo` (solo ADMIN→familia, como el motivo de observación de documentos). Lo hecho por ADMIN nace `VERIFICADO`.
- **Solicitudes** (tabla única `solicitud_familiar`: `tipo`, entidad objetivo, `payload` mínimo, `estado` `PENDIENTE/APROBADA/RECHAZADA/OBSERVADA`, `familia_id`, `solicitante_id`, `resuelta_por/en`, `version`) para todo lo que **toca algo ya verificado o compartido**: vínculo con un deportista, corrección de identidad, responsabilidad. Reutiliza el patrón de revisión de documentos (CAS, motivo, bandeja, auditoría).

### 4.3 Qué puede editar la familia
- **Directo (con auditoría de campos y versión):** teléfono, correo, domicilio, localidad, partido, código postal, nacionalidad — del tutor propio y de los deportistas **de su familia que solo ella tiene vinculados**.
- **Identidad** (nombre, apellido, DNI, CUIL, fecha de nacimiento) **de datos ya verificados**: solo por solicitud de corrección que ADMIN resuelve.
- **Deportista compartido** (≥2 familias `ACTIVO`): todo cambio pasa por solicitud (cerrar el caso de padres separados con domicilios distintos).
- Nunca: estado activo/inactivo, vínculos, temporadas, datos deportivos, documentos de otros.

### 4.4 Duplicados sin oráculo (la decisión más importante)
- **Una familia no crea deportistas: crea una solicitud de alta o de vínculo** con todos sus datos (RF-06). Si el DNI ya existe, la respuesta es **la misma** que si no existiera («Recibimos tu solicitud, el club la revisa»): nunca `DNI_DUPLICADO`, nunca tiempos distintos.
- ADMIN ve en la bandeja la coincidencia (DNI existente, familias vinculadas) y elige: **crear y vincular**, **vincular al existente** (`ACTIVO` + `autorizado_por`), o **rechazar** (`RECHAZADO`). El clic reemplaza la carga manual; el dato lo aportó la familia.
- Tutores: DNI no es único; se avisa dentro de la misma familia (mismo DNI o correo) y **solo ADMIN** ve coincidencias entre familias.

### 4.5 Responsabilidades
La familia solicita (tipo declarado por tutor y deportista); al aprobar, ADMIN crea la vigencia con el servicio actual (huella, idempotencia, cuatro revisiones, bandera). Se conserva «no acredita representación».

### 4.6 Padres separados
El deportista es único en la escuela; cada familia solo ve **su** familia (tutores, responsables, documentos que subió) y la **ficha mínima** del deportista. Lo que una familia declara de **su** hogar no se muestra a la otra (misma regla de procedencia de U21). Domicilio/contacto del deportista: único dato verificado por ADMIN; si hay ≥2 familias, solicitud (4.3).

### 4.7 Cuentas (prerrequisito, corte 0)
ADMIN necesita **ver los usuarios de cada familia y desactivar/reactivar** uno (hoy imposible). Sin eso no se puede cortar el acceso de un adulto tras una separación ni responder a una cuenta comprometida.

## 5. Permisos, privacidad y abuso

| Acción | FAMILIA | ADMIN |
|---|---|---|
| Declarar/editar tutor de su familia | Sí (propio alcance) | Sí |
| Declarar «soy este tutor» | Sí → `DECLARADO` | Verifica |
| Solicitar alta/vínculo de deportista | Sí (solicitud) | Resuelve |
| Editar contacto de deportista propio exclusivo | Sí (campos directos) | Sí |
| Corregir identidad verificada | Solo solicitud | Resuelve |
| Solicitar responsabilidad | Sí | Confirma |
| Vincular/revocar, activar/desactivar, cuentas | **No** | Sí |
| Ver datos de otra familia | **Nunca** | Sí |

- `404` uniforme; sin buscadores por DNI/correo; DTO cerrados; DNI siempre enmascarado en pantalla; auditoría con **nombres de campo**, no valores; sin datos personales en logs, errores ni cachés persistentes.
- **Límites de frecuencia** por usuario e IP en altas y solicitudes (no existen hoy); topes por familia (tutores y solicitudes abiertas) contra abuso y relleno de la bandeja.
- Cada corte detrás de su **bandera apagada por defecto** (`banfield.autoservicio.<corte>.habilitado`).

## 6. Experiencia de usuario (portal, mobile-first)
«Mi familia» como centro con pasos y estado: **Mis datos → Otros adultos → Deportistas → Responsabilidades**. Cada ítem muestra `Declarado` / `En revisión` / `Verificado` / `Observado` (+ motivo del club). Guardado por sección, mensajes neutros («Recibimos tu solicitud»), aviso de conflicto de versión, formularios cortos y accesibles, sin repetir lo ya cargado. ADMIN: bandeja «Datos por verificar» (hermana de «Documentos pendientes») con la coincidencia de duplicados y acciones Aprobar / Observar / Rechazar.

## 7. Validaciones
Backend como fuente de verdad (reutiliza `@Dni`, `@Cuil`, tamaños de columna, correo); normalización; fecha de nacimiento plausible; trim; sin caracteres de control; idempotencia en altas y solicitudes; máximos por familia; mensajes fijos que nunca devuelven lo escrito.

## 8. Pruebas (por corte)
Unitarias de validación; WebMvc de roles/CSRF/cuerpo cerrado; **DB con dos familias** (aislamiento, `404` uniforme, DNI idéntico → respuesta indistinguible), **concurrencia** (dos usuarios de una familia editando; dos familias solicitando el mismo DNI a la vez → una sola persona); idempotencia y reintento; auditoría sin valores; Vitest con FakeBackend; **E2E reales** (familia A, familia B, ADMIN, revocación de cuenta); banderas apagadas.

## 9. División en cortes (cada uno: backend, luego frontend, banderas apagadas)

| Corte | Contenido | Tamaño relativo |
|---|---|---|
| **C0** | ADMIN: listar y desactivar/reactivar usuarios de una familia | Chico |
| **C1** | Perfil propio y vínculo cuenta↔tutor declarativo + onboarding | Mediano |
| **C2** | Tutores declarados por la familia (padre sin cuenta, abuelos) + estados + bandeja de verificación | Grande |
| **C3** | Solicitud de alta/vínculo de deportista, duplicados sin oráculo, resolución ADMIN | Grande |
| **C4** | Solicitud y confirmación de responsabilidades | Mediano |
| **C5** | Edición directa de contacto y solicitudes de corrección de identidad; cierre y documentación | Mediano |

## 10. Riesgos y condiciones
Enumeración de DNI/correo (mitigado por respuestas uniformes y límites); relleno de la bandeja; asociación errónea cuenta↔tutor (por eso declarativa y verificable, sin permisos); latencia de aprobación; retención de datos personales (se suma a la condición pendiente de la documentación médica); endurecer `usuario.familia_id` (V18) antes de apoyarse más en la procedencia.

## 11. Decisión que necesito
**Alta de deportista por solicitud (recomendada) o creación directa por la familia.** Creación directa es más rápida para la familia pero abre el oráculo de duplicados y deja crear menores que no existen en el club; por solicitud ADMIN solo aprueba, sin tipear. El resto de las recomendaciones de este documento las tomo como base salvo que se indique otra cosa.
