# Fase 2 — Spike de automatización federativa (`automatizacion-federacion-spike`)

Objetivo: determinar si Playwright puede completar los formularios reales de empadronamiento y aptos físicos de la federación (con adjuntos) sin duplicar la carga.
Rama: `feat/automatizacion-federacion-spike` (desde `main` `d367137`). Estrategia de entrega: `ask-on-risk`. Runner: Node.js 24 + Playwright 1.64 (aún no instalado en el repo).
Estado: **DESCUBRIMIENTO PARCIAL — mapeo real pendiente; la conexión a un Chrome ya autenticado está definida y a la espera de que el usuario instale la extensión y recargue la sesión (V6–V8, §10).** Sin implementación, sin push ni merge.

## Specs

- **S1 (O12, textual).** "Utilizar una cuenta Google autorizada, con autenticación manual." / "Explorar los formularios oficiales sin enviar respuestas ni adjuntar archivos." / "Utilizar datos personales ficticios y archivos sintéticos." / "Ejecutar pruebas completas con archivos únicamente en una copia de prueba autorizada o en un entorno controlado." / "No guardar credenciales ni sesiones dentro del repositorio." / "No automatizar CAPTCHA, MFA ni otros mecanismos de seguridad." / "No utilizar información real de menores durante el spike."
- **S2.** "No enviar respuestas, no subir archivos a formularios oficiales y no utilizar datos reales de menores. Las pruebas de carga de archivos deberán realizarse en un entorno de prueba autorizado."
- **S3.** "No implementar todavía el worker, no modificar Spring Boot ni Supabase, no hacer push ni merge."
- **S4.** "Proponer un spike independiente en Node.js + Playwright, sin integrarlo todavía con Spring Boot."
- **S5.** "Proponé una arquitectura reutilizable para formularios de otros años, sin fijar los identificadores de Google Forms dentro de la lógica de negocio."
- **S6.** "Diferenciá el formulario de presentación federativa de la ficha médica, cuyo contenido clínico corresponde al profesional."
- **S7.** Criterios de éxito mínimos ya aprobados (REQUERIMIENTOS §7): padrón ≥ 90 % de los campos que no son archivos; apto 100 % de los que no son archivos; repetir con ≥ 3 deportistas sin errores de secciones/validaciones.

## Tasks

| ID | Specs | Tarea | Ruta | Estado |
| --- | --- | --- | --- | --- |
| D1 | S1 | Verificar accesibilidad anónima de ambos formularios | inline | **hecho** (V1) |
| D2 | S6 | Leer ficha médica, nota CAP y Reglamento Nacional 2026 | inline | **hecho** (V2–V4) |
| D3 | S1,S2 | Mapear campos, obligatoriedad, opciones, validaciones, secciones y subida de archivos | inline | **pendiente: necesita un Chrome autenticado al que conectarse** (§10) |
| D4 | — | Comparar con lo que guarda el backend | inline | **preliminar** (§5); se completa con D3 |
| D5 | S4,S5,S7 | Arquitectura, diseño del spike y criterios | inline | **propuesta** (§6, §7) |
| I1… | S3 | Implementación del spike | — | **no autorizada** |

## Log

**L1 — pedido original (2026-11-01, textual).**

