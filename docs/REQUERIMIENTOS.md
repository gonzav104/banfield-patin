**BANFIELD PATÍN CARRERA**

# Requerimientos funcionales y no funcionales SMART

*MVP de la plataforma de gestión deportiva y administrativa*

*Versión 1.0 · 3 de octubre de 2026*

**Objetivo del MVP**

Reducir el trabajo manual de Banfield Patín Carrera en la gestión de deportistas, documentación federativa y aptos físicos, organización de carreras, exportación de listas de buena fe y rifas, manteniendo una landing pública de la escuela.

## 1. Alcance y contexto

**Volumen actual:** Entre 20 y 25 deportistas activos aproximadamente; en carreras oficiales participan normalmente entre 10 y 15.

**Administración:** Banfield operará inicialmente con dos cuentas administrativas. El rol ADMIN no será visible ni seleccionable por las familias.

**Usuarios del MVP:** Los actores funcionales son ADMIN y FAMILIA. El deportista es una entidad gestionada por ambos; no tendrá credenciales propias en el MVP.

**Problemas prioritarios:** Empadronamiento de nuevos atletas, recepción y reutilización de documentos/DNI, carga anual de aptos físicos, confirmación de asistencia a carreras, generación de listas de buena fe y gestión básica de rifas.

**Evolución:** La solución se diseña con separación por escuela para permitir incorporar otras instituciones en el futuro, sin desarrollar todavía un producto SaaS completo.

## 2. Criterio SMART utilizado

Cada requisito se redacta para ser:

**Específico:** define actor, acción y dato afectado.

**Medible:** incluye una condición de aceptación observable.

**Alcanzable:** se limita al alcance del MVP y a la tecnología prevista.

**Relevante:** responde a un problema operativo real de la escuela.

**Temporal:** establece plazo, fecha límite del proceso o tiempo máximo de respuesta cuando corresponde.

## 3. Actores y reglas de acceso

| **Actor** | **Acceso** | **Responsabilidades principales** |
|---|---|---|
| ADMIN | /admin/login; no visible en la landing | Deportistas, familias, documentos, empadronamientos, aptos, carreras, LBF, rifas y configuración operativa. |
| FAMILIA | /login y /registro desde la landing | Gestionar deportistas vinculados, cargar documentos y aptos, confirmar carreras y gestionar sus números de rifa. |
| DEPORTISTA | Sin login en el MVP | Entidad central con datos personales, deportivos, federativos, documentales y de participación. |

## 4. Requerimientos funcionales SMART

### 4.1 Acceso, landing y cuentas

| **ID** | **Requerimiento SMART** | **Criterio de aceptación / medición** |
|---|---|---|
| **RF-01** | La aplicación deberá publicar una landing de Banfield Patín Carrera accesible sin autenticación, con información institucional, horarios/lugar de entrenamiento, contacto y acceso al portal de familias. | La landing deberá cargar sus contenidos principales en ≤ 2,5 s en una conexión 4G razonable y no deberá mostrar enlaces, roles ni llamadas a la acción de ADMIN. |
| **RF-02** | Una persona responsable deberá poder crear una cuenta FAMILIA con nombre, apellido, correo electrónico y contraseña, sin opción de seleccionar otro rol. | El sistema rechazará correos duplicados dentro de la misma escuela y la cuenta deberá quedar disponible para iniciar sesión luego de completar la validación definida para el MVP. |
| **RF-03** | El acceso ADMIN deberá realizarse mediante una ruta de acceso separada y no promocionada en la interfaz pública; las cuentas ADMIN no podrán crearse por auto-registro. | En Banfield se habilitarán inicialmente solo dos cuentas ADMIN. Una cuenta FAMILIA que intente acceder a un recurso administrativo deberá recibir HTTP 403. |
| **RF-04** | El sistema deberá permitir vincular una FAMILIA con uno o más DEPORTISTAS mediante invitación o aprobación de un ADMIN. | Ninguna familia podrá consultar o modificar datos de un deportista hasta que exista una vinculación activa y autorizada; el cambio deberá verse reflejado en ≤ 2 s. |

### 4.2 Módulo de deportistas

