package com.banfieldpatin.backend.usuarios.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.banfieldpatin.backend.FixturesDominio;
import com.banfieldpatin.backend.compartido.auditoria.AccionAuditoria;
import com.banfieldpatin.backend.compartido.auditoria.AuditoriaService;
import com.banfieldpatin.backend.compartido.auditoria.EventoAuditoria;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.escuelas.EscuelaRepository;
import com.banfieldpatin.backend.seguridad.SeguridadPropiedades;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.seguridad.mfa.Base32;
import com.banfieldpatin.backend.seguridad.mfa.CifradorSecretoMfa;
import com.banfieldpatin.backend.seguridad.mfa.Totp;
import com.banfieldpatin.backend.usuarios.Rol;
import com.banfieldpatin.backend.usuarios.Usuario;
import com.banfieldpatin.backend.usuarios.UsuarioRepository;
import com.banfieldpatin.backend.usuarios.dto.UsuarioActualRespuesta;
import com.banfieldpatin.backend.usuarios.mfa.dto.EnrolamientoMfaRespuesta;

/**
 * Logica de MfaService con el cifrador, TOTP y Base32 reales. El repositorio de factores es un doble en memoria que
 * replica la semantica de las consultas condicionales; el SQL real se prueba en las pruebas db (MfaDbTest).
 */
class MfaServiceTest {

	private static final DatosSolicitud SOLICITUD = new DatosSolicitud("10.0.0.1", "JUnit");
	private static final Instant INICIO = Instant.parse("2026-03-01T12:00:10Z");
	private static final String CLAVE_MFA = Base64.getEncoder()
			.encodeToString("test-only-fictitious-mfa-key-32b".getBytes(StandardCharsets.UTF_8));

	/** Reloj manual para avanzar pasos de 30 s y vencer bloqueos. */
	private static final class RelojManual extends Clock {
		private Instant ahora = INICIO;

		void avanzar(Duration d) {
			ahora = ahora.plus(d);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return ahora;
		}
	}

	private final RelojManual reloj = new RelojManual();
	private final UsuarioRepository usuarios = mock(UsuarioRepository.class);
	private final EscuelaRepository escuelas = mock(EscuelaRepository.class);
	private final UsuarioMfaRepository mfas = mock(UsuarioMfaRepository.class);
	private final AuditoriaService auditoria = mock(AuditoriaService.class);
	private final CifradorSecretoMfa cifrador = new CifradorSecretoMfa(propiedades());
	/** Estado del doble de usuario_mfa: usuario_id -> fila. */
	private final Map<UUID, UsuarioMfa> filas = new HashMap<>();

	private final UUID escuelaId = UUID.randomUUID();
	private final UUID adminId = UUID.randomUUID();
	private final UUID otroAdminId = UUID.randomUUID();
	private Usuario admin;
	private Usuario otroAdmin;
	private UsuarioAutenticado identidad;
	private MfaService servicio;

