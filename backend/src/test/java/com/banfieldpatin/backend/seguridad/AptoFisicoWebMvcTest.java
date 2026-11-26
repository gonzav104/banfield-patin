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

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.banfieldpatin.backend.deportistas.DeportistaAdminService;
import com.banfieldpatin.backend.documentos.apto.AptoFisicoTemporada;
import com.banfieldpatin.backend.documentos.apto.EstadoApto;
import com.banfieldpatin.backend.temporadas.TemporadaAdminService;
import com.banfieldpatin.backend.usuarios.Rol;

import tools.jackson.databind.json.JsonMapper;

/** Las dos rutas del apto por temporada: quién entra, el alcance de la sesión y el contrato exacto de la respuesta. */
class AptoFisicoWebMvcTest extends BaseOpenApiWebMvc {
	private final UUID id = UUID.randomUUID();
	private final UUID temporada = UUID.randomUUID();

	private String ruta(String area) {
		return "/api/" + area + "/deportistas/" + id + "/temporadas/" + temporada + "/apto-fisico";
	}

	private AptoFisicoTemporada respuesta(boolean conRevision) {
		return new AptoFisicoTemporada(id, temporada, 2026, EstadoApto.EN_REVISION, false,
				new AptoFisicoTemporada.Vigencia(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), true, "Reglamento Nacional 2026"),
				List.of(), new AptoFisicoTemporada.DocumentoApto(UUID.randomUUID(), "CARGADO", LocalDate.of(2026, 3, 1),
						"Medico Sintetico", "MP 0001", null, Instant.parse("2026-03-02T10:00:00Z"), conRevision ? 0L : null, true));
	}

	@BeforeEach
	void preparar() {
		verificador.reiniciar();
		clearInvocations(aptoFisicoService);
		when(aptoFisicoService.consultar(any(), eq(id), eq(temporada))).thenReturn(respuesta(true));
	}

	@Test
	void cadaRolLeeSuRutaConElContratoExactoYSinCache() throws Exception {
		for (var par : List.of(List.of("admin", Rol.ADMIN), List.of("familia", Rol.FAMILIA))) {
			var cuerpo = mvc.perform(get(ruta((String) par.get(0))).cookie(sesion((Rol) par.get(1)))).andExpect(status().isOk())
					.andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
					.andExpect(jsonPath("$.estado").value("EN_REVISION")).andExpect(jsonPath("$.bloqueante").value(false))
					.andExpect(jsonPath("$.vigencia.hasta").value("2026-12-31"))
					.andExpect(jsonPath("$.documento.medicoMatricula").value("MP 0001"))
					.andReturn().getResponse().getContentAsString();
			var raiz = new JsonMapper().readTree(cuerpo);
			assertThat(raiz.propertyNames()).containsExactlyInAnyOrder("deportistaId", "temporadaId", "anio", "estado", "bloqueante",
					"vigencia", "avisos", "documento");
			// Cerrado: ni archivo, ni ubicación, ni hash, ni nombre original, ni identidad de quien lo cargó.
			assertThat(raiz.path("documento").propertyNames()).containsExactlyInAnyOrder("id", "estado", "fechaEmision",
					"medicoNombre", "medicoMatricula", "motivoObservacion", "cargadoEn", "revision", "datosVisibles");
			assertThat(cuerpo).doesNotContain("storage", "sha256", "nombreOriginal", "cargadoPor", "LISTO_PARA_PRESENTAR", "EMPADRONADO");
		}
	}

	@Test
	void anonimatoYRolIncorrectoNoLlegaAlServicio() throws Exception {
		for (String area : List.of("admin", "familia")) {
			mvc.perform(get(ruta(area))).andExpect(status().isUnauthorized());
			mvc.perform(get(ruta(area)).cookie(sesion(area.equals("admin") ? Rol.FAMILIA : Rol.ADMIN))).andExpect(status().isForbidden());
			mvc.perform(get(ruta(area).replace(id.toString(), "no-uuid")).cookie(sesion(area.equals("admin") ? Rol.ADMIN : Rol.FAMILIA)))
					.andExpect(status().isBadRequest());
			mvc.perform(get(ruta(area).replace(temporada.toString(), "no-uuid")).cookie(sesion(area.equals("admin") ? Rol.ADMIN : Rol.FAMILIA)))
					.andExpect(status().isBadRequest());
		}
		mvc.perform(get(ruta("admin")).cookie(sesionMfaPendiente())).andExpect(status().isForbidden());
		verifyNoInteractions(aptoFisicoService);
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
		verify(aptoFisicoService).consultar(capturado.capture(), eq(id), eq(temporada));
		assertThat(capturado.getValue()).isEqualTo(new UsuarioAutenticado(usuario, escuela, familia, Rol.FAMILIA));
	}

	@Test
	void deportistaOTemporadaFueraDeAlcanceSonUn404UniformeSinCampos() throws Exception {
		when(aptoFisicoService.consultar(any(), eq(id), eq(temporada))).thenThrow(DeportistaAdminService.deportistaNoEncontrado());
		for (var par : List.of(List.of("admin", Rol.ADMIN), List.of("familia", Rol.FAMILIA))) {
			mvc.perform(get(ruta((String) par.get(0))).cookie(sesion((Rol) par.get(1)))).andExpect(status().isNotFound())
					.andExpect(jsonPath("$.codigo").value("DEPORTISTA_NO_ENCONTRADO")).andExpect(jsonPath("$.documento").doesNotExist());
		}
		when(aptoFisicoService.consultar(any(), eq(id), eq(temporada))).thenThrow(TemporadaAdminService.temporadaNoEncontrada());
		mvc.perform(get(ruta("familia")).cookie(sesion(Rol.FAMILIA))).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.codigo").value("TEMPORADA_NO_ENCONTRADA"));
	}
}
