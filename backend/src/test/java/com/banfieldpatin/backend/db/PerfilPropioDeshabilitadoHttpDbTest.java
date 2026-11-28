package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.banfieldpatin.backend.seguridad.ServicioTokens;
import com.banfieldpatin.backend.usuarios.Rol;

import jakarta.servlet.http.Cookie;

/** Con las banderas apagadas (valor por defecto) la familia solo puede consultar: nada se escribe y no se lee nada antes de rechazar. */
@Tag("db")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
		"spring.datasource.hikari.maximum-pool-size=3", "spring.datasource.hikari.minimum-idle=1" })
@AutoConfigureMockMvc
@ActiveProfiles({ "test", "e2e" })
@Import(SesionVigenteHttpDbTest.Soporte.class)
class PerfilPropioDeshabilitadoHttpDbTest extends BaseDbTest {

	private static final String RUTA = "/api/familia/mi-perfil";
	private static final String CUERPO = "{\"nombre\":\"Laura\",\"apellido\":\"Gomez\"}";

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	ServicioTokens tokens;

	private DatosDb datos;
	private UUID escuela;
	private UUID familia;
	private UUID usuario;

	@BeforeEach
	void preparar() {
		datos = new DatosDb(jdbc);
		escuela = datos.escuela("perfil-off-" + UUID.randomUUID());
		familia = datos.familia(escuela, "Familia", true);
		usuario = datos.usuarioFamilia(escuela, familia, "off-" + UUID.randomUUID() + "@example.invalid");
		datos.tutor(escuela, familia, "Persona", "Libre");
	}

	@AfterEach
	void limpiar() {
		datos.limpiarEscuela(escuela);
	}

	@Test
	void consultarFuncionaYInformaQueNoEstaHabilitadoPeroEscribirDa503SinTocarNada() throws Exception {
		mvc.perform(get(RUTA).cookie(sesion())).andExpect(status().isOk()).andExpect(jsonPath("$.habilitado").value(false))
				.andExpect(jsonPath("$.personasSinCuenta.length()").value(1));
		mvc.perform(escribir(post(RUTA), CUERPO)).andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.codigo").value("AUTOSERVICIO_PERFIL_DESHABILITADO"));
		mvc.perform(escribir(put(RUTA), CUERPO.replace("}", ",\"versionEsperada\":\"0\"}"))).andExpect(status().isServiceUnavailable());
		mvc.perform(escribir(post(RUTA + "/reclamar-tutor"), "{\"tutorId\":\"" + UUID.randomUUID() + "\",\"versionEsperada\":\"0\"}"))
				.andExpect(status().isServiceUnavailable());
		assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.tutor WHERE escuela_id = :e AND usuario_id IS NOT NULL").param("e", escuela)
				.query(Long.class).single()).isZero();
		assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.auditoria WHERE escuela_id = :e AND accion IN ('PERFIL_DECLARADO','PERFIL_ACTUALIZADO','TUTOR_RECLAMADO')")
				.param("e", escuela).query(Long.class).single()).isZero();
	}

	/** U21-BIS C2 con su bandera apagada (por defecto): los adultos se consultan, cargar y editar da 503 sin escribir nada. */
	@Test
	void losAdultosDeLaFamiliaSeConsultanPeroCargarYEditarDa503SinTocarNada() throws Exception {
		UUID libre = jdbc.sql("SELECT id FROM gestion_patin.tutor WHERE escuela_id = :e").param("e", escuela).query(UUID.class).single();
		mvc.perform(get("/api/familia/tutores").cookie(sesion())).andExpect(status().isOk())
				.andExpect(jsonPath("$.habilitado").value(false)).andExpect(jsonPath("$.adultos.length()").value(1));
		mvc.perform(escribir(post("/api/familia/tutores"), CUERPO).header("Idempotency-Key", UUID.randomUUID().toString()))
				.andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.codigo").value("AUTOSERVICIO_TUTORES_DESHABILITADO"));
		mvc.perform(escribir(put("/api/familia/tutores/" + libre), CUERPO.replace("}", ",\"versionEsperada\":\"0\"}")))
				.andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.codigo").value("AUTOSERVICIO_TUTORES_DESHABILITADO"));
		assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.tutor WHERE escuela_id = :e").param("e", escuela).query(Long.class)
				.single()).isEqualTo(1);
		assertThat(jdbc.sql("SELECT nombre FROM gestion_patin.tutor WHERE id = :t").param("t", libre).query(String.class).single())
				.isEqualTo("Persona");
		assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.auditoria WHERE escuela_id = :e AND accion IN ('TUTOR_DECLARADO','TUTOR_ACTUALIZADO_POR_FAMILIA')")
				.param("e", escuela).query(Long.class).single()).isZero();
	}

	private Cookie sesion() {
		long v = jdbc.sql("SELECT version_sesion FROM gestion_patin.usuario WHERE id = :u").param("u", usuario).query(Long.class).single();
		return new Cookie("BP_SESION", tokens.emitir(usuario, Rol.FAMILIA, escuela, familia, v));
	}

	private MockHttpServletRequestBuilder escribir(MockHttpServletRequestBuilder req, String cuerpo) throws Exception {
		Cookie x = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		return req.cookie(x, sesion()).header("X-XSRF-TOKEN", x.getValue()).contentType(MediaType.APPLICATION_JSON).content(cuerpo);
	}
}
