package com.banfieldpatin.backend.familias.vinculos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import com.banfieldpatin.backend.FixturesDominio;
import com.banfieldpatin.backend.compartido.auditoria.AccionAuditoria;
import com.banfieldpatin.backend.compartido.auditoria.AuditoriaService;
import com.banfieldpatin.backend.compartido.auditoria.EventoAuditoria;
import com.banfieldpatin.backend.compartido.error.DetalleError;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.deportistas.Deportista;
import com.banfieldpatin.backend.deportistas.DeportistaRepository;
import com.banfieldpatin.backend.familias.FamiliaRepository;
import com.banfieldpatin.backend.familias.vinculos.dto.ResultadoVinculacion;
import com.banfieldpatin.backend.familias.vinculos.dto.VinculacionRespuesta;
import com.banfieldpatin.backend.familias.vinculos.dto.VinculoRespuesta;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;

class VinculoAdminServiceTest {

	private static final DatosSolicitud DATOS = new DatosSolicitud("203.0.113.9", "JUnit");
	private static final Instant AHORA = Instant.parse("2026-05-05T12:00:00Z");

	private final UUID escuelaId = UUID.randomUUID();
	private final UUID adminId = UUID.randomUUID();
	private final UsuarioAutenticado admin = new UsuarioAutenticado(adminId, escuelaId, null, Rol.ADMIN);
	private final UUID familiaId = UUID.randomUUID();
	private final UUID d1 = UUID.randomUUID();
	private final UUID d2 = UUID.randomUUID();

	private FamiliaRepository familias;
	private DeportistaRepository deportistas;
	private FamiliaDeportistaRepository vinculos;
	private AuditoriaService auditoria;
	private VinculoAdminService servicio;
	/** Estado (id, esPrincipal) de cada entidad en el momento exacto de cada saveAndFlush. */
	private final List<String> escrituras = new ArrayList<>();

	@BeforeEach
	void preparar() {
		familias = mock(FamiliaRepository.class);
		deportistas = mock(DeportistaRepository.class);
		vinculos = mock(FamiliaDeportistaRepository.class);
		auditoria = mock(AuditoriaService.class);
		servicio = new VinculoAdminService(familias, deportistas, vinculos, auditoria,
				Clock.fixed(AHORA, ZoneOffset.UTC));
		when(vinculos.saveAndFlush(any(FamiliaDeportista.class))).thenAnswer(inv -> {
			FamiliaDeportista fd = inv.getArgument(0);
			if (fd.getId() == null) {
				ReflectionTestUtils.setField(fd, "id", UUID.randomUUID());
			}
			escrituras.add(fd.getDeportistaId() + ":" + fd.getEstado() + ":" + fd.isEsPrincipal());
			return fd;
		});
	}

	// ---------- fixtures ----------

	private void familia(boolean activa) {
		when(familias.findByIdAndEscuelaId(familiaId, escuelaId))
				.thenReturn(Optional.of(FixturesDominio.familia(familiaId, escuelaId, activa)));
	}

	private Deportista deportista(UUID id, boolean activo) {
		return FixturesDominio.deportista(id, escuelaId, "3011" + id.toString().substring(0, 4), "Nombre", "Apellido",
				activo);
	}

	private void bloqueables(Deportista... encontrados) {
		when(deportistas.bloquearParaVincular(eq(escuelaId), any())).thenReturn(List.of(encontrados));
	}

	private FamiliaDeportista vinculo(UUID deportistaId, EstadoVinculo estado, boolean principal) {
		return FixturesDominio.vinculo(UUID.randomUUID(), escuelaId, familiaId, deportistaId, estado, principal);
	}

	private void existentes(FamiliaDeportista... existentes) {
		when(vinculos.deFamiliaYDeportistas(eq(escuelaId), eq(familiaId), any())).thenReturn(List.of(existentes));
	}

	private void principales(UUID... deportistaIds) {
		when(vinculos.principalesActivos(eq(escuelaId), any())).thenReturn(List.of(deportistaIds));
	}

	private List<EventoAuditoria> eventos(int cantidad) {
		ArgumentCaptor<EventoAuditoria> captor = ArgumentCaptor.forClass(EventoAuditoria.class);
		verify(auditoria, org.mockito.Mockito.times(cantidad)).registrar(captor.capture());
		return captor.getAllValues();
	}

