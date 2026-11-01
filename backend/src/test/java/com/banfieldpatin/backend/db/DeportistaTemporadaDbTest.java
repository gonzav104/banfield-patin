package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.Pagina;
import com.banfieldpatin.backend.temporadas.DeportistaTemporadaAdminService;
import com.banfieldpatin.backend.temporadas.EstadoDeportivo;
import com.banfieldpatin.backend.temporadas.dto.DeportistaTemporadaRespuesta;
import com.banfieldpatin.backend.temporadas.dto.DeportistaTemporadaSolicitud;
import com.banfieldpatin.backend.temporadas.dto.PlantelRespuesta;

/**
 * Datos deportivos por temporada contra PostgreSQL real: alta, consulta, aislamiento por escuela, restricciones de V1,
 * auditoria sin datos personales y cantidad de consultas (sin N+1).
 */
@PruebaDb
@Import(ConfigTemporadasDb.class)
@TestPropertySource(properties = { "spring.datasource.hikari.maximum-pool-size=5",
		"spring.datasource.hikari.minimum-idle=1", "spring.jpa.properties.hibernate.generate_statistics=true" })
class DeportistaTemporadaDbTest extends BaseTemporadasDb {

	@org.springframework.beans.factory.annotation.Autowired
	DeportistaTemporadaAdminService dts;

	private static void assertError(Throwable e, HttpStatus estado, String codigo) {
		assertThat(e).isInstanceOfSatisfying(ExcepcionNegocio.class, n -> {
			assertThat(n.getEstado()).isEqualTo(estado);
			assertThat(n.getCodigo()).isEqualTo(codigo);
		});
	}

	private static DeportistaTemporadaSolicitud alta(UUID temporada, String categoria, String numero) {
		return new DeportistaTemporadaSolicitud(temporada, categoria, "Femenino", "A", numero, null);
	}

	private String filaDeportista(UUID deportistaId) {
		return jdbc.sql("SELECT d::text FROM gestion_patin.deportista d WHERE d.id = :i").param("i", deportistaId)
				.query(String.class).single();
	}

	// ---------- alta ----------

	@Test
	void elAltaPersisteLosDatosEnUnaTemporadaINACTIVAConCerosInicialesYSinTocarLaFichaPermanente() {
		UUID d = deportista(escuelaA, "Ana", "Perez", true);
		UUID inactiva = datos.temporada(escuelaA, 2027, false);
		String fichaAntes = filaDeportista(d);

		DeportistaTemporadaRespuesta r = dts.crear(admin, d, alta(inactiva, " Sub  12 ", " 007 "), DATOS);

		assertThat(r.anio()).isEqualTo(2027);
		assertThat(r.categoria()).isEqualTo("Sub 12");
		assertThat(r.numeroCorredor()).isEqualTo("007");
		assertThat(r.estadoDeportivo()).isEqualTo(EstadoDeportivo.ACTIVO);
		Map<String, Object> fila = jdbc.sql("SELECT * FROM gestion_patin.deportista_temporada WHERE id = :i")
				.param("i", r.id()).query().singleRow();
		assertThat(fila).containsEntry("escuela_id", escuelaA).containsEntry("numero_corredor", "007")
				.containsEntry("categoria", "Sub 12").containsEntry("estado_deportivo", "ACTIVO");
		assertThat(filaDeportista(d)).as("la ficha permanente no cambia").isEqualTo(fichaAntes);
	}

	@Test
	void laAuditoriaDelAltaTieneSoloIdsYAnioSinElNumeroNiLaCategoria() {
		UUID d = deportista(escuelaA, "Ana", "Perez", true);
		UUID t = datos.temporada(escuelaA, 2027, true);

		DeportistaTemporadaRespuesta r = dts.crear(admin, d, alta(t, "Sub 12", "007"), DATOS);

		Map<String, Object> evento = jdbc.sql("""
				SELECT recurso_tipo, recurso_id, usuario_id, detalle::text AS detalle, host(ip) AS ip
				FROM gestion_patin.auditoria WHERE escuela_id = :e AND accion = 'DEPORTISTA_TEMPORADA_CREADA'
				""").param("e", escuelaA).query().singleRow();
		assertThat(evento).containsEntry("recurso_tipo", "DEPORTISTA_TEMPORADA").containsEntry("recurso_id", r.id())
				.containsEntry("usuario_id", admin.id()).containsEntry("ip", "10.0.0.9");
		assertThat((String) evento.get("detalle")).contains(t.toString(), "2027")
				.doesNotContain("007", "Sub 12", "Femenino");
	}

