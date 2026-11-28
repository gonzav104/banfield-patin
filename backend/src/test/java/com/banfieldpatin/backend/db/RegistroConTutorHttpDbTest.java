package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import jakarta.servlet.http.Cookie;

/**
 * El registro REAL por invitación (POST /api/auth/registro/invitacion, sin cuenta previa) cuando la invitación lleva un tutor
 * concreto (U21-BIS C1): la cuenta nace vinculada y VERIFICADA a esa persona, sin permisos nuevos; si otra persona la reclamó
 * mientras tanto, el registro termina igual (la persona queda registrada) sin vínculo, y la auditoría lo dice.
 */
@Tag("db")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles({ "test", "e2e" })
class RegistroConTutorHttpDbTest extends BaseDbTest {

	private static final String SLUG = "registro-tutor-" + UUID.randomUUID();
	private static final String CLAVE = "clave-larga-de-prueba-123";

	@DynamicPropertySource
	static void configuracion(DynamicPropertyRegistry registro) {
		registro.add("banfield.escuela.slug", () -> SLUG);
	}

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	com.banfieldpatin.backend.seguridad.ServicioTokens tokens;

	private DatosDb datos;
	private UUID escuela;
	private UUID admin;
	private UUID familia;

	@BeforeEach
	void preparar() {
		datos = new DatosDb(jdbc);
		escuela = datos.escuelaPorSlugOCrear(SLUG);
		admin = datos.admin(escuela, "admin-" + UUID.randomUUID() + "@example.invalid", true);
		familia = datos.familia(escuela, "Familia Registro", true);
	}

	/** Solo el contenido: la escuela vigente se resuelve por slug y su id queda en caché. */
	@AfterEach
	void limpiar() {
		datos.limpiarContenido(escuela);
	}

