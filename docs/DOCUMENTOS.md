# Documentos privados — consulta de metadatos (D1)

Esta unidad consulta la tabla existente `gestion_patin.documento`. No integra Storage ni habilita cargas, descargas, revisión, reemplazos o borrado. No cambia migraciones ni determina vigencia anual de WADA, retención, escaneo o límites operativos.

## API

| Método y ruta | Rol | Alcance |
| --- | --- | --- |
| `GET /api/admin/deportistas/{id}/documentos` | ADMIN | Deportista de su escuela, incluso inactivo |
| `GET /api/admin/tutores/{id}/documentos` | ADMIN | Tutor de su escuela, incluso inactivo |
| `GET /api/familia/deportistas/{id}/documentos` | FAMILIA | Vínculo ACTIVO y familia activa, sin exigir deportista activo ni vínculo principal |
| `GET /api/familia/tutores/{id}/documentos` | FAMILIA | Tutor activo de su propia familia activa; no requiere vínculo con un deportista |

Cada ruta recibe `page` (0 por defecto) y `size` (20 por defecto). Se reutiliza `Pagina`: página negativa pasa a 0, tamaño acotado a 1–100. Orden estable: creadoEn descendente, id ascendente. No se admiten selectores de escuela/familia para ampliar alcance.

Respuesta: `Pagina<DocumentoMetadatos>`, con `contenido`, `pagina`, `tamanio`, `totalElementos`. Cada fila contiene únicamente `id`, `tipo`, `estado`, `temporadaId`, `creadoEn`, `vigente`, `fechaVencimiento`, `motivoObservacion`. El motivo se devuelve sólo para OBSERVADO, null en otros estados. No hay nombre original, MIME/tamaño, claves, bucket, URL ni identidad de quien cargó/revisó.

Sólo versiones `vigente=true`; no se filtra por aprobación ni temporada activa. Leer un estado existente no implica aprobar transiciones ni una vigencia anual. No hay historia o detalle individual documental en D1.

- 401 sin sesión válida; 403 por rol incorrecto; 400 para parámetros no convertibles.
- 404 `DEPORTISTA_NO_ENCONTRADO` / `TUTOR_NO_ENCONTRADO`, igual para padre inexistente, de otra escuela o fuera de alcance familiar.
- Padre autorizado sin archivos o página fuera de rango: 200 con contenido vacío, no 404.
- Nuevas rutas sólo GET; protección de sesión, roles y CSRF existentes sin cambios.
- Respuestas privadas sin caché pública, incluso en errores. No se aceptan URLs o keys del cliente.

## Consultas y auditoría

Proyecciones DTO explícitas sin relaciones EAGER ni serialización de entidades. Cada consulta de datos y su conteo aplican el mismo predicado escuela/propietario/alcance. Se reutilizan los predicados familia-deportista existentes; tutor usa un predicado propio de tutor/familia, compartido entre contenido, conteo y comprobación de existencia.

Si la página tiene contenido no se consulta el propietario por separado; si está vacía se comprueba su existencia dentro del mismo alcance para distinguir de un padre ajeno. La cantidad de consultas es constante, no crece por documento. Con página completa puede haber SELECT de datos y count; el camino vacío agrega SELECT de existencia. El INSERT JDBC de auditoría es adicional y no lo cuenta la estadística de Hibernate.

`DOCUMENTOS_CONSULTADOS` registra escuela, usuario, propietario como recurso, cantidad de filas de la página y número de página. No copia metadatos de Storage, motivos, nombres o valores personales al detalle. La transacción no es readOnly porque agrega auditoría; falla sin entregar resultados si no puede registrar el acceso. No modifica Documento ni propietarios. No se registran éxitos para 404.

Se revalida sesión en cada petición y el SQL aplica alcance en cada lectura. Una revocación ya confirmada impide peticiones posteriores. No se promete cancelar datos de una petición autorizada que ya estaba ejecutándose; esta unidad no introduce locks ni nuevas garantías para futuras cargas.

## Decisiones y pendientes

O1-A y O2-A aprobadas en `DECISIONES.md`. O2 es sólo diseño: no existe adaptador Storage en D1. Estados/reemplazos son base conceptual; transiciones/restricciones deben verificarse antes de migrar. WADA anual, retención, escaneo y límites siguen pendientes.

