package com.banfieldpatin.backend.deportistas;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.banfieldpatin.backend.compartido.RelojConfig;
import com.banfieldpatin.backend.compartido.error.ManejadorGlobalErrores;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.compartido.web.FiltroEstado;
import com.banfieldpatin.backend.compartido.web.Pagina;
import com.banfieldpatin.backend.deportistas.dto.DeportistaDetalle;
import com.banfieldpatin.backend.deportistas.dto.DeportistaResumen;
import com.banfieldpatin.backend.deportistas.dto.DeportistaSolicitud;
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

@WebMvcTest(controllers = DeportistaAdminController.class)
@Import({ SeguridadConfig.class, JwtConfig.class, CookieSesion.class, CookieBearerTokenResolver.class,
		PuntoEntradaJson.class, ManejadorAccesoDenegadoJson.class, ManejadorGlobalErrores.class,
		ServicioTokens.class, RelojConfig.class, SesionVigenteDePrueba.class })
@ActiveProfiles("test")
class DeportistaAdminControllerWebMvcTest {

	private static final String MINIMO = "{\"dni\":\"30.111.222\",\"nombre\":\"Juan\",\"apellido\":\"Perez\"}";
	private static final String RUTA = "/api/admin/deportistas";

	@Autowired
	MockMvc mvc;
	@Autowired
	ServicioTokens tokens;
	@Autowired
	SesionVigenteDePrueba.Verificador verificador;
	@MockitoBean
	DeportistaAdminService servicio;

	private final UUID escuelaId = UUID.randomUUID();
	private final UUID adminId = UUID.randomUUID();
	private final UUID id = UUID.randomUUID();

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

	private DeportistaDetalle detalle(boolean activo) {
		return new DeportistaDetalle(id, "Juan", "Perez", "30111222", "20301112220", LocalDate.of(2012, 5, 10),
				"Argentina", "Calle 1", "Piso 2", "Banfield", "Lomas", "1828", "11-5555-0000", "juan@example.com",
				activo);
	}

	private static String conCampo(String campo, String valor) {
		return "{\"dni\":\"30111222\",\"nombre\":\"Juan\",\"apellido\":\"Perez\",\"" + campo + "\":" + valor + "}";
	}

	// ---------- crear ----------

	@Test
	void crearDevuelve201ConLocationYElDetalleSinEscuelaNiVinculos() throws Exception {
		when(servicio.crear(any(), any(), any())).thenReturn(detalle(true));

		mvc.perform(cuerpo(post(RUTA), admin(),
				"{\"dni\":\" 30.111.222 \",\"nombre\":\"  Juan \",\"apellido\":\"Perez\",\"cuil\":\"20-30111222-0\","
						+ "\"fechaNacimiento\":\"2012-05-10\",\"emailFederativo\":\"JUAN@Example.com\","
						+ "\"telefonoContacto\":\"  \",\"localidad\":\"Banfield\"}"))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", RUTA + "/" + id))
				.andExpect(jsonPath("$.id").value(id.toString()))
				.andExpect(jsonPath("$.dni").value("30111222"))
				.andExpect(jsonPath("$.fechaNacimiento").value("2012-05-10"))
				.andExpect(jsonPath("$.activo").value(true))
				.andExpect(jsonPath("$.escuelaId").doesNotExist())
				.andExpect(jsonPath("$.familias").doesNotExist());

		ArgumentCaptor<UsuarioAutenticado> actor = ArgumentCaptor.forClass(UsuarioAutenticado.class);
		ArgumentCaptor<DeportistaSolicitud> solicitud = ArgumentCaptor.forClass(DeportistaSolicitud.class);
		verify(servicio).crear(actor.capture(), solicitud.capture(), any(DatosSolicitud.class));
		assertThat(actor.getValue().id()).isEqualTo(adminId);
		assertThat(actor.getValue().escuelaId()).isEqualTo(escuelaId);
		assertThat(solicitud.getValue().dni()).isEqualTo("30.111.222");
		assertThat(solicitud.getValue().nombre()).isEqualTo("Juan");
		assertThat(solicitud.getValue().cuil()).isEqualTo("20-30111222-0");
		assertThat(solicitud.getValue().emailFederativo()).isEqualTo("juan@example.com");
		assertThat(solicitud.getValue().telefonoContacto()).isNull();
		assertThat(solicitud.getValue().fechaNacimiento()).isEqualTo(LocalDate.of(2012, 5, 10));
	}

