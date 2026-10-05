package com.banfieldpatin.backend.deportistas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import com.banfieldpatin.backend.FixturesDominio;
import com.banfieldpatin.backend.compartido.auditoria.AccionAuditoria;
import com.banfieldpatin.backend.compartido.auditoria.AuditoriaService;
import com.banfieldpatin.backend.compartido.auditoria.EventoAuditoria;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.compartido.web.FiltroEstado;
import com.banfieldpatin.backend.compartido.web.Pagina;
import com.banfieldpatin.backend.deportistas.dto.DeportistaDetalle;
import com.banfieldpatin.backend.deportistas.dto.DeportistaResumen;
import com.banfieldpatin.backend.deportistas.dto.DeportistaSolicitud;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;

class DeportistaAdminServiceTest {

	private static final DatosSolicitud DATOS = new DatosSolicitud("203.0.113.9", "JUnit");
	// Cadenas propias de la prueba: la auditoria se comprueba contra ellas.
	private static final String DNI = "30111222";
	private static final String DNI_CRUDO = "30.111.222";
	private static final String CUIL = "20301112220";
	private static final String CUIL_CRUDO = "20-30111222-0";
	private static final LocalDate NACIMIENTO = LocalDate.of(2012, 5, 10);

	private final UUID escuelaId = UUID.randomUUID();
	private final UUID adminId = UUID.randomUUID();
	private final UsuarioAutenticado admin = new UsuarioAutenticado(adminId, escuelaId, null, Rol.ADMIN);
	private final UUID id = UUID.randomUUID();

	private DeportistaRepository deportistas;
	private AuditoriaService auditoria;
	private DeportistaAdminService servicio;

	@BeforeEach
	void preparar() {
		deportistas = mock(DeportistaRepository.class);
		auditoria = mock(AuditoriaService.class);
		servicio = new DeportistaAdminService(deportistas, auditoria);
		when(deportistas.saveAndFlush(any(Deportista.class))).thenAnswer(inv -> {
			Deportista d = inv.getArgument(0);
			if (d.getId() == null) {
				ReflectionTestUtils.setField(d, "id", UUID.randomUUID());
			}
			return d;
		});
		when(deportistas.activoPorDni(any(), any())).thenReturn(Optional.empty());
		when(deportistas.activoPorDniDeOtro(any(), any(), any())).thenReturn(Optional.empty());
	}

	private DeportistaSolicitud solicitud(String dni, String cuil, String localidad) {
		return new DeportistaSolicitud(dni, "Juan", "Perez", cuil, NACIMIENTO, "Argentina", "Calle 1", null, localidad,
				null, null, "11-5555-0000", "juan@example.com");
	}

	private Deportista existente() {
		Deportista d = Deportista.crear(escuelaId, "Juan", "Perez", DNI, CUIL, NACIMIENTO, "Argentina", "Calle 1", null,
				"Banfield", null, null, "11-5555-0000", "juan@example.com");
		ReflectionTestUtils.setField(d, "id", id);
		when(deportistas.findByIdAndEscuelaId(id, escuelaId)).thenReturn(Optional.of(d));
		return d;
	}

	private EventoAuditoria unicoEvento() {
		ArgumentCaptor<EventoAuditoria> captor = ArgumentCaptor.forClass(EventoAuditoria.class);
		verify(auditoria).registrar(captor.capture());
		return captor.getValue();
	}

	private static void assertError(Throwable e, HttpStatus estado, String codigo) {
		assertThat(e).isInstanceOfSatisfying(ExcepcionNegocio.class, ex -> {
			assertThat(ex.getEstado()).isEqualTo(estado);
			assertThat(ex.getCodigo()).isEqualTo(codigo);
		});
	}

	private static DataIntegrityViolationException violacion(String restriccion) {
		return new DataIntegrityViolationException("conflicto", new ConstraintViolationException("duplicado",
				new SQLException("x"), "insert ...", ConstraintKind.UNIQUE, restriccion));
	}

	// ---------- crear ----------