Plan y evidencia: `odd/tasks/documentos-privados.md`. Pruebas de SQL real usan PostgreSQL 17 local descartable, nunca Supabase. No utilizar datos personales reales en pruebas. Auditoría general consultable/retención operativa y nuevos workflows corresponden a unidades posteriores, sin afirmar cumplimiento completo de toda Fase 3 con este listado.

## Integridad SQL D2 (V5)

V5 no habilita operaciones de escritura ni modifica el DTO D1. Añade `serie_id` (ID de la raíz histórica) y `version` (contador SQL no negativo, inicial 0). D1 sigue siendo una vista JPA inmutable. El soporte de versión **no implementa** por sí solo bloqueo optimista: el futuro mutador deberá usar versión esperada y comprobar filas afectadas, sin exponer mutadores en esta unidad.

- DNI frente/dorso: propietario compatible, sin temporada, como máximo uno vigente por propietario/tipo. Cada versión histórica conserva su registro.
- OTRO admite múltiples series independientes, con como máximo una vigente por serie. SOLICITUD_LICENCIA y CERTIFICADO_WADA tienen únicamente unicidad por serie, sin anualidad ni cardinalidad por temporada. APTO_FISICO no recibe reglas específicas de Fase 4.
- CHECK/FK e índices únicos impiden propietario incompatible, cruces de escuela/propietario/tipo/serie, autorreferencias, bifurcaciones y vigentes duplicados. Las FK por rama usan MATCH SIMPLE: el XOR de V1 exige que una de ellas siempre se compruebe cuando existe predecesor.
- Un trigger acotado deriva la serie, compara la temporada nullable del predecesor e impide modificar ID, escuela, propietario, tipo, temporada, serie y predecesor. El padre debe existir antes de insertar al hijo; el contexto y las aristas son inmutables, por lo que no se pueden construir ciclos por INSERT/UPDATE ordinarios. No implementa transiciones administrativas ni permisos de API.
- Las restricciones permiten cero vigentes en una serie, no exigen exactamente una ni fuerzan que la vigente sea la última. Un reemplazo atómico futuro deberá desactivar e insertar en una misma transacción; no se ha implementado ese workflow.

### Aplicación de la migración

Flyway ejecuta V5 transaccionalmente y toma un bloqueo exclusivo sobre Documento antes de prevalidar. Puede bloquear consultas/escrituras durante la migración: debe planificarse su ventana operativa antes de un despliegue; aquí sólo se ejecuta en PostgreSQL descartable local. Los conflictos abortan sin reparar ni seleccionar una versión ganadora. El backfill asigna la misma raíz a toda cadena válida; conserva todos los valores V4 y sus timestamps. El trigger de timestamp se deshabilita sólo dentro de esa transacción para el backfill y se restablece antes del commit.

No se editaron V1–V4. Una base con historia incompatible necesita resolución humana explícita antes de aplicar V5; no se supone vacía. La migración no sustituye la autorización por escuela/familia ni la revocación de vínculos en las consultas D1. El propietario de la base puede desactivar triggers o restricciones: esa administración privilegiada queda fuera de las garantías de D2.

## Adaptador interno de Storage (D3a)

`AlmacenDocumentos` encapsula `RestClient`: sólo creación POST sin sobrescritura (`x-upsert=false`) y eliminación compensatoria del objeto recién confirmado. El backend genera UUID aleatorios, sin nombres personales, DNI/CUIL ni nombres originales en las claves. No existen entradas para rutas, un borrado genérico, URLs públicas, descargas o endpoints de escritura. D1 no consume este puerto y mantiene su contrato.

El resultado `Creado` es una capacidad interna, no un DTO: permite obtener la clave para futura persistencia y compensar una sola vez desde la misma instancia emisora. No puede reconstruirse a partir de una clave guardada; un fallo consume el intento y requiere una decisión de reconciliación futura. No utilizarlo para borrar versiones históricas. El adaptador no coordina PostgreSQL/Storage ni implementa una política de compensación integral.

### Configuración externa, desactivada por defecto

