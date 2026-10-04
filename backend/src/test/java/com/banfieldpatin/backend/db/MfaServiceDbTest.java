package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import com.banfieldpatin.backend.compartido.auditoria.AuditoriaService;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.seguridad.SeguridadPropiedades;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.seguridad.mfa.Base32;
import com.banfieldpatin.backend.seguridad.mfa.CifradorSecretoMfa;
import com.banfieldpatin.backend.seguridad.mfa.Totp;
import com.banfieldpatin.backend.usuarios.Rol;
import com.banfieldpatin.backend.usuarios.mfa.MfaService;
import com.banfieldpatin.backend.usuarios.mfa.dto.EnrolamientoMfaRespuesta;

import tools.jackson.databind.json.JsonMapper;

/**
 * MfaService con repositorios, cifrador, auditoria (jsonb/inet) y proxies transaccionales reales sobre PostgreSQL 17:
 * lo que los mocks de MfaServiceTest no pueden ver (SQL real, rollback con auditoria REQUIRES_NEW, FK de auditoria).
 */
@PruebaDb
@ActiveProfiles("test")
@Import(MfaServiceDbTest.Config.class)
class MfaServiceDbTest extends BaseDbTest {

	private static final DatosSolicitud DATOS = new DatosSolicitud("10.0.0.7", "JUnit");

	@TestConfiguration
	@EnableConfigurationProperties(SeguridadPropiedades.class)
	@Import({ MfaService.class, AuditoriaService.class, CifradorSecretoMfa.class })
	static class Config {

		@Bean
		Clock reloj() {
			return Clock.systemUTC();
		}

		@Bean
		JsonMapper jsonMapper() {
			return JsonMapper.builder().build();
		}
	}

	@Autowired
	JdbcClient jdbc;
	@Autowired
	MfaService servicio;
	@Autowired
	CifradorSecretoMfa cifrador;

	private DatosDb datos;
	private UUID escuelaA;
	private UUID escuelaB;
	private UUID adminId;
	private UUID otroAdminId;
	private UUID adminDeBId;
	private UsuarioAutenticado admin;
	private UsuarioAutenticado otroAdmin;
	private UsuarioAutenticado adminDeB;

	@BeforeEach
	void preparar() {
		datos = new DatosDb(jdbc);
		escuelaA = datos.escuela("mfa-a-" + UUID.randomUUID());
		escuelaB = datos.escuela("mfa-b-" + UUID.randomUUID());
		adminId = datos.admin(escuelaA, "admin@a.example", true);
		otroAdminId = datos.admin(escuelaA, "otro@a.example", true);
		adminDeBId = datos.admin(escuelaB, "admin@b.example", true);
		admin = new UsuarioAutenticado(adminId, escuelaA, null, Rol.ADMIN);
		otroAdmin = new UsuarioAutenticado(otroAdminId, escuelaA, null, Rol.ADMIN);
		adminDeB = new UsuarioAutenticado(adminDeBId, escuelaB, null, Rol.ADMIN);
	}

	@AfterEach
	void limpiar() {
		datos.limpiarEscuela(escuelaA);
		datos.limpiarEscuela(escuelaB);
	}

	// ---------- utilidades ----------

	private static String codigo(EnrolamientoMfaRespuesta e, int pasosDeDesfase) {
		return Totp.codigo(Base32.decodificar(e.secretoBase32()), Totp.pasoDe(Instant.now()) + pasosDeDesfase);
	}

	private boolean mfaHabilitado(UUID usuarioId) {
		return jdbc.sql("SELECT mfa_habilitado FROM gestion_patin.usuario WHERE id = :u").param("u", usuarioId)
				.query(Boolean.class).single();
	}

	private long filasMfa(UUID usuarioId) {
		return jdbc.sql("SELECT count(*) FROM gestion_patin.usuario_mfa WHERE usuario_id = :u").param("u", usuarioId)
				.query(Long.class).single();
	}

	private byte[] cifrado(UUID usuarioId) {
		return jdbc.sql("SELECT secreto_cifrado FROM gestion_patin.usuario_mfa WHERE usuario_id = :u")
				.param("u", usuarioId).query(byte[].class).single();
	}

