# Vinculación explícita tutor–deportista
Base: `57cf7a9`; rama: `feat/vinculos-tutor-deportista`; entrega local sin publicación.
Estrategia: `stacked-to-main` sin rama de integración permanente (pronóstico 1.500–2.400 líneas propias, excluidos generados); runners: Maven, PostgreSQL Testcontainers y Playwright/Vitest.

## Specs

- S1 — «Cada vínculo tutor–deportista tiene su propia vigencia e historial.» «Cerrar una vigencia es irreversible. Una reactivación posterior de los registros padre no la reabre.» «Ningún vínculo se crea automáticamente a partir de los datos existentes.» «Incorporar FKs compuestas, integridad de escuela y familia, invariantes de vigencia y unicidad parcial.»
- S2 — «Implementar consultas ADMIN autorizadas.» «Probar instalación limpia y actualización desde V13 mediante Flyway y Hibernate.» «Ejecutar pruebas PostgreSQL reales de restricciones, aislamiento e historial.» «Antes de continuar a C2, realizar una verificación independiente de C1 y corregir los hallazgos bloqueantes.»
- S3 — «Desactivar un tutor, una familia o un deportista cierra todas las vigencias correspondientes en la misma transacción.» «Revocar familia_deportista también cierra las vigencias afectadas.» «Preservar las semánticas existentes de familia_deportista, es_principal y versionVinculos.»
- S4 — «ADMIN debe confirmar una nueva alta para restablecer una responsabilidad.» «Un deportista puede tener uno o varios responsables, sin exigir obligatoriamente madre y padre.» «El tipo de relación es un dato declarado, no una acreditación legal ni una autorización de acceso.» «Crear un vínculo debe verificar bajo bloqueo las revisiones observadas de familia, deportista, tutor y versionVinculos.» «Todas las escrituras deben conservar aislamiento por escuela, CSRF, revisiones optimistas, bloqueos de orden consistente, auditoría e idempotencia cuando corresponda.»
- S5 — «No considerar suficiente el experimento SQL aislado: repetir las comprobaciones pertinentes mediante los servicios reales.» Los casos exactos están en L1.
- S6 — «ADMIN debe poder consultar y gestionar responsables desde la ficha del deportista.» «La familia solamente podrá consultar los responsables de su propia familia, sin acceder al historial o a los datos de otras familias vinculadas al mismo deportista.» «Mantener 404 uniforme y evitar filtraciones de datos personales.» «Los nuevos vínculos no conceden automáticamente permisos de acceso a deportistas o documentos.» «No cambiar todavía el checklist U20 ni los permisos documentales.»
- S7 — «Documentar expresamente que V14 debe instalarse antes del backend nuevo y que no podrán habilitarse altas mientras existan instancias antiguas capaces de realizar escrituras incompatibles.» «No habilitar un rollback inseguro si ya existen vigencias abiertas.» «Mantener DOCUMENTOS_CARGA_HABILITADA y DOCUMENTOS_STORAGE_HABILITADO en false por defecto.» «No comenzar U21 ni U22.»
- S8 — «Aplicar las revisiones independientes y el procedimiento Gentle AI correspondiente.» «Mantener los cambios divididos en commits locales coherentes, sin squash.» «La autorización es exclusivamente para implementación, pruebas, revisiones y commits locales.» «No autorizo push, PR, merge ni despliegue.»

- S9 — «Diseñar e implementar la separación de roles Flyway/runtime para el futuro despliegue.» «Reproducir esa separación en PostgreSQL 17 y CI, sin modificar los permisos de Supabase real.» «Demostrar que el rol del backend puede ejecutar sus operaciones legítimas, pero no alterar las marcas privadas, sus triggers ni asumir la identidad propietaria.»
- S10 — «Implementar la alternativa validada de marcas privadas en V14, siempre que no aparezca evidencia de que se aplicó en otro entorno persistente.» «Comprobar que se recupera el comportamiento de concurrencia anterior a V14.» «Conservar el ajuste estricto de InventarioRutasTest.» «Ejecutar las suites completas de backend y PostgreSQL, más las pruebas específicas de integridad y concurrencia.» «Completar revisión independiente y Gentle AI.»

