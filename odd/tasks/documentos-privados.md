# Fase 3 — Gestión privada de documentos
Objetivo: consolidar diseño y decisiones antes de implementar. Base: `main` `8a132ee`; estado: D1 AUTORIZADA (consulta de metadatos de deportistas y tutores); O1-A/O2-A aprobadas. Resto de decisiones específicas según L4.
Rama: `feat/documentos-privados`, creada desde main 8a132ee conservando este documento. Entrega D1: `exception-ok`, aprobada exclusivamente para el candidato de 22 archivos y 952 líneas más su registro documental (L7); no se hereda a otras unidades ni de Fase 2. Runner: `cd backend && ./mvnw -o clean verify` y `./mvnw -o verify -Pdb-tests` (PostgreSQL 17). Commit bloqueado hasta completar RDD sin bloqueantes.

## Specs

### Requisitos autorizados y límites

- **S1.** "Objetivo inicial: definir el diseño funcional y técnico de la fase, sin implementar código todavía." / "Prepará el documento ODD inicial y señalá las decisiones que requieren aprobación."
- **S2.** "Identificá qué entidades, campos, restricciones, servicios y endpoints ya existen, para reutilizarlos y evitar duplicaciones."
- **S3.** "La matriz exacta de tipos documentales, propietarios y relación con temporada." / "OTRO: admitir múltiples documentos."
- **S4.** "Las transiciones permitidas entre estados." / "Reemplazo: conservar historial, invalidar la vigencia anterior y exigir nueva revisión."
- **S5.** "Las restricciones de base de datos necesarias, sin modificar V1–V4." / "Las garantías de concurrencia y autorización, incluyendo revocación de vínculos."
- **S6.** "La estrategia mínima viable de consistencia entre PostgreSQL y Storage." / "O2-A: integración Supabase Storage mediante RestClient y un adaptador AlmacenDocumentos." / "Descargas: preferir mediación por backend."
- **S7.** "O1-A: FAMILIA puede gestionar documentos de tutores pertenecientes a su familia autorizada." / "Eliminación: no implementar borrado físico arbitrario; documentar retención y eliminación segura."
- **S8.** "Contemplá carga PDF/JPG/PNG, validación de contenido, tamaño configurable, descarga individual y generación de ZIP." / "Separá claramente las funcionalidades documentales de Fase 3 de la gestión de aptos físicos de Fase 4."
- **S9.** "Los criterios de aceptación y pruebas para la primera unidad ODD." / "Priorizá una solución robusta pero proporcional al MVP. No implementes mecanismos complejos que todavía no estén justificados."
- **S11 (autorización vigente).** "Autorizo comenzar la primera unidad ODD: consulta autorizada de metadatos documentales." / "FAMILIA consulta únicamente documentos de deportistas vinculados de forma activa y tutores de su familia autorizada." / "No integrar Supabase Storage, implementar cargas, descargas, reemplazos ni modificar migraciones en esta unidad." / "No hacer push ni merge sin autorización."
- **S10.** "Tratálas como propuestas pendientes de aprobación formal, no como decisiones ya registradas." / "No modificar código de producción, migraciones ni Supabase todavía. Respetar ODD y RDD. No hacer push ni merge."
- **S12 (entrega D1, autorización L7).** "Autorizo la excepción de tamaño exclusivamente para la unidad D1 de Fase 3: 22 archivos y 952 líneas del candidato actual." / "Conservá juntos comportamiento, pruebas y documentación, sin ampliar el alcance funcional." / "No realices el commit hasta completar satisfactoriamente RDD y resolver cualquier bloqueante."

Las tablas siguientes conservan propuestas para unidades posteriores. O1-A/O2-A y D1 están aprobadas según L4; no se aprueban por arrastre WADA anual, retención, escaneo, límites operativos ni transiciones/restricciones definitivas. Registro formal de O1/O2 en `docs/DECISIONES.md`.

### A. Base existente verificada

- `backend/src/main/resources/db/migration/V1__crear_esquema_inicial.sql:237–307`: tabla `gestion_patin.documento`, propietario exclusivo deportista/tutor, escuela, temporada opcional, metadatos, estados, `vigente` y predecessor. No hay versión optimista ni módulo Java documental.
- La misma V1 ya distingue `apto_fisico`; no duplicar sus campos clínicos en Documento.
- `FamiliaDeportistaRepository:129–153`: alcance por familia/escuela y vínculo ACTIVO; no exige deportista activo ni vínculo principal. Reutilizar la semántica, no copiar una comprobación previa como si garantizara escrituras concurrentes.
- `TutorRepository`: consultas por escuela y por familia; falta una consulta individual acotada a familia. Un tutor no es necesariamente el usuario autenticado.
- Reutilizar `UsuarioAutenticado`, revalidación central, CSRF, DTOs/errores, auditoría y consultas de escuela. No abrir acceso directo del frontend a Storage.
- El sanitizador de auditoría actual no basta para nombres de archivo, URLs o valores personales. Las nuevas acciones documentales deben usar detalles permitidos explícitamente.
- `docs/ROADMAP.md` conserva estados viejos de Fases 1/2. Su reconciliación futura no reabre pruebas de Forms ni convierte el spike en automatización productiva.

