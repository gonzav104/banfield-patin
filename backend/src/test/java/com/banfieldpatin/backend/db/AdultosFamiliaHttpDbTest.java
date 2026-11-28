package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.banfieldpatin.backend.seguridad.ServicioTokens;
import com.banfieldpatin.backend.usuarios.Rol;

import jakarta.servlet.http.Cookie;

/**
 * U21-BIS C2 por HTTP con la cadena de seguridad completa y PostgreSQL 17: la familia carga y edita a otros adultos de SU
 * familia. Dos familias y otra escuela para el aislamiento; tope de 3 adultos cargados por la familia para probar el límite.
 * Declarar no autoriza: ningún camino crea vínculos, responsabilidades ni cuentas.
 */
@Tag("db")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
		"spring.datasource.hikari.maximum-pool-size=6", "spring.datasource.hikari.minimum-idle=1",
		"banfield.autoservicio.perfil-habilitado=true", "banfield.autoservicio.tutores-habilitado=true",
		"banfield.autoservicio.tope-tutores-por-familia=3" })
@AutoConfigureMockMvc
@ActiveProfiles({ "test", "e2e" })
@Import(SesionVigenteHttpDbTest.Soporte.class)
class AdultosFamiliaHttpDbTest extends BaseDbTest {

	private static final String COOKIE = "BP_SESION";
	private static final String RUTA = "/api/familia/tutores";
	private static final String ABUELA = "{\"nombre\":\"Marta\",\"apellido\":\"Diaz\",\"dni\":\"20.333.444\","
			+ "\"telefono\":\"11 4000-0000\",\"email\":\"Marta@Example.Test\",\"parentesco\":\"Abuela\"}";

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	ServicioTokens tokens;
	@Autowired
	DataSource fuente;

	private DatosDb datos;
	private UUID escuela;
	private UUID otraEscuela;
	private UUID admin;
	private UUID fam1;
	private UUID fam2;
	private UUID famOtraEscuela;
	private UUID madre;
	private UUID padre;
	private UUID deFam2;
	private UUID deportista;

	@BeforeEach
	void preparar() {
		datos = new DatosDb(jdbc);
		escuela = datos.escuela("adultos-a-" + UUID.randomUUID());
		otraEscuela = datos.escuela("adultos-b-" + UUID.randomUUID());
		admin = datos.admin(escuela, "admin-" + UUID.randomUUID() + "@example.invalid", true);
		fam1 = datos.familia(escuela, "Familia Uno", true);
		fam2 = datos.familia(escuela, "Familia Dos", true);
		famOtraEscuela = datos.familia(otraEscuela, "Familia Otra Escuela", true);
		madre = datos.usuarioFamilia(escuela, fam1, "madre-" + UUID.randomUUID() + "@example.invalid");
		padre = datos.usuarioFamilia(escuela, fam1, "padre-" + UUID.randomUUID() + "@example.invalid");
		deFam2 = datos.usuarioFamilia(escuela, fam2, "dos-" + UUID.randomUUID() + "@example.invalid");
		deportista = datos.deportista(escuela, "ADU" + UUID.randomUUID().toString().substring(0, 8), "Nina", "Prueba");
		datos.vinculo(escuela, fam1, deportista, "ACTIVO", true, admin);
	}

	@AfterEach
	void limpiar() {
		jdbc.sql("DELETE FROM gestion_patin.documento WHERE escuela_id = :e").param("e", escuela).update();
		datos.limpiarEscuela(escuela);
		datos.limpiarEscuela(otraEscuela);
	}

	// ---------- alta ----------

