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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * V12 (U18): el predecesor de un reemplazo es parte durable e inmutable de la operación de carga y el
 * trigger de V6 pasa a exigir la cadena documental correcta. SQL real en una base privada PostgreSQL 17.
 */
@Tag("db")
class V12PredecesorOperacionDbTest {
    private static final String FRENTE = "DNI_DEPORTISTA_FRENTE";
    private static final String DORSO = "DNI_DEPORTISTA_DORSO";

    private String base;
    private DriverManagerDataSource ds;
    private JdbcClient jdbc;
    private UUID escuela, autor, atleta;
    private String operacionesV11, documentosV11;
    private UUID previaPublicada, previaPendiente;

    @BeforeEach
    void preparar() {
        base = PostgresDescartable.crearBase("v12");
        ds = new DriverManagerDataSource(PostgresDescartable.urlDeBase(base), PostgresDescartable.usuario(),
                PostgresDescartable.clave());
        jdbc = JdbcClient.create(ds);
        migrar("11");
        var datos = new DatosDb(jdbc);
        escuela = datos.escuela("v12-" + UUID.randomUUID());
        autor = datos.admin(escuela, "sintetico@example.invalid", true);
        atleta = datos.deportista(escuela, "SINTETICO-1", "Prueba", "Doce");
        // Estado previo a V12 (de otro deportista): una raíz publicada y una operación pendiente, que V12 no debe alterar.
        UUID previo = datos.deportista(escuela, "SINTETICO-0", "Previo", "Doce");
        previaPublicada = operacionV11(previo, FRENTE);
        almacenada(previaPublicada);
        documentoRaiz(previaPublicada);
        cambiar(previaPublicada, "estado='PUBLICADA',documento_id=id,reserva_hasta=NULL");
        previaPendiente = operacionV11(previo, DORSO);
        operacionesV11 = snapshotOperaciones();
        documentosV11 = snapshotDocumentos();
        migrar("12");
    }

    @AfterEach
    void cerrar() {
        if (base != null) PostgresDescartable.eliminarBase(base);
    }

    private int migrar(String version) {
        return Flyway.configure().dataSource(ds).locations("classpath:db/migration").schemas("gestion_patin")
                .defaultSchema("gestion_patin").target(version).cleanDisabled(true).load().migrate().migrationsExecuted;
    }

    // ---- fixtures -------------------------------------------------------------------------------------------

    private UUID operacionV11(UUID deportista, String tipo) {
        return jdbc.sql("""
                INSERT INTO gestion_patin.operacion_carga_documental(escuela_id,solicitante_id,deportista_id,tipo,
                    storage_bucket,objeto_id,idempotencia)
                VALUES(:e,:u,:d,:t,'privado',:o,:i) RETURNING id
                """).param("e", escuela).param("u", autor).param("d", deportista).param("t", tipo)
                .param("o", UUID.randomUUID()).param("i", UUID.randomUUID()).query(UUID.class).single();
    }

    private UUID operacion(UUID escuelaId, UUID solicitante, UUID deportista, String tipo, UUID predecesor) {
        return jdbc.sql("""
                INSERT INTO gestion_patin.operacion_carga_documental(escuela_id,solicitante_id,deportista_id,tipo,
                    storage_bucket,objeto_id,idempotencia,predecesor_id)
                VALUES(:e,:u,:d,:t,'privado',:o,:i,CAST(:p AS uuid)) RETURNING id
                """).param("e", escuelaId).param("u", solicitante).param("d", deportista).param("t", tipo)
                .param("o", UUID.randomUUID()).param("i", UUID.randomUUID()).param("p", predecesor)
                .query(UUID.class).single();
    }

    private UUID operacion(String tipo, UUID predecesor) {
        return operacion(escuela, autor, atleta, tipo, predecesor);
    }

    private int cambiar(UUID id, String asignaciones) {
        long version = jdbc.sql("SELECT version FROM gestion_patin.operacion_carga_documental WHERE id=:id")
                .param("id", id).query(Long.class).single();
        return jdbc.sql("UPDATE gestion_patin.operacion_carga_documental SET " + asignaciones
                + ",version=version+1 WHERE id=:id AND version=:v").param("id", id).param("v", version).update();
    }

