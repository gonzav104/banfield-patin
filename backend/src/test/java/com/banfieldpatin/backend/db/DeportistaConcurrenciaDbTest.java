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
import com.banfieldpatin.backend.deportistas.DeportistaAdminService;
import com.banfieldpatin.backend.deportistas.dto.DeportistaActualizacion;
import com.banfieldpatin.backend.familias.vinculos.VinculoAdminService;
import com.banfieldpatin.backend.seguridad.ServicioTokens;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;
import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.Cookie;

/**
 * FE-05/U6: revision optimista de Deportista sobre PostgreSQL 17 descartable, con la cadena HTTP real (MockMvc), JPA y la
 * concurrencia de dos transacciones reales; ningun servicio simulado. Las esperas se observan en {@code pg_stat_activity}
 * (nada depende de temporizadores) para demostrar el bloqueo real y no solo el resultado.
 */
@Tag("db")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "spring.datasource.hikari.maximum-pool-size=6", "spring.datasource.hikari.minimum-idle=1" })
@AutoConfigureMockMvc
@ActiveProfiles({ "test", "e2e" })
class DeportistaConcurrenciaDbTest extends BaseDbTest {
    private static final DatosSolicitud DATOS = new DatosSolicitud("203.0.113.3", "JUnit-U6");
    private static final String DNI = "30111222";
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired DeportistaAdminService deportistasSvc;
    @Autowired VinculoAdminService vinculosSvc;
    @Autowired ServicioTokens tokens;
    @Autowired PlatformTransactionManager tm;
    @Autowired EntityManager em;
    private DatosDb datos;
    private UUID escuela, otraEscuela, adminId, otroAdminId, familia, deportista;
    private UsuarioAutenticado admin;
    private ExecutorService hilos;

    @BeforeEach void preparar() {
        datos = new DatosDb(jdbc);
        escuela = datos.escuela("u6-" + UUID.randomUUID());
        otraEscuela = datos.escuela("u6-otra-" + UUID.randomUUID());
        adminId = datos.admin(escuela, "uno@example.invalid", true);
        otroAdminId = datos.admin(escuela, "dos@example.invalid", true);
        admin = new UsuarioAutenticado(adminId, escuela, null, Rol.ADMIN);
        familia = datos.familia(escuela, "Familia sintetica", true);
        deportista = datos.deportista(escuela, DNI, "Ana", "Sintetica");
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
    private String ruta() { return "/api/admin/deportistas/" + deportista; }
    private static String version(String revision) { return "{\"versionEsperada\":" + revision + "}"; }
    /** Reemplazo del deportista sembrado (Ana Sintetica, solo con DNI) salvo la localidad. */
    private static String edicion(String localidad, String revision) {
        return "{\"dni\":\"" + DNI + "\",\"nombre\":\"Ana\",\"apellido\":\"Sintetica\",\"localidad\":"
                + (localidad == null ? "null" : "\"" + localidad + "\"") + ",\"versionEsperada\":" + revision + "}";
    }
    private static DeportistaActualizacion cambio(String localidad, long revision) {
        return new DeportistaActualizacion(DNI, "Ana", "Sintetica", null, null, null, null, null, localidad, null, null,
                null, null, revision);
    }
    private long revision(UUID id) {
        return jdbc.sql("SELECT version FROM gestion_patin.deportista WHERE id=:i").param("i", id).query(Long.class).single();
    }
    private long revision() { return revision(deportista); }
    private boolean activo() {
        return jdbc.sql("SELECT activo FROM gestion_patin.deportista WHERE id=:i").param("i", deportista).query(Boolean.class).single();
    }
    private String localidad() {
        return jdbc.sql("SELECT localidad FROM gestion_patin.deportista WHERE id=:i").param("i", deportista).query(String.class).optional().orElse(null);
    }
    private long auditorias() {
        return jdbc.sql("SELECT count(*) FROM gestion_patin.auditoria WHERE escuela_id=:e")
                .param("e", escuela).query(Long.class).single();
    }
    private String snapshot(UUID id) {
        return jdbc.sql("SELECT row_to_json(d)::text FROM gestion_patin.deportista d WHERE id=:i").param("i", id).query(String.class).single();
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
    @Test void laRevisionSeExponeYSoloAvanzaCuandoCambianDatosOEstado() throws Exception {
        mvc.perform(get(ruta()).cookie(sesion(adminId))).andExpect(status().isOk()).andExpect(jsonPath("$.version").value("0"))
                .andExpect(jsonPath("$.dni").value(DNI)).andExpect(jsonPath("$.escuelaId").doesNotExist());

        mvc.perform(comando(put(ruta()), edicion("Banfield", "\"0\""))).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("1")).andExpect(jsonPath("$.localidad").value("Banfield"));
        mvc.perform(comando(post(ruta() + "/desactivar"), version("\"1\""))).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("2")).andExpect(jsonPath("$.activo").value(false));
        mvc.perform(comando(post(ruta() + "/activar"), version("\"2\""))).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("3")).andExpect(jsonPath("$.activo").value(true));