- S11 — «Crear la siguiente migración disponible, previsiblemente V15. No modificar V14.» «Implementar funciones SECURITY DEFINER de alcance mínimo para los cierres permitidos.» «Restringir su ejecución al rol correspondiente y revocar los permisos de PUBLIC.» «Utilizar propietarios adecuados, nombres de objetos calificados y search_path seguro.» «No usar SQL dinámico innecesario.» «Comprobar que el runtime no pueda modificar directamente tutor_deportista, alterar los triggers ni manipular las marcas privadas.» «Verificar que ninguna función permita cerrar vínculos de otra escuela mediante identificadores arbitrarios.» «Si no existe un contexto de autorización de escuela verificable dentro de PostgreSQL, documentar expresamente el límite de la garantía y asegurar el control de autorización del servicio Java.»
- S12 — «Integrar el cierre, la operación sobre el registro padre y la auditoría en una misma transacción, respetando el orden de bloqueos existente.» «Desactivar tutor: cerrar sus vigencias abiertas.» «Desactivar familia: cerrar las vigencias correspondientes.» «Desactivar deportista: cerrar sus vigencias.» «Revocar familia_deportista: cerrar únicamente las vigencias de esa relación.» «Reactivar registros padre: nunca reabrir vigencias anteriores.» «Repetir una operación ya aplicada: conservar la semántica de idempotencia y evitar auditorías duplicadas.» «Mantener las revisiones optimistas, el manejo de conflictos y los contratos existentes.»
- S13 — «Probar con PostgreSQL 17 real» los casos de L12. «Realizar revisión independiente y aplicar el procedimiento nativo de Gentle AI.» «Crear commits locales pequeños y coherentes después de superar sus verificaciones y revisiones.» «No autorizo push, PR, merge, despliegue ni cambios en Supabase.» «No comenzar C2-B, C3, U21 ni U22.»

- S14 — «Incorporar una protección transaccional que rechace la secuencia baja → cierre de vigencias → reactivación del mismo padre dentro de una única transacción.» «Aplicar la protección a tutor, familia, deportista y familia_deportista, según las operaciones de cierre previstas en C2-A.» «La protección no debe poder eludirse mediante SET CONSTRAINTS ALL IMMEDIATE, SET CONSTRAINTS ALL DEFERRED ni invocaciones SQL directas autorizadas.» «Mantener permitida la reactivación en una transacción posterior, sin reabrir vigencias históricas.» «Conservar el comportamiento idempotente de operaciones repetidas que no producen cambios efectivos.» «Mantener el cierre, el cambio de estado del padre y la auditoría como una operación atómica.» «No conceder al runtime UPDATE general sobre tutor_deportista ni acceso directo a las marcas privadas.» «No modificar V14 ni ampliar el alcance de las funciones SECURITY DEFINER.» Casos y revisión nueva: L14.

- S15 — «Implementar funciones SECURITY DEFINER específicas para el alta, edición del tipo declarado y revocación individual.» «Mantener banfield_runtime sin permisos directos de INSERT, UPDATE o DELETE sobre tutor_deportista.» «Utilizar Idempotency-Key como contrato HTTP del alta, con persistencia y huella calculada por el servidor.» «Las altas deben quedar deshabilitadas por defecto.» «No presentar las comprobaciones SQL como una garantía de identidad independiente cuando el backend utiliza un login PostgreSQL compartido.»

## Tasks

- [ ] C1 · S1–S2, S7–S8 · implementación inline; verificación independiente sin bloqueantes · riesgo 1/2 (invariantes e aislamiento) · C1-A `5ac5824` revisado; C1-B contiene consultas y contrato, su comprobación nativa se registra en el siguiente corte.
- [ ] C2 · S3–S5, S7–S8 · bloqueado por C1; riesgo 2/4 (ADMIN y carreras entre escritores) · commit(s) pendiente(s).
- [ ] C3 · S6–S8 · bloqueado por C2; riesgo 2 (privacidad y roles) · commit(s) pendiente(s).

- [x] C1-D · S10 · inventario estricto · inline · RED (GET no inventariado), GREEN 28/28 · `f94fd82` (Gentle AI: `under_budget`).
- [ ] C1-E · S1–S2, S7–S10 · marcas privadas y separación Flyway/runtime · inline, con verificación independiente · riesgos 1/2/4 · implementación y focos verificados; suite PG integral y RDD pendientes.

- [x] C2-A1 · S11, S13–S14 · V15, permisos operativos y pruebas PostgreSQL de la interfaz SQL · inline con verificación independiente · riesgo 1/2/4 · `6418186`, revisión nativa aprobada y reconocida.
- [x] C2-A2 · S3, S5, S12–S13 · integración de los cierres familia/deportista/revocación, auditoría y concurrencia · inline con verificación independiente · riesgo 1/2/4 · `c030920`, aprobado y reconocido.
- [x] C2-A3 · S3, S5, S12–S13 · comandos ADMIN de estado de tutor y pruebas · inline con verificación independiente · riesgo 1/2/4.

- [ ] C2-B1 · S4–S5, S15 · V16: funciones SECURITY DEFINER de alta (idempotente), tipo y revocación individual; autorización de runtime; pruebas PostgreSQL · inline, riesgos 1/2/4 · commit pendiente.
- [ ] C2-B2 · S4–S5, S15 · puerto Java y servicio de alta (revisiones bajo bloqueo, Idempotency-Key, bandera) · inline, riesgo 4 · commit pendiente.
- [ ] C2-B3 · S4–S5, S15 · edición de tipo y revocación individual · inline, riesgo 4 · commit pendiente.
- [ ] C2-B4 · S4–S5, S15 · controlador, DTOs, OpenAPI e inventarios estrictos · inline, riesgo 3 · commit pendiente.
- [ ] C2-B5 · S15 · documentación, ODD y confirmación posterior de C2-A · inline · commit pendiente.

## Log

### L1 — Solicitud original