	private Long ultimoPaso(UUID usuarioId) {
		return (Long) jdbc.sql("SELECT ultimo_paso_usado FROM gestion_patin.usuario_mfa WHERE usuario_id = :u")
				.param("u", usuarioId).query().singleRow().get("ultimo_paso_usado");
	}

	private List<String> acciones(UUID escuela) {
		return jdbc.sql("SELECT accion FROM gestion_patin.auditoria WHERE escuela_id = :e ORDER BY id")
				.param("e", escuela).query(String.class).list();
	}

	private static void assertCodigoInvalido(Runnable accion) {
		assertThatThrownBy(accion::run).isInstanceOfSatisfying(ExcepcionNegocio.class, e -> {
			assertThat(e.getEstado().value()).isEqualTo(401);
			assertThat(e.getCodigo()).isEqualTo("CODIGO_MFA_INVALIDO");
		});
	}

	private EnrolamientoMfaRespuesta enrolarYConfirmar(UsuarioAutenticado quien) {
		EnrolamientoMfaRespuesta e = servicio.enrolar(quien, DATOS);
		servicio.confirmar(quien, codigo(e, 0), DATOS);
		return e;
	}

	// ---------- flujo ----------

	@Test
	void enrolarConfirmarYVerificarPersistenElEstadoRealEnLaBase() {
		EnrolamientoMfaRespuesta e = servicio.enrolar(admin, DATOS);

		assertThat(filasMfa(adminId)).isEqualTo(1);
		assertThat(mfaHabilitado(adminId)).isFalse();
		byte[] guardado = cifrado(adminId);
		assertThat(guardado).hasSize(12 + 20 + 16);
		assertThat(new String(guardado, StandardCharsets.ISO_8859_1))
				.doesNotContain(new String(Base32.decodificar(e.secretoBase32()), StandardCharsets.ISO_8859_1));
		assertThat(cifrador.descifrar(guardado, adminId)).isEqualTo(Base32.decodificar(e.secretoBase32()));

		var respuesta = servicio.confirmar(admin, codigo(e, 0), DATOS);

		assertThat(respuesta.mfaEnrolado()).isTrue();
		assertThat(respuesta.mfaPendiente()).isFalse();
		assertThat(mfaHabilitado(adminId)).isTrue();
		long pasoConfirmacion = ultimoPaso(adminId);
		assertThat(pasoConfirmacion).isBetween(Totp.pasoDe(Instant.now()) - 1, Totp.pasoDe(Instant.now()) + 1);

		// Verificacion con el codigo del paso siguiente (dentro de la ventana +1, mayor que el ultimo usado).
		servicio.verificar(admin, codigo(e, 1), DATOS);
		assertThat(ultimoPaso(adminId)).isGreaterThan(pasoConfirmacion);
	}

	@Test
	void laAuditoriaRegistraLosEventosRealesSinCodigosNiSecretos() {
		EnrolamientoMfaRespuesta e = servicio.enrolar(admin, DATOS);
		String bueno = codigo(e, 0);
		String malo = "%06d".formatted((Integer.parseInt(bueno) + 1) % 1_000_000);
		assertCodigoInvalido(() -> servicio.confirmar(admin, malo, DATOS));
		servicio.confirmar(admin, bueno, DATOS);
		servicio.verificar(admin, codigo(e, 1), DATOS);

		assertThat(acciones(escuelaA)).containsExactly("MFA_ENROLADO", "MFA_FALLO", "MFA_CONFIRMADO", "MFA_VERIFICADO");
		var filas = jdbc.sql("""
				SELECT accion, usuario_id, recurso_tipo, recurso_id, host(ip) AS ip, user_agent, detalle::text AS detalle
				FROM gestion_patin.auditoria WHERE escuela_id = :e ORDER BY id
				""").param("e", escuelaA).query().listOfRows();
		for (var fila : filas) {
			assertThat(fila.get("usuario_id")).isEqualTo(adminId);
			assertThat(fila.get("recurso_id")).isEqualTo(adminId);
			assertThat(fila.get("ip")).isEqualTo("10.0.0.7");
			String todo = fila.toString();
			assertThat(todo).doesNotContain(bueno).doesNotContain(malo).doesNotContain(e.secretoBase32())
					.doesNotContain("otpauth");
		}
		assertThat((String) filas.get(1).get("detalle")).contains("CONFIRMACION", "CODIGO");
	}

