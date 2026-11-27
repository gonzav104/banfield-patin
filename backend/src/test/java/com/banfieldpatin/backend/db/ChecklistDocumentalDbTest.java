package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.documentos.ChecklistDocumental;
import com.banfieldpatin.backend.documentos.ChecklistDocumentalService;
import com.banfieldpatin.backend.documentos.EstadoRequisito;
import com.banfieldpatin.backend.documentos.RequisitoDocumental;
import com.banfieldpatin.backend.documentos.TipoDocumento;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;

/** El checklist parcial contra PostgreSQL: estados reales, historial, alcance familiar y aislamiento por escuela. */
@PruebaDb
@Import({ ConfigFamiliasAdminDb.class, ChecklistDocumentalService.class })
class ChecklistDocumentalDbTest extends BaseVinculosDb {
	@Autowired
	ChecklistDocumentalService checklist;
	@Autowired
	com.banfieldpatin.backend.documentos.DocumentoRepository repositorio;
	UUID familia;
	UUID deportista;
	UsuarioAutenticado familiar;

	@BeforeEach
	void preparar() {
		familia = datos.familia(escuelaA, "Familia checklist", true);
		familiar = new UsuarioAutenticado(datos.usuarioFamilia(escuelaA, familia, "checklist@example.invalid"), escuelaA, familia, Rol.FAMILIA);
		deportista = deportista("Lucia");
		datos.vinculo(escuelaA, familia, deportista, "ACTIVO", false, adminId);
	}

	@AfterEach
	void limpiar() {
		for (UUID escuela : List.of(escuelaA, escuelaB)) {
			jdbc.sql("DELETE FROM gestion_patin.documento WHERE escuela_id = :e").param("e", escuela).update();
		}
	}

	private final java.util.Map<UUID, UUID> temporadas = new java.util.HashMap<>();

	/** El apto físico pertenece a una temporada: una sola, sintética, por escuela. */
	private UUID temporadaDe(UUID escuela) {
		return temporadas.computeIfAbsent(escuela, e -> datos.temporada(e, 2026, false));
	}

	UUID documento(UUID escuela, UUID titular, boolean esTutor, String tipo, String estado, boolean vigente, int minutos) {
		return jdbc.sql("""
				INSERT INTO gestion_patin.documento(escuela_id, deportista_id, tutor_id, tipo, nombre_original, storage_key,
				  mime_type, tamanio_bytes, estado, vigente, cargado_por, creado_en, motivo_observacion,
				  temporada_id, fecha_emision, medico_nombre, medico_matricula)
				VALUES (:e, CAST(:d AS uuid), CAST(:t AS uuid), :tipo, 'NOMBRE_PRIVADO.pdf', :key, 'application/pdf', 100,
				  :estado, :vigente, :autor, :creado, CASE WHEN :estado = 'OBSERVADO' THEN 'MOTIVO_FICTICIO' END,
				  CAST(:temp AS uuid), CASE WHEN :tipo = 'APTO_FISICO' THEN DATE '2026-03-01' END,
				  CASE WHEN :tipo = 'APTO_FISICO' THEN 'Medico Sintetico' END,
				  CASE WHEN :tipo = 'APTO_FISICO' THEN 'MP 0001' END)
				RETURNING id
				""").param("e", escuela).param("temp", "APTO_FISICO".equals(tipo) ? temporadaDe(escuela) : null).param("d", esTutor ? null : titular).param("t", esTutor ? titular : null)
				.param("tipo", tipo).param("key", "RUTA_PRIVADA/" + UUID.randomUUID()).param("estado", estado)
				.param("vigente", vigente).param("autor", escuela.equals(escuelaA) ? adminId : adminDeB.id())
				.param("creado", OffsetDateTime.parse("2026-03-10T10:00:00Z").plusMinutes(minutos)).query(UUID.class).single();
	}

	List<EstadoRequisito> estados(ChecklistDocumental c) {
		return c.requisitos().stream().map(RequisitoDocumental::estado).toList();
	}

	void noEncontrado(Runnable lectura) {
		assertThatThrownBy(lectura::run).isInstanceOfSatisfying(ExcepcionNegocio.class, e -> {
			assertThat(e.getEstado().value()).isEqualTo(404);
			assertThat(e.getCodigo()).isEqualTo("DEPORTISTA_NO_ENCONTRADO");
		});
	}

	@Test
	void sinDocumentosFaltanAmbosLadosParaAdminYParaFamilia() {
		assertThat(estados(checklist.deportista(admin, deportista))).containsExactly(EstadoRequisito.FALTA, EstadoRequisito.FALTA);
		var porFamilia = checklist.deportista(familiar, deportista);
		assertThat(estados(porFamilia)).containsExactly(EstadoRequisito.FALTA, EstadoRequisito.FALTA);
		assertThat(porFamilia.parcial()).isTrue();
		assertThat(porFamilia.deportistaId()).isEqualTo(deportista);
	}

