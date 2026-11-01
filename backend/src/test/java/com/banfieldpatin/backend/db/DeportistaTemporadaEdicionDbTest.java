package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.temporadas.DeportistaTemporadaAdminService;
import com.banfieldpatin.backend.temporadas.EstadoDeportivo;
import com.banfieldpatin.backend.temporadas.dto.DeportistaTemporadaActualizacion;
import com.banfieldpatin.backend.temporadas.dto.DeportistaTemporadaRespuesta;
import com.banfieldpatin.backend.temporadas.dto.DeportistaTemporadaSolicitud;
import com.banfieldpatin.backend.temporadas.dto.PlantelRespuesta;

/** Edicion (PUT) de los datos deportivos contra PostgreSQL real. */
@PruebaDb
@Import(ConfigTemporadasDb.class)
@TestPropertySource(properties = { "spring.datasource.hikari.maximum-pool-size=5",
		"spring.datasource.hikari.minimum-idle=1", "spring.jpa.properties.hibernate.generate_statistics=true" })
class DeportistaTemporadaEdicionDbTest extends BaseTemporadasDb {

	@Autowired
	DeportistaTemporadaAdminService dts;

	private static void assertError(Throwable e, HttpStatus estado, String codigo) {
		assertThat(e).isInstanceOfSatisfying(ExcepcionNegocio.class, n -> {
			assertThat(n.getEstado()).isEqualTo(estado);
			assertThat(n.getCodigo()).isEqualTo(codigo);
		});
	}

	private DeportistaTemporadaRespuesta alta(UsuarioDeEscuela u, UUID d, UUID t, String categoria, String numero) {
		return dts.crear(u.admin(), d, new DeportistaTemporadaSolicitud(t, categoria, "Femenino", "A", numero, null), DATOS);
	}

	/** Pequeno ayudante para no repetir el actor en cada alta. */
	private record UsuarioDeEscuela(com.banfieldpatin.backend.seguridad.UsuarioAutenticado admin) {
	}

	private DeportistaTemporadaActualizacion cambio(String categoria, String numero, EstadoDeportivo estado) {
		return new DeportistaTemporadaActualizacion(categoria, "Femenino", "A", numero, estado);
	}

	private String filaDeportista(UUID id) {
		return jdbc.sql("SELECT d::text FROM gestion_patin.deportista d WHERE d.id = :i").param("i", id).query(String.class)
				.single();
	}

	private Map<String, Object> filaDt(UUID id) {
		return jdbc.sql("SELECT * FROM gestion_patin.deportista_temporada WHERE id = :i").param("i", id).query().singleRow();
	}

	@Test
	void editarCambiaSoloEsaFilaConservaCerosYNoToca2026NiLaFichaPermanente() {
		UUID d = deportista(escuelaA, "Ana", "Perez", true);
		UUID t26 = datos.temporada(escuelaA, 2026, false);
		UUID t27 = datos.temporada(escuelaA, 2027, false);
		DeportistaTemporadaRespuesta de26 = alta(new UsuarioDeEscuela(admin), d, t26, "Sub 10", "5");
		DeportistaTemporadaRespuesta de27 = alta(new UsuarioDeEscuela(admin), d, t27, "Sub 12", "7");
		String ficha = filaDeportista(d);
		Map<String, Object> antes26 = filaDt(de26.id());

		DeportistaTemporadaRespuesta r = dts.actualizar(admin, d, t27, cambio("  Sub   14 ", " 008 ", EstadoDeportivo.SUSPENDIDO), DATOS);

		assertThat(r.id()).isEqualTo(de27.id());
		assertThat(r.categoria()).isEqualTo("Sub 14");
		assertThat(r.numeroCorredor()).isEqualTo("008");
		assertThat(r.estadoDeportivo()).isEqualTo(EstadoDeportivo.SUSPENDIDO);
		assertThat(r.anio()).isEqualTo(2027);
		assertThat(filaDt(de26.id())).as("2026 queda intacta").isEqualTo(antes26);
		assertThat(filaDeportista(d)).as("la ficha permanente no cambia").isEqualTo(ficha);
		assertThat(dts.obtener(admin, d, t27).categoria()).isEqualTo("Sub 14");
	}

	@Test
	void laAuditoriaTieneNombresDeCamposYElCambioDeEstadoPeroNingunValorDeportivo() {
		UUID d = deportista(escuelaA, "Ana", "Perez", true);
		UUID t = datos.temporada(escuelaA, 2027, false);
		DeportistaTemporadaRespuesta creada = alta(new UsuarioDeEscuela(admin), d, t, "Sub 12", "007");

		dts.actualizar(admin, d, t, cambio("Sub 14", "009", EstadoDeportivo.SUSPENDIDO), DATOS);

		Map<String, Object> evento = jdbc.sql("""
				SELECT recurso_id, usuario_id, detalle::text AS detalle FROM gestion_patin.auditoria
				WHERE escuela_id = :e AND accion = 'DEPORTISTA_TEMPORADA_ACTUALIZADA'
				""").param("e", escuelaA).query().singleRow();
		assertThat(evento).containsEntry("recurso_id", creada.id()).containsEntry("usuario_id", admin.id());
		assertThat((String) evento.get("detalle")).contains("categoria", "numeroCorredor", "estadoDeportivo", "ACTIVO",
				"SUSPENDIDO", t.toString()).doesNotContain("Sub 14", "Sub 12", "007", "009", "Femenino");
	}