| **ID** | **Requerimiento SMART** | **Criterio de aceptación / medición** |
|---|---|---|
| **RF-05** | El ADMIN deberá poder crear, consultar, editar, buscar y marcar como activo/inactivo a cada deportista sin eliminar su historial. | La lista deberá permitir búsqueda por nombre, apellido o DNI y reflejar el estado activo/inactivo; un deportista inactivo no aparecerá por defecto en nuevas carreras. |
| **RF-06** | Cada deportista deberá almacenar como mínimo nombre, apellido, DNI, CUIL, fecha de nacimiento, nacionalidad, domicilio, localidad, partido, código postal y datos de contacto cuando correspondan. | Antes de marcar a un atleta como “listo para empadronar”, el sistema deberá validar la presencia de todos los datos requeridos por el circuito federativo vigente. |
| **RF-07** | El sistema deberá mantener datos deportivos por temporada separados de los datos personales permanentes: categoría, rama, divisional, número de corredor, número/tipo de licencia y estado deportivo. | Cambiar datos de la temporada 2027 no deberá modificar ni eliminar la información registrada para 2026 u otras temporadas. |
| **RF-08** | El ADMIN deberá poder consultar desde la ficha del deportista un resumen único de familia vinculada, temporada actual, documentación, apto físico, empadronamiento y participaciones en carreras. | La ficha deberá mostrar el estado de cada bloque sin requerir navegar a más de una pantalla adicional para abrir el detalle correspondiente. |

### 4.3 Carpeta digital, aptos y federación

| **ID** | **Requerimiento SMART** | **Criterio de aceptación / medición** |
|---|---|---|
| **RF-09** | La FAMILIA deberá poder cargar desde celular o computadora documentos del deportista y sus tutores en formatos PDF, JPG o PNG: DNI frente/dorso, apto físico, solicitud de licencia y otros documentos habilitados. | Cada archivo deberá asociarse al deportista/tutor correcto, registrar fecha de carga y admitir un tamaño máximo configurable de al menos 15 MB por archivo. |
| **RF-10** | El ADMIN deberá poder revisar cada documento y asignarle uno de los estados PENDIENTE, CARGADO, APROBADO, OBSERVADO o VENCIDO, registrando un motivo cuando sea observado. | Cuando un documento pase a OBSERVADO, la familia deberá ver el motivo en su portal y podrá reemplazar el archivo; el nuevo estado deberá reflejarse en ≤ 2 s. |
| **RF-11** | El sistema deberá gestionar un APTO FÍSICO por deportista y temporada, almacenando archivo, fecha de emisión, nombre del médico, matrícula y estado de presentación. | No se podrá marcar el apto como “listo para presentar” si falta cualquiera de esos datos o el archivo; el sistema deberá indicar exactamente qué dato falta. |
| **RF-12** | El sistema deberá calcular un checklist de empadronamiento para atletas nuevos y marcar “LISTO PARA PRESENTAR” solo cuando datos y documentos obligatorios estén completos y aprobados. | El checklist deberá actualizarse automáticamente después de cada cambio y deberá impedir el estado LISTO si falta al menos un requisito obligatorio. |
| **RF-13** | El ADMIN deberá poder descargar los documentos federativos de un deportista individualmente o en un paquete ZIP con nombres normalizados. | Para un expediente de hasta 10 archivos, el ZIP deberá generarse en ≤ 5 s y usar nombres identificables por deportista, tipo de documento y temporada. |
| **RF-14** | El ADMIN deberá poder registrar el estado de empadronamiento/licencia de cada deportista por temporada como PENDIENTE, LISTO_PARA_PRESENTAR, PRESENTADO, EMPADRONADO u OBSERVADO. | Cada cambio deberá guardar usuario, fecha/hora y observación opcional, preservando el historial de temporadas anteriores. |

### 4.4 Carreras y listas de buena fe