	@Test
	void elFalloSeAuditaAunqueLaTransaccionDeNegocioHagaRollback() {
		EnrolamientoMfaRespuesta e = servicio.enrolar(admin, DATOS);
		String malo = "%06d".formatted((Integer.parseInt(codigo(e, 0)) + 7) % 1_000_000);

		assertCodigoInvalido(() -> servicio.confirmar(admin, malo, DATOS));

		assertThat(acciones(escuelaA)).contains("MFA_FALLO");
		assertThat(mfaHabilitado(adminId)).isFalse();
		assertThat(ultimoPaso(adminId)).isNull();
	}

	@Test
	void unCodigoRepetidoSeRechazaYNoCambiaElPasoGuardado() {
		EnrolamientoMfaRespuesta e = servicio.enrolar(admin, DATOS);
		String codigo = codigo(e, 0);
		servicio.confirmar(admin, codigo, DATOS);
		long paso = ultimoPaso(adminId);

		assertCodigoInvalido(() -> servicio.verificar(admin, codigo, DATOS));

		assertThat(ultimoPaso(adminId)).isEqualTo(paso);
		assertThat(acciones(escuelaA)).contains("MFA_FALLO");
	}

	@Test
	void variasPeticionesConcurrentesConElMismoCodigoTienenExactamenteUnExito() throws Exception {
		EnrolamientoMfaRespuesta e = servicio.enrolar(admin, DATOS);
		servicio.confirmar(admin, codigo(e, 0), DATOS);
		// Codigo del paso siguiente (valido por la ventana +1): todas las peticiones lo presentan a la vez.
		String codigo = codigo(e, 1);
		int hilos = 4;
		ExecutorService pool = Executors.newFixedThreadPool(hilos);
		CountDownLatch salida = new CountDownLatch(1);
		List<Future<Boolean>> futuros = new ArrayList<>();
		for (int i = 0; i < hilos; i++) {
			futuros.add(pool.submit(() -> {
				salida.await();
				try {
					servicio.verificar(admin, codigo, DATOS);
					return true;
				} catch (ExcepcionNegocio ex) {
					assertThat(ex.getCodigo()).isEqualTo("CODIGO_MFA_INVALIDO");
					return false;
				}
			}));
		}
		salida.countDown();
		int exitos = 0;
		for (Future<Boolean> f : futuros) {
			exitos += f.get(30, TimeUnit.SECONDS) ? 1 : 0;
		}
		pool.shutdown();

		assertThat(exitos).isEqualTo(1);
		assertThat(acciones(escuelaA).stream().filter("MFA_VERIFICADO"::equals).count()).isEqualTo(1);
	}

	@Test
	void unEnrolamientoSinConfirmarSeReemplazaYUnoConfirmadoNo() {
		EnrolamientoMfaRespuesta primero = servicio.enrolar(admin, DATOS);
		byte[] cifradoPrimero = cifrado(adminId);
		EnrolamientoMfaRespuesta segundo = servicio.enrolar(admin, DATOS);

		assertThat(cifrado(adminId)).isNotEqualTo(cifradoPrimero);
		assertThat(filasMfa(adminId)).isEqualTo(1);
		assertThat(segundo.secretoBase32()).isNotEqualTo(primero.secretoBase32());

		servicio.confirmar(admin, codigo(segundo, 0), DATOS);
		byte[] confirmado = cifrado(adminId);

		assertThatThrownBy(() -> servicio.enrolar(admin, DATOS)).isInstanceOfSatisfying(ExcepcionNegocio.class,
				ex -> assertThat(ex.getCodigo()).isEqualTo("MFA_ESTADO_INVALIDO"));
		assertThat(cifrado(adminId)).isEqualTo(confirmado);
	}

