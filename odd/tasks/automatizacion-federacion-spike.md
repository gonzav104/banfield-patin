# Fase 2 — Spike de automatización federativa (`automatizacion-federacion-spike`)

Objetivo: determinar si Playwright puede completar los formularios reales de empadronamiento y aptos físicos de la federación (con adjuntos) sin duplicar la carga.
Rama: `feat/automatizacion-federacion-spike` (desde `main` `d367137`). Estrategia de entrega: `ask-on-risk`. Runner: Node.js 24 + Playwright 1.64 (aún no instalado en el repo).
Estado: **FASE A COMPLETA (mapeo real de ambos formularios, solo lectura).** Sin implementación de las fases B, C ni D, sin push ni merge. Pendiente de decisión del usuario: §11.

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
| D3 | S1,S2 | Mapear campos, obligatoriedad, opciones, validaciones, secciones y subida de archivos | inline | **hecho** (§3; extracción por la extensión de Playwright, V9) |
| D4 | — | Comparar con lo que guarda el backend | inline | **hecho** (§5) |
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

**V9 — Extracción por la extensión (verificado, 2026-11-02).** Con Chrome de Windows ya autenticado por la persona y la extensión "Playwright Extension" instalada, el servidor MCP en Windows (V8) se conectó y Playwright leyó el esquema de ambos formularios **sin interacción humana más allá de aprobar la conexión**. Se usó `browser_navigate` (sobre la pestaña compartida) y `browser_evaluate` con una función que devuelve solo `titulo`, `descripcion`, `items` y `config` de `FB_PUBLIC_LOAD_DATA_`; no se leyeron cookies, almacenamiento, respuestas ni otras páginas, y no se completó, adjuntó ni envió nada. Observaciones: (a) las herramientas `mcp__playwright-extension__*` **no se cargaron** en la sesión en segundo plano reanudada, por lo que se habló con el mismo servidor por stdio desde un cliente mínimo (scratch, fuera del repo); (b) la primera lectura devolvió datos válidos pero mi intérprete de la respuesta falló y se repitió; (c) una segunda conexión tardó ~12 min hasta que la persona la aprobó. Tiempos de extracción una vez conectado: 5 s (padrón) y 3 s (apto). **El esquema extraído no contiene datos personales** (es la estructura pública del formulario). Se conserva en un directorio temporal fuera del repo; el contenido relevante está en §3.

## 2. Hallazgo que corrige una premisa de la ficha

La ficha dice "validez por un año"; el Reglamento dice **año calendario de la temporada, sin importar la emisión**. La regla federativa manda. Hoy `apto_fisico` guarda `fecha_emision` y `documento.fecha_vencimiento`. **Recomendación (decisión del usuario en la Fase 4, no ahora):** derivar el vencimiento como el 31/12 del año de la temporada y no como emisión + 1 año.

## 3. Mapeo real de los formularios (D3, verificado)

Quién los publica: **Asociación Gaucha de Patinaje** (consultas a `info@patinajegaucha.org`), no la CAP. Ambos exigen sesión de Google. El esquema se leyó de `FB_PUBLIC_LOAD_DATA_`; el `entry.<id>` de cada pregunta es el primer valor de su definición (no el id del ítem). Obligatoria: `*`. "Num." = validación de respuesta "Número: es número"; "Email" = validación de correo (interpretación de los códigos `[1,9]` y `[2,102]`: inferida del contexto, a confirmar en la prueba de relleno). Las fechas piden día, mes y año, sin hora.

### 3.1 Padrón 2026 — `AG_Padron_2026` (42 ítems: 28 preguntas sin archivo, 9 de archivo, 5 secciones)