	@Test
	void unPutSinCambiosRealesNoEscribeNiAudita() {
		UUID d = deportista(escuelaA, "Ana", "Perez", true);
		UUID t = datos.temporada(escuelaA, 2027, false);
		DeportistaTemporadaRespuesta creada = alta(new UsuarioDeEscuela(admin), d, t, "Sub 12", "007");
		Object antes = jdbc.sql("SELECT actualizado_en FROM gestion_patin.deportista_temporada WHERE id = :i")
				.param("i", creada.id()).query().singleValue();

		dts.actualizar(admin, d, t, cambio("Sub 12", "007", EstadoDeportivo.ACTIVO), DATOS);

		assertThat(jdbc.sql("SELECT actualizado_en FROM gestion_patin.deportista_temporada WHERE id = :i")
				.param("i", creada.id()).query().singleValue()).as("sin UPDATE el trigger no toca actualizado_en").isEqualTo(antes);
		assertThat(contarAuditoria(escuelaA, "DEPORTISTA_TEMPORADA_ACTUALIZADA")).isZero();
	}

	@Test
	void unNumeroTomadoDa409ConservarElPropioOQuitarloNoYElMismoNumeroEnOtraTemporadaSePermite() {
		UUID d1 = deportista(escuelaA, "Ana", "Perez", true);
		UUID d2 = deportista(escuelaA, "Eva", "Gomez", true);
		UUID t26 = datos.temporada(escuelaA, 2026, false);
		UUID t27 = datos.temporada(escuelaA, 2027, false);
		alta(new UsuarioDeEscuela(admin), d1, t27, null, "7");
		alta(new UsuarioDeEscuela(admin), d2, t27, null, "8");
		alta(new UsuarioDeEscuela(admin), d2, t26, null, null);

		assertThatThrownBy(() -> dts.actualizar(admin, d2, t27, cambio(null, "7", EstadoDeportivo.ACTIVO), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "NUMERO_CORREDOR_DUPLICADO"));
		assertThat(dts.actualizar(admin, d2, t27, cambio("Sub 12", "8", EstadoDeportivo.ACTIVO), DATOS).numeroCorredor())
				.isEqualTo("8");
		assertThat(dts.actualizar(admin, d2, t27, cambio("Sub 12", null, EstadoDeportivo.ACTIVO), DATOS).numeroCorredor())
				.isNull();
		assertThat(dts.actualizar(admin, d2, t26, cambio(null, "7", EstadoDeportivo.ACTIVO), DATOS).numeroCorredor())
				.isEqualTo("7");
		assertThat(dts.obtener(admin, d2, t27).numeroCorredor()).as("la fila rechazada no se modifico").isNull();
	}

	@Test
	void sePuedeEditarEnUnaTemporadaInactivaYConUnDeportistaInactivo() {
		UUID d = deportista(escuelaA, "Ana", "Perez", true);
		UUID t = datos.temporada(escuelaA, 2027, false);
		alta(new UsuarioDeEscuela(admin), d, t, "Sub 12", null);
		jdbc.sql("UPDATE gestion_patin.deportista SET activo = false WHERE id = :i").param("i", d).update();

		DeportistaTemporadaRespuesta r = dts.actualizar(admin, d, t, cambio("Sub 14", null, EstadoDeportivo.INACTIVO), DATOS);

		assertThat(r.categoria()).isEqualTo("Sub 14");
		assertThat(r.estadoDeportivo()).isEqualTo(EstadoDeportivo.INACTIVO);
	}

	@Test
	void editarUnaFilaAjenaOInexistenteDa404SinCambiosYElEstadoNuevoSeVeEnElPlantel() {
		UUID d = deportista(escuelaA, "Ana", "Perez", true);
		UUID dB = deportista(escuelaB, "Eva", "Gomez", true);
		UUID t = datos.temporada(escuelaA, 2027, false);
		UUID tSinFila = datos.temporada(escuelaA, 2028, false);
		alta(new UsuarioDeEscuela(admin), d, t, "Sub 12", null);

		assertThatThrownBy(() -> dts.actualizar(adminDeB, d, t, cambio(null, null, EstadoDeportivo.ACTIVO), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.NOT_FOUND, "DEPORTISTA_NO_ENCONTRADO"));
		assertThatThrownBy(() -> dts.actualizar(admin, dB, t, cambio(null, null, EstadoDeportivo.ACTIVO), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.NOT_FOUND, "DEPORTISTA_NO_ENCONTRADO"));
		assertThatThrownBy(() -> dts.actualizar(admin, d, UUID.randomUUID(), cambio(null, null, EstadoDeportivo.ACTIVO), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.NOT_FOUND, "TEMPORADA_NO_ENCONTRADA"));
		assertThatThrownBy(() -> dts.actualizar(admin, d, tSinFila, cambio(null, null, EstadoDeportivo.ACTIVO), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.NOT_FOUND, "DEPORTISTA_TEMPORADA_NO_ENCONTRADA"));

		dts.actualizar(admin, d, t, cambio("Sub 12", null, EstadoDeportivo.SUSPENDIDO), DATOS);
		assertThat(dts.plantel(admin, t, "SUSPENDIDO", PageRequest.of(0, 20)).contenido())
				.extracting(PlantelRespuesta::apellido).containsExactly("Perez");
		assertThat(dts.plantel(admin, t, "ACTIVO", PageRequest.of(0, 20)).contenido()).isEmpty();
	}
}
