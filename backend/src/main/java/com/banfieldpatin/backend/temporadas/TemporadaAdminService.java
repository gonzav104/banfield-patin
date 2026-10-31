package com.banfieldpatin.backend.temporadas;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.banfieldpatin.backend.compartido.auditoria.AccionAuditoria;
import com.banfieldpatin.backend.compartido.auditoria.AuditoriaService;
import com.banfieldpatin.backend.compartido.auditoria.EventoAuditoria;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.error.RestriccionViolada;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.compartido.web.Pagina;
import com.banfieldpatin.backend.escuelas.EscuelaRepository;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.temporadas.dto.TemporadaRespuesta;
import com.banfieldpatin.backend.temporadas.dto.TemporadaSolicitud;

/**
 * Gestion administrativa de temporadas. La escuela y el actor salen siempre del JWT ({@link UsuarioAutenticado}); un id de
 * otra escuela es indistinguible de uno inexistente (mismo 404). Una temporada nace INACTIVA y puede no haber ninguna activa
 * (O10).
 *
 * <p>Activar es la unica operacion concurrente. Orden fijo: (1) lectura escalar sin bloqueo: si ya esta activa, 200 sin
 * escrituras ni auditoria; (2) bloqueo de la fila de la escuela ({@code FOR NO KEY UPDATE}, con espera acotada), que serializa
 * las activaciones de ESA escuela y de ninguna otra; (3) con el bloqueo tomado se vuelve a leer el estado, porque otra
 * transaccion pudo activarla mientras se esperaba (tambien responde 200 sin auditar); (4) se desactiva la activa anterior y
 * se hace flush ANTES de activar la nueva, porque el indice parcial unico no es diferible; (5) auditoria en la misma
 * transaccion. El indice parcial queda como red de seguridad: una violacion se traduce a 409 CONFLICTO_CONCURRENCIA.
 */
@Service
public class TemporadaAdminService {

	private static final Logger log = LoggerFactory.getLogger(TemporadaAdminService.class);
	private static final String RECURSO = "TEMPORADA";
	static final String RESTRICCION_ANIO = "uq_temporada_escuela_anio";
	static final String RESTRICCION_ACTIVA = "uq_temporada_activa_por_escuela";

	private final TemporadaRepository temporadas;
	private final EscuelaRepository escuelas;
	private final AuditoriaService auditoria;
	private final LockTimeoutTemporadas lockTimeout;

	public TemporadaAdminService(TemporadaRepository temporadas, EscuelaRepository escuelas,
			AuditoriaService auditoria, LockTimeoutTemporadas lockTimeout) {
		this.temporadas = temporadas;
		this.escuelas = escuelas;
		this.auditoria = auditoria;
		this.lockTimeout = lockTimeout;
	}

	public static ExcepcionNegocio temporadaNoEncontrada() {
		return new ExcepcionNegocio(HttpStatus.NOT_FOUND, "TEMPORADA_NO_ENCONTRADA", "La temporada no existe.");
	}

	public static ExcepcionNegocio anioDuplicado() {
		return new ExcepcionNegocio(HttpStatus.CONFLICT, "TEMPORADA_ANIO_DUPLICADO",
				"Ya existe una temporada para ese anio.");
	}

	/**
	 * Mismo codigo que el resto de los conflictos de concurrencia, con mensaje propio: el del manejador global habla del
	 * deportista (es el de vinculos) y aqui no corresponde.
	 */
	public static ExcepcionNegocio conflictoConcurrente() {
		return new ExcepcionNegocio(HttpStatus.CONFLICT, "CONFLICTO_CONCURRENCIA",
				"Otra activacion de temporadas esta en curso. Reintentá en unos segundos.");
	}

	@Transactional(readOnly = true)
	public Pagina<TemporadaRespuesta> listar(UsuarioAutenticado admin, Pageable pageable) {
		return Pagina.de(temporadas.listar(admin.escuelaId(), pageable), TemporadaRespuesta::de);
	}

	@Transactional(readOnly = true)
	public TemporadaRespuesta obtener(UsuarioAutenticado admin, UUID id) {
		return temporadas.vistaDe(id, admin.escuelaId()).map(TemporadaRespuesta::de)
				.orElseThrow(TemporadaAdminService::temporadaNoEncontrada);
	}

