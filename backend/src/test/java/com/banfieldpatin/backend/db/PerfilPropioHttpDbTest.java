package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.familias.perfil.PerfilPropioService;
import com.banfieldpatin.backend.familias.perfil.dto.ReclamoSolicitud;
import com.banfieldpatin.backend.seguridad.ServicioTokens;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;

import jakarta.servlet.http.Cookie;

/**
 * U21-BIS C1 por HTTP con la cadena de seguridad completa y PostgreSQL 17: la familia declara su propio perfil y reclama a una
 * persona de SU familia. Declarar no autoriza: no cambia vínculos, roles ni responsabilidades. Dos familias para el aislamiento.
 */
@Tag("db")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
		"spring.datasource.hikari.maximum-pool-size=6", "spring.datasource.hikari.minimum-idle=1",
		"banfield.autoservicio.perfil-habilitado=true" })
@AutoConfigureMockMvc
@ActiveProfiles({ "test", "e2e" })
@Import(SesionVigenteHttpDbTest.Soporte.class)
class PerfilPropioHttpDbTest extends BaseDbTest {

	private static final String COOKIE = "BP_SESION";
	private static final String RUTA = "/api/familia/mi-perfil";
	private static final String DNI = "30111222";
	private static final String CUERPO = "{\"nombre\":\"Laura\",\"apellido\":\"Gomez\",\"dni\":\"30.111.222\","
			+ "\"telefono\":\"11 5555-0000\",\"email\":\"Laura@Example.Test\",\"parentesco\":\"Madre\"}";

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	ServicioTokens tokens;
	@Autowired
	PerfilPropioService servicio;
	@Autowired
	com.banfieldpatin.backend.familias.invitaciones.InvitacionRepository invitaciones;
	@Autowired
	org.springframework.transaction.PlatformTransactionManager transacciones;

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
	private UUID deOtraEscuela;
	private UUID deportista;

	@BeforeEach
	void preparar() {
		datos = new DatosDb(jdbc);
		escuela = datos.escuela("perfil-a-" + UUID.randomUUID());
		otraEscuela = datos.escuela("perfil-b-" + UUID.randomUUID());
		admin = datos.admin(escuela, "admin-" + UUID.randomUUID() + "@example.invalid", true);
		fam1 = datos.familia(escuela, "Familia Uno", true);
		fam2 = datos.familia(escuela, "Familia Dos", true);
		famOtraEscuela = datos.familia(otraEscuela, "Familia Otra Escuela", true);
		madre = datos.usuarioFamilia(escuela, fam1, "madre-" + UUID.randomUUID() + "@example.invalid");
		padre = datos.usuarioFamilia(escuela, fam1, "padre-" + UUID.randomUUID() + "@example.invalid");
		deFam2 = datos.usuarioFamilia(escuela, fam2, "dos-" + UUID.randomUUID() + "@example.invalid");
		deOtraEscuela = datos.usuarioFamilia(otraEscuela, famOtraEscuela, "b-" + UUID.randomUUID() + "@example.invalid");
		deportista = datos.deportista(escuela, "PER" + UUID.randomUUID().toString().substring(0, 8), "Nina", "Prueba");
		datos.vinculo(escuela, fam1, deportista, "ACTIVO", true, admin);
	}

	@AfterEach
	void limpiar() {
		datos.limpiarEscuela(escuela);
		datos.limpiarEscuela(otraEscuela);
	}

	// ---------- crear y leer ----------

