package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.banfieldpatin.backend.familias.portal.EdadDeportista;
import com.banfieldpatin.backend.seguridad.ServicioTokens;
import com.banfieldpatin.backend.usuarios.Rol;

import jakarta.servlet.http.Cookie;

/**
 * El listado de FAMILIA agrega {@code edad} (calculada en el servidor, la MISMA regla de la ficha) y MANTIENE por ahora
 * {@code fechaNacimiento}: HTTP real, PostgreSQL 17 descartable y cookies de usuarios PERSISTIDOS. Paginacion, orden y alcance
 * no cambian.
 */
@Tag("db")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
		"spring.datasource.hikari.maximum-pool-size=3", "spring.datasource.hikari.minimum-idle=1" })
@AutoConfigureMockMvc
@ActiveProfiles({ "test", "e2e" })
@Import(SesionVigenteHttpDbTest.Soporte.class)
class FamiliaPortalListadoEdadHttpDbTest extends BaseDbTest {

	private static final String COOKIE = "BP_SESION";
	private static final String LISTADO = "/api/familia/deportistas";

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
	private UUID usuarioFa1;
	private UUID usuarioFa2;
	/** Los deportistas propios, en el orden esperado del listado (apellido, nombre). */
	private final List<UUID> propios = new ArrayList<>();
	private final List<LocalDate> nacimientos = new ArrayList<>();
	private final List<Integer> edades = new ArrayList<>();

	@BeforeEach
	void preparar() {
		datos = new DatosDb(jdbc);
		contador.baseCaida.set(false);
		escuelaA = datos.escuela("lista-a-" + UUID.randomUUID());
		escuelaB = datos.escuela("lista-b-" + UUID.randomUUID());
		adminId = datos.admin(escuelaA, "admin@lista.example", true);
		UUID adminB = datos.admin(escuelaB, "admin@lista-b.example", true);
		fa1 = datos.familia(escuelaA, "Familia Uno", true);
		fa2 = datos.familia(escuelaA, "Familia Dos", true);
		UUID fb1 = datos.familia(escuelaB, "Familia B", true);
		usuarioFa1 = datos.usuarioFamilia(escuelaA, fa1, "uno@lista.example");
		usuarioFa2 = datos.usuarioFamilia(escuelaA, fa2, "dos@lista.example");
		datos.usuarioFamilia(escuelaB, fb1, "b@lista.example");
		LocalDate hoy = LocalDate.now(EdadDeportista.ZONA_ESCUELA);
		// Apellidos en orden alfabetico: Aguirre < Bravo < Cano < Duarte < Esteban.
		propio("36100001", "Ana", "Aguirre", true, hoy.minusYears(9).minusDays(1), 9);
		propio("36100002", "Beto", "Bravo", true, null, null);
		propio("36100003", "Cata", "Cano", true, hoy.plusYears(1), null); // fecha futura: sin dato fiable
		propio("36100004", "Dora", "Duarte", true, hoy.minusYears(120), 120); // limite exacto
		propio("36100005", "Ema", "Esteban", false, hoy.minusYears(3).minusDays(1), 3); // inactiva con vinculo activo
		// Ruido que NO debe verse: revocado, de otra familia y de otra escuela.
		UUID revocado = datos.deportista(escuelaA, "36100010", "Rev", "Ruido", true);
		datos.vinculo(escuelaA, fa1, revocado, "REVOCADO", false, adminId);
		datos.vinculo(escuelaA, fa2, datos.deportista(escuelaA, "36100011", "Zeta", "DeOtra", true), "ACTIVO", true, adminId);
		datos.vinculo(escuelaB, fb1, datos.deportista(escuelaB, "36100012", "Beta", "DeB", true), "ACTIVO", true, adminB);
	}

	private void propio(String dni, String nombre, String apellido, boolean activo, LocalDate nacimiento, Integer edad) {
		UUID d = datos.deportista(escuelaA, dni, nombre, apellido, activo);
		datos.vinculo(escuelaA, fa1, d, "ACTIVO", propios.isEmpty(), adminId);
		jdbc.sql("UPDATE gestion_patin.deportista SET fecha_nacimiento = :f WHERE id = :d").param("f", nacimiento)
				.param("d", d).update();
		propios.add(d);
		nacimientos.add(nacimiento);
		edades.add(edad);
	}

	@AfterEach
	void limpiar() {
		contador.baseCaida.set(false);
		datos.limpiarEscuela(escuelaA);
		datos.limpiarEscuela(escuelaB);
	}

	private Cookie sesionFa1() {
		return new Cookie(COOKIE, tokens.emitir(usuarioFa1, Rol.FAMILIA, escuelaA, fa1));
	}

	private ResultActions pedir(String ruta, Cookie sesion) throws Exception {
		return mvc.perform(get(ruta).cookie(sesion));
	}

