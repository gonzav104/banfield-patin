package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Bases privadas dentro de PostgreSQL 17 descartable; SQL real, sin endpoints mutantes ni Storage. */
@Tag("db")
class V5IntegridadDocumentalDbTest {
    private String base;
    private DriverManagerDataSource ds;
    private JdbcClient jdbc;
    private DatosDb datos;
    private UUID escuela, autor, atleta, otroAtleta, tutor, temporada;

    @BeforeEach void prepararV4() {
        base = PostgresDescartable.crearBase("v5");
        ds = fuente(base);
        jdbc = JdbcClient.create(ds);
        datos = new DatosDb(jdbc);
        flyway(ds, "4").migrate();
        escuela = datos.escuela("v5-" + UUID.randomUUID());
        autor = datos.admin(escuela, "admin@example.invalid", true);
        atleta = datos.deportista(escuela, "FICTICIO-A", "Prueba", "A");
        otroAtleta = datos.deportista(escuela, "FICTICIO-B", "Prueba", "B");
        tutor = datos.tutor(escuela, datos.familia(escuela, "Familia ficticia", true), "Tutor", "Prueba");
        temporada = datos.temporada(escuela, 2026, false);
    }

    @AfterEach void limpiar() { PostgresDescartable.eliminarBase(base); }

    private DriverManagerDataSource fuente(String nombre) {
        return new DriverManagerDataSource(PostgresDescartable.urlDeBase(nombre),
                PostgresDescartable.usuario(), PostgresDescartable.clave());
    }
    private Flyway flyway(DriverManagerDataSource fuente, String version) {
        return Flyway.configure().dataSource(fuente).locations("classpath:db/migration")
                .schemas("gestion_patin").defaultSchema("gestion_patin").target(version).cleanDisabled(true).load();
    }
    private void migrar() { flyway(ds, "latest").migrate(); }

    private UUID documento(UUID d, UUID t, String tipo, UUID temp, UUID anterior, boolean vigente) {
        return jdbc.sql("""
                INSERT INTO gestion_patin.documento(escuela_id,deportista_id,tutor_id,tipo,temporada_id,
                    documento_anterior_id,vigente,nombre_original,storage_key,mime_type,tamanio_bytes,cargado_por)
                VALUES(:e,CAST(:d AS uuid),CAST(:t AS uuid),:tipo,CAST(:temp AS uuid),CAST(:ant AS uuid),
                    :v,'ficticio.pdf',:key,'application/pdf',100,:autor) RETURNING id
                """).param("e", escuela).param("d", d).param("t", t).param("tipo", tipo).param("temp", temp)
                .param("ant", anterior).param("v", vigente).param("key", "ficticio/" + UUID.randomUUID())
                .param("autor", autor).query(UUID.class).single();
    }
    private UUID otro(UUID anterior, boolean vigente) {
        return documento(atleta, null, "OTRO", null, anterior, vigente);
    }
    private String snapshot() {
        return jdbc.sql("SELECT coalesce(json_agg(d ORDER BY id)::text,'[]') FROM gestion_patin.documento d")
                .query(String.class).single();
    }
    private void rechazar(Runnable accion) {
        String antes = snapshot();
        assertThatThrownBy(accion::run).isInstanceOfSatisfying(DataIntegrityViolationException.class,
                e -> assertThat(((SQLException) e.getMostSpecificCause()).getSQLState())
                        .isIn("23503", "23505", "23514"));
        assertThat(snapshot()).isEqualTo(antes);
    }
    private void rechazarMigracion() {
        String antes = snapshot();
        assertThatThrownBy(this::migrar).isInstanceOf(FlywayException.class);
        assertThat(snapshot()).isEqualTo(antes);
        assertThat(jdbc.sql("SELECT max(version::int) FROM gestion_patin.flyway_schema_history WHERE success")
                .query(Integer.class).single()).isEqualTo(4);
        assertThat(jdbc.sql("SELECT count(*) FROM information_schema.columns WHERE table_schema='gestion_patin' "
                + "AND table_name='documento' AND column_name IN ('serie_id','version')")
                .query(Long.class).single()).isZero();
    }

    @Test void propietarioYCardinalidadDni() {
        migrar();
        for (String tipo : List.of("DNI_DEPORTISTA_FRENTE", "DNI_DEPORTISTA_DORSO")) {
            rechazar(() -> documento(null, tutor, tipo, null, null, true));
            rechazar(() -> documento(atleta, null, tipo, temporada, null, true));
            documento(atleta, null, tipo, null, null, true);
            rechazar(() -> documento(atleta, null, tipo, null, null, true));
        }
        for (String tipo : List.of("DNI_TUTOR_FRENTE", "DNI_TUTOR_DORSO")) {
            rechazar(() -> documento(atleta, null, tipo, null, null, true));
            rechazar(() -> documento(null, tutor, tipo, temporada, null, true));
            documento(null, tutor, tipo, null, null, true);
            rechazar(() -> documento(null, tutor, tipo, null, null, true));
        }
        rechazar(() -> documento(null, null, "OTRO", null, null, true));
        rechazar(() -> documento(atleta, tutor, "OTRO", null, null, true));
    }

