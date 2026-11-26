package com.banfieldpatin.backend.seguridad;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.deportistas.DeportistaAdminService;
import com.banfieldpatin.backend.familias.responsables.CausaCierreVigencia;
import com.banfieldpatin.backend.familias.responsables.ResponsablesAdminService;
import com.banfieldpatin.backend.familias.responsables.TipoVinculoTutor;
import com.banfieldpatin.backend.familias.responsables.VinculoTutorRespuesta;
import com.banfieldpatin.backend.usuarios.Rol;

import jakarta.servlet.http.Cookie;

/** Contrato HTTP de las escrituras de responsables: ADMIN, CSRF, cabecera, cuerpo estricto y códigos de error. */
class ResponsablesAdminWebMvcTest extends BaseOpenApiWebMvc {

	private static final String CUERPO_ALTA = "{\"tutorId\":\"%s\",\"versionFamilia\":\"0\",\"versionDeportista\":\"0\","
			+ "\"versionTutor\":\"0\",\"versionVinculos\":\"0\"}";

	private final UUID deportista = UUID.randomUUID();
	private final UUID tutor = UUID.randomUUID();
	private final UUID vigencia = UUID.randomUUID();
	private final UUID clave = UUID.randomUUID();

	@BeforeEach
	void preparar() {
		verificador.reiniciar();
	}

	private String ruta() {
		return "/api/admin/deportistas/" + deportista + "/vinculos-tutores";
	}

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

	private VinculoTutorRespuesta respuesta(boolean cerrada) {
		return new VinculoTutorRespuesta(vigencia, UUID.randomUUID(), tutor, "Ana", "Perez", TipoVinculoTutor.MADRE,
				Instant.parse("2026-01-01T00:00:00Z"), cerrada ? Instant.parse("2026-02-01T00:00:00Z") : null,
				cerrada ? CausaCierreVigencia.REVOCACION_ADMIN : null, 0L);
	}

	@Test
	void soloAdminConSesionCompletaYCsrfPuedeEscribir() throws Exception {
		var cuerpo = CUERPO_ALTA.formatted(tutor);
		mvc.perform(conCsrf(json(post(ruta()).header("Idempotency-Key", clave.toString()), cuerpo)))
				.andExpect(status().isUnauthorized());
		mvc.perform(conCsrf(json(post(ruta()).header("Idempotency-Key", clave.toString()), cuerpo), sesion(Rol.FAMILIA)))
				.andExpect(status().isForbidden());
		mvc.perform(conCsrf(json(post(ruta()).header("Idempotency-Key", clave.toString()), cuerpo), sesionMfaPendiente()))
				.andExpect(status().isForbidden());
		mvc.perform(json(post(ruta()).header("Idempotency-Key", clave.toString()), cuerpo).cookie(sesion(Rol.ADMIN)))
				.andExpect(status().isForbidden());
		mvc.perform(conCsrf(json(put(ruta() + "/" + vigencia), "{\"versionEsperada\":\"0\"}"), sesion(Rol.FAMILIA)))
				.andExpect(status().isForbidden());
		mvc.perform(conCsrf(json(post(ruta() + "/" + vigencia + "/revocar"), "{\"versionEsperada\":\"0\"}"), sesion(Rol.FAMILIA)))
				.andExpect(status().isForbidden());
		verifyNoInteractions(responsablesAdminService);
	}

	@Test
	void altaResponde201SinLocationYDelegaEscuelaYClaveDelContexto() throws Exception {
		when(responsablesAdminService.crear(any(), eq(deportista), eq(clave), any()))
				.thenReturn(new ResponsablesAdminService.ResultadoAlta(respuesta(false), true));
		mvc.perform(conCsrf(json(post(ruta()).header("Idempotency-Key", clave.toString()), CUERPO_ALTA.formatted(tutor)),
				sesion(Rol.ADMIN))).andExpect(status().isCreated()).andExpect(header().doesNotExist("Location"))
				.andExpect(jsonPath("$.id").value(vigencia.toString())).andExpect(jsonPath("$.version").value("0"))
				.andExpect(jsonPath("$.dni").doesNotExist());
	}

	@Test
	void altaExigeCabeceraDeIdempotenciaUnicaYValidaYCuerpoEstricto() throws Exception {
		var cuerpo = CUERPO_ALTA.formatted(tutor);
		mvc.perform(conCsrf(json(post(ruta()), cuerpo), sesion(Rol.ADMIN))).andExpect(status().isBadRequest());
		mvc.perform(conCsrf(json(post(ruta()).header("Idempotency-Key", "no-es-uuid"), cuerpo), sesion(Rol.ADMIN)))
				.andExpect(status().isBadRequest());
		// Faltan revisiones, revisión negativa o decimal inválida, tipo desconocido y propiedades no declaradas.
		for (String malo : new String[] { "{\"tutorId\":\"" + tutor + "\"}",
				cuerpo.replace("\"versionTutor\":\"0\"", "\"versionTutor\":\"-1\""),
				cuerpo.replace("\"versionTutor\":\"0\"", "\"versionTutor\":\"1.5\""),
				cuerpo.replace("}", ",\"tipo\":\"ABUELO\"}"),
				cuerpo.replace("}", ",\"escuelaId\":\"" + UUID.randomUUID() + "\"}"),
				cuerpo.replace("}", ",\"solicitudId\":\"" + UUID.randomUUID() + "\"}") }) {
			mvc.perform(conCsrf(json(post(ruta()).header("Idempotency-Key", clave.toString()), malo), sesion(Rol.ADMIN)))
					.andExpect(status().isBadRequest());
		}
		verifyNoInteractions(responsablesAdminService);
	}