	@Test
	void cadaEstadoDelDocumentoVigenteSeTraduceAlRequisito() {
		documento(escuelaA, deportista, false, "DNI_DEPORTISTA_FRENTE", "APROBADO", true, 0);
		documento(escuelaA, deportista, false, "DNI_DEPORTISTA_DORSO", "OBSERVADO", true, 1);
		var c = checklist.deportista(familiar, deportista);
		assertThat(c.requisitos()).containsExactly(
				new RequisitoDocumental(TipoDocumento.DNI_DEPORTISTA_FRENTE, EstadoRequisito.CUMPLIDO, null),
				new RequisitoDocumental(TipoDocumento.DNI_DEPORTISTA_DORSO, EstadoRequisito.OBSERVADO, "MOTIVO_FICTICIO"));
		assertThat(checklist.deportista(admin, deportista)).isEqualTo(c); // el mismo cálculo para ambos roles
	}

	@Test
	void cargadoEstaEnRevision() {
		documento(escuelaA, deportista, false, "DNI_DEPORTISTA_FRENTE", "CARGADO", true, 0);
		assertThat(estados(checklist.deportista(admin, deportista))).containsExactly(EstadoRequisito.EN_REVISION, EstadoRequisito.FALTA);
	}

	@Test
	void unReemplazoVigenteSustituyeALaVersionObservadaHistorica() {
		documento(escuelaA, deportista, false, "DNI_DEPORTISTA_DORSO", "OBSERVADO", false, 0);
		documento(escuelaA, deportista, false, "DNI_DEPORTISTA_DORSO", "CARGADO", true, 5);
		var c = checklist.deportista(familiar, deportista);
		assertThat(c.requisitos().get(1)).isEqualTo(new RequisitoDocumental(TipoDocumento.DNI_DEPORTISTA_DORSO, EstadoRequisito.EN_REVISION, null));
	}

	@Test
	void unDocumentoAprobadoQueYaNoEsVigenteNoCumple() {
		documento(escuelaA, deportista, false, "DNI_DEPORTISTA_FRENTE", "APROBADO", false, 0);
		assertThat(estados(checklist.deportista(admin, deportista))).containsExactly(EstadoRequisito.FALTA, EstadoRequisito.FALTA);
	}

	@Test
	void losDniDeTutoresYLosOtrosTiposNoCuentanParaElDeportista() {
		var tutor = datos.tutor(escuelaA, familia, "Rosa", "Sintetica");
		documento(escuelaA, tutor, true, "DNI_TUTOR_FRENTE", "APROBADO", true, 0);
		documento(escuelaA, tutor, true, "DNI_TUTOR_DORSO", "APROBADO", true, 1);
		documento(escuelaA, deportista, false, "APTO_FISICO", "APROBADO", true, 2);
		documento(escuelaA, deportista, false, "OTRO", "APROBADO", true, 3);
		assertThat(estados(checklist.deportista(familiar, deportista))).containsExactly(EstadoRequisito.FALTA, EstadoRequisito.FALTA);
	}

	@Test
	void unDeportistaInactivoSigueSiendoConsultableParaAdmin() {
		var inactivo = deportista(escuelaA, "Inactiva", false);
		documento(escuelaA, inactivo, false, "DNI_DEPORTISTA_FRENTE", "APROBADO", true, 0);
		assertThat(estados(checklist.deportista(admin, inactivo))).containsExactly(EstadoRequisito.CUMPLIDO, EstadoRequisito.FALTA);
	}

	@Test
	void adminNoVeNiConfirmaDeportistasDeOtraEscuela() {
		var deOtraEscuela = deportista(escuelaB, "OtraEscuela", true);
		documento(escuelaB, deOtraEscuela, false, "DNI_DEPORTISTA_FRENTE", "APROBADO", true, 0);
		noEncontrado(() -> checklist.deportista(admin, deOtraEscuela));
		noEncontrado(() -> checklist.deportista(admin, UUID.randomUUID())); // misma respuesta que uno inexistente
		assertThat(estados(checklist.deportista(adminDeB, deOtraEscuela))).containsExactly(EstadoRequisito.CUMPLIDO, EstadoRequisito.FALTA);
		noEncontrado(() -> checklist.deportista(adminDeB, deportista));
	}

