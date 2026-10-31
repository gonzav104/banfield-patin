# CI-3 — Protección de `main`

Objetivo: impedir la integración en `main` de cambios con pruebas fallidas, mediante protección de rama en GitHub (configuración remota, no código).
Rama de trabajo local: `feat/ci-3-proteccion` (desde `main` = `4c98d48`). Entrega: sin commits, push ni merges sin autorización. Ruta: todo inline.

## Specs

- **S1** — "Proteger exclusivamente `main`."
- **S2** — "Exigir Pull Request antes del merge."
- **S3** — "Exigir ambos checks: suite normal y suite DB, usando sus nombres reales."
  - Check: nombres publicados por Actions: `Suite normal` y `Suite db (PostgreSQL 17)` (app `github-actions`).
- **S4** — "No exigir aprobaciones de revisores, ya que actualmente soy el único desarrollador."
- **S5** — "Bloquear force push y eliminación de `main`."
- **S6** — "Mantener permitido el método merge commit; no exigir historial lineal."
- **S7** — "Evitar restricciones adicionales que puedan bloquear innecesariamente el desarrollo."
- **S8** — "Si GitHub no permite activar la protección con el plan actual, no cambies la visibilidad del repositorio ni contrates un plan. Informá la limitación."
- **S9** — "No modificar código Java, workflows existentes, migraciones ni datos de Supabase. No crear commits, push o merges adicionales sin autorización."
- **S10** — Validación: "Consultar nuevamente la configuración remota y comprobar que las reglas están activas", "Verificar que ambos checks son obligatorios", "Confirmar que el procedimiento de merge continúa siendo viable", "Registrar evidencia en ODD y Engram."

## Tasks

| ID | S# | Tarea | Ruta | Estado | Evidencia |
| --- | --- | --- | --- | --- | --- |
| T1 | — | Verificar PR #2 fusionado y actualizar `main` | inline | hecho | PR #2 `MERGED` `4c98d48`; `git pull --ff-only origin main` |
| T2 | S3 | Confirmar nombres de checks | inline | hecho | `Suite normal`, `Suite db (PostgreSQL 17)` |
| T3 | S8 | Confirmar que el repo admite protección | inline | hecho | repo público, `viewerPermission: ADMIN`, sin protección ni rulesets previos |
| T4 | S1–S7 | Aplicar la protección | inline | hecho | `PUT /repos/gonzav104/banfield-patin/branches/main/protection` |
| T5 | S10 | Releer la configuración y probar viabilidad del merge | inline | hecho | releída por API (ver L3); viabilidad real del merge pendiente del próximo PR |
| T6 | S10 | Registrar evidencia en ODD y Engram | inline | hecho | este `L3` + topic `odd/ci-3-proteccion/tasks` |

## Log

### L1 — Pedido original (verbatim)

> Autorizo comenzar `ci-3-proteccion` siguiendo Organic Driven Development (ODD).
>
> **Objetivo:** proteger la rama `main` del repositorio Banfield Patín Carrera para impedir la integración de cambios con pruebas fallidas.
>
> Configuración requerida:
> - Proteger exclusivamente `main`.
> - Exigir Pull Request antes del merge.
> - Exigir ambos checks: suite normal y suite DB, usando sus nombres reales.
> - No exigir aprobaciones de revisores, ya que actualmente soy el único desarrollador.
> - Bloquear force push y eliminación de `main`.
> - Mantener permitido el método merge commit; no exigir historial lineal.
> - Evitar restricciones adicionales que puedan bloquear innecesariamente el desarrollo.
>
> Aplicá la configuración mediante GitHub CLI/API si los permisos lo permiten.

### L2 — Evidencia previa

- Repo público; el plan permite protección de ramas. Sin reglas previas (`branches/main/protection` 404, `rulesets` vacío).
- Merge commit, squash y rebase permitidos a nivel repo; la protección no los restringe.
- Decisión de diseño: `enforce_admins: true`. Sin esto, el único usuario (ADMIN) podría empujar a `main` sin PR ni checks y la protección sería decorativa. Es reversible. `strict: false` (no obliga a rebasar la rama contra `main`).

### L3 — Evidencia remota (releída tras aplicar)

`GET /branches/main/protection`:

| Regla | Valor |
| --- | --- |
| Checks obligatorios | `Suite normal`, `Suite db (PostgreSQL 17)` (app `github-actions`, id 15368) |
| `strict` (rama al día con `main`) | no |
| Pull Request requerido | sí, con 0 aprobaciones, sin code owners |
| `enforce_admins` | sí |
| Force push / eliminación | bloqueados |
| Historial lineal / resolución de conversaciones | no exigidos |
| Restricciones de push | ninguna |

`main` es la única rama con `protected=true`. Merge commit, squash y rebase siguen habilitados a nivel repositorio.

**Viabilidad del merge:** la configuración no exige aprobaciones ni historial lineal, así que un PR con ambos checks verdes se puede fusionar con merge commit. No se probó con un PR real: hacerlo requeriría push y PR no autorizados; queda confirmado con el próximo PR de la Fase 1.

**Riesgos:**
- `enforce_admins` impide empujar directo a `main` incluso al propietario; si hiciera falta una excepción hay que relajar la regla desde Settings.
- Los checks se identifican por nombre: renombrar un job en `backend.yml` dejaría el PR esperando un check inexistente hasta actualizar la regla.
- La protección es configuración remota, no versionada en el repo.

### L4 — Validación con PR real

PR [#3](https://github.com/gonzav104/banfield-patin/pull/3), commit `c595260`, rama `feat/ci-3-proteccion` basada en `main` = `4c98d48`. Sin fallos artificiales.

| Momento | Estado del PR |
| --- | --- |
| Checks `IN_PROGRESS` | `mergeable=MERGEABLE`, `mergeStateStatus=BLOCKED` |
| Checks terminados | `mergeable=MERGEABLE`, `mergeStateStatus=CLEAN` |

| Run | Evento | Job | Tests | Fallos | Errores | Omitidos | Build |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `41236265224` | `push` | Suite normal | 766 | 0 | 0 | 0 | SUCCESS |
| `41236265224` | `push` | Suite db (PostgreSQL 17) | 324 | 0 | 0 | 0 | SUCCESS |
| `41236270183` | `pull_request` | Suite normal | 766 | 0 | 0 | 0 | SUCCESS |
| `41236270183` | `pull_request` | Suite db (PostgreSQL 17) | 324 | 0 | 0 | 0 | SUCCESS |

El bloqueo mientras los checks estaban pendientes y la liberación al terminar confirman que ambos son obligatorios y que el PR queda habilitado para fusionar con merge commit (aún no fusionado). RDD: `assess` del commit = `passive`, `review_due=false`; no se inició revisión.

No se probó el rechazo con un check fallido (no se introducen fallos artificiales).

