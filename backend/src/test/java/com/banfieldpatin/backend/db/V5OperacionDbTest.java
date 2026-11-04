package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Ensayo operacional reproducible; no cambia V5 ni consume la configuración de la aplicación. */
@Tag("db")
class V5OperacionDbTest {
    @Test void respaldoSeRestauraEnOtroServidorYRecuperaV4TrasMigrar() throws Exception {
        try (var origen = new V5OperacionSupport(); var restaurada = new V5OperacionSupport()) {
            origen.prepararV4();
            restaurada.prepararV4();
            var antes = origen.estadoRestaurable();
            var destinoAntes = restaurada.estado();
            var dump = origen.respaldo();
            var corrupto = V5OperacionSupport.EVIDENCIAS.resolve("corrupto.dump");
            Files.writeString(corrupto, "NO ES UN ARCHIVO POSTGRESQL");
            assertThat(restaurada.restaurar(corrupto)).isNotZero();
            assertThat(restaurada.estado()).isEqualTo(destinoAntes);
            assertThat(restaurada.pg.getContainerId()).isNotEqualTo(origen.pg.getContainerId());
            assertThat(restaurada.restaurar(dump)).isZero();
            assertThat(restaurada.estadoRestaurable()).isEqualTo(antes);
            assertThat(origen.flyway("5", "").migrate().migrationsExecuted).isEqualTo(1);
            assertThat(origen.datos(true)).isEqualTo(antes.datos());
            origen.integridadV5();
            var migrada = origen.estado();
            assertThat(origen.flyway("5", "").migrate().migrationsExecuted).isZero();
            assertThat(origen.estado()).isEqualTo(migrada);

            // Una restauración anterior no conserva escrituras posteriores: requiere ventana sin escrituras/RPO aprobado.
            origen.documento(origen.atleta, null, "OTRO", null, null, true);
            assertThat(restaurada.restaurar(dump)).isZero();
            assertThat(restaurada.estadoRestaurable()).isEqualTo(antes);
            assertThat(restaurada.numero("SELECT count(*) FROM gestion_patin.documento"))
                    .isEqualTo(origen.numero("SELECT count(*) FROM gestion_patin.documento") - 1);
            assertThat(restaurada.texto("SELECT max(version) FROM gestion_patin.flyway_schema_history WHERE success")).isEqualTo("4");
            origen.evidencia("respaldo-restauracion", Map.of("dump", dump.getFileName().toString(),
                    "dumpSha256", V5OperacionSupport.hash(dump), "estadoV4Sha256", V5OperacionSupport.hash(antes),
                    "restauradoSha256", V5OperacionSupport.hash(restaurada.estadoRestaurable()), "archivoCorruptoRechazado", true,
                    "datosV4Conservados", true, "reintentoV5SinCambios", true, "escriturasPosterioresFueraDelRespaldo", 1));
        }
    }