### B. Decisiones propuestas que requieren aprobación

| ID | Propuesta | Estado y consecuencia |
| --- | --- | --- |
| P1 / O1-A | FAMILIA gestiona documentos propios de tutores de su familia autorizada | APROBADA en L4; no modifica tutor, familia ni vínculos; revisar no está permitido para FAMILIA |
| P2 / O2-A | `RestClient` detrás de `AlmacenDocumentos`; carga y descarga mediadas | APROBADA RestClient/AlmacenDocumentos en L4; integración fuera de D1; credencial sólo backend, bucket privado; sin SDK/WebFlux ni firma criptográfica propia |
| P3 | Matriz B1 y múltiples series para OTRO | Pendiente; tipos anuales y OTRO necesitan ratificación funcional |
| P4 | Máquina B2, historial de archivos y revisión nueva en cada reemplazo | Pendiente; expiración efectiva por fecha y marcado administrativo separados |
| P5 | Restricciones B3, series y control optimista | Pendiente; inspeccionar datos antes de cualquier migración, sin arreglos destructivos automáticos |
| P6 | Autorización y serialización B4 | Pendiente; alcance se revalida al publicar; operación iniciada no implica permiso permanente |
| P7 | Flujo síncrono B5, compensación y reconciliación operativa | Pendiente; puede dejar objetos privados huérfanos, nunca documentos publicados incompletos |
| P8 | No borrado físico de versiones desde la API; retención aprobada antes de datos reales | Pendiente; no se inventa plazo legal ni se promete conservación indefinida |
| P9 | Validación y escaneo de contenido antes de disponibilidad | Pendiente; elegir solución privada y política si falla antes de habilitar cargas reales |
| P10 | Primera unidad sólo lectura de metadatos de deportistas y tutores | AUTORIZADA en L4 y ampliada a tutores; sin implementación de archivos |

#### B1. Matriz exacta propuesta

Temporada obligatoria significa temporada existente de la misma escuela, no necesariamente la activa. Cambiar la temporada activa no mueve archivos ni borra historia. `—` significa `temporada_id = NULL` obligatorio.

| Tipo canónico | Propietario | Temporada | Multiplicidad vigente | Fase |
| --- | --- | --- | --- | --- |
| DNI_DEPORTISTA_FRENTE | Deportista | — | Una por propietario/tipo | 3 |
| DNI_DEPORTISTA_DORSO | Deportista | — | Una por propietario/tipo | 3 |
| DNI_TUTOR_FRENTE | Tutor | — | Una por propietario/tipo | 3, sujeto a P1 |
| DNI_TUTOR_DORSO | Tutor | — | Una por propietario/tipo | 3, sujeto a P1 |
| SOLICITUD_LICENCIA | Deportista | Obligatoria | Una por propietario/tipo/temporada | 3: archivo, no estado de licencia |
| CERTIFICADO_WADA | Deportista | Obligatoria | Una por propietario/tipo/temporada | 3: archivo, no decisión médica |
| OTRO | Deportista o Tutor, nunca ambos | Opcional, explícita al crear | Múltiples series independientes por propietario y temporada | 3 |
| APTO_FISICO | Deportista | Obligatoria | Una por propietario/tipo/temporada | Reservado a 4; rechazado en cargas de Fase 3 |

Para OTRO: título descriptivo saneado en metadatos, sin catálogo nuevo ni esquema configurable. Cada alta crea una serie UUID server-side. El título no identifica la serie ni concede autorización. Cada serie admite sucesivos reemplazos, pero sólo una versión vigente. No utilizar OTRO para saltar la reserva de APTO_FISICO: no ofrece funcionalidades médicas ni completa requisitos de apto.

La propuesta anual de SOLICITUD_LICENCIA/WADA no se deduce del CHECK SQL: requiere aprobación. No interpretar temporada opcional de OTRO como permiso para cambiarla después.

#### B2. Estados, reemplazos y vencimiento

Estados persistidos sin cambios al vocabulario: PENDIENTE, CARGADO, APROBADO, OBSERVADO, VENCIDO. `vigente` indica versión actual, no aprobación ni validez temporal.

| Origen de versión vigente | Destinos administrativos permitidos | Actor |
| --- | --- | --- |
| PENDIENTE | APROBADO, OBSERVADO, VENCIDO | ADMIN de escuela, mediante revisión explícita |
| CARGADO | PENDIENTE, APROBADO, OBSERVADO, VENCIDO | ADMIN de escuela |
| APROBADO | PENDIENTE, OBSERVADO, VENCIDO | ADMIN de escuela |
| OBSERVADO | PENDIENTE, VENCIDO | ADMIN de escuela |
| VENCIDO | Ninguno sobre ese archivo | Reemplazar para nueva revisión |

