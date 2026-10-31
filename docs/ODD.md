# ODD — Organic Driven Development en este proyecto

Guía corta del protocolo **real** que usamos. No reemplaza los contratos de Gentle AI: los referencia.

## Fuente de verdad

Los contratos viven fuera del repositorio, en la instalación de OpenCode:

| Contrato | Qué define |
| --- | --- |
| `~/.config/opencode/skills/_shared/persistence-contract.md` | Cuándo hay artefacto durable y cómo se escribe |
| `~/.config/opencode/skills/_shared/engram-convention.md` | Nombre del topic, recuperación, lectura de ambos lados |
| `~/.config/opencode/skills/_shared/research-lifecycle.md` | Investigación opcional y toma de decisiones |
| `~/.config/opencode/skills/_shared/skill-resolver.md` | Handoff de skills a subagentes |
| `~/.config/opencode/skills/_shared/review-ledger-contract.md` | Review nativo RDD (carril separado) |

Regla: si este documento y un contrato difieren, gana el contrato. Este archivo solo traduce el protocolo al contexto del proyecto.

## Qué artefactos corresponden

| Situación | Artefacto |
| --- | --- |
| Lectura, auditoría, explicación, revisión | Ninguno. No se escribe nada. |
| Cambio chico y entendido (se reanuda con el pedido + `git diff`) | Ninguno. Trabajo inline. |
| Implementación grande autorizada | `odd/tasks/{feature}.md` + espejo en Engram |

No hay skills ODD invocables en este entorno: ODD es el workflow del orquestador, no una skill. Por eso no se registra en `.atl/skill-registry.md`.

## Artefacto de trabajo

- Local: `odd/tasks/{feature-name}.md`
- Engram: topic `odd/{feature-name}/tasks`, con el documento completo y el locator relativo, no un resumen.
- Se escriben **antes** de la primera escritura de código, sin pedir permiso.
- Se leen de vuelta ambos: no son atómicos.
- Si Engram falla: se conserva el local y el espejo queda marcado pendiente.
- `mem_save` es upsert por topic: leer antes de actualizar y no borrar progreso previo.

Estructura fija, contenido estable primero y bitácora al final:

1. Encabezado de 2–3 líneas: objetivo, rama, estrategia de entrega, runner.
2. `## Specs`: requisitos numerados `S#`, citando **verbatim** los pedidos del usuario. No se agregan requisitos que el usuario no pidió; los supuestos se marcan como supuestos.
3. `## Tasks`: una línea por tarea con ID, `S#`, ruta (inline/delegada), estado y evidencia o commit.
4. `## Log`: `L1` es el pedido original literal; después correcciones, evidencia y decisiones.

Un cambio de requisito agrega su entrada literal en `## Log`, reescribe solo el `S#` afectado y reabre solo su tarea.

## Ejecución

- Rutas: inline (por defecto) o delegada, cuando hay motivo nombrado: paralelismo real o contexto largo.
- Test-first cuando existe un test determinista y un resultado esperado: RED observado, GREEN, refactor.
- Unidades de trabajo de ~400 líneas autoradas; tests y docs junto al comportamiento.
- Commits convencionales en la rama de feature. Push, PR y merge son decisiones humanas.
- Un checkbox marcado no es aprobación: la evidencia es el test o el commit que lo respalda.

## Verificación y cierre

Una unidad se cierra solo con evidencia observada:

```bash
cd backend
./mvnw clean verify          # suite normal, sin Docker
./mvnw verify -Pdb-tests     # Testcontainers PostgreSQL 17
```

Además, según el cambio:

- OpenAPI actualizado en la misma entrega (RNF-14).
- Migración Flyway nueva si cambia el esquema; nunca editar una aplicada.
- Sin secretos ni datos personales en logs.
- Documentación del módulo actualizada.
- Riesgos y pendientes informados, incluidos los checks fallidos o no ejecutados.

## Relación con lo anterior

- Las memorias `sdd/*` son historial de la metodología anterior. No se migran ni se reescriben.
- El trabajo nuevo usa `odd/{feature}/tasks`.
- RDD (review) es un interruptor del usuario y un carril separado: no se activa ni se simula desde acá.
