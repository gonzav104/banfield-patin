# CI-2 — Suite db (PostgreSQL 17 + Testcontainers) en GitHub Actions

Objetivo: sumar al workflow del backend un segundo job que corre las pruebas `@Tag("db")` contra PostgreSQL 17 descartable (Testcontainers), sin Supabase ni secretos.
Rama: `feat/ci-2-suite-db`, creada desde `main` (`dacbdbe`, PR #1 fusionado). Entrega: `ask-on-risk` (sin push ni merge). Runner: `ubuntu-latest` (Docker disponible); verificación local limitada por la falta de Docker.

## Specs

- **S1** — "desarrollar `ci-2-suite-db`, incorporando GitHub Actions para ejecutar las pruebas de integración con PostgreSQL 17 y Testcontainers."
  - Check: `.github/workflows/backend.yml` tiene un job `suite-db` que ejecuta `./mvnw -B verify -Pdb-tests` (ROADMAP: "Segundo job con `./mvnw -B verify -Pdb-tests`").
- **S2** — "Job verde; sin Supabase ni secretos" (ROADMAP, verificación de `ci-2-suite-db`).
  - Check: el job no declara `env` con secretos ni `secrets.*`; la imagen es `postgres:17-alpine` (`PostgresDescartable`).
- **S3** — "No repitas implementaciones existentes, no modifiques Supabase y no hagas commits, merges ni push sin autorización."
  - Check: no se toca `backend/src`, `backend/pom.xml` ni migraciones; el job `suite-normal` conserva su contenido; sin push ni merge.
- **S4** — Autorizado: "pasos 1 a 3 en local, con un commit convencional en `feat/ci-2-suite-db` y sin push".
  - **S4.1** — Crear este artefacto ODD y su espejo Engram `odd/ci-2-suite-db/tasks`.
  - **S4.2** — Agregar el job `suite-db`, en paralelo e independiente de `suite-normal`.
  - **S4.3** — Ajustar `concurrency` para agrupar por `github.event_name` (observación de CI-1).
- **S5** — Verificar la sintaxis del workflow. Verificar el job en verde requiere push y PR: no autorizado (supuesto: queda pendiente).

## Tasks

| ID | S# | Tarea | Ruta | Estado | Evidencia |
| --- | --- | --- | --- | --- | --- |
| T1 | S4.1 | Crear este artefacto y el espejo Engram | inline | hecho | archivo + topic `odd/ci-2-suite-db/tasks` |
| T2 | S1, S2, S4.2 | Agregar el job `suite-db` | inline | hecho | job paralelo, `./mvnw -B verify -Pdb-tests`, sin `env` ni `secrets` |
| T3 | S4.3 | Ajustar `concurrency` por `github.event_name` | inline | hecho | grupo `backend-<workflow>-<event_name>-<ref>` |
| T4 | S5 | Validar YAML | inline | hecho | `yaml.safe_load` OK: jobs `suite-normal`, `suite-db`; `on: push, pull_request` |
| T5 | S4 | Commit convencional local | inline | hecho | commit local en `feat/ci-2-suite-db` |
| T6 | S5 | Publicar, abrir PR y verificar el job en Actions | inline | hecho | PR [#2](https://github.com/gonzav104/banfield-patin/pull/2); runs `41235342551` (push) y `41235346678` (pull_request): ambos jobs verdes (ver L4) |

## Log

### L1 — Pedido original (verbatim)

> Vamos a continuar el desarrollo del backend de **Banfield Patín Carrera**.
>
> **Próximo objetivo:** desarrollar `ci-2-suite-db`, incorporando GitHub Actions para ejecutar las pruebas de integración con PostgreSQL 17 y Testcontainers.
>
> No repitas implementaciones existentes, no modifiques Supabase y no hagas commits, merges ni push sin autorización.

### L2 — Autorización (verbatim, 2026-10-31)

> si, te autorizo

(a la propuesta: pasos 1 a 3 en local, con un commit convencional en `feat/ci-2-suite-db` y sin push.)

### L3 — Evidencia previa

- PR #1 `MERGED` (2026-10-31 06:55Z, `dacbdbe`); `main` = `origin/main`.
- `backend/pom.xml`: el perfil `db-tests` fija `groups=db` y anula `excludedGroups`.
- `PostgresDescartable`: `postgres:17-alpine`, `max_connections=300`, valida que el host sea loopback.
- Docker no está disponible localmente: la suite db solo se puede ejecutar en Actions.
- Riesgo del ROADMAP: la imagen se descarga en cada corrida; se acepta el costo inicial.

Risk: none previsto (solo workflow nuevo; no altera `suite-normal` ni contratos).

### L4 — Evidencia remota

Rama publicada con `git push -u origin feat/ci-2-suite-db` y PR abierto contra `main`: **https://github.com/gonzav104/banfield-patin/pull/2** (sin merge). Commit verificado: `aaa7de3`, basado en `main` = `dacbdbe` (PR #1 fusionado).

| Run | Evento | Job | Tests | Fallos | Errores | Omitidos | Build | Tiempo Maven |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `41235342551` | `push` | Suite normal | 766 | 0 | 0 | 0 | SUCCESS | 33.9 s |
| `41235342551` | `push` | Suite db (PostgreSQL 17) | 324 | 0 | 0 | 0 | SUCCESS | 51.1 s |
| `41235346678` | `pull_request` | Suite normal | 766 | 0 | 0 | 0 | SUCCESS | 39.2 s |
| `41235346678` | `pull_request` | Suite db (PostgreSQL 17) | 324 | 0 | 0 | 0 | SUCCESS | 65 s |

Los 766 tests de la suite normal coinciden con CI-1. Testcontainers descargó `testcontainers/ryuk:0.14.0` y `postgres:17-alpine` en cada job; el costo de la descarga quedó dentro de ~1 min. Sin secretos ni Supabase.

Riesgos pendientes (no bloquean; sugerencias de la revisión RDD aprobada):
- `push` y `pull_request` corren ambos en una rama con PR (duplica la carga de Docker).
- `suite-db` no sube los reportes surefire si falla.
- Aún sin `ci-3-proteccion`: los jobs no son obligatorios en `main`.
