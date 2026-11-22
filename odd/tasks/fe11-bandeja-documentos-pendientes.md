# FE-11 — Bandeja administrativa de documentos pendientes
Rama `feat/fe11-bandeja-documentos-pendientes` (desde `origin/main` 7e043d0, U19 incluida). Un único commit; sin push/PR/merge/despliegue.
Estrategia de entrega: ask-on-risk (el corte es chico). Runner: Maven (`./mvnw`, `-Pdb-tests`) y Vitest/Playwright.

## Specs
- S1: «Mostrar exclusivamente documentos con: `vigente = true`, `estado = CARGADO`». Incluye deportistas y tutores.
- S2: «Los reemplazos de documentos observados deben aparecer porque el sucesor está en estado CARGADO. Identificarlos mediante la existencia de `documento_anterior_id`.» No mostrar OBSERVADOS, APROBADOS ni versiones históricas no vigentes.
- S3: `GET /api/admin/documentos/pendientes`: solo ADMIN con MFA; aislamiento estricto por escuela; paginado, del más antiguo al más reciente con criterio secundario estable.
- S4: cada registro incluye identificador documental, tipo, fecha de carga, tipo de titular, identificador y nombre del titular e indicador de reemplazo; sin contenido de archivos, claves de Storage, hashes ni información sensible innecesaria.
- S5: consulta eficiente, sin consultas adicionales por resultado; sin migraciones salvo necesidad técnica demostrable.
- S6: pantalla «Documentos pendientes» en el menú administrativo; cada fila navega a la ficha del deportista o tutor; la aprobación, observación y descarga siguen en las fichas; estados de carga, error y lista vacía; filtro por titular solo si no complica.
- S7: no implementar notificaciones ni historial documental; no comenzar U20; no habilitar la carga en producción.

## Tasks
- [x] T1 · S1-S5 · inline · backend: `DocumentoPendienteService` (JdbcClient, dos consultas por página), controlador, registro OpenAPI y guardas, docs · RED/GREEN: `DocumentoPendienteDbTest` 7, `DocumentoPendienteWebMvcTest` 4; mutaciones (vigente, escuela, desempate, estado) matadas.
- [x] T2 · S6 · inline · frontend: página, API, menú, ruta, doble de backend, Vitest (12) y E2E real (3).

## Log
### L1
Pedido del usuario (resumen fiel): bandeja unificada de documentos pendientes para ADMIN; `vigente = true` y `estado = CARGADO`; reemplazos identificados por `documento_anterior_id`; endpoint `GET /api/admin/documentos/pendientes` con las condiciones S3-S5; pantalla con las condiciones S6; un único commit idealmente.
### L2 — Decisiones
- La fila lleva además `familiaId` (solo tutores; `null` en deportistas): la ficha del tutor se direcciona `/admin/familias/:familiaId/tutores/:tutorId`, no hay otra forma de enlazarla.
- Filtro opcional `titular=DEPORTISTA|TUTOR` en backend y pantalla (costo mínimo); un valor distinto es 400.
- Sin auditoría (lectura de listado, como los demás listados admin) y sin migración (`ix_documento_estado`).
- Se incluyen todos los tipos con estado `CARGADO` vigente tal como se pidió; hoy la carga solo admite DNI, y la revisión desde las fichas solo existe para DNI (otros tipos quedarían visibles sin revisión posible, ver pendientes).
- Un test existente (`deportista-documentos.test.tsx`) buscaba «cualquier enlace llamado /documento/i» para comprobar que el enlace temporal de descarga desaparece; el nuevo ítem del menú lo hacía fallar, y se acotó a `a[download]`.
### L3 — Evidencia
Backend normal verde; `-Pdb-tests` 793 con 1 error en `V5OperacionDbTest` (conexión rechazada a un contenedor de respaldo; falla igual sobre `origin/main` sin estos cambios: entorno local, no regresión). Vitest 1550/1550; eslint, `tsc`, build; E2E real local 3/3 más regresión de descarga/tutor.