| **ID** | **Requerimiento SMART** | **Criterio de aceptación / medición** |
|---|---|---|
| **RF-15** | El ADMIN deberá poder crear una competencia indicando nombre, tipo (APM o NACIONAL), lugar, fecha de realización y fecha/hora límite de confirmación. | La competencia deberá quedar visible a las familias vinculadas en ≤ 2 s y no admitir una fecha límite posterior a la fecha de la carrera. |
| **RF-16** | La FAMILIA deberá poder responder PARTICIPA o NO_PARTICIPA por cada deportista vinculado y modificar la respuesta hasta la fecha límite. | El sistema deberá conservar una sola respuesta vigente por deportista/competencia y bloquear cambios de familia luego del cierre, salvo intervención ADMIN. |
| **RF-17** | El ADMIN deberá visualizar para cada competencia los deportistas CONFIRMADOS, NO_PARTICIPAN y SIN_RESPONDER. | Los totales deberán coincidir con la cantidad de deportistas activos convocados y actualizarse en ≤ 2 s después de una respuesta. |
| **RF-18** | Para competencias APM, el sistema deberá generar una Lista de Buena Fe Excel a partir de la plantilla oficial y de los deportistas confirmados. | La exportación deberá completar como mínimo Nº de deportista, apellido y nombre, categoría/código requerido, club, fecha de nacimiento y DNI, preservando formato y estructura de la plantilla. |
| **RF-19** | Para competencias nacionales, el sistema deberá generar la LBF en el modelo correspondiente a FEDERADOS o INTERMEDIA usando la plantilla oficial. | La exportación deberá completar los campos requeridos de función, apellido y nombre, fecha de nacimiento, DNI y categoría, sin sobrescribir las celdas de fórmula de LICENCIA Nº cuando la plantilla las calcule automáticamente. |
| **RF-20** | Antes de exportar cualquier LBF, el sistema deberá validar que todos los confirmados tengan los datos obligatorios para la plantilla seleccionada. | Si existe al menos un dato faltante, la exportación se bloqueará y se mostrará una lista por deportista/campo faltante; con datos completos, el archivo deberá generarse en ≤ 5 s para hasta 30 participantes. |

### 4.5 Rifas

| **ID** | **Requerimiento SMART** | **Criterio de aceptación / medición** |
|---|---|---|
| **RF-21** | El ADMIN deberá poder crear una rifa indicando nombre, fecha de sorteo, rango de números, precio por número y cantidad objetivo de números por familia. | El sistema no deberá permitir números duplicados dentro de la misma rifa y deberá mostrar cuántos están disponibles y asignados. |
| **RF-22** | El ADMIN deberá poder asignar bloques de números a cada familia, usando como valor inicial configurable 10 números por familia. | Una asignación deberá quedar visible en el portal familiar en ≤ 2 s y un mismo número no podrá pertenecer simultáneamente a dos familias. |
| **RF-23** | La FAMILIA deberá poder consultar sus números asignados y marcar cada uno como VENDIDO o DISPONIBLE durante una rifa activa. | El ADMIN deberá visualizar el estado consolidado de la rifa y el total teórico recaudado por números vendidos; la conciliación detallada de dinero queda fuera del MVP hasta relevar el proceso real. |
| **RF-24** | El dashboard ADMIN deberá resumir deportistas activos, expedientes/aptos pendientes, próxima competencia con respuestas pendientes y estado de la rifa activa; el dashboard FAMILIA deberá mostrar tareas pendientes de sus deportistas. | Los indicadores deberán calcularse con datos actuales y cargarse en ≤ 2,5 s para el volumen previsto del MVP. |

## 5. Requerimientos no funcionales SMART

### 5.1 Seguridad y privacidad

| **ID** | **Requerimiento SMART** | **Criterio de aceptación / medición** |
|---|---|---|
| **RNF-01** | Toda autorización deberá aplicarse en el backend mediante control de rol y pertenencia a escuela/familia; ocultar opciones en el frontend no se considerará una medida de seguridad. | El 100 % de los endpoints privados deberá rechazar con 401/403 las solicitudes sin autorización; deberán existir pruebas automatizadas de acceso permitido y denegado para operaciones críticas. |
| **RNF-02** | Los archivos sensibles deberán almacenarse en un bucket privado de Supabase Storage y nunca exponerse mediante URL pública permanente. | Toda descarga deberá requerir autorización y utilizar una URL firmada o transmisión equivalente con vigencia máxima de 5 minutos. |
| **RNF-03** | Las cuentas ADMIN deberán usar autenticación reforzada y no podrán existir credenciales administrativas en el código fuente o frontend. | Antes de producción, MFA deberá estar habilitado para todas las cuentas ADMIN y toda clave de servicio deberá residir únicamente en secretos del backend/infraestructura. |
| **RNF-04** | Las contraseñas deberán almacenarse con un algoritmo de hash adaptativo seguro y las sesiones web deberán usar cookies seguras. | En producción las cookies de sesión deberán ser HttpOnly, Secure y SameSite; ninguna contraseña o token completo podrá escribirse en logs. |
| **RNF-05** | El sistema deberá registrar auditoría de acciones sensibles: acceso/descarga de documentos, cambio de estado documental/federativo, exportación LBF y cambios administrativos relevantes. | Cada evento deberá registrar usuario, escuela, acción, recurso y fecha/hora; la auditoría deberá poder consultarse por ADMIN y conservarse al menos 12 meses. |

