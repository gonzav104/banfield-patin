package com.banfieldpatin.backend.deportistas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.util.ReflectionTestUtils;

import com.banfieldpatin.backend.compartido.RelojConfig;
import com.banfieldpatin.backend.compartido.auditoria.AuditoriaService;
import com.banfieldpatin.backend.compartido.error.ManejadorGlobalErrores;
import com.banfieldpatin.backend.seguridad.CookieBearerTokenResolver;
import com.banfieldpatin.backend.seguridad.CookieSesion;
import com.banfieldpatin.backend.seguridad.JwtConfig;
import com.banfieldpatin.backend.seguridad.ManejadorAccesoDenegadoJson;
import com.banfieldpatin.backend.seguridad.PuntoEntradaJson;
import com.banfieldpatin.backend.seguridad.SeguridadConfig;
import com.banfieldpatin.backend.seguridad.ServicioTokens;
import com.banfieldpatin.backend.seguridad.SesionVigenteDePrueba;
import com.banfieldpatin.backend.usuarios.Rol;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.Cookie;

/**
 * REQ-DEP-10 S1 (sin base de datos): el controlador, el servicio REAL y el manejador de errores, atravesados por
 * solicitudes HTTP de alta y edicion (con exito, con 409 y con un 500), no escriben el DNI ni el CUIL en ningun log, ni
 * siquiera con el nivel DEBUG. (En TRACE el propio Spring MVC imprime el valor rechazado de los errores de validacion; la
 * aplicacion no lo configura y por defecto es INFO.) Las aserciones se hacen contra las cadenas propias de esta prueba.
 */
@WebMvcTest(controllers = DeportistaAdminController.class)
@Import({ SeguridadConfig.class, JwtConfig.class, CookieSesion.class, CookieBearerTokenResolver.class,
		PuntoEntradaJson.class, ManejadorAccesoDenegadoJson.class, ManejadorGlobalErrores.class, ServicioTokens.class,
		RelojConfig.class, SesionVigenteDePrueba.class, DeportistaAdminService.class })
@ActiveProfiles("test")
class DeportistaLogsSinDocumentosWebMvcTest {

	private static final String DNI = "37123456";
	private static final String DNI_CRUDO = "37.123.456";
	private static final String CUIL = "20371234560";
	private static final String CUIL_CRUDO = "20-37123456-0";
	private static final String CUERPO = "{\"dni\":\"" + DNI_CRUDO + "\",\"nombre\":\"Zuleica\",\"apellido\":\"Quintana\","
			+ "\"cuil\":\"" + CUIL_CRUDO + "\",\"domicilio\":\"Calle Secreta 123\"}";

	@Autowired
	MockMvc mvc;
	@Autowired
	ServicioTokens tokens;
	@MockitoBean
	DeportistaRepository deportistas;
	@MockitoBean
	AuditoriaService auditoria;

	private final UUID escuelaId = UUID.randomUUID();
	private final UUID id = UUID.randomUUID();

	private Logger raiz;
	private Level nivelOriginal;
	private ListAppender<ILoggingEvent> logs;

	@BeforeEach
	void capturarTodo() {
		raiz = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		nivelOriginal = raiz.getLevel();
		raiz.setLevel(Level.DEBUG);
		logs = new ListAppender<>();
		logs.start();
		raiz.addAppender(logs);
		when(deportistas.saveAndFlush(any(Deportista.class))).thenAnswer(inv -> {
			Deportista d = inv.getArgument(0);
			if (d.getId() == null) {
				ReflectionTestUtils.setField(d, "id", id);
			}
			return d;
		});
		when(deportistas.activoPorDni(any(), any())).thenReturn(Optional.empty());
		when(deportistas.activoPorDniDeOtro(any(), any(), any())).thenReturn(Optional.empty());
	}

	@AfterEach
	void liberar() {
		raiz.detachAppender(logs);
		raiz.setLevel(nivelOriginal);
	}

	private Cookie admin() {
		return new Cookie("BP_SESION", tokens.emitir(UUID.randomUUID(), Rol.ADMIN, escuelaId, null));
	}

	private MockHttpServletRequestBuilder conCuerpo(MockHttpServletRequestBuilder req, String json) throws Exception {
		MockHttpServletResponse r = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse();
		Cookie x = r.getCookie("XSRF-TOKEN");
		return req.cookie(x, admin()).header("X-XSRF-TOKEN", x.getValue()).contentType(MediaType.APPLICATION_JSON)
				.content(json);
	}

	private Deportista existente() {
		Deportista d = Deportista.crear(escuelaId, "Zuleica", "Quintana", DNI, null, null, null, null, null, null, null,
				null, null, null);
		ReflectionTestUtils.setField(d, "id", id);
		when(deportistas.findByIdAndEscuelaId(id, escuelaId)).thenReturn(Optional.of(d));
		return d;
	}

	private void sinDocumentosEnLogs() {
		assertThat(logs.list).isNotEmpty();
		sinDocumentosEnLosEventos();
	}

