# Temporadas y DeportistaTemporada

Reglas del modelo para la Fase 1. Basado en el DDL real de `backend/src/main/resources/db/migration/V1__crear_esquema_inicial.sql` (líneas 186-231). **No requiere migración nueva**: ambas tablas ya existen.

## 1. Esquema vigente

### `temporada`

| Columna | Tipo | Restricción |
| --- | --- | --- |
| `id` | uuid | PK, `gen_random_uuid()` |
| `escuela_id` | uuid | NOT NULL, FK a `escuela` |
| `anio` | smallint | NOT NULL, `CHECK (anio BETWEEN 2020 AND 2100)` |
| `activa` | boolean | NOT NULL, default `false` |
| `creado_en` / `actualizado_en` | timestamptz | default `now()`, trigger de actualización |

Restricciones:

- `uq_temporada_escuela_anio UNIQUE (escuela_id, anio)` — un año por escuela.
- `uq_temporada_id_escuela UNIQUE (id, escuela_id)` — habilita FKs compuestas.
- `uq_temporada_activa_por_escuela UNIQUE (escuela_id) WHERE activa = true` — **índice parcial**: como máximo una activa por escuela.

### `deportista_temporada`

| Columna | Tipo | Restricción |
| --- | --- | --- |
| `id` | uuid | PK |
| `escuela_id` | uuid | NOT NULL, FK a `escuela` |
| `deportista_id` | uuid | NOT NULL, FK compuesta `(deportista_id, escuela_id)` |
| `temporada_id` | uuid | NOT NULL, FK compuesta `(temporada_id, escuela_id)` |
| `categoria`, `rama`, `divisional` | varchar | opcionales |
| `numero_corredor` | varchar(30) | opcional |
| `estado_deportivo` | varchar(20) | NOT NULL, default `ACTIVO`, `CHECK IN ('ACTIVO','INACTIVO','SUSPENDIDO')` |

Restricciones:

- `uq_deportista_temporada UNIQUE (deportista_id, temporada_id)` — una fila por deportista y temporada.
- `uq_numero_corredor_por_temporada UNIQUE (escuela_id, temporada_id, numero_corredor) WHERE numero_corredor IS NOT NULL` — número único dentro de la temporada.

## 2. Reglas de negocio

1. Una temporada nueva nace **inactiva**.
2. El año debe estar entre 2020 y 2100 (ya validado por `CHECK`; el backend devuelve `400 VALIDACION` con mensaje propio).
3. No puede haber dos temporadas del mismo año en la misma escuela (`409`).
4. Activar una temporada **desactiva la anterior** en la misma transacción.
5. Activar la temporada ya activa es **idempotente**: `200`, sin escrituras ni auditoría duplicada.
6. No se elimina una temporada ni sus datos históricos.
7. Una temporada de otra escuela es indistinguible de una inexistente (`404`).
8. La escuela sale del contexto autenticado, nunca del cliente.
9. `DeportistaTemporada` puede crearse en una temporada inactiva: el ADMIN carga datos antes de activar.
10. Cambiar datos de la temporada 2027 no toca filas de 2026; cada año es una fila nueva.

## 3. Activación y concurrencia

El riesgo real: dos ADMIN activan temporadas distintas al mismo tiempo y ambas quedan activas, o una activación pisa a la otra.

Estrategia recomendada:

```text
BEGIN
  SELECT id FROM escuela WHERE id = :escuelaId FOR NO KEY UPDATE;
  UPDATE temporada SET activa = false
    WHERE escuela_id = :escuelaId AND activa = true AND id <> :temporadaId;
  UPDATE temporada SET activa = true WHERE id = :temporadaId AND escuela_id = :escuelaId;
COMMIT
```

- El `FOR NO KEY UPDATE` sobre la fila de escuela serializa las activaciones de esa escuela y de ninguna otra. Se eligió sobre `FOR UPDATE` porque PostgreSQL toma `FOR KEY SHARE` sobre esa fila al comprobar cualquier clave foránea hacia `escuela` (familias, auditoría, temporadas, ...): `FOR UPDATE` choca con eso y frenaría altas de la misma escuela; `FOR NO KEY UPDATE` no. Está probado contra PostgreSQL real, con un control que demuestra que `FOR UPDATE` sí las frenaría.
- El estado se vuelve a leer **después** de tomar el bloqueo: otra activación pudo confirmarse mientras se esperaba. Si ya está activa, es el caso idempotente (`200`, sin escrituras ni auditoría).
- La espera del bloqueo está acotada (`banfield.temporadas.lock-timeout`, 3 s por defecto, entre 100 ms y 30 s); si vence, `409 CONFLICTO_CONCURRENCIA` sin escribir ni auditar.
- Se desactiva la anterior y se hace `flush` **antes** de activar la nueva: el índice parcial único no es diferible.
- El índice parcial es la **red de seguridad**: si algo se escapa, la segunda escritura falla con violación de unicidad.
- Esa violación se traduce a `409 CONFLICTO_CONCURRENCIA`, código ya usado por el módulo de vínculos.
- No usar `SERIALIZABLE` global: innecesario para este caso y encarece el resto.

