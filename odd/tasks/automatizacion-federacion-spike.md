# Fase 2 — Spike de automatización federativa (`automatizacion-federacion-spike`)

Objetivo: determinar si Playwright puede completar los formularios reales de empadronamiento y aptos físicos de la federación (con adjuntos) sin duplicar la carga.
Rama: `feat/automatizacion-federacion-spike` (desde `main` `d367137`). Estrategia de entrega: `single-pr` por decisión explícita del usuario; cadena de commits en la rama actual y un único PR final hacia `main`, sin PRs intermedios. Runner: Node.js 24 + Playwright 1.64 (aún no instalado en el repo).
Estado: **FASE A COMPLETA; L1 DE §12 CERRADA Y VALIDADA POR EL USUARIO; L2 NO CONCLUYENTE (§12.7); PRUEBAS EN VIVO DETENIDAS POR ORDEN DEL USUARIO (§12.8); AUDITORÍA INTEGRAL OFFLINE COMPLETA (§12.9); CORRECCIÓN DE PRIVACIDAD EN VERIFICACIÓN (§12.10).** Detector de cambios (D) validado offline y contra los formularios reales, sin diferencias en L1. Las mediciones anteriores de la fase B siguen siendo **no válidas** (§12.3); las métricas de solo lectura de L1 se conservan por separado (§12.6). Informe de cierre técnico en `docs/auditoria-federacion-spike.md`, pendiente de revisión del usuario; sin fase C, push ni merge.

## Specs

- **S1 (O12, textual).** "Utilizar una cuenta Google autorizada, con autenticación manual." / "Explorar los formularios oficiales sin enviar respuestas ni adjuntar archivos." / "Utilizar datos personales ficticios y archivos sintéticos." / "Ejecutar pruebas completas con archivos únicamente en una copia de prueba autorizada o en un entorno controlado." / "No guardar credenciales ni sesiones dentro del repositorio." / "No automatizar CAPTCHA, MFA ni otros mecanismos de seguridad." / "No utilizar información real de menores durante el spike."
- **S2.** "No enviar respuestas, no subir archivos a formularios oficiales y no utilizar datos reales de menores. Las pruebas de carga de archivos deberán realizarse en un entorno de prueba autorizado."
- **S3.** "No implementar todavía el worker, no modificar Spring Boot ni Supabase, no hacer push ni merge."
- **S4.** "Proponer un spike independiente en Node.js + Playwright, sin integrarlo todavía con Spring Boot."
- **S5.** "Proponé una arquitectura reutilizable para formularios de otros años, sin fijar los identificadores de Google Forms dentro de la lógica de negocio."
- **S6.** "Diferenciá el formulario de presentación federativa de la ficha médica, cuyo contenido clínico corresponde al profesional."
- **S7.** Criterios de éxito mínimos ya aprobados (REQUERIMIENTOS §7): padrón ≥ 90 % de los campos que no son archivos; apto 100 % de los que no son archivos; repetir con ≥ 3 deportistas sin errores de secciones/validaciones.
- **S8 (auditoría, textual).** "El objetivo es completar la auditoría de seguridad y privacidad de `tools/federacion-spike/`, no repetir las pruebas de Google Forms." / "No abrir Playwright, modificar código, hacer push ni merge."
- **S9 (corrección, textual).** "Modificá el generador para que únicamente permita incluir en URLs campos expresamente autorizados como no sensibles. DNI, CUIL, nombres, contactos y demás datos personales deben quedar excluidos por defecto." / "Incorporá pruebas que comprueben que ningún dato personal ficticio de los perfiles termina en el enlace generado."
- **S10 (diagnósticos, textual).** "Establecé una política de redacción de diagnósticos, bitácoras y reportes que evite persistir valores sensibles o URLs completas." / "Mantené el ejecutor Playwright experimental aislado y deshabilitado para flujos de producción. No lo reescribas completamente en esta unidad."
- **S11 (continuidad y cierre, textual).** "Conservá la detección de cambios de esquema y los componentes reutilizables." / "Documentá claramente qué funcionalidades están validadas, cuáles son experimentales y cuáles siguen pendientes." / "Ejecutá pruebas offline y revisión RDD." / "No ejecutar Playwright, no utilizar datos reales, no modificar Spring Boot ni Supabase y no hacer push ni merge."
- **S12 (entrega, textual).** "cadena de commits sobre la rama de feature actual, con integración conjunta a `main` mediante un único PR" / "No abras PRs intermedios. No ejecutes Playwright ni hagas push o merge sin autorización." / "Si la revisión es satisfactoria, realizá el commit de las correcciones, con mensaje Conventional Commits en español."

