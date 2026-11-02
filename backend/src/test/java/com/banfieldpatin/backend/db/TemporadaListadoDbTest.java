package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.banfieldpatin.backend.compartido.web.Pagina;
import com.banfieldpatin.backend.temporadas.TemporadaAdminService;
import com.banfieldpatin.backend.temporadas.dto.TemporadaRespuesta;

/**
 * Consultas del LISTADO y del detalle ADMIN de temporadas: una temporada no tiene asociaciones, asi que el listado es una
 * consulta de pagina (mas su conteo cuando la pagina esta llena) y el detalle una sola sentencia, sin importar cuantas filas
 * haya. Mismo contexto cacheado que las demas pruebas de BD de temporadas con estadisticas de Hibernate.
 */
@PruebaDb
@Import(ConfigTemporadasDb.class)
@TestPropertySource(properties = { "spring.datasource.hikari.maximum-pool-size=5",
		"spring.datasource.hikari.minimum-idle=1", "spring.jpa.properties.hibernate.generate_statistics=true" })
class TemporadaListadoDbTest extends BaseTemporadasDb {

	@Autowired
	TemporadaAdminService temporadas;

	@Test
	void laCantidadDeSentenciasDelListadoYDelDetalleNoCreceConLaCantidadDeTemporadas() {
		UUID una = datos.temporada(escuelaA, 2026, true);
		long listadoConUna = sentencias(() -> temporadas.listar(admin, Pagina.pedir(0, 20)));
		long detalleConUna = sentencias(() -> temporadas.obtener(admin, una));
		for (int anio = 2027; anio < 2039; anio++) {
			datos.temporada(escuelaA, anio, false);
		}

		Pagina<TemporadaRespuesta> pagina = temporadas.listar(admin, Pagina.pedir(0, 20));

		assertThat(pagina.contenido()).hasSize(13);
		assertThat(sentencias(() -> temporadas.listar(admin, Pagina.pedir(0, 20)))).as("listado con 13 filas")
				.isEqualTo(listadoConUna).isEqualTo(1);
		// Pagina llena: se agrega el conteo (una sentencia mas), nunca una consulta por fila.
		assertThat(sentencias(() -> temporadas.listar(admin, Pagina.pedir(0, 5)))).as("listado con conteo")
				.isLessThanOrEqualTo(listadoConUna + 1);
		assertThat(sentencias(() -> temporadas.obtener(admin, una))).as("detalle").isEqualTo(detalleConUna).isEqualTo(1);
	}

	@Test
	void elListadoNoMezclaEscuelasNiAlteraElOrdenDescendentePorAnio() {
		datos.temporada(escuelaA, 2026, true);
		datos.temporada(escuelaA, 2028, false);
		datos.temporada(escuelaB, 2030, false);

		Pagina<TemporadaRespuesta> deA = temporadas.listar(admin, Pagina.pedir(0, 20));
		Pagina<TemporadaRespuesta> deB = temporadas.listar(adminDeB, Pagina.pedir(0, 20));

		assertThat(deA.contenido()).extracting(TemporadaRespuesta::anio).containsExactly(2028, 2026);
		assertThat(deB.contenido()).extracting(TemporadaRespuesta::anio).containsExactly(2030);
	}
}
