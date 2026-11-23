package com.banfieldpatin.backend.seguridad;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

import com.banfieldpatin.backend.compartido.web.Pagina;
import com.banfieldpatin.backend.deportistas.DeportistaAdminService;
import com.banfieldpatin.backend.usuarios.Rol;

class VinculosTutorAdminWebMvcTest extends BaseOpenApiWebMvc {

	private final UUID id = UUID.randomUUID();

	private String ruta() {
		return "/api/admin/deportistas/" + id + "/vinculos-tutores";
	}

	@BeforeEach
	void preparar() {
		verificador.reiniciar();
	}

	@Test
	void administraLaConsultaPaginadaSinExponerlaAlOtroRol() throws Exception {
		mvc.perform(get(ruta())).andExpect(status().isUnauthorized());
		mvc.perform(get(ruta()).cookie(sesion(Rol.FAMILIA))).andExpect(status().isForbidden());
		mvc.perform(get(ruta()).cookie(sesionMfaPendiente())).andExpect(status().isForbidden());
		verifyNoInteractions(vinculosTutorConsultaAdmin);
		when(vinculosTutorConsultaAdmin.listar(any(), eq(id), eq(false), eq(0), eq(20)))
				.thenReturn(new Pagina<>(List.of(), 0, 20, 0));
		mvc.perform(get(ruta()).cookie(sesion(Rol.ADMIN)))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
				.andExpect(jsonPath("$.contenido").isArray())
				.andExpect(jsonPath("$.totalElementos").value(0));
	}

	@Test
	void el404NoDistingueUnDeportistaInexistenteDeUnoDeOtraEscuela() throws Exception {
		when(vinculosTutorConsultaAdmin.listar(any(), eq(id), eq(true), eq(0), eq(20)))
				.thenThrow(DeportistaAdminService.deportistaNoEncontrado());
		mvc.perform(get(ruta()).param("historial", "true").cookie(sesion(Rol.ADMIN)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.codigo").value("DEPORTISTA_NO_ENCONTRADO"))
				.andExpect(jsonPath("$.contenido").doesNotExist());
	}
}