> Vamos a iniciar el descubrimiento de la Fase 2 del backend de Banfield Patín Carrera: `automatizacion-federacion-spike`. Trabajamos con Organic Driven Development (ODD). **Objetivo:** determinar si Playwright puede completar automáticamente los formularios reales de empadronamiento y aptos físicos de la federación, incluyendo archivos adjuntos, sin duplicar la carga de información. **Decisión O12:** Utilizar una cuenta Google autorizada, con autenticación manual. Explorar los formularios oficiales sin enviar respuestas ni adjuntar archivos. Utilizar datos personales ficticios y archivos sintéticos. Ejecutar pruebas completas con archivos únicamente en una copia de prueba autorizada o en un entorno controlado. No guardar credenciales ni sesiones dentro del repositorio. No automatizar CAPTCHA, MFA ni otros mecanismos de seguridad. No utilizar información real de menores durante el spike. **Descubrimiento:** 1. Actualizar `main` y verificar el cierre de la Fase 1. 2. Registrar la evidencia ODD pendiente de temp-5. 3. Revisar los formularios federativos de empadronamiento y aptos físicos. 4. Identificar todos sus campos, validaciones, secciones condicionales y archivos requeridos. 5. Determinar cómo Playwright manejaría la autenticación Google y la carga de archivos. 6. Identificar riesgos de cambios en los formularios, bloqueos de automatización y manejo de sesiones. 7. Proponer un spike independiente en Node.js + Playwright, sin integrarlo todavía con Spring Boot. 8. Definir criterios de éxito y fracaso verificables. 9. Identificar qué datos deberá almacenar nuestra aplicación para completar los formularios automáticamente. 10. Separar lo que puede validarse contra los formularios oficiales de lo que requiere un entorno de prueba. **Entregables:** Plan ODD. Mapeo preliminar de formularios. Diseño del spike. Estrategia de autenticación y archivos. Riesgos y dependencias externas. Criterios de aceptación. Por ahora, solo descubrimiento y planificación. No implementar el worker, no subir documentos reales, no enviar formularios, no tocar Supabase y no realizar push ni merge. Esperar aprobación antes de implementar.

**L2 — enlaces y alcance (2026-11-02, textual, resumen de las instrucciones).** Formularios: Empadronamiento 2026 `https://forms.gle/AkdJR7r1yFMKmsuZ7`; Aptos físicos 2026 `https://forms.gle/aUSDJwWwHa7cWmAVA`. Documentación: ficha médica oficial, nota CAP y Reglamento Nacional 2026 (enlaces de Drive). "Autorizo continuar exclusivamente el descubrimiento": 1. verificar la accesibilidad de ambos formularios con un navegador controlado; 2. identificar campos, obligatoriedad, opciones, validaciones y secciones condicionales; 3. analizar los controles de subida de archivos y sus restricciones; 4. comparar con los datos del backend; 5. identificar qué campos se completan automáticamente y qué documentos se recuperan del expediente digital; 6. evaluar si los enlaces precompletados complementan a Playwright; 7. diferenciar el formulario de presentación federativa de la ficha médica; 8. proponer una arquitectura reutilizable para otros años sin fijar los identificadores de Google Forms en la lógica de negocio; 9. registrar resultados y limitaciones verificadas en el plan ODD. "No enviar respuestas, no subir archivos a formularios oficiales y no utilizar datos reales de menores." "No implementar todavía el worker, no modificar Spring Boot ni Supabase, no hacer push ni merge." Entregables: mapeo real de ambos formularios, limitaciones encontradas y propuesta de implementación del spike.

**L3 — evidencia de `temp-5` registrada** en `fase-1-cierre.md` y `temp-5-cierre.md` (commit `f025739`).

**L4 — 2026-11-02 (resumen textual del usuario).** "Confirmé que puedo iniciar sesión con la cuenta institucional de Banfield en Google Chrome normal de Windows y acceder a los formularios. Por lo tanto, el bloqueo ocurre específicamente cuando intentamos iniciar sesión desde Chrome controlado por Playwright. … No seguir intentando automatizar el login ni eludir las restricciones de Google." Después pidió usar la extensión oficial de Playwright para conectarse a un Chrome ya autenticado: "No volver a intentar automatizar el inicio de sesión de Google. No completar campos, adjuntar documentos ni enviar respuestas. No extraer ni guardar cookies, contraseñas o tokens."

## 1. Resultados verificados

