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
  SELECT id FROM escuela WHERE id = :escuelaId FOR UPDATE;
  UPDATE temporada SET activa = false
    WHERE escuela_id = :escuelaId AND activa = true AND id <> :temporadaId;
  UPDATE temporada SET activa = true WHERE id = :temporadaId AND escuela_id = :escuelaId;
COMMIT
```

- El `FOR UPDATE` sobre la fila de escuela serializa las activaciones de esa escuela.
- El índice parcial es la **red de seguridad**: si algo se escapa, la segunda escritura falla con violación de unicidad.
- Esa violación se traduce a `409 CONFLICTO_CONCURRENCIA`, código ya usado por el módulo de familias.
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
