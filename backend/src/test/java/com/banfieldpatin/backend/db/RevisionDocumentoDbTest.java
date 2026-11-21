package com.banfieldpatin.backend.db;

import static com.banfieldpatin.backend.documentos.EstadoDocumento.APROBADO;
import static com.banfieldpatin.backend.documentos.EstadoDocumento.OBSERVADO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.banfieldpatin.backend.compartido.auditoria.AccionAuditoria;
import com.banfieldpatin.backend.compartido.auditoria.AuditoriaService;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.documentos.EstadoDocumento;
import com.banfieldpatin.backend.documentos.revision.RevisionDocumentoService;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;

@PruebaDb
@Import({ConfigFamiliasAdminDb.class, RevisionDocumentoService.class})
@TestPropertySource(properties = {"spring.datasource.hikari.maximum-pool-size=3",
        "spring.datasource.hikari.minimum-idle=1"})
class RevisionDocumentoDbTest extends BaseVinculosDb {
    @Autowired RevisionDocumentoService revision;
    @MockitoSpyBean AuditoriaService auditoria;

    @AfterEach
    void limpiarDocumentos() {
        jdbc.sql("DELETE FROM gestion_patin.documento WHERE escuela_id IN (:a, :b)")
                .param("a", escuelaA).param("b", escuelaB).update();
    }

    @Test
    void transicionesActualizanUnaVezYNoTocanColumnasDelArchivo() {
        UUID atleta = deportista("Revisión");
        UUID frente = semilla(atleta, "DNI_DEPORTISTA_FRENTE", "CARGADO", true, 0);
        UUID dorso = semilla(atleta, "DNI_DEPORTISTA_DORSO", "CARGADO", true, 9007199254740993L);
        String sinRevision = instantanea(frente, true);

        assertThat(revision.revisar(admin, atleta, frente, APROBADO, 0, null).version()).isEqualTo("1");
        assertThat(revision.revisar(admin, atleta, frente, OBSERVADO, 1, "Imagen ilegible").version()).isEqualTo("2");
        assertThat(revision.revisar(admin, atleta, dorso, OBSERVADO, 9007199254740993L, "Recorte").version())
                .isEqualTo("9007199254740994");
        assertThat(instantanea(frente, true)).isEqualTo(sinRevision);
        var fila = jdbc.sql("""
                SELECT revisado_por, revisado_en, motivo_observacion FROM gestion_patin.documento WHERE id=:id
                """).param("id", frente).query().singleRow();
        assertThat(fila).containsEntry("revisado_por", adminId).containsEntry("motivo_observacion", "Imagen ilegible");
        assertThat(fila.get("revisado_en")).isNotNull();

        var eventos = jdbc.sql("""
                SELECT detalle::text FROM gestion_patin.auditoria
                WHERE escuela_id=:e AND usuario_id=:u AND recurso_id=:id AND accion='DOCUMENTO_REVISADO'
                """).param("e", escuelaA).param("u", adminId).param("id", frente).query(String.class).list();
        assertThat(eventos).hasSize(2).allSatisfy(e -> assertThat(e).contains("estadoAnterior", "estadoNuevo")
                .doesNotContain("Imagen ilegible", "storage"));
        assertThat(contarAuditoria("DOCUMENTO_REVISADO")).isEqualTo(3);
    }

