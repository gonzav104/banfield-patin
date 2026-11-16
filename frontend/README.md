# Frontend — Banfield Patín Carrera

React + TypeScript estricto + Vite + React Router + TanStack Query + Tailwind v4 + React Hook Form + Zod + Axios.
Stack y reglas: ver [`AGENTS.md`](./AGENTS.md). Estado actual: **FE-01 — autenticación y sesiones**
(login de familias, login ADMIN con MFA/TOTP, rutas protegidas, logout) y **FE-02 — invitaciones y registro de familias**
(panel ADMIN de invitaciones + registro por enlace) y **FE-03 (primera unidad) — portal de familias, solo lectura**
(dashboard y listado de deportistas vinculados) y **FE-04 — ficha del deportista y temporada vigente** (solo lectura) y **FE-05 / U2 y U3 — familias en el panel ADMIN**
(listado, detalle, alta, edición del nombre y activar/desactivar). No incluye tutores, licencias, empadronamiento, aptos físicos, competencias, rifas ni carga documental.

## Ejecutar localmente

Requisitos: Node 22+ (probado con 24), Java 21 y una base PostgreSQL vacía para el backend.

### 1. Backend

Desde `backend/`, con estas variables de entorno (valores de ejemplo; **generá los tuyos**, no los versiones):

```bash
export SPRING_PROFILES_ACTIVE=dev
export DB_URL=jdbc:postgresql://127.0.0.1:5432/NOMBRE_DB
export DB_USERNAME=...
export DB_PASSWORD=...
export JWT_SECRET="$(openssl rand -base64 48)"
export MFA_CLAVE_CIFRADO="$(openssl rand -base64 32)"
export AUTH_COOKIE_SECURE=false            # solo desarrollo local (http)
export BOOTSTRAP_ADMIN_HABILITADO=true     # crea el primer ADMIN al arrancar (idempotente)
export BOOTSTRAP_ADMIN_EMAIL=admin@example.test
export BOOTSTRAP_ADMIN_NOMBRE=Admin
export BOOTSTRAP_ADMIN_APELLIDO=Pruebas
export BOOTSTRAP_ADMIN_PASSWORD="elegí-una-de-10+-caracteres"
export FRONTEND_URL_BASE=http://127.0.0.1:5173   # el backend arma enlaceRegistro = {base}/registro/invitacion#{token}
./mvnw spring-boot:run
```

La carga documental y el storage quedan **deshabilitados** por defecto (`DOCUMENTOS_CARGA_HABILITADA=false`).
Flyway crea el esquema en el primer arranque.

### 2. Frontend

```bash
cd frontend
npm install
npm run dev        # http://127.0.0.1:5173  (proxy de /api -> http://127.0.0.1:8080)
```

Para apuntar a otro backend: `BACKEND_URL=http://host:puerto npm run dev`.

### 3. Recorrido manual

| Quién | URL | Qué esperar |
| --- | --- | --- |
| Administrador (primera vez) | `/admin/login` | contraseña → pantalla de enrolamiento: escanear el QR (o cargar la clave) en una app autenticadora → código de 6 dígitos → `/admin` |
| Administrador (siguientes) | `/admin/login` | contraseña → código de 6 dígitos → `/admin` |
| Familia | `/login` | correo + contraseña → `/familia` |

No hay registro público: las cuentas de familia se crean con una invitación emitida por un ADMIN (FE-02, abajo).
El acceso ADMIN no está enlazado desde ninguna pantalla pública.

### 4. Invitaciones y registro de familias (FE-02)

1. Ingresá como ADMIN (paso 3) y abrí **Invitaciones** en el menú (`/admin/invitaciones`).
2. **Nueva invitación**: elegí *Familia nueva* (nombre de referencia) o *Familia existente* (búsqueda), y opcionalmente un
   correo sugerido y los días de vigencia (vacío = valor por defecto del servidor).
3. Se muestra el **enlace una sola vez**: copialo con *Copiar enlace*. El backend no permite recuperarlo después; si se
   pierde, revocá la invitación y creá otra. Si el backend no tiene `FRONTEND_URL_BASE`, el enlace se arma con la
   dirección actual de la página (y se avisa).
4. Abrí el enlace en otro navegador o ventana privada: `/registro/invitacion#{token}`. Se valida la invitación, se
   completa nombre, apellido, correo y contraseña (≥ 10 caracteres) y se redirige a `/login` **sin iniciar sesión**.
   Los enlaces del formato anterior (`/registro/invitacion/{token}`) todavía se aceptan temporalmente; ver el riesgo
   residual en `docs/SEGURIDAD.md` §7.
5. En el listado podés filtrar por estado (Pendiente, Usada, Revocada, Vencida) y **revocar** invitaciones pendientes.

El backend responde siempre el mismo `400 INVITACION_NO_DISPONIBLE` para una invitación inexistente, vencida, revocada
o ya usada (decisión de seguridad, `docs/SEGURIDAD.md` §7), por lo que el registro muestra un único mensaje: "Esta
invitación no está disponible". El token va en el fragmento del enlace (el navegador no lo envía al servidor), viaja
solo en el cuerpo de las peticiones, se quita de la barra de direcciones al abrir el enlace y nunca se guarda en el navegador.

### 5. Portal de familias (FE-03, solo lectura)

Una cuenta FAMILIA ve, desde el teléfono o la computadora:

| Ruta | Qué muestra |
| --- | --- |
| `/familia` | saludo, nombre de la familia, tutores (solo nombre y parentesco), total de deportistas vinculados y hasta 3 de ellos |
| `/familia/deportistas` | listado paginado (10 por página): nombre, apellido, edad y estado **Activo/Inactivo**; cada tarjeta es un enlace a la ficha |
| `/familia/deportistas/:deportistaId` | ficha del deportista (FE-04): edad, estado en la escuela y datos de la temporada vigente (ver abajo) |

