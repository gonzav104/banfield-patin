package com.banfieldpatin.backend.usuarios.cuentas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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
import com.banfieldpatin.backend.seguridad.SesionVigenteDePrueba;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;
import com.banfieldpatin.backend.usuarios.cuentas.dto.CuentaFamiliar;
import com.banfieldpatin.backend.usuarios.cuentas.dto.CuentasFamilia;

import jakarta.servlet.http.Cookie;

/** Cuentas de una familia: solo ADMIN con sesión completa; actor y escuela salen del token; escrituras con CSRF. */
@WebMvcTest(controllers = CuentasFamiliaAdminController.class)
@Import({ SeguridadConfig.class, JwtConfig.class, CookieSesion.class, CookieBearerTokenResolver.class,
		PuntoEntradaJson.class, ManejadorAccesoDenegadoJson.class, ManejadorGlobalErrores.class,
		ServicioTokens.class, RelojConfig.class, SesionVigenteDePrueba.class })
@ActiveProfiles("test")
class CuentasFamiliaAdminControllerWebMvcTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	ServicioTokens tokens;
	@MockitoBean
	CuentasFamiliaAdminService servicio;

	private final UUID escuelaId = UUID.randomUUID();
	private final UUID adminId = UUID.randomUUID();
	private final UUID familiaId = UUID.randomUUID();
	private final UUID cuentaId = UUID.randomUUID();

	private String ruta() {
		return "/api/admin/familias/" + familiaId + "/cuentas";
	}

	private Cookie admin() {
		return new Cookie("BP_SESION", tokens.emitir(adminId, Rol.ADMIN, escuelaId, null));
	}

	private MockHttpServletRequestBuilder conCsrf(MockHttpServletRequestBuilder req) throws Exception {
		MockHttpServletResponse r = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse();
		Cookie x = r.getCookie("XSRF-TOKEN");
		return req.cookie(x).header("X-XSRF-TOKEN", x.getValue());
	}

	@Test
	void adminListaConLaEscuelaDelToken() throws Exception {
		when(servicio.listar(any(), eq(familiaId))).thenReturn(new CuentasFamilia(false,
				List.of(new CuentaFamiliar(cuentaId, "Ana", "Prueba", "ana@example.invalid", true, null, null))));

		mvc.perform(get(ruta()).cookie(admin())).andExpect(status().isOk())
				.andExpect(jsonPath("$.gestionHabilitada").value(false))
				.andExpect(jsonPath("$.cuentas[0].activa").value(true));

		ArgumentCaptor<UsuarioAutenticado> actor = ArgumentCaptor.forClass(UsuarioAutenticado.class);
		verify(servicio).listar(actor.capture(), eq(familiaId));
		assertThat(actor.getValue().id()).isEqualTo(adminId);
		assertThat(actor.getValue().escuelaId()).isEqualTo(escuelaId);
	}

	@Test
	void desactivarYReactivarDelegadosConCsrf() throws Exception {
		when(servicio.desactivar(any(), eq(familiaId), eq(cuentaId), any(DatosSolicitud.class)))
				.thenReturn(new CuentaFamiliar(cuentaId, "Ana", "Prueba", "ana@example.invalid", false, null, null));
		when(servicio.reactivar(any(), eq(familiaId), eq(cuentaId), any(DatosSolicitud.class)))
				.thenReturn(new CuentaFamiliar(cuentaId, "Ana", "Prueba", "ana@example.invalid", true, null, null));

		mvc.perform(conCsrf(post(ruta() + "/" + cuentaId + "/desactivar")).cookie(admin()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.activa").value(false));
		mvc.perform(conCsrf(post(ruta() + "/" + cuentaId + "/reactivar")).cookie(admin()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.activa").value(true));
	}

	@Test
	void sinCsrfFamiliaMfaPendienteOAnonimoNoLleganAlServicio() throws Exception {
		mvc.perform(post(ruta() + "/" + cuentaId + "/desactivar").cookie(admin())).andExpect(status().isForbidden());
		Cookie familia = new Cookie("BP_SESION", tokens.emitir(UUID.randomUUID(), Rol.FAMILIA, escuelaId, familiaId));
		mvc.perform(get(ruta()).cookie(familia)).andExpect(status().isForbidden());
		mvc.perform(conCsrf(post(ruta() + "/" + cuentaId + "/desactivar")).cookie(familia))
				.andExpect(status().isForbidden());
		Cookie pendiente = new Cookie("BP_SESION", tokens.emitirMfaPendiente(adminId, escuelaId));
		mvc.perform(get(ruta()).cookie(pendiente)).andExpect(status().isForbidden());
		mvc.perform(get(ruta())).andExpect(status().isUnauthorized());
		verifyNoInteractions(servicio);
	}

	@Test
	void idsMalFormadosDan400SinLlegarAlServicio() throws Exception {
		mvc.perform(get("/api/admin/familias/no-es-uuid/cuentas").cookie(admin())).andExpect(status().isBadRequest());
		mvc.perform(conCsrf(post(ruta() + "/no-es-uuid/desactivar")).cookie(admin())).andExpect(status().isBadRequest());
		verifyNoInteractions(servicio);
	}

	@Test
	void conLaGestionApagadaResponde503ConCodigoEstable() throws Exception {
		when(servicio.desactivar(any(), any(), any(), any())).thenThrow(new ExcepcionNegocio(
				HttpStatus.SERVICE_UNAVAILABLE, "GESTION_CUENTAS_DESHABILITADA", "La gestión de cuentas no está habilitada."));

		mvc.perform(conCsrf(post(ruta() + "/" + cuentaId + "/desactivar")).cookie(admin()))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.codigo").value("GESTION_CUENTAS_DESHABILITADA"));
	}
}