	@Test
	void unaTemporadaDeOtraEscuelaOUnDeportistaDeOtraEscuelaSonIndistinguiblesDeInexistentes() {
		UUID d = deportista(escuelaA, "Ana", "Perez", true);
		UUID deB = deportista(escuelaB, "Eva", "Gomez", true);
		UUID tA = datos.temporada(escuelaA, 2027, false);
		UUID tB = datos.temporada(escuelaB, 2027, false);

		assertThatThrownBy(() -> dts.crear(admin, d, alta(tB, null, null), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.NOT_FOUND, "TEMPORADA_NO_ENCONTRADA"));
		assertThatThrownBy(() -> dts.crear(admin, deB, alta(tA, null, null), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.NOT_FOUND, "DEPORTISTA_NO_ENCONTRADO"));
		assertThatThrownBy(() -> dts.historial(admin, deB))
				.satisfies(e -> assertError(e, HttpStatus.NOT_FOUND, "DEPORTISTA_NO_ENCONTRADO"));
		assertThatThrownBy(() -> dts.plantel(admin, tB, null, PageRequest.of(0, 20)))
				.satisfies(e -> assertError(e, HttpStatus.NOT_FOUND, "TEMPORADA_NO_ENCONTRADA"));
		assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.deportista_temporada").query(Long.class).single()).isZero();
	}

	@Test
	void unDeportistaInactivoNoSumaFilasNuevasPeroSuHistorialSeSigueLeyendo() {
		UUID d = deportista(escuelaA, "Ana", "Perez", true);
		UUID t26 = datos.temporada(escuelaA, 2026, false);
		UUID t27 = datos.temporada(escuelaA, 2027, false);
		dts.crear(admin, d, alta(t26, "Sub 10", null), DATOS);
		jdbc.sql("UPDATE gestion_patin.deportista SET activo = false WHERE id = :i").param("i", d).update();

		assertThatThrownBy(() -> dts.crear(admin, d, alta(t27, "Sub 12", null), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "DEPORTISTA_INACTIVO"));
		assertThat(dts.historial(admin, d)).extracting(DeportistaTemporadaRespuesta::anio).containsExactly(2026);
	}

	// ---------- unicidad ----------

	@Test
	void unSegundoAltaDelMismoParDa409YNoDejaAuditoria() {
		UUID d = deportista(escuelaA, "Ana", "Perez", true);
		UUID t = datos.temporada(escuelaA, 2027, false);
		dts.crear(admin, d, alta(t, "Sub 12", null), DATOS);

		assertThatThrownBy(() -> dts.crear(admin, d, alta(t, "Sub 14", null), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "DEPORTISTA_TEMPORADA_DUPLICADA"));

		assertThat(contarAuditoria(escuelaA, "DEPORTISTA_TEMPORADA_CREADA")).isEqualTo(1);
	}