## Tasks

| ID | Specs | Tarea | Ruta | Estado |
| --- | --- | --- | --- | --- |
| D1 | S1 | Verificar accesibilidad anónima de ambos formularios | inline | **hecho** (V1) |
| D2 | S6 | Leer ficha médica, nota CAP y Reglamento Nacional 2026 | inline | **hecho** (V2–V4) |
| D3 | S1,S2 | Mapear campos, obligatoriedad, opciones, validaciones, secciones y subida de archivos | inline | **hecho** (§3; extracción por la extensión de Playwright, V9) |
| D4 | — | Comparar con lo que guarda el backend | inline | **hecho** (§5) |
| D5 | S4,S5,S7 | Arquitectura, diseño del spike y criterios | inline | **propuesta** (§6, §7) |
| I1… | S3 | Implementación del spike | — | **no autorizada** |
| A1 | S1,S2,S8 | Auditoría integral offline y cierre técnico | delegado (exploración) + inline (verificación e informe) | **hecho** (§12.9; 9/9 pruebas offline; sin código) |
| C1 | S9,S10,S11 | Corrección de enlaces, redacción y aislamiento experimental con pruebas | inline (las capas comparten el contrato del ejecutor; riesgo 2: privacidad) | **en curso** (§12.10; RED/GREEN y revisión pendientes) |

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

## 12. Diagnóstico del ciclo de reintentos (2026-11-02)

**Orden del usuario (textual, resumen):** "DETENÉ la ejecución actual del spike… No autorizo más reintentos automáticos… No vuelvas a solicitar conexiones de Playwright Extension por ahora… No modifiques más código intentando resolver el problema mediante prueba y error sobre Google Forms… marcá las mediciones contaminadas por borradores como no válidas… No ejecutes nuevas pruebas en vivo sin mi autorización."

### 12.1 Estado

- **Procesos:** ninguno del spike en ejecución. Se detuvo el experimento en segundo plano (`beknx0wiq`); los ejecutores anteriores ya habían terminado; el demonio de exploración se cerró antes. Quedan vivos, **ajenos al spike y sin tocar**: la instancia `claude` del usuario y su servidor `playwright-extension` (Windows, desde las 00:15), `context7` y el `playwright` global. Chrome no se tocó.
- **Cambios locales:** árbol limpio; 12 commits del spike más 3 de documentación sobre `main` (`d367137`), sin push. 73 pruebas offline en verde. Los resultados crudos de las corridas están en `tools/federacion-spike/salida/` (ignorada por git); el último informe se renombró `fase-b.corrida-final.NO-VALIDA.json` con un `LEEME.txt`.
- **Costo del ciclo:** 7 commits `fix(spike)` consecutivos y ≥ 9 aprobaciones de conexión, cada fix corregía el síntoma de la corrida anterior.

### 12.2 Causa raíz

1. **Diseño circular.** Para medir la precarga necesito *interactuar* (marcar la casilla de correo, elegir el radio, pulsar "Siguiente"); esas interacciones hacen que Google **guarde un borrador en la cuenta**; el borrador anula o mezcla la precarga de la corrida siguiente; para evitarlo agregué automatizar la UI de "Borrar formulario", que tiene las mismas fragilidades que se querían medir. El problema que se intentaba resolver lo creaba el propio método.
2. **Sincronización no determinista.** "Página lista" se decidía con esperas fijas y sondeos de la instantánea ("hay botones"), que no garantizan que la página haya terminado de activarse. Efectos observados: lecturas antes de que cargue, un primer clic que expira (`Timeout 5000ms` en la bitácora) sin que nadie lo vea, el diálogo de borrador que aparece con retraso, y que **las conclusiones sobre los radios cambien según cuándo se lee** (sin resolver, ver 12.3).
3. **Las pruebas offline no cubren a Google.** Los 73 tests verifican *mi* código contra instantáneas que yo escribí con mis supuestos (cliente simulado con respuestas guionadas). Los supuestos eran falsos y solo se descubrieron en vivo: el diálogo de borrado es `dialog` (no `alertdialog`), no aparece si el formulario está vacío, el botón sigue diciendo "Siguiente" con "No" (salto a enviar), existe una casilla obligatoria de correo que no está en los ítems. Pruebas verdes + corrida en vivo roja es consecuencia de no haber grabado páginas reales como fixtures.
4. **Métricas defectuosas.** `unir()` combinaba la lectura previa y la posterior a mis clics, por lo que un radio que yo marqué contaba como "precargado"; y `avanzo` significaba "se hizo clic", no "cambió la sección" (el padrón figuraba como avanzado estando bloqueado por el archivo obligatorio). Los "100 %" del padrón son un artefacto.
5. **Modelo de conexión.** Cada corrida lanza un servidor MCP nuevo y exige una aprobación humana (con latencias de segundos a ~12 minutos y dos expiraciones). Iterar en vivo con ese costo convierte cada hipótesis en una espera, y empuja a "probar un arreglo más".
6. **Elementos ocultos del contrato** descubiertos de a uno en vivo (casilla de correo verificado en la configuración del formulario, saltos a "enviar" con etiqueta engañosa, borradores). El detector ya cubre la configuración y los saltos; la guarda anti-envío ya consulta el contrato.

