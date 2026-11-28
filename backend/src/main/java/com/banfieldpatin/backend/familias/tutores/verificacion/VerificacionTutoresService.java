package com.banfieldpatin.backend.familias.tutores.verificacion;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.banfieldpatin.backend.compartido.auditoria.AccionAuditoria;
import com.banfieldpatin.backend.compartido.auditoria.AuditoriaService;
import com.banfieldpatin.backend.compartido.auditoria.EventoAuditoria;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.compartido.web.Pagina;
import com.banfieldpatin.backend.familias.FamiliaAdminService;
import com.banfieldpatin.backend.familias.perfil.ReglasAutoservicioTutores;
import com.banfieldpatin.backend.familias.tutores.Tutor;
import com.banfieldpatin.backend.familias.tutores.TutorAdminService;
import com.banfieldpatin.backend.familias.tutores.TutorRepository;
import com.banfieldpatin.backend.familias.tutores.verificacion.dto.DecisionVerificacion;
import com.banfieldpatin.backend.familias.tutores.verificacion.dto.EstadoVerificacion;
import com.banfieldpatin.backend.familias.tutores.verificacion.dto.LoteVerificacion;
import com.banfieldpatin.backend.familias.tutores.verificacion.dto.ResolucionVerificacion;
import com.banfieldpatin.backend.familias.tutores.verificacion.dto.ResultadoLote;
import com.banfieldpatin.backend.familias.tutores.verificacion.dto.TutorPorVerificar;
import com.banfieldpatin.backend.familias.tutores.verificacion.dto.VerificacionResultado;
import com.banfieldpatin.backend.familias.vinculos.LockTimeoutVinculos;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;

import jakarta.persistence.EntityManager;

/**
 * Bandeja y verificación administrativa de los datos de tutores declarados por las familias (U21-BIS C2).
 *
 * <ul>
 * <li><b>Qué garantiza:</b> {@code VERIFICADO} significa que ADMIN revisó la identidad y los datos del tutor tal como estaban
 * en la revisión que vio (CAS por {@code versionEsperada}). Es la única garantía de identidad (U22): aprobar la imagen de un
 * DNI no la otorga, y verificar no aprueba documentos ni concede acceso a deportistas o responsabilidades.</li>
 * <li><b>Alcance:</b> la escuela sale del token; un tutor de otra escuela es el mismo 404 que uno inexistente.</li>
 * <li><b>Bloqueos:</b> familia {@code FOR SHARE} → tutor {@code FOR NO KEY UPDATE}, el mismo orden que la edición familiar y
 * la carga documental, con {@code lock_timeout}. No toma el bloqueo consultivo de las altas familiares: nunca lo espera, así
 * que no puede formar un ciclo con ellas.</li>
 * <li><b>Repetición:</b> pedir el estado que el tutor ya tiene (mismo motivo, si es una observación) no cambia nada ni audita;
 * así un doble clic no falla.</li>
 * <li><b>Lote:</b> solo VERIFICAR y solo desde DECLARADO; cada tutor en su propia transacción, con su revisión y su resultado:
 * uno que falla (incluso por una falla inesperada, {@code ERROR}) no deshace ni deja sin resultado a los demás, y pasado
 * {@link #PLAZO_LOTE} los que faltan vuelven {@code NO_PROCESADO}.</li>
 * <li><b>Observar</b> solo lo DECLARADO u OBSERVADO: una identidad VERIFICADA no se reabre (409 {@code ESTADO_INCOMPATIBLE}).</li>
 * <li><b>Bloqueo vencido</b> en la decisión individual: 409 {@code CONFLICTO_CONCURRENCIA} (manejador global); en el lote,
 * {@code OCUPADO}.</li>
 * <li><b>Auditoría:</b> {@code TUTOR_VERIFICADO} / {@code TUTOR_OBSERVADO} con la familia y el estado anterior; nunca el motivo
 * ni datos personales.</li>
 * </ul>
 */
@Service
public class VerificacionTutoresService {

	private static final String RECURSO = "TUTOR";
	private static final Logger log = LoggerFactory.getLogger(VerificacionTutoresService.class);