    @Test void falloTardioDePermisosRevierteDdlBackfillYFlywayYPermiteReintento() throws Exception {
        try (var b = new V5OperacionSupport()) {
            b.prepararV4();
            b.sql.execute("CREATE ROLE v5_operativa_migrador NOLOGIN");
            b.sql.execute("GRANT USAGE, CREATE ON SCHEMA gestion_patin TO v5_operativa_migrador");
            b.sql.execute("ALTER TABLE gestion_patin.documento OWNER TO v5_operativa_migrador");
            b.sql.execute("GRANT ALL ON gestion_patin.flyway_schema_history TO v5_operativa_migrador");
            b.sql.execute("REVOKE CREATE ON SCHEMA gestion_patin FROM v5_operativa_migrador");
            assertThat(b.numero("SELECT has_schema_privilege('v5_operativa_migrador','gestion_patin','CREATE')::int")).isZero();
            var antes = b.estado();
            String estado = fallarMigracion(b, "SET ROLE v5_operativa_migrador");
            assertThat(estado).isEqualTo("42501");
            assertThat(b.pg.getLogs()).contains("ADD CONSTRAINT ck_documento_version");
            assertThat(b.estado()).isEqualTo(antes);
            assertThat(b.numero("SELECT count(*) FROM information_schema.columns WHERE table_schema='gestion_patin' AND table_name='documento' AND column_name='serie_id'")).isZero();
            // Corrección del permiso exclusivamente en la base ficticia; no se repara historial ni se edita V5.
            b.sql.execute("GRANT CREATE ON SCHEMA gestion_patin TO v5_operativa_migrador");
            assertThat(b.flyway("5", "SET ROLE v5_operativa_migrador").migrate().migrationsExecuted).isEqualTo(1);
            assertThat(b.datos(true)).isEqualTo(antes.datos());
            b.integridadV5();
            b.evidencia("rollback-ddl-tardio", Map.of("sqlstate", estado, "falloEnAlterConstraintsDespuesDelBackfill", true,
                    "rollbackDatosCatalogoHistoria", true, "estadoV4Sha256", V5OperacionSupport.hash(antes), "reintentoTrasPermisoLocal", true));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"propietario", "dni-temporada", "dni-duplicado", "contexto", "bifurcacion", "ciclo", "dos-vigentes"})
    void incompatiblesRechazadosSinCambiosEnTodaLaBase(String caso) throws Exception {
        try (var b = new V5OperacionSupport()) {
            b.prepararV4();
            switch (caso) {
                case "propietario" -> b.documento(null, b.tutor, "DNI_DEPORTISTA_FRENTE", null, null, true);
                case "dni-temporada" -> b.documento(b.atleta, null, "DNI_DEPORTISTA_DORSO", b.temporada, null, false);
                case "dni-duplicado" -> b.documento(b.atleta, null, "DNI_DEPORTISTA_FRENTE", null, null, true);
                case "contexto" -> {
                    UUID raiz = b.documento(b.atleta, null, "OTRO", null, null, false);
                    b.documento(null, b.tutor, "OTRO", null, raiz, true);
                }
                case "bifurcacion" -> {
                    UUID raiz = b.documento(b.atleta, null, "OTRO", null, null, false);
                    b.documento(b.atleta, null, "OTRO", null, raiz, false);
                    b.documento(b.atleta, null, "OTRO", null, raiz, true);
                }
                case "ciclo" -> {
                    UUID raiz = b.documento(b.atleta, null, "OTRO", null, null, false);
                    UUID hijo = b.documento(b.atleta, null, "OTRO", null, raiz, true);
                    b.jdbc.sql("UPDATE gestion_patin.documento SET documento_anterior_id=:h WHERE id=:r")
                            .param("h", hijo).param("r", raiz).update();
                }
                case "dos-vigentes" -> {
                    UUID raiz = b.documento(b.atleta, null, "OTRO", null, null, true);
                    b.documento(b.atleta, null, "OTRO", null, raiz, true);
                }
                default -> throw new AssertionError(caso);
            }
            var antes = b.estado();
            assertThat(fallarMigracion(b, "")).isEqualTo("23514");
            assertThat(b.estado()).isEqualTo(antes);
            // Repetir con la incompatibilidad intacta debe rechazar otra vez, no aplicar/reparar parcialmente.
            assertThat(fallarMigracion(b, "")).isEqualTo("23514");
            assertThat(b.estado()).isEqualTo(antes);
            b.evidencia("incompatible-" + caso, Map.of("sqlstate", "23514", "sinCambiosTrasDosIntentos", true,
                    "estadoV4Sha256", V5OperacionSupport.hash(antes)));
        }
    }

    @Test void lecturaPreviaProvocaLockTimeoutYLaLiberacionPermiteMigrar() throws Exception {
        try (var b = new V5OperacionSupport(); Connection lector = b.ds.getConnection(); var hilos = Executors.newSingleThreadExecutor()) {
            b.prepararV4();
            var antes = b.estado();
            lector.setAutoCommit(false);
            int pid;
            try (var st = lector.createStatement(); var r = st.executeQuery("SELECT pg_backend_pid(),count(*) FROM gestion_patin.documento")) {
                r.next(); pid = r.getInt(1);
            }
            long inicio = System.nanoTime();
            var migracion = hilos.submit(() -> fallarMigracion(b, "SET application_name='v5_ops_timeout'; SET lock_timeout='2s'"));
            try {
                V5OperacionSupport.esperar(() -> b.bloqueado(pid, "v5_ops_timeout"));
                assertThat(migracion.get(12, TimeUnit.SECONDS)).isEqualTo("55P03");
                assertThat(b.estado()).isEqualTo(antes);
            } finally { lector.rollback(); }
            long milisegundos = Duration.ofNanos(System.nanoTime() - inicio).toMillis();
            assertThat(b.flyway("5", "").migrate().migrationsExecuted).isEqualTo(1);
            assertThat(b.datos(true)).isEqualTo(antes.datos());
            b.integridadV5();
            b.evidencia("lock-timeout", Map.of("bloqueoRealObservado", true, "sqlstate", "55P03",
                    "timeoutConfiguradoMs", 2000, "duracionObservadaMs", milisegundos, "reintentoTrasLiberacion", true));
        }
    }