**V1 — Accesibilidad (verificado dos veces).** Ambos enlaces cortos redirigen al formulario real y de ahí a `accounts.google.com` (cadena de `302` con `curl`; y con Chromium headless de Playwright, sin sesión, la página final es `accounts.google.com/v3/signin/identifier`, título "Formularios de Google: Acceso"). `FB_PUBLIC_LOAD_DATA_` (el JSON con el esquema completo del formulario) **no existe** sin sesión. Conclusión: **no se pueden leer anónimamente**; el esquema solo se obtiene con una sesión de Google iniciada por una persona. La causa probable es que el formulario tiene una pregunta de subida de archivos (Google exige inicio de sesión en ese caso); es una inferencia, no un hecho comprobado.

IDs de formulario resueltos (identifican el formulario, no son secretos; viven solo en este plan y nunca en el código de negocio): padrón `1FAIpQLSfZmFQWM2gZ8MgLCgaFjGc4CmLUJpFZc-ObR38Kk6baa5zcQA`, apto `1FAIpQLScQdYy9uaULjdGzVrua8NRGjDXdoA5L8z0H8t_JBudP7cUKww`.

**V2 — Ficha médica 2026 (PDF de una hoja, CAP/CNC).** No es el formulario de presentación: es el documento clínico que se escanea y se adjunta. Secciones:
- *Datos personales del deportista* (completa la familia): apellidos, nombres, DNI, fecha de nacimiento, grupo sanguíneo y factor, obra social y n.º de afiliado, domicilio, teléfono, localidad, provincia; y del padre/tutor: nombre, DNI, domicilio, teléfono.
- *Historia clínica* (32 ítems para marcar), *valoración funcional* (frecuencia cardíaca, tensión, talla, peso…), *evaluación clínica* (40 ítems), *evaluación podológica*, observaciones: **solo el médico**.
- *Constancia de aptitud* con firma y sello del médico, localidad y fecha; **validez de un año** (texto de la propia ficha).
- *Autorización y declaración jurada* con firma, aclaración y DNI del padre/tutor. Advierte que "los datos y firmas deben ser reales y verdaderas".

**V3 — Nota CAP (31/12/2025).** Solo remite el Reglamento Nacional 2026 de Patinaje de Velocidad a las afiliadas. Sin requisitos propios.

**V4 — Reglamento Nacional 2026 (101 páginas).** Evidencia relevante, con su ubicación en el texto:
- **Ficha médica anual con vigencia del 01/01/2026 al 31/12/2026, "independientemente de la fecha de emisión"**; se presenta el DNI y la ficha médica, "tanto en original como en formato PDF solo una vez, y se presentarán nuevas a medida que se incorporen corredores" (reunión de delegados, p. 12; en el área Escuelas, p. 72, "ficha médica adjuntando certificado de aptitud física anual").
- Art. 23: la **licencia nacional es obligatoria**; sin ella no se integra el padrón. La licencia, el n.º de deportista y el padrón viven en **DATASKATE**.
- Art. 125: el club debe tener empadronados a sus patinadores y archivar DNI y certificado de aptitud actualizado; Art. 127: la licencia deportiva se tramita por la federación; Art. 128: el padrón de Escuela y Transición lleva n.º de deportista, nombre y apellido, DNI, fecha de nacimiento y categoría.
- Para juveniles y mayores convocados a concentraciones o selección: análisis de sangre y orina y electrocardiograma semestrales. **Fuera de alcance** de una escuela.
- La inscripción en la web de la CAP **no** reemplaza el Excel con n.º de licencia, n.º de deportista, nombre, nacimiento, categoría, club y función.

**V5 — Entorno.** Node 24.21.0; Playwright 1.64.1 se instaló solo en un directorio de trabajo fuera del repo; hay Chromium en caché y **pantalla disponible (WSLg)**, por lo que un login manual en un navegador visible es viable. El MCP de Playwright de esta sesión busca Chrome estable (`/opt/google/chrome/chrome`), que no está instalado: no sirve para el spike y no se instala.