- Datos reales: `GET /api/familia/mi-familia` y `GET /api/familia/deportistas?page&size` (paginación del backend; el total sale de `totalElementos`).
  La familia **sale de la sesión**: el cliente nunca envía un identificador de familia y el backend es la autoridad.
- «Activo/Inactivo» es el estado administrativo del deportista en la escuela. **No** es una inscripción ni un estado de temporada.
- No se muestra DNI, CUIL, domicilio, teléfono ni identificadores; los datos que la interfaz no necesita se descartan al validar la respuesta.
- Tres situaciones distintas: cuenta sin familia asociada (no se llama a `/api/familia/*`), familia no disponible (`404 FAMILIA_NO_ENCONTRADA`) y familia sin deportistas.
- Navegación: barra inferior en pantallas chicas y lateral desde `md` (respeta las safe areas). La **edad la calcula el backend** (el listado ya no recibe la
  fecha de nacimiento); si falta o no es válida, la respuesta trae `edad: null` y se muestra «Edad no informada».
- Aislamiento en el cliente: las claves de caché llevan usuario y familia, y si `/api/auth/me` revela otra identidad se descarta el estado privado
  (la pantalla nunca muestra datos de la identidad anterior). Ver el hallazgo y su corrección en `odd/tasks/fe-03-portal-familias.md` (L6, L7).

#### Ficha del deportista (FE-04, solo lectura)

Flujo completo de la familia: `/login` → `/familia` → **Deportistas** → tarjeta del deportista → ficha → **← Volver a deportistas**. También se abre por la
dirección (`/familia/deportistas/<id>`), con recarga y con el historial del navegador. Al abrir la ficha, el foco queda en su título.

- **Qué muestra:** nombre y apellido; sección «Datos del deportista» con **Edad** y **Estado en la escuela** (Activo/Inactivo); sección «Temporada {año}» con
  Categoría, Rama, Divisional, **Número de corredor** y **Estado deportivo** (Activo/Inactivo/Suspendido); y una nota que explica que el estado en la escuela y el
  estado deportivo son dos cosas distintas. El número de corredor es texto: `007` no se convierte en `7`.
- **Qué no inventa:** «Suspendido» se muestra tal cual, sin motivo ni causa. Un campo nulo se muestra «No informado». Sin datos en la temporada vigente (el
  deportista no tiene fila o la escuela no tiene temporada activa) aparece un texto neutro y **nunca** una temporada anterior.
- **Contrato (backend, solo lectura):** `GET /api/familia/deportistas/{deportistaId}/ficha` →
  `{nombre, apellido, edad, activo, temporadaVigente: {anio, categoria, rama, divisional, numeroCorredor, estadoDeportivo} | null}`. Es una lista cerrada: no trae DNI,
  CUIL, fecha de nacimiento, domicilio, contacto ni identificadores. El esquema Zod (`athleteFichaSchema`) es estricto con los tipos y los largos del backend y descarta
  lo que no conoce. La edad se calcula con la zona `America/Argentina/Buenos_Aires`. El listado trae `{id, nombre, apellido, edad, activo}`.
- **Autorización:** la familia sale de la sesión. Un deportista de otra familia, uno inexistente, uno con vínculo revocado y uno de otra escuela dan el **mismo 404**
  (`DEPORTISTA_NO_ENCONTRADO`), y la pantalla muestra el mismo aviso «No encontramos este deportista» sin distinguirlos. Un id que no es UUID no llega al servidor.
  Sin sesión el servidor responde 401 y la dirección lleva al login; un ADMIN recibe 403 (el portal es solo de FAMILIA).
- **Aislamiento:** las claves de caché llevan usuario, familia y deportista; no hay datos de relleno mientras carga; la pantalla se monta con una `key` de identidad y
  deportista; una respuesta tardía de otra identidad o de otra ruta no se muestra (**demostrado con pruebas unitarias**; el E2E solo lo observa, ver la matriz de abajo). Durante la carga no hay datos privados en pantalla.
- **Mientras no cargue:** estado de carga («Cargando la ficha…»), sesión vencida (vuelve al login y, después de ingresar, a la misma ficha) y error de red con «Reintentar» (la sesión se conserva).

Para verlo con datos, un ADMIN crea y vincula deportistas con la API de administración (`POST /api/admin/deportistas`,
`POST /api/admin/familias/{id}/deportistas`); esta unidad no incluye pantallas de administración de deportistas (las del listado, detalle y alta están en la sección «Deportistas en el panel ADMIN», FE-05 / U7).

### 6. Familias en el panel ADMIN (FE-05 / U2)

Menú **Familias** (`/admin/familias`), siempre con ADMIN + MFA completada y limitado a la escuela de la sesión:

| Ruta | Qué hace | Contrato backend |
| --- | --- | --- |
| `/admin/familias` | Listado paginado (20), búsqueda por nombre y filtro Todas/Activas/Inactivas | `GET /api/admin/familias/listado` |
| `/admin/familias/:id` | Detalle: estado y tutores (solo lectura) con el DNI **enmascarado**; desde acá se edita el nombre y se activa/desactiva (U3) | `GET /api/admin/familias/{id}`, `PUT /api/admin/familias/{id}`, `POST …/activar` y `…/desactivar` |
| `/admin/familias/nueva` | Alta con el nombre de referencia (máx. 150); al crear lleva al detalle | `POST /api/admin/familias` |
| `/admin/familias/:id/tutores/:tutorId` | Detalle **individual** de un tutor (con el enlace «Editar tutor»): es la única pantalla con el **DNI completo** (el detalle de familia lo muestra enmascarado) | `GET /api/admin/tutores/{id}` |
| `/admin/familias/:id/tutores/:tutorId/editar` | Edición de un tutor (FE-05 / U5-B): parte del detalle **completo**, no del resumen familiar; guardado explícito y protegido por **dos** revisiones | `GET /api/admin/tutores/{id}`, `GET /api/admin/familias/{id}`, `PUT /api/admin/tutores/{id}` |
| `/admin/familias/:id/tutores/nuevo` | Alta de un tutor dentro de la familia (nombre y apellido obligatorios; DNI, teléfono, correo y parentesco opcionales) | `POST /api/admin/familias/{id}/tutores` |