	private static void assertError(Throwable e, HttpStatus estado, String codigo) {
		assertThat(e).isInstanceOfSatisfying(ExcepcionNegocio.class, ex -> {
			assertThat(ex.getEstado()).isEqualTo(estado);
			assertThat(ex.getCodigo()).isEqualTo(codigo);
		});
	}

	private static DataIntegrityViolationException violacion(String restriccion) {
		return new DataIntegrityViolationException("no se imprime",
				new ConstraintViolationException("no se imprime", new SQLException("no se imprime"), "insert ...",
						ConstraintKind.UNIQUE, restriccion));
	}

	// ---------- vincular: camino feliz ----------

	@Test
	void vincularCreaVinculosActivosConDedupeEnOrdenAutorizacionYUnaAuditoriaPorVinculo() {
		familia(true);
		bloqueables(deportista(d1, true), deportista(d2, true));
		existentes();
		principales();

		VinculacionRespuesta respuesta = servicio.vincular(admin, familiaId, List.of(d2, d1, d2), DATOS);

		assertThat(respuesta.resultados()).extracting(r -> r.vinculo().deportistaId()).containsExactly(d2, d1);
		assertThat(respuesta.resultados()).extracting(r -> r.resultado())
				.containsExactly(ResultadoVinculacion.CREADO, ResultadoVinculacion.CREADO);
		assertThat(respuesta.resultados()).allSatisfy(r -> {
			assertThat(r.vinculo().estado()).isEqualTo(EstadoVinculo.ACTIVO);
			assertThat(r.vinculo().esPrincipal()).isTrue();
			assertThat(r.vinculo().autorizadoEn()).isEqualTo(AHORA);
			assertThat(r.vinculo().familiaId()).isEqualTo(familiaId);
		});
		ArgumentCaptor<FamiliaDeportista> guardados = ArgumentCaptor.forClass(FamiliaDeportista.class);
		verify(vinculos, org.mockito.Mockito.times(2)).saveAndFlush(guardados.capture());
		assertThat(guardados.getAllValues()).allSatisfy(fd -> {
			assertThat(fd.getEscuelaId()).isEqualTo(escuelaId);
			assertThat(fd.getAutorizadoPor()).isEqualTo(adminId);
			assertThat(fd.getAutorizadoEn()).isEqualTo(AHORA);
		});
		List<EventoAuditoria> auditados = eventos(2);
		assertThat(auditados).allSatisfy(e -> {
			assertThat(e.accion()).isEqualTo(AccionAuditoria.VINCULO_ACTIVADO);
			assertThat(e.recursoTipo()).isEqualTo("FAMILIA_DEPORTISTA");
			assertThat(e.escuelaId()).isEqualTo(escuelaId);
			assertThat(e.usuarioId()).isEqualTo(adminId);
			assertThat(e.detalle()).containsEntry("familiaId", familiaId).containsEntry("esPrincipal", true)
					.containsEntry("origen", "NUEVO").containsOnlyKeys("familiaId", "deportistaId", "esPrincipal", "origen");
		});
		assertThat(auditados).extracting(e -> e.detalle().get("deportistaId")).containsExactly(d2, d1);
	}

	@Test
	void vincularSigueElOrdenFamiliaBloqueoConsultasEscrituras() {
		familia(true);
		bloqueables(deportista(d1, true));
		existentes();
		principales();

		servicio.vincular(admin, familiaId, List.of(d1), DATOS);

		InOrder orden = inOrder(familias, deportistas, vinculos, auditoria);
		orden.verify(familias).findByIdAndEscuelaId(familiaId, escuelaId);
		orden.verify(deportistas).bloquearParaVincular(eq(escuelaId), any());
		orden.verify(vinculos).deFamiliaYDeportistas(eq(escuelaId), eq(familiaId), any());
		orden.verify(vinculos).principalesActivos(eq(escuelaId), any());
		orden.verify(vinculos).saveAndFlush(any(FamiliaDeportista.class));
		orden.verify(auditoria).registrar(any(EventoAuditoria.class));
	}

