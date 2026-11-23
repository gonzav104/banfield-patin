# Vínculos explícitos entre tutores y deportistas

Una fila de `tutor_deportista` representa una vigencia. El tipo declarado no acredita representación legal ni modifica el acceso a deportistas o documentos. V14 crea la tabla vacía: ni `tutor.parentesco` ni la cantidad de deportistas de una familia generan asociaciones.

## Consulta ADMIN

`GET /api/admin/deportistas/{deportistaId}/vinculos-tutores` lista solo vigentes por defecto. `historial=true` incluye las vigencias cerradas. `page` y `size` siguen la paginación administrativa (0 y 20 por defecto; máximo 100). Orden: alta más reciente primero, con el ID como desempate.

- Deportista sin vínculos: `200` y `contenido: []`. Deportista inexistente o de otra escuela: `404 DEPORTISTA_NO_ENCONTRADO` uniforme.
- Campos: ID de la vigencia, IDs de tutor y familia, nombre y apellido del tutor, tipo declarado nullable, fecha de alta, fecha y causa de cierre si corresponden, y revisión decimal como cadena. No se incluyen DNI, contactos, cuentas ni datos de idempotencia.
- El acceso requiere el rol ADMIN. No se agrega acceso familiar ni se cambian los permisos documentales en C1.

## Integridad y secuencia de instalación

Las FKs permanentes mantienen la referencia histórica y obligan a que tutor y deportista coincidan con la misma familia y escuela. Las FKs de estado exigen padres activos solo para una vigencia abierta. El índice parcial permite varios responsables, pero solo una vigencia abierta para cada par tutor–deportista. Ni la reactivación de un padre ni un cambio del tipo declarado pueden reabrir un historial.

1. **Instalar V14 antes del backend nuevo.** Mantener las altas de responsables deshabilitadas. No modificar V1–V13 ni ejecutar un backfill.
2. Antes de habilitar altas en un futuro despliegue autorizado, verificar que todas las instancias y procesos capaces de escribir usen el backend que cierra vigencias antes de desactivar un padre o revocar `familia_deportista`. Drenar también las transacciones antiguas. Con vigencias abiertas la FK rechaza las escrituras antiguas incompatibles: preserva los datos, pero no preserva la disponibilidad de esas operaciones.
3. Si existen vigencias abiertas, **no volver a un escritor incompatible**. Deshabilitar nuevas altas no elimina las vigencias existentes. Para recuperar el servicio, conservar una versión compatible o corregir hacia adelante.

La instalación de V14 no habilita altas en producción ni modifica los valores por defecto de `DOCUMENTOS_CARGA_HABILITADA` y `DOCUMENTOS_STORAGE_HABILITADO`.