### 12.3 Validez de la evidencia

| Evidencia | Válida | Motivo |
| --- | --- | --- |
| Esquema real de ambos formularios (Fase A), contratos y perfiles | **Sí** | Leído por la extensión; reproducible; es estructura pública sin datos personales |
| Detector contra los formularios reales: 0 diferencias, misma huella, perfil válido | **Sí** | Independiente de borradores y de clics |
| 73 pruebas offline y 14 mutaciones detectadas | **Sí** (solo de mi código) | No prueban el comportamiento de Google |
| Precarga de texto, desplegables y fechas en la sección 1, primera lectura en estado limpio (sin borrador previo) | **Sí, con reserva** | Los valores coincidían con cada perfil (no pudieron venir de un borrador) |
| Precarga en la sección 2 del apto (fecha, médico, matrícula) tras elegir "Sí" y avanzar | **Sí, con reserva** | Valores distintos por perfil; hubo diálogo de borrador y se eligió "Continuar" |
| El diálogo "¿Quieres continuar con el borrador actual?" y que el borrador anula la precarga | **Sí** | Observado en pantalla |
| Casilla obligatoria de correo verificado; padrón bloqueado en la sección 1 por el archivo obligatorio | **Sí** | Observado en pantalla (alertas) |
| Con "No" el botón sigue diciendo "Siguiente" (el esquema dice que salta a enviar) | **Sí** | Releído 3 veces con espera, sin pulsar |
| No se envió nada | **Sí** | Cero peticiones `formResponse`/POST registradas en la sesión de exploración; además el envío estaba bloqueado por la casilla de correo sin marcar. Las corridas del ejecutor no pueden pulsar "Enviar" (guarda + prueba) |
| **¿Se precargan los radios con salto?** | **L1: no se observó selección** | Las pruebas anteriores se contradijeron y no son métricas válidas. En L1, los dos radios permanecieron vacíos en los tres perfiles y en dos lecturas separadas por 3 s, sin interacción (§12.6). La causa no está demostrada. |
| Corridas 1 a 3 del ejecutor (borradores viejos, página sin cargar) | **No** | Datos de borradores ajenos o página vacía |
| Corrida final: "100 %" del padrón y del apto, "avanzó" | **No** | Ver causa 4; el borrado del borrador no está verificado en forma independiente |
| Porcentajes de precarga por formulario | **L1 válida solo para la primera sección visible** | Los porcentajes anteriores siguen descartados. Denominadores y límites de L1 en §12.6. |

### 12.4 Solución determinista (L1 ejecutada; L2 pendiente)

1. **Medir sin crear borradores.** Un borrador solo nace al *editar*. Toda la medición de precarga (texto, lista, fecha **y radios**) se hace **solo navegando y leyendo**: 6 enlaces, sin clics. Sin clics no hay borrador y no hace falta borrar nada. Es el 90 % del valor de la fase B.
2. **Una sola sesión de conexión por lote, con un plan fijo.** Una conexión, un plan declarado de antemano, un archivo de evidencia, **fail-fast sin reintentos**: ante cualquier anomalía aborta e informa, sin limpiezas automáticas ni "reintentar con otro arreglo". Idealmente desde una sesión interactiva de Claude Code que ya tenga cargadas las herramientas `playwright-extension` (una aprobación para toda la sesión), en vez de un servidor nuevo por corrida.
3. **Presupuesto limitado: 2 ejecuciones en vivo en total.**
   - **L1 (solo lectura, sin clics):** esquema vivo + detector (2 formularios) y las 6 URLs precompletadas, leyendo cada una con un *predicado de página lista evaluado dentro de la página* (`FB_PUBLIC_LOAD_DATA_` presente, `readyState=complete`, cantidad esperada de `listitem`, sin diálogo) y **dos lecturas separadas por 3 s** para resolver de una vez la duda de los radios (si difieren, el tiempo importa y queda medido). Sin borradores, sin casilla, sin "Siguiente".
   - **L2 (interactiva, opcional y mínima):** un solo perfil por formulario: apto `menor` ("Sí" + casilla + "Siguiente" → leer la sección 2) y padrón `menor` (hasta el bloqueo por el archivo). Cada clic con **postcondición verificada** (radio `aria-checked=true`, casilla marcada, cambió el conjunto de preguntas visibles). **Sin automatizar el borrado:** al terminar, el usuario pulsa "Borrar formulario" a mano una vez, o se deja el borrador ficticio (inocuo).
