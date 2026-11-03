package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DataIntegrityViolationException;

/** Regresiones derivadas de los probes independientes D2; PostgreSQL descartable, sin Storage. */
@Tag("db")
class V5CasosLimiteDbTest {
    String base;
    JdbcClient jdbc;
    UUID escuela,autor,tutor,temporada;
    @BeforeEach void preparar() {
        base=PostgresDescartable.crearBase("independiente");
        var ds=new DriverManagerDataSource(PostgresDescartable.urlDeBase(base),PostgresDescartable.usuario(),PostgresDescartable.clave());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").schemas("gestion_patin").defaultSchema("gestion_patin").load().migrate();
        jdbc=JdbcClient.create(ds);
        var datos=new DatosDb(jdbc);
        escuela=datos.escuela("independiente-"+UUID.randomUUID());
        autor=datos.admin(escuela,"independiente@example.invalid",true);
        tutor=datos.tutor(escuela,datos.familia(escuela,"Familia sintética",true),"Tutor","Sintético");
        temporada=datos.temporada(escuela,2026,false);
    }
    @AfterEach void cerrar() { PostgresDescartable.eliminarBase(base); }
    UUID insertar(UUID padre,UUID temp,String tipo) {
        return jdbc.sql("""
          INSERT INTO gestion_patin.documento(escuela_id,tutor_id,temporada_id,tipo,documento_anterior_id,
             nombre_original,storage_key,mime_type,tamanio_bytes,cargado_por,vigente)
          VALUES(:e,:t,CAST(:temp AS uuid),:tipo,CAST(:p AS uuid),'sintetico.pdf',:k,'application/pdf',1,:a,false) RETURNING id
          """).param("e",escuela).param("t",tutor).param("temp",temp).param("tipo",tipo).param("p",padre)
          .param("k","sintetico/"+UUID.randomUUID()).param("a",autor).query(UUID.class).single();
    }
    String snapshot() { return jdbc.sql("SELECT coalesce(jsonb_agg(to_jsonb(d) ORDER BY id),'[]'::jsonb)::text FROM gestion_patin.documento d").query(String.class).single(); }
    void rechazar(Runnable r) {
        String antes=snapshot();
        assertThatThrownBy(r::run).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(snapshot()).isEqualTo(antes);
    }
    @Test void cadenaTutorTemporalEIndependenciaDeMultiplesSeries() {
        UUID raiz=insertar(null,temporada,"OTRO"), hijo=insertar(raiz,temporada,"OTRO");
        for(int i=0;i<30;i++) insertar(null,temporada,"OTRO");
        assertThat(jdbc.sql("SELECT count(DISTINCT serie_id) FROM gestion_patin.documento").query(Long.class).single()).isEqualTo(31);
        assertThat(jdbc.sql("SELECT serie_id FROM gestion_patin.documento WHERE id=:id").param("id",hijo).query(UUID.class).single()).isEqualTo(raiz);
        rechazar(()->insertar(hijo,null,"OTRO"));
        rechazar(()->jdbc.sql("DELETE FROM gestion_patin.documento WHERE id=:id").param("id",raiz).update());
        rechazar(()->jdbc.sql("UPDATE gestion_patin.documento SET escuela_id=gen_random_uuid()").update());
        rechazar(()->jdbc.sql("UPDATE gestion_patin.documento SET temporada_id=NULL").update());
    }
    @Test void sentenciaMasivaInvalidaNoModificaNingunaFila() {
        UUID a=insertar(null,null,"OTRO"),b=insertar(null,null,"OTRO");
        rechazar(()->jdbc.sql("UPDATE gestion_patin.documento SET vigente=true,version=CASE WHEN id=:id THEN -1 ELSE 1 END")
            .param("id",b).update());
        assertThat(jdbc.sql("SELECT version FROM gestion_patin.documento WHERE id=:id").param("id",a).query(Long.class).single()).isZero();
    }
    @Test void ciclosEnInsertMasivoNoEludenVisibilidadDelPredecesor() {
        UUID a=UUID.randomUUID(), b=UUID.randomUUID();
        rechazar(()->jdbc.sql("""
           INSERT INTO gestion_patin.documento(id,escuela_id,tutor_id,tipo,documento_anterior_id,
             nombre_original,storage_key,mime_type,tamanio_bytes,cargado_por,vigente)
           VALUES(:a,:e,:t,'OTRO',:b,'sintetico.pdf',:ka,'application/pdf',1,:u,false),
                 (:b,:e,:t,'OTRO',:a,'sintetico.pdf',:kb,'application/pdf',1,:u,false)
           """).param("a",a).param("b",b).param("e",escuela).param("t",tutor).param("u",autor)
           .param("ka","sintetico/"+a).param("kb","sintetico/"+b).update());
    }
    @Test void aptoTutorNoRecibeRestriccionesNuevasDeFaseCuatro() {
        insertar(null,null,"APTO_FISICO"); insertar(null,temporada,"APTO_FISICO");
        assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.documento").query(Long.class).single()).isEqualTo(2);
    }
}