	@Test
	void laCuentaQueSeRegistraConUnaInvitacionAUnTutorQuedaVinculadaYVerificadaSinPermisosNuevos() throws Exception {
		UUID tutor = datos.tutor(escuela, familia, "Laura", "Gomez");
		String token = invitacion(tutor);
		String email = "laura-" + UUID.randomUUID() + "@example.invalid";

		registrar(token, email).andExpect(status().isCreated());

		UUID usuario = jdbc.sql("SELECT id FROM gestion_patin.usuario WHERE lower(email) = :e").param("e", email).query(UUID.class).single();
		Map<String, Object> fila = jdbc.sql("SELECT usuario_id, vinculo_cuenta, declarado_por, verificacion, origen, nombre FROM gestion_patin.tutor WHERE id = :t")
				.param("t", tutor).query().singleRow();
		assertThat(fila).containsEntry("usuario_id", usuario).containsEntry("vinculo_cuenta", "VERIFICADO")
				.containsEntry("declarado_por", admin).containsEntry("origen", "ADMIN").containsEntry("nombre", "Laura");
		// La cuenta es de la familia invitada, con el rol de siempre, y no ganó ningún acceso a deportistas.
		assertThat(jdbc.sql("SELECT rol, familia_id FROM gestion_patin.usuario WHERE id = :u").param("u", usuario).query().singleRow())
				.containsEntry("rol", "FAMILIA").containsEntry("familia_id", familia);
		assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.familia_deportista WHERE escuela_id = :e").param("e", escuela).query(Long.class)
				.single()).isZero();
		assertThat(jdbc.sql("SELECT detalle::text FROM gestion_patin.auditoria WHERE accion = 'REGISTRO_POR_INVITACION' AND usuario_id = :u")
				.param("u", usuario).query(String.class).single()).contains("\"tutorVinculado\": true");
	}

	@Test
	void siOtraPersonaReclamoElTutorElRegistroTerminaIgualSinVinculoYLaAuditoriaLoDice() throws Exception {
		UUID tutor = datos.tutor(escuela, familia, "Laura", "Gomez");
		String token = invitacion(tutor);
		UUID otro = datos.usuarioFamilia(escuela, familia, "otro-" + UUID.randomUUID() + "@example.invalid");
		jdbc.sql("UPDATE gestion_patin.tutor SET usuario_id = :u, vinculo_cuenta = 'DECLARADO', declarado_por = :u WHERE id = :t")
				.param("u", otro).param("t", tutor).update();
		String email = "nueva-" + UUID.randomUUID() + "@example.invalid";

		registrar(token, email).andExpect(status().isCreated());

		UUID usuario = jdbc.sql("SELECT id FROM gestion_patin.usuario WHERE lower(email) = :e").param("e", email).query(UUID.class).single();
		assertThat(jdbc.sql("SELECT usuario_id FROM gestion_patin.tutor WHERE id = :t").param("t", tutor).query(UUID.class).single()).isEqualTo(otro);
		assertThat(jdbc.sql("SELECT detalle::text FROM gestion_patin.auditoria WHERE accion = 'REGISTRO_POR_INVITACION' AND usuario_id = :u")
				.param("u", usuario).query(String.class).single()).contains("\"tutorVinculado\": false");
	}

	/**
	 * La invitación dirigida decide la ASOCIACIÓN de la cuenta, no la verificación de los datos del tutor: un tutor DECLARADO sigue
	 * DECLARADO. Y ADMIN ve en el listado, sin mirar auditorías, si la persona invitada quedó asociada o no.
	 */
	@Test
	void laAsociacionDeLaCuentaNoCambiaLaVerificacionDelTutorYAdminVeCuandoQuedoSinAsociar() throws Exception {
		UUID declarado = datos.tutor(escuela, familia, "Declarado", "Gomez");
		jdbc.sql("UPDATE gestion_patin.tutor SET verificacion = 'DECLARADO', origen = 'FAMILIA' WHERE id = :t").param("t", declarado).update();
		UUID reclamado = datos.tutor(escuela, familia, "Reclamado", "Gomez");
		String t1 = invitacion(declarado);
		String t2 = invitacion(reclamado);
		// Una cuenta de la familia reclama a la segunda persona ANTES de que se acepte su invitación.
		UUID quienReclama = datos.usuarioFamilia(escuela, familia, "reclama-" + UUID.randomUUID() + "@example.invalid");
		jdbc.sql("UPDATE gestion_patin.tutor SET usuario_id = :u, vinculo_cuenta = 'DECLARADO', declarado_por = :u WHERE id = :t")
				.param("u", quienReclama).param("t", reclamado).update();

		registrar(t1, "uno-" + UUID.randomUUID() + "@example.invalid").andExpect(status().isCreated());
		registrar(t2, "dos-" + UUID.randomUUID() + "@example.invalid").andExpect(status().isCreated());

		assertThat(jdbc.sql("SELECT vinculo_cuenta || '/' || verificacion FROM gestion_patin.tutor WHERE id = :t").param("t", declarado)
				.query(String.class).single()).isEqualTo("VERIFICADO/DECLARADO");
		// Quien reclamó antes no sube de categoría ni gana nada por la invitación ajena.
		assertThat(jdbc.sql("SELECT usuario_id || '/' || vinculo_cuenta || '/' || verificacion FROM gestion_patin.tutor WHERE id = :t").param("t", reclamado)
				.query(String.class).single()).isEqualTo(quienReclama + "/DECLARADO/VERIFICADO");
		assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.familia_deportista WHERE escuela_id = :e").param("e", escuela).query(Long.class).single()).isZero();

		Cookie admin = new Cookie("BP_SESION", tokens.emitir(this.admin, com.banfieldpatin.backend.usuarios.Rol.ADMIN, escuela, null,
				jdbc.sql("SELECT version_sesion FROM gestion_patin.usuario WHERE id = :u").param("u", this.admin).query(Long.class).single()));
		String lista = mvc.perform(get("/api/admin/invitaciones").param("familiaId", familia.toString()).cookie(admin)).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertThat(lista).contains("\"asociacionDePersona\":\"ASOCIADA\"").contains("\"asociacionDePersona\":\"NO_ASOCIADA\"");
	}

	private org.springframework.test.web.servlet.ResultActions registrar(String token, String email) throws Exception {
		Cookie x = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		String cuerpo = "{\"token\":\"" + token + "\",\"nombre\":\"Laura\",\"apellido\":\"Gomez\",\"email\":\"" + email + "\",\"password\":\"" + CLAVE + "\"}";
		return mvc.perform(post("/api/auth/registro/invitacion").cookie(x).header("X-XSRF-TOKEN", x.getValue())
				.contentType(MediaType.APPLICATION_JSON).content(cuerpo));
	}

	/** Una invitación pendiente de la familia que apunta al tutor; devuelve el token en claro (en la base solo va su SHA-256). */
	private String invitacion(UUID tutor) throws Exception {
		// Formato del token real: 43 caracteres base64url (256 bits).
		String token = (UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")).substring(0, 43);
		String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		jdbc.sql("""
				INSERT INTO gestion_patin.invitacion (escuela_id, familia_id, tutor_id, token_hash, expira_en, creada_por, creado_en)
				VALUES (:e, :f, :t, :h, now() + interval '1 day', :a, now())
				""").param("e", escuela).param("f", familia).param("t", tutor).param("h", hash).param("a", admin).update();
		return token;
	}
}
