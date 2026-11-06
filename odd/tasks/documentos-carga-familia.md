# Primera carga documental familiar segura
Objetivo: flujo vertical FAMILIA → archivo validado/escaneado → operación durable → Documento CARGADO → metadatos D1.
Rama: `feat/documentos-carga-familia`, base `64cf0abd5d3a3eece22c260cff85819856dd1b41`. Una entrega funcional local, sin commit/publicación hasta controles y autorización aplicable. Runner: Maven/JUnit, PostgreSQL 17 Testcontainers y servidores HTTP/TCP simulados.

## Specs

- S1: «`POST /api/familia/deportistas/{id}/documentos`»; «Autorizar según sesión, escuela, familia y vínculo activo.»; «Mantener CSRF y las protecciones existentes.»; «Verificar permisos antes de procesar y nuevamente al publicar, coordinándose correctamente con revocaciones concurrentes.»
- S2: «Aceptar un archivo por solicitud.»; «Admitir PDF, JPEG y PNG con límite inicial configurable de 5.000.000 bytes (cambio L15; el texto original sigue en L1).»; «Validar tamaño real, estructura y contenido, no solamente extensión o MIME.»; «Rechazar archivos malformados, PDF cifrados, JavaScript y adjuntos embebidos.»; «Controlar consumo de memoria, temporales y recursos de procesamiento.»
- S3: «Integrar escaneo mediante ClamAV privado, con fallo cerrado.»; «No mantengas transacciones PostgreSQL abiertas durante la transferencia o el escaneo.»; «La integración real debe permanecer desactivada por defecto. Los dobles de prueba sólo podrán utilizarse en perfiles y entornos de test.»
- S4: «Utilizar los destinos preparados de B2 y las operaciones durables de V6.»; «Conservar idempotencia, estados de incertidumbre y controles de concurrencia.»; «Crear Documento con estado CARGADO y registrar auditoría en una transacción corta.»; «Evitar duplicados ante reintentos con una operación ya confirmada.»
- S5: «No borrar objetos cuando exista incertidumbre sobre su publicación.»; «No implementes reconciliación automática de operaciones inciertas ni eliminación genérica por clave. Cuando no se pueda confirmar el resultado, preservá la operación durable para posterior resolución supervisada.»
- S6: «Una familia autenticada puede enviar un PDF, JPEG o PNG válido para un deportista autorizado. El backend verifica la operación, registra el documento en estado CARGADO y permite consultar sus metadatos mediante D1.» Se demuestran los ocho escenarios literales de L1 con PostgreSQL 17 descartable, Storage HTTP y scanner simulados, archivos/sesiones/familias sintéticos. Pruebas RED/GREEN por regla, regresiones, suites normal/PG, verificador independiente y RDD nativo.
- S7: «No hacer commit hasta completar las verificaciones y obtener cualquier excepción de tamaño necesaria.»; «No hacer push, PR ni merge todavía.»; «No modificar V1–V6 sin demostrar la necesidad y solicitar autorización específica.»; «No conectar Supabase real, desplegar ni ejecutar migraciones sobre bases compartidas.»; «Mantené B1-C abierta.»
- S8: «Conservá intactas las cinco entradas locales sin seguimiento.»; «Organizá la implementación en cambios internos coherentes, pero no abras un PR por cada adaptador, validador o servicio.»; «No implementar revisión, reemplazo, ZIP, descarga, carga de tutores ni flujos específicos de aptos físicos en esta entrega.»

- S9: «Determinar si el rechazo de conexión proviene de una regresión del candidato, un defecto del harness o una intermitencia ambiental.»; «Realizá únicamente reproducciones adicionales que aporten evidencia nueva; capturá estado del contenedor y puertos durante el fallo.»; «No modificar V1–V6 ni el flujo funcional de carga documental.»; «Si no se demuestra la causa: No modifiques código especulativamente. Conservá B1-C abierta.»

- S10: «Esta publicación es exclusivamente para verificación, no para aprobación funcional.»; «Rama remota exclusiva: test/carga-documental-ci.»; «Se permite utilizar la sesión actual de GitHub CLI para publicar únicamente esa rama y consultar workflows y logs.»; «No inicies una revisión nativa de riesgo alto sin el consentimiento requerido.»

## Tasks

- T1 · S1–S8 · inline + explorador de sólo lectura (mapa de autorización/SQL para decidir; presupuesto de evidencia excedido) · completado · sin commit; base/árbol limpio confirmados, excepción propia L2.
- T2 · S2/S3/S8 · inline · GREEN focal ampliado · validación acotada, temporales y ClamAV privado; RED/GREEN antes de integrar.
- T3 · S1/S4/S5 · inline · GREEN integrado · persistencia CAS V6, autorización/revocación y publicación transaccional; RED/GREEN PostgreSQL real.
- T4 · S1–S6/S8 · inline · GREEN integrado y regresiones completas CI (L13) · endpoint y demostración integrada de los ocho escenarios; pruebas de seguridad y privacidad.
- T5 · S6/S7/S8 · delegado al verificador independiente (ítems de riesgo 2/4/5: autorización, concurrencia y dependencia de inspección) · prueba funcional e independiente satisfecha; consentimiento granted, RDD bloqueado por presupuesto nativo (L14) · suites completas, sondas en scratch externo, spot-check, assess y RDD nativo con consentimiento si corresponde. Entrega final no autorizada; commit de validación excepcional L11–L13.

- T5D · S6/S7/S8/S9 · inline con mapa delegado y captura diagnóstica independiente · diagnóstico acotado concluido, CAUSA INCONCLUSA · diagnóstico causal acotado; registro antes de cualquier corrección, sin RDD hasta resolver verificabilidad.

- T5CI · S6/S7/S8/S10 · inline para Git/GitHub y delegado para observación de CI larga · concluido, ambas suites success, intento 1 · excepción propia de publicación para verificación; sin PR, aprobación funcional ni RDD implícito.

- T6 · S2 (L15) · inline + verificador independiente (ítem 2: validación de contenido y CORS) · candidato local sin commit; suite normal 1083/1083, suite PG pendiente de CI · cuatro parches de corrección (límites 5 MB/16 MP, PDF con DCT como stop filter más encabezado JPEG acotado, rechazo diferido de JPEG con anexos, Idempotency-Key en CORS) y RF-09.

## Log

### L1 — Solicitud original, textual

Continuemos el backend de Banfield Patín Carrera con un cambio de estrategia: priorizar entregas funcionales completas, manteniendo ODD, RDD y los controles de seguridad obligatorios.

B2 está integrada en main mediante:
`64cf0abd5d3a3eece22c260cff85819856dd1b41`

CI posterior: 1013 pruebas normales y 508 PostgreSQL aprobadas.

## Objetivo autorizado

