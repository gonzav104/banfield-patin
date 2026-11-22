package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.banfieldpatin.backend.documentos.DocumentoPendienteAdmin;
import com.banfieldpatin.backend.documentos.DocumentoPendienteService;
import com.banfieldpatin.backend.documentos.TipoDocumento;
import com.banfieldpatin.backend.documentos.TitularDocumento;
import com.banfieldpatin.backend.compartido.web.Pagina;

/** La bandeja contra PostgreSQL: que entra, que no, el aislamiento por escuela y el orden estable. */
@PruebaDb
@Import({ ConfigFamiliasAdminDb.class, DocumentoPendienteService.class })
class DocumentoPendienteDbTest extends BaseVinculosDb {
	@Autowired
	DocumentoPendienteService bandeja;
	UUID familia;
	UUID deportista;
	UUID tutor;

	@BeforeEach
	void preparar() {
		familia = datos.familia(escuelaA, "Familia pendiente", true);
		deportista = deportista("Lucia");
		tutor = datos.tutor(escuelaA, familia, "Rosa", "Sintetica");
	}

	@AfterEach
	void limpiar() {
		for (UUID escuela : List.of(escuelaA, escuelaB)) {
			jdbc.sql("DELETE FROM gestion_patin.documento WHERE escuela_id = :e").param("e", escuela).update();
		}
	}

	UUID documento(UUID escuela, UUID titular, boolean esTutor, String tipo, String estado, boolean vigente,
			OffsetDateTime creado, UUID anterior) {
		return jdbc.sql("""
				INSERT INTO gestion_patin.documento(escuela_id, deportista_id, tutor_id, tipo, nombre_original,
				  storage_key, mime_type, tamanio_bytes, estado, vigente, cargado_por, creado_en, documento_anterior_id,
				  motivo_observacion)
				VALUES (:e, CAST(:d AS uuid), CAST(:t AS uuid), :tipo, 'NOMBRE_PRIVADO.pdf', :key, 'application/pdf', 100,
				  :estado, :vigente, :autor, :creado, CAST(:anterior AS uuid),
				  CASE WHEN :estado = 'OBSERVADO' THEN 'MOTIVO_FICTICIO' END)
				RETURNING id
				""").param("e", escuela).param("d", esTutor ? null : titular).param("t", esTutor ? titular : null)
				.param("tipo", tipo).param("key", "RUTA_PRIVADA/" + UUID.randomUUID()).param("estado", estado)
				.param("vigente", vigente).param("autor", escuela.equals(escuelaA) ? adminId : adminDeB.id())
				.param("creado", creado).param("anterior", anterior).query(UUID.class).single();
	}

	UUID cargado(UUID escuela, UUID titular, boolean esTutor, OffsetDateTime creado) {
		return documento(escuela, titular, esTutor, esTutor ? "DNI_TUTOR_FRENTE" : "DNI_DEPORTISTA_FRENTE", "CARGADO",
				true, creado, null);
	}

	static OffsetDateTime hora(int minutos) {
		return OffsetDateTime.parse("2026-03-10T10:00:00Z").plusMinutes(minutos);
	}

	List<UUID> ids(Pagina<DocumentoPendienteAdmin> pagina) {
		return pagina.contenido().stream().map(DocumentoPendienteAdmin::id).toList();
	}

	@Test
	void unDocumentoCargadoVigenteApareceConSuTitularYSinMarcaDeReemplazo() {
		var delDeportista = cargado(escuelaA, deportista, false, hora(0));
		var delTutor = cargado(escuelaA, tutor, true, hora(1));

		var pagina = bandeja.listar(admin, null, Pagina.pedir(0, 20));

		assertThat(pagina.totalElementos()).isEqualTo(2);
		var primero = pagina.contenido().get(0);
		assertThat(primero).extracting(DocumentoPendienteAdmin::id, DocumentoPendienteAdmin::tipo,
				DocumentoPendienteAdmin::titularTipo, DocumentoPendienteAdmin::titularId,
				DocumentoPendienteAdmin::titularNombre, DocumentoPendienteAdmin::familiaId,
				DocumentoPendienteAdmin::reemplazo)
				.containsExactly(delDeportista, TipoDocumento.DNI_DEPORTISTA_FRENTE, TitularDocumento.DEPORTISTA,
						deportista, "Lucia ApellidoLucia", null, false);
		assertThat(primero.creadoEn()).isEqualTo(hora(0).toInstant());
		var segundo = pagina.contenido().get(1);
		assertThat(segundo).extracting(DocumentoPendienteAdmin::id, DocumentoPendienteAdmin::tipo,
				DocumentoPendienteAdmin::titularTipo, DocumentoPendienteAdmin::titularId,
				DocumentoPendienteAdmin::titularNombre, DocumentoPendienteAdmin::familiaId,
				DocumentoPendienteAdmin::reemplazo)
				.containsExactly(delTutor, TipoDocumento.DNI_TUTOR_FRENTE, TitularDocumento.TUTOR, tutor,
						"Rosa Sintetica", familia, false);
	}