- Toda transición omitida se rechaza con conflicto, sin cambios ni evento de éxito. Repetir destino con la versión actual devuelve estado sin reescritura ni doble auditoría; versión esperada obsoleta produce conflicto.
- Pasar por PENDIENTE permite revisión nueva del mismo archivo observado; aprobar siempre registra revisor y fecha. OBSERVADO exige motivo no vacío de hasta 500 caracteres, visible sólo dentro del alcance. No copiar el texto a logs/auditoría genérica.
- Una posición sin archivo se presenta como pendiente virtual, sin fila Documento con metadatos ficticios. El estado persistido PENDIENTE sólo corresponde a un archivo existente devuelto a revisión.
- Alta o reemplazo válido produce CARGADO, nunca APROBADO; FAMILIA y ADMIN pueden cargar pero sólo ADMIN revisa. No hay autoaprobación del archivo cargado por un ADMIN.
- Reemplazo: nuevo UUID y key, misma serie/propietario/tipo/temporada/escuela; anterior `vigente=false`; anterior estado/revisión conservados. No sobrescribir binario. Se permite desde cualquier estado vigente, por actor autorizado; un no vigente no admite revisión ni reemplazo directo (conflicto).
- Historial consultable dentro del mismo alcance; sin revivir versiones viejas. Recuperar contenido anterior requiere una nueva carga y revisión.
- Propuesta de fecha: vencimiento opcional, válido hasta fin de ese día en zona de negocio configurable (valor propuesto America/Argentina/Buenos_Aires). No se puede aprobar un documento ya vencido por fecha. `utilizable = vigente && estado == APROBADO && fecha no vencida`. Lectura devuelve estado administrativo y señal de vencimiento, sin mutar filas. ADMIN puede persistir VENCIDO; no añadir scheduler para este MVP ni afirmar que el estado persistido cambia automáticamente.
- Un documento reemplazado deja de ser utilizable inmediatamente aunque el nuevo siga en revisión: no se conserva como aprobado vigente de reserva.

#### B3. Base de datos: mínimo propuesto, no SQL aprobado

Nueva migración después de V4 (usar el siguiente número libre al implementar), jamás editar V1–V4. Conservar tabla y FK actuales salvo sustitución explícita de la FK débil del predecessor.

1. Conservar CHECK de propietario exclusivo, tipos/estados, tamaño positivo, keys únicas y FK de escuela existentes. Añadir compatibilidad tipo/propietario/temporada de B1 y motivo observado no blanco.
2. Añadir `serie_id UUID NOT NULL`, inmutable por API, y `version BIGINT NOT NULL` para bloqueo optimista. Una fila representa un archivo; `version` cuenta mutaciones de revisión/vigencia, no la versión del archivo. Para OTRO añadir `titulo` acotado (propuesta: 120 caracteres), obligatorio no blanco sólo para ese tipo.
3. Índice único parcial `(escuela_id, serie_id)` para `vigente=true`: una versión actual por serie. Índices únicos parciales separados por propietario para tipos distintos de OTRO sobre escuela/propietario/tipo/temporada, con NULLS NOT DISTINCT en PostgreSQL 17: una posición vigente, también sin temporada.
4. UNIQUE `(id, escuela_id, serie_id)` y FK compuesta del predecessor a esos campos, CHECK predecessor distinto de sí mismo, índice único sobre predecessor no nulo: misma escuela/serie y sin dos sucesores del mismo archivo.
5. No fingir que esa FK garantiza propietario/tipo/temporada idénticos: lo comprueba el servicio bloqueando el anterior; esos atributos y predecessor no son editables en la API. Las cadenas sólo se crean apuntando a una fila ya existente; no se actualizan ni importan libremente, evitando ciclos por las operaciones públicas. SQL administrativo directo no queda protegido contra toda corrupción: no añadir triggers recursivos ni grandes claves compuestas sin necesidad demostrada.
6. Precheck de sólo lectura antes de migrar: duplicados vigentes, enlaces cruzados/ciclos, estados incompatibles y motivo vacío. Si hay conflictos, detener migración y pedir resolución; no borrar, reasignar ni aprobar datos automáticamente. Si hay historia válida, asignar una serie a cada raíz y sus descendientes; no suponer tabla vacía ni generar una serie distinta por fila histórica.
7. Límite máximo de bytes configurable en aplicación/infra, no valor fijo duplicado en CHECK. Ninguna columna almacena URL firmada o binario.

No añadir por ahora tabla de tipos, tabla de series, historial de estados separado, outbox ni cola. Archivo anterior + auditoría existente cubren trazabilidad propuesta; las mutaciones de estado conservan evento de actor/fecha/origen/destino sin contenido sensible.

#### B4. Autorización y concurrencia

