package com.banfieldpatin.backend.db;

import static com.banfieldpatin.backend.db.DatosDb.EN_1_DIA;
import static com.banfieldpatin.backend.db.DatosDb.HACE_1_DIA;
import static com.banfieldpatin.backend.db.DatosDb.HACE_2_DIAS;
import static com.banfieldpatin.backend.db.DatosDb.NULO;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.banfieldpatin.backend.compartido.auditoria.AuditoriaService;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.escuelas.EscuelaActual;
import com.banfieldpatin.backend.familias.invitaciones.GeneradorTokenInvitacion;
import com.banfieldpatin.backend.familias.invitaciones.RegistroPorInvitacionService;
import com.banfieldpatin.backend.familias.invitaciones.dto.RegistroSolicitud;
import com.banfieldpatin.backend.usuarios.dto.UsuarioActualRespuesta;

import tools.jackson.databind.json.JsonMapper;

/**
 * Consumo atomico de una invitacion contra PostgreSQL real: bloqueo FOR UPDATE, insert del usuario antes del UPDATE
 * condicional, rollback ante email duplicado y dos registros concurrentes con el mismo token. Se usan los beans
 * reales (proxies transaccionales incluidos); la auditoria de fallos (REQUIRES_NEW) debe sobrevivir al rollback.
 */