	private static SeguridadPropiedades propiedades() {
		return new SeguridadPropiedades(
				new SeguridadPropiedades.Jwt(Base64.getEncoder()
						.encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)), "e",
						Duration.ofHours(8)),
				new SeguridadPropiedades.Cookie("BP_SESION", false, "Lax"),
				new SeguridadPropiedades.Cors(null),
				new SeguridadPropiedades.Login(3, Duration.ofMinutes(15), Duration.ofMinutes(15), 100),
				new SeguridadPropiedades.Mfa(CLAVE_MFA, Duration.ofMinutes(5), "Banfield Patin"));
	}

	@BeforeEach
	void preparar() {
		admin = FixturesDominio.usuario(adminId, escuelaId, null, Rol.ADMIN, "admin@example.com", "{noop}x", true);
		otroAdmin = FixturesDominio.usuario(otroAdminId, escuelaId, null, Rol.ADMIN, "otro@example.com", "{noop}x", true);
		identidad = new UsuarioAutenticado(adminId, escuelaId, null, Rol.ADMIN);
		when(usuarios.findByIdAndEscuelaId(adminId, escuelaId)).thenReturn(Optional.of(admin));
		when(usuarios.findByIdAndEscuelaId(otroAdminId, escuelaId)).thenReturn(Optional.of(otroAdmin));
		when(escuelas.findById(escuelaId)).thenReturn(Optional.of(FixturesDominio.escuela(escuelaId, true)));
		stubRepositorioDeFactores();
		servicio = new MfaService(usuarios, escuelas, mfas, cifrador, auditoria, reloj, propiedades());
	}

	private void stubRepositorioDeFactores() {
		when(mfas.findById(any())).thenAnswer(i -> Optional.ofNullable(filas.get(i.<UUID>getArgument(0))));
		when(mfas.guardarEnrolamiento(any(), any(), any())).thenAnswer(i -> {
			UUID id = i.getArgument(0);
			UsuarioMfa existente = filas.get(id);
			if (existente != null && existente.estaConfirmado()) {
				return 0;
			}
			filas.put(id, fila(id, i.getArgument(1), i.getArgument(2), null, null));
			return 1;
		});
		when(mfas.confirmar(any(), anyLong(), any())).thenAnswer(i -> {
			UsuarioMfa f = filas.get(i.<UUID>getArgument(0));
			if (f == null || f.estaConfirmado()) {
				return 0;
			}
			ReflectionTestUtils.setField(f, "confirmadoEn", i.<Instant>getArgument(2));
			ReflectionTestUtils.setField(f, "ultimoPasoUsado", i.<Long>getArgument(1));
			return 1;
		});
		when(mfas.consumirPaso(any(), anyLong())).thenAnswer(i -> {
			UsuarioMfa f = filas.get(i.<UUID>getArgument(0));
			long paso = i.getArgument(1);
			if (f == null || !f.estaConfirmado() || (f.getUltimoPasoUsado() != null && f.getUltimoPasoUsado() >= paso)) {
				return 0;
			}
			ReflectionTestUtils.setField(f, "ultimoPasoUsado", paso);
			return 1;
		});
		when(mfas.eliminar(any())).thenAnswer(i -> filas.remove(i.<UUID>getArgument(0)) == null ? 0 : 1);
	}

	private static UsuarioMfa fila(UUID usuarioId, UUID escuelaId, byte[] cifrado, Instant confirmadoEn, Long paso) {
		try {
			var ctor = UsuarioMfa.class.getDeclaredConstructor();
			ctor.setAccessible(true);
			UsuarioMfa f = ctor.newInstance();
			ReflectionTestUtils.setField(f, "usuarioId", usuarioId);
			ReflectionTestUtils.setField(f, "escuelaId", escuelaId);
			ReflectionTestUtils.setField(f, "secretoCifrado", cifrado);
			ReflectionTestUtils.setField(f, "confirmadoEn", confirmadoEn);
			ReflectionTestUtils.setField(f, "ultimoPasoUsado", paso);
			return f;
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	// ---------- utilidades ----------

	private byte[] secretoGuardado(UUID usuarioId) {
		return cifrador.descifrar(filas.get(usuarioId).getSecretoCifrado(), usuarioId);
	}

	private String codigoActual() {
		return codigoDe(adminId);
	}

	private String codigoDe(UUID usuarioId) {
		return Totp.codigo(secretoGuardado(usuarioId), Totp.pasoDe(reloj.instant()));
	}

	/** Un codigo seguro de que NO es valido en la ventana actual. */
	private String codigoIncorrecto() {
		byte[] secreto = secretoGuardado(adminId);
		long actual = Totp.pasoDe(reloj.instant());
		for (int candidato = 0; candidato < 1_000_000; candidato++) {
			String c = "%06d".formatted(candidato);
			boolean valido = false;
			for (long p = actual - 1; p <= actual + 1; p++) {
				valido |= c.equals(Totp.codigo(secreto, p));
			}
			if (!valido) {
				return c;
			}
		}
		throw new IllegalStateException();
	}

	private void enrolarYConfirmar() {
		servicio.enrolar(identidad, SOLICITUD);
		servicio.confirmar(identidad, codigoActual(), SOLICITUD);
		// Pasa al paso siguiente para que el primer codigo de verificacion no coincida con el de confirmacion.
		reloj.avanzar(Duration.ofSeconds(30));
	}

	private void assertUniforme(Runnable accion) {
		assertThatThrownBy(accion::run).isInstanceOfSatisfying(ExcepcionNegocio.class, e -> {
			assertThat(e.getEstado().value()).isEqualTo(401);
			assertThat(e.getCodigo()).isEqualTo("CODIGO_MFA_INVALIDO");
			assertThat(e.getMessage()).isEqualTo("El código ingresado no es válido o ya venció.");
		});
	}

	private List<EventoAuditoria> eventos(boolean fallos) {
		ArgumentCaptor<EventoAuditoria> c = ArgumentCaptor.forClass(EventoAuditoria.class);
		if (fallos) {
			verify(auditoria, org.mockito.Mockito.atLeast(0)).registrarFallo(c.capture());
		} else {
			verify(auditoria, org.mockito.Mockito.atLeast(0)).registrar(c.capture());
		}
		return new ArrayList<>(c.getAllValues());
	}

	private String detalleDeFallo() {
		List<EventoAuditoria> fallos = eventos(true);
		assertThat(fallos).isNotEmpty();
		return fallos.get(fallos.size() - 1).detalle().toString();
	}

	// ---------- enrolar ----------

	@Test
	void enrolarGuardaElSecretoCifradoYDevuelveUriYBase32UnaVez() {
		EnrolamientoMfaRespuesta r = servicio.enrolar(identidad, SOLICITUD);

		assertThat(r.secretoBase32()).matches("[A-Z2-7]{32}");
		byte[] secreto = Base32.decodificar(r.secretoBase32());
		assertThat(secreto).hasSize(20);
		// En la fila solo hay texto cifrado: descifra al secreto devuelto y no contiene el secreto en claro.
		byte[] cifrado = filas.get(adminId).getSecretoCifrado();
		assertThat(cifrado).hasSize(12 + 20 + 16);
		assertThat(secretoGuardado(adminId)).isEqualTo(secreto);
		assertThat(new String(cifrado, StandardCharsets.ISO_8859_1))
				.doesNotContain(new String(secreto, StandardCharsets.ISO_8859_1));
		assertThat(filas.get(adminId).estaConfirmado()).isFalse();
		assertThat(admin.isMfaHabilitado()).isFalse();
	}

	@Test
	void laUriOtpauthTieneElFormatoEstandar() {
		EnrolamientoMfaRespuesta r = servicio.enrolar(identidad, SOLICITUD);

		assertThat(r.otpauthUri()).startsWith("otpauth://totp/Banfield%20Patin:admin%40example.com?secret=")
				.contains("secret=" + r.secretoBase32())
				.contains("&issuer=Banfield%20Patin&algorithm=SHA1&digits=6&period=30");
	}

	@Test
	void enrolarDeNuevoAntesDeConfirmarReemplazaElSecretoYSigueSiendoValido() {
		EnrolamientoMfaRespuesta primero = servicio.enrolar(identidad, SOLICITUD);
		EnrolamientoMfaRespuesta segundo = servicio.enrolar(identidad, SOLICITUD);

		assertThat(segundo.secretoBase32()).isNotEqualTo(primero.secretoBase32());
		assertThat(secretoGuardado(adminId)).isEqualTo(Base32.decodificar(segundo.secretoBase32()));
		// El secreto viejo ya no sirve para confirmar; el nuevo si.
		String delViejo = Totp.codigo(Base32.decodificar(primero.secretoBase32()), Totp.pasoDe(reloj.instant()));
		assertUniforme(() -> servicio.confirmar(identidad, delViejo, SOLICITUD));
		assertThat(servicio.confirmar(identidad, codigoActual(), SOLICITUD).mfaEnrolado()).isTrue();
	}

	@Test
	void noSePuedeEnrolarCuandoElMfaYaEstaConfirmado() {
		enrolarYConfirmar();

		assertThatThrownBy(() -> servicio.enrolar(identidad, SOLICITUD)).isInstanceOfSatisfying(ExcepcionNegocio.class,
				e -> {
					assertThat(e.getEstado().value()).isEqualTo(409);
					assertThat(e.getCodigo()).isEqualTo("MFA_ESTADO_INVALIDO");
				});
	}

	@Test
	void siElFactorSeConfirmaEntreLecturaYEscrituraElEnrolamientoDa409() {
		when(mfas.guardarEnrolamiento(any(), any(), any())).thenReturn(0);

		assertThatThrownBy(() -> servicio.enrolar(identidad, SOLICITUD)).isInstanceOfSatisfying(ExcepcionNegocio.class,
				e -> assertThat(e.getCodigo()).isEqualTo("MFA_ESTADO_INVALIDO"));
		verify(auditoria, never()).registrar(any());
	}

	@Test
	void enrolarAuditaSinSecretoNiUri() {
		EnrolamientoMfaRespuesta r = servicio.enrolar(identidad, SOLICITUD);

		List<EventoAuditoria> registrados = eventos(false);
		assertThat(registrados).hasSize(1);
		EventoAuditoria e = registrados.get(0);
		assertThat(e.accion()).isEqualTo(AccionAuditoria.MFA_ENROLADO);
		assertThat(e.usuarioId()).isEqualTo(adminId);
		assertThat(e.escuelaId()).isEqualTo(escuelaId);
		assertThat(e.recursoId()).isEqualTo(adminId);
		assertThat(e.toString()).doesNotContain(r.secretoBase32()).doesNotContain("otpauth");
	}

	@Test
	void unUsuarioQueNoEsAdminActivoDeUnaEscuelaActivaDa401() {
		Usuario familia = FixturesDominio.usuario(UUID.randomUUID(), escuelaId, UUID.randomUUID(), Rol.FAMILIA,
				"f@example.com", "{noop}x", true);
		Usuario inactivo = FixturesDominio.usuario(UUID.randomUUID(), escuelaId, null, Rol.ADMIN, "i@example.com",
				"{noop}x", false);
		when(usuarios.findByIdAndEscuelaId(familia.getId(), escuelaId)).thenReturn(Optional.of(familia));
		when(usuarios.findByIdAndEscuelaId(inactivo.getId(), escuelaId)).thenReturn(Optional.of(inactivo));
		UUID inexistente = UUID.randomUUID();

		for (UUID id : new UUID[] { familia.getId(), inactivo.getId(), inexistente }) {
			assertThatThrownBy(() -> servicio.enrolar(new UsuarioAutenticado(id, escuelaId, null, Rol.ADMIN), SOLICITUD))
					.isInstanceOfSatisfying(ExcepcionNegocio.class, e -> assertThat(e.getEstado().value()).isEqualTo(401));
		}
		when(escuelas.findById(escuelaId)).thenReturn(Optional.of(FixturesDominio.escuela(escuelaId, false)));
		assertThatThrownBy(() -> servicio.enrolar(identidad, SOLICITUD)).isInstanceOfSatisfying(ExcepcionNegocio.class,
				e -> assertThat(e.getEstado().value()).isEqualTo(401));
		assertThat(filas).isEmpty();
	}

	// ---------- confirmar ----------

	@Test
	void confirmarConElCodigoCorrectoActivaElMfaYRegistraElPaso() {
		servicio.enrolar(identidad, SOLICITUD);

		UsuarioActualRespuesta r = servicio.confirmar(identidad, codigoActual(), SOLICITUD);

		assertThat(r.mfaPendiente()).isFalse();
		assertThat(r.mfaEnrolado()).isTrue();
		assertThat(admin.isMfaHabilitado()).isTrue();
		assertThat(filas.get(adminId).getConfirmadoEn()).isEqualTo(INICIO);
		assertThat(filas.get(adminId).getUltimoPasoUsado()).isEqualTo(Totp.pasoDe(INICIO));
		assertThat(eventos(false)).extracting(EventoAuditoria::accion)
				.containsExactly(AccionAuditoria.MFA_ENROLADO, AccionAuditoria.MFA_CONFIRMADO);
	}

	@Test
	void confirmarConUnCodigoIncorrectoDa401UniformeNoActivaYAuditaElFallo() {
		servicio.enrolar(identidad, SOLICITUD);
		String malo = codigoIncorrecto();

		assertUniforme(() -> servicio.confirmar(identidad, malo, SOLICITUD));

		assertThat(admin.isMfaHabilitado()).isFalse();
		assertThat(filas.get(adminId).estaConfirmado()).isFalse();
		assertThat(detalleDeFallo()).contains("CONFIRMACION", "CODIGO").doesNotContain(malo);
	}

	@Test
	void confirmarSinEnrolamientoPendienteODespuesDeConfirmadoDa409() {
		assertThatThrownBy(() -> servicio.confirmar(identidad, "123456", SOLICITUD))
				.isInstanceOfSatisfying(ExcepcionNegocio.class, e -> assertThat(e.getCodigo()).isEqualTo("MFA_ESTADO_INVALIDO"));

		enrolarYConfirmar();
		assertThatThrownBy(() -> servicio.confirmar(identidad, codigoActual(), SOLICITUD))
				.isInstanceOfSatisfying(ExcepcionNegocio.class, e -> assertThat(e.getCodigo()).isEqualTo("MFA_ESTADO_INVALIDO"));
	}

	@Test
	void laVentanaDeConfirmacionEsDeMasMenosUnPaso() {
		servicio.enrolar(identidad, SOLICITUD);
		byte[] secreto = secretoGuardado(adminId);
		long actual = Totp.pasoDe(reloj.instant());

		assertUniforme(() -> servicio.confirmar(identidad, Totp.codigo(secreto, actual - 2), SOLICITUD));
		assertUniforme(() -> servicio.confirmar(identidad, Totp.codigo(secreto, actual + 2), SOLICITUD));
		servicio.confirmar(identidad, Totp.codigo(secreto, actual - 1), SOLICITUD);

		assertThat(filas.get(adminId).getUltimoPasoUsado()).isEqualTo(actual - 1);
	}

	// ---------- verificar ----------

	@Test
	void verificarConElCodigoCorrectoAceptaYAvanzaElPasoUsado() {
		enrolarYConfirmar();

		UsuarioActualRespuesta r = servicio.verificar(identidad, codigoActual(), SOLICITUD);

		assertThat(r.mfaPendiente()).isFalse();
		assertThat(filas.get(adminId).getUltimoPasoUsado()).isEqualTo(Totp.pasoDe(reloj.instant()));
		assertThat(eventos(false)).extracting(EventoAuditoria::accion).last().isEqualTo(AccionAuditoria.MFA_VERIFICADO);
	}

	@Test
	void verificarSinEstarEnroladoDa409() {
		assertThatThrownBy(() -> servicio.verificar(identidad, "123456", SOLICITUD))
				.isInstanceOfSatisfying(ExcepcionNegocio.class, e -> assertThat(e.getCodigo()).isEqualTo("MFA_ESTADO_INVALIDO"));
		// Enrolado pero sin confirmar tampoco alcanza para verificar.
		servicio.enrolar(identidad, SOLICITUD);
		assertThatThrownBy(() -> servicio.verificar(identidad, codigoActual(), SOLICITUD))
				.isInstanceOfSatisfying(ExcepcionNegocio.class, e -> assertThat(e.getCodigo()).isEqualTo("MFA_ESTADO_INVALIDO"));
	}

	@Test
	void unCodigoYaUsadoNoSePuedeRepetirAunDentroDeLaVentana() {
		enrolarYConfirmar();
		String codigo = codigoActual();
		servicio.verificar(identidad, codigo, SOLICITUD);

		assertUniforme(() -> servicio.verificar(identidad, codigo, SOLICITUD));
		// Tampoco sirve el de un paso anterior (dentro de la ventana, pero menor que el ultimo usado).
		assertUniforme(() -> servicio.verificar(identidad,
				Totp.codigo(secretoGuardado(adminId), Totp.pasoDe(reloj.instant()) - 1), SOLICITUD));
	}

	@Test
	void unCodigoDeUnPasoPosteriorSiSeAceptaDespuesDeUsarUnoAnterior() {
		enrolarYConfirmar();
		servicio.verificar(identidad, codigoActual(), SOLICITUD);
		reloj.avanzar(Duration.ofSeconds(30));

		servicio.verificar(identidad, codigoActual(), SOLICITUD);
	}

	@Test
	void laRepeticionConcurrenteDelMismoCodigoSePierdeEnLaSentenciaCondicional() {
		enrolarYConfirmar();
		String codigo = codigoActual();
		// El codigo es valido para Totp.verificar, pero otra peticion ya consumio el paso: 0 filas.
		when(mfas.consumirPaso(any(), anyLong())).thenReturn(0);

		assertUniforme(() -> servicio.verificar(identidad, codigo, SOLICITUD));

		assertThat(detalleDeFallo()).contains("VERIFICACION", "REPETIDO");
	}

	@Test
	void superadoElLimiteElCodigoCorrectoSeRechazaIgualQueUnoIncorrectoYNoSeConsumeElPaso() {
		enrolarYConfirmar();
		String malo = codigoIncorrecto();
		for (int i = 0; i < 3; i++) {
			assertUniforme(() -> servicio.verificar(identidad, malo, SOLICITUD));
		}
		Long pasoAntes = filas.get(adminId).getUltimoPasoUsado();

		// Bloqueado: ni el codigo correcto entra, y la respuesta es identica a la de un codigo erroneo.
		assertUniforme(() -> servicio.verificar(identidad, codigoActual(), SOLICITUD));

		assertThat(filas.get(adminId).getUltimoPasoUsado()).isEqualTo(pasoAntes);
		assertThat(detalleDeFallo()).contains("BLOQUEO");
		// Vencido el bloqueo, el codigo correcto funciona de nuevo.
		reloj.avanzar(Duration.ofMinutes(16));
		servicio.verificar(identidad, codigoActual(), SOLICITUD);
	}

	@Test
	void unExitoReiniciaElContadorDeFallos() {
		enrolarYConfirmar();
		String malo = codigoIncorrecto();
		assertUniforme(() -> servicio.verificar(identidad, malo, SOLICITUD));
		assertUniforme(() -> servicio.verificar(identidad, malo, SOLICITUD));
		servicio.verificar(identidad, codigoActual(), SOLICITUD);
		reloj.avanzar(Duration.ofSeconds(30));
		String malo2 = codigoIncorrecto();

		assertUniforme(() -> servicio.verificar(identidad, malo2, SOLICITUD));
		assertUniforme(() -> servicio.verificar(identidad, malo2, SOLICITUD));
		// Sin el reinicio ya estaria bloqueado (3 limite): tras 2 fallos mas el correcto aun pasa.
		servicio.verificar(identidad, codigoActual(), SOLICITUD);
	}

	@Test
	void elLimitadorEsPorUsuario() {
		UsuarioAutenticado otro = new UsuarioAutenticado(otroAdminId, escuelaId, null, Rol.ADMIN);
		enrolarYConfirmar();
		servicio.enrolar(otro, SOLICITUD);
		servicio.confirmar(otro, codigoDe(otroAdminId), SOLICITUD);
		reloj.avanzar(Duration.ofSeconds(30));
		String malo = codigoIncorrecto();
		for (int i = 0; i < 3; i++) {
			assertUniforme(() -> servicio.verificar(identidad, malo, SOLICITUD));
		}
		assertUniforme(() -> servicio.verificar(identidad, codigoActual(), SOLICITUD));

		// El otro admin no esta bloqueado por los fallos del primero.
		assertThat(servicio.verificar(otro, codigoDe(otroAdminId), SOLICITUD).mfaPendiente()).isFalse();
	}

	@Test
	void nadaSeAuditaNiSeExponeDelCodigoNiDelSecreto() {
		EnrolamientoMfaRespuesta enrolamiento = servicio.enrolar(identidad, SOLICITUD);
		String correcto = codigoActual();
		String malo = codigoIncorrecto();
		assertUniforme(() -> servicio.confirmar(identidad, malo, SOLICITUD));
		servicio.confirmar(identidad, correcto, SOLICITUD);

		List<EventoAuditoria> todos = new ArrayList<>(eventos(false));
		todos.addAll(eventos(true));
		assertThat(todos).isNotEmpty();
		for (EventoAuditoria e : todos) {
			assertThat(e.toString()).doesNotContain(correcto).doesNotContain(malo)
					.doesNotContain(enrolamiento.secretoBase32()).doesNotContain("otpauth");
		}
	}

	// ---------- reiniciar ----------

	@Test
	void unAdminReiniciaElMfaDeOtroAdminDeSuEscuela() {
		filas.put(otroAdminId, fila(otroAdminId, escuelaId, cifrador.cifrar(new byte[20], otroAdminId), INICIO, 5L));
		otroAdmin.habilitarMfa();

		servicio.reiniciar(identidad, otroAdminId, SOLICITUD);

		assertThat(filas).doesNotContainKey(otroAdminId);
		assertThat(otroAdmin.isMfaHabilitado()).isFalse();
		EventoAuditoria e = eventos(false).get(0);
		assertThat(e.accion()).isEqualTo(AccionAuditoria.MFA_REINICIADO);
		assertThat(e.usuarioId()).isEqualTo(adminId);
		assertThat(e.recursoId()).isEqualTo(otroAdminId);
		assertThat(e.escuelaId()).isEqualTo(escuelaId);
	}

	@Test
	void reiniciarSuPropioMfaEstaProhibido() {
		enrolarYConfirmar();

		assertThatThrownBy(() -> servicio.reiniciar(identidad, adminId, SOLICITUD))
				.isInstanceOfSatisfying(ExcepcionNegocio.class, e -> {
					assertThat(e.getEstado().value()).isEqualTo(403);
					assertThat(e.getCodigo()).isEqualTo("MFA_AUTOREINICIO_NO_PERMITIDO");
				});

		assertThat(filas).containsKey(adminId);
		assertThat(admin.isMfaHabilitado()).isTrue();
		verify(mfas, never()).eliminar(any());
	}

	@Test
	void reiniciarAUnUsuarioDeOtraEscuelaODeOtroRolODesconocidoDa404SinTocarNada() {
		UUID deOtraEscuela = UUID.randomUUID();
		// El repositorio filtra por escuela: para esta escuela el usuario no existe.
		when(usuarios.findByIdAndEscuelaId(deOtraEscuela, escuelaId)).thenReturn(Optional.empty());
		Usuario familia = FixturesDominio.usuario(UUID.randomUUID(), escuelaId, UUID.randomUUID(), Rol.FAMILIA,
				"f@example.com", "{noop}x", true);
		when(usuarios.findByIdAndEscuelaId(familia.getId(), escuelaId)).thenReturn(Optional.of(familia));

		for (UUID objetivo : new UUID[] { deOtraEscuela, familia.getId(), UUID.randomUUID() }) {
			assertThatThrownBy(() -> servicio.reiniciar(identidad, objetivo, SOLICITUD))
					.isInstanceOfSatisfying(ExcepcionNegocio.class, e -> {
						assertThat(e.getEstado().value()).isEqualTo(404);
						assertThat(e.getCodigo()).isEqualTo("USUARIO_NO_ENCONTRADO");
					});
		}
		verify(mfas, never()).eliminar(any());
		verify(auditoria, never()).registrar(any());
	}

	@Test
	void despuesDeReiniciarElAdminDebeEnrolarDeNuevoYLosFallosPreviosNoLoBloquean() {
		enrolarYConfirmar();
		String malo = codigoIncorrecto();
		for (int i = 0; i < 3; i++) {
			assertUniforme(() -> servicio.verificar(identidad, malo, SOLICITUD));
		}

		// El otro admin reinicia el MFA del primero.
		servicio.reiniciar(new UsuarioAutenticado(otroAdminId, escuelaId, null, Rol.ADMIN), adminId, SOLICITUD);

		assertThat(admin.isMfaHabilitado()).isFalse();
		assertThatThrownBy(() -> servicio.verificar(identidad, "123456", SOLICITUD))
				.isInstanceOfSatisfying(ExcepcionNegocio.class, e -> assertThat(e.getCodigo()).isEqualTo("MFA_ESTADO_INVALIDO"));
		// Vuelve a enrolar y confirma: el bloqueo por fallos anteriores se levanto con el reinicio.
		servicio.enrolar(identidad, SOLICITUD);
		servicio.confirmar(identidad, codigoActual(), SOLICITUD);
		assertThat(admin.isMfaHabilitado()).isTrue();
	}
}