	@Test
	void crearTomaLaEscuelaDelTokenNormalizaDniYCuilYDejaElDeportistaActivo() {
		DeportistaDetalle creado = servicio.crear(admin, solicitud(DNI_CRUDO, CUIL_CRUDO, "Banfield"), DATOS);

		ArgumentCaptor<Deportista> guardado = ArgumentCaptor.forClass(Deportista.class);
		verify(deportistas).saveAndFlush(guardado.capture());
		assertThat(guardado.getValue().getEscuelaId()).isEqualTo(escuelaId);
		assertThat(guardado.getValue().getDni()).isEqualTo(DNI);
		assertThat(guardado.getValue().getCuil()).isEqualTo(CUIL);
		assertThat(guardado.getValue().isActivo()).isTrue();
		assertThat(creado.dni()).isEqualTo(DNI);
		assertThat(creado.cuil()).isEqualTo(CUIL);
		assertThat(creado.activo()).isTrue();
		// Las comprobaciones previas se hacen con los valores normalizados y la escuela del token.
		verify(deportistas).activoPorDni(escuelaId, DNI);
		verify(deportistas).existsByEscuelaIdAndCuil(escuelaId, CUIL);
	}

	@Test
	void crearAuditaDespuesDeGuardarConSoloElIndicadorDeCuil() {
		servicio.crear(admin, solicitud(DNI_CRUDO, CUIL_CRUDO, "Banfield"), DATOS);

		InOrder orden = inOrder(deportistas, auditoria);
		orden.verify(deportistas).saveAndFlush(any(Deportista.class));
		orden.verify(auditoria).registrar(any());
		EventoAuditoria evento = unicoEvento();
		assertThat(evento.accion()).isEqualTo(AccionAuditoria.DEPORTISTA_CREADO);
		assertThat(evento.recursoTipo()).isEqualTo("DEPORTISTA");
		assertThat(evento.escuelaId()).isEqualTo(escuelaId);
		assertThat(evento.usuarioId()).isEqualTo(adminId);
		assertThat(evento.solicitud()).isEqualTo(DATOS);
		assertThat(evento.detalle()).containsOnly(Map.entry("conCuil", true));
		assertThat(evento.detalle().toString()).doesNotContain(DNI).doesNotContain(CUIL).doesNotContain("Juan")
				.doesNotContain("Perez").doesNotContain("Calle 1");
	}

	@Test
	void crearSinCuilNoConsultaElCuilYAuditaConCuilFalso() {
		servicio.crear(admin, solicitud(DNI, null, null), DATOS);

		verify(deportistas, never()).existsByEscuelaIdAndCuil(any(), anyString());
		assertThat(unicoEvento().detalle()).containsOnly(Map.entry("conCuil", false));
	}

	@Test
	void crearConElDniDeUnDeportistaActivoDa409DniDuplicadoSinGuardarNiAuditar() {
		when(deportistas.activoPorDni(escuelaId, DNI)).thenReturn(Optional.of(true));

		assertThatThrownBy(() -> servicio.crear(admin, solicitud(DNI_CRUDO, null, null), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "DNI_DUPLICADO"));

		verify(deportistas, never()).saveAndFlush(any());
		verifyNoInteractions(auditoria);
	}

	@Test
	void crearConElDniDeUnDeportistaInactivoDa409DniReservadoPorInactivoConElMensajeDeReactivar() {
		when(deportistas.activoPorDni(escuelaId, DNI)).thenReturn(Optional.of(false));

		assertThatThrownBy(() -> servicio.crear(admin, solicitud(DNI_CRUDO, null, null), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "DNI_RESERVADO_POR_INACTIVO"))
				.hasMessage("Ya existe un deportista inactivo con ese DNI. Reactivalo en lugar de crear uno nuevo.");

		verify(deportistas, never()).saveAndFlush(any());
		verifyNoInteractions(auditoria);
	}

	@Test
	void crearConUnCuilYaUsadoDa409CuilDuplicadoSinGuardarNiAuditar() {
		when(deportistas.existsByEscuelaIdAndCuil(escuelaId, CUIL)).thenReturn(true);

		assertThatThrownBy(() -> servicio.crear(admin, solicitud(DNI, CUIL_CRUDO, null), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "CUIL_DUPLICADO"));

		verify(deportistas, never()).saveAndFlush(any());
		verifyNoInteractions(auditoria);
	}

	@Test
	void elMensajeDeLos409NoIncluyeDniCuilNiIds() {
		when(deportistas.activoPorDni(escuelaId, DNI)).thenReturn(Optional.of(true));
		when(deportistas.existsByEscuelaIdAndCuil(escuelaId, CUIL)).thenReturn(true);

		assertThatThrownBy(() -> servicio.crear(admin, solicitud(DNI_CRUDO, null, null), DATOS))
				.hasMessageNotContaining(DNI).hasMessageNotContaining(escuelaId.toString())
				.hasMessageNotContaining("uq_deportista");
		when(deportistas.activoPorDni(escuelaId, DNI)).thenReturn(Optional.empty());
		assertThatThrownBy(() -> servicio.crear(admin, solicitud(DNI, CUIL_CRUDO, null), DATOS))
				.hasMessageNotContaining(CUIL).hasMessageNotContaining("uq_deportista");
	}