	private String cuerpo(String ruta, Cookie sesion) throws Exception {
		return pedir(ruta, sesion).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	private String fila(int i, String nombre, String apellido, boolean activo) {
		String fecha = nacimientos.get(i) == null ? "null" : "\"" + nacimientos.get(i) + "\"";
		String edad = edades.get(i) == null ? "null" : String.valueOf(edades.get(i));
		return "{\"id\":\"" + propios.get(i) + "\",\"nombre\":\"" + nombre + "\",\"apellido\":\"" + apellido
				+ "\",\"fechaNacimiento\":" + fecha + ",\"edad\":" + edad + ",\"activo\":" + activo + "}";
	}

	@Test
	void cadaFilaTrae_edad_ademasDeLaFechaDeNacimientoQueSeMantieneTemporalmente() throws Exception {
		String esperado = "{\"contenido\":[" + fila(0, "Ana", "Aguirre", true) + "," + fila(1, "Beto", "Bravo", true) + ","
				+ fila(2, "Cata", "Cano", true) + "," + fila(3, "Dora", "Duarte", true) + ","
				+ fila(4, "Ema", "Esteban", false) + "],\"pagina\":0,\"tamanio\":20,\"totalElementos\":5}";

		assertThat(cuerpo(LISTADO, sesionFa1())).isEqualTo(esperado);
	}

	@Test
	void laEdadSeCalculaConLaReglaUnicaYNuncaSeInventa() throws Exception {
		pedir(LISTADO, sesionFa1()).andExpect(status().isOk()).andExpect(jsonPath("$.contenido[0].edad").value(9))
				.andExpect(jsonPath("$.contenido[1].edad").value((Object) null))
				.andExpect(jsonPath("$.contenido[2].edad").value((Object) null))
				.andExpect(jsonPath("$.contenido[3].edad").value(120)).andExpect(jsonPath("$.contenido[4].edad").value(3));
	}

	@Test
	void elListadoYLaFichaDanLaMismaEdadParaCadaDeportista() throws Exception {
		for (int i = 0; i < propios.size(); i++) {
			Object esperada = edades.get(i);
			pedir(LISTADO, sesionFa1()).andExpect(jsonPath("$.contenido[" + i + "].edad").value(esperada));
			pedir(LISTADO + "/" + propios.get(i) + "/ficha", sesionFa1()).andExpect(status().isOk())
					.andExpect(jsonPath("$.edad").value(esperada));
		}
	}

	@Test
	void laPaginacionYElOrdenNoCambian() throws Exception {
		pedir(LISTADO + "?page=0&size=2", sesionFa1()).andExpect(jsonPath("$.contenido.length()").value(2))
				.andExpect(jsonPath("$.contenido[0].apellido").value("Aguirre"))
				.andExpect(jsonPath("$.contenido[1].apellido").value("Bravo")).andExpect(jsonPath("$.pagina").value(0))
				.andExpect(jsonPath("$.tamanio").value(2)).andExpect(jsonPath("$.totalElementos").value(5));
		pedir(LISTADO + "?page=2&size=2", sesionFa1()).andExpect(jsonPath("$.contenido.length()").value(1))
				.andExpect(jsonPath("$.contenido[0].apellido").value("Esteban")).andExpect(jsonPath("$.contenido[0].edad").value(3))
				.andExpect(jsonPath("$.totalElementos").value(5));
		pedir(LISTADO + "?page=9&size=2", sesionFa1()).andExpect(jsonPath("$.contenido.length()").value(0))
				.andExpect(jsonPath("$.totalElementos").value(5));
	}

	@Test
	void elAlcanceNoCambiaSoloLosVinculosActivosPropiosEIncluyeAlInactivo() throws Exception {
		String cuerpo = cuerpo(LISTADO, sesionFa1());

		assertThat(cuerpo).doesNotContain("Ruido", "DeOtra", "DeB", "Zeta", "Beta");
		pedir(LISTADO, sesionFa1()).andExpect(jsonPath("$.totalElementos").value(5))
				.andExpect(jsonPath("$.contenido[4].activo").value(false));
		// La otra familia solo ve el suyo, con su propia edad nula (sin fecha).
		pedir(LISTADO, new Cookie(COOKIE, tokens.emitir(usuarioFa2, Rol.FAMILIA, escuelaA, fa2)))
				.andExpect(jsonPath("$.totalElementos").value(1)).andExpect(jsonPath("$.contenido[0].apellido").value("DeOtra"))
				.andExpect(jsonPath("$.contenido[0].edad").value((Object) null));
		pedir(LISTADO + "?familiaId=" + fa2 + "&escuelaId=" + escuelaB, sesionFa1()).andExpect(jsonPath("$.totalElementos").value(5));
	}

	@Test
	void ningunDatoSensibleSaleEnElListado() throws Exception {
		String cuerpo = cuerpo(LISTADO, sesionFa1());

		for (String prohibido : List.of("36100001", "36100005", "dni", "cuil", "domicilio", "telefono", "email", "escuelaId",
				"familiaId", "esPrincipal", "vinculo", "nacionalidad")) {
			assertThat(cuerpo).as(prohibido).doesNotContain(prohibido);
		}
	}

	@Test
	void sinSesionDa401UnAdminDa403YLasEscriturasDa405() throws Exception {
		mvc.perform(get(LISTADO)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
		Cookie admin = new Cookie(COOKIE, tokens.emitir(adminId, Rol.ADMIN, escuelaA, null));
		mvc.perform(get(LISTADO).cookie(admin)).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
		Cookie x = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		for (MockHttpServletRequestBuilder escritura : List.of(post(LISTADO), put(LISTADO), patch(LISTADO), delete(LISTADO))) {
			mvc.perform(escritura.cookie(x, sesionFa1()).header("X-XSRF-TOKEN", x.getValue()))
					.andExpect(status().isMethodNotAllowed());
		}
	}
}