Implementar localmente el primer flujo vertical de carga documental segura para una FAMILIA y un DEPORTISTA vinculado.

Criterio funcional:

Una familia autenticada puede enviar un PDF, JPEG o PNG válido para un deportista autorizado. El backend verifica la operación, registra el documento en estado CARGADO y permite consultar sus metadatos mediante D1.

No es una autorización para utilizar Supabase real ni documentación personal.

## Organización

1. Sincronizá main y creá una nueva rama de funcionalidad.
2. Conservá intactas las cinco entradas locales sin seguimiento.
3. Revisá el código de D1, D2, D3a, B1 y B2 y reutilizá las garantías existentes.
4. Registrá un plan ODD compacto, orientado al comportamiento completo.
5. Organizá la implementación en cambios internos coherentes, pero no abras un PR por cada adaptador, validador o servicio.
6. Solicitá intervención humana solo ante decisiones realmente bloqueantes, consentimientos RDD exigidos o excepciones obligatorias.

## Alcance funcional

Implementá el primer endpoint de carga:

`POST /api/familia/deportistas/{id}/documentos`

Debe:

- Autorizar según sesión, escuela, familia y vínculo activo.
- Mantener CSRF y las protecciones existentes.
- Verificar permisos antes de procesar y nuevamente al publicar, coordinándose correctamente con revocaciones concurrentes.
- Aceptar un archivo por solicitud.
- Admitir PDF, JPEG y PNG con límite inicial configurable de 15.000.000 bytes.
- Validar tamaño real, estructura y contenido, no solamente extensión o MIME.
- Rechazar archivos malformados, PDF cifrados, JavaScript y adjuntos embebidos.
- Controlar consumo de memoria, temporales y recursos de procesamiento.
- Integrar escaneo mediante ClamAV privado, con fallo cerrado.
- Utilizar los destinos preparados de B2 y las operaciones durables de V6.
- Conservar idempotencia, estados de incertidumbre y controles de concurrencia.
- Crear Documento con estado CARGADO y registrar auditoría en una transacción corta.
- Evitar duplicados ante reintentos con una operación ya confirmada.
- No borrar objetos cuando exista incertidumbre sobre su publicación.

No implementar revisión, reemplazo, ZIP, descarga, carga de tutores ni flujos específicos de aptos físicos en esta entrega.

## Seguridad y consistencia

Usá los componentes existentes. No introduzcas otra capa de infraestructura si no existe un problema demostrado.

No implementes reconciliación automática de operaciones inciertas ni eliminación genérica por clave. Cuando no se pueda confirmar el resultado, preservá la operación durable para posterior resolución supervisada.

No mantengas transacciones PostgreSQL abiertas durante la transferencia o el escaneo.

La integración real debe permanecer desactivada por defecto. Los dobles de prueba sólo podrán utilizarse en perfiles y entornos de test.

## Validación integral

Prepará una demostración reproducible con:

- PostgreSQL 17 descartable.
- Storage HTTP simulado.
- Scanner simulado para pruebas automatizadas.
- Archivos completamente sintéticos.
- Sesiones y familias de prueba.

Demostrá al menos:

1. Familia autorizada: carga exitosa y documento visible por D1.
2. Familia ajena o vínculo revocado: operación denegada.
3. Archivo inválido o peligroso: rechazo sin publicación.
4. Fallo o indisponibilidad del scanner: rechazo seguro.
5. Timeout o resultado incierto de Storage: no publicar éxito supuesto.
6. Repetición de carga confirmada: sin duplicar documento.
7. Concurrencia y revocación: sin publicación fuera de autorización.
8. Ausencia de claves Storage, credenciales y datos sensibles en respuestas y logs.

Ejecutá pruebas específicas, regresiones y suites completas. Realizá verificación independiente y RDD conforme al riesgo, sin repetir verificaciones de código idéntico injustificadamente.

## Restricciones de entrega

No hacer commit hasta completar las verificaciones y obtener cualquier excepción de tamaño necesaria.

No hacer push, PR ni merge todavía.

No modificar V1–V6 sin demostrar la necesidad y solicitar autorización específica.

No conectar Supabase real, desplegar ni ejecutar migraciones sobre bases compartidas.

Mantené B1-C abierta.

## Resultado esperado

Entregá una primera carga documental funcional y demostrable en entorno aislado.

Informá qué funciona, pruebas, riesgos residuales, limitaciones para producción y decisiones estrictamente necesarias para su publicación.

No propongas otra cadena de siete entregas independientes salvo que exista una justificación técnica bloqueante.

### L2 — Excepción operativa propia y preparación

El usuario respondió literalmente «autorizo» a: «¿Autorizás, exclusivamente para este flujo vertical, usar spawn_agent/wait_agent sin list_agents, con el modelo heredado para exploración y gpt-6.1-sol/medium para la verificación independiente, manteniendo RDD completo y registrando la excepción en ODD?» La excepción no constituye cumplimiento literal de la superficie habitual v2 y no se extiende a otras unidades, tamaños ni consentimiento RDD.

main/origin/main sincronizados en la base anterior; rama nueva creada sin cambios previos. Las cinco entradas ajenas se mantienen fuera de Git; snapshot externo de 2340 entradas en `/tmp/carga-familia-protegidas-62cc0hn2.json`. Explorador Arendt `01a1addd-9b04-7191-b78f-500ead35cb53`, tarea `carga_vertical_mapa`, modelo heredado, sólo lectura; wait_agent confirmó estado terminal completed: mapa conforme, skill_resolution paths-injected, sin pruebas ni escrituras. V6/B2 permiten la vertical sin cambiar migraciones; el padre comprobó el predicado de sesión y la revocación administrativa. No se atribuye verificación al explorador.

Una entrega vertical, con cambios internos y pruebas juntos, no siete PRs. Pronóstico provisional 1800–2800 líneas, a recalcular tras el mapa; no es excepción de tamaño ni autorización de commit. No hay razón para delegar escritura por tamaño: padre inline, exploración de mapa y verificación separadas por sus reglas. B1-C permanece abierta. Risk: ítems 1/2/4; preservación de formato/contexto durables, seguridad y concurrencia.

### L3 — Diseño acotado tras el mapa

Se mantiene la semántica vigente de sesión JWT: firma/expiración y usuario/escuela/familia activos; no se inventa revocación persistida por logout. La carga revalida expiración y filas de autorización antes de la publicación. Publicación y revocación se serializan por filas comunes, con lock_timeout existente, sin transacciones durante parser/scanner/Storage. Se prueba la revocación de vínculo durante el escaneo/transferencia. La ausencia de revocación de jti por logout se documentará como límite previo, no como garantía implementada.

