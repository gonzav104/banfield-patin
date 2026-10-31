# CI-1 — Suite normal en GitHub Actions

Objetivo: incorporar el primer workflow de integración continua del backend (suite normal, sin Docker ni Supabase).
Rama: `feat/ci-1-suite-normal`, creada desde `main` con los cambios de Fase 0 preservados. Entrega: `ask-on-risk` (sin push ni merge). Runner: `ubuntu-latest` en GitHub Actions; verificación local en OpenJDK 21.

## Specs

- **S1** — "Crear una rama de trabajo desde `main`, preservando todos los cambios documentales pendientes de Fase 0."
  - Check: `git switch -c feat/ci-1-suite-normal` desde `main` sin descartar ni stashear el working tree.
- **S2** — "Primero revisar los cambios de Fase 0 para detectar inconsistencias o errores evidentes antes de incorporarlos a un commit separado."
  - Check: revisión de enlaces internos, encabezados y tablas de `docs/DECISIONES.md`, `docs/ODD.md`, `docs/ROADMAP.md`, `docs/TEMPORADAS.md`, `docs/REVISION-DOCUMENTAL.md` y de los tres archivos modificados; la Fase 0 va en su propio commit, previo al de CI.
- **S3** — "Implementar `ci-1-suite-normal` mediante GitHub Actions:"
  - **S3.1** — "Workflow `.github/workflows/backend.yml`."
  - **S3.2** — "Ejecutar `./mvnw -B clean verify`."
  - **S3.3** — "Java 21."
  - **S3.4** — "Caché Maven."
  - **S3.5** — "Disparadores `push` y `pull_request`."
  - **S3.6** — "Sin credenciales reales ni acceso a Supabase."
- **S4** — "Crear el artefacto ODD correspondiente y su espejo Engram antes de modificar código/configuración."
  - Check: este archivo y el topic `odd/ci-1-suite-normal/tasks` existen y se leyeron de vuelta **antes** de crear el workflow.
- **S5** — "Verificar sintaxis del workflow y ejecutar localmente la suite."
  - Check: parseo YAML exitoso + `./mvnw -B clean verify` verde en `backend/` sin `JWT_SECRET` ni `MFA_CLAVE_CIFRADO` en el entorno.
- **S6** — "No modificar código Java, migraciones ni configuraciones de producción."
  - Check: `git diff --stat` de la unidad no toca `backend/src/main`, `backend/src/test` ni `backend/pom.xml`.
- **S7** — "No hacer push ni merge todavía."
  - Check: los commits quedan locales en `feat/ci-1-suite-normal`.
- **S8** — "Mantener los mensajes de commit en español, respetando Conventional Commits."
- **S9** — "Cuando termine `ci-1`, continuaremos con `ci-2-suite-db`, incorporando Testcontainers PostgreSQL 17."
  - Alcance: fuera de esta unidad; no se crea el segundo job todavía.
- **S10** — "Corrección adicional: verificá el código de error de concurrencia documentado en `docs/TEMPORADAS.md`. Aparece `CONCURSO_CONCURRENCIA`, que podría ser un error tipográfico frente al código existente `CONFLICTO_CONCURRENCIA`. Corregilo únicamente si confirmás la inconsistencia."
  - Check: búsqueda en repo de ambos literales; si `CONCURSO_CONCURRENCIA` no existe, no se toca nada.
- **S11** — "Al terminar, informá los archivos modificados, las pruebas ejecutadas, el estado de ODD/Engram, los commits realizados y qué falta para verificar GitHub Actions en remoto."
- **S12** — "Autorizo publicar la rama `feat/ci-1-suite-normal` en GitHub y abrir un Pull Request hacia `main`."
  - **S12.1** — "No hacer merge todavía."
  - **S12.2** — "No modificar código ni migraciones."
  - **S12.3** — "Verificar la ejecución real del workflow en GitHub Actions."
  - **S12.4** — "Confirmar que la suite normal termina satisfactoriamente."
  - **S12.5** — "Si falla, diagnosticar la causa antes de corregir y mantener los cambios dentro del alcance de CI-1."
  - **S12.6** — "Registrar la evidencia de ejecución en el artefacto ODD y Engram."

## Tasks

