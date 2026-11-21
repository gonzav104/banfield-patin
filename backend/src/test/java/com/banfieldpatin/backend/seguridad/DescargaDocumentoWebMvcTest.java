package com.banfieldpatin.backend.seguridad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.documentos.descarga.DescargaDocumentoService;
import com.banfieldpatin.backend.usuarios.Rol;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;

/** Matriz HTTP de la descarga administrativa (U16) con la cadena de seguridad real y el servicio simulado. */
class DescargaDocumentoWebMvcTest extends BaseOpenApiWebMvc {
	private static final byte[] CONTENIDO = "%PDF-sintetico".getBytes(StandardCharsets.US_ASCII);
	private static final String PLANTILLA = "/api/admin/deportistas/{deportistaId}/documentos/{documentoId}/archivo";
	private final UUID deportista = UUID.randomUUID(), documento = UUID.randomUUID();
	private Cookie xsrf;

	@BeforeEach
	void preparar() throws Exception {
		verificador.reiniciar();
		clearInvocations(descargaDocumentoService);
		xsrf = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		assertThat(xsrf).isNotNull();
		doAnswer(i -> {
			i.<DescargaDocumentoService.Entrega>getArgument(4).escribir("application/pdf", "dni-dorso.pdf", CONTENIDO);
			return null;
		}).when(descargaDocumentoService).descargar(any(), any(), any(), any(), any());
	}

	private String ruta() { return "/api/admin/deportistas/" + deportista + "/documentos/" + documento + "/archivo"; }