	@Test
	void unSecretoCopiadoALaFilaDeOtroUsuarioNoDescifraPorElAad() {
		EnrolamientoMfaRespuesta deA = enrolarYConfirmar(admin);
		EnrolamientoMfaRespuesta deB = enrolarYConfirmar(otroAdmin);
		jdbc.sql("UPDATE gestion_patin.usuario_mfa SET secreto_cifrado = :s WHERE usuario_id = :u")
				.param("s", cifrado(adminId)).param("u", otroAdminId).update();

		// El secreto de A bajo el id de B no autentica (AAD distinto): fallo interno, nunca una verificacion exitosa.
		assertThatThrownBy(() -> servicio.verificar(otroAdmin, codigo(deA, 1), DATOS))
				.isInstanceOf(IllegalStateException.class).hasMessageNotContaining(deA.secretoBase32())
				.hasMessageNotContaining(deB.secretoBase32());
	}

	// ---------- reinicio ----------

	@Test
	void otroAdminReiniciaElMfaYElReinicioQuedaAuditadoConElActor() {
		enrolarYConfirmar(admin);
		assertThat(mfaHabilitado(adminId)).isTrue();

		servicio.reiniciar(otroAdmin, adminId, DATOS);

		assertThat(filasMfa(adminId)).isZero();
		assertThat(mfaHabilitado(adminId)).isFalse();
		var evento = jdbc.sql("""
				SELECT usuario_id, recurso_id, detalle::text AS detalle FROM gestion_patin.auditoria
				WHERE escuela_id = :e AND accion = 'MFA_REINICIADO'
				""").param("e", escuelaA).query().singleRow();
		assertThat(evento.get("usuario_id")).isEqualTo(otroAdminId);
		assertThat(evento.get("recurso_id")).isEqualTo(adminId);
		// Puede enrolar de nuevo despues del reinicio.
		EnrolamientoMfaRespuesta nuevo = servicio.enrolar(admin, DATOS);
		servicio.confirmar(admin, codigo(nuevo, 0), DATOS);
		assertThat(mfaHabilitado(adminId)).isTrue();
	}

	@Test
	void reiniciarElPropioMfaDa403YNoCambiaNada() {
		enrolarYConfirmar(admin);

		assertThatThrownBy(() -> servicio.reiniciar(admin, adminId, DATOS)).isInstanceOfSatisfying(ExcepcionNegocio.class,
				ex -> {
					assertThat(ex.getEstado().value()).isEqualTo(403);
					assertThat(ex.getCodigo()).isEqualTo("MFA_AUTOREINICIO_NO_PERMITIDO");
				});

		assertThat(filasMfa(adminId)).isEqualTo(1);
		assertThat(mfaHabilitado(adminId)).isTrue();
	}

	@Test
	void reiniciarAUnAdminDeOtraEscuelaDa404YNoTocaSuFactor() {
		enrolarYConfirmar(adminDeB);

		assertThatThrownBy(() -> servicio.reiniciar(admin, adminDeBId, DATOS)).isInstanceOfSatisfying(ExcepcionNegocio.class,
				ex -> assertThat(ex.getEstado().value()).isEqualTo(404));

		assertThat(filasMfa(adminDeBId)).isEqualTo(1);
		assertThat(mfaHabilitado(adminDeBId)).isTrue();
		assertThat(acciones(escuelaB)).doesNotContain("MFA_REINICIADO");
	}

	@Test
	void reiniciarAUnUsuarioFamiliaDa404() {
		UUID familia = datos.familia(escuelaA, "Los Gomez", true);
		UUID madre = datos.usuarioFamilia(escuelaA, familia, "madre@a.example");

		assertThatThrownBy(() -> servicio.reiniciar(admin, madre, DATOS)).isInstanceOfSatisfying(ExcepcionNegocio.class,
				ex -> assertThat(ex.getEstado().value()).isEqualTo(404));
	}
}