4. **Medir mejor:** informar por separado *precarga* (lectura previa a cualquier clic) e *interacción* (posterior); nunca unirlas. "Avanzó" = cambió el conjunto de preguntas visibles. Un porcentaje sin su denominador explícito no se informa.
5. **Pruebas offline con páginas reales.** Guardar como fixtures las instantáneas reales ya capturadas en esta sesión (con correos redactados): formulario con diálogo de borrador, diálogo de borrado (`dialog`), apto sección 2, padrón con alerta de archivo, apto con "No" + "Siguiente". Las pruebas del analizador y de la guarda leen esos archivos; así un cambio de la página rompe una prueba offline, no una corrida en vivo.
6. **Eliminar de la ruta crítica** `limpiarBorrador`, los reintentos de clic y el modo "una conexión por corrida". El código existente se conserva como referencia (no se borra) hasta que el usuario decida.
7. **Decisión de producto independiente de todo esto.** El camino principal recomendado (§11) es el enlace precompletado que abre **el propio navegador de la persona**; Playwright queda para verificar y detectar cambios de esquema. Eso ya está respaldado por la evidencia válida (detector y precarga de texto/listas/fechas). Una fase B perfecta no cambia esa recomendación; lo que falta medir es solo el comportamiento de los radios.

### 12.5 Estado de autorización

El usuario autorizó L1 desde Codex y luego validó y cerró sus resultados. Posteriormente autorizó una L2 acotada exclusivamente a los dos radios, que se detuvo antes del primer clic por un preestado inesperado (§12.7). Hasta una nueva decisión: sin más conexiones, pruebas en vivo, código nuevo, push ni merge.

### 12.6 L1 validada y diagnóstico offline de radios (2026-11-02)

**Instrucción posterior del usuario (textual):**

> L1 queda validada y cerrada. Los resultados son confiables y la Fase D no detectó diferencias.
>
> Antes de autorizar L2, quiero un diagnóstico acotado y exclusivamente offline de los dos radios que no se precargan.
>
> 1. Revisá los esquemas reales, sus identificadores `entry.*`, las opciones exactas y los enlaces generados.
> 2. Comprobá si el problema está en nuestro generador, el mapeo, la codificación de parámetros o en una limitación del comportamiento de Google Forms.
> 3. No afirmes que Google Forms impide precargar preguntas de salto sin evidencia suficiente.
> 4. No modifiques el generador por hipótesis. Si encontrás un defecto demostrable, presentá la corrección propuesta y sus pruebas.
> 5. Conservá las métricas válidas de L1, sin recalcularlas usando resultados de otras ejecuciones.
> 6. Evaluá como alternativa una interacción asistida que seleccione exclusivamente los radios faltantes, sin enviar el formulario.
> 7. Registrá la conclusión en ODD.
>
> No abras conexiones nuevas de Playwright, no ejecutes pruebas en vivo ni L2, no hagas push ni merge.
>
> Quiero una recomendación concreta: corregir el generador, aceptar una selección manual/asistida o descartar esa vía de automatización.

**Resultado válido de L1, sin recalcular:** en los perfiles `menor`, `mayor` y `condicional`, Padrón registró respectivamente 17/18 (94,4 %), 16/17 (94,1 %) y 17/18 (94,4 %) de campos correctos entre los verificables de la primera sección; Apto registró 4/5 (80 %) en cada perfil. `apto.tiene` (Padrón) y `deportista.empadronado` (Apto) estaban vacíos en las dos lecturas separadas por 3 s. Las secciones posteriores, los archivos y los campos sin dato no integran esos denominadores. Fase D: cero diferencias y misma huella de contrato duro en ambos formularios. Estos resultados no se mezclan con las corridas anteriores de la fase B.

**Comprobación offline del enlace y el contrato:**