    @Test
    void rechazosNoCambianLaFilaNiAuditanYRespetanPrecedencia() {
        UUID atleta = deportista("Origen");
        UUID otro = deportista("Distinto");
        UUID observado = semilla(atleta, "DNI_DEPORTISTA_FRENTE", "OBSERVADO", true, 1);
        UUID historico = semilla(atleta, "DNI_DEPORTISTA_DORSO", "CARGADO", false, 0);
        UUID agotado = semilla(atleta, "DNI_DEPORTISTA_DORSO", "CARGADO", true, Long.MAX_VALUE);
        UUID excluido = semilla(atleta, "OTRO", "CARGADO", true, 0);
        var ids = List.of(observado, historico, agotado, excluido);
        var antes = ids.stream().map(id -> instantanea(id, false)).toList();

        falla("DOCUMENTO_NO_ENCONTRADO", () -> revision.revisar(adminDeB, atleta, observado, APROBADO, 1, null));
        falla("DOCUMENTO_NO_ENCONTRADO", () -> revision.revisar(admin, otro, observado, APROBADO, 1, null));
        falla("DOCUMENTO_NO_ENCONTRADO", () -> revision.revisar(admin, atleta, excluido, APROBADO, 0, null));
        falla("DOCUMENTO_NO_ENCONTRADO", () -> revision.revisar(admin, atleta, UUID.randomUUID(), APROBADO, 0, null));
        falla("DOCUMENTO_NO_VIGENTE", () -> revision.revisar(admin, atleta, historico, APROBADO, 1, null));
        falla("EDICION_DESACTUALIZADA", () -> revision.revisar(admin, atleta, observado, APROBADO, 0, null));
        falla("TRANSICION_DOCUMENTO_NO_PERMITIDA", () -> revision.revisar(admin, atleta, observado, APROBADO, 1, null));
        falla("VERSION_DOCUMENTO_AGOTADA", () -> revision.revisar(admin, atleta, agotado, APROBADO, Long.MAX_VALUE, null));

        assertThat(ids.stream().map(id -> instantanea(id, false)).toList()).isEqualTo(antes);
        assertThat(contarAuditoria("DOCUMENTO_REVISADO")).isZero();
    }

    @Test
    void dosAdministradoresConLaMismaVersionTienenUnSoloGanador() throws Exception {
        UUID atleta = deportista("Concurrencia");
        UUID dni = semilla(atleta, "DNI_DEPORTISTA_FRENTE", "CARGADO", true, 0);
        var segundo = new UsuarioAutenticado(datos.admin(escuelaA, "segundo@a.example", true), escuelaA, null, Rol.ADMIN);
        var listos = new CountDownLatch(2);
        var a = hilos.submit(() -> competir(listos, admin, atleta, dni, APROBADO, null));
        var b = hilos.submit(() -> competir(listos, segundo, atleta, dni, OBSERVADO, "Imagen ilegible"));
        var resultados = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        assertThat(resultados).containsExactlyInAnyOrder("1", "EDICION_DESACTUALIZADA");
        var revisor = resultados.getFirst().equals("1") ? admin.id() : segundo.id();
        assertThat(jdbc.sql("SELECT usuario_id FROM gestion_patin.auditoria WHERE recurso_id=:id AND accion='DOCUMENTO_REVISADO'")
                .param("id", dni).query(UUID.class).single()).isEqualTo(revisor);
    }