- **Privacidad:** el término de búsqueda y el nombre de la familia **no** van en la URL de navegación, ni en storage ni en consola (el término sí viaja
  como parámetro de la petición `GET`, que el backend no registra). Estado y página sí van en la URL (`?estado=…&pagina=…`).
- **Crear una familia no crea una cuenta de usuario:** el acceso sigue gestionándose con **Invitaciones**. La pantalla de alta y el detalle sin tutores lo aclaran.
- **Resultado incierto:** el alta nunca se reintenta sola. Ante un corte de red, un timeout o un 5xx se explica que no se sabe si se creó y se pide revisar el
  listado antes de reintentar; el botón se deshabilita mientras la petición está en curso (evita duplicados por doble clic).
- **Edición y estados (FE-05 / U3):** las escrituras envían `versionEsperada`, la revisión (`version`, FE-05/U1) que el administrador vio **al abrir** la edición o la
  confirmación; no se muestra. Un `409 EDICION_DESACTUALIZADA` (otro administrador modificó el registro) **no** se reintenta ni se resuelve solo: el borrador queda en memoria
  (nunca en URL, storage ni consola), «Ver datos vigentes» recarga y muestra el nombre vigente junto al borrador, y recién ante «Seguir editando mi borrador» la revisión
  base pasa a la vigente (hay que volver a guardar). No existe guardado forzado ni fusión automática. Un `409 CONFLICTO_CONCURRENCIA` (contención) se informa aparte.
- **Resultado incierto** (corte de red, timeout, 5xx): no se reintenta ni se afirma que falló; «Verificar datos vigentes» recarga y avisa si el cambio ya figura guardado.
- **Cambios sin guardar:** `beforeunload` (cerrar o recargar), clics en enlaces internos y «Cancelar» muestran un aviso. **Límite conocido:** el botón «Atrás» del navegador
  no se intercepta (`BrowserRouter` no admite `useBlocker`).
- **Desactivar una familia** pide confirmación y explica las consecuencias, verificadas en el backend: sus personas no pueden ingresar al portal y sus sesiones abiertas dejan de
  ser válidas; no se pueden crear ni editar tutores ni vínculos; **no** se elimina el historial ni se desactivan deportistas, vínculos o tutores. Reactivar la deja como estaba.
- **Tutores (FE-05 / U5-A):** el alta envía `versionFamiliaEsperada`, la revisión de la familia **capturada al abrir el formulario**; no se reemplaza sola aunque los datos se refresquen y solo cambia por una elección explícita tras «Ver datos vigentes». Crear un tutor **no** mueve la revisión de la familia, así que ante un resultado incierto (corte de red, timeout, 5xx) el reenvío queda **bloqueado hasta «Verificar si se creó»**: un reenvío a ciegas con la misma revisión podría crear el tutor dos veces (el DNI no es único). La verificación es solo un **indicio**: el backend no tiene idempotencia, así que una coincidencia de nombre y apellido entre los tutores nuevos nunca se presenta como prueba de que nuestra solicitud lo creó (una o varias coincidencias se informan como no confirmables o ambiguas y el reenvío sigue bloqueado), y no encontrarlo tampoco prueba que la solicitud no se vaya a procesar después. Un nuevo intento es siempre una acción explícita («Volver a intentar», con aviso de posible duplicado) y el aviso «Tutor agregado correctamente.» solo aparece cuando el backend confirma la creación. **No se puede garantizar la ausencia de duplicados ante una respuesta perdida.** Los tres `409` se distinguen: `EDICION_DESACTUALIZADA` (la familia cambió; borrador conservado), `FAMILIA_INACTIVA` (no se pueden agregar tutores) y `CONFLICTO_CONCURRENCIA` (contención, nada se aplicó). Crear un tutor **no** crea una cuenta de usuario ni una invitación.
- **Privacidad de los tutores:** el DNI completo, el teléfono y el correo solo viven en memoria: nunca en la URL (solo ids), el almacenamiento ni la consola; la consulta individual no se conserva al salir de la pantalla (`gcTime: 0`) y todo se descarta al cambiar la identidad.
- **Edición de tutores (FE-05 / U5-B):** el `PUT` es un **reemplazo completo** y exige dos revisiones, `versionEsperada` (del tutor) y `versionFamiliaEsperada` (de la familia), ambas **capturadas una sola vez al abrir la edición** a partir de datos recién leídos (el formulario no se monta hasta que tutor y familia llegaron después de abrir la pantalla). No cambian solas aunque los datos se refresquen; solo cambian cuando el administrador, **tras ver los datos vigentes**, elige «Seguir editando mi borrador». Un `409 EDICION_DESACTUALIZADA` (es el mismo código si cambió el tutor o la familia) conserva el borrador solo en memoria, bloquea el envío, y «Ver datos vigentes» muestra una **comparación** campo por campo; al continuar, los campos que el administrador **no** tocó pasan a los datos vigentes (no se deshace el cambio ajeno) y los que modificó conservan su borrador. Nunca hay guardado automático ni reintentos. `FAMILIA_INACTIVA` (no se puede continuar), `CONFLICTO_CONCURRENCIA` (nada se aplicó), validación, permisos y resultado incierto se tratan por separado. Ante un resultado incierto, «Verificar datos vigentes» informa si no se ve ningún cambio (un nuevo intento es una acción explícita; si la solicitud original se aplicara, la revisión rechazaría el reintento en vez de aplicarlo dos veces), si los datos vigentes ya coinciden con el borrador (sin atribuir el guardado a nuestro envío) o si cambiaron. Un campo opcional vaciado viaja como `null` y la pantalla avisa, sin mostrar valores, qué datos guardados se van a quitar. El aviso «Cambios guardados.» solo aparece con la respuesta confirmada del backend.
- **Sin pantallas todavía:** edición de tutores (U5-B) y cualquier desactivación individual (los tutores no se desactivan).