| Variable | Uso |
| --- | --- |
| `DOCUMENTOS_STORAGE_HABILITADO` | `false` por defecto; no habilitar contra un servicio real en D3a |
| `SUPABASE_URL` | Origen HTTPS, sin ruta, usuario, query ni fragmento; HTTP sólo con loopback IP para pruebas |
| `SUPABASE_BUCKET_DOCUMENTOS` | Nombre de un bucket privado ya existente; un segmento, nunca una ruta |
| `SUPABASE_SERVICE_ROLE_KEY` | Secreto de servidor; no guardar en Git, frontend ni reportes |
| `DOCUMENTOS_STORAGE_TIMEOUT_CONEXION` | Conexión, `PT3S` por defecto |
| `DOCUMENTOS_STORAGE_TIMEOUT_RESPUESTA` | Respuesta completa, `PT15S` por defecto |

Ambos plazos deben estar entre 1 ms y 5 minutos: límites técnicos para impedir esperas ilimitadas, no límites funcionales de carga. La configuración habilitada inválida falla al iniciar; desactivada no exige secretos, no crea el adaptador y no efectúa llamadas de arranque. No se crea ni inspecciona ningún bucket real en esta entrega. Antes de cada POST, el adaptador exige una lectura GET de metadatos del bucket que confirme `public=false`; si falta la confirmación, no inicia la carga. Los fallos de esa lectura no implican una creación incierta. La privacidad efectiva y permisos deberán comprobarse también antes de autorizar cargas reales: usar service_role o una ruta no pública no vuelve privado un bucket público. Cambios administrativos posteriores o concurrentes a esa lectura quedan fuera del control del adaptador.

`crear` recibe bytes no vacíos y MIME concreto sin parámetros; todavía no verifica firma real, límite funcional de tamaño, autorización de usuario ni antimalware. Rechazar parámetros impide transportar nombres personales en MIME. Se utiliza un cliente dedicado sin interceptores globales, redirecciones ni reintentos de aplicación. No habilitar trazas HTTP del JDK, captura de cabeceras/cuerpos, proxies de inspección o volcados de memoria con datos reales: contienen credenciales y documentos independientemente del saneamiento de excepciones.

### Fallos y límites de la evidencia

Los fallos tienen códigos internos (`ENTRADA_INVALIDA`, `AUTENTICACION`, `CONFLICTO`, `RECHAZADO`, `RED`, `TIMEOUT`, `RESPUESTA_INESPERADA`) y bandera de resultado incierto. No retienen el cuerpo del proveedor, URI, cabeceras o causa original. HTTP 409 se clasifica como conflicto; respuestas 400, incluidas variantes antiguas de conflicto, se rechazan sin volver a intentar. Sólo se acepta la confirmación 200 con clave exacta; un 2xx inesperado o confirmación inválida no significa éxito. Las respuestas se limitan a 16 KiB.

Timeouts y fallos de transporte, respuestas 5xx/redirecciones o confirmaciones no válidas pueden dejar un objeto privado huérfano. No se devuelve recibo de éxito ni se intenta borrar automáticamente en ese caso; D3b deberá decidir cómo correlacionar y reconciliar esos resultados sin filtrarlos al cliente. Un error al compensar no prueba que el objeto siga existiendo ni que se haya borrado. No se promete reintento seguro tras perder un recibo/reiniciar el proceso.

Pruebas: HTTP loopback simulado y configuración Spring, además de regresión D1/D2. No equivalen a validar Supabase real, políticas RLS/bucket, rendimiento de archivos grandes ni comportamiento frente a cambios de versión del proveedor. Antes de D3b siguen pendientes autorización/revalidación, transacción de publicación, correlación durable, compensación/reconciliación, límites/MIME real/escaneo y retención. No se aprueban por arrastre.

## Registro durable SQL — B1 de D3b

V6 añade sólo `operacion_carga_documental`: identidad, escuela/solicitante/propietario, tipo/temporada, bucket y UUID opaco, idempotencia, manifiesto SHA-256/MIME/bytes, fase, versión, reserva, Documento resultante y fecha. El id de operación reserva el futuro id de Documento; el UUID Storage es distinto. Se reutilizan FK compuestas de V1. Tres índices: PK y dos UNIQUE (escuela/solicitante/idempotencia, bucket/objeto). No hay backfill ni cambios de V1–V5.

Propietario exclusivo; compatibilidad de tipo/propietario y DNI sin temporada; APTO FÍSICO no se habilita. OTRO conserva múltiples series. No se agrega anualidad ni cardinalidad por temporada para WADA/solicitud. Un manifiesto tiene todos sus campos o ninguno y no cambia tras asignarse. SQL sólo valida estructura: SHA de 32 bytes, MIME permitido y tamaño positivo; no prueba contenido, límite configurado de archivo, scanner ni existencia remota.