**V6 — Autenticación (limitación técnica NO resuelta).** Con Chrome 156 estable de Linux lanzado por Playwright (`channel: 'chrome'`, visible, perfil persistente fuera del repo, permisos `700`), la sesión de Google no se pudo completar: el navegador se cerró a los ~3 minutos sin llegar a los formularios (no se sabe si lo cerró el usuario o Google). El usuario confirmó que en Chrome normal de Windows el inicio de sesión funciona. No se volvió a intentar ni se intentó evitar el control. La causa exacta no se verificó; queda registrada como limitación del navegador **controlado** por Playwright.

**V7 — Cómo se conecta la extensión (verificado en el código de `playwright-core` 1.64.1 y `@playwright/mcp` 0.0.84).** El servidor MCP abre un relé WebSocket en un puerto aleatorio de **su propio** `localhost` y lanza el Chrome **de su propio sistema operativo** con `chrome-extension://mmlmfjhmonkocbjadbfplnigmagldckm/connect.html?mcpRelayUrl=ws://localhost:<puerto>…`. La extensión oficial es "Playwright Extension" de la Chrome Web Store (ese ID). Sin variable de token, la página de conexión espera la aprobación manual de la persona; con token, el servidor espera 30 s. La extensión se adhiere al navegador real (sesiones, cookies, extensiones), por eso la persona decide qué pestaña comparte. En 0.0.84 el flag es `--extension` (`--extension=chrome` falla con `unknown option`).

**V8 — WSL2 ↔ Windows (verificado).** Red de WSL2 en modo **NAT** (`wslinfo --networking-mode` = `nat`, sin `.wslconfig`): un servidor en Linux no alcanza el `localhost` de Windows ni al revés de forma garantizada, y un servidor MCP en Linux lanzaría el Chrome de Linux, que no tiene la extensión ni la sesión. Hay interop con Windows (`cmd.exe`), Node 20.19.5 y Chrome en `C:\Program Files\Google\Chrome\Application\chrome.exe`. **Prueba:** `cmd.exe /c "npx -y @playwright/mcp@0.0.84 --extension"` desde WSL por stdio respondió `initialize` y devolvió 25 herramientas (incluida `browser_evaluate`), sin abrir Chrome. Es decir, el servidor corre en Windows (relé y Chrome en el mismo `localhost`) y Claude Code en WSL lo usa por stdio. `cmd.exe` imprime un aviso por el directorio UNC y vuelve a `C:\Windows`; es inofensivo.

## 2. Hallazgo que corrige una premisa de la ficha

La ficha dice "validez por un año"; el Reglamento dice **año calendario de la temporada, sin importar la emisión**. La regla federativa manda. Hoy `apto_fisico` guarda `fecha_emision` y `documento.fecha_vencimiento`. **Recomendación (decisión del usuario en la Fase 4, no ahora):** derivar el vencimiento como el 31/12 del año de la temporada y no como emisión + 1 año.

## 3. Mapeo de los formularios — **PENDIENTE de sesión (D3)**

No se pudo mapear ningún campo real. Lo siguiente es **hipótesis no verificada**, tomada de REQUERIMIENTOS §7 y del Reglamento:

| Formulario | Campos que se espera (hipótesis) | Archivos esperados (hipótesis) |
| --- | --- | --- |
| Padrón 2026 (`AG_Padron_2026`) | DNI, apellido y nombre, fecha de nacimiento, categoría, club, datos de contacto/domicilio, datos del tutor | DNI frente y dorso del deportista (y del tutor), ficha médica |
| Apto 2026 (`AG_AptosFisicos2026`) | DNI, nombre, club, "empadronado", fecha, médico y matrícula | Escaneo del apto |

Para cerrar D3 hace falta, por formulario: lista y orden de preguntas, tipo, obligatoriedad, opciones exactas (texto), validaciones (texto, número, regex, longitud), secciones y saltos condicionales, y por cada pregunta de archivo: tipos permitidos, cantidad máxima, tamaño máximo y destino. Todo eso está en `FB_PUBLIC_LOAD_DATA_` y se lee **sin interactuar ni enviar** una vez que hay sesión.

