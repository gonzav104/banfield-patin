# AGENTS.md — Banfield Patín Carrera

## Propósito

Este repositorio contiene la aplicación web de gestión de Banfield Patín Carrera.

El sistema debe resolver, en este orden de prioridad:

1. gestión de deportistas;
2. carpeta digital de documentación;
3. empadronamiento de nuevos atletas;
4. gestión anual de aptos físicos;
5. organización de asistencia a competencias;
6. exportación de Listas de Buena Fe APM y Nacional;
7. gestión de rifas;
8. landing pública institucional de Banfield Patín Carrera.

No agregar funcionalidades fuera del alcance sin una decisión explícita documentada.

## Arquitectura general

Monorepo:

```text
banfield-patin/
├── backend/
├── frontend/
├── docs/
├── infra/
├── AGENTS.md
└── CLAUDE.md
```

Arquitectura objetivo:

```text
Frontend React + TypeScript
        |
        | HTTPS / REST
        v
Backend Spring Boot
        |
        +--> PostgreSQL en Supabase
        |
        +--> Supabase Storage privado
```

El backend es la autoridad de negocio y seguridad.

El frontend NO debe acceder directamente a datos sensibles en Supabase.

## Actores

### ADMIN

Representa al técnico/delegado autorizado.

Reglas:

- no existe registro público como ADMIN;
- ADMIN no se muestra como opción a las familias;
- el acceso administrativo se realiza por una ruta específica;
- inicialmente existirán como máximo dos administradores;
- ambos tienen permisos administrativos completos en el MVP.

### FAMILIA

Cuenta del padre, madre o tutor.

Puede:

- gestionar únicamente los deportistas vinculados a su familia;
- cargar documentación;
- cargar aptos físicos;
- confirmar o rechazar asistencia a competencias;
- consultar rifas y números asignados;
- mantener datos autorizados de su grupo familiar.

### DEPORTISTA

En el MVP es una entidad del dominio, no un usuario autenticado.

No crear cuentas propias para deportistas salvo que el alcance sea modificado explícitamente.

## Multi-escuela

El MVP se implementa para Banfield Patín Carrera.

El modelo debe quedar preparado para otras escuelas mediante `escuela_id` en las entidades raíz relevantes.

No implementar todavía:

- superadministrador;
- planes;
- facturación;
- suscripciones;
- configuración SaaS;
- onboarding de nuevas escuelas.

## Reglas de dominio

- No eliminar deportistas que dejan de competir: marcarlos como inactivos.
- Los datos deportivos que cambian por año deben pertenecer a la temporada.
- No duplicar información que ya existe y continúa vigente.
- DNI y documentación reutilizable deben conservarse mientras sean válidos y necesarios.
- El apto físico es anual.
- La familia puede estar vinculada a uno o más deportistas.
- Un deportista puede conservar historial de temporadas.
- La Lista de Buena Fe se genera desde deportistas confirmados para una competencia.
- Las plantillas oficiales APM/Nacional son la referencia para exportación.
- Los formatos externos no deben convertirse en el modelo interno de dominio.
- Un código externo de categoría debe derivarse de datos internos cuando sea posible.

## Seguridad obligatoria

La aplicación manejará datos de menores y documentos de identidad.

Por lo tanto:

- ningún DNI, apto o documento federativo puede almacenarse en un bucket público;
- no exponer URLs permanentes de archivos sensibles;
- usar almacenamiento privado y acceso temporal/autorizado;
- nunca exponer `SUPABASE_SERVICE_ROLE_KEY` al frontend;
- no guardar secretos en Git;
- no confiar en roles enviados por el cliente;
- toda autorización se valida en backend;
- una familia nunca puede acceder a deportistas de otra familia;
- registrar eventos administrativos sensibles cuando corresponda;
- validar tipo MIME, tamaño y extensión de archivos;
- no registrar contenido sensible en logs;
- proteger rutas `/admin/**` con autorización real de backend;
- ocultar una ruta no constituye una medida de seguridad.

## Base de datos

- PostgreSQL.
- Producción: Supabase PostgreSQL.
- Migraciones: Flyway.
- Hibernate debe usar `ddl-auto=validate`.
- Prohibido usar `ddl-auto=create`, `create-drop` o `update`.
- Toda modificación del esquema se realiza mediante una nueva migración Flyway.
- No editar migraciones ya aplicadas en entornos compartidos.
- Nombres de tablas y columnas: español, `snake_case`.
- IDs preferentemente UUID salvo justificación.
- Mantener claves foráneas, restricciones e índices explícitos.

## Archivos

PostgreSQL almacena metadatos.

Supabase Storage almacena el archivo.

Ejemplo conceptual:

```text
documentos
- id
- deportista_id / tutor_id
- tipo
- storage_key
- mime_type
- tamanio
- estado
- fecha_carga
```

Nunca guardar binarios grandes directamente en PostgreSQL sin una decisión explícita.

## Integración con Federación

Los formularios externos de Federación/Asociación no están bajo nuestro control.

Objetivo del MVP:

- reutilizar los datos ya cargados;
- preparar documentación;
- reducir carga manual;
- permitir autocompletado del Google Form cuando sea técnicamente confiable;
- dejar revisión humana antes del envío.

No implementar automatización frágil de envío silencioso.

No hardcodear identificadores `entry.*` sin encapsularlos en una configuración/mapeo reemplazable.

## Listas de Buena Fe

El MVP DEBE exportar:

- LBF APM;
- LBF Nacional;
- variantes oficiales necesarias según la plantilla vigente.

Reglas:

- partir de las plantillas oficiales;
- preservar estructura, formato y fórmulas cuando corresponda;
- no inventar una plantilla alternativa si existe una oficial;
- los datos deben provenir del módulo Deportistas + Temporada + Inscripción a competencia;
- validar campos obligatorios antes de exportar.

## Rifas

MVP:

- crear rifa;
- definir rango/cantidad de números;
- asignar números a familias;
- registrar disponible/asignado/vendido;
- registrar pendiente/cobrado;
- visualizar recaudación.

No integrar pasarelas de pago en el MVP salvo decisión explícita.

## Idioma y nomenclatura

- Documentación: español.
- Dominio: español.
- Entidades de negocio: español.
- Tablas/columnas: español.
- Endpoints: español cuando expresen dominio.
- Evitar mezclar inglés y español sin necesidad técnica.

Ejemplos:

```text
Deportista
Familia
Tutor
Temporada
AptoFisico
Empadronamiento
Competencia
InscripcionCompetencia
Rifa
```

## Calidad

Antes de considerar una tarea terminada:

1. compilar;
2. ejecutar tests relevantes;
3. revisar errores/warnings;
4. validar permisos si afecta seguridad;
5. validar migraciones si afecta persistencia;
6. verificar que no se hayan agregado secretos;
7. describir cambios realizados;
8. indicar riesgos o trabajo pendiente.

## Prohibiciones

No:

- implementar todo el MVP en una sola tarea;
- introducir microservicios;
- crear dependencias innecesarias;
- cambiar stack sin justificarlo;
- crear tablas manualmente desde Hibernate;
- exponer documentos sensibles;
- crear registro público ADMIN;
- permitir selección de rol en registro familiar;
- borrar historial deportivo para “simplificar”;
- inventar campos de Federación no respaldados por los requisitos;
- realizar refactors masivos no solicitados.

## Fuente de verdad

Prioridad:

1. `docs/REQUERIMIENTOS.md`;
2. decisiones documentadas en `docs/`;
3. este archivo;
4. código existente.

Si hay contradicción, detenerse y señalarla antes de tomar una decisión irreversible.