    @Test void migracionLimpiaYBackfillConservaCadenasSinCambiarDatosPrevios() {
        String vacia = PostgresDescartable.crearBase("v5fresh");
        try { assertThat(flyway(fuente(vacia), "latest").migrate().migrationsExecuted).isEqualTo(5); }
        finally { PostgresDescartable.eliminarBase(vacia); }
        UUID raiz = otro(null, false), medio = otro(raiz, false), ultimo = otro(medio, true);
        UUID independiente = otro(null, true);
        var antes = jdbc.sql("SELECT to_jsonb(d) FROM gestion_patin.documento d ORDER BY id").query(String.class).list();
        migrar();
        // Comparación exacta de columnas V4, incluidos actualizado_en y valores Storage: sólo se agregan columnas.
        assertThat(jdbc.sql("SELECT to_jsonb(d)-'serie_id'-'version' FROM gestion_patin.documento d ORDER BY id")
                .query(String.class).list()).isEqualTo(antes);
        for (UUID id : List.of(raiz, medio, ultimo))
            assertThat(jdbc.sql("SELECT serie_id FROM gestion_patin.documento WHERE id=:id").param("id", id)
                    .query(UUID.class).single()).isEqualTo(raiz);
        assertThat(jdbc.sql("SELECT serie_id FROM gestion_patin.documento WHERE id=:id").param("id", independiente)
                .query(UUID.class).single()).isEqualTo(independiente);
        assertThat(jdbc.sql("SELECT sum(version) FROM gestion_patin.documento").query(Long.class).single()).isZero();
    }

    @Test void prechecksRechazanIncompatiblesSinReparar() {
        documento(null, tutor, "DNI_DEPORTISTA_FRENTE", null, null, true);
        rechazarMigracion();
    }
    @Test void prechecksRechazanDniConTemporada() {
        documento(atleta, null, "DNI_DEPORTISTA_FRENTE", temporada, null, true);
        rechazarMigracion();
    }
    @Test void prechecksRechazanVigentesDuplicados() {
        documento(atleta, null, "DNI_DEPORTISTA_FRENTE", null, null, true);
        documento(atleta, null, "DNI_DEPORTISTA_FRENTE", null, null, true);
        rechazarMigracion();
    }
    @ParameterizedTest
    @ValueSource(strings={"propietario","escuela","tipo","temporada","autorreferencia"})
    void prechecksRechazanContextoCruzado(String caso) {
        UUID raiz = otro(null, false);
        UUID hijo=otro(raiz,true);
        switch(caso) {
            case "propietario" -> jdbc.sql("UPDATE gestion_patin.documento SET deportista_id=:v WHERE id=:id")
                    .param("v",otroAtleta).param("id",hijo).update();
            case "escuela" -> {
                UUID e=datos.escuela("otra-"+UUID.randomUUID());
                UUID a=datos.admin(e,"otra@example.invalid",true), d=datos.deportista(e,"FICTICIO","Otra","Escuela");
                jdbc.sql("UPDATE gestion_patin.documento SET escuela_id=:e,cargado_por=:a,deportista_id=:d WHERE id=:id")
                        .param("e",e).param("a",a).param("d",d).param("id",hijo).update();
            }
            case "tipo" -> jdbc.sql("UPDATE gestion_patin.documento SET tipo='SOLICITUD_LICENCIA' WHERE id=:id").param("id",hijo).update();
            case "temporada" -> jdbc.sql("UPDATE gestion_patin.documento SET temporada_id=:v WHERE id=:id").param("v",temporada).param("id",hijo).update();
            case "autorreferencia" -> jdbc.sql("UPDATE gestion_patin.documento SET documento_anterior_id=id WHERE id=:id").param("id",hijo).update();
            default -> throw new IllegalArgumentException(caso);
        }
        rechazarMigracion();
    }
    @Test void prechecksRechazanBifurcaciones() {
        UUID raiz = otro(null, false); otro(raiz, false); otro(raiz, true);
        rechazarMigracion();
    }
    @Test void prechecksRechazanCiclosSinRaiz() {
        UUID a = otro(null, false), b = otro(a, true);
        jdbc.sql("UPDATE gestion_patin.documento SET documento_anterior_id=:b WHERE id=:a")
                .param("a", a).param("b", b).update();
        rechazarMigracion();
    }
    @Test void prechecksRechazanDosVigentesDentroDeCadena() {
        UUID a = otro(null, true); otro(a, true); rechazarMigracion();
    }

