# backend/AGENTS.md — Backend

## Stack

- Java 21.
- Spring Boot.
- Maven.
- Spring Web.
- Spring Data JPA.
- Spring Security.
- Bean Validation.
- PostgreSQL.
- Flyway.
- Supabase PostgreSQL.
- Supabase Storage privado.
- JUnit 5.
- Testcontainers para integración cuando corresponda.

Agregar OpenAPI/Swagger cuando se implemente la API pública del backend.

## Arquitectura

Usar monolito modular.

Organización recomendada por dominio:

```text
com.banfieldpatin.backend
├── seguridad
├── usuarios
├── familias
├── deportistas
├── documentos
├── federacion
├── competencias
├── listasbuena
├── rifas
├── escuelas
└── compartido
```

Dentro de cada módulo, mantener responsabilidades claras:

```text
controller
service
repository
dto
model
mapper (si corresponde)
```

No crear una arquitectura ceremonial con capas vacías.

## Entidades principales

El dominio esperado incluye, como mínimo:

- Escuela
- Usuario
- Familia
- Tutor
- Deportista
- FamiliaDeportista
- Temporada
- DeportistaTemporada
- Documento
- AptoFisico
- Empadronamiento
- Licencia
- Competencia
- InscripcionCompetencia
- PlantillaLbf
- ExportacionLbf
- Rifa
- NumeroRifa

No crear todas las entidades de una vez si la tarea sólo necesita un subconjunto.

## Persistencia

- Flyway es la fuente de verdad del esquema.
- `spring.jpa.hibernate.ddl-auto=validate`.
- No usar `CascadeType.ALL` por defecto.
- Definir cascadas sólo cuando la semántica de propiedad sea clara.
- Evitar `EAGER` por defecto.
- Prevenir N+1 mediante consultas explícitas, `join fetch`, entity graphs o proyecciones según el caso.
- No serializar entidades JPA directamente desde controllers.
- Usar DTOs.
- No exponer IDs internos o datos sensibles innecesarios.

## Multi-escuela

Toda consulta de negocio sensible debe quedar limitada por la escuela correspondiente.

No confiar únicamente en recibir `escuelaId` desde el cliente.

La escuela debe derivarse de la identidad/autorización cuando corresponda.

## Autenticación y autorización

Roles:

```text
ADMIN
FAMILIA
```

Reglas:

- no hay endpoint público para crear ADMIN;
- una FAMILIA no puede autoasignarse roles;
- ADMIN puede gestionar datos de su escuela;
- FAMILIA sólo puede operar sobre deportistas vinculados;
- validar autorización en backend, no sólo en frontend;
- responder 401 para no autenticado y 403 para autenticado sin permiso.

Preferencia de sesión:

- autenticación gestionada por Spring Security;
- token/sesión en cookie `HttpOnly`, `Secure` en producción y `SameSite` apropiado;
- no almacenar tokens sensibles en `localStorage`.

La estrategia concreta de autenticación (JWT HS256 en cookie `HttpOnly`, CSRF, invitaciones, bootstrap de ADMIN y riesgos aceptados) está documentada en `../docs/SEGURIDAD.md`.

## Supabase

Supabase se usa como infraestructura, no como sustituto del backend.

### PostgreSQL

Spring Boot accede mediante conexión de servidor.

Variables esperadas:

```text
DB_URL
DB_USERNAME
DB_PASSWORD
```

### Storage

Bucket privado para documentos.

Variables de backend:

```text
SUPABASE_URL
SUPABASE_SERVICE_ROLE_KEY
SUPABASE_BUCKET_DOCUMENTOS
```

La `service_role` nunca sale del backend.

No almacenar URL pública permanente.

Guardar `storage_key`.

Las descargas deben requerir autorización.

## Documentos

Tipos iniciales esperados:

- DNI deportista frente;
- DNI deportista dorso;
- DNI tutor frente;
- DNI tutor dorso;
- apto físico;
- solicitud de licencia;
- otros documentos federativos.