No hay parser PDF mantenido cacheado. Se incorpora sólo PDFBox 3.0.8 y sus dependencias necesarias: inspección del grafo COS sin ejecutar contenido; se rechazan cifrado, acciones/JavaScript y archivos embebidos. El parser de archivos no confiables se ejecutará con heap y tiempo acotados en proceso JVM hijo, sin servicio adicional, para no confundir cancelación de un Future con terminación real de un parser bloqueado. Se validan imágenes con límites de dimensiones/píxeles, estructura y decodificación. ClamAV usa protocolo privado INSTREAM, resultado limpio exacto y plazos de conexión/escritura/lectura, sin dependencias de scanner. Fuentes oficiales consultadas: https://pdfbox.apache.org/download.cgi, https://pdfbox.apache.org/security.html, https://pdfbox.apache.org/3.0/migration.html; protocolo ClamAV pendiente de comprobar antes de implementarlo.

Contrato propuesto de esta unidad: multipart con exactamente archivo/tipo y temporadaId opcional, cabecera Idempotency-Key UUID; no claves/rutas de Storage ni escuela/familia del cliente. Los tipos se limitan a los de deportista admitidos por V6, sin APTO_FISICO ni tutores; DNI sin temporada, OTRO sin cardinalidad adicional. Respuesta con DTO D1, 201 al crear y 200 al repetir confirmado tras reautorizar y comprobar contexto/manifiesto; conflicto para idempotencia/contexto distinto o ejecución en curso/incierta. No se reenvía Storage ni se borra por clave. Temporales privados y permisos POSIX; sin nombres originales persistidos (nombre documental técnico neutro para la columna obligatoria). Habilitación explícita, desactivada por defecto; mocks sólo en tests. Estos detalles técnicos no introducen políticas de retención legal ni reemplazo.

### L4 — RED/GREEN propio inicial y límites de evidencia

Contrato: RED real por ClassNotFoundException de PreparadorDocumento; el primer intento con ruta de tests incorrecta no cuenta como RED y su directorio accidental vacío fue retirado. Preparador: stub explícito sin validación seguido de RED 17/17 fallos; GREEN real 17/17 tras parser estricto acotado, PNG/JPEG con estructura, límites y SHA/temporales privados. Scanner: primer intento tenía una aserción con firma inválida y no cuenta; RED real 7 casos (6 fallos y error del caso válido porque el stub no abrió conexión), GREEN 7/7 con TCP INSTREAM sintético. Contrato + contenido + scanner: GREEN 25/25, cero fallos/errores/omitidos, `/tmp/carga-green-focal.log`. PDFBox 3.0.8 fue descargado de Maven Central y la compilación observada satisfactoria; no se usa servicio PDF adicional.

RED HTTP: antes de contar evidencia se corrigieron exclusivamente fixture ACTIVO sin autorización V4 y cabecera CSRF (la SPA exige token crudo de cookie, no el token XOR del JSON). RED válido observó 405 frente a 201 para familia autenticada con CSRF correcto, `/tmp/carga-red-http.log`; no se declara RED de negocio por los fallos de preparación anteriores. Ocho escenarios integrados en ejecución contra base exclusiva PG17 y fakes HTTP/TCP; sin resultado anticipado.

Implementación inline de transacciones separadas: autorización con filas comunes a revocación, reserva V6/manifiesto inmutable, scanner fuera de transacción, CAS TRANSFIRIENDO antes del único POST B2, ALMACENADA y publicación Documento/auditoría; fallos conservan operación, sin DELETE/reconciliación. Nueva ruta registrada en OpenAPI y filtro previo a multipart; integración desactivada por defecto. No afirmar aún robustez completa de producción ni todas las carreras: restan escenarios ampliados, regresiones y verificación independiente.

### L5 — Evidencia integrada, endurecimiento demostrado y verificación final pendiente

HTTP real con PostgreSQL 17 y servidores HTTP/TCP sintéticos: ocho escenarios iniciales GREEN; ampliación de 24 casos GREEN 24/24, cero fallos/errores/omitidos (`/tmp/carga-http-normalizada.log`). Incluye JPEG/PDF/PNG, D1, replay confirmado, contexto distinto, carreras sobre idempotencia/revocación/expiración/desactivación, publicación bloqueando una revocación real, scanner caído/infectado, transferencia tardía, CAS vencido, rollback de auditoría y respuesta perdida después de commit confirmado. Ningún éxito supuesto, DELETE ni reconciliación. La respuesta perdida conserva PUBLICADA y el reintento devuelve el mismo documento. La prueba de DNI existente inicialmente observó 503/transferencia innecesaria; la comprobación de primera carga bajo bloqueo común ahora rechaza 409 antes de transferir y conserva el replay confirmado.

RED/GREEN adicionales: PDFParser.parse() restablece leniencia aunque se configure antes; un xref corrupto aceptado pasó a rechazo con parse(false). PNG rechazará PLTE posterior a IDAT, demostrado frente al control positivo de paleta válida. Recursos temporales inseguros producen 503, no rechazo documental 422; scanner admite sólo direcciones IP privadas literales sin consulta DNS (sonda estática). Excepción transaccional previa al procesamiento y colisión con una operación preexistente de propietario tutor tienen pruebas negativas específicas; no se habilita carga de tutores.

El fallback CSRF de parámetros podía procesar multipart antes de autorizar: RED con getParameter prohibido; la cabecera X-XSRF-TOKEN se exige antes del CsrfFilter para cargas y solicitudes multipart inseguras. Multipart fuera del único POST permitido se rechaza antes del procesador; RED GET alcanzaba el procesador, GREEN 6/6 guardas (`/tmp/carga-green-multipart-alcance.log`), seguido de HTTP real de éxito/D1/replay 1/1 GREEN (`/tmp/carga-green-ultimo-post.log`). Inventarios de rutas y OpenAPI se actualizaron exclusivamente para el nuevo POST: dos fallos de inventario esperados por el nuevo contrato fueron corregidos en las expectativas exactas, sin ampliar rutas administrativas. Inventario corregido 12/12; la ejecución focal anterior de 89 casos había pasado los otros 77. No se presenta como una única ejecución 89/89.

Los fallos iniciales de fixtures/cabecera CSRF y una ejecución restringida sin attach de Mockito no son RED de negocio. Se requieren permisos locales para Mockito, sockets y Docker. No se atribuye automáticamente un error nuevo a B1-C: permanece abierta, sin causa demostrada. Se normalizaron únicamente archivos Java nuevos con Google Java Format temporal, sin dependencia de formato del proyecto ni cambios V1–V6. El riesgo real es 2/4/5 (autorización, concurrencia y parser mantenido añadido); el ítem 1 del pronóstico L2 no implica cambio de formato persistido o migración.

