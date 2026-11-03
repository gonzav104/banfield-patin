package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import tools.jackson.databind.json.JsonMapper;

/** Recursos exclusivos del ensayo: nunca acepta una URL, nombre o credencial externos. */
final class V5OperacionSupport implements AutoCloseable {
    static final Path EVIDENCIAS = directorio();
    final PostgreSQLContainer pg = new PostgreSQLContainer(PostgresDescartable.IMAGEN)
            .withDatabaseName("banfield_test_v5ops").withUsername("banfield_test").withPassword("banfield_test");
    final DriverManagerDataSource ds;
    final JdbcClient jdbc;
    final JdbcTemplate sql;
    UUID escuela, atleta, tutor, autor, temporada;
    int documentos;

    V5OperacionSupport() {
        String docker = System.getenv("DOCKER_HOST");
        if (docker != null && !docker.isBlank() && !docker.startsWith("unix://")) {
            throw new IllegalStateException("El ensayo requiere Docker local por socket Unix");
        }
        if (!"unix".equals(DockerClientFactory.instance().getTransportConfig().getDockerHost().getScheme())) {
            throw new IllegalStateException("Se rechaza un motor Docker no local antes de crear el contenedor");
        }
        pg.start();
        try {
            DestinoDbPrueba.validarUrl(pg.getJdbcUrl());
            ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            jdbc = JdbcClient.create(ds);
            sql = new JdbcTemplate(ds);
            assertThat(texto("SHOW server_version")).startsWith("17.");
        } catch (RuntimeException | Error e) {
            pg.close();
            throw e;
        }
    }

    Flyway flyway(String destino, String inicio) {
        return Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                .schemas("gestion_patin").defaultSchema("gestion_patin").target(destino)
                .initSql(inicio).cleanDisabled(true).load();
    }

    void prepararV4() {
        assertThat(flyway("4", "").migrate().migrationsExecuted).isEqualTo(4);
        var datos = new DatosDb(jdbc);
        for (int e = 0; e < 2; e++) {
            escuela = datos.escuela("v5-operativa-" + e);
            autor = datos.admin(escuela, "admin" + e + "@example.invalid", true);
            temporada = datos.temporada(escuela, 2027, false);
            for (int f = 0; f < 2; f++) {
                UUID familia = datos.familia(escuela, "Familia ficticia " + f, true);
                tutor = datos.tutor(escuela, familia, "Tutor ficticio", "Prueba");
                documento(null, tutor, "DNI_TUTOR_FRENTE", null, null, true);
                for (int a = 0; a < 2; a++) {
                    atleta = datos.deportista(escuela, "FICTICIO-" + f + "-" + a, "Atleta ficticio", "Prueba");
                    datos.vinculo(escuela, familia, atleta, "ACTIVO", true, autor);
                    datos.datosDeportivos(escuela, atleta, temporada, "Categoria ficticia", "0" + (f * 2 + a), "ACTIVO");
                    documento(atleta, null, "DNI_DEPORTISTA_FRENTE", null, null, true);
                    documento(atleta, null, "DNI_DEPORTISTA_DORSO", null, null, true);
                    UUID previo = null;
                    for (int v = 0; v < 4; v++) previo = documento(atleta, null, "OTRO", null, previo, v == 3);
                    documento(atleta, null, "OTRO", null, null, true);
                    documento(atleta, null, "SOLICITUD_LICENCIA", temporada, null, true);
                    documento(atleta, null, "CERTIFICADO_WADA", temporada, null, true);
                }
            }
        }
    }

    UUID documento(UUID d, UUID t, String tipo, UUID temp, UUID previo, boolean vigente) {
        UUID id = UUID.nameUUIDFromBytes(("v5-operativa-documento-" + documentos++).getBytes(StandardCharsets.UTF_8));
        return jdbc.sql("""
                INSERT INTO gestion_patin.documento(id,escuela_id,deportista_id,tutor_id,tipo,temporada_id,
                    documento_anterior_id,vigente,nombre_original,storage_key,mime_type,tamanio_bytes,cargado_por)
                VALUES(:id,:e,CAST(:d AS uuid),CAST(:t AS uuid),:tipo,CAST(:temp AS uuid),CAST(:ant AS uuid),
                    :v,'ficticio.pdf',:key,'application/pdf',100,:autor) RETURNING id
                """).param("id", id).param("e", escuela).param("d", d).param("t", t).param("tipo", tipo)
                .param("temp", temp).param("ant", previo).param("v", vigente)
                .param("key", "ficticio/" + id).param("autor", autor).query(UUID.class).single();
    }

    String texto(String query) { return jdbc.sql(query).query(String.class).single(); }
    long numero(String query) { return jdbc.sql(query).query(Long.class).single(); }