## 4. Enlaces precompletados: evaluación

Estado: **conocimiento general, a verificar en el formulario real.**
- Google Forms admite un enlace con parámetros `entry.<id>=<valor>` que precarga preguntas de texto, opción, casilla, lista, fecha y hora. **No** precarga preguntas de archivo. Una opción solo se precarga si el valor coincide exactamente con el texto de la opción.
- Sirven como complemento (no sustituto) de Playwright: cubren los campos que no son archivos sin automatizar el navegador, y no necesitan guardar sesión. Playwright queda solo para lo que el enlace no cubre (secciones condicionales, verificación de lo precargado, archivos).
- **Riesgo de seguridad propio:** el enlace lleva DNI y datos de un menor en la URL (historial del navegador, registros de Google, `Referer`). Mitigación: generarlo solo por una acción explícita del ADMIN, no registrarlo ni persistirlo, abrirlo con `rel="noopener noreferrer"` y `Referrer-Policy: no-referrer`.
- Los identificadores `entry.*` cambian si se recrea el formulario (por ejemplo, el de 2027), por eso **no pueden estar en la lógica de negocio** (AGENTS.md ya lo exige).

## 5. Comparación preliminar con lo que guarda el backend

| Dato federativo (ficha, Reglamento) | ¿Lo guardamos? | Dónde |
| --- | --- | --- |
| Apellido, nombre, DNI, fecha de nacimiento | Sí | `deportista` |
| Domicilio, localidad, partido, código postal, teléfono, CUIL, nacionalidad | Sí | `deportista` |
| **Provincia** | **No** | falta (`localidad` y `partido` sí) |
| Categoría, rama, divisional, n.º de corredor | Sí | `deportista_temporada` |
| Tutor: nombre, apellido, DNI, teléfono, parentesco | Sí | `tutor` |
| **Domicilio del tutor** | **No** | falta |
| Club | Sí | `escuela.nombre_federativo` |
| N.º de licencia y tipo | Esquema sí, módulo no | `licencia` (Fase 5) |
| **N.º de deportista federativo** (DATASKATE) | **No** | decisión pendiente: ¿es el n.º de corredor? |
| Fecha de emisión, médico, matrícula del apto | Esquema sí, módulo no | `apto_fisico` (Fase 4) |
| Archivos (DNI, apto, ficha) | Esquema sí, módulo no | `documento` (Fase 3) |
| **Obra social, n.º de afiliado, grupo sanguíneo y factor** | **No** | **no incorporar sin decisión**: son datos de salud, sensibles |

Sin migración en esta fase: son solo hallazgos para `ROADMAP`.

## 6. Propuesta de arquitectura reutilizable (S5)

Separar **qué dato se necesita** de **cómo se carga en un formulario concreto**:

1. **Dominio (sin Google Forms):** claves canónicas (`deportista.dni`, `deportista.fechaNacimiento`, `tutor.1.dni`, `apto.medicoMatricula`, `documento.APTO_FISICO`…). Un resolvedor las toma de nuestros datos y devuelve valores canónicos.
2. **Perfil de formulario versionado por año (datos, no código):** archivo JSON, p. ej. `perfiles/padron/2026.json`, con la clave de cada pregunta, su `entry` actual, tipo, obligatoriedad, el mapa `valorInterno → etiqueta del formulario` para las opciones, las secciones condicionales y las preguntas de archivo (con la clave de documento). El ID del formulario viene de configuración, no del código. Un formulario de 2027 es **otro perfil**, sin tocar código.
3. **Descubrimiento y detección de cambios:** un comando de solo lectura extrae `FB_PUBLIC_LOAD_DATA_`, genera un borrador de perfil y calcula una huella del esquema (preguntas, tipos, opciones). Antes de completar, se compara con el perfil: si difiere, **se detiene** y avisa qué cambió. Es la defensa contra cambios del formulario.
4. **Dos estrategias detrás de una misma interfaz:** (a) enlace precompletado; (b) Playwright con navegador visible que completa y **se detiene antes de "Enviar"**, con revisión humana (lo que exige AGENTS.md: "No implementar automatización frágil de envío silencioso").
5. **No duplicar la carga:** clave de idempotencia = deportista + temporada + formulario. El estado `PRESENTADO` lo confirma una persona en nuestra aplicación; nunca se deduce de la automatización.