	private static final String FILAS = """
			SELECT t.id, t.familia_id, f.nombre_referencia, t.nombre, t.apellido, t.dni, t.telefono, t.email, t.parentesco,
			       t.verificacion, t.motivo_observacion, t.origen, t.usuario_id IS NOT NULL AS tiene_cuenta,
			       t.vinculo_cuenta = 'VERIFICADO' AS cuenta_confirmada,
			       (SELECT d.estado FROM gestion_patin.documento d WHERE d.escuela_id = t.escuela_id AND d.tutor_id = t.id
			          AND d.vigente AND d.tipo = 'DNI_TUTOR_FRENTE' LIMIT 1) AS dni_frente,
			       (SELECT d.estado FROM gestion_patin.documento d WHERE d.escuela_id = t.escuela_id AND d.tutor_id = t.id
			          AND d.vigente AND d.tipo = 'DNI_TUTOR_DORSO' LIMIT 1) AS dni_dorso,
			       (SELECT count(*) FROM gestion_patin.tutor o WHERE o.escuela_id = t.escuela_id AND o.familia_id <> t.familia_id
			          AND o.activo AND t.dni IS NOT NULL AND o.dni = t.dni) AS coincidencias,
			       t.creado_en, t.version
			""";

	private static final String DESDE = """
			FROM gestion_patin.tutor t
			JOIN gestion_patin.familia f ON f.id = t.familia_id AND f.escuela_id = t.escuela_id
			WHERE t.escuela_id = :escuela AND t.activo AND f.activa AND t.verificacion = :estado
			""";

	private final TutorRepository tutores;
	private final ReglasAutoservicioTutores reglas;
	private final JdbcClient jdbc;
	private final EntityManager em;
	private final AuditoriaService auditoria;
	private final TransactionTemplate transacciones;
	private final LockTimeoutVinculos lockTimeout;
	private final Clock reloj;

	public VerificacionTutoresService(TutorRepository tutores, ReglasAutoservicioTutores reglas, JdbcClient jdbc,
			EntityManager em, AuditoriaService auditoria, PlatformTransactionManager transacciones,
			LockTimeoutVinculos lockTimeout, Clock reloj) {
		this.tutores = tutores;
		this.reglas = reglas;
		this.jdbc = jdbc;
		this.em = em;
		this.auditoria = auditoria;
		this.transacciones = new TransactionTemplate(transacciones);
		this.lockTimeout = lockTimeout;
		this.reloj = reloj;
	}

	public static ExcepcionNegocio estadoIncompatible() {
		return new ExcepcionNegocio(HttpStatus.CONFLICT, "ESTADO_INCOMPATIBLE",
				"Este tutor no se puede resolver en su estado actual.");
	}

	private static ExcepcionNegocio validacion(String mensaje) {
		return new ExcepcionNegocio(HttpStatus.BAD_REQUEST, "VALIDACION", mensaje);
	}

	/** Del más antiguo al más reciente ({@code creado_en}, {@code id}); 2 consultas por página, sin consultas por fila. */
	@Transactional(readOnly = true)
	public Pagina<TutorPorVerificar> listar(UsuarioAutenticado admin, EstadoVerificacion estado, Pageable pagina) {
		exigirAdmin(admin);
		String filtro = (estado == null ? EstadoVerificacion.DECLARADO : estado).name();
		List<TutorPorVerificar> filas = jdbc.sql(FILAS + DESDE + " ORDER BY t.creado_en, t.id LIMIT :limite OFFSET :desde")
				.param("escuela", admin.escuelaId()).param("estado", filtro).param("limite", pagina.getPageSize())
				.param("desde", pagina.getOffset())
				.query((rs, n) -> new TutorPorVerificar(rs.getObject("id", UUID.class), rs.getObject("familia_id", UUID.class),
						rs.getString("nombre_referencia"), rs.getString("nombre"), rs.getString("apellido"), rs.getString("dni"),
						rs.getString("telefono"), rs.getString("email"), rs.getString("parentesco"), rs.getString("verificacion"),
						rs.getString("motivo_observacion"), rs.getString("origen"), rs.getBoolean("tiene_cuenta"),
						rs.getBoolean("cuenta_confirmada"), rs.getString("dni_frente"), rs.getString("dni_dorso"),
						rs.getLong("coincidencias"), rs.getTimestamp("creado_en").toInstant(),
						Long.toString(rs.getLong("version"))))
				.list();
		long total = jdbc.sql("SELECT count(*) " + DESDE).param("escuela", admin.escuelaId()).param("estado", filtro)
				.query(Long.class).single();
		return Pagina.de(new PageImpl<>(filas, pagina, total), fila -> fila);
	}

