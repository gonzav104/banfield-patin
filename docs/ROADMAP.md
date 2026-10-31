# Roadmap

Secuencia vigente desde 2026-10-31. Reemplaza el roadmap anterior. Cada fase cierra con evidencia, no con fechas.

## 1. Fases

| Fase | Resultado | Alcance | Depende de |
| --- | --- | --- | --- |
| **0** | Decisiones y documentación cerradas | `docs/ODD.md`, `docs/DECISIONES.md`, `docs/REVISION-DOCUMENTAL.md`, `docs/TEMPORADAS.md`, este roadmap; estados alineados; procedimiento de segunda cuenta ADMIN | — |
| **CI-0** | Pipeline verde | Suite normal y suite Testcontainers en GitHub Actions, sin secretos reales | Repo en GitHub |
| **1** | Temporadas y datos anuales | `Temporada` + `DeportistaTemporada`; activación única; DNI enmascarado en listados | 0, CI-0 |
| **2** | Spike federativo | Playwright sobre los formularios reales de empadronamiento y apto: login Google, campos, secciones condicionales, uploads, hasta antes de "Enviar" | 0 |
| **3** | Documentos privados | Supabase Storage privado; carga FAMILIA/ADMIN; revisión; reemplazo; descarga firmada; ZIP | 1, 2 |
| **4** | Aptos físicos | Apto por deportista/temporada con documento, médico, matrícula, estados | 1, 3 |
| **5** | Empadronamiento y licencias | Checklist, estados, confirmación ADMIN, **historial de dominio** por cambio | 1, 3, 4 |
| **6** | Competencias | Convocatoria explícita materializada; respuesta FAMILIA solo para convocados; consolidado ADMIN | 1, 5 |
| **7** | LBF APM/Nacional | Plantillas oficiales reales, mapeo, Apache POI, validación previa | 6 + plantillas |
| **8** | Rifas | Creación, asignación, estado de número (FAMILIA) y de pago (ADMIN), totales | 0, 6 |
| **9** | Ficha, dashboards y auditoría | RF-08, RF-24, consulta de auditoría con filtros y retención 12 meses | Todas |
| **10** | Hardening | Carga, backup/restore, monitoreo, seguridad, OpenAPI, dependencias | Todas |

### Bloqueos explícitos

- **Fase 7 bloqueada** hasta tener las plantillas oficiales APM y Nacional reales. No se inventan plantillas.
- **Fase 2 bloqueada** hasta definir cuenta y datos de prueba (O12).
- **Fase 8** necesita una migración nueva: `numero_rifa` no tiene estado de pago.
- **Fase 5** necesita una migración nueva: no existe historial federativo.

## 2. Orden de unidades ODD

### CI-0

| Unidad | Contenido | Verificación |
| --- | --- | --- |
| `ci-1-suite-normal` | `.github/workflows/backend.yml`: job con `./mvnw -B clean verify`, cache de Maven, triggers `push` y `pull_request` | Workflow verde en un PR de prueba |
| `ci-2-suite-db` | Segundo job con `./mvnw -B verify -Pdb-tests` (Docker disponible en `ubuntu-latest`) | Job verde; sin Supabase ni secretos |
| `ci-3-proteccion` | Jobs obligatorios en `main` (configuración de GitHub, no código) | PR bloqueado si falla un job |

Riesgo de `ci-2`: Testcontainers descarga la imagen en cada corrida. Mitigación: cache de imágenes o aceptar el costo inicial.

### Fase 1 — Temporadas

| Unidad | Contenido | Verificación |
| --- | --- | --- |
| `temp-1-temporada` | Entidad, repositorio, servicio, controller ADMIN: alta, listado, detalle, activación con `FOR UPDATE` + índice parcial | Servicio, WebMvc (401/403/CSRF/validación), DB (unicidad, activación concurrente, auditoría) |
| `temp-2-deportista-temporada` | Alta, edición y consulta de categoría, rama, divisional, número de corredor y estado deportivo | Servicio, WebMvc, DB (unicidad por deportista/temporada y por número de corredor) |
| `temp-3-lectura-familia` | Lectura de temporada y datos anuales para FAMILIA, solo de sus deportistas vinculados | WebMvc de autorización; prueba de aislamiento entre familias |
| `temp-4-dni-enmascarado` | `DeportistaResumen` sin DNI completo; búsqueda por prefijo se mantiene; DNI completo solo en detalle | WebMvc de contrato + prueba de búsqueda |
| `temp-5-cierre` | OpenAPI, documentación del módulo, eventos de auditoría, revisión de riesgos | `./mvnw clean verify` y `-Pdb-tests` verdes |

Orden: `ci-1` y `ci-2` antes de cerrar Fase 1; `temp-4` puede hacerse en paralelo porque no depende de las otras unidades.

## 3. Reglas transversales

- Toda ruta nueva entra con su prueba de autorización y su entrada OpenAPI en la misma unidad (RNF-14).
- Todo cambio de esquema va en una migración nueva; `V1`–`V4` son inmutables.
- Las pruebas que necesitan PostgreSQL real llevan `@Tag("db")` y corren con `-Pdb-tests`.
- Sin secretos en el repositorio ni en los workflows.
- Sin datos de menores en logs ni en memorias de trabajo.