    @Test void contextoHistoricoInmutableYPredecesorCompatible() {
        migrar();
        UUID a = otro(null, false), b = otro(a, false);
        rechazar(() -> documento(otroAtleta, null, "OTRO", null, b, true));
        rechazar(() -> documento(null, tutor, "OTRO", null, b, true));
        rechazar(() -> documento(atleta, null, "SOLICITUD_LICENCIA", null, b, true));
        rechazar(() -> documento(atleta, null, "OTRO", temporada, b, true));
        UUID temporal = documento(atleta, null, "OTRO", temporada, null, false);
        rechazar(() -> otro(temporal, true));
        UUID escuelaOriginal = escuela, autorOriginal = autor, atletaOriginal = atleta;
        escuela = datos.escuela("cruzada-" + UUID.randomUUID());
        autor = datos.admin(escuela, "otra@example.invalid", true);
        atleta = datos.deportista(escuela, "FICTICIO-A", "Otra", "Escuela");
        rechazar(() -> otro(a, true));
        escuela = escuelaOriginal; autor = autorOriginal; atleta = atletaOriginal;
        rechazar(() -> otro(a, true)); // bifurcación
        for (String asignacion : List.of("documento_anterior_id=id", "serie_id=gen_random_uuid()",
                "id=gen_random_uuid()", "deportista_id='" + otroAtleta + "'", "temporada_id='" + temporada + "'",
                "tipo='SOLICITUD_LICENCIA'", "tutor_id='" + tutor + "',deportista_id=NULL"))
            rechazar(() -> jdbc.sql("UPDATE gestion_patin.documento SET " + asignacion + " WHERE id=:id").param("id", a).update());
        rechazar(() -> jdbc.sql("UPDATE gestion_patin.documento SET documento_anterior_id=:a WHERE id=:id")
                .param("id", a).param("a", b).update());
        rechazar(() -> jdbc.sql("UPDATE gestion_patin.documento SET version=-1 WHERE id=:id").param("id", a).update());
    }

    @Test void multiplicidadOtroYSinAnualidadNiReglasApto() {
        migrar();
        for (String tipo : List.of("OTRO", "SOLICITUD_LICENCIA", "CERTIFICADO_WADA", "APTO_FISICO"))
            for (UUID temp : new UUID[]{null, temporada}) {
                documento(atleta, null, tipo, temp, null, true);
                documento(atleta, null, tipo, temp, null, true);
            }
        UUID a = otro(null, true), b = otro(a, false);
        rechazar(() -> jdbc.sql("UPDATE gestion_patin.documento SET vigente=true WHERE id=:id").param("id", b).update());
    }

    @Test void insercionesConSerieExplicitaNoPuedenMezclarCadenasNiReferirseASiMismas() throws Exception {
        migrar(); UUID a=otro(null,false), b=otro(null,false);
        for(UUID anterior:new UUID[]{null,a})
            rechazar(()->jdbc.sql("""
                    INSERT INTO gestion_patin.documento(escuela_id,deportista_id,tipo,documento_anterior_id,serie_id,
                      nombre_original,storage_key,mime_type,tamanio_bytes,cargado_por)
                    VALUES(:e,:d,'OTRO',CAST(:a AS uuid),:s,'ficticio.pdf',:k,'application/pdf',100,:u)
                    """).param("e",escuela).param("d",atleta).param("a",anterior).param("s",b)
                    .param("k","ficticio/"+UUID.randomUUID()).param("u",autor).update());
        try(Connection c=ds.getConnection()) {
            UUID propio=UUID.randomUUID();
            assertThatThrownBy(()->insertar(c,propio,"OTRO",propio,true)).isInstanceOf(SQLException.class);
        }
    }