	@Test
	void crearConElMinimoDniNombreYApellidoEsValidoYLosOpcionalesLlegaNulos() throws Exception {
		when(servicio.crear(any(), any(), any())).thenReturn(detalle(true));

		mvc.perform(cuerpo(post(RUTA), admin(), MINIMO)).andExpect(status().isCreated());

		ArgumentCaptor<DeportistaSolicitud> solicitud = ArgumentCaptor.forClass(DeportistaSolicitud.class);
		verify(servicio).crear(any(), solicitud.capture(), any());
		assertThat(solicitud.getValue().cuil()).isNull();
		assertThat(solicitud.getValue().fechaNacimiento()).isNull();
		assertThat(solicitud.getValue().emailFederativo()).isNull();
	}

	@Test
	void dniInvalidoDa400ValidacionNombrandoElCampoSinEcoDelValor() throws Exception {
		for (String dni : new String[] { "123456", "1234567890", "12-345-678", "A1234567", "12ab" }) {
			mvc.perform(cuerpo(post(RUTA), admin(),
					"{\"dni\":\"" + dni + "\",\"nombre\":\"Juan\",\"apellido\":\"Perez\"}"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.codigo").value("VALIDACION"))
					.andExpect(jsonPath("$.detalles[0].campo").value("dni"))
					.andExpect(content().string(Matchers.not(Matchers.containsString(dni))));
		}
		verifyNoInteractions(servicio);
	}

	@Test
	void cadaCampoInvalidoDa400ValidacionConSuNombre() throws Exception {
		String ayer = LocalDate.now().minusDays(1).toString();
		String hoy = LocalDate.now().toString();
		String manana = LocalDate.now().plusDays(1).toString();
		String[][] casos = {
				{ "{\"nombre\":\"Juan\",\"apellido\":\"Perez\"}", "dni" },
				{ "{\"dni\":\"   \",\"nombre\":\"Juan\",\"apellido\":\"Perez\"}", "dni" },
				{ "{\"dni\":\"30111222\",\"apellido\":\"Perez\"}", "nombre" },
				{ "{\"dni\":\"30111222\",\"nombre\":\"   \",\"apellido\":\"Perez\"}", "nombre" },
				{ "{\"dni\":\"30111222\",\"nombre\":\"Juan\"}", "apellido" },
				{ conCampo("nombre", "\"" + "a".repeat(101) + "\"").replace("\"nombre\":\"Juan\",", ""), "nombre" },
				{ conCampo("cuil", "\"20-30111222-5\""), "cuil" },
				{ conCampo("cuil", "\"2030111222\""), "cuil" },
				{ conCampo("cuil", "\"2030111222012\""), "cuil" },
				{ conCampo("cuil", "\"abcdefghijk\""), "cuil" },
				{ conCampo("cuil", "\"" + "2".repeat(21) + "\""), "cuil" },
				{ conCampo("fechaNacimiento", "\"" + hoy + "\""), "fechaNacimiento" },
				{ conCampo("fechaNacimiento", "\"" + manana + "\""), "fechaNacimiento" },
				{ conCampo("emailFederativo", "\"no-es-email\""), "emailFederativo" },
				{ conCampo("emailFederativo", "\"" + "a".repeat(175) + "@x.com\""), "emailFederativo" },
				{ conCampo("nacionalidad", "\"" + "a".repeat(81) + "\""), "nacionalidad" },
				{ conCampo("domicilio", "\"" + "a".repeat(181) + "\""), "domicilio" },
				{ conCampo("otrosDatosDomicilio", "\"" + "a".repeat(251) + "\""), "otrosDatosDomicilio" },
				{ conCampo("localidad", "\"" + "a".repeat(101) + "\""), "localidad" },
				{ conCampo("partido", "\"" + "a".repeat(101) + "\""), "partido" },
				{ conCampo("codigoPostal", "\"" + "1".repeat(16) + "\""), "codigoPostal" },
				{ conCampo("telefonoContacto", "\"" + "1".repeat(41) + "\""), "telefonoContacto" } };
		for (String[] caso : casos) {
			mvc.perform(cuerpo(post(RUTA), admin(), caso[0])).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.codigo").value("VALIDACION"))
					.andExpect(jsonPath("$.detalles[?(@.campo == '" + caso[1] + "')]").isNotEmpty());
		}
		// Ayer y 1900-01-01 son validos.
		when(servicio.crear(any(), any(), any())).thenReturn(detalle(true));
		mvc.perform(cuerpo(post(RUTA), admin(), conCampo("fechaNacimiento", "\"" + ayer + "\"")))
				.andExpect(status().isCreated());
		mvc.perform(cuerpo(post(RUTA), admin(), conCampo("fechaNacimiento", "\"1900-01-01\"")))
				.andExpect(status().isCreated());
	}

	@Test
	void unCuilValidoConSeparadoresSePasaAlServicio() throws Exception {
		when(servicio.crear(any(), any(), any())).thenReturn(detalle(true));

		mvc.perform(cuerpo(post(RUTA), admin(), conCampo("cuil", "\"20-30111222-0\""))).andExpect(status().isCreated());
		mvc.perform(cuerpo(post(RUTA), admin(), conCampo("cuil", "\"  \""))).andExpect(status().isCreated());
	}

	@Test
	void unaFechaMalFormadaDa400SolicitudInvalida() throws Exception {
		for (String fecha : new String[] { "\"31/12/2012\"", "\"2012-13-45\"", "\"ayer\"", "{\"a\":1}" }) {
			mvc.perform(cuerpo(post(RUTA), admin(), conCampo("fechaNacimiento", fecha)))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
		}
		verifyNoInteractions(servicio);
	}

	@Test
	void propiedadesProhibidasDan400SolicitudInvalidaEnPostYPut() throws Exception {
		for (String extra : new String[] { "\"activo\":false", "\"id\":\"" + UUID.randomUUID() + "\"",
				"\"escuelaId\":\"" + UUID.randomUUID() + "\"", "\"familiaId\":\"" + UUID.randomUUID() + "\"",
				"\"estado\":\"ACTIVO\"", "\"cualquiera\":1" }) {
			mvc.perform(cuerpo(post(RUTA), admin(), MINIMO.replace("}", "," + extra + "}")))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
			mvc.perform(cuerpo(put(RUTA + "/" + id), admin(), MINIMO.replace("}", "," + extra + "}")))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
		}
		verifyNoInteractions(servicio);
	}

	// ---------- 409 ----------

	@Test
	void losTresConflictosDan409ConSuCodigoYSinNombresDeRestriccionNiIds() throws Exception {
		when(servicio.crear(any(), any(), any())).thenThrow(DeportistaAdminService.dniDuplicado())
				.thenThrow(DeportistaAdminService.dniReservadoPorInactivo()).thenThrow(DeportistaAdminService.cuilDuplicado());

		String[] codigos = { "DNI_DUPLICADO", "DNI_RESERVADO_POR_INACTIVO", "CUIL_DUPLICADO" };
		for (String codigo : codigos) {
			String cuerpo = mvc.perform(cuerpo(post(RUTA), admin(), MINIMO)).andExpect(status().isConflict())
					.andExpect(jsonPath("$.codigo").value(codigo)).andReturn().getResponse().getContentAsString();
			assertThat(cuerpo).doesNotContain("uq_").doesNotContain("constraint").doesNotContain("index")
					.doesNotContain("30111222").doesNotContain("30.111.222").doesNotContain(escuelaId.toString())
					.doesNotContain(id.toString());
		}
	}

	@Test
	void elMensajeDeDniReservadoIndicaReactivar() throws Exception {
		when(servicio.crear(any(), any(), any())).thenThrow(DeportistaAdminService.dniReservadoPorInactivo());

		mvc.perform(cuerpo(post(RUTA), admin(), MINIMO)).andExpect(jsonPath("$.mensaje")
				.value("Ya existe un deportista inactivo con ese DNI. Reactivalo en lugar de crear uno nuevo."));
	}

	@Test
	void unConflictoEnLaEdicionTambienDa409() throws Exception {
		when(servicio.actualizar(any(), eq(id), any(), any())).thenThrow(DeportistaAdminService.dniDuplicado());

		mvc.perform(cuerpo(put(RUTA + "/" + id), admin(), MINIMO)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("DNI_DUPLICADO"));
	}

	// ---------- listado ----------

	@Test
	void listarPorDefectoFiltraActivosYDevuelveLaPaginaSinEscuela() throws Exception {
		when(servicio.listar(any(), any(), any(), any())).thenReturn(new Pagina<>(
				List.of(new DeportistaResumen(id, "Juan", "Perez", "30111222", LocalDate.of(2012, 5, 10), true)), 0, 20, 1));

		mvc.perform(get(RUTA).cookie(admin())).andExpect(status().isOk())
				.andExpect(jsonPath("$.contenido[0].id").value(id.toString()))
				.andExpect(jsonPath("$.contenido[0].dni").value("30111222"))
				.andExpect(jsonPath("$.contenido[0].activo").value(true))
				.andExpect(jsonPath("$.contenido[0].escuelaId").doesNotExist())
				.andExpect(jsonPath("$.contenido[0].cuil").doesNotExist())
				.andExpect(jsonPath("$.totalElementos").value(1));

		ArgumentCaptor<UsuarioAutenticado> actor = ArgumentCaptor.forClass(UsuarioAutenticado.class);
		verify(servicio).listar(actor.capture(), eq(FiltroEstado.ACTIVOS), eq(""), any());
		assertThat(actor.getValue().escuelaId()).isEqualTo(escuelaId);
	}

	@Test
	void listarAceptaLosTresEstadosYUnaBusquedaSinProcesar() throws Exception {
		when(servicio.listar(any(), any(), any(), any())).thenReturn(new Pagina<>(List.of(), 0, 20, 0));

		for (String estado : new String[] { "ACTIVOS", "INACTIVOS", "TODOS" }) {
			mvc.perform(get(RUTA).param("estado", estado).param("busqueda", "100%_x").cookie(admin()))
					.andExpect(status().isOk());
			verify(servicio).listar(any(), eq(FiltroEstado.valueOf(estado)), eq("100%_x"), any());
		}
	}

	@Test
	void unEstadoInvalidoDa400SolicitudInvalida() throws Exception {
		mvc.perform(get(RUTA).param("estado", "x").cookie(admin())).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
		mvc.perform(get(RUTA).param("estado", "activos").cookie(admin())).andExpect(status().isBadRequest());
		verifyNoInteractions(servicio);
	}

	@Test
	void elTamanioYLaPaginaSeAcotanSinDar400() throws Exception {
		when(servicio.listar(any(), any(), any(), any())).thenReturn(new Pagina<>(List.of(), 0, 100, 0));

		mvc.perform(get(RUTA).param("size", "1000").param("page", "-3").cookie(admin())).andExpect(status().isOk());
		mvc.perform(get(RUTA).param("size", "0").cookie(admin())).andExpect(status().isOk());

		ArgumentCaptor<org.springframework.data.domain.Pageable> paginacion = ArgumentCaptor
				.forClass(org.springframework.data.domain.Pageable.class);
		verify(servicio, org.mockito.Mockito.times(2)).listar(any(), any(), any(), paginacion.capture());
		assertThat(paginacion.getAllValues().get(0).getPageSize()).isEqualTo(100);
		assertThat(paginacion.getAllValues().get(0).getPageNumber()).isZero();
		assertThat(paginacion.getAllValues().get(1).getPageSize()).isEqualTo(1);
	}

	// ---------- obtener / actualizar ----------

	@Test
	void obtenerDevuelve200ConTodosLosCamposPermanentesSinEscuela() throws Exception {
		when(servicio.obtener(any(), eq(id))).thenReturn(detalle(false));

		mvc.perform(get(RUTA + "/" + id).cookie(admin())).andExpect(status().isOk())
				.andExpect(jsonPath("$.cuil").value("20301112220"))
				.andExpect(jsonPath("$.nacionalidad").value("Argentina"))
				.andExpect(jsonPath("$.otrosDatosDomicilio").value("Piso 2"))
				.andExpect(jsonPath("$.codigoPostal").value("1828"))
				.andExpect(jsonPath("$.emailFederativo").value("juan@example.com"))
				.andExpect(jsonPath("$.activo").value(false))
				.andExpect(jsonPath("$.escuelaId").doesNotExist());
		ArgumentCaptor<UsuarioAutenticado> actor = ArgumentCaptor.forClass(UsuarioAutenticado.class);
		verify(servicio).obtener(actor.capture(), eq(id));
		assertThat(actor.getValue().escuelaId()).isEqualTo(escuelaId);
	}

	@Test
	void ajenoYAleatorioDanExactamenteElMismoCuerpo404() throws Exception {
		when(servicio.obtener(any(), any())).thenThrow(DeportistaAdminService.deportistaNoEncontrado());

		String ajeno = mvc.perform(get(RUTA + "/" + id).cookie(admin())).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.codigo").value("DEPORTISTA_NO_ENCONTRADO")).andReturn().getResponse()
				.getContentAsString();
		String aleatorio = mvc.perform(get(RUTA + "/" + UUID.randomUUID()).cookie(admin()))
				.andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();

		assertThat(ajeno).isEqualTo(aleatorio);
		assertThat(ajeno).contains("El deportista no existe.");
	}

	@Test
	void unIdQueNoEsUuidDa400SolicitudInvalidaEnTodasLasRutasConId() throws Exception {
		mvc.perform(get(RUTA + "/no-es-uuid").cookie(admin())).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
		mvc.perform(cuerpo(put(RUTA + "/no-es-uuid"), admin(), MINIMO)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
		mvc.perform(conCsrf(post(RUTA + "/no-es-uuid/activar")).cookie(admin())).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
		mvc.perform(conCsrf(post(RUTA + "/no-es-uuid/desactivar")).cookie(admin())).andExpect(status().isBadRequest());
		verifyNoInteractions(servicio);
	}

	@Test
	void actualizarDevuelve200YValidaElCuerpo() throws Exception {
		when(servicio.actualizar(any(), eq(id), any(), any())).thenReturn(detalle(true));

		mvc.perform(cuerpo(put(RUTA + "/" + id), admin(), MINIMO)).andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id.toString()));
		mvc.perform(cuerpo(put(RUTA + "/" + id), admin(), conCampo("cuil", "\"20-30111222-5\"")))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.detalles[0].campo").value("cuil"));
		mvc.perform(cuerpo(put(RUTA + "/" + id), admin(), "{\"nombre\":\"Juan\",\"apellido\":\"Perez\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.detalles[0].campo").value("dni"));
	}

	@Test
	void actualizarUnDeportistaAjenoDa404() throws Exception {
		when(servicio.actualizar(any(), any(), any(), any())).thenThrow(DeportistaAdminService.deportistaNoEncontrado());

		mvc.perform(cuerpo(put(RUTA + "/" + id), admin(), MINIMO)).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.codigo").value("DEPORTISTA_NO_ENCONTRADO"));
	}

	@Test
	void elParametroEscuelaIdSeIgnoraYLaEscuelaSaleDelToken() throws Exception {
		when(servicio.obtener(any(), any())).thenReturn(detalle(true));
		when(servicio.listar(any(), any(), any(), any())).thenReturn(new Pagina<>(List.of(), 0, 20, 0));

		mvc.perform(get(RUTA + "/" + id + "?escuelaId=" + UUID.randomUUID()).cookie(admin())).andExpect(status().isOk());
		mvc.perform(get(RUTA + "?escuelaId=" + UUID.randomUUID()).cookie(admin())).andExpect(status().isOk());

		ArgumentCaptor<UsuarioAutenticado> actor = ArgumentCaptor.forClass(UsuarioAutenticado.class);
		verify(servicio).obtener(actor.capture(), any());
		verify(servicio).listar(actor.capture(), any(), any(), any());
		assertThat(actor.getAllValues()).allSatisfy(a -> assertThat(a.escuelaId()).isEqualTo(escuelaId));
	}

	// ---------- activar / desactivar ----------

	@Test
	void activarYDesactivarDevuelven200ConElDetalleDelDeportista() throws Exception {
		when(servicio.activar(any(), eq(id), any())).thenReturn(detalle(true));
		when(servicio.desactivar(any(), eq(id), any())).thenReturn(detalle(false));

		mvc.perform(conCsrf(post(RUTA + "/" + id + "/activar")).cookie(admin())).andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id.toString())).andExpect(jsonPath("$.activo").value(true))
				.andExpect(jsonPath("$.dni").value("30111222"));
		mvc.perform(conCsrf(post(RUTA + "/" + id + "/desactivar")).cookie(admin())).andExpect(status().isOk())
				.andExpect(jsonPath("$.activo").value(false)).andExpect(jsonPath("$.nombre").value("Juan"));
	}

	@Test
	void activarYDesactivarDeUnoAjenoDan404() throws Exception {
		when(servicio.activar(any(), any(), any())).thenThrow(DeportistaAdminService.deportistaNoEncontrado());
		when(servicio.desactivar(any(), any(), any())).thenThrow(DeportistaAdminService.deportistaNoEncontrado());

		mvc.perform(conCsrf(post(RUTA + "/" + id + "/activar")).cookie(admin())).andExpect(status().isNotFound());
		mvc.perform(conCsrf(post(RUTA + "/" + id + "/desactivar")).cookie(admin())).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.codigo").value("DEPORTISTA_NO_ENCONTRADO"));
	}

	// ---------- 405 ----------

	@Test
	void deleteYPatchDan405MetodoNoPermitido() throws Exception {
		mvc.perform(conCsrf(delete(RUTA + "/" + id)).cookie(admin())).andExpect(status().isMethodNotAllowed())
				.andExpect(jsonPath("$.codigo").value("METODO_NO_PERMITIDO"));
		mvc.perform(cuerpo(patch(RUTA + "/" + id), admin(), MINIMO)).andExpect(status().isMethodNotAllowed());
		mvc.perform(conCsrf(delete(RUTA)).cookie(admin())).andExpect(status().isMethodNotAllowed());
		verifyNoInteractions(servicio);
	}

	// ---------- seguridad ----------

	@Test
	void anonimoRecibe401EnTodasLasRutas() throws Exception {
		mvc.perform(get(RUTA)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
		mvc.perform(get(RUTA + "/" + id)).andExpect(status().isUnauthorized());
		mvc.perform(conCsrf(post(RUTA)).contentType(MediaType.APPLICATION_JSON).content(MINIMO))
				.andExpect(status().isUnauthorized());
		mvc.perform(conCsrf(put(RUTA + "/" + id)).contentType(MediaType.APPLICATION_JSON).content(MINIMO))
				.andExpect(status().isUnauthorized());
		mvc.perform(conCsrf(post(RUTA + "/" + id + "/activar"))).andExpect(status().isUnauthorized());
		mvc.perform(conCsrf(post(RUTA + "/" + id + "/desactivar"))).andExpect(status().isUnauthorized());
		verifyNoInteractions(servicio);
	}

	@Test
	void familiaRecibe403AccesoDenegadoEnTodasLasRutas() throws Exception {
		Cookie familia = familiaSesion();
		mvc.perform(get(RUTA).cookie(familia)).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
		mvc.perform(get(RUTA + "/" + id).cookie(familia)).andExpect(status().isForbidden());
		mvc.perform(cuerpo(post(RUTA), familia, MINIMO)).andExpect(status().isForbidden());
		mvc.perform(cuerpo(put(RUTA + "/" + id), familia, MINIMO)).andExpect(status().isForbidden());
		mvc.perform(conCsrf(post(RUTA + "/" + id + "/activar")).cookie(familia)).andExpect(status().isForbidden());
		mvc.perform(conCsrf(post(RUTA + "/" + id + "/desactivar")).cookie(familia)).andExpect(status().isForbidden());
		verifyNoInteractions(servicio);
	}

	@Test
	void lasMutacionesSinCsrfDan403CsrfInvalido() throws Exception {
		mvc.perform(post(RUTA).cookie(admin()).contentType(MediaType.APPLICATION_JSON).content(MINIMO))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
		mvc.perform(put(RUTA + "/" + id).cookie(admin()).contentType(MediaType.APPLICATION_JSON).content(MINIMO))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
		mvc.perform(post(RUTA + "/" + id + "/activar").cookie(admin())).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
		mvc.perform(post(RUTA + "/" + id + "/desactivar").cookie(admin())).andExpect(status().isForbidden());
		verifyNoInteractions(servicio);
	}

	@Test
	void laRevalidacionCentralDeSesionAplicaATodasLasRutasDeDeportistas() throws Exception {
		verificador.rechazar();
		try {
			mvc.perform(get(RUTA).cookie(admin())).andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
			mvc.perform(get(RUTA + "/" + id).cookie(admin())).andExpect(status().isUnauthorized());
			mvc.perform(cuerpo(post(RUTA), admin(), MINIMO)).andExpect(status().isUnauthorized());
			mvc.perform(cuerpo(put(RUTA + "/" + id), admin(), MINIMO)).andExpect(status().isUnauthorized());
			mvc.perform(conCsrf(post(RUTA + "/" + id + "/activar")).cookie(admin())).andExpect(status().isUnauthorized());
			mvc.perform(conCsrf(post(RUTA + "/" + id + "/desactivar")).cookie(admin()))
					.andExpect(status().isUnauthorized());
			verifyNoInteractions(servicio);

			verificador.fallarConBaseNoDisponible();
			mvc.perform(get(RUTA).cookie(admin())).andExpect(status().isServiceUnavailable())
					.andExpect(jsonPath("$.codigo").value("SERVICIO_NO_DISPONIBLE"));
			verifyNoInteractions(servicio);
		} finally {
			verificador.reiniciar();
		}
	}
}
