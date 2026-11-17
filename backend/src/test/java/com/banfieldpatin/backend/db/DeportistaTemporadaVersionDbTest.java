package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.temporadas.DeportistaTemporadaAdminService;
import com.banfieldpatin.backend.temporadas.EstadoDeportivo;
import com.banfieldpatin.backend.temporadas.dto.DeportistaTemporadaActualizacion;
import com.banfieldpatin.backend.temporadas.dto.DeportistaTemporadaRespuesta;
import com.banfieldpatin.backend.temporadas.dto.DeportistaTemporadaSolicitud;

/** Revision optimista por fila de los datos deportivos (FE-05/U9) contra PostgreSQL real, sin concurrencia de hilos. */
@PruebaDb
@Import(ConfigTemporadasDb.class)
@TestPropertySource(properties = { "spring.datasource.hikari.maximum-pool-size=5",
		"spring.datasource.hikari.minimum-idle=1", "spring.jpa.properties.hibernate.generate_statistics=true" })
class DeportistaTemporadaVersionDbTest extends BaseTemporadasDb {

	@Autowired
	DeportistaTemporadaAdminService dts;

	private static void assertError(Throwable e, HttpStatus estado, String codigo) {
		assertThat(e).isInstanceOfSatisfying(ExcepcionNegocio.class, n -> {
			assertThat(n.getEstado()).isEqualTo(estado);
			assertThat(n.getCodigo()).isEqualTo(codigo);
		});
	}

	private DeportistaTemporadaRespuesta alta(UUID d, UUID t, String categoria, String numero) {
		return dts.crear(admin, d, new DeportistaTemporadaSolicitud(t, categoria, "Femenino", "A", numero, null), DATOS);
	}

	private DeportistaTemporadaActualizacion cambio(String categoria, String numero, Long revision) {
		return new DeportistaTemporadaActualizacion(categoria, "Femenino", "A", numero, EstadoDeportivo.ACTIVO, revision);
	}

	private long revisionEnBase(UUID deportista, UUID temporada) {
		return jdbc.sql("SELECT version FROM gestion_patin.deportista_temporada WHERE deportista_id = :d AND temporada_id = :t")
				.param("d", deportista).param("t", temporada).query(Long.class).single();
	}

	private Object actualizadoEn(UUID deportista, UUID temporada) {
		return jdbc.sql("SELECT actualizado_en FROM gestion_patin.deportista_temporada WHERE deportista_id = :d AND temporada_id = :t")
				.param("d", deportista).param("t", temporada).query().singleValue();
	}

	@Test
	void unaFilaNuevaNaceEnLaRevisionCeroYLaConsultaYElHistorialLaExponenComoCadena() {
		UUID d = deportista(escuelaA, "Ana", "Perez", true);
		UUID t26 = datos.temporada(escuelaA, 2026, false);
		UUID t27 = datos.temporada(escuelaA, 2027, false);

		assertThat(alta(d, t27, "Sub 12", "007").version()).isEqualTo("0");
		alta(d, t26, "Sub 10", null);

		assertThat(revisionEnBase(d, t27)).isZero();
		assertThat(dts.obtener(admin, d, t27).version()).isEqualTo("0");
		assertThat(dts.historial(admin, d)).extracting(DeportistaTemporadaRespuesta::version).containsExactly("0", "0");
	}

	@Test
	void cadaEdicionRealAvanzaLaRevisionUnaVezYUnPutSinCambiosEfectivosNoLaMueveNiEscribe() {
		UUID d = deportista(escuelaA, "Ana", "Perez", true);
		UUID t = datos.temporada(escuelaA, 2027, false);
		alta(d, t, "Sub 12", "007");

		DeportistaTemporadaRespuesta primera = dts.actualizar(admin, d, t, cambio("Sub 14", "007", 0L), DATOS);
		assertThat(primera.version()).isEqualTo("1");
		assertThat(revisionEnBase(d, t)).isEqualTo(1L);

		Object antes = actualizadoEn(d, t);
		DeportistaTemporadaRespuesta sinCambios = dts.actualizar(admin, d, t, cambio("Sub 14", "007", 1L), DATOS);
		assertThat(sinCambios.version()).as("sin cambio efectivo la revision no se mueve").isEqualTo("1");
		assertThat(revisionEnBase(d, t)).isEqualTo(1L);
		assertThat(actualizadoEn(d, t)).as("sin UPDATE el trigger no toca actualizado_en").isEqualTo(antes);

		assertThat(dts.actualizar(admin, d, t, cambio("Sub 16", "007", 1L), DATOS).version()).isEqualTo("2");
		assertThat(contarAuditoria(escuelaA, "DEPORTISTA_TEMPORADA_ACTUALIZADA")).isEqualTo(2);
	}