	@Test
	void elLoteSeBloqueaSoloUnaVezConLosIdsSinDuplicadosYUnaSolaConsultaPorTipo() {
		familia(true);
		bloqueables(deportista(d1, true), deportista(d2, true));
		existentes();
		principales();

		servicio.vincular(admin, familiaId, List.of(d1, d2, d1, d2, d1), DATOS);

		verify(deportistas).bloquearParaVincular(escuelaId, List.of(d1, d2));
		verify(vinculos).deFamiliaYDeportistas(escuelaId, familiaId, List.of(d1, d2));
		verify(vinculos).principalesActivos(escuelaId, List.of(d1, d2));
	}

	@Test
	void vincularEsPrincipalSoloSiElDeportistaNoTieneOtroPrincipalActivo() {
		familia(true);
		bloqueables(deportista(d1, true), deportista(d2, true));
		existentes();
		principales(d1);

		VinculacionRespuesta respuesta = servicio.vincular(admin, familiaId, List.of(d1, d2), DATOS);

		assertThat(respuesta.resultados().get(0).vinculo().esPrincipal()).isFalse();
		assertThat(respuesta.resultados().get(1).vinculo().esPrincipal()).isTrue();
		assertThat(eventos(2)).extracting(e -> e.detalle().get("esPrincipal")).containsExactly(false, true);
	}

	@Test
	void unVinculoRevocadoSeReutilizaConElMismoIdYUnaAutorizacionNuevaYAuditaReutilizado() {
		familia(true);
		bloqueables(deportista(d1, true));
		FamiliaDeportista revocado = vinculo(d1, EstadoVinculo.REVOCADO, false);
		UUID id = revocado.getId();
		existentes(revocado);
		principales();

		VinculacionRespuesta respuesta = servicio.vincular(admin, familiaId, List.of(d1), DATOS);

		assertThat(respuesta.resultados()).singleElement().satisfies(r -> {
			assertThat(r.resultado()).isEqualTo(ResultadoVinculacion.REACTIVADO);
			assertThat(r.vinculo().vinculoId()).isEqualTo(id);
			assertThat(r.vinculo().estado()).isEqualTo(EstadoVinculo.ACTIVO);
			assertThat(r.vinculo().esPrincipal()).isTrue();
		});
		assertThat(revocado.getAutorizadoPor()).isEqualTo(adminId);
		assertThat(revocado.getAutorizadoEn()).isEqualTo(AHORA);
		assertThat(eventos(1).get(0).detalle()).containsEntry("origen", "REUTILIZADO");
	}

	@Test
	void pendienteYRechazadoTambienSeReutilizanYElPrincipalViejoDeLaFilaNoSobrevive() {
		familia(true);
		bloqueables(deportista(d1, true), deportista(d2, true));
		FamiliaDeportista pendiente = vinculo(d1, EstadoVinculo.PENDIENTE, true);
		FamiliaDeportista rechazado = vinculo(d2, EstadoVinculo.RECHAZADO, true);
		existentes(pendiente, rechazado);
		principales(d1, d2);

		VinculacionRespuesta respuesta = servicio.vincular(admin, familiaId, List.of(d1, d2), DATOS);

		assertThat(respuesta.resultados()).extracting(r -> r.resultado())
				.containsExactly(ResultadoVinculacion.REACTIVADO, ResultadoVinculacion.REACTIVADO);
		assertThat(pendiente.isEsPrincipal()).isFalse();
		assertThat(rechazado.isEsPrincipal()).isFalse();
		assertThat(pendiente.getEstado()).isEqualTo(EstadoVinculo.ACTIVO);
	}

	@Test
	void unVinculoYaActivoEsSinCambiosSinEscrituraNiAuditoriaYElRestoSiSeProcesa() {
		familia(true);
		bloqueables(deportista(d1, true), deportista(d2, true));
		FamiliaDeportista activo = vinculo(d1, EstadoVinculo.ACTIVO, true);
		existentes(activo);
		principales(d1);

		VinculacionRespuesta respuesta = servicio.vincular(admin, familiaId, List.of(d1, d2), DATOS);

		assertThat(respuesta.resultados()).extracting(r -> r.resultado())
				.containsExactly(ResultadoVinculacion.SIN_CAMBIOS, ResultadoVinculacion.CREADO);
		assertThat(respuesta.resultados().get(0).vinculo().vinculoId()).isEqualTo(activo.getId());
		assertThat(escrituras).hasSize(1);
		assertThat(eventos(1).get(0).detalle()).containsEntry("deportistaId", d2);
	}

	// ---------- vincular: precedencia de errores ----------

