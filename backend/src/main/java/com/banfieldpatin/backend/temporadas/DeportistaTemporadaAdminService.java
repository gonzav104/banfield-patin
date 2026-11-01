package com.banfieldpatin.backend.temporadas;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
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
import com.banfieldpatin.backend.deportistas.Deportista;
import com.banfieldpatin.backend.deportistas.DeportistaAdminService;
import com.banfieldpatin.backend.deportistas.DeportistaRepository;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.temporadas.dto.DeportistaTemporadaActualizacion;
import com.banfieldpatin.backend.temporadas.dto.DeportistaTemporadaRespuesta;
import com.banfieldpatin.backend.temporadas.dto.DeportistaTemporadaSolicitud;
import com.banfieldpatin.backend.temporadas.dto.PlantelRespuesta;

/**
 * Datos deportivos de un deportista en una temporada, para ADMIN. La escuela y el actor salen siempre del JWT; un
 * deportista, una temporada o una fila de otra escuela es indistinguible de uno inexistente (mismo 404). Se puede cargar y
 * editar en una temporada INACTIVA (se prepara el anio siguiente). Nunca modifica la ficha permanente del deportista.
 *
 * <p>Unicidad (V1): una fila por deportista y temporada y un numero de corredor unico por temporada. La consulta previa da
 * el 409 amable y la base arbitra la carrera (mismo 409, segun el NOMBRE de la restriccion). La auditoria se escribe en la
 * transaccion del cambio, solo con cambio real y solo con ids, anio y NOMBRES de campo: nunca un valor deportivo.
 */
@Service
public class DeportistaTemporadaAdminService {

	private static final String RECURSO = "DEPORTISTA_TEMPORADA";
	static final String RESTRICCION_PAR = "uq_deportista_temporada";
	static final String RESTRICCION_NUMERO = "uq_numero_corredor_por_temporada";
	private static final String TODOS = "TODOS";

	private final DeportistaTemporadaRepository datos;
	private final DeportistaRepository deportistas;
	private final TemporadaRepository temporadas;
	private final AuditoriaService auditoria;
	private final LockTimeoutTemporadas lockTimeout;

	public DeportistaTemporadaAdminService(DeportistaTemporadaRepository datos, DeportistaRepository deportistas,
			TemporadaRepository temporadas, AuditoriaService auditoria, LockTimeoutTemporadas lockTimeout) {
		this.datos = datos;
		this.deportistas = deportistas;
		this.temporadas = temporadas;
		this.auditoria = auditoria;
		this.lockTimeout = lockTimeout;
	}

	public static ExcepcionNegocio filaNoEncontrada() {
		return new ExcepcionNegocio(HttpStatus.NOT_FOUND, "DEPORTISTA_TEMPORADA_NO_ENCONTRADA",
				"El deportista no tiene datos cargados en esa temporada.");
	}

	static ExcepcionNegocio parDuplicado() {
		return new ExcepcionNegocio(HttpStatus.CONFLICT, "DEPORTISTA_TEMPORADA_DUPLICADA",
				"El deportista ya tiene datos cargados en esa temporada.");
	}

	static ExcepcionNegocio numeroDuplicado() {
		return new ExcepcionNegocio(HttpStatus.CONFLICT, "NUMERO_CORREDOR_DUPLICADO",
				"Ya existe un deportista con ese numero de corredor en la temporada.");
	}

	static ExcepcionNegocio deportistaInactivo() {
		return new ExcepcionNegocio(HttpStatus.CONFLICT, "DEPORTISTA_INACTIVO",
				"El deportista esta inactivo. Reactivalo para cargarle datos en una temporada nueva.");
	}

	@Transactional
	public DeportistaTemporadaRespuesta crear(UsuarioAutenticado admin, UUID deportistaId,
			DeportistaTemporadaSolicitud s, DatosSolicitud solicitud) {
		UUID escuelaId = admin.escuelaId();
		Deportista deportista = cargarDeportista(escuelaId, deportistaId);
		TemporadaVista temporada = temporadas.vistaDe(s.temporadaId(), escuelaId)
				.orElseThrow(TemporadaAdminService::temporadaNoEncontrada);
		if (!deportista.isActivo()) {
			throw deportistaInactivo();
		}
		if (datos.existsByEscuelaIdAndDeportistaIdAndTemporadaId(escuelaId, deportistaId, s.temporadaId())) {
			throw parDuplicado();
		}
		if (s.numeroCorredor() != null
				&& datos.existsByEscuelaIdAndTemporadaIdAndNumeroCorredor(escuelaId, s.temporadaId(), s.numeroCorredor())) {
			throw numeroDuplicado();
		}
		DeportistaTemporada creada = guardar("crear", DeportistaTemporada.crear(escuelaId, deportistaId, s.temporadaId(),
				s.categoria(), s.rama(), s.divisional(), s.numeroCorredor(), s.estadoDeportivo()));
		Map<String, Object> detalle = new LinkedHashMap<>();
		detalle.put("temporadaId", s.temporadaId());
		detalle.put("anio", (int) temporada.anio());
		registrar(admin, AccionAuditoria.DEPORTISTA_TEMPORADA_CREADA, creada.getId(), detalle, solicitud);
		return respuesta(creada, temporada.anio());
	}