    @Test void migracionConLockExclusivoInterrumpeLecturasYEscrituras() throws Exception {
        try (var b = new V5OperacionSupport(); Connection control = b.ds.getConnection(); var hilos = Executors.newFixedThreadPool(3)) {
            b.prepararV4();
            var datosAntes = b.datos(false);
            // Instrumentación sólo en este contenedor: pausa el Flyway real después del primer ALTER TABLE.
            b.sql.execute("""
                    CREATE FUNCTION public.v5_ops_pausa() RETURNS event_trigger LANGUAGE plpgsql AS $$
                    BEGIN IF current_setting('application_name')='v5_ops_pausa' THEN
                      PERFORM pg_advisory_xact_lock(170005);
                    END IF; END $$
                    """);
            b.sql.execute("CREATE EVENT TRIGGER v5_ops_pausa ON ddl_command_end WHEN TAG IN ('ALTER TABLE') EXECUTE FUNCTION public.v5_ops_pausa()");
            int pidControl;
            try (var st = control.createStatement(); var r = st.executeQuery("SELECT pg_backend_pid(),pg_advisory_lock(170005)")) {
                r.next(); pidControl = r.getInt(1);
            }
            long inicio = System.nanoTime();
            var migracion = hilos.submit(() -> b.flyway("5", "SET application_name='v5_ops_pausa'; SET statement_timeout='20s'").migrate());
            try {
                V5OperacionSupport.esperar(() -> b.bloqueado(pidControl, "v5_ops_pausa"));
                int pidMigracion = b.pid("v5_ops_pausa");
                var lectura = hilos.submit(() -> consultaConTimeout(b, "v5_ops_lector", "SELECT count(*) FROM gestion_patin.documento"));
                var escritura = hilos.submit(() -> consultaConTimeout(b, "v5_ops_escritor", "UPDATE gestion_patin.documento SET vigente=vigente WHERE false"));
                V5OperacionSupport.esperar(() -> b.bloqueado(pidMigracion, "v5_ops_lector"));
                V5OperacionSupport.esperar(() -> b.bloqueado(pidMigracion, "v5_ops_escritor"));
                assertThat(lectura.get(8, TimeUnit.SECONDS)).isEqualTo("57014");
                assertThat(escritura.get(8, TimeUnit.SECONDS)).isEqualTo("57014");
            } finally {
                try (var st = control.createStatement()) { st.execute("SELECT pg_advisory_unlock(170005)"); }
            }
            assertThat(migracion.get(15, TimeUnit.SECONDS).migrationsExecuted).isEqualTo(1);
            long duracion = Duration.ofNanos(System.nanoTime() - inicio).toMillis();
            b.sql.execute("DROP EVENT TRIGGER v5_ops_pausa");
            b.sql.execute("DROP FUNCTION public.v5_ops_pausa()");
            assertThat(b.datos(true)).isEqualTo(datosAntes);
            b.integridadV5();
            b.evidencia("interrupcion-servicio", Map.of("lecturaBloqueadaPorFlyway", true,
                    "escrituraBloqueadaPorFlyway", true, "sqlstateConsultas", "57014", "timeoutConsultaMs", 1200,
                    "duracionInstrumentadaMs", duracion, "instrumentacionArtificialNoBenchmark", true));
        }
    }

    private static String consultaConTimeout(V5OperacionSupport b, String aplicacion, String consulta) throws Exception {
        try (var c = b.ds.getConnection(); var st = c.createStatement()) {
            st.execute("SET application_name='" + aplicacion + "'; SET statement_timeout='1200ms'");
            try { st.execute(consulta); return "EXITO"; }
            catch (SQLException e) { return e.getSQLState(); }
        }
    }

    private static String fallarMigracion(V5OperacionSupport b, String inicio) {
        try { b.flyway("5", inicio).migrate(); return "EXITO"; }
        catch (FlywayException e) {
            for (Throwable t = e; t != null; t = t.getCause())
                if (t instanceof SQLException sql && sql.getSQLState() != null) return sql.getSQLState();
            throw e;
        }
    }
}
