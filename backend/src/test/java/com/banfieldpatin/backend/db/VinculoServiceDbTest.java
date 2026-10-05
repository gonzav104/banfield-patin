package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;

import com.banfieldpatin.backend.FixturesDominio;
import com.banfieldpatin.backend.compartido.auditoria.AuditoriaService;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.error.RestriccionViolada;
import com.banfieldpatin.backend.deportistas.DeportistaRepository;
import com.banfieldpatin.backend.familias.FamiliaRepository;
import com.banfieldpatin.backend.familias.vinculos.FamiliaDeportistaRepository;
import com.banfieldpatin.backend.familias.vinculos.VinculoAdminService;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Auditoria, red de seguridad de los indices y logs saneados del servicio de vinculos contra PostgreSQL real. Para ejercer
 * la red de seguridad (las violaciones de uq_fd_principal_activo / uq_familia_deportista que el bloqueo de fila y las
 * comprobaciones previas evitan) se arma un servicio con repositorios decorados que se saltan esas comprobaciones.
 */
@PruebaDb
@Import(ConfigFamiliasAdminDb.class)
@TestPropertySource(properties = { "spring.datasource.hikari.maximum-pool-size=3",
		"spring.datasource.hikari.minimum-idle=1", "spring.jpa.properties.hibernate.generate_statistics=true" })
class VinculoServiceDbTest extends BaseVinculosDb {

	// Cadenas propias de esta prueba (no un patron generico de digitos: los UUID pueden ser todo digitos).
	private static final String DNI = "36789012";
	private static final String CUIL = "20367890127";

	@Autowired
	FamiliaRepository familias;
	@Autowired
	DeportistaRepository deportistas;
	@Autowired
	AuditoriaService auditoria;
	@Autowired
	Clock reloj;

	private ListAppender<ILoggingEvent> logs;
	private Logger raiz;

	@BeforeEach
	void capturarLogs() {
		raiz = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		logs = new ListAppender<>();
		logs.start();
		raiz.addAppender(logs);
	}

	@AfterEach
	void liberarLogs() {
		raiz.detachAppender(logs);
	}

	private UUID deportistaConDocumentos() {
		return datos.deportistaConCuil(escuelaA, DNI, CUIL, "Zuleica", "Quintana");
	}

	// ---------- auditoria ----------

	@Test
	void laAuditoriaSoloRegistraCambiosRealesConIdsYBanderasSinDocumentosNiNombres() {
		UUID f1 = datos.familia(escuelaA, "Familia Secreta", true);
		UUID f2 = datos.familia(escuelaA, "Otra", true);
		UUID d = deportistaConDocumentos();
		UUID vinculoF1 = servicio.vincular(admin, f1, List.of(d), DATOS).resultados().get(0).vinculo().vinculoId();
		servicio.vincular(admin, f1, List.of(d), DATOS); // SIN_CAMBIOS: sin auditoria
		servicio.vincular(admin, f2, List.of(d), DATOS);
		servicio.cambiarPrincipal(admin, f2, d, DATOS);
		servicio.cambiarPrincipal(admin, f2, d, DATOS); // ya es principal: sin auditoria
		servicio.revocar(admin, f1, d, DATOS);
		servicio.revocar(admin, f1, d, DATOS); // repetida: sin auditoria
		servicio.vincular(admin, f1, List.of(d), DATOS); // reutiliza la fila

		List<Map<String, Object>> eventos = jdbc.sql("""
				SELECT accion, usuario_id, recurso_tipo, recurso_id, ip::text AS ip, user_agent, detalle::text AS detalle
				FROM gestion_patin.auditoria WHERE escuela_id = :e ORDER BY id
				""").param("e", escuelaA).query().listOfRows();

		assertThat(eventos).extracting(e -> e.get("accion")).containsExactly("VINCULO_ACTIVADO", "VINCULO_ACTIVADO",
				"VINCULO_PRINCIPAL_CAMBIADO", "VINCULO_REVOCADO", "VINCULO_ACTIVADO");
		assertThat(eventos).allSatisfy(e -> {
			assertThat(e.get("usuario_id")).isEqualTo(adminId);
			assertThat(e.get("recurso_tipo")).isEqualTo("FAMILIA_DEPORTISTA");
			assertThat((String) e.get("ip")).startsWith("10.0.0.9");
			assertThat(e.get("user_agent")).isEqualTo("JUnit");
			assertThat((String) e.get("detalle")).doesNotContain(DNI).doesNotContain(CUIL).doesNotContain("Zuleica")
					.doesNotContain("Quintana").doesNotContain("Familia Secreta");
		});
		assertThat((String) eventos.get(0).get("detalle")).contains("NUEVO").contains("\"esPrincipal\": true");
		assertThat((String) eventos.get(1).get("detalle")).contains("NUEVO").contains("\"esPrincipal\": false");
		assertThat((String) eventos.get(2).get("detalle")).contains(vinculoF1.toString());
		assertThat((String) eventos.get(3).get("detalle")).contains("\"eraPrincipal\": false");
		assertThat((String) eventos.get(4).get("detalle")).contains("REUTILIZADO");
		assertThat(eventos.get(0).get("recurso_id")).isEqualTo(vinculoF1);
		assertThat(eventos.get(4).get("recurso_id")).isEqualTo(vinculoF1);
	}