| Formulario / clave | `itemId` | `entry` en perfil = esquema real = contrato | Opciones y salto del esquema | Parámetro en los tres enlaces |
| --- | ---: | ---: | --- | --- |
| Padrón / `apto.tiene` | `13000283` | `1747068347` | `Sí` → sección `2837880`; `No` → sección `642911103` | `entry.1747068347`: `Sí`, `Sí`, `No` |
| Apto / `deportista.empadronado` | `1983536038` | `1942851838` | `Sí` → siguiente; `No` → enviar | `entry.1942851838`: `Sí`, `Sí`, `No` |

En cada enlace, el parámetro correspondiente aparece **una sola vez**. El generador usa el rótulo exacto del perfil (`SI`/`NO` → `Sí`/`No`) y `encodeURIComponent`: `Sí` queda como `S%C3%AD`, que se decodifica de nuevo a `Sí`; `No` permanece `No`. Las opciones y los `entry` coinciden con los esquemas reales conservados en `fixtures/` y con `contratos/`. Las tres pruebas offline existentes de enlaces, perfil-contrato y comparación pasaron (3/3). Las seis instantáneas archivadas de L1 tampoco muestran un radio marcado al abrir las URLs; el lector de controles no encontró ningún `[role="radio"][aria-checked="true"]` en las dos lecturas de cada caso.

**Conclusión y límite de evidencia:** no se demostró un defecto del generador, mapeo ni codificación. Tampoco se demostró que Google Forms **impida** precargar preguntas con salto: falta una referencia independiente del enlace precompletado generado por el propio formulario o una comprobación equivalente, que no se obtiene exclusivamente offline. Sigue abierta, además, la posibilidad de que el lector no reconozca alguna representación del estado seleccionado; no se modifica por hipótesis. No hay corrección de código ni pruebas nuevas justificadas ahora.

**Recomendación del diagnóstico offline:** conservar los enlaces para los campos que L1 sí verificó y aceptar **selección manual de los dos radios** como comportamiento seguro por defecto, sin descartar la vía de precarga. La variante asistida requería autorización separada: seleccionar únicamente el radio faltante, verificar `aria-checked=true` y detenerse antes de avanzar o enviar. Ventaja: evita volver a cargar el resto; costo: editar puede crear un borrador y exige revisión humana y limpieza manual. La autorización y el resultado posterior constan en §12.7; no se ejecutó ninguna prueba en vivo durante este diagnóstico.

### 12.7 L2 acotada: detenida antes del primer clic (2026-11-02)

**Autorización posterior del usuario (textual):**

> Apruebo el diagnóstico del §12.6 del plan ODD.
>
> Conservamos el generador de enlaces sin modificaciones. No hay evidencia suficiente para atribuirle el problema.
>
> **Autorizo L2, exclusivamente para validar la selección asistida de los dos radios faltantes.**
>
> Condiciones:
>
> 1. Reutilizá Playwright Extension y la conexión existente en Codex.
> 2. Utilizá únicamente datos ficticios.
> 3. En el formulario de Padrón, seleccioná mediante Playwright el radio `apto.tiene`.
> 4. En el formulario de Aptos, seleccioná mediante Playwright el radio `deportista.empadronado`.
> 5. Verificá antes y después que el radio correspondiente cambia realmente de estado.
> 6. Realizá como máximo una prueba de selección por formulario.
> 7. No avances de sección, no adjuntes documentos y no envíes respuestas.
> 8. Si Google guarda un borrador, registralo. No intentes borrarlo automáticamente.
> 9. Si aparece un error, detené la ejecución sin reintentos.
> 10. No modifiques código durante las pruebas en vivo.
>
> **Quiero determinar:**
> - Si Playwright puede identificar y seleccionar ambos radios de forma confiable.
> - Si la selección se refleja correctamente en Google Forms.
> - Si hay diferencias entre los dos formularios.
> - Si podemos incorporar esa interacción como paso automatizado del futuro flujo federativo.
>
> Al finalizar, registrá los resultados en ODD y presentá una conclusión técnica.
>
> No hacer push ni merge. No continuar con más experimentos sin autorización.

**Observación y parada:** `browser_tabs(list)` del cliente MCP actual devolvió solamente la página de conexión de la extensión. Se navegó esa misma pestaña, sin crear otra ni iniciar un cliente adicional, al enlace precompletado de Padrón para el perfil ficticio `menor`. El formulario `AG_Padron_2026` cargó con `readyState=complete`, esquema presente, 21 preguntas visibles y sin diálogo. Tras esperar 3 s, el radio `apto.tiene` ya tenía `Sí` con `aria-checked=true` y `No` con `aria-checked=false` **antes de cualquier clic**. El valor coincide con el enlace, pero esta única observación no permite distinguir entre precarga y borrador previo; tampoco modifica ni invalida las métricas cerradas de L1.

