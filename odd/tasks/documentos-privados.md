# Fase 3 — Gestión privada de documentos
Objetivo actual: B1 de D3b, registro SQL durable sin mutadores ni Storage. D3a integrada por PR #11, merge `85f354636e3ae0c44fa2e94638393a97e5002886`; historial anterior conservado.
Rama B1: `feat/operaciones-carga-documental`, desde main actualizado. Entrega `ask-on-risk`; pronóstico 400–600 líneas, sin excepción de tamaño heredada y sin commit antes de verificaciones/RDD. Runner: `cd backend && ./mvnw -o clean verify` y `./mvnw -o verify -Pdb-tests` (PostgreSQL 17 local). Sin push/PR/merge B1 ni despliegue.

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
- **S13 (D2, L8).** "Objetivo: reforzar la integridad de `gestion_patin.documento` mediante una nueva migración Flyway, sin implementar todavía reemplazos ni integración con Storage." Las decisiones y condiciones exactas de L8 sustituyen B3 sólo para D2. No editar V1–V4 ni adaptar D1 a escritura.
- **S14 (excepción operativa D2, L9).** "utilizar `spawn_agent` y `wait_agent` sin `list_agents`" / "Conservá una verificación independiente real, realizada por un agente distinto del implementador." / "No hacer commit antes de completar satisfactoriamente las verificaciones y RDD." No se extiende a D3.
- **S15 (corrección CI PR #10, L15).** "Vamos a diagnosticarlos y corregir únicamente las causas demostradas." / "No desactives CSRF, no cambies expectativas sin justificación ni debilites las pruebas de seguridad." / "Contrastá `main` y la rama del PR en condiciones equivalentes para determinar si hay una regresión nueva o un fallo preexistente." / "No modificar V5 ni ejecutar migraciones en Supabase o bases compartidas." / "No hacer push, merge ni despliegue todavía."
- **S16 (verificación CI, L15).** "Ejecutá las suites relevantes y la revisión independiente exigida por ODD." / "Aplicá RDD al candidato corregido; solicitá consentimiento si corresponde." Excepción operativa autorizada para esta corrección mediante respuesta textual "si autorizo": spawn_agent/wait_agent sin list_agents, con resultado terminal real; no constituye cumplimiento literal del protocolo habitual y no se extiende a D3.

- **S17 (D3a, L17).** "Definí una interfaz propia `AlmacenDocumentos`." / "Implementá un adaptador Supabase Storage con `RestClient`, conforme a la decisión O2-A." / "Diseñá las operaciones mínimas de almacenamiento privado y eliminación compensatoria de objetos recién creados." / "Usá claves opacas generadas por el backend. No aceptes rutas arbitrarias ni nombres de archivo como identificadores de almacenamiento." / "Garantizá que las cargas futuras no sobrescriban objetos existentes."
- **S18 (D3a, L17).** "Configurá URL, bucket, credenciales y timeouts desde configuración externa." / "Nunca expongas claves de servicio, URLs públicas ni rutas internas al frontend." / "Definí errores tipados y controlados para fallos de red, timeouts, autenticación, respuestas inesperadas y estado remoto incierto." / "No registres tokens, nombres personales, contenido documental ni respuestas sensibles del proveedor en logs."
- **S19 (D3a, L17).** "Implementá pruebas automatizadas contra un servidor HTTP simulado, incluyendo fallos y casos límite. No conectes todavía con Supabase real." / "Aplicá ODD con pruebas RED/GREEN." / "Ejecutá las suites normal y PostgreSQL." / "Realizá verificación independiente proporcional al riesgo." / "Ejecutá assess RDD y solicitá consentimiento si corresponde."
- **S20 (D3a, L17).** "Registrá D3a como unidad diferenciada de D3b en ODD, sin heredar excepciones de tamaño o delegación anteriores." / "No hacer commit antes de completar los controles exigidos. No hacer push, PR ni merge sin autorización posterior." El resto de las restricciones literales de L17 forma parte del alcance. Excepciones operativas nuevas y exclusivas: spawn/wait sin list_agents y explorador gpt-6-luna/high, autorizadas por dos respuestas "autorizo"; no constituyen cumplimiento literal del protocolo habitual.

- **S21 (B1, L20).** "Objetivo: implementar `V6__registrar_operaciones_carga_documental.sql`, pruebas PostgreSQL 17 y documentación ODD." / "Una tabla `gestion_patin.operacion_carga_documental`." / "Manifiesto validado estructuralmente y de asignación única." / "Estados RESERVADA, TRANSFIRIENDO, ALMACENADA, PUBLICADA, RECONCILIANDO y CERRADA."
- **S22 (B1, L20).** "Exigí versión esperada para las transiciones operativas y comprobación de filas afectadas." / "Rechazá publicaciones con contexto documental incompatible, incluyendo temporada nullable y destino Storage." / "No interpretes el vencimiento de una reserva como confirmación de que terminó una transferencia externa."
- **S23 (B1, L20).** "Usá pruebas RED/GREEN, migración limpia, actualización desde V5 y pruebas concurrentes con conexiones PostgreSQL reales." / "Ejecutá regresiones de D1, D2 y D3a, las suites completas y la verificación independiente exigida por ODD." / "Ejecutá assess RDD y solicitá consentimiento para el candidato si corresponde."
- **S24 (B1, L20).** "No modificar V1–V5." / "No implementar repositorios mutadores, endpoints, cargas, scanner, integración real con Supabase ni reconciliación." / "No realizar commit de B1 antes de completar verificaciones y RDD. Cualquier excepción de tamaño requiere autorización específica." Excepción operativa NUEVA B1: el usuario respondió "autorizo" a spawn_agent/wait_agent sin list_agents, manteniendo verificación independiente real y RDD completo; no constituye cumplimiento literal del protocolo habitual ni se extiende a otras unidades.

- **S25 (diagnóstico B1-C, L21).** "Identificar y corregir la causa raíz demostrable sin modificar comportamiento funcional ni debilitar pruebas." / "Inspeccioná las evidencias existentes antes de ejecutar nuevas pruebas." / "Contrastá baseline y rama B1 bajo condiciones equivalentes." / "Capturá evidencia suficiente para establecer causalidad, no solo correlación."
- **S26 (B1-C, L21).** "No introduzcas sleeps arbitrarios, reintentos ciegos ni cambios de expectativa." / "No desactives tests, restricciones ni verificaciones de seguridad." / "No modifiques las migraciones V1–V6." / "Si el fallo no puede atribuirse a una causa demostrable, no implementes correcciones especulativas. Presentá el diagnóstico y las alternativas."
- **S27 (B1-C, L21).** "Ejecutá repetidamente la prueba afectada para comprobar estabilidad." / "Ejecutá todas las pruebas de `V5OperacionDbTest`." / "Repetí las 57 pruebas B1." / "Ejecutá las suites completas normal y PostgreSQL." / "Realizá una verificación independiente del diagnóstico y la corrección." / "No iniciar RDD hasta completar satisfactoriamente la verificación funcional." / "No solicitar excepción de tamaño todavía."

- **S28 (verificabilidad B1, L22).** "Cerrar la evaluación de verificabilidad de B1 y, si las reglas ODD lo permiten, realizar RDD sobre el candidato definitivo." / "Mantené B1-C registrada como incidencia intermitente sin causa raíz demostrada." / "Diferenciá claramente entre verificación funcional aprobada e incidencia ambiental no resuelta." / "Si B1-C constituye un bloqueo obligatorio según ODD, no lo ignores: explicá la regla y detené el avance."
- **S29 (RDD B1, L22).** "Recalculá el inventario exacto de archivos, líneas y hash del candidato, incluyendo los últimos cambios documentales." / "Ejecutá el assess nativo RDD sobre el candidato completo." / "Solicitá el consentimiento correspondiente si el protocolo lo requiere." / "Realizá una revisión independiente con las lentes pertinentes a persistencia, seguridad, aislamiento y concurrencia."
- **S30 (límites RDD B1, L22).** "Corregí exclusivamente defectos demostrados, siguiendo RED/GREEN y reevaluando el candidato si cambia." / "No modificar V1–V6 salvo que RDD demuestre un defecto y exista autorización específica para corregirlo." / "No hacer commit, push, PR ni merge." / "No solicitar todavía excepción de tamaño." / "Si el protocolo exige consentimiento o intervención humana, detenete en ese punto."

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

### D2 — alcance aprobado de integridad (prevalece sobre propuestas B1/B3)

- DNI frente/dorso de deportista o tutor: propietario compatible, sin temporada, una fila vigente por propietario/tipo. OTRO admite múltiples series, una vigente por serie. SOLICITUD_LICENCIA/WADA: sólo unicidad por serie; no anualidad ni cardinalidad por temporada. APTO_FISICO conserva las reglas existentes, sin restricciones específicas de Fase 4.
- Toda cadena mantiene escuela, propietario, tipo, temporada y serie. Sin autorreferencias, ciclos, bifurcaciones ni cambio de contexto histórico. Prevalidación de V4 antes de DDL/backfill; conflictos abortan, sin reparar o elegir ganadores. Serie de una cadena = ID de su raíz, conservado por todos sus descendientes.
- Soporte `version` SQL no negativo con valor inicial 0; el CAS futuro será responsabilidad del mutador autorizado. D1 sigue inmutable, sin exponer nuevas columnas en DTO.
- V5 confirmada disponible sobre baseline `b077021`. Ruta inline: una unidad de migración/tests/docs; no hay escritores paralelos. Verificación independiente obligatoria por Risk: ítems 1/2/4. Excepción de tamaño D1 no heredada; cualquier tamaño D2 se evalúa antes de commit.
- Excepción operativa autorizada por el usuario exclusivamente para D2: usar spawn_agent/wait_agent sin list_agents **no constituye cumplimiento literal del protocolo habitual**. Identificador, tarea, resultado terminal de wait_agent y conclusión se registrarán en el Log. Si no se obtiene conclusión terminal, detener D2. RDD nativo sin excepción.
- Verificación: RED de invariantes antes de V5; PostgreSQL 17/Testcontainers local descartable (incluidas dos conexiones concurrentes, no sleeps); regresión D1 y suites `./mvnw -o clean verify`, `./mvnw -o verify -Pdb-tests`. Sin Supabase, Storage, datos reales ni mutadores. Pruebas de migración reutilizan PostgresDescartable/DatosDb y no alteran la BD compartida de pruebas.

## Tasks

| ID | Specs | Unidad propuesta | Ruta | Estado / evidencia / commit |
| --- | --- | --- | --- | --- |
| D0 | S1–S11 | Consolidar este borrador y decisiones pendientes | inline: una unidad documental comprendida | O1/O2 registradas; diseño consolidado y D1/D2 cerradas según L14 |
| D1 | S2,S5,S7,S9,S11,S12 | Consulta autorizada de metadatos de deportista y tutor, B6 | inline; verificación independiente por ítem 2 | CERRADA LOCALMENTE en b077021; RDD nativo cuatro lentes sin hallazgos, reconocido; normal 903/903 y DB 415/415; no reabrir revisión de contenido idéntico |
| D2 | S3–S5,S13,S14 | Integridad acotada aprobada en L8, V5 y pruebas PostgreSQL/concurrencia | inline; verificador independiente con excepción operativa D2 | CERRADA LOCALMENTE en 82ff948; 903 normales/440 DB, verificación independiente y spot-check 20/20; RDD reconocido y delta documental evaluado; tamaños autorizados L13/L14 |
| CI1 | S15,S16 | Causas demostradas de CI: aislamiento CSRF de pruebas y aserción estructurada de auditoría | inline; diagnóstico/verificación independientes por ítems 2/5, excepción CI específica | VERIFICADA FUNCIONAL, INDEPENDIENTE Y RDD; RED reproducido; GREEN 37/37 y 6/6, suites 903/903 y 440/440; RDD tres lentes sin hallazgos y reconocimiento confirmado en L16; sin commit/push |
| D3a | S17–S20 | Puerto y adaptador privado, errores/configuración y HTTP simulado | implementación inline; mapa delegado por presupuesto; verificador independiente por ítem 2 | VERIFICADA funcional e independientemente en L17: 50 específicas, 953 normales, 451 DB y 4 probes; explorador/verificador terminados por wait_agent, paths-injected; RDD cuatro lentes sin hallazgos reconocido en L18; commit pendiente de decisión de tamaño/entrega |
| B1 | S21–S24,S28–S30 | Registro SQL durable: V6, pruebas y documentación | implementación inline; verificación independiente por ítems 1/2/4 | VERIFICADA FUNCIONAL, INDEPENDIENTE Y RDD L22: 57 B1, 953 normales vigentes, 508 DB; cuatro lentes sin hallazgos y reconocimiento nativo confirmado; B1-C abierta; delta documental de cierre se evalúa aparte, sin commit ni excepción de tamaño |
| B1-C | S25–S27,S28 | Diagnóstico y corrección causal del arranque local bloqueante | inline; verificación independiente por entorno/concurrencia | ABIERTA, diagnóstico INCONCLUSO L21; causalidad pendiente, sin corrección; L22 separa esta incidencia histórica de la verificación funcional actual aprobada, sin declararla resuelta |
| D3b | S5,S6,S8 | Flujo de carga documental con autorización, publicación y compensación integral | reevaluar al autorizar | NO AUTORIZADA; depende D3a y decisiones P6/P7/P9 y límites operativos; sin endpoints en D3a |
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

### L8 — Implementación D2 autorizada (2026-11-03), textual

Apruebo las decisiones acotadas de D2 y autorizo su implementación.

**Objetivo:** reforzar la integridad de `gestion_patin.documento` mediante una nueva migración Flyway, sin implementar todavía reemplazos ni integración con Storage.

Decisiones:
- DNI frente/dorso: como máximo uno vigente por propietario y tipo, sin temporada.
- OTRO: múltiples series independientes, con máximo un vigente por serie.
- SOLICITUD_LICENCIA y CERTIFICADO_WADA: unicidad por serie; no imponer anualidad ni cardinalidad por temporada todavía.
- APTO_FISICO: no agregar reglas específicas de Fase 4.
- Historial: garantizar que el predecesor pertenezca a la misma escuela, propietario, tipo, temporada y serie.
- Control optimista: incorporar soporte SQL, sin adaptar todavía D1 a escritura.

Implementación:

1. Actualizá el plan ODD con las decisiones aprobadas y las diferidas.
2. Confirmá que `V5__reforzar_integridad_documental.sql` esté disponible.
3. Implementá una migración que prevalidate los registros existentes, conserve las cadenas históricas válidas y rechace inconsistencias sin realizar reparaciones automáticas.
4. Agregá `serie_id`, la versión y las restricciones necesarias. Asigná una misma serie a todas las versiones de una cadena existente.
5. Priorizá CHECK, FK e índices únicos. Utilizá un trigger acotado únicamente para las invariantes entre versiones que no puedan expresarse declarativamente.
6. Garantizá que no existan autorreferencias, ciclos, bifurcaciones, modificaciones indebidas del contexto histórico ni múltiples documentos vigentes en una misma serie.
7. Comprobá que los mecanismos de integridad funcionan bajo concurrencia real, no solamente en pruebas secuenciales.
8. Implementá pruebas PostgreSQL 17/Testcontainers: migración limpia, actualización desde V4, datos históricos inconsistentes, versiones cruzadas, cardinalidad y concurrencia.
9. Ejecutá la regresión completa de D1 y verificá que los endpoints de consulta mantengan su contrato y autorización.
10. Realizá verificación independiente y seguí el protocolo RDD.

No editar V1–V4, implementar mutadores documentales, integrar Supabase Storage ni ampliar el alcance a Fase 4.

No hacer push ni merge.

Al finalizar, informá los archivos modificados, pruebas ejecutadas, riesgos residuales y si D2 está preparada para revisión y commit. Solicitá consentimiento RDD si corresponde.

### L9 — Excepción operativa exclusiva D2 (2026-11-03), textual

Autorizo una excepción operativa **únicamente para D2**: utilizar `spawn_agent` y `wait_agent` sin `list_agents`.

Condiciones obligatorias:

1. Registrá explícitamente la excepción en el plan ODD, indicando que fue autorizada por el usuario y que no constituye cumplimiento literal del protocolo habitual.
2. Conservá una verificación independiente real, realizada por un agente distinto del implementador.
3. Registrá el identificador del agente delegado, su tarea, el resultado obtenido mediante `wait_agent` y su conclusión.
4. No simules herramientas faltantes ni declares completada una revisión que no se ejecutó.
5. Si no podés verificar que el agente terminó y entregó resultados, detené D2.
6. Implementá D2 según el alcance y las restricciones previamente aprobadas.
7. Ejecutá las pruebas PostgreSQL/Testcontainers, las regresiones de D1 y la verificación independiente.
8. Aplicá posteriormente el RDD nativo completo, sin excepciones adicionales.
9. Si RDD requiere consentimiento, solicitá autorización específica.

Esta excepción no se extiende a D3 ni a futuras unidades.

No hacer commit antes de completar satisfactoriamente las verificaciones y RDD. No hacer push ni merge.

Al finalizar, presentá las pruebas realizadas, los hallazgos de la verificación independiente, los riesgos residuales y el estado de D2.


### L10 — Implementación D2 y checks propios (2026-11-03)

V5 libre confirmada. Se implementaron prechecks transaccionales bajo lock exclusivo y backfill de cadena con raíz estable, preservando columnas/timestamps V4. CHECK para DNI/propietario/temporada y versión; dos FK compuestas por rama de propietario garantizan escuela/tipo/serie sin tratar NULL como igualdad; índices únicos para serie vigente, sucesor y posición DNI. Trigger limitado a derivación de serie, temporada nullable e inmutabilidad del contexto. Sin reglas nuevas de estados, anualidad WADA, APTO Fase 4 ni mutadores. Ningún Java productivo cambió.

RED observado: `./mvnw -o test -Pdb-tests -Dtest=V5IntegridadDocumentalDbTest#propietarioYCardinalidadDni`, 1 fallo de aserción real en V4 (aceptaba propietario incompatible), 0 errores. No se afirma RED separado de cada caso posterior. GREEN final focalizado: V5 21/21 y DocumentosConsultaDbTest 7/7, total 28/28. Cuatro carreras usan dos conexiones y verifican el bloqueo PostgreSQL mediante `pg_blocking_pids`, sin sleeps: alta DNI duplicada, sucesores aunque no vigentes, activaciones en la misma serie y CAS con versión obsoleta. Rechazos verifican SQLSTATE de integridad y snapshot de Documento sin cambios; migraciones inválidas conservan V4, sin columnas nuevas parciales. Pruebas sólo contra PostgreSQL 17.11/Testcontainers local. Logs externos `/tmp/d2-red.log` y `/tmp/d2-focused-final.log`.

Pendiente: suites completas normal/DB y verificación independiente en copia scratch externa, registro terminal del agente mediante wait_agent, spot-check del padre y RDD nativo con consentimiento. El candidato puede superar 400 líneas; no se hereda la excepción de D1 ni se hace commit sin resolver tamaño y RDD. Risk: ítems 1/2/4. Sin Supabase, push ni merge.

### L11 — Verificador independiente D2 y corrección acotada (2026-11-03)

Agente distinto del implementador: `01a1a27d-a6da-7643-8435-9f7bbd2ff519` (tarea `d2_integridad_verify`, gpt-6.1-sol/medium). `wait_agent` devolvió estado terminal `completed` con informe PARTIAL y `skill_resolution=paths-injected`; no se simuló list_agents. Scratch externo `/tmp/d2-integridad-verify.0FdLz4`, baseline b077021. Resultado: suite normal 903 (1 fallo de inventario), DB 436 (1 inventario y 1 advisory previo), V5 21/21, D1 DB 7/7 y contratos/servicio D1 12/12 en ambas versiones. Probes adicionales 4/4: tutor/series múltiples, rollback de sentencia masiva, ciclo por INSERT masivo y no nuevas reglas APTO. No reprodujo defecto SQL productivo en lo examinado; no prueba ausencia absoluta de riesgos.

Dos bloqueantes del candidato: inventario V2MigracionEstaticaTest limitado a V1–V4 e historial V4ConstraintsDbTest limitado a 1–4; ambos pasan en baseline. Único lote correctivo: actualizar inventarios a V5 (sin tocar migraciones V1–V4) y conservar los probes como V5CasosLimiteDbTest. No se cambia SQL ni Java productivo. Advisory previo: DeportistaTemporadaEdicionDbTest busca `009` en el JSON completo y puede coincidir en un UUID, misma aserción en baseline; no se corrige fuera de alcance. Recheck independiente limitado a los dos bloqueantes pendiente; regresiones funcionales finales pendientes. Sin commit ni RDD ejecutado todavía.

### L12 — Recheck independiente y estado previo a RDD (2026-11-03)

Mismo agente `01a1a27d-a6da-7643-8435-9f7bbd2ff519`, tarea `d2_integridad_verify`. Retorno terminal de wait_agent: `completed`, conclusión «Recheck D2 satisfactorio», skill_resolution paths-injected. Recheck limitado a los dos bloqueantes: inventario estático 7/7 e historial Flyway 20/20; probes conservados equivalentes 4/4. Sin nuevo barrido. Regresión funcional del candidato corregido en nueva copia externa desde b077021: `./mvnw -o clean verify` 903/903 y `./mvnw -o verify -Pdb-tests` 440/440, cero fallos/errores/omitidos; incluye V5 21/21 y D1 DB 7/7. Advisory previo UUID intacto, sin fallo ni reintentos en esta corrida. Spot-check del padre: `./mvnw -o test -Pdb-tests -Dtest=V4ConstraintsDbTest` 20/20. V1–V4 y todo Java productivo sin diferencias contra b077021.

Los logs y reportes de ambas copias externas se preservan en `/tmp/d2-verificacion-evidencia/`; scratches originales retirados después de preservar evidencia. No se afirma cumplimiento literal de list_agents: se usó la excepción D2 autorizada. No se amplía a D3. La revisión independiente verificó S13 (integridad/regresión) y su retorno terminal permite cerrar la condición independiente de S14; RDD nativo aún pendiente de consentimiento. No se repite revisión D1. Whitespace check-only conforme; no normalizador mutante configurado. No commit: tamaño D2 >400 requiere decisión propia antes de commit; excepción D1 no heredada.

Riesgos residuales: lock exclusivo durante migración requiere ventana operativa; historia inválida exige resolución humana, no reparación automática; `version` sólo ofrece soporte SQL, no workflow optimista de D1; los permisos administrativos capaces de desactivar restricciones están fuera de la garantía. No retención/borrado, anualidad WADA, Storage ni nuevas reglas de Fase 4.

### L13 — Excepción de tamaño y cierre local D2 (2026-11-03)

**Autorización del usuario, textual:**

Autorizo la excepción de tamaño exclusivamente para D2: 7 archivos y 619 líneas como una única unidad.

Continuá con el cierre local:

1. Registrá la excepción y su justificación en ODD.
2. Verificá que el contenido ejecutable coincida con el candidato aprobado por RDD: `sha256:d020d19e1f21434e677effc1db5b7001258bd7b9e452abca642476e20c767c62`.
3. Si registrar la excepción modifica el candidato, aplicá el protocolo ODD/RDD correspondiente; no supongas que una aprobación anterior cubre automáticamente los nuevos bytes.
4. Confirmá que las verificaciones funcionales e independientes siguen siendo aplicables.
5. Realizá el commit local con mensaje Conventional Commits en español:
   `feat(documentos): reforzar integridad documental en PostgreSQL`
6. Verificá el SHA del commit y que el árbol de trabajo quede limpio.
7. Informá el estado final de D2 y los riesgos residuales.

No implementar D3 ni modificar Supabase.

**No hacer push, abrir PR ni merge todavía.**

**Justificación:** la migración, las pruebas de integridad/concurrencia y la documentación forman una única unidad coherente; se acepta su tamaño sólo para D2, sin heredar ni extender excepciones. El candidato original (árbol `02d91cbadd0a67781c36685107e7e32a5c58f705`) recibió RDD nativo de cuatro lentes sin hallazgos, reconocido en `review-496f961de3ae3901`. Este registro agrega bytes documentales: no se considera automáticamente cubierto por aquella aprobación. Antes del commit se evaluará el nuevo candidato conforme al protocolo nativo. Las pruebas 903 normales/440 PostgreSQL, verificación independiente terminal y spot-check 20/20 siguen siendo aplicables al contenido ejecutable idéntico; no se afirma una nueva ejecución. Riesgos residuales L12 sin cambios. Commit todavía pendiente.

### L14 — Cierres consolidados y Draft PR conjunto autorizado (2026-11-03)

Las entradas anteriores conservan su carácter histórico: los estados «pendiente» de L5–L13 describen cada instante, no el estado actual. D1 cerró en `b077021ebf5b2513c7f97143a70f3ce646036e57`, con RDD `review-1740248c823dbef7`, cuatro lentes sin hallazgos y reconocimiento confirmado. D2 cerró en `82ff948dc6b794cfb5fbb9534e9650e76ead50af`, con RDD `review-496f961de3ae3901`, cuatro lentes sin hallazgos y reconocimiento confirmado. Los bytes ejecutables D2 coinciden con su candidato aprobado; el delta posterior exclusivamente documental recibió assess `medium`, `review_due=false`, `under_budget`, sin atribuirle cobertura automática de la revisión anterior. Los mensajes de commit conservan esta trazabilidad.

**Autorización del usuario, textual:**

Apruebo la integración propuesta de D1 y D2 de Fase 3 en un único Draft PR.

Autorizo la excepción de tamaño acumulada: 27 archivos y 1.615 líneas, más los ajustes documentales mínimos necesarios en el mismo archivo ODD.

Procedé de la siguiente manera:

1. Actualizá `odd/tasks/documentos-privados.md` para reflejar los cierres reales de D1 y D2, conservando la trazabilidad histórica.
2. Registrá la excepción de tamaño del PR conjunto y su justificación.
3. Ejecutá el assess RDD para el ajuste documental. No repitas revisiones aprobadas sobre código idéntico.
4. Si corresponde, creá un commit documental Conventional Commits en español.
5. Verificá que el diff acumulado contra `main` siga limitado a D1, D2, migración V5, pruebas y documentación.
6. Confirmá árbol limpio, trazabilidad de commits y ausencia de secretos.
7. Verificá el remoto y la autenticación de GitHub.
8. Si no hay bloqueantes, hacé push sin force y abrí un único Draft PR contra `main`.
9. Incluí la excepción de tamaño, resultados 903/440, verificación independiente y RDD.
10. Documentá expresamente los riesgos operativos de V5: bloqueo durante la migración, posibles datos históricos incompatibles, necesidad de respaldo y recuperación, y ausencia de validación sobre bases compartidas.
11. Verificá los checks de GitHub Actions y presentá su estado.

No ejecutar migraciones en bases compartidas ni desplegar.

No implementar D3, cargas o descargas ni modificar Supabase.

**No marcar el PR como listo para revisión final ni hacer merge sin nueva autorización.**

Si aparece un cambio funcional inesperado o un bloqueo de CI, detenete e informalo.

**Excepción y justificación:** el usuario acepta expresamente un único Draft PR hacia main con D1/D2, 27 archivos/1.615 líneas más este ajuste ODD. Se conservan juntas las consultas autorizadas, la migración de integridad y sus pruebas/documentación; no se elimina evidencia ni se divide por capas para reducir el tamaño. Esta excepción acumulada es propia y no se deduce de las individuales. La excepción operativa spawn/wait sin list_agents sigue limitada a D2 y no se extiende a D3.

**Verificación aplicable y operación:** últimas suites locales 903 normales/440 PostgreSQL sin fallos/errores/omitidos; incluye regresión D1, V5 y concurrencia real. Verificación independiente D2: agente `01a1a27d-a6da-7643-8435-9f7bbd2ff519`, retorno terminal comprobado con wait_agent, dos inventarios corregidos y recheck satisfactorio; no hay bloqueantes pendientes reproducidos de D1/D2. El ajuste actual sólo modifica este documento; se evaluará por assess antes del commit, sin repetir revisiones de código idéntico. CI disponible: Suite normal y Suite db (PostgreSQL 17), sin despliegue ni Supabase. El estado remoto de esos checks se informará después de crear el Draft, no se presupone aprobado.

**Riesgos de despliegue, no autorización para ejecutar:** V5 toma ACCESS EXCLUSIVE y puede esperar/bloquear lecturas y escrituras. Antes de un despliegue se necesita ventana operativa, presupuesto de espera/ejecución, respaldo verificable y procedimiento de recuperación ensayado. Historia incompatible aborta sin reparación automática; exige resolución humana autorizada. No se inspeccionó ni validó una base compartida ni se ejecutó allí la migración. Revertir el commit no deshace una V5 aplicada; recuperación de base requiere un plan aprobado independiente. Version es sólo soporte SQL, no mutadores optimistas; no se aprueban anualidad WADA, reglas nuevas de Fase 4, retención, escaneo o Storage.

### L15 — Corrección acotada de CI PR #10 (2026-11-03)

**Pedido del usuario, textual:**

El Draft PR #10 presenta fallos de CI. Vamos a diagnosticarlos y corregir únicamente las causas demostradas.

Revisé los logs remotos. La suite normal muestra:
- `OpenApiRutasTest`: espera `X-CSRF-TOKEN`, pero OpenAPI declara `X-XSRF-TOKEN`.
- `OpenApiEstadosRealesTest`: nueve errores por cookie CSRF nula.
- `OpenApiExposicionSinDevTest`: un error por cookie CSRF nula.

La configuración `SeguridadConfig` establece `CookieCsrfTokenRepository`, por lo que debemos explicar la discrepancia antes de modificar nada.

**Trabajo autorizado:**

1. Inspeccioná los logs completos de CI, `SeguridadConfig`, `CsrfController`, `BaseOpenApiWebMvc` y las pruebas afectadas.
2. Compará la ejecución local y GitHub Actions, incluyendo perfiles Spring, configuración CSRF, autoconfiguraciones y dependencias.
3. Reproducí primero los fallos específicos y luego la suite con el comando de CI.
4. Contrastá `main` y la rama del PR en condiciones equivalentes para determinar si hay una regresión nueva o un fallo preexistente.
5. Determiná por qué el endpoint `/api/auth/csrf` devuelve una cabecera distinta y no genera la cookie esperada.
6. No desactives CSRF, no cambies expectativas sin justificación ni debilites las pruebas de seguridad.
7. Investigá por separado la intermitencia de `DeportistaTemporadaEdicionDbTest:98`. No modifiques esa prueba sin demostrar la causa.
8. Si identificás una corrección mínima, implementala con pruebas de regresión y verificá que el comportamiento de producción se mantenga seguro.
9. Ejecutá las suites relevantes y la revisión independiente exigida por ODD.
10. Aplicá RDD al candidato corregido; solicitá consentimiento si corresponde.

No modificar V5 ni ejecutar migraciones en Supabase o bases compartidas.

No hacer push, merge ni despliegue todavía.

Al finalizar, presentá la causa raíz demostrada, archivos modificados, pruebas ejecutadas y riesgos. Si no conseguís reproducir o explicar el problema, informalo sin introducir cambios especulativos.

**Excepción operativa propia, no heredada de D2:** ante la solicitud de habilitar spawn_agent/wait_agent sin list_agents exclusivamente para esta corrección, el usuario respondió "si autorizo". No constituye cumplimiento literal del protocolo habitual. Se conserva una verificación independiente real y RDD nativo completo; no se extiende a D3 ni autoriza publicación.

**Ruta y diagnóstico antes de modificar código:** implementación inline de una corrección comprendida, sin razón para delegar escritura; exploración/verificación independiente por seguridad y CI (ítems 2/5). Agente `01a1a361-4036-7f13-b236-0e18713b894b`, tarea `ci10_independent_diagnosis`, modelo `gpt-6.1-sol`, esfuerzo `medium`; wait_agent devolvió estado terminal `completed`, conclusión: ambas causas demostradas, no aprobación de una implementación todavía inexistente. Scratch externo con PostgreSQL 17 y datos ficticios, sin acceso a Supabase.

- CSRF: Spring Security test 7.1.1, `SecurityMockMvcRequestPostProcessors:519–524` y `WebTestUtils:144–147`, sustituye por reflexión el repositorio del CsrfFilter cacheado al usar `.with(csrf())`. El nuevo `DocumentosRutasWebMvcTest:82` comparte `BaseOpenApiWebMvc`: las solicitudes posteriores usan un repositorio de sesión, devuelven `X-CSRF-TOKEN` y no crean `XSRF-TOKEN`. Una sonda idéntica prueba el mecanismo en ambos refs; el PR introduce su activación en este contexto. No es una autoconfiguración alternativa de producción.
- Comparación equivalente: main `8a132ee` y PR `e5c6179`, mismo POM/workflow/configuración de seguridad, perfil Spring `test`, Boot 4.1.1/Security 7.1.1. Local Java Ubuntu 21.0.12.1 frente a CI Temurin 21.0.13+9: no se afirma paridad exacta de JDK. No hay evidencia de que esa diferencia cause el fallo; el orden controlado lo reproduce localmente.
- RED independiente y spot-check del padre: `./mvnw -o '-Dtest=DocumentosRutasWebMvcTest,OpenApiRutasTest,OpenApiEstadosRealesTest' -Dsurefire.runOrder=alphabetical test`: 27 pruebas, 1 fallo/9 errores. OpenAPI sin Documentos pasa en ambos refs; las tres clases OpenAPI aisladas también pasan. Suites completas con el comando exacto `./mvnw -B clean verify`: main 891/891 y PR 903/903 pasan localmente sin ordenar; esto muestra dependencia del orden, no invalida el fallo remoto.
- Auditoría: mismo test/servicio original en main y PR. UUID sintético `11111111-1111-4111-8111-111111100911`: falla en ambos al buscar `009` como subcadena del JSON completo, aunque es parte legítima de `temporadaId`; UUID de control sin esa subcadena pasa en ambos. El servicio registra intencionalmente temporadaId. Es una fragilidad preexistente, no una regresión de V5.

**Corrección prevista y comprobaciones pendientes:** sustituir únicamente el csrf sintético de Documentos por intercambio real cookie/cabecera y comprobar nuevamente el endpoint después de los POST rechazados; conservar 405 sin servicio. Convertir la aserción de auditoría a igualdad JSON exacta con UUID determinista que contenga 007/009, manteniendo la prohibición de campos/valores deportivos. Pruebas RED antes de la corrección; luego secuencia alfabética completa afectada, suites CI normal/DB, verificación independiente y assess/RDD del nuevo candidato. Producción, dependencias, workflow, V5 y contratos no se modifican. Sin commit antes del cierre de verificaciones/RDD, ni push, merge o despliegue. Frontera de reversión: exclusivamente las dos clases de prueba y esta entrada documental. Harness de producción: no aplica, no cambia código ejecutable de producción.

**RED → GREEN propio observado:** antes de la corrección, la nueva comprobación CSRF posterior al POST falló por ausencia de XSRF-TOKEN (1/1 fallo); el UUID fijo que contiene 007 y 009 hizo fallar la aserción antigua (1/1 fallo, sólo esas subcadenas). Tras la corrección, secuencia alfabética `DocumentosRutasWebMvcTest,OpenApiRutasTest,OpenApiEstadosRealesTest,OpenApiExposicionSinDevTest`: 37/37; clase `DeportistaTemporadaEdicionDbTest` con PostgreSQL 17: 6/6, cero errores/omitidos. La igualdad JSON exacta admite únicamente temporadaId, camposModificados y los dos estados, sin relajación de privacidad. Producción, V5, POM, workflow y perfiles siguen idénticos. Verificación independiente posterior y suites completas pendientes.


**Verificación independiente posterior terminada:** mismo agente `01a1a361-4036-7f13-b236-0e18713b894b`, tarea `ci10_independent_postfix`, con scratch nuevo basado en `e5c6179` + diff del candidato. `wait_agent` confirmó resultado terminal `completed`: PASS funcional/independiente para S15; S16 verificación satisfecha, RDD nativo aún pendiente. Sin bloqueantes causados por el cambio. `./mvnw -B clean verify`: 903/903; `./mvnw -B verify -Pdb-tests`: 440/440; secuencia alfabética de las cuatro clases: 37/37; cero fallos, errores u omitidos. Comandos completos ejecutados secuencialmente con `MAVEN_ARGS=-o` (caché local), PostgreSQL 17/Testcontainers. Incluye D1 7, V5 21 + bordes 4 y edición 6 pruebas DB. No se afirma que CI remoto haya pasado ni paridad de parche JDK.

**Controles adicionales y aplicabilidad:** tres sondas independientes pasan en baseline y candidato: POST sin CSRF o inválido sigue en 403 sin negocio; cuatro GET conservan cuerpo/estado/content-type/no-store/nosniff; igualdad JSON rechaza campos deportivos adicionales sin rechazar fragmentos del UUID. Las regresiones duraderas están integradas en las dos clases modificadas; las sondas comparativas y sus salidas son temporales fuera del repositorio, sin rutas a historia Git en los tests. Hash del patch inspeccionado: `78cb845aa9282b7ba688d17f4a61b35d85e661a67d4259d83fadc336d613f270`; manifiesto de fuentes: `b2b18e5167f852c3e0572c03ea50054fd0bbb9fd5f34452d7737176de59afc8c`. Este registro documental posterior no altera fuentes ejecutables ni extiende una aprobación RDD anterior. Spot-check posterior del padre: secuencia alfabética 37/37. El manifiesto coincide con el workspace salvo `mvnw.cmd` por terminadores LF en workspace/blob Git frente a CRLF en el archivo de scratch, según el atributo eol=crlf; contenido normalizado idéntico, wrapper Linux usado sin diferencias; no se modifica ese archivo.

**Alcance y riesgos residuales:** únicamente dos clases de pruebas y este documento, sin archivos nuevos ni secretos detectados por inspección y patrones de credenciales (no es garantía exhaustiva). V5 conserva SHA-256 `fb9971ff8cab0210b973bae9a0c01cc406e62048c1166a775ef6dd8ea05a875f`. No cambiaron producción, contratos HTTP, migraciones, dependencias, perfiles, workflow o autoconfiguraciones. Avisos existentes Mockito/ByteBuddy sobre carga dinámica del agente; CI remoto conserva el resultado anterior hasta una publicación futura autorizada. Permanece la diferencia de JDK local/CI ya registrada; no hubo Supabase, despliegue ni bases compartidas. RDD activado por defecto: assess y consentimiento específicos del nuevo candidato pendientes; D1/D2 no se revisan otra vez por contenido idéntico. No commit antes de ese cierre. Risk: item 2 (pruebas del contrato CSRF real y de privacidad de auditoría), contexto de CI ítem 5; verificador independiente satisfecho.


### L16 — Cierre RDD de la corrección CI1 (2026-11-03)

El usuario respondió "granted" al consentimiento específico de CI1. RDD nativo `review-8c45f1fd3449f4f9`, candidato `sha256:d89435ad2534d0543a72a3cbadfcc08f29fed3ccd79d666252f67822bf5d6261`, árbol `dc892ebf62d94e562c12eba668005553ed57a885`: tres lentes ejecutadas concurrentemente (riesgo, legibilidad, confiabilidad), todas sin hallazgos. La última captura devolvió approved; su reconocimiento exacto confirmó `action=acknowledged`, `authority=burned`. No hubo correcciones durante RDD ni cambios ejecutables posteriores; siguen aplicables las pruebas e independencia de L15. Esta entrada y el estado de CI1 son un delta documental posterior, no bytes automáticamente cubiertos por aquella aprobación; se evalúan separadamente. No se repitieron las revisiones de D1/D2. Sin commit, push, merge, despliegue ni cambios en Supabase; CI remoto conserva su resultado anterior.

Assess posterior del registro de cierre: `risk=medium`, `review_due=false`, `review_due_reason=under_budget`; no se inicia otra revisión del código idéntico ni se atribuye al delta documental la aprobación previa. La comparación con el árbol aprobado sólo agrega documentación; fuentes ejecutables y verificaciones funcionales conservan su aplicabilidad.

### L17 — D3a autorizada y plan previo a implementación (2026-11-04)

Solicitud literal del usuario:

> Continuemos con la Fase 3 del backend de Banfield Patín Carrera.
>
> El PR #10 quedó integrado en `main` mediante merge commit `42d356c543da6805367cb165f2aa8abe60b6b9dc`, con CI verde.
>
> Autorizo comenzar **D3a — Adaptador de almacenamiento privado Supabase Storage**.
>
> Objetivo: implementar la infraestructura mínima de Storage, sin habilitar todavía endpoints de carga de documentos.
>
> ### Preparación
>
> 1. Confirmá el estado de `origin/main`, el merge del PR #10 y el árbol de trabajo.
> 2. Conservá intactas las cinco entradas locales sin seguimiento. No las borres, muevas ni agregues al commit.
> 3. Creá una rama nueva desde `main` actualizado, por ejemplo `feat/documentos-storage`.
> 4. Revisá `docs/DECISIONES.md`, `docs/DOCUMENTOS.md`, `odd/tasks/documentos-privados.md` y la implementación existente.
> 5. Registrá D3a como unidad diferenciada de D3b en ODD, sin heredar excepciones de tamaño o delegación anteriores.
>
> ### Implementación D3a
>
> 1. Definí una interfaz propia `AlmacenDocumentos`.
> 2. Implementá un adaptador Supabase Storage con `RestClient`, conforme a la decisión O2-A.
> 3. Diseñá las operaciones mínimas de almacenamiento privado y eliminación compensatoria de objetos recién creados.
> 4. Usá claves opacas generadas por el backend. No aceptes rutas arbitrarias ni nombres de archivo como identificadores de almacenamiento.
> 5. Garantizá que las cargas futuras no sobrescriban objetos existentes.
> 6. Configurá URL, bucket, credenciales y timeouts desde configuración externa.
> 7. Nunca expongas claves de servicio, URLs públicas ni rutas internas al frontend.
> 8. Definí errores tipados y controlados para fallos de red, timeouts, autenticación, respuestas inesperadas y estado remoto incierto.
> 9. No registres tokens, nombres personales, contenido documental ni respuestas sensibles del proveedor en logs.
> 10. Implementá pruebas automatizadas contra un servidor HTTP simulado, incluyendo fallos y casos límite. No conectes todavía con Supabase real.
>
> ### Restricciones
>
> - No implementar endpoints POST, PUT ni DELETE documentales.
> - No modificar V1–V5.
> - No habilitar cargas reales.
> - No implementar revisión, reemplazo, descarga ni ZIP.
> - No agregar dependencias innecesarias.
> - No introducir colas, sagas ni servicios adicionales sin justificación.
> - No ejecutar migraciones contra bases compartidas.
> - No modificar Supabase ni crear buckets reales.
> - No asumir que las políticas de retención, escaneo o compensación integral ya están aprobadas.
>
> ### Verificación
>
> 1. Aplicá ODD con pruebas RED/GREEN.
> 2. Ejecutá pruebas específicas y regresiones.
> 3. Ejecutá las suites normal y PostgreSQL.
> 4. Realizá verificación independiente proporcional al riesgo.
> 5. Ejecutá assess RDD y solicitá consentimiento si corresponde.
> 6. Presentá el alcance, resultados, riesgos y decisiones pendientes para D3b.
>
> No hacer commit antes de completar los controles exigidos. No hacer push, PR ni merge sin autorización posterior.
>
> Si una decisión pendiente impide diseñar correctamente D3a, presentá las alternativas y detené solamente la parte afectada.

**Excepciones nuevas y acotadas:** el usuario respondió "autorizo" a usar spawn_agent y wait_agent sin list_agents exclusivamente para D3a; luego respondió "autorizo" a sustituir gpt-6.1-luna por gpt-6-luna/high para la exploración de D3a, conservando gpt-6.1-sol/medium para verificación. No constituyen cumplimiento literal del protocolo habitual. No se heredan excepciones de D1/D2, validación V5 o CI ni de tamaño. Un primer spawn fue rechazado por límite de hilos: wait_agent confirmó terminados los cuatro agentes anteriores; se cerró únicamente el agente terminado `01a1a27d-a6da-7643-8435-9f7bbd2ff519` para liberar capacidad, sin cancelar trabajo activo. Explorador real Hilbert `01a1a52b-63cb-73a0-a24d-3ed728f5278b`, tarea d3a_storage_map: wait_agent confirmó completed con mapa de O2-A, propiedades/configuración, límites pendientes y HttpServer JDK sin dependencias; conclusión: adaptador aislado viable sin decidir D3b. Skill resolution paths-injected. No escribió ni ejecutó pruebas.

**Preparación observada:** main y origin/main `42d356c543da6805367cb165f2aa8abe60b6b9dc`, PR #10 MERGED por lectura remota. Fetch limitado y fast-forward sin cambios; rama nueva `feat/documentos-storage`. Cinco raíces ajenas fotografiadas en `/tmp/d3a-untracked-before.json`, no versionables en esta unidad. Cambios iniciales sólo este plan; V1–V5 y dependencias se conservan.

**Plan y criterios antes de la primera escritura de fuentes:**
1. Puerto `AlmacenDocumentos` para crear con UUID opaco generado internamente y compensar sólo con recibo no construible por clientes, emitido al confirmar creación por la misma instancia. Sin ruta/nombre original en entradas, sin borrado genérico ni URL pública. D3b decidirá cómo coordinar transacción, autorización y objetos huérfanos; no se implementa reconciliación ni retención.
2. Adaptador RestClient sin SDK, redirecciones ni reintentos de aplicación, alta POST con x-upsert=false. Validar confirmación y límite de respuesta. El recibo no permite borrar historia; error incierto nunca da éxito ni desencadena borrado automático. Configuración externa opt-in, inactiva por defecto; habilitada valida HTTPS (HTTP sólo loopback para pruebas), bucket, secreto y timeouts finitos sin imprimir valores sensibles.
3. RED real antes de producción mediante pruebas JUnit contra HttpServer loopback: contrato/credenciales simuladas, claves distintas, compensación acotada, conflictos/no sobrescritura, fallos/redirecciones/red/timeouts/cuerpo inválido, salida saneada y configuración. Primero fallo de compilación por API aún ausente; luego GREEN y casos afectados. Sin runner de producción existente que comparta este código nuevo: diferencial previo no aplicable, contrato D1 protegido por regresión completa.
4. Normalización check-only, sin formatter mutante conocido; suites normales y PostgreSQL 17 locales, verificación independiente desde scratch externo, spot-check del padre. Risk: item 2 (credencial privilegiada y almacenamiento privado), con fallos de red de resultado incierto. Verificador independiente no es el implementador; registrar su ID y retorno terminal real.
5. Assess RDD del candidato final después de todas las fuentes/documentación y verificaciones; solicitar consentimiento específico si corresponde. No commit antes de completar controles. Publicación siempre requiere autorización posterior. Si supera el presupuesto de entrega, resolver estrategia/tamaño antes de commit; no dividir artificialmente comportamiento/tests/docs.

Referencias técnicas primarias consultadas: [Supabase standard uploads](https://supabase.com/docs/guides/storage/uploads/standard-uploads) (alta sin upsert y conflictos), [cliente oficial StorageFileApi](https://github.com/supabase/storage-js/blob/main/src/packages/StorageFileApi.ts) (POST object y DELETE object/bucket con prefixes), [Spring RestClient](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html). No se accedió al proyecto Supabase real.

**Implementación y prueba propia D3a (antes de verificación independiente):** 5 clases de producción y 2 de pruebas nuevas, más configuración y documentación, sin dependencia nueva. El adaptador valida metadatos `public=false` antes de cada POST: el control no hace privado un bucket ni impide cambios administrativos concurrentes. Recibo de una sola compensación confirmada y de la misma instancia; sus carreras locales se prueban con dos tareas concurrentes, sin sleeps. Datos en memoria y MIME concreto sin parámetros; límites funcionales/escaneo no implementados.

RED confirmado: `/tmp/d3a-red-confirmado.log`, fallo de compilación por API ausente. RED de comportamiento `/tmp/d3a-red-mime-confirmado.log`, 1 fallo por permitir parámetros MIME; corregido antes de congelar. RED `/tmp/d3a-red-bucket.log`, 4/4 fallos por no consultar privacidad; corregido con GET y fallo cerrado antes de POST. GREEN `/tmp/d3a-especificas-final.log`: 50/50, cero fallos/errores/omitidas (33 HTTP, 17 configuración). Primer intento de prueba HTTP dentro del sandbox no podía abrir sockets (Operation not permitted); reejecutado con autorización exclusivamente loopback. Un primer comando de pruebas no encontró el test por una ruta relativa equivocada; se movió sólo el archivo propio y se retiraron los directorios vacíos creados por ese comando. No se cuenta ese intento como RED de comportamiento. Normalización: git diff --check conforme; sin normalizador mutante. Suites completas, verificación independiente y RDD pendientes. No commit/push.


**Cierre funcional e independiente de D3a, previo a RDD:** verificador distinto Nietzsche `01a1a53d-c197-70d0-9653-fbad2f4d88c4`, clase odd-verify, modelo gpt-6.1-sol/medium; tarea d3a_storage_independent. wait_agent confirmó estado terminal completed y entregó resultados, skill_resolution paths-injected. Extrajo baseline en scratch externo, superpuso exactamente 11 archivos del manifiesto, comprobó hashes antes/después y otros 401 archivos regulares del baseline idénticos. Conclusión por S17/S18: conforme localmente; S19: funcional e independiente completo, RDD pendiente; S20: alcance y separación conservados. Sin bloqueantes reproducidos.

Resultados observados del verificador, desde backend en scratch: `./mvnw -o clean verify` 953/953; `DOCKER_HOST=unix:///var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 ./mvnw -o verify -Pdb-tests` 451/451, PostgreSQL 17 real local; `./mvnw -o -Dtest=VerificacionIndependienteTest test` 4/4 en copia separada. Todos sin fallos, errores ni omitidas. Los probes adicionales comprobaron denegación de bucket sin escrituras, timeout incierto sin borrado automático, ausencia de marcadores sensibles en logs/Throwable y altas/compensación paralelas preservando un objeto histórico ajeno. El primer intento normal en sandbox falló por restricciones de sockets/JVM (5 fallos/591 errores); reejecución escalada local aprobada, no se presenta ese intento como regresión corregida. Spot-check propio del padre: 50/50 en `/tmp/d3a-spotcheck-padre.log`, sobre las mismas fuentes. V5 conserva SHA-256 `fb9971ff8cab0210b973bae9a0c01cc406e62048c1166a775ef6dd8ea05a875f`; V1–V5, endpoints, entidades/consultas D1 y dependencias sin cambios.

Aviso no bloqueante: dos llamadas `JsonNode.asText()` deprecadas en SupabaseAlmacenDocumentos; javac -Xlint:deprecation confirma compilación exitosa. No se modifica el candidato por ese aviso. Se conservan avisos previos de Mockito/ByteBuddy y fuentes no tocadas. Informe, logs, XML, hashes, conteos y probes conservados exclusivamente en `/tmp/d3a-verificacion-evidencia/`, no en Git; las dos copias scratch del verificador y de la suite se retiran después de conservar evidencia. No hay validación de credenciales reales, RLS/bucket real, archivos grandes, retención/escaneo ni reconciliación; no es aprobación de producción. Lectura de public=false no impide cambios administrativos concurrentes. El contenido de los cinco árboles ajenos sigue idéntico al snapshot D3a. Sin commit, push, PR, migraciones compartidas ni Supabase real. Risk: item 2 (credencial y Storage privado), item 4 (consumo concurrente de capacidad de compensación). Próximo paso: assess y consentimiento RDD específicos, sin heredar consentimientos anteriores.


### L18 — Cierre RDD D3a (2026-11-04)

El usuario respondió "granted" al consentimiento específico del candidato `sha256:30cb6a5fbf314cfcdf087719361b20be3ca86209f559d48c63cb0f9e4ba52b27` (11 archivos, 701 líneas; árbol `aacdfb345c889fc428fa00664899acf22f105457`). Se ejecutó exactamente la invocación nativa seleccionada, lineage `review-469b8c212631c291`, y luego su STATUS vinculado. Las cuatro capturas nativas se lanzaron concurrentemente en orden riesgo, resiliencia, legibilidad y confiabilidad; todas terminaron y fueron admitidas, sin hallazgos. Inspeccionaron parches inmutables, no ejecutaron pruebas; la evidencia funcional e independiente sigue siendo la de L17.

La última captura devolvió approved y su continuación exacta de reconocimiento; ésta confirmó `gentle-ai.review-acknowledged/v1`, `action=acknowledged`, `authority=burned`, revisión consumida `sha256:d3f7029a0d29de76e8d90c09817dd0d2f7d0b351de47581cdb0f1c9f1b60b39f`. No hubo corrección ni cambios ejecutables posteriores. Resultados de L17 conservados: 50 específicas, 953 normales, 451 PostgreSQL 17 y 4 probes independientes aprobados; no se repiten sobre bytes idénticos. Persisten los dos avisos asText deprecados y los límites de validación real ya documentados.

Esta entrada y el estado de la tarea son un ajuste documental posterior, no bytes automáticamente cubiertos por el candidato revisado. Se comprueba su estructura y se aplica assess por separado, sin atribuirle el reconocimiento anterior. La unidad excede el presupuesto de entrega: no hay excepción de tamaño D3a aprobada ni se heredan las anteriores. No se realiza commit, push, PR, merge ni Supabase real. Se conserva la separación D3b no autorizada y las cinco entradas locales ajenas. Risk: item 2 e item 4; revisión y verificación independiente completadas, publicación no autorizada.

Assess del ajuste documental posterior: `risk=medium`, `review_due=false`, `review_due_reason=under_budget`; sólo cambió este documento respecto del árbol reconocido. Lectura estructural y whitespace conformes; no se reinicia revisión del código idéntico. El delta documental no hereda el reconocimiento del candidato original. Las verificaciones funcionales e independientes siguen aplicando a fuentes/configuración idénticas.


### L19 — Excepción propia y commit local D3a (2026-11-04)

El usuario autorizó: "Autorizo la excepción de tamaño exclusivamente para D3a: 11 archivos y 712 líneas, más el registro documental mínimo de esta autorización en ODD." También autorizó el commit local `feat(documentos): implementar adaptador privado de Supabase Storage`, condicionado a identidad ejecutable/pruebas, ausencia de secretos y assess sin nueva revisión obligatoria. Se mantienen juntos puerto, adaptador, configuración, pruebas y documentación porque forman una única infraestructura verificable, sin ampliar funciones ni heredar excepciones. La comparación binaria con el árbol RDD `aacdfb345c889fc428fa00664899acf22f105457` confirma diez archivos idénticos; sólo este documento agrega cierre y autorización. Siguen aplicando 50 específicas, 953 normales, 451 PostgreSQL y 4 comprobaciones independientes; no se repiten sobre fuentes idénticas. Registro posterior sujeto a assess propio, no a la aprobación anterior. No push, PR, merge, Supabase real, V1–V5 ni D3b. Los JSON/logs temporales RDD ya no están disponibles; la trazabilidad reconocida se conserva en L18 y Engram, sin simular nuevas ejecuciones. Risk: item 2 e item 4; controles ya completados, alcance de esta continuación únicamente documental y commit local.


### L20 — B1 autorizada y plan anterior a fuentes (2026-11-04)

El usuario autorizó integrar PR #11 con HEAD `e4015356a0e064365080000c489582965a566b72` usando merge commit y luego implementar B1 desde main actualizado. Resultado observado: merge `85f354636e3ae0c44fa2e94638393a97e5002886`; CI posterior 953 normales/451 PostgreSQL aprobadas. Cinco entradas ajenas intactas; rama B1 desde ese merge; V6 disponible y FK compuestas compatibles con V1/V5.

Solicitud B1 literal:

> Tras integrar D3a, autorizo comenzar B1 en una rama nueva desde main actualizado.
>
> Objetivo: implementar `V6__registrar_operaciones_carga_documental.sql`, pruebas PostgreSQL 17 y documentación ODD.
>
> Alcance aprobado:
> - Una tabla `gestion_patin.operacion_carga_documental`.
> - Identidad de operación, escuela, solicitante, propietario exclusivo, tipo y temporada.
> - Destino Storage opaco y único.
> - Idempotencia única por escuela y solicitante.
> - Manifiesto validado estructuralmente y de asignación única.
> - Estados RESERVADA, TRANSFIRIENDO, ALMACENADA, PUBLICADA, RECONCILIANDO y CERRADA.
> - Transiciones controladas, versionado y reservas.
> - Publicación vinculada al Documento correspondiente.
>
> Condiciones:
> 1. Comprobá nuevamente que V6 está disponible y que las FK propuestas son compatibles con el esquema existente.
> 2. Priorizá restricciones SQL declarativas. Utilizá un trigger acotado para las invariantes que realmente lo requieran.
> 3. El manifiesto no podrá quedar parcialmente informado ni modificarse después de su primera asignación.
> 4. Exigí versión esperada para las transiciones operativas y comprobación de filas afectadas.
> 5. Rechazá publicaciones con contexto documental incompatible, incluyendo temporada nullable y destino Storage.
> 6. No interpretes el vencimiento de una reserva como confirmación de que terminó una transferencia externa.
> 7. No prometas validación antimalware, autorización de familias ni transferencia exitosa: B1 sólo establece la estructura SQL necesaria.
> 8. Usá pruebas RED/GREEN, migración limpia, actualización desde V5 y pruebas concurrentes con conexiones PostgreSQL reales.
> 9. Ejecutá regresiones de D1, D2 y D3a, las suites completas y la verificación independiente exigida por ODD.
> 10. Ejecutá assess RDD y solicitá consentimiento para el candidato si corresponde.
>
> No modificar V1–V5.
> No implementar repositorios mutadores, endpoints, cargas, scanner, integración real con Supabase ni reconciliación.
> No realizar commit de B1 antes de completar verificaciones y RDD. Cualquier excepción de tamaño requiere autorización específica.
> No hacer push, PR ni merge de B1.
> No ejecutar migraciones en bases compartidas ni desplegar.
> Al finalizar, informá la evidencia de B1, el tamaño real, las pruebas, los riesgos residuales y el estado del candidato para RDD y commit local.

**Excepción B1 propia:** faltando list_agents, el usuario respondió "autorizo" a usar exclusivamente para B1 spawn_agent y wait_agent, con verificación independiente real y RDD completo. No se hereda de D3a ni constituye cumplimiento literal del protocolo habitual. Implementador: sesión principal; verificador gpt-6.1-sol/medium distinto, ID y resultado terminal se registrarán. No se solicita sustitución de modelo ni excepción de tamaño por arrastre.

**Plan:** una unidad comprendida, inline, V6 declarativa y un trigger para contexto inmutable, manifiesto asignable una vez, transiciones y validación de publicación; reutilizar claves compuestas V1 y PostgresDescartable/DatosDb para pruebas. CAS SQL en pruebas directas con versión esperada y filas afectadas; ningún repositorio mutador. Reservas vencidas no habilitan reenvío de TRANSFIRIENDO: sólo reconciliación. Publicación valida documento insertado en la misma transacción, raíz serie=id, sin predecesor, estado inicial CARGADO. B1 no prueba antimalware, autorización familiar ni existencia remota. Primera carga/registro durable aprobados sustituyen para D3b la propuesta B5 sin registro. RED antes de V6; después GREEN, migración limpia y desde V5, carreras con dos conexiones/barreras, regresión completa, verificación independiente desde scratch y spot-check del padre. Risk: ítems 1/2/4. Pruebas largas se delegarán al verificador; no afirmar resultados antes de wait_agent terminal. Normalización antes de congelar: whitespace check-only, sin formatter mutante conocido. Assess/RDD posterior, consentimiento específico si corresponde; tamaño real se resolverá antes de commit. No otras unidades.

**Evidencia propia previa a verificación independiente:** RED real `/tmp/b1-red.log`: 1 prueba fallida, esperaba versión 6 y obtuvo 5, Docker/PostgreSQL real disponible. GREEN inicial 44/44 en `/tmp/b1-green-inicial.log`. Ampliación de contexto documental detectó un fixture incorrecto (Documento de otra escuela con temporada de la original); se corrigió el fixture para que el documento ajeno fuese válido antes de probar el rechazo de publicación, sin cambiar la regla. GREEN focal final `/tmp/b1-focalizadas-final.log`: 57 B1 + 21 V5 = 78/78, cero fallos/errores/omitidas; incluye carreras con conexiones distintas para reserva CAS, idempotencia y publicación, sin sleeps. Rechazos comprueban SQLSTATE y conservación tanto de operaciones como de documentos.

Regresión demostrada del harness histórico: `/tmp/b1-regresion-v5-red.log`, esperaba 5 migraciones pero `latest` ya aplicaba 6. Ajuste mínimo únicamente en V5IntegridadDocumentalDbTest: target explícito 5 en sus dos caminos de migración; mantiene la aserción y escenarios V5 sin cambiarlos. No edita V1–V5. El resto de diferencias: nueva V6, nueva prueba B1 y documentación. Documentado contrato CAS: WHERE con versión esperada y comprobar una fila; trigger exige incremento, sin fingir que adivina la versión de un cliente SQL arbitrario. B1 no implementa mutadores ni purga; DELETE de operaciones no habilitado hasta política propia, sin afirmar retención legal. Whitespace check-only conforme, no formatter mutante. Suites completas y verificación independiente pendientes; no congelar RDD antes de sus resultados. Sin commit ni excepción de tamaño.

**Primera verificación independiente real:** Raman `01a1a7e6-60de-70e2-9b08-f5b92c4fa81f`, gpt-6.1-sol/medium; wait_agent confirmó completed, skill_resolution paths-injected. Scratch externo desde baseline y cinco SHA iguales al inicio/final. S21/S22 y alcance S24 conformes; S23 bloqueada por harnesses históricos. Suite normal 953: 1 fallo (inventario sólo V1–V5). Suite DB 508: 3 fallos y 3 errores: historia V4 exacta sin V6, ensayos V5 `latest` que intentan dos migraciones o V6 con un rol V5 restringido, más dos conexiones loopback rechazadas. Controles baseline confirmaron las regresiones deterministas; también reprodujeron un error de conexión anterior, que no se atribuye a V6. Probes SQL independientes 4/4 y CAS propio 1/1 aprobados; sin defecto adicional reproducido de V6. Evidencia inicial `/tmp/b1-independent.JtMJe9/`.

**Única corrección acotada de los bloqueantes:** inventario estático ampliado exactamente a V6 (conserva hash V1 y lista cerrada); consulta de V4 acotada al prefijo histórico V1–V5; ensayos operativos V5 ahora target 5 explícito en sus seis llamadas, sin ampliar permisos ni cambiar sus aserciones. No se cambió V6 ni su test B1. Siete pruebas estáticas focales aprobadas. Focal de V5Operación/V4: 12 pruebas, cero fallos, tres errores de conexión en casos incompatibles anteriores (Connection refused antes de migrar; SQLSTATE no capturado en esos logs); los bloqueantes deterministas desaparecieron, pero no se declara la suite verde. Recheck independiente limitado a estos bloqueantes y suites completas pendiente. Se permite una única ejecución local de recheck con host loopback `localhost` en vez de `127.0.0.1` para comprobar el entorno sin alterar código ni omitir pruebas; no se afirma causa raíz ni reparación del problema de conexión. Si permanece prueba requerida fallida, informar parcial, no iterar hasta verde.


**Recheck terminal limitado, sin nueva corrección:** mismo verificador Raman, resultado PARTIAL confirmado por wait_agent. Scratch nuevo `/tmp/b1-recheck.abet3v`, ocho SHA iguales al inicio/final, SQL V6 y pruebas B1 idénticos a la primera verificación. Correcciones deterministas confirmadas: inventario V2 7/7, pruebas V4 20/20 y los tres métodos operativos V5 antes bloqueantes aprobados, sin permisos nuevos ni aserciones debilitadas. Ejecuciones únicas con `DOCKER_HOST=unix:///var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=localhost`: `./mvnw -o clean verify` 953/953 (exit 0); `./mvnw -o verify -Pdb-tests` 508 pruebas, cero fallos, un error y cero omitidas (exit 1). PostgreSQL 17.11. B1 57/57, V5Integridad 21/21, V5Operación 10/11. Error restante: `V5OperacionDbTest.incompatiblesRechazadosSinCambiosEnTodaLaBase(String)[2]`, Connection refused a localhost:50568 al preparar el contenedor, antes de aplicar migración. Baseline y candidato inicial también mostraron conexión rechazada con 127.0.0.1. No se demostró la causa raíz ni se afirma que el host alternativo repare el entorno.

S21/S22 y alcance S24 verificados; S23 permanece incompleta por la suite requerida fallida. Probes SQL independientes previos 4/4 y spot-check CAS propio 1/1 siguen aplicando al SQL sin cambios; no representan aprobación de la suite completa. Se agotó la corrección acotada/recheck; no se modifican más fuentes ni se repiten comprobaciones para buscar verde. Próximo paso requiere decisión del usuario sobre diagnóstico del arranque/conectividad del harness local V5, separado de las invariantes B1. Assess de riesgo solicitado pendiente de registrar fuera de este archivo; no iniciar START/RDD con verificación funcional incompleta ni pedir consentimiento de revisión como sustituto. No commit, excepción de tamaño, push, PR, proveedor real ni migraciones compartidas. Evidencias preservadas en `/tmp/b1-verificacion-evidencia/`, fuera del repositorio; no incorporar respaldos. Risk: ítems 1/2/4.


### L21 — Diagnóstico local autorizado del bloqueo B1 (2026-11-04)

El usuario autorizó exclusivamente diagnosticar y corregir el rechazo de conexión de V5OperacionDbTest, también observado en baseline. Contexto confirmado: 8 archivos/583 líneas, 57 B1 y 953 normales aprobadas, DB 507/508, probes independientes 4/4 y CAS 1/1. No se infiere culpa de V6 por correlación. Continúa la excepción operativa propia de B1 (spawn/wait sin list_agents, verificador real), sin extenderla a otras unidades.

Procedimiento literal autorizado:
> 1. Inspeccioná las evidencias existentes antes de ejecutar nuevas pruebas.
> 2. Revisá el ciclo de vida de PostgreSQL Testcontainers y el harness de `V5OperacionDbTest`.
> 3. Determiná si el fallo proviene de:
>    - Contenedor terminado prematuramente.
>    - Puerto dinámico obsoleto.
>    - Conexión JDBC reutilizada después del cierre.
>    - Carrera de inicialización o teardown.
>    - Interferencia entre pruebas o ejecuciones concurrentes.
>    - Problema local de Docker/WSL2 o resolución de direcciones.
> 4. Contrastá baseline y rama B1 bajo condiciones equivalentes.
> 5. Capturá evidencia suficiente para establecer causalidad, no solo correlación.
> 6. Si identificás la causa, aplicá la corrección mínima al componente responsable.
> 7. No introduzcas sleeps arbitrarios, reintentos ciegos ni cambios de expectativa.
> 8. No desactives tests, restricciones ni verificaciones de seguridad.
> 9. No modifiques las migraciones V1–V6.
> 10. No alteres código de producción ajeno al problema.

Validación literal:
> 1. Ejecutá repetidamente la prueba afectada para comprobar estabilidad.
> 2. Ejecutá todas las pruebas de `V5OperacionDbTest`.
> 3. Repetí las 57 pruebas B1.
> 4. Ejecutá las suites completas normal y PostgreSQL.
> 5. Realizá una verificación independiente del diagnóstico y la corrección.
> 6. Confirmá que el candidato B1 sigue cumpliendo sus invariantes y que no se alteraron las garantías de V5.

Restricciones: sin commit/push/PR/migraciones compartidas/Supabase ni modificación de las cinco entradas locales; no iniciar RDD ni pedir excepción de tamaño. Sólo corregir tras causalidad demostrada. La autorización reabre el diagnóstico agotado anterior; no se repiten intentos para buscar verde.

**Plan previo a cambios del harness:** evidencias ya inspeccionadas en `/tmp/b1-verificacion-evidencia/`: fallo en constructor, antes de cualquier migración; fuente V5OperacionSupport idéntica en baseline y B1. Testcontainers 2.0.5 PostgreSQLContainer espera únicamente dos mensajes de log; su override no usa la comprobación JDBC de JdbcDatabaseContainer. Esto es una hipótesis de readiness insuficiente, aún NO causa probada. Experimento acotado en scratch externos baseline/B1, secuencial y con misma imagen instalada/host: capturar al primer fallo, ANTES del cierre, estado fresco del contenedor, puertos efectivos, readiness interna, TCP externo y JDBC después de una condición explícita de puerto; conservar y volver a lanzar el error original, sin ocultarlo. Si no se prueba la hipótesis, no tocar el harness. Si se prueba, corregir únicamente la estrategia de readiness, con regresión determinista de la barrera y validación repetida/independiente. Sin pausas arbitrarias ni reenvíos de operaciones. Risk: ítems 4/5; SQL B1 intacto y RDD pendiente.

**Resultado B1-C: diagnóstico causal inconcluso, sin corrección.** Baseline `85f354636e3ae0c44fa2e94638393a97e5002886` y candidato contrastados secuencialmente con Docker Unix local, imagen PostgreSQL 17 instalada y host `127.0.0.1`. El padre ejecutó 12 arranques independientes y los 11 escenarios V5Operacion por variante: todos aprobaron. No son reintentos de una aserción fallida. La copia diagnóstica sólo agrega captura al catch, antes de cerrar el contenedor, y relanza el error original; no modifica el camino exitoso. No se incorporó al repositorio.

Verificador independiente real `01a1a7e6-60de-70e2-9b08-f5b92c4fa81f`, tarea `b1_conectividad_independiente`: suites PostgreSQL completas una vez por variante, salida 0, baseline **451/451**, B1 **508/508**, cero fallos/errores/omitidas. XML B1: **57/57 V6**, **21/21 V5Integridad**, **11/11 V5Operacion**. Comando: `DOCKER_HOST=unix:///var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 ./mvnw -o -Pdb-tests test`. Support instrumentado idéntico en ambas copias, SHA-256 `f4de20f4db27a44a7c38461ff265a2517d5f6e361c2965baeb7af2e3e543757f`. La suite normal previa **953/953** sigue siendo aplicable porque ningún ejecutable cambió; no se volvió a ejecutar. Las cuatro comprobaciones SQL y el spot-check CAS anteriores permanecen aplicables, sin atribuirles ejecuciones nuevas.

El informe fue interrumpido por un límite de uso después de terminar las suites. El usuario indicó literalmente «segui»; se retomó sólo la síntesis, sin repetir pruebas. `wait_agent` devolvió finalmente **completed**, con conclusión **INCONCLUSO**, no PASS global; `skill_resolution: paths-injected`. S25 parcial (falta causalidad), S26 conforme (sin parche especulativo), S27 con evidencia funcional DB e independiente obtenida, pero sin cierre causal. El padre contrastó los XML y el manifiesto por separado.

La captura se activó **cero veces**: no existe evidencia nueva del instante del rechazo para distinguir contenedor terminado, puerto obsoleto, carrera o interferencia Docker/WSL2. La primera conexión usa un DriverManagerDataSource nuevo; no es una conexión de pool reutilizada. El error anterior precede a las migraciones. Que Testcontainers espere logs no demuestra que esa estrategia causara el rechazo. Limitaciones del diagnóstico: `pg.start()` queda fuera del catch y un probe que falle puede impedir los siguientes; no se alteró el harness del workspace para corregir estas limitaciones sin una nueva necesidad demostrada.

Evidencia reproducible preservada fuera de Git: `/tmp/b1-verificacion-evidencia/conectividad/`, con logs, XML, manifiestos, diff diagnóstico, comandos y hashes; sin respaldos PostgreSQL. Único archivo del repositorio modificado en B1-C: este plan. V1–V6, pruebas y producción conservan sus bytes; las cinco entradas ajenas conservan las 2340 entradas y sus hashes/modos/enlaces. Sin commit, publicación, migraciones compartidas, Supabase ni RDD. No se solicitó excepción de tamaño.

**Siguiente decisión:** mantener B1-C abierta y no declarar B1 habilitada para RDD con una causa inventada. La alternativa preferida es una captura prospectiva acotada de la siguiente ocurrencia, preservando estado Docker/puertos y el error original antes de cerrar; su límite es que depende de reproducir la intermitencia. Contrastar con Docker Linux nativo podría aislar dependencia del entorno, pero requiere otro entorno y tampoco demostraría por sí solo la causa. No se autorizaron ni ejecutaron esas nuevas pruebas. No agregar sleeps, reintentos ni una barrera de readiness por hipótesis. Risk: ítems 4/5 (incidencia local intermitente sin causa demostrada); no cambio funcional.


### L22 — Verificabilidad B1 y autorización para preflight RDD (2026-11-04)

Solicitud literal del usuario:

> Autorizo avanzar hacia la revisión RDD de B1, manteniendo documentada la intermitencia B1-C.
>
> ### Contexto
>
> La incidencia de conectividad JDBC no pudo reproducirse durante el diagnóstico.
>
> Resultados comprobados:
> - 12/12 arranques independientes por variante.
> - 11/11 escenarios operativos V5 por variante.
> - 451/451 PostgreSQL baseline.
> - 508/508 PostgreSQL B1, incluidas las 57 específicas.
> - 953/953 suite normal previa, sin cambios ejecutables posteriores.
> - Verificación independiente completada.
>
> No se modificó código funcional ni V1–V6. Únicamente se actualizó ODD, L21.
>
> ### Objetivo
>
> Cerrar la evaluación de verificabilidad de B1 y, si las reglas ODD lo permiten, realizar RDD sobre el candidato definitivo.
>
> ### Procedimiento
>
> 1. Confirmá que los resultados actuales y sus evidencias son suficientes conforme al protocolo ODD.
> 2. Mantené B1-C registrada como incidencia intermitente sin causa raíz demostrada.
> 3. Diferenciá claramente entre verificación funcional aprobada e incidencia ambiental no resuelta.
> 4. Si B1-C constituye un bloqueo obligatorio según ODD, no lo ignores: explicá la regla y detené el avance.
> 5. Si no hay impedimento, actualizá el estado de verificabilidad de B1 sin declarar corregida la intermitencia.
> 6. Recalculá el inventario exacto de archivos, líneas y hash del candidato, incluyendo los últimos cambios documentales.
> 7. Ejecutá el assess nativo RDD sobre el candidato completo.
> 8. Solicitá el consentimiento correspondiente si el protocolo lo requiere.
> 9. Realizá una revisión independiente con las lentes pertinentes a persistencia, seguridad, aislamiento y concurrencia.
> 10. Corregí exclusivamente defectos demostrados, siguiendo RED/GREEN y reevaluando el candidato si cambia.
> 11. Registrá resultados, hallazgos y riesgos residuales.
>
> ### Restricciones
>
> - No modificar V1–V6 salvo que RDD demuestre un defecto y exista autorización específica para corregirlo.
> - No introducir reintentos ciegos, sleeps ni relajaciones de pruebas.
> - No declarar B1-C resuelta sin evidencia.
> - No hacer commit, push, PR ni merge.
> - No ejecutar migraciones en bases compartidas.
> - No conectarse a Supabase real.
> - Conservar las cinco entradas locales sin seguimiento.
> - No solicitar todavía excepción de tamaño.
>
> ### Resultado esperado
>
> Informá:
>
> - Estado de la verificación funcional.
> - Estado separado de B1-C.
> - Inventario y hash definitivos del candidato.
> - Resultado RDD y reconocimiento nativo.
> - Correcciones necesarias, si las hubiera.
> - Si B1 queda preparada para solicitar autorización de commit local.
>
> Si el protocolo exige consentimiento o intervención humana, detenete en ese punto.

**Evaluación ODD previa a RDD:** las evidencias de L20/L21 son suficientes para verificar el comportamiento del candidato actual, no para resolver B1-C. Se comprobaron los hashes de los 120 archivos de evidencia conservados, los resultados XML y logs de 953 normales, 451 DB baseline y 508 DB B1 (57 específicas), los cuatro probes SQL independientes y el spot-check CAS. No hay cambios ejecutables posteriores; la captura diagnóstica de scratch sólo agregaba un camino de error, nunca activado, conservando la excepción original. Las 953 normales previas y las pruebas de las invariantes siguen siendo aplicables. No se atribuyen nuevas ejecuciones a esta comprobación documental.

**Reglas aplicadas:** ODD exige verificaciones propias y un verificador independiente para los ítems de alto riesgo, antes de RDD; la regla de fallos de comandos requeridos impide aprobar una suite actualmente fallida, no convierte automáticamente una intermitencia histórica en un veto perpetuo tras comprobaciones completas aprobadas. El contrato de verificación distingue defectos nuevos reproducibles del candidato de observaciones ya presentes en baseline. La incidencia anterior también apareció en baseline y antes de ejecutar las migraciones; no se reproduce ahora y no demuestra un defecto de V6. No existe una regla adicional que obligue a inventar una reparación o cerrar causalmente B1-C antes de revisar estos bytes. La autorización actual permite separar ambos estados, sin rebajar la verificación requerida ni declarar que la causa sea definitivamente ambiental.

**Estado:** B1 verificada funcional e independientemente para entrar al preflight nativo. B1-C ABIERTA, causa no demostrada; L21 y sus resultados históricos permanecen intactos. Verificador SQL y diagnóstico: agente distinto `01a1a7e6-60de-70e2-9b08-f5b92c4fa81f`, handoffs terminales reales registrados en L20/L21; no se simula otra verificación ni se repiten pruebas de código idéntico. No es un PASS de RDD ni una autorización de entrega. Se conservan los límites B1: sin autorización familiar, scanner, existencia remota ni flujo de transferencia demostrados por este registro SQL.

**Plan acotado:** ruta inline documental y orquestación RDD nativa; ninguna implementación nueva ni otra delegación genérica. Antes de congelar: lectura estructural, `git diff --check`, inventario de los ocho archivos y sus modos/bytes, exclusión de las cinco raíces ajenas, hashes V1–V6 y evidencia conservada. Después: assess nativo elevado por ítem 1 y preflight con riesgo (persistencia, propietario/escuela y publicación), resiliencia (reservas, concurrencia y resultados inciertos), legibilidad (contrato SQL/documental) y confiabilidad (transiciones y pruebas). El proveedor conserva la selección/admisión y el consentimiento específico. Si lo exige, detenerse antes de ejecutar lentes. Sin normalizadores mutantes posteriores al freeze. Identidad, inventario y continuaciones se guardarán fuera del candidato para no cambiar sus bytes durante el consentimiento; sólo registrar resultados en el plan después del cierre y evaluar por separado ese delta documental. No solicitar excepción de tamaño ni hacer commit/publicación. Risk: ítems 1/2/4; B1-C sigue como riesgo residual no resuelto.

**Cierre RDD B1 (2026-11-04):** el usuario respondió literalmente `granted`. Se ejecutó una sola vez la invocación exacta del consentimiento nativo, para `sha256:3829634bfd39f9f4e8d20c16e34a9bf558c6e91577ee8b90201736b36ec14a0a`: ocho archivos, **710 líneas (697 añadidas/13 eliminadas)**, base `ea4c2307f69b74166d9e1b4abb659cb711393b79`, candidato `ce55320456c25308e19e994bf605c6d63ea37e12`. El assess había sido alto por escalamiento de persistencia; START registró tier medio y seleccionó expresamente las cuatro lentes solicitadas. No se rebajó por ello el riesgo de datos/aislamiento/concurrencia de B1.

Las cuatro capturas nativas se lanzaron concurrentemente, en orden riesgo, resiliencia, legibilidad y confiabilidad, mediante los argumentos exactos del proveedor. Todas terminaron con salida 0 y **cero hallazgos**. Inspeccionaron los ocho patches inmutables; no ejecutaron pruebas ni sustituyen las verificaciones funcionales e independientes previas. No hicieron falta refutación, corrección ni validación dirigida. El presupuesto nativo de 200 líneas no se utilizó y no se modificó V6 ni ningún ejecutable.

La última captura devolvió `approved` pendiente de reconocimiento. El STATUS ligado a esa misma revisión recuperó la misma invocación de reconocimiento, token y revisión; se ejecutó una sola vez. Resultado real `gentle-ai.review-acknowledged/v1`, `action: acknowledged`, `authority: burned`, lineage `review-a95e68fe42dc56fa`, revisión consumida `sha256:a2453ad991598a6300d4cdb589538831d059a3b9d826196c189f853b89e3fb94`. El ciclo termina ahí: no se reutiliza esa autoridad ni se inicia otra revisión del código idéntico. Evidencia externa: `/tmp/b1-rdd-cierre.json` y capturas en `/tmp/b1-rdd-native-9b5ftw4h/`.

Se mantienen 57 B1, 953 normales previas, 508 PostgreSQL B1, 451 baseline, cuatro probes SQL y un spot-check CAS: no se atribuyen nuevas ejecuciones a RDD. **B1-C permanece abierta e inconclusa**, no reparada ni atribuida definitivamente al entorno. Tampoco se demuestra autorización de familias, antimalware, transferencia remota ni recuperación operativa por el solo hecho de aprobar la estructura SQL.

Este registro y el estado de la tabla son un **delta documental posterior al reconocimiento**, no bytes cubiertos automáticamente por el candidato aprobado. Se comprobará estructuralmente y mediante assess nativo separado; su identidad y resultado se conservarán fuera del candidato para evitar un bucle de cambios documentales. No hubo ni se autorizan commit, push, PR, merge, nuevas migraciones compartidas, Supabase real o excepciones de tamaño en este cierre. Risk: ítems 1/2/4 del cambio SQL ya revisado; el delta nuevo sólo documenta hechos observados y B1-C sigue abierta.


### L23 — Excepción de tamaño y commit local B1 autorizados (2026-11-04)

El usuario autorizó explícitamente, sólo para B1, «8 archivos, 720 líneas (707 añadidas y 13 eliminadas)», candidato `sha256:c6a15154f9f79b3c73d9b2fa5198d531d13cd29e933cdf382c3759e7fd5d10f9`, más este registro documental mínimo, y el commit local `feat(documentos): registrar operaciones durables de carga documental`. Justificación: conservar como una unidad coherente las invariantes SQL, sus pruebas y la trazabilidad ODD/RDD ya verificadas. No se extiende a B2 ni otras unidades. Se conserva la evidencia 953 normales/508 PostgreSQL (57 B1), verificación independiente, cuatro lentes sin hallazgos y reconocimiento nativo; no se repiten pruebas de ejecutables idénticos. Este delta documental requiere assess propio, sin atribuirle automáticamente la aprobación anterior. B1-C permanece ABIERTA e inconclusa, sin reparación demostrada. No se autorizan push, PR, merge, despliegue, Supabase real ni bases compartidas; las cinco entradas ajenas permanecen excluidas. El SHA y las comprobaciones finales se registran en el cierre externo para no generar otro cambio posterior al commit.
