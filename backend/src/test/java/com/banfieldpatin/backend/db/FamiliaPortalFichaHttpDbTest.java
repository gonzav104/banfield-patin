package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.banfieldpatin.backend.familias.portal.EdadDeportista;
import com.banfieldpatin.backend.seguridad.ServicioTokens;
import com.banfieldpatin.backend.usuarios.Rol;

import jakarta.servlet.http.Cookie;

/**
 * Ficha minima de FAMILIA por HTTP real: cadena de seguridad completa (MockMvc), servicios y manejador REALES, PostgreSQL 17
 * descartable y cookies {@code BP_SESION} emitidas por el {@link ServicioTokens} real para usuarios PERSISTIDOS. Cada
 * escenario muta la base con SQL directo entre dos solicitudes con el MISMO token: no hay cache y el cambio se nota en la
 * siguiente solicitud (revalidacion central de la sesion).
 */
@Tag("db")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
		"spring.datasource.hikari.maximum-pool-size=3", "spring.datasource.hikari.minimum-idle=1" })
@AutoConfigureMockMvc
@ActiveProfiles({ "test", "e2e" })
@Import(SesionVigenteHttpDbTest.Soporte.class)
class FamiliaPortalFichaHttpDbTest extends BaseDbTest {

	private static final String COOKIE = "BP_SESION";
	private static final String NO_ENCONTRADO = "{\"codigo\":\"DEPORTISTA_NO_ENCONTRADO\",\"mensaje\":\"El deportista no existe.\","
			+ "\"detalles\":[]}";
	/** Valores distintivos que NUNCA pueden aparecer en los bytes de la ficha. */
	private static final List<String> CENTINELAS = List.of("36000001", "20-CUIL-CENTINELA", "Nacionalidad-CENTINELA",
			"Calle-CENTINELA 123", "Piso-CENTINELA", "Localidad-CENTINELA", "Partido-CENTINELA", "CP-CENT", "011-CENTINELA",
			"centinela@sentinel.example");

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	ServicioTokens tokens;
	@Autowired
	SesionVigenteHttpDbTest.Contador contador;

	private DatosDb datos;
	private UUID escuelaA;
	private UUID escuelaB;
	private UUID adminId;
	private UUID fa1;
	private UUID fa2;
	private UUID fb1;
	private UUID usuarioFa1;
	private UUID usuarioFa2;
	private UUID usuarioFb1;
	private UUID t25;
	private UUID t26;
	private UUID t27;
	private UUID dPropio;
	private UUID dInactivo;
	private UUID dDeFa2;
	private UUID dDeB;
	private UUID vinculoPropio;
	private LocalDate nacimientoDePropio;