	@Test
	void declararElPropioPerfilLoDejaDeclaradoSinTocarPermisosYSinFiltrarDatos() throws Exception {
		long vinculosAntes = count("familia_deportista");
		ResultActions r = mvc.perform(escribir(post(RUTA), CUERPO, madre, fam1, escuela));
		r.andExpect(status().isOk()).andExpect(jsonPath("$.habilitado").value(true))
				.andExpect(jsonPath("$.perfil.nombre").value("Laura")).andExpect(jsonPath("$.perfil.estado").value("DECLARADO"))
				.andExpect(jsonPath("$.perfil.identidadEditable").value(true))
				.andExpect(jsonPath("$.perfil.email").value("laura@example.test"))
				.andExpect(jsonPath("$.perfil.dniEnmascarado").exists()).andExpect(jsonPath("$.perfil.dni").doesNotExist());
		String cuerpo = r.andReturn().getResponse().getContentAsString();
		assertThat(cuerpo).doesNotContain(DNI).doesNotContain("30.111.222");

		Map<String, Object> fila = jdbc.sql("""
				SELECT origen, verificacion, vinculo_cuenta, usuario_id, declarado_por, familia_id, dni FROM gestion_patin.tutor
				WHERE usuario_id = :u
				""").param("u", madre).query().singleRow();
		assertThat(fila).containsEntry("origen", "FAMILIA").containsEntry("verificacion", "DECLARADO")
				.containsEntry("vinculo_cuenta", "DECLARADO").containsEntry("declarado_por", madre)
				.containsEntry("familia_id", fam1).containsEntry("dni", DNI);
		// Declarar no autoriza: ni vínculos nuevos, ni cambio de rol, ni responsabilidades.
		assertThat(count("familia_deportista")).isEqualTo(vinculosAntes);
		assertThat(count("tutor_deportista")).isZero();
		assertThat(jdbc.sql("SELECT rol FROM gestion_patin.usuario WHERE id = :u").param("u", madre).query(String.class)
				.single()).isEqualTo("FAMILIA");
		// Auditoría: nombres de campo y ids, nunca valores personales.
		var evento = jdbc.sql("SELECT usuario_id, detalle::text AS d FROM gestion_patin.auditoria WHERE escuela_id = :e AND accion = 'PERFIL_DECLARADO'")
				.param("e", escuela).query().singleRow();
		assertThat(evento.get("usuario_id")).isEqualTo(madre);
		assertThat((String) evento.get("d")).doesNotContain("Laura", "Gomez", DNI, "example.test");

		mvc.perform(get(RUTA).cookie(sesion(madre, fam1, escuela))).andExpect(status().isOk())
				.andExpect(jsonPath("$.perfil.nombre").value("Laura")).andExpect(jsonPath("$.personasSinCuenta.length()").value(0));
	}

	@Test
	void unaCuentaConPerfilNoPuedeCrearOtroYDosPedidosSimultaneosDejanUnoSolo() throws Exception {
		mvc.perform(escribir(post(RUTA), CUERPO, madre, fam1, escuela)).andExpect(status().isOk());
		mvc.perform(escribir(post(RUTA), CUERPO, madre, fam1, escuela)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.codigo").value("PERFIL_YA_EXISTE"));

		UsuarioAutenticado yo = new UsuarioAutenticado(padre, escuela, fam1, Rol.FAMILIA);
		ExecutorService hilos = Executors.newFixedThreadPool(2);
		try {
			CountDownLatch salida = new CountDownLatch(1);
			List<Future<Object>> resultados = List.of(1, 2).stream().map(i -> hilos.<Object>submit(() -> {
				salida.await(10, TimeUnit.SECONDS);
				try {
					return servicio.crear(yo, new com.banfieldpatin.backend.familias.perfil.dto.PerfilSolicitud("Martin", "Gomez",
							null, null, null, "Padre"), new DatosSolicitud(null, null));
				} catch (ExcepcionNegocio e) {
					return e.getCodigo();
				}
			})).toList();
			salida.countDown();
			long creados = 0;
			long conflictos = 0;
			for (Future<Object> f : resultados) {
				Object v = f.get(30, TimeUnit.SECONDS);
				if ("PERFIL_YA_EXISTE".equals(v)) {
					conflictos++;
				} else {
					creados++;
				}
			}
			assertThat(creados).isEqualTo(1);
			assertThat(conflictos).isEqualTo(1);
		} finally {
			hilos.shutdownNow();
		}
		assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.tutor WHERE usuario_id = :u").param("u", padre).query(Long.class)
				.single()).isEqualTo(1);
	}

