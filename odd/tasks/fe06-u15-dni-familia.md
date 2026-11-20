# FE-06 / U15 — DNI del deportista desde la ficha familiar
Objetivo: primera unidad del circuito documental U15→U18 (carga familiar del DNI frente/dorso), con la carga deshabilitada por defecto. Rama `feat/fe06-u15-dni-familia` (base `main` e75cbc6). Entrega: tres commits locales, un PR posterior (sin push/PR/merge autorizados). Runners: Maven/JUnit (`./mvnw`), Vitest, Playwright E2E reales con PostgreSQL.

## Specs
- S1 (aislamiento): «la carga debe permanecer deshabilitada por defecto. La configuración de CI con dobles no debe afectar producción ni otros entornos. Verificar que las pruebas existentes continúen funcionando y que no exista contaminación entre casos.»
- S2 (móvil): «implementar la normalización mediante Canvas sin agregar dependencias innecesarias. No asumir compatibilidad universal con HEIC. Documentar los navegadores efectivamente probados y los casos pendientes de validación en dispositivos reales.»
- S3 (legibilidad): «asegurar que la normalización mantenga la orientación correcta y una calidad suficiente para leer el DNI. Si no puede garantizarse una transformación válida, informar claramente al usuario. No presentar la comprobación de dimensiones como garantía de legibilidad.»
- S4 (idempotencia): «preservar exactamente los bytes normalizados y la Idempotency-Key para reintentos de la misma operación. Verificar especialmente las respuestas perdidas, los conflictos 409 y los resultados inciertos.»
- S5 (privacidad): «mantener nombres de archivo neutros, eliminar metadatos de imágenes mediante recodificación y evitar persistencia intencional de los documentos en el navegador.»
- S6 (seguridad): «no modificar ni debilitar los controles existentes del pipeline de carga. Los dobles deben permanecer exclusivamente en la infraestructura de pruebas, nunca en el artefacto de producción.»
- S7 (verificación): «ejecutar pruebas unitarias, integración y E2E reales con PostgreSQL. Mantener también las suites actuales como regresión. La integración simulada no reemplaza las verificaciones opt-in contra Supabase y ClamAV reales.»
- S8 (alcance): «no adelantar U16, U17, U18 ni la migración V12. No habilitar la carga en producción.»
- S9 (P2, contrato aprobado): `GET /api/familia/documentos/disponibilidad` → `200 {"habilitada": bool, "maxBytes": number | null}`, solo FAMILIA, refleja configuración y no salud del escáner; registrado en `OpenApiConfig`. `apiWrite` admite cabeceras opcionales sin que puedan sobrescribir la cabecera CSRF.
- S10 (P1, aprobada): «utilizar dobles externos de Storage y ClamAV en los dos shards E2E existentes.»
- S11 (plan aprobado): sección «Documentos del deportista» en la ficha familiar con espacios DNI frente y dorso (Falta / En revisión / Aprobado / Observado + motivo), carga por cámara o archivo (JPG/PNG/PDF), vista previa local, normalización (orientación, lado largo ≤ 2400 px, JPEG 0.85 con escalones 0.75/0.65, aviso de legibilidad si el lado corto < 1000 px), PDF sin cambios, nombre neutro, reintento manual con la misma clave y los mismos bytes, criterios de aceptación 1–8 del plan.
- S12 (detención): «Si encontrás un problema que requiera ampliar el alcance o modificar contratos previamente aprobados, detené esa modificación y presentame las alternativas antes de continuar.»

## Tasks
- [x] T1 · S1, S6, S9 · inline · Backend: `DisponibilidadCargaController` + `OpenApiConfig` + pruebas WebMvc + `docs/DOCUMENTOS.md`. Risk: none (endpoint aditivo de solo lectura). Commit `9479c77`.
- [x] T2 · S2–S5, S9, S11 · inline · Frontend: `apiWrite` con cabeceras, API/esquemas/hooks, `DocumentsSection`, `DniSlot`, `image-normalization`, `upload-outcome`, `fakeBackend`, Vitest. Commit `ea4ccb0`.
- [x] T3 · S1, S4–S7, S10 · delegated (disparador: respaldo de contexto largo de la sesión padre) · E2E: dobles Storage/clamd, fixtures JPEG con GPS y bytes finales, `documentos-dni.real.spec.ts`, `shards.json`, `frontend-e2e.sh`. Risk: item 5 (script de CI). Commit: el tercero de la unidad (`test(documentos)`).
- [x] T4 · S7 · inline + verificador independiente · Suites completas, revisión independiente, `review assess`, tres commits locales.