Verificación T5 autorizada, antes de revisión nativa: en copia nueva mktemp externa, ejecutar una vez `DOCUMENTOS_CARGA_HABILITADA=false DOCUMENTOS_STORAGE_HABILITADO=false ./mvnw -o clean verify` y después `DOCUMENTOS_CARGA_HABILITADA=false DOCUMENTOS_STORAGE_HABILITADO=false DOCKER_HOST=unix:///var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 ./mvnw -o -Pdb-tests verify`, en backend. Los tests habilitan explícitamente sus dobles locales. Incluir sondas independientes y una inspección desde el JAR empaquetado mediante PropertiesLauncher/loader.main del inspector: nunca arrancar la aplicación ni Flyway contra una base externa. Probar una entrada sintética válida y otra inválida, sin datos reales. El padre hará un spot-check y luego assess RDD del candidato completo; no hay consentimiento RDD ni excepción de tamaño implícitos.

Límites abiertos: scanner real/firmas/aislamiento TCP y permisos efectivos Supabase no validados; JVM hija acotada no equivale a sandbox de SO ni heap a RSS total; temporales tras caída abrupta y límites de clientes lentos requieren operación; no se implementa revocación persistida de JWT por logout, retención legal, compensación recuperable ni reconciliación. V5/V6 siguen necesitando autorización y planificación sobre bases compartidas. Risk: ítems 2/4/5.

Cierre focal previo a congelación: la colisión idempotente con propietario exclusivo tutor produjo RED 503 frente a 409; Objects.equals evita el null y GREEN PostgreSQL 1/1, `/tmp/carga-green-tx-propietario.log`. CannotCreateTransactionException no deriva de DataAccessException: RED mostró excepción no saneada; se captura RuntimeException tras preservar ExcepcionNegocio para devolver el 503 fijo. Normalización final sólo de Java nuevos; limpieza de espacio final en una línea del inventario modificado, sin semántica.

Saneamiento de preautorización GREEN 1/1, `/tmp/carga-green-tx-preautorizacion.log`. Dependencia POM presentada en formato legible coherente con el archivo, sin cambio de artefacto. Las cinco raíces siguen idénticas (2340 entradas, hash/modos) y V1–V6 sin diff. Congelación previa al verificador; suites completas todavía pendientes.

### L6 — Verificación independiente real: carga demostrada, suite PostgreSQL no aprobada

Agente distinto del implementador: Kant `01a1ae5d-6ffd-7733-a78a-4631b842b725`, tarea `carga_vertical_independiente`, gpt-6.1-sol/medium, fork_context=false; skills exactos resueltos e informados paths-injected. Se utilizó exclusivamente la excepción propia L2. wait_agent devolvió estado terminal completed y su informe completo; no se simula list_agents, RDD ni aprobación de verificabilidad total. Scratch fresco `/tmp/carga-verificacion-tzlu5aor`, copia desde base y overlay de 36 rutas con SHA/modos comprobados. Manifiesto de prueba previo a este registro: `c3aea07f2a426639759a6c9a0c49802e11dd9c3a8c799cab854dbba241ba427a` (hash de inventario externo, no identidad nativa RDD), +2994/−20.

| Verificación observada | Resultado |
|---|---|
| `DOCUMENTOS_CARGA_HABILITADA=false DOCUMENTOS_STORAGE_HABILITADO=false ./mvnw -o clean verify` | exit 0; 1054/1054, cero fallos/errores/omitidas |
| `DOCUMENTOS_CARGA_HABILITADA=false DOCUMENTOS_STORAGE_HABILITADO=false DOCKER_HOST=unix:///var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 ./mvnw -o -Pdb-tests verify` | exit 1; 533 ejecutadas, 0 fallos, 2 errores, 0 omitidas; NO GREEN |
| HTTP de carga en esa suite PG | 25/25; D1, escenarios de autorización/concurrencia, fallos y replay satisfactorios |
| Regresión V6/B1 en esa suite | 57/57 |
| Contenido / scanner / recursos / guardas nuevas | 19/19, 7/7, 6/6 y 6/6, respectivamente, dentro de suite normal |
| Sondas independientes de inspector empaquetado / guardas | 11/11 (salidas 0/42) y 12/12; sin arrancar aplicación ni Flyway |

Errores PG: `V5OperacionDbTest.respaldoSeRestauraEnOtroServidorYRecuperaV4TrasMigrar` (línea 24) e `incompatiblesRechazadosSinCambiosEnTodaLaBase[6]` (línea 86), ambos en `V5OperacionSupport.java:52` antes de ejecutar el escenario, CannotGetJdbcConnectionException / Connection refused a puertos locales dinámicos. Contraste focal en copia limpia de la base, `-Pdb-tests -Dtest=V5OperacionDbTest test`: exit 1, 11 ejecutadas, 0 fallos, 1 error, 0 omitidas; mismo constructor falló en caso [4]. Tests y soporte idénticos al baseline. Esto demuestra que la clase de fallo también existe en la base, NO demuestra su causa raíz ni atribuye automáticamente cada error a B1-C. El error individual de respaldo no se reprodujo en esa pasada. B1-C sigue abierta, sin declarar corrección ni eximir la suite. No se hacen reintentos ciegos ni una segunda suite para obtener verde.

Veredictos independientes S1–S5 y S7/S8 conformes dentro del entorno aislado; S6 PARCIAL por suite PG no aprobada y RDD aún no iniciado. No se detectó defecto funcional nuevo demostrado, pero eso no equivale a cierre integral. El padre conserva el resultado no disponible como bloqueo de prueba obligatoria: detener antes de iniciar RDD y solicitar decisión para diagnóstico acotado de conectividad/harness V5, sin corrección especulativa ni cambios de migraciones. No se pide aún excepción de tamaño.

Evidencia externa: normal.log/postgres.log/base-v5.log y salidas, reportes-normal/reportes-postgres, integridad.json/resultados-suites.json, sondas.py/sondas.json/sondas y SondaGuardas.java/guardas.log. Spot-check del padre: repetir sólo el comando del PNG válido mediante PropertiesLauncher, registrado aparte en spot-check-padre.json; no se repiten suites idénticas. Se conservan los temporales diagnósticos V5 `/tmp/banfield-v5-operativa-497071166335629761` y `/tmp/banfield-v5-operativa-18445966696343045868` fuera de Git mientras se decide el diagnóstico, pendientes de limpieza al cerrar su uso. Warning conocido de Mockito/ByteBuddy, sin omitir tests ni bajar controles.

Sin cambios ejecutables posteriores a las suites; este delta es únicamente trazabilidad. V1–V6 y las 2340 entradas protegidas mantienen bytes y modos. Sin secretos de alta confianza en el inventario, sin respaldos/temporales en las rutas candidatas, sin staging/commit/push/PR. Permanece desactivada la integración real. Estado: implementación local demostrada, cierre de verificación BLOQUEADO; no preparada para commit/publicación ni RDD hasta resolver la prueba faltante conforme al protocolo. Risk: ítems 2/4/5.

