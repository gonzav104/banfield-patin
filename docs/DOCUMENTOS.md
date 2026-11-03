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
