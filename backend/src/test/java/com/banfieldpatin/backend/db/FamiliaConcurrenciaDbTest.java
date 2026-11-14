package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.time.Duration;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.banfieldpatin.backend.familias.FamiliaAdminService;
import com.banfieldpatin.backend.familias.dto.FamiliaActualizacion;
import com.banfieldpatin.backend.familias.dto.FamiliaSolicitud;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.seguridad.ServicioTokens;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;
import jakarta.servlet.http.Cookie;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import org.springframework.dao.OptimisticLockingFailureException;

/** HTTP con cadena real (MockMvc), JPA y PostgreSQL 17 descartable; ningun servicio simulado. */
@Tag("db")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "spring.datasource.hikari.maximum-pool-size=5", "spring.datasource.hikari.minimum-idle=1" })
@AutoConfigureMockMvc
@ActiveProfiles({ "test", "e2e" })
class FamiliaConcurrenciaDbTest extends BaseDbTest {
    private static final DatosSolicitud DATOS = new DatosSolicitud("203.0.113.1", "JUnit-U1");
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired FamiliaAdminService servicio;
    @Autowired ServicioTokens tokens;
    @Autowired PlatformTransactionManager tm;
    @Autowired EntityManager em;
    private DatosDb datos;
    private UUID escuela, otraEscuela, id, adminId, otroAdminId;
    private UsuarioAutenticado admin;
    private ExecutorService hilos;