	@Test
	void cargarUnAdultoLoDejaDeclaradoSinCuentaSinPermisosYSinFiltrarDatos() throws Exception {
		long vinculos = count("familia_deportista");
		long usuarios = count("usuario");
		MockHttpServletResponse r = mvc.perform(escribir(post(RUTA), ABUELA, madre, fam1).header("Idempotency-Key", clave()))
				.andExpect(status().isCreated()).andExpect(header().doesNotExist("Location"))
				.andExpect(jsonPath("$.estado").value("DECLARADO")).andExpect(jsonPath("$.email").value("marta@example.test"))
				.andExpect(jsonPath("$.esMiPerfil").value(false)).andExpect(jsonPath("$.tieneCuentaPropia").value(false))
				.andExpect(jsonPath("$.editable").value(true)).andExpect(jsonPath("$.identidadEditable").value(true))
				.andExpect(jsonPath("$.dniEnmascarado").exists()).andExpect(jsonPath("$.dni").doesNotExist())
				.andReturn().getResponse();
		assertThat(r.getHeader("Cache-Control")).contains("no-store");
		assertThat(r.getContentAsString()).doesNotContain("20333444").doesNotContain("20.333.444");

		Map<String, Object> fila = jdbc.sql("""
				SELECT origen, verificacion, usuario_id, vinculo_cuenta, declarado_por, familia_id, dni, verificado_por
				FROM gestion_patin.tutor WHERE escuela_id = :e AND apellido = 'Diaz'
				""").param("e", escuela).query().singleRow();
		assertThat(fila).containsEntry("origen", "FAMILIA").containsEntry("verificacion", "DECLARADO")
				.containsEntry("usuario_id", null).containsEntry("vinculo_cuenta", null).containsEntry("declarado_por", madre)
				.containsEntry("familia_id", fam1).containsEntry("dni", "20333444").containsEntry("verificado_por", null);
		// Declarar no autoriza: ni vínculos, ni responsabilidades, ni cuentas nuevas.
		assertThat(count("familia_deportista")).isEqualTo(vinculos);
		assertThat(count("tutor_deportista")).isZero();
		assertThat(count("usuario")).isEqualTo(usuarios);
		assertThat(ultimaAuditoria("TUTOR_DECLARADO")).contains(fam1.toString()).doesNotContain("Marta", "Diaz", "20333444",
				"marta@example.test", "4000-0000");

		// Lo ve toda la familia (también el padre), con los datos de contacto y el DNI enmascarado.
		mvc.perform(get(RUTA).cookie(sesion(padre, fam1))).andExpect(status().isOk())
				.andExpect(jsonPath("$.habilitado").value(true)).andExpect(jsonPath("$.tope").value(3))
				.andExpect(jsonPath("$.cargadosPorLaFamilia").value(1)).andExpect(jsonPath("$.adultos.length()").value(1))
				.andExpect(jsonPath("$.adultos[0].nombre").value("Marta")).andExpect(jsonPath("$.adultos[0].dni").doesNotExist());
	}