	public VerificacionResultado resolver(UsuarioAutenticado admin, UUID tutorId, ResolucionVerificacion pedido,
			DatosSolicitud datos) {
		exigirAdmin(admin);
		if (pedido.decision() == DecisionVerificacion.OBSERVAR && pedido.motivo() == null) {
			throw validacion("Para observar hay que indicar qué debe corregir la familia.");
		}
		if (pedido.decision() == DecisionVerificacion.VERIFICAR && pedido.motivo() != null) {
			throw validacion("Verificar no lleva motivo.");
		}
		return Objects.requireNonNull(transacciones.execute(estado -> resolverEnTransaccion(admin, tutorId, pedido.decision(),
				pedido.motivo(), pedido.versionEsperada(), false, datos))).resultado();
	}

	/** Cada tutor en su transacción; el resultado de uno nunca depende de otro. */
	public ResultadoLote verificarLote(UsuarioAutenticado admin, LoteVerificacion lote, DatosSolicitud datos) {
		exigirAdmin(admin);
		if (new HashSet<>(lote.tutores().stream().map(LoteVerificacion.Item::tutorId).toList()).size() != lote.tutores().size()) {
			throw validacion("Un tutor aparece más de una vez en el lote.");
		}
		Instant limite = reloj.instant().plus(PLAZO_LOTE);
		return new ResultadoLote(procesar(lote.tutores(), item -> verificarUno(admin, item, datos), reloj, limite));
	}

	/** Tiempo total que un lote puede ocupar; los tutores no alcanzados vuelven como NO_PROCESADO para reintentarlos. */
	static final Duration PLAZO_LOTE = Duration.ofSeconds(15);

	/**
	 * Recorre el lote en orden: un tutor nunca deja sin resultado a los demás. Una falla inesperada de uno es {@code ERROR}:
	 * casi siempre su transacción se revirtió, pero si falló al confirmar el resultado es incierto; reintentar es seguro
	 * (devuelve YA_VERIFICADO o EDICION_DESACTUALIZADA). Vencido el plazo, los que faltan son
	 * {@code NO_PROCESADO} sin tocarlos: la respuesta llega acotada aunque haya esperas de bloqueos.
	 */
	static List<ResultadoLote.Item> procesar(List<LoteVerificacion.Item> items,
			java.util.function.Function<LoteVerificacion.Item, ResultadoLote.Item> uno, Clock reloj, Instant limite) {
		List<ResultadoLote.Item> resultados = new ArrayList<>();
		for (LoteVerificacion.Item item : items) {
			if (!reloj.instant().isBefore(limite)) {
				resultados.add(new ResultadoLote.Item(item.tutorId(), "NO_PROCESADO", null));
				continue;
			}
			try {
				resultados.add(uno.apply(item));
			} catch (RuntimeException e) {
				// Clase, causa raíz y SQLState para diagnosticar; nunca el mensaje (puede llevar SQL o valores personales).
				Throwable raiz = org.springframework.core.NestedExceptionUtils.getMostSpecificCause(e);
				log.warn("Verificación en lote: falla inesperada en un tutor (se informa ERROR y sigue): excepcion={} causa={} sqlState={}",
						e.getClass().getName(), raiz.getClass().getName(),
						raiz instanceof java.sql.SQLException sql ? sql.getSQLState() : "-");
				resultados.add(new ResultadoLote.Item(item.tutorId(), "ERROR", null));
			}
		}
		return List.copyOf(resultados);
	}