	@Test
	void unaViolacionDeLaRestriccionDeDniEnElFlushSeMapeaADniDuplicadoSinAuditar() {
		when(deportistas.saveAndFlush(any(Deportista.class))).thenThrow(violacion("uq_deportista_dni_escuela"));

		assertThatThrownBy(() -> servicio.crear(admin, solicitud(DNI, null, null), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "DNI_DUPLICADO"))
				.hasMessage("Ya existe un deportista con ese DNI.");

		verifyNoInteractions(auditoria);
	}

	@Test
	void unaViolacionDeLaRestriccionDeCuilEnElFlushSeMapeaACuilDuplicadoSinAuditar() {
		when(deportistas.saveAndFlush(any(Deportista.class))).thenThrow(violacion("uq_deportista_cuil_escuela"));

		assertThatThrownBy(() -> servicio.crear(admin, solicitud(DNI, CUIL, null), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "CUIL_DUPLICADO"));

		verifyNoInteractions(auditoria);
	}

	@Test
	void cualquierOtraViolacionDeIntegridadSeRelanzaParaQueSeaUn500() {
		DataIntegrityViolationException otra = violacion("otra_restriccion");
		DataIntegrityViolationException sinNombre = new DataIntegrityViolationException("sin causa de hibernate");
		when(deportistas.saveAndFlush(any(Deportista.class))).thenThrow(otra);

		assertThatThrownBy(() -> servicio.crear(admin, solicitud(DNI, null, null), DATOS)).isSameAs(otra);

		when(deportistas.saveAndFlush(any(Deportista.class))).thenThrow(sinNombre);
		assertThatThrownBy(() -> servicio.crear(admin, solicitud(DNI, null, null), DATOS)).isSameAs(sinNombre);
		verifyNoInteractions(auditoria);
	}

	// ---------- lectura ----------

	@Test
	void obtenerDeOtraEscuelaOInexistenteDaElMismo404() {
		when(deportistas.findByIdAndEscuelaId(any(), any())).thenReturn(Optional.empty());

		for (UUID otro : new UUID[] { id, UUID.randomUUID() }) {
			assertThatThrownBy(() -> servicio.obtener(admin, otro)).isInstanceOfSatisfying(ExcepcionNegocio.class, e -> {
				assertThat(e.getEstado()).isEqualTo(HttpStatus.NOT_FOUND);
				assertThat(e.getCodigo()).isEqualTo("DEPORTISTA_NO_ENCONTRADO");
				assertThat(e.getMessage()).isEqualTo("El deportista no existe.");
			});
		}
		verify(deportistas).findByIdAndEscuelaId(id, escuelaId);
	}

	@Test
	void obtenerDevuelveElDetalleDeUnDeportistaInactivo() {
		Deportista d = existente();
		d.desactivar();

		DeportistaDetalle detalle = servicio.obtener(admin, id);

		assertThat(detalle.id()).isEqualTo(id);
		assertThat(detalle.activo()).isFalse();
		verifyNoInteractions(auditoria);
	}

	@Test
	void listarPasaLaEscuelaDelTokenElEstadoElPatronEscapadoYLosDigitosDelDni() {
		Deportista d = FixturesDominio.deportista(id, escuelaId, DNI, "Juan", "Perez", true);
		Page<Deportista> pagina = new PageImpl<>(List.of(d), PageRequest.of(0, 20), 1);
		when(deportistas.buscar(any(), anyString(), anyString(), anyString(), any())).thenReturn(pagina);

		Pagina<DeportistaResumen> r = servicio.listar(admin, FiltroEstado.TODOS, " 12.345 ", Pagina.pedir(0, 20));

		verify(deportistas).buscar(eq(escuelaId), eq("TODOS"), eq("12.345"), eq("12345"), any());
		assertThat(r.totalElementos()).isEqualTo(1);
		assertThat(r.contenido().get(0).id()).isEqualTo(id);
		assertThat(r.contenido().get(0).dni()).isEqualTo(DNI);

		servicio.listar(admin, FiltroEstado.ACTIVOS, "100%_Perez!", Pagina.pedir(0, 20));
		verify(deportistas).buscar(eq(escuelaId), eq("ACTIVOS"), eq("100!%!_Perez!!"), eq(""), any());
	}

	// ---------- actualizar ----------

