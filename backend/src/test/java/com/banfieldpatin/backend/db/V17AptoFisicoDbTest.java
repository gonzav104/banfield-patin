package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** V17 en una base privada: actualización desde V16 con datos previos, metadatos exclusivos del apto y serie única por temporada. */
@Tag("db")
class V17AptoFisicoDbTest {
    private String base;
    private DriverManagerDataSource ds;
    private JdbcClient jdbc;
    private UUID escuela, otraEscuela, autor, atleta, otroAtleta, tutor, temporada, otraTemporada;
    private long documentosAntes, operacionesAntes;

    @BeforeEach
    void preparar() {
        base = PostgresDescartable.crearBase("v17");
        ds = new DriverManagerDataSource(PostgresDescartable.urlDeBase(base), PostgresDescartable.usuario(),
                PostgresDescartable.clave());
        jdbc = JdbcClient.create(ds);
        migrar("16");
        var datos = new DatosDb(jdbc);
        escuela = datos.escuela("v17-" + UUID.randomUUID());
        otraEscuela = datos.escuela("v17b-" + UUID.randomUUID());
        autor = datos.admin(escuela, "sintetico@example.invalid", true);
        atleta = datos.deportista(escuela, "SINTETICO", "Prueba", "SQL");
        otroAtleta = datos.deportista(escuela, "SINTETICO2", "Otra", "SQL");
        tutor = datos.tutor(escuela, datos.familia(escuela, "Familia sintética", true), "Tutor", "SQL");
        temporada = datos.temporada(escuela, 2026, false);
        otraTemporada = datos.temporada(escuela, 2027, false);
        jdbc.sql("""
                INSERT INTO gestion_patin.documento(escuela_id,deportista_id,tipo,nombre_original,storage_key,
                  mime_type,tamanio_bytes,cargado_por) VALUES(:e,:d,'OTRO','sintetico.pdf',:k,'application/pdf',10,:a)
                """).param("e", escuela).param("d", atleta).param("k", UUID.randomUUID().toString()).param("a", autor).update();
        documentosAntes = contar("documento");
        operacionesAntes = contar("operacion_carga_documental");
        migrar("17");
    }

    @AfterEach
    void cerrar() {
        if (base != null) PostgresDescartable.eliminarBase(base);
    }

    private void migrar(String version) {
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").schemas("gestion_patin")
                .defaultSchema("gestion_patin").target(version).cleanDisabled(true).load().migrate();
    }

    private long contar(String tabla) {
        return jdbc.sql("SELECT count(*) FROM gestion_patin." + tabla).query(Long.class).single();
    }

    private UUID apto(UUID esc, UUID deportista, UUID temp, boolean vigente, String nombre, String matricula, String fecha) {
        return jdbc.sql("""
                INSERT INTO gestion_patin.documento(escuela_id,deportista_id,temporada_id,tipo,nombre_original,storage_key,
                  mime_type,tamanio_bytes,cargado_por,vigente,fecha_emision,medico_nombre,medico_matricula)
                VALUES(:e,:d,CAST(:t AS uuid),'APTO_FISICO','sintetico.pdf',:k,'application/pdf',10,:a,:v,
                  CAST(:f AS date),:n,:m) RETURNING id
                """).param("e", esc).param("d", deportista).param("t", temp).param("k", UUID.randomUUID().toString())
                .param("a", autor).param("v", vigente).param("f", fecha).param("n", nombre).param("m", matricula)
                .query(UUID.class).single();
    }

    private UUID apto(UUID deportista, UUID temp) {
        return apto(escuela, deportista, temp, true, "Medico Sintetico", "MP 0001", "2026-03-01");
    }

    private void rechazar(String sqlState, Runnable accion) {
        assertThatThrownBy(accion::run).isInstanceOfSatisfying(DataIntegrityViolationException.class,
                ex -> assertThat(((SQLException) ex.getMostSpecificCause()).getSQLState()).isEqualTo(sqlState));
    }

