package com.banfieldpatin.backend.seguridad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.banfieldpatin.backend.deportistas.dto.DeportistaDetalle;
import com.banfieldpatin.backend.familias.dto.FamiliaDetalle;
import com.banfieldpatin.backend.familias.dto.FamiliaResumen;
import com.banfieldpatin.backend.familias.invitaciones.EstadoInvitacion;
import com.banfieldpatin.backend.familias.invitaciones.dto.InvitacionCreadaRespuesta;
import com.banfieldpatin.backend.familias.tutores.dto.TutorRespuesta;
import com.banfieldpatin.backend.usuarios.Rol;
import com.banfieldpatin.backend.usuarios.dto.UsuarioActualRespuesta;

import jakarta.servlet.http.Cookie;

/**
 * Los codigos de exito que declara la especificacion ({@link OpenApiRutasTest#CREAN} y {@link OpenApiRutasTest#SIN_CONTENIDO})
 * son los que los controladores devuelven de verdad: cada una de esas rutas se invoca por HTTP (cadena de seguridad real,
 * servicios simulados) y se comprueba el estado y el header Location. Es lo que impide que un {@code ResponseEntity.created}
 * quede documentado como 200, o al reves.
 */
class OpenApiEstadosRealesTest extends BaseOpenApiWebMvc {

	private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");

	private MockHttpServletRequestBuilder conCsrf(MockHttpServletRequestBuilder pedido, Cookie... cookies) throws Exception {
		Cookie xsrf = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		pedido.cookie(xsrf);
		for (Cookie cookie : cookies) {
			pedido.cookie(cookie);
		}
		return pedido.header("X-XSRF-TOKEN", xsrf.getValue());
	}

	private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder pedido, String cuerpo) {
		return pedido.contentType(MediaType.APPLICATION_JSON).content(cuerpo);
	}

	@Test
	void postFamiliasResponde201ConLocation() throws Exception {
		when(familiaAdminService.crear(any(), any(), any())).thenReturn(new FamiliaDetalle(ID, "Familia Prueba", true, List.of()));

		mvc.perform(conCsrf(json(post("/api/admin/familias"), "{\"nombreReferencia\":\"Familia Prueba\"}"), sesion(Rol.ADMIN)))
				.andExpect(status().isCreated()).andExpect(header().string("Location", "/api/admin/familias/" + ID));
	}

	@Test
	void postTutoresResponde201ConLocation() throws Exception {
		when(tutorAdminService.crear(any(), any(), any(), any())).thenReturn(
				new TutorRespuesta(ID, ID, "Ana", "Prueba", null, null, null, null, true));

		mvc.perform(conCsrf(json(post("/api/admin/familias/" + ID + "/tutores"), "{\"nombre\":\"Ana\",\"apellido\":\"Prueba\"}"),
				sesion(Rol.ADMIN))).andExpect(status().isCreated())
				.andExpect(header().string("Location", "/api/admin/tutores/" + ID));
	}

	@Test
	void postDeportistasResponde201ConLocation() throws Exception {
		when(deportistaAdminService.crear(any(), any(), any())).thenReturn(new DeportistaDetalle(ID, "Ana", "Prueba", "10000001",
				null, null, null, null, null, null, null, null, null, null, true));

		mvc.perform(conCsrf(json(post("/api/admin/deportistas"),
				"{\"dni\":\"10000001\",\"nombre\":\"Ana\",\"apellido\":\"Prueba\"}"), sesion(Rol.ADMIN)))
				.andExpect(status().isCreated()).andExpect(header().string("Location", "/api/admin/deportistas/" + ID));
	}

	@Test
	void postInvitacionesResponde201ConLocationYSinCache() throws Exception {
		when(invitacionAdminService.crear(any(), any(), any())).thenReturn(new InvitacionCreadaRespuesta(ID,
				EstadoInvitacion.PENDIENTE, "t", "e", Instant.parse("2030-01-01T00:00:00Z"), new FamiliaResumen(ID, "F"), null));

		mvc.perform(conCsrf(json(post("/api/admin/invitaciones"), "{\"nuevaFamilia\":{\"nombreReferencia\":\"Familia Prueba\"}}"),
				sesion(Rol.ADMIN))).andExpect(status().isCreated())
				.andExpect(header().string("Location", "/api/admin/invitaciones/" + ID))
				.andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
	}

	@Test
	void postRegistroResponde201SinLocation() throws Exception {
		when(registroPorInvitacionService.registrar(any(), any())).thenReturn(
				new UsuarioActualRespuesta(ID, "Ana", "Prueba", "ana@example.test", Rol.FAMILIA, ID, ID));

		mvc.perform(conCsrf(json(post("/api/auth/registro/invitacion"),
				"{\"token\":\"abc\",\"nombre\":\"Ana\",\"apellido\":\"Prueba\",\"email\":\"ana@example.test\","
						+ "\"password\":\"Una-Clave-Larga-1\"}")))
				.andExpect(status().isCreated()).andExpect(header().doesNotExist("Location"));
	}

	@Test
	void postLogoutResponde204() throws Exception {
		mvc.perform(conCsrf(post("/api/auth/logout"), sesion(Rol.ADMIN))).andExpect(status().isNoContent());
	}

	@Test
	void postReiniciarMfaResponde204() throws Exception {
		mvc.perform(conCsrf(post("/api/admin/usuarios/" + ID + "/mfa/reiniciar"), sesion(Rol.ADMIN)))
				.andExpect(status().isNoContent());
	}

	@Test
	void lasRutasProbadasSonExactamenteLasQueLaEspecificacionDeclaraComo201Y204() {
		// Si se agrega una ruta 201/204 a la especificacion, este test obliga a probarla aqui tambien.
		assertThat(OpenApiRutasTest.CREAN).containsExactlyInAnyOrder("POST /api/admin/familias",
				"POST /api/admin/familias/{familiaId}/tutores", "POST /api/admin/deportistas", "POST /api/admin/invitaciones",
				"POST /api/auth/registro/invitacion");
		assertThat(OpenApiRutasTest.SIN_CONTENIDO).containsExactlyInAnyOrder("POST /api/auth/logout",
				"POST /api/admin/usuarios/{id}/mfa/reiniciar");
		assertThat(OpenApiConfig.RUTAS.entrySet().stream().filter(e -> e.getValue().exito() == 201).map(java.util.Map.Entry::getKey))
				.containsExactlyInAnyOrderElementsOf(OpenApiRutasTest.CREAN);
		assertThat(OpenApiConfig.RUTAS.entrySet().stream().filter(e -> e.getValue().exito() == 204).map(java.util.Map.Entry::getKey))
				.containsExactlyInAnyOrderElementsOf(OpenApiRutasTest.SIN_CONTENIDO);
	}
}