Autorizo implementar localmente la nueva unidad de **vinculación explícita tutor–deportista**, en tres cortes C1–C3.

Modelo recomendado: GPT-6.2-Sol.
Reasoning effort: High para C1 y C2; Medium para C3.

### Base

Trabajar en un worktree aislado desde `origin/main`, cuyo commit esperado es `57cf7a9`.

Verificar nuevamente la base antes de comenzar. No modificar el checkout principal ni sus cambios ajenos.

### Decisiones funcionales aprobadas

1. Cada vínculo tutor–deportista tiene su propia vigencia e historial.
2. Desactivar un tutor, una familia o un deportista cierra todas las vigencias correspondientes en la misma transacción.
3. Revocar familia_deportista también cierra las vigencias afectadas.
4. Cerrar una vigencia es irreversible. Una reactivación posterior de los registros padre no la reabre.
5. ADMIN debe confirmar una nueva alta para restablecer una responsabilidad.
6. Ningún vínculo se crea automáticamente a partir de los datos existentes.
7. Un deportista puede tener uno o varios responsables, sin exigir obligatoriamente madre y padre.
8. El tipo de relación es un dato declarado, no una acreditación legal ni una autorización de acceso.
9. Crear un vínculo debe verificar bajo bloqueo las revisiones observadas de familia, deportista, tutor y versionVinculos.
10. Los nuevos vínculos no conceden automáticamente permisos de acceso a deportistas o documentos.

### C1 — Migración y lectura

- Implementar V14 de forma aditiva, sin backfill.
- Incorporar FKs compuestas, integridad de escuela y familia, invariantes de vigencia y unicidad parcial.
- Conservar historial y evitar reaperturas.
- Implementar consultas ADMIN autorizadas.
- Probar instalación limpia y actualización desde V13 mediante Flyway y Hibernate.
- Ejecutar pruebas PostgreSQL reales de restricciones, aislamiento e historial.

Antes de continuar a C2, realizar una verificación independiente de C1 y corregir los hallazgos bloqueantes.

### C2 — Escrituras y cierres transaccionales

Implementar:
- Alta individual de responsable.
- Edición de tipo declarado.
- Revocación.
- Comandos ADMIN explícitos de desactivación y reactivación de tutores.
- Cierre automático de vigencias al desactivar familia o deportista.
- Cierre automático de vigencias al revocar familia_deportista.

Preservar las semánticas existentes de familia_deportista, es_principal y versionVinculos.

Todas las escrituras deben conservar aislamiento por escuela, CSRF, revisiones optimistas, bloqueos de orden consistente, auditoría e idempotencia cuando corresponda.

Probar con transacciones reales:
- Alta concurrente con desactivación de tutor.
- Alta concurrente con desactivación de familia.
- Alta concurrente con desactivación de deportista.
- Alta concurrente con revocación familiar.
- Altas duplicadas.
- Reactivación posterior y repetición tardía de solicitudes.
- Rollback, timeout e interbloqueos.
- Compatibilidad con los bloqueos de carga documental existentes.

No considerar suficiente el experimento SQL aislado: repetir las comprobaciones pertinentes mediante los servicios reales.

### C3 — Interfaz

ADMIN debe poder consultar y gestionar responsables desde la ficha del deportista.

La familia solamente podrá consultar los responsables de su propia familia, sin acceder al historial o a los datos de otras familias vinculadas al mismo deportista.

Mantener 404 uniforme y evitar filtraciones de datos personales.

No cambiar todavía el checklist U20 ni los permisos documentales.

### Verificaciones por corte

Aplicar las revisiones independientes y el procedimiento Gentle AI correspondiente.

Mantener los cambios divididos en commits locales coherentes, sin squash.

Si aparece un problema de integridad, seguridad o concurrencia que requiera ampliar significativamente el alcance, detenerse e informarlo antes de continuar.

### Publicación y despliegue

La autorización es exclusivamente para implementación, pruebas, revisiones y commits locales.

No autorizo push, PR, merge ni despliegue.

No habilitar altas de responsables en producción.

Documentar expresamente que V14 debe instalarse antes del backend nuevo y que no podrán habilitarse altas mientras existan instancias antiguas capaces de realizar escrituras incompatibles.

No habilitar un rollback inseguro si ya existen vigencias abiertas.

Mantener DOCUMENTOS_CARGA_HABILITADA y DOCUMENTOS_STORAGE_HABILITADO en false por defecto.

No comenzar U21 ni U22.

### Entrega

Al finalizar, informar:
- Commits locales.
- Archivos y migraciones.
- Pruebas backend, PostgreSQL, frontend y E2E.
- Resultado de las revisiones independientes y Gentle AI.
- Riesgos pendientes y limitaciones de compatibilidad.
- Recomendación de publicación del PR.

Responder en español.

### L2 — Preparación

- `HEAD` del worktree, referencia local `origin/main` y SHA esperado: `57cf7a956eeb69673f8dad7b39abb9e35c30fe40`. El checkout principal conserva su estado ajeno.
- V14 y el orden de bloqueos se validaron previamente en PostgreSQL 17.11; recuperar los hallazgos de `architecture/vinculacion-explicita-tutor-deportista/v14-validacion-postgresql`.
- RDD activo por defecto, sin cambio de configuración. No se inicia C2 sin la verificación independiente de C1 y su corrección cuando corresponda.