    private void almacenada(UUID id) {
        cambiar(id, "reserva_hasta=clock_timestamp()+interval '1 minute'");
        cambiar(id, "sha256_archivo=decode(repeat('ab',32),'hex'),mime_type='image/png',tamanio_bytes=10");
        cambiar(id, "estado='TRANSFIRIENDO'");
        cambiar(id, "estado='ALMACENADA'");
    }

    private void documentoRaiz(UUID operacion) {
        insertarDocumento(operacion, null);
    }

    private void insertarDocumento(UUID operacion, UUID anterior) {
        jdbc.sql("""
                INSERT INTO gestion_patin.documento(id,escuela_id,deportista_id,tipo,nombre_original,storage_bucket,
                    storage_key,mime_type,tamanio_bytes,cargado_por,documento_anterior_id)
                SELECT id,escuela_id,deportista_id,tipo,'documento',storage_bucket,objeto_id::text,mime_type,
                    tamanio_bytes,solicitante_id,CAST(:a AS uuid)
                FROM gestion_patin.operacion_carga_documental WHERE id=:id
                """).param("id", operacion).param("a", anterior).update();
    }

    private UUID observado(UUID deportista, String tipo) {
        return jdbc.sql("""
                INSERT INTO gestion_patin.documento(escuela_id,deportista_id,tipo,nombre_original,storage_key,
                    mime_type,tamanio_bytes,estado,motivo_observacion,cargado_por)
                VALUES(:e,:d,:t,'documento',:k,'image/png',10,'OBSERVADO','Imagen ilegible',:a) RETURNING id
                """).param("e", escuela).param("d", deportista).param("t", tipo)
                .param("k", UUID.randomUUID().toString()).param("a", autor).query(UUID.class).single();
    }

    private void retirarVigencia(UUID documento) {
        assertThat(jdbc.sql("UPDATE gestion_patin.documento SET vigente=false WHERE id=:id").param("id", documento)
                .update()).isEqualTo(1);
    }

    private void publicar(UUID id) {
        cambiar(id, "estado='PUBLICADA',documento_id=id,reserva_hasta=NULL");
    }