        assertThat(revision()).isEqualTo(3);
        assertThat(auditorias()).isEqualTo(3); // edicion + desactivacion + activacion, nada mas
        // El alta no tiene revision que esperar: nace en 0, y una revision en el cuerpo del alta se rechaza.
        mvc.perform(comando(post("/api/admin/deportistas"), "{\"dni\":\"30999888\",\"nombre\":\"Eva\",\"apellido\":\"Nueva\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.version").value("0"));
        mvc.perform(comando(post("/api/admin/deportistas"),
                "{\"dni\":\"30999777\",\"nombre\":\"Eva\",\"apellido\":\"Nueva\",\"versionEsperada\":\"0\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
    }

    @Test void revisionesAusentesInvalidasYObsoletasNoEscribenNiAuditanNiFiltranDatos() throws Exception {
        mvc.perform(comando(put(ruta()), edicion("Banfield", "\"0\""))).andExpect(status().isOk()); // revision 1
        String antes = snapshot(deportista);
        long auditoriasAntes = auditorias();

        // Ausentes o nulas: 400 VALIDACION (las tres operaciones).
        mvc.perform(comando(put(ruta()), "{\"dni\":\"" + DNI + "\",\"nombre\":\"Ana\",\"apellido\":\"Sintetica\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("VALIDACION"));
        for (String accion : List.of("activar", "desactivar")) {
            mvc.perform(comando(post(ruta() + "/" + accion), "{}")).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.codigo").value("VALIDACION"));
            mvc.perform(comando(post(ruta() + "/" + accion), version("null"))).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.codigo").value("VALIDACION"));
        }
        // Con formato invalido: 400 SOLICITUD_INVALIDA.
        for (String invalido : new String[] { "1", "-1", "\"-1\"", "\"abc\"", "\"" + "9".repeat(20) + "\"" }) {
            mvc.perform(comando(put(ruta()), edicion("Temperley", invalido))).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
            mvc.perform(comando(post(ruta() + "/desactivar"), version(invalido))).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
        }
        // Obsoletas: 409, mensaje fijo, sin ecos de datos personales ni ids.
        List<MockHttpServletRequestBuilder> obsoletas = List.of(
                comando(put(ruta()), edicion("Temperley", "\"0\"")),
                comando(put(ruta()), edicion("Temperley", "\"7\"")),
                comando(post(ruta() + "/desactivar"), version("\"0\"")),
                comando(post(ruta() + "/activar"), version("\"0\"")));
        for (var req : obsoletas) {
            String cuerpo = mvc.perform(req).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.codigo").value("EDICION_DESACTUALIZADA")).andReturn().getResponse().getContentAsString();
            assertThat(cuerpo).doesNotContain(deportista.toString(), DNI, "Ana", "Sintetica", "Temperley", "Banfield", "version");
        }

        assertThat(snapshot(deportista)).isEqualTo(antes);
        assertThat(auditorias()).isEqualTo(auditoriasAntes);
    }

    @Test void sinCambiosConRevisionVigenteNoIncrementaNiAudita_ConCualquierRevisionViejaSeRechaza() throws Exception {
        mvc.perform(comando(put(ruta()), edicion(null, "\"0\""))).andExpect(status().isOk()).andExpect(jsonPath("$.version").value("0"));
        mvc.perform(comando(post(ruta() + "/activar"), version("\"0\""))).andExpect(status().isOk()).andExpect(jsonPath("$.version").value("0"));
        assertThat(revision()).isZero();
        assertThat(auditorias()).isZero();

        mvc.perform(comando(post(ruta() + "/desactivar"), version("\"0\""))).andExpect(status().isOk()); // revision 1
        String antes = snapshot(deportista);
        long auditoriasAntes = auditorias();
        // Ya inactivo: repetir con la revision vieja se rechaza aunque el estado ya coincida; con la vigente es un no-op.
        mvc.perform(comando(post(ruta() + "/desactivar"), version("\"0\""))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("EDICION_DESACTUALIZADA"));
        mvc.perform(comando(post(ruta() + "/desactivar"), version("\"1\""))).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("1"));
        mvc.perform(comando(put(ruta()), edicion(null, "\"0\""))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("EDICION_DESACTUALIZADA")); // mismos datos, revision vieja
        mvc.perform(comando(put(ruta()), edicion(null, "\"1\""))).andExpect(status().isOk()).andExpect(jsonPath("$.version").value("1"));
        assertThat(snapshot(deportista)).isEqualTo(antes);
        assertThat(auditorias()).isEqualTo(auditoriasAntes);
    }

    @Test void laRevisionVaAntesQueLaUnicidadYLaUnicidadSigueVigenteConRevisionActual() throws Exception {
        datos.deportista(escuela, "30555666", "Otro", "Ajeno");
        // Revision vigente: el DNI de otro deportista sigue siendo DNI_DUPLICADO.
        mvc.perform(comando(put(ruta()), "{\"dni\":\"30.555.666\",\"nombre\":\"Ana\",\"apellido\":\"Sintetica\",\"versionEsperada\":\"0\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("DNI_DUPLICADO"));
        // Revision vieja: gana EDICION_DESACTUALIZADA y no se aprende que ese DNI existe.
        mvc.perform(comando(put(ruta()), "{\"dni\":\"30.555.666\",\"nombre\":\"Ana\",\"apellido\":\"Sintetica\",\"versionEsperada\":\"4\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("EDICION_DESACTUALIZADA"));
        // Conservar el propio DNI (escrito con separadores) no es un conflicto.
        mvc.perform(comando(put(ruta()), "{\"dni\":\"30.111.222\",\"nombre\":\"Ana Maria\",\"apellido\":\"Sintetica\",\"versionEsperada\":\"0\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dni").value(DNI)).andExpect(jsonPath("$.version").value("1"));
    }

    // ---------- concurrencia real ----------
    @Test void dosAdministradoresEditandoElMismoDeportistaConLaMismaRevisionSoloUnoConfirma() throws Exception {
        MvcResult perdedor = carrera(() -> deportistasSvc.actualizar(admin, deportista, cambio("Banfield", 0), DATOS),
                () -> mvc.perform(comando(put(ruta()), edicion("Temperley", "\"0\""))).andReturn(),
                "%update%deportista%");
        assertConflicto(perdedor, "EDICION_DESACTUALIZADA");
        assertThat(localidad()).isEqualTo("Banfield"); // gano la primera, la segunda no pisa nada
        assertThat(revision()).isEqualTo(1);
        assertThat(auditorias()).isEqualTo(1);
    }

    @Test void unaDesactivacionConfirmadaMientrasSeEditaRechazaLaEdicionSinCambiarNada() throws Exception {
        MvcResult edicion = carrera(() -> deportistasSvc.desactivar(admin, deportista, 0L, DATOS),
                () -> mvc.perform(comando(put(ruta()), edicion("Temperley", "\"0\""))).andReturn(),
                "%update%deportista%");
        assertConflicto(edicion, "EDICION_DESACTUALIZADA");
        assertThat(activo()).isFalse();
        assertThat(localidad()).isNull();
        assertThat(revision()).isEqualTo(1);
        assertThat(auditorias()).isEqualTo(1);
    }

    @Test void unaEdicionConfirmadaMientrasSeDesactivaRechazaLaDesactivacion() throws Exception {
        MvcResult desactivacion = carrera(() -> deportistasSvc.actualizar(admin, deportista, cambio("Banfield", 0), DATOS),
                () -> mvc.perform(comando(post(ruta() + "/desactivar"), version("\"0\""))).andReturn(),
                "%cerrar_vigencias_tutor%");
        assertConflicto(desactivacion, "EDICION_DESACTUALIZADA");
        assertThat(activo()).isTrue();
        assertThat(localidad()).isEqualTo("Banfield");
        assertThat(revision()).isEqualTo(1);
    }

    @Test void activarYDesactivarConcurrentesSobreLaMismaRevisionDejanUnSoloGanador() throws Exception {
        MvcResult activar = carrera(() -> deportistasSvc.desactivar(admin, deportista, 0L, DATOS),
                () -> mvc.perform(comando(post(ruta() + "/desactivar"), version("\"0\""))).andReturn(), // mismo estado final
                "%cerrar_vigencias_tutor%");
        // Aunque el estado final ya coincidia, la segunda opera con una revision que dejo de ser vigente: se rechaza.
        assertConflicto(activar, "EDICION_DESACTUALIZADA");
        assertThat(activo()).isFalse();
        assertThat(revision()).isEqualTo(1); // un solo incremento
        assertThat(auditorias()).isEqualTo(1);
    }

    @Test void elNoOpEnCursoFallaAlConfirmarSiOtroCambiaElDeportistaYRevierteTodoLoDeSuTransaccion() throws Exception {
        UUID otro = datos.deportista(escuela, "30777888", "Otro", "Sintetico");
        CountDownLatch comprobado = new CountDownLatch(1), terminar = new CountDownLatch(1);
        Future<?> a = hilos.submit(() -> new TransactionTemplate(tm).execute(s -> {
            // Un cambio real sobre OTRO deportista (datos, version y auditoria)...
            deportistasSvc.actualizar(admin, otro, new DeportistaActualizacion("30777888", "Otro", "Sintetico", null, null,
                    null, null, null, "Lanus", null, null, null, null, 0L), DATOS);
            // ...y un no-op sobre el deportista de la prueba: solo queda pendiente la comprobacion optimista.
            deportistasSvc.activar(admin, deportista, 0L, DATOS);
            comprobado.countDown(); esperar(terminar); return null;
        }));
        esperar(comprobado);
        try { deportistasSvc.desactivar(admin, deportista, 0L, DATOS); } finally { terminar.countDown(); }
        assertThatThrownBy(() -> a.get(15, TimeUnit.SECONDS)).isInstanceOfSatisfying(ExecutionException.class,
                e -> assertThat(e.getCause()).isInstanceOfAny(OptimisticLockingFailureException.class, OptimisticLockException.class));
        assertThat(revision(otro)).isZero(); // la edicion de la transaccion fallida se revirtio entera
        assertThat(jdbc.sql("SELECT localidad FROM gestion_patin.deportista WHERE id=:i").param("i", otro).query(String.class).optional()).isEmpty();
        assertThat(revision()).isEqualTo(1);
        assertThat(activo()).isFalse();
        assertThat(auditorias()).isEqualTo(1); // solo la desactivacion confirmada; la auditoria de la edicion tambien se revirtio
        em.clear();
    }

    @Test void vincularYCargarDocumentosBloqueanLaFilaPeroNoMuevenLaRevisionYUnaDesactivacionEsperaYSeAplica() throws Exception {
        MvcResult desactivacion = carrera(() -> vinculosSvc.vincular(admin, familia,
                List.of(new com.banfieldpatin.backend.familias.vinculos.dto.DeportistaAVincular(deportista, 0L)), DATOS),
                () -> mvc.perform(comando(post(ruta() + "/desactivar"), version("\"0\""))).andReturn(),
                "%cerrar_vigencias_tutor%");
        // La revision cubre datos personales y estado, no los vinculos: la desactivacion espero, vio la misma revision y se aplico.
        assertThat(desactivacion.getResponse().getStatus()).isEqualTo(200);
        assertThat((String) JsonPath.read(desactivacion.getResponse().getContentAsString(), "$.version")).isEqualTo("1");
        assertThat(activo()).isFalse();
        assertThat(revision()).isEqualTo(1);
        // U11-A: la vinculacion movio solo la revision de vinculos (una vez) y la desactivacion, que la entidad escribe sin esa
        // columna, no la piso con el valor que habia leido antes del incremento.
        assertThat(jdbc.sql("SELECT version_vinculos FROM gestion_patin.deportista WHERE id=:i").param("i", deportista)
                .query(Long.class).single()).isEqualTo(1);
        // Desactivar no revoca vinculos ni borra historial (regla existente).
        assertThat(jdbc.sql("SELECT estado FROM gestion_patin.familia_deportista WHERE deportista_id=:d").param("d", deportista)
                .query(String.class).list()).containsExactly("ACTIVO");
        assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.auditoria WHERE escuela_id=:e AND recurso_tipo='FAMILIA_DEPORTISTA'")
                .param("e", escuela).query(Long.class).single()).isEqualTo(1);
    }

    @Test void elRollbackNoDejaVersionDatosNiAuditoriaSiLaTransaccionSeAborta() throws Exception {
        long auditoriasAntes = auditorias();
        String antes = snapshot(deportista);
        // Una transaccion que escribe el cambio y luego se aborta: ni datos, ni version, ni auditoria quedan.
        assertThatThrownBy(() -> new TransactionTemplate(tm).execute(s -> {
            deportistasSvc.actualizar(admin, deportista, cambio("Banfield", 0), DATOS);
            throw new IllegalStateException("fallo simulado posterior a la auditoria");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(snapshot(deportista)).isEqualTo(antes);
        assertThat(revision()).isZero();
        assertThat(auditorias()).isEqualTo(auditoriasAntes);
        em.clear();
        // Y la revision vigente sigue sirviendo: nada se consumio.
        mvc.perform(comando(put(ruta()), edicion("Banfield", "\"0\""))).andExpect(status().isOk()).andExpect(jsonPath("$.version").value("1"));
    }

    // ---------- seguridad, aislamiento y compatibilidad ----------
    @Test void escuelaAjenaNoLeeNiModificaNiCambiaDeEstadoNiAudita() throws Exception {
        UUID ajeno = datos.deportista(otraEscuela, "30444555", "Otro", "Ajeno");
        String ajenoAntes = snapshot(ajeno);
        String rutaAjena = "/api/admin/deportistas/" + ajeno;
        mvc.perform(get(rutaAjena).cookie(sesion(adminId))).andExpect(status().isNotFound());
        mvc.perform(comando(put(rutaAjena), "{\"dni\":\"30444555\",\"nombre\":\"No\",\"apellido\":\"Permitido\",\"versionEsperada\":\"0\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.codigo").value("DEPORTISTA_NO_ENCONTRADO"));
        for (String revision : new String[] { "\"0\"", "\"99\"" }) { // la revision no cambia el 404: no revela si existe
            mvc.perform(comando(post(rutaAjena + "/activar"), version(revision))).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.codigo").value("DEPORTISTA_NO_ENCONTRADO"));
            mvc.perform(comando(post(rutaAjena + "/desactivar"), version(revision))).andExpect(status().isNotFound());
        }
        assertThat(snapshot(ajeno)).isEqualTo(ajenoAntes);
        assertThat(auditorias()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.auditoria WHERE escuela_id=:e").param("e", otraEscuela).query(Long.class).single()).isZero();
    }

    @Test void sinCsrfComoFamiliaOConSegundoFactorPendienteNoEscribe() throws Exception {
        String antes = snapshot(deportista);
        String cuerpo = edicion("Banfield", "\"0\"");
        // Sin el token CSRF.
        mvc.perform(put(ruta()).cookie(sesion(adminId)).contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
        mvc.perform(post(ruta() + "/desactivar").cookie(sesion(adminId)).contentType(MediaType.APPLICATION_JSON).content(version("\"0\"")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
        // Una cuenta de FAMILIA, aunque tenga CSRF.
        UUID usuarioFamilia = datos.usuarioFamilia(escuela, familia, "familia@example.invalid");
        Cookie csrf = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        Cookie comoFamilia = new Cookie("BP_SESION", tokens.emitir(usuarioFamilia, Rol.FAMILIA, escuela, familia));
        mvc.perform(put(ruta()).cookie(comoFamilia, csrf).header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo)).andExpect(status().isForbidden());
        mvc.perform(post(ruta() + "/activar").cookie(comoFamilia, csrf).header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON).content(version("\"0\""))).andExpect(status().isForbidden());
        // Un ADMIN que solo paso la contrasena (segundo factor pendiente).
        Cookie pendiente = new Cookie("BP_SESION", tokens.emitirMfaPendiente(adminId, escuela));
        mvc.perform(put(ruta()).cookie(pendiente, csrf).header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo)).andExpect(status().isForbidden());
        mvc.perform(post(ruta() + "/desactivar").cookie(pendiente, csrf).header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON).content(version("\"0\""))).andExpect(status().isForbidden());
        assertThat(snapshot(deportista)).isEqualTo(antes);
        assertThat(auditorias()).isZero();
    }

    @Test void losContratosDeLecturaDelListadoYDelPortalNoCambian() throws Exception {
        datos.vinculo(escuela, familia, deportista, "ACTIVO", true, adminId);
        String listado = mvc.perform(get("/api/admin/deportistas").cookie(sesion(adminId))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(listado).doesNotContain("version").doesNotContain(DNI); // el resumen conserva el DNI enmascarado y no trae revision
        UUID usuario = datos.usuarioFamilia(escuela, familia, "portal@example.invalid");
        Cookie comoFamilia = new Cookie("BP_SESION", tokens.emitir(usuario, Rol.FAMILIA, escuela, familia));
        for (String ruta : new String[] { "/api/familia/deportistas", "/api/familia/deportistas/" + deportista,
                "/api/familia/deportistas/" + deportista + "/ficha" }) {
            String portal = mvc.perform(get(ruta).cookie(comoFamilia)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(portal).as(ruta).doesNotContain("version");
        }
    }
}