Alternativa más simple: confiar solo en el índice parcial y reintentar. Se descarta porque el reintento no es determinista y deja al usuario sin saber cuál quedó activa.

## 4. Historial

- El historial **es** la colección de filas: una `deportista_temporada` por año.
- No se actualizan filas de temporadas cerradas para "corregir" datos: se corrige la fila de la temporada correspondiente y queda el `actualizado_en`.
- No hay borrado en cascada desde `temporada` ni desde `deportista`.
- `deportista.activo` (global) y `deportista_temporada.estado_deportivo` (por temporada) **no son lo mismo**:
  - `deportista.activo = false` → la persona dejó la escuela; no aparece por defecto en listados.
  - `estado_deportivo = INACTIVO` → no compite esa temporada, pero sigue en la escuela.
  - `estado_deportivo = SUSPENDIDO` → no puede ser convocado mientras dure la suspensión.

## 5. Preguntas abiertas de Temporadas

| Pregunta | Opciones | Recomendación |
| --- | --- | --- |
| ¿Puede no haber temporada activa? | A: sí / B: siempre una | **A** (ver O10 en `docs/DECISIONES.md`) |
| ¿Se puede desactivar sin activar otra? | A: no, solo activando otra / B: endpoint de desactivación | **A** en el MVP: menos estados posibles |
| ¿Se puede editar el año de una temporada? | A: no / B: sí mientras no tenga datos | **A**: el año es la identidad funcional |
| ¿Qué pasa al cambiar de año? | Se crea la nueva y se activa; la anterior queda con sus filas | Sin acción automática sobre deportistas |
| ¿`numero_corredor` es obligatorio? | A: no / B: sí al activar la temporada | **A**; se valida al exportar LBF |
| ¿Se cargan temporadas históricas? | A: solo desde 2026 / B: backfill manual | **B** si la escuela tiene datos previos útiles para LBF |

## 6. Alcance de la Fase 1

Incluye:

- Entidad, repositorio, servicio y controller ADMIN de `Temporada`.
- Alta, listado paginado, detalle y activación.
- `DeportistaTemporada`: alta, edición y consulta por deportista/temporada.
- Lectura para FAMILIA limitada a sus deportistas vinculados.
- DNI enmascarado en el listado ADMIN existente.
- Auditoría de creación, activación y cambios de datos de temporada.

No incluye:

- Licencias, aptos, documentos, competencias, LBF.
- Importación masiva.
- Frontend.
- Migración nueva (no hace falta).

## 7. Contrato HTTP del módulo (`temp-1-temporada`)

Solo ADMIN (`/api/admin/**`); la escuela y el actor salen del JWT, nunca del cliente. Sin `PUT`, `DELETE` ni desactivar. La respuesta de una temporada es `{id, anio, activa}` (sin `escuelaId` ni marcas de tiempo, que asigna la base).

| Ruta | Éxito | Errores propios |
| --- | --- | --- |
| `GET /api/admin/temporadas?page&size` | `200` página de temporadas, del año más nuevo al más viejo | — |
| `POST /api/admin/temporadas` con `{"anio": 2027}` | `201` con `Location`; nace inactiva | `400 VALIDACION` (año nulo o fuera de 2020–2100), `400 SOLICITUD_INVALIDA` (cuerpo ilegible o con otros campos), `409 TEMPORADA_ANIO_DUPLICADO` |
| `GET /api/admin/temporadas/{id}` | `200` | `404 TEMPORADA_NO_ENCONTRADA` (también si es de otra escuela) |
| `POST /api/admin/temporadas/{id}/activar` | `200` con la temporada activa; idempotente | `404 TEMPORADA_NO_ENCONTRADA`, `409 CONFLICTO_CONCURRENCIA` |

Transversales: `401` sin sesión, `403` para FAMILIA y `403 CSRF_INVALIDO` sin token en las escrituras. La lectura de FAMILIA llega en `temp-3`.

**Auditoría** (misma transacción, solo con cambio real): `TEMPORADA_CREADA` con `{anio}` y `TEMPORADA_ACTIVADA` con `{anio, anteriorTemporadaId}` (nulo si no había activa). Activar la ya activa no audita.

## 8. Datos deportivos por temporada (`temp-2-deportista-temporada`)

Solo ADMIN (`/api/admin/**`); la escuela y el actor salen del JWT, nunca del cliente. La identidad de una fila es el par `(deportistaId, temporadaId)` de la ruta. Sin `DELETE` ni `PATCH`: un deportista que no compite una temporada pasa a `INACTIVO`, la fila no se borra. Sin migración (V1 ya cubre el contrato).

