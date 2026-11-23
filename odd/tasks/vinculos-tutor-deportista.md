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

## Tasks

- [ ] C1 · S1–S2, S7–S8 · implementación inline; verificación independiente sin bloqueantes · riesgo 1/2 (invariantes e aislamiento) · C1-A `5ac5824` revisado; C1-B contiene consultas y contrato, su comprobación nativa se registra en el siguiente corte.
- [ ] C2 · S3–S5, S7–S8 · bloqueado por C1; riesgo 2/4 (ADMIN y carreras entre escritores) · commit(s) pendiente(s).
- [ ] C3 · S6–S8 · bloqueado por C2; riesgo 2 (privacidad y roles) · commit(s) pendiente(s).

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