	private MockHttpServletRequestBuilder conCsrf(MockHttpServletRequestBuilder pedido) {
		return pedido.cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue());
	}

	@Test
	void adminConMfaYCsrfRecibeElArchivoConCabecerasSeguras() throws Exception {
		var usuario = UUID.randomUUID();
		var escuela = UUID.randomUUID();
		var sesion = new Cookie(COOKIE, tokens.emitir(usuario, Rol.ADMIN, escuela, null));
		var r = mvc.perform(conCsrf(get(ruta())).cookie(sesion).param("escuelaId", UUID.randomUUID().toString()))
				.andExpect(status().isOk()).andReturn().getResponse();
		assertThat(r.getContentAsByteArray()).isEqualTo(CONTENIDO);
		assertThat(r.getContentType()).isEqualTo("application/pdf");
		assertThat(r.getContentLengthLong()).isEqualTo(CONTENIDO.length);
		assertThat(r.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"dni-dorso.pdf\"");
		assertThat(r.getHeaders("X-Content-Type-Options")).containsExactly("nosniff");
		assertThat(r.getHeaders("Cache-Control")).containsExactly("no-store");
		assertThat(r.getHeader("Content-Security-Policy")).isEqualTo("sandbox; default-src 'none'");
		var identidad = ArgumentCaptor.forClass(UsuarioAutenticado.class);
		verify(descargaDocumentoService).descargar(identidad.capture(), eq(deportista), eq(documento), any(), any());
		assertThat(identidad.getValue()).isEqualTo(new UsuarioAutenticado(usuario, escuela, null, Rol.ADMIN));
	}

	// ---- U19: la descarga de un DNI de un tutor tiene las mismas reglas de acceso ----

	private String rutaTutor() { return "/api/admin/tutores/" + deportista + "/documentos/" + documento + "/archivo"; }

	@Test
	void laDescargaDeTutorExigeAdminConMfaYCsrfYNuncaUsaLaRutaDelDeportista() throws Exception {
		doAnswer(i -> {
			i.<DescargaDocumentoService.Entrega>getArgument(4).escribir("image/png", "dni-frente.png", CONTENIDO);
			return null;
		}).when(descargaDocumentoService).descargarTutor(any(), any(), any(), any(), any());
		mvc.perform(conCsrf(get(rutaTutor()))).andExpect(status().isUnauthorized());
		mvc.perform(conCsrf(get(rutaTutor())).cookie(sesion(Rol.FAMILIA))).andExpect(status().isForbidden());
		mvc.perform(conCsrf(get(rutaTutor())).cookie(sesionMfaPendiente())).andExpect(status().isForbidden());
		mvc.perform(get(rutaTutor()).cookie(sesion(Rol.ADMIN), xsrf)).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
		mvc.perform(conCsrf(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head(rutaTutor())).cookie(sesion(Rol.ADMIN))).andExpect(status().isMethodNotAllowed());
		verifyNoInteractions(descargaDocumentoService);
		var r = mvc.perform(conCsrf(get(rutaTutor())).cookie(sesion(Rol.ADMIN))).andExpect(status().isOk())
				.andReturn().getResponse();
		assertThat(r.getContentAsByteArray()).isEqualTo(CONTENIDO);
		assertThat(r.getHeaders("Cache-Control")).containsExactly("no-store");
		assertThat(r.getHeader("Content-Security-Policy")).isEqualTo("sandbox; default-src 'none'");
		verify(descargaDocumentoService).descargarTutor(any(), eq(deportista), eq(documento), any(), any());
		verify(descargaDocumentoService, org.mockito.Mockito.never()).descargar(any(), any(), any(), any(), any());
	}

	@Test
	void sinSesionEs401YNoConsulta() throws Exception {
		mvc.perform(conCsrf(get(ruta()))).andExpect(status().isUnauthorized());
		verifyNoInteractions(descargaDocumentoService);
	}

	@Test
	void familiaYAdminConMfaPendienteSonRechazados() throws Exception {
		mvc.perform(conCsrf(get(ruta())).cookie(sesion(Rol.FAMILIA))).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
		mvc.perform(conCsrf(get(ruta())).cookie(sesionMfaPendiente())).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
		verifyNoInteractions(descargaDocumentoService);
	}

	@Test
	void sinCabeceraCsrfOConUnValorDistintoEs403CsrfInvalido() throws Exception {
		var sesion = sesion(Rol.ADMIN);
		List<MockHttpServletRequestBuilder> pedidos = List.of(
				get(ruta()).cookie(sesion, xsrf),
				get(ruta()).cookie(sesion, xsrf).header("X-XSRF-TOKEN", ""),
				get(ruta()).cookie(sesion, xsrf).header("X-XSRF-TOKEN", UUID.randomUUID().toString()),
				get(ruta()).cookie(sesion).header("X-XSRF-TOKEN", xsrf.getValue()),
				get(ruta()).cookie(sesion).param("_csrf", xsrf.getValue()).cookie(xsrf));
		for (var pedido : pedidos) {
			mvc.perform(pedido).andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"))
					.andExpect(jsonPath("$.mensaje").value("Token CSRF ausente o inválido."))
					.andExpect(jsonPath("$.detalles").isArray());
		}
		verifyNoInteractions(descargaDocumentoService);
	}

	@Test
	void identificadoresQueNoSonUuidSon400() throws Exception {
		var sesion = sesion(Rol.ADMIN);
		for (String ruta : List.of(ruta().replace(deportista.toString(), "no-uuid"), ruta().replace(documento.toString(), "1")))
			mvc.perform(conCsrf(get(ruta)).cookie(sesion)).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
		verifyNoInteractions(descargaDocumentoService);
	}

	@Test
	void sesionRevocadaOBaseNoDisponibleFallaCerrado() throws Exception {
		verificador.rechazar();
		mvc.perform(conCsrf(get(ruta())).cookie(sesion(Rol.ADMIN))).andExpect(status().isUnauthorized());
		verificador.fallarConBaseNoDisponible();
		mvc.perform(conCsrf(get(ruta())).cookie(sesion(Rol.ADMIN))).andExpect(status().isServiceUnavailable());
		verifyNoInteractions(descargaDocumentoService);
	}

	@ParameterizedTest
	@CsvSource({ "NOT_FOUND,DOCUMENTO_NO_ENCONTRADO", "CONFLICT,INTEGRIDAD_NO_VERIFICABLE", "BAD_GATEWAY,INTEGRIDAD_FALLIDA",
			"SERVICE_UNAVAILABLE,DESCARGA_NO_DISPONIBLE", "TOO_MANY_REQUESTS,DESCARGA_OCUPADA" })
	void losErroresDeLaRutaSonJsonSinContenidoDocumental(HttpStatus estado, String codigo) throws Exception {
		doThrow(new ExcepcionNegocio(estado, codigo, "Mensaje fijo.")).when(descargaDocumentoService)
				.descargar(any(), any(), any(), any(), any());
		var r = mvc.perform(conCsrf(get(ruta())).cookie(sesion(Rol.ADMIN)).header("Accept", "application/pdf, */*"))
				.andExpect(status().is(estado.value())).andExpect(jsonPath("$.codigo").value(codigo))
				.andReturn().getResponse();
		assertThat(r.getContentType()).startsWith("application/json");
		assertThat(r.getHeader("Content-Disposition")).isNull();
		assertThat(r.getContentAsString()).doesNotContain("%PDF");
	}

	@Test
	void otrosMetodosNoSeHabilitan() throws Exception {
		mvc.perform(conCsrf(post(ruta())).cookie(sesion(Rol.ADMIN))).andExpect(status().isMethodNotAllowed());
		verifyNoInteractions(descargaDocumentoService);
	}

	/**
	 * Spring atiende HEAD en un {@code @GetMapping}: sin este rechazo, un HEAD válido leería Storage y registraría
	 * DOCUMENTO_DESCARGADO sin entregar ningún byte (la auditoría acredita el inicio de una entrega).
	 */
	@Test
	void headNoLeeNiAuditaUnaDescarga() throws Exception {
		mvc.perform(conCsrf(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head(ruta()))
				.cookie(sesion(Rol.ADMIN))).andExpect(status().isMethodNotAllowed());
		verifyNoInteractions(descargaDocumentoService);
	}

	@Test
	void openApiDocumentaElArchivoBinarioLosCodigosD3YLaCabeceraCsrf() throws Exception {
		JsonNode op = arbol().path("paths").path(PLANTILLA).path("get");
		assertThat(op.path("responses").path("200").path("content").propertyNames())
				.containsExactlyInAnyOrder("application/pdf", "image/jpeg", "image/png");
		assertThat(op.path("responses").path("200").path("content").path("application/pdf").path("schema").path("format")
				.asString()).isEqualTo("binary");
		assertThat(op.path("responses").path("200").path("headers").has("Content-Disposition")).isTrue();
		assertThat(op.path("responses").propertyNames()).contains("400", "401", "403", "404", "409", "429", "502", "503");
		assertThat(op.path("responses").path("403").path("description").asString()).contains("CSRF_INVALIDO", "ACCESO_DENEGADO");
		assertThat(op.path("responses").path("404").path("description").asString()).contains("DOCUMENTO_NO_ENCONTRADO");
		assertThat(op.path("responses").path("409").path("description").asString()).contains("INTEGRIDAD_NO_VERIFICABLE");
		assertThat(op.path("responses").path("429").path("description").asString()).contains("DESCARGA_OCUPADA");
		assertThat(op.path("responses").path("502").path("description").asString()).contains("INTEGRIDAD_FALLIDA");
		assertThat(op.path("responses").path("503").path("description").asString()).contains("DESCARGA_NO_DISPONIBLE");
		var esquemas = new java.util.TreeSet<String>();
		op.path("security").forEach(r -> r.propertyNames().forEach(esquemas::add));
		assertThat(esquemas).containsExactlyInAnyOrder("sesionCookie", "csrfHeader");
		assertThat(op.path("description").asString()).contains("no acredita", "recepción completa");
	}
}