	@Test
	void unaFamiliaAjenaOInexistenteDa404SinTocarNada() {
		when(familias.findByIdAndEscuelaId(familiaId, escuelaId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> servicio.vincular(admin, familiaId, List.of(d1), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.NOT_FOUND, "FAMILIA_NO_ENCONTRADA"));

		verifyNoInteractions(deportistas, vinculos, auditoria);
	}

	@Test
	void unaFamiliaInactivaDa409AntesDeBloquearIncluidoUnLoteTodoSinCambios() {
		familia(false);

		assertThatThrownBy(() -> servicio.vincular(admin, familiaId, List.of(d1, d2), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "FAMILIA_INACTIVA"));

		verifyNoInteractions(deportistas, vinculos, auditoria);
	}

	@Test
	void algunIdInexistenteOAjenoDa404ConCadaPosicionYSinEscribirNiAuditarNiEcoDeValores() {
		familia(true);
		UUID ajeno = UUID.randomUUID();
		bloqueables(deportista(d1, true));

		assertThatThrownBy(() -> servicio.vincular(admin, familiaId, List.of(ajeno, d1, ajeno), DATOS))
				.satisfies(e -> {
					assertError(e, HttpStatus.NOT_FOUND, "DEPORTISTA_NO_ENCONTRADO");
					List<DetalleError> detalles = ((ExcepcionNegocio) e).getDetalles();
					assertThat(detalles).extracting(DetalleError::campo).containsExactly("deportistaIds[0]",
							"deportistaIds[2]");
					assertThat(detalles.toString() + e.getMessage()).doesNotContain(ajeno.toString());
				});

		verify(vinculos, never()).saveAndFlush(any());
		verify(vinculos, never()).deFamiliaYDeportistas(any(), any(), any());
		verifyNoInteractions(auditoria);
	}

	@Test
	void algunDeportistaInactivoDa409ConLaPosicionYNoEscribeNadaAunqueHayaOtrosValidos() {
		familia(true);
		bloqueables(deportista(d1, true), deportista(d2, false));

		assertThatThrownBy(() -> servicio.vincular(admin, familiaId, List.of(d1, d2), DATOS)).satisfies(e -> {
			assertError(e, HttpStatus.CONFLICT, "DEPORTISTA_INACTIVO");
			assertThat(((ExcepcionNegocio) e).getDetalles()).extracting(DetalleError::campo)
					.containsExactly("deportistaIds[1]");
		});

		verify(vinculos, never()).saveAndFlush(any());
		verifyNoInteractions(auditoria);
	}

	@Test
	void unIdInexistenteTienePrioridadSobreUnDeportistaInactivo() {
		familia(true);
		bloqueables(deportista(d1, false));

		assertThatThrownBy(() -> servicio.vincular(admin, familiaId, List.of(d1, UUID.randomUUID()), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.NOT_FOUND, "DEPORTISTA_NO_ENCONTRADO"));
	}

	// ---------- vincular: carreras que gana la base ----------

	@Test
	void unaViolacionDelIndiceDePrincipalDa409ConflictoYNoAuditaEseVinculo() {
		familia(true);
		bloqueables(deportista(d1, true));
		existentes();
		principales();
		when(vinculos.saveAndFlush(any(FamiliaDeportista.class))).thenThrow(violacion("uq_fd_principal_activo"));

		assertThatThrownBy(() -> servicio.vincular(admin, familiaId, List.of(d1), DATOS)).satisfies(e -> {
			assertError(e, HttpStatus.CONFLICT, "VINCULO_PRINCIPAL_EN_CONFLICTO");
			assertThat(e.getMessage()).isEqualTo("Otro cambio sobre el mismo deportista ocurrio al mismo tiempo. Reintentá.");
		});

		verifyNoInteractions(auditoria);
	}

	@Test
	void unaViolacionDelParFamiliaDeportistaTambienSeTraduceYOtraRestriccionSeRelanza() {
		familia(true);
		bloqueables(deportista(d1, true));
		existentes();
		principales();
		when(vinculos.saveAndFlush(any(FamiliaDeportista.class))).thenThrow(violacion("uq_familia_deportista"));
		assertThatThrownBy(() -> servicio.vincular(admin, familiaId, List.of(d1), DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "VINCULO_PRINCIPAL_EN_CONFLICTO"));

		DataIntegrityViolationException otra = violacion("otra_restriccion");
		when(vinculos.saveAndFlush(any(FamiliaDeportista.class))).thenThrow(otra);
		assertThatThrownBy(() -> servicio.vincular(admin, familiaId, List.of(d1), DATOS)).isSameAs(otra);
	}

	// ---------- revocar ----------

	private FamiliaDeportista vinculoParaRevocar(EstadoVinculo estado, boolean principal) {
		FamiliaDeportista fd = vinculo(d1, estado, principal);
		bloqueables(deportista(d1, true));
		when(vinculos.buscarVinculo(escuelaId, familiaId, d1)).thenReturn(Optional.of(fd));
		when(vinculos.respuesta(escuelaId, familiaId, d1)).thenReturn(Optional.of(respuesta(fd)));
		return fd;
	}

	private VinculoRespuesta respuesta(FamiliaDeportista fd) {
		return new VinculoRespuesta(fd.getId(), familiaId, "Familia", d1, "N", "A", true, EstadoVinculo.REVOCADO, false,
				AHORA);
	}

	@Test
	void revocarUnVinculoActivoPrincipalUsaLaActualizacionCondicionalYAuditaEraPrincipal() {
		FamiliaDeportista fd = vinculoParaRevocar(EstadoVinculo.ACTIVO, true);
		when(vinculos.revocarSiActivo(escuelaId, familiaId, d1)).thenReturn(1);

		VinculoRespuesta respuesta = servicio.revocar(admin, familiaId, d1, DATOS);

		assertThat(respuesta.vinculoId()).isEqualTo(fd.getId());
		InOrder orden = inOrder(deportistas, vinculos, auditoria);
		orden.verify(deportistas).bloquearParaVincular(escuelaId, List.of(d1));
		orden.verify(vinculos).buscarVinculo(escuelaId, familiaId, d1);
		orden.verify(vinculos).revocarSiActivo(escuelaId, familiaId, d1);
		orden.verify(auditoria).registrar(any(EventoAuditoria.class));
		EventoAuditoria evento = eventos(1).get(0);
		assertThat(evento.accion()).isEqualTo(AccionAuditoria.VINCULO_REVOCADO);
		assertThat(evento.recursoId()).isEqualTo(fd.getId());
		assertThat(evento.detalle()).containsOnlyKeys("familiaId", "deportistaId", "eraPrincipal")
				.containsEntry("eraPrincipal", true);
	}

	@Test
	void revocarNoPromueveNingunOtroVinculoNiEscribeEntidades() {
		vinculoParaRevocar(EstadoVinculo.ACTIVO, true);
		when(vinculos.revocarSiActivo(escuelaId, familiaId, d1)).thenReturn(1);

		servicio.revocar(admin, familiaId, d1, DATOS);

		verify(vinculos, never()).principalActivo(any(), any());
		verify(vinculos, never()).saveAndFlush(any());
	}

	@Test
	void revocarDeNuevoEsIdempotenteSinActualizarNiAuditar() {
		vinculoParaRevocar(EstadoVinculo.REVOCADO, false);

		assertThat(servicio.revocar(admin, familiaId, d1, DATOS).estado()).isEqualTo(EstadoVinculo.REVOCADO);

		verify(vinculos, never()).revocarSiActivo(any(), any(), any());
		verifyNoInteractions(auditoria);
	}

	@Test
	void siLaActualizacionCondicionalNoCambiaFilasNoSeAudita() {
		vinculoParaRevocar(EstadoVinculo.ACTIVO, false);
		when(vinculos.revocarSiActivo(escuelaId, familiaId, d1)).thenReturn(0);

		servicio.revocar(admin, familiaId, d1, DATOS);

		verifyNoInteractions(auditoria);
	}

	@Test
	void revocarPendienteORechazadoDa409VinculoNoActivo() {
		for (EstadoVinculo estado : new EstadoVinculo[] { EstadoVinculo.PENDIENTE, EstadoVinculo.RECHAZADO }) {
			vinculoParaRevocar(estado, true);
			assertThatThrownBy(() -> servicio.revocar(admin, familiaId, d1, DATOS))
					.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "VINCULO_NO_ACTIVO"));
		}
		verify(vinculos, never()).revocarSiActivo(any(), any(), any());
		verifyNoInteractions(auditoria);
	}

	@Test
	void revocarSinVinculoODeOtraEscuelaDa404YNuncaConsultaLaFamilia() {
		bloqueables();
		when(vinculos.buscarVinculo(escuelaId, familiaId, d1)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> servicio.revocar(admin, familiaId, d1, DATOS))
				.satisfies(e -> assertError(e, HttpStatus.NOT_FOUND, "VINCULO_NO_ENCONTRADO"));

		// Revocar siempre se permite: no depende de que la familia exista ni este activa.
		verifyNoInteractions(familias, auditoria);
	}

	// ---------- cambiar el principal ----------

	private FamiliaDeportista destinoPrincipal(EstadoVinculo estado, boolean principal) {
		FamiliaDeportista destino = vinculo(d1, estado, principal);
		when(vinculos.buscarVinculo(escuelaId, familiaId, d1)).thenReturn(Optional.of(destino));
		return destino;
	}

	@Test
	void cambiarPrincipalBajaAlActualYLuegoSubeAlElegidoCadaUnoConSaveAndFlushYAudita() {
		familia(true);
		bloqueables(deportista(d1, true));
		FamiliaDeportista destino = destinoPrincipal(EstadoVinculo.ACTIVO, false);
		FamiliaDeportista actual = FixturesDominio.vinculo(UUID.randomUUID(), escuelaId, UUID.randomUUID(), d1,
				EstadoVinculo.ACTIVO, true);
		when(vinculos.principalActivo(escuelaId, d1)).thenReturn(Optional.of(actual));

		VinculoRespuesta respuesta = servicio.cambiarPrincipal(admin, familiaId, d1, DATOS);

		assertThat(respuesta.vinculoId()).isEqualTo(destino.getId());
		assertThat(respuesta.esPrincipal()).isTrue();
		assertThat(escrituras).containsExactly(d1 + ":ACTIVO:false", d1 + ":ACTIVO:true");
		InOrder orden = inOrder(familias, deportistas, vinculos, auditoria);
		orden.verify(familias).findByIdAndEscuelaId(familiaId, escuelaId);
		orden.verify(deportistas).bloquearParaVincular(escuelaId, List.of(d1));
		orden.verify(vinculos).buscarVinculo(escuelaId, familiaId, d1);
		orden.verify(vinculos).principalActivo(escuelaId, d1);
		orden.verify(vinculos).saveAndFlush(actual);
		orden.verify(vinculos).saveAndFlush(destino);
		orden.verify(auditoria).registrar(any(EventoAuditoria.class));
		EventoAuditoria evento = eventos(1).get(0);
		assertThat(evento.accion()).isEqualTo(AccionAuditoria.VINCULO_PRINCIPAL_CAMBIADO);
		assertThat(evento.recursoId()).isEqualTo(destino.getId());
		assertThat(evento.detalle()).containsOnlyKeys("familiaId", "deportistaId", "anteriorVinculoId")
				.containsEntry("anteriorVinculoId", actual.getId());
	}

	@Test
	void sinPrincipalPrevioSeSubeElElegidoYAnteriorVinculoIdEsNulo() {
		familia(true);
		bloqueables(deportista(d1, true));
		destinoPrincipal(EstadoVinculo.ACTIVO, false);
		when(vinculos.principalActivo(escuelaId, d1)).thenReturn(Optional.empty());

		servicio.cambiarPrincipal(admin, familiaId, d1, DATOS);

		assertThat(escrituras).containsExactly(d1 + ":ACTIVO:true");
		assertThat(eventos(1).get(0).detalle()).containsEntry("anteriorVinculoId", null);
	}

	@Test
	void siYaEsPrincipalEsUn200SinEscribirNiAuditar() {
		familia(true);
		bloqueables(deportista(d1, true));
		destinoPrincipal(EstadoVinculo.ACTIVO, true);

		assertThat(servicio.cambiarPrincipal(admin, familiaId, d1, DATOS).esPrincipal()).isTrue();

		assertThat(escrituras).isEmpty();
		verify(vinculos, never()).principalActivo(any(), any());
		verifyNoInteractions(auditoria);
	}

	@Test
	void unaFamiliaInexistenteDa404YUnaInactivaDa409AntesDeBloquearOMirarElVinculo() {
		when(familias.findByIdAndEscuelaId(familiaId, escuelaId)).thenReturn(Optional.empty());
		assertThatThrownBy(() -> servicio.cambiarPrincipal(admin, familiaId, d1, DATOS))
				.satisfies(e -> assertError(e, HttpStatus.NOT_FOUND, "FAMILIA_NO_ENCONTRADA"));

		familia(false);
		// Incluso con un vinculo no ACTIVO: la familia se comprueba primero.
		destinoPrincipal(EstadoVinculo.REVOCADO, false);
		assertThatThrownBy(() -> servicio.cambiarPrincipal(admin, familiaId, d1, DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "FAMILIA_INACTIVA"));

		verifyNoInteractions(deportistas, auditoria);
		assertThat(escrituras).isEmpty();
	}

	@Test
	void sinVinculoDa404VinculoNoEncontradoYUnVinculoNoActivoDa409VinculoNoActivo() {
		familia(true);
		bloqueables(deportista(d1, true));
		when(vinculos.buscarVinculo(escuelaId, familiaId, d1)).thenReturn(Optional.empty());
		assertThatThrownBy(() -> servicio.cambiarPrincipal(admin, familiaId, d1, DATOS))
				.satisfies(e -> assertError(e, HttpStatus.NOT_FOUND, "VINCULO_NO_ENCONTRADO"));

		for (EstadoVinculo estado : new EstadoVinculo[] { EstadoVinculo.REVOCADO, EstadoVinculo.PENDIENTE,
				EstadoVinculo.RECHAZADO }) {
			destinoPrincipal(estado, false);
			assertThatThrownBy(() -> servicio.cambiarPrincipal(admin, familiaId, d1, DATOS))
					.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "VINCULO_NO_ACTIVO"));
		}
		assertThat(escrituras).isEmpty();
		verifyNoInteractions(auditoria);
	}

	@Test
	void unDeportistaAjenoNoBloqueableDa404VinculoNoEncontradoComoUnoInexistente() {
		familia(true);
		bloqueables();

		assertThatThrownBy(() -> servicio.cambiarPrincipal(admin, familiaId, d1, DATOS))
				.satisfies(e -> assertError(e, HttpStatus.NOT_FOUND, "VINCULO_NO_ENCONTRADO"));

		verify(vinculos, never()).buscarVinculo(any(), any(), any());
	}

	@Test
	void unaViolacionDelIndiceAlCambiarElPrincipalDa409ConflictoSinAuditar() {
		familia(true);
		bloqueables(deportista(d1, true));
		destinoPrincipal(EstadoVinculo.ACTIVO, false);
		when(vinculos.principalActivo(escuelaId, d1)).thenReturn(Optional.empty());
		when(vinculos.saveAndFlush(any(FamiliaDeportista.class))).thenThrow(violacion("uq_fd_principal_activo"));

		assertThatThrownBy(() -> servicio.cambiarPrincipal(admin, familiaId, d1, DATOS))
				.satisfies(e -> assertError(e, HttpStatus.CONFLICT, "VINCULO_PRINCIPAL_EN_CONFLICTO"));

		verifyNoInteractions(auditoria);
	}

	// ---------- listados ----------

	@Test
	void losListadosDanElMismo404QueUnIdAjeno() {
		when(familias.findByIdAndEscuelaId(familiaId, escuelaId)).thenReturn(Optional.empty());
		when(deportistas.findByIdAndEscuelaId(d1, escuelaId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> servicio.listarDeFamilia(admin, familiaId))
				.satisfies(e -> assertError(e, HttpStatus.NOT_FOUND, "FAMILIA_NO_ENCONTRADA"));
		assertThatThrownBy(() -> servicio.listarDeDeportista(admin, d1))
				.satisfies(e -> assertError(e, HttpStatus.NOT_FOUND, "DEPORTISTA_NO_ENCONTRADO"));

		verifyNoInteractions(vinculos);
	}

	@Test
	void losListadosUsanLaEscuelaDelActor() {
		familia(true);
		when(deportistas.findByIdAndEscuelaId(d1, escuelaId)).thenReturn(Optional.of(deportista(d1, true)));
		when(vinculos.deFamilia(escuelaId, familiaId)).thenReturn(List.of(respuesta(vinculo(d1, EstadoVinculo.ACTIVO, true))));
		when(vinculos.deDeportista(escuelaId, d1)).thenReturn(List.of());

		assertThat(servicio.listarDeFamilia(admin, familiaId)).hasSize(1);
		assertThat(servicio.listarDeDeportista(admin, d1)).isEmpty();
	}
}