	@Test
	void editarUnaTemporadaNoMueveLaRevisionDeOtraNiLaDeLaFichaPermanenteDelDeportista() {
		UUID d = deportista(escuelaA, "Ana", "Perez", true);
		UUID t26 = datos.temporada(escuelaA, 2026, false);
		UUID t27 = datos.temporada(escuelaA, 2027, false);
		alta(d, t26, "Sub 10", "5");
		alta(d, t27, "Sub 12", "7");
		long fichaAntes = jdbc.sql("SELECT version FROM gestion_patin.deportista WHERE id = :i").param("i", d).query(Long.class).single();

		dts.actualizar(admin, d, t27, cambio("Sub 14", "7", 0L), DATOS);
		dts.actualizar(admin, d, t27, cambio("Sub 16", "7", 1L), DATOS);

		assertThat(revisionEnBase(d, t27)).isEqualTo(2L);
		assertThat(revisionEnBase(d, t26)).as("la otra temporada del mismo deportista no se mueve").isZero();
		assertThat(jdbc.sql("SELECT version FROM gestion_patin.deportista WHERE id = :i").param("i", d).query(Long.class).single())
				.as("la revision de la ficha permanente es otra y no se toca").isEqualTo(fichaAntes);
		// La revision de la ficha tampoco sirve como revision deportiva: la fila de 2027 espera la suya (2), no la de la ficha.
		assertThatThrownBy(() -> dts.actualizar(admin, d, t27, cambio("Sub 18", "7", fichaAntes + 40L), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "EDICION_DESACTUALIZADA"));
	}

	@Test
	void unaRevisionObsoletaDa409AunqueLosValoresCoincidanYNoEscribeNiAudita() {
		UUID d = deportista(escuelaA, "Ana", "Perez", true);
		UUID t = datos.temporada(escuelaA, 2027, false);
		alta(d, t, "Sub 12", "007");
		dts.actualizar(admin, d, t, cambio("Sub 14", "007", 0L), DATOS);
		Object antes = actualizadoEn(d, t);

		// Mismos valores que ya tiene la fila, con la revision vieja: no se acepta en silencio.
		assertThatThrownBy(() -> dts.actualizar(admin, d, t, cambio("Sub 14", "007", 0L), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "EDICION_DESACTUALIZADA"));
		// Otros valores con la revision vieja: tampoco.
		assertThatThrownBy(() -> dts.actualizar(admin, d, t, cambio("Sub 18", "999", 0L), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "EDICION_DESACTUALIZADA"));

		assertThat(revisionEnBase(d, t)).isEqualTo(1L);
		assertThat(actualizadoEn(d, t)).isEqualTo(antes);
		assertThat(dts.obtener(admin, d, t).categoria()).isEqualTo("Sub 14");
		assertThat(contarAuditoria(escuelaA, "DEPORTISTA_TEMPORADA_ACTUALIZADA")).as("solo el exito se audita").isEqualTo(1);
	}

	@Test
	void unaRevisionAdelantadaOAusenteSeRechazaSinEscribirNiAuditar() {
		UUID d = deportista(escuelaA, "Ana", "Perez", true);
		UUID t = datos.temporada(escuelaA, 2027, false);
		alta(d, t, "Sub 12", null);

		assertThatThrownBy(() -> dts.actualizar(admin, d, t, cambio("Sub 14", null, 5L), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "EDICION_DESACTUALIZADA"));
		assertThatThrownBy(() -> dts.actualizar(admin, d, t, cambio("Sub 14", null, null), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.BAD_REQUEST, "VALIDACION"));

		assertThat(revisionEnBase(d, t)).isZero();
		assertThat(contarAuditoria(escuelaA, "DEPORTISTA_TEMPORADA_ACTUALIZADA")).isZero();
	}

	@Test
	void dosAdministradoresDesdeLaMismaRevisionDejanUnGanadorYElSegundoPuedeReintentarConLaVigente() {
		UUID d = deportista(escuelaA, "Ana", "Perez", true);
		UUID t = datos.temporada(escuelaA, 2027, false);
		alta(d, t, "Sub 12", "7");

		// Ambos leyeron la revision 0.
		DeportistaTemporadaRespuesta ganadora = dts.actualizar(admin, d, t, cambio("Sub 12", "9", 0L), DATOS);
		assertThatThrownBy(() -> dts.actualizar(admin, d, t, cambio("Sub 14", "7", 0L), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "EDICION_DESACTUALIZADA"));

		assertThat(ganadora.version()).isEqualTo("1");
		assertThat(dts.obtener(admin, d, t).numeroCorredor()).as("no se pierde la actualizacion de la ganadora").isEqualTo("9");
		// El segundo relee (revision 1) y reenvia sobre lo vigente.
		DeportistaTemporadaRespuesta reenviada = dts.actualizar(admin, d, t,
				cambio("Sub 14", dts.obtener(admin, d, t).numeroCorredor(), Long.parseLong(dts.obtener(admin, d, t).version())), DATOS);
		assertThat(reenviada.version()).isEqualTo("2");
		assertThat(reenviada.categoria()).isEqualTo("Sub 14");
		assertThat(contarAuditoria(escuelaA, "DEPORTISTA_TEMPORADA_ACTUALIZADA")).isEqualTo(2);
	}