La condición de medir un cambio de estado de un **radio faltante** no se cumplió. Se detuvo la ejecución sin seleccionar ningún radio, sin abrir Aptos, sin avanzar, adjuntar, enviar ni borrar borradores. No apareció un diálogo de borrador; no se puede afirmar si Google guardó o reutilizó uno. **Resultado de L2: no verificable; cero selecciones de las dos previstas.** No atribuir este preestado al generador, a Google Forms ni al lector sin evidencia adicional. No hay autorización para repetir o extender la prueba automáticamente.

### 12.8 Consolidación de Fase 2: cierre pendiente (2026-11-02)

**Instrucción posterior del usuario (textual):**

> Apruebo detener definitivamente las pruebas en vivo del spike por ahora.
>
> No autorizo nuevas ejecuciones L1 o L2.
>
> Vamos a consolidar los resultados de la Fase 2 antes de continuar con el desarrollo.
>
> 1. Actualizá el plan ODD con los resultados válidos de L1 y el resultado no concluyente de L2.
> 2. Separá claramente funcionalidades verificadas, limitaciones observadas y comportamientos no demostrados.
> 3. No atribuyas el radio seleccionado a un borrador ni a la precarga sin evidencia.
> 4. Revisá `tools/federacion-spike/` para distinguir código reproducible, pruebas y herramientas experimentales. Identificá código innecesario o frágil, sin eliminarlo todavía.
> 5. Revisá la seguridad de los enlaces precompletados, especialmente la exposición de DNI, CUIL y otros datos personales en URLs.
> 6. Proponé una arquitectura híbrida para el MVP: datos preparados por el backend, revisión humana, campos faltantes y adjuntos manuales.
> 7. Definí cómo actualizar los mapeos cuando la Federación cambie los formularios.
> 8. Prepará un informe de cierre técnico con criterios cumplidos, pendientes y riesgos aceptados.
> 9. Ejecutá las pruebas offline correspondientes y el assess RDD.
>
> No abrir conexiones Playwright, modificar Spring Boot, tocar Supabase, hacer push ni merge.
>
> Quiero revisar el informe final y el alcance real de los cambios antes de autorizar la publicación de esta unidad.

**Estado de esta consolidación:** L1 y L2 permanecen según §§12.6–12.7, sin atribuir el radio ya seleccionado a precarga ni a borrador. No se realizaron pruebas en vivo. Las 9 suites offline de `tools/federacion-spike/test/*.test.mjs` pasaron (9/9) mediante `node --test test/*.test.mjs`; estas pruebas no demuestran interacción real con Google Forms. El assess RDD de ese cambio documental devolvió `risk=passive`, `review_due=false`, `review_due_reason=passive`. La auditoría completa de `tools/federacion-spike/`, el análisis de exposición de datos en URL y el informe final siguen **pendientes**. Inicialmente, el agente de exploración no pudo iniciar porque `gpt-6.1-luna` no estaba disponible. El usuario autorizó sustituirlo por `gpt-6-luna`, pero el agente iniciado no efectuó la auditoría: constató que esta sesión expone `spawn_agent` y `wait_agent`, pero no `list_agents`, requerido por el protocolo local de delegación. Esta falta de herramienta se comprobó también en el orquestador. No se sustituyó la auditoría por conclusiones no verificadas. No se inició una revisión RDD adicional.

**Autorización posterior del usuario (textual):**

