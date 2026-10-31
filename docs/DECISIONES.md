# Decisiones del proyecto

Estado a 2026-10-31. Las decisiones cerradas ya fueron aprobadas por el usuario. Las abiertas **no** están decididas: incluyen recomendación, pero requieren confirmación.

## 1. Decisiones cerradas

| ID | Decisión | Consecuencia técnica | Fase |
| --- | --- | --- | --- |
| D1 | FAMILIA deja de ser globalmente solo lectura. Solo puede escribir en workflows explícitos: documentos y aptos propios, respuesta a competencias y estado de sus números de rifa. No tiene CRUD de familias ni de deportistas. | Rutas `/api/familia/**` de escritura acotadas, con CSRF y alcance por familia. El invariante actual de solo lectura cambia. | 3, 6, 8 |
| D2 | Rifas: FAMILIA gestiona `VENDIDO`/`DISPONIBLE` de sus números; ADMIN gestiona `PENDIENTE`/`COBRADO`. Sin pasarela de pago. | Estado del número y estado de pago son dimensiones separadas. `numero_rifa` no tiene columna de pago en `V1`. | 8 |
| D3 | Competencias: ADMIN convoca explícitamente. "Convocar todos los activos" es una acción de conveniencia que **materializa** filas de inscripción. FAMILIA responde solo por convocados. | Toda inscripción nace `SIN_RESPONDER`; sin fila no hay respuesta posible. | 6 |
| D4 | Empadronamiento y licencia requieren historial de dominio explícito con usuario, fecha, estado y observación. La auditoría no sustituye ese historial. | `V1` guarda solo el estado actual; hace falta una tabla de historial nueva. | 5 |
| D5 | LBF queda bloqueado hasta incorporar las plantillas oficiales reales. No se crean plantillas ficticias. | La fase 7 no arranca sin archivos oficiales y su mapeo de celdas. | 7 |
| D6 | DNI minimizado o enmascarado en listados ADMIN; completo solo en detalle cuando sea necesario. | `DeportistaResumen` deja de exponer DNI completo. La búsqueda por prefijo de DNI se mantiene. | 0, 1 |
| D7 | Estados de Documento, Apto, Empadronamiento, Licencia e Inscripción se alinean con `V1` y `REQUERIMIENTOS.md`. | `backend/AGENTS.md` corregido; ver `docs/REVISION-DOCUMENTAL.md`. | 0 |
| D8 | La segunda cuenta ADMIN se crea con un procedimiento operativo documentado. No hay endpoint público ni administrativo para crearla. | Solo bootstrap por entorno y procedimiento manual auditado. | 0 |
| D9 | ODD significa Organic Driven Development y se rige por los contratos de Gentle AI. | `docs/ODD.md`; artefactos `odd/{feature}/tasks`; `sdd/*` congelado. | 0 |
| D10 | CI básico se incorpora temprano, con suite normal y suite Testcontainers. | `CI-0` en paralelo a Fase 1, obligatorio antes de cerrarla. | CI-0 |
| D11 | El spike Playwright federativo es una fase propia, anterior a Documentos. | Fase 2 entre Temporadas y Documentos. | 2 |

## 2. Estados canónicos (D7)

| Entidad | Estados | Fuente |
| --- | --- | --- |
| Documento | `PENDIENTE`, `CARGADO`, `APROBADO`, `OBSERVADO`, `VENCIDO` | `V1` línea 273 + RF-10 |
| Apto físico | `PENDIENTE`, `CARGADO`, `APROBADO`, `OBSERVADO`, `LISTO_PARA_PRESENTAR`, `PRESENTADO` | `V1` 325-328 + RF-11 |
| Empadronamiento | `PENDIENTE`, `LISTO_PARA_PRESENTAR`, `PRESENTADO`, `EMPADRONADO`, `OBSERVADO` | `V1` 360-363 + RF-12 |
| Licencia | `PENDIENTE`, `SOLICITADA`, `APROBADA`, `OBSERVADA`, `VENCIDA` | `V1` 393 |
| Inscripción | `SIN_RESPONDER`, `PARTICIPA`, `NO_PARTICIPA` | `V1` 460 + RF-16 |
| Competencia | `BORRADOR`, `ABIERTA`, `CERRADA`, `FINALIZADA`, `CANCELADA` | `V1` 430 |
| Rifa | `BORRADOR`, `ACTIVA`, `CERRADA`, `SORTEADA`, `CANCELADA` | `V1` 553 |
| Número de rifa | `DISPONIBLE`, `ASIGNADO`, `VENDIDO` | `V1` 574 |