	private ResultadoLote.Item verificarUno(UsuarioAutenticado admin, LoteVerificacion.Item item, DatosSolicitud datos) {
		try {
			Resolucion r = Objects.requireNonNull(transacciones.execute(estado -> resolverEnTransaccion(admin,
					item.tutorId(), DecisionVerificacion.VERIFICAR, null, item.versionEsperada(), true, datos)));
			return new ResultadoLote.Item(item.tutorId(), r.cambio() ? "VERIFICADO" : "YA_VERIFICADO", r.resultado().version());
		} catch (ExcepcionNegocio e) {
			return new ResultadoLote.Item(item.tutorId(), e.getCodigo(), null);
		} catch (OptimisticLockingFailureException e) {
			return new ResultadoLote.Item(item.tutorId(), "EDICION_DESACTUALIZADA", null);
		} catch (PessimisticLockingFailureException e) {
			return new ResultadoLote.Item(item.tutorId(), "OCUPADO", null);
		}
	}

	/** Lo que quedó y si esta llamada lo cambió (falso si el tutor ya estaba en el estado pedido). */
	private record Resolucion(VerificacionResultado resultado, boolean cambio) {
	}

	private Resolucion resolverEnTransaccion(UsuarioAutenticado admin, UUID tutorId, DecisionVerificacion decision,
			String motivo, long versionEsperada, boolean soloDeclarados, DatosSolicitud datos) {
		lockTimeout.aplicar();
		Tutor tutor = tutores.findByIdAndEscuelaId(tutorId, admin.escuelaId())
				.orElseThrow(TutorAdminService::tutorNoEncontrado);
		if (!reglas.bloquearFamiliaActiva(admin.escuelaId(), tutor.getFamiliaId())) {
			throw FamiliaAdminService.familiaInactiva();
		}
		if (!reglas.bloquearTutorDeLaFamilia(admin.escuelaId(), tutor.getFamiliaId(), tutorId)) {
			throw estadoIncompatible(); // inactivo: no se verifica
		}
		em.refresh(tutor);
		String anterior = tutor.getVerificacion();
		if (yaResuelto(tutor, decision, motivo)) {
			return new Resolucion(resultado(tutor), false);
		}
		if (soloDeclarados && !"DECLARADO".equals(anterior)) {
			throw estadoIncompatible();
		}
		// Observar es sobre lo DECLARADO: una identidad ya verificada no se reabre a la familia; la corrige ADMIN en la ficha.
		if (decision == DecisionVerificacion.OBSERVAR && tutor.identidadVerificada()) {
			throw estadoIncompatible();
		}
		if (tutor.getVersion() != versionEsperada) {
			throw FamiliaAdminService.edicionDesactualizada();
		}
		Instant ahora = reloj.instant();
		if (decision == DecisionVerificacion.VERIFICAR) {
			tutor.verificar(admin.id(), ahora);
		} else {
			tutor.observar(admin.id(), ahora, motivo);
		}
		tutores.saveAndFlush(tutor);
		Map<String, Object> detalle = soloDeclarados
				? Map.of("familiaId", tutor.getFamiliaId().toString(), "estadoAnterior", anterior, "lote", true)
				: Map.of("familiaId", tutor.getFamiliaId().toString(), "estadoAnterior", anterior);
		auditoria.registrar(new EventoAuditoria(admin.escuelaId(), admin.id(),
				decision == DecisionVerificacion.VERIFICAR ? AccionAuditoria.TUTOR_VERIFICADO : AccionAuditoria.TUTOR_OBSERVADO,
				RECURSO, tutor.getId(), detalle, datos));
		return new Resolucion(resultado(tutor), true);
	}

	private static boolean yaResuelto(Tutor tutor, DecisionVerificacion decision, String motivo) {
		return decision == DecisionVerificacion.VERIFICAR ? tutor.identidadVerificada()
				: tutor.observado() && Objects.equals(tutor.getMotivoObservacion(), motivo);
	}

	private static VerificacionResultado resultado(Tutor t) {
		return new VerificacionResultado(t.getId(), t.getVerificacion(), t.getMotivoObservacion(),
				Long.toString(t.getVersion()));
	}

	private static void exigirAdmin(UsuarioAutenticado admin) {
		if (admin.rol() != Rol.ADMIN) {
			throw new ExcepcionNegocio(HttpStatus.FORBIDDEN, "ACCESO_DENEGADO", "No tenés permisos para esta acción.");
		}
	}
}