	@Test
	void elConflictoDeNumeroDeCorredorConservaSuCodigoConLaRevisionVigenteYLaRevisionManda_siEsObsoleta() {
		UUID d1 = deportista(escuelaA, "Ana", "Perez", true);
		UUID d2 = deportista(escuelaA, "Eva", "Gomez", true);
		UUID t = datos.temporada(escuelaA, 2027, false);
		alta(d1, t, "Sub 12", "7");
		alta(d2, t, "Sub 12", "8");

		assertThatThrownBy(() -> dts.actualizar(admin, d2, t, cambio("Sub 12", "7", 0L), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "NUMERO_CORREDOR_DUPLICADO"));
		assertThat(revisionEnBase(d2, t)).as("el conflicto de unicidad no mueve la revision").isZero();

		dts.actualizar(admin, d2, t, cambio("Sub 14", "8", 0L), DATOS); // d2 pasa a la revision 1
		assertThatThrownBy(() -> dts.actualizar(admin, d2, t, cambio("Sub 14", "7", 0L), DATOS))
				.as("obsoleta Y con numero tomado: manda la revision")
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "EDICION_DESACTUALIZADA"));
	}

	@Test
	void aislamientoEntreEscuelasUnaFilaAjenaDa404ConCualquierRevisionYNoSeMueve() {
		UUID dA = deportista(escuelaA, "Ana", "Perez", true);
		UUID dB = deportista(escuelaB, "Eva", "Gomez", true);
		UUID tA = datos.temporada(escuelaA, 2027, false);
		UUID tB = datos.temporada(escuelaB, 2027, false);
		alta(dA, tA, "Sub 12", "7");
		dts.crear(adminDeB, dB, new DeportistaTemporadaSolicitud(tB, "Sub 12", null, null, "7", null), DATOS);

		assertThatThrownBy(() -> dts.actualizar(adminDeB, dA, tA, cambio("Sub 14", "7", 0L), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.NOT_FOUND, "DEPORTISTA_NO_ENCONTRADO"));
		assertThatThrownBy(() -> dts.actualizar(adminDeB, dA, tA, cambio("Sub 14", "7", 99L), DATOS))
				.as("tampoco filtra la revision de una fila ajena").satisfies(e -> assertError(e, HttpStatus.NOT_FOUND, "DEPORTISTA_NO_ENCONTRADO"));
		assertThat(revisionEnBase(dA, tA)).isZero();

		// La edicion de la fila de A no mueve la de B (el mismo numero de corredor y la misma temporada de otra escuela).
		dts.actualizar(admin, dA, tA, cambio("Sub 14", "7", 0L), DATOS);
		assertThat(revisionEnBase(dA, tA)).isEqualTo(1L);
		assertThat(revisionEnBase(dB, tB)).isZero();
	}

	@Test
	void laBaseImpideUnaRevisionNegativaONulaYConservaLasUnicidades() {
		UUID d = deportista(escuelaA, "Ana", "Perez", true);
		UUID t = datos.temporada(escuelaA, 2027, false);
		UUID fila = alta(d, t, "Sub 12", "7").id();

		assertThatThrownBy(() -> jdbc.sql("UPDATE gestion_patin.deportista_temporada SET version = -1 WHERE id = :i").param("i", fila).update())
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.sql("UPDATE gestion_patin.deportista_temporada SET version = NULL WHERE id = :i").param("i", fila).update())
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(revisionEnBase(d, t)).isZero();
		assertThatThrownBy(() -> alta(d, t, "Otra", null)).satisfies(e -> assertError(e, HttpStatus.CONFLICT, "DEPORTISTA_TEMPORADA_DUPLICADA"));
	}

	@Test
	void elPlantelConservaSuContratoYNoTraeLaRevision() {
		UUID d = deportista(escuelaA, "Ana", "Perez", true);
		UUID t = datos.temporada(escuelaA, 2027, false);
		alta(d, t, "Sub 12", "7");

		var plantel = dts.plantel(admin, t, null, org.springframework.data.domain.PageRequest.of(0, 20));

		assertThat(plantel.contenido()).hasSize(1);
		assertThat(List.of(plantel.contenido().get(0).getClass().getRecordComponents()).stream().map(c -> c.getName()))
				.doesNotContain("version");
	}
}