	@Test
	void laFamiliaSoloVeALosDeportistasDeSuAlcance() {
		var ajeno = deportista("Ajena");
		var otraFamilia = datos.familia(escuelaA, "Otra familia", true);
		datos.vinculo(escuelaA, otraFamilia, ajeno, "ACTIVO", false, adminId);
		documento(escuelaA, ajeno, false, "DNI_DEPORTISTA_FRENTE", "APROBADO", true, 0);
		noEncontrado(() -> checklist.deportista(familiar, ajeno));
		var sinVinculo = deportista("SinVinculo");
		noEncontrado(() -> checklist.deportista(familiar, sinVinculo));
		noEncontrado(() -> checklist.deportista(familiar, UUID.randomUUID()));
	}

	@Test
	void unVinculoQueNoEstaActivoNoDaAcceso() {
		for (String estado : List.of("PENDIENTE", "RECHAZADO", "REVOCADO")) {
			var d = deportista("Vinculo" + estado);
			datos.vinculo(escuelaA, familia, d, estado, false, adminId);
			noEncontrado(() -> checklist.deportista(familiar, d));
		}
	}

	@Test
	void unaFamiliaInactivaOUnUsuarioSinFamiliaNoAccede() {
		var inactiva = datos.familia(escuelaA, "Inactiva", false);
		var d = deportista("DeInactiva");
		datos.vinculo(escuelaA, inactiva, d, "ACTIVO", false, adminId);
		var usuario = new UsuarioAutenticado(datos.usuarioFamilia(escuelaA, inactiva, "inactiva@example.invalid"), escuelaA, inactiva, Rol.FAMILIA);
		noEncontrado(() -> checklist.deportista(usuario, d));
		noEncontrado(() -> checklist.deportista(new UsuarioAutenticado(UUID.randomUUID(), escuelaA, null, Rol.FAMILIA), deportista));
	}

	@Test
	void unaFamiliaDeOtraEscuelaNoAccedeAunqueElIdCoincida() {
		var familiaB = datos.familia(escuelaB, "Familia B", true);
		var deB = deportista(escuelaB, "DeB", true);
		datos.vinculo(escuelaB, familiaB, deB, "ACTIVO", false, adminDeB.id());
		var usuarioB = new UsuarioAutenticado(datos.usuarioFamilia(escuelaB, familiaB, "b@example.invalid"), escuelaB, familiaB, Rol.FAMILIA);
		noEncontrado(() -> checklist.deportista(usuarioB, deportista));
		// La familia A consultando con el id del deportista de B (otra escuela): el mismo 404.
		noEncontrado(() -> checklist.deportista(familiar, deB));
	}

	@Test
	void laLecturaNoEscribeNiAudita() {
		documento(escuelaA, deportista, false, "DNI_DEPORTISTA_FRENTE", "APROBADO", true, 0);
		long auditoria = contar("auditoria", escuelaA);
		String antes = jdbc.sql("SELECT coalesce(json_agg(d ORDER BY id)::text,'[]') FROM gestion_patin.documento d WHERE escuela_id = :e")
				.param("e", escuelaA).query(String.class).single();
		checklist.deportista(admin, deportista);
		checklist.deportista(familiar, deportista);
		assertThat(contar("auditoria", escuelaA)).isEqualTo(auditoria);
		assertThat(jdbc.sql("SELECT coalesce(json_agg(d ORDER BY id)::text,'[]') FROM gestion_patin.documento d WHERE escuela_id = :e")
				.param("e", escuelaA).query(String.class).single()).isEqualTo(antes);
	}

	@Test
	void laConsultaFamiliarDelRepositorioPorSiSolaRespetaElAlcanceSinDependerDelControlPrevio() {
		documento(escuelaA, deportista, false, "DNI_DEPORTISTA_FRENTE", "APROBADO", true, 0);
		var ajeno = deportista("AjenaRepo");
		var otraFamilia = datos.familia(escuelaA, "Otra familia repo", true);
		datos.vinculo(escuelaA, otraFamilia, ajeno, "ACTIVO", false, adminId);
		documento(escuelaA, ajeno, false, "DNI_DEPORTISTA_FRENTE", "APROBADO", true, 1);
		var tipos = List.of(TipoDocumento.DNI_DEPORTISTA_FRENTE, TipoDocumento.DNI_DEPORTISTA_DORSO);

		assertThat(repositorio.requisitosFamilia(escuelaA, deportista, familia, tipos)).hasSize(1);
		assertThat(repositorio.requisitosFamilia(escuelaA, ajeno, familia, tipos)).isEmpty(); // de otra familia
		assertThat(repositorio.requisitosFamilia(escuelaB, deportista, familia, tipos)).isEmpty(); // de otra escuela
		assertThat(repositorio.requisitosAdmin(escuelaB, deportista, tipos)).isEmpty();
	}
}