Transiciones: RESERVADA→TRANSFIRIENDO/CERRADA; TRANSFIRIENDO→ALMACENADA/RECONCILIANDO; ALMACENADA→PUBLICADA/RECONCILIANDO; RECONCILIANDO→ALMACENADA/CERRADA. PUBLICADA/CERRADA son terminales y no conservan reserva. Contexto/destino/idempotencia son inmutables. No hay purga SQL habilitada en B1: olvidar una operación permitiría reutilizar una idempotencia o perder un resultado incierto; esto no establece una retención legal indefinida.

### Contrato CAS obligatorio para futuros mutadores

Cada sentencia operativa debe incluir `WHERE id=:id AND version=:esperada` y comprobar exactamente una fila afectada. Cero filas significa versión perdida o fila no disponible; no continuar. El trigger exige `version=version+1` e impide cambios sin incremento, pero no puede inferir la versión que tenía en memoria un cliente que construya SQL arbitrario. Las pruebas ejecutan las sentencias CAS de referencia; no existe todavía un repositorio mutador. Un usuario con SQL privilegiado no queda convertido en cliente autorizado por este mecanismo.

La adquisición comprueba además reserva nula/vencida y fase permitida, usando reloj PostgreSQL. La versión retornada se transporta como token de la ejecución y no se sustituye silenciosamente por una versión consultada después. El titular vigente puede renovar o renunciar a su reserva; renunciar dejándola vencida NO confirma fin de transferencia. Una TRANSFIRIENDO vencida no se renueva: sólo puede reclamarse para RECONCILIANDO con otra versión. No se fija duración ni límite de concurrencia en esta migración. Las sentencias con reserva activa que ya bloquearon la fila se serializan con los demás adquirentes.

### Vínculo de publicación y recuperación

PUBLICADA requiere Documento cuyo id coincide con la operación, misma escuela/propietario/tipo/temporada (NULL-safe), bucket, UUID de objeto convertido a clave canónica, MIME/tamaño y cargador. Debe ser raíz (`serie_id=id`, sin predecesor), vigente y CARGADO al publicar. El Documento debe insertarse antes del UPDATE a PUBLICADA, en la misma transacción futura; las pruebas SQL ejercitan commit/rollback de ambos. El trigger comprueba el contexto al publicar y bloquea esa fila, no implementa controles familiares ni protege contra administradores que desactiven restricciones. No modifica el Documento ni su modelo D1 inmutable.

Un commit incierto exige leer operación y Documento desde otra conexión antes de decidir. Un reinicio no prueba éxito externo. TRANSFIRIENDO o RECONCILIANDO sin respuesta permanecen sin resolver: reserva vencida, 404 remoto o cancelación local no demuestran ausencia definitiva del objeto. Ningún código B1 consulta/borra Storage ni interpreta CERRADA como resultado de un borrado real. Reconciliación, scanner, autorización y atomicidad con auditoría pertenecen a unidades posteriores y requieren pruebas propias.

No persistir nombres originales, DNI/CUIL, contenido, credenciales ni respuestas crudas en este registro. Identificadores y SHA de archivo son información interna; no deben filtrarse en URLs, logs ni DTO. B1 no añade API ni lectura de estas filas al frontend.


## B2 — Puerto recuperable, sin flujo documental

`prepararDestino()` genera sin tráfico un destino interno `(storage_bucket, objeto_id UUID)` para persistirlo **antes** del POST. `DestinoPreparado.desdeRegistro` rehidrata esos valores tras reinicio, valida el segmento como V6 y no admite rutas, nombres originales ni URLs. El adaptador exige que el bucket coincida exactamente con su configuración para cada alta o inspección. Estos tipos no son DTO HTTP; no deben serializarse al frontend. Sus métodos `toString` omiten las referencias. No hay cambios en V1–V6.

`crear(destino, InputStream, longitud, MIME)` sólo envía al UUID preparado, con `x-upsert=false`, y confirma el `Key` exacto. Una colisión es CONFLICTO; no se sobrescribe ni se compensa/reintenta automáticamente. La entrada se copia en bloques de **8 KiB**, sin materializar otra copia completa, y debe coincidir con su longitud declarada. MIME sintácticamente válido no equivale a validar el contenido: validación estructural y antimalware pertenecen a otras unidades. No se fija aquí el límite funcional de archivo ni la concurrencia del flujo futuro.

