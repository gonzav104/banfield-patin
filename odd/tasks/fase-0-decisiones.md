# Fase 0 — Decisiones, documentación y orden de unidades

Objetivo: dejar el roadmap y las reglas de negocio documentadas y sin contradicciones antes de CI y Temporadas.
Rama: `main` (sin rama de feature; solo documentación, sin commits). Entrega: `ask-on-risk`. Runner: no aplica (solo documentación; verificación por inspección y enlaces).

## Specs

- **S1** — "Crear `docs/ODD.md` como guía breve del protocolo real del proyecto, referenciando los contratos de Gentle AI sin duplicarlos."
  - Alcance: definición, cuándo hay artefacto, naming local y en Engram, estructura del documento, persistencia, unidades de trabajo, criterios de cierre.
  - Check: no duplica el texto de `~/.config/opencode/skills/_shared/`; lo referencia por ruta relativa al home.
- **S2** — "Revisar `REQUERIMIENTOS.md`, `AGENTS.md`, las migraciones V1–V4 y los documentos técnicos existentes."
  - Alcance: lectura completa de `docs/REQUERIMIENTOS.md`, `AGENTS.md`, `backend/AGENTS.md`, `docs/SEGURIDAD.md`, `docs/FAMILIAS-DEPORTISTAS.md` y las cuatro migraciones.
- **S3** — "Consolidar las decisiones que ya aprobamos sobre permisos FAMILIA, rifas, convocatorias, historial federativo, DNI y segunda cuenta ADMIN."
  - Alcance: una tabla de decisiones cerradas, con estado y fuente. Sin reabrir lo ya decidido.
- **S4** — "Identificar contradicciones documentales y proponer correcciones."
  - Alcance: hallazgos con archivo:línea, impacto, corrección propuesta y si fue aplicada.
- **S5** — "Revisar las reglas del modelo de Temporadas y DeportistaTemporada, especialmente activación, unicidad, historial y concurrencia."
  - Alcance: DDL real de `V1`, reglas de activación, índices únicos, concurrencia e historial.
- **S6** — "Mantener el spike Playwright federativo como fase específica, anterior al desarrollo de Documentos."
  - Check: el roadmap ubica el spike en Fase 2 y Documentos en Fase 3.
- **S7** — "Preparar el orden de unidades ODD para CI básico y Temporadas."
  - Alcance: lista ordenada de unidades con dependencias y verificaciones.
- **S8** — "Para las decisiones todavía abiertas, presentá opciones, ventajas, riesgos y una recomendación fundamentada. No inventes decisiones de negocio ni las cierres unilateralmente."
  - Check: cada decisión abierta queda marcada como pendiente de aprobación, con recomendación explícita.
- **S9** — Restricciones: "No modificar código Java, migraciones ni datos de Supabase." / "No implementar CI ni Temporadas todavía." / "No crear commits ni hacer push." / "No reescribir las memorias históricas SDD."

## Tasks

| ID | S# | Tarea | Ruta | Estado | Evidencia |
| --- | --- | --- | --- | --- | --- |
| T1 | S2 | Leer requerimientos, agentes, migraciones y documentos técnicos | inline | hecho | lectura de V1–V4, REQUERIMIENTOS, AGENTS, SEGURIDAD, FAMILIAS |
| T2 | S3 | Consolidar decisiones cerradas en `docs/DECISIONES.md` | inline | hecho | documento creado |
| T3 | S4 | Registrar contradicciones y correcciones en `docs/REVISION-DOCUMENTAL.md` | inline | hecho | documento creado |
| T4 | S1 | Escribir `docs/ODD.md` | inline | hecho | documento creado |
| T5 | S5 | Documentar reglas de Temporadas en `docs/TEMPORADAS.md` | inline | hecho | documento creado |
| T6 | S6, S7 | Roadmap y orden de unidades en `docs/ROADMAP.md` | inline | hecho | documento creado |
| T7 | S4 | Aplicar correcciones documentales no controvertidas | inline | hecho | `backend/AGENTS.md`, `docs/SEGURIDAD.md`, `docs/FAMILIAS-DEPORTISTAS.md` |
| T8 | S8 | Presentar decisiones abiertas con opciones, riesgos y recomendación | inline | hecho | `docs/DECISIONES.md`, sección 3 |
| T9 | S3 | Documentar el procedimiento operativo de la segunda cuenta ADMIN | inline | hecho | `docs/DECISIONES.md`, sección 4 |

