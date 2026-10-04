# frontend/CLAUDE.md — Claude Code Frontend

## Antes de editar

1. leer `../AGENTS.md`;
2. leer `./AGENTS.md`;
3. revisar rutas existentes;
4. revisar cliente HTTP;
5. revisar componentes reutilizables;
6. revisar `docs/REQUERIMIENTOS.md` si existe;
7. presentar plan breve.

## Comandos

Desde `frontend/`:

```bash
npm install
npm run dev
npm run build
npm run test
npm run lint
```

Usar los scripts reales del `package.json`; no inventar scripts si no existen.

## TypeScript

- `strict: true`;
- evitar `any`;
- modelar tipos por dominio;
- no duplicar tipos de API en múltiples carpetas;
- diferenciar DTOs API de estado de UI cuando sea necesario.

## Componentes

Crear componentes cuando:

- existe reutilización real;
- mejora legibilidad;
- encapsula una interacción significativa.

No fragmentar una pantalla en decenas de componentes triviales.

## Datos

Toda comunicación con backend debe pasar por una capa central.

Ejemplo:

```text
src/
├── api/
├── features/
├── components/
├── layouts/
├── routes/
├── schemas/
└── types/
```

No es obligatorio respetar exactamente esta estructura si el proyecto ya tiene una coherente.

## TanStack Query

Usar claves estables:

```text
['deportistas']
['deportistas', id]
['competencias', id]
```

Invalidar sólo lo necesario después de mutaciones.

No usar TanStack Query como almacén de estado UI.

## Formularios

Patrón:

```text
Zod schema
-> React Hook Form
-> submit mutation
-> feedback usuario
-> invalidación query
```

Mantener validaciones alineadas con backend.

Backend sigue siendo autoridad final.

## Admin

No crear:

- selector de rol;
- registro ADMIN;
- enlace público prominente al login ADMIN.

Rutas administrativas deben tener guard de navegación por UX, sabiendo que la seguridad verdadera vive en backend.

## Familia

Diseñar pensando primero en teléfono.

La carga documental debe requerir la menor cantidad de pasos posible.

No pedir nuevamente datos vigentes ya disponibles.

## Diseño de errores

Distinguir:

- error de validación;
- 401;
- 403;
- 404;
- error de red;
- error de servidor.

Mostrar mensajes accionables.

No mostrar stack traces ni respuestas técnicas crudas.

## Accesibilidad

Cada nueva pantalla debe ser usable:

- con teclado;
- con labels;
- con foco correcto;
- con mensajes de error asociados;
- sin depender únicamente de color.

## Archivos

Antes de upload:

- validar formato;
- validar tamaño;
- mostrar preview cuando corresponda;
- mostrar progreso si la API lo permite.

Nunca usar una URL de Supabase construida manualmente desde el cliente para documentos privados.

## Definition of Done Frontend

Antes de cerrar:

```bash
npm run lint
npm run test
npm run build
```

Si algún script no existe, indicarlo y ejecutar los disponibles.

Verificar además:

- sin `console.log` olvidados;
- sin `any` injustificados;
- sin secretos;
- responsive básico;
- estados loading/error/empty;
- accesibilidad básica;
- integración coherente con API.