### L3 — C1 en verificación

- RED: el destino de Flyway 14 no existía (6 errores esperados) y el GET ADMIN devolvía 404 en lugar de 200 con la sesión autorizada.
- GREEN: PostgreSQL/Flyway/Hibernate 7 pruebas; WebMvc/seguridad/OpenAPI 12 pruebas. `git diff --check` pasó. C1 todavía sin commits; verificación independiente y revisión nativa pendientes.
- Estimación observada de C1: aproximadamente 620 líneas excluyendo este log; el esquema y la lectura pueden ser dos unidades coherentes dentro de C1. No se autoriza publicar.

### L4 — Verificación independiente de C1

- Reprodujo 7/7 PostgreSQL/Flyway/Hibernate y 12/12 WebMvc/OpenAPI en copia aislada. Sonda adicional 1/1: V14 conserva el hash de `familia_deportista` y las operaciones rechazadas no cambian el hash de vigencias. Sin bloqueantes; no hubo corrección.
- Comprobación del orquestador: `git diff --check` correcto, `HEAD` sigue en `57cf7a9` y el estado del checkout principal conserva el hash inicial.
- Antes de los commits locales se debe escoger la estrategia de cadena (el pronóstico supera 400 líneas). Es una decisión de división futura, no autorización de push, PR, merge o despliegue. Luego: commits coherentes dentro de C1 y revisión nativa aplicable; C2 y C3 todavía no iniciados.

### L5 — Estrategia de integración y cierre local

> Elijo la estrategia de integración progresiva hacia main, sin rama de integración permanente.
>
> Crear dos commits coherentes:
> - C1-A: migración V14, invariantes e integridad.
> - C1-B: consultas ADMIN, contratos y pruebas correspondientes.
>
> C1 se publicará posteriormente como PR independiente hacia main, solo cuando lo autorice.
>
> C2 deberá construirse sobre C1, sin duplicar commits ni reescribir su historial. C3 dependerá de C2.

- `stacked-to-main`: C1 → C2 → C3. C1 es un PR futuro con C1-A y C1-B. Ninguna publicación está autorizada.
- Repetir verificaciones de C1 y aplicar RDD por unidad. «Si requiere consentimiento explícito adicional, detenerse y solicitarlo.» No iniciar C2 hasta cerrar C1.

### L6 — Verificaciones y commit de esquema

- PostgreSQL/Flyway/Hibernate: 7/7. WebMvc/OpenAPI: 12/12. No se tocó el checkout principal.
- C1-A: `5ac582491d395e8b797f6e82af6c37c97a51a320`, directo de `57cf7a9`; 338 líneas, V14, mapeo y pruebas. Gentle AI: aprobado y reconocimiento exacto confirmado. Sin correcciones bloqueantes.
- Avisos informativos R1-001 y R3-001: agregar sondas que aíslen las FKs del actor de alta y de cierre al verificar las escrituras de C2; las FKs existen, pero una prueba con varios campos inválidos no prueba cada una por separado.


### L7 — Corrección local del PR #52

> Confirmación del proyecto: Banfield todavía NO tiene el backend desplegado en ningún servidor.
>
> La instancia Supabase identificada registra únicamente V1–V4. No hay V14 aplicada allí.
>
> Con esta información, continuá la corrección local de C1 del PR #52.
>
> Objetivos:
>
> 1. Diseñar e implementar la separación de roles Flyway/runtime para el futuro despliegue.
> 2. Reproducir esa separación en PostgreSQL 17 y CI, sin modificar los permisos de Supabase real.
> 3. Implementar la alternativa validada de marcas privadas en V14, siempre que no aparezca evidencia de que se aplicó en otro entorno persistente.
> 4. Demostrar que el rol del backend puede ejecutar sus operaciones legítimas, pero no alterar las marcas privadas, sus triggers ni asumir la identidad propietaria.
> 5. Comprobar que se recupera el comportamiento de concurrencia anterior a V14.
> 6. Conservar el ajuste estricto de InventarioRutasTest.
> 7. Ejecutar las suites completas de backend y PostgreSQL, más las pruebas específicas de integridad y concurrencia.
> 8. Completar revisión independiente y Gentle AI.
> 9. Preparar commits correctivos locales sin reescribir los tres commits de C1 ya publicados.
>
> No acceder ni modificar la base Supabase real. No publicar cambios en el PR #52 hasta nueva autorización.
>
> Preservar el checkout principal y los cambios pendientes de C2-A.
>
> Si la implementación necesita alterar de manera importante la arquitectura de permisos o no puede garantizar las invariantes, detenerse e informar el bloqueo.
>
> Al finalizar, presentar los resultados de pruebas, revisiones, commits locales y riesgos pendientes.