## 3. Decisiones abiertas — requieren aprobación

### O1 — ¿FAMILIA puede cargar documentos de tutores?

- **A. Sí, solo de tutores de su propia familia.** RF-09 lo pide explícitamente y el tutor es quien firma la solicitud de licencia.
- **B. Solo documentos del deportista.** Menos superficie de escritura, pero obliga al ADMIN a cargar DNI de tutores.

Ventajas de A: cumple RF-09 y evita ida y vuelta. Riesgos: hay que validar pertenencia tutor↔familia y no confundir al tutor con el usuario autenticado.
Recomendación: **A**, con alcance derivado del vínculo activo y auditoría de cada carga.
Fase: 3.

### O2 — ¿Cómo se integra Supabase Storage?

- **A. REST con `WebClient`/`RestClient` y URLs firmadas.** Sin SDK extra, control total del contrato.
- **B. SDK oficial de Supabase.** Menos código de integración, pero suma dependencia y acopla versiones.

Ventajas de A: menos dependencias, fácil de testear con dobles. Riesgos: hay que implementar bien la firma y el manejo de errores.
Recomendación: **A**, encapsulado en un `AlmacenDocumentos` con interfaz propia.
Fase: 3.

### O3 — ¿El apto físico necesita historial de estados?

- **A. Solo estado actual.** `V1` ya tiene una fila por deportista/temporada.
- **B. Historial completo como empadronamiento.**

Ventajas de A: menos esquema y menos escritura. Riesgo: no se puede reconstruir quién observó un apto y cuándo.
Recomendación: **A** para el MVP, con auditoría de cambios de estado; revisar en Fase 10 si el uso real lo exige.
Fase: 4.

### O4 — Diseño del historial federativo

- **A. Una tabla por entidad:** `empadronamiento_historial`, `licencia_historial`.
- **B. Una tabla genérica de transiciones:** recurso, estado anterior, estado nuevo, usuario, observación.

Ventajas de A: FK reales, consultas simples, borrado accidental imposible por diseño. Ventajas de B: un solo lugar para auditar. Riesgos de B: claves polimórficas sin integridad referencial.
Recomendación: **A**.
Fase: 5.

### O5 — ¿Quién ejecuta cada transición de licencia?

- **A. Solo ADMIN** (`PENDIENTE → SOLICITADA → APROBADA/OBSERVADA → VENCIDA`).
- **B. FAMILIA solicita, ADMIN aprueba u observa.**

Ventajas de B: refleja el circuito real. Riesgo: agrega una escritura FAMILIA no prevista en D1.
Recomendación: **A** para el MVP; B solo si el circuito real lo exige, y en ese caso se amplía D1 explícitamente.
Fase: 5.

### O6 — ¿Se puede quitar un convocado después de que respondió?

- **A. No. La convocatoria es inmutable; se agrega una observación.**
- **B. Sí, y la respuesta se descarta.**

Ventajas de A: trazabilidad y menos estados imposibles. Riesgo: un error de convocatoria obliga a cancelar y recrear la inscripción.
Recomendación: **A**, con acción ADMIN explícita que deje constancia.
Fase: 6.

### O7 — ¿Qué significa "activo" al convocar?

- **A. `deportista.activo` global.**
- **B. `deportista_temporada.estado_deportivo = ACTIVO` de la temporada de la competencia.**

Ventajas de B: correcto cuando alguien se lesiona o deja de competir a mitad de año. Riesgo: exige que exista fila de temporada para cada deportista.
Recomendación: **B**, y que la acción masiva salte deportistas sin fila de temporada e informe cuántos quedaron afuera.
Fase: 6.

### O8 — Formato de enmascarado de DNI

- **A. Últimos 3 dígitos:** `•••••322`.
- **B. Sin DNI en el listado**, solo en detalle.

Ventajas de A: permite reconocer a simple vista y mantiene la búsqueda por prefijo. Riesgo: sigue siendo dato personal parcial.
Recomendación: **A**, con búsqueda por DNI completo permitida solo a ADMIN y DNI completo únicamente en el detalle.
Fase: 0, 1.

### O9 — Transiciones de pago en rifas