Ruta: T1–T2 inline. T3 delegada a un escritor acotado por el respaldo de contexto (~150k) de la sesión padre; el padre conserva git y verifica.

## Log
### L1 — Autorización de implementación (textual), 2026-11-20
Apruebo el plan ejecutable de U15 con las decisiones propuestas:

- **P1 = A:** utilizar dobles externos de Storage y ClamAV en los dos shards E2E existentes.
- **P2:** aprobar el nuevo endpoint de disponibilidad con el contrato propuesto.

Autorizo comenzar la implementación de U15 exclusivamente, respetando el alcance definido.

(Condiciones 1–8 transcriptas como S1–S8.) Implementá en un worktree nuevo desde origin/main, siguiendo el proceso de desarrollo, pruebas y revisión independiente establecido en el proyecto. Podés organizar los cambios en los tres commits locales propuestos. Antes de publicar la rama, abrir el PR o integrar cambios, presentame: resumen de implementación; archivos modificados; resultados de las pruebas; hallazgos de la revisión independiente; riesgos pendientes y desviaciones respecto del plan; estado del worktree y commits locales. **Queda autorizada la implementación local de U15. No autorizo todavía push, apertura de PR ni merge.**

### L2 — Contexto de decisiones previas
T1 (V12 en U18) y T2–T6 aprobadas el 2026-11-20 (Engram `decision/documentos-circuito-dni-t1`, `decision/documentos-circuito-dni-t2-t6`). Hechos verificados para el plan: `StoragePropiedades` admite `http` solo en loopback; `ClamAvPrivado` admite loopback; los esquemas zod del portal no son `.strict()`.

### L3 — Desviación menor anunciada
`frontend/CLAUDE.md` exige «mostrar progreso si la API lo permite»: se agrega un porcentaje de subida simple (axios `onUploadProgress`), aunque el plan excluía la barra de progreso. Sin nuevo contrato ni dependencia.

### L4 — T1 y T2 implementadas (evidencia), 2026-11-20
- T1: RED `DisponibilidadCargaWebMvcTest` (404 sin ruta) → GREEN. La ruta nueva se agregó también a las listas cerradas `RutasDocumentadasTest`, `InventarioRutasTest` y a la tabla de `docs/FAMILIAS-DEPORTISTAS.md` (lecturas; la única escritura familiar sigue siendo el POST de carga). Suite normal previa al ajuste: 1318 pruebas, 1 fallo (`InventarioRutasTest`, lista cerrada) → corregido.
- T2: RED/GREEN en `http.test.ts` (cabeceras extra sin pisar CSRF; sin opciones = comportamiento previo), `upload-outcome.test.ts` (17), `image-normalization.test.ts` (11), `documents-section.test.tsx` (12).
- Hallazgo y corrección: tras un 409 la lista se refresca y el aviso desaparecía junto con el área de carga → el aviso se muestra aunque ya exista el documento.
- Cuatro pruebas existentes de la ficha (`athlete-sheet-ui.test.tsx`) codificaban la ficha previa (una sola solicitud; ninguna aparición de la palabra «DNI»; dos regiones). No son decisiones de producto de FE-04 (D3/D4/D8/D9/D10); se ajustaron conservando su intención: una solicitud de ficha por carga, solo se suman los metadatos documentales y la disponibilidad, nunca el detalle de 15 campos ni temporadas; los datos del deportista siguen sin DNI/CUIL/domicilio (la sección documental solo nombra los tipos).
- Hallazgo y corrección: la prueba de aislamiento por cambio de identidad detectó que las consultas documentales en vuelo se reconstruían con la clave de la identidad anterior (render tardío de la pantalla saliente). Corrección local: `gcTime: 0` en ambas consultas documentales (sin tocar el código de sesión compartido).