	@Test
	void actualizarConUnCampoNuevoAuditaSoloSuNombre() {
		Deportista d = existente();

		DeportistaDetalle r = servicio.actualizar(admin, id, solicitud(DNI, CUIL, "Temperley"), DATOS);

		assertThat(r.localidad()).isEqualTo("Temperley");
		assertThat(d.getLocalidad()).isEqualTo("Temperley");
		verify(deportistas).saveAndFlush(d);
		EventoAuditoria evento = unicoEvento();
		assertThat(evento.accion()).isEqualTo(AccionAuditoria.DEPORTISTA_ACTUALIZADO);
		assertThat(evento.recursoId()).isEqualTo(id);
		assertThat(evento.detalle()).containsOnly(Map.entry("camposModificados", List.of("localidad")));
		assertThat(evento.detalle().toString()).doesNotContain("Temperley").doesNotContain(DNI).doesNotContain(CUIL);
	}

	@Test
	void actualizarDniYTelefonoAuditaSusNombresSinValores() {
		existente();

		servicio.actualizar(admin, id, new DeportistaSolicitud("30999888", "Juan", "Perez", CUIL, NACIMIENTO, null, null,
				null, null, null, null, "11-9999-0000", null), DATOS);

		EventoAuditoria evento = unicoEvento();
		@SuppressWarnings("unchecked")
		List<String> campos = (List<String>) evento.detalle().get("camposModificados");
		assertThat(campos).contains("dni", "telefonoContacto");
		assertThat(evento.detalle().toString()).doesNotContain("30999888").doesNotContain("11-9999-0000");
	}

	@Test
	void actualizarConLosMismosDatosNoEscribeNiAuditaNiConsultaUnicidad() {
		existente();

		// DNI y CUIL escritos con separadores: tras normalizar son los mismos.
		servicio.actualizar(admin, id, solicitud(DNI_CRUDO, CUIL_CRUDO, "Banfield"), DATOS);

		verify(deportistas, never()).saveAndFlush(any());
		verify(deportistas, never()).activoPorDniDeOtro(any(), any(), any());
		verify(deportistas, never()).existsByEscuelaIdAndCuilAndIdNot(any(), any(), any());
		verifyNoInteractions(auditoria);
	}

	@Test
	void conservarElPropioDniYCuilNoEsUnConflictoAunqueOtroCambie() {
		existente();

		servicio.actualizar(admin, id, solicitud(DNI, CUIL, "Temperley"), DATOS);

		verify(deportistas, never()).activoPorDniDeOtro(any(), any(), any());
		verify(deportistas, never()).existsByEscuelaIdAndCuilAndIdNot(any(), any(), any());
		verify(deportistas).saveAndFlush(any(Deportista.class));
	}

	@Test
	void cambiarElDniAUnoLibreComprobandoLaUnicidadIgnorandoAlPropioDeportista() {
		Deportista d = existente();

		servicio.actualizar(admin, id, solicitud("30.999.888", CUIL, "Banfield"), DATOS);

		verify(deportistas).activoPorDniDeOtro(escuelaId, "30999888", id);
		assertThat(d.getDni()).isEqualTo("30999888");
		assertThat(unicoEvento().detalle()).containsOnly(Map.entry("camposModificados", List.of("dni")));
	}

	@Test
	void cambiarElDniAlDeOtroActivoDa409DniDuplicadoYAlDeUnInactivoDniReservadoSinCambiarNada() {
		Deportista d = existente();
		when(deportistas.activoPorDniDeOtro(escuelaId, "30999888", id)).thenReturn(Optional.of(true));
		when(deportistas.activoPorDniDeOtro(escuelaId, "30888777", id)).thenReturn(Optional.of(false));

		assertThatThrownBy(() -> servicio.actualizar(admin, id, solicitud("30999888", CUIL, "Banfield"), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "DNI_DUPLICADO"));
		assertThatThrownBy(() -> servicio.actualizar(admin, id, solicitud("30888777", CUIL, "Banfield"), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "DNI_RESERVADO_POR_INACTIVO"));

		assertThat(d.getDni()).isEqualTo(DNI);
		verify(deportistas, never()).saveAndFlush(any());
		verifyNoInteractions(auditoria);
	}

	@Test
	void cambiarElCuilAlDeOtroDa409CuilDuplicadoSinCambiarNada() {
		Deportista d = existente();
		when(deportistas.existsByEscuelaIdAndCuilAndIdNot(escuelaId, "27234567891", id)).thenReturn(true);

		assertThatThrownBy(() -> servicio.actualizar(admin, id, solicitud(DNI, "27-23456789-1", "Banfield"), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "CUIL_DUPLICADO"));

		assertThat(d.getCuil()).isEqualTo(CUIL);
		verify(deportistas, never()).saveAndFlush(any());
		verifyNoInteractions(auditoria);
	}