### 5.2 Rendimiento y disponibilidad

| **ID** | **Requerimiento SMART** | **Criterio de aceptación / medición** |
|---|---|---|
| **RNF-06** | Las operaciones API comunes que no impliquen carga de archivos ni generación de Excel deberán responder con p95 ≤ 500 ms bajo la carga objetivo del MVP. | La medición deberá realizarse antes de producción con al menos 100 solicitudes representativas por operación crítica. |
| **RNF-07** | Las páginas principales de landing, dashboard ADMIN y dashboard FAMILIA deberán ser utilizables en ≤ 2,5 s en condiciones móviles razonables. | La verificación deberá realizarse en producción/staging con recursos optimizados y sin errores JavaScript bloqueantes. |
| **RNF-08** | La generación de una LBF deberá completarse en ≤ 5 s para hasta 30 participantes y conservar fórmulas, formato y estructura de la plantilla oficial. | Se deberán comparar automáticamente celdas/hojas críticas contra una plantilla de referencia en pruebas de exportación. |
| **RNF-09** | La solución productiva deberá contar con recuperación ante pérdida de datos con RPO máximo de 24 h y RTO objetivo máximo de 8 h. | Antes del lanzamiento deberá existir un procedimiento probado de backup/restauración de PostgreSQL y referencia de objetos almacenados. |
| **RNF-10** | La disponibilidad objetivo del sistema será ≥ 99,5 % mensual, excluyendo mantenimiento planificado comunicado. | Se deberá disponer de monitoreo básico de disponibilidad y errores de backend en producción. |

### 5.3 Integridad y calidad

| **ID** | **Requerimiento SMART** | **Criterio de aceptación / medición** |
|---|---|---|
| **RNF-11** | La base de datos deberá impedir inconsistencias críticas mediante restricciones y transacciones. | Como mínimo: DNI único por escuela para deportistas activos/registrados, una inscripción vigente por deportista/competencia y un número único por rifa. |
| **RNF-12** | Las migraciones de base de datos deberán versionarse con Flyway y ser reproducibles entre desarrollo, pruebas y producción. | Una instalación vacía deberá poder reconstruir el esquema completo ejecutando las migraciones en orden sin intervención manual. |
| **RNF-13** | Los flujos críticos deberán tener pruebas automatizadas antes de cada despliegue. | La CI deberá bloquear el despliegue si fallan pruebas de autorización, vinculación familia-deportista, documentos, apto físico, confirmación de carrera o exportación de LBF. |
| **RNF-14** | El backend deberá publicar y mantener documentación OpenAPI coherente con los endpoints implementados. | Toda nueva ruta pública/privada deberá estar documentada en la misma entrega en la que se incorpora y la especificación deberá validar sin errores. |

### 5.4 Usabilidad, compatibilidad y escalabilidad

| **ID** | **Requerimiento SMART** | **Criterio de aceptación / medición** |
|---|---|---|
| **RNF-15** | La interfaz de FAMILIA deberá ser mobile-first y permitir completar los flujos principales desde un teléfono de 360 px de ancho sin scroll horizontal. | Registro, carga de documento, confirmación de carrera y gestión de rifa deberán probarse al menos en un viewport móvil de 360×800 y uno de escritorio. |
| **RNF-16** | Los flujos principales deberán alcanzar un nivel de accesibilidad equivalente a WCAG 2.1 AA en contraste, foco, labels y navegación por teclado. | Antes de producción no deberán quedar errores críticos de accesibilidad en landing, login y formularios principales según auditoría automática y revisión manual básica. |
| **RNF-17** | La aplicación deberá ser compatible con las dos versiones principales más recientes de Chrome, Edge, Firefox y Safari, incluyendo Safari/Chrome móvil. | Los flujos críticos deberán ejecutarse sin bloqueos funcionales en la matriz de navegadores definida antes de cada release mayor. |
| **RNF-18** | El diseño de datos deberá ser multi-escuela desde el MVP, aunque Banfield sea la única escuela operativa inicial. | Todos los datos de negocio deberán quedar asociados a una escuela y las consultas privadas deberán filtrar por ese contexto; incorporar una segunda escuela no deberá requerir rediseñar el esquema principal. |
| **RNF-19** | La arquitectura deberá soportar, sin cambios estructurales, al menos 50 escuelas, 500 deportistas por escuela y 100 sesiones concurrentes manteniendo los objetivos de rendimiento definidos. | Se realizará una prueba de carga representativa antes de habilitar el uso multi-escuela comercial; para el MVP Banfield se validará al menos la carga objetivo actual multiplicada por 5. |