	@Test
	void elCuerpoSoloAceptaLosDatosPersonalesYNuncaLaFamiliaLaEscuelaNiLaCuenta() throws Exception {
		for (String extra : List.of("\"familiaId\":\"" + fam2 + "\"", "\"escuelaId\":\"" + otraEscuela + "\"",
				"\"usuarioId\":\"" + deFam2 + "\"", "\"verificacion\":\"VERIFICADO\"", "\"origen\":\"ADMIN\"")) {
			mvc.perform(escribir(post(RUTA), CUERPO.replace("}", "," + extra + "}"), madre, fam1, escuela))
					.andExpect(status().isBadRequest());
		}
		mvc.perform(escribir(post(RUTA), "{\"nombre\":\"\",\"apellido\":\"G\"}", madre, fam1, escuela)).andExpect(status().isBadRequest());
		mvc.perform(escribir(post(RUTA), CUERPO.replace("30.111.222", "abc"), madre, fam1, escuela)).andExpect(status().isBadRequest());
		assertThat(count("tutor")).isZero();
	}

	@Test
	void soloUnaCuentaFamiliaPuedeUsarloAdminAnonimoYSinCsrfNo() throws Exception {
		Cookie comoAdmin = new Cookie(COOKIE, tokens.emitir(admin, Rol.ADMIN, escuela, null, versionSesion(admin)));
		mvc.perform(get(RUTA).cookie(comoAdmin)).andExpect(status().isForbidden());
		mvc.perform(get(RUTA)).andExpect(status().isUnauthorized());
		mvc.perform(post(RUTA).cookie(sesion(madre, fam1, escuela)).contentType(MediaType.APPLICATION_JSON).content(CUERPO))
				.andExpect(status().isForbidden());
		assertThat(count("tutor")).isZero();
	}

	// ---------- editar: identidad vs contacto ----------

	@Test
	void editarExigeLaRevisionVistaYUnaIdentidadVerificadaNoSeCambiaSola() throws Exception {
		mvc.perform(escribir(post(RUTA), CUERPO, madre, fam1, escuela)).andExpect(status().isOk());
		String v0 = version(madre, true);

		// Mientras está DECLARADO la persona corrige todo.
		String cambioNombre = CUERPO.replace("Laura", "Laurita").replace("}", ",\"versionEsperada\":\"" + v0 + "\"}");
		mvc.perform(escribir(put(RUTA), cambioNombre, madre, fam1, escuela)).andExpect(status().isOk())
				.andExpect(jsonPath("$.perfil.nombre").value("Laurita"));
		// Revisión vieja: conflicto, sin pisar.
		mvc.perform(escribir(put(RUTA), cambioNombre.replace("Laurita", "Otra"), madre, fam1, escuela))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("EDICION_DESACTUALIZADA"));
		assertThat(jdbc.sql("SELECT nombre FROM gestion_patin.tutor WHERE usuario_id = :u").param("u", madre).query(String.class)
				.single()).isEqualTo("Laurita");
		// Sin DNI en el pedido (la familia nunca lo recibe completo) se conserva el cargado; no es un cambio de identidad.
		String v05 = version(madre, true);
		mvc.perform(escribir(put(RUTA), "{\"nombre\":\"Laurita\",\"apellido\":\"Gomez\",\"telefono\":\"11 7777-2222\",\"versionEsperada\":\"" + v05 + "\"}",
				madre, fam1, escuela)).andExpect(status().isOk()).andExpect(jsonPath("$.perfil.dniEnmascarado").exists());
		assertThat(jdbc.sql("SELECT dni FROM gestion_patin.tutor WHERE usuario_id = :u").param("u", madre).query(String.class).single())
				.isEqualTo(DNI);
		jdbc.sql("UPDATE gestion_patin.tutor SET telefono = '11 5555-0000', email = 'laura@example.test', parentesco = 'Madre' WHERE usuario_id = :u")
				.param("u", madre).update();
		// Sin cambios: no audita.
		long antes = auditoria("PERFIL_ACTUALIZADO");
		String v1 = version(madre, true);
		mvc.perform(escribir(put(RUTA), CUERPO.replace("\"nombre\":\"Laura\"", "\"nombre\":\"Laurita\"").replace("}", ",\"versionEsperada\":\"" + v1 + "\"}"), madre,
				fam1, escuela)).andExpect(status().isOk());
		assertThat(auditoria("PERFIL_ACTUALIZADO")).isEqualTo(antes);

