# FE-13 / U21 — Apto físico anual (backend, primer PR)
Rama `feat/u21-apto-fisico-backend` (desde `origin/main` fc926e6, con `8447e92` cherry-picked como e64124d). Tres cortes backend; commits locales; sin push/PR/merge/despliegue; sin U22. Segundo PR (frontend) solo tras integrar este.
Runner: Maven (`./mvnw`, `-Pdb-tests` para PostgreSQL 17 con Testcontainers). Estrategia de entrega: `ask-on-risk`, un único PR backend con `size:exception` a decidir al publicar.

## Specs
- S1: «Vigencia calendario hasta el 31/12 del año de la temporada (regla 2026)»; no presentada como confirmada para 2027; se permite cargar para temporadas futuras con la función habilitada y con aviso de requisitos pendientes de confirmación.
- S2: Metadatos en `documento` y en la operación de carga correspondiente; `apto_fisico` NO se usa como segunda fuente de verdad todavía.
- S3: FAMILIA carga archivo, fecha de emisión, nombre del médico y matrícula; ADMIN revisa y puede corregir metadatos con trazabilidad; matrícula alfanumérica de 1 a 50 caracteres con trim; obligatoriedad informativa y no bloqueante; la familia ve estado y metadatos autorizados sin descargar el archivo; presentación/exportación federativa y elegibilidad global quedan FUERA de U21.
- S4: Migración V17 aditiva que preserva V14, V15 y V16: metadatos exclusivos de APTO_FISICO; unicidad de la serie vigente por deportista y temporada; preservación de versiones históricas; metadatos propios por versión; integridad por escuela; compatibilidad con reemplazos, operaciones de carga y reconciliación; instalación limpia y actualización desde V16; sin backfill ficticio.
- S5: Reutilizar el motor de carga (storage, antivirus, validaciones, auditoría, reemplazos, idempotencia); metadatos y temporada en la huella calculada por el servidor; misma clave con metadatos distintos produce el conflicto correspondiente; `DOCUMENTOS_CARGA_HABILITADA` y `DOCUMENTOS_STORAGE_HABILITADO` siguen deshabilitados por defecto; sin caminos alternativos que salteen protecciones.
- S6: Privacidad: autorización de carga/lectura/descarga, almacenamiento privado, auditoría de descargas, sin datos personales ni médicos en logs, sin valores personales reales en ejemplos/pruebas, aislamiento por escuela y por familia, privacidad cuando dos familias comparten un deportista; NO ampliar permisos documentales automáticamente.
- S7: Revisión ADMIN: reutilizar aprobar/observar/descarga auditada/bandeja; corrección de metadatos con autorización y revisión optimista, auditoría de actor/documento/nombres de campos (sin valores personales), historial; DEFINIR qué pasa con una aprobación cuando se modifican los metadatos de un documento ya aprobado; sin modificaciones silenciosas que invaliden la decisión.
- S8: Estados derivados FALTA, EN_REVISION, OBSERVADO, CUMPLIDO, VENCIDO con precedencia y transiciones documentadas; vencimiento calculado por la regla de temporada con fecha/zona deterministas; distinguir regla confirmada de provisional; no modificar el checklist U20 ni inferir elegibilidad de empadronamiento.
- S9: Restricciones: «No hacer push, PR, merge ni deploy. No acceder a Supabase. No modificar migraciones históricas. No habilitar escrituras en producción. No comenzar U22. No incorporar las deudas técnicas ajenas a U21. Preservar el checkout principal.»

## Decisión de diseño (S7) — corrección de metadatos de un apto APROBADO
La corrección de metadatos de la versión vigente de un apto en estado CARGADO o APROBADO la deja en CARGADO (la aprobación se RETIRA y debe volver a revisarse) y se audita con los nombres de campos cambiados. Un apto OBSERVADO no se corrige: se reemplaza (nueva versión con sus propios metadatos). Lo impone V17 con el trigger `trg_documento_apto_metadatos` (`APTO_METADATOS_NO_EDITABLES`), no solo el servicio. Nunca queda un documento APROBADO con datos distintos a los revisados.

## Tasks
- [x] T1 · S2 S4 S8 · inline · Corte 1: V17, integridad, regla de vigencia, estado derivado y consultas · commits 61be92a, 6f17007
- [x] T2 · S3 S5 S6 · inline · Corte 2: carga FAMILIA con metadatos, huella idempotente, reemplazo de apto observado · commit 61be92a
- [x] T3 · S6 S7 · inline · Corte 3: revisión/descarga de APTO, corrección ADMIN con retiro de aprobación, bandeja · commit 6f17007
- [x] T4 · S1 S9 · inline · Docs, suites completas, Gentle AI, commits · commits 0d76caa, d5f83c6

Route: todo inline (una sola cadena de cambios dependientes en el motor; sin unidades paralelas independientes → sin Writer trigger).