	/** La consulta previa da el mensaje claro; ante una carrera decide la restriccion de la base (mismo 409). */
	@Transactional
	public TemporadaRespuesta crear(UsuarioAutenticado admin, TemporadaSolicitud solicitud, DatosSolicitud datos) {
		int anio = solicitud.anio();
		if (temporadas.existsByEscuelaIdAndAnio(admin.escuelaId(), (short) anio)) {
			throw anioDuplicado();
		}
		Temporada temporada;
		try {
			temporada = temporadas.saveAndFlush(Temporada.crear(admin.escuelaId(), anio));
		} catch (DataIntegrityViolationException e) {
			if (RESTRICCION_ANIO.equals(RestriccionViolada.nombre(e).orElse(""))) {
				RestriccionViolada.registrarMapeada("TemporadaAdminService.crear", e);
				throw anioDuplicado();
			}
			throw e;
		}
		registrar(admin, AccionAuditoria.TEMPORADA_CREADA, temporada.getId(), Map.of("anio", anio), datos);
		return TemporadaRespuesta.de(temporada);
	}

	/**
	 * Si el bloqueo de la escuela no se obtiene a tiempo (lock_timeout) o PostgreSQL elige esta transaccion como victima de un
	 * deadlock, no quedo nada escrito ni auditado: 409 CONFLICTO_CONCURRENCIA y UNA linea WARN con la operacion y la clase de
	 * la excepcion (su mensaje nombra tablas y filas y jamas se imprime).
	 */
	@Transactional
	public TemporadaRespuesta activar(UsuarioAutenticado admin, UUID id, DatosSolicitud datos) {
		try {
			return activarConBloqueo(admin, id, datos);
		} catch (PessimisticLockingFailureException e) {
			log.warn("Conflicto de concurrencia por bloqueo de base de datos (409): operacion=TemporadaAdminService.activar excepcion={}",
					e.getClass().getName());
			throw conflictoConcurrente();
		}
	}

	private TemporadaRespuesta activarConBloqueo(UsuarioAutenticado admin, UUID id, DatosSolicitud datos) {
		lockTimeout.aplicar();
		UUID escuelaId = admin.escuelaId();
		TemporadaVista previa = temporadas.vistaDe(id, escuelaId).orElseThrow(TemporadaAdminService::temporadaNoEncontrada);
		if (previa.activa()) {
			return TemporadaRespuesta.de(previa);
		}
		escuelas.bloquearParaActivarTemporada(escuelaId)
				.orElseThrow(() -> new IllegalStateException("La escuela de la sesion no existe"));
		// Primera carga de la entidad: ocurre DESPUES del bloqueo, asi que ve lo que otra activacion confirmo mientras se esperaba.
		Temporada temporada = temporadas.findByIdAndEscuelaId(id, escuelaId)
				.orElseThrow(TemporadaAdminService::temporadaNoEncontrada);
		if (temporada.isActiva()) {
			return TemporadaRespuesta.de(temporada);
		}
		Temporada anterior = temporadas.activaDe(escuelaId).orElse(null);
		if (anterior != null) {
			anterior.desactivar();
			guardar("activar", anterior);
		}
		temporada.activar();
		guardar("activar", temporada);
		Map<String, Object> detalle = new LinkedHashMap<>();
		detalle.put("anio", (int) temporada.getAnio());
		detalle.put("anteriorTemporadaId", anterior == null ? null : anterior.getId());
		registrar(admin, AccionAuditoria.TEMPORADA_ACTIVADA, temporada.getId(), detalle, datos);
		return TemporadaRespuesta.de(temporada);
	}

	/**
	 * {@code saveAndFlush} dentro del try: obliga a que el UPDATE llegue a la base en este orden (el indice parcial unico no
	 * es diferible). Si la base arbitra una carrera que el bloqueo no evito, la respuesta es 409 CONFLICTO_CONCURRENCIA con
	 * un WARN saneado; cualquier otra violacion se relanza (500 saneado en el manejador).
	 */
	private void guardar(String operacion, Temporada temporada) {
		try {
			temporadas.saveAndFlush(temporada);
		} catch (DataIntegrityViolationException e) {
			if (RESTRICCION_ACTIVA.equals(RestriccionViolada.nombre(e).orElse(""))) {
				RestriccionViolada.registrarMapeada("TemporadaAdminService." + operacion, e);
				throw conflictoConcurrente();
			}
			throw e;
		}
	}

	private void registrar(UsuarioAutenticado admin, AccionAuditoria accion, UUID recursoId, Map<String, Object> detalle,
			DatosSolicitud datos) {
		auditoria.registrar(new EventoAuditoria(admin.escuelaId(), admin.id(), accion, RECURSO, recursoId, detalle, datos));
	}
}