| # | Pregunta | Tipo | `entry` | Obl. | Validación / opciones |
| --- | --- | --- | --- | --- | --- |
| 0 | CLUB | lista | 1551725616 | * | 28 opciones (§3.3); si no figura, "Asociación Gaucha" |
| 1 | FUNCION | lista | 905774763 | * | Atleta, Técnico, Juez, Dirigente |
| 2 | TIPO LICENCIA | lista | 1603123285 | * | Solo Seguro, Regional, Federativa, Promocional, Nacional |
| 3 | CERTIFICADO DE SOLICITUD DE LICENCIA (archivo) | archivo | 1371472553 | no | Solo atletas con licencia Federativa, Promocional o Nacional |
| 4 | APELLIDOS | texto | 2038527210 | * | "Tal como se visualiza en el DNI" |
| 5 | NOMBRES | texto | 373714051 | * | |
| 6 | DNI | texto | 1245518198 | * | Num. |
| 7 | CUIL | texto | 1125203474 | * | "Tal como se visualiza en el dorso del DNI" (sin validación) |
| 8 | FOTO FRENTE DEL DNI (archivo) | archivo | 1533392906 | * | 1 archivo, PDF o imagen |
| 9 | FOTO DORSO DNI (archivo) | archivo | 1530094292 | * | 1 archivo, PDF o imagen |
| 10 | FECHA DE NACIMIENTO | fecha | 1615450528 | * | con año, sin hora |
| 11 | NACIONALIDAD | texto | 687468341 | * | |
| 12 | DOMICILIO | texto | 1485601769 | * | "Calle y Altura" |
| 13 | OTROS DATOS DEL DOMICILIO | párrafo | 559537710 | no | piso, depto, barrio, etc. |
| 14 | LOCALIDAD | texto | 1110676023 | * | |
| 15 | PARTIDO | texto | 936022093 | * | |
| 16 | CODIGO POSTAL | texto | 733384249 | * | |
| 17 | CORREO ELECTRONICO | texto | 2088867511 | * | Email; "uno por persona" (hermanos sin correo propio: uno el del padre, otro el de la madre) |
| 18 | TELEFONO DE CONTACTO | texto | 776294012 | * | Num.; con código de área, solo números |
| 19 | DEPORTE | lista | 2033930699 | * | Artístico, Velocidad, Hockey, Otro |
| 20 | TIENE CERTIFICADO MEDICO? | opción | 1747068347 | * | **Sí → sección "Certificado Medico"; No → salta a "Certificado WADA"** |
| 21 | *Sección* "Certificado Medico" | — | — | — | enlaces a la planilla (artístico y velocidad) |
| 22 | FECHA EMISION CERTIFICADO MEDICO | fecha | 1754245199 | * | |
| 23 | APELLIDO Y NOMBRE DEL MEDICO | texto | 325538294 | * | |
| 24 | NUMERO DE MATRICULA DEL MEDICO | texto | 890114021 | * | **Num.** |
| 25 | CERTIFICADO MEDICO (archivo) | archivo | 592837963 | * | "Adjuntar la planilla (Apto médico) vigente" |
| 26 | *Sección* "Certificado WADA" | — | — | — | "mayores de 16 años y WS, Técnicos, Jueces y Delegados" |
| 27 | TIENE CERTIFICADO WADA? | opción | 577509160 | * | **Sí → "Subir certificado WADA"; No → salta a "Menores de Edad"** |
| 28 | *Sección* "Subir certificado WADA" | — | — | — | |
| 29 | FECHA EMISION CERTIFICADO WADA | fecha | 1408231423 | * | |
| 30 | CERTIFICADO WADA (archivo) | archivo | 195438215 | * | |
| 31 | *Sección* "Menores de Edad" | — | — | — | "hasta los 17 años", lo completan los padres |
| 32 | Es menor? | opción | 1572893979 | * | **Sí → "Datos Padres"; No → ENVIAR el formulario** |
| 33 | *Sección* "Datos Padres" | — | — | — | |
| 34 | Apellido y Nombre de la Madre | texto | 971793754 | * | |
| 35 | Número de DNI de la Madre | texto | 100113650 | * | Num.; "solo números, sin puntos" |
| 36 | Foto frente DNI de la Madre (archivo) | archivo | 811314206 | * | 1 archivo |
| 37 | Foto dorso DNI de la Madre (archivo) | archivo | 721456363 | * | 1 archivo |
| 38 | Apellido y Nombre del Padre | texto | 175571160 | * | |
| 39 | Número de DNI del Padre | texto | 983267618 | * | Num. |
| 40 | Foto frente DNI del Padre (archivo) | archivo | 2031360264 | * | 1 archivo |
| 41 | Foto dorso DNI del Padre (archivo) | archivo | 1178893024 | * | 1 archivo |

