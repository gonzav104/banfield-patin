# FE-12 / U20 — Checklist de requisitos documentales (parcial)
Rama `feat/fe12-u20-checklist-documental` (desde `origin/main` 84acdd0). Tres cortes (un commit por corte); sin push/PR/merge/despliegue; sin U21 ni U22.
Runner: Maven (`./mvnw`, `-Pdb-tests`), Vitest, Playwright real vía `.github/scripts/frontend-e2e.sh`.

## Specs
- S1: «U20 evaluará inicialmente DNI_DEPORTISTA_FRENTE y DNI_DEPORTISTA_DORSO.»
- S2: «Solo un documento vigente y APROBADO cumple el requisito.» «CARGADO significa EN_REVISION; OBSERVADO conserva su motivo; ausencia de documento significa FALTA.»
- S3: «No declarar LISTO_PARA_PRESENTAR ni EMPADRONADO.» «No calcular un porcentaje de cumplimiento global del empadronamiento.» «Distinguir claramente que el checklist es parcial.»
- S4: «La documentación federativa disponible corresponde a 2026; nuestro objetivo operativo es 2027. No convertir requisitos de 2026 en reglas permanentes ni afirmar que los de 2027 están confirmados.» «La edad deportiva y la condición de menor en el formulario son conceptos diferentes.» «No inferir responsables legales utilizando solamente tutor.parentesco o pertenencia a una familia.»
- S5 (C1 backend): catálogo mínimo de requisitos y cálculo de estados; lectura desde documentos vigentes; autorización familiar y aislamiento por escuela; pruebas de estados, documentos históricos, alcance y resultados parciales.
- S6 (C2 frontend familiar): checklist en la ficha del deportista; indicador de documentación pendiente en el dashboard reutilizando los datos disponibles; navegación a las acciones documentales existentes.
- S7 (C3 frontend admin): mismo cálculo en la ficha administrativa; indicador en el listado solo si no requiere N+1 ni ampliación significativa; no duplicar la bandeja de pendientes.
- S8: autorizados implementación, pruebas, revisiones y commits locales; no push, PR, merge, despliegue ni habilitar la carga en producción.

## Tasks
- [x] T1 · S1-S5 · inline · C1 backend · commit C1 (ver L2)
- [x] T2 · S6 · inline · C2 frontend familiar · commit C2 (ver L3)
- [ ] T3 · S7 · inline · C3 frontend administrativo

## Log
### L1
Pedido del usuario: ver `S1`–`S8` (citas literales). Decisiones conocidas para futuras unidades: el empadronamiento ante la Federación se presenta por patinador; el padrón incluye alumnos que no compiten; para menores puede existir un único responsable válido; el vínculo explícito tutor-deportista se resuelve antes de evaluar documentos de responsables; ficha médica anual = U21; solicitud de licencia, preparación y confirmación = U22.

### L2 — C1 backend
`CatalogoRequisitosDocumentales` (catálogo mínimo + cálculo puro), `ChecklistDocumentalService` (alcance como la consulta de documentos, 404 uniforme, solo lectura, sin auditoría), `ChecklistDocumentalController` (`GET /api/{admin|familia}/deportistas/{id}/checklist-documental`) y dos consultas por tipo en `DocumentoRepository`. Decisiones: respuesta `{deportistaId, parcial: true, alcance: "DNI_DEPORTISTA", requisitos[{tipo, estado, motivoObservacion}]}`; `EstadoRequisito` = CUMPLIDO | EN_REVISION | OBSERVADO | FALTA; `PENDIENTE`/`VENCIDO` (que ningún flujo produce para un DNI) se informan como FALTA; sin migración. Pruebas: `CatalogoRequisitosDocumentalesTest` 10, `ChecklistDocumentalDbTest` 13, `ChecklistDocumentalWebMvcTest` 4; guardas de rutas/OpenAPI/docs actualizadas deliberadamente; 6 mutaciones del cálculo y del alcance del servicio, todas detectadas; el verificador independiente encontró una séptima que sobrevivía (la cláusula de alcance de `requisitosFamilia`, redundante con el control previo del servicio) y se agregó una prueba directa del repositorio. Suite normal verde.

### L3 — C2 frontend familiar
`features/documentos-checklist` (esquema zod estricto, mensajes, `DocumentChecklistView` compartida), `FamilyDocumentChecklist` en la ficha, `DniSummaryLine` en las tarjetas del panel (solo las 3 de la vista previa: sin N+1 sobre el listado completo), `familyKeys.checklist`, invalidación tras carga/reemplazo/refresco, ancla `documentos-deportista` para «Ir a cargar los documentos». El doble de backend (`fakeBackend`) calcula los cuatro estados desde los documentos. Pruebas: 9 en `document-checklist.test.tsx` + 12 en `documentos-checklist/*.test.ts`; se actualizaron a propósito 4 pruebas existentes cuyas expectativas enumeraban las peticiones o secciones de la ficha y del panel (ahora incluyen el checklist y las 3 consultas del panel). Dos mutaciones detectadas (invalidación; prioridad del resumen, esta última tras agregar la prueba unitaria que la cubre).
