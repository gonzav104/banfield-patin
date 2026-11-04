package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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

/** SQL real en bases privadas PostgreSQL 17; no simula Storage ni afirma autorización/escaneo. */
@Tag("db")
class V6OperacionCargaDbTest {
    private String base;
    private DriverManagerDataSource ds;
    private JdbcClient jdbc;
    private UUID escuela, autor, atleta, tutor, temporada;
    private String documentosV5;

    @BeforeEach void preparar() {
        base = PostgresDescartable.crearBase("v6");
        ds = fuente(base);
        jdbc = JdbcClient.create(ds);
        migrar(ds, "5");
        var datos = new DatosDb(jdbc);
        escuela = datos.escuela("v6-" + UUID.randomUUID());
        autor = datos.admin(escuela, "sintetico@example.invalid", true);
        atleta = datos.deportista(escuela, "SINTETICO", "Prueba", "SQL");
        tutor = datos.tutor(escuela, datos.familia(escuela, "Familia sintética", true), "Tutor", "SQL");
        temporada = datos.temporada(escuela, 2026, false);
        jdbc.sql("""
            INSERT INTO gestion_patin.documento(escuela_id,deportista_id,tipo,nombre_original,storage_key,
              mime_type,tamanio_bytes,cargado_por) VALUES(:e,:d,'OTRO','sintetico.pdf',:k,'application/pdf',10,:a)
            """).param("e", escuela).param("d", atleta).param("k", UUID.randomUUID().toString()).param("a", autor).update();
        documentosV5 = snapshotDocumentos();
        migrar(ds, "latest");
    }
    @AfterEach void cerrar() { if (base != null) PostgresDescartable.eliminarBase(base); }
    private DriverManagerDataSource fuente(String nombre) {
        return new DriverManagerDataSource(PostgresDescartable.urlDeBase(nombre), PostgresDescartable.usuario(), PostgresDescartable.clave());
    }
    private int migrar(DriverManagerDataSource fuente, String version) {
        return Flyway.configure().dataSource(fuente).locations("classpath:db/migration").schemas("gestion_patin")
            .defaultSchema("gestion_patin").target(version).cleanDisabled(true).load().migrate().migrationsExecuted;
    }
    private UUID nueva(UUID solicitante, UUID deportista, UUID tutorId, String tipo, UUID temp, UUID idem, UUID objeto) {
        return jdbc.sql("""
            INSERT INTO gestion_patin.operacion_carga_documental(escuela_id,solicitante_id,deportista_id,tutor_id,
                tipo,temporada_id,storage_bucket,objeto_id,idempotencia)
            VALUES(:e,:u,CAST(:d AS uuid),CAST(:t AS uuid),:tipo,CAST(:s AS uuid),'privado',:o,:i) RETURNING id
            """).param("e", escuela).param("u", solicitante).param("d", deportista).param("t", tutorId)
            .param("tipo", tipo).param("s", temp).param("o", objeto).param("i", idem).query(UUID.class).single();
    }
    private UUID nueva() { return nueva(autor, atleta, null, "OTRO", null, UUID.randomUUID(), UUID.randomUUID()); }
    private int cambiar(UUID id, long version, String asignaciones, String filtro) {
        return jdbc.sql("UPDATE gestion_patin.operacion_carga_documental SET " + asignaciones
            + ",version=version+1 WHERE id=:id AND version=:v " + filtro).param("id", id).param("v", version).update();
    }
    private void cambiar(UUID id, String asignaciones) { assertThat(cambiar(id, version(id), asignaciones, "")).isEqualTo(1); }
    private long version(UUID id) {
        return jdbc.sql("SELECT version FROM gestion_patin.operacion_carga_documental WHERE id=:id").param("id", id).query(Long.class).single();
    }
    private void tomar(UUID id) { cambiar(id, "reserva_hasta=clock_timestamp()+interval '1 minute'"); }
    private void manifiesto(UUID id) {
        cambiar(id, "sha256_archivo=decode(repeat('ab',32),'hex'),mime_type='application/pdf',tamanio_bytes=10");
    }
    private void almacenada(UUID id) {
        tomar(id); manifiesto(id); cambiar(id, "estado='TRANSFIRIENDO'"); cambiar(id, "estado='ALMACENADA'");
    }
    private void documento(UUID id, String cambios) {
        jdbc.sql("""
            INSERT INTO gestion_patin.documento(id,escuela_id,deportista_id,tutor_id,tipo,temporada_id,
              nombre_original,storage_bucket,storage_key,mime_type,tamanio_bytes,cargado_por)
            SELECT id,escuela_id,deportista_id,tutor_id,tipo,temporada_id,'sintetico.pdf',storage_bucket,
              objeto_id::text,mime_type,tamanio_bytes,solicitante_id
            FROM gestion_patin.operacion_carga_documental WHERE id=:id
            """).param("id", id).update();
        if (!cambios.isEmpty()) jdbc.sql("UPDATE gestion_patin.documento SET " + cambios + " WHERE id=:id").param("id", id).update();
    }
    private void publicar(UUID id) { cambiar(id, "estado='PUBLICADA',documento_id=id,reserva_hasta=NULL"); }
    private String snapshotDocumentos() {
        return jdbc.sql("SELECT coalesce(jsonb_agg(to_jsonb(d) ORDER BY id),'[]'::jsonb)::text FROM gestion_patin.documento d").query(String.class).single();
    }
    private String snapshot() {
        return jdbc.sql("SELECT coalesce(jsonb_agg(to_jsonb(o) ORDER BY id),'[]'::jsonb)::text FROM gestion_patin.operacion_carga_documental o").query(String.class).single();
    }
    private void rechazar(String estadoSql, Runnable accion) {
        String antes = snapshot();
        String docsAntes = snapshotDocumentos();
        assertThatThrownBy(accion::run).isInstanceOfSatisfying(DataIntegrityViolationException.class,
            ex -> assertThat(((SQLException) ex.getMostSpecificCause()).getSQLState()).isEqualTo(estadoSql));
        assertThat(snapshot()).isEqualTo(antes);
        assertThat(snapshotDocumentos()).isEqualTo(docsAntes);
    }