- Rama correctiva local sobre `ef717f0`: `fix/c1-integridad-concurrencia`. Se mantiene C1 como un único PR independiente; dos commits correctivos, sin reescritura ni publicación. Pronóstico: 500–750 líneas propias (incluye pruebas concurrentes y procedimiento de roles, no se recortan pruebas por tamaño).
- RED comprobado en PostgreSQL 17: las claves únicas de estado en los cuatro padres hacen que un UPDATE de estado espere el bloqueo `KEY SHARE` previo; el mismo escenario de servicio no espera en `57cf7a9`. El prototipo de marcas descartable es evidencia de diseño, no la verificación de este candidato.

### L8 — Correctivo local verificado en foco; suite integral pendiente

- V14: se trasladaron cuatro claves de estado a marcas privadas sin backfill. Se preservaron las FKs permanentes y de estado inmediatas, la unicidad parcial y la guarda de historial. Con `tutor_deportista` vacía, los cuatro cambios de estado conservan compatibilidad con `KEY SHARE`; el escenario de servicio que fallaba volvió a pasar. Roles de producción: propietario sin login, sesión Flyway separada y runtime sin DML en `tutor_deportista`, sin membresía ni acceso privado. `prod` conserva validación Hibernate y no inicia Flyway.
- Revisión independiente: integridad y concurrencia pasaron (73 pruebas focales sobre la primera versión). Detectó que el lock de activación de temporadas no funciona con solo SELECT en escuela y que una ACL de columna a PUBLIC escapaba al chequeo de tabla en Flyway. Se concedió UPDATE exclusivamente en `escuela.actualizado_en` y se incorporó el chequeo efectivo de ACLs por columna. Recheck independiente 3/3 con el servicio real y rechazo de alteración del estado de escuela y del historial Flyway.
- Suite backend normal: 1.450/1.450. PostgreSQL 17.11 completo: primera corrida 881 pruebas, 0 fallos de aserción y 2 errores de conexión V5 antes de ejecutar sus escenarios. Una corrida final con loopback IPv4 explícito ejecutó 882 pruebas (0 fallos de aserción, 3 errores de conexión V5, sin omisiones). Pasaron en esa corrida `V14MarcasPrivadasDbTest` 68/68, `V14VigenciasDbTest` 6/6 y `VinculosConcurrenciaDbTest` 21/21. No se aumentaron esperas, cambiaron V5 ni excluyeron pruebas.
- Corte C1-E sin commit y sin aprobación Gentle AI: falta una corrida PostgreSQL 17 completa verde en un runner Linux con Docker local y publicación no autorizada. Ajuste de rutas C1-D `f94fd82`, `under_budget`. No se contactó Supabase, no se modificó main/C2-A ni el PR #52.


### L9 — Evidencia recuperada: C1 cerrado; inicio de C2

- C1-B `680eb0cea725d9e6f5f2c62d2f81ff834241cd7a` sobre C1-A. Gentle AI aprobado y reconocido, sin bloqueantes.
- Avisos C1-B: distinguir en el nombre de la prueba el mapeo HTTP de la comparación de alcance en DB y cubrir una fila real en el contrato JSON (revisión string, campos de cierre y sin datos sensibles). No reabren C1.
- C2 conservará la barrera de alta apagada hasta que todos los escritores cierren antes de cambiar estado. Primero se comprueban los cierres de servicios existentes con una vigencia sintética en PostgreSQL.

### L10 — Evidencia recuperada: C2-A: cierres en servicios existentes

- RED: 3/3 servicios escribían el padre antes de cerrar y V14 rechazaba el cambio por las FKs de estado. GREEN: 28/28 pruebas PostgreSQL (cierres y regresión de vínculos y familia).
- El bloqueo exclusivo se toma solo para una baja efectiva después de validar la revisión, antes de tocar las vigencias. Los no-op conservan la comprobación optimista al commit; bloquearlos rompía una regresión de activación/desactivación solapada. La sonda de espera de familia observa tanto el UPDATE original como el bloqueo explícito nuevo.
- C2-A no agrega operaciones públicas ni habilita altas. Quedan comandos de tutor y escrituras explícitas, verificación completa de carreras y documentación del contrato de C2.

### L11 — Evidencia recuperada: verificación independiente y compatibilidad de C1

- C2-A: el verificador reprodujo tres `500 ERROR_INTERNO` por bloqueos reales. La corrección local traduce excepciones JPA de bloqueo y SQLSTATE JDBC 55P03/40P01 a la excepción de concurrencia existente, sin capturar otros fallos SQL.
- Regresión fijada en `CierreVigenciasConcurrenciaDbTest`: 6/6, incluidas tres esperas reales que devuelven `409 CONFLICTO_CONCURRENCIA` sin cambios en datos ni auditoría. Revalidación independiente 6/6. Suites del candidato: 34/34 PostgreSQL y 115/115 unitarias/WebMvc.
- Funcionalidad C1: el inventario estricto de `V2MigracionEstaticaTest` aún espera V1–V13 y rechaza V14. Los dos commits C1 ya reconocidos no se reescriben. No se confirma C2-A ni se publica C1 hasta resolver si se agrega un commit correctivo independiente de C1 para preservar su PR futuro autónomo.