@PruebaDb
@Import(ConsumoAtomicoDbTest.Config.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsumoAtomicoDbTest extends BaseDbTest {

	/** Slug de la escuela "configurada": EscuelaActual cachea su id, por eso la escuela vive toda la clase. */
	private static final String SLUG = "consumo-" + UUID.randomUUID();
	private static final String PASSWORD = "clave-secreta-123";
	private static final DatosSolicitud DATOS = new DatosSolicitud("10.0.0.1", "JUnit");

	@TestConfiguration
	@Import({ RegistroPorInvitacionService.class, AuditoriaService.class, EscuelaActual.class })
	static class Config {

		@Bean
		Clock reloj() {
			return Clock.systemUTC();
		}

		@Bean
		PasswordEncoder passwordEncoder() {
			return PasswordEncoderFactories.createDelegatingPasswordEncoder();
		}

		@Bean
		JsonMapper jsonMapper() {
			return JsonMapper.builder().build();
		}
	}

	@DynamicPropertySource
	static void escuelaConfigurada(DynamicPropertyRegistry registro) {
		registro.add("banfield.escuela.slug", () -> SLUG);
	}

	@Autowired
	JdbcClient jdbc;
	@Autowired
	RegistroPorInvitacionService registro;

	private DatosDb datos;
	private UUID escuelaId;
	private UUID familiaId;
	private UUID adminId;

	@BeforeAll
	void crearEscuela() {
		datos = new DatosDb(jdbc);
		escuelaId = datos.escuela(SLUG);
	}

	@AfterAll
	void borrarEscuela() {
		datos.limpiarEscuela(escuelaId);
	}

	@BeforeEach
	void preparar() {
		familiaId = datos.familia(escuelaId, "Familia de prueba", true);
		adminId = datos.admin(escuelaId, "admin@consumo.example", true);
	}

	@AfterEach
	void limpiar() {
		datos.limpiarContenido(escuelaId);
	}

	private String nuevaInvitacion(String creadoEn, String expiraEn) {
		String token = new GeneradorTokenInvitacion().generar();
		datos.invitacion(escuelaId, familiaId, adminId, GeneradorTokenInvitacion.sha256Hex(token), creadoEn, expiraEn,
				null, NULO, null, NULO);
		return token;
	}

	private static RegistroSolicitud solicitud(String token, String email) {
		return new RegistroSolicitud(token, "Ana", "Perez", email, PASSWORD);
	}

	private long contar(String sql) {
		return jdbc.sql(sql).param("e", escuelaId).query(Long.class).single();
	}

	// ---------- exito ----------

	@Test
	void registroExitosoCreaUsuarioFamiliaMarcaLaInvitacionYAudita() {
		String token = nuevaInvitacion("now() - interval '1 hour'", EN_1_DIA);

		UsuarioActualRespuesta r = registro.registrar(solicitud(token, "Madre@Consumo.example"), DATOS);

		assertThat(r.rol().name()).isEqualTo("FAMILIA");
		assertThat(r.email()).isEqualTo("madre@consumo.example");
		assertThat(r.familiaId()).isEqualTo(familiaId);
		assertThat(jdbc.sql("""
				SELECT usuario_id FROM gestion_patin.invitacion WHERE token_hash = :h
				""").param("h", GeneradorTokenInvitacion.sha256Hex(token)).query(UUID.class).single()).isEqualTo(r.id());
		assertThat(jdbc.sql("SELECT password_hash FROM gestion_patin.usuario WHERE id = :id").param("id", r.id())
				.query(String.class).single()).startsWith("{bcrypt}");
		// Auditoria real: fila con el usuario nuevo, ip como inet y sin token en el detalle.
		var fila = jdbc.sql("""
				SELECT usuario_id, host(ip) AS ip, detalle::text AS detalle FROM gestion_patin.auditoria
				WHERE escuela_id = :e AND accion = 'REGISTRO_POR_INVITACION'
				""").param("e", escuelaId).query().singleRow();
		assertThat(fila.get("usuario_id")).isEqualTo(r.id());
		assertThat(fila.get("ip")).isEqualTo("10.0.0.1");
		assertThat((String) fila.get("detalle")).doesNotContain(token);
	}

	@Test
	void unSegundoUsoDelMismoTokenDaNoDisponible() {
		String token = nuevaInvitacion("now() - interval '1 hour'", EN_1_DIA);
		registro.registrar(solicitud(token, "uno@consumo.example"), DATOS);

		ExcepcionNegocio e = fallo(solicitud(token, "dos@consumo.example"));

		assertThat(e.getCodigo()).isEqualTo("INVITACION_NO_DISPONIBLE");
		assertThat(contar("SELECT count(*) FROM gestion_patin.usuario WHERE escuela_id = :e AND rol = 'FAMILIA'"))
				.isEqualTo(1);
	}

	@Test
	void unaInvitacionVencidaNoSeConsume() {
		String token = nuevaInvitacion(HACE_2_DIAS, HACE_1_DIA);

		ExcepcionNegocio e = fallo(solicitud(token, "madre@consumo.example"));

		assertThat(e.getCodigo()).isEqualTo("INVITACION_NO_DISPONIBLE");
		assertThat(datos.contarUsuarios(escuelaId, "FAMILIA")).isZero();
	}

	// ---------- email duplicado: rollback ----------

	@Test
	void emailDuplicadoDa409DejaLaInvitacionPendienteYSePuedeReutilizar() {
		datos.usuarioFamilia(escuelaId, familiaId, "existente@consumo.example");
		String token = nuevaInvitacion("now() - interval '1 hour'", EN_1_DIA);

		ExcepcionNegocio e = fallo(solicitud(token, "EXISTENTE@consumo.example"));

		assertThat(e.getCodigo()).isEqualTo("EMAIL_YA_REGISTRADO");
		assertThat(datos.contarUsuarios(escuelaId, "FAMILIA")).isEqualTo(1);
		assertThat(contar("SELECT count(*) FROM gestion_patin.invitacion WHERE escuela_id = :e AND usado_en IS NULL"))
				.isEqualTo(1);
		// La auditoria de fallo se escribio en su propia transaccion y sobrevive al rollback.
		assertThat(contar("SELECT count(*) FROM gestion_patin.auditoria WHERE escuela_id = :e AND accion = 'REGISTRO_FALLIDO'"))
				.isEqualTo(1);
		// La misma invitacion sigue sirviendo con otro email.
		assertThat(registro.registrar(solicitud(token, "nuevo@consumo.example"), DATOS).email())
				.isEqualTo("nuevo@consumo.example");
	}

	// ---------- concurrencia ----------

	@Test
	void dosRegistrosConcurrentesConElMismoTokenCreanExactamenteUnUsuario() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			for (int ronda = 0; ronda < 5; ronda++) {
				String token = nuevaInvitacion("now() - interval '1 hour'", EN_1_DIA);
				long antes = datos.contarUsuarios(escuelaId, "FAMILIA");
				CountDownLatch salida = new CountDownLatch(1);
				List<Future<Object>> futuros = new ArrayList<>();
				for (int i = 0; i < 2; i++) {
					String email = "ronda" + ronda + "-" + i + "@consumo.example";
					futuros.add(pool.submit(intento(salida, solicitud(token, email))));
				}
				salida.countDown();

				int exitos = 0;
				int rechazos = 0;
				for (Future<Object> f : futuros) {
					Object resultado = f.get(30, TimeUnit.SECONDS);
					if (resultado instanceof UsuarioActualRespuesta) {
						exitos++;
					} else if (resultado instanceof ExcepcionNegocio e && e.getCodigo().equals("INVITACION_NO_DISPONIBLE")) {
						rechazos++;
					}
				}

				assertThat(exitos).as("ronda %d: exitos", ronda).isEqualTo(1);
				assertThat(rechazos).as("ronda %d: rechazos", ronda).isEqualTo(1);
				assertThat(datos.contarUsuarios(escuelaId, "FAMILIA")).isEqualTo(antes + 1);
			}
		} finally {
			pool.shutdownNow();
		}
	}

	private Callable<Object> intento(CountDownLatch salida, RegistroSolicitud solicitud) {
		return () -> {
			salida.await();
			try {
				return registro.registrar(solicitud, DATOS);
			} catch (ExcepcionNegocio e) {
				return e;
			}
		};
	}

	private ExcepcionNegocio fallo(RegistroSolicitud solicitud) {
		try {
			registro.registrar(solicitud, DATOS);
		} catch (ExcepcionNegocio e) {
			return e;
		}
		throw new AssertionError("Se esperaba ExcepcionNegocio");
	}
}