## Log
### L1
Pedido del usuario (aprobación del plan U21): ver `S1`–`S9` (citas y precisiones de la aprobación). «Dejar el backend de U21 implementado, probado, revisado y guardado en commits locales. Informar migración, contratos, garantías de privacidad, pruebas, revisiones, commits, limitaciones y preparación para publicar.»

### L2 — avance 2026-11-26
V17 escrita; paquete `documentos/apto` (regla, estado, validación); motor de carga extendido (metadatos en operación y documento, reemplazo hereda temporada, unicidad por temporada). Compila. Próximo: tests RED→GREEN del corte 1/2.

### L3 — cierre local 2026-11-26
Commits locales sobre e64124d: 61be92a (V17 + carga), 6f17007 (lectura, revisión, corrección), 0d76caa (docs), d5f83c6 (V17 con roles separados + enum). Suites limpias sobre d5f83c6: normal 1523/1523, PostgreSQL 1045/1045. Orden real: el código del motor se escribió antes que sus tests (la compactación invirtió el RED); los tests verifican estado real en BD y respuestas.
Revisión Gentle AI: el candidato completo (2571 líneas) excedió el presupuesto del revisor (`lens_context_budget_exceeded`, sin autoridad creada) y se revisó en dos candidatos con consentimiento por candidato: 61be92a (lentes risk, resilience, reliability: 0 hallazgos, acknowledged) y 6f17007 (4 lentes: 2 sugerencias de legibilidad; se aplicó el uso del enum y se dejó el controlador duplicado por simetría con el checklist U20; acknowledged). La suite PostgreSQL detectó que `V16EscriturasVigenciasDbTest` asumía una sola migración pendiente: se fijó en V16 y se agregó la prueba de V17 con roles separados.
Pendiente / limitaciones: retención y borrado de aptos vencidos (sin política), regla 2027 sin confirmar, frontend (segundo PR), `V5OperacionDbTest` es inestable por Docker (pasó aislado).

### L4 — verificación de privacidad entre familias y candidato final
Pedido del usuario: los datos aportados por una familia (fecha de emisión, médico, matrícula) no deben quedar disponibles para otra familia vinculada al mismo deportista. Reproducido primero (RED, 5 fallos): `AptoFisicoService` devolvía los datos a toda familia en alcance. Corrección mínima sin cambios de esquema ni de permisos: procedencia por `documento.cargado_por → usuario.familia_id`; otra familia recibe estado, temporada, vigencia e id con `datosVisibles=false` y datos en `null`; ADMIN ve todo. Acreditado con `AptoFisicoDbTest` (+5), una carga real por HTTP con dos familias (`CargaDocumentalHttpDbTest`), y comprobación de que listado, checklist y respuestas de carga no llevan los datos y la familia no tiene ruta de archivo. Corrección ADMIN no cambia la procedencia; un reemplazo la pasa a la familia que lo hizo; revocado el vínculo la familia deja de ver todo.
Revisiones Gentle AI (cada una con consentimiento, todas aprobadas y reconocidas): 61be92a; 6f17007; 52273c7..2bd756a (privacidad); 6f17007..52273c7 (refactor a enum y test V17, para cerrar una brecha de cobertura). Las sugerencias R3-001 (enum/VENCIDO) se descartaron con evidencia (el CHECK de V1 y el enum tienen los mismos cinco estados) y R3-002 se aplicó (test reforzado).
**CONDICIÓN PENDIENTE ANTES DEL DESPLIEGUE:** política de retención y borrado de documentación médica vencida (plazo, borrado en Storage y en BD, auditoría). Sin implementar.

### L5 — motivo de observación solo para la familia que cargó (post PR #57)
Pedido del usuario: «Mantener motivoObservacion detallado visible para ADMIN y la familia que cargó la versión vigente. Ocultarlo a otras familias vinculadas al deportista.» Reproducido primero (RED, 3 fallos). Contratos familiares revisados: el endpoint del apto y el listado `GET /api/familia/deportistas/{id}/documentos` lo exponían; el checklist solo evalúa DNI, y las respuestas de carga/reemplazo son de versiones `CARGADO` (sin motivo). Corrección: campo de solo lectura `cargado_por` en la entidad y proyección familiar del repositorio con la misma regla de procedencia; la otra familia recibe `OBSERVADO` más el aviso genérico `APTO_OBSERVADO_REQUIERE_REEMPLAZO`. La política del motivo de los DNI no cambia. Sin cambios de esquema ni de permisos.
`usuario.familia_id`: ningún código lo modifica tras crear el usuario y la entidad lo mapea `updatable=false`; la base no lo impide con una restricción propia (el runtime tiene UPDATE a nivel de tabla). Riesgo bajo, documentado en `docs/SEGURIDAD.md`; endurecerlo (trigger) sería una migración nueva y no se hace sin autorización.