    @Test void actualizaV5SinCambiarDocumentosYReintentoFlywayEsInocuo() {
        assertThat(snapshotDocumentos()).isEqualTo(documentosV5);
        assertThat(jdbc.sql("SELECT max(version::int) FROM gestion_patin.flyway_schema_history WHERE success").query(Integer.class).single()).isEqualTo(6);
        assertThat(migrar(ds, "latest")).isZero();
        UUID id = nueva();
        assertThat(version(id)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM pg_indexes WHERE schemaname='gestion_patin' AND tablename='operacion_carga_documental'").query(Integer.class).single()).isEqualTo(3);
    }
    @Test void migracionLimpia() {
        String limpia = PostgresDescartable.crearBase("v6_limpia");
        try { assertThat(migrar(fuente(limpia), "latest")).isEqualTo(6); }
        finally { PostgresDescartable.eliminarBase(limpia); }
    }
    @Test void admiteTutorOTROYSolicitudSinInventarAnualidad() {
        nueva(autor, null, tutor, "DNI_TUTOR_FRENTE", null, UUID.randomUUID(), UUID.randomUUID());
        nueva(autor, null, tutor, "OTRO", temporada, UUID.randomUUID(), UUID.randomUUID());
        for (int i = 0; i < 2; i++) nueva(autor, atleta, null, "CERTIFICADO_WADA", temporada, UUID.randomUUID(), UUID.randomUUID());
        nueva(autor, atleta, null, "SOLICITUD_LICENCIA", null, UUID.randomUUID(), UUID.randomUUID());
    }
    @Test void propietarioExclusivoYContextosDeEscuela() {
        rechazar("23514", () -> nueva(autor, null, null, "OTRO", null, UUID.randomUUID(), UUID.randomUUID()));
        rechazar("23514", () -> nueva(autor, atleta, tutor, "OTRO", null, UUID.randomUUID(), UUID.randomUUID()));
        var datos = new DatosDb(jdbc); UUID otra = datos.escuela("otra-" + UUID.randomUUID());
        UUID otroAutor = datos.admin(otra, "otro@example.invalid", true);
        UUID otroAtleta = datos.deportista(otra, "SINTETICO", "Otra", "Escuela");
        UUID otroTutor = datos.tutor(otra, datos.familia(otra, "Otra", true), "Tutor", "Otro");
        UUID otraTemporada = datos.temporada(otra, 2026, false);
        rechazar("23503", () -> nueva(otroAutor, atleta, null, "OTRO", null, UUID.randomUUID(), UUID.randomUUID()));
        rechazar("23503", () -> nueva(autor, otroAtleta, null, "OTRO", null, UUID.randomUUID(), UUID.randomUUID()));
        rechazar("23503", () -> nueva(autor, null, otroTutor, "OTRO", null, UUID.randomUUID(), UUID.randomUUID()));
        rechazar("23503", () -> nueva(autor, atleta, null, "OTRO", otraTemporada, UUID.randomUUID(), UUID.randomUUID()));
    }
    @ParameterizedTest @ValueSource(strings = {"DNI_TUTOR_FRENTE", "APTO_FISICO", "INVENTADO"})
    void rechazaTiposFueraDeContexto(String tipo) {
        rechazar("23514", () -> nueva(autor, atleta, null, tipo, null, UUID.randomUUID(), UUID.randomUUID()));
    }
    @Test void dniNoAdmiteTemporada() {
        rechazar("23514", () -> nueva(autor, atleta, null, "DNI_DEPORTISTA_FRENTE", temporada, UUID.randomUUID(), UUID.randomUUID()));
    }
    @Test void idempotenciaNoIncluyePropietarioYDestinoNoSeReutiliza() {
        UUID idem = UUID.randomUUID(), objeto = UUID.randomUUID();
        nueva(autor, atleta, null, "OTRO", null, idem, objeto);
        rechazar("23505", () -> nueva(autor, null, tutor, "OTRO", null, idem, UUID.randomUUID()));
        rechazar("23505", () -> nueva(autor, atleta, null, "OTRO", null, UUID.randomUUID(), objeto));
        UUID otroAutor = new DatosDb(jdbc).admin(escuela, "segundo@example.invalid", true);
        nueva(otroAutor, atleta, null, "OTRO", null, idem, UUID.randomUUID());
    }
    @ParameterizedTest @ValueSource(strings = {
        "sha256_archivo=decode(repeat('ab',32),'hex')", "tamanio_bytes=1", "mime_type='application/pdf'",
        "sha256_archivo=decode('ab','hex'),mime_type='application/pdf',tamanio_bytes=1",
        "sha256_archivo=decode(repeat('ab',32),'hex'),mime_type='text/plain',tamanio_bytes=1",
        "sha256_archivo=decode(repeat('ab',32),'hex'),mime_type='application/pdf',tamanio_bytes=0"})
    void manifiestoInvalidoNoCambiaLaFila(String valores) {
        UUID id = nueva(); tomar(id); rechazar("23514", () -> cambiar(id, valores));
    }
    @ParameterizedTest @ValueSource(strings = {"sha256_archivo=decode(repeat('cd',32),'hex')", "mime_type='image/png'", "tamanio_bytes=11",
        "sha256_archivo=NULL,mime_type=NULL,tamanio_bytes=NULL", "objeto_id=gen_random_uuid()", "idempotencia=gen_random_uuid()",
        "storage_bucket='otro'", "temporada_id=gen_random_uuid()", "tipo='SOLICITUD_LICENCIA'", "creado_en=creado_en+interval '1 second'"})
    void contextoYManifiestoFijadosSonInmutables(String cambio) {
        UUID id = nueva(); tomar(id); manifiesto(id); rechazar("23514", () -> cambiar(id, cambio));
    }
    @Test void versionExplicitaYFilasAfectadas() {
        UUID id = nueva(); tomar(id);
        rechazar("23514", () -> jdbc.sql("UPDATE gestion_patin.operacion_carga_documental SET estado='CERRADA',reserva_hasta=NULL WHERE id=:id").param("id", id).update());
        long actual = version(id);
        assertThat(cambiar(id, actual - 1, "estado='CERRADA',reserva_hasta=NULL", "")).isZero();
        assertThat(version(id)).isEqualTo(actual);
        rechazar("23514", () -> jdbc.sql("UPDATE gestion_patin.operacion_carga_documental SET version=version+2 WHERE id=:id").param("id", id).update());
        cambiar(id, "estado='CERRADA',reserva_hasta=NULL");
        rechazar("23514", () -> cambiar(id, "estado='RESERVADA'"));
    }
    @Test void vencimientoNoDemuestraFinDelPostYRechazaWorkerTardio() {
        UUID id = nueva(); tomar(id); manifiesto(id); cambiar(id, "estado='TRANSFIRIENDO'");
        long tardia = version(id);
        // El dueño renuncia a su reserva; no simula éxito ni usa sleeps para vencerla.
        cambiar(id, "reserva_hasta=clock_timestamp()-interval '1 second'");
        rechazar("23514", () -> cambiar(id, "reserva_hasta=clock_timestamp()+interval '1 minute'"));
        rechazar("23514", () -> cambiar(id, "estado='ALMACENADA'"));
        cambiar(id, "estado='RECONCILIANDO',reserva_hasta=clock_timestamp()+interval '1 minute'");
        assertThat(cambiar(id, tardia, "estado='ALMACENADA'", "")).isZero();
        cambiar(id, "estado='ALMACENADA'");
    }
    @ParameterizedTest @ValueSource(strings = {"PUBLICADA", "ALMACENADA", "RECONCILIANDO", "INVENTADO"})
    void noSaltaDesdeReservada(String estado) {
        UUID id = nueva(); tomar(id); manifiesto(id);
        rechazar("23514", () -> cambiar(id, "estado='" + estado + "'"));
    }
    @ParameterizedTest @ValueSource(strings = {"storage_key='otro'", "storage_bucket='otro'", "mime_type='image/png'", "tamanio_bytes=11",
        "cargado_por=(SELECT id FROM gestion_patin.usuario WHERE email='otro@example.invalid')", "estado='APROBADO'", "vigente=false"})
    void publicacionRechazaDocumentoIncompatible(String cambios) {
        new DatosDb(jdbc).admin(escuela, "otro@example.invalid", true);
        UUID id = nueva(); almacenada(id); documento(id, cambios);
        rechazar("23514", () -> publicar(id));
    }
    @Test void temporadaNullableYPropietarioAlPublicar() {
        UUID id = nueva(); almacenada(id); documento(id, "");
        publicar(id);
        rechazar("23514", () -> cambiar(id, "estado='RECONCILIANDO',documento_id=NULL"));
        UUID temporal = nueva(autor, null, tutor, "OTRO", temporada, UUID.randomUUID(), UUID.randomUUID());
        almacenada(temporal); documento(temporal, ""); publicar(temporal);
        UUID distinta = nueva(); almacenada(distinta);
        jdbc.sql("INSERT INTO gestion_patin.documento(id,escuela_id,deportista_id,tipo,temporada_id,nombre_original,storage_bucket,storage_key,mime_type,tamanio_bytes,cargado_por) "
            + "SELECT id,escuela_id,deportista_id,tipo,:s,'sintetico.pdf',storage_bucket,objeto_id::text,mime_type,tamanio_bytes,solicitante_id FROM gestion_patin.operacion_carga_documental WHERE id=:id")
            .param("s", temporada).param("id", distinta).update();
        rechazar("23514", () -> publicar(distinta));
    }
    @Test void sinDocumentoYConOtroIdNoSePublica() {
        UUID id = nueva(); almacenada(id); rechazar("23514", () -> publicar(id));
        documento(id, "");
        UUID otro = nueva(); almacenada(otro);
        rechazar("23514", () -> cambiar(otro, "estado='PUBLICADA',documento_id='" + id + "',reserva_hasta=NULL"));
    }
    @Test void reservaNoEsPermisoFamiliarNiPruebaDeArchivo() {
        UUID id = nueva();
        rechazar("23514", () -> cambiar(id, "estado='TRANSFIRIENDO'"));
        tomar(id); rechazar("23514", () -> cambiar(id, "estado='TRANSFIRIENDO'"));
        manifiesto(id); cambiar(id, "estado='TRANSFIRIENDO'");
        cambiar(id, "estado='RECONCILIANDO'"); cambiar(id, "estado='CERRADA',reserva_hasta=NULL");
        assertThat(snapshotDocumentos()).isEqualTo(documentosV5);
    }
    @Test void commitYRollbackSeRecuperanDesdeOtraConexion() throws Exception {
        UUID id = nueva(); almacenada(id);
        for (boolean commit : List.of(false, true)) {
            try (Connection c = ds.getConnection()) {
                c.setAutoCommit(false);
                try (var s = c.prepareStatement("INSERT INTO gestion_patin.documento(id,escuela_id,deportista_id,tutor_id,tipo,temporada_id,nombre_original,storage_bucket,storage_key,mime_type,tamanio_bytes,cargado_por) SELECT id,escuela_id,deportista_id,tutor_id,tipo,temporada_id,'sintetico.pdf',storage_bucket,objeto_id::text,mime_type,tamanio_bytes,solicitante_id FROM gestion_patin.operacion_carga_documental WHERE id=?")) {
                    s.setObject(1,id); assertThat(s.executeUpdate()).isEqualTo(1);
                }
                try (var s = c.prepareStatement("UPDATE gestion_patin.operacion_carga_documental SET estado='PUBLICADA',documento_id=id,reserva_hasta=NULL,version=version+1 WHERE id=? AND version=?")) {
                    s.setObject(1,id); s.setLong(2,version(id)); assertThat(s.executeUpdate()).isEqualTo(1);
                }
                if (commit) c.commit(); else c.rollback();
            }
            assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.documento WHERE id=:id").param("id",id).query(Integer.class).single()).isEqualTo(commit ? 1 : 0);
            try (Connection nueva=ds.getConnection();var st=nueva.prepareStatement("SELECT estado,documento_id FROM gestion_patin.operacion_carga_documental WHERE id=?")) {
                st.setObject(1,id);try(var r=st.executeQuery()) {
                    r.next();assertThat(r.getString(1)).isEqualTo(commit ? "PUBLICADA" : "ALMACENADA");
                    assertThat(r.getObject(2)).isEqualTo(commit ? id : null);
                }
            }
        }
    }
    @Test void dosConexionesCompitenPorUnaReservaConCAS() throws Exception {
        UUID id = nueva(); long esperada = version(id);
        var preparados = new CountDownLatch(2); var salir = new CountDownLatch(1);
        try (var tareas = Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<Integer> tomar = () -> {
                try (Connection c = ds.getConnection(); var s = c.prepareStatement("UPDATE gestion_patin.operacion_carga_documental SET reserva_hasta=clock_timestamp()+interval '1 minute',version=version+1 WHERE id=? AND version=? AND (reserva_hasta IS NULL OR reserva_hasta<=clock_timestamp())")) {
                    s.setObject(1,id); s.setLong(2,esperada); preparados.countDown();
                    assertThat(salir.await(5,TimeUnit.SECONDS)).isTrue(); return s.executeUpdate();
                }
            };
            var a=tareas.submit(tomar); var b=tareas.submit(tomar);
            assertThat(preparados.await(5,TimeUnit.SECONDS)).isTrue(); salir.countDown();
            assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(1,0);
        }
        assertThat(version(id)).isEqualTo(1);
    }
    @Test void dosConexionesCompitenPorLaMismaIdempotencia() throws Exception {
        UUID idem=UUID.randomUUID(); var preparados=new CountDownLatch(2); var salir=new CountDownLatch(1);
        try(var tareas=Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<String> insertar=()-> {
                try(Connection c=ds.getConnection();var s=c.prepareStatement("INSERT INTO gestion_patin.operacion_carga_documental(escuela_id,solicitante_id,deportista_id,tipo,storage_bucket,idempotencia) VALUES(?,?,?,'OTRO','privado',?)")) {
                    s.setObject(1,escuela);s.setObject(2,autor);s.setObject(3,atleta);s.setObject(4,idem);preparados.countDown();
                    assertThat(salir.await(5,TimeUnit.SECONDS)).isTrue();s.executeUpdate();return "OK";
                } catch(SQLException e) {return e.getSQLState();}
            };
            var a=tareas.submit(insertar);var b=tareas.submit(insertar);
            assertThat(preparados.await(5,TimeUnit.SECONDS)).isTrue();salir.countDown();
            assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder("OK","23505");
        }
    }
    @ParameterizedTest @ValueSource(strings={"propietario","tipo","temporada","escuela","historia"})
    void publicacionComparaTodoElContextoInclusoHistoria(String variante) {
        UUID id=nueva(autor,atleta,null,"OTRO",temporada,UUID.randomUUID(),UUID.randomUUID());almacenada(id);
        UUID otraEscuela=new DatosDb(jdbc).escuela("cruce-"+UUID.randomUUID());
        UUID otroAutor=new DatosDb(jdbc).admin(otraEscuela,"otro@example.invalid",true);
        UUID otroAtleta=new DatosDb(jdbc).deportista(otraEscuela,"SINTETICO","Otro","Contexto");
        String e=variante.equals("escuela") ? "'"+otraEscuela+"'::uuid" : "escuela_id";
        String u=variante.equals("escuela") ? "'"+otroAutor+"'::uuid" : "solicitante_id";
        String d=variante.equals("escuela") ? "'"+otroAtleta+"'::uuid" : variante.equals("propietario") ? "NULL::uuid" : "deportista_id";
        String t=variante.equals("propietario") ? "'"+tutor+"'::uuid" : "tutor_id";
        String tipo=variante.equals("tipo") ? "'SOLICITUD_LICENCIA'" : "tipo";
        String temp=variante.equals("escuela") ? "\'"+new DatosDb(jdbc).temporada(otraEscuela,2026,false)+"\'::uuid" : variante.equals("temporada") ? "NULL::uuid" : "temporada_id";
        String ant="NULL::uuid";
        if(variante.equals("historia")) {
            UUID raiz=jdbc.sql("INSERT INTO gestion_patin.documento(escuela_id,deportista_id,tipo,temporada_id,nombre_original,storage_key,mime_type,tamanio_bytes,cargado_por,vigente) VALUES(:e,:d,'OTRO',:s,'sintetico.pdf',:k,'application/pdf',10,:a,false) RETURNING id")
                .param("e",escuela).param("d",atleta).param("s",temporada).param("k",UUID.randomUUID().toString()).param("a",autor).query(UUID.class).single();
            ant="'"+raiz+"'::uuid";
        }
        jdbc.sql("INSERT INTO gestion_patin.documento(id,escuela_id,deportista_id,tutor_id,tipo,temporada_id,documento_anterior_id,nombre_original,storage_bucket,storage_key,mime_type,tamanio_bytes,cargado_por) SELECT id,"+e+","+d+","+t+","+tipo+","+temp+","+ant+",'sintetico.pdf',storage_bucket,objeto_id::text,mime_type,tamanio_bytes,"+u+" FROM gestion_patin.operacion_carga_documental WHERE id=:id").param("id",id).update();
        rechazar("23514",()->publicar(id));
    }
    @ParameterizedTest @ValueSource(strings={"RESERVADA","TRANSFIRIENDO","PUBLICADA"})
    void noRetrocedeNiPublicaSinDocumento(String destino) {
        UUID id=nueva();almacenada(id);
        rechazar("23514",()->cambiar(id,"estado='"+destino+"'"));
    }
    @Test void soloNaceReservadaConVersionCeroYNoSeOlvidaLaIdempotencia() {
        for(String campos:List.of("'ALMACENADA',0","'RESERVADA',1")) {
            rechazar("23514",()->jdbc.sql("INSERT INTO gestion_patin.operacion_carga_documental(escuela_id,solicitante_id,deportista_id,tipo,storage_bucket,idempotencia,estado,version) VALUES(:e,:a,:d,'OTRO','privado',:i,"+campos+")")
                .param("e",escuela).param("a",autor).param("d",atleta).param("i",UUID.randomUUID()).update());
        }
        UUID id=nueva();tomar(id);cambiar(id,"estado='CERRADA',reserva_hasta=NULL");
        rechazar("23514",()->jdbc.sql("DELETE FROM gestion_patin.operacion_carga_documental WHERE id=:id").param("id",id).update());
    }

    @Test void dosPublicadoresConVersionEsperadaNoConfirmanDosVeces() throws Exception {
        UUID id=nueva();almacenada(id);documento(id,"");long esperada=version(id);
        var listos=new CountDownLatch(2);var salir=new CountDownLatch(1);
        try(var tareas=Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<Integer> publicar=()->{
                try(Connection c=ds.getConnection();var st=c.prepareStatement("UPDATE gestion_patin.operacion_carga_documental SET estado='PUBLICADA',documento_id=id,reserva_hasta=NULL,version=version+1 WHERE id=? AND version=?")) {
                    st.setObject(1,id);st.setLong(2,esperada);listos.countDown();
                    assertThat(salir.await(5,TimeUnit.SECONDS)).isTrue();return st.executeUpdate();
                }
            };
            var a=tareas.submit(publicar);var b=tareas.submit(publicar);
            assertThat(listos.await(5,TimeUnit.SECONDS)).isTrue();salir.countDown();
            assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(1,0);
        }
        assertThat(version(id)).isEqualTo(esperada+1);
    }
    @ParameterizedTest @ValueSource(strings={"../privado","nombre personal","privado/otro"})
    void destinoNoAceptaRutas(String bucket) {
        rechazar("23514",()->jdbc.sql("INSERT INTO gestion_patin.operacion_carga_documental(escuela_id,solicitante_id,deportista_id,tipo,storage_bucket,idempotencia) VALUES(:e,:a,:d,'OTRO',:b,:i)")
            .param("e",escuela).param("a",autor).param("d",atleta).param("b",bucket).param("i",UUID.randomUUID()).update());
    }

}