> Autorizo reemplazar `gpt-6.1-luna` por `gpt-6-luna` para completar la auditoría pendiente de `automatizacion-federacion-spike`.
>
> **Alcance autorizado:**
>
> 1. Auditar completamente `tools/federacion-spike/`, identificando código reutilizable, experimental, duplicado, frágil o innecesario.
> 2. Revisar seguridad y privacidad, especialmente DNI, CUIL y otros datos personales incluidos en URLs precompletadas, logs, historiales, archivos y evidencias.
> 3. Evaluar los riesgos de autenticación, sesiones persistentes y Playwright Extension.
> 4. Distinguir evidencia validada de L1, resultados no concluyentes de L2 y funcionalidades todavía no probadas.
> 5. Proponer una arquitectura de automatización federativa con revisión humana, indicando qué componentes se pueden reutilizar.
> 6. Preparar el informe de cierre técnico y actualizar el plan ODD.
> 7. Clasificar los hallazgos en bloqueantes, mejoras recomendadas y deuda técnica aceptable.
> 8. Ejecutar las pruebas offline necesarias y el protocolo RDD correspondiente.
>
> **Restricciones:**
>
> - No ejecutar nuevas pruebas contra Google Forms.
> - No abrir Playwright ni modificar formularios.
> - No implementar funcionalidades nuevas.
> - No modificar Spring Boot, migraciones ni Supabase.
> - No exponer datos personales, credenciales ni tokens.
> - No hacer push ni merge.
>
> Utilizá `gpt-6-luna` para la revisión delegada y verificá sus conclusiones contra el código real. No des por cumplida la auditoría únicamente porque el agente la declare aprobada.
>
> Al finalizar, presentá el informe, hallazgos, riesgos residuales y recomendación sobre si el spike está listo para publicar.

### 12.9 Auditoría integral offline y cierre técnico (2026-11-02)

**Instrucción actual del usuario (textual):**

> Necesito completar la auditoría integral pendiente de `automatizacion-federacion-spike`.
>
> El bloqueo anterior fue que la sesión exponía `spawn_agent` y `wait_agent`, pero no `list_agents`, requerido por nuestro protocolo ODD para la delegación.
>
> Antes de realizar cualquier trabajo:
>
> 1. Comprobá qué herramientas de colaboración multiagente están disponibles en esta nueva sesión.
> 2. Verificá específicamente `spawn_agent`, `wait_agent` y `list_agents`.
> 3. Si las tres están disponibles, recuperá el contexto de AGENTS.md y del plan ODD y continuá con la auditoría integral delegada.
> 4. Si falta alguna, detenete e informá el bloqueo. No intentes omitir las reglas de ODD ni cambiar de modelo repetidamente.
>
> No abrir Playwright, modificar código, hacer push ni merge.
>
> El objetivo es completar la auditoría de seguridad y privacidad de `tools/federacion-spike/`, no repetir las pruebas de Google Forms.

La sesión actual expuso `spawn_agent`, `wait_agent` y `list_agents`; `list_agents` respondió antes del trabajo. Se recuperaron `AGENTS.md`, `docs/REQUERIMIENTOS.md`, el plan ODD y su espejo Engram. La auditoría de lectura se delegó una sola vez al modelo `gpt-6-luna`, autorizado previamente, y sus conclusiones principales se contrastaron directamente con `src/precompletado/enlace.mjs`, `src/vivo/`, los perfiles, las pruebas y el estado de archivos ignorados. No se abrió Playwright ni se ejecutaron pruebas contra Google Forms.

**Resultado:** `docs/auditoria-federacion-spike.md` contiene el informe de cierre, la clasificación de bloqueantes, mejoras y deuda de prototipo, la arquitectura híbrida recomendada y el procedimiento para cambios de mapeo. La precarga con sensibilidad `alta` por defecto y la falta de una frontera integral de redacción impiden usar el spike con datos reales. El ejecutor con Chrome autenticado queda experimental. L1 conserva su alcance validado; L2 sigue no concluyente y los criterios de adjuntos/envío/robustez no están demostrados. No se atribuye causalidad al radio observado.

**Verificación:** `node --test test/*.test.mjs` pasó 9/9 (offline). `git diff --check` pasó y el diff no modificó archivos de código. El assess RDD del informe y plan, declarando explícitamente el archivo nuevo, devolvió `risk=passive`, `review_due=false`, `review_due_reason=passive`; no corresponde iniciar una revisión RDD. Los comentarios de `test/enlace.test.mjs:77–82` y `src/vivo/sesion.mjs:17–19` contienen afirmaciones causales más fuertes que la evidencia de §12.7; se documentan para corrección posterior, sin modificar código. La salida local no válida está ignorada por Git; un escaneo acotado no detectó patrones de URL, correo ni identificador numérico de 7–14 dígitos, sin certificar ausencia de datos personales. El usuario debe revisar informe y diff antes de cualquier publicación. Sin push ni merge.

### 12.10 Unidad de corrección de privacidad para publicación experimental (2026-11-02)

**Instrucción posterior del usuario (textual):**

