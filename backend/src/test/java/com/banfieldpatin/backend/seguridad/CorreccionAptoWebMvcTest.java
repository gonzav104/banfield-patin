package com.banfieldpatin.backend.seguridad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.documentos.apto.AptoFisicoTemporada;
import com.banfieldpatin.backend.documentos.apto.MetadatosApto;
import com.banfieldpatin.backend.usuarios.Rol;

import jakarta.servlet.http.Cookie;

/** La ruta de corrección de datos del apto: solo ADMIN con MFA y CSRF, cuerpo cerrado y sin eco de valores. */
class CorreccionAptoWebMvcTest extends BaseOpenApiWebMvc {
	private static final String RUTA = "/api/admin/deportistas/{deportistaId}/documentos/{documentoId}/datos-apto";
	private static final String CUERPO = """
			{"versionEsperada":"3","fechaEmision":"2026-03-01","medicoNombre":"Medico Sintetico","medicoMatricula":"MP 0001"}
			""";
	private final UUID deportista = UUID.randomUUID(), documento = UUID.randomUUID();
	private Cookie xsrf;

	@BeforeEach
	void preparar() throws Exception {
		verificador.reiniciar();
		org.mockito.Mockito.clearInvocations(correccionAptoService);
		xsrf = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		assertThat(xsrf).isNotNull();
		when(correccionAptoService.corregir(any(), eq(deportista), eq(documento), anyLong(), any())).thenReturn(
				new AptoFisicoTemporada.DocumentoApto(documento, "CARGADO", LocalDate.of(2026, 3, 1), "Medico Sintetico",
						"MP 0001", null, Instant.parse("2026-03-02T10:00:00Z"), 4L, true));
	}

	private MockHttpServletRequestBuilder pedido(String cuerpo) {
		return put(RUTA, deportista, documento).contentType(MediaType.APPLICATION_JSON).content(cuerpo);
	}

	private MockHttpServletRequestBuilder conCsrf(MockHttpServletRequestBuilder pedido) {
		return pedido.cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue());
	}

	@Test
	void soloAdminConMfaYCsrfLlegaAlServicio() throws Exception {
		mvc.perform(conCsrf(pedido(CUERPO))).andExpect(status().isUnauthorized());
		for (var cookie : List.of(sesion(Rol.FAMILIA), sesionMfaPendiente())) {
			mvc.perform(conCsrf(pedido(CUERPO)).cookie(cookie)).andExpect(status().isForbidden());
		}
		mvc.perform(pedido(CUERPO).cookie(sesion(Rol.ADMIN))).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
		verifyNoInteractions(correccionAptoService);
	}

	@Test
	void unaCorreccionValidaDevuelveElDocumentoConLaRevisionNuevaYLaSesionDefineElAlcance() throws Exception {
		mvc.perform(conCsrf(pedido(CUERPO)).cookie(sesion(Rol.ADMIN))).andExpect(status().isOk())
				.andExpect(jsonPath("$.estado").value("CARGADO")).andExpect(jsonPath("$.revision").value(4));
		var datos = ArgumentCaptor.forClass(MetadatosApto.Solicitado.class);
		verify(correccionAptoService).corregir(any(), eq(deportista), eq(documento), eq(3L), datos.capture());
		assertThat(datos.getValue()).isEqualTo(new MetadatosApto.Solicitado("2026-03-01", "Medico Sintetico", "MP 0001"));
	}

	@Test
	void elCuerpoEsCerradoYCompleto() throws Exception {
		for (String cuerpo : List.of("{}", """
				{"versionEsperada":"3","fechaEmision":"2026-03-01","medicoNombre":"Medico Sintetico"}
				""", """
				{"versionEsperada":3,"fechaEmision":"2026-03-01","medicoNombre":"M","medicoMatricula":"1"}
				""", """
				{"versionEsperada":"3","fechaEmision":20260301,"medicoNombre":"M","medicoMatricula":"1"}
				""", "no-es-json")) {
			mvc.perform(conCsrf(pedido(cuerpo)).cookie(sesion(Rol.ADMIN))).andExpect(status().is4xxClientError());
		}
		verifyNoInteractions(correccionAptoService);
	}

	/** Como en el resto de la API, un campo desconocido se ignora: ni el estado ni otro documento se pueden fijar desde el cuerpo. */
	@Test
	void loQueNoEsDelContratoSeIgnoraYElAlcanceSaleSoloDeLaRutaYLaSesion() throws Exception {
		String conExtras = """
				{"versionEsperada":"3","fechaEmision":"2026-03-01","medicoNombre":"Medico Sintetico","medicoMatricula":"MP 0001",
				 "estado":"APROBADO","documentoId":"%s","deportistaId":"%s","escuelaId":"%s"}
				""".formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
		mvc.perform(conCsrf(pedido(conExtras)).cookie(sesion(Rol.ADMIN))).andExpect(status().isOk());
		verify(correccionAptoService).corregir(any(), eq(deportista), eq(documento), eq(3L), any());
	}

	@Test
	void losErroresDelServicioSeTraducenSinValores() throws Exception {
		when(correccionAptoService.corregir(any(), eq(deportista), eq(documento), anyLong(), any()))
				.thenThrow(new ExcepcionNegocio(HttpStatus.CONFLICT, "APTO_NO_CORREGIBLE", "Solo se corrige un apto en revisión o aprobado; uno observado se reemplaza."));
		var respuesta = mvc.perform(conCsrf(pedido(CUERPO)).cookie(sesion(Rol.ADMIN))).andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("APTO_NO_CORREGIBLE")).andReturn().getResponse().getContentAsString();
		assertThat(respuesta).doesNotContain("Medico Sintetico", "MP 0001", "2026-03-01");
		when(correccionAptoService.corregir(any(), eq(deportista), eq(documento), anyLong(), any()))
				.thenThrow(new IllegalStateException("fallo con valores: Medico Sintetico MP 0001"));
		var interno = mvc.perform(conCsrf(pedido(CUERPO)).cookie(sesion(Rol.ADMIN))).andExpect(status().isInternalServerError())
				.andReturn().getResponse().getContentAsString();
		assertThat(interno).doesNotContain("Medico Sintetico", "MP 0001");
	}
}
