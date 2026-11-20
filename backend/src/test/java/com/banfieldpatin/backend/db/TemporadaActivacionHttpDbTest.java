package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.banfieldpatin.backend.seguridad.ServicioTokens;
import com.banfieldpatin.backend.usuarios.Rol;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.json.JsonMapper;

/**
 * FE-05/U13 por HTTP REAL (cadena de seguridad, controlador, manejador de errores y la configuracion real de Jackson, con
 * PostgreSQL descartable): el cuerpo {@code {"activaEsperadaId": uuid|null}} es obligatorio, una intencion desactualizada
 * da 409 EDICION_DESACTUALIZADA sin escribir nada, y la autorizacion (401/403/CSRF) se decide antes de mirar el cuerpo.
 */
@Tag("db")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
		"spring.datasource.hikari.maximum-pool-size=3", "spring.datasource.hikari.minimum-idle=1" })
@AutoConfigureMockMvc
@ActiveProfiles({ "test", "e2e" })
@Import(SesionVigenteHttpDbTest.Soporte.class)
class TemporadaActivacionHttpDbTest extends BaseDbTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	ServicioTokens tokens;

	private DatosDb datos;
	private UUID escuela;
	private UUID adminId;
	private UUID familiaId;
	private UUID usuarioFamiliaId;

	@BeforeEach
	void preparar() {
		datos = new DatosDb(jdbc);
		escuela = datos.escuela("activ-" + UUID.randomUUID());
		adminId = datos.admin(escuela, "admin@activ.example", true);
		familiaId = datos.familia(escuela, "Familia", true);
		usuarioFamiliaId = datos.usuarioFamilia(escuela, familiaId, "fam@activ.example");
	}

	@AfterEach
	void limpiar() {
		datos.limpiarEscuela(escuela);
	}

	private MockHttpServletResponse activar(UUID temporada, String cuerpo) throws Exception {
		Cookie xsrf = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		Cookie sesion = new Cookie("BP_SESION", tokens.emitir(adminId, Rol.ADMIN, escuela, null));
		var req = post("/api/admin/temporadas/" + temporada + "/activar").cookie(xsrf, sesion)
				.header("X-XSRF-TOKEN", xsrf.getValue());
		if (cuerpo != null) {
			req = req.contentType(MediaType.APPLICATION_JSON).content(cuerpo);
		}
		return mvc.perform(req).andReturn().getResponse();
	}

	private String codigo(MockHttpServletResponse r) throws Exception {
		return new JsonMapper().readTree(r.getContentAsString()).path("codigo").asString("");
	}

	private java.util.List<UUID> activas() {
		return jdbc.sql("SELECT id FROM gestion_patin.temporada WHERE escuela_id = :e AND activa").param("e", escuela)
				.query(UUID.class).list();
	}

	private long auditoriasDeActivacion() {
		return jdbc.sql("SELECT count(*) FROM gestion_patin.auditoria WHERE escuela_id = :e AND accion = 'TEMPORADA_ACTIVADA'")
				.param("e", escuela).query(Long.class).single();
	}

	@Test
	void elCuerpoEsObligatorioYTodoLoMalformadoDa400SinEscribirNada() throws Exception {
		UUID t = datos.temporada(escuela, 2027, false);
		Map<String, String> casos = new LinkedHashMap<>();
		casos.put("<sin cuerpo>", null);
		casos.put("{}", "{}");
		casos.put("{\"activaEsperadaId\":\"no-es-uuid\"}", "{\"activaEsperadaId\":\"no-es-uuid\"}");
		casos.put("{\"activaEsperadaId\":123}", "{\"activaEsperadaId\":123}");
		casos.put("{\"activaEsperadaId\":true}", "{\"activaEsperadaId\":true}");
		casos.put("{\"activaEsperadaId\":[]}", "{\"activaEsperadaId\":[]}");
		casos.put("propiedad ajena", "{\"activaEsperadaId\":null,\"escuelaId\":\"" + UUID.randomUUID() + "\"}");
		casos.put("versionEsperada ajena", "{\"activaEsperadaId\":null,\"versionEsperada\":\"1\"}");

		for (Map.Entry<String, String> caso : casos.entrySet()) {
			MockHttpServletResponse r = activar(t, caso.getValue());
			assertThat(r.getStatus()).as(caso.getKey()).isEqualTo(400);
			assertThat(codigo(r)).as(caso.getKey()).isIn("SOLICITUD_INVALIDA", "VALIDACION");
		}
		assertThat(activas()).isEmpty();
		assertThat(auditoriasDeActivacion()).isZero();
	}

	@Test
	void conLaIntencionCorrectaActivaYUnaIntencionViejaDa409SinEscribirNiAuditar() throws Exception {
		UUID a = datos.temporada(escuela, 2026, false);
		UUID b = datos.temporada(escuela, 2027, false);
		UUID c = datos.temporada(escuela, 2028, false);

		// Sin ninguna activa: null es la intencion correcta; un id no.
		assertThat(activar(a, "{\"activaEsperadaId\":\"" + UUID.randomUUID() + "\"}").getStatus()).isEqualTo(409);
		assertThat(activas()).isEmpty();
		assertThat(activar(a, "{\"activaEsperadaId\":null}").getStatus()).isEqualTo(200);
		assertThat(activas()).containsExactly(a);
		// Otro admin activa B observando A; el primero confirma C creyendo que sigue A.
		assertThat(activar(b, "{\"activaEsperadaId\":\"" + a + "\"}").getStatus()).isEqualTo(200);
		MockHttpServletResponse vieja = activar(c, "{\"activaEsperadaId\":\"" + a + "\"}");

		assertThat(vieja.getStatus()).isEqualTo(409);
		assertThat(codigo(vieja)).isEqualTo("EDICION_DESACTUALIZADA");
		assertThat(vieja.getContentAsString()).doesNotContain(a.toString()).doesNotContain(b.toString());
		assertThat(activas()).containsExactly(b);
		assertThat(auditoriasDeActivacion()).as("A y B; C no").isEqualTo(2);
		// Idempotencia: la que ya esta activa responde 200 aunque la intencion este vieja.
		assertThat(activar(b, "{\"activaEsperadaId\":\"" + a + "\"}").getStatus()).isEqualTo(200);
		assertThat(auditoriasDeActivacion()).isEqualTo(2);
	}

	@Test
	void unaTemporadaInexistenteOAjenaDa404ConCualquierIntencion() throws Exception {
		UUID otraEscuela = datos.escuela("activ-otra-" + UUID.randomUUID());
		try {
			UUID ajena = datos.temporada(otraEscuela, 2027, false);

			assertThat(activar(ajena, "{\"activaEsperadaId\":null}").getStatus()).isEqualTo(404);
			assertThat(activar(UUID.randomUUID(), "{\"activaEsperadaId\":null}").getStatus()).isEqualTo(404);
			assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.temporada WHERE escuela_id = :e AND activa")
					.param("e", otraEscuela).query(Long.class).single()).isZero();
		} finally {
			datos.limpiarEscuela(otraEscuela);
		}
	}

	@Test
	void sinSesionDa401UnaFamiliaDa403YSinCsrfDa403AntesDeMirarElCuerpo() throws Exception {
		UUID t = datos.temporada(escuela, 2027, false);
		Cookie xsrf = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		String cuerpo = "{\"activaEsperadaId\":null}";

		int sinSesion = mvc.perform(post("/api/admin/temporadas/" + t + "/activar").cookie(xsrf)
				.header("X-XSRF-TOKEN", xsrf.getValue()).contentType(MediaType.APPLICATION_JSON).content(cuerpo))
				.andReturn().getResponse().getStatus();
		Cookie sesionFamilia = new Cookie("BP_SESION", tokens.emitir(usuarioFamiliaId, Rol.FAMILIA, escuela, familiaId));
		int comoFamilia = mvc.perform(post("/api/admin/temporadas/" + t + "/activar").cookie(xsrf, sesionFamilia)
				.header("X-XSRF-TOKEN", xsrf.getValue()).contentType(MediaType.APPLICATION_JSON).content(cuerpo))
				.andReturn().getResponse().getStatus();
		Cookie sesionAdmin = new Cookie("BP_SESION", tokens.emitir(adminId, Rol.ADMIN, escuela, null));
		int sinCsrf = mvc.perform(post("/api/admin/temporadas/" + t + "/activar").cookie(sesionAdmin)
				.contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus();

		assertThat(sinSesion).isEqualTo(401);
		assertThat(comoFamilia).isEqualTo(403);
		assertThat(sinCsrf).isEqualTo(403);
		assertThat(activas()).isEmpty();
	}
}