	@Test
	void tipoNuloSeAceptaYSeDistingueDeSinDeterminar() throws Exception {
		when(responsablesAdminService.actualizarTipo(any(), eq(deportista), eq(vigencia), any())).thenReturn(respuesta(false));
		mvc.perform(conCsrf(json(put(ruta() + "/" + vigencia), "{\"tipo\":null,\"versionEsperada\":\"0\"}"), sesion(Rol.ADMIN)))
				.andExpect(status().isOk());
		mvc.perform(conCsrf(json(put(ruta() + "/" + vigencia), "{\"tipo\":\"SIN_DETERMINAR\",\"versionEsperada\":\"0\"}"),
				sesion(Rol.ADMIN))).andExpect(status().isOk());
		mvc.perform(conCsrf(json(put(ruta() + "/" + vigencia), "{\"tipo\":\"MADRE\"}"), sesion(Rol.ADMIN)))
				.andExpect(status().isBadRequest());
		mvc.perform(conCsrf(json(put(ruta() + "/" + vigencia), "{\"tipo\":\"MADRE\",\"versionEsperada\":\"0\",\"familiaId\":\"x\"}"),
				sesion(Rol.ADMIN))).andExpect(status().isBadRequest());
	}

	@Test
	void revocarDevuelveLaVigenciaCerradaYRechazaCuerpoConPropiedadesAjenas() throws Exception {
		when(responsablesAdminService.revocar(any(), eq(deportista), eq(vigencia), any())).thenReturn(respuesta(true));
		mvc.perform(conCsrf(json(post(ruta() + "/" + vigencia + "/revocar"), "{\"versionEsperada\":\"0\"}"), sesion(Rol.ADMIN)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.causaCierre").value("REVOCACION_ADMIN"));
		mvc.perform(conCsrf(json(post(ruta() + "/" + vigencia + "/revocar"), "{\"versionEsperada\":\"0\",\"tipo\":\"MADRE\"}"),
				sesion(Rol.ADMIN))).andExpect(status().isBadRequest());
	}

	@Test
	void losErroresDeNegocioYDeConcurrenciaSeMapeanSinFiltrarDatos() throws Exception {
		var cuerpo = CUERPO_ALTA.formatted(tutor);
		var alta = conCsrf(json(post(ruta()).header("Idempotency-Key", clave.toString()), cuerpo), sesion(Rol.ADMIN));
		when(responsablesAdminService.crear(any(), eq(deportista), eq(clave), any()))
				.thenThrow(DeportistaAdminService.deportistaNoEncontrado())
				.thenThrow(new ExcepcionNegocio(HttpStatus.CONFLICT, "ESTADO_INCOMPATIBLE", "El estado actual no permite esta operación."))
				.thenThrow(new ExcepcionNegocio(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUTILIZADA", "La clave de idempotencia ya se usó con otro contenido."))
				.thenThrow(new ExcepcionNegocio(HttpStatus.SERVICE_UNAVAILABLE, "ALTA_RESPONSABLES_DESHABILITADA", "El alta de responsables no está habilitada."))
				.thenThrow(new CannotAcquireLockException("espera"));
		mvc.perform(alta).andExpect(status().isNotFound()).andExpect(jsonPath("$.codigo").value("DEPORTISTA_NO_ENCONTRADO"));
		mvc.perform(conCsrf(json(post(ruta()).header("Idempotency-Key", clave.toString()), cuerpo), sesion(Rol.ADMIN)))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("ESTADO_INCOMPATIBLE"));
		mvc.perform(conCsrf(json(post(ruta()).header("Idempotency-Key", clave.toString()), cuerpo), sesion(Rol.ADMIN)))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("IDEMPOTENCY_KEY_REUTILIZADA"));
		mvc.perform(conCsrf(json(post(ruta()).header("Idempotency-Key", clave.toString()), cuerpo), sesion(Rol.ADMIN)))
				.andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.codigo").value("ALTA_RESPONSABLES_DESHABILITADA"));
		mvc.perform(conCsrf(json(post(ruta()).header("Idempotency-Key", clave.toString()), cuerpo), sesion(Rol.ADMIN)))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("CONFLICTO_CONCURRENCIA"));
	}
}