- ADMIN opera sólo en su escuela; FAMILIA en deportistas con vínculo ACTIVO/familia activa o tutores activos de su familia (P1). Escuela/familia/actor proceden de sesión revalidada, no de metadatos del multipart. No exigir deportista activo ni vínculo principal.
- Documentos de deportista pueden ser accesibles a varias familias con vínculos ACTIVO; documentos de tutores no se comparten por ese motivo. No dar a FAMILIA CRUD de tutores ni de deportistas.
- Consulta individual, listado, historia, descarga y miembros del ZIP repiten el predicado de alcance. No exponer bucket, key, URL firmada, nombres originales ni motivo de otro propietario. 401 sin sesión; 403 por rol/CSRF; 404 uniforme para recurso inexistente/fuera de alcance.
- Para publicar una carga/reemplazo: autorización antes de recibir el archivo y nuevamente dentro de transacción corta final. No sostener locks ni transacción mientras se transfiere a Storage.
- Contrato de locks a implementar y probar junto con los mutadores afectados: orden global escuela → usuario → familia → propietario/vínculo → documento, IDs en orden determinista. Reutilizar patrones existentes, no crear mutex en memoria. Las comprobaciones definitivas y revocaciones/desactivaciones deben serializar sobre filas comunes; un lock usado sólo por documentos no basta.
- Si revocación confirma primero, publicación/lectura posterior se deniega; si publicación confirma primero, queda auditada como anterior a revocación y después no es accesible a esa familia. No prometer deshacer una transferencia ya autorizada y comenzada.
- Revisión usa versión esperada: una sola actualización gana. Reemplazo bloquea vigente, comprueba versión y nuevo alcance; perdedor recibe 409, no crea otra versión publicada. La unicidad DB protege también el primer documento concurrente cuando aún no hay fila para bloquear.
- Descarga mediada: autorizar y registrar inicio antes de bytes; sin caché pública, Content-Disposition attachment y nosniff. No revalidar una vez por bloque ni mantener una transacción durante el streaming. Revocación impide solicitudes nuevas, no recupera bytes ya entregados.

#### B5. Consistencia mínima viable

Propuesta deliberadamente síncrona, sin saga, proceso asíncrono, tabla de sesiones de carga ni reintentos automáticos de POST en esta primera versión:

1. Autorizar; validar metadatos y contenido completo en temporal privado con límites. Límite inicial propuesto 15 MB por archivo, configurable, coherente con proxy/multipart/bucket; formatos PDF/JPEG/PNG. Contenido parseable y límites de recursos; firma/MIME solos no bastan. Escaneo P9 antes de publicación; si no se puede validar, no publicar.
2. Generar UUID/key server-side opacos; subir objeto privado con `upsert=false`, sin URL pública, sin transacción DB abierta. `AlmacenDocumentos` encapsula contrato REST, timeouts y errores redactados; no propaga respuestas crudas del proveedor.
3. Sólo tras éxito confirmado, transacción corta: revalidar acceso, bloquear/comprobar versión, crear Documento, invalidar anterior si corresponde y registrar auditoría de éxito atómicamente. Responder con DTO, nunca con key o URL del proveedor.
4. Si falla Storage, no crear Documento ni tocar el anterior. Si el objeto existe pero falla la confirmación DB, intentar borrado compensatorio de esa key nueva; nunca del anterior. Si resultado es incierto, devolver fallo controlado y registrar código/correlación no sensible, no éxito supuesto.
5. Si compensación falla o el proceso cae, puede quedar objeto privado huérfano. Ningún endpoint descarga keys sin Documento autorizado. Reconciliación operativa de inventario y referencias PostgreSQL, con reporte privado y dry-run; borrado sólo de objetos no referenciados, fuera de ventana de carga acordada, rechecados y con autorización operativa. Sin barrido automático agresivo ni borrar versiones históricas. El procedimiento debe estar probado antes de usar datos reales.
6. No prometer exactamente-una-vez en carga inicial si se pierde la respuesta. No reintentar silenciosamente desde UI: refrescar carpeta y decidir. Reemplazo exige ID/version esperados y evita doble reemplazo con el mismo anterior. OTRO puede admitir duplicados si el usuario repite conscientemente una carga incierta; es deuda explícita. Incorporar idempotencia durable sólo si ese riesgo deja de ser aceptable para el producto.

Retención P8: no DELETE físico en API ni limpieza de versiones por antigüedad sin política aprobada. Los temporales se eliminan al terminar/fallar; el procedimiento de huérfanos no es borrado documental. Definir plazos de documentos/versiones/backups, responsables y excepciones antes de datos reales; separar el mínimo de 12 meses de auditoría (RNF-05), que no fija retención de binarios. No registrar valores de archivos, nombres, keys, URLs, tokens o textos libres en logs.

#### B6. Primera unidad autorizada: D1, consulta autorizada