### 3.2 Aptos físicos 2026 — `AG_AptosFisicos2026` (10 ítems: 8 preguntas sin archivo, 1 de archivo, 1 sección)

| # | Pregunta | Tipo | `entry` | Obl. | Validación / opciones |
| --- | --- | --- | --- | --- | --- |
| 0 | DNI | texto | 2011177755 | * | Num.; "el mismo que se registró en el padrón" |
| 1 | APELLIDOS | texto | 1262704907 | * | "tal y como se registró en el padrón" |
| 2 | NOMBRES | texto | 949077727 | * | ídem |
| 3 | Club | lista | 1397501273 | * | 27 opciones (§3.3) |
| 4 | Atleta empadronado? | opción | 1942851838 | * | **Sí → continúa; No → ENVIAR el formulario** (el texto remite al padrón) |
| 5 | *Sección* "Registrado en el padrón" | — | — | — | |
| 6 | CERTIFICADO MEDICO (archivo) | archivo | 314532520 | * | planilla vigente (enlaces artístico/carrera) |
| 7 | FECHA EMISION CERTIFICADO MEDICO | fecha | 2098635769 | * | |
| 8 | APELLIDO Y NOMBRE DEL MEDICO | texto | 1070253322 | * | |
| 9 | NUMERO DE MATRICULA DEL MEDICO | texto | 647391604 | * | **Num.** |

### 3.3 Listas con diferencias entre formularios

La lista de clubes **no es la misma** en los dos formularios (28 vs 27 opciones) y el rótulo cambia: padrón "Asociación Gaucha" / apto "Asociación Gaucha de Patinaje"; "Club Atlético y Social Dudignac" / "… (CASD)"; "Loop Escuela de Patín Artístico" / "… (Rojas)". Solo en el padrón: Patiencantos, Patín Carrera Junín, Sociedad de Fomento 12 de Octubre, Sprint Junín. Solo en el apto: Club Municipal Chacabuco, Club San Martín, Club Vélez Sarsfield de Martínez. **El rótulo de nuestra escuela, "Club Social y Deportivo Banfield San Pedro", coincide con `escuela.nombre_federativo` y existe en ambos.** Consecuencia: el mapa `valorInterno → rótulo` debe ser **por formulario**.

### 3.4 Archivos

- El padrón tiene **9** preguntas de archivo: solicitud de licencia (opcional), DNI frente y dorso del deportista, certificado médico, certificado WADA, y DNI frente y dorso de madre y de padre.
- **Restricciones leídas del DOM visible, sin abrir el selector:** "Sube 1 archivo compatible: PDF o image. El tamaño máximo es de 100 MB." (verificado para DNI frente y dorso del deportista, que son las únicas preguntas de archivo de la primera sección). Las preguntas de las secciones siguientes no se renderizan hasta avanzar y no se verificaron; se espera la misma configuración (el texto de ayuda dice "Un solo archivo (imagen o PDF)"). **El esquema no expone las restricciones de archivo** (solo `entry` y obligatoriedad).
- El apto tiene **1** pregunta de archivo, en la sección 2 (no renderizada: sin verificar).
- Cómo se adjunta (selector "Agregar archivo", `aria-label` "Agregar archivo" / "Archivos seleccionados") **no se pudo observar sin abrirlo**; queda para la fase C, en la copia de prueba.

### 3.5 Lógica condicional (saltos)

Padrón: `¿Tiene certificado médico?` Sí → [Certificado médico] → [WADA]; No → [WADA]. `¿Tiene WADA?` Sí → [Subir WADA] → [Menores]; No → [Menores]. `¿Es menor?` Sí → [Datos Padres]; **No → fin**. Apto: `¿Empadronado?` Sí → [datos y archivo del certificado]; **No → fin sin datos** (callejón sin salida: hay que haber hecho el padrón primero).

## 4. Enlaces precompletados: evaluación

