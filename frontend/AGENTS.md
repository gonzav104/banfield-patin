# frontend/AGENTS.md — Frontend

## Stack

- React.
- TypeScript estricto.
- Vite.
- React Router.
- TanStack Query.
- React Hook Form.
- Zod.
- Tailwind CSS.
- shadcn/ui.
- Axios o cliente HTTP centralizado.
- Vitest.
- Testing Library.
- Playwright para flujos E2E críticos cuando corresponda.

## Objetivo UX

Existen tres zonas visuales:

### Sitio público

Landing de Banfield Patín Carrera.

Debe comunicar:

- identidad de la escuela;
- información institucional;
- horarios;
- contacto;
- actividades/competencias relevantes;
- acceso para familias.

### Portal Familia

Prioridad mobile-first.

Tareas principales:

- ver hijos/deportistas;
- completar datos pendientes;
- cargar documentación desde celular;
- cargar apto físico;
- confirmar asistencia a competencias;
- ver números de rifas;
- visualizar avisos y pendientes.

### Panel ADMIN

Orientado a escritorio pero responsive.

Tareas principales:

- gestionar deportistas;
- revisar documentación;
- gestionar empadronamientos;
- gestionar aptos;
- crear competencias;
- ver confirmaciones;
- exportar LBF;
- gestionar rifas.

ADMIN no debe aparecer como opción visible para familias.

## Rutas

Base esperada:

```text
/                    landing
/login               login familia
/registro            registro familia
/familia/*           portal familia

/admin/login         acceso admin
/admin/*             panel admin
```

No colocar enlaces hacia `/admin/login` en la navegación pública salvo decisión explícita.

Conocer la URL de administración no otorga seguridad; el backend debe autorizar.

## Autenticación

- no guardar tokens sensibles en `localStorage`;
- enviar cookies seguras mediante `withCredentials` si la estrategia backend usa cookies;
- no inferir autorización únicamente por elementos ocultos;
- manejar 401 y 403 explícitamente;
- nunca permitir que el registro elija rol ADMIN.

## Estado y datos

TanStack Query para datos de servidor.

No usar `useEffect + fetch` como patrón principal de obtención de datos.

Centralizar cliente HTTP.

Separar:

- server state → TanStack Query;
- formulario → React Hook Form;
- validación → Zod;
- UI local → estado React cuando sea necesario.

No introducir Redux/Zustand sin necesidad real.

## Formularios

Usar React Hook Form + Zod.

Mostrar:

- errores junto al campo;
- estados de carga;
- confirmación clara;
- campos obligatorios;
- ayuda contextual.

No replicar un Google Form gigante en una única pantalla.

Para empadronamiento dividir en pasos lógicos.

## Carga de documentos

Experiencia pensada para celular.

Debe permitir:

- elegir archivo;
- tomar foto cuando el dispositivo lo permita;
- preview;
- repetir/reemplazar;
- mostrar formato/tamaño permitido;
- mostrar estado de revisión.

Estados visuales:

```text
Pendiente
Cargado
Aprobado
Observado
Vencido
```

Nunca exponer rutas internas de Storage.

## Accesibilidad

Mínimo:

- etiquetas asociadas a inputs;
- navegación por teclado;
- foco visible;
- contraste suficiente;
- mensajes de error accesibles;
- botones con nombres claros;
- no depender sólo del color para estados.

Objetivo: WCAG 2.1 AA en componentes principales.

## Responsive

Familia: mobile-first.

Admin:

- usable desde notebook/escritorio;
- funcional desde tablet;
- no romper en móvil.

Evitar tablas imposibles de usar en pantallas pequeñas; ofrecer tarjetas, scroll controlado o columnas prioritarias.

## Diseño

La landing puede tener identidad deportiva propia.

El panel debe priorizar claridad operativa.

No abusar de:

- gradientes;
- animaciones;
- glassmorphism;
- cards innecesarias.

La interfaz administrativa debe responder primero:

```text
¿Qué requiere atención?
¿Qué está pendiente?
¿Qué vence?
¿Qué competencia se aproxima?
```

## Módulo Deportistas

ADMIN debe poder:

- listar;
- buscar;
- filtrar activo/inactivo;
- crear;
- editar;
- consultar temporada;
- consultar familia;
- consultar documentación;
- consultar situación federativa.

No mezclar edición completa en una tabla.

## Competencias

Familia debe poder responder fácilmente:

```text
Sí participa
No participa
```

ADMIN debe ver:

```text
Confirmados
No participan
Sin responder
```

La fecha límite debe mostrarse claramente.

## LBF

La UI de exportación debe:

- indicar tipo APM/Nacional;
- indicar cantidad de confirmados;
- advertir datos faltantes;
- bloquear exportación inválida cuando corresponda;
- permitir descargar el archivo generado.

No hacer generación XLSX compleja en el navegador si corresponde al backend.

## Rifas

Familia:

- ver números asignados;
- marcar/consultar estado según las reglas definidas.

ADMIN:

- asignar;
- visualizar vendidos;
- visualizar cobrados;
- consultar recaudación.

No diseñar un marketplace de números en el MVP.

## Privacidad

Evitar mostrar DNI completo en listados generales.

Cuando sea necesario identificar:

```text
•••••322
```

o equivalente.

No mostrar documentos sensibles en previews masivas.

## Testing

Priorizar:

- formularios;
- permisos de navegación;
- estados de carga/error;
- carga documental;
- confirmación de carrera;
- exportación LBF desde UI;
- rifas.

Los tests frontend no sustituyen la autorización backend.

## Rendimiento

- evitar refetch innecesario;
- usar caché de TanStack Query correctamente;
- lazy-load de rutas pesadas cuando tenga sentido;
- no cargar imágenes/documentos completos en listados;
- comprimir imágenes antes de subir sólo si se conserva legibilidad.
