package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.documentos.DocumentoPendienteService;
import com.banfieldpatin.backend.documentos.EstadoDocumento;
import com.banfieldpatin.backend.documentos.apto.CorreccionAptoService;
import com.banfieldpatin.backend.documentos.apto.MetadatosApto;
import com.banfieldpatin.backend.documentos.revision.RevisionDocumentoService;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;

/** Corrección administrativa de los datos de un apto contra PostgreSQL: política de aprobación, CAS, alcance y auditoría. */
@PruebaDb
@Import({ ConfigFamiliasAdminDb.class, CorreccionAptoService.class, RevisionDocumentoService.class,
		DocumentoPendienteService.class, CorreccionAptoDbTest.RelojFijo.class })
@TestPropertySource(properties = { "spring.datasource.hikari.maximum-pool-size=4", "spring.datasource.hikari.minimum-idle=1" })
class CorreccionAptoDbTest extends BaseVinculosDb {
	@TestConfiguration(proxyBeanMethods = false)
	static class RelojFijo {
		@Bean
		@Primary
		Clock relojFijo() {
			return Clock.fixed(Instant.parse("2026-06-15T15:00:00Z"), ZoneId.of("UTC"));
		}
	}

	static final String NOMBRE = "Medico Sintetico";
	static final String MATRICULA = "MP 0001";
	static final String FECHA = "2026-03-01";

	@Autowired
	CorreccionAptoService correccion;
	@Autowired
	RevisionDocumentoService revision;
	@Autowired
	DocumentoPendienteService pendientes;
	UUID atleta, temporada;

	@BeforeEach
	void preparar() {
		atleta = datos.deportista(escuelaA, "CORR" + UUID.randomUUID().toString().substring(0, 8), "Lucia", "Sintetica");
		temporada = datos.temporada(escuelaA, 2026, false);
	}

	@AfterEach
	void limpiar() {
		jdbc.sql("DELETE FROM gestion_patin.documento WHERE escuela_id IN (:a, :b)").param("a", escuelaA).param("b", escuelaB)
				.update();
	}

	UUID apto(String estado, boolean vigente, long version) {
		return apto(estado, vigente, version, temporada);
	}

	UUID apto(String estado, boolean vigente, long version, UUID temporadaDelApto) {
		return jdbc.sql("""
				INSERT INTO gestion_patin.documento(escuela_id, deportista_id, temporada_id, tipo, nombre_original, storage_key,
				  mime_type, tamanio_bytes, estado, vigente, cargado_por, motivo_observacion, version, fecha_emision,
				  medico_nombre, medico_matricula, revisado_por, revisado_en)
				VALUES (:e, :d, :t, 'APTO_FISICO', 'NOMBRE_PRIVADO.pdf', :k, 'application/pdf', 100, :s, :v, :a,
				  CASE WHEN :s = 'OBSERVADO' THEN 'MOTIVO_FICTICIO' END, :ver, DATE '2026-03-01', :n, :m,
				  CASE WHEN :s IN ('APROBADO','OBSERVADO') THEN CAST(:a AS uuid) END,
				  CASE WHEN :s IN ('APROBADO','OBSERVADO') THEN clock_timestamp() END)
				RETURNING id
				""").param("e", escuelaA).param("d", atleta).param("t", temporadaDelApto).param("k", "RUTA_PRIVADA/" + UUID.randomUUID())
				.param("s", estado).param("v", vigente).param("a", adminId).param("ver", version).param("n", NOMBRE)
				.param("m", MATRICULA).query(UUID.class).single();
	}

	MetadatosApto.Solicitado datos(String fecha, String nombre, String matricula) {
		return new MetadatosApto.Solicitado(fecha, nombre, matricula);
	}

	java.util.Map<String, Object> fila(UUID id) {
		return jdbc.sql("SELECT * FROM gestion_patin.documento WHERE id=:i").param("i", id).query().singleRow();
	}

	void falla(String codigo, Runnable accion) {
		assertThatThrownBy(accion::run).isInstanceOfSatisfying(ExcepcionNegocio.class, e -> assertThat(e.getCodigo()).isEqualTo(codigo));
	}

	@Test
	void corregirUnAptoCargadoCambiaLosDatosLoDejaEnRevisionYAuditaSoloNombresDeCampos() {
		UUID id = apto("CARGADO", true, 0);
		var r = correccion.corregir(admin, atleta, id, 0, datos(FECHA, NOMBRE, "MP 0002"));
		assertThat(r.revision()).isEqualTo(1L);
		assertThat(r.medicoMatricula()).isEqualTo("MP 0002");
		assertThat(fila(id)).containsEntry("estado", "CARGADO").containsEntry("medico_matricula", "MP 0002")
				.containsEntry("medico_nombre", NOMBRE).containsEntry("version", 1L);
		var eventos = jdbc.sql("""
				SELECT detalle::text FROM gestion_patin.auditoria
				WHERE escuela_id=:e AND usuario_id=:u AND recurso_id=:id AND accion='DOCUMENTO_APTO_CORREGIDO'
				""").param("e", escuelaA).param("u", adminId).param("id", id).query(String.class).list();
		assertThat(eventos).hasSize(1);
		assertThat(eventos.getFirst()).contains("medicoMatricula", "aprobacionRetirada")
				.doesNotContain("MP 0002", MATRICULA, NOMBRE, FECHA, "NOMBRE_PRIVADO");
	}

