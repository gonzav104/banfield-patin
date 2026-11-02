# Spike federativo: prototipo experimental

Este directorio conserva evidencia y herramientas **offline** para estudiar los formularios federativos de 2026. No es un worker de producción ni automatiza el envío. El estado y los límites de L1/L2 están en `odd/tasks/automatizacion-federacion-spike.md` §§12.6–12.10.

## Uso seguro

1. Ejecutar únicamente las pruebas locales: `npm test` (no abre Playwright).
2. Revisar `docs/auditoria-federacion-spike.md` antes de compartir código o evidencias.
3. No ejecutar `ejecutar-fase-b.mjs` mientras siga vigente la suspensión de pruebas en vivo. El archivo está aislado en `tools/`, requiere simultáneamente `NODE_ENV=development` y `FEDERACION_SPIKE_EXPERIMENTAL=1`, y rechaza otros entornos antes de iniciar el cliente MCP. Esa compuerta técnica **no sustituye** una autorización humana específica.

El generador sólo permite en la URL el nombre institucional público de `escuela.club`, con valor y rótulo revisados en código. No acepta DNI, CUIL, nombres, contactos, fechas, datos médicos ni estados deportivos, aunque se solicite `sensibilidadMaxima: 'alta'`. Agregar otro campo o valor requiere una nueva revisión de privacidad y pruebas. No usar información real de menores para probar este prototipo.

Los informes persistidos por el ejecutor se construyen con una lista cerrada de estados, booleanos y contadores. Se descartan respuestas de controles, alertas textuales, datos esperados/actuales, URLs completas y texto libre de herramientas MCP. La bitácora conserva sólo acciones y resultados codificados. El cliente MCP admite únicamente las seis herramientas que usa este experimento; aun así, `browser_click` y `browser_evaluate` siguen siendo capacidades activas. La extensión y el navegador pueden tener sus propias superficies de registro, fuera del control de este repositorio: no usarlos con datos reales.

## Estado de los componentes

| Estado | Componentes y alcance |
| --- | --- |
| Validado offline | Normalización y comparación de esquemas, contratos y perfiles 2026, datos derivados, política de URL y sanitización de reportes. `npm test` verifica lógica local, no Google Forms. |
| Evidencia L1 limitada | Lecturas de precarga previamente validadas por el usuario; no prueban seguridad con datos reales ni estabilidad futura del formulario. |
| Experimental, detenido | Cliente MCP, sesión de Chrome/Playwright Extension y `ejecutar-fase-b.mjs`; no se ejecutan en esta unidad. |
| Pendiente | L2 concluyente, carga de adjuntos, envío, robustez en tres deportistas y operación con formularios modificados. No hay autorización para retomar esas pruebas. |

Si la Federación modifica un formulario, conservar esquema crudo, contrato y perfil por versión/año; comparar offline entradas, tipos, obligatoriedad, opciones, validaciones y saltos; detener mapeos incompatibles y exigir revisión humana antes de actualizar el perfil. Nunca trasladar automáticamente identificadores `entry.*` a otro formulario.