> La auditoría integral de la Fase 2 está completada.
>
> Autorizo comenzar una unidad de corrección de los hallazgos bloqueantes, respetando ODD y RDD.
>
> Objetivo: dejar `tools/federacion-spike/` en condiciones de publicarse como prototipo experimental seguro, no como automatización lista para producción.
>
> 1. Revisá el informe `docs/auditoria-federacion-spike.md` y el plan ODD.
> 2. Modificá el generador para que únicamente permita incluir en URLs campos expresamente autorizados como no sensibles. DNI, CUIL, nombres, contactos y demás datos personales deben quedar excluidos por defecto.
> 3. Incorporá pruebas que comprueben que ningún dato personal ficticio de los perfiles termina en el enlace generado.
> 4. Establecé una política de redacción de diagnósticos, bitácoras y reportes que evite persistir valores sensibles o URLs completas.
> 5. Mantené el ejecutor Playwright experimental aislado y deshabilitado para flujos de producción. No lo reescribas completamente en esta unidad.
> 6. Conservá la detección de cambios de esquema y los componentes reutilizables.
> 7. Documentá claramente qué funcionalidades están validadas, cuáles son experimentales y cuáles siguen pendientes.
> 8. Ejecutá pruebas offline y revisión RDD.
>
> No ejecutar Playwright, no utilizar datos reales, no modificar Spring Boot ni Supabase y no hacer push ni merge.
>
> Al finalizar, presentá los cambios concretos, resultados de pruebas y riesgos residuales. No declares el spike listo para producción.

**Diseño de esta unidad:** una lista permitida cerrada en código autorizará sólo metadatos públicos de la escuela, no datos de deportistas, tutores, profesionales ni estados médicos/deportivos. La salida por defecto no incluirá valores personales. El ejecutor experimental deberá requerir activación explícita y rechazar entornos de producción; sus reportes y bitácoras conservarán sólo estados y códigos, no respuestas crudas ni URLs. Se mantiene la comparación de esquemas existente. Ruta inline: la política de enlace y la de redacción comparten el contrato del ejecutor; no hay dos unidades independientes para escrituras paralelas. Riesgo: ítem 2 (datos personales de menores).

**Evidencia RED → GREEN:** las nuevas pruebas de política de enlace, sanitización y entorno fallaron inicialmente ante el generador permisivo o los módulos aún inexistentes. El caso de inyección mediante rótulo público alterado y el caso de ejecución con entorno indefinido también fallaron antes de sus respectivas correcciones. Tras la implementación, `node --test test/*.test.mjs` pasó 13/13 y `node --check` pasó en ejecutor, generador y sanitizador. Las pruebas no abrieron Playwright ni usaron datos reales. El ejecutor no se comparó dinámicamente contra el commit base porque hacerlo iniciaría Playwright y está prohibido; la comparación de esquemas conserva sus pruebas offline previas. La verificación independiente pasó S9–S11 offline en copia temporal y no reprodujo bloqueantes causados por el cambio. Señaló como advertencia que la validación de `formId` y `entry` comprueba sintaxis, no procedencia; el caso fue reproducible en el baseline con perfiles arbitrariamente alterados. RDD y decisión de estrategia de entrega: pendientes.

### 12.11 Estrategia de entrega y cierre RDD (2026-11-02)

**Decisión posterior del usuario (textual):**

> Definimos la estrategia de entrega: **cadena de commits sobre la rama de feature actual, con integración conjunta a `main` mediante un único PR**.
>
> Continuá respetando ODD y RDD.
>
> 1. Registrá esta decisión en el plan ODD.
> 2. Ejecutá la revisión RDD pendiente según corresponda.
> 3. Si la revisión es satisfactoria, realizá el commit de las correcciones, con mensaje Conventional Commits en español.
> 4. Conservá la trazabilidad de los commits existentes.
> 5. Verificá que el conjunto completo de cambios sea coherente y que las pruebas offline sigan pasando.
> 6. Prepará un resumen del diff acumulado respecto de `main`, indicando riesgos residuales y limitaciones del spike.
>
> No abras PRs intermedios. No ejecutes Playwright ni hagas push o merge sin autorización.
>
> Al finalizar, indicá si la rama está lista para solicitar el PR final como prueba de concepto experimental, sin presentarla como automatización de producción.

**Estrategia registrada:** los commits existentes permanecen intactos; la corrección de privacidad forma el siguiente commit coherente en esta rama. Se preparará un solo PR final hacia `main` cuando el usuario lo autorice, sin PRs intermedios ni tracker. La rama supera ampliamente el presupuesto orientativo de 400 líneas; la opción de PR único es una decisión explícita y debe señalarse como excepción de tamaño al solicitar revisión, no ocultarse mediante cambios cosméticos. RDD y commit: pendientes.