## Pruebas

```bash
npm run typecheck   # tsc --noEmit (incluye src/ y e2e/)
npm run lint        # eslint
npm test            # Vitest + Testing Library — con backend SIMULADO (ver abajo)
npm run build       # tsc + vite build
npm run test:e2e    # Playwright — contra backend + PostgreSQL REALES
```

**Pruebas con mocks (`npm test`)**: el cliente HTTP real corre sobre un adaptador de axios que simula al backend
(`src/test/fakeBackend.ts`) con las reglas del contrato (CSRF por cookie/encabezado, cookie de sesión, MFA pendiente,
401/403/409/503). Verifican cliente, guards, formularios y pantallas, **no** al servidor real.

**Pruebas E2E reales (`npm run test:e2e`)**: requieren el backend corriendo (paso 1) y

```bash
export E2E_ADMIN_EMAIL=...      # el BOOTSTRAP_ADMIN_EMAIL
export E2E_ADMIN_PASSWORD=...   # el BOOTSTRAP_ADMIN_PASSWORD
export E2E_DB_CONTAINER=...     # opcional: contenedor PostgreSQL DESCARTABLE (ver la salvaguarda de abajo), para los 2 escenarios que usan SQL
export E2E_DB_NAME=bp_e2e       # con E2E_DB_CONTAINER: la base debe llamarse exactamente bp_e2e
npx playwright install chromium # una vez
npm run test:e2e
```

El primer test enrola el TOTP del ADMIN por la UI y guarda el secreto en `e2e/.state.json` (ignorado por Git,
solo para bases descartables); luego crea una familia e invitación por la API real. **Si recreás la base, borrá
`e2e/.state.json`.** Dos escenarios (503 y sin conexión) se simulan con `page.route` y están rotulados como tales.
`portal.real.spec.ts` (FE-03) siembra **datos sintéticos por la API real de ADMIN** (dos familias, 13 deportistas con DNI sintético de 9 dígitos que
empieza con 9, uno inactivo, uno sin fecha de nacimiento y uno con vínculo revocado) y comprueba: dashboard y listado en 2 páginas reales, ausencia
de datos sensibles en la API y en pantalla, 360 px sin desborde y navegación inferior/lateral, aislamiento entre familias (incluido el mismo 404 del
backend para un id ajeno y uno inexistente), cambio de familia en el mismo navegador, sesión vencida y una caída **simulada** (`page.route`).
Depende del administrador enrolado por `auth.real.spec.ts` (se ejecuta después, por orden alfabético en modo serial).
`invitations.real.spec.ts` recorre el ciclo completo contra el backend real: crear invitación → enlace único → registro →
no hay sesión automática → login; enlace usado, revocado y vencido (este último requiere `E2E_DB_CONTAINER`); permisos
de FAMILIA/anónimo sobre la API ADMIN. El backend limita por IP las validaciones **fallidas** (5 cada 15 min, en memoria):
esa spec provoca 3, así que **reiniciá el backend (o esperá 15 minutos) entre corridas**.
`familias-listado.real.spec.ts` y `familias-detalle-alta.real.spec.ts` (FE-05/U2) siembran familias, tutores y un deportista **por la API real de ADMIN** y comprueban:
listado en dos páginas reales, búsqueda y filtro sin el término en URL/consola/storage, detalle con DNI enmascarado (la propia API ya no lo trae completo), «no encontrado»
uniforme (id inexistente y mal formado), alta con doble clic que crea **una** familia, validación sin petición, 360 px, y 401/403 sobre la API y las pantallas. La caída de red
del alta es una prueba **simulada** (`page.route`). El aislamiento entre dos escuelas lo cubren las pruebas de base de datos del backend.
`familias-edicion-estados.real.spec.ts` (FE-05/U3) prueba contra el backend real la edición con la revisión que avanza, el rechazo de una edición obsoleta (el cambio del «otro administrador» no se
pisa; el borrador sobrevive; no hay reintento), el descarte, la caída **simulada** (`page.route`), el aviso de cambios sin guardar (incluido el diálogo `beforeunload`), la desactivación con
confirmación (la familia **deja de poder ingresar** de verdad y vuelve a poder al activarla), el cambio de estado con revisión obsoleta, el doble clic, los permisos (anónimo, FAMILIA, sin CSRF y
ADMIN con segundo factor pendiente) y 360 px. El «otro administrador» es el mismo administrador escribiendo por la API con una revisión más nueva; para el backend es equivalente.
`familias-tutores.real.spec.ts` (FE-05/U5-A) prueba contra el backend real la consulta (DNI enmascarado en el detalle de familia y completo solo en el individual, mismo «no encontrado» para un tutor inexistente, de **otra familia** o mal formado), el alta con la revisión capturada (la revisión de la familia no se mueve y no se crean cuentas ni invitaciones, comprobado en la base), validaciones, doble clic, una revisión obsoleta real (borrador conservado y continuación solo por elección explícita), familia inactiva (al cargar y desactivada mientras tanto), dos cortes **simulados** (`page.route`): respuesta perdida **después** de que el backend creó el tutor (la verificación informa la coincidencia como no confirmable, no afirma la creación, mantiene el reenvío bloqueado y no se duplica) y solicitud que nunca llegó (la verificación informa que no encontró el tutor, advierte la posible duplicación y recién ante una acción explícita se reintenta, una vez), permisos (anónimo, FAMILIA, sin CSRF) y 360 px.