Entrega de sólo lectura: entidad/enum/DTO/repository/servicio y listado paginado de metadatos vigentes por deportista o tutor. Rutas D1: `GET /api/admin/deportistas/{id}/documentos`, `GET /api/familia/deportistas/{id}/documentos`, `GET /api/admin/tutores/{id}/documentos` y `GET /api/familia/tutores/{id}/documentos`. Sin carga, descarga, revisión, Storage o migraciones en D1. El futuro refuerzo B3 llega antes de habilitar escrituras.

Contrato DTO propuesto: id, tipo, estado, temporadaId, creadoEn, vigente, fechaVencimiento y motivoObservacion si corresponde. Excluir nombreOriginal, bucket/key, URLs, ids de usuarios de revisión y binarios. Orden estable por creadoEn/id; paginación y límites reutilizan el contrato `Pagina` existente. Sin historia en D1: sólo vigentes, aun cuando no estén aprobados. Sin caché de respuestas sensibles.

**Criterios de aceptación, todos pendientes de ejecución:**

- CA1: ADMIN obtiene exclusivamente documentos del deportista y escuela solicitados. FAMILIA sólo de un deportista con vínculo ACTIVO de su familia/escuela o tutor activo de su familia autorizada. ADMIN consulta tutores incluso inactivos dentro de su escuela; FAMILIA no accede a tutor inactivo. Deportista inactivo con vínculo ACTIVO sigue accesible.
- CA2: PENDIENTE/RECHAZADO/REVOCADO, familia/escuela inactivas, otra familia/escuela e ID inexistente no filtran datos. La respuesta de recurso fuera de alcance es idéntica a inexistente; sin sesión 401, rol incorrecto 403.
- CA3: padre fuera de alcance se comprueba antes de listar: no devolver 200 vacío que permita distinguir existencia. Padre autorizado sin documentos devuelve página vacía. Cada listado respeta el propietario exclusivo de su ruta; no mezcla documentos de deportista y tutor ni versiones no vigentes.
- CA4: filtros escuelaId/familiaId enviados por el cliente no amplían alcance. La consulta aplica IDs y escuela a toda relación; paginación total y filas conservan el mismo predicado, sin N+1.
- CA5: DTO/OpenAPI permiten sólo campos previstos; pruebas con marcadores sintéticos demuestran ausencia de keys/URLs/nombres originales. No hay llamada a Storage, actualización de Documento ni endpoint de escritura; GET no cambia el estado de negocio.
- CA6: acceso documental autorizado se audita con identidad/escuela/recurso y código de acción, sin valores documentales; fallo al persistir auditoría impide entregar datos. Intentos denegados no generan eventos de éxito ni revelan propietario en errores. La auditoría es un efecto esperado de GET, no una mutación de Documento.
- CA7: secuencia lectura permitida → revocación confirmada → nueva lectura denegada, y sesión desactivada → nueva lectura denegada. La petición ya autorizada no se promete revocable retrospectivamente.
- CA8: pruebas focalizadas, suite normal y suite db pasan; rutas/documentación/OpenAPI actualizadas en la misma unidad. No se usan datos reales ni conexión a Supabase. No implementar UI ni medir ZIP en D1.

Pruebas previstas: JUnit servicio/DTO, WebMvc (rol/sesión/errores/contrato), Testcontainers PostgreSQL 17 con al menos dos escuelas y dos familias y pruebas del SQL real (aislamiento/total/paginación/revocación); capturar logs con marcadores ficticios. RED observado antes de implementar cada regla aplicable; GREEN y regresión de endpoints existentes que comparten alcance. Diferencial contra baseline para rutas afectadas, stdout/HTTP y estado persistido, en scratch externo al workspace. Coste de Docker se anuncia antes de lanzar suite db. Risk previsto D1: ítem 2 (acceso a metadatos sensibles); verificador independiente antes de RDD si corresponde. No declarar PASS anticipadamente.

## Tasks