### L5 — T3 implementada (evidencia), 2026-11-20
- Escritor acotado: dobles externos `e2e/support/fakes/{storage-fake,clamd-fake,eicar}.ts` (solo 127.0.0.1; EICAR armado en tiempo de ejecución), `e2e/support/jpeg.ts` + `jpeg.test.ts` (5 pruebas, RED observado), `documentos-dni.real.spec.ts` (shard 2), `frontend-e2e.sh` (carga habilitada solo en CI contra los dobles, credencial sintética por ejecución y enmascarada), sección nueva en `docs/DOCUMENTOS.md`. `application*.yml` y código backend sin cambios.
- Desviaciones del escritor: la respuesta perdida se corta con CDP (`Fetch` en etapa de respuesta) porque Chromium no expone a Playwright el cuerpo multipart de un Blob; la igualdad de clave y bytes se prueba con un script de inicio que registra clave, nombre, tipo, tamaño y SHA-256 de lo enviado; EICAR en PDF termina en `422 ARCHIVO_PELIGROSO` (el doble lo detecta en cualquier parte del flujo, a diferencia de ClamAV real).
- Padre: `portal-ficha.real.spec.ts:212` esperaba dos encabezados nivel 2 (efecto de T2); ajustado a tres con la misma intención. Entrada nueva en `frontend/README.md`.
- Resultados: `npm test` 60 archivos / 1451 pruebas; typecheck, lint y build en verde; E2E shard 1: 112 passed; shard 2: 103 passed (incluido el spec nuevo).
- Navegadores efectivamente probados: Chromium (Playwright). Pendiente en dispositivos reales: iOS Safari (HEIC, cámara) y Android (Ultra HDR/Motion Photo reales).

### L6 — Verificación de cierre, 2026-11-20
- Backend: `./mvnw -o clean verify` 1318/0/0/0; `-Pdb-tests verify` (PostgreSQL 17) 700/0/0/0.
- Frontend: typecheck, lint y build en verde; `npm test` 60 archivos / 1451 pruebas.
- E2E: shard 1 112 passed; shard 2 103 passed.
- Verificador independiente (Sonnet 5.5 / high): sin bloqueantes; S1, S4–S12 CUMPLE; S2 y S3 CUMPLE en código y NO VERIFICABLE en dispositivos reales. Prueba diferencial de `apiWrite` sin opciones: configuración idéntica a la base. Advertencias: la orientación EXIF no se prueba de punta a punta con una foto rotada; el doble de clamd detecta EICAR en cualquier parte del flujo (documentado).
- Commits locales: `9479c77` (T1), `ea4ccb0` (T2) y el tercero (T3 + este documento). Sin push, PR ni merge.

### L7 — Revisión nativa y cierre local, 2026-11-20
- Revisión nativa (Gentle AI, lentes `review-risk`, `review-resilience`, `review-reliability`) sobre el rango `e75cbc6..33ef0b2`: **aprobada y reconocida** (`gentle-ai.review-acknowledged/v1`, lineage `review-4765d3bc5f81d5aa`, objetivo `sha256:a8b01bc9…89b6b`). Riesgo evaluado: alto (`process_boundary` y `shell_source` en `.github/scripts/frontend-e2e.sh`). Consentimiento otorgado por el usuario para este candidato. Sin bloqueantes: ninguna corrección abierta.
- Hallazgos no bloqueantes (trabajo aparte; ninguno reabre la revisión): `R4-409-in-progress-drops-key` (resiliencia, WARNING), `R3-e2e-maxbytes-coupling` (confiabilidad, WARNING), `R3-node-ts-runtime` (confiabilidad, WARNING), `R3-exif-orientation-unproved` (confiabilidad, SUGGESTION). Clasificación previa al merge / posterior en la descripción del PR.
- Autorización del usuario: push de la rama y PR hacia `main`; **merge no autorizado**. Este registro se agrega en un commit posterior de solo documentación: el código revisado no cambia.