	private void sinDocumentosEnLosEventos() {
		for (ILoggingEvent evento : logs.list) {
			String texto = evento.getFormattedMessage() + " " + evento.getThrowableProxy() + " " + evento.getMDCPropertyMap()
					+ " " + java.util.Arrays.toString(evento.getArgumentArray());
			assertThat(texto).as("log de %s", evento.getLoggerName()).doesNotContain(DNI).doesNotContain(DNI_CRUDO)
					.doesNotContain(CUIL).doesNotContain(CUIL_CRUDO).doesNotContain("Calle Secreta");
		}
	}

	@Test
	void unAltaExitosaNoRegistraDniNiCuil() throws Exception {
		mvc.perform(conCuerpo(post("/api/admin/deportistas"), CUERPO)).andReturn();

		sinDocumentosEnLogs();
	}

	@Test
	void unAltaRechazadaPorDniDuplicadoOCuilDuplicadoNoRegistraDniNiCuil() throws Exception {
		when(deportistas.activoPorDni(any(), any())).thenReturn(Optional.of(true));
		mvc.perform(conCuerpo(post("/api/admin/deportistas"), CUERPO)).andReturn();
		when(deportistas.activoPorDni(any(), any())).thenReturn(Optional.empty());
		when(deportistas.existsByEscuelaIdAndCuil(any(), any())).thenReturn(true);
		mvc.perform(conCuerpo(post("/api/admin/deportistas"), CUERPO)).andReturn();

		sinDocumentosEnLogs();
	}

	@Test
	void unaViolacionDeUnicidadEnElFlushNoRegistraDniNiCuil() throws Exception {
		when(deportistas.saveAndFlush(any(Deportista.class))).thenThrow(new DataIntegrityViolationException("conflicto",
				new ConstraintViolationException("duplicado", new SQLException("x"), "insert ...", ConstraintKind.UNIQUE,
						"uq_deportista_dni_escuela")));

		mvc.perform(conCuerpo(post("/api/admin/deportistas"), CUERPO)).andReturn();

		sinDocumentosEnLogs();
	}

	@Test
	void unaEdicionConCambiosOSinEllosNoRegistraDniNiCuil() throws Exception {
		existente();

		mvc.perform(conCuerpo(put("/api/admin/deportistas/" + id), CUERPO)).andReturn();
		mvc.perform(conCuerpo(put("/api/admin/deportistas/" + id), CUERPO)).andReturn();

		sinDocumentosEnLogs();
	}

	@Test
	void unaEdicionRechazadaPorUnicidadNoRegistraDniNiCuil() throws Exception {
		existente();
		when(deportistas.activoPorDniDeOtro(any(), any(), any())).thenReturn(Optional.of(false));

		mvc.perform(conCuerpo(put("/api/admin/deportistas/" + id), CUERPO.replace(DNI_CRUDO, "37.999.888"))).andReturn();

		sinDocumentosEnLogs();
	}

	@Test
	void unErrorInesperadoDelRepositorioNoArrastraElCuerpoDeLaSolicitudALosLogs() throws Exception {
		when(deportistas.saveAndFlush(any(Deportista.class))).thenThrow(new IllegalStateException("fallo simulado"));

		MockHttpServletResponse r = mvc.perform(conCuerpo(post("/api/admin/deportistas"), CUERPO)).andReturn()
				.getResponse();

		assertThat(r.getStatus()).isEqualTo(500);
		assertThat(r.getContentAsString()).doesNotContain(DNI).doesNotContain(CUIL);
		sinDocumentosEnLogs();
	}

	/**
	 * Con el nivel por defecto de la aplicacion (INFO). A DEBUG el propio Spring MVC registra "Resolved
	 * [MethodArgumentNotValidException ... rejected value [...]]" con el valor rechazado de un campo invalido: es
	 * comportamiento del framework, no se activa en la aplicacion y por eso aqui se comprueba el nivel real.
	 */
	@Test
	void unCuerpoInvalidoOMalFormadoNoRegistraDniNiCuilConElNivelPorDefecto() throws Exception {
		raiz.setLevel(Level.INFO);
		mvc.perform(conCuerpo(post("/api/admin/deportistas"), CUERPO.replace(CUIL_CRUDO, "20-37123456-5"))).andReturn();
		mvc.perform(conCuerpo(post("/api/admin/deportistas"), CUERPO.replace("}", ",\"activo\":false}"))).andReturn();
		mvc.perform(conCuerpo(post("/api/admin/deportistas"), CUERPO + "{{")).andReturn();

		sinDocumentosEnLosEventos();
	}

	@Test
	void laRepresentacionEnTextoDeLosDtosNoIncluyeDocumentos() {
		var solicitud = new com.banfieldpatin.backend.deportistas.dto.DeportistaSolicitud(DNI, "Zuleica", "Quintana", CUIL,
				null, null, null, null, null, null, null, null, null);
		var detalle = com.banfieldpatin.backend.deportistas.dto.DeportistaDetalle
				.de(Deportista.crear(escuelaId, "Zuleica", "Quintana", DNI, CUIL, null, null, null, null, null, null, null,
						null, null));

		assertThat(solicitud.toString()).doesNotContain(DNI).doesNotContain(CUIL).doesNotContain("Zuleica");
		assertThat(detalle.toString()).doesNotContain(DNI).doesNotContain(CUIL).doesNotContain("Zuleica");
	}
}
