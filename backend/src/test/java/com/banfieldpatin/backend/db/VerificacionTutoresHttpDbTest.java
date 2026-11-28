package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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

/**
 * U21-BIS C2 por HTTP y PostgreSQL 17: bandeja de tutores por verificar y decisión de ADMIN (uno o en lote). Verificar es la
 * garantía de identidad: no aprueba documentos ni concede acceso; la familia deja de poder cambiar la identidad verificada.
 */
@Tag("db")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
		"spring.datasource.hikari.maximum-pool-size=6", "spring.datasource.hikari.minimum-idle=1",
		"banfield.autoservicio.tutores-habilitado=true", "banfield.vinculos.lock-timeout=PT0.5S" })
@AutoConfigureMockMvc
@ActiveProfiles({ "test", "e2e" })
@Import(SesionVigenteHttpDbTest.Soporte.class)
class VerificacionTutoresHttpDbTest extends BaseDbTest {

	private static final String COOKIE = "BP_SESION";
	private static final String BANDEJA = "/api/admin/tutores/por-verificar";

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	ServicioTokens tokens;
	@Autowired
	javax.sql.DataSource fuente;

	private DatosDb datos;
	private UUID escuela;
	private UUID otraEscuela;
	private UUID admin;
	private UUID fam1;
	private UUID fam2;
	private UUID madre;

	@BeforeEach
	void preparar() {
		datos = new DatosDb(jdbc);
		escuela = datos.escuela("verif-a-" + UUID.randomUUID());
		otraEscuela = datos.escuela("verif-b-" + UUID.randomUUID());
		admin = datos.admin(escuela, "admin-" + UUID.randomUUID() + "@example.invalid", true);
		fam1 = datos.familia(escuela, "Familia Uno", true);
		fam2 = datos.familia(escuela, "Familia Dos", true);
		madre = datos.usuarioFamilia(escuela, fam1, "madre-" + UUID.randomUUID() + "@example.invalid");
	}

	@AfterEach
	void limpiar() {
		jdbc.sql("DELETE FROM gestion_patin.documento WHERE escuela_id = :e").param("e", escuela).update();
		datos.limpiarEscuela(escuela);
		datos.limpiarEscuela(otraEscuela);
	}

	@Test
	void laBandejaMuestraLoDeclaradoListoParaVerificarDelMasAntiguoAlMasReciente() throws Exception {
		UUID primero = declarado(fam1, "Marta", "Diaz", "20333444", "2026-01-01T10:00:00Z");
		UUID segundo = declarado(fam1, "Jorge", "Diaz", null, "2026-01-02T10:00:00Z");
		UUID deOtraFamiliaMismoDni = datos.tutor(escuela, fam2, "Marta", "Diaz");
		jdbc.sql("UPDATE gestion_patin.tutor SET dni = '20333444' WHERE id = :t").param("t", deOtraFamiliaMismoDni).update();
		datos.tutor(escuela, fam1, "Del", "Club"); // VERIFICADO: no aparece
		UUID inactivo = declarado(fam1, "Inactivo", "Propio", null, "2026-01-03T10:00:00Z");
		datos.desactivarTutor(inactivo);
		UUID famInactiva = datos.familia(escuela, "Familia Inactiva", false);
		declarado(famInactiva, "De", "Inactiva", null, "2026-01-04T10:00:00Z");
		UUID famOtraEscuela = datos.familia(otraEscuela, "Otra", true);
		declarado(famOtraEscuela, "De", "OtraEscuela", null, "2026-01-05T10:00:00Z", datos.usuarioFamilia(otraEscuela,
				famOtraEscuela, "b-" + UUID.randomUUID() + "@example.invalid"));
		documento(primero, "DNI_TUTOR_FRENTE", "APROBADO");
		documento(primero, "DNI_TUTOR_DORSO", "CARGADO");

		mvc.perform(get(BANDEJA).cookie(sesionAdmin())).andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElementos").value(2)).andExpect(jsonPath("$.contenido.length()").value(2))
				.andExpect(jsonPath("$.contenido[0].tutorId").value(primero.toString()))
				.andExpect(jsonPath("$.contenido[0].familiaNombre").value("Familia Uno"))
				.andExpect(jsonPath("$.contenido[0].dni").value("20333444"))
				.andExpect(jsonPath("$.contenido[0].estado").value("DECLARADO"))
				.andExpect(jsonPath("$.contenido[0].origen").value("FAMILIA"))
				.andExpect(jsonPath("$.contenido[0].dniFrente").value("APROBADO"))
				.andExpect(jsonPath("$.contenido[0].dniDorso").value("CARGADO"))
				.andExpect(jsonPath("$.contenido[0].coincidenciasEnOtrasFamilias").value(1))
				.andExpect(jsonPath("$.contenido[1].tutorId").value(segundo.toString()))
				.andExpect(jsonPath("$.contenido[1].dniFrente").doesNotExist())
				.andExpect(jsonPath("$.contenido[1].coincidenciasEnOtrasFamilias").value(0));
		mvc.perform(get(BANDEJA).param("estado", "OBSERVADO").cookie(sesionAdmin())).andExpect(jsonPath("$.totalElementos").value(0));
		mvc.perform(get(BANDEJA).param("estado", "VERIFICADO").cookie(sesionAdmin())).andExpect(status().isBadRequest());
		mvc.perform(get(BANDEJA).cookie(sesionFamilia())).andExpect(status().isForbidden());
	}

