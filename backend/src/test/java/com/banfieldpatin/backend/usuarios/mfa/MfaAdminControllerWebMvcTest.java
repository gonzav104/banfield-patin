package com.banfieldpatin.backend.usuarios.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.banfieldpatin.backend.compartido.RelojConfig;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.error.ManejadorGlobalErrores;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.seguridad.CookieBearerTokenResolver;
import com.banfieldpatin.backend.seguridad.CookieSesion;
import com.banfieldpatin.backend.seguridad.JwtConfig;
import com.banfieldpatin.backend.seguridad.ManejadorAccesoDenegadoJson;
import com.banfieldpatin.backend.seguridad.PuntoEntradaJson;
import com.banfieldpatin.backend.seguridad.SeguridadConfig;
import com.banfieldpatin.backend.seguridad.ServicioTokens;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;

import jakarta.servlet.http.Cookie;

/** POST /api/admin/usuarios/{id}/mfa/reiniciar: solo ADMIN con sesion completa; el actor y la escuela salen del JWT. */
@WebMvcTest(controllers = MfaAdminController.class)
@Import({ SeguridadConfig.class, JwtConfig.class, CookieSesion.class, CookieBearerTokenResolver.class,
		PuntoEntradaJson.class, ManejadorAccesoDenegadoJson.class, ManejadorGlobalErrores.class,
		ServicioTokens.class, RelojConfig.class })
@ActiveProfiles("test")
class MfaAdminControllerWebMvcTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	ServicioTokens tokens;
	@MockitoBean
	MfaService servicio;

	private final UUID escuelaId = UUID.randomUUID();
	private final UUID adminId = UUID.randomUUID();
	private final UUID objetivoId = UUID.randomUUID();

	private String ruta(UUID id) {
		return "/api/admin/usuarios/" + id + "/mfa/reiniciar";
	}

	private Cookie completa() {
		return new Cookie("BP_SESION", tokens.emitir(adminId, Rol.ADMIN, escuelaId, null));
	}

	private MockHttpServletRequestBuilder conCsrf(MockHttpServletRequestBuilder req) throws Exception {
		MockHttpServletResponse r = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse();
		Cookie x = r.getCookie("XSRF-TOKEN");
		return req.cookie(x).header("X-XSRF-TOKEN", x.getValue());
	}

	@Test
	void adminConSesionCompletaReiniciaYRecibe204() throws Exception {
		mvc.perform(conCsrf(post(ruta(objetivoId))).cookie(completa())).andExpect(status().isNoContent());

		ArgumentCaptor<UsuarioAutenticado> actor = ArgumentCaptor.forClass(UsuarioAutenticado.class);
		verify(servicio).reiniciar(actor.capture(), eq(objetivoId), any(DatosSolicitud.class));
		assertThat(actor.getValue().id()).isEqualTo(adminId);
		assertThat(actor.getValue().escuelaId()).isEqualTo(escuelaId);
	}

	@Test
	void adminConMfaPendienteNoPuedeReiniciar() throws Exception {
		Cookie pendiente = new Cookie("BP_SESION", tokens.emitirMfaPendiente(adminId, escuelaId));

		mvc.perform(conCsrf(post(ruta(objetivoId))).cookie(pendiente))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
		verifyNoInteractions(servicio);
	}

	@Test
	void familiaDa403YAnonimoDa401() throws Exception {
		Cookie familia = new Cookie("BP_SESION", tokens.emitir(UUID.randomUUID(), Rol.FAMILIA, escuelaId, UUID.randomUUID()));

		mvc.perform(conCsrf(post(ruta(objetivoId))).cookie(familia)).andExpect(status().isForbidden());
		mvc.perform(conCsrf(post(ruta(objetivoId)))).andExpect(status().isUnauthorized());
		verifyNoInteractions(servicio);
	}

	@Test
	void sinCsrfDa403() throws Exception {
		mvc.perform(post(ruta(objetivoId)).cookie(completa()))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
		verifyNoInteractions(servicio);
	}

	@Test
	void unaRespuestaDeNegocioDelServicioSePropagaConSuCodigo() throws Exception {
		doThrow(new ExcepcionNegocio(HttpStatus.FORBIDDEN, "MFA_AUTOREINICIO_NO_PERMITIDO", "no"))
				.when(servicio).reiniciar(any(), eq(adminId), any());
		doThrow(new ExcepcionNegocio(HttpStatus.NOT_FOUND, "USUARIO_NO_ENCONTRADO", "no"))
				.when(servicio).reiniciar(any(), eq(objetivoId), any());

		mvc.perform(conCsrf(post(ruta(adminId))).cookie(completa()))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("MFA_AUTOREINICIO_NO_PERMITIDO"));
		mvc.perform(conCsrf(post(ruta(objetivoId))).cookie(completa()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.codigo").value("USUARIO_NO_ENCONTRADO"));
	}

	@Test
	void unIdQueNoEsUuidDa400SinLlamarAlServicio() throws Exception {
		mvc.perform(conCsrf(post("/api/admin/usuarios/no-es-uuid/mfa/reiniciar")).cookie(completa()))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(servicio);
	}
}