`deportistas-edicion-estados.real.spec.ts` (FE-05/U8, 15 escenarios) prueba contra el backend real la edición completa (opcionales `null`, estado intacto), los tres duplicados con la revisión sin mover, el conflicto de revisión con fusión por campo y elección explícita, un segundo cambio concurrente, una revisión movida por un cambio de estado, las dos caídas **simuladas** de la edición y del cambio de estado (respuesta perdida después de aplicar y petición que no llegó), la activación y desactivación con confirmación (sin tocar el vínculo familiar ni los datos), el permiso (anónimo, FAMILIA y sin CSRF), 360 px y la ausencia de datos personales en URL, almacenamiento y consola.
`deportistas-consulta-alta.real.spec.ts` (FE-05/U7, 16 escenarios) prueba contra el backend real el listado (DNI enmascarado, filtros, búsqueda por nombre y por DNI sin escribirla en la URL, paginación y retorno desde el detalle), el detalle (DNI y CUIL completos solo ahí, «No informado», identificadores inexistentes o mal formados con un mismo «no encontrado»), el alta (normalización del backend, sin vínculos, cuentas ni invitaciones), los tres duplicados, el doble clic, las dos caídas **simuladas** (respuesta perdida después de crear y petición que no llegó), el aviso de cambios sin guardar, los permisos (anónimo, FAMILIA y sin CSRF), 360 px y que ningún dato personal llegue a URL, almacenamiento ni consola.
`tutores-edicion.real.spec.ts` (FE-05/U5-B) prueba contra el backend real la edición con las **dos** revisiones capturadas (la del tutor se mueve y la de la familia no, sin cuentas ni invitaciones, nada en URL, consola ni almacenamiento), el reemplazo completo (los opcionales vaciados se guardan como `null`), validación y doble clic, un tutor modificado por otro administrador (borrador conservado, comparación, continuación explícita que no deshace el cambio ajeno, y nuevo conflicto si vuelve a cambiar), una familia modificada, una familia desactivada mientras se edita o inactiva al abrir, dos cortes **simulados** (`page.route`: respuesta perdida después de que el backend aplicó la edición, y solicitud que nunca llegó con reintento explícito único), permisos (anónimo, FAMILIA, sin CSRF, sin revisiones) y 360 px (formulario y comparación).
`portal-ficha.real.spec.ts` (FE-04, 22 pruebas) siembra, también por la API real de ADMIN, una temporada anterior (con datos distintos, que nunca deben verse) y la
vigente, y ocho deportistas con un caso cada uno: completo (`007`), suspendido, inactivo, sin edad, sin fila en la temporada vigente, con campos nulos, con vínculo
revocado y de otra familia. La edad de las pruebas es exacta (fechas de nacimiento relativas a hoy en la zona de la escuela). La ficha no genera capturas, trazas ni videos.
Las capturas de las demás pruebas quedan en `e2e/artifacts/` (ignorado por Git).

**Qué demuestra cada nivel en FE-04** (para no atribuirle a una prueba lo que solo se infirió):

| Garantía | E2E reales (navegador + Spring + PostgreSQL) | Pruebas unitarias o de integración | Sin verificar |
| --- | --- | --- | --- |
| Contrato de la ficha y del listado | Claves exactas de las respuestas HTTP; el esquema Zod acepta cada respuesta real y no descarta nada | `FamiliaPortalFichaHttpDbTest`, `FamiliaPortalListadoEdadHttpDbTest`, esquemas en Vitest | — |
| Privacidad | Ninguna de las respuestas `/api/familia/*` **que estos E2E observaron** (ficha, listado y resumen de familia, todas leídas y esperadas) trae fecha de nacimiento, DNI, CUIL ni domicilio; tampoco la pantalla ni su HTML | Las mismas garantías en el backend | **El detalle de 15 campos (`GET /api/familia/deportistas/{id}`) sigue existiendo, con DNI, CUIL, fecha y domicilio, y estos E2E no lo prueban ni lo minimizan** (riesgo residual R8) |
| 404 uniforme | Mismo cuerpo, byte a byte, para ajeno, inexistente y vínculo revocado; la pantalla muestra el mismo aviso; un id inválido no llega al servidor; 401 sin sesión y 403 para ADMIN | Otra escuela y las demás variantes de no acceso, byte a byte, en `FamiliaPortalFichaHttpDbTest` | — |
| Cambio de familia | B pide la ficha de A y recibe 404; se recorre **todo** el historial del navegador sin datos de A en ninguna parada (cerrar e iniciar sesión reemplaza las entradas, así que la dirección de la ficha de A no queda en el historial); la dirección directa da el mismo resultado | `athlete-sheet-ui.test.tsx` y `ficha-identity.test.tsx` (caché por usuario, familia y deportista) | Un cambio de cookie hecho desde **otra pestaña**: no se probó |
| Respuesta tardía | **En la propia ficha:** la respuesta demorada llega (el navegador informa `requestfinished`) y la pantalla pasa de «Cargando la ficha…» a sus datos. **Al cambiar de ruta o de usuario:** se *observa* qué hizo el navegador con la petición pendiente (en las corridas de FE-04: cambio de ruta → la terminó; cambio de usuario → la abortó) y se exige que nada de la ficha anterior quede en pantalla. El E2E **no fuerza** que la respuesta llegue ni demuestra que la aplicación la haya leído | Respuestas retenidas y entregadas tarde, con el orden controlado: cambio de identidad, de usuario y de ruta en `athlete-sheet-ui.test.tsx` y `ficha-identity.test.tsx` | Que la aplicación lea y descarte bien una respuesta **real** que llega tras un cambio de identidad: solo se demuestra con el simulador; en E2E depende de que el navegador cancele o no la petición y no se fuerza |
| Sesión vencida | Vuelve al login sin datos y, al ingresar, a la misma ficha; también ante la siguiente petición | Estados tipados en Vitest | — |
| ADMIN | Recibe 403 de la API y la pantalla «No tenés permiso…» (se espera esa pantalla antes de afirmar que no hay datos) | Guardas por rol en Vitest | — |
| Móvil y teclado | 360 y 390 px sin desborde, enlace de regreso ≥ 44 px de alto, foco en el título y orden de lectura | Roles, nombres y foco en `athlete-sheet-ui.test.tsx` | Lector de pantalla real, herramienta automática de accesibilidad y dispositivos reales |
| Edad | Exacta (9) en la respuesta y en pantalla | `FamiliaPortalFichaDbTest` con reloj fijo (instante que en UTC y en Buenos Aires cae en días distintos; 29/2, 0 y 120 años, futura) | Las pruebas HTTP del backend usan el reloj real y tienen una ventana de milisegundos en el cambio de día |