## Log

### L1 — Pedido original (verbatim)

> Vamos a comenzar la **Fase 0 del backend de Banfield Patín Carrera**, utilizando Organic Driven Development (ODD) de Gentle AI.
>
> La integración de herramientas ya fue verificada. No vuelvas a auditar la configuración global salvo que aparezca un problema concreto.
>
> **Objetivo:** dejar el roadmap funcional y técnico preparado para comenzar CI y Temporadas sin decisiones críticas pendientes.
>
> Tareas:
>
> 1. Crear `docs/ODD.md` como guía breve del protocolo real del proyecto, referenciando los contratos de Gentle AI sin duplicarlos.
> 2. Revisar `REQUERIMIENTOS.md`, `AGENTS.md`, las migraciones V1–V4 y los documentos técnicos existentes.
> 3. Consolidar las decisiones que ya aprobamos sobre permisos FAMILIA, rifas, convocatorias, historial federativo, DNI y segunda cuenta ADMIN.
> 4. Identificar contradicciones documentales y proponer correcciones.
> 5. Revisar las reglas del modelo de Temporadas y DeportistaTemporada, especialmente activación, unicidad, historial y concurrencia.
> 6. Mantener el spike Playwright federativo como fase específica, anterior al desarrollo de Documentos.
> 7. Preparar el orden de unidades ODD para CI básico y Temporadas.
>
> Para las decisiones todavía abiertas, presentá opciones, ventajas, riesgos y una recomendación fundamentada. No inventes decisiones de negocio ni las cierres unilateralmente.
>
> Aplicá el protocolo ODD real para determinar qué artefactos de trabajo corresponden a esta tarea.
>
> **Restricciones:**
> - Podés crear o corregir documentación de la Fase 0.
> - No modificar código Java, migraciones ni datos de Supabase.
> - No implementar CI ni Temporadas todavía.
> - No crear commits ni hacer push.
> - No reescribir las memorias históricas SDD.
>
> Al finalizar, entregá un resumen de cambios, decisiones cerradas, decisiones pendientes y propuesta de la primera unidad de implementación.

### L2 — Clasificación ODD

Tarea **grande**: varios entregables documentales, decisiones abiertas y trabajo que no se reanuda solo con el diff. Corresponde documento de feature + espejo en Engram. Rutas: todo inline (no hay trigger de writer: son documentos cortos y acoplados entre sí).

### L3 — Evidencia relevada

- `V1` ya contiene `temporada`, `deportista_temporada`, `documento`, `apto_fisico`, `empadronamiento`, `licencia`, `competencia`, `inscripcion_competencia`, `plantilla_lbf`, `exportacion_lbf`, `rifa`, `numero_rifa`, `auditoria`.
- `numero_rifa` no tiene estado de pago; el historial federativo no tiene tabla propia.
- `InventarioRutasTest:192` fija el invariante "FAMILIA solo lectura", que la decisión aprobada reemplaza por workflows acotados.
- `docs/FAMILIAS-DEPORTISTAS.md:30` dice 24 rutas y la tabla tiene 33 filas.
- `docs/V1__crear_esquema_inicial.sql` es idéntico a la migración `V1`.

### L4 — Verificación

Documentación enlazada entre sí, sin código Java, migraciones ni commits. `git status` muestra solo documentación modificada/creada.

### L5 — Cierre

- Creados: `docs/ODD.md`, `docs/DECISIONES.md`, `docs/REVISION-DOCUMENTAL.md`, `docs/ROADMAP.md`, `docs/TEMPORADAS.md`, `odd/tasks/fase-0-decisiones.md`.
- Corregidos: `backend/AGENTS.md` (estados, tipos, licencia, competencia, OpenAPI), `docs/SEGURIDAD.md` (V1–V4), `docs/FAMILIAS-DEPORTISTAS.md` (FAMILIA y conteo de rutas).
- Sin cambios en Java, migraciones, Supabase, CI ni commits.
- Decisiones abiertas: O1–O14 en `docs/DECISIONES.md`, ninguna cerrada unilateralmente.
