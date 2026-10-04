# backend/CLAUDE.md — Claude Code Backend

## Inicio de cada tarea

Antes de editar:

1. leer `../AGENTS.md`;
2. leer `./AGENTS.md`;
3. leer `../docs/REQUERIMIENTOS.md` si existe;
4. revisar migraciones;
5. revisar código del módulo afectado;
6. ejecutar `git status`;
7. presentar plan breve.

## Comandos

Desde `backend/`:

```bash
./mvnw clean test
./mvnw spring-boot:run
./mvnw clean verify
```

En Windows:

```powershell
.\mvnw.cmd clean test
.\mvnw.cmd spring-boot:run
.\mvnw.cmd clean verify
```

No asumir que Maven global está instalado.

## Configuración

Usar variables de entorno.

Nunca escribir valores reales en:

- `application.yml`;
- `application.properties`;
- tests versionados;
- README;
- migraciones.

Mantener `.env.example` sólo con nombres y valores ficticios.

## Migraciones

Antes de crear una migración:

1. revisar la última versión;
2. describir el cambio;
3. crear la siguiente `Vn__descripcion.sql`;
4. verificar compatibilidad con PostgreSQL/Supabase;
5. ejecutar tests de integración pertinentes.

No reescribir migraciones aplicadas.

## Implementación por módulo

Orden preferido:

```text
migración (si hace falta)
-> modelo/repositorio
-> DTOs
-> servicio
-> autorización
-> controller
-> tests
-> documentación API
```

No generar CRUD mecánico sin revisar reglas de negocio.

## Seguridad

Para cualquier endpoint nuevo preguntarse:

1. ¿requiere autenticación?
2. ¿qué rol puede usarlo?
3. ¿cómo se determina la escuela?
4. ¿cómo se demuestra que una familia puede acceder al deportista?
5. ¿expone datos personales innecesarios?
6. ¿hay riesgo de IDOR?

Tests de autorización obligatorios para endpoints sensibles.

## Documentos

Para upload/download:

- no devolver `storage_key` como mecanismo de acceso público;
- validar ownership antes de emitir acceso;
- limitar tamaño;
- validar tipos permitidos;
- usar nombres internos generados;
- conservar nombre original sólo como metadata;
- sanitizar metadatos de archivo.

## APIs

Convenciones:

```text
/api/admin/...
/api/familia/...
```

o una alternativa equivalente centralizada mediante autorización.

No duplicar lógica de negocio sólo por separar rutas.

Usar sustantivos del dominio en español:

```text
/api/deportistas
/api/competencias
/api/rifas
```

## Definition of Done Backend

Antes de terminar:

```bash
./mvnw clean verify
```

Además:

- sin secretos;
- sin migraciones inconsistentes;
- sin entidades expuestas directamente;
- permisos validados;
- errores consistentes;
- cambios resumidos.

Si `verify` falla, no declarar terminado.