### L7 — Autorización de diagnóstico acotado, 2026-11-06 (textual)

Autorizo un diagnóstico acotado de los dos fallos JDBC del harness `V5OperacionDbTest` que bloquean la carga documental vertical.

### Contexto

Candidato funcional: 36 archivos, +3015/−20.

Evidencias:
- Suite normal: 1054/1054.
- PostgreSQL: 531 aprobadas, 2 errores.
- HTTP de carga: 25/25.
- V6: 57/57.
- Comprobaciones independientes: 23/23.
- El mismo tipo de fallo JDBC fue reproducido sobre una copia limpia del baseline.

RDD no iniciado. V1–V6 y las cinco entradas locales sin seguimiento están intactas.

### Objetivo

Determinar si el rechazo de conexión proviene de una regresión del candidato, un defecto del harness o una intermitencia ambiental.

No invertir recursos en repetir pruebas indefinidamente sin obtener información nueva.

### Procedimiento

1. Analizá primero las evidencias y los errores completos ya disponibles.
2. Identificá las dos pruebas afectadas y la fase exacta del rechazo JDBC.
3. Inspeccioná el ciclo de vida del contenedor PostgreSQL, su estado al fallar, el host y puerto JDBC efectivos, la asignación de puertos y el teardown.
4. Revisá posibles interferencias por paralelismo, procesos concurrentes, Docker/WSL2 y reutilización del contenedor.
5. Compará el harness baseline con el de la rama funcional.
6. Realizá únicamente reproducciones adicionales que aporten evidencia nueva; capturá estado del contenedor y puertos durante el fallo.
7. Si identificás una causa demostrable, proponé la corrección mínima y aplicala únicamente dentro del alcance autorizado.
8. No introducir sleeps, reintentos ciegos, desactivación de tests ni modificaciones de aserciones para ocultar fallos.
9. No modificar V1–V6 ni el flujo funcional de carga documental.

### Validación

Si realizás una corrección:
- Demostrá primero el defecto y su resolución.
- Ejecutá las pruebas afectadas.
- Repetí las suites necesarias para comprobar ausencia de regresiones.
- Solicitá verificación independiente de la corrección.

Si no se demuestra la causa:
- No modifiques código especulativamente.
- Conservá B1-C abierta.
- Separá claramente la verificación funcional aprobada de la incidencia ambiental no resuelta.
- Determiná si ODD permite avanzar a RDD con esa incidencia registrada o si existe un bloqueo obligatorio.
- Presentá una recomendación concreta para decidir el siguiente paso, sin volver a iniciar una cadena de diagnósticos equivalentes.

### Restricciones

No hacer commit, push ni PR.
No iniciar RDD si las reglas ODD todavía lo impiden.
No ampliar el alcance del candidato.
No conectar Supabase real ni ejecutar migraciones compartidas.
No modificar las cinco entradas locales sin seguimiento.

Entregá el dictamen: REGRESIÓN DEMOSTRADA, DEFECTO DEL HARNESS, INCIDENCIA AMBIENTAL DEMOSTRADA o CAUSA INCONCLUSA.

Indicá exactamente qué evidencia respalda la clasificación y si el candidato puede pasar a RDD.

### L8 — Plan diagnóstico y límites

Se revisaron primero los errores completos conservados y el constructor del soporte: el rechazo ocurre en SHOW server_version, antes de prepararV4 o ejecutar V5. Harness sin diff respecto a 64cf0abd; no se reutiliza conexión cerrada por el flujo funcional (DataSource crea conexión nueva). El catch actual cierra el contenedor antes de conservar evidencia del estado fallido, por lo que los logs anteriores no prueban proceso muerto ni fallo de Docker/WSL2.

Mapa delegado de sólo lectura a Gauss `01a1aeee-ae0e-7a21-8c59-67db07db9b2e`, tarea jdbc_v5_mapa_causal, modelo heredado y excepción propia L2; sin nuevas pruebas ni escrituras. El padre conserva implementación/diagnóstico. Próximo paso condicionado al mapa: una comparación instrumental acotada de la clase V5 en copias scratch baseline/candidato, capturando antes de close estado/puerto/logs y listener real, sin convertir fallo en éxito. No repetir suites completas salvo corrección causal demostrada y verificación requerida. Si la captura no demuestra causa, dictamen inconcluso y decisión única de verificabilidad según reglas ODD; no continuar una cadena equivalente. No hay excepción de tamaño, aprobación RDD ni permiso de publicación.

### L9 — Dictamen del diagnóstico acotado: CAUSA INCONCLUSA

Gauss jdbc_v5_mapa_causal terminó completed mediante wait_agent; paths-injected, sólo lectura sin pruebas. Su mapa, contrastado por el padre con el source JAR 2.0.5, confirma PostgreSQLContainer espera dos mensajes ready y sobreescribe el sondeo JDBC heredado. Esto por sí solo no prueba un defecto del harness ni una falsa coincidencia de fases. El padre usó Python zipfile tras comprobar que unzip no está instalado; no se descargaron herramientas.

Captura independiente ejecutada por Beauvoir `01a1aef2-e3dd-72c1-9254-c0ec7458334f`, tarea jdbc_v5_captura, gpt-6.1-sol/medium y excepción propia L2. wait_agent confirmó terminal completed, paths-injected. Una sola pareja secuencial de `./mvnw -o -Pdb-tests -Dtest=V5OperacionDbTest test` con las mismas variables locales documentadas en L5 y habilitación de diagnósticos por JAVA_TOOL_OPTIONS, en copias nuevas externas. Instrumentación idéntica exclusivamente en V5OperacionSupport y helper DiagnosticoPostgres de esas copias; no se entrega al candidato. No hubo segunda serie, sleeps ni reintentos JDBC. El pronóstico de 22 arranques fue inexacto: 22 escenarios, 24 contenedores, ya que el ensayo de restauración usa dos servidores.

| Variante instrumentada | Exit | Pruebas | Fallos / errores / omitidas |
|---|---:|---:|---:|
| Baseline 64cf0abd | 1 | 11 | 0 / 1 / 0 |
| Candidato, mismos ejecutables que L6 | 0 | 11 | 0 / 0 / 0 |

Nueva captura antes de close: baseline incompatibles[5], caso bifurcacion, falla en SHOW server_version antes de prepararV4. Contenedor propio b1e1d516…, URL efectiva host 127.0.0.1, puerto 53574. UTC 2026-11-06: PG definitivo PID1 registra escucha IPv4/IPv6 a 03:47:16.928 y ready a .933; start retorna a .935410895, rechazo JDBC a .937052976; inspect fresco .949595690 muestra running=true, OOM=false, restart=0 y mismos bindings 0.0.0.0/::53574 que cache y URL. Una única sonda socket .950799649 también recibe rechazo. Después, 03:47:17.028502956 se comprueba listener interno 5432 y PID1 vivo; pg_isready TCP interno termina exit0 a 03:47:17.098986909. Recién a .176282795 se solicita close por catch, seguido de kill/die/destroy. El error JDBC original se conserva en XML; las excepciones diagnósticas quedan suprimidas, no convierten FAIL en PASS. El FATAL role root de la sonda pg_isready es posterior al fallo original y no es su causa.