	@Test
	void corregirUnAptoAprobadoRetiraLaAprobacionYLoDevuelveALaBandejaDePendientes() {
		UUID id = apto("APROBADO", true, 3);
		assertThat(pendientes.listar(admin, null, org.springframework.data.domain.PageRequest.of(0, 10)).contenido()).isEmpty();
		var r = correccion.corregir(admin, atleta, id, 3, datos("2026-03-02", NOMBRE, MATRICULA));
		assertThat(r.estado()).isEqualTo("CARGADO");
		assertThat(r.revision()).isEqualTo(4L);
		assertThat(fila(id)).containsEntry("estado", "CARGADO").containsEntry("revisado_por", null).containsEntry("revisado_en", null);
		assertThat(pendientes.listar(admin, null, org.springframework.data.domain.PageRequest.of(0, 10)).contenido())
				.extracting(p -> p.id()).containsExactly(id);
		assertThat(jdbc.sql("SELECT detalle::text FROM gestion_patin.auditoria WHERE recurso_id=:i AND accion='DOCUMENTO_APTO_CORREGIDO'")
				.param("i", id).query(String.class).single()).contains("\"aprobacionRetirada\": true", "fechaEmision");
		// y vuelve a aprobarse por el flujo de siempre, sobre la revisión nueva
		assertThat(revision.revisar(admin, atleta, id, EstadoDocumento.APROBADO, 4, null).estado()).isEqualTo(EstadoDocumento.APROBADO);
	}

	@Test
	void unaCorreccionSinCambiosNoEscribeNoAuditaYConservaLaAprobacion() {
		UUID id = apto("APROBADO", true, 2);
		String antes = fila(id).toString();
		var r = correccion.corregir(admin, atleta, id, 2, datos(" " + FECHA + " ", "  " + NOMBRE, MATRICULA + " "));
		assertThat(r.estado()).isEqualTo("APROBADO");
		assertThat(r.revision()).isEqualTo(2L);
		assertThat(fila(id).toString()).isEqualTo(antes);
		assertThat(contarAuditoria("DOCUMENTO_APTO_CORREGIDO")).isZero();
	}

	@Test
	void soloSeCorrigeLaVersionVigenteEnRevisionOAprobada() {
		UUID observado = apto("OBSERVADO", true, 1);
		UUID vencido = apto("VENCIDO", false, 1);
		UUID historico = apto("CARGADO", false, 0);
		UUID agotado = apto("CARGADO", true, Long.MAX_VALUE, datos.temporada(escuelaA, 2030, false));
		var ids = List.of(observado, vencido, historico, agotado);
		var antes = ids.stream().map(i -> fila(i).toString()).toList();
		falla("APTO_NO_CORREGIBLE", () -> correccion.corregir(admin, atleta, observado, 1, datos(FECHA, NOMBRE, "MP 9")));
		falla("DOCUMENTO_NO_VIGENTE", () -> correccion.corregir(admin, atleta, vencido, 1, datos(FECHA, NOMBRE, "MP 9")));
		falla("DOCUMENTO_NO_VIGENTE", () -> correccion.corregir(admin, atleta, historico, 0, datos(FECHA, NOMBRE, "MP 9")));
		falla("VERSION_DOCUMENTO_AGOTADA", () -> correccion.corregir(admin, atleta, agotado, Long.MAX_VALUE, datos(FECHA, NOMBRE, "MP 9")));
		assertThat(ids.stream().map(i -> fila(i).toString()).toList()).isEqualTo(antes);
		assertThat(contarAuditoria("DOCUMENTO_APTO_CORREGIDO")).isZero();
	}

	@Test
	void unaVersionVencidaEsConflictoYNoCambiaNada() {
		UUID id = apto("CARGADO", true, 5);
		String antes = fila(id).toString();
		falla("EDICION_DESACTUALIZADA", () -> correccion.corregir(admin, atleta, id, 4, datos(FECHA, NOMBRE, "MP 9")));
		assertThat(fila(id).toString()).isEqualTo(antes);
	}