	@Test
	void sinPrincipalPreviaElCambioAuditaAnteriorVinculoIdNuloYUnaFalloDeNegocioNoAudita() {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID d = deportista("Uno");
		servicio.vincular(admin, f1, List.of(d), DATOS);
		servicio.revocar(admin, f1, d, DATOS);
		servicio.vincular(admin, f1, List.of(d), DATOS);
		// Se baja el principal por fuera para simular "no hay principal previo" y se vuelve a promover.
		jdbc.sql("UPDATE gestion_patin.familia_deportista SET es_principal = false WHERE familia_id = :f").param("f", f1).update();

		servicio.cambiarPrincipal(admin, f1, d, DATOS);

		assertThat(jdbc.sql("""
				SELECT detalle->'anteriorVinculoId' IS NOT NULL AS clave, detalle->>'anteriorVinculoId' IS NULL AS nulo
				FROM gestion_patin.auditoria WHERE accion = 'VINCULO_PRINCIPAL_CAMBIADO' AND escuela_id = :e
				""").param("e", escuelaA).query().singleRow()).containsEntry("clave", true).containsEntry("nulo", true);
	}

	// ---------- red de seguridad de los indices (se salta el bloqueo y las comprobaciones previas) ----------

	@SuppressWarnings("unchecked")
	private static <T> T decorar(Class<T> tipo, T real, Map<String, Function<Object[], Object>> sustituciones) {
		return (T) Proxy.newProxyInstance(tipo.getClassLoader(), new Class<?>[] { tipo }, (proxy, metodo, args) -> {
			Function<Object[], Object> sustituto = sustituciones.get(metodo.getName());
			if (sustituto != null) {
				return sustituto.apply(args);
			}
			try {
				return metodo.invoke(real, args);
			} catch (InvocationTargetException e) {
				throw e.getCause();
			}
		});
	}

	/** Servicio SIN bloqueo de fila y SIN comprobaciones previas de principal ni de vinculos existentes. */
	private VinculoAdminService servicioSinProtecciones(boolean ignorarExistentes) {
		DeportistaRepository sinBloqueo = decorar(DeportistaRepository.class, deportistas, Map.of("bloquearParaVincular",
				args -> deportistas.findAllById((java.util.Collection<UUID>) args[1]).stream()
						.filter(x -> x.getEscuelaId().equals(args[0])).toList()));
		FamiliaDeportistaRepository sinComprobaciones = decorar(FamiliaDeportistaRepository.class, repositorio,
				ignorarExistentes
						? Map.of("principalesActivos", args -> List.of(), "deFamiliaYDeportistas", args -> List.of())
						: Map.of("principalesActivos", args -> List.of()));
		return new VinculoAdminService(familias, sinBloqueo, sinComprobaciones, auditoria, reloj);
	}