## 7. Diseño del spike y criterios

Ubicación propuesta: `tools/federacion-spike/` (directorio nuevo, independiente de Spring Boot). Dependencia: `playwright` (justificación: es el objetivo del spike; no hay alternativa en el stack). `.gitignore` incluye el perfil de navegador, las capturas y los artefactos.

**Autenticación.** Contexto persistente de Chromium **visible**, con el directorio de perfil **fuera del repo** (`~/.cache/banfield-federacion-spike/perfil`, configurable por variable de entorno) y una guarda que se niega a arrancar si esa ruta cae dentro del repo. La persona inicia sesión a mano; el spike nunca ve ni guarda credenciales, y si aparece CAPTCHA o verificación en dos pasos se detiene y devuelve el control a la persona.

**Archivos.** En Google Forms la pregunta de archivo abre un selector embebido de Google (a verificar). Hipótesis: se podría automatizar con `frameLocator` y `setInputFiles`. **Solo se prueba en una copia de prueba propiedad de la cuenta autorizada, con archivos sintéticos**; nunca en el formulario oficial.

**Fases y criterios verificables:**

| Fase | Qué se hace | Éxito | Fracaso |
| --- | --- | --- | --- |
| A. Mapeo (solo lectura, formulario oficial) | Con sesión manual, extraer el esquema de ambos formularios sin escribir nada | Esquema completo de los dos; perfil borrador generado; huella reproducible en 3 corridas | No se puede iniciar sesión en un navegador automatizado, o el esquema no es legible |
| B. Relleno sin envío (formulario oficial, datos ficticios) | Precompletar y completar con Playwright hasta antes de "Enviar"; leer de vuelta los valores del DOM | Padrón ≥ 90 % y apto 100 % de los campos que no son archivo; 3 deportistas ficticios, incluyendo ramas condicionales, sin errores de validación | Un campo obligatorio no se puede completar, o Google bloquea la automatización |
| C. Archivos (solo copia de prueba) | Subir PDF/JPG/PNG sintéticos en los límites declarados | 5 de 5 corridas suben bien y aparecen en la copia; límites (tipos, cantidad, tamaño) documentados | Más de 1 falla en 5 corridas: la subida queda manual |
| D. Robustez | Cambiar a propósito una pregunta de la copia | La detección de huella se detiene y nombra el campo cambiado | Completa con un formulario cambiado sin avisar |

**Decisión posterior:** si A y B pasan y C falla, el producto abre el enlace precompletado y deja la selección de archivo y el envío como tareas manuales (previsto en REQUERIMIENTOS §7). Si A falla, queda como solución estable la carpeta digital con descarga rápida.

**Qué se valida contra los formularios oficiales y qué requiere entorno de prueba:**

| Contra el formulario oficial (solo lectura, sin enviar) | Requiere copia/entorno autorizado |
| --- | --- |
| Esquema, tipos, obligatoriedad, opciones, secciones, restricciones declaradas de archivos | Subida real de archivos |
| Relleno hasta antes de "Enviar" con datos ficticios | Pruebas con envío y verificación de la respuesta registrada |
| Detección de la huella del esquema | Detección de cambios provocados |

## 8. Riesgos y dependencias externas