**Nota de corrección.** El mensaje del commit `2faecae` afirma que «la aplicación no cancela la petición: la respuesta llega, se procesa» al cambiar de ruta o de usuario.
Esa frase quedó **refutada** por la revisión `review-89948f41c8a6f81a` y por observación posterior (el navegador la aborta en el cambio de usuario); el commit
no se reescribió y la corrección está en `e04b891` y en este texto.

Los escenarios con `page.route` (respuesta lenta y caída del servidor) están rotulados como **SIMULADOS**: la demora o la caída se inyectan, pero la respuesta es la del servidor real.

**Salvaguarda del SQL de los E2E (`e2e/support/db.ts`):** solo dos escenarios usan SQL, porque la API de ADMIN no puede producir esos estados (invitación vencida y
escuela sin temporada activa); cada sentencia se limita por id o por nombre sembrado y quien la llama comprueba que cambió exactamente 1 fila (`UPDATE 1`). Antes de ejecutar nada
**se niega** salvo que: el contenedor se llame `bp-e2e-postgres-*`, la base sea exactamente `bp_e2e` y el contenedor lleve la etiqueta
`com.banfieldpatin.e2e.disposable=true`, que solo le pone `.github/scripts/frontend-e2e.sh` al crear el suyo. Un contenedor de desarrollo, de otro proyecto o
configurado a mano se rechaza sin tocar la base (si querés usar uno propio, tenés que crearlo a propósito con esa etiqueta). El SQL viaja por stdin y los valores
como variables de psql citadas por psql; las credenciales no salen del contenedor. Tres estados, que no se confunden: **sin `E2E_DB_CONTAINER`** esas
pruebas se **omiten** de forma explícita; **con un contenedor demostrablemente descartable** se ejecutan; y **con una configuración inválida o insegura** (nombre,
base o etiqueta incorrectos, o contenedor que no se puede inspeccionar) la prueba **falla antes de crear nada**, nunca se omite en silencio. `E2E_DB_NAME` solo confirma
la intención (psql siempre usa `bp_e2e`) y se vuelve a comprobar en cada llamada. **Límite:** nombre y etiqueta se pueden falsificar a propósito; protegen de errores
de configuración, no de quien quiera saltarse la salvaguarda.

**Artefactos sin secretos (H-02):** Playwright no guarda trazas, videos ni capturas automáticas (registran cuerpos de
peticiones y el texto de la página). Las capturas de evidencia enmascaran el QR, el secreto TOTP y los enlaces de
invitación. Al terminar, el teardown redacta y verifica los artefactos de texto (`e2e/support/artifact-check.ts`) y
`npm run test:e2e:check-artifacts` repite la verificación. `e2e/.state.json` sí contiene el secreto TOTP y contraseñas de
prueba (necesarios entre corridas): es local, ignorado por Git, con permisos 600, y debe borrarse con la base descartable.

## Seguridad (cómo está resuelto)

- Sesión entre pestañas: el logout en una pestaña cierra la sesión en las demás, y un login en otra pestaña hace que
  esta limpie sus datos y revalide la identidad (`BroadcastChannel`, solo el tipo de evento, sin datos).
- Un 401 sin código en una petición autenticada (por ejemplo, de un proxy) revalida `/api/auth/me` en lugar de cerrar la
  sesión; un 503 o la falta de conexión nunca la cierran. Al iniciar sesión (o completar el MFA) se descarta todo dato
  privado cacheado de la identidad anterior.

- Sesión en cookie `HttpOnly` emitida por el backend; el JS nunca ve el JWT. Nada sensible en `localStorage`/`sessionStorage`.
- CSRF: antes de **cada** escritura se pide `GET /api/auth/csrf`, se lee la cookie `XSRF-TOKEN` y se envía en
  `X-XSRF-TOKEN`. No se cachea (login, logout y MFA descartan el token anterior en el servidor) y las escrituras
  nunca se reintentan automáticamente. La cookie solo es legible si SPA y API comparten origen: en desarrollo lo
  garantiza el proxy de Vite (`changeOrigin: false`); en producción debe haber un reverse proxy o el mismo dominio.
- ADMIN con solo contraseña queda con sesión de MFA pendiente: `/admin` y `/familia` lo envían a `/admin/mfa`.
- 401 `NO_AUTENTICADO` en cualquier request → se descarta la sesión y la caché; 503 o falta de conexión **no** cierran
  la sesión (estado desconocido): se conserva la identidad conocida y se avisa.
- Las variables de las mutaciones (contraseña, código TOTP, secreto de enrolamiento) no se retienen en la caché (`gcTime: 0`).
- La autorización real siempre la decide el backend; los guards del frontend solo ordenan la navegación.

## Integración continua

`.github/workflows/frontend.yml` corre en cada `push` y `pull_request`, con Node fijado en `frontend/.node-version`:

- **Verificación:** `npm ci`, `typecheck`, `lint`, `npm test` y `npm run build`.
- **Auditoría:** `npm audit --audit-level=moderate`, en un job aparte: un aviso nuevo del registro de npm lo hace
  fallar sin que el código haya cambiado, y así se distingue de una regresión.
- **E2E reales:** `.github/scripts/frontend-e2e.sh` levanta una PostgreSQL descartable con Docker, el backend y Vite, y
  corre Playwright. Las credenciales se generan en cada ejecución y el contenedor se elimina al terminar. El mismo
  script sirve en local (Docker, Java 21, `npm ci` y `npx playwright install chromium`): `bash .github/scripts/frontend-e2e.sh`
  **sin ninguna variable corre la suite completa**, como siempre.