	@Test
	void documentoDeOtroDeportistaOtraEscuelaOtroTipoOInexistenteEsElMismo404() {
		UUID id = apto("CARGADO", true, 0);
		UUID otro = datos.deportista(escuelaA, "OTRO" + UUID.randomUUID().toString().substring(0, 8), "Otra", "Sintetica");
		UUID dni = jdbc.sql("""
				INSERT INTO gestion_patin.documento(escuela_id, deportista_id, tipo, nombre_original, storage_key, mime_type,
				  tamanio_bytes, estado, vigente, cargado_por)
				VALUES (:e, :d, 'DNI_DEPORTISTA_FRENTE', 'x.pdf', :k, 'application/pdf', 10, 'CARGADO', true, :a) RETURNING id
				""").param("e", escuelaA).param("d", atleta).param("k", UUID.randomUUID().toString()).param("a", adminId)
				.query(UUID.class).single();
		var d = datos(FECHA, NOMBRE, "MP 9");
		falla("DOCUMENTO_NO_ENCONTRADO", () -> correccion.corregir(adminDeB, atleta, id, 0, d));
		falla("DOCUMENTO_NO_ENCONTRADO", () -> correccion.corregir(admin, otro, id, 0, d));
		falla("DOCUMENTO_NO_ENCONTRADO", () -> correccion.corregir(admin, atleta, dni, 0, d));
		falla("DOCUMENTO_NO_ENCONTRADO", () -> correccion.corregir(admin, atleta, UUID.randomUUID(), 0, d));
		assertThat(contarAuditoria("DOCUMENTO_APTO_CORREGIDO")).isZero();
	}

	@Test
	void unaSesionQueNoEsAdminNoCorrige() {
		UUID id = apto("CARGADO", true, 0);
		var familiar = new UsuarioAutenticado(UUID.randomUUID(), escuelaA, UUID.randomUUID(), Rol.FAMILIA);
		falla("ACCESO_DENEGADO", () -> correccion.corregir(familiar, atleta, id, 0, datos(FECHA, NOMBRE, "MP 9")));
		assertThat(fila(id)).containsEntry("medico_matricula", MATRICULA);
	}

	@Test
	void datosInvalidosSeRechazanSinTocarNadaNiEcoDeValores() {
		UUID id = apto("APROBADO", true, 0);
		String antes = fila(id).toString();
		for (var d : List.of(datos("no-es-fecha", NOMBRE, MATRICULA), datos("2099-01-01", NOMBRE, MATRICULA),
				datos(FECHA, " ", MATRICULA), datos(FECHA, NOMBRE, "***SECRETA***"), datos(null, null, null))) {
			assertThatThrownBy(() -> correccion.corregir(admin, atleta, id, 0, d)).isInstanceOfSatisfying(ExcepcionNegocio.class, e -> {
				assertThat(e.getCodigo()).isEqualTo("VALIDACION");
				assertThat(e.getMessage()).doesNotContain("SECRETA", "no-es-fecha", "2099");
			});
		}
		assertThat(fila(id).toString()).isEqualTo(antes);
		assertThat(contarAuditoria("DOCUMENTO_APTO_CORREGIDO")).isZero();
	}

	@Test
	void dosCorreccionesConLaMismaVersionSoloUnaGana() throws Exception {
		UUID id = apto("APROBADO", true, 0);
		var pool = Executors.newFixedThreadPool(2);
		try {
			Callable<String> a = () -> intentar(id, "MP 0100");
			Callable<String> b = () -> intentar(id, "MP 0200");
			var resultados = pool.invokeAll(List.of(a, b)).stream().map(f -> {
				try {
					return f.get(30, TimeUnit.SECONDS);
				} catch (Exception e) {
					throw new IllegalStateException(e);
				}
			}).toList();
			assertThat(resultados).containsExactlyInAnyOrder("OK", "EDICION_DESACTUALIZADA");
		} finally {
			pool.shutdownNow();
		}
		assertThat(fila(id)).containsEntry("version", 1L).containsEntry("estado", "CARGADO");
		assertThat(contarAuditoria("DOCUMENTO_APTO_CORREGIDO")).isEqualTo(1);
	}

	private String intentar(UUID id, String matricula) {
		try {
			correccion.corregir(admin, atleta, id, 0, datos(FECHA, NOMBRE, matricula));
			return "OK";
		} catch (ExcepcionNegocio e) {
			return e.getCodigo();
		}
	}

	@Test
	void elAptoSeRevisaPorElFlujoDeSiempreYLaRevisionOptimistaEsLaMisma() {
		UUID id = apto("CARGADO", true, 0);
		assertThat(revision.revisar(admin, atleta, id, EstadoDocumento.APROBADO, 0, null).estado()).isEqualTo(EstadoDocumento.APROBADO);
		assertThat(revision.revisar(admin, atleta, id, EstadoDocumento.OBSERVADO, 1, "Imagen ilegible").estado())
				.isEqualTo(EstadoDocumento.OBSERVADO);
		falla("TRANSICION_DOCUMENTO_NO_PERMITIDA", () -> revision.revisar(admin, atleta, id, EstadoDocumento.APROBADO, 2, null));
		falla("DOCUMENTO_NO_ENCONTRADO", () -> revision.revisar(adminDeB, atleta, id, EstadoDocumento.APROBADO, 2, null));
		assertThat(contarAuditoria("DOCUMENTO_REVISADO")).isEqualTo(2);
	}
}