	@Test
	void unaViolacionDelIndiceDePrincipalQueGanaLaBaseDa409SeRevierteElLoteCompletoYDejaUnWarnSaneado() throws Exception {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID f2 = datos.familia(escuelaA, "Dos", true);
		UUID d = deportistaConDocumentos();
		UUID otro = deportista("Otro");
		VinculoAdminService sinProtecciones = servicioSinProtecciones(false);

		// A (servicio real) crea el vinculo principal SIN confirmar; B (sin protecciones) intenta otro principal para el
		// mismo deportista tras insertar primero a "otro": se queda esperando el indice unico y, al confirmar A, la base lo
		// rechaza. Se traduce a 409 y el lote entero de B (incluido "otro" y su auditoria) se revierte.
		Par par = bBloqueadaDetrasDeA(() -> servicio.vincular(admin, f1, List.of(d), DATOS),
				() -> transaccion.execute(s -> sinProtecciones.vincular(admin, f2, List.of(otro, d), DATOS)));

		assertThat(par.a()).isNotNull();
		assertThat(par.b()).isInstanceOfSatisfying(ExcepcionNegocio.class, e -> {
			assertThat(e.getCodigo()).isEqualTo("VINCULO_PRINCIPAL_EN_CONFLICTO");
			assertThat(e.getMessage()).isEqualTo("Otro cambio sobre el mismo deportista ocurrio al mismo tiempo. Reintentá.");
		});
		assertThat(fila(f1, d).orElseThrow()).containsEntry("es_principal", true);
		assertThat(fila(f2, d)).isEmpty();
		assertThat(fila(f2, otro)).as("el otro item del lote tambien se revierte").isEmpty();
		assertThat(contarAuditoria("VINCULO_ACTIVADO")).isEqualTo(1);
		assertThat(principalesActivos(d)).isEqualTo(1);
		verificarInvariantes();

		// Un WARN saneado por la violacion mapeada: restriccion, operacion y clase; nada mas.
		List<ILoggingEvent> restricciones = logs.list.stream().filter(e -> e.getLoggerName().endsWith("RestriccionViolada"))
				.toList();
		assertThat(restricciones).singleElement().satisfies(e -> {
			assertThat(e.getLevel()).isEqualTo(Level.WARN);
			assertThat(e.getFormattedMessage()).isEqualTo("Violacion de restriccion mapeada a conflicto: "
					+ "restriccion=uq_fd_principal_activo operacion=VinculoAdminService.vincular "
					+ "excepcion=org.springframework.dao.DataIntegrityViolationException");
			assertThat(e.getThrowableProxy()).isNull();
		});
		sinDatosSensiblesEnLogs(f1, f2, d, otro);
	}

	@Test
	void unaViolacionDelParFamiliaDeportistaSeTraduceAlMismo409YDejaUnWarnConSuRestriccion() {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID d = deportistaConDocumentos();
		servicio.vincular(admin, f1, List.of(d), DATOS);

		// Sin la comprobacion previa de vinculos existentes, el segundo alta del mismo par viola uq_familia_deportista.
		Throwable e = catchThrowable(() -> transaccion.execute(s -> servicioSinProtecciones(true).vincular(admin, f1, List.of(d), DATOS)));

		assertThat(e).isInstanceOfSatisfying(ExcepcionNegocio.class,
				ex -> assertThat(ex.getCodigo()).isEqualTo("VINCULO_PRINCIPAL_EN_CONFLICTO"));
		assertThat(filas(d)).hasSize(1);
		assertThat(contarAuditoria("VINCULO_ACTIVADO")).isEqualTo(1);
		assertThat(logs.list.stream().filter(l -> l.getLoggerName().endsWith("RestriccionViolada")))
				.singleElement().satisfies(l -> {
					assertThat(l.getLevel()).isEqualTo(Level.WARN);
					assertThat(l.getFormattedMessage()).contains("restriccion=uq_familia_deportista")
							.contains("operacion=VinculoAdminService.vincular");
				});
		sinDatosSensiblesEnLogs(f1, d);
	}