La evidencia descarta para esa captura muerte previa, OOM o reinicio observado, puerto obsoleto/discrepante y destrucción previa por teardown; no usa una conexión JDBC reutilizada tras cerrar. La secuencia de logs corresponde al postmaster definitivo, no a una confusión demostrada con el servidor temporal. Dos instantáneas no encontraron Maven/Surefire concurrentes dentro del alcance inspeccionado, pero no excluyen interferencia externa. No hay configuración de paralelismo/forks/reuse explícita ni diferencia del harness con baseline. Las sondas posteriores y la perturbación temporal de instrumentar limitan las inferencias.

Dictamen: CAUSA INCONCLUSA. Quedó acotada la frontera host→puerto publicado con PostgreSQL vivo e internamente disponible en las observaciones; no se demuestra raíz Docker/WSL2, carrera concreta ni corrección mínima. No hay REGRESIÓN DEMOSTRADA, DEFECTO DEL HARNESS demostrado ni INCIDENCIA AMBIENTAL DEMOSTRADA en cuanto a causa raíz. No modificar readiness, host, aserciones, reintentos, tests, migraciones o flujo funcional por hipótesis. B1-C sigue abierta sin atribuir automáticamente los dos errores históricos a esta misma causa.

Evidencia: `/tmp/jdbc-v5-captura-b10ve14_/DICTAMEN.md`, resultados.json, captura-selectiva.json, integridad.json, logs/XML de ambas variantes y `evidencia-baseline/pg-8f68a965-9531-4ab1-8032-e1d3d03704e5.log` (líneas 66/67/74/76/78/118/184/187/190/193). Parche instrumental externo SHA256 `32b89640938d3ca4686fa296647e8386990f70fb9179b3eb7ac75a5ef2a57544`; spot-check del padre recalculó ese hash, contrastó resultados XML/JSON y cronología, sin repetir pruebas. Los temporales instrumentales y evidencia sintética quedan fuera de Git, conservados para trazabilidad y pendientes de limpieza al terminar su uso.

ODD: S6 pide suite completa PostgreSQL; el comando obligatorio conserva exit1 y dos errores. La regla de verificación exige marcar partial cualquier otro comando requerido fallido y no sustituye esa prueba con RDD. Por ello NO puede pasar a RDD como candidato completamente verificado ni iniciar esa revisión en este estado. El 11/11 focal instrumentado no reemplaza ni recalcula la suite anterior 531 aprobadas/2 errores. Se conservan como válidas 1054 normales, 25 HTTP, 57 V6 y 23 sondas independientes; no se afirma cierre integral.

Recomendación concreta: cerrar esta ronda diagnóstica y no ejecutar más series equivalentes en el mismo entorno. Mantener RDD/commit bloqueados; para obtener la prueba faltante, proponer una única suite PG17 completa en un entorno Linux aislado con Docker nativo y las mismas versiones/datos sintéticos, como contraste de infraestructura y no como corrección supuesta. Requiere disponer y autorizar ese entorno; no se inicia conexión remota ni nueva ejecución automáticamente, no se solicita una excepción implícita al requisito. Si ese entorno no está disponible, conservar el bloqueo en vez de declarar aprobada la suite. No hay nuevas escrituras ejecutables, commit, push, PR, Supabase ni migraciones compartidas. Risk: none para el delta entregado de diagnóstico (trazabilidad pasiva); riesgos 2/4/5 del candidato y B1-C permanecen.

### L10 — Ronda diagnóstica cerrada; entorno alternativo no disponible, 2026-11-06

Solicitud del usuario (textual):

Acepto el dictamen CAUSA INCONCLUSA y autorizo cerrar esta ronda de diagnóstico B1-C.

Autorizo una única ejecución de la suite PostgreSQL completa del candidato de carga documental en un entorno Linux aislado con Docker nativo, si está disponible.

### Procedimiento

1. No repetir diagnósticos equivalentes en WSL2.
2. Verificá primero si existe un entorno Linux aislado con Docker nativo disponible.
3. Utilizá el mismo candidato, versiones de Java, PostgreSQL y dependencias, con datos exclusivamente sintéticos.
4. No modificar código productivo, harness ni migraciones V1–V6.
5. Ejecutá una sola suite PostgreSQL completa y conservá logs y resultado.
6. Si aprueba íntegramente, registrá la evidencia y evaluá el pase a RDD según las reglas ODD.
7. Si falla, registrá la causa observable y determiná si hay evidencia nueva que justifique una corrección puntual.
8. Si no existe un entorno disponible, detenete e informá la alternativa más sencilla, indicando si requiere infraestructura o publicación remota.

### Restricciones

- No hacer commit, push ni PR.
- No desplegar ni conectar Supabase real.
- No ejecutar migraciones sobre bases compartidas.
- No modificar las cinco entradas locales sin seguimiento.
- No iniciar RDD sin satisfacer la verificación obligatoria.
- Mantener B1-C abierta mientras no se demuestre su causa raíz.

### Resultado

Informá solamente:
- Entorno utilizado o disponibilidad.
- Resultado de la suite completa, si se ejecutó.
- Evidencia nueva.
- Si puede comenzar RDD.
- Próxima acción necesaria.

Priorizá el cierre de la funcionalidad. No amplíes la investigación sin evidencia nueva.

Disponibilidad comprobada mediante metadatos locales, sin lanzar contenedores/pruebas ni consultar contextos, accesos o credenciales remotos: Ubuntu 26.04 LTS, kernel 6.18.33.2-microsoft-standard-WSL2; sólo cliente docker, sin dockerd local ni podman, sin sockets rootless/Podman en ubicaciones estándar. El único socket Docker local comprobado es /run/docker.sock. Consulta explícita `docker -H unix:///var/run/docker.sock info --format 'OS={{.OperatingSystem}}; Kernel={{.KernelVersion}}; Tipo={{.OSType}}; Docker={{.ServerVersion}}'`: OS=Docker Desktop; Kernel=6.18.33.2-microsoft-standard-WSL2; Tipo=linux; Docker=29.9.2. El primer acceso restringido al socket fue denegado y se solicitó escalación para la misma consulta de metadatos, no para pruebas. No se infiere disponibilidad de otros equipos/VM o acceso remoto por credenciales ambientales.