- **A. `COBRADO` no bloquea el estado del número; FAMILIA puede volver a `DISPONIBLE`, pero el pago queda registrado y el ADMIN decide.**
- **B. Si está `COBRADO`, el número no puede volver a `DISPONIBLE`.**

Ventajas de A: refleja el caso real de una venta caída. Riesgo: recaudación y estado del número pueden desalinearse.
Recomendación: **A** con historial de cambios de pago; el total recaudado se calcula solo sobre `VENDIDO` + `COBRADO`.
Fase: 8.

### O10 — Reglas de temporada

- **A. Puede no haber temporada activa; el ADMIN activa cuando corresponde.**
- **B. Siempre debe existir exactamente una activa.**

Ventajas de A: no fuerza datos falsos al inicio. Riesgo: módulos que dependen de "temporada actual" deben manejar el vacío.
Recomendación: **A**, con error de negocio explícito en los módulos que la requieran.
Fase: 1.

### O11 — Plantillas LBF

- **A. Se versionan como recurso del backend y se registran en `plantilla_lbf`.**
- **B. Se guardan en Storage y se referencian por clave.**

Ventajas de A: la plantilla viaja con el código y los tests la cubren. Riesgo: hay que actualizarla con cada cambio de la Federación.
Recomendación: **A**, con hash de plantilla en la exportación.
Fase: 7 (bloqueada).

### O12 — Acceso para el spike Playwright

- **A. Cuenta Google de prueba y datos ficticios.**
- **B. Cuenta real con datos reales de un deportista autorizado.**

Ventajas de A: sin datos de menores. Riesgo: el formulario real puede rechazar datos ficticios y el spike no valida nada.
Recomendación: **B acotada**, un solo deportista, con autorización explícita y sin enviar el formulario.
Fase: 2.

### O13 — CI: alcance y obligatoriedad

- **A. Dos jobs obligatorios en PR y push a `main`: suite normal y `-Pdb-tests`.**
- **B. Solo suite normal obligatoria; Testcontainers opcional.**

Ventajas de A: cubre migraciones y restricciones reales. Riesgo: más tiempo de pipeline y dependencia de Docker en el runner.
Recomendación: **A**, con cache de Maven y sin secretos reales.
Fase: CI-0.

### O14 — Procedimiento de la segunda cuenta ADMIN

- **A. El ADMIN existente ejecuta un procedimiento documentado por consola/entorno y enrola MFA en el primer ingreso.**
- **B. Endpoint administrativo protegido.**

Ventajas de A: no amplía la superficie de ataque. Riesgo: depende de acceso operativo a la infraestructura.
Recomendación: **A**. B queda descartado por D8.
Fase: 0.

## 4. Procedimiento operativo — segunda cuenta ADMIN

No hay endpoint. Se reutiliza el bootstrap de `docs/SEGURIDAD.md`, sección 8, que ya limita a `max_administradores` (2 en Banfield) y es idempotente.

1. Preparar en el entorno del backend, nunca en Git: `BOOTSTRAP_ADMIN_HABILITADO=true` y `BOOTSTRAP_ADMIN_EMAIL`, `BOOTSTRAP_ADMIN_NOMBRE`, `BOOTSTRAP_ADMIN_APELLIDO`, `BOOTSTRAP_ADMIN_PASSWORD` de la **segunda** persona. El email debe ser distinto del primer ADMIN.
2. Arrancar la aplicación una vez. Si ya hay dos ADMIN activos, el bootstrap no crea nada y solo avisa en el log.
3. Verificar en el log el evento `ADMIN_BOOTSTRAP` y que la cuenta quedó creada con `mfa_habilitado=false`.
4. Iniciar sesión en `/api/auth/admin/login` y **enrolar el TOTP de inmediato**, antes de operar.
5. Volver `BOOTSTRAP_ADMIN_HABILITADO=false`, borrar `BOOTSTRAP_ADMIN_PASSWORD` del entorno y reiniciar.
6. Comprobar que el primer ADMIN sigue funcionando y que no existe una tercera cuenta activa.
7. Registrar el alta en la bitácora operativa de la escuela, fuera del repositorio.

Reglas:

- Nunca compartir el mismo TOTP entre las dos cuentas.
- Si la segunda cuenta ya existe, no se "recrea": se usa el reinicio de MFA ya implementado.
- La contraseña temporal se cambia en el primer ingreso.
- La ventana entre el alta y el enrolamiento queda sin segundo factor: minimizarla.
