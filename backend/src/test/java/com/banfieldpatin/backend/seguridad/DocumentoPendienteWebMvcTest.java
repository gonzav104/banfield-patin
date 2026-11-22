package com.banfieldpatin.backend.seguridad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import com.banfieldpatin.backend.compartido.web.Pagina;
import com.banfieldpatin.backend.documentos.DocumentoPendienteAdmin;
import com.banfieldpatin.backend.documentos.TipoDocumento;
import com.banfieldpatin.backend.documentos.TitularDocumento;
import com.banfieldpatin.backend.usuarios.Rol;

import tools.jackson.databind.json.JsonMapper;

/** La bandeja de pendientes: solo ADMIN con sesion completa, alcance de la sesion y contrato exacto de la fila. */
class DocumentoPendienteWebMvcTest extends BaseOpenApiWebMvc {
	private static final String RUTA = "/api/admin/documentos/pendientes";

	@BeforeEach
	void preparar() {
		verificador.reiniciar();
		clearInvocations(documentoPendienteService);
		var fila = new DocumentoPendienteAdmin(UUID.randomUUID(), TipoDocumento.DNI_TUTOR_FRENTE,
				Instant.parse("2026-03-10T14:00:00Z"), TitularDocumento.TUTOR, UUID.randomUUID(), "Rosa Sintética",
				UUID.randomUUID(), true);
		when(documentoPendienteService.listar(any(), any(), any())).thenReturn(new Pagina<>(List.of(fila), 0, 20, 1));
	}

	@Test
	void adminRecibeSoloLosCamposDocumentadosYSinCache() throws Exception {
		var cuerpo = mvc.perform(get(RUTA).cookie(sesion(Rol.ADMIN))).andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElementos").value(1)).andExpect(jsonPath("$.contenido[0].reemplazo").value(true))
				.andExpect(jsonPath("$.contenido[0].titularTipo").value("TUTOR"))
				.andReturn().getResponse();
		assertThat(cuerpo.getHeader("Cache-Control")).contains("no-store");
		var campos = new JsonMapper().readTree(cuerpo.getContentAsString()).path("contenido").get(0).propertyNames();
		assertThat(campos).containsExactlyInAnyOrder("id", "tipo", "creadoEn", "titularTipo", "titularId",
				"titularNombre", "familiaId", "reemplazo");
	}

	@Test
	void anonimatoFamiliaYMfaPendienteNoLlegaAlServicio() throws Exception {
		mvc.perform(get(RUTA)).andExpect(status().isUnauthorized());
		mvc.perform(get(RUTA).cookie(sesion(Rol.FAMILIA))).andExpect(status().isForbidden());
		mvc.perform(get(RUTA).cookie(sesionMfaPendiente())).andExpect(status().isForbidden());
		verifyNoInteractions(documentoPendienteService);
	}

	@Test
	void laEscuelaSaleDeLaSesionYLaPaginacionSeAcota() throws Exception {
		var usuario = UUID.randomUUID();
		var escuela = UUID.randomUUID();
		var cookie = new jakarta.servlet.http.Cookie(COOKIE, tokens.emitir(usuario, Rol.ADMIN, escuela, null));
		mvc.perform(get(RUTA).cookie(cookie).param("escuelaId", UUID.randomUUID().toString())
				.param("familiaId", UUID.randomUUID().toString()).param("page", "-1").param("size", "1000"))
				.andExpect(status().isOk());
		var admin = ArgumentCaptor.forClass(com.banfieldpatin.backend.seguridad.UsuarioAutenticado.class);
		var pagina = ArgumentCaptor.forClass(Pageable.class);
		verify(documentoPendienteService).listar(admin.capture(), isNull(), pagina.capture());
		assertThat(admin.getValue()).isEqualTo(new UsuarioAutenticado(usuario, escuela, null, Rol.ADMIN));
		assertThat(pagina.getValue().getPageNumber()).isZero();
		assertThat(pagina.getValue().getPageSize()).isEqualTo(100);
	}

	@Test
	void elFiltroPorTitularAceptaSoloLosValoresDelContrato() throws Exception {
		mvc.perform(get(RUTA).cookie(sesion(Rol.ADMIN)).param("titular", "TUTOR")).andExpect(status().isOk());
		verify(documentoPendienteService).listar(any(), org.mockito.ArgumentMatchers.eq(TitularDocumento.TUTOR), any());
		clearInvocations(documentoPendienteService);
		mvc.perform(get(RUTA).cookie(sesion(Rol.ADMIN)).param("titular", "OTRO")).andExpect(status().isBadRequest());
		verifyNoInteractions(documentoPendienteService);
	}
}
