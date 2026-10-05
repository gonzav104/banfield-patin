package com.banfieldpatin.backend.familias;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.banfieldpatin.backend.FixturesDominio;
import com.banfieldpatin.backend.compartido.RelojConfig;
import com.banfieldpatin.backend.compartido.error.ManejadorGlobalErrores;
import com.banfieldpatin.backend.seguridad.CookieBearerTokenResolver;
import com.banfieldpatin.backend.seguridad.CookieSesion;
import com.banfieldpatin.backend.seguridad.JwtConfig;
import com.banfieldpatin.backend.seguridad.ManejadorAccesoDenegadoJson;
import com.banfieldpatin.backend.seguridad.PuntoEntradaJson;
import com.banfieldpatin.backend.seguridad.SeguridadConfig;
import com.banfieldpatin.backend.seguridad.ServicioTokens;
import com.banfieldpatin.backend.seguridad.SesionVigenteDePrueba;
import com.banfieldpatin.backend.usuarios.Rol;

import jakarta.servlet.http.Cookie;

@WebMvcTest(controllers = FamiliaAdminController.class)
@Import({ SeguridadConfig.class, JwtConfig.class, CookieSesion.class, CookieBearerTokenResolver.class,
		PuntoEntradaJson.class, ManejadorAccesoDenegadoJson.class, ManejadorGlobalErrores.class,
		ServicioTokens.class, RelojConfig.class, SesionVigenteDePrueba.class })
@ActiveProfiles("test")
class FamiliaAdminControllerWebMvcTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	ServicioTokens tokens;
	@MockitoBean
	FamiliaRepository familias;

	private final UUID escuelaId = UUID.randomUUID();

	private Cookie admin() {
		return new Cookie("BP_SESION", tokens.emitir(UUID.randomUUID(), Rol.ADMIN, escuelaId, null));
	}

	private Cookie familia() {
		return new Cookie("BP_SESION",
				tokens.emitir(UUID.randomUUID(), Rol.FAMILIA, escuelaId, UUID.randomUUID()));
	}

	private Pageable pedirConParametros(String query) throws Exception {
		when(familias.buscarActivas(any(), any(), any())).thenReturn(new PageImpl<>(List.of()));
		mvc.perform(get("/api/admin/familias" + query).cookie(admin())).andExpect(status().isOk());
		ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
		verify(familias).buscarActivas(eq(escuelaId), any(), captor.capture());
		return captor.getValue();
	}

	@Test
	void adminListaSoloIdYNombreDeSuEscuela() throws Exception {
		UUID id = UUID.randomUUID();
		when(familias.buscarActivas(eq(escuelaId), eq(""), any()))
				.thenReturn(new PageImpl<>(List.of(FixturesDominio.familia(id, escuelaId, true))));

		mvc.perform(get("/api/admin/familias").cookie(admin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.contenido[0].id").value(id.toString()))
				.andExpect(jsonPath("$.contenido[0].nombreReferencia").value("Familia Prueba"))
				.andExpect(jsonPath("$.contenido[0].escuelaId").doesNotExist())
				.andExpect(jsonPath("$.contenido[0].activa").doesNotExist())
				.andExpect(jsonPath("$.totalElementos").value(1));
	}

	@Test
	void laEscuelaSaleDelTokenNoDeLosParametros() throws Exception {
		when(familias.buscarActivas(any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

		mvc.perform(get("/api/admin/familias?escuelaId=" + UUID.randomUUID()).cookie(admin()))
				.andExpect(status().isOk());

		verify(familias).buscarActivas(eq(escuelaId), any(), any());
	}

	@Test
	void familiaRecibe403() throws Exception {
		mvc.perform(get("/api/admin/familias").cookie(familia()))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
		verifyNoInteractions(familias);
	}

	@Test
	void anonimoRecibe401() throws Exception {
		mvc.perform(get("/api/admin/familias"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
		verifyNoInteractions(familias);
	}

	@Test
	void elTamanioSeAcotaSiempreIgual() throws Exception {
		assertThat(pedirConParametros("?size=1000").getPageSize()).isEqualTo(100);
	}

	@Test
	void tamanioYPaginaInvalidosSeNormalizan() throws Exception {
		Pageable p = pedirConParametros("?size=0&page=-5");
		assertThat(p.getPageSize()).isEqualTo(1);
		assertThat(p.getPageNumber()).isZero();
	}

	@Test
	void porDefectoPagina0Tamanio20() throws Exception {
		Pageable p = pedirConParametros("");
		assertThat(p.getPageNumber()).isZero();
		assertThat(p.getPageSize()).isEqualTo(20);
	}

	@Test
	void losComodinesDeLikeSeEscapanEnLaBusqueda() throws Exception {
		when(familias.buscarActivas(any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

		mvc.perform(get("/api/admin/familias").param("busqueda", "  50%_! ").cookie(admin()))
				.andExpect(status().isOk());

		verify(familias).buscarActivas(eq(escuelaId), eq("50!%!_!!"), any());
	}

	@Test
	void parametroNumericoInvalidoDa400ConElFormatoUniforme() throws Exception {
		mvc.perform(get("/api/admin/familias?page=abc").cookie(admin()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
	}
}