	@Test
	void elNumeroDeCorredorEsUnicoPorTemporadaPeroSeRepiteEnOtraTemporadaOtraEscuelaYComoNulo() {
		UUID d1 = deportista(escuelaA, "Ana", "Perez", true);
		UUID d2 = deportista(escuelaA, "Eva", "Gomez", true);
		UUID d3 = deportista(escuelaA, "Luz", "Diaz", true);
		UUID dB = deportista(escuelaB, "Pia", "Ruiz", true);
		UUID t26 = datos.temporada(escuelaA, 2026, false);
		UUID t27 = datos.temporada(escuelaA, 2027, false);
		UUID tB = datos.temporada(escuelaB, 2027, false);
		dts.crear(admin, d1, alta(t27, null, "7"), DATOS);

		assertThatThrownBy(() -> dts.crear(admin, d2, alta(t27, null, "7"), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "NUMERO_CORREDOR_DUPLICADO"));
		// "07" y "7" son distintos (texto opaco); el mismo numero en otra temporada o escuela no choca; varios nulos tampoco.
		dts.crear(admin, d2, alta(t27, null, "07"), DATOS);
		dts.crear(admin, d1, alta(t26, null, "7"), DATOS);
		dts.crear(adminDeB, dB, alta(tB, null, "7"), DATOS);
		dts.crear(admin, d3, alta(t27, null, null), DATOS);
		UUID d4 = deportista(escuelaA, "Ona", "Paz", true);
		dts.crear(admin, d4, alta(t27, null, null), DATOS);

		assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.deportista_temporada WHERE temporada_id = :t")
				.param("t", t27).query(Long.class).single()).isEqualTo(4);
	}

	// ---------- restricciones de V1 (red de seguridad de la base) ----------

	private void insertar(UUID escuela, UUID deportista, UUID temporada, String numero, String estado) {
		jdbc.sql("""
				INSERT INTO gestion_patin.deportista_temporada
				    (escuela_id, deportista_id, temporada_id, numero_corredor, estado_deportivo)
				VALUES (:e, :d, :t, :n, :s)
				""").param("e", escuela).param("d", deportista).param("t", temporada).param("n", numero).param("s", estado)
				.update();
	}

	@Test
	void laBaseRechazaCruzarEscuelasUnParRepetidoUnNumeroRepetidoYUnEstadoInvalido() {
		UUID d = deportista(escuelaA, "Ana", "Perez", true);
		UUID d2 = deportista(escuelaA, "Eva", "Gomez", true);
		UUID tA = datos.temporada(escuelaA, 2027, false);
		UUID tB = datos.temporada(escuelaB, 2027, false);
		insertar(escuelaA, d, tA, "7", "ACTIVO");

		assertThatThrownBy(() -> insertar(escuelaA, d, tB, null, "ACTIVO")).isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("fk_dt_temporada_misma_escuela");
		assertThatThrownBy(() -> insertar(escuelaB, d, tB, null, "ACTIVO")).isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("fk_dt_deportista_misma_escuela");
		assertThatThrownBy(() -> insertar(escuelaA, d, tA, null, "ACTIVO")).isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("uq_deportista_temporada");
		assertThatThrownBy(() -> insertar(escuelaA, d2, tA, "7", "ACTIVO")).isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("uq_numero_corredor_por_temporada");
		assertThatThrownBy(() -> insertar(escuelaA, d2, tA, null, "RETIRADO")).isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_deportista_temporada_estado");
	}

	// ---------- consulta ----------

	@Test
	void elHistorialVaDelAnioMasNuevoAlMasViejoYNoMezclaDeportistasNiEscuelas() {
		UUID d = deportista(escuelaA, "Ana", "Perez", true);
		UUID otro = deportista(escuelaA, "Eva", "Gomez", true);
		UUID t26 = datos.temporada(escuelaA, 2026, false);
		UUID t28 = datos.temporada(escuelaA, 2028, false);
		UUID t27 = datos.temporada(escuelaA, 2027, true);
		dts.crear(admin, d, alta(t26, "Sub 10", null), DATOS);
		dts.crear(admin, d, alta(t28, "Sub 14", null), DATOS);
		dts.crear(admin, d, alta(t27, "Sub 12", null), DATOS);
		dts.crear(admin, otro, alta(t27, "Sub 12", null), DATOS);

		assertThat(dts.historial(admin, d)).extracting(DeportistaTemporadaRespuesta::anio).containsExactly(2028, 2027, 2026);
		assertThat(dts.obtener(admin, d, t27).categoria()).isEqualTo("Sub 12");
		assertThatThrownBy(() -> dts.obtener(admin, otro, t26))
				.satisfies(e -> assertError(e, HttpStatus.NOT_FOUND, "DEPORTISTA_TEMPORADA_NO_ENCONTRADA"));
		assertThatThrownBy(() -> dts.obtener(adminDeB, d, t27))
				.satisfies(e -> assertError(e, HttpStatus.NOT_FOUND, "DEPORTISTA_NO_ENCONTRADO"));
	}

	@Test
	void elPlantelVaPorApellidoYNombreFiltraPorEstadoPaginaYNoMuestraDniNiOtraEscuela() {
		UUID t = datos.temporada(escuelaA, 2027, true);
		UUID tB = datos.temporada(escuelaB, 2027, true);
		UUID zeta = deportista(escuelaA, "Ana", "Zeta", true);
		UUID alfa = deportista(escuelaA, "Luz", "Alfa", true);
		UUID beta = deportista(escuelaA, "Eva", "Beta", true);
		dts.crear(admin, zeta, alta(t, "Sub 12", "9"), DATOS);
		dts.crear(admin, alfa, new DeportistaTemporadaSolicitud(t, null, null, null, null, EstadoDeportivo.SUSPENDIDO), DATOS);
		dts.crear(admin, beta, alta(t, "Sub 14", null), DATOS);
		dts.crear(adminDeB, deportista(escuelaB, "Pia", "Ajena", true), alta(tB, null, null), DATOS);

		Pagina<PlantelRespuesta> todos = dts.plantel(admin, t, null, PageRequest.of(0, 20));
		assertThat(todos.contenido()).extracting(PlantelRespuesta::apellido).containsExactly("Alfa", "Beta", "Zeta");
		assertThat(todos.totalElementos()).isEqualTo(3);
		assertThat(todos.contenido().get(2).numeroCorredor()).isEqualTo("9");
		assertThat(PlantelRespuesta.class.getRecordComponents()).extracting(c -> c.getName()).doesNotContain("dni", "escuelaId");
		assertThat(dts.plantel(admin, t, "SUSPENDIDO", PageRequest.of(0, 20)).contenido()).extracting(PlantelRespuesta::apellido)
				.containsExactly("Alfa");
		Pagina<PlantelRespuesta> segunda = dts.plantel(admin, t, "TODOS", PageRequest.of(1, 2));
		assertThat(segunda.contenido()).extracting(PlantelRespuesta::apellido).containsExactly("Zeta");
		assertThat(segunda.totalElementos()).isEqualTo(3);
	}

	@Test
	void cambiarDatosDeUnAnioNoTocaLosDeOtro() {
		UUID d = deportista(escuelaA, "Ana", "Perez", true);
		UUID t26 = datos.temporada(escuelaA, 2026, false);
		UUID t27 = datos.temporada(escuelaA, 2027, false);
		dts.crear(admin, d, alta(t26, "Sub 10", "5"), DATOS);

		dts.crear(admin, d, alta(t27, "Sub 12", "7"), DATOS);

		DeportistaTemporadaRespuesta de2026 = dts.obtener(admin, d, t26);
		assertThat(de2026.categoria()).isEqualTo("Sub 10");
		assertThat(de2026.numeroCorredor()).isEqualTo("5");
	}

	// ---------- sin N+1 ----------

	@Test
	void laCantidadDeConsultasDelHistorialYDelPlantelNoCrecenConLasFilas() {
		UUID t = datos.temporada(escuelaA, 2027, true);
		UUID unico = deportista(escuelaA, "Ana", "Perez", true);
		dts.crear(admin, unico, alta(t, "Sub 12", null), DATOS);
		long historialConUna = sentencias(() -> dts.historial(admin, unico));
		long plantelConUna = sentencias(() -> dts.plantel(admin, t, null, PageRequest.of(0, 20)));

		for (int anio = 2020; anio < 2026; anio++) {
			dts.crear(admin, unico, alta(datos.temporada(escuelaA, anio, false), "Sub 12", null), DATOS);
		}
		List<UUID> mas = new java.util.ArrayList<>();
		for (int i = 0; i < 11; i++) {
			UUID otro = deportista(escuelaA, "N" + i, "Apellido" + i, true);
			mas.add(otro);
			dts.crear(admin, otro, alta(t, "Sub 12", null), DATOS);
		}

		assertThat(dts.historial(admin, unico)).hasSize(7);
		assertThat(sentencias(() -> dts.historial(admin, unico))).as("historial con 7 filas").isEqualTo(historialConUna);
		assertThat(dts.plantel(admin, t, null, PageRequest.of(0, 20)).contenido()).hasSize(12);
		assertThat(sentencias(() -> dts.plantel(admin, t, null, PageRequest.of(0, 20)))).as("plantel con 12 filas")
				.isEqualTo(plantelConUna);
		// Pagina llena: se agrega el conteo (una sentencia mas) pero no una por fila.
		assertThat(sentencias(() -> dts.plantel(admin, t, null, PageRequest.of(0, 5)))).as("plantel con conteo")
				.isLessThanOrEqualTo(plantelConUna + 1);
	}
}