### L12 — 2026-11-24 · Aprobación de C2-A

Apruebo el cambio de diseño de C2-A: implementar una migración posterior a V14 con funciones de cierre de vigencias de alcance limitado.

Continuá trabajando en el worktree persistente:

~/worktrees/banfield-c2a

Rama: feat/vinculos-tutor-deportista-c2a

Base: main, merge commit 159c738a326662efcda4e4b6ce572ee880f410d5.

### Objetivo

Completar C2-A sin conceder al rol banfield_runtime permisos generales de escritura sobre tutor_deportista ni sobre las marcas privadas.

### Implementación autorizada

1. Crear la siguiente migración disponible, previsiblemente V15. No modificar V14.
2. Implementar funciones SECURITY DEFINER de alcance mínimo para los cierres permitidos.
3. Restringir su ejecución al rol correspondiente y revocar los permisos de PUBLIC.
4. Utilizar propietarios adecuados, nombres de objetos calificados y search_path seguro.
5. No usar SQL dinámico innecesario.
6. Comprobar que el runtime no pueda modificar directamente tutor_deportista, alterar los triggers ni manipular las marcas privadas.
7. Verificar que ninguna función permita cerrar vínculos de otra escuela mediante identificadores arbitrarios.
8. Si no existe un contexto de autorización de escuela verificable dentro de PostgreSQL, documentar expresamente el límite de la garantía y asegurar el control de autorización del servicio Java.
9. Integrar el cierre, la operación sobre el registro padre y la auditoría en una misma transacción, respetando el orden de bloqueos existente.

### Reglas funcionales

- Desactivar tutor: cerrar sus vigencias abiertas.
- Desactivar familia: cerrar las vigencias correspondientes.
- Desactivar deportista: cerrar sus vigencias.
- Revocar familia_deportista: cerrar únicamente las vigencias de esa relación.
- Reactivar registros padre: nunca reabrir vigencias anteriores.
- Repetir una operación ya aplicada: conservar la semántica de idempotencia y evitar auditorías duplicadas.
- Mantener las revisiones optimistas, el manejo de conflictos y los contratos existentes.

### Validación

Probar con PostgreSQL 17 real:

- Roles migrador/runtime separados.
- Intentos de escritura directa denegados.
- Invocaciones fuera de alcance y entre escuelas.
- Concurrencia alta/baja y baja/reactivación.
- Orden de bloqueos y ausencia de nuevos interbloqueos.
- Rollback completo, incluida auditoría.
- Operaciones existentes y carga documental.
- Suites backend y PostgreSQL pertinentes.

Realizar revisión independiente y aplicar el procedimiento nativo de Gentle AI.

### Protección del trabajo

Trabajar exclusivamente en el worktree persistente.

Crear commits locales pequeños y coherentes después de superar sus verificaciones y revisiones. No volver a depender de un directorio temporal como única copia del trabajo.

Podés continuar autónomamente dentro del alcance aprobado y corregir los problemas detectados por las pruebas. Detenete si aparece un cambio sustancial de arquitectura, una vulnerabilidad sin solución clara o un conflicto con las reglas aprobadas.

### Restricciones

Autorizo implementación local, pruebas, revisiones y commits locales de C2-A.

No autorizo push, PR, merge, despliegue ni cambios en Supabase.

No comenzar C2-B, C3, U21 ni U22.

### Entrega

Presentar:
- Migración implementada.
- Funciones y permisos.
- Cambios en servicios Java.
- Resultados de pruebas y revisiones.
- Commits locales.
- Riesgos pendientes.
- Estado de preparación de C2-A para un PR independiente.

Responder en español.


### L13 — Evidencia de C2-A1 (sin commit)

- RED: 4 casos PostgreSQL fallaron por ausencia de la función de cierre.
- GREEN: regresión V14/V15 inicial 80/80 (PostgreSQL 17.11); candidato con carreras V15 13/13; inventario estricto 7/7. V14 intacta.
- Verificación independiente: 13/13 PostgreSQL y 7/7 inventario; 14/14 sondas adicionales de actualización desde V14 poblada, exclusión entre escuelas y rollback, sin bloqueantes. Estos resultados todavía no verifican auditoría ni carreras por los servicios Java.
- Evaluación Gentle AI: riesgo alto (V15, SECURITY DEFINER y permisos de runtime); revisión nativa pendiente.


### L14 — Aprobación de la protección de reactivación

La validación nativa anterior (R1-001, `review-e7a60f84e839d53f`) terminó `escalated`, sin aprobación. Esta aprobación de alcance habilita un candidato distinto, no una repetición de aquel linaje.

Apruebo extender V15 para impedir la reactivación de un registro padre dentro de la misma transacción en la que su baja cerró vigencias tutor–deportista.

Continuar en el worktree persistente:
~/worktrees/banfield-c2a

### Alcance aprobado