No se encontró un entorno Linux alternativo con Docker nativo disponible/autorizado desde esta sesión. La ejecución única condicional NO se consumió: suite completa NO ejecutada; no se repitió diagnóstico WSL2. Código funcional y pruebas permanecen idénticos; únicamente esta trazabilidad documental cambia. Ronda diagnóstica cerrada por aceptación expresa; B1-C sigue abierta, raíz inconclusa. RDD permanece bloqueado por la verificación obligatoria PG pendiente.

Alternativa mínima propuesta: disponer una VM Linux local fuera de WSL2 con Docker Engine nativo, las mismas versiones de Java/imagen PostgreSQL/dependencias y copia del candidato mediante archivo local, sin commit, push, PR ni publicación remota. Requiere aprovisionar infraestructura local y autorizar el acceso/transferencia concretos; no se crea ni contacta automáticamente. Una vez disponible, ejecutar exclusivamente la suite completa ya autorizada y conservar evidencia. No añadir investigación equivalente ni suponer resuelto el fallo.

### L11 — Excepción propia de tamaño y publicación exclusiva para CI, 2026-11-06

Autorización explícita del usuario: «Autorizo explícitamente la excepción de tamaño y la publicación excepcional para CI de la carga documental vertical.» Alcance: «Repositorio: gonzav104/banfield-patin.»; «Rama remota exclusiva: test/carga-documental-ci.»; «Candidato: 36 archivos, 3.176 líneas modificadas (+3.156/−20), más el registro documental ODD mínimo de esta autorización.»; «Se permite utilizar la sesión actual de GitHub CLI para publicar únicamente esa rama y consultar workflows y logs.»; «Esta publicación es exclusivamente para verificación, no para aprobación funcional.»

Justificación: conservar juntos el flujo vertical, pruebas y documentación; evitar fraccionar una verificación integral. Esta excepción permite únicamente el commit/publicación de validación antes del cierre funcional/RDD, porque el entorno local Docker Desktop/WSL2 no produjo la prueba PG obligatoria. No hereda excepciones anteriores ni autoriza entrega aprobada, otras ramas, PR, merge, despliegue o alteración de producción/tests/V1–V6. RDD permanece activado; consentimiento nativo posterior separado. Las reglas locales de entrega (AGENTS global, Delivery follows ordinary repository policy) hacen informativos los gates nativos; no se desactiva, omite ni simula ningún control.

Prepublicación: 36 rutas autorizadas; 35 archivos no ODD idénticos en bytes/modos al manifiesto funcional; sólo trazabilidad ODD adicional. V1–V6 intactas, sin rutas ajenas, secretos de alta confianza, credenciales persistidas, datos personales reales ni temporales/respaldos añadidos; cinco raíces excluidas e idénticas (2340 entradas). Sesión gh existente gonzav104/HTTPS comprobada sin exponer tokens ni cambiar autenticación. Workflow backend.yml existente: push en cualquier rama, Ubuntu-latest, Temurin 21 (parche no fijado), suites normal y db/Testcontainers PostgreSQL 17; sin Supabase, secretos de proveedor o despliegue. No se modifica workflow ni se repite WSL2. Estado anterior PG 531 aprobadas/2 errores y B1-C abierta se conserva hasta evidencia remota; el nuevo resultado no resolverá por sí solo la causa raíz. Después de CI, registrar evidencia local sin segundo push automático ni atribuir aprobación RDD. Risk: ítem 5 (publicación excepcional para CI expresamente autorizada); controles funcionales y RDD aún pendientes.

### L12 — Snapshot de validación publicado; CI en curso

Commit exclusivo de validación `2f5b9a2fdc89d8a0d60c7fcf375bc03449e8933a`, mensaje `test(documentos): validar carga documental vertical en CI`. Rama local/remota `test/carga-documental-ci`; base/main remoto confirmado `64cf0abd5d3a3eece22c260cff85819856dd1b41`. Publicadas exactamente 36 rutas, +3168/−20 = 3188 líneas: las 3176 autorizadas más 12 líneas de registro mínimo ODD. Todos los blobs/modos coinciden con el índice validado y 35 archivos no ODD permanecen idénticos al candidato funcional; cinco raíces intactas. Push único sin force, tags ni submódulos, credencial gh sólo para el comando sin cambiar autenticación persistida. SHA remoto comprobado por API. Sin PR ni otras ramas publicadas.

Assess nativo informativo del rango base→commit: high, review_due=true/high_risk, 36 rutas/3188 líneas, motivo proceso hijo más escalación de seguridad. Primer assess exigió declaración explícita de archivos sin seguimiento; STATUS read-only inicial encontró restricción de escritura del sandbox y su repetición autorizada obtuvo inventario canónico. Forecast nativo: horizon=partial; step=1, kind=collect, reason_code=intended_untracked_selection_required, description=intended untracked selection required. Se declaró untracked-scope=exclude con inventario nativo sha256:518422689781839570f7e00c18abdd56fb9508a13c61b596d9e0ebd1c6a1df54 y assess terminó correctamente. No se ejecutó START, consentimiento, reviewer, acknowledge ni cambio de modo RDD; la excepción sólo permite CI previo, no baja el riesgo ni aprueba entrega.

Único workflow push backend, run `42163856915`, https://github.com/gonzav104/banfield-patin/actions/runs/42163856915, vinculado al SHA exacto. Observación de la ejecución larga delegada a Chandrasekhar `01a1af29-d620-7520-9279-2979633532da`, tarea carga_ci_resultados, gpt-6.1-sol/medium, excepción propia L2, acceso remoto limitado a leer este run/jobs/logs con la sesión gh autorizada. No puede reejecutar, despachar, cancelar, corregir código ni publicar. Resultado aún pendiente; no se declara éxito ni habilitación RDD. El registro final de resultados permanecerá local sin segundo push automático, para no disparar otra ejecución.

### L13 — CI excepcional completa satisfactoria; condición funcional para RDD satisfecha

Run https://github.com/gonzav104/banfield-patin/actions/runs/42163856915, evento push, intento 1, completed/success; HEAD exacto `2f5b9a2fdc89d8a0d60c7fcf375bc03449e8933a`, rama `test/carga-documental-ci`. Chandrasekhar carga_ci_resultados terminó completed según wait_agent, paths-injected, sin escrituras al workspace ni nuevas ejecuciones. El padre hizo un único spot-check remoto de metadatos y contrastó los totales finales con los logs ya descargados, sin repetir tests.