	@Test
	void unaViolacionEnElFlushDeLaEdicionSeMapeaPorNombreDeRestriccionYNoAudita() {
		existente();
		when(deportistas.saveAndFlush(any(Deportista.class))).thenThrow(violacion("uq_deportista_dni_escuela"));

		assertThatThrownBy(() -> servicio.actualizar(admin, id, solicitud("30999888", CUIL, "Banfield"), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "DNI_DUPLICADO"));

		when(deportistas.saveAndFlush(any(Deportista.class))).thenThrow(violacion("uq_deportista_cuil_escuela"));
		assertThatThrownBy(() -> servicio.actualizar(admin, id, solicitud("30999888", "27234567891", "Banfield"), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "CUIL_DUPLICADO"));
		verifyNoInteractions(auditoria);
	}

	@Test
	void actualizarUnDeportistaAjenoDa404SinEscribirNiAuditar() {
		when(deportistas.findByIdAndEscuelaId(id, escuelaId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> servicio.actualizar(admin, id, solicitud(DNI, null, null), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.NOT_FOUND, "DEPORTISTA_NO_ENCONTRADO"));

		verify(deportistas, never()).saveAndFlush(any());
		verifyNoInteractions(auditoria);
	}

	@Test
	void actualizarNoCambiaElEstadoDelDeportista() {
		Deportista d = existente();
		d.desactivar();

		servicio.actualizar(admin, id, solicitud(DNI, CUIL, "Temperley"), DATOS);

		assertThat(d.isActivo()).isFalse();
	}

	// ---------- activar / desactivar ----------

	@Test
	void desactivarCambiaElEstadoAuditaYDevuelveElDetalle() {
		Deportista d = existente();

		DeportistaDetalle r = servicio.desactivar(admin, id, DATOS);

		assertThat(r.activo()).isFalse();
		assertThat(d.isActivo()).isFalse();
		verify(deportistas).saveAndFlush(d);
		EventoAuditoria evento = unicoEvento();
		assertThat(evento.accion()).isEqualTo(AccionAuditoria.DEPORTISTA_DESACTIVADO);
		assertThat(evento.detalle()).isEmpty();
	}

	@Test
	void desactivarDeNuevoEsIdempotenteSinEscribirNiAuditarOtraVez() {
		existente().desactivar();

		DeportistaDetalle r = servicio.desactivar(admin, id, DATOS);

		assertThat(r.activo()).isFalse();
		verify(deportistas, never()).saveAndFlush(any());
		verifyNoInteractions(auditoria);
	}

	@Test
	void activarUnInactivoLoReactivaYAudita() {
		Deportista d = existente();
		d.desactivar();

		DeportistaDetalle r = servicio.activar(admin, id, DATOS);

		assertThat(r.activo()).isTrue();
		assertThat(unicoEvento().accion()).isEqualTo(AccionAuditoria.DEPORTISTA_ACTIVADO);
	}

	@Test
	void activarUnActivoEsIdempotenteSinEscribirNiAuditar() {
		existente();

		assertThat(servicio.activar(admin, id, DATOS).activo()).isTrue();

		verify(deportistas, never()).saveAndFlush(any());
		verifyNoInteractions(auditoria);
	}

	@Test
	void activarYDesactivarDeUnDeportistaAjenoDan404() {
		when(deportistas.findByIdAndEscuelaId(id, escuelaId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> servicio.activar(admin, id, DATOS))
				.satisfies(e -> assertError(e, HttpStatus.NOT_FOUND, "DEPORTISTA_NO_ENCONTRADO"));
		assertThatThrownBy(() -> servicio.desactivar(admin, id, DATOS))
				.satisfies(e -> assertError(e, HttpStatus.NOT_FOUND, "DEPORTISTA_NO_ENCONTRADO"));

		verifyNoInteractions(auditoria);
	}

	@Test
	void elServicioNoTieneDependenciaConVinculosNiFamiliasPorLoQueNuncaLosToca() {
		// F4: activar/desactivar y editar solo hablan con el repositorio de deportistas y la auditoria.
		assertThat(DeportistaAdminService.class.getDeclaredConstructors()).hasSize(1);
		assertThat(DeportistaAdminService.class.getDeclaredConstructors()[0].getParameterTypes())
				.containsExactly(DeportistaRepository.class, AuditoriaService.class);
	}
}