1. Incorporar una protección transaccional que rechace la secuencia baja → cierre de vigencias → reactivación del mismo padre dentro de una única transacción.
2. Aplicar la protección a tutor, familia, deportista y familia_deportista, según las operaciones de cierre previstas en C2-A.
3. La protección no debe poder eludirse mediante SET CONSTRAINTS ALL IMMEDIATE, SET CONSTRAINTS ALL DEFERRED ni invocaciones SQL directas autorizadas.
4. Mantener permitida la reactivación en una transacción posterior, sin reabrir vigencias históricas.
5. Conservar el comportamiento idempotente de operaciones repetidas que no producen cambios efectivos.
6. Mantener el cierre, el cambio de estado del padre y la auditoría como una operación atómica.
7. No conceder al runtime UPDATE general sobre tutor_deportista ni acceso directo a las marcas privadas.
8. No modificar V14 ni ampliar el alcance de las funciones SECURITY DEFINER.

### Validaciones

Probar expresamente la secuencia que detectó Gentle AI:

- Baja que cierra vigencias.
- SET CONSTRAINTS para adelantar la guarda.
- Reactivación dentro de la misma transacción.
- Intento de COMMIT.

La operación debe rechazarse y no dejar cambios parciales confirmados.

Probar además reactivación en una transacción posterior, concurrencia entre sesiones, aislamiento por escuela, rollback, savepoints e idempotencia.

Verificar que el mecanismo no introduce nuevos interbloqueos ni altera operaciones existentes.

### Revisión obligatoria

La revisión nativa anterior terminó sin aprobación. No considerarla válida para el nuevo candidato.

Después de implementar y probar la corrección, ejecutar una nueva revisión independiente y el procedimiento nativo completo de Gentle AI.

Resolver los hallazgos bloqueantes y no crear commits hasta obtener la aprobación correspondiente.

### Límites

Autorizo corregir V15 localmente, ejecutar pruebas y solicitar una nueva revisión.

No autorizo push, PR, merge, despliegue ni cambios en Supabase.

No iniciar C2-B, C3, U21 ni U22.

Si la protección no puede demostrarse sin ampliar significativamente el diseño o los privilegios, detenerse y presentar las alternativas.

### Entrega

Informar la solución implementada, la prueba de reproducción del fallo, el resultado de las suites, el dictamen de Gentle AI y si C2-A está listo para continuar con la integración Java.

### L15 — Reproducción y autorización de implementación

«La prueba de regresión confirmó el defecto de V15 en los cuatro registros padre.»
«Rechazar inmediatamente una reactivación cuando la misma transacción haya efectuado una baja que cerró vigencias del mismo registro.»
«No aceptar una corrección que solo cambie la expectativa de la prueba o retrase el rechazo hasta un punto que pueda eludirse.»
«No crear commits hasta completar las revisiones requeridas.»

RED en PostgreSQL 17.11: 4/4 fallaron al esperar el rechazo; la reactivación y el COMMIT no emitieron error. Log `/tmp/banfield-c2a-reactivacion-red.vfZY8a.log`. La corrección comprueba la reactivación en el UPDATE con metadatos transaccionales del cierre, sin sumar privilegios ni cambiar la guarda diferida.


### L16 — Integración Java C2-A2/A3

«Continuá con la integración Java de C2-A. No repitas la implementación SQL ni la revisión de V15.»
«Cada operación debe utilizar las funciones restringidas de V15, verificar el alcance de escuela y conservar cierre, modificación del padre, revisión optimista y auditoría en una misma transacción.»
«Obtener actor y escuela desde la sesión autenticada. No confiar en identificadores arbitrarios enviados por el cliente.»
«No modificar V14 ni reescribir el commit V15.»

Base congelada `64181868c79653f5f2b4a072f79033f0bcd257f4` (V15, árbol `a24bf23472cfd7222cc6bbc2e7d27eb358d8df60`, reconocimiento del linaje `review-5d7c0d7b9f0696a3`). C2-A2 conecta familia, deportista y revocación sin cambiar HTTP; C2-A3 agrega el comando ADMIN de tutor. El relevamiento delegado de solo lectura verificó el orden padre → marca → vigencias, con deportista primero en la revocación y sin inversión respecto de la carga documental. Se conservan el lock_timeout configurado, los controles de revisión y la auditoría de V15 sin duplicarla.

### L17 — Retomar y verificar C2-A2

«Continuar únicamente con el trabajo pendiente, sin repetir las pruebas exitosas salvo que los cambios posteriores lo justifiquen.»
«Verificar que el tratamiento de conflictos bajo REPEATABLE READ se limite al conflicto identificado, sin ocultar otras violaciones de integridad.»
«Ejecutar las suites completas de backend y PostgreSQL 17 sobre el candidato definitivo.»

C2-A2: las tres operaciones ADMIN existentes cierran antes de escribir el padre, sin cambios de contrato ni SQL de migración. V15 audita por vigencia y Java mantiene la auditoría de la transición del padre. El foco anterior verificó 65/65 (concurrencia y HTTP) y luego 10/10 (incluido el timeout real). En PostgreSQL, un alta invisible bajo REPEATABLE READ impide confirmar la baja por la FK de la marca; una transacción nueva ve el alta y permite reintentar el comando entero. La traducción solo acepta SQLSTATE 23503 y el nombre exacto de la FK esperada. RED negativo de otro SQLSTATE: 1/3; GREEN 3/3 unitarias y 10/10 PG17 con el ajuste. No se repitió la regresión documental verde: se ejecutará con las suites completas del candidato definitivo después de C2-A3.


