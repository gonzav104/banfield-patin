# Revisión documental — Fase 0

Hallazgos al 2026-10-31 sobre `REQUERIMIENTOS.md`, `AGENTS.md`, `backend/AGENTS.md`, `docs/SEGURIDAD.md`, `docs/FAMILIAS-DEPORTISTAS.md` y migraciones `V1`–`V4`.

Marca **Aplicada** = corregido en esta fase. **Propuesta** = requiere tu aprobación (borra o cambia contenido que otros documentos consumen).

| ID | Hallazgo | Evidencia | Corrección | Estado |
| --- | --- | --- | --- | --- |
| F1 | `backend/AGENTS.md` omite `PENDIENTE` en estados de Documento | `backend/AGENTS.md` vs `V1:273`, RF-10 | Agregar `PENDIENTE` | **Aplicada** |
| F2 | `backend/AGENTS.md` omite `LISTO_PARA_PRESENTAR` en Apto | `V1:325-328`, RF-11 | Agregar el estado | **Aplicada** |
| F3 | `backend/AGENTS.md` usa `CONFIRMADO` para inscripción | `V1:460`, RF-16 | Cambiar a `PARTICIPA` | **Aplicada** |
| F4 | Faltan tipos de documento | `V1:262-271` incluye `CERTIFICADO_WADA` | Agregarlo a la lista | **Aplicada** |
| F5 | Faltan estados de Licencia y Competencia en `backend/AGENTS.md` | `V1:393`, `V1:430` | Documentarlos | **Aplicada** |
| F6 | `backend/AGENTS.md` dice agregar OpenAPI "cuando se implemente" | `backend/pom.xml:48-51` ya tiene springdoc; `SEGURIDAD.md` documenta `/v3/api-docs` | Reemplazar por la regla vigente (RNF-14) | **Aplicada** |
| F7 | `SEGURIDAD.md` no menciona `V4` y dice que `V3` debe revisarse antes de aplicarse | `SEGURIDAD.md:204-205` | Actualizar a `V1`–`V4` con el estado real de `V4` | **Aplicada** |
| F8 | `FAMILIAS-DEPORTISTAS.md` declara FAMILIA "solo lectura" | `FAMILIAS-DEPORTISTAS.md:13`; `InventarioRutasTest:192` | Nota de decisión D1 + cambio diferido del invariante | **Aplicada (nota)** |
| F9 | `FAMILIAS-DEPORTISTAS.md` hardcodea "24 rutas" y su tabla tiene 33 filas | `FAMILIAS-DEPORTISTAS.md:30` vs tabla | Quitar el número y referir a la especificación generada | **Aplicada** |
| F10 | `docs/V1__crear_esquema_inicial.sql` duplica la migración | `diff` sin diferencias | Borrarlo o dejarlo como copia de referencia con aviso de no editar | **Propuesta** |
| F11 | RF-14 mezcla estados de empadronamiento y licencia | RF-14 vs `V1:360-363` y `V1:393` | Abrir decisión O5 y tratar licencia como entidad con estados propios | **Propuesta** |
| F12 | No existe historial federativo en el esquema | `V1` no tiene tablas de historial | Migración nueva en Fase 5 (O4) | **Propuesta** |
| F13 | `numero_rifa` no tiene estado de pago | `V1:562-593` | Migración nueva en Fase 8 (O9) | **Propuesta** |
| F14 | El invariante de FAMILIA solo lectura contradice D1 | `InventarioRutasTest:192` | Cambiarlo cuando existan las rutas de escritura (Fases 3, 6 y 8) | **Diferida** |

## Impacto de F14

El test actual afirma:

> FAMILIA es solo lectura (F5, REQ-XC-07): ninguna ruta POST/PUT/PATCH/DELETE bajo /api/familia/.

Eso deja de ser cierto con D1. No se toca ahora porque todavía no existen esas rutas; el test se ajusta en la misma unidad que introduce cada escritura, junto con su prueba de autorización. Hasta entonces el invariante sigue protegiendo el estado actual.

## Riesgo de F10

Mantener dos copias idénticas del esquema invita a editar la equivocada. La copia en `docs/` no la ejecuta Flyway, así que no rompe nada hoy, pero es deuda documental. Si se conserva, debe llevar un encabezado que diga "copia de referencia, no editar; la fuente es la migración".