	@Test
	void laMismaClaveConLosMismosDatosDevuelveElOriginalYConOtrosDatosEsConflicto() throws Exception {
		String clave = clave();
		String id = idDe(mvc.perform(escribir(post(RUTA), ABUELA, madre, fam1).header("Idempotency-Key", clave))
				.andExpect(status().isCreated()).andReturn().getResponse());
		mvc.perform(escribir(post(RUTA), ABUELA, madre, fam1).header("Idempotency-Key", clave)).andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value(id));
		mvc.perform(escribir(post(RUTA), ABUELA.replace("Marta", "Mirta"), madre, fam1).header("Idempotency-Key", clave))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("IDEMPOTENCY_KEY_REUTILIZADA"));
		assertThat(adultosDe(fam1)).isEqualTo(1);
		assertThat(auditorias("TUTOR_DECLARADO")).isEqualTo(1);
		// La clave es de la cuenta: otra persona de la familia con la misma clave es otra alta (y aquí, un duplicado).
		mvc.perform(escribir(post(RUTA), ABUELA, padre, fam1).header("Idempotency-Key", clave)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("TUTOR_DUPLICADO"));
		// Sin clave o con una clave que no es UUID: 400 antes de escribir.
		mvc.perform(escribir(post(RUTA), ABUELA, madre, fam1)).andExpect(status().isBadRequest());
		mvc.perform(escribir(post(RUTA), ABUELA, madre, fam1).header("Idempotency-Key", "no-es-uuid"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void losDuplicadosSeDetectanSoloDentroDeLaPropiaFamiliaYSinDecirCual() throws Exception {
		crear(madre, fam1, ABUELA).andExpect(status().isCreated());
		// Mismo DNI en la misma familia: conflicto, sin eco de lo escrito.
		String r = crear(padre, fam1, ABUELA.replace("Marta", "Otra").replace("Diaz", "Persona")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("TUTOR_DUPLICADO")).andReturn().getResponse().getContentAsString();
		assertThat(r).doesNotContain("20333444", "Marta", "Otra");
		// Mismo nombre y apellido sin DNI: la misma persona.
		crear(padre, fam1, "{\"nombre\":\" marta \",\"apellido\":\"DIAZ\"}").andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("TUTOR_DUPLICADO"));
		// Mismo nombre con otro DNI: otra persona.
		crear(padre, fam1, "{\"nombre\":\"Marta\",\"apellido\":\"Diaz\",\"dni\":\"20999000\"}").andExpect(status().isCreated());
		// El mismo DNI en OTRA familia no se consulta ni se rechaza: la respuesta es la de siempre.
		crear(deFam2, fam2, ABUELA).andExpect(status().isCreated()).andExpect(jsonPath("$.estado").value("DECLARADO"));
	}

	@Test
	void elTopeCuentaElPerfilPropioYLasAltasPeroNoLoQueCargaElClub() throws Exception {
		datos.tutor(escuela, fam1, "Cargado", "PorElClub"); // origen ADMIN: no cuenta
		mvc.perform(escribir(post("/api/familia/mi-perfil"), "{\"nombre\":\"Laura\",\"apellido\":\"Gomez\"}", madre, fam1))
				.andExpect(status().isOk());
		crear(madre, fam1, ABUELA).andExpect(status().isCreated());
		crear(madre, fam1, "{\"nombre\":\"Jorge\",\"apellido\":\"Diaz\"}").andExpect(status().isCreated());
		// Tres adultos cargados por la familia (perfil incluido): el cuarto no entra por ningún camino.
		crear(madre, fam1, "{\"nombre\":\"Ana\",\"apellido\":\"Ruiz\"}").andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("TOPE_TUTORES_ALCANZADO"));
		mvc.perform(escribir(post("/api/familia/mi-perfil"), "{\"nombre\":\"Pablo\",\"apellido\":\"Gomez\"}", padre, fam1))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("TOPE_TUTORES_ALCANZADO"));
		mvc.perform(get(RUTA).cookie(sesion(madre, fam1))).andExpect(jsonPath("$.cargadosPorLaFamilia").value(3))
				.andExpect(jsonPath("$.adultos.length()").value(4));
		// El club sigue pudiendo cargar adultos (casos excepcionales) y una baja libera un lugar.
		datos.tutor(escuela, fam1, "Otro", "DelClub");
		UUID jorge = jdbc.sql("SELECT id FROM gestion_patin.tutor WHERE escuela_id = :e AND nombre = 'Jorge'").param("e", escuela)
				.query(UUID.class).single();
		datos.desactivarTutor(jorge);
		crear(madre, fam1, "{\"nombre\":\"Ana\",\"apellido\":\"Ruiz\"}").andExpect(status().isCreated());
		// El tope es por familia: la otra familia no se ve afectada.
		crear(deFam2, fam2, "{\"nombre\":\"Ana\",\"apellido\":\"Ruiz\"}").andExpect(status().isCreated());
	}

	/**
	 * Con la familia en el tope menos uno, otra transacción de la MISMA familia que ya tomó el bloqueo de altas y cargó un
	 * adulto (sin confirmar) hace esperar al alta HTTP (comprobado con {@code pg_blocking_pids}); al confirmar, el alta ve el
	 * adulto nuevo y responde el tope. Sin el bloqueo, el alta contaría solo lo confirmado y lo superaría.
	 */
	@Test
	void dosAltasSimultaneasEnElLimiteNoSuperanElTope() throws Exception {
		crear(madre, fam1, ABUELA).andExpect(status().isCreated());
		crear(madre, fam1, "{\"nombre\":\"Jorge\",\"apellido\":\"Diaz\"}").andExpect(status().isCreated());
		ExecutorService hilos = Executors.newSingleThreadExecutor();
		try (Connection otra = fuente.getConnection()) {
			otra.setAutoCommit(false);
			long pid = pid(otra);
			try (var ps = otra.prepareStatement("SELECT pg_advisory_xact_lock(2102, hashtext(?))")) {
				ps.setString(1, escuela + "/" + fam1);
				ps.executeQuery();
			}
			try (var ps = otra.prepareStatement("""
					INSERT INTO gestion_patin.tutor(escuela_id, familia_id, nombre, apellido, origen, verificacion, declarado_por)
					VALUES (?, ?, 'Simultaneo', 'Ganador', 'FAMILIA', 'DECLARADO', ?)
					""")) {
				ps.setObject(1, escuela);
				ps.setObject(2, fam1);
				ps.setObject(3, padre);
				ps.executeUpdate();
			}
			MockHttpServletRequestBuilder alta = escribir(post(RUTA), "{\"nombre\":\"Ana\",\"apellido\":\"Ruiz\"}", madre, fam1)
					.header("Idempotency-Key", clave());
			Future<MockHttpServletResponse> enCurso = hilos.submit(() -> mvc.perform(alta).andReturn().getResponse());
			esperarBloqueadaPor(pid);
			otra.commit();
			MockHttpServletResponse r = enCurso.get(30, TimeUnit.SECONDS);
			assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(409);
			assertThat(r.getContentAsString()).contains("TOPE_TUTORES_ALCANZADO");
		} finally {
			hilos.shutdownNow();
		}
		assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.tutor WHERE familia_id = :f AND activo AND origen = 'FAMILIA'")
				.param("f", fam1).query(Long.class).single()).isEqualTo(3);
	}

	// ---------- alcance ----------

	@Test
	void otraFamiliaOtraEscuelaUnInactivoOUnIdInexistenteSonElMismo404() throws Exception {
		String deFam1 = idDe(crear(madre, fam1, ABUELA).andExpect(status().isCreated()).andReturn().getResponse());
		UUID deLaOtraFamilia = datos.tutor(escuela, fam2, "Ajena", "Familia");
		UUID deLaOtraEscuela = datos.tutor(otraEscuela, famOtraEscuela, "Ajena", "Escuela");
		UUID inactivo = datos.tutor(escuela, fam1, "Inactivo", "Propio");
		datos.desactivarTutor(inactivo);

		mvc.perform(get(RUTA).cookie(sesion(deFam2, fam2))).andExpect(jsonPath("$.adultos.length()").value(1))
				.andExpect(jsonPath("$.adultos[0].nombre").value("Ajena")).andExpect(jsonPath("$.cargadosPorLaFamilia").value(0));
		String cuerpo = "{\"nombre\":\"X\",\"apellido\":\"Y\",\"versionEsperada\":\"0\"}";
		String primero = null;
		for (Object[] caso : new Object[][] { { deFam1, deFam2, fam2 }, { deLaOtraFamilia, madre, fam1 },
				{ deLaOtraEscuela, madre, fam1 }, { inactivo, madre, fam1 }, { UUID.randomUUID(), madre, fam1 } }) {
			String r = mvc.perform(escribir(put(RUTA + "/" + caso[0]), cuerpo, (UUID) caso[1], (UUID) caso[2]))
					.andExpect(status().isNotFound()).andExpect(jsonPath("$.codigo").value("TUTOR_NO_ENCONTRADO")).andReturn()
					.getResponse().getContentAsString();
			if (primero == null) {
				primero = r;
			}
			assertThat(r).isEqualTo(primero);
		}
		assertThat(jdbc.sql("SELECT nombre FROM gestion_patin.tutor WHERE id = :t").param("t", UUID.fromString(deFam1))
				.query(String.class).single()).isEqualTo("Marta");
	}

	@Test
	void adminNoUsaLasRutasDeLaFamiliaYSinCsrfNoSeEscribe() throws Exception {
		Cookie x = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		mvc.perform(post(RUTA).cookie(x, sesionAdmin()).header("X-XSRF-TOKEN", x.getValue()).header("Idempotency-Key", clave())
				.contentType(MediaType.APPLICATION_JSON).content(ABUELA)).andExpect(status().isForbidden());
		mvc.perform(post(RUTA).cookie(sesion(madre, fam1)).header("Idempotency-Key", clave())
				.contentType(MediaType.APPLICATION_JSON).content(ABUELA)).andExpect(status().isForbidden());
		mvc.perform(escribir(post(RUTA), ABUELA.replace("}", ",\"familiaId\":\"" + fam2 + "\"}"), madre, fam1)
				.header("Idempotency-Key", clave())).andExpect(status().isBadRequest());
		assertThat(adultosDe(fam1)).isZero();
		assertThat(adultosDe(fam2)).isZero();
	}

	// ---------- edición ----------

	@Test
	void editarExigeLaRevisionVistaYNoTocaAQuienTieneSuPropiaCuenta() throws Exception {
		String id = idDe(crear(madre, fam1, ABUELA).andExpect(status().isCreated()).andReturn().getResponse());
		String version = version(UUID.fromString(id));
		mvc.perform(escribir(put(RUTA + "/" + id), conVersion(ABUELA.replace("4000-0000", "4111-1111"), version), padre, fam1))
				.andExpect(status().isOk()).andExpect(jsonPath("$.telefono").value("11 4111-1111"));
		// La madre todavía tiene la revisión anterior: no pisa lo que cambió el padre.
		mvc.perform(escribir(put(RUTA + "/" + id), conVersion(ABUELA.replace("4000-0000", "4222-2222"), version), madre, fam1))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("EDICION_DESACTUALIZADA"));
		assertThat(ultimaAuditoria("TUTOR_ACTUALIZADO_POR_FAMILIA")).contains("telefono").doesNotContain("4111");

		// El perfil del padre (su cuenta) no lo edita la madre; él sí, por cualquiera de las dos rutas.
		mvc.perform(escribir(post("/api/familia/mi-perfil"), "{\"nombre\":\"Pablo\",\"apellido\":\"Gomez\"}", padre, fam1))
				.andExpect(status().isOk());
		UUID perfilPadre = jdbc.sql("SELECT id FROM gestion_patin.tutor WHERE usuario_id = :u").param("u", padre).query(UUID.class)
				.single();
		String cambio = conVersion("{\"nombre\":\"Pablo\",\"apellido\":\"Gomez\",\"telefono\":\"11 1\"}", version(perfilPadre));
		mvc.perform(escribir(put(RUTA + "/" + perfilPadre), cambio, madre, fam1)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("TUTOR_CON_CUENTA"));
		mvc.perform(get(RUTA).cookie(sesion(madre, fam1)))
				.andExpect(jsonPath("$.adultos[?(@.nombre == 'Pablo')].tieneCuentaPropia").value(true))
				.andExpect(jsonPath("$.adultos[?(@.nombre == 'Pablo')].editable").value(false));
		mvc.perform(escribir(put(RUTA + "/" + perfilPadre), cambio, padre, fam1)).andExpect(status().isOk())
				.andExpect(jsonPath("$.esMiPerfil").value(true));
	}

	@Test
	void laIdentidadVerificadaNoCambiaPeroElContactoSiYSigueVerificado() throws Exception {
		UUID delClub = datos.tutor(escuela, fam1, "Rosa", "Lopez"); // ADMIN, VERIFICADO, sin cuenta
		mvc.perform(get(RUTA).cookie(sesion(madre, fam1)))
				.andExpect(jsonPath("$.adultos[0].cargadoPorElClub").value(true))
				.andExpect(jsonPath("$.adultos[0].identidadEditable").value(false))
				.andExpect(jsonPath("$.adultos[0].editable").value(true));
		mvc.perform(escribir(put(RUTA + "/" + delClub), conVersion("{\"nombre\":\"Rosita\",\"apellido\":\"Lopez\"}",
				version(delClub)), madre, fam1)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("IDENTIDAD_VERIFICADA"));
		mvc.perform(escribir(put(RUTA + "/" + delClub), conVersion(
				"{\"nombre\":\"Rosa\",\"apellido\":\"Lopez\",\"telefono\":\"11 3000-0000\"}", version(delClub)), madre, fam1))
				.andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("VERIFICADO"))
				.andExpect(jsonPath("$.telefono").value("11 3000-0000"));
	}

	/** Salvaguarda de S4: con un DNI APROBADO la familia no cambia la identidad; el historial documental no se toca. */
	@Test
	void conUnDniAprobadoLaIdentidadDeUnAdultoDeclaradoQuedaRespaldada() throws Exception {
		UUID id = UUID.fromString(idDe(crear(madre, fam1, ABUELA).andExpect(status().isCreated()).andReturn().getResponse()));
		UUID documento = documentoDni(id, "APROBADO");
		Map<String, Object> antes = documento(documento);
		mvc.perform(get(RUTA).cookie(sesion(madre, fam1))).andExpect(jsonPath("$.adultos[0].identidadEditable").value(false))
				.andExpect(jsonPath("$.adultos[0].estado").value("DECLARADO"));
		for (String identidad : List.of(ABUELA.replace("Marta", "Mirta"), ABUELA.replace("20.333.444", "20.333.445"))) {
			mvc.perform(escribir(put(RUTA + "/" + id), conVersion(identidad, version(id)), madre, fam1))
					.andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("IDENTIDAD_CON_DNI_APROBADO"));
		}
		mvc.perform(escribir(put(RUTA + "/" + id), conVersion(ABUELA.replace("Abuela", "Abuela materna"), version(id)), madre,
				fam1)).andExpect(status().isOk()).andExpect(jsonPath("$.parentesco").value("Abuela materna"));
		assertThat(documento(documento)).isEqualTo(antes);
		assertThat(jdbc.sql("SELECT nombre || '/' || dni || '/' || verificacion FROM gestion_patin.tutor WHERE id = :t")
				.param("t", id).query(String.class).single()).isEqualTo("Marta/20333444/DECLARADO");
	}

	/**
	 * Una aprobación del DNI que ya tomó el documento (sin confirmar) hace esperar a la edición de identidad, que al confirmarse
	 * ve la aprobación y rechaza el cambio: la combinación «imagen aprobada + identidad cambiada» no puede quedar por carrera.
	 */
	@Test
	void unaAprobacionConcurrenteDelDniSeSerializaConLaEdicionDeIdentidad() throws Exception {
		UUID id = UUID.fromString(idDe(crear(madre, fam1, ABUELA).andExpect(status().isCreated()).andReturn().getResponse()));
		UUID documento = documentoDni(id, "CARGADO");
		ExecutorService hilos = Executors.newSingleThreadExecutor();
		try (Connection revision = fuente.getConnection()) {
			revision.setAutoCommit(false);
			long pid = pid(revision);
			try (var ps = revision.prepareStatement(
					"UPDATE gestion_patin.documento SET estado = 'APROBADO', version = version + 1 WHERE id = ?")) {
				ps.setObject(1, documento);
				assertThat(ps.executeUpdate()).isEqualTo(1);
			}
			MockHttpServletRequestBuilder edicion = escribir(put(RUTA + "/" + id),
					conVersion(ABUELA.replace("Marta", "Mirta"), version(id)), madre, fam1);
			Future<MockHttpServletResponse> enCurso = hilos.submit(() -> mvc.perform(edicion).andReturn().getResponse());
			esperarBloqueadaPor(pid);
			revision.commit();
			MockHttpServletResponse r = enCurso.get(30, TimeUnit.SECONDS);
			assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(409);
			assertThat(r.getContentAsString()).contains("IDENTIDAD_CON_DNI_APROBADO");
		} finally {
			hilos.shutdownNow();
		}
		assertThat(jdbc.sql("SELECT nombre FROM gestion_patin.tutor WHERE id = :t").param("t", id).query(String.class).single())
				.isEqualTo("Marta");
	}

	// ---------- auxiliares ----------

	private org.springframework.test.web.servlet.ResultActions crear(UUID usuario, UUID familia, String cuerpo) throws Exception {
		return mvc.perform(escribir(post(RUTA), cuerpo, usuario, familia).header("Idempotency-Key", clave()));
	}

	private MockHttpServletRequestBuilder escribir(MockHttpServletRequestBuilder req, String cuerpo, UUID usuario, UUID familia)
			throws Exception {
		Cookie x = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		return req.cookie(x, sesion(usuario, familia)).header("X-XSRF-TOKEN", x.getValue())
				.contentType(MediaType.APPLICATION_JSON).content(cuerpo);
	}

	private Cookie sesion(UUID usuario, UUID familia) {
		return new Cookie(COOKIE, tokens.emitir(usuario, Rol.FAMILIA, escuela, familia, versionSesion(usuario)));
	}

	private Cookie sesionAdmin() {
		return new Cookie(COOKIE, tokens.emitir(admin, Rol.ADMIN, escuela, null, versionSesion(admin)));
	}

	private long versionSesion(UUID usuario) {
		return jdbc.sql("SELECT version_sesion FROM gestion_patin.usuario WHERE id = :u").param("u", usuario).query(Long.class)
				.single();
	}

	private String version(UUID tutor) {
		return Long.toString(jdbc.sql("SELECT version FROM gestion_patin.tutor WHERE id = :t").param("t", tutor)
				.query(Long.class).single());
	}

	private UUID documentoDni(UUID tutor, String estado) {
		UUID id = jdbc.sql("""
				INSERT INTO gestion_patin.documento(escuela_id, tutor_id, tipo, nombre_original, storage_key, mime_type, tamanio_bytes, cargado_por)
				VALUES (:e, :t, 'DNI_TUTOR_FRENTE', 'sintetico.png', :k, 'image/png', 10, :u) RETURNING id
				""").param("e", escuela).param("t", tutor).param("k", UUID.randomUUID().toString()).param("u", madre)
				.query(UUID.class).single();
		if (!"CARGADO".equals(estado)) {
			jdbc.sql("UPDATE gestion_patin.documento SET estado = :s, version = version + 1 WHERE id = :d").param("s", estado)
					.param("d", id).update();
		}
		return id;
	}

	private Map<String, Object> documento(UUID id) {
		return jdbc.sql("SELECT estado, vigente, version, tutor_id FROM gestion_patin.documento WHERE id = :d").param("d", id)
				.query().singleRow();
	}

	private static String conVersion(String cuerpo, String version) {
		return cuerpo.replace("}", ",\"versionEsperada\":\"" + version + "\"}");
	}

	private static String clave() {
		return UUID.randomUUID().toString();
	}

	private static String idDe(MockHttpServletResponse r) throws Exception {
		String cuerpo = r.getContentAsString();
		int i = cuerpo.indexOf("\"id\":\"") + 6;
		return cuerpo.substring(i, cuerpo.indexOf('"', i));
	}

	private long adultosDe(UUID familia) {
		return jdbc.sql("SELECT count(*) FROM gestion_patin.tutor WHERE familia_id = :f").param("f", familia).query(Long.class)
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

	private static long pid(Connection c) throws Exception {
		try (var s = c.createStatement(); var rs = s.executeQuery("SELECT pg_backend_pid()")) {
			rs.next();
			return rs.getLong(1);
		}
	}

	/** Espera (hasta 10 s) a que exactamente una sesión quede bloqueada por {@code pid}. */
	private void esperarBloqueadaPor(long pid) throws InterruptedException {
		List<Map<String, Object>> bloqueadas = List.of();
		for (int i = 0; i < 200; i++) {
			bloqueadas = jdbc.sql("""
					SELECT pid, query FROM pg_stat_activity
					WHERE datname = current_database() AND :pid = ANY(pg_blocking_pids(pid))
					""").param("pid", pid).query().listOfRows();
			if (bloqueadas.size() == 1) {
				return;
			}
			Thread.sleep(50);
		}
		throw new IllegalStateException("se esperaba una sesión bloqueada por " + pid + ": " + bloqueadas);
	}
}