    @Test
    void esperaDeBloqueoAcotadaNoCambiaNada() throws Exception {
        UUID atleta = deportista("Bloqueo");
        UUID dni = semilla(atleta, "DNI_DEPORTISTA_DORSO", "CARGADO", true, 0);
        String antes = instantanea(dni, false);
        var bloqueado = new CountDownLatch(1);
        var liberar = new CountDownLatch(1);
        var ocupante = hilos.submit(() -> transaccion.executeWithoutResult(tx -> {
            jdbc.sql("SELECT id FROM gestion_patin.documento WHERE id=:id FOR UPDATE")
                    .param("id", dni).query(UUID.class).single();
            bloqueado.countDown();
            try {
                assertThat(liberar.await(10, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }));
        try {
            assertThat(bloqueado.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> revision.revisar(admin, atleta, dni, APROBADO, 0, null))
                    .isInstanceOf(PessimisticLockingFailureException.class);
        } finally {
            liberar.countDown();
        }
        ocupante.get(10, TimeUnit.SECONDS);
        assertThat(instantanea(dni, false)).isEqualTo(antes);
        assertThat(contarAuditoria("DOCUMENTO_REVISADO")).isZero();
    }

    @Test
    void falloDespuesDeInsertarAuditoriaRevierteDocumentoYEvento() {
        UUID atleta = deportista("Reversión");
        UUID dni = semilla(atleta, "DNI_DEPORTISTA_FRENTE", "CARGADO", true, 0);
        String antes = instantanea(dni, false);
        doAnswer(invocacion -> {
            invocacion.callRealMethod();
            assertThat(contarAuditoria("DOCUMENTO_REVISADO")).isEqualTo(1);
            throw new IllegalStateException("fallo después de insertar auditoría");
        }).when(auditoria).registrar(argThat(e -> e.accion() == AccionAuditoria.DOCUMENTO_REVISADO));
        assertThatThrownBy(() -> revision.revisar(admin, atleta, dni, OBSERVADO, 0, "Imagen ilegible"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(instantanea(dni, false)).isEqualTo(antes);
        assertThat(contarAuditoria("DOCUMENTO_REVISADO")).isZero();
    }

    private String competir(CountDownLatch listos, UsuarioAutenticado usuario, UUID atleta, UUID dni,
            EstadoDocumento destino, String motivo) throws InterruptedException {
        listos.countDown();
        assertThat(listos.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            return revision.revisar(usuario, atleta, dni, destino, 0, motivo).version();
        } catch (ExcepcionNegocio e) {
            return e.getCodigo();
        }
    }

    private void falla(String codigo, Runnable accion) {
        assertThatThrownBy(accion::run).isInstanceOfSatisfying(ExcepcionNegocio.class,
                e -> assertThat(e.getCodigo()).isEqualTo(codigo));
    }

    private String instantanea(UUID id, boolean sinCamposEditables) {
        String excluir = sinCamposEditables ? " - ARRAY['estado','version','motivo_observacion',"
                + "'revisado_por','revisado_en','actualizado_en']" : "";
        return jdbc.sql("SELECT (to_jsonb(d)" + excluir + ")::text FROM gestion_patin.documento d WHERE id=:id")
                .param("id", id).query(String.class).single();
    }

    private UUID semilla(UUID atleta, String tipo, String estado, boolean vigente, long version) {
        return jdbc.sql("""
                INSERT INTO gestion_patin.documento(escuela_id,deportista_id,tipo,nombre_original,storage_key,
                    mime_type,tamanio_bytes,estado,vigente,cargado_por,motivo_observacion,version)
                VALUES (:e,:d,:tipo,'semilla.pdf',:key,'application/pdf',100,:estado,:vigente,:autor,
                    CASE WHEN :estado='OBSERVADO' THEN 'motivo semilla' ELSE NULL END,:v) RETURNING id
                """).param("e", escuelaA).param("d", atleta).param("tipo", tipo).param("key", UUID.randomUUID().toString())
                .param("estado", estado).param("vigente", vigente).param("autor", adminId).param("v", version)
                .query(UUID.class).single();
    }

    private UUID tutor(String nombre) {
        return new DatosDb(jdbc).tutor(escuelaA, new DatosDb(jdbc).familia(escuelaA, "Familia " + nombre, true), nombre, "Sintético");
    }

    private UUID semillaTutor(UUID tutor, String tipo, String estado, boolean vigente, long version) {
        return jdbc.sql("""
                INSERT INTO gestion_patin.documento(escuela_id,tutor_id,tipo,nombre_original,storage_key,
                    mime_type,tamanio_bytes,estado,vigente,cargado_por,motivo_observacion,version)
                VALUES (:e,:t,:tipo,'semilla.pdf',:key,'application/pdf',100,:estado,:vigente,:autor,
                    CASE WHEN :estado='OBSERVADO' THEN 'motivo semilla' ELSE NULL END,:v) RETURNING id
                """).param("e", escuelaA).param("t", tutor).param("tipo", tipo).param("key", UUID.randomUUID().toString())
                .param("estado", estado).param("vigente", vigente).param("autor", adminId).param("v", version)
                .query(UUID.class).single();
    }

    @Test
    void laRevisionDeUnDniDeTutorTieneLasMismasTransicionesVersionYAuditoria() {
        UUID tutor = tutor("Revisión");
        UUID frente = semillaTutor(tutor, "DNI_TUTOR_FRENTE", "CARGADO", true, 0);
        UUID dorso = semillaTutor(tutor, "DNI_TUTOR_DORSO", "CARGADO", true, 9007199254740993L);
        String sinRevision = instantanea(frente, true);

        assertThat(revision.revisarTutor(admin, tutor, frente, APROBADO, 0, null).version()).isEqualTo("1");
        assertThat(revision.revisarTutor(admin, tutor, frente, OBSERVADO, 1, "Imagen ilegible").version()).isEqualTo("2");
        assertThat(revision.revisarTutor(admin, tutor, dorso, OBSERVADO, 9007199254740993L, "Recorte").version())
                .isEqualTo("9007199254740994");
        assertThat(instantanea(frente, true)).isEqualTo(sinRevision);
        // Protección optimista y transiciones: la misma versión dos veces, o salir de OBSERVADO, es un 409.
        falla("EDICION_DESACTUALIZADA", () -> revision.revisarTutor(admin, tutor, frente, APROBADO, 1, null));
        falla("TRANSICION_DOCUMENTO_NO_PERMITIDA", () -> revision.revisarTutor(admin, tutor, frente, APROBADO, 2, null));
        var eventos = jdbc.sql("""
                SELECT detalle::text FROM gestion_patin.auditoria
                WHERE escuela_id=:e AND usuario_id=:u AND recurso_id=:id AND accion='DOCUMENTO_REVISADO'
                """).param("e", escuelaA).param("u", adminId).param("id", frente).query(String.class).list();
        assertThat(eventos).hasSize(2).allSatisfy(e -> assertThat(e).contains("estadoAnterior", "estadoNuevo")
                .doesNotContain("Imagen ilegible", "storage"));
    }

    @Test
    void laRevisionDeTutorSoloAlcanzaDnisVigentesDeEseTutorYEscuelaYNoSeCruzaConElDeportista() {
        UUID tutor = tutor("Alcance");
        UUID otro = tutor("Otro");
        UUID atleta = deportista("Cruce");
        UUID propio = semillaTutor(tutor, "DNI_TUTOR_FRENTE", "CARGADO", true, 0);
        UUID deOtro = semillaTutor(otro, "DNI_TUTOR_FRENTE", "CARGADO", true, 0);
        UUID otroTipo = semillaTutor(tutor, "OTRO", "CARGADO", true, 0);
        UUID historico = semillaTutor(tutor, "DNI_TUTOR_DORSO", "CARGADO", false, 0);
        UUID deDeportista = semilla(atleta, "DNI_DEPORTISTA_FRENTE", "CARGADO", true, 0);
        for (UUID id : List.of(deOtro, otroTipo, deDeportista, UUID.randomUUID()))
            falla("DOCUMENTO_NO_ENCONTRADO", () -> revision.revisarTutor(admin, tutor, id, APROBADO, 0, null));
        falla("DOCUMENTO_NO_VIGENTE", () -> revision.revisarTutor(admin, tutor, historico, APROBADO, 0, null));
        // La ruta del deportista no alcanza al tutor ni al revés; un ADMIN de otra escuela ve el mismo 404.
        falla("DOCUMENTO_NO_ENCONTRADO", () -> revision.revisar(admin, atleta, propio, APROBADO, 0, null));
        falla("DOCUMENTO_NO_ENCONTRADO", () -> revision.revisarTutor(admin, atleta, deDeportista, APROBADO, 0, null));
        var adminB = new UsuarioAutenticado(UUID.randomUUID(), escuelaB, null, Rol.ADMIN);
        falla("DOCUMENTO_NO_ENCONTRADO", () -> revision.revisarTutor(adminB, tutor, propio, APROBADO, 0, null));
        // Una FAMILIA no revisa.
        var familiar = new UsuarioAutenticado(UUID.randomUUID(), escuelaA, UUID.randomUUID(), Rol.FAMILIA);
        falla("ACCESO_DENEGADO", () -> revision.revisarTutor(familiar, tutor, propio, APROBADO, 0, null));
        assertThat(jdbc.sql("SELECT version FROM gestion_patin.documento WHERE id=:id").param("id", propio)
                .query(Long.class).single()).isZero();
        assertThat(contarAuditoria("DOCUMENTO_REVISADO")).isZero();
    }
}