    @BeforeEach void preparar() {
        datos = new DatosDb(jdbc);
        escuela = datos.escuela("u1-" + UUID.randomUUID());
        otraEscuela = datos.escuela("u1-otra-" + UUID.randomUUID());
        adminId = datos.admin(escuela, "uno@example.invalid", true);
        otroAdminId = datos.admin(escuela, "dos@example.invalid", true);
        admin = new UsuarioAutenticado(adminId, escuela, null, Rol.ADMIN);
        id = datos.familia(escuela, "Familia sintetica", true);
        hilos = Executors.newFixedThreadPool(2);
    }
    @AfterEach void limpiar() throws Exception {
        hilos.shutdownNow();
        assertThat(hilos.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        datos.limpiarEscuela(escuela);
        datos.limpiarEscuela(otraEscuela);
    }
    private Cookie sesion(UUID actor) {
        return new Cookie("BP_SESION", tokens.emitir(actor, Rol.ADMIN, escuela, null));
    }
    private MockHttpServletRequestBuilder comando(MockHttpServletRequestBuilder req, String json) throws Exception {
        Cookie csrf = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        return req.cookie(sesion(otroAdminId), csrf).header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON).content(json);
    }
    private long version() { return Long.parseLong(servicio.obtener(admin, id).version()); }
    private long auditorias() {
        return jdbc.sql("SELECT count(*) FROM gestion_patin.auditoria WHERE escuela_id=:e")
                .param("e", escuela).query(Long.class).single();
    }
    private String snapshot() {
        return jdbc.sql("SELECT row_to_json(f)::text FROM gestion_patin.familia f WHERE id=:i")
                .param("i", id).query(String.class).single();
    }
    private void esperar(CountDownLatch latch) {
        try { assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    private void esperarBloqueo(Future<?> futuro) throws Exception {
        long limite = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < limite) {
            long esperando = jdbc.sql("""
                    SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()
                    AND wait_event_type='Lock' AND lower(query) LIKE '%update%familia%'
                    """).query(Long.class).single();
            if (esperando > 0) return;
            assertThat(futuro.isDone()).as("la segunda escritura debe alcanzar el UPDATE bloqueado").isFalse();
            Thread.sleep(20);
        }
        throw new AssertionError("No se observo el UPDATE bloqueado en PostgreSQL");
    }

    @Test void detalleAltaYNoOpMantienenElContratoYNoCreanUsuarios() throws Exception {
        mvc.perform(get("/api/admin/familias/" + id).cookie(sesion(adminId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value("0"));
        servicio.actualizar(admin, id, new FamiliaActualizacion("Familia sintetica", 0L), DATOS);
        servicio.activar(admin, id, 0L, DATOS);
        assertThat(version()).isZero();
        assertThat(auditorias()).isZero();
        long usuarios = jdbc.sql("SELECT count(*) FROM gestion_patin.usuario WHERE escuela_id=:e")
                .param("e", escuela).query(Long.class).single();
        var creada = servicio.crear(admin, new FamiliaSolicitud("Nueva sintetica"), DATOS);
        assertThat(creada.version()).isEqualTo("0");
        assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.usuario WHERE escuela_id=:e")
                .param("e", escuela).query(Long.class).single()).isEqualTo(usuarios);
    }

    @Test void invitacionConFamiliaNuevaSigueCreandoUnaRevisionInicialSinUsuario() throws Exception {
        long usuarios = jdbc.sql("SELECT count(*) FROM gestion_patin.usuario WHERE escuela_id=:e")
                .param("e", escuela).query(Long.class).single();
        String cuerpo = mvc.perform(comando(post("/api/admin/invitaciones"),
                "{\"nuevaFamilia\":{\"nombreReferencia\":\"Invitada sintetica\"}}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String familiaId = com.jayway.jsonpath.JsonPath.read(cuerpo, "$.familia.id");
        mvc.perform(get("/api/admin/familias/" + familiaId).cookie(sesion(adminId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value("0"));
        assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.usuario WHERE escuela_id=:e")
                .param("e", escuela).query(Long.class).single()).isEqualTo(usuarios);
    }

    @Test void putActualizaYLaRevisionViejaNoEscribeNiAuditaAunqueCoincidaElNombre() throws Exception {
        mvc.perform(comando(put("/api/admin/familias/" + id),
                "{\"nombreReferencia\":\"Nueva sintetica\",\"versionEsperada\":\"0\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value("1"));
        String antes = snapshot();
        for (String nombre : new String[] { "Nueva sintetica", "Otra sintetica" }) {
            String cuerpo = mvc.perform(comando(put("/api/admin/familias/" + id),
                    "{\"nombreReferencia\":\"" + nombre + "\",\"versionEsperada\":\"0\"}"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("EDICION_DESACTUALIZADA"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(cuerpo).doesNotContain(nombre, id.toString(), "version");
        }
        assertThat(snapshot()).isEqualTo(antes);
        assertThat(auditorias()).isEqualTo(1);
    }

    @Test void estadosRechazanRevisionesViejasYNoOpVigenteNoIncrementa() throws Exception {
        servicio.desactivar(admin, id, 0L, DATOS);
        String antes = snapshot();
        for (String accion : new String[] { "activar", "desactivar" }) {
            mvc.perform(comando(post("/api/admin/familias/" + id + "/" + accion), "{\"versionEsperada\":\"0\"}"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("EDICION_DESACTUALIZADA"));
        }
        assertThat(snapshot()).isEqualTo(antes);
        servicio.desactivar(admin, id, 1L, DATOS);
        assertThat(version()).isEqualTo(1);
        assertThat(auditorias()).isEqualTo(1);
        servicio.activar(admin, id, 1L, DATOS);
        servicio.activar(admin, id, 2L, DATOS);
        assertThat(version()).isEqualTo(2);
        assertThat(auditorias()).isEqualTo(2);
    }

    private void carrera(Supplier<?> primera, String metodo, String json) throws Exception {
        CountDownLatch escrito = new CountDownLatch(1), confirmar = new CountDownLatch(1);
        Future<?> a = hilos.submit(() -> new TransactionTemplate(tm).execute(s -> {
            Object resultado = primera.get(); escrito.countDown(); esperar(confirmar); return resultado;
        }));
        esperar(escrito);
        Future<Integer> b = hilos.submit(() -> {
            var req = metodo.equals("PUT") ? put("/api/admin/familias/" + id)
                    : post("/api/admin/familias/" + id + "/" + metodo);
            return mvc.perform(comando(req, json)).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.codigo").value("EDICION_DESACTUALIZADA"))
                    .andReturn().getResponse().getStatus();
        });
        try { esperarBloqueo(b); } finally { confirmar.countDown(); }
        a.get(15, TimeUnit.SECONDS);
        assertThat(b.get(15, TimeUnit.SECONDS)).isEqualTo(409);
    }
    @Test void dosPutConLaMismaRevisionSoloUnoConfirmaInclusoConLecturasSolapadas() throws Exception {
        carrera(() -> servicio.actualizar(admin, id, new FamiliaActualizacion("Ganadora sintetica", 0L), DATOS),
                "PUT", "{\"nombreReferencia\":\"Perdedora sintetica\",\"versionEsperada\":\"0\"}");
        assertThat(servicio.obtener(admin, id).nombreReferencia()).isEqualTo("Ganadora sintetica");
        assertThat(version()).isEqualTo(1);
        assertThat(auditorias()).isEqualTo(1);
    }
    @Test void dosDesactivacionesConcurrentesNoSeAceptanComoNoOpConRevisionVieja() throws Exception {
        carrera(() -> servicio.desactivar(admin, id, 0L, DATOS), "desactivar", "{\"versionEsperada\":\"0\"}");
        assertThat(servicio.obtener(admin, id).activa()).isFalse();
        assertThat(version()).isEqualTo(1);
        assertThat(auditorias()).isEqualTo(1);
    }
    @Test void dosActivacionesConcurrentesNoSeAceptanComoNoOpConRevisionVieja() throws Exception {
        servicio.desactivar(admin, id, 0L, DATOS);
        carrera(() -> servicio.activar(admin, id, 1L, DATOS), "activar", "{\"versionEsperada\":\"1\"}");
        assertThat(servicio.obtener(admin, id).activa()).isTrue();
        assertThat(version()).isEqualTo(2);
        assertThat(auditorias()).isEqualTo(2);
    }
    @Test void edicionContraDesactivacionConcurrenteNoRevierteElEstadoNiElNombre() throws Exception {
        carrera(() -> servicio.desactivar(admin, id, 0L, DATOS), "PUT",
                "{\"nombreReferencia\":\"Perdedora sintetica\",\"versionEsperada\":\"0\"}");
        assertThat(servicio.obtener(admin, id).nombreReferencia()).isEqualTo("Familia sintetica");
        assertThat(servicio.obtener(admin, id).activa()).isFalse();
        assertThat(version()).isEqualTo(1);
        assertThat(auditorias()).isEqualTo(1);
    }

    @Test void noOpTambienCompruebaLaVersionAlCommitYRevierteTrabajoPrevioDeLaTransaccion() throws Exception {
        CountDownLatch leido = new CountDownLatch(1), terminar = new CountDownLatch(1);
        Future<?> a = hilos.submit(() -> new TransactionTemplate(tm).execute(s -> {
            servicio.crear(admin, new FamiliaSolicitud("Debe revertirse"), DATOS);
            servicio.actualizar(admin, id, new FamiliaActualizacion("Familia sintetica", 0L), DATOS);
            leido.countDown(); esperar(terminar); return null;
        }));
        esperar(leido);
        try { servicio.desactivar(admin, id, 0L, DATOS); } finally { terminar.countDown(); }
        assertThatThrownBy(() -> a.get(15, TimeUnit.SECONDS)).isInstanceOfSatisfying(ExecutionException.class,
                e -> assertThat(e.getCause()).isInstanceOfAny(OptimisticLockingFailureException.class, OptimisticLockException.class));
        assertThat(version()).isEqualTo(1);
        assertThat(auditorias()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.familia WHERE escuela_id=:e")
                .param("e", escuela).query(Long.class).single()).isEqualTo(1);
        em.clear();
    }

    @Test void activarYDesactivarSolapadasValidanTambienLaOperacionQueEraNoOp() throws Exception {
        for (boolean inicialActiva : new boolean[] { true, false }) {
            long esperada = version();
            assertThat(servicio.obtener(admin, id).activa()).isEqualTo(inicialActiva);
            long eventosAntes = auditorias();
            CountDownLatch leido = new CountDownLatch(1), terminar = new CountDownLatch(1);
            Future<?> a = hilos.submit(() -> new TransactionTemplate(tm).execute(s -> {
                if (inicialActiva) servicio.activar(admin, id, esperada, DATOS);
                else servicio.desactivar(admin, id, esperada, DATOS);
                leido.countDown(); esperar(terminar); return null;
            }));
            esperar(leido);
            try {
                if (inicialActiva) servicio.desactivar(admin, id, esperada, DATOS);
                else servicio.activar(admin, id, esperada, DATOS);
            } finally { terminar.countDown(); }
            assertThatThrownBy(() -> a.get(15, TimeUnit.SECONDS)).isInstanceOfSatisfying(ExecutionException.class,
                    e -> assertThat(e.getCause()).isInstanceOfAny(OptimisticLockingFailureException.class, OptimisticLockException.class));
            assertThat(version()).isEqualTo(esperada + 1);
            assertThat(auditorias()).isEqualTo(eventosAntes + 1);
        }
    }

    @Test void escuelaAjenaNoObtieneNiModificaLaRevision() throws Exception {
        UUID ajena = datos.familia(otraEscuela, "Ajena sintetica", true);
        mvc.perform(get("/api/admin/familias/" + ajena).cookie(sesion(adminId))).andExpect(status().isNotFound());
        mvc.perform(comando(put("/api/admin/familias/" + ajena),
                "{\"nombreReferencia\":\"No permitido\",\"versionEsperada\":\"0\"}"))
                .andExpect(status().isNotFound());
        for (String accion : new String[] { "activar", "desactivar" }) {
            mvc.perform(comando(post("/api/admin/familias/" + ajena + "/" + accion), "{\"versionEsperada\":\"0\"}"))
                    .andExpect(status().isNotFound());
        }
        assertThat(auditorias()).isZero();
    }
}