### 7. Deportistas en el panel ADMIN (FE-05 / U7 y U8)

Menú **Deportistas** (`/admin/deportistas`), siempre con ADMIN + MFA completada y limitado a la escuela de la sesión. U7 es de **consulta y alta** y U8 agrega la **edición protegida** y el **cambio de estado** (activar y desactivar). Nada de esto asigna familias, crea cuentas ni invitaciones ni
administra temporadas o datos deportivos.

| Ruta | Qué hace | Contrato backend |
| --- | --- | --- |
| `/admin/deportistas` | Listado paginado (20), búsqueda por nombre, apellido o DNI (con debounce) y filtro Activos (por defecto, como el backend) / Inactivos / Todos. Muestra apellido y nombre, **DNI enmascarado** y estado | `GET /api/admin/deportistas` |
| `/admin/deportistas/:id` | Detalle **individual** (los datos son texto, no se editan en el lugar; desde acá se edita y se cambia el estado, U8): es la única pantalla (con la de edición) con el **DNI y el CUIL completos**; muestra fecha de nacimiento, contacto y domicilio («No informado» si falta) y el estado. Valida la revisión `version` (cadena decimal) sin mostrarla | `GET /api/admin/deportistas/{id}` |
| `/admin/deportistas/nuevo` | Alta (DNI, nombre y apellido obligatorios; CUIL, nacimiento, nacionalidad, domicilio y contacto opcionales). Al confirmarse el `201` lleva al detalle | `POST /api/admin/deportistas` |
| `/admin/deportistas/:id/editar` | Edición (U8): reemplazo completo, parte de una lectura hecha **después** de montar la pantalla y captura la revisión `version`; guardado explícito, sin autosave y sin tocar el estado | `GET /api/admin/deportistas/{id}`, `PUT /api/admin/deportistas/{id}` |

- **Privacidad:** el término de búsqueda, los nombres y el DNI **no** van en la URL de navegación, ni en storage ni en consola (el término sí viaja como parámetro de la petición `GET`). Estado y página
  sí van en la URL (`?estado=…&pagina=…`) y «Volver al listado» los conserva; la búsqueda no se conserva. El detalle no deja el DNI ni el CUIL en memoria al salir (`gcTime: 0`) y se reinicia
  si cambia la identidad o el deportista. El listado también se reinicia al cambiar la identidad: descarta el término escrito, las filas y cualquier respuesta pendiente de la identidad anterior.
- **Duplicados:** `DNI_DUPLICADO` (titular activo), `DNI_RESERVADO_POR_INACTIVO` (titular inactivo: el índice único de DNI no es parcial, no se puede crear otro) y `CUIL_DUPLICADO` se explican
  cada uno junto a su campo, con el borrador intacto. El backend no devuelve el id del titular existente: el aviso remite al listado.
- **Edición protegida (U8):** el `PUT` es un **reemplazo completo**: viajan los 13 campos y cada opcional vacío va como `null` (la pantalla nombra, sin valores, los datos que se quitarían). La
  revisión `versionEsperada` (cadena decimal) es la de la lectura al abrir y **no cambia sola**. Ante `409 EDICION_DESACTUALIZADA` el borrador queda solo en memoria, el guardado se bloquea y
  «Ver datos vigentes» hace una **fusión de tres vías por campo** (original, borrador y vigente, comparados como los compara el backend): un campo que el administrador no tocó toma el valor
  vigente, uno que solo cambió él conserva su borrador y uno que **ambos cambiaron distinto exige una elección explícita** («Mantener mi valor» / «Usar el valor vigente», sin opción por defecto);
  recién entonces se puede seguir con la revisión vigente y un segundo cambio concurrente vuelve a dar conflicto. Nada se sobrescribe, reintenta ni fuerza.
- **Resultado incierto de la edición:** no se reintenta ni se declara guardado; «Verificar datos vigentes» compara la revisión: sin cambio de revisión informa que no ve cambios guardados y ofrece
  «Volver a intentar» (la misma revisión no puede aplicarse dos veces); con la revisión movida y datos iguales al borrador dice que no puede saber si fue su envío u otro cambio.
- **Estado (U8):** el detalle muestra solo la acción que corresponde (`Desactivar deportista` o `Reactivar deportista`, accesible desde el propio detalle del inactivo), con confirmación explícita y las
  consecuencias verificadas (no se elimina, no cambian vínculos ni datos deportivos, deja de contarse entre los activos de su familia y su DNI sigue reservado). La revisión es la de la apertura de
  la confirmación. Ante un resultado incierto no hay reenvío hasta «Verificar estado vigente», y un estado vigente que coincide con el pedido **no** se presenta como confirmación de la solicitud.
  El detalle y el listado solo se actualizan con la respuesta confirmada.
- **Resultado incierto:** el alta nunca se reintenta sola ni se declara creada por coincidencia. Ante un corte de red, un timeout o un 5xx el formulario queda cerrado con el borrador a la vista;
  «Verificar en el listado» consulta por DNI (solo lectura, todos los estados, la primera página de hasta **100** resultados, el máximo del backend) y compara el **DNI enmascarado completo**, nunca nombre y
  apellido; una coincidencia se presenta como **indicio**, no como confirmación. Sin coincidencias solo dice que **no se observa** el registro en ese momento (no que la solicitud no se aplicó). Si la
  búsqueda devolvió más resultados de los revisados (posible con DNI de 7 dígitos, cuya búsqueda por prefijo abarca hasta 111) la verificación se declara **incompleta** y no concluye nada; no recorre páginas.
  «Volver a intentar» es una decisión explícita: como el DNI es único, un reenvío no puede duplicar al deportista (si el primero sí se aplicó, el segundo recibe `DNI_DUPLICADO`, y el aviso dice que
  podría ser el primero).