    List<String> tablas() {
        return jdbc.sql("SELECT tablename FROM pg_tables WHERE schemaname='gestion_patin' ORDER BY tablename")
                .query(String.class).list();
    }

    Map<String, List<String>> datos(boolean legado) {
        Map<String, List<String>> resultado = new TreeMap<>();
        for (String tabla : tablas()) {
            // Nombres leídos del catálogo del contenedor propio; no se acepta SQL externo.
            String nombre = '"' + tabla.replace("\"", "\"\"") + '"';
            String fila = legado && tabla.equals("documento") ? "to_jsonb(r)-'serie_id'-'version'" : "to_jsonb(r)";
            String filtro = legado && tabla.equals("flyway_schema_history") ? " WHERE version IS DISTINCT FROM '5'" : "";
            resultado.put(tabla, jdbc.sql("SELECT (" + fila + ")::text FROM gestion_patin." + nombre
                    + " r" + filtro + " ORDER BY 1").query(String.class).list());
        }
        return resultado;
    }

    Map<String, List<String>> catalogo() {
        Map<String, List<String>> resultado = new TreeMap<>();
        resultado.put("columnas", filas("""
                SELECT concat_ws('|',table_name,column_name,data_type,is_nullable,column_default)
                FROM information_schema.columns WHERE table_schema='gestion_patin' ORDER BY table_name,ordinal_position
                """));
        resultado.put("restricciones", filas("""
                SELECT concat_ws('|',conrelid::regclass::text,conname,convalidated,pg_get_constraintdef(oid))
                FROM pg_constraint WHERE connamespace='gestion_patin'::regnamespace ORDER BY 1
                """));
        resultado.put("indices", filas("""
                SELECT concat_ws('|',c.relname,i.indisvalid,i.indisready,pg_get_indexdef(i.indexrelid))
                FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid
                WHERE c.relnamespace='gestion_patin'::regnamespace ORDER BY 1
                """));
        resultado.put("triggers", filas("""
                SELECT concat_ws('|',t.tgname,t.tgenabled,pg_get_triggerdef(t.oid)) FROM pg_trigger t
                JOIN pg_class c ON c.oid=t.tgrelid
                WHERE c.relnamespace='gestion_patin'::regnamespace AND NOT t.tgisinternal ORDER BY 1
                """));
        resultado.put("funciones", filas("""
                SELECT pg_get_functiondef(oid) FROM pg_proc WHERE pronamespace='gestion_patin'::regnamespace
                ORDER BY proname,oid::regprocedure::text
                """));
        resultado.put("propietarios", filas("""
                SELECT concat_ws('|',relname,pg_get_userbyid(relowner),relacl::text)
                FROM pg_class WHERE relnamespace='gestion_patin'::regnamespace ORDER BY relname
                """));
        resultado.put("esquema", filas("""
                SELECT concat_ws('|',nspname,pg_get_userbyid(nspowner),nspacl::text)
                FROM pg_namespace WHERE nspname='gestion_patin'
                """));
        resultado.put("secuencias", filas("""
                SELECT concat_ws('|',sequencename,last_value) FROM pg_sequences WHERE schemaname='gestion_patin'
                ORDER BY sequencename
                """));
        return resultado;
    }

    private List<String> filas(String query) { return jdbc.sql(query).query(String.class).list(); }

    record Estado(Map<String, List<String>> datos, Map<String, List<String>> catalogo) {}
    Estado estado() { return new Estado(datos(false), catalogo()); }

    Estado estadoRestaurable() throws Exception {
        var esquema = catalogo();
        var restricciones = new java.util.ArrayList<String>();
        for (var fila : sql.queryForList("""
                SELECT conrelid::regclass::text tabla, pg_get_constraintdef(oid) definicion,
                    concat_ws('|',conrelid::regclass::text,conname,convalidated) prefijo
                FROM pg_constraint WHERE connamespace='gestion_patin'::regnamespace ORDER BY 1,conname
                """)) {
            String definicion = (String) fila.get("definicion");
            if (definicion.startsWith("CHECK")) definicion = checkCanonico((String) fila.get("tabla"), definicion);
            restricciones.add(fila.get("prefijo") + "|" + definicion);
        }
        restricciones.sort(String::compareTo);
        esquema.put("restricciones", restricciones);
        return new Estado(datos(false), esquema);
    }