	@Test
	@SuppressWarnings("unchecked")
	void unaViolacionSinMapearConUnMensajeRealDePostgresSeRegistraEnErrorSoloConRestriccionOperacionYClase() {
		UUID d = deportistaConDocumentos();
		UUID inexistente = UUID.randomUUID();
		// Una familia que NO existe en la base (el repositorio decorado la inventa): el INSERT viola una FK compuesta.
		FamiliaRepository inventa = decorar(FamiliaRepository.class, familias, Map.of("findByIdAndEscuelaId",
				args -> Optional.of(FixturesDominio.familia((UUID) args[0], (UUID) args[1], true))));
		VinculoAdminService servicioConFamiliaFalsa = new VinculoAdminService(inventa, deportistas, repositorio, auditoria,
				reloj);

		Throwable e = catchThrowable(() -> transaccion.execute(s -> servicioConFamiliaFalsa.vincular(admin, inexistente, List.of(d), DATOS)));

		// La excepcion REAL arrastra el mensaje del servidor con los valores de la clave: es lo que NO debe llegar al log.
		assertThat(e).isInstanceOf(DataIntegrityViolationException.class);
		assertThat(e.getMessage() + causas(e)).contains(inexistente.toString()).contains("not present");
		assertThat(RestriccionViolada.nombre((DataIntegrityViolationException) e)).contains("fk_fd_familia_misma_escuela");
		assertThat(contar("familia_deportista", escuelaA)).isZero();

		RestriccionViolada.registrarNoMapeada("POST /api/admin/familias/{familiaId}/deportistas", (DataIntegrityViolationException) e);

		assertThat(logs.list.stream().filter(l -> l.getLoggerName().endsWith("RestriccionViolada")))
				.singleElement().satisfies(l -> {
					assertThat(l.getLevel()).isEqualTo(Level.ERROR);
					assertThat(l.getFormattedMessage()).isEqualTo("Violacion de restriccion sin mapear (500): "
							+ "restriccion=fk_fd_familia_misma_escuela operacion=POST /api/admin/familias/{familiaId}/deportistas "
							+ "excepcion=org.springframework.dao.DataIntegrityViolationException");
					assertThat(l.getThrowableProxy()).isNull();
				});
		sinDatosSensiblesEnLogs(inexistente, d);
	}

	private static String causas(Throwable e) {
		StringBuilder texto = new StringBuilder();
		for (Throwable c = e; c != null; c = c.getCause() == c ? null : c.getCause()) {
			texto.append(' ').append(c.getMessage());
		}
		return texto.toString();
	}

	// ---------- logs ----------

	@Test
	void ningunLogDeLasOperacionesNormalesContieneDocumentosNombresNiIdsDePersonas() {
		UUID f1 = datos.familia(escuelaA, "Familia Secreta", true);
		UUID f2 = datos.familia(escuelaA, "Otra", true);
		UUID d = deportistaConDocumentos();

		servicio.vincular(admin, f1, List.of(d), DATOS);
		servicio.vincular(admin, f2, List.of(d), DATOS);
		servicio.cambiarPrincipal(admin, f2, d, DATOS);
		servicio.revocar(admin, f1, d, DATOS);
		catchThrowable(() -> servicio.vincular(admin, f1, List.of(UUID.randomUUID()), DATOS));

		sinDatosSensiblesEnLogs(f1, f2, d);
	}

	/** Ningun evento de log (cualquier nivel capturado) contiene documentos, nombres, ids de las partes ni texto de PostgreSQL. */
	private void sinDatosSensiblesEnLogs(UUID... ids) {
		raiz.detachAppender(logs);
		for (ILoggingEvent evento : logs.list) {
			String texto = evento.getFormattedMessage() + " " + evento.getThrowableProxy();
			assertThat(texto).as("log de %s", evento.getLoggerName()).doesNotContain(DNI).doesNotContain(CUIL)
					.doesNotContain("Zuleica").doesNotContain("Quintana").doesNotContain("Familia Secreta")
					.doesNotContain("duplicate key").doesNotContain("Detail:").doesNotContain("Key (")
					.doesNotContain("is not present").doesNotContain(escuelaA.toString());
			for (UUID id : ids) {
				assertThat(texto).as("log de %s", evento.getLoggerName()).doesNotContain(id.toString());
			}
		}
	}
}