### E2E reales repartidos en shards

En CI la suite se reparte en **shards** (`frontend/e2e/shards.json`): cada shard es un runner independiente, con su **propia** base
PostgreSQL descartable, backend, frontend y credenciales efímeras, y dentro de cada uno se conserva `workers: 1` (no hay concurrencia
sobre una misma base). Se ejecutan los mismos escenarios, sin omitir ni duplicar ninguno: solo cambia en qué runner corre cada archivo.

- **Control protegido.** El control exigido por la protección de la rama, `E2E reales (backend + PostgreSQL descartable)`, conserva su
  nombre y ahora es un job agregador (`e2e`) que depende de los shards (`e2e-shard`, con nombres distintos) y **solo termina en success
  cuando todos terminaron en success**. Corre con `always()` y ejecuta `.github/scripts/e2e-gate.sh`: sin eso, si un shard falla el
  agregador quedaría «omitido» y GitHub trata un control omitido como aprobado. Un shard fallido, cancelado u omitido deja el control en rojo.
  Cada shard verifica por su cuenta los artefactos sensibles (`npm run test:e2e:check-artifacts`).
- **Por qué un reparto explícito y no `playwright --shard`.** El primer escenario de `auth.real.spec.ts` enrola al administrador por la
  interfaz (TOTP) y todos los demás specs dependen de ese enrolamiento; además el backend rechaza reutilizar un paso TOTP, y los tiempos
  los domina el inicio de sesión de administrador (uno por ventana de 30 s). El reparto va por archivo y está balanceado por el tiempo
  medido, no por la cantidad de escenarios. Los specs son independientes entre sí (cada uno siembra datos con un sufijo aleatorio propio).
- **Enrolamiento por shard.** El shard que contiene `auth.real.spec.ts` lo enrola con ese spec, que va primero. Los demás shards enrolan
  al administrador antes de empezar con `e2e/support/enrol-admin.ts`, por la API real (login, enrolar y confirmar con un código vigente);
  no es un escenario y no cuenta como prueba. El secreto queda solo en `e2e/.state.json`, que el script elimina al terminar.
- **Cobertura verificada.** `npm test` valida el reparto contra los archivos del repositorio (falta, sobra o se repite un spec; `auth` no
  va primero) y el script ejecuta antes de levantar nada `node e2e/support/shards.ts check`, que compara con la lista que da Playwright:
  la unión de los shards son exactamente todos los escenarios, sin omitir ni duplicar.
- **Local.** Sin `E2E_SHARD` corre todo. `E2E_SHARD=1 bash .github/scripts/frontend-e2e.sh` (o `2`) corre un shard; no pueden correr dos a la
  vez en la misma máquina (usan los mismos puertos 8080 y 5173). Un spec nuevo hay que agregarlo a `e2e/shards.json`; si no, `npm test`
  y el arranque de cada shard fallan. Para rebalancear, usar los tiempos por archivo que imprime el reporter `list` de Playwright.
- **Sin medición de mejora todavía.** Este reparto no afirma una reducción del tiempo total: hay que medirlo en CI. El `timeout-minutes` de
  cada shard se mantuvo en 40 (no se aumentó); conviene bajarlo cuando haya mediciones reales.

## Limitaciones conocidas

- La landing no vive en esta app: `/` redirige a `/login`.
- El logo es una marca provisional (SVG); falta integrar el recurso oficial.
- **Detalle de 15 campos sin uso:** `GET /api/familia/deportistas/{id}` sigue existiendo en el backend (incluye DNI, CUIL, fecha de nacimiento y domicilio) y ninguna pantalla lo
  usa. Queda como riesgo residual R8 (ver `docs/SEGURIDAD.md`); no se modificó.
- **Compatibilidad de despliegue (FE-04, no validada en producción).** La ficha y la edad calculada por el backend cambian el contrato del listado. Para pasar de FE-03 a
  FE-04 **sin degradación visual** el orden es: (1) backend aditivo (agrega `edad` y la ficha, conserva `fechaNacimiento`), (2) frontend nuevo (lee `edad`) y (3) backend final
  (retira `fechaNacimiento` del listado). Si se despliega el backend final antes que el frontend nuevo, el listado de FE-03 muestra «Edad no informada»; si se despliega el
  frontend nuevo contra un backend sin `edad`, también. No existe una estrategia de despliegue verificada que evite ese orden. **Ningún despliegue productivo fue validado.**
- E2E solo en Chromium (escritorio); móvil verificado por emulación de viewport, no en dispositivos reales.
- Portal de familias: la ficha es de solo lectura y no incluye licencia, empadronamiento, aptos, historial de temporadas ni edición; los datos se consideran vigentes 60 s (`staleTime`): pasado ese tiempo se vuelven a pedir al volver a la pestaña o a la ventana (`refetchOnWindowFocus`), al volver a entrar a la pantalla o con «Reintentar». No hay actualización periódica en segundo plano.
- **Cookies entre pestañas: riesgo residual, NO probado.** Los E2E de la ficha cubren el cambio de familia y de usuario **en la misma pestaña** (cierre de sesión e
  ingreso, historial y dirección; las respuestas tardías de un cambio de identidad **solo** las demuestran las pruebas unitarias); un cambio de la cookie hecho desde **otra pestaña** no se probó de forma específica.
- Si otra pestaña inicia sesión con otra persona, esta pestaña conserva su identidad hasta el siguiente `/api/auth/me` (foco o próxima petición); ninguna petición mezcla datos y el backend es la autoridad.
- No se puede distinguir en pantalla *por qué* una invitación no está disponible (vencida, revocada, usada, inválida): el backend lo oculta a propósito.
- El enlace de invitación solo existe en la respuesta de creación; no hay forma de recuperarlo luego.
- El bloqueo por intentos fallidos del backend responde igual que unas credenciales incorrectas: la UI no puede distinguirlo.