| ID | Specs | Unidad propuesta | Ruta | Estado / evidencia / commit |
| --- | --- | --- | --- | --- |
| D0 | S1–S11 | Consolidar este borrador y decisiones pendientes | inline: una unidad documental comprendida | O1/O2 registradas y alcance D1 autorizado; evidencia de implementación pendiente |
| D1 | S2,S5,S7,S9,S11,S12 | Consulta autorizada de metadatos de deportista y tutor, B6 | inline; verificación independiente por ítem 2 | VERIFICADA funcionalmente; normal 903/903 y DB 415/415; verificador sin bloqueantes tras recheck; excepción de tamaño aprobada L7; PENDIENTE RDD nativo/commit |
| D2 | S3–S5 | Invariantes B3 y modelo de series, con migración/pruebas PostgreSQL | inline; verificar ítems 1/2/4 | NO AUTORIZADA; requiere P3–P6 |
| D3 | S5,S6,S8 | Carga privada síncrona completa con compensación, B5 | inline; verificar ítems 1/2/4/5 | NO AUTORIZADA; depende D2 y P2/P7/P9; dividir sólo en comportamientos seguros con tests/docs propios |
| D4 | S4,S5 | Revisión y reemplazo sin sobrescritura | inline; verificar ítems 1/2/4 | NO AUTORIZADA; depende D3 y P4 |
| D5 | S5,S7 | Gestión documental de tutores con alcance familiar | inline; verificar ítem 2 | NO AUTORIZADA para escritura; consulta de tutor incluida en D1; depende D4 |
| D6 | S6,S8 | Descarga mediada e historial, con auditoría | inline; verificar ítem 2 | NO AUTORIZADA; depende D4 y P2/P8 |
| D7 | S8 | ZIP ADMIN acotado con nombres seguros y fallos explícitos | inline; verificar ítems 2/6 si aplica | NO AUTORIZADA; depende D6; falta volumen agregado para medir <=5s/10 archivos |
| D8 | S1,S8,S9 | Portal y cierre: flujo móvil, operación segura y evidencias | inline; reevaluar riesgo real | NO AUTORIZADA; depende D5–D7 y política retención/escaneo |

Rutas son pronóstico, no delegaciones autorizadas. Cada unidad conserva código/tests/docs de su comportamiento. Sin workers de escritura por cantidad de archivos. Estimación preliminar de implementación: varios miles de líneas (supuesto no medido), excederá ~400; estrategia ask-on-risk requiere elegir entrega antes de commits de implementación que superen ese umbral. No heredar single-pr/exception-ok de Fase 2.

Hoy no hay RED ejecutable de comportamiento: sólo documento propuesto y lectura estructural. RDD está habilitado; evaluar el borrador con inclusión explícita del archivo nuevo, sin consentimientos ni PASS inventados. Futura implementación: normalizar antes de congelar candidato, pruebas y verificación proporcional, assess por unidad y revisión nativa sólo cuando corresponda; decisiones de publicación siempre humanas. No crear autoridad de revisión de todo el diseño como sustituto de aprobar requisitos.

## Log

### L1 — Pedido inicial de Fase 3, textual

Vamos a comenzar la **Fase 3: Gestión Privada de Documentos** de Banfield Patín Carrera.

Trabajá respetando ODD, RDD y las decisiones existentes del proyecto.

**Objetivo inicial: definir el diseño funcional y técnico de la fase, sin implementar código todavía.**

1. Recuperá el estado actual de `main` y revisá `docs/REQUERIMIENTOS.md`, `docs/DECISIONES.md`, `AGENTS.md`, las migraciones Flyway y el código existente relacionado con Documento, Deportista, Tutor y Familia.
2. Identificá qué entidades, campos, restricciones, servicios y endpoints ya existen, para reutilizarlos y evitar duplicaciones.
3. Definí los requisitos funcionales y no funcionales de Fase 3, con criterios de aceptación verificables.
4. Diseñá el modelo de documentos, propietarios, estados, reemplazos, metadatos y vinculación con deportistas, tutores y escuelas.
5. Especificá una política de autorización para ADMIN y FAMILIA que impida acceder a documentos de otras familias o escuelas.
6. Proponé la integración con Supabase Storage privado, tratamiento de errores, consistencia entre almacenamiento y PostgreSQL, URLs temporales y auditoría.
7. Contemplá carga PDF/JPG/PNG, validación de contenido, tamaño configurable, descarga individual y generación de ZIP.
8. Separá claramente las funcionalidades documentales de Fase 3 de la gestión de aptos físicos de Fase 4.
9. Revisá las decisiones abiertas O1 y O2. Mi preferencia es la opción A en ambas, pero todavía requieren aprobación formal.
10. Dividí la implementación en unidades ODD pequeñas, ordenadas por dependencias y con pruebas de seguridad previstas.

**Entregables:** diagnóstico del estado actual, propuesta funcional, arquitectura, riesgos, decisiones pendientes y plan de implementación.

No modificar código de producción, migraciones ni Supabase todavía. No utilizar datos personales reales, abrir Playwright, hacer push ni merge.

Primero quiero revisar el diseño antes de autorizar la implementación.

### L2 — Consolidación autorizada, textual

Revisé la propuesta de Fase 3 y apruebo su arquitectura general como base de trabajo.

Antes de implementar, vamos a consolidar las decisiones funcionales y técnicas.

Mis opciones recomendadas son:
- O1-A: FAMILIA puede gestionar documentos de tutores pertenecientes a su familia autorizada.
- O2-A: integración Supabase Storage mediante RestClient y un adaptador AlmacenDocumentos.
- OTRO: admitir múltiples documentos.
- Reemplazo: conservar historial, invalidar la vigencia anterior y exigir nueva revisión.
- Descargas: preferir mediación por backend.
- Eliminación: no implementar borrado físico arbitrario; documentar retención y eliminación segura.

Tratálas como propuestas pendientes de aprobación formal, no como decisiones ya registradas.