	@Test
	void verificarRespaldaLaIdentidadSinAprobarDocumentosNiDarAccesoYUnDobleClicNoFalla() throws Exception {
		UUID tutor = declarado(fam1, "Marta", "Diaz", "20333444", "2026-01-01T10:00:00Z");
		UUID dni = documento(tutor, "DNI_TUTOR_FRENTE", "CARGADO");
		long vinculos = count("familia_deportista");
		String version = version(tutor);
		String pedido = "{\"decision\":\"VERIFICAR\",\"versionEsperada\":\"" + version + "\"}";

		mvc.perform(escribirAdmin(post(ruta(tutor)), pedido)).andExpect(status().isOk())
				.andExpect(jsonPath("$.estado").value("VERIFICADO")).andExpect(jsonPath("$.motivoObservacion").doesNotExist());
		Map<String, Object> fila = jdbc.sql("SELECT verificacion, verificado_por, verificado_en IS NOT NULL AS en FROM gestion_patin.tutor WHERE id = :t")
				.param("t", tutor).query().singleRow();
		assertThat(fila).containsEntry("verificacion", "VERIFICADO").containsEntry("verificado_por", admin).containsEntry("en", true);
		assertThat(jdbc.sql("SELECT estado FROM gestion_patin.documento WHERE id = :d").param("d", dni).query(String.class).single())
				.as("verificar no aprueba documentos").isEqualTo("CARGADO");
		assertThat(count("familia_deportista")).isEqualTo(vinculos);
		assertThat(count("tutor_deportista")).isZero();
		assertThat(ultimaAuditoria("TUTOR_VERIFICADO")).contains("DECLARADO", fam1.toString()).doesNotContain("Marta", "20333444");

		// El mismo pedido otra vez (doble clic, revisión vieja): mismo estado, sin otra auditoría.
		mvc.perform(escribirAdmin(post(ruta(tutor)), pedido)).andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("VERIFICADO"));
		assertThat(auditorias("TUTOR_VERIFICADO")).isEqualTo(1);
		// La familia ya no cambia la identidad verificada.
		mvc.perform(escribirFamilia(put("/api/familia/tutores/" + tutor),
				"{\"nombre\":\"Mirta\",\"apellido\":\"Diaz\",\"versionEsperada\":\"" + version(tutor) + "\"}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("IDENTIDAD_VERIFICADA"));
		mvc.perform(get(BANDEJA).cookie(sesionAdmin())).andExpect(jsonPath("$.totalElementos").value(0));
	}

	@Test
	void observarExigeMotivoLaFamiliaLoVeYAlCorregirVuelveAEstarDeclarado() throws Exception {
		UUID tutor = declarado(fam1, "Marta", "Diaz", "20333444", "2026-01-01T10:00:00Z");
		mvc.perform(escribirAdmin(post(ruta(tutor)), "{\"decision\":\"OBSERVAR\",\"versionEsperada\":\"" + version(tutor) + "\"}"))
				.andExpect(status().isBadRequest());
		mvc.perform(escribirAdmin(post(ruta(tutor)), "{\"decision\":\"OBSERVAR\",\"motivo\":\"" + "x".repeat(501)
				+ "\",\"versionEsperada\":\"" + version(tutor) + "\"}")).andExpect(status().isBadRequest());
		mvc.perform(escribirAdmin(post(ruta(tutor)), "{\"decision\":\"VERIFICAR\",\"motivo\":\"no\",\"versionEsperada\":\""
				+ version(tutor) + "\"}")).andExpect(status().isBadRequest());
		String motivo = "Revisá el apellido: en el DNI figura con tilde.";
		mvc.perform(escribirAdmin(post(ruta(tutor)), "{\"decision\":\"OBSERVAR\",\"motivo\":\"" + motivo
				+ "\",\"versionEsperada\":\"" + version(tutor) + "\"}")).andExpect(status().isOk())
				.andExpect(jsonPath("$.estado").value("OBSERVADO")).andExpect(jsonPath("$.motivoObservacion").value(motivo));
		assertThat(ultimaAuditoria("TUTOR_OBSERVADO")).doesNotContain("tilde", "apellido");
		mvc.perform(get(BANDEJA).param("estado", "OBSERVADO").cookie(sesionAdmin())).andExpect(jsonPath("$.totalElementos").value(1));

		mvc.perform(get("/api/familia/tutores").cookie(sesionFamilia()))
				.andExpect(jsonPath("$.adultos[0].estado").value("OBSERVADO"))
				.andExpect(jsonPath("$.adultos[0].motivoObservacion").value(motivo))
				.andExpect(jsonPath("$.adultos[0].identidadEditable").value(true));
		mvc.perform(escribirFamilia(put("/api/familia/tutores/" + tutor),
				"{\"nombre\":\"Marta\",\"apellido\":\"Díaz\",\"versionEsperada\":\"" + version(tutor) + "\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("DECLARADO"))
				.andExpect(jsonPath("$.motivoObservacion").doesNotExist());
		assertThat(jdbc.sql("SELECT coalesce(motivo_observacion, '-') || '/' || coalesce(verificado_por::text, '-') FROM gestion_patin.tutor WHERE id = :t")
				.param("t", tutor).query(String.class).single()).isEqualTo("-/-");
		mvc.perform(get(BANDEJA).cookie(sesionAdmin())).andExpect(jsonPath("$.totalElementos").value(1));
	}

	@Test
	void unaRevisionViejaOtraEscuelaOUnInactivoNoSeResuelven() throws Exception {
		UUID tutor = declarado(fam1, "Marta", "Diaz", null, "2026-01-01T10:00:00Z");
		String vieja = version(tutor);
		jdbc.sql("UPDATE gestion_patin.tutor SET telefono = '1', version = version + 1 WHERE id = :t").param("t", tutor).update();
		mvc.perform(escribirAdmin(post(ruta(tutor)), "{\"decision\":\"VERIFICAR\",\"versionEsperada\":\"" + vieja + "\"}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("EDICION_DESACTUALIZADA"));
		UUID famOtra = datos.familia(otraEscuela, "Otra", true);
		UUID ajeno = datos.tutor(otraEscuela, famOtra, "Ajeno", "Escuela");
		mvc.perform(escribirAdmin(post(ruta(ajeno)), "{\"decision\":\"VERIFICAR\",\"versionEsperada\":\"0\"}"))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.codigo").value("TUTOR_NO_ENCONTRADO"));
		UUID inactivo = declarado(fam1, "Inactivo", "Propio", null, "2026-01-02T10:00:00Z");
		datos.desactivarTutor(inactivo);
		mvc.perform(escribirAdmin(post(ruta(inactivo)), "{\"decision\":\"VERIFICAR\",\"versionEsperada\":\"" + version(inactivo) + "\"}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("ESTADO_INCOMPATIBLE"));
		mvc.perform(escribirFamilia(post(ruta(tutor)), "{\"decision\":\"VERIFICAR\",\"versionEsperada\":\"" + version(tutor) + "\"}"))
				.andExpect(status().isForbidden());
		assertThat(auditorias("TUTOR_VERIFICADO")).isZero();
	}

	@Test
	void elLoteResuelveCadaTutorEnSuTransaccionConSuPropioResultado() throws Exception {
		UUID ok = declarado(fam1, "Uno", "Ok", null, "2026-01-01T10:00:00Z");
		UUID viejo = declarado(fam1, "Dos", "Viejo", null, "2026-01-02T10:00:00Z");
		UUID observado = declarado(fam2, "Tres", "Observado", null, "2026-01-03T10:00:00Z");
		UUID yaVerificado = datos.tutor(escuela, fam2, "Cuatro", "DelClub");
		UUID ajeno = datos.tutor(otraEscuela, datos.familia(otraEscuela, "Otra", true), "Cinco", "Ajeno");
		String versionVieja = version(viejo);
		jdbc.sql("UPDATE gestion_patin.tutor SET telefono = '1', version = version + 1 WHERE id = :t").param("t", viejo).update();
		jdbc.sql("""
				UPDATE gestion_patin.tutor SET verificacion = 'OBSERVADO', motivo_observacion = 'falta', verificado_por = :a,
				  verificado_en = now(), version = version + 1 WHERE id = :t
				""").param("a", admin).param("t", observado).update();

		String lote = "{\"tutores\":[" + item(ok, version(ok)) + "," + item(viejo, versionVieja) + "," + item(observado, version(observado))
				+ "," + item(yaVerificado, version(yaVerificado)) + "," + item(ajeno, "0") + "]}";
		mvc.perform(escribirAdmin(post("/api/admin/tutores/verificacion-lote"), lote)).andExpect(status().isOk())
				.andExpect(jsonPath("$.resultados.length()").value(5))
				.andExpect(jsonPath("$.resultados[0].tutorId").value(ok.toString()))
				.andExpect(jsonPath("$.resultados[0].resultado").value("VERIFICADO"))
				.andExpect(jsonPath("$.resultados[0].version").value(version(ok)))
				.andExpect(jsonPath("$.resultados[1].resultado").value("EDICION_DESACTUALIZADA"))
				.andExpect(jsonPath("$.resultados[1].version").doesNotExist())
				.andExpect(jsonPath("$.resultados[2].resultado").value("ESTADO_INCOMPATIBLE"))
				.andExpect(jsonPath("$.resultados[3].resultado").value("YA_VERIFICADO"))
				.andExpect(jsonPath("$.resultados[4].resultado").value("TUTOR_NO_ENCONTRADO"));
		assertThat(estado(ok)).isEqualTo("VERIFICADO");
		assertThat(estado(viejo)).isEqualTo("DECLARADO");
		assertThat(estado(observado)).isEqualTo("OBSERVADO");
		assertThat(auditorias("TUTOR_VERIFICADO")).isEqualTo(1);
		assertThat(ultimaAuditoria("TUTOR_VERIFICADO")).contains("\"lote\": true");

		// Repetidos o más de 50: 400 sin escribir nada.
		mvc.perform(escribirAdmin(post("/api/admin/tutores/verificacion-lote"),
				"{\"tutores\":[" + item(viejo, version(viejo)) + "," + item(viejo, version(viejo)) + "]}"))
				.andExpect(status().isBadRequest());
		String cincuentaYUno = IntStream.range(0, 51).mapToObj(i -> item(UUID.randomUUID(), "0")).collect(Collectors.joining(","));
		mvc.perform(escribirAdmin(post("/api/admin/tutores/verificacion-lote"), "{\"tutores\":[" + cincuentaYUno + "]}"))
				.andExpect(status().isBadRequest());
		mvc.perform(escribirAdmin(post("/api/admin/tutores/verificacion-lote"), "{\"tutores\":[]}")).andExpect(status().isBadRequest());
		assertThat(estado(viejo)).isEqualTo("DECLARADO");
	}

	@Test
	void unaIdentidadVerificadaNoSeObservaYUnBloqueoVencidoEsUnConflictoUOcupadoEnElLote() throws Exception {
		UUID verificado = datos.tutor(escuela, fam1, "Rosa", "Lopez");
		mvc.perform(escribirAdmin(post(ruta(verificado)), "{\"decision\":\"OBSERVAR\",\"motivo\":\"revisar\",\"versionEsperada\":\""
				+ version(verificado) + "\"}")).andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("ESTADO_INCOMPATIBLE"));
		assertThat(estado(verificado)).isEqualTo("VERIFICADO");

		UUID tomado = declarado(fam1, "Tomado", "PorOtro", null, "2026-01-01T10:00:00Z");
		UUID libre = declarado(fam2, "Libre", "Sin", null, "2026-01-02T10:00:00Z");
		try (java.sql.Connection otra = fuente.getConnection()) {
			otra.setAutoCommit(false);
			try (var ps = otra.prepareStatement("SELECT 1 FROM gestion_patin.tutor WHERE id = ? FOR UPDATE")) {
				ps.setObject(1, tomado);
				ps.executeQuery();
			}
			mvc.perform(escribirAdmin(post(ruta(tomado)), "{\"decision\":\"VERIFICAR\",\"versionEsperada\":\"" + version(tomado) + "\"}"))
					.andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("CONFLICTO_CONCURRENCIA"));
			mvc.perform(escribirAdmin(post("/api/admin/tutores/verificacion-lote"),
					"{\"tutores\":[" + item(tomado, version(tomado)) + "," + item(libre, version(libre)) + "]}"))
					.andExpect(status().isOk()).andExpect(jsonPath("$.resultados[0].resultado").value("OCUPADO"))
					.andExpect(jsonPath("$.resultados[1].resultado").value("VERIFICADO"));
			otra.rollback();
		}
		assertThat(estado(tomado)).isEqualTo("DECLARADO");
		assertThat(estado(libre)).isEqualTo("VERIFICADO");
	}

	// ---------- auxiliares ----------

	private UUID declarado(UUID familia, String nombre, String apellido, String dni, String creadoEn) {
		return declarado(familia, nombre, apellido, dni, creadoEn, madre);
	}

	private UUID declarado(UUID familia, String nombre, String apellido, String dni, String creadoEn, UUID declarante) {
		UUID escuelaDe = jdbc.sql("SELECT escuela_id FROM gestion_patin.familia WHERE id = :f").param("f", familia)
				.query(UUID.class).single();
		return jdbc.sql("""
				INSERT INTO gestion_patin.tutor(escuela_id, familia_id, nombre, apellido, dni, origen, verificacion, declarado_por, creado_en)
				VALUES (:e, :f, :n, :a, :d, 'FAMILIA', 'DECLARADO', :u, CAST(:c AS timestamptz)) RETURNING id
				""").param("e", escuelaDe).param("f", familia).param("n", nombre).param("a", apellido).param("d", dni)
				.param("u", declarante).param("c", creadoEn).query(UUID.class).single();
	}

	private UUID documento(UUID tutor, String tipo, String estado) {
		UUID id = jdbc.sql("""
				INSERT INTO gestion_patin.documento(escuela_id, tutor_id, tipo, nombre_original, storage_key, mime_type, tamanio_bytes, cargado_por)
				VALUES (:e, :t, :tipo, 'sintetico.png', :k, 'image/png', 10, :u) RETURNING id
				""").param("e", escuela).param("t", tutor).param("tipo", tipo).param("k", UUID.randomUUID().toString())
				.param("u", madre).query(UUID.class).single();
		if (!"CARGADO".equals(estado)) {
			jdbc.sql("UPDATE gestion_patin.documento SET estado = :s, version = version + 1 WHERE id = :d").param("s", estado)
					.param("d", id).update();
		}
		return id;
	}

	private static String ruta(UUID tutor) {
		return "/api/admin/tutores/" + tutor + "/verificacion";
	}

	private static String item(UUID tutor, String version) {
		return "{\"tutorId\":\"" + tutor + "\",\"versionEsperada\":\"" + version + "\"}";
	}

	private MockHttpServletRequestBuilder escribirAdmin(MockHttpServletRequestBuilder req, String cuerpo) throws Exception {
		return conCsrf(req, sesionAdmin(), cuerpo);
	}

	private MockHttpServletRequestBuilder escribirFamilia(MockHttpServletRequestBuilder req, String cuerpo) throws Exception {
		return conCsrf(req, sesionFamilia(), cuerpo);
	}

	private MockHttpServletRequestBuilder conCsrf(MockHttpServletRequestBuilder req, Cookie sesion, String cuerpo) throws Exception {
		Cookie x = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		return req.cookie(x, sesion).header("X-XSRF-TOKEN", x.getValue()).contentType(MediaType.APPLICATION_JSON).content(cuerpo);
	}

	private Cookie sesionAdmin() {
		return new Cookie(COOKIE, tokens.emitir(admin, Rol.ADMIN, escuela, null, versionSesion(admin)));
	}

	private Cookie sesionFamilia() {
		return new Cookie(COOKIE, tokens.emitir(madre, Rol.FAMILIA, escuela, fam1, versionSesion(madre)));
	}

	private long versionSesion(UUID usuario) {
		return jdbc.sql("SELECT version_sesion FROM gestion_patin.usuario WHERE id = :u").param("u", usuario).query(Long.class)
				.single();
	}

	private String version(UUID tutor) {
		return Long.toString(jdbc.sql("SELECT version FROM gestion_patin.tutor WHERE id = :t").param("t", tutor)
				.query(Long.class).single());
	}

	private String estado(UUID tutor) {
		return jdbc.sql("SELECT verificacion FROM gestion_patin.tutor WHERE id = :t").param("t", tutor).query(String.class)
				.single();
	}

	private long count(String tabla) {
		return jdbc.sql("SELECT count(*) FROM gestion_patin." + tabla + " WHERE escuela_id = :e").param("e", escuela)
				.query(Long.class).single();
	}

	private long auditorias(String accion) {
		return jdbc.sql("SELECT count(*) FROM gestion_patin.auditoria WHERE escuela_id = :e AND accion = :a").param("e", escuela)
				.param("a", accion).query(Long.class).single();
	}

	private String ultimaAuditoria(String accion) {
		return jdbc.sql("SELECT detalle::text FROM gestion_patin.auditoria WHERE escuela_id = :e AND accion = :a ORDER BY id DESC LIMIT 1")
				.param("e", escuela).param("a", accion).query(String.class).single();
	}
}
