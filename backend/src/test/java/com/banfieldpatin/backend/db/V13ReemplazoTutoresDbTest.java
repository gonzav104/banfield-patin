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

/**
 * V13 (U19): el predecesor de un reemplazo admite también los DNI de un tutor. El comportamiento de V12 para los DNI del
 * deportista no cambia. SQL real en una base privada PostgreSQL 17.
 */
@Tag("db")
class V13ReemplazoTutoresDbTest {
    private static final String T_FRENTE = "DNI_TUTOR_FRENTE";
    private static final String D_FRENTE = "DNI_DEPORTISTA_FRENTE";

    private String base;
    private DriverManagerDataSource ds;
    private JdbcClient jdbc;
    private UUID escuela, autor, atleta, tutor, otroTutor;
    private String operacionesV12, documentosV12;

    @BeforeEach
    void preparar() {
        base = PostgresDescartable.crearBase("v13");
        ds = new DriverManagerDataSource(PostgresDescartable.urlDeBase(base), PostgresDescartable.usuario(),
                PostgresDescartable.clave());
        jdbc = JdbcClient.create(ds);
        migrar("12");
        var datos = new DatosDb(jdbc);
        escuela = datos.escuela("v13-" + UUID.randomUUID());
        autor = datos.admin(escuela, "sintetico@example.invalid", true);
        atleta = datos.deportista(escuela, "SINTETICO-1", "Prueba", "Trece");
        UUID familia = datos.familia(escuela, "Familia sintética", true);
        tutor = datos.tutor(escuela, familia, "Tutor", "Uno");
        otroTutor = datos.tutor(escuela, familia, "Tutor", "Dos");
        // Estado previo a V13: una operación pendiente y una cadena de deportista ya reemplazada (V12).
        UUID observadoDeportista = observado(atleta, null, D_FRENTE);
        UUID op = operacion(D_FRENTE, atleta, null, observadoDeportista);
        almacenada(op);
        reemplazar(op, observadoDeportista, atleta, null);
        operacionesV12 = snapshotOperaciones();
        documentosV12 = snapshotDocumentos();
        migrar("13");
    }

    @AfterEach
    void cerrar() {
        if (base != null) PostgresDescartable.eliminarBase(base);
    }

    private int migrar(String version) {
        return Flyway.configure().dataSource(ds).locations("classpath:db/migration").schemas("gestion_patin")
                .defaultSchema("gestion_patin").target(version).cleanDisabled(true).load().migrate().migrationsExecuted;
    }

    private UUID operacion(String tipo, UUID deportista, UUID tutorId, UUID predecesor) {
        return jdbc.sql("""
                INSERT INTO gestion_patin.operacion_carga_documental(escuela_id,solicitante_id,deportista_id,tutor_id,tipo,
                    storage_bucket,objeto_id,idempotencia,predecesor_id)
                VALUES(:e,:u,CAST(:d AS uuid),CAST(:t AS uuid),:tipo,'privado',:o,:i,CAST(:p AS uuid)) RETURNING id
                """).param("e", escuela).param("u", autor).param("d", deportista).param("t", tutorId).param("tipo", tipo)
                .param("o", UUID.randomUUID()).param("i", UUID.randomUUID()).param("p", predecesor)
                .query(UUID.class).single();
    }

    private void cambiar(UUID id, String asignaciones) {
        long version = jdbc.sql("SELECT version FROM gestion_patin.operacion_carga_documental WHERE id=:id")
                .param("id", id).query(Long.class).single();
        assertThat(jdbc.sql("UPDATE gestion_patin.operacion_carga_documental SET " + asignaciones
                + ",version=version+1 WHERE id=:id AND version=:v").param("id", id).param("v", version).update())
                .isEqualTo(1);
    }

    private void almacenada(UUID id) {
        cambiar(id, "reserva_hasta=clock_timestamp()+interval '1 minute'");
        cambiar(id, "sha256_archivo=decode(repeat('ab',32),'hex'),mime_type='image/png',tamanio_bytes=10");
        cambiar(id, "estado='TRANSFIRIENDO'");
        cambiar(id, "estado='ALMACENADA'");
    }

