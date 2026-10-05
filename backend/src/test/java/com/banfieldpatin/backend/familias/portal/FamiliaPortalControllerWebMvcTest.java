package com.banfieldpatin.backend.familias.portal;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.banfieldpatin.backend.compartido.RelojConfig;
import com.banfieldpatin.backend.compartido.error.ManejadorGlobalErrores;
import com.banfieldpatin.backend.compartido.web.Pagina;
import com.banfieldpatin.backend.deportistas.DeportistaAdminService;
import com.banfieldpatin.backend.familias.FamiliaAdminService;
import com.banfieldpatin.backend.familias.portal.dto.DeportistaDeFamilia;
import com.banfieldpatin.backend.familias.portal.dto.DeportistaDeFamiliaDetalle;
import com.banfieldpatin.backend.familias.portal.dto.MiFamiliaRespuesta;
import com.banfieldpatin.backend.familias.portal.dto.TutorDeFamilia;
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

/**
 * Contrato HTTP del portal de FAMILIA sobre la cadena de seguridad REAL (servicio simulado): forma de los DTO (con
 * {@code activo}, sin campos prohibidos), matriz 401/403/200, identidad solo del JWT (los parametros de alcance se ignoran),
 * id no UUID -> 400 y ausencia total de escritura (405 con CSRF, 403 sin CSRF).
 */
@WebMvcTest(controllers = FamiliaPortalController.class)
@Import({ SeguridadConfig.class, JwtConfig.class, CookieSesion.class, CookieBearerTokenResolver.class,
		PuntoEntradaJson.class, ManejadorAccesoDenegadoJson.class, ManejadorGlobalErrores.class, ServicioTokens.class,
		RelojConfig.class, SesionVigenteDePrueba.class })