	@Test
	void elSucesorCargadoDeUnDocumentoObservadoApareceIdentificadoComoReemplazo() {
		var observado = documento(escuelaA, deportista, false, "DNI_DEPORTISTA_DORSO", "OBSERVADO", false, hora(0), null);
		var sucesor = documento(escuelaA, deportista, false, "DNI_DEPORTISTA_DORSO", "CARGADO", true, hora(5), observado);

		var pagina = bandeja.listar(admin, null, Pagina.pedir(0, 20));

		assertThat(ids(pagina)).containsExactly(sucesor); // el observado (historico) no aparece
		assertThat(pagina.contenido().getFirst().reemplazo()).isTrue();
	}

	@Test
	void observadosAprobadosVencidosYVersionesNoVigentesNoAparecen() {
		var visible = cargado(escuelaA, tutor, true, hora(0));
		documento(escuelaA, tutor, true, "DNI_TUTOR_DORSO", "OBSERVADO", true, hora(1), null);
		documento(escuelaA, deportista, false, "DNI_DEPORTISTA_FRENTE", "APROBADO", true, hora(2), null);
		documento(escuelaA, deportista, false, "APTO_FISICO", "VENCIDO", true, hora(3), null);
		documento(escuelaA, deportista, false, "DNI_DEPORTISTA_DORSO", "CARGADO", false, hora(4), null);

		var pagina = bandeja.listar(admin, null, Pagina.pedir(0, 20));

		assertThat(ids(pagina)).containsExactly(visible);
		assertThat(pagina.totalElementos()).isEqualTo(1);
	}

	@Test
	void cadaAdminVeSoloLosPendientesDeSuEscuela() {
		var deA = cargado(escuelaA, deportista, false, hora(0));
		var familiaB = datos.familia(escuelaB, "Familia B", true);
		var tutorB = datos.tutor(escuelaB, familiaB, "Tutor", "DeB");
		var deB = cargado(escuelaB, tutorB, true, hora(0));

		assertThat(ids(bandeja.listar(admin, null, Pagina.pedir(0, 20)))).containsExactly(deA);
		var vistoPorB = bandeja.listar(adminDeB, null, Pagina.pedir(0, 20));
		assertThat(ids(vistoPorB)).containsExactly(deB);
		assertThat(vistoPorB.totalElementos()).isEqualTo(1);
	}

	@Test
	void elOrdenEsDelMasAntiguoAlMasRecienteYEstableConEmpatesEnLaPaginacion() {
		var mismoInstante = hora(0);
		var todos = new ArrayList<UUID>();
		for (int i = 0; i < 5; i++) {
			// Un titular distinto por documento: solo hay un DNI vigente por titular y tipo.
			UUID titular = i % 2 == 0 ? deportista("D" + i) : datos.tutor(escuelaA, familia, "T" + i, "Sintetico");
			todos.add(cargado(escuelaA, titular, i % 2 != 0, mismoInstante));
		}
		var masReciente = cargado(escuelaA, deportista, false, hora(30));
		var masAntiguo = cargado(escuelaA, tutor, true, hora(-30));

		var esperado = new ArrayList<UUID>();
		esperado.add(masAntiguo);
		esperado.addAll(todos.stream().sorted(java.util.Comparator.comparing(UUID::toString)).toList()); // empate: por id (PostgreSQL ordena el uuid byte a byte, no como UUID.compareTo)
		esperado.add(masReciente);
		var leidos = new ArrayList<UUID>();
		for (int pagina = 0; pagina < 4; pagina++) {
			var p = bandeja.listar(admin, null, Pagina.pedir(pagina, 2));
			assertThat(p.totalElementos()).isEqualTo(7);
			leidos.addAll(ids(p));
		}

		assertThat(leidos).containsExactlyElementsOf(esperado);
		assertThat(ids(bandeja.listar(admin, null, Pagina.pedir(9, 2)))).isEmpty();
	}

	@Test
	void elFiltroPorTitularRestringeFilasYTotal() {
		var deDeportista = cargado(escuelaA, deportista, false, hora(0));
		var deTutor = cargado(escuelaA, tutor, true, hora(1));

		var soloDeportistas = bandeja.listar(admin, TitularDocumento.DEPORTISTA, Pagina.pedir(0, 20));
		var soloTutores = bandeja.listar(admin, TitularDocumento.TUTOR, Pagina.pedir(0, 20));

		assertThat(ids(soloDeportistas)).containsExactly(deDeportista);
		assertThat(soloDeportistas.totalElementos()).isEqualTo(1);
		assertThat(ids(soloTutores)).containsExactly(deTutor);
		assertThat(soloTutores.totalElementos()).isEqualTo(1);
	}

	@Test
	void unTitularInactivoSigueApareciendoPorqueSuFichaSigueSiendoAccesible() {
		var inactivo = deportista(escuelaA, "Inactiva", false);
		var id = cargado(escuelaA, inactivo, false, hora(0));

		assertThat(ids(bandeja.listar(admin, null, Pagina.pedir(0, 20)))).containsExactly(id);
	}
}