Validar:

- MIME;
- extensión;
- tamaño;
- propietario;
- vínculo familiar;
- estado.

Estados posibles:

```text
CARGADO
APROBADO
OBSERVADO
VENCIDO
```

No borrar un documento observado automáticamente.

## Apto físico

Debe almacenar:

- deportista;
- temporada;
- documento;
- fecha de emisión;
- nombre del médico;
- matrícula;
- estado;
- fecha de presentación si corresponde.

Estados:

```text
PENDIENTE
CARGADO
APROBADO
OBSERVADO
PRESENTADO
```

Debe existir como máximo un apto activo/principal por deportista y temporada, salvo que el dominio defina versionado.

## Empadronamiento

Estados esperados:

```text
PENDIENTE
LISTO_PARA_PRESENTAR
PRESENTADO
EMPADRONADO
OBSERVADO
```

La aplicación no debe marcar automáticamente a un atleta como empadronado sin una confirmación administrativa.

## Competencias

Tipos iniciales:

```text
APM
NACIONAL
```

Inscripción:

```text
SIN_RESPONDER
CONFIRMADO
NO_PARTICIPA
```

La fecha límite debe validarse en backend.

Una familia sólo responde por sus deportistas.

## LBF

La exportación es parte del MVP.

Usar plantillas oficiales.

Preferencia técnica para XLSX: Apache POI, salvo una alternativa mejor ya presente.

No reconstruir la estética desde cero si la plantilla oficial puede rellenarse.

Validar todos los campos obligatorios antes de exportar.

Si faltan datos, devolver errores accionables por deportista/campo.

## Rifas

Modelo MVP simple.

Estados de número:

```text
DISPONIBLE
ASIGNADO
VENDIDO
```

Estado de pago:

```text
PENDIENTE
COBRADO
```

No implementar contabilidad formal ni gateway de pagos en el MVP.

## Validación

Usar Bean Validation en DTOs.

No confiar en validaciones del frontend.

Ejemplos:

- DNI sólo formato esperado;
- email válido;
- teléfono normalizado;
- fechas coherentes;
- importes no negativos;
- rangos de números válidos.

## Manejo de errores

Implementar un manejador global consistente.

Formato recomendado:

```json
{
  "codigo": "DOCUMENTO_NO_AUTORIZADO",
  "mensaje": "No tenés permiso para acceder al documento.",
  "detalles": []
}
```

No devolver stack traces al cliente.

## Logs y auditoría

No loguear:

- DNI completo salvo necesidad controlada;
- tokens;
- claves;
- contenido de documentos;
- contraseñas.

Registrar acciones administrativas relevantes:

- aprobación/observación de documentos;
- cambios de estado federativo;
- generación de LBF;
- cambios importantes de rifas.

## Testing

Cada módulo debe cubrir:

- reglas de negocio;
- autorización;
- validaciones;
- persistencia crítica.

Usar Testcontainers cuando una prueba dependa de comportamiento real de PostgreSQL y haya Docker disponible.

Las pruebas que necesitan PostgreSQL real usan Testcontainers (`postgres:17-alpine`, requiere Docker), llevan `@Tag("db")` (anotación `@PruebaDb`), quedan excluidas de `./mvnw clean verify` y se ejecutan con `./mvnw verify -Pdb-tests`. El contenedor es local y descartable: nunca Supabase ni una base compartida. Detalle en `../docs/SEGURIDAD.md`, sección 11.

No sustituir todos los tests de integración por mocks.

## Rendimiento

Con 20–25 deportistas actuales no optimizar prematuramente.

Sí evitar desde el inicio:

- N+1;
- carga completa de documentos para listados;
- consultas sin índices sobre claves frecuentes;
- endpoints que realizan una consulta por cada deportista.

Listados deben poder paginarse aunque inicialmente el volumen sea bajo.