	@BeforeEach
	void preparar() {
		datos = new DatosDb(jdbc);
		contador.baseCaida.set(false);
		escuelaA = datos.escuela("ficha-a-" + UUID.randomUUID());
		escuelaB = datos.escuela("ficha-b-" + UUID.randomUUID());
		adminId = datos.admin(escuelaA, "admin@ficha.example", true);
		UUID adminB = datos.admin(escuelaB, "admin@ficha-b.example", true);
		fa1 = datos.familia(escuelaA, "Familia Uno", true);
		fa2 = datos.familia(escuelaA, "Familia Dos", true);
		fb1 = datos.familia(escuelaB, "Familia B", true);
		usuarioFa1 = datos.usuarioFamilia(escuelaA, fa1, "uno@ficha.example");
		usuarioFa2 = datos.usuarioFamilia(escuelaA, fa2, "dos@ficha.example");
		usuarioFb1 = datos.usuarioFamilia(escuelaB, fb1, "b@ficha.example");
		t25 = datos.temporada(escuelaA, 2025, false);
		t26 = datos.temporada(escuelaA, 2026, true);
		t27 = datos.temporada(escuelaA, 2027, false);
		UUID tb26 = datos.temporada(escuelaB, 2026, true);
		dPropio = datos.deportista(escuelaA, "36000001", "Lola", "Propia", true);
		dInactivo = datos.deportista(escuelaA, "36000002", "Pepe", "Inactivo", false);
		dDeFa2 = datos.deportista(escuelaA, "36000003", "Nico", "Ajeno", true);
		dDeB = datos.deportista(escuelaB, "36000004", "Beto", "DeB", true);
		vinculoPropio = datos.vinculo(escuelaA, fa1, dPropio, "ACTIVO", true, adminId);
		datos.vinculo(escuelaA, fa1, dInactivo, "ACTIVO", true, adminId);
		datos.vinculo(escuelaA, fa2, dDeFa2, "ACTIVO", true, adminId);
		datos.vinculo(escuelaB, fb1, dDeB, "ACTIVO", true, adminB);
		datos.datosDeportivos(escuelaA, dPropio, t25, "cat25", null, "ACTIVO");
		datos.datosDeportivos(escuelaA, dPropio, t26, "cat26", "007", "ACTIVO");
		datos.datosDeportivos(escuelaA, dPropio, t27, "cat27", null, "ACTIVO");
		datos.datosDeportivos(escuelaA, dInactivo, t26, "cat26-inactivo", null, "INACTIVO");
		datos.datosDeportivos(escuelaA, dDeFa2, t26, "cat26-fa2", null, "ACTIVO");
		datos.datosDeportivos(escuelaB, dDeB, tb26, "catB26", null, "ACTIVO");
		// Nacio hace 9 anios y un dia (en la zona de la escuela): tiene 9, sea cual sea el dia en que corra la prueba.
		nacimientoDePropio = LocalDate.now(EdadDeportista.ZONA_ESCUELA).minusYears(9).minusDays(1);
		jdbc.sql("""
				UPDATE gestion_patin.deportista SET fecha_nacimiento = :f, cuil = '20-CUIL-CENTINELA',
				    nacionalidad = 'Nacionalidad-CENTINELA', domicilio = 'Calle-CENTINELA 123',
				    otros_datos_domicilio = 'Piso-CENTINELA', localidad = 'Localidad-CENTINELA', partido = 'Partido-CENTINELA',
				    codigo_postal = 'CP-CENT', telefono_contacto = '011-CENTINELA', email_federativo = 'centinela@sentinel.example'
				WHERE id = :d
				""").param("f", nacimientoDePropio).param("d", dPropio).update();
	}

	@AfterEach
	void limpiar() {
		contador.baseCaida.set(false);
		datos.limpiarEscuela(escuelaA);
		datos.limpiarEscuela(escuelaB);
	}

	private Cookie familia(UUID usuario, UUID escuela, UUID familiaId) {
		return new Cookie(COOKIE, tokens.emitir(usuario, Rol.FAMILIA, escuela, familiaId));
	}

	private Cookie sesionFa1() {
		return familia(usuarioFa1, escuelaA, fa1);
	}

	private static String ficha(UUID deportista) {
		return "/api/familia/deportistas/" + deportista + "/ficha";
	}

	private ResultActions pedir(String ruta, Cookie sesion) throws Exception {
		return mvc.perform(get(ruta).cookie(sesion));
	}

	private MockHttpServletResponse respuesta(String ruta, Cookie sesion) throws Exception {
		return pedir(ruta, sesion).andReturn().getResponse();
	}

	private String cuerpo(String ruta, Cookie sesion) throws Exception {
		return respuesta(ruta, sesion).getContentAsString(StandardCharsets.UTF_8);
	}

	private MockHttpServletRequestBuilder conCsrf(MockHttpServletRequestBuilder req) throws Exception {
		Cookie x = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		return req.cookie(x).header("X-XSRF-TOKEN", x.getValue());
	}

	private static void sesionMuerta(ResultActions r) throws Exception {
		r.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"))
				.andExpect(cookie().exists(COOKIE)).andExpect(cookie().value(COOKIE, ""))
				.andExpect(cookie().maxAge(COOKIE, 0)).andExpect(cookie().httpOnly(COOKIE, true))
				.andExpect(cookie().path(COOKIE, "/"));
	}

	private long auditoria() {
		return jdbc.sql("SELECT count(*) FROM gestion_patin.auditoria WHERE escuela_id = :e").param("e", escuelaA)
				.query(Long.class).single();
	}

	// ---------- el contrato exacto con datos reales ----------