| ID | S# | Tarea | Ruta | Estado | Evidencia |
| --- | --- | --- | --- | --- | --- |
| T1 | S1 | Crear rama `feat/ci-1-suite-normal` desde `main` | inline | hecho | `git switch -c`, working tree intacto |
| T2 | S2 | Revisar los cambios de Fase 0 antes de commitear | inline | hecho | sin enlaces `docs/docs`, encabezados y 11 filas D consistentes |
| T3 | S10 | Verificar `CONCURSO_CONCURRENCIA` vs `CONFLICTO_CONCURRENCIA` | inline | hecho | 0 ocurrencias de `CONCURSO_CONCURRENCIA`; `docs/TEMPORADAS.md:70` ya dice `CONFLICTO_CONCURRENCIA` |
| T4 | S4 | Crear este artefacto y el espejo Engram antes de tocar config | inline | hecho | `odd/tasks/ci-1-suite-normal.md` + topic `odd/ci-1-suite-normal/tasks` leído |
| T5 | S3 | Crear `.github/workflows/backend.yml` | inline | hecho | job `suite-normal`, temurin 21, `cache: maven`, `working-directory: backend` |
| T6 | S5 | Validar YAML | inline | hecho | `yaml.safe_load` OK; `on: [push, pull_request]` |
| T7 | S5 | Ejecutar `./mvnw -B clean verify` sin variables de entorno | inline | hecho | `Tests run: 766, Failures: 0, Errors: 0, Skipped: 0` · BUILD SUCCESS · 38 s |
| T8 | S3 | Corregir el bit de ejecución de `backend/mvnw` en el índice | inline | hecho | `git update-index --chmod=+x`; el checkout previo lo dejaba en `100644` y `./mvnw` habría fallado con *Permission denied* |
| T9 | S2 | Commit de Fase 0 | inline | hecho | `docs(fase-0): consolidar decisiones, roadmap y reglas de temporadas` |
| T10 | S3 | Commit de CI-1 | inline | hecho | `ci(backend): agregar workflow de integración continua` |
| T11 | S11 | Informe final con pruebas, ODD/Engram y pendiente remoto | inline | hecho | respuesta al usuario |
| T12 | S12 | Publicar la rama y abrir el PR hacia `main` | inline | hecho | PR [#1](https://github.com/gonzav104/banfield-patin/pull/1), sin merge |
| T13 | S12.3, S12.4 | Verificar la ejecución real en GitHub Actions | inline | hecho | run `41177567411`: 766 tests, 0 fallos, BUILD SUCCESS, 49 s |
| T14 | S12.5 | Corregir las deprecaciones que el run expuso | inline | hecho | `actions/checkout@v4`→`v5` y `actions/setup-java@v4`→`v5`; run de verificación verde |
| T15 | S12.6 | Registrar la evidencia en el artefacto ODD y en Engram | inline | hecho | este `L8` + upsert del topic `odd/ci-1-suite-normal/tasks` |

## Log

### L1 — Pedido original (verbatim)

> Aprobado. Vamos a comenzar por `ci-1-suite-normal`.
>
> Trabajá siguiendo el protocolo real ODD de Gentle AI.
>
> **Objetivo:** implementar la primera unidad de integración continua del backend de Banfield Patín Carrera.
>
> Condiciones:
>
> 1. Crear una rama de trabajo desde `main`, preservando todos los cambios documentales pendientes de Fase 0.
> 2. Primero revisar los cambios de Fase 0 para detectar inconsistencias o errores evidentes antes de incorporarlos a un commit separado.
> 3. Implementar `ci-1-suite-normal` mediante GitHub Actions:
>    - Workflow `.github/workflows/backend.yml`.
>    - Ejecutar `./mvnw -B clean verify`.
>    - Java 21.
>    - Caché Maven.
>    - Disparadores `push` y `pull_request`.
>    - Sin credenciales reales ni acceso a Supabase.
> 4. Crear el artefacto ODD correspondiente y su espejo Engram antes de modificar código/configuración.
> 5. Verificar sintaxis del workflow y ejecutar localmente la suite.
> 6. No modificar código Java, migraciones ni configuraciones de producción.
> 7. No hacer push ni merge todavía.
> 8. Mantener los mensajes de commit en español, respetando Conventional Commits.
>
> Cuando termine `ci-1`, continuaremos con `ci-2-suite-db`, incorporando Testcontainers PostgreSQL 17.
>
> **Corrección adicional:** verificá el código de error de concurrencia documentado en `docs/TEMPORADAS.md`. Aparece `CONCURSO_CONCURRENCIA`, que podría ser un error tipográfico frente al código existente `CONFLICTO_CONCURRENCIA`. Corregilo únicamente si confirmás la inconsistencia.
>
> Al terminar, informá los archivos modificados, las pruebas ejecutadas, el estado de ODD/Engram, los commits realizados y qué falta para verificar GitHub Actions en remoto.

### L2 — Clasificación ODD

Unidad chica y con resultado observable (un workflow de 20 líneas + commits). Se reanuda con el pedido y el diff, pero el usuario pidió explícitamente artefacto ODD y espejo Engram: se crean igual, antes de escribir configuración. Rutas: todo inline.

### L3 — Evidencia relevada antes de escribir

- `docs/TEMPORADAS.md:70` ya dice `409 CONFLICTO_CONCURRENCIA`; `grep -rI "concurso"` en el repo no devuelve nada. **No hay inconsistencia que corregir.**
- `backend/src/test/resources/application-test.yml` define el secreto JWT y la clave MFA ficticios; la suite normal no necesita secretos.
- `backend/mvnw` es ejecutable (`-rwxr-xr-x`) y está versionado.
- No existe `.github/` en el repositorio.
- Java local: OpenJDK 21.0.12.1; `JAVA_HOME` vacío; `JWT_SECRET` y `MFA_CLAVE_CIFRADO` sin definir.

### L4 — Decisión de diseño del workflow

- Un solo job `suite-normal` en `ubuntu-latest`.
- `actions/checkout@v4` + `actions/setup-java@v4` (temurin 21, `cache: maven`), que usa el `pom.xml` de `backend/` como clave de caché. *(Actualizadas a `@v5` en L8 por deprecación.)*
- `defaults.run.working-directory: backend` para que `./mvnw` resuelva el wrapper.
- `permissions: contents: read` explícito.
- `timeout-minutes` acotado para no colgar runners.
- Sin `env` con secretos: la suite normal no los necesita.
- `ci-2-suite-db` (Testcontainers) queda para la siguiente unidad, en otro job.

### L5 — Verificación

- `yaml.safe_load('.github/workflows/backend.yml')` OK: un job, `runs-on: ubuntu-latest`, `working-directory: backend`, último step `./mvnw -B clean verify`.
- Suite local sin secretos (`env -u JWT_SECRET -u MFA_CLAVE_CIFRADO`): **766 tests, 0 fallos, 0 errores, 0 omitidos**, `BUILD SUCCESS` en 38 s.
- Hallazgo: `backend/mvnw` estaba versionado como `100644` y `core.fileMode=false` ocultaba la diferencia. Se corrigió el índice a `100755`; `git checkout-index -a --prefix=/tmp/...` confirma que un checkout nuevo lo deja ejecutable.
- `grep -rI "concurso"` sin resultados: `docs/TEMPORADAS.md` ya usaba `CONFLICTO_CONCURRENCIA`. **Sin cambios.**
- Sin tocar `backend/src/main`, `backend/src/test`, `backend/pom.xml` ni migraciones.

### L6 — Pendiente remoto

Verificar el workflow en verde requiere `push` de `feat/ci-1-suite-normal` y un PR: no autorizado en esta unidad. `ci-2-suite-db` (Testcontainers PostgreSQL 17) queda para la unidad siguiente.

### L7 — Autorización de publicación (verbatim)

> La implementación local de `ci-1-suite-normal` está aprobada.
>
> Autorizo publicar la rama `feat/ci-1-suite-normal` en GitHub y abrir un Pull Request hacia `main`.
>
> Condiciones:
> - No hacer merge todavía.
> - No modificar código ni migraciones.
> - Verificar la ejecución real del workflow en GitHub Actions.
> - Confirmar que la suite normal termina satisfactoriamente.
> - Si falla, diagnosticar la causa antes de corregir y mantener los cambios dentro del alcance de CI-1.
> - Registrar la evidencia de ejecución en el artefacto ODD y Engram.
>
> Al finalizar, informar URL del PR, resultado del workflow y si la unidad puede considerarse validada remotamente.

### L8 — Evidencia remota

Rama publicada con `git push -u origin feat/ci-1-suite-normal` y PR abierto contra `main`: **https://github.com/gonzav104/banfield-patin/pull/1** (sin merge).

| Run | Evento | Workflow | Resultado | Duración |
| --- | --- | --- | --- | --- |
| `41177417313` | `pull_request` | `@v4` | ✅ success | 57 s |
| `41177567411` | `pull_request` | `@v5` | ✅ success | 49 s |

Salida del run `41177567411` (última versión del workflow):

```
[INFO] Tests run: 766, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  37.302 s
```

El conteo remoto coincide exactamente con el local (766). Sin secretos configurados en el repositorio; el job no usó credenciales ni Supabase.

**Diagnóstico del primer run (no fue un fallo, fue deuda):** GitHub emitió dos anotaciones de deprecación sobre `actions/checkout@v4` (Node.js 20 forzado a 24) y `actions/setup-java@v4` ("deprecated and will no longer receive updates. Please migrate to actions/setup-java@v5"). Se verificaron los inputs de v5 (`distribution`, `java-version`, `cache`, `cache-dependency-path` se mantienen), se actualizaron ambas acciones y se re-ejecutó: verde y sin las dos anotaciones. Queda solo el aviso informativo de migración de `ubuntu-latest` a Ubuntu 26.

**Alcance respetado:** los commits remotos solo tocan `.github/workflows/backend.yml`, `odd/tasks/ci-1-suite-normal.md` y el modo de `backend/mvnw`. Sin cambios en `backend/src`, `backend/pom.xml` ni migraciones.

**Coincidencia a vigilar:** `concurrency` agrupa por `workflow-ref`, pero los eventos `push` y `pull_request` del mismo commit corrieron en paralelo en vez de cancelarse. No afecta el resultado; si molesta, `ci-2` puede agrupar por `github.event_name` o quitar `pull_request` para ramas del mismo repo.
