package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.banfieldpatin.backend.JsonDeContrato;
import com.banfieldpatin.backend.compartido.web.FiltroEstado;
import com.banfieldpatin.backend.compartido.web.Pagina;
import com.banfieldpatin.backend.deportistas.DeportistaAdminService;
import com.banfieldpatin.backend.deportistas.dto.DeportistaDetalle;
import com.banfieldpatin.backend.deportistas.dto.DeportistaResumen;
import com.banfieldpatin.backend.familias.FamiliaAdminService;
import com.banfieldpatin.backend.familias.dto.FamiliaDetalle;
import com.banfieldpatin.backend.familias.tutores.TutorAdminService;
import com.banfieldpatin.backend.familias.tutores.dto.TutorRespuesta;
import com.banfieldpatin.backend.familias.tutores.dto.TutorSolicitud;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * DNI enmascarado (O8) contra PostgreSQL real: los listados y los tutores anidados nunca traen el DNI completo (se comprueba
 * sobre el JSON serializado, por claves y por valores, no por subcadenas del texto crudo), la busqueda por prefijo sigue
 * funcionando con el DNI completo almacenado y el detalle individual lo conserva.
 */
@PruebaDb
@Import(ConfigFamiliasAdminDb.class)
@TestPropertySource(properties = { "spring.datasource.hikari.maximum-pool-size=3",
		"spring.datasource.hikari.minimum-idle=1", "spring.jpa.properties.hibernate.generate_statistics=true" })
class DniEnmascaradoDbTest extends BaseVinculosDb {

	@Autowired
	DeportistaAdminService deportistasAdmin;
	@Autowired
	FamiliaAdminService familiasAdmin;
	@Autowired
	TutorAdminService tutoresAdmin;
	@Autowired
	JsonMapper json;

	private Pagina<DeportistaResumen> listar(String busqueda) {
		return deportistasAdmin.listar(admin, FiltroEstado.TODOS, busqueda, Pagina.pedir(0, 50));
	}

	private List<String> enmascarados(String busqueda) {
		return listar(busqueda).contenido().stream().map(DeportistaResumen::dniEnmascarado).toList();
	}

	@Test
	void elListadoEnmascaraDnisDeSieteOchoYNueveDigitosYSuJsonNoTraeElCompleto() {
		List<String> dnis = List.of("4412345", "30111322", "271234567");
		datos.deportista(escuelaA, dnis.get(0), "Ana", "Alfa", true);
		datos.deportista(escuelaA, dnis.get(1), "Beto", "Beta", false);
		datos.deportista(escuelaA, dnis.get(2), "Cata", "Gamma", true);

		Pagina<DeportistaResumen> pagina = listar("");

		assertThat(pagina.contenido()).extracting(DeportistaResumen::apellido).containsExactly("Alfa", "Beta", "Gamma");
		assertThat(enmascarados("")).containsExactly("••••345", "•••••322", "••••••567");
		JsonNode arbol = json.valueToTree(pagina);
		assertThat(JsonDeContrato.claves(arbol)).contains("dniEnmascarado").doesNotContain("dni");
		assertThat(JsonDeContrato.filtracionesDeDni(arbol, dnis)).isEmpty();
	}

	@Test
	void laBusquedaPorPrefijoSigueFuncionandoConElDniCompletoYMuestraElResultadoEnmascarado() {
		datos.deportista(escuelaA, "30111322", "Ana", "Alfa", true);
		datos.deportista(escuelaA, "30999111", "Beto", "Beta", true);
		datos.deportista(escuelaA, "44123456", "Cata", "Gamma", true);

		assertThat(enmascarados("30")).containsExactly("•••••322", "•••••111");
		assertThat(enmascarados("30.111")).as("con puntos").containsExactly("•••••322");
		assertThat(enmascarados("30111322")).as("DNI completo").containsExactly("•••••322");
		assertThat(enmascarados("441")).containsExactly("•••••456");
		assertThat(enmascarados("99")).as("no es prefijo de ninguno").isEmpty();
		assertThat(enmascarados("Gamma")).as("por apellido").containsExactly("•••••456");
		// Limitacion conocida y documentada: lo que se ve (los ultimos 3 digitos) no sirve para buscar (la busqueda es por prefijo).
		assertThat(enmascarados("322")).isEmpty();
	}

	@Test
	void elDetalleIndividualConservaElDniCompleto() {
		UUID id = datos.deportista(escuelaA, "30111322", "Ana", "Alfa", true);

		DeportistaDetalle detalle = deportistasAdmin.obtener(admin, id);

		assertThat(detalle.dni()).isEqualTo("30111322");
		assertThat(JsonDeContrato.claves(json.valueToTree(detalle))).contains("dni").doesNotContain("dniEnmascarado");
	}

	@Test
	void elDetalleDeFamiliaEnmascaraElDniDeSusTutoresYEsOpcionalYElIndividualLoConserva() {
		UUID familia = datos.familia(escuelaA, "Familia Gomez", true);
		TutorRespuesta conDni = tutoresAdmin.crear(admin, familia,
				new TutorSolicitud("Ana", "Gomez", "27.123.456", "11 5555 0000", "ana@example.test", "Madre"), DATOS);
		tutoresAdmin.crear(admin, familia, new TutorSolicitud("Luis", "Gomez", null, null, null, "Padre"), DATOS);
		tutoresAdmin.crear(admin, familia, new TutorSolicitud("Eva", "Gomez", "4412345", null, null, null), DATOS);

		FamiliaDetalle detalle = familiasAdmin.obtener(admin, familia);

		assertThat(detalle.tutores()).extracting(t -> t.nombre() + ":" + t.dniEnmascarado())
				.containsExactlyInAnyOrder("Ana:•••••456", "Luis:null", "Eva:••••345");
		JsonNode arbol = json.valueToTree(detalle);
		assertThat(JsonDeContrato.claves(arbol)).contains("dniEnmascarado").doesNotContain("dni");
		assertThat(JsonDeContrato.filtracionesDeDni(arbol, List.of("27123456", "4412345"))).isEmpty();
		// Tambien las respuestas de escritura de familia (activar) llevan el tutor enmascarado.
		FamiliaDetalle tras = familiasAdmin.activar(admin, familia, Long.valueOf(familiasAdmin.obtener(admin, familia).version()), DATOS);
		assertThat(JsonDeContrato.filtracionesDeDni(json.valueToTree(tras), List.of("27123456", "4412345"))).isEmpty();
		// El detalle individual del tutor SI conserva el DNI completo (y se guarda normalizado).
		assertThat(tutoresAdmin.obtener(admin, conDni.id()).dni()).isEqualTo("27123456");
	}

	@Test
	void laEntidadYElAlmacenamientoConservanElDniCompleto() {
		UUID id = datos.deportista(escuelaA, "30111322", "Ana", "Alfa", true);

		assertThat(jdbc.sql("SELECT dni FROM gestion_patin.deportista WHERE id = :i").param("i", id).query(String.class).single())
				.isEqualTo("30111322");
		assertThat(deportistasAdmin.listar(admin, FiltroEstado.TODOS, "", Pagina.pedir(0, 5)).contenido()).hasSize(1);
		assertThat(jdbc.sql("SELECT dni FROM gestion_patin.deportista WHERE id = :i").param("i", id).query(String.class).single())
				.as("listar no modifica lo almacenado").isEqualTo("30111322");
	}
}
