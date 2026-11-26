package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.familias.FamiliaAdminService;
import com.banfieldpatin.backend.familias.dto.FamiliaActualizacion;
import com.banfieldpatin.backend.familias.tutores.TutorAdminService;
import com.banfieldpatin.backend.familias.tutores.dto.TutorActualizacion;
import com.banfieldpatin.backend.familias.tutores.dto.TutorSolicitud;
import com.banfieldpatin.backend.seguridad.ServicioTokens;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;
import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.Cookie;

/**
 * FE-05/U4: revision optimista de Tutor sobre PostgreSQL 17 descartable, con la cadena HTTP real (MockMvc), JPA y la
 * concurrencia de dos transacciones reales; ningun servicio simulado. Las esperas se observan en {@code pg_stat_activity}
 * (nada depende de temporizadores) para demostrar el bloqueo real y no solo el resultado.
 */
@Tag("db")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "spring.datasource.hikari.maximum-pool-size=6", "spring.datasource.hikari.minimum-idle=1" })
@AutoConfigureMockMvc
@ActiveProfiles({ "test", "e2e" })
class TutorConcurrenciaDbTest extends BaseDbTest {
    private static final DatosSolicitud DATOS = new DatosSolicitud("203.0.113.2", "JUnit-U4");
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired TutorAdminService tutoresSvc;
    @Autowired FamiliaAdminService familiasSvc;
    @Autowired ServicioTokens tokens;
    @Autowired PlatformTransactionManager tm;
    @Autowired EntityManager em;
    private DatosDb datos;
    private UUID escuela, otraEscuela, adminId, otroAdminId, familia, tutor;
    private UsuarioAutenticado admin;
    private ExecutorService hilos;