| Job | Resultado remoto | Conteos |
|---|---|---|
| [Suite normal](https://github.com/gonzav104/banfield-patin/actions/runs/42163856915/job/127715600546) | BUILD SUCCESS | 1054 pruebas, 0 fallos, 0 errores, 0 omitidas; suma de 98 clases coincide |
| [Suite db PostgreSQL 17](https://github.com/gonzav104/banfield-patin/actions/runs/42163856915/job/127715600266) | BUILD SUCCESS | 533 pruebas, 0 fallos, 0 errores, 0 omitidas; suma de 51 clases coincide |

En la suite remota: HTTP de carga 25/25, B1/V6 57/57, V5OperacionDbTest 11/11; D1 consulta 7+4, integridad V5 21, casos límite V5 4; adaptador Storage 33, contrato recuperable 2, configuración 17 y almacén recuperable 58, todos sin errores/omitidos. No se recalculan ni borran los resultados locales previos fallidos: la nueva prueba completa corresponde a este commit/runner y satisface la evidencia obligatoria pendiente. Las verificaciones independientes anteriores mantienen aplicabilidad porque no cambió código/pruebas.

Entorno observado en logs: Ubuntu 26.04.1 LTS, imagen ubuntu-26.04 versión 20261101.186.1; Temurin 21.0.13+9; Docker servidor 29.4.2 API 1.54; Testcontainers 2.0.5, postgres:17-alpine PostgreSQL 17.11. Digest de imagen no registrado; no se presume identidad binaria del runner con WSL2. El workflow existente fija Java 21 pero no su parche: local 21.0.12.1 versus CI 21.0.13+9, ambas ejecuciones normales satisfactorias; no se cambió Java, POM o workflow para obtener verde. Warnings de agente dinámico Mockito/ByteBuddy y CDS, sin pruebas omitidas. No conexión Supabase real ni migraciones compartidas.

Evidencia privada fuera de Git: `/tmp/carga-ci-resultados-99jENPA3/`, metadata-inicial.json/metadata-final.json, spot-check-padre.json, watch.log/watch-exit.txt, run-completo.log, normal.log, postgres.log, analisis.json, resumen.md y SHA256SUMS. SHA256 run-completo.log `07ac6bc28ccac8780fc0a73d675044846d477244b552166a730a3ab4b824145e`; normal.log `cb84442d644199318d6e7fd314a93d6a0ed67dce66f76faa108ab76244a185f6`; postgres.log `c2a39bcd777cb887b78f40ce47b6f55ecd66c7f3fd6d15f241c18f444e3d5085`. Ningún log, respaldo o credencial se incorpora al candidato.

Estado ODD: verificación funcional obligatoria e independiente satisfecha, antes bloqueada por PG; ahora puede solicitarse consentimiento nativo RDD del candidato. Estado RDD: assess high/review_due=true vigente sobre el commit, sin START, reviewers, reconocimiento ni aprobación del candidato. La publicación de validación no es entrega funcional aprobada ni Ready/PR/merge. Este registro de CI es delta documental local sin commit/push adicional automático: único push y único run. B1-C sigue ABIERTA y CAUSA INCONCLUSA: el éxito Ubuntu no demuestra causa raíz del rechazo host→puerto publicado local ni permite declararlo corregido. Siguen pendientes permisos/servicios reales Supabase/ClamAV, operación de temporales/retención/reconciliación supervisada y planificación/autorización V5/V6 sobre bases compartidas. Risk: ítems 2/4/5 del candidato; registro posterior de evidencia pasivo, sin nueva aprobación atribuida.

### L14 — RDD bloqueado antes de crear autoridad

El usuario respondió literalmente `granted` al consentimiento de riesgo alto del candidato completo (36 archivos/3213 líneas, sha256:e44c2afc1b017fb9d84c4367b12e97f2ce24202ff8c7e94391a94281ed26d24c). Se ejecutó una sola vez la invocación exacta devuelta por el proveedor. START rechazado: `lens_context_budget_exceeded`, fase preflight, mutation_outcome=not_started, authority_applicability=not_evaluated, next_action=stop; lineage review-da4299c3fe002c15. No se creó autoridad ni se ejecutaron lentes, correcciones, dictamen o reconocimiento. El proveedor prohíbe truncar evidencia y advierte que reintentar el mismo candidato no puede funcionar; no hay nada que abandonar/reparar. La excepción de tamaño de entrega no amplía este límite nativo. CI 1054/533 y verificaciones previas siguen válidas; B1-C continúa abierta. Necesita decisión del usuario para organizar cortes locales de revisión sin cambiar el árbol funcional final ni la rama remota; cada candidato reducido requerirá su consentimiento. Sin nuevo commit, push, PR o modificación ejecutable.

### L15 — Cambio formal de RF-09 y correcciones mínimas de la carga, 2026-11-06

Decisión de producto del usuario (textual): «RF-09 cambia formalmente. El MVP tendrá un límite predeterminado de 5.000.000 bytes por archivo, configurable. Ya no se exige un mínimo efectivo de 15 MB. Conservamos la capacidad técnica de configuración existente.» Se reescribe sólo S2 y se actualiza únicamente la fila RF-09 de `docs/REQUERIMIENTOS.md`; L1 conserva el texto original. Reabre únicamente T2 por el valor de S2, cubierto por T6.

Alcance aprobado (textual): «Establecer el límite predeterminado de archivo en **5.000.000 bytes (5 MB)**, tanto en backend como en los límites multipart correspondientes. Mantenerlo configurable.»; «Aprobar 16.000.000 píxeles como máximo inicial.»; «utilizando únicamente DCT como stop filter. Mantener límites explícitos contra descompresión excesiva»; «Si esto requiere una implementación compleja, diferir esa compatibilidad y mantener el rechazo seguro.»; «Aprobar Idempotency-Key en CORS sin ampliar los orígenes ni reducir las protecciones CSRF.» Ultra HDR/MPF queda diferido: todo byte posterior al EOI sigue rechazado y las pruebas lo fijan.

Evidencia: suite normal `./mvnw -o clean verify` 1083/1083 sobre el árbol final (sin archivos de backend más nuevos que esa compilación); RED observado antes de cada corrección; comparación diferencial base vs final sobre 25 archivos sintéticos, sólo cambian los PDF escaneados. Verificador independiente: bloqueante B1 (JPEG de 16000×16000 dentro de PDF pasaba al no decodificar DCT), corregido con una verificación del encabezado de cuadro y recheck limitado: cerrado. Advertencias sin bloqueo: DCT con encabezado válido seguido de basura se acepta (diseño sin decodificar ráster); cadenas Flate duplicadas pasan también en la base. Suite PostgreSQL y el test HTTP de 5.000.000/5.000.001 bytes NO ejecutados localmente (sin Docker): pendientes de CI. Conteos esperados: normal 1083; PG 534 si no cambia nada más (533 + 1 prueba nueva).

Estado: sin commit, push, PR ni RDD. La publicación de una rama nueva para CI NO está cubierta por L11 (rama exclusiva `test/carga-documental-ci`) y requiere autorización nueva. Risk: ítem 2 (validación de archivos y política CORS); ítem 3 no aplica (se amplía una cabecera permitida; no se retira ni cambia un contrato consumido).