    private UUID observado(UUID deportista, UUID tutorId, String tipo) {
        return jdbc.sql("""
                INSERT INTO gestion_patin.documento(escuela_id,deportista_id,tutor_id,tipo,nombre_original,storage_key,
                    mime_type,tamanio_bytes,estado,motivo_observacion,cargado_por)
                VALUES(:e,CAST(:d AS uuid),CAST(:t AS uuid),:tipo,'documento',:k,'image/png',10,'OBSERVADO',
                    'Imagen ilegible',:a) RETURNING id
                """).param("e", escuela).param("d", deportista).param("t", tutorId).param("tipo", tipo)
                .param("k", UUID.randomUUID().toString()).param("a", autor).query(UUID.class).single();
    }

    /** El orden de U18: la vigencia del predecesor se retira ANTES de insertar al sucesor; luego se publica. */
    private void reemplazar(UUID operacion, UUID predecesor, UUID deportista, UUID tutorId) {
        jdbc.sql("UPDATE gestion_patin.documento SET vigente=false WHERE id=:id").param("id", predecesor).update();
        insertarSucesor(operacion, predecesor);
        cambiar(operacion, "estado='PUBLICADA',documento_id=id,reserva_hasta=NULL");
    }

    private void insertarSucesor(UUID operacion, UUID predecesor) {
        jdbc.sql("""
                INSERT INTO gestion_patin.documento(id,escuela_id,deportista_id,tutor_id,tipo,nombre_original,
                    storage_bucket,storage_key,mime_type,tamanio_bytes,cargado_por,documento_anterior_id)
                SELECT id,escuela_id,deportista_id,tutor_id,tipo,'documento',storage_bucket,objeto_id::text,mime_type,
                    tamanio_bytes,solicitante_id,CAST(:a AS uuid)
                FROM gestion_patin.operacion_carga_documental WHERE id=:id
                """).param("id", operacion).param("a", predecesor).update();
    }

    private String snapshotOperaciones() {
        return jdbc.sql("SELECT coalesce(jsonb_agg(to_jsonb(o) ORDER BY id),'[]'::jsonb)::text FROM "
                + "gestion_patin.operacion_carga_documental o").query(String.class).single();
    }

    private String snapshotDocumentos() {
        return jdbc.sql("SELECT coalesce(jsonb_agg(to_jsonb(d) ORDER BY id),'[]'::jsonb)::text FROM "
                + "gestion_patin.documento d").query(String.class).single();
    }

    private void rechazar(String estadoSql, Runnable accion) {
        String operaciones = snapshotOperaciones();
        String documentos = snapshotDocumentos();
        assertThatThrownBy(accion::run).isInstanceOfSatisfying(DataIntegrityViolationException.class,
                ex -> assertThat(((SQLException) ex.getMostSpecificCause()).getSQLState()).isEqualTo(estadoSql));
        assertThat(snapshotOperaciones()).isEqualTo(operaciones);
        assertThat(snapshotDocumentos()).isEqualTo(documentos);
    }