    @BeforeEach void preparar() {
        datos = new DatosDb(jdbc);
        escuela = datos.escuela("u4-" + UUID.randomUUID());
        otraEscuela = datos.escuela("u4-otra-" + UUID.randomUUID());
        adminId = datos.admin(escuela, "uno@example.invalid", true);
        otroAdminId = datos.admin(escuela, "dos@example.invalid", true);
        admin = new UsuarioAutenticado(adminId, escuela, null, Rol.ADMIN);
        familia = datos.familia(escuela, "Familia sintetica", true);
        tutor = datos.tutor(escuela, familia, "Ana", "Sintetica");
        hilos = Executors.newFixedThreadPool(3);
    }
    @AfterEach void limpiar() throws Exception {
        hilos.shutdownNow();
        assertThat(hilos.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        datos.limpiarEscuela(escuela);
        datos.limpiarEscuela(otraEscuela);
    }

    // ---------- utilidades ----------
    private Cookie sesion(UUID actor) {
        return new Cookie("BP_SESION", tokens.emitir(actor, Rol.ADMIN, escuela, null));
    }
    private MockHttpServletRequestBuilder comando(MockHttpServletRequestBuilder req, String json) throws Exception {
        Cookie csrf = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        return req.cookie(sesion(otroAdminId), csrf).header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON).content(json);
    }
    private String rutaAlta() { return "/api/admin/familias/" + familia + "/tutores"; }
    private String rutaTutor() { return "/api/admin/tutores/" + tutor; }
    private static String alta(String nombre, String versionFamilia) {
        return "{\"nombre\":\"" + nombre + "\",\"apellido\":\"Nueva\",\"versionFamiliaEsperada\":" + versionFamilia + "}";
    }
    /** Reemplazo del tutor sembrado (Ana Sintetica, sin opcionales) salvo el telefono. */
    private static String edicion(String telefono, String versionTutor, String versionFamilia) {
        return "{\"nombre\":\"Ana\",\"apellido\":\"Sintetica\",\"telefono\":" + (telefono == null ? "null" : "\"" + telefono + "\"")
                + ",\"versionEsperada\":" + versionTutor + ",\"versionFamiliaEsperada\":" + versionFamilia + "}";
    }
    private static TutorActualizacion cambio(String telefono, long versionTutor, long versionFamilia) {
        return new TutorActualizacion("Ana", "Sintetica", null, telefono, null, null, versionTutor, versionFamilia);
    }
    private long versionTutor() {
        return jdbc.sql("SELECT version FROM gestion_patin.tutor WHERE id=:i").param("i", tutor).query(Long.class).single();
    }
    private long versionFamilia() {
        return jdbc.sql("SELECT version FROM gestion_patin.familia WHERE id=:i").param("i", familia).query(Long.class).single();
    }
    private long auditorias() {
        return jdbc.sql("SELECT count(*) FROM gestion_patin.auditoria WHERE escuela_id=:e")
                .param("e", escuela).query(Long.class).single();
    }
    private long tutores() {
        return jdbc.sql("SELECT count(*) FROM gestion_patin.tutor WHERE escuela_id=:e")
                .param("e", escuela).query(Long.class).single();
    }
    private String snapshotTutor() {
        return jdbc.sql("SELECT row_to_json(t)::text FROM gestion_patin.tutor t WHERE id=:i").param("i", tutor).query(String.class).single();
    }
    private String snapshotFamilia() {
        return jdbc.sql("SELECT row_to_json(f)::text FROM gestion_patin.familia f WHERE id=:i").param("i", familia).query(String.class).single();
    }
    private void esperar(CountDownLatch latch) {
        try { assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    /** Espera a OBSERVAR en PostgreSQL una sentencia que cumple el patron esperando un bloqueo de fila. */
    private void esperarBloqueo(Future<?> futuro, String patronLike) throws Exception {
        long limite = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < limite) {
            long esperando = jdbc.sql("""
                    SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()
                    AND wait_event_type='Lock' AND lower(query) LIKE :patron
                    """).param("patron", patronLike).query(Long.class).single();
            if (esperando > 0) return;
            assertThat(futuro.isDone()).as("la segunda operacion debe quedar esperando el bloqueo").isFalse();
            Thread.sleep(20);
        }
        throw new AssertionError("No se observo en PostgreSQL la sentencia bloqueada " + patronLike);
    }
    /**
     * La primera transaccion escribe y queda ABIERTA; la segunda arranca, se observa bloqueada y recien entonces se
     * confirma la primera. Devuelve el resultado de la segunda.
     */
    private MvcResult carrera(Runnable primera, Callable<MvcResult> segunda, String patronBloqueo) throws Exception {
        CountDownLatch escrito = new CountDownLatch(1), confirmar = new CountDownLatch(1);
        Future<?> a = hilos.submit(() -> new TransactionTemplate(tm).execute(s -> {
            primera.run(); escrito.countDown(); esperar(confirmar); return null;
        }));
        esperar(escrito);
        Future<MvcResult> b = hilos.submit(segunda);
        try { esperarBloqueo(b, patronBloqueo); } finally { confirmar.countDown(); }
        a.get(15, TimeUnit.SECONDS);
        return b.get(15, TimeUnit.SECONDS);
    }
    private static void assertConflicto(MvcResult r, String codigo) throws Exception {
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat((String) JsonPath.read(r.getResponse().getContentAsString(), "$.codigo")).isEqualTo(codigo);
    }

    // ---------- contrato ----------
    @Test void laRevisionDelTutorSeExponeYAltaYEdicionNoCambianLaDeLaFamilia() throws Exception {
        mvc.perform(get(rutaTutor()).cookie(sesion(adminId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value("0"));

        String creado = mvc.perform(comando(post(rutaAlta()), alta("Eva", "\"0\"")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.version").value("0"))
                .andReturn().getResponse().getContentAsString();
        assertThat(versionFamilia()).isZero(); // crear un tutor no incrementa la revision de nombre/estado de la familia
        assertThat(tutores()).isEqualTo(2);

        mvc.perform(comando(put(rutaTutor()), edicion("11-5555-0000", "\"0\"", "\"0\"")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value("1"))
                .andExpect(jsonPath("$.telefono").value("11-5555-0000"));
        assertThat(versionTutor()).isEqualTo(1);
        assertThat(versionFamilia()).isZero();
        assertThat(auditorias()).isEqualTo(2); // alta + edicion, nada mas
        assertThat((String) JsonPath.read(creado, "$.id")).isNotEqualTo(tutor.toString());
    }

    @Test void revisionesAusentesInvalidasYObsoletasNoEscribenNiAuditanNiFiltranDatos() throws Exception {
        familiasSvc.actualizar(admin, familia, new FamiliaActualizacion("Familia renombrada", 0L), DATOS); // familia: revision 1
        mvc.perform(comando(put(rutaTutor()), edicion("11-1111-1111", "\"0\"", "\"1\""))).andExpect(status().isOk()); // tutor: revision 1
        String tutorAntes = snapshotTutor(), familiaAntes = snapshotFamilia();
        long auditoriasAntes = auditorias(), tutoresAntes = tutores();

        // Ausentes o nulas: 400 VALIDACION.
        for (String cuerpo : List.of(
                "{\"nombre\":\"Ana\",\"apellido\":\"Sintetica\",\"versionFamiliaEsperada\":\"1\"}",
                "{\"nombre\":\"Ana\",\"apellido\":\"Sintetica\",\"versionEsperada\":null,\"versionFamiliaEsperada\":\"1\"}",
                "{\"nombre\":\"Ana\",\"apellido\":\"Sintetica\",\"versionEsperada\":\"1\"}")) {
            mvc.perform(comando(put(rutaTutor()), cuerpo)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.codigo").value("VALIDACION"));
        }
        mvc.perform(comando(post(rutaAlta()), "{\"nombre\":\"Eva\",\"apellido\":\"Nueva\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("VALIDACION"));
        // Con formato invalido: 400 SOLICITUD_INVALIDA.
        for (String invalido : new String[] { "1", "-1", "\"-1\"", "\"abc\"", "\"" + "9".repeat(20) + "\"" }) {
            mvc.perform(comando(put(rutaTutor()), edicion("11-2222-2222", invalido, "\"1\"")))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
            mvc.perform(comando(post(rutaAlta()), alta("Eva", invalido)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
        }
        // Obsoletas (tutor, familia o ambas) y alta con familia obsoleta: 409, mensaje fijo, sin ecos.
        List<MockHttpServletRequestBuilder> obsoletas = List.of(
                comando(put(rutaTutor()), edicion("11-3333-3333", "\"0\"", "\"1\"")),
                comando(put(rutaTutor()), edicion("11-3333-3333", "\"1\"", "\"0\"")),
                comando(put(rutaTutor()), edicion("11-3333-3333", "\"0\"", "\"0\"")),
                comando(post(rutaAlta()), alta("Eva", "\"0\"")));
        for (var req : obsoletas) {
            String cuerpo = mvc.perform(req).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.codigo").value("EDICION_DESACTUALIZADA")).andReturn().getResponse().getContentAsString();
            assertThat(cuerpo).doesNotContain(tutor.toString(), familia.toString(), "Ana", "Sintetica", "Eva", "11-3333-3333", "version");
        }

        assertThat(snapshotTutor()).isEqualTo(tutorAntes);
        assertThat(snapshotFamilia()).isEqualTo(familiaAntes);
        assertThat(auditorias()).isEqualTo(auditoriasAntes);
        assertThat(tutores()).isEqualTo(tutoresAntes);
    }

    @Test void sinCambiosConRevisionesVigentesNoIncrementaNiAudita_ConCualquierRevisionViejaSeRechaza() throws Exception {
        mvc.perform(comando(put(rutaTutor()), edicion(null, "\"0\"", "\"0\"")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value("0"));
        assertThat(versionTutor()).isZero();
        assertThat(auditorias()).isZero();

        mvc.perform(comando(put(rutaTutor()), edicion("11-5555-0000", "\"0\"", "\"0\""))).andExpect(status().isOk()); // tutor -> 1
        String antes = snapshotTutor();
        long auditoriasAntes = auditorias();
        // Mismos datos que los guardados pero con la revision vieja del tutor, de la familia o de ambos.
        for (String[] revisiones : new String[][] { { "\"0\"", "\"0\"" }, { "\"0\"", "\"5\"" }, { "\"1\"", "\"5\"" } }) {
            mvc.perform(comando(put(rutaTutor()), edicion("11-5555-0000", revisiones[0], revisiones[1])))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("EDICION_DESACTUALIZADA"));
        }
        // Y el sin cambios vigente sigue siendo un no-op con la revision actual.
        mvc.perform(comando(put(rutaTutor()), edicion("11-5555-0000", "\"1\"", "\"0\"")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value("1"));
        assertThat(snapshotTutor()).isEqualTo(antes);
        assertThat(auditorias()).isEqualTo(auditoriasAntes);
    }

    @Test void unaFamiliaInactivaConservaFamiliaInactivaYEstaPorEncimaDeLaRevisionVieja() throws Exception {
        familiasSvc.desactivar(admin, familia, 0L, DATOS); // familia: revision 1, inactiva
        String antes = snapshotTutor();
        long auditoriasAntes = auditorias();
        for (String revisionFamilia : new String[] { "\"1\"", "\"0\"" }) { // vigente y vieja: el mismo conflicto de negocio
            mvc.perform(comando(post(rutaAlta()), alta("Eva", revisionFamilia)))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("FAMILIA_INACTIVA"));
            mvc.perform(comando(put(rutaTutor()), edicion("11-9999-9999", "\"0\"", revisionFamilia)))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("FAMILIA_INACTIVA"));
            mvc.perform(comando(put(rutaTutor()), edicion(null, "\"0\"", revisionFamilia))) // identica a lo guardado
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("FAMILIA_INACTIVA"));
        }
        assertThat(snapshotTutor()).isEqualTo(antes);
        assertThat(auditorias()).isEqualTo(auditoriasAntes);
        assertThat(tutores()).isEqualTo(1);
        // Las lecturas no dependen del estado de la familia y exponen la revision del tutor.
        mvc.perform(get(rutaTutor()).cookie(sesion(adminId))).andExpect(status().isOk()).andExpect(jsonPath("$.version").value("0"));
    }

    // ---------- concurrencia real ----------
    @Test void dosAdministradoresEditandoElMismoTutorConLaMismaRevisionSoloUnoConfirma() throws Exception {
        MvcResult perdedor = carrera(() -> tutoresSvc.actualizar(admin, tutor, cambio("11-1111-1111", 0, 0), DATOS),
                () -> mvc.perform(comando(put(rutaTutor()), edicion("11-2222-2222", "\"0\"", "\"0\""))).andReturn(),
                "%update%tutor%");
        assertConflicto(perdedor, "EDICION_DESACTUALIZADA");
        assertThat(jdbc.sql("SELECT telefono FROM gestion_patin.tutor WHERE id=:i").param("i", tutor).query(String.class).single())
                .isEqualTo("11-1111-1111"); // gano la primera, la segunda no pisa nada
        assertThat(versionTutor()).isEqualTo(1);
        assertThat(auditorias()).isEqualTo(1);
    }

    @Test void unaDesactivacionConfirmadaMientrasSeIntentaCrearUnTutorRechazaElAltaSinEscribirNada() throws Exception {
        MvcResult alta = carrera(() -> familiasSvc.desactivar(admin, familia, 0L, DATOS),
                () -> mvc.perform(comando(post(rutaAlta()), alta("Eva", "\"0\""))).andReturn(),
                "%familia%for share%"); // el alta leyo la familia activa en la revision 0 y queda esperando el bloqueo
        assertConflicto(alta, "EDICION_DESACTUALIZADA");
        assertThat(tutores()).isEqualTo(1); // no se creo el tutor sobre una familia que ya no era la observada
        assertThat(versionFamilia()).isEqualTo(1);
        assertThat(auditorias()).isEqualTo(1); // solo la desactivacion
    }

    @Test void unaDesactivacionConfirmadaMientrasSeEditaUnTutorRechazaLaEdicionSinCambiarNada() throws Exception {
        String antes = snapshotTutor();
        MvcResult edicion = carrera(() -> familiasSvc.desactivar(admin, familia, 0L, DATOS),
                () -> mvc.perform(comando(put(rutaTutor()), edicion("11-1111-1111", "\"0\"", "\"0\""))).andReturn(),
                "%familia%for share%");
        assertConflicto(edicion, "EDICION_DESACTUALIZADA");
        assertThat(snapshotTutor()).isEqualTo(antes);
        assertThat(auditorias()).isEqualTo(1);
    }

    @Test void unNoOpTambienEsRechazadoSiLaFamiliaSeDesactivaMientrasTanto() throws Exception {
        MvcResult noOp = carrera(() -> familiasSvc.desactivar(admin, familia, 0L, DATOS),
                () -> mvc.perform(comando(put(rutaTutor()), edicion(null, "\"0\"", "\"0\""))).andReturn(), // identica a lo guardado
                "%familia%for share%");
        assertConflicto(noOp, "EDICION_DESACTUALIZADA");
    }

    @Test void unaAltaEnCursoHaceEsperarALaDesactivacionYAmbasQuedanOrdenadas() throws Exception {
        // El alta con revisión 0 mantiene FOR SHARE sobre la familia hasta confirmar. La baja
        // espera al tomar el bloqueo del padre en la función de cierre, antes del UPDATE.
        MvcResult desactivacion = carrera(() -> tutoresSvc.crear(admin, familia,
                new TutorSolicitud("Eva", "Nueva", null, null, null, null, 0L), DATOS),
                () -> mvc.perform(comando(post("/api/admin/familias/" + familia + "/desactivar"), "{\"versionEsperada\":\"0\"}")).andReturn(),
                "%cerrar_vigencias_tutor%");
        assertThat(desactivacion.getResponse().getStatus()).isEqualTo(200); // despues de la alta, con la revision que no se movio
        assertThat(tutores()).isEqualTo(2);
        assertThat(versionFamilia()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT activa FROM gestion_patin.familia WHERE id=:i").param("i", familia).query(Boolean.class).single()).isFalse();
        assertThat(auditorias()).isEqualTo(2);
    }

    @Test void dosAltasEnLaMismaFamiliaNoSeSerializanEntreSi() throws Exception {
        CountDownLatch escrito = new CountDownLatch(1), confirmar = new CountDownLatch(1);
        Future<?> a = hilos.submit(() -> new TransactionTemplate(tm).execute(s -> {
            tutoresSvc.crear(admin, familia, new TutorSolicitud("Eva", "Nueva", null, null, null, null, 0L), DATOS);
            escrito.countDown(); esperar(confirmar); return null;
        }));
        try {
            esperar(escrito);
            // Con la primera alta todavia abierta (FOR SHARE tomado), la segunda termina sin esperar.
            MvcResult segunda = hilos.submit(() -> mvc.perform(comando(post(rutaAlta()), alta("Luis", "\"0\""))).andReturn())
                    .get(10, TimeUnit.SECONDS);
            assertThat(segunda.getResponse().getStatus()).isEqualTo(201);
        } finally { confirmar.countDown(); }
        a.get(15, TimeUnit.SECONDS);
        assertThat(tutores()).isEqualTo(3);
        assertThat(versionFamilia()).isZero();
    }

    @Test void elRollbackRevierteDatosVersionesYAuditoriaSiElTutorCambioAntesDelCommit() throws Exception {
        CountDownLatch comprobado = new CountDownLatch(1), terminar = new CountDownLatch(1);
        Future<?> a = hilos.submit(() -> new TransactionTemplate(tm).execute(s -> {
            tutoresSvc.crear(admin, familia, new TutorSolicitud("Debe", "Revertirse", null, null, null, null, 0L), DATOS);
            tutoresSvc.actualizar(admin, tutor, cambio(null, 0, 0), DATOS); // sin cambios: solo queda la comprobacion pendiente
            comprobado.countDown(); esperar(terminar); return null;
        }));
        esperar(comprobado);
        try { tutoresSvc.actualizar(admin, tutor, cambio("11-7777-7777", 0, 0), DATOS); } finally { terminar.countDown(); }
        assertThatThrownBy(() -> a.get(15, TimeUnit.SECONDS)).isInstanceOfSatisfying(ExecutionException.class,
                e -> assertThat(e.getCause()).isInstanceOfAny(OptimisticLockingFailureException.class, OptimisticLockException.class));
        assertThat(tutores()).isEqualTo(1); // el alta de la transaccion fallida se revirtio entera
        assertThat(versionTutor()).isEqualTo(1);
        assertThat(versionFamilia()).isZero();
        assertThat(auditorias()).isEqualTo(1); // solo la edicion confirmada; la auditoria del alta tambien se revirtio
        em.clear();
    }

    // ---------- seguridad, aislamiento y compatibilidad ----------
    @Test void escuelaAjenaNoLeeNiModificaNiCreaNiAudita() throws Exception {
        UUID familiaAjena = datos.familia(otraEscuela, "Ajena sintetica", true);
        UUID tutorAjeno = datos.tutor(otraEscuela, familiaAjena, "Otro", "Ajeno");
        String ajenoAntes = jdbc.sql("SELECT row_to_json(t)::text FROM gestion_patin.tutor t WHERE id=:i").param("i", tutorAjeno).query(String.class).single();
        mvc.perform(get("/api/admin/tutores/" + tutorAjeno).cookie(sesion(adminId))).andExpect(status().isNotFound());
        mvc.perform(comando(put("/api/admin/tutores/" + tutorAjeno),
                "{\"nombre\":\"No\",\"apellido\":\"Permitido\",\"versionEsperada\":\"0\",\"versionFamiliaEsperada\":\"0\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.codigo").value("TUTOR_NO_ENCONTRADO"));
        mvc.perform(comando(post("/api/admin/familias/" + familiaAjena + "/tutores"), alta("Eva", "\"0\"")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.codigo").value("FAMILIA_NO_ENCONTRADA"));
        assertThat(jdbc.sql("SELECT row_to_json(t)::text FROM gestion_patin.tutor t WHERE id=:i").param("i", tutorAjeno).query(String.class).single())
                .isEqualTo(ajenoAntes);
        assertThat(auditorias()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.auditoria WHERE escuela_id=:e").param("e", otraEscuela).query(Long.class).single()).isZero();
    }

    @Test void sinCsrfComoFamiliaOConSegundoFactorPendienteNoEscribe() throws Exception {
        String antes = snapshotTutor();
        String cuerpo = edicion("11-8888-8888", "\"0\"", "\"0\"");
        // Sin el token CSRF.
        mvc.perform(put(rutaTutor()).cookie(sesion(adminId)).contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
        mvc.perform(post(rutaAlta()).cookie(sesion(adminId)).contentType(MediaType.APPLICATION_JSON).content(alta("Eva", "\"0\"")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
        // Una cuenta de FAMILIA, aunque tenga CSRF.
        UUID usuarioFamilia = datos.usuarioFamilia(escuela, familia, "familia@example.invalid");
        Cookie csrf = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        Cookie comoFamilia = new Cookie("BP_SESION", tokens.emitir(usuarioFamilia, Rol.FAMILIA, escuela, familia));
        mvc.perform(put(rutaTutor()).cookie(comoFamilia, csrf).header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo)).andExpect(status().isForbidden());
        // Un ADMIN que solo paso la contrasena (segundo factor pendiente).
        Cookie pendiente = new Cookie("BP_SESION", tokens.emitirMfaPendiente(adminId, escuela));
        mvc.perform(put(rutaTutor()).cookie(pendiente, csrf).header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo)).andExpect(status().isForbidden());
        mvc.perform(post(rutaAlta()).cookie(pendiente, csrf).header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON).content(alta("Eva", "\"0\""))).andExpect(status().isForbidden());
        assertThat(snapshotTutor()).isEqualTo(antes);
        assertThat(tutores()).isEqualTo(1);
        assertThat(auditorias()).isZero();
    }

    @Test void losContratosDeLecturaDelDetalleFamiliarYDelPortalNoCambian() throws Exception {
        jdbc.sql("UPDATE gestion_patin.tutor SET dni='30111222', telefono='11-5555-0000', email='ana@example.invalid', parentesco='Madre' WHERE id=:i")
                .param("i", tutor).update();
        String detalle = mvc.perform(get("/api/admin/familias/" + familia).cookie(sesion(adminId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        // El resumen del tutor DENTRO del detalle de familia conserva sus claves y su DNI enmascarado: la revision no se agrega ahi.
        java.util.Map<String, Object> resumen = JsonPath.read(detalle, "$.tutores[0]");
        assertThat(resumen).containsOnlyKeys("id", "familiaId", "nombre", "apellido", "dniEnmascarado", "telefono", "email",
                "parentesco", "activo");
        assertThat((String) resumen.get("dniEnmascarado")).doesNotContain("30111222").endsWith("222");
        // El detalle individual (ADMIN autorizado) si trae la revision y el DNI completo, sin ids internos.
        mvc.perform(get(rutaTutor()).cookie(sesion(adminId))).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("0")).andExpect(jsonPath("$.dni").value("30111222"))
                .andExpect(jsonPath("$.escuelaId").doesNotExist()).andExpect(jsonPath("$.usuarioId").doesNotExist());
        // El portal de FAMILIA no recibe ninguna revision.
        UUID usuario = datos.usuarioFamilia(escuela, familia, "portal@example.invalid");
        Cookie comoFamilia = new Cookie("BP_SESION", tokens.emitir(usuario, Rol.FAMILIA, escuela, familia));
        String portal = mvc.perform(get("/api/familia/mi-familia").cookie(comoFamilia)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(portal).doesNotContain("version");
    }
}