### L18 — C2-A3 sobre C2-A2 reconocido

C2-A2 quedó guardado en `c0309205712146d6eb588650075c2a4fe6a53da8`; Gentle AI `review-a724860142c623d1` aprobó y reconoció el árbol `4349a193a44e75916cf7cafc9229ca4376a70827`. La revisión independiente quedó sin bloqueantes (sonda 4/4); los dos avisos nativos se tratan como seguimientos separados. Siguiente corte: comandos explícitos ADMIN de estado del tutor, con revisión del tutor, sin cambiar la revisión, estado ni vínculos de su familia. Verificación de Java y suites completas en el candidato definitivo.

Los dos comandos de tutor usan `TutorVersionEsperada` (solo `versionEsperada` decimal como cadena), CSRF, rol ADMIN y tutor buscado en la escuela del JWT. El cierre precede al UPDATE del tutor, con revisión optimista; V15 audita vigencias y Java el estado. El inventario cerrado, la especificación OpenAPI y la tabla de rutas declaran los dos POST; no se cambia el PUT existente ni la carga documental.

RED: el POST devolvió 404 antes de implementarse. GREEN: 89/89 unitarias, WebMvc y contratos; 3/3 nuevos casos PG17 de tutor (historial, rollback/escuela y lock documental). La matriz de familia, deportista y revocación pasó 6/6 bajo READ COMMITTED y REPEATABLE READ; completa la cobertura del aviso nativo de C2-A2 sin cambiar su implementación. Pendientes las dos suites completas y la revisión de este candidato.

La primera suite normal completa detectó tres pruebas o fixtures que el foco no incluía: mutabilidad de `Tutor.activo`, lista cerrada de consumidores indirectos del timeout y bean del cierre en un contexto WebMvc de privacidad. Se conservaron y actualizaron sus aserciones sin cambiar producción; verificación focal 25/25. La primera corrida completa sin sandbox registró 1460 pruebas, 2 fallos y 12 errores en estos tres grupos; PostgreSQL completo no se lanzó sobre ese candidato.

La suite normal completa del segundo candidato quedó verde (1460/1460). Su PostgreSQL 17.11 completo ejecutó 929 casos: 2 fallos de fixtures de tutor, un smoke con runtime sobre V14 que debía usar V15 y un `Connection refused` en V5. Las 17 pruebas de integración de cierres pasaron. Se corrigieron los tres fixtures reproducibles sin editar las migraciones: persistencia de estado y edición independiente, sondeo de espera en el cierre y traslado del smoke al esquema V15 con una vigencia real. Foco corregido 3/3; el test V5 y sus timeouts quedan intactos. Ambas suites finales se ejecutan de nuevo sobre el árbol actualizado.

### L19 — Cierre y publicación de C2-A (PR #53)

C2-A quedó guardado en `6418186`, `c030920` y `292edcf` sobre `main @ 159c738`. Gentle AI (lentes risk, resilience y reliability; ítem 4 escalado) aprobó C2-A3 sin hallazgos y se ejecutó el reconocimiento (`review-fde6ab6ad522557b`). PostgreSQL 17 completo en la última corrida local: 929/929, sin `Connection refused`; el backend normal 1460/1460 procede de la ejecución anterior de Codex. Decisión funcional aceptada: se puede reactivar un tutor de una familia inactiva sin restaurar vigencias ni ampliar permisos. Publicado como PR #53 con `size:exception`. En la CI, `TemporadasConcurrenciaDbTest.dosActivacionesEncadenadas...` falló una vez en el evento `push` (el helper `sentenciaEsperandoUnBloqueo` toma la primera sesión en espera sin orden ni filtro) y pasó en el mismo SHA bajo `pull_request` y tras un único reintento; no se tocó la prueba. Riesgos conocidos: causa raíz de los `Connection refused` previos sin identificar; helper de temporadas con carrera preexistente, fuera del alcance de C2-A.

### L20 — C2-B autorizado (plan aprobado)

«Apruebo el plan técnico de C2-B y autorizo su implementación local.» Base `main @ f1646b3`; worktree `~/worktrees/banfield-c2b`, rama `feat/vinculos-tutor-deportista-c2b`. Decisiones: V16 sin tocar V14/V15; `REVOCACION_ADMIN` como causa de cierre individual; tipo opcional distinguiendo NULL de SIN_DETERMINAR; bandera de altas desactivada por defecto; «Una repetición tardía devuelve la vigencia original, aunque esté cerrada.» «Reutilizar una clave con contenido diferente devuelve 409.» Restricciones: sin push, PR, merge ni despliegue; sin Supabase; sin C3, U21 ni U22; sin corregir la deuda de `TemporadasConcurrenciaDbTest`.