	@Test
	void unaFamiliaVeLaFichaDeSuDeportistaConExactamenteElContratoYLaEdadCalculada() throws Exception {
		String esperado = "{\"nombre\":\"Lola\",\"apellido\":\"Propia\",\"edad\":9,\"activo\":true,\"temporadaVigente\":"
				+ "{\"anio\":2026,\"categoria\":\"cat26\",\"rama\":\"Femenino\",\"divisional\":\"A\",\"numeroCorredor\":\"007\","
				+ "\"estadoDeportivo\":\"ACTIVO\"}}";

		MockHttpServletResponse r = respuesta(ficha(dPropio), sesionFa1());

		assertThat(r.getStatus()).isEqualTo(200);
		assertThat(r.getContentType()).startsWith("application/json");
		assertThat(r.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(esperado);
	}

	@Test
	void ningunDatoSensibleNiLaFechaDeNacimientoApareceEnLosBytesDeLaRespuesta() throws Exception {
		byte[] bytes = respuesta(ficha(dPropio), sesionFa1()).getContentAsByteArray();
		String texto = new String(bytes, StandardCharsets.UTF_8);

		for (String centinela : CENTINELAS) {
			assertThat(texto).as("centinela %s", centinela).doesNotContain(centinela);
		}
		// La fecha de nacimiento en los formatos habituales, ni siquiera parcial.
		for (String formato : List.of(nacimientoDePropio.toString(), nacimientoDePropio.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")),
				nacimientoDePropio.format(DateTimeFormatter.ofPattern("dd-MM-yyyy")), nacimientoDePropio.format(DateTimeFormatter.BASIC_ISO_DATE),
				String.valueOf(nacimientoDePropio.getYear()))) {
			assertThat(texto).as("fecha %s", formato).doesNotContain(formato);
		}
		for (String clave : List.of("dni", "cuil", "fechaNacimiento", "nacionalidad", "domicilio", "localidad", "partido",
				"codigoPostal", "telefono", "email", "escuelaId", "familiaId", "\"id\"", "temporadaId", "temporadaActiva")) {
			assertThat(texto).as("clave %s", clave).doesNotContain(clave);
		}
	}

	@Test
	void unDeportistaInactivoConVinculoActivoSeVeConActivoFalseYSuEstadoDeportivoPropio() throws Exception {
		pedir(ficha(dInactivo), sesionFa1()).andExpect(status().isOk()).andExpect(jsonPath("$.activo").value(false))
				.andExpect(jsonPath("$.edad").value((Object) null))
				.andExpect(jsonPath("$.temporadaVigente.estadoDeportivo").value("INACTIVO"))
				.andExpect(jsonPath("$.temporadaVigente.categoria").value("cat26-inactivo"));
	}

	@Test
	void losParametrosDeAlcanceDeLaSolicitudNoCambianQuienEs() throws Exception {
		pedir(ficha(dDeFa2) + "?familiaId=" + fa2 + "&escuelaId=" + escuelaA + "&usuarioId=" + usuarioFa2, sesionFa1())
				.andExpect(status().isNotFound());
		pedir(ficha(dPropio) + "?familiaId=" + fa2, sesionFa1()).andExpect(status().isOk());
	}

	@Test
	void unDeportistaCompartidoPorDosFamiliasLoVenAmbasIgual() throws Exception {
		datos.vinculo(escuelaA, fa2, dPropio, "ACTIVO", false, adminId);

		String deUno = cuerpo(ficha(dPropio), sesionFa1());
		String deDos = cuerpo(ficha(dPropio), familia(usuarioFa2, escuelaA, fa2));

		assertThat(deDos).isEqualTo(deUno);
	}

	// ---------- 404 uniforme ----------

	@Test
	void todasLasCausasDeNotFoundDanElMismoCuerpoExactoYLosMismosEncabezadosQueElDetalle() throws Exception {
		UUID pendiente = datos.deportista(escuelaA, "36000010", "Pen", "Diente", true);
		datos.vinculo(escuelaA, fa1, pendiente, "PENDIENTE", false, null);
		UUID rechazado = datos.deportista(escuelaA, "36000011", "Rec", "Hazado", true);
		datos.vinculo(escuelaA, fa1, rechazado, "RECHAZADO", false, adminId);
		datos.estadoVinculo(vinculoPropio, "REVOCADO", adminId);
		byte[] referencia = respuesta("/api/familia/deportistas/" + UUID.randomUUID(), sesionFa1()).getContentAsByteArray();
		assertThat(new String(referencia, StandardCharsets.UTF_8)).isEqualTo(NO_ENCONTRADO);

		for (UUID id : List.of(dDeFa2 /* otra familia */, dDeB /* otra escuela */, dPropio /* vinculo revocado */,
				pendiente, rechazado, UUID.randomUUID() /* inexistente */, new UUID(0, 0))) {
			MockHttpServletResponse r = respuesta(ficha(id), sesionFa1());

			assertThat(r.getStatus()).as("id %s", id).isEqualTo(404);
			byte[] cuerpo = r.getContentAsByteArray();
			assertThat(new String(cuerpo, StandardCharsets.UTF_8)).isEqualTo(NO_ENCONTRADO);
			assertThat(cuerpo).as("cuerpo byte a byte para %s", id).isEqualTo(referencia);
			assertThat(r.getContentType()).startsWith("application/json");
			assertThat(r.getHeaders(HttpHeaders.SET_COOKIE)).noneMatch(h -> h.startsWith(COOKIE + "="));
		}
	}

	@Test
	void unTokenDeOtraEscuelaConLaFamiliaDeEstaNuncaRecibeDatos() throws Exception {
		int estado = respuesta(ficha(dPropio), familia(usuarioFb1, escuelaB, fa1)).getStatus();

		assertThat(estado).isIn(401, 404);
	}

	@Test
	void unVinculoRevocadoTieneEfectoEnLaSiguienteSolicitudConElMismoToken() throws Exception {
		Cookie sesion = sesionFa1();
		pedir(ficha(dPropio), sesion).andExpect(status().isOk());

		datos.estadoVinculo(vinculoPropio, "REVOCADO", adminId);

		assertThat(respuesta(ficha(dPropio), sesion).getStatus()).isEqualTo(404);
		datos.estadoVinculo(vinculoPropio, "ACTIVO", adminId);
		pedir(ficha(dPropio), sesion).andExpect(status().isOk());
	}

	// ---------- temporada vigente y su ausencia ----------

	@Test
	void sinTemporadaActivaEnLaEscuelaLaFichaSigueSiendo200ConLaTemporadaNula() throws Exception {
		datos.activarTemporada(escuelaA, null);

		pedir(ficha(dPropio), sesionFa1()).andExpect(status().isOk()).andExpect(jsonPath("$.nombre").value("Lola"))
				.andExpect(jsonPath("$.temporadaVigente").value((Object) null)).andExpect(jsonPath("$.length()").value(5));
	}

	@Test
	void conTemporadaActivaPeroSinFilaDelDeportistaLaTemporadaEsNula() throws Exception {
		UUID sinFila = datos.deportista(escuelaA, "36000020", "Sin", "Fila", true);
		datos.vinculo(escuelaA, fa1, sinFila, "ACTIVO", false, adminId);

		pedir(ficha(sinFila), sesionFa1()).andExpect(status().isOk()).andExpect(jsonPath("$.temporadaVigente").value((Object) null));
	}

	@Test
	void conFilasSoloDeAniosAnterioresONadaMasQueUnBorradorFuturoLaTemporadaEsNula() throws Exception {
		UUID soloVieja = datos.deportista(escuelaA, "36000021", "Solo", "Vieja", true);
		UUID soloBorrador = datos.deportista(escuelaA, "36000022", "Solo", "Borrador", true);
		datos.vinculo(escuelaA, fa1, soloVieja, "ACTIVO", false, adminId);
		datos.vinculo(escuelaA, fa1, soloBorrador, "ACTIVO", false, adminId);
		datos.datosDeportivos(escuelaA, soloVieja, t25, "vieja", null, "ACTIVO");
		datos.datosDeportivos(escuelaA, soloBorrador, t27, "borrador", null, "ACTIVO");

		pedir(ficha(soloVieja), sesionFa1()).andExpect(status().isOk()).andExpect(jsonPath("$.temporadaVigente").value((Object) null));
		pedir(ficha(soloBorrador), sesionFa1()).andExpect(status().isOk()).andExpect(jsonPath("$.temporadaVigente").value((Object) null));
	}

	@Test
	void unaTemporadaActivaDeOtraEscuelaNuncaSeUsa() throws Exception {
		// La escuela B tiene su 2026 activa; la A queda sin ninguna: el deportista de A no hereda la de B.
		datos.activarTemporada(escuelaA, null);

		pedir(ficha(dPropio), sesionFa1()).andExpect(jsonPath("$.temporadaVigente").value((Object) null));
	}

	@Test
	void alCambiarLaTemporadaActivaLaFichaMuestraLaNuevaVigenteYAlVolverLaAnterior() throws Exception {
		datos.activarTemporada(escuelaA, t27);
		pedir(ficha(dPropio), sesionFa1()).andExpect(jsonPath("$.temporadaVigente.anio").value(2027))
				.andExpect(jsonPath("$.temporadaVigente.categoria").value("cat27"));

		datos.activarTemporada(escuelaA, t25);
		pedir(ficha(dPropio), sesionFa1()).andExpect(jsonPath("$.temporadaVigente.anio").value(2025))
				.andExpect(jsonPath("$.temporadaVigente.categoria").value("cat25"));
	}

	@Test
	void losTresEstadosDeportivosSeMuestranTalCual() throws Exception {
		for (String estado : List.of("ACTIVO", "INACTIVO", "SUSPENDIDO")) {
			jdbc.sql("UPDATE gestion_patin.deportista_temporada SET estado_deportivo = :s WHERE deportista_id = :d AND temporada_id = :t")
					.param("s", estado).param("d", dPropio).param("t", t26).update();

			pedir(ficha(dPropio), sesionFa1()).andExpect(jsonPath("$.temporadaVigente.estadoDeportivo").value(estado));
		}
	}

	@Test
	void laVigenteDeLaFichaEsLaMismaFilaQueElHistorialMarcaComoActiva() throws Exception {
		String historial = cuerpo("/api/familia/deportistas/" + dPropio + "/temporadas", sesionFa1());
		String ficha = cuerpo(ficha(dPropio), sesionFa1());

		// El historial marca una unica activa; la ficha trae sus mismos datos deportivos.
		assertThat(historial).contains("\"anio\":2026", "\"temporadaActiva\":true", "\"categoria\":\"cat26\"",
				"\"numeroCorredor\":\"007\"");
		assertThat(ficha).contains("\"anio\":2026", "\"categoria\":\"cat26\"", "\"rama\":\"Femenino\"", "\"divisional\":\"A\"",
				"\"numeroCorredor\":\"007\"", "\"estadoDeportivo\":\"ACTIVO\"");
		assertThat(historial.split("\"temporadaActiva\":true", -1)).hasSize(2);
	}

	// ---------- sesion, efectos y metodos ----------

	@Test
	void unaFamiliaOUnUsuarioDesactivadosPierdenLaSesionYNoRecibenDatos() throws Exception {
		Cookie sesion = sesionFa1();
		datos.desactivarFamilia(fa1);
		sesionMuerta(pedir(ficha(dPropio), sesion));
		datos.reactivarFamilia(fa1);
		pedir(ficha(dPropio), sesion).andExpect(status().isOk());

		datos.desactivarUsuario(usuarioFa1);
		sesionMuerta(pedir(ficha(dPropio), sesion));
	}

	@Test
	void sinSesionDa401YUnAdminDa403() throws Exception {
		mvc.perform(get(ficha(dPropio))).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
		Cookie admin = new Cookie(COOKIE, tokens.emitir(adminId, Rol.ADMIN, escuelaA, null));
		mvc.perform(get(ficha(dPropio)).cookie(admin)).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
	}

	@Test
	void leerLaFichaNoEscribeNadaNiAudita() throws Exception {
		long antes = auditoria();

		pedir(ficha(dPropio), sesionFa1()).andExpect(status().isOk());
		pedir(ficha(dDeFa2), sesionFa1()).andExpect(status().isNotFound());

		assertThat(auditoria()).isEqualTo(antes);
	}

	@Test
	void cualquierMetodoQueEscribeDa405ConCsrfY403SinCsrf() throws Exception {
		for (MockHttpServletRequestBuilder escritura : List.of(post(ficha(dPropio)), put(ficha(dPropio)), patch(ficha(dPropio)),
				delete(ficha(dPropio)))) {
			mvc.perform(conCsrf(escritura).cookie(sesionFa1())).andExpect(status().isMethodNotAllowed())
					.andExpect(jsonPath("$.codigo").value("METODO_NO_PERMITIDO"));
		}
		mvc.perform(post(ficha(dPropio)).cookie(sesionFa1())).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
	}
}
