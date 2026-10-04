# CLAUDE.md — Instrucciones globales para Claude Code

## Objetivo

Trabajá sobre Banfield Patín Carrera como un producto real, no como un ejercicio académico.

Priorizá:

1. corrección;
2. seguridad;
3. mantenibilidad;
4. simplicidad;
5. experiencia de usuario.

## Antes de programar

En cada tarea:

1. leé el `AGENTS.md` raíz;
2. leé el `AGENTS.md` y `CLAUDE.md` del subproyecto afectado;
3. inspeccioná el código existente;
4. revisá `docs/REQUERIMIENTOS.md` cuando la tarea afecte comportamiento;
5. identificá archivos que vas a modificar;
6. planteá un plan breve;
7. recién después implementá.

Si el pedido contradice los requisitos o arquitectura, indicá el conflicto.

## Forma de trabajo

Preferir tareas verticales y pequeñas.

Ejemplo:

```text
Deportista:
migración -> persistencia -> servicio -> API -> tests
```

Evitar:

```text
"Implementar todo el backend"
```

No modificar archivos no relacionados con la tarea.

No realizar “mejoras” amplias sin pedirlas.

## Decisiones

Podés tomar decisiones locales y reversibles.

Pedí confirmación antes de:

- cambiar el stack;
- modificar una regla de negocio;
- cambiar el modelo de autorización;
- eliminar datos;
- cambiar el contrato de una API ya utilizada;
- introducir una infraestructura nueva;
- modificar una plantilla oficial de LBF;
- reemplazar la estrategia de autenticación;
- automatizar envíos hacia sistemas externos.

## Dependencias

Antes de agregar una dependencia:

1. explicar qué problema resuelve;
2. verificar si ya existe una solución en el stack;
3. preferir librerías maduras;
4. evitar dependencias para tareas triviales.

## Seguridad

Nunca:

- mostrar secretos;
- imprimir secretos en logs;
- leer y copiar `.env` a respuestas;
- incorporar credenciales reales en código;
- enviar claves de Supabase al cliente;
- desactivar seguridad para “hacer funcionar” un test;
- crear accesos públicos a documentos sensibles.

Si necesitás una variable, agregala a `.env.example` con valor ficticio.

## Git

No hacer automáticamente:

- `git push`;
- force push;
- rebase destructivo;
- borrado de ramas;
- commits masivos;

salvo que el usuario lo pida expresamente.

Sí podés:

- revisar `git status`;
- revisar `git diff`;
- proponer commits;
- ejecutar tests y builds.

## Migraciones

Toda modificación de PostgreSQL:

```text
V2__descripcion.sql
V3__descripcion.sql
...
```

No modificar `V1` si ya fue aplicada fuera de un entorno descartable.

## Criterio de finalización

Al terminar una tarea, informar de forma breve:

- qué cambió;
- archivos principales;
- tests ejecutados;
- resultado;
- decisiones relevantes;
- riesgos o TODO pendientes.

No declarar una tarea correcta si los tests/build no fueron ejecutados o fallaron.

## Alcance MVP

El MVP incluye:

- landing pública;
- ADMIN y FAMILIA;
- módulo Deportistas;
- familias/tutores;
- documentación privada;
- empadronamiento;
- aptos físicos;
- competencias;
- confirmación de participación;
- exportación LBF APM/Nacional;
- rifas.

Fuera del MVP por defecto:

- cuentas de deportistas;
- gamificación;
- cartas estilo FIFA;
- estadísticas deportivas avanzadas;
- pagos online;
- microservicios;
- SaaS completo;
- aplicación móvil nativa.

## Comunicación

Sé concreto.

Si encontrás un problema real, señalalo aunque no coincida con la hipótesis inicial.

No asumas que una decisión previa es correcta sólo porque ya existe.