Estado: **conocimiento general, a verificar en el formulario real.**
- Google Forms admite un enlace con parámetros `entry.<id>=<valor>` que precarga preguntas de texto, opción, casilla, lista, fecha y hora. **No** precarga preguntas de archivo. Una opción solo se precarga si el valor coincide exactamente con el texto de la opción.
- Sirven como complemento (no sustituto) de Playwright: cubren los campos que no son archivos sin automatizar el navegador, y no necesitan guardar sesión. Playwright queda solo para lo que el enlace no cubre (secciones condicionales, verificación de lo precargado, archivos).
- **Riesgo de seguridad propio:** el enlace lleva DNI y datos de un menor en la URL (historial del navegador, registros de Google, `Referer`). Mitigación: generarlo solo por una acción explícita del ADMIN, no registrarlo ni persistirlo, abrirlo con `rel="noopener noreferrer"` y `Referrer-Policy: no-referrer`.
- Los identificadores `entry.*` cambian si se recrea el formulario (por ejemplo, el de 2027), por eso **no pueden estar en la lógica de negocio** (AGENTS.md ya lo exige).
- **Verificado con el esquema real (§3):** los `entry` existen y se pueden leer; hay 28 preguntas sin archivo en el padrón y 8 en el apto, todas precompletables en principio (texto, lista, opción y fecha); las 9 + 1 preguntas de archivo **no** se precompletan. Las listas y opciones exigen el **rótulo exacto** (Sí, No, rótulos de club distintos por formulario). **Sin verificar en el formulario real:** formato de la fecha en el enlace, comportamiento con valores en secciones condicionales, y largo máximo de URL.
- **Cobertura teórica del criterio S7:** padrón 28/28 y apto 8/8 de las preguntas sin archivo (100 %) tienen un dato fuente identificado (§5); el 100 % depende de módulos futuros (apto, licencia, documentos) y de los puntos abiertos de §5.

## 5. Comparación con lo que guarda el backend (verificada contra el esquema real)

**Corrección:** las brechas de la v1 de este plan (provincia, domicilio del tutor, n.º de deportista federativo, obra social, grupo sanguíneo) venían de la ficha médica y del Reglamento de la CAP. **Estos formularios no las piden.** No se necesitan para esta automatización.

| Pregunta del formulario | Dato fuente | Estado |
| --- | --- | --- |
| APELLIDOS, NOMBRES, DNI, CUIL, FECHA DE NACIMIENTO, NACIONALIDAD | `deportista` | Existe |
| DOMICILIO, OTROS DATOS, LOCALIDAD, PARTIDO, CÓDIGO POSTAL, TELÉFONO | `deportista` (`domicilio`, `otros_datos_domicilio`, `localidad`, `partido`, `codigo_postal`, `telefono_contacto`) | Existe; el teléfono debe normalizarse a solo números con código de área |
| CORREO ELECTRONICO | `deportista.email_federativo` | Existe ("uno por persona") |
| CLUB | `escuela.nombre_federativo` + mapa de rótulos por formulario | Existe; **mapa por formulario** (§3.3) |
| FUNCION, DEPORTE | Constantes de configuración de la escuela ("Atleta", "Velocidad") | **Falta** (configuración, no esquema) |
| TIPO LICENCIA | `licencia.tipo` (REGIONAL, FEDERATIVA, PROMOCIONAL, NACIONAL) | **Brecha: falta "Solo Seguro"** (obligatorio en el formulario) |
| TIENE CERTIFICADO MEDICO?, FECHA EMISION, MEDICO, MATRÍCULA, archivo | `apto_fisico` + `documento` | Esquema existe; módulo en la Fase 4. **La matrícula debe ser numérica** y `medico_matricula` es `varchar(50)` libre: normalizar o validar |
| TIENE CERTIFICADO WADA?, FECHA EMISION WADA, archivo | `documento` tipo `CERTIFICADO_WADA` | **Brecha: no hay fecha de emisión** (solo `fecha_vencimiento`); obligatorio si el atleta tiene WADA |
| Es menor? | Derivado de `fecha_nacimiento` (hasta 17 años) | Calculable |
| Madre / Padre: apellido y nombre, DNI, fotos del DNI | `tutor` + `documento` (`DNI_TUTOR_FRENTE/DORSO`) | Existe; **hace falta distinguir madre y padre** (`parentesco` es texto libre) |
| Solicitud de licencia (archivo) | `documento` tipo `SOLICITUD_LICENCIA` | Existe |
| DNI frente/dorso del deportista | `documento` tipo `DNI_DEPORTISTA_FRENTE/DORSO` | Existe |