	@Transactional(readOnly = true)
	public List<DeportistaTemporadaRespuesta> historial(UsuarioAutenticado admin, UUID deportistaId) {
		cargarDeportista(admin.escuelaId(), deportistaId);
		return datos.historial(admin.escuelaId(), deportistaId).stream().map(DeportistaTemporadaRespuesta::de).toList();
	}

	/** Una sola consulta cuando existe; si falta, se averigua que falta (deportista, temporada o la fila) para dar el 404 exacto. */
	@Transactional(readOnly = true)
	public DeportistaTemporadaRespuesta obtener(UsuarioAutenticado admin, UUID deportistaId, UUID temporadaId) {
		return datos.fila(admin.escuelaId(), deportistaId, temporadaId).map(DeportistaTemporadaRespuesta::de)
				.orElseThrow(() -> diagnosticarAusencia(admin.escuelaId(), deportistaId, temporadaId));
	}

	@Transactional
	public DeportistaTemporadaRespuesta actualizar(UsuarioAutenticado admin, UUID deportistaId, UUID temporadaId,
			DeportistaTemporadaActualizacion actualizacion, DatosSolicitud solicitud) {
		throw new UnsupportedOperationException("pendiente");
	}

	@Transactional(readOnly = true)
	public Pagina<PlantelRespuesta> plantel(UsuarioAutenticado admin, UUID temporadaId, String estadoDeportivo,
			Pageable pageable) {
		temporadas.vistaDe(temporadaId, admin.escuelaId()).orElseThrow(TemporadaAdminService::temporadaNoEncontrada);
		EstadoDeportivo estado = estadoDe(estadoDeportivo);
		String filtro = estado == null ? TODOS : estado.name();
		return Pagina.de(datos.plantel(admin.escuelaId(), temporadaId, filtro,
				estado == null ? EstadoDeportivo.ACTIVO : estado, pageable), PlantelRespuesta::de);
	}

	/** Ausente, en blanco o "TODOS" = sin filtro; cualquier otro valor que no sea un estado deportivo da 400 SOLICITUD_INVALIDA. */
	private static EstadoDeportivo estadoDe(String valor) {
		if (valor == null || valor.isBlank() || TODOS.equals(valor.strip())) {
			return null;
		}
		for (EstadoDeportivo estado : EstadoDeportivo.values()) {
			if (estado.name().equals(valor.strip())) {
				return estado;
			}
		}
		throw new ExcepcionNegocio(HttpStatus.BAD_REQUEST, "SOLICITUD_INVALIDA", "La solicitud no es válida.");
	}

	private Deportista cargarDeportista(UUID escuelaId, UUID deportistaId) {
		return deportistas.findByIdAndEscuelaId(deportistaId, escuelaId)
				.orElseThrow(DeportistaAdminService::deportistaNoEncontrado);
	}

	private ExcepcionNegocio diagnosticarAusencia(UUID escuelaId, UUID deportistaId, UUID temporadaId) {
		if (deportistas.findByIdAndEscuelaId(deportistaId, escuelaId).isEmpty()) {
			return DeportistaAdminService.deportistaNoEncontrado();
		}
		if (temporadas.vistaDe(temporadaId, escuelaId).isEmpty()) {
			return TemporadaAdminService.temporadaNoEncontrada();
		}
		return filaNoEncontrada();
	}

	/**
	 * {@code saveAndFlush} dentro del try: una violacion de unicidad que gane la carrera a la comprobacion previa se
	 * traduce a 409 segun el NOMBRE de la restriccion (el cliente nunca lo ve) y deja un WARN saneado (restriccion,
	 * operacion y clase, sin valores); cualquier otra se relanza (el manejador global la registra como ERROR saneado).
	 */
	private DeportistaTemporada guardar(String operacion, DeportistaTemporada fila) {
		try {
			return datos.saveAndFlush(fila);
		} catch (DataIntegrityViolationException e) {
			String restriccion = RestriccionViolada.nombre(e).orElse("");
			if (RESTRICCION_PAR.equals(restriccion)) {
				RestriccionViolada.registrarMapeada("DeportistaTemporadaAdminService." + operacion, e);
				throw parDuplicado();
			}
			if (RESTRICCION_NUMERO.equals(restriccion)) {
				RestriccionViolada.registrarMapeada("DeportistaTemporadaAdminService." + operacion, e);
				throw numeroDuplicado();
			}
			throw e;
		}
	}

	private static DeportistaTemporadaRespuesta respuesta(DeportistaTemporada f, Short anio) {
		return new DeportistaTemporadaRespuesta(f.getId(), f.getDeportistaId(), f.getTemporadaId(), anio, f.getCategoria(),
				f.getRama(), f.getDivisional(), f.getNumeroCorredor(), f.getEstadoDeportivo());
	}

	private void registrar(UsuarioAutenticado admin, AccionAuditoria accion, UUID recursoId, Map<String, Object> detalle,
			DatosSolicitud solicitud) {
		auditoria.registrar(new EventoAuditoria(admin.escuelaId(), admin.id(), accion, RECURSO, recursoId, detalle,
				solicitud));
	}
}