| ID | Riesgo o dependencia | Mitigación |
| --- | --- | --- |
| R1 | **El login de Google no funciona en el Chrome controlado por Playwright** (V6, causa no verificada) | Limitación aceptada: no se automatiza el login ni se evade el control. La persona inicia sesión en su Chrome normal y Playwright se conecta a esa sesión mediante la extensión (§10); alternativa sin dependencias: fragmento de consola que descarga el esquema |
| R2 | El formulario cambia (preguntas, `entry`, opciones) | Perfil versionado + huella de esquema + detención |
| R3 | La subida de archivos usa un selector embebido difícil de automatizar | Fase C aparte; si falla, queda manual |
| R4 | Datos de menores en la URL precompletada | §4; datos ficticios en el spike |
| R5 | Sesión de Google guardada en disco | Fuera del repo, permisos 700, guarda en el código y regla en `.gitignore` |
| R6 | Envío accidental | El spike no contiene ninguna acción de "Enviar"; una prueba verifica que el código no hace clic en él |
| R7 | Términos de uso de Google / volumen | Se usa con la cuenta autorizada, supervisado y sin repetición masiva |
| R8 | El formulario de 2026 puede cerrarse o reemplazarse | Perfiles por año |
| E1 | **Cuenta de Google autorizada y su sesión manual** | Dependencia del usuario |
| E2 | **Copia de prueba de ambos formularios en la cuenta autorizada** (si no tenemos permiso de edición sobre los originales, hay que recrear su estructura a partir del mapeo) | Dependencia del usuario; requiere la fase A |
| E3 | Decisión sobre n.º de deportista federativo, provincia y domicilio del tutor | Producto, después de la fase A |

## 9. Próximo paso (esperando al usuario)

Para cerrar D3: iniciar sesión **a mano** en un Chromium visible lanzado por el spike, con el perfil fuera del repo. No hace falta compartir credenciales. Después, un script de solo lectura extrae el esquema de ambos formularios; el resultado (sin datos personales) se incorpora a este plan.

## 10. Conexión a un Chrome ya autenticado (a la espera del usuario)

**Configuración propuesta (sin tocar el servidor `playwright` existente, que es global y sin `--extension`).** Un servidor con otro nombre, en alcance local (queda en `~/.claude.json`, no en el repo, sin `.mcp.json` nuevo):

```
claude mcp add playwright-extension --scope local -- cmd.exe /c "cd /d %USERPROFILE% && npx -y @playwright/mcp@0.0.84 --extension"
```

- Versión fijada (0.0.84) para que la prueba sea reproducible; `@latest` puede cambiar flags.
- Corre en Windows (V8). No se define `PLAYWRIGHT_MCP_EXTENSION_TOKEN`: cada conexión exige la aprobación manual de la persona en la página de conexión.
- Las herramientas aparecerán como `mcp__playwright-extension__*` al **reiniciar o reanudar la sesión de Claude Code** (un servidor MCP nuevo no se carga en caliente).

**Pasos del usuario:** instalar "Playwright Extension" desde la Chrome Web Store en el Chrome de Windows; iniciar sesión en Google con la cuenta institucional en ese Chrome; abrir una pestaña solo con el formulario; ejecutar el comando de arriba; reanudar la sesión; al primer uso aceptar la conexión y elegir esa pestaña.

**Riesgo de privilegios.** La extensión da acceso al navegador real: con `browser_evaluate` se podría leer más que el formulario. Mitigaciones: compartir **solo** la pestaña del formulario; recomendado un perfil de Chrome dedicado con solo la cuenta institucional (flag `--profile-dir-name`); la extracción lee únicamente los cuatro campos del esquema de `FB_PUBLIC_LOAD_DATA_`, sin `document.cookie`, almacenamiento ni otras páginas.

**Alternativas si falla:** (1) fragmento de consola en Chrome normal que descarga `padron.esquema.json` y `apto.esquema.json` (ya entregado, sin dependencias); (2) `--cdp-endpoint` a un Chrome de Windows: exige red `mirrored` en `.wslconfig` y un reinicio de WSL, no recomendado.