@ActiveProfiles("test")
class FamiliaPortalControllerWebMvcTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	ServicioTokens tokens;
	@MockitoBean
	FamiliaPortalService servicio;

	private final UUID escuelaId = UUID.randomUUID();
	private final UUID familiaId = UUID.randomUUID();
	private final UUID usuarioId = UUID.randomUUID();
	private final UUID deportistaId = UUID.randomUUID();
	private static final List<String> RUTAS = List.of("/api/familia/mi-familia", "/api/familia/deportistas",
			"/api/familia/deportistas/" + UUID.randomUUID());

	private Cookie familia() {
		return new Cookie("BP_SESION", tokens.emitir(usuarioId, Rol.FAMILIA, escuelaId, familiaId));
	}

	private Cookie admin() {
		return new Cookie("BP_SESION", tokens.emitir(UUID.randomUUID(), Rol.ADMIN, escuelaId, null));
	}

	private MockHttpServletRequestBuilder conCsrf(MockHttpServletRequestBuilder req) throws Exception {
		MockHttpServletResponse r = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse();
		Cookie x = r.getCookie("XSRF-TOKEN");
		return req.cookie(x).header("X-XSRF-TOKEN", x.getValue());
	}

	private static DeportistaDeFamiliaDetalle detalle(UUID id, boolean activo) {
		return new DeportistaDeFamiliaDetalle(id, "Lola", "Gomez", "40111222", "27401112226", LocalDate.of(2015, 1, 2),
				"Argentina", "Calle 1", "Piso 2", "San Pedro", "San Pedro", "2930", "3329400000", "lola@fed.example",
				activo);
	}

	// ---------- mi-familia ----------

	@Test
	void miFamiliaDevuelveSoloLosCamposDelContratoYTomaLaIdentidadDelJwt() throws Exception {
		UUID tutorId = UUID.randomUUID();
		when(servicio.miFamilia(any())).thenReturn(new MiFamiliaRespuesta(familiaId, "Familia Gomez",
				List.of(new TutorDeFamilia(tutorId, "Ana", "Gomez", "Madre", "3329111111", "ana@example.com"))));

		mvc.perform(get("/api/familia/mi-familia").cookie(familia())).andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(3))
				.andExpect(jsonPath("$.id").value(familiaId.toString()))
				.andExpect(jsonPath("$.nombreReferencia").value("Familia Gomez"))
				.andExpect(jsonPath("$.tutores.length()").value(1))
				.andExpect(jsonPath("$.tutores[0].length()").value(6))
				.andExpect(jsonPath("$.tutores[0].id").value(tutorId.toString()))
				.andExpect(jsonPath("$.tutores[0].nombre").value("Ana"))
				.andExpect(jsonPath("$.tutores[0].apellido").value("Gomez"))
				.andExpect(jsonPath("$.tutores[0].parentesco").value("Madre"))
				.andExpect(jsonPath("$.tutores[0].telefono").value("3329111111"))
				.andExpect(jsonPath("$.tutores[0].email").value("ana@example.com"))
				.andExpect(jsonPath("$.tutores[0].dni").doesNotExist())
				.andExpect(jsonPath("$.tutores[0].activo").doesNotExist())
				.andExpect(jsonPath("$.tutores[0].familiaId").doesNotExist())
				.andExpect(jsonPath("$.tutores[0].usuarioId").doesNotExist())
				.andExpect(jsonPath("$.escuelaId").doesNotExist()).andExpect(jsonPath("$.activa").doesNotExist());

		ArgumentCaptor<UsuarioAutenticado> actor = ArgumentCaptor.forClass(UsuarioAutenticado.class);
		verify(servicio).miFamilia(actor.capture());
		assertThat(actor.getValue()).isEqualTo(new UsuarioAutenticado(usuarioId, escuelaId, familiaId, Rol.FAMILIA));
	}

	@Test
	void miFamiliaSinResultadoDa404ConElCodigoDeFamiliaNoEncontrada() throws Exception {
		when(servicio.miFamilia(any())).thenThrow(FamiliaAdminService.familiaNoEncontrada());

		mvc.perform(get("/api/familia/mi-familia").cookie(familia())).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.codigo").value("FAMILIA_NO_ENCONTRADA"))
				.andExpect(jsonPath("$.mensaje").value("La familia no existe."));
	}

	@Test
	void unaCuentaFamiliaSinFamiliaIdEnElTokenNuncaObtieneDatos() throws Exception {
		// El token se emite sin familia (la revalidacion central de la sesion lo rechazaria en produccion; aqui se
		// comprueba la defensa del propio portal): el servicio recibe familiaId nulo y responde 404/pagina vacia.
		Cookie sinFamilia = new Cookie("BP_SESION", tokens.emitir(usuarioId, Rol.FAMILIA, escuelaId, null));
		when(servicio.miFamilia(any())).thenThrow(FamiliaAdminService.familiaNoEncontrada());

		mvc.perform(get("/api/familia/mi-familia").cookie(sinFamilia)).andExpect(status().isNotFound());

		ArgumentCaptor<UsuarioAutenticado> actor = ArgumentCaptor.forClass(UsuarioAutenticado.class);
		verify(servicio).miFamilia(actor.capture());
		assertThat(actor.getValue().familiaId()).isNull();
	}

	// ---------- listado ----------

	@Test
	void elListadoExponeActivoYNingunCampoProhibido() throws Exception {
		var activo = new DeportistaDeFamilia(deportistaId, "Lola", "Gomez", LocalDate.of(2015, 1, 2), true);
		var inactivo = new DeportistaDeFamilia(UUID.randomUUID(), "Pepe", "Gomez", LocalDate.of(2012, 3, 4), false);
		when(servicio.deportistas(any(), any())).thenReturn(new Pagina<>(List.of(activo, inactivo), 0, 20, 2));

		mvc.perform(get("/api/familia/deportistas").cookie(familia())).andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElementos").value(2))
				.andExpect(jsonPath("$.contenido.length()").value(2))
				.andExpect(jsonPath("$.contenido[0].length()").value(5))
				.andExpect(jsonPath("$.contenido[0].id").value(deportistaId.toString()))
				.andExpect(jsonPath("$.contenido[0].nombre").value("Lola"))
				.andExpect(jsonPath("$.contenido[0].apellido").value("Gomez"))
				.andExpect(jsonPath("$.contenido[0].fechaNacimiento").value("2015-01-02"))
				.andExpect(jsonPath("$.contenido[0].activo").value(true))
				.andExpect(jsonPath("$.contenido[1].activo").value(false))
				.andExpect(jsonPath("$.contenido[0].escuelaId").doesNotExist())
				.andExpect(jsonPath("$.contenido[0].esPrincipal").doesNotExist())
				.andExpect(jsonPath("$.contenido[0].estado").doesNotExist())
				.andExpect(jsonPath("$.contenido[0].autorizadoEn").doesNotExist())
				.andExpect(jsonPath("$.contenido[0].autorizadoPor").doesNotExist())
				.andExpect(jsonPath("$.contenido[0].vinculoId").doesNotExist())
				.andExpect(jsonPath("$.contenido[0].dni").doesNotExist());
	}

	@Test
	void elListadoAcotaLaPaginaYElTamanioComoElRestoDeLaApi() throws Exception {
		when(servicio.deportistas(any(), any())).thenReturn(new Pagina<>(List.of(), 0, 20, 0));

		mvc.perform(get("/api/familia/deportistas").cookie(familia())).andExpect(status().isOk());
		mvc.perform(get("/api/familia/deportistas?page=3&size=500").cookie(familia())).andExpect(status().isOk());
		mvc.perform(get("/api/familia/deportistas?page=-4&size=0").cookie(familia())).andExpect(status().isOk());

		ArgumentCaptor<Pageable> pagina = ArgumentCaptor.forClass(Pageable.class);
		verify(servicio, org.mockito.Mockito.times(3)).deportistas(any(), pagina.capture());
		assertThat(pagina.getAllValues()).extracting(p -> p.getPageNumber()).containsExactly(0, 3, 0);
		assertThat(pagina.getAllValues()).extracting(p -> p.getPageSize()).containsExactly(20, 100, 1);
	}

	@Test
	void unTamanioOPaginaNoNumericosDan400SolicitudInvalida() throws Exception {
		mvc.perform(get("/api/familia/deportistas?page=abc").cookie(familia())).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
		verifyNoInteractions(servicio);
	}

	// ---------- detalle ----------

	@Test
	void elDetalleExponeLosQuinceCamposDelContratoIncluidoActivoYNingunoMas() throws Exception {
		when(servicio.deportista(any(), eq(deportistaId))).thenReturn(detalle(deportistaId, false));

		mvc.perform(get("/api/familia/deportistas/" + deportistaId).cookie(familia())).andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(15))
				.andExpect(jsonPath("$.id").value(deportistaId.toString()))
				.andExpect(jsonPath("$.dni").value("40111222")).andExpect(jsonPath("$.cuil").value("27401112226"))
				.andExpect(jsonPath("$.fechaNacimiento").value("2015-01-02"))
				.andExpect(jsonPath("$.nacionalidad").value("Argentina")).andExpect(jsonPath("$.domicilio").value("Calle 1"))
				.andExpect(jsonPath("$.otrosDatosDomicilio").value("Piso 2"))
				.andExpect(jsonPath("$.localidad").value("San Pedro")).andExpect(jsonPath("$.partido").value("San Pedro"))
				.andExpect(jsonPath("$.codigoPostal").value("2930"))
				.andExpect(jsonPath("$.telefonoContacto").value("3329400000"))
				.andExpect(jsonPath("$.emailFederativo").value("lola@fed.example"))
				.andExpect(jsonPath("$.activo").value(false))
				.andExpect(jsonPath("$.escuelaId").doesNotExist()).andExpect(jsonPath("$.esPrincipal").doesNotExist())
				.andExpect(jsonPath("$.autorizadoPor").doesNotExist()).andExpect(jsonPath("$.autorizadoEn").doesNotExist())
				.andExpect(jsonPath("$.estado").doesNotExist()).andExpect(jsonPath("$.vinculoId").doesNotExist())
				.andExpect(jsonPath("$.creadoEn").doesNotExist());
	}

	@Test
	void unDeportistaFueraDelAlcanceDa404UniformeConMensajeFijo() throws Exception {
		when(servicio.deportista(any(), any())).thenThrow(DeportistaAdminService.deportistaNoEncontrado());

		mvc.perform(get("/api/familia/deportistas/" + UUID.randomUUID()).cookie(familia()))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.codigo").value("DEPORTISTA_NO_ENCONTRADO"))
				.andExpect(jsonPath("$.mensaje").value("El deportista no existe."))
				.andExpect(jsonPath("$.detalles.length()").value(0));
	}

	@Test
	void unIdQueNoEsUuidDa400SolicitudInvalidaSinLlegarAlServicio() throws Exception {
		mvc.perform(get("/api/familia/deportistas/no-es-un-uuid").cookie(familia())).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
		mvc.perform(get("/api/familia/deportistas/123").cookie(familia())).andExpect(status().isBadRequest());

		verifyNoInteractions(servicio);
	}

	// ---------- identidad: los parametros de alcance se ignoran ----------

	@Test
	void losParametrosFamiliaIdEscuelaIdYUsuarioIdSeIgnoranEnLasTresRutas() throws Exception {
		UUID ajena = UUID.randomUUID();
		when(servicio.miFamilia(any())).thenReturn(new MiFamiliaRespuesta(familiaId, "Familia Gomez", List.of()));
		when(servicio.deportistas(any(), any())).thenReturn(new Pagina<>(List.of(), 0, 20, 0));
		when(servicio.deportista(any(), any())).thenReturn(detalle(deportistaId, true));
		String parametros = "?familiaId=" + ajena + "&escuelaId=" + ajena + "&usuarioId=" + ajena;

		mvc.perform(get("/api/familia/mi-familia" + parametros).cookie(familia())).andExpect(status().isOk());
		mvc.perform(get("/api/familia/deportistas" + parametros).cookie(familia())).andExpect(status().isOk());
		mvc.perform(get("/api/familia/deportistas/" + deportistaId + parametros).cookie(familia()))
				.andExpect(status().isOk());

		ArgumentCaptor<UsuarioAutenticado> actor = ArgumentCaptor.forClass(UsuarioAutenticado.class);
		verify(servicio).miFamilia(actor.capture());
		verify(servicio).deportistas(actor.capture(), any());
		verify(servicio).deportista(actor.capture(), eq(deportistaId));
		assertThat(actor.getAllValues()).hasSize(3).allSatisfy(
				u -> assertThat(u).isEqualTo(new UsuarioAutenticado(usuarioId, escuelaId, familiaId, Rol.FAMILIA)));
	}

	// ---------- 401 / 403 / 200 ----------

	@Test
	void sinSesionLasTresRutasDan401NoAutenticado() throws Exception {
		for (String ruta : RUTAS) {
			mvc.perform(get(ruta)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
		}
		verifyNoInteractions(servicio);
	}

	@Test
	void unAdminEnLasTresRutasDa403AccesoDenegadoSinTocarElServicio() throws Exception {
		for (String ruta : RUTAS) {
			mvc.perform(get(ruta).cookie(admin())).andExpect(status().isForbidden())
					.andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
		}
		verifyNoInteractions(servicio);
	}

	@Test
	void unaFamiliaPuedeLeerLasTresRutas() throws Exception {
		when(servicio.miFamilia(any())).thenReturn(new MiFamiliaRespuesta(familiaId, "F", List.of()));
		when(servicio.deportistas(any(), any())).thenReturn(new Pagina<>(List.of(), 0, 20, 0));
		when(servicio.deportista(any(), any())).thenReturn(detalle(deportistaId, true));

		for (String ruta : RUTAS) {
			mvc.perform(get(ruta).cookie(familia())).andExpect(status().isOk());
		}
	}

	// ---------- sin escritura ----------

	private Map<String, MockHttpServletRequestBuilder> escrituras(String ruta) {
		return Map.of("POST", post(ruta), "PUT", put(ruta), "PATCH", patch(ruta), "DELETE", delete(ruta));
	}

	@Test
	void cualquierMetodoQueEscribeConCsrfDa405MetodoNoPermitidoEnLasTresRutas() throws Exception {
		for (String ruta : RUTAS) {
			for (var escritura : escrituras(ruta).entrySet()) {
				mvc.perform(conCsrf(escritura.getValue()).cookie(familia()))
						.andExpect(status().isMethodNotAllowed())
						.andExpect(jsonPath("$.codigo").value("METODO_NO_PERMITIDO"));
			}
		}
		verifyNoInteractions(servicio);
	}

	@Test
	void cualquierMetodoQueEscribeSinCsrfDa403CsrfInvalidoEnLasTresRutas() throws Exception {
		for (String ruta : RUTAS) {
			for (var escritura : escrituras(ruta).entrySet()) {
				mvc.perform(escritura.getValue().cookie(familia())).andExpect(status().isForbidden())
						.andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
			}
		}
		verifyNoInteractions(servicio);
	}

	@Test
	void unAdminQueEscribeEnElPortalSigueRecibiendo403YNoLlegaAlServicio() throws Exception {
		for (String ruta : RUTAS) {
			mvc.perform(conCsrf(post(ruta)).cookie(admin())).andExpect(status().isForbidden());
		}
		verifyNoInteractions(servicio);
	}
}