    private UUID insertar(Connection c, UUID id, String tipo, UUID anterior, boolean vigente) throws SQLException {
        try (var p = c.prepareStatement("""
                INSERT INTO gestion_patin.documento(id,escuela_id,deportista_id,tipo,documento_anterior_id,vigente,
                  nombre_original,storage_key,mime_type,tamanio_bytes,cargado_por)
                VALUES(?,?,?,?,?,?,'ficticio.pdf',?,'application/pdf',100,?)
                """)) {
            p.setObject(1,id); p.setObject(2,escuela); p.setObject(3,atleta); p.setString(4,tipo);
            p.setObject(5,anterior); p.setBoolean(6,vigente); p.setString(7,"ficticio/"+id); p.setObject(8,autor);
            p.executeUpdate(); return id;
        }
    }
    @FunctionalInterface interface Operacion { int ejecutar(Connection c) throws SQLException; }
    private int carrera(Operacion primera, Operacion segunda) throws Exception {
        try (Connection c1=ds.getConnection(); Connection c2=ds.getConnection()) {
            c1.setAutoCommit(false); c2.setAutoCommit(false);
            var j1 = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(c1,true));
            var j2 = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(c2,true));
            int pid1=j1.queryForObject("SELECT pg_backend_pid()",Integer.class), pid2=j2.queryForObject("SELECT pg_backend_pid()",Integer.class);
            j2.execute("SET LOCAL lock_timeout='10s'");
            primera.ejecutar(c1);
            CountDownLatch inicio=new CountDownLatch(1);
            CompletableFuture<Integer> pendiente=CompletableFuture.supplyAsync(()->{
                inicio.countDown();
                try { int filas=segunda.ejecutar(c2); c2.commit(); return filas; }
                catch(SQLException e) { try { c2.rollback(); } catch(SQLException ex) { e.addSuppressed(ex); }
                    if ("23505".equals(e.getSQLState())) return -1;
                    throw new IllegalStateException(e); }
            });
            try {
                assertThat(inicio.await(5,TimeUnit.SECONDS)).isTrue();
                long limite=System.nanoTime()+Duration.ofSeconds(5).toNanos(); boolean bloqueada=false;
                while(System.nanoTime()<limite && !pendiente.isDone()) {
                    bloqueada=jdbc.sql("SELECT :p1 = ANY(pg_blocking_pids(:p2))").param("p1",pid1).param("p2",pid2).query(Boolean.class).single();
                    if(bloqueada) break;
                }
                assertThat(bloqueada).as("segunda transacción bloqueada realmente por la primera").isTrue();
                c1.commit();
                return pendiente.get(10,TimeUnit.SECONDS);
            } finally { c1.rollback(); if(!pendiente.isDone()) pendiente.get(12,TimeUnit.SECONDS); }
        }
    }
    @Test void altasDniConcurrentesUnaSolaGana() throws Exception {
        migrar();
        assertThat(carrera(c->{insertar(c,UUID.randomUUID(),"DNI_DEPORTISTA_FRENTE",null,true);return 1;},
                c->{insertar(c,UUID.randomUUID(),"DNI_DEPORTISTA_FRENTE",null,true);return 1;})).isEqualTo(-1);
        assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.documento").query(Long.class).single()).isEqualTo(1);
    }
    @Test void sucesoresConcurrentesNoBifurcanAunqueNoSeanVigentes() throws Exception {
        migrar(); UUID a=otro(null,false);
        assertThat(carrera(c->{insertar(c,UUID.randomUUID(),"OTRO",a,false);return 1;},
                c->{insertar(c,UUID.randomUUID(),"OTRO",a,false);return 1;})).isEqualTo(-1);
        assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.documento WHERE documento_anterior_id=:a").param("a",a)
                .query(Long.class).single()).isEqualTo(1);
    }
    @Test void activacionesConcurrentesRespetanUnVigentePorSerie() throws Exception {
        migrar(); UUID a=otro(null,false), b=otro(a,false);
        assertThat(carrera(c->activar(c,a), c->activar(c,b))).isEqualTo(-1);
        assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.documento WHERE vigente").query(Long.class).single()).isEqualTo(1);
    }
    private int activar(Connection c,UUID id) throws SQLException {
        try(var p=c.prepareStatement("UPDATE gestion_patin.documento SET vigente=true WHERE id=?")) {
            p.setObject(1,id); return p.executeUpdate();
        }
    }
    @Test void soporteCasConcurrenteVersionObsoletaNoModifica() throws Exception {
        migrar(); UUID a=otro(null,true);
        Operacion cas=c->{try(var p=c.prepareStatement("UPDATE gestion_patin.documento SET version=version+1 WHERE id=? AND version=0")) {
            p.setObject(1,a);return p.executeUpdate();}};
        assertThat(carrera(cas,cas)).isZero();
        assertThat(jdbc.sql("SELECT version FROM gestion_patin.documento WHERE id=:a").param("a",a).query(Long.class).single()).isEqualTo(1);
    }
    @Test void falloAlInsertarSucesorRevierteDesactivacion() throws Exception {
        migrar(); UUID a=otro(null,true);
        String antes=snapshot();
        try(Connection c=ds.getConnection()) {
            c.setAutoCommit(false);
            try(var p=c.prepareStatement("UPDATE gestion_patin.documento SET vigente=false,version=version+1 WHERE id=?")) {
                p.setObject(1,a);p.executeUpdate();
            }
            assertThatThrownBy(()->insertar(c,UUID.randomUUID(),"SOLICITUD_LICENCIA",a,true)).isInstanceOf(SQLException.class);
            c.rollback();
        }
        assertThat(snapshot()).isEqualTo(antes);
    }
}