Definí también:
1. La matriz exacta de tipos documentales, propietarios y relación con temporada.
2. Las transiciones permitidas entre estados.
3. Las restricciones de base de datos necesarias, sin modificar V1–V4.
4. Las garantías de concurrencia y autorización, incluyendo revocación de vínculos.
5. La estrategia mínima viable de consistencia entre PostgreSQL y Storage.
6. Los criterios de aceptación y pruebas para la primera unidad ODD.

Priorizá una solución robusta pero proporcional al MVP. No implementes mecanismos complejos que todavía no estén justificados.

Prepará el documento ODD inicial y señalá las decisiones que requieren aprobación.

No modificar código de producción, migraciones ni Supabase todavía. Respetar ODD y RDD. No hacer push ni merge.

### L3 — Evidencia de preparación

Se reutilizó el relevamiento de main 8a132ee y se contrastó la tabla documental y los contratos existentes. Sólo se autoriza este documento; decisiones específicas y cualquier implementación permanecen pendientes. La propuesta reduce la consistencia inicialmente más amplia a flujo síncrono con compensación y reconciliación operativa: acepta huérfanos privados como riesgo explícito, no inconsistencia publicada. No se ejecutaron pruebas funcionales ni consultas a Supabase. No se modificaron decisiones oficiales, código, migraciones ni estados del roadmap.

### L4 — Autorización de primera unidad y decisiones formales (2026-11-03), textual

Vamos a avanzar con la Fase 3 de Documentos Privados.

Apruebo formalmente estas decisiones de diseño:
- O1-A: FAMILIA puede gestionar documentos de tutores pertenecientes a su familia autorizada.
- O2-A: Supabase Storage mediante RestClient, encapsulado en AlmacenDocumentos.

Registrá ambas en docs/DECISIONES.md respetando ODD.

Apruebo como base conceptual la propuesta de estados y reemplazos, pero las transiciones y restricciones definitivas deberán verificarse antes de sus respectivas migraciones.

La vigencia anual de WADA, la política de retención, el escaneo antimalware y los límites operativos permanecen pendientes. No los presentes como decisiones cerradas.

**Autorizo comenzar la primera unidad ODD: consulta autorizada de metadatos documentales.**

Alcance:
1. Preparar una rama feature desde main, conservando el documento ODD ya creado.
2. Reutilizar gestion_patin.documento sin duplicar tablas.
3. Implementar exclusivamente las entidades, repositorios, servicios y endpoints necesarios para consultar metadatos.
4. ADMIN consulta documentos de su escuela.
5. FAMILIA consulta únicamente documentos de deportistas vinculados de forma activa y tutores de su familia autorizada.
6. Verificar aislamiento entre escuelas, familias y propietarios, incluyendo vínculos revocados.
7. No exponer storage_key, bucket, URLs internas ni información innecesaria en los DTO.
8. Incorporar pruebas de autorización, contratos API, OpenAPI y regresión con PostgreSQL/Testcontainers.
9. Respetar ODD, RDD y el esquema existente. Priorizar consultas eficientes y evitar N+1.

No integrar Supabase Storage, implementar cargas, descargas, reemplazos ni modificar migraciones en esta unidad.

Al finalizar, informá endpoints implementados, pruebas, decisiones técnicas y riesgos. No hacer push ni merge sin autorización.

Ruta D1: implementación inline, una sola unidad de consulta con tests/docs; no hay unidades independientes de escritura paralela. Risk: ítem 2 (metadatos privados y aislamiento). Verificador independiente después de checks propios. El mapeo reutiliza la verificación de fundamentos y una comprobación de puntos transversales, no delega la escritura. Estimación D1 superior a 400 líneas contando pruebas/documento; entrega sigue ask-on-risk, estrategia pendiente antes de commit.

### L5 — Implementación inicial y verificaciones propias (2026-11-03)

O1/O2 registradas sin cerrar WADA anual, retención, escaneo ni límites. D1 implementada inline como proyecciones DTO con consultas/predicados idénticos en datos/count y auditoría de metadatos; sin cambios de migraciones, Storage o endpoints mutantes. Se reutilizan el alcance familia-deportista y errores/seguridad/paginación del proyecto. La entidad documental es inmutable, parcial y sin metadatos Storage.

RED: DocumentosRutasWebMvcTest, 2 fallos reales esperado 200/obtenido 404 antes de implementar. Primer intento aislado no fue RED funcional: Mockito no pudo adjuntar agente en sandbox; se repitió con permisos y se observó el fallo HTTP. GREEN focalizado sin Docker: 73 pruebas, 0 fallos/errores (servicio, rutas documentales, OpenAPI, inventarios y regresión portal/tutores).

