package com.banfieldpatin.backend.seguridad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.banfieldpatin.backend.deportistas.DeportistaAdminService;
import com.banfieldpatin.backend.documentos.CatalogoRequisitosDocumentales;
import com.banfieldpatin.backend.usuarios.Rol;

import tools.jackson.databind.json.JsonMapper;

/** Las dos rutas del checklist parcial: quién entra, el alcance de la sesión y el contrato exacto de la respuesta. */
class ChecklistDocumentalWebMvcTest extends BaseOpenApiWebMvc {
	private final UUID id = UUID.randomUUID();

	private String ruta(String area) {
		return "/api/" + area + "/deportistas/" + id + "/checklist-documental";
	}

	@BeforeEach
	void preparar() {
		verificador.reiniciar();
		clearInvocations(checklistDocumentalService);
		when(checklistDocumentalService.deportista(any(), eq(id))).thenReturn(CatalogoRequisitosDocumentales.calcular(id, List.of()));
	}

	@Test
	void cadaRolLeeSuRutaConElContratoExactoYSinCache() throws Exception {
		for (var par : List.of(List.of("admin", Rol.ADMIN), List.of("familia", Rol.FAMILIA))) {
			var respuesta = mvc.perform(get(ruta((String) par.get(0))).cookie(sesion((Rol) par.get(1)))).andExpect(status().isOk())
					.andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
					.andExpect(jsonPath("$.parcial").value(true)).andExpect(jsonPath("$.alcance").value("DNI_DEPORTISTA"))
					.andExpect(jsonPath("$.requisitos[0].tipo").value("DNI_DEPORTISTA_FRENTE"))
					.andExpect(jsonPath("$.requisitos[0].estado").value("FALTA"))
					.andExpect(jsonPath("$.requisitos[1].tipo").value("DNI_DEPORTISTA_DORSO"))
					.andReturn().getResponse().getContentAsString();
			var raiz = new JsonMapper().readTree(respuesta);
			assertThat(raiz.propertyNames()).containsExactlyInAnyOrder("deportistaId", "parcial", "alcance", "requisitos");
			assertThat(raiz.path("requisitos").get(0).propertyNames()).containsExactlyInAnyOrder("tipo", "estado", "motivoObservacion");
			// Ningún estado federativo ni porcentaje global: solo lo que se evalúa.
			assertThat(respuesta).doesNotContain("LISTO_PARA_PRESENTAR", "EMPADRONADO", "porcentaje");
		}
	}

	@Test
	void anonimatoYRolIncorrectoNoLlegaAlServicio() throws Exception {
		for (String area : List.of("admin", "familia")) {
			mvc.perform(get(ruta(area))).andExpect(status().isUnauthorized());
			mvc.perform(get(ruta(area)).cookie(sesion(area.equals("admin") ? Rol.FAMILIA : Rol.ADMIN))).andExpect(status().isForbidden());
			mvc.perform(get(ruta(area).replace(id.toString(), "no-uuid")).cookie(sesion(area.equals("admin") ? Rol.ADMIN : Rol.FAMILIA)))
					.andExpect(status().isBadRequest());
		}
		mvc.perform(get(ruta("admin")).cookie(sesionMfaPendiente())).andExpect(status().isForbidden());
		verifyNoInteractions(checklistDocumentalService);
	}

	@Test
	void laIdentidadYLaEscuelaSalenDeLaSesionYNoDeLaPeticion() throws Exception {
		var usuario = UUID.randomUUID();
		var escuela = UUID.randomUUID();
		var familia = UUID.randomUUID();
		var cookie = new jakarta.servlet.http.Cookie(COOKIE, tokens.emitir(usuario, Rol.FAMILIA, escuela, familia));
		mvc.perform(get(ruta("familia")).cookie(cookie).param("escuelaId", UUID.randomUUID().toString())
				.param("familiaId", UUID.randomUUID().toString())).andExpect(status().isOk());
		var capturado = ArgumentCaptor.forClass(UsuarioAutenticado.class);
		verify(checklistDocumentalService).deportista(capturado.capture(), eq(id));
		assertThat(capturado.getValue()).isEqualTo(new UsuarioAutenticado(usuario, escuela, familia, Rol.FAMILIA));
	}

	@Test
	void elDeportistaFueraDeAlcanceEsUn404UniformeSinCampos() throws Exception {
		when(checklistDocumentalService.deportista(any(), eq(id))).thenThrow(DeportistaAdminService.deportistaNoEncontrado());
		for (var par : List.of(List.of("admin", Rol.ADMIN), List.of("familia", Rol.FAMILIA))) {
			mvc.perform(get(ruta((String) par.get(0))).cookie(sesion((Rol) par.get(1)))).andExpect(status().isNotFound())
					.andExpect(jsonPath("$.codigo").value("DEPORTISTA_NO_ENCONTRADO")).andExpect(jsonPath("$.requisitos").doesNotExist());
		}
	}
}
