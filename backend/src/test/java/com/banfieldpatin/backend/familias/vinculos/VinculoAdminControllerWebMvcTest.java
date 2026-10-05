package com.banfieldpatin.backend.familias.vinculos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.banfieldpatin.backend.compartido.RelojConfig;
import com.banfieldpatin.backend.compartido.error.DetalleError;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.error.ManejadorGlobalErrores;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.deportistas.DeportistaAdminService;
import com.banfieldpatin.backend.familias.FamiliaAdminService;
import com.banfieldpatin.backend.familias.vinculos.dto.ResultadoVinculacion;
import com.banfieldpatin.backend.familias.vinculos.dto.VinculacionRespuesta;
import com.banfieldpatin.backend.familias.vinculos.dto.VinculoRespuesta;
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

import jakarta.servlet.http.Cookie;

@WebMvcTest(controllers = VinculoAdminController.class)
@Import({ SeguridadConfig.class, JwtConfig.class, CookieSesion.class, CookieBearerTokenResolver.class,
		PuntoEntradaJson.class, ManejadorAccesoDenegadoJson.class, ManejadorGlobalErrores.class, ServicioTokens.class,
		RelojConfig.class, SesionVigenteDePrueba.class })
@ActiveProfiles("test")
class VinculoAdminControllerWebMvcTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	ServicioTokens tokens;
	@Autowired
	SesionVigenteDePrueba.Verificador verificador;
	@MockitoBean
	VinculoAdminService servicio;

	private final UUID escuelaId = UUID.randomUUID();
	private final UUID adminId = UUID.randomUUID();
	private final UUID familiaId = UUID.randomUUID();
	private final UUID d1 = UUID.randomUUID();
	private final UUID d2 = UUID.randomUUID();
	private final String listaDeFamilia = "/api/admin/familias/" + familiaId + "/deportistas";
	private final String listaDeDeportista = "/api/admin/deportistas/" + d1 + "/familias";

	private Cookie admin() {
		return new Cookie("BP_SESION", tokens.emitir(adminId, Rol.ADMIN, escuelaId, null));
	}

	private Cookie familiaSesion() {
		return new Cookie("BP_SESION", tokens.emitir(UUID.randomUUID(), Rol.FAMILIA, escuelaId, UUID.randomUUID()));
	}

	private MockHttpServletRequestBuilder conCsrf(MockHttpServletRequestBuilder req) throws Exception {
		MockHttpServletResponse r = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse();
		Cookie x = r.getCookie("XSRF-TOKEN");
		return req.cookie(x).header("X-XSRF-TOKEN", x.getValue());
	}

	private MockHttpServletRequestBuilder cuerpo(MockHttpServletRequestBuilder req, Cookie sesion, String json)
			throws Exception {
		return conCsrf(req.contentType(MediaType.APPLICATION_JSON).content(json)).cookie(sesion);
	}

	private VinculoRespuesta vinculo(UUID deportistaId, EstadoVinculo estado, boolean principal) {
		return new VinculoRespuesta(UUID.randomUUID(), familiaId, "Familia Perez", deportistaId, "Juan", "Perez", true,
				estado, principal, Instant.parse("2026-05-05T12:00:00Z"));
	}

	private static String ids(UUID... ids) {
		StringBuilder json = new StringBuilder("{\"deportistaIds\":[");
		for (int i = 0; i < ids.length; i++) {
			json.append(i == 0 ? "" : ",").append('"').append(ids[i]).append('"');
		}
		return json.append("]}").toString();
	}

	// ---------- vincular ----------

	@Test
	void vincularDevuelve200ConLosResultadosEnElOrdenDeLaSolicitudYSoloLosCamposDelContrato() throws Exception {
		when(servicio.vincular(any(), eq(familiaId), any(), any())).thenReturn(new VinculacionRespuesta(List.of(
				new VinculacionRespuesta.Resultado(vinculo(d2, EstadoVinculo.ACTIVO, true), ResultadoVinculacion.CREADO),
				new VinculacionRespuesta.Resultado(vinculo(d1, EstadoVinculo.ACTIVO, false),
						ResultadoVinculacion.SIN_CAMBIOS))));

		mvc.perform(cuerpo(post(listaDeFamilia), admin(), ids(d2, d1))).andExpect(status().isOk())
				.andExpect(jsonPath("$.resultados.length()").value(2))
				.andExpect(jsonPath("$.resultados[0].resultado").value("CREADO"))
				.andExpect(jsonPath("$.resultados[0].vinculo.deportistaId").value(d2.toString()))
				.andExpect(jsonPath("$.resultados[0].vinculo.estado").value("ACTIVO"))
				.andExpect(jsonPath("$.resultados[0].vinculo.esPrincipal").value(true))
				.andExpect(jsonPath("$.resultados[0].vinculo.familiaNombre").value("Familia Perez"))
				.andExpect(jsonPath("$.resultados[0].vinculo.deportistaActivo").value(true))
				.andExpect(jsonPath("$.resultados[0].vinculo.autorizadoEn").exists())
				.andExpect(jsonPath("$.resultados[0].vinculo.escuelaId").doesNotExist())
				.andExpect(jsonPath("$.resultados[0].vinculo.autorizadoPor").doesNotExist())
				.andExpect(jsonPath("$.resultados[0].vinculo.dni").doesNotExist())
				.andExpect(jsonPath("$.resultados[1].resultado").value("SIN_CAMBIOS"));

		ArgumentCaptor<UsuarioAutenticado> actor = ArgumentCaptor.forClass(UsuarioAutenticado.class);
		verify(servicio).vincular(actor.capture(), eq(familiaId), eq(List.of(d2, d1)), any(DatosSolicitud.class));
		assertThat(actor.getValue().escuelaId()).isEqualTo(escuelaId);
		assertThat(actor.getValue().id()).isEqualTo(adminId);
	}

	@Test
	void vincularConIdsRepetidosLosPasaTalCualAlServicioQueDeduplica() throws Exception {
		when(servicio.vincular(any(), any(), any(), any())).thenReturn(new VinculacionRespuesta(List.of()));

		mvc.perform(cuerpo(post(listaDeFamilia), admin(), ids(d1, d1))).andExpect(status().isOk());

		verify(servicio).vincular(any(), eq(familiaId), eq(List.of(d1, d1)), any());
	}

	@Test
	void unaListaVaciaDeMasDe50ItemsONulaDa400ValidacionNombrandoElCampo() throws Exception {
		StringBuilder cincuentaYUno = new StringBuilder("{\"deportistaIds\":[");
		for (int i = 0; i < 51; i++) {
			cincuentaYUno.append(i == 0 ? "" : ",").append('"').append(UUID.randomUUID()).append('"');
		}
		cincuentaYUno.append("]}");

		for (String json : new String[] { "{\"deportistaIds\":[]}", cincuentaYUno.toString(),
				"{\"deportistaIds\":[\"" + d1 + "\",null]}", "{}", "{\"deportistaIds\":null}" }) {
			mvc.perform(cuerpo(post(listaDeFamilia), admin(), json)).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.codigo").value("VALIDACION"))
					.andExpect(jsonPath("$.detalles[0].campo").value(org.hamcrest.Matchers.startsWith("deportistaIds")));
		}
		verifyNoInteractions(servicio);
	}

	@Test
	void cincuentaIdsExactosSonValidos() throws Exception {
		when(servicio.vincular(any(), any(), any(), any())).thenReturn(new VinculacionRespuesta(List.of()));
		UUID[] cincuenta = new UUID[50];
		for (int i = 0; i < 50; i++) {
			cincuenta[i] = UUID.randomUUID();
		}

		mvc.perform(cuerpo(post(listaDeFamilia), admin(), ids(cincuenta))).andExpect(status().isOk());
	}

	@Test
	void unCuerpoConPropiedadesQueDecideElServidorDa400SolicitudInvalidaSinEcoYNoLlegaAlServicio() throws Exception {
		for (String extra : new String[] { "\"esPrincipal\":true", "\"estado\":\"ACTIVO\"",
				"\"autorizadoPor\":\"" + UUID.randomUUID() + "\"", "\"familiaId\":\"" + UUID.randomUUID() + "\"",
				"\"escuelaId\":\"" + UUID.randomUUID() + "\"" }) {
			mvc.perform(cuerpo(post(listaDeFamilia), admin(), "{\"deportistaIds\":[\"" + d1 + "\"]," + extra + "}"))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"))
					.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("esPrincipal"))));
		}
		mvc.perform(cuerpo(post(listaDeFamilia), admin(), "{\"deportistaIds\":[\"no-es-uuid\"]}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
		verifyNoInteractions(servicio);
	}

	@Test
	void losErroresDeNegocioDelLoteSeMapeanConSuCodigoYLasPosicionesSinValores() throws Exception {
		when(servicio.vincular(any(), any(), any(), any()))
				.thenThrow(FamiliaAdminService.familiaNoEncontrada())
				.thenThrow(FamiliaAdminService.familiaInactiva())
				.thenThrow(new ExcepcionNegocio(HttpStatus.NOT_FOUND, "DEPORTISTA_NO_ENCONTRADO", "No existe.",
						List.of(new DetalleError("deportistaIds[1]", "El deportista no existe."))))
				.thenThrow(new ExcepcionNegocio(HttpStatus.CONFLICT, "DEPORTISTA_INACTIVO", "Inactivo.",
						List.of(new DetalleError("deportistaIds[0]", "El deportista esta inactivo."))))
				.thenThrow(VinculoAdminService.conflictoConcurrente());

		mvc.perform(cuerpo(post(listaDeFamilia), admin(), ids(d1))).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.codigo").value("FAMILIA_NO_ENCONTRADA"));
		mvc.perform(cuerpo(post(listaDeFamilia), admin(), ids(d1))).andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("FAMILIA_INACTIVA"));
		mvc.perform(cuerpo(post(listaDeFamilia), admin(), ids(d1, d2))).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.codigo").value("DEPORTISTA_NO_ENCONTRADO"))
				.andExpect(jsonPath("$.detalles[0].campo").value("deportistaIds[1]"))
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(d2.toString()))));
		mvc.perform(cuerpo(post(listaDeFamilia), admin(), ids(d1))).andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("DEPORTISTA_INACTIVO"))
				.andExpect(jsonPath("$.detalles[0].campo").value("deportistaIds[0]"));
		mvc.perform(cuerpo(post(listaDeFamilia), admin(), ids(d1))).andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("VINCULO_PRINCIPAL_EN_CONFLICTO"))
				.andExpect(jsonPath("$.mensaje").value("Otro cambio sobre el mismo deportista ocurrio al mismo tiempo. Reintentá."));
	}

	// ---------- revocar y principal ----------

	@Test
	void revocarYPrincipalDevuelven200ConElVinculoYUsanLasRutasDelContrato() throws Exception {
		when(servicio.revocar(any(), eq(familiaId), eq(d1), any())).thenReturn(vinculo(d1, EstadoVinculo.REVOCADO, false));
		when(servicio.cambiarPrincipal(any(), eq(familiaId), eq(d1), any()))
				.thenReturn(vinculo(d1, EstadoVinculo.ACTIVO, true));

		mvc.perform(conCsrf(post(listaDeFamilia + "/" + d1 + "/revocar")).cookie(admin())).andExpect(status().isOk())
				.andExpect(jsonPath("$.estado").value("REVOCADO")).andExpect(jsonPath("$.esPrincipal").value(false))
				.andExpect(jsonPath("$.vinculoId").exists()).andExpect(jsonPath("$.escuelaId").doesNotExist());
		mvc.perform(conCsrf(post(listaDeFamilia + "/" + d1 + "/principal")).cookie(admin())).andExpect(status().isOk())
				.andExpect(jsonPath("$.estado").value("ACTIVO")).andExpect(jsonPath("$.esPrincipal").value(true));
	}

	@Test
	void revocarYPrincipalMapeanSus404Y409() throws Exception {
		when(servicio.revocar(any(), any(), any(), any())).thenThrow(VinculoAdminService.vinculoNoEncontrado())
				.thenThrow(VinculoAdminService.vinculoNoActivo());
		when(servicio.cambiarPrincipal(any(), any(), any(), any())).thenThrow(FamiliaAdminService.familiaNoEncontrada())
				.thenThrow(FamiliaAdminService.familiaInactiva()).thenThrow(VinculoAdminService.vinculoNoEncontrado())
				.thenThrow(VinculoAdminService.vinculoNoActivo()).thenThrow(VinculoAdminService.conflictoConcurrente());
		String revocar = listaDeFamilia + "/" + d1 + "/revocar";
		String principal = listaDeFamilia + "/" + d1 + "/principal";

		mvc.perform(conCsrf(post(revocar)).cookie(admin())).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.codigo").value("VINCULO_NO_ENCONTRADO"));
		mvc.perform(conCsrf(post(revocar)).cookie(admin())).andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("VINCULO_NO_ACTIVO"));
		mvc.perform(conCsrf(post(principal)).cookie(admin())).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.codigo").value("FAMILIA_NO_ENCONTRADA"));
		// Cuerpo de FAMILIA_INACTIVA: sin ids de la familia ni del deportista.
		mvc.perform(conCsrf(post(principal)).cookie(admin())).andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("FAMILIA_INACTIVA"))
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(familiaId.toString()))))
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(d1.toString()))));
		mvc.perform(conCsrf(post(principal)).cookie(admin())).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.codigo").value("VINCULO_NO_ENCONTRADO"));
		mvc.perform(conCsrf(post(principal)).cookie(admin())).andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("VINCULO_NO_ACTIVO"));
		mvc.perform(conCsrf(post(principal)).cookie(admin())).andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("VINCULO_PRINCIPAL_EN_CONFLICTO"));
	}

	@Test
	void losIdsNoUuidDeLaRutaDan400SolicitudInvalida() throws Exception {
		mvc.perform(conCsrf(post("/api/admin/familias/no-uuid/deportistas/" + d1 + "/principal")).cookie(admin()))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
		mvc.perform(conCsrf(post(listaDeFamilia + "/no-uuid/revocar")).cookie(admin()))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/admin/deportistas/no-uuid/familias").cookie(admin())).andExpect(status().isBadRequest());
		verifyNoInteractions(servicio);
	}

	// ---------- listados ----------

	@Test
	void losListadosDevuelven200ConTodosLosEstadosYSinDatosPersonalesDeMas() throws Exception {
		when(servicio.listarDeFamilia(any(), eq(familiaId))).thenReturn(List.of(vinculo(d1, EstadoVinculo.ACTIVO, true),
				vinculo(d2, EstadoVinculo.REVOCADO, false)));
		when(servicio.listarDeDeportista(any(), eq(d1))).thenReturn(List.of(vinculo(d1, EstadoVinculo.ACTIVO, true)));

		mvc.perform(get(listaDeFamilia).cookie(admin())).andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[1].estado").value("REVOCADO"))
				.andExpect(jsonPath("$[0].deportistaNombre").value("Juan"))
				.andExpect(jsonPath("$[0].dni").doesNotExist()).andExpect(jsonPath("$[0].cuil").doesNotExist())
				.andExpect(jsonPath("$[0].escuelaId").doesNotExist());
		mvc.perform(get(listaDeDeportista).cookie(admin())).andExpect(status().isOk())
				.andExpect(jsonPath("$[0].familiaNombre").value("Familia Perez"));
	}

	@Test
	void losListadosDan404ParaUnIdAjenoOInexistente() throws Exception {
		when(servicio.listarDeFamilia(any(), any())).thenThrow(FamiliaAdminService.familiaNoEncontrada());
		when(servicio.listarDeDeportista(any(), any())).thenThrow(DeportistaAdminService.deportistaNoEncontrado());

		mvc.perform(get(listaDeFamilia).cookie(admin())).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.codigo").value("FAMILIA_NO_ENCONTRADA"));
		mvc.perform(get(listaDeDeportista).cookie(admin())).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.codigo").value("DEPORTISTA_NO_ENCONTRADO"));
	}

	@Test
	void losParametrosEscuelaIdYFamiliaIdSeIgnoranYLaEscuelaSaleDelToken() throws Exception {
		when(servicio.listarDeFamilia(any(), any())).thenReturn(List.of());
		when(servicio.vincular(any(), any(), any(), any())).thenReturn(new VinculacionRespuesta(List.of()));

		mvc.perform(get(listaDeFamilia + "?escuelaId=" + UUID.randomUUID() + "&familiaId=" + UUID.randomUUID())
				.cookie(admin())).andExpect(status().isOk());
		mvc.perform(cuerpo(post(listaDeFamilia + "?escuelaId=" + UUID.randomUUID()), admin(), ids(d1)))
				.andExpect(status().isOk());

		ArgumentCaptor<UsuarioAutenticado> actor = ArgumentCaptor.forClass(UsuarioAutenticado.class);
		verify(servicio).listarDeFamilia(actor.capture(), eq(familiaId));
		assertThat(actor.getValue().escuelaId()).isEqualTo(escuelaId);
		verify(servicio).vincular(actor.capture(), eq(familiaId), any(), any());
		assertThat(actor.getValue().escuelaId()).isEqualTo(escuelaId);
	}

	// ---------- 405 ----------

	@Test
	void deleteYPatchSobreLasRutasDeVinculosDan405MetodoNoPermitido() throws Exception {
		String revocar = listaDeFamilia + "/" + d1 + "/revocar";
		String principal = listaDeFamilia + "/" + d1 + "/principal";

		for (String ruta : new String[] { listaDeFamilia, revocar, principal, listaDeDeportista }) {
			mvc.perform(conCsrf(delete(ruta)).cookie(admin())).andExpect(status().isMethodNotAllowed())
					.andExpect(jsonPath("$.codigo").value("METODO_NO_PERMITIDO"));
			mvc.perform(cuerpo(patch(ruta), admin(), "{}")).andExpect(status().isMethodNotAllowed());
		}
		// GET sobre las rutas que solo aceptan POST, y PUT sobre las demas.
		mvc.perform(get(revocar).cookie(admin())).andExpect(status().isMethodNotAllowed());
		mvc.perform(cuerpo(put(principal), admin(), "{}")).andExpect(status().isMethodNotAllowed());
		verifyNoInteractions(servicio);
	}

	// ---------- seguridad ----------

	@Test
	void anonimoRecibe401EnTodasLasRutas() throws Exception {
		mvc.perform(get(listaDeFamilia)).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
		mvc.perform(get(listaDeDeportista)).andExpect(status().isUnauthorized());
		mvc.perform(conCsrf(post(listaDeFamilia)).contentType(MediaType.APPLICATION_JSON).content(ids(d1)))
				.andExpect(status().isUnauthorized());
		mvc.perform(conCsrf(post(listaDeFamilia + "/" + d1 + "/revocar"))).andExpect(status().isUnauthorized());
		mvc.perform(conCsrf(post(listaDeFamilia + "/" + d1 + "/principal"))).andExpect(status().isUnauthorized());
		verifyNoInteractions(servicio);
	}

	@Test
	void familiaRecibe403AccesoDenegadoEnTodasLasRutas() throws Exception {
		Cookie familia = familiaSesion();
		mvc.perform(get(listaDeFamilia).cookie(familia)).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
		mvc.perform(get(listaDeDeportista).cookie(familia)).andExpect(status().isForbidden());
		mvc.perform(cuerpo(post(listaDeFamilia), familia, ids(d1))).andExpect(status().isForbidden());
		mvc.perform(conCsrf(post(listaDeFamilia + "/" + d1 + "/revocar")).cookie(familia))
				.andExpect(status().isForbidden());
		mvc.perform(conCsrf(post(listaDeFamilia + "/" + d1 + "/principal")).cookie(familia))
				.andExpect(status().isForbidden());
		verifyNoInteractions(servicio);
	}

	@Test
	void lasMutacionesSinCsrfDan403CsrfInvalido() throws Exception {
		mvc.perform(post(listaDeFamilia).cookie(admin()).contentType(MediaType.APPLICATION_JSON).content(ids(d1)))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
		mvc.perform(post(listaDeFamilia + "/" + d1 + "/revocar").cookie(admin())).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
		mvc.perform(post(listaDeFamilia + "/" + d1 + "/principal").cookie(admin())).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
		verifyNoInteractions(servicio);
	}

	@Test
	void laRevalidacionCentralDeSesionAplicaATodasLasRutasDeVinculos() throws Exception {
		verificador.rechazar();
		try {
			mvc.perform(get(listaDeFamilia).cookie(admin())).andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
			mvc.perform(get(listaDeDeportista).cookie(admin())).andExpect(status().isUnauthorized());
			mvc.perform(cuerpo(post(listaDeFamilia), admin(), ids(d1))).andExpect(status().isUnauthorized());
			mvc.perform(conCsrf(post(listaDeFamilia + "/" + d1 + "/revocar")).cookie(admin()))
					.andExpect(status().isUnauthorized());
			mvc.perform(conCsrf(post(listaDeFamilia + "/" + d1 + "/principal")).cookie(admin()))
					.andExpect(status().isUnauthorized());
			verifyNoInteractions(servicio);
		} finally {
			verificador.reiniciar();
		}
	}
}