    /** El orden que seguirá U18: la vigencia del predecesor se retira ANTES de insertar al sucesor. */
    private void reemplazar(UUID operacion, UUID predecesor) {
        retirarVigencia(predecesor);
        insertarDocumento(operacion, predecesor);
        publicar(operacion);
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

    private void rechazarConMensaje(String mensaje, Runnable accion) {
        String operaciones = snapshotOperaciones();
        assertThatThrownBy(accion::run).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(mensaje);
        assertThat(snapshotOperaciones()).isEqualTo(operaciones);
    }

    // ---- pruebas --------------------------------------------------------------------------------------------

    @Test
    void migraSobreV11SinTocarLasOperacionesNiLosDocumentosPrevios() {
        assertThat(jdbc.sql("SELECT max(version::int) FROM gestion_patin.flyway_schema_history WHERE success")
                .query(Integer.class).single()).isEqualTo(12);
        assertThat(migrar("12")).isZero();
        // Mismo contenido de antes: solo aparece la columna nueva, nula en toda fila previa.
        assertThat(jdbc.sql("SELECT coalesce(jsonb_agg(to_jsonb(o) - 'predecesor_id' ORDER BY id),'[]'::jsonb)::text"
                + " FROM gestion_patin.operacion_carga_documental o").query(String.class).single())
                .isEqualTo(operacionesV11);
        assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.operacion_carga_documental WHERE predecesor_id IS NOT"
                + " NULL").query(Integer.class).single()).isZero();
        assertThat(snapshotDocumentos()).isEqualTo(documentosV11);
    }

    @Test
    void sinPredecesorElComportamientoDeV6SeConserva() {
        UUID id = operacion(DORSO, null);
        almacenada(id);
        documentoRaiz(id);
        publicar(id);
        assertThat(jdbc.sql("SELECT serie_id=id AND documento_anterior_id IS NULL FROM gestion_patin.documento "
                + "WHERE id=:id").param("id", id).query(Boolean.class).single()).isTrue();
        // Una publicación sin predecesor sigue sin admitir un documento con cadena.
        UUID observado = observado(atleta, FRENTE);
        UUID otra = operacion(FRENTE, null);
        almacenada(otra);
        retirarVigencia(observado);
        insertarDocumento(otra, observado);
        rechazar("23514", () -> publicar(otra));
    }

    @Test
    void reemplazoValidoHeredaLaSerieYConservaAlPredecesor() {
        UUID predecesor = observado(atleta, FRENTE);
        UUID serie = jdbc.sql("SELECT serie_id FROM gestion_patin.documento WHERE id=:id").param("id", predecesor)
                .query(UUID.class).single();
        UUID id = operacion(FRENTE, predecesor);
        almacenada(id);
        reemplazar(id, predecesor);

        var sucesor = jdbc.sql("SELECT serie_id,documento_anterior_id,estado,vigente FROM gestion_patin.documento "
                + "WHERE id=:id").param("id", id).query().singleRow();
        assertThat(sucesor).containsEntry("serie_id", serie).containsEntry("documento_anterior_id", predecesor)
                .containsEntry("estado", "CARGADO").containsEntry("vigente", true);
        var anterior = jdbc.sql("SELECT estado,motivo_observacion,vigente,serie_id FROM gestion_patin.documento "
                + "WHERE id=:id").param("id", predecesor).query().singleRow();
        assertThat(anterior).containsEntry("estado", "OBSERVADO").containsEntry("motivo_observacion", "Imagen ilegible")
                .containsEntry("vigente", false).containsEntry("serie_id", serie);
        assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.documento WHERE serie_id=:s AND vigente")
                .param("s", serie).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT estado FROM gestion_patin.operacion_carga_documental WHERE id=:id")
                .param("id", id).query(String.class).single()).isEqualTo("PUBLICADA");
    }

    @Test
    void elPredecesorDebeSerDelMismoDeportistaTipoYEscuela() {
        UUID otroAtleta = new DatosDb(jdbc).deportista(escuela, "SINTETICO-2", "Otro", "Doce");
        UUID ajeno = observado(otroAtleta, FRENTE);
        UUID dorso = observado(atleta, DORSO);
        rechazar("23514", () -> operacion(FRENTE, ajeno));
        rechazar("23514", () -> operacion(FRENTE, dorso));
        rechazar("23514", () -> operacion(FRENTE, UUID.randomUUID()));

        var datos = new DatosDb(jdbc);
        UUID otra = datos.escuela("otra-" + UUID.randomUUID());
        UUID otroAutor = datos.admin(otra, "otro@example.invalid", true);
        UUID atletaOtra = datos.deportista(otra, "SINTETICO-3", "Otra", "Escuela");
        UUID propio = observado(atleta, FRENTE);
        // El trigger BEFORE rechaza antes de que se evalúe la FK compuesta (que también lo impediría).
        rechazar("23514", () -> operacion(otra, otroAutor, atletaOtra, FRENTE, propio));
    }

    @Test
    void soloLosDniDelDeportistaAdmitenPredecesor() {
        UUID otro = jdbc.sql("""
                INSERT INTO gestion_patin.documento(escuela_id,deportista_id,tipo,nombre_original,storage_key,
                    mime_type,tamanio_bytes,cargado_por)
                VALUES(:e,:d,'OTRO','documento',:k,'image/png',10,:a) RETURNING id
                """).param("e", escuela).param("d", atleta).param("k", UUID.randomUUID().toString())
                .param("a", autor).query(UUID.class).single();
        rechazar("23514", () -> operacion("OTRO", otro));
    }

    @Test
    void elPredecesorEsInmutable() {
        UUID predecesor = observado(atleta, FRENTE);
        UUID otroPredecesor = observado(atleta, DORSO);
        UUID conPredecesor = operacion(FRENTE, predecesor);
        UUID sin = operacion(FRENTE, null);
        // Con la reserva activa, ninguna otra regla del trigger puede rechazar el cambio: solo la inmutabilidad.
        cambiar(conPredecesor, "reserva_hasta=clock_timestamp()+interval '1 minute'");
        cambiar(sin, "reserva_hasta=clock_timestamp()+interval '1 minute'");
        rechazarConMensaje("OPERACION_CONTEXTO_INMUTABLE", () -> cambiar(conPredecesor, "predecesor_id=NULL"));
        rechazarConMensaje("OPERACION_CONTEXTO_INMUTABLE",
                () -> cambiar(conPredecesor, "predecesor_id='" + otroPredecesor + "'"));
        rechazarConMensaje("OPERACION_CONTEXTO_INMUTABLE", () -> cambiar(sin, "predecesor_id='" + predecesor + "'"));
    }

    @Test
    void publicarUnReemplazoExigeUnPredecesorObservadoYSinVigencia() {
        // Predecesor que no está OBSERVADO (p. ej. un CARGADO al que alguien retiró la vigencia).
        UUID cargado = jdbc.sql("""
                INSERT INTO gestion_patin.documento(escuela_id,deportista_id,tipo,nombre_original,storage_key,
                    mime_type,tamanio_bytes,cargado_por)
                VALUES(:e,:d,:t,'documento',:k,'image/png',10,:a) RETURNING id
                """).param("e", escuela).param("d", atleta).param("t", FRENTE)
                .param("k", UUID.randomUUID().toString()).param("a", autor).query(UUID.class).single();
        UUID id = operacion(FRENTE, cargado);
        almacenada(id);
        retirarVigencia(cargado);
        insertarDocumento(id, cargado);
        rechazar("23514", () -> publicar(id));
    }

    @Test
    void elSucesorSinLaCadenaDeLaOperacionNoSePublica() {
        UUID predecesor = observado(atleta, FRENTE);
        UUID id = operacion(FRENTE, predecesor);
        almacenada(id);
        retirarVigencia(predecesor);
        documentoRaiz(id); // sin documento_anterior_id: no corresponde a la operación de reemplazo
        rechazar("23514", () -> publicar(id));
    }

    @Test
    void unaOperacionAbandonadaNoBloqueaOtraDelMismoPredecesor() {
        UUID predecesor = observado(atleta, FRENTE);
        UUID abandonada = operacion(FRENTE, predecesor); // queda RESERVADA para siempre
        UUID viva = operacion(FRENTE, predecesor);
        almacenada(viva);
        reemplazar(viva, predecesor);
        assertThat(jdbc.sql("SELECT estado FROM gestion_patin.operacion_carga_documental WHERE id=:id")
                .param("id", abandonada).query(String.class).single()).isEqualTo("RESERVADA");

        // La abandonada, si algún día la retoman, no puede publicar un segundo sucesor.
        almacenada(abandonada);
        rechazar("23505", () -> insertarDocumento(abandonada, predecesor));
    }

    @Test
    void elIndiceDeApoyoNoEsUnicoYNingunIndiceUnicoBloqueaPorPredecesor() {
        var indices = jdbc.sql("SELECT indexdef FROM pg_indexes WHERE schemaname='gestion_patin' AND tablename="
                + "'operacion_carga_documental' AND indexdef LIKE '%predecesor_id%'").query(String.class).list();
        assertThat(indices).hasSize(1);
        assertThat(indices.get(0)).contains("ix_operacion_predecesor").doesNotContain("UNIQUE");
    }

    @Test
    void siFallaLaPublicacionTodoElReemplazoSeRevierte() throws Exception {
        UUID predecesor = observado(atleta, FRENTE);
        UUID id = operacion(FRENTE, predecesor); // RESERVADA: no puede pasar a PUBLICADA
        String antes = snapshotDocumentos();
        try (var c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (var s = c.createStatement()) {
                s.executeUpdate("UPDATE gestion_patin.documento SET vigente=false WHERE id='" + predecesor + "'");
                s.executeUpdate("INSERT INTO gestion_patin.documento(id,escuela_id,deportista_id,tipo,nombre_original,"
                        + "storage_bucket,storage_key,mime_type,tamanio_bytes,cargado_por,documento_anterior_id) "
                        + "SELECT id,escuela_id,deportista_id,tipo,'documento',storage_bucket,objeto_id::text,"
                        + "'image/png',10,solicitante_id,'" + predecesor + "' FROM "
                        + "gestion_patin.operacion_carga_documental WHERE id='" + id + "'");
                assertThatThrownBy(() -> s.executeUpdate("UPDATE gestion_patin.operacion_carga_documental SET "
                        + "estado='PUBLICADA',documento_id=id,reserva_hasta=NULL,version=version+1 WHERE id='" + id
                        + "'")).isInstanceOf(SQLException.class);
            }
            c.rollback();
        }
        assertThat(snapshotDocumentos()).isEqualTo(antes);
    }
}