    @Test
    void migraSobreV12SinTocarLasFilasPreviasYEsIdempotente() {
        assertThat(jdbc.sql("SELECT max(version::int) FROM gestion_patin.flyway_schema_history WHERE success")
                .query(Integer.class).single()).isEqualTo(13);
        assertThat(migrar("13")).isZero();
        assertThat(snapshotOperaciones()).isEqualTo(operacionesV12);
        assertThat(snapshotDocumentos()).isEqualTo(documentosV12);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DNI_TUTOR_FRENTE", "DNI_TUTOR_DORSO"})
    void reemplazoDeUnDniDeTutorHeredaLaSerieYConservaAlPredecesor(String tipo) {
        UUID predecesor = observado(null, tutor, tipo);
        UUID serie = jdbc.sql("SELECT serie_id FROM gestion_patin.documento WHERE id=:id").param("id", predecesor)
                .query(UUID.class).single();
        UUID op = operacion(tipo, null, tutor, predecesor);
        almacenada(op);
        reemplazar(op, predecesor, null, tutor);

        var sucesor = jdbc.sql("SELECT serie_id,documento_anterior_id,estado,vigente,tutor_id,deportista_id FROM "
                + "gestion_patin.documento WHERE id=:id").param("id", op).query().singleRow();
        assertThat(sucesor).containsEntry("serie_id", serie).containsEntry("documento_anterior_id", predecesor)
                .containsEntry("estado", "CARGADO").containsEntry("vigente", true).containsEntry("tutor_id", tutor)
                .containsEntry("deportista_id", null);
        var anterior = jdbc.sql("SELECT estado,motivo_observacion,vigente FROM gestion_patin.documento WHERE id=:id")
                .param("id", predecesor).query().singleRow();
        assertThat(anterior).containsEntry("estado", "OBSERVADO").containsEntry("motivo_observacion", "Imagen ilegible")
                .containsEntry("vigente", false);
        assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.documento WHERE serie_id=:s AND vigente")
                .param("s", serie).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void elReemplazoDeUnDniDelDeportistaSigueFuncionandoComoEnV12() {
        UUID predecesor = observado(atleta, null, "DNI_DEPORTISTA_DORSO");
        UUID op = operacion("DNI_DEPORTISTA_DORSO", atleta, null, predecesor);
        almacenada(op);
        reemplazar(op, predecesor, atleta, null);
        assertThat(jdbc.sql("SELECT documento_anterior_id FROM gestion_patin.documento WHERE id=:id").param("id", op)
                .query(UUID.class).single()).isEqualTo(predecesor);
    }

    @Test
    void elPredecesorDebeSerDelMismoPropietarioYTipo() {
        UUID deDos = observado(null, otroTutor, T_FRENTE);
        UUID deTutor = observado(null, tutor, "DNI_TUTOR_DORSO");
        UUID deDeportista = observado(atleta, null, "DNI_DEPORTISTA_DORSO");
        rechazar("23514", () -> operacion(T_FRENTE, null, tutor, deDos)); // otro tutor
        rechazar("23514", () -> operacion(T_FRENTE, null, tutor, deTutor)); // otro tipo
        rechazar("23514", () -> operacion(T_FRENTE, null, tutor, deDeportista)); // un documento de deportista
        rechazar("23514", () -> operacion("DNI_DEPORTISTA_DORSO", atleta, null, deTutor)); // el inverso
    }

    @Test
    void soloLosDniAdmitenPredecesor() {
        UUID otro = jdbc.sql("""
                INSERT INTO gestion_patin.documento(escuela_id,tutor_id,tipo,nombre_original,storage_key,mime_type,
                    tamanio_bytes,cargado_por)
                VALUES(:e,:t,'OTRO','documento',:k,'image/png',10,:a) RETURNING id
                """).param("e", escuela).param("t", tutor).param("k", UUID.randomUUID().toString()).param("a", autor)
                .query(UUID.class).single();
        rechazar("23514", () -> operacion("OTRO", null, tutor, otro));
    }

    @Test
    void publicarUnReemplazoDeTutorExigeUnPredecesorObservadoYSinVigencia() {
        UUID cargado = jdbc.sql("""
                INSERT INTO gestion_patin.documento(escuela_id,tutor_id,tipo,nombre_original,storage_key,mime_type,
                    tamanio_bytes,cargado_por)
                VALUES(:e,:t,:tipo,'documento',:k,'image/png',10,:a) RETURNING id
                """).param("e", escuela).param("t", tutor).param("tipo", T_FRENTE)
                .param("k", UUID.randomUUID().toString()).param("a", autor).query(UUID.class).single();
        UUID op = operacion(T_FRENTE, null, tutor, cargado);
        almacenada(op);
        jdbc.sql("UPDATE gestion_patin.documento SET vigente=false WHERE id=:id").param("id", cargado).update();
        insertarSucesor(op, cargado);
        rechazar("23514", () -> cambiar(op, "estado='PUBLICADA',documento_id=id,reserva_hasta=NULL"));
    }
}
