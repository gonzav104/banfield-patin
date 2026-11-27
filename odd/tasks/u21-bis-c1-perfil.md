# U21-BIS · C1 — Perfil propio y vínculo cuenta ↔ tutor
Rama `feat/u21-bis-c1-perfil` desde `main` 0ff0758 (worktree `~/worktrees/banfield-u21bis-c1`). Solo commits locales: sin push/PR hasta autorización; sin C2–C5 ni U22; sin Supabase; sin migraciones históricas; sin multiescuela.
Runner: Maven (`./mvnw`, `-Pdb-tests`), Vitest + FakeBackend, Playwright E2E real. Modelo: la sesión corre con claude-sonnet-5-5; las decisiones de identidad/autorización/concurrencia se piden con claude-opus-5-5 High, que no puedo activar desde la sesión (se informa).

## Specs
- S1 (alcance, del diseño `docs/U21-BIS-AUTOSERVICIO-FAMILIAR.md` §4.7, §9 y la tabla de contratos): «Perfil propio y vínculo cuenta↔tutor declarativo + onboarding», «`GET/PUT /api/familia/mi-perfil`, `POST /api/familia/mi-perfil/reclamar-tutor`», «`POST /api/admin/invitaciones` (campo opcional `tutorId`)»; migración V20 prevista del diseño = **V21** (la V20 se usó en la revocación por familia y escuela).
- S2 (precisiones del usuario para cortes posteriores): «La edición directa de tutores no debe permitir alterar silenciosamente una identidad previamente verificada. Distinguir datos de contacto de datos identificatorios. El reclamo Soy este tutor es una declaración, no una acreditación automática de identidad ni representación legal. El reclamo de un tutor no concede permisos adicionales.»
- S3 (prioridades): «Experiencia sencilla para las familias. Reutilización de infraestructura existente. Reducción del trabajo administrativo manual. Privacidad y aislamiento. Validaciones claras. Concurrencia entre usuarios de una misma familia. Separación entre datos declarados y permisos efectivos.» «No establecer arbitrariamente los topes de solicitudes y tutores pendientes de aprobación.»
- S4 (proceso): Gentle AI con consentimiento y **hallazgos guardados antes de reconocer**; recuentos de reportes limpios; esperas acotadas. «No publicar C1 sin autorización posterior. No comenzar C2–C5 ni U22.» Banderas apagadas por defecto.

## Decisiones de diseño de C1
- **Datos:** `tutor` gana `origen` (`ADMIN`/`FAMILIA`, por defecto `ADMIN`), `verificacion` (`DECLARADO`/`VERIFICADO`/`OBSERVADO`, por defecto `VERIFICADO`: lo existente lo cargó ADMIN), `vinculo_cuenta` (`DECLARADO`/`VERIFICADO`, nulo sin cuenta) y `declarado_por`. `usuario_id` (ya existe y es único) se mapea. La coherencia la impone la base: `usuario_id` nulo ⇔ `vinculo_cuenta` nulo; la cuenta es de la **misma familia** que el tutor (FK compuesta); `invitacion.tutor_id` apunta a un tutor de **su** familia (FK compuesta).
- **Identidad vs contacto:** identidad = nombre, apellido, DNI; contacto = teléfono, correo, parentesco. Mientras el tutor no esté `VERIFICADO` la familia edita todo; una vez `VERIFICADO`, el cambio de identidad se rechaza (`409 IDENTIDAD_VERIFICADA`, sin eco) y se reserva a la solicitud de corrección (C5). En C1 solo ADMIN (hoy) verifica.
- **Reclamo («Soy esta persona»):** `UPDATE … SET usuario_id = :yo, vinculo_cuenta = 'DECLARADO', declarado_por = :yo WHERE id = :t AND familia_id = :mi_familia AND usuario_id IS NULL AND activo AND version = :v`: uno gana y el otro recibe un conflicto neutro. No cambia permisos ni verifica identidad. Una cuenta tiene a lo sumo un tutor (índice único existente) ⇒ **no hace falta ningún tope** en C1.
- **Invitación con `tutorId`:** solo para una familia existente; el tutor debe ser de esa familia, estar activo y sin cuenta; al registrarse, la cuenta nace vinculada `VERIFICADO`. Si el tutor fue reclamado en el medio, el registro **no falla** (la persona queda sin vínculo, auditado).
- **Bandera:** `banfield.autoservicio.perfil-habilitado` (`AUTOSERVICIO_PERFIL_HABILITADO`, apagada). Apagada: la lectura de `mi-perfil` responde su estado y las escrituras `503 AUTOSERVICIO_PERFIL_DESHABILITADO`.
- **Interfaz:** tarjeta «Mis datos» en el panel de la familia → pantalla simple: si hay personas de la familia sin cuenta, «¿Sos alguna de estas personas?» con «Soy yo»; si no, un formulario corto. Sin jerga (CAS, vigencias, revisiones).

## Tasks
- [ ] T1 · S1 S2 · inline · V21 + entidad `Tutor` + servicio de perfil + endpoints + auditoría + bandera + OpenAPI/inventarios + pruebas (unitarias, WebMvc, PostgreSQL con dos familias, concurrencia, runtime real)
- [ ] T2 · S1 · inline · Invitación con `tutorId` (backend + selector en el formulario de ADMIN)
- [ ] T3 · S3 · inline · Frontend: «Mis datos», reclamo, Vitest/FakeBackend, E2E real
- [ ] T4 · S4 · inline · docs, suites completas, Gentle AI, commits, Engram

Route: todo inline (cadena dependiente BE → FE; sin unidades paralelas independientes).

## Log
### L1
Pedido del usuario (2026-11-27): continuación automática tras integrar la revocación por familia y escuela (merge 0ff0758, CI posterior 8/8): ver S1–S4. Alcance de C1 recuperado del diseño; sin otro relevamiento general.