| Ruta | Éxito | Errores propios |
| --- | --- | --- |
| `GET /api/admin/deportistas/{deportistaId}/temporadas` | `200` historial, del año más nuevo al más viejo (a lo sumo 81 filas por el `CHECK` del año) | `404 DEPORTISTA_NO_ENCONTRADO` |
| `POST /api/admin/deportistas/{deportistaId}/temporadas` con `{temporadaId, categoria?, rama?, divisional?, numeroCorredor?, estadoDeportivo?}` | `201` con `Location` del par; el estado por defecto es `ACTIVO` | `400 VALIDACION` / `SOLICITUD_INVALIDA`; `404 DEPORTISTA_NO_ENCONTRADO`, `TEMPORADA_NO_ENCONTRADA`; `409 DEPORTISTA_INACTIVO`, `DEPORTISTA_TEMPORADA_DUPLICADA`, `NUMERO_CORREDOR_DUPLICADO` |
| `GET /api/admin/deportistas/{deportistaId}/temporadas/{temporadaId}` | `200` | `404 DEPORTISTA_NO_ENCONTRADO`, `TEMPORADA_NO_ENCONTRADA`, `DEPORTISTA_TEMPORADA_NO_ENCONTRADA` |
| `PUT /api/admin/deportistas/{deportistaId}/temporadas/{temporadaId}` con `{categoria?, rama?, divisional?, numeroCorredor?, estadoDeportivo}` | `200` reemplazo completo | los `404` de arriba; `409 NUMERO_CORREDOR_DUPLICADO`, `CONFLICTO_CONCURRENCIA` |
| `GET /api/admin/temporadas/{temporadaId}/deportistas?estadoDeportivo&page&size` | `200` plantel paginado por apellido y nombre; `estadoDeportivo` admite `TODOS` (por defecto), `ACTIVO`, `INACTIVO` o `SUSPENDIDO` | `404 TEMPORADA_NO_ENCONTRADA`; `400 SOLICITUD_INVALIDA` con un estado desconocido |

Una fila responde `{id, deportistaId, temporadaId, anio, categoria, rama, divisional, numeroCorredor, estadoDeportivo}`; el plantel agrega `nombre` y `apellido` y **no incluye el DNI** (el enmascarado llega en `temp-4`). Transversales: `401` sin sesión, `403` para FAMILIA y `403 CSRF_INVALIDO` en las escrituras; la lectura de FAMILIA llega en `temp-3`.

**Validación al guardar** (no se espera a la exportación de la LBF; no hay catálogo oficial, así que no hay enums de categoría, rama ni divisional):
- `categoria` (≤ 80), `rama` (≤ 40) y `divisional` (≤ 80) son opcionales, como en el esquema. Se recortan, las rachas de espacios internos se colapsan a uno y un valor en blanco pasa a `null`.
- `numeroCorredor` (≤ 30) es opcional y es **texto opaco**: se recorta, conserva los ceros iniciales (`007` y `7` son distintos) y los espacios internos, y en blanco pasa a `null`.
- Ningún valor puede contener caracteres de control (`400 VALIDACION`). El estado deportivo es un enum cerrado (el `CHECK` de V1); en el `PUT` es obligatorio.
- El cuerpo no acepta propiedades que decide el servidor o la ruta (`escuelaId`, `deportistaId`, `temporadaId` en el `PUT`, `id`): `400 SOLICITUD_INVALIDA`.

**Reglas:**
- Se puede cargar y editar en una temporada **inactiva** (se prepara el año siguiente) y editar el historial de un deportista inactivo; el **alta** exige un deportista activo (`409 DEPORTISTA_INACTIVO`).
- Una fila por deportista y temporada y un número de corredor único por temporada (índices de V1). La consulta previa da el `409` amable y la base arbitra la carrera con el mismo código; varios `null` y el mismo número en otra temporada o escuela sí se permiten.
- Editar una fila nunca modifica la ficha permanente del deportista ni las filas de otras temporadas (RF-07).
- El plantel y el historial se resuelven en una consulta (más su conteo): no hay una consulta por fila.

**Concurrencia del `PUT`:** bloquea la fila con `FOR NO KEY UPDATE` (con `banfield.temporadas.lock-timeout`) y la vuelve a leer dentro de la transacción, así que dos ediciones simultáneas se serializan y la lista de campos modificados de la auditoría se calcula contra el estado confirmado más reciente. **Limitación aceptada:** el bloqueo serializa las escrituras pero no evita que un `PUT` armado con datos viejos pise un cambio posterior; no hay columna de versión (agregarla exige una migración) y gana la última escritura. El bloqueo de la fila no frena otras filas ni la activación de una temporada.

**Auditoría** (misma transacción, solo con cambio real, solo ids, año y **nombres** de campo; nunca un valor deportivo): `DEPORTISTA_TEMPORADA_CREADA` con `{temporadaId, anio}` y `DEPORTISTA_TEMPORADA_ACTUALIZADA` con `{temporadaId, camposModificados}`, más `{estadoAnterior, estadoNuevo}` cuando cambia el estado deportivo.