		// ADMIN verifica la identidad (C2 traerá la pantalla): ahora solo el contacto es editable por la familia.
		jdbc.sql("UPDATE gestion_patin.tutor SET verificacion = 'VERIFICADO', version = version + 1 WHERE usuario_id = :u")
				.param("u", madre).update();
		String v2 = version(madre, true);
		mvc.perform(escribir(put(RUTA), CUERPO.replace("Laura", "Laurita").replace("Gomez", "Perez").replace("}", ",\"versionEsperada\":\"" + v2 + "\"}"),
				madre, fam1, escuela)).andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("IDENTIDAD_VERIFICADA"));
		mvc.perform(escribir(put(RUTA), CUERPO.replace("Laura", "Laurita").replace("30.111.222", "30.999.888").replace("}", ",\"versionEsperada\":\"" + v2 + "\"}"),
				madre, fam1, escuela)).andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("IDENTIDAD_VERIFICADA"));
		String soloContacto = CUERPO.replace("Laura", "Laurita").replace("11 5555-0000", "11 6666-1111")
				.replace("}", ",\"versionEsperada\":\"" + v2 + "\"}");
		mvc.perform(escribir(put(RUTA), soloContacto, madre, fam1, escuela)).andExpect(status().isOk())
				.andExpect(jsonPath("$.perfil.identidadEditable").value(false)).andExpect(jsonPath("$.perfil.telefono").value("11 6666-1111"));
		var d = jdbc.sql("SELECT detalle::text AS d FROM gestion_patin.auditoria WHERE escuela_id = :e AND accion = 'PERFIL_ACTUALIZADO' ORDER BY id DESC LIMIT 1")
				.param("e", escuela).query(String.class).single();
		assertThat(d).contains("telefono").doesNotContain("6666", "Laurita", "example.test");
	}

	@Test
	void editarSinPerfilEs404() throws Exception {
		mvc.perform(escribir(put(RUTA), CUERPO.replace("}", ",\"versionEsperada\":\"0\"}"), madre, fam1, escuela))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.codigo").value("PERFIL_NO_ENCONTRADO"));
	}

	// ---------- reclamar: «soy esta persona» ----------

	@Test
	void reclamarUnaPersonaDeMiFamiliaEsUnaDeclaracionQueNoVerificaNiConcedePermisos() throws Exception {
		UUID tutor = datos.tutor(escuela, fam1, "Laura", "Gomez");
		String v = version(tutor);
		mvc.perform(get(RUTA).cookie(sesion(madre, fam1, escuela))).andExpect(status().isOk())
				.andExpect(jsonPath("$.perfil").doesNotExist()).andExpect(jsonPath("$.personasSinCuenta.length()").value(1))
				.andExpect(jsonPath("$.personasSinCuenta[0].tutorId").value(tutor.toString()))
				.andExpect(jsonPath("$.personasSinCuenta[0].dni").doesNotExist());
		long vinculos = count("familia_deportista");

		mvc.perform(escribir(post(RUTA + "/reclamar-tutor"), "{\"tutorId\":\"" + tutor + "\",\"versionEsperada\":\"" + v + "\"}", madre, fam1,
				escuela)).andExpect(status().isOk()).andExpect(jsonPath("$.perfil.tutorId").value(tutor.toString()))
				.andExpect(jsonPath("$.personasSinCuenta.length()").value(0));

		Map<String, Object> fila = jdbc.sql("SELECT usuario_id, vinculo_cuenta, verificacion, origen, declarado_por, nombre FROM gestion_patin.tutor WHERE id = :t")
				.param("t", tutor).query().singleRow();
		// Declarado por la persona; la identidad del tutor (cargada por ADMIN) no cambia ni se acredita nada nuevo.
		assertThat(fila).containsEntry("usuario_id", madre).containsEntry("vinculo_cuenta", "DECLARADO")
				.containsEntry("verificacion", "VERIFICADO").containsEntry("origen", "ADMIN").containsEntry("declarado_por", madre)
				.containsEntry("nombre", "Laura");
		assertThat(count("familia_deportista")).isEqualTo(vinculos);
		assertThat(count("tutor_deportista")).isZero();
		assertThat(auditoria("TUTOR_RECLAMADO")).isEqualTo(1);
		// Ya tiene perfil: otro reclamo (u otro tutor) es un conflicto neutro.
		UUID otro = datos.tutor(escuela, fam1, "Otra", "Persona");
		mvc.perform(escribir(post(RUTA + "/reclamar-tutor"), "{\"tutorId\":\"" + otro + "\",\"versionEsperada\":\"" + version(otro) + "\"}", madre,
				fam1, escuela)).andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("PERSONA_NO_DISPONIBLE"));
	}

	@Test
	void unTutorAjenoInexistenteInactivoOYaReclamadoDanElMismoConflictoNeutro() throws Exception {
		UUID deOtraFamilia = datos.tutor(escuela, fam2, "Ajena", "Familia");
		UUID deLaOtraEscuela = datos.tutor(otraEscuela, famOtraEscuela, "Ajena", "Escuela");
		UUID inactivo = datos.tutor(escuela, fam1, "Inactivo", "Propio");
		jdbc.sql("UPDATE gestion_patin.tutor SET activo = false WHERE id = :t").param("t", inactivo).update();
		UUID yaReclamado = datos.tutor(escuela, fam1, "Reclamado", "Propio");
		String vYa = version(yaReclamado);
		mvc.perform(escribir(post(RUTA + "/reclamar-tutor"), "{\"tutorId\":\"" + yaReclamado + "\",\"versionEsperada\":\"" + vYa + "\"}", padre,
				fam1, escuela)).andExpect(status().isOk());
		UUID disponible = datos.tutor(escuela, fam1, "Disponible", "Propio");

		String esperado = null;
		for (UUID objetivo : List.of(deOtraFamilia, deLaOtraEscuela, UUID.randomUUID(), inactivo, yaReclamado)) {
			// Con la revisión VIGENTE cuando el tutor existe: lo que impide el reclamo es que ya tiene cuenta, no una revisión vieja.
			String revision = jdbc.sql("SELECT version FROM gestion_patin.tutor WHERE id = :t").param("t", objetivo).query(Long.class)
					.optional().map(String::valueOf).orElse("0");
			String cuerpo = mvc.perform(escribir(post(RUTA + "/reclamar-tutor"),
					"{\"tutorId\":\"" + objetivo + "\",\"versionEsperada\":\"" + revision + "\"}", madre, fam1, escuela))
					.andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("PERSONA_NO_DISPONIBLE")).andReturn()
					.getResponse().getContentAsString();
			if (esperado == null) {
				esperado = cuerpo;
			}
			assertThat(cuerpo).isEqualTo(esperado);
		}
		// Revisión vieja: también conflicto neutro, y el tutor sigue libre.
		mvc.perform(escribir(post(RUTA + "/reclamar-tutor"), "{\"tutorId\":\"" + disponible + "\",\"versionEsperada\":\"99\"}", madre, fam1,
				escuela)).andExpect(status().isConflict());
		assertThat(jdbc.sql("SELECT usuario_id FROM gestion_patin.tutor WHERE id = :t").param("t", disponible).query(UUID.class)
				.optional()).isEmpty();
		assertThat(jdbc.sql("SELECT usuario_id FROM gestion_patin.tutor WHERE id = :t").param("t", deOtraFamilia).query().singleRow()
				.get("usuario_id")).isNull();
	}

	@Test
	void dosCuentasReclamandoALaMismaPersonaALaVezDejanUnSoloGanador() throws Exception {
		UUID tutor = datos.tutor(escuela, fam1, "Persona", "Libre");
		long v = Long.parseLong(version(tutor));
		ExecutorService hilos = Executors.newFixedThreadPool(2);
		try {
			CountDownLatch salida = new CountDownLatch(1);
			List<Future<String>> r = List.of(madre, padre).stream().map(u -> hilos.submit(() -> {
				salida.await(10, TimeUnit.SECONDS);
				try {
					servicio.reclamar(new UsuarioAutenticado(u, escuela, fam1, Rol.FAMILIA), new ReclamoSolicitud(tutor, v),
							new DatosSolicitud(null, null));
					return "OK";
				} catch (ExcepcionNegocio e) {
					return e.getCodigo();
				}
			})).toList();
			salida.countDown();
			List<String> res = new java.util.ArrayList<>();
			for (Future<String> f : r) {
				res.add(f.get(30, TimeUnit.SECONDS));
			}
			assertThat(res).containsExactlyInAnyOrder("OK", "PERSONA_NO_DISPONIBLE");
		} catch (ExecutionException e) {
			throw e;
		} finally {
			hilos.shutdownNow();
		}
		assertThat(jdbc.sql("SELECT count(*) FROM gestion_patin.tutor WHERE id = :t AND usuario_id IS NOT NULL").param("t", tutor)
				.query(Long.class).single()).isEqualTo(1);
		assertThat(auditoria("TUTOR_RECLAMADO")).isEqualTo(1);
	}

	@Test
	void laListaDePersonasSinCuentaSoloMuestraLaPropiaFamiliaYNuncaDatosSensibles() throws Exception {
		datos.tutor(escuela, fam1, "Propia", "Uno");
		datos.tutor(escuela, fam2, "Ajena", "Dos");
		datos.tutor(otraEscuela, famOtraEscuela, "Ajena", "Tres");
		String cuerpo = mvc.perform(get(RUTA).cookie(sesion(madre, fam1, escuela))).andExpect(status().isOk())
				.andExpect(jsonPath("$.personasSinCuenta.length()").value(1)).andReturn().getResponse().getContentAsString();
		assertThat(cuerpo).contains("Propia").doesNotContain("Ajena").doesNotContain("dni").doesNotContain("email").doesNotContain("telefono");
		mvc.perform(get(RUTA).cookie(sesion(deFam2, fam2, escuela))).andExpect(jsonPath("$.personasSinCuenta[0].apellido").value("Dos"));
	}

	// ---------- invitación a un tutor concreto: la cuenta nace vinculada y VERIFICADA ----------

	@Test
	void alRegistrarseLaCuentaInvitadaQuedaVinculadaYVerificadaYSiOtroLoReclamoElRegistroSigueSinVinculo() {
		UUID tutor = datos.tutor(escuela, fam1, "Laura", "Gomez");
		UUID invitacion = invitacionParaTutor(tutor, admin);
		assertThat(vincular(invitacion, madre)).isEqualTo(1);
		Map<String, Object> fila = jdbc.sql("SELECT usuario_id, vinculo_cuenta, declarado_por, verificacion, origen FROM gestion_patin.tutor WHERE id = :t")
				.param("t", tutor).query().singleRow();
		assertThat(fila).containsEntry("usuario_id", madre).containsEntry("vinculo_cuenta", "VERIFICADO")
				.containsEntry("declarado_por", admin).containsEntry("origen", "ADMIN");
		assertThat(count("familia_deportista")).isEqualTo(1);

		// Otro tutor reclamado por una persona de la familia antes de que la invitada se registre: no cambia nada (0).
		UUID otro = datos.tutor(escuela, fam1, "Otro", "Gomez");
		UUID invitacionOtro = invitacionParaTutor(otro, admin);
		jdbc.sql("UPDATE gestion_patin.tutor SET usuario_id = :u, vinculo_cuenta = 'DECLARADO' WHERE id = :t").param("u", padre).param("t", otro)
				.update();
		assertThat(vincular(invitacionOtro, deFam2)).isZero();
		assertThat(jdbc.sql("SELECT usuario_id FROM gestion_patin.tutor WHERE id = :t").param("t", otro).query(UUID.class).single())
				.isEqualTo(padre);
	}

	/** Como en el registro real: dentro de la transacción del servicio. */
	private int vincular(UUID invitacion, UUID usuario) {
		return new org.springframework.transaction.support.TransactionTemplate(transacciones)
				.execute(st -> invitaciones.vincularTutor(invitacion, usuario));
	}

	private UUID invitacionParaTutor(UUID tutor, UUID creadaPor) {
		return jdbc.sql("""
				INSERT INTO gestion_patin.invitacion (escuela_id, familia_id, tutor_id, token_hash, expira_en, creada_por, creado_en)
				VALUES (:e, :f, :t, :h, now() + interval '1 day', :a, now()) RETURNING id
				""").param("e", escuela).param("f", fam1).param("t", tutor).param("h", UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""))
				.param("a", creadaPor).query(UUID.class).single();
	}

	// ---------- la base impone la coherencia ----------

	@Test
	void laBaseNoDejaAsociarUnaCuentaAUnTutorDeOtraFamiliaNiVinculoSinCuenta() {
		UUID tutor = datos.tutor(escuela, fam1, "Persona", "Libre");
		assertThatThrownBy(() -> jdbc.sql("UPDATE gestion_patin.tutor SET usuario_id = :u, vinculo_cuenta = 'DECLARADO' WHERE id = :t")
				.param("u", deFam2).param("t", tutor).update()).hasMessageContaining("fk_tutor_cuenta_misma_familia");
		assertThatThrownBy(() -> jdbc.sql("UPDATE gestion_patin.tutor SET usuario_id = :u WHERE id = :t").param("u", madre)
				.param("t", tutor).update()).hasMessageContaining("ck_tutor_cuenta_vinculo");
		assertThatThrownBy(() -> jdbc.sql("UPDATE gestion_patin.tutor SET vinculo_cuenta = 'DECLARADO' WHERE id = :t").param("t", tutor)
				.update()).hasMessageContaining("ck_tutor_cuenta_vinculo");
	}

	// ---------- auxiliares ----------

	private MockHttpServletRequestBuilder escribir(MockHttpServletRequestBuilder req, String cuerpo, UUID usuario, UUID familia, UUID escuelaId)
			throws Exception {
		Cookie x = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		return req.cookie(x, sesion(usuario, familia, escuelaId)).header("X-XSRF-TOKEN", x.getValue())
				.contentType(MediaType.APPLICATION_JSON).content(cuerpo);
	}

	private Cookie sesion(UUID usuario, UUID familia, UUID escuelaId) {
		return new Cookie(COOKIE, tokens.emitir(usuario, Rol.FAMILIA, escuelaId, familia, versionSesion(usuario)));
	}

	private long versionSesion(UUID usuario) {
		return jdbc.sql("SELECT version_sesion FROM gestion_patin.usuario WHERE id = :u").param("u", usuario).query(Long.class).single();
	}

	/** Revisión del tutor del usuario ({@code porUsuario}) como cadena decimal. */
	private String version(UUID id, boolean porUsuario) {
		return jdbc.sql("SELECT version FROM gestion_patin.tutor WHERE usuario_id = :u").param("u", id).query(Long.class).single().toString();
	}

	private String version(UUID tutorId) {
		return jdbc.sql("SELECT version FROM gestion_patin.tutor WHERE id = :t").param("t", tutorId).query(Long.class).single().toString();
	}

	private long count(String tabla) {
		return jdbc.sql("SELECT count(*) FROM gestion_patin." + tabla + " WHERE escuela_id = :e").param("e", escuela).query(Long.class).single();
	}

	private long auditoria(String accion) {
		return jdbc.sql("SELECT count(*) FROM gestion_patin.auditoria WHERE escuela_id = :e AND accion = :a").param("e", escuela)
				.param("a", accion).query(Long.class).single();
	}
}