    @Test
    void laActualizacionDesdeV16ConservaLosDatosYNoInventaAptosNiMetadatos() {
        assertThat(contar("documento")).isEqualTo(documentosAntes);
        assertThat(contar("operacion_carga_documental")).isEqualTo(operacionesAntes);
        assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.documento WHERE tipo='APTO_FISICO'"
                + " OR fecha_emision IS NOT NULL OR medico_nombre IS NOT NULL OR medico_matricula IS NOT NULL")
                .query(Long.class).single()).isZero();
        assertThat(contar("apto_fisico")).isZero();
    }

    @Test
    void unAptoValidoSeGuardaConSusMetadatos() {
        var id = apto(atleta, temporada);
        assertThat(jdbc.sql("SELECT medico_matricula FROM gestion_patin.documento WHERE id=:i").param("i", id)
                .query(String.class).single()).isEqualTo("MP 0001");
    }

    @Test
    void unAptoSinMetadatosOSinTemporadaSeRechaza() {
        rechazar("23514", () -> apto(escuela, atleta, temporada, true, null, "MP 0001", "2026-03-01"));
        rechazar("23514", () -> apto(escuela, atleta, temporada, true, "Medico Sintetico", null, "2026-03-01"));
        rechazar("23514", () -> apto(escuela, atleta, temporada, true, "Medico Sintetico", "MP 0001", null));
        rechazar("23514", () -> apto(escuela, atleta, null, true, "Medico Sintetico", "MP 0001", "2026-03-01"));
    }

    @Test
    void elAptoEsDelDeportistaNuncaDeUnTutor() {
        rechazar("23514", () -> jdbc.sql("""
                INSERT INTO gestion_patin.documento(escuela_id,tutor_id,temporada_id,tipo,nombre_original,storage_key,
                  mime_type,tamanio_bytes,cargado_por,fecha_emision,medico_nombre,medico_matricula)
                VALUES(:e,:t,:temp,'APTO_FISICO','sintetico.pdf',:k,'application/pdf',10,:a,DATE '2026-03-01','M','1')
                """).param("e", escuela).param("t", tutor).param("temp", temporada).param("k", UUID.randomUUID().toString())
                .param("a", autor).update());
    }

    @Test
    void losMetadatosSonExclusivosDelApto() {
        rechazar("23514", () -> jdbc.sql("""
                INSERT INTO gestion_patin.documento(escuela_id,deportista_id,tipo,nombre_original,storage_key,
                  mime_type,tamanio_bytes,cargado_por,fecha_emision,medico_nombre,medico_matricula)
                VALUES(:e,:d,'OTRO','sintetico.pdf',:k,'application/pdf',10,:a,DATE '2026-03-01','M','1')
                """).param("e", escuela).param("d", atleta).param("k", UUID.randomUUID().toString())
                .param("a", autor).update());
    }

    @ParameterizedTest
    @ValueSource(strings = {" Medico", "Medico ", ""})
    void elNombreDelMedicoDebeVenirRecortadoYNoVacio(String nombre) {
        rechazar("23514", () -> apto(escuela, atleta, temporada, true, nombre, "MP 0001", "2026-03-01"));
    }

    @ParameterizedTest
    @ValueSource(strings = {" MP1", "MP1 ", ""})
    void laMatriculaDebeVenirRecortadaYNoVacia(String matricula) {
        rechazar("23514", () -> apto(escuela, atleta, temporada, true, "Medico Sintetico", matricula, "2026-03-01"));
    }

    @Test
    void lasLongitudesSeAcotanYLaFechaNoPuedeSerAnteriorA2000() {
        rechazar("22001", () -> apto(escuela, atleta, temporada, true, "M".repeat(151), "MP 0001", "2026-03-01"));
        rechazar("22001", () -> apto(escuela, atleta, temporada, true, "Medico Sintetico", "9".repeat(51), "2026-03-01"));
        rechazar("23514", () -> apto(escuela, atleta, temporada, true, "Medico Sintetico", "MP 0001", "1999-12-31"));
    }

    @Test
    void soloUnaSerieVigenteDeAptoPorDeportistaYTemporada() {
        apto(atleta, temporada);
        rechazar("23505", () -> apto(atleta, temporada));
        // otra temporada y otro deportista conviven; una versión histórica no cuenta
        apto(atleta, otraTemporada);
        apto(otroAtleta, temporada);
        apto(escuela, atleta, temporada, false, "Medico Sintetico", "MP 0001", "2026-02-01");
        assertThat(contar("documento")).isEqualTo(documentosAntes + 4);
    }

    @Test
    void elAptoDeOtraEscuelaNoSePuedeAtarAUnaTemporadaAjena() {
        rechazar("23503", () -> apto(otraEscuela, atleta, temporada, true, "Medico Sintetico", "MP 0001", "2026-03-01"));
    }

    @Test
    void corregirMetadatosDeUnAptoCargadoEsPosible() {
        var id = apto(atleta, temporada);
        assertThat(jdbc.sql("UPDATE gestion_patin.documento SET medico_matricula='MP 0002',version=version+1 WHERE id=:i")
                .param("i", id).update()).isEqualTo(1);
    }

    @Test
    void corregirMetadatosDeUnAptoAprobadoExigeRetirarLaAprobacionEnLaMismaSentencia() {
        var id = apto(atleta, temporada);
        jdbc.sql("UPDATE gestion_patin.documento SET estado='APROBADO',version=version+1 WHERE id=:i").param("i", id).update();
        rechazar("23514", () -> jdbc.sql(
                "UPDATE gestion_patin.documento SET medico_matricula='MP 0002',version=version+1 WHERE id=:i")
                .param("i", id).update());
        assertThat(jdbc.sql("UPDATE gestion_patin.documento SET medico_matricula='MP 0002',estado='CARGADO',"
                + "version=version+1 WHERE id=:i").param("i", id).update()).isEqualTo(1);
    }

    @Test
    void losMetadatosDeUnAptoObservadoOHistoricoNoSeEditan() {
        var observado = apto(atleta, temporada);
        jdbc.sql("UPDATE gestion_patin.documento SET estado='OBSERVADO',motivo_observacion='MOTIVO_FICTICIO',"
                + "version=version+1 WHERE id=:i").param("i", observado).update();
        rechazar("23514", () -> jdbc.sql("UPDATE gestion_patin.documento SET medico_matricula='MP 0002',estado='CARGADO',"
                + "version=version+1 WHERE id=:i").param("i", observado).update());
        var historico = apto(escuela, otroAtleta, temporada, false, "Medico Sintetico", "MP 0001", "2026-03-01");
        rechazar("23514", () -> jdbc.sql(
                "UPDATE gestion_patin.documento SET medico_matricula='MP 0002',version=version+1 WHERE id=:i")
                .param("i", historico).update());
    }

    @Test
    void laOperacionDeCargaAdmiteAptoSoloConMetadatosYTemporada() {
        String insertar = """
                INSERT INTO gestion_patin.operacion_carga_documental(escuela_id,solicitante_id,deportista_id,tipo,
                  temporada_id,storage_bucket,objeto_id,idempotencia,fecha_emision,medico_nombre,medico_matricula)
                VALUES(:e,:u,:d,'APTO_FISICO',CAST(:t AS uuid),'privado',:o,:i,CAST(:f AS date),:n,:m)
                """;
        java.util.function.BiFunction<UUID, String, Integer> intento = (temp, nombre) -> jdbc.sql(insertar)
                .param("e", escuela).param("u", autor).param("d", atleta).param("t", temp).param("o", UUID.randomUUID())
                .param("i", UUID.randomUUID()).param("f", "2026-03-01").param("n", nombre).param("m", "MP 0001").update();
        assertThat(intento.apply(temporada, "Medico Sintetico")).isEqualTo(1);
        rechazar("23514", () -> intento.apply(null, "Medico Sintetico"));
        rechazar("23514", () -> intento.apply(temporada, null));
    }

    @Test
    void laOperacionNoTransportaMetadatosParaOtrosTipos() {
        rechazar("23514", () -> jdbc.sql("""
                INSERT INTO gestion_patin.operacion_carga_documental(escuela_id,solicitante_id,deportista_id,tipo,
                  storage_bucket,objeto_id,idempotencia,medico_nombre)
                VALUES(:e,:u,:d,'OTRO','privado',:o,:i,'M')
                """).param("e", escuela).param("u", autor).param("d", atleta).param("o", UUID.randomUUID())
                .param("i", UUID.randomUUID()).update());
    }

    @Test
    void losMetadatosDeLaOperacionSonInmutablesUnaVezReservada() {
        var id = jdbc.sql("""
                INSERT INTO gestion_patin.operacion_carga_documental(escuela_id,solicitante_id,deportista_id,tipo,
                  temporada_id,storage_bucket,objeto_id,idempotencia,fecha_emision,medico_nombre,medico_matricula)
                VALUES(:e,:u,:d,'APTO_FISICO',:t,'privado',:o,:i,DATE '2026-03-01','Medico Sintetico','MP 0001') RETURNING id
                """).param("e", escuela).param("u", autor).param("d", atleta).param("t", temporada)
                .param("o", UUID.randomUUID()).param("i", UUID.randomUUID()).query(UUID.class).single();
        rechazar("23514", () -> jdbc.sql("UPDATE gestion_patin.operacion_carga_documental"
                + " SET medico_matricula='MP 0002',version=version+1 WHERE id=:i").param("i", id).update());
    }
}