**Puntos abiertos de producto:** (1) el formulario **exige madre y padre** para menores (ambos obligatorios, sin opción "no aplica"): un deportista con un solo tutor, tutor legal u otro parentesco **no puede completarlo tal cual**; (2) "Solo Seguro" no existe en nuestro modelo de licencia; (3) la fecha de emisión del WADA no se guarda; (4) la matrícula alfanumérica no pasa la validación; (5) el apto exige estar empadronado primero (coincide con nuestra regla de estados, pero obliga a ordenar el flujo).

Sin migración en esta fase: son hallazgos para las Fases 3 a 5.

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
| R9 | Las listas de clubes difieren entre formularios y cambian año a año | Mapa de rótulos por formulario y por año; la huella de esquema detecta el cambio |
| R10 | Un envío "No" del apto o "No es menor" del padrón **termina el formulario**; un salto mal calculado enviaría datos incompletos | Calcular la ruta de secciones desde nuestros datos antes de completar y comparar con el esquema; el spike nunca envía |
| R11 | La conexión por la extensión requiere aprobación manual y tarda (~12 min en un caso); las herramientas MCP no se cargaron en la sesión reanudada | Cliente stdio propio como alternativa; la extracción de esquema no necesita ser interactiva si hay una sesión de Chrome abierta |
| R12 | La extensión da acceso al navegador real de la persona | Compartir solo la pestaña del formulario; leer solo `FB_PUBLIC_LOAD_DATA_`; perfil de Chrome dedicado recomendado |
| E1 | **Cuenta de Google autorizada y su sesión manual** | Dependencia del usuario |
| E2 | **Copia de prueba de ambos formularios en la cuenta autorizada** (si no tenemos permiso de edición sobre los originales, hay que recrear su estructura a partir del mapeo) | Dependencia del usuario; requiere la fase A |
| E3 | Decisión sobre n.º de deportista federativo, provincia y domicilio del tutor | Producto, después de la fase A |

## 9. Próximo paso (esperando al usuario)

Para cerrar D3: iniciar sesión **a mano** en un Chromium visible lanzado por el spike, con el perfil fuera del repo. No hace falta compartir credenciales. Después, un script de solo lectura extrae el esquema de ambos formularios; el resultado (sin datos personales) se incorpora a este plan.

## 10. Conexión a un Chrome ya autenticado (verificada, ver V9)

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

## 11. Estrategia recomendada y decisión pendiente

**Recomendación (a decidir por el usuario antes de cualquier implementación):**

1. **Camino principal: enlace precompletado** ("Abrir formulario precargado") generado por el backend desde un **perfil por año y formulario**. Cubre las 28 + 8 preguntas sin archivo sin automatizar el navegador ni guardar sesión. Riesgos: datos de un menor en la URL (§4) y verificación pendiente de la fecha y las secciones.
2. **Archivos: manuales con apoyo.** Las 10 preguntas de archivo no se precompletan. La app prepara y descarga los archivos con nombres normalizados (RF-13) y la persona los selecciona. Playwright sobre el selector de archivos solo si la fase C lo demuestra fiable; es la parte frágil y la menos valiosa.
3. **Playwright con navegador visible y humano en el circuito** para (a) verificar de vuelta lo precompletado, (b) detectar cambios de esquema y (c) en la fase C, probar la subida en una copia. No como envío automático.
4. **Detección de cambios de esquema** como entregable independiente y barato: ya está probado que se puede extraer el esquema (V9) y compararlo con un perfil.
5. **Antes del producto:** resolver los 5 puntos abiertos de §5 (madre/padre, "Solo Seguro", fecha WADA, matrícula numérica, orden padrón → apto).

**Siguiente paso si el usuario lo autoriza:** fase B (relleno sin envío, datos ficticios, formulario oficial) y fase D (detección de cambios). La fase C requiere una copia de prueba de los formularios (E2). Nada de esto está autorizado todavía.