Pruebas PostgreSQL: DocumentosConsultaDbTest no pudo iniciar; socket /var/run/docker.sock ausente incluso fuera del sandbox. No se sustituyó por H2 ni se afirmó validación del SQL/aislamiento. No reintentar hasta que cambie el entorno. Preparadas 7 pruebas contra PostgreSQL 17 para escuelas/familias/propietarios, estados de vínculo/revocación, tutor/familia activos, páginas vacías/fuera de rango, no N+1 y ausencia de cambios en Documento/valores Storage. Verificación independiente y suite normal completa pendientes en esta entrada. Commit pendiente de pruebas y estrategia ask-on-risk; sin push/merge.

### L6 — Verificación independiente y corrección acotada (2026-11-03)

Docker pasó a estar disponible durante la verificación. El verificador independiente trabajó en scratch externo a partir de baseline 8a132ee y el diff del candidato, sin editar el workspace. Suite normal `./mvnw -o clean verify`: 903 pruebas, 0 fallos/errores/omitidos. Diferencial de contratos existentes TutorAdminControllerWebMvcTest y FamiliaPortalControllerWebMvcTest: 35 baseline y 35 candidato, sin regresión observada.

PostgreSQL focalizado reprodujo 1 fallo de fixture: el bucle insertaba para ADMIN y después esperaba vacía la misma carpeta para FAMILIA. Único lote correctivo: ambas comprobaciones de vacío antes de insertar una sola vez; se agregó invariante de snapshot Documento sin cambios tras lecturas denegadas. Parent reejecutó el comando: 7/7. Recheck independiente limitado al defecto: 7/7; `./mvnw -o verify -Pdb-tests` completo: 415/415, sin fallos/errores/omitidos. Ningún defecto de producción reproducido en esta revisión acotada; no equivale a ausencia absoluta de riesgos. Sin acceso a Supabase ni datos reales.

Assess RDD nativo del candidato: high por ítem 2; revisión nativa/consentimiento aún no iniciados ni aprobados. D1 no se declara cerrada mientras falte RDD. Plan ask-on-risk: diff de 22 archivos y más de 900 líneas, incluidas más de 260 del documento ODD previo. Una separación sólo por capas rompería la unidad de comportamiento; mantener consultas de ambos propietarios y su matriz de pruebas juntas requiere aceptar tamaño de esta unidad o elegir una división funcional antes de commit. Ninguna excepción de Fase 2 se hereda. No hay commit, push ni merge.

### L7 — Excepción exclusiva D1 y preparación RDD (2026-11-03)

**Instrucción del usuario, textual:**

> Autorizo la excepción de tamaño exclusivamente para la unidad D1 de Fase 3: 22 archivos y 952 líneas del candidato actual.
>
> Conservá juntos comportamiento, pruebas y documentación, sin ampliar el alcance funcional.
>
> Continuá con el protocolo ODD/RDD:
>
> 1. Registrá la excepción y su justificación en el plan ODD.
> 2. Prepará la revisión RDD nativa de alto riesgo, priorizando autorización, aislamiento entre escuelas y familias, vínculos revocados, filtración de metadatos, paginación y consultas eficientes.
> 3. Si RDD exige consentimiento específico, presentá el candidato y solicitá esa autorización antes de ejecutar la revisión.
> 4. No realices el commit hasta completar satisfactoriamente RDD y resolver cualquier bloqueante.
> 5. Conservá los resultados de las verificaciones funcionales ya realizadas y repetí las pruebas afectadas si cambia el código.
>
> No incorporar Storage, nuevas migraciones ni funcionalidades fuera de D1.
>
> No hacer push ni merge.
>
> Informame el resultado de RDD y si D1 queda preparada para commit.

**Decisión y justificación:** excepción `exception-ok` sólo para D1; el candidato inicial se verificó como 22 archivos, +943/−9 = 952 líneas respecto de `8a132ee`. Se mantienen juntas las cuatro consultas y su matriz de autorización, paginación, regresión y documentación; dividir sólo por capas separaría comportamiento de sus pruebas. El registro de esta autorización agrega únicamente documentación en el mismo archivo ODD, no nuevas rutas ni funcionalidad. Otras unidades conservan su evaluación de tamaño independiente.

**Preparación:** se conservan íntegros los resultados L5–L6: suite normal 903/903, PostgreSQL 415/415, focalizada DB 7/7, diferencial 35/35 por versión y spot-check de servicio 4/4. En esta continuación no cambió el código funcional; no se repiten suites por una anotación documental. Los logs temporales antiguos ya no están disponibles; la evidencia conservada es el registro ODD y su espejo, no una nueva ejecución. Normalización: no hay formateador mutante configurado en `backend/pom.xml`; comprobación de whitespace con `git diff --check` y `git diff --cached --check` antes de congelar. Revisión nativa pendiente de su consentimiento específico; no se autoriza a inferirlo de la excepción de tamaño. Foco de revisión: autorización/aislamiento, revocación, DTO sin metadatos Storage, fallos de auditoría, paginación y consultas acotadas. Risk: ítem 2. No commit, push ni merge.