El stream es prestado, de un solo uso y propiedad del llamador: debe cerrarse con `try-with-resources`, incluso al fallar. No se reabre ni reproduce. Debe proceder de una fuente local controlada que permita terminar/cerrar la lectura; B2 no hace interruptible un `InputStream` arbitrario cuyo `read()` se bloquee. Un timeout no demuestra que cesó una escritura remota ni autoriza borrar o reenviar. Tras iniciar el POST, errores de red, confirmación inválida o discrepancia de longitud dejan resultado incierto. Un error de entrada o de comprobación previa del bucket no inicia el POST. La API D3a de `byte[]` y su recibo efímero conservan su comportamiento. `resultadoIncierto=false` se refiere al intento actual: nunca descarta que un escritor de un intento anterior continúe pendiente.

`inspeccionar(destino)` comprueba primero el bucket privado y usa **HEAD autenticado** sin descargar el documento. Sólo proyecta tamaño positivo y MIME sintácticamente válido; cabeceras ausentes, inválidas o inesperadas producen incertidumbre. La consulta no retorna URLs, cuerpos del proveedor, nombres, IDs ni ETag. **No ofrece un SHA-256 del contenido** ni presupone que ETag sea equivalente. Fuentes primarias consultadas para el contrato HTTP: [ruta HEAD autenticada](https://github.com/supabase/storage/blob/master/src/http/routes/object/getObjectInfo.ts) y [cliente oficial](https://github.com/supabase/storage-js/blob/main/src/packages/StorageFileApi.ts). La compatibilidad con una instalación concreta no se ha probado contra Supabase real.

| Resultado | Alcance de la observación |
| --- | --- |
| EXISTENCIA_CONFIRMADA | HEAD 200 y metadatos válidos en ese instante; no prueba contenido, publicación ni estabilidad posterior. |
| AUSENCIA_OBSERVADA | HEAD 404 después de comprobar el bucket; no prueba fin de un POST pendiente ni ausencia definitiva. |
| INCIERTA | Red, timeout, autenticación, bucket no privado, respuesta inesperada o metadatos inválidos; motivo tipado sin respuesta cruda. |

Una entrada/destino inválidos fallan antes de contactar con el proveedor. Los errores de inspección no se transforman en ausencia. Una reserva vencida o una observación 404 no habilitan reenvíos ni borrados automáticos; B1 mantiene la decisión durable pendiente.

**Compensación recuperable fuera de B2:** no se añade borrado por destino ni se reconstruye `Creado` desde la fila SQL. Se preserva `eliminarCreado(Creado)` de D3a, ligado a su emisor y consumible una vez, sólo para creación confirmada dentro de esa instancia. Una limpieza tras reinicio depende de B6/B8: autorización fresca, ausencia de publicación y control de escritores remotos tardíos deben demostrarse allí. B2 no implementa esos servicios, scanner, mutadores, endpoints ni reconciliación operativa. Storage sigue desactivado por defecto y los tests sólo usan HTTP sintético local.

La rehidratación presupone la configuración del mismo proyecto Storage asociado al registro PostgreSQL: V6 no persiste un origen remoto. Un cambio de URL/proyecto exige evaluación operativa de las operaciones pendientes, no una reasignación silenciosa por coincidencia de bucket/UUID. La comprobación de privacidad es previa a cada operación; no bloquea cambios administrativos posteriores del proveedor.

## Primera carga familiar — flujo vertical local

Esta sección incorpora una única escritura habilitable; las secciones D1/D2/D3a/B1/B2 anteriores conservan su alcance histórico. No habilita descargas, revisión, reemplazos, ZIP, tutores ni aptos físicos.

### Contrato HTTP y autorización

`POST /api/familia/deportistas/{id}/documentos`, con sesión FAMILIA y cabecera `X-XSRF-TOKEN` que contiene el valor crudo de la cookie CSRF. `Idempotency-Key` es un UUID obligatorio. Multipart admite exactamente un `archivo`, un `tipo` y, opcionalmente, un `temporadaId`; partes adicionales o duplicadas se rechazan. El id de escuela, familia y solicitante nunca procede del cliente.

Tipos permitidos: DNI_DEPORTISTA_FRENTE, DNI_DEPORTISTA_DORSO, SOLICITUD_LICENCIA, CERTIFICADO_WADA y OTRO. DNI sin temporada y sin reemplazo implícito si ya existe uno vigente. No se establece anualidad de WADA/solicitud ni cardinalidad por temporada; OTRO mantiene series independientes. Una temporada informada debe pertenecer a la escuela.

- `201`: nuevo `DocumentoMetadatos`, estado CARGADO, visible por el GET D1; sin Location hacia una descarga inexistente.
- `200`: repetición confirmada con mismo contexto y manifiesto, tras autorizar nuevamente; devuelve el mismo documento, sin otro POST a Storage ni nueva auditoría de carga.
- `401/403/404`: sesión/rol/acceso; padre ajeno, inexistente o vínculo no ACTIVO mantienen el 404 uniforme. No exige deportista activo ni vínculo principal, como D1.
- `400/415`: solicitud o contenedor multipart/tipo HTTP inválido; `413`: bytes reales excedidos.
- `422`: formato/estructura/contenido no admitido o detección positiva del scanner.
- `409`: clave/contexto diferentes, operación en curso/pendiente de resolución, versión perdida o DNI ya vigente.
- `429`: capacidad de procesamiento ocupada; no se encola otro archivo.
- `503`: integración apagada, scanner/infraestructura no disponibles o resultado no confirmable. No significa que el objeto/documento no exista.

La primera guarda de cabecera CSRF evita que el fallback a parámetros lea multipart antes de la autorización, también para métodos mutantes no implementados. Después de la autorización general, cualquier multipart fuera del único POST documental se rechaza con 415 sin llegar al procesador; no se habilitan archivos en consultas GET ni otros endpoints. Los cuerpos JSON conservan su tratamiento previo. Spring Security sigue validando el token, la sesión y el rol; después, otro filtro comprueba el alcance y adquiere capacidad **antes** del DispatcherServlet/multipart. No se confía en atributos enviados por el cliente.

Al publicar se bloquean escuela → usuario → familia (SHARE), deportista (helper existente de vinculación) y vínculo (SHARE); se revalidan sesión, expiración y predicado D1. Son filas comunes a los mutadores administrativos de revocación/desactivación. Documento, transición PUBLICADA y auditoría DOCUMENTO_CARGADO confirman juntos. Se acota la espera de locks; no se promete ausencia universal de deadlocks. Se conserva la semántica vigente de JWT: el logout borra cookie pero no existe revocación persistida de jti; esta entrega no inventa invalidación servidor por logout.

### Contenido, scanner y recursos

Límite inicial 5.000.000 bytes (configurable con `max-bytes`), contado al copiar, además del límite servlet por archivo y por solicitud (64 KiB adicionales para el envoltorio). Extensión y MIME deben corresponder a PDF/JPEG/PNG; no bastan para aceptar contenido. No se conserva el nombre original: Documento recibe el nombre técnico neutro `documento`.

PDFBox 3.0.8 inspecciona el PDF estricto y su grafo COS, sin ejecutar acciones; se rechazan cifrado, JavaScript, adjuntos y acciones externas peligrosas/XFA. Se limita a 1000 páginas, 50.000 objetos y 32 MB descomprimidos examinados. PNG valida firma/chunks/CRC/final y JPEG marcadores/segmentos/final; ambos se decodifican con ImageIO, se rechazan advertencias, y se acotan dimensiones (16.000 por lado) y píxeles (16 millones por defecto). No es una garantía de inocuidad absoluta.

La inspección ocurre en una JVM hija sin entorno heredado de secretos, con heap de 128 MiB, metaspace de 96 MiB y plazo inicial de 10 segundos; al vencer se fuerza su terminación y se espera antes de retirar el temporal. Estos límites no equivalen a un límite de RSS de todo el servicio ni a un sandbox del sistema operativo. Pico de RSS medido por JVM hija con los límites iniciales: ~125 MB para un JPEG de 16 MP, ~218 MB para un JPEG progresivo de 16 MP, ~146 MB para un PNG RGBA de 16 MP y ~111 MB para una foto de 12,2 MP; con dos plazas, prever hasta unos 450 MB adicionales al proceso principal. Los errores del parser no se imprimen ni se devuelven. El empaquetado ejecutable debe conservar el launcher/clases/dependencias necesarios; su comprobación forma parte del cierre.

ClamAV usa TCP INSTREAM con IP privada literal, sin DNS ni rutas del host remoto; bloques de 8 KiB y plazo total de conexión/escritura/lectura. Sólo se acepta una confirmación exacta `stream: OK` terminada en NUL y cierre de conexión; detección, respuesta truncada/extraña, ERROR, exceso o indisponibilidad fallan cerrado. No incluye un scanner de producción ni valida aquí sus firmas. El protocolo TCP de clamd no cifra ni autentica: debe aislarse y no exponerse a Internet. El operador deberá validar firmas, límites de escaneo, alertas de límites y acceso efectivo antes de habilitarlo. Fuentes: [protocolo ClamD](https://docs.clamav.net/manual/Usage/ClamdProtocol.html), [seguridad de PDFBox](https://pdfbox.apache.org/security.html).

Temporales con directorios 0700 y archivos 0600, UUID/nombres técnicos, fuera del repositorio; eliminación al terminar la petición, también ante rechazo. Un directorio no privado se rechaza sin cambiar sus permisos. No se establece retención legal; caída abrupta del proceso/disco o fallo de eliminación requieren procedimiento operativo supervisado, no una purga automática nueva. No habilitar trazas HTTP/JDBC de parámetros, volcados de memoria o captura de cuerpos con documentación real.

### Consistencia y estados inciertos

V6 conserva contexto, destino opaco y manifiesto SHA-256/MIME/bytes antes de transferir. Se usan sus transiciones y CAS de versión recibida, sin recargar una versión para que un trabajador tardío publique. El scanner, la copia/inspección y el único POST B2 ocurren fuera de transacciones. No hay transacción PostgreSQL abierta durante esos I/O.

Ante pérdida de confirmación, timeout, versión perdida, revocación o fallo de publicación, se conserva la operación y el objeto. Marcar RECONCILIANDO identifica resolución pendiente: no inspecciona, reenvía ni borra remotamente. Si no puede confirmar ese cambio, se conserva el estado anterior; no se afirma éxito. Un commit de publicación puede haber confirmado aunque falle la respuesta: repetir la misma clave/contexto/manifiesto permite recuperar el documento si ya está PUBLICADA. Una operación no publicada no se reanuda automáticamente.

No se interpreta un 404 como fin de una transferencia pendiente ni ETag como SHA-256. No se usa la compensación efímera D3a como borrado recuperable. B6/B8 deberán resolver autorización y ausencia de publicación de forma supervisada; no existe aquí su reconciliación ni eliminación genérica.

### Configuración y demostración aislada

`DOCUMENTOS_CARGA_HABILITADA=false` por defecto; además requiere el adaptador Storage habilitado y configurado explícitamente. Propiedades externas `banfield.documentos.carga.*`: `max-bytes`, `temporales`, `max-pixeles`, `procesamiento`, `scanner-plazo`, `scanner-host`, `scanner-puerto`, `concurrencia`, `reserva`. Valores iniciales: 5 MB decimales, 16 millones de píxeles, 10 s parser, 20 s scanner, loopback:3310, dos plazas y reserva de tres minutos. Dos plazas constituyen una guarda de recursos probada, **no** una capacidad definitiva/benchmark de producción. Ajustar infraestructura, timeouts, reserva, límites del servlet y protección ante clientes lentos antes de habilitar tráfico real; una reserva vencida no habilita reenvíos.

Desde `backend/`, después de resolver dependencias aprobadas:

```bash
./mvnw -o -Dtest='*CargaTest,PreparadorDocumentoTest,ClamAvPrivadoTest,CargaDocumentalSeguridadWebMvcTest' test
DOCKER_HOST=unix:///var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 ./mvnw -o -Pdb-tests -Dtest=CargaDocumentalHttpDbTest test
./mvnw -o clean verify
DOCKER_HOST=unix:///var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 ./mvnw -o -Pdb-tests verify
```

La prueba HTTP arranca PostgreSQL 17 exclusivo, Tomcat en puerto aleatorio, HTTP Storage y TCP scanner sintéticos, sesiones/familias y archivos generados. Los dobles sólo existen en código/configuración de test y no se empaquetan como proveedores de producción. No sustituye Supabase/ClamAV reales, permisos efectivos, hardening, análisis de capacidad, recuperación de temporales tras caída ni revisión operativa de V5/V6. B1-C permanece ABIERTA sin causa raíz demostrada. Flyway sigue habilitado al arrancar Spring Boot: no iniciar contra una base compartida sin autorización y evaluación de migraciones pendientes.