## 6. Restricciones y decisiones técnicas vigentes

- Frontend: React + TypeScript + Vite, con diseño responsive/mobile-first.
- Backend: Java 21 + Spring Boot, con Spring Security como autoridad de autenticación/autorización.
- Persistencia: PostgreSQL administrado en Supabase.
- Documentos: Supabase Storage con buckets privados; la base almacena metadatos y claves de objeto, no los binarios.
- Migraciones: Flyway.
- Integración: API REST documentada con OpenAPI.
- Exportación LBF: se reutilizarán las plantillas oficiales APM y Nacional; no se recrearán desde cero.
- Arquitectura inicial: monolito modular; no microservicios.

## 7. Validación técnica previa: Google Forms

La automatización directa de formularios de Federación no se considera comprometida hasta realizar una prueba técnica. El objetivo de esa prueba será medir si los campos no documentales pueden precargarse de forma confiable sin controlar el formulario externo.

| **Prueba** | **Éxito mínimo** | **Decisión** |
|---|---|---|
| Padrón | Precargar ≥ 90 % de los campos no-archivo usados por un atleta menor de Banfield. | Si se cumple, incorporar botón “Abrir formulario precargado” al MVP o primera iteración posterior. |
| Apto físico | Precargar el 100 % de los campos no-archivo (DNI, nombre, club, empadronado, fecha, médico y matrícula). | Si se cumple, dejar como única tarea manual externa la selección del archivo y el envío. |
| Robustez | Repetir la prueba con al menos 3 deportistas y sin errores de secciones/validaciones. | Si falla, mantener como solución estable la carpeta digital + descarga rápida de documentos. |

## 8. Fuera de alcance del MVP

- Cuenta/login propio para deportistas.
- Estadísticas deportivas, medallas, rankings, cartas tipo FIFA y gamificación.
- Pasarela de pago, Mercado Pago o conciliación contable completa de rifas.
- Envío automático no supervisado de formularios externos de Federación.
- Panel de superadministración para altas/bajas de múltiples escuelas, planes o suscripciones.
- Registro formal de resultados de carreras y analítica deportiva avanzada.

## 9. Criterios de salida del MVP

**1.** Una familia puede registrarse, vincularse de forma autorizada con sus deportistas y cargar documentación desde móvil.

**2.** Un ADMIN puede revisar documentos, gestionar aptos y saber qué atleta está listo para empadronar.

**3.** Una familia puede confirmar o rechazar asistencia a una carrera y el ADMIN obtiene el listado consolidado.

**4.** El sistema genera correctamente las LBF APM y Nacional sobre las plantillas oficiales, bloqueando exportaciones con datos incompletos.

**5.** Una rifa puede crearse, asignarse por familia y controlarse por número vendido/disponible.

**6.** La landing pública está operativa y no expone acceso administrativo.

**7.** Los controles de seguridad, almacenamiento privado, auditoría y pruebas críticas definidos en los RNF están activos.

## 10. Fuentes de relevamiento utilizadas

- Cómo utilizar las carpetas del drive_VELOCIDAD.docx.
- AG_Padron_2026 (respuesta de Google Forms).
- AG_AptosFisicos2026 (respuesta de Google Forms).
- ListaDeBuena_Banfield_formula_mejorada.xlsx.
- LBF-APM-Mayo.xlsx.
- Modelo LBF - carrera.xlsx (Federados, Intermedia e instructivo).
- Relevamiento funcional realizado en conversación con Banfield Patín Carrera.

> Nota: este documento define el alcance funcional del MVP. El modelo entidad-relación y el esquema físico de PostgreSQL deberán derivarse de estos requisitos, no al revés.
