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
