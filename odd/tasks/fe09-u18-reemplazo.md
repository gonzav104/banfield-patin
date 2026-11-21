# FE-09 / U18 — Reemplazo de documentos observados
Rama: `feat/fe09-u18-reemplazo` · Base: `origin/main` `c43a74b` (incluye U15–U17 y la corrección del 409). Entrega local en tres commits incrementales sobre una única rama; PR único con `size:exception` aceptada, sin publicar. Pronóstico: ~1500 líneas. Pruebas: Maven, PostgreSQL/Testcontainers, Vitest, Playwright.

## Specs
- S1: «Implementar exclusivamente el reemplazo por FAMILIA de documentos `DNI_DEPORTISTA_FRENTE` y `DNI_DEPORTISTA_DORSO` que estén vigentes y en estado `OBSERVADO`.» Fuera: tutores, otros tipos, APROBADO/CARGADO, reemplazo por ADMIN, U19, producción.
- S2: «Utilizar la ruta dedicada `POST /api/familia/deportistas/{id}/documentos/{documentoId}/reemplazo`, manteniendo intacto el contrato original de carga.» Una única parte multipart `archivo`; el tipo sale del predecesor; mismos CSRF e `Idempotency-Key`.
- S3 (C1): migración V12 (`predecesor_id` en `operacion_carga_documental`, inmutable, parte de la identidad idempotente; obligatorio solo en reemplazos) y trigger de integridad redefinido; el sucesor hereda la serie del predecesor. Índice no único de apoyo; ningún índice único por estados no terminales.
- S4 (C2): reserva concurrente. «Si existe un reemplazo activo del mismo predecesor, rechazar el segundo intento con `409 OPERACION_EN_CURSO`, siempre que corresponda realmente a una operación transitoria.» «Si el predecesor ya fue reemplazado, utilizar `409 OPERACION_NO_REPETIBLE`.» «Si la primera operación terminó sin publicar y el predecesor sigue vigente y OBSERVADO, permitir un nuevo intento válido.» Misma clave: idempotencia existente. Sin estados bloqueados permanentemente por operaciones abandonadas o reservas vencidas.
- S5: «Una operación ya PUBLICADA debe seguir respondiendo idempotentemente al reintento legítimo con la misma clave y manifiesto, aunque el predecesor haya pasado a no vigente.»
- S6: «Verificar el orden efectivo de bloqueos de todo el pipeline antes de introducir el bloqueo adicional del predecesor.» «La comprobación de operaciones activas y la reserva deben ser atómicas y seguras bajo concurrencia.»
- S7: «El predecesor debe conservar su historial y motivo de observación. El sucesor debe heredar correctamente la serie documental y publicarse como el único documento vigente correspondiente. La publicación, los cambios de vigencia y la auditoría deben respetar los límites transaccionales aprobados.»
- S8: «No modificar el comportamiento del POST original de U15.» Regresiones U15/U16/U17: CSRF, filtros multipart, control de acceso, idempotencia, descarga solo de vigentes.
- S9 (C3): frontend, reemplazo desde la ficha familiar y E2E.
- S10: verificación independiente en cada corte; revisión nativa de Gentle AI con consentimiento solicitado antes de ejecutarla; no declarar revisiones no realizadas. No push, PR, merge, despliegue ni habilitación de carga.

## Tasks
- [x] T1 · S3/S8/S10 · inline · C1: V12 + trigger + pruebas PostgreSQL · commit C1 (ver Log L4); verificación independiente sin bloqueantes; Gentle AI aprobada y reconocida; pruebas 11/11 (V12), 57/57 (V6), db completa 734/0.
- [ ] T2 · S2/S4/S5/S6/S7/S8/S10 · inline · C2: backend, reserva concurrente, publicación atómica, auditoría, OpenAPI, docs · pendiente.
- [ ] T3 · S9/S8/S10 · inline · C3: frontend y E2E · pendiente.

Los cortes son secuenciales (V12 → backend → UI): no hay escritor delegado por paralelismo.

## Log
### L1 — Autorización de implementación (2026-11-21)
Apruebo el plan ejecutable de **U18 — Reemplazo de documentos observados** y autorizo comenzar su implementación local. Decisiones: ruta dedicada `POST /api/familia/deportistas/{id}/documentos/{documentoId}/reemplazo` con el contrato original intacto; una única rama `feat/fe09-u18-reemplazo` con tres commits (C1 V12/integridad, C2 backend/reserva/publicación/auditoría, C3 frontend/E2E); `size:exception` aceptada para el futuro PR. Idempotencia: la PUBLICADA responde idempotente aunque el predecesor ya no sea vigente. Concurrencia: verificar el orden de bloqueos antes del bloqueo del predecesor; reserva atómica; conflictos transitorios vs definitivos; una operación cerrada o reserva vencida no bloquea. Integridad: el predecesor conserva historial y motivo; el sucesor hereda la serie. Compatibilidad: POST original intacto; regresiones U15/U16/U17. Revisiones: verificación independiente por corte y Gentle AI con consentimiento previo. Límites: sin push, PR, merge, despliegue ni habilitación documental; no U19.

### L2 — Orden de bloqueos verificado (antes de C2)
`autorizar` toma: escuela FOR SHARE → usuario FOR SHARE → familia FOR SHARE → deportista PESSIMISTIC_WRITE (`bloquearParaVincular`, ordenado por id) → familia_deportista FOR SHARE, con `lock_timeout` 2 s local a la transacción. `reservar` y `publicar` lo ejecutan primero, por lo que ya serializan por deportista. Orden nuevo: …deportista → documento predecesor FOR UPDATE → operación (INSERT/FOR UPDATE/UPDATE). U17 `revisar` toma documento FOR UPDATE y audita; nunca toma deportista ni operación, y no revisa OBSERVADO: sin ciclo. `iniciar/almacenada/cerrar*/liberar` solo tocan la operación. Consecuencia: el timeout del lock se maneja por la vía existente (503 reintentable); el EN_CURSO por lock del predecesor planificado no se emite.

### L3 — C1: verificación independiente
Sin bloqueantes. Diferencial V11 vs V12: 11.106 intentos con resultado idéntico para operaciones sin predecesor; la función V12 difiere de V6 solo en lo previsto. Mutaciones (11): sobrevivieron M1 (inmutabilidad; la prueba pasaba por `RESERVA_NO_ADQUIRIBLE`, corregida: ahora fija `OPERACION_CONTEXTO_INMUTABLE` con reserva activa) y tres defensas en profundidad inalcanzables con los índices de V5 (M4 vigente del predecesor, M5 `FOUND`, M8 escuela, redundante con deportista y la FK compuesta). **Invariante de bloqueo para C2 (S6):** el trigger corre con la fila de la operación ya bloqueada y luego toma documento nuevo y predecesor (`FOR UPDATE`); un publicador que bloquee el predecesor después de la operación se cruza con otro que lo bloquee antes (deadlock probado con dos sesiones). Por eso toda transacción de reemplazo que pueda llegar a INSERT/PUBLICADA bloquea el predecesor ANTES de tocar la operación. C2 debe comparar `predecesor_id` en el reintento idempotente.

### L4 — C1 cerrado
Revisión nativa Gentle AI aprobada y reconocida (`review-6d117ebef0a214d1`, lentes risk, resilience, reliability; consentimiento del usuario). Avisos no bloqueantes: R3-publish-lock-order (el invariante P→operación lo debe cumplir y probar C2) y R3-insert-keyshare-race (validar vigencia/OBSERVADO del predecesor al reservar es del servicio, por diseño).
