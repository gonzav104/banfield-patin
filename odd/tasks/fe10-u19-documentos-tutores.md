# FE-10 / U19 — Documentación de tutores
Rama: `feat/fe10-u19-documentos-tutores` · Base: `origin/main` `700e3e3` (incluye U15–U18). Entrega local en cuatro commits sobre una única rama; PR único con `size:exception` previsto, sin publicar. Pruebas: Maven, PostgreSQL/Testcontainers, Vitest, Playwright.

## Specs
- S1: «Limitar U19 a DNI_TUTOR_FRENTE y DNI_TUTOR_DORSO.»
- S2: «Utilizar autorización por familia: un usuario FAMILIA activo podrá gestionar la documentación de los tutores activos pertenecientes a su familia, respetando el alcance de escuela y las respuestas 404 uniformes. No interpretar este permiso como acreditación de identidad del titular.»
- S3: «Incluir carga inicial, reemplazo de documentos OBSERVADOS, revisión administrativa y descarga auditada.» La familia nunca descarga documentos.
- S4: «Utilizar un punto de extensión mínimo del pipeline documental existente. Evitar duplicación de lógica y también generalizaciones arquitectónicas innecesarias.» El camino del deportista queda idéntico (diferencial base/candidato).
- S5: «Dejar fuera de U19 la obligatoriedad de documentos según edad y parentesco.» U20 define requisitos y una identificación confiable de vínculos; U22 aplica las validaciones de empadronamiento. No basar estas reglas en comparaciones frágiles de texto libre (`tutor.parentesco`).
- S6: «Utilizar una rama aislada con cuatro commits y prever size:exception para el futuro PR.»
- S7 (C1): V13 e integridad: generalizar los controles para tutores conservando los del deportista; metadatos administrativos con versión; migración sobre datos existentes y cadenas documentales.
- S8 (C2): rutas de tutor de carga y reemplazo; normalización, antivirus, Storage, idempotencia, reconciliación y reservas reutilizados; mismos controles de seguridad; autorización por familia y escuela, estados inactivos, concurrencia; pruebas diferenciales U15–U18.
- S9 (C3): adaptar U16 y U17 a tutores: MFA, CSRF, versiones, protección optimista y auditoría; sin descargas familiares; aislamiento entre escuelas y tutores.
- S10 (C4): frontend del portal familiar y detalle administrativo de tutor; recorrido cargar, observar, reemplazar y aprobar.
- S11: verificaciones focales e independientes por corte con Gentle AI (consentimiento previo a cada revisión nativa); sin push, PR, merge ni despliegue; no habilitar la carga en producción; no U20; sin bandeja de pendientes.

## Tasks
- [x] T1 · S7/S11 · inline · C1: V13, lectura admin de tutor con versión · commit C1 (ver L3); verificación independiente sin bloqueantes; Gentle AI aprobada y reconocida.
- [ ] T2 · S2/S4/S8/S11 · inline · C2: carga y reemplazo familiar de tutor · pendiente.
- [ ] T3 · S9/S11 · inline · C3: revisión y descarga ADMIN de tutor · pendiente.
- [ ] T4 · S10/S11 · inline · C4: frontend y E2E · pendiente.

Cortes secuenciales (cada uno consume el anterior): sin escritor delegado por paralelismo.

## Log
### L1 — Autorización de implementación (2026-11-21)
Apruebo las seis decisiones del plan de U19 y autorizo comenzar su implementación local. 1) DNI_TUTOR_FRENTE/DORSO. 2) Autorización por familia (usuario FAMILIA activo, tutores activos de su familia, escuela, 404 uniformes; no acredita identidad del titular). 3) Carga, reemplazo de OBSERVADOS, revisión y descarga auditada. 4) Punto de extensión mínimo, sin duplicar ni generalizar de más. 5) Obligatoriedad por edad/parentesco fuera de U19; U20 requisitos e identificación confiable de vínculos, U22 validaciones de empadronamiento. 6) Rama aislada con cuatro commits y size:exception previsto. Calidad: verificaciones focales e independientes por corte, Gentle AI con consentimiento previo; worktree nuevo desde origin/main (700e3e3). Límites: implementación, pruebas y commits locales; sin push, PR, merge ni despliegue; carga no habilitada en producción; sin U20 ni bandeja de pendientes. Hallazgos de producto registrados como pendientes (no resolver aquí): bandeja administrativa de documentos por revisar, identificación de documentos reemplazados y contexto de observaciones anteriores, notificaciones a la familia.

### L2 — Hechos que condicionan el diseño (verificados en origin/main 700e3e3)
Esquema listo para tutores (V1 `documento.tutor_id`; V5 `uq_documento_dni_tutor` y FK de cadena por tutor; V6 operación con `tutor_id` y tipos `DNI_TUTOR`). `tutor.usuario_id` no se mapea ni se gestiona: no hay vínculo usuario-tutor, el permiso solo puede ser por familia. Atado a deportista: `autorizar/reservar/publicar/comprobarPrimera` de la carga, `ALCANCE` de U17, SQL de U16, CHECK y trigger de V12, filtros de ruta. El portal familiar solo lista tutores; el detalle admin de tutor no tiene documentos.

### L3 — C1 cerrado
Pruebas: V13 7/7 (con DORSO), V12 11/11, V6 57/57, consulta 9/9; normal sin fallos; `-Pdb-tests` 769 con un único error ambiental (`V5OperacionDbTest.respaldoSeRestauraEnOtroServidor…`, `CannotGetJdbcConnection`) que pasa 11/11 al aislarlo. RED observado (6 errores sin V13; compilación sin el método de servicio). Verificación independiente: sin bloqueantes; diferencial V12/V13 de 48 pasos idéntico para deportista; mutaciones: sobrevivió M3 (reemplazo de DORSO sin prueba) y se cerró antes de la revisión nativa; M4/M6/M7 equivalentes. Gentle AI aprobada sin hallazgos y reconocida (`review-f049d4838cd8691b`; lentes risk y reliability; consentimiento del usuario).