    // pg_dump puede distribuir casts de ARRAY al volver a parsear un CHECK. Se compara el punto fijo
    // del parser real sobre columnas idénticas, sin eliminar casts por regex ni ignorar constraints.
    private String checkCanonico(String tabla, String definicion) throws Exception {
        try (var c = ds.getConnection()) {
            c.setAutoCommit(false);
            for (int paso = 0; paso < 5; paso++) {
                String nueva;
                try (var st = c.createStatement()) {
                    st.execute("CREATE TEMP TABLE v5_ops_check (LIKE " + tabla + ")");
                    st.execute("ALTER TABLE v5_ops_check ADD CONSTRAINT v5_ops_check " + definicion);
                    try (var r = st.executeQuery("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='pg_temp.v5_ops_check'::regclass AND conname='v5_ops_check'")) {
                        r.next(); nueva = r.getString(1);
                    }
                } finally { c.rollback(); }
                if (nueva.equals(definicion)) return nueva;
                definicion = nueva;
            }
        }
        throw new AssertionError("El CHECK no alcanzó una representación canónica; no se omite su comparación");
    }

    Path respaldo() throws Exception {
        ejecutar("pg_dump", "-U", pg.getUsername(), "-d", pg.getDatabaseName(), "-Fc", "-f", "/tmp/v5-before.dump");
        Path archivo = EVIDENCIAS.resolve("v4-" + UUID.randomUUID() + ".dump");
        pg.copyFileFromContainer("/tmp/v5-before.dump", archivo.toString());
        assertThat(Files.size(archivo)).isPositive();
        return archivo;
    }

    int restaurar(Path archivo) throws Exception {
        pg.copyFileToContainer(MountableFile.forHostPath(archivo), "/tmp/restaurar.dump");
        return pg.execInContainer("pg_restore", "--exit-on-error", "--single-transaction", "--clean", "--if-exists",
                "-U", pg.getUsername(), "-d", pg.getDatabaseName(), "/tmp/restaurar.dump").getExitCode();
    }

    String ejecutar(String... argumentos) throws Exception {
        var r = pg.execInContainer(argumentos);
        assertThat(r.getExitCode()).as("comando PostgreSQL del contenedor propio").isZero();
        return r.getStdout().trim();
    }

    void integridadV5() {
        assertThat(numero("SELECT count(*) FROM pg_constraint WHERE connamespace='gestion_patin'::regnamespace AND NOT convalidated")).isZero();
        assertThat(numero("""
                SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid
                WHERE c.relnamespace='gestion_patin'::regnamespace AND (NOT indisvalid OR NOT indisready)
                """)).isZero();
        assertThat(numero("SELECT count(*) FROM gestion_patin.documento WHERE serie_id IS NULL OR version<>0")).isZero();
        assertThat(numero("""
                WITH RECURSIVE c AS (
                  SELECT id,id raiz FROM gestion_patin.documento WHERE documento_anterior_id IS NULL
                  UNION ALL SELECT d.id,c.raiz FROM gestion_patin.documento d JOIN c ON d.documento_anterior_id=c.id)
                SELECT count(*) FROM c JOIN gestion_patin.documento d USING(id) WHERE d.serie_id<>c.raiz
                """)).isZero();
        assertThat(numero("""
                SELECT count(*) FROM pg_trigger WHERE tgrelid='gestion_patin.documento'::regclass
                AND tgname IN ('trg_documento_contexto','trg_documento_actualizado') AND tgenabled='O'
                """)).isEqualTo(2);
    }

    static void esperar(BooleanSupplier condicion) {
        long fin = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < fin) if (condicion.getAsBoolean()) return;
        throw new AssertionError("No se observó la condición de bloqueo dentro del límite");
    }

    boolean bloqueado(int bloqueador, String aplicacion) {
        return jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE application_name=:a
                    AND :p=ANY(pg_blocking_pids(pid)) AND wait_event_type='Lock')
                """).param("p", bloqueador).param("a", aplicacion).query(Boolean.class).single();
    }

    int pid(String aplicacion) {
        return jdbc.sql("SELECT pid FROM pg_stat_activity WHERE application_name=:a AND wait_event_type='Lock'")
                .param("a", aplicacion).query(Integer.class).single();
    }

    void evidencia(String caso, Map<String, ?> extra) throws Exception {
        Map<String, Object> informe = new TreeMap<>(extra);
        informe.put("servidor", texto("SHOW server_version"));
        informe.put("pg_dump", ejecutar("pg_dump", "--version"));
        informe.put("flyway", Path.of(Flyway.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getFileName().toString());
        informe.put("conteos", datos(false).entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> e.getValue().size())));
        Files.writeString(EVIDENCIAS.resolve(caso + ".json"), new JsonMapper().writeValueAsString(informe));
        System.out.println("V5_OPERATIVA " + caso + " OK evidencias=" + EVIDENCIAS);
    }

    static String hash(Object dato) throws Exception {
        byte[] bytes = dato instanceof Path p ? Files.readAllBytes(p) : dato.toString().getBytes(StandardCharsets.UTF_8);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Path directorio() {
        try { return Files.createTempDirectory("banfield-v5-operativa-"); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    @Override public void close() { pg.close(); }
}
