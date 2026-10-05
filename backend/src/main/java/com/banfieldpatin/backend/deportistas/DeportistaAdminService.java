package com.banfieldpatin.backend.deportistas;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.banfieldpatin.backend.compartido.auditoria.AccionAuditoria;
import com.banfieldpatin.backend.compartido.auditoria.AuditoriaService;
import com.banfieldpatin.backend.compartido.auditoria.EventoAuditoria;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.error.RestriccionViolada;
import com.banfieldpatin.backend.compartido.web.Busqueda;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.compartido.web.FiltroEstado;
import com.banfieldpatin.backend.compartido.web.Pagina;
import com.banfieldpatin.backend.deportistas.dto.DeportistaDetalle;
import com.banfieldpatin.backend.deportistas.dto.DeportistaResumen;
import com.banfieldpatin.backend.deportistas.dto.DeportistaSolicitud;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;

/**
 * Gestion administrativa de deportistas (datos permanentes). La escuela y el actor salen siempre del JWT; un id de otra
 * escuela es indistinguible de uno inexistente (mismo 404). DNI y CUIL se normalizan aqui otra vez antes de persistir.
 * El DNI es unico por escuela INCLUSO entre inactivos (el indice de V1 no es parcial): una comprobacion previa da el
 * codigo amable (DNI_DUPLICADO si el titular esta activo, DNI_RESERVADO_POR_INACTIVO si no) y la base arbitra la carrera
 * (el perdedor recibe DNI_DUPLICADO). Activar/desactivar NO toca vinculos ni familias. La auditoria se escribe en la
 * transaccion del cambio, solo cuando hubo un cambio real y solo con ids y NOMBRES de campos: nunca se registra ni se
 * audita un DNI, un CUIL ni ningun otro dato personal.
 */
@Service
public class DeportistaAdminService {

	private static final String RECURSO = "DEPORTISTA";
	private static final String RESTRICCION_DNI = "uq_deportista_dni_escuela";
	private static final String RESTRICCION_CUIL = "uq_deportista_cuil_escuela";

	private final DeportistaRepository deportistas;
	private final AuditoriaService auditoria;

	public DeportistaAdminService(DeportistaRepository deportistas, AuditoriaService auditoria) {
		this.deportistas = deportistas;
		this.auditoria = auditoria;
	}

	public static ExcepcionNegocio deportistaNoEncontrado() {
		return new ExcepcionNegocio(HttpStatus.NOT_FOUND, "DEPORTISTA_NO_ENCONTRADO", "El deportista no existe.");
	}

	static ExcepcionNegocio dniDuplicado() {
		return new ExcepcionNegocio(HttpStatus.CONFLICT, "DNI_DUPLICADO", "Ya existe un deportista con ese DNI.");
	}

	static ExcepcionNegocio dniReservadoPorInactivo() {
		return new ExcepcionNegocio(HttpStatus.CONFLICT, "DNI_RESERVADO_POR_INACTIVO",
				"Ya existe un deportista inactivo con ese DNI. Reactivalo en lugar de crear uno nuevo.");
	}

	static ExcepcionNegocio cuilDuplicado() {
		return new ExcepcionNegocio(HttpStatus.CONFLICT, "CUIL_DUPLICADO", "Ya existe un deportista con ese CUIL.");
	}

	/** {@code busqueda} llega sin procesar: aqui se obtiene el patron LIKE escapado y los digitos del DNI. */
	@Transactional(readOnly = true)
	public Pagina<DeportistaResumen> listar(UsuarioAutenticado admin, FiltroEstado estado, String busqueda,
			Pageable pageable) {
		Page<Deportista> pagina = deportistas.buscar(admin.escuelaId(), estado.name(), Busqueda.patronLike(busqueda),
				Busqueda.digitos(busqueda), pageable);
		return Pagina.de(pagina, DeportistaResumen::de);
	}

	@Transactional(readOnly = true)
	public DeportistaDetalle obtener(UsuarioAutenticado admin, UUID id) {
		return DeportistaDetalle.de(cargar(admin, id));
	}

	@Transactional
	public DeportistaDetalle crear(UsuarioAutenticado admin, DeportistaSolicitud s, DatosSolicitud datos) {
		String dni = DocumentoIdentidad.normalizarDni(s.dni());
		String cuil = DocumentoIdentidad.normalizarCuil(s.cuil());
		exigirDniLibre(deportistas.activoPorDni(admin.escuelaId(), dni));
		if (cuil != null && deportistas.existsByEscuelaIdAndCuil(admin.escuelaId(), cuil)) {
			throw cuilDuplicado();
		}
		Deportista deportista = guardar("crear", Deportista.crear(admin.escuelaId(), s.nombre(), s.apellido(), dni, cuil,
				s.fechaNacimiento(), s.nacionalidad(), s.domicilio(), s.otrosDatosDomicilio(), s.localidad(),
				s.partido(), s.codigoPostal(), s.telefonoContacto(), s.emailFederativo()));
		registrar(admin, AccionAuditoria.DEPORTISTA_CREADO, deportista, Map.of("conCuil", cuil != null), datos);
		return DeportistaDetalle.de(deportista);
	}

	/**
	 * Reemplazo completo; auditoria solo si algun campo cambio realmente. Conservar el propio DNI o CUIL no es un
	 * conflicto: la unicidad solo se comprueba cuando el valor cambia (y siempre ignorando al propio deportista).
	 */
	@Transactional
	public DeportistaDetalle actualizar(UsuarioAutenticado admin, UUID id, DeportistaSolicitud s, DatosSolicitud datos) {
		Deportista deportista = cargar(admin, id);
		String dni = DocumentoIdentidad.normalizarDni(s.dni());
		String cuil = DocumentoIdentidad.normalizarCuil(s.cuil());
		if (!dni.equals(deportista.getDni())) {
			exigirDniLibre(deportistas.activoPorDniDeOtro(admin.escuelaId(), dni, id));
		}
		if (cuil != null && !cuil.equals(deportista.getCuil())
				&& deportistas.existsByEscuelaIdAndCuilAndIdNot(admin.escuelaId(), cuil, id)) {
			throw cuilDuplicado();
		}
		List<String> cambios = deportista.actualizar(s.nombre(), s.apellido(), dni, cuil, s.fechaNacimiento(),
				s.nacionalidad(), s.domicilio(), s.otrosDatosDomicilio(), s.localidad(), s.partido(), s.codigoPostal(),
				s.telefonoContacto(), s.emailFederativo());
		if (!cambios.isEmpty()) {
			guardar("actualizar", deportista);
			registrar(admin, AccionAuditoria.DEPORTISTA_ACTUALIZADO, deportista, Map.of("camposModificados", cambios),
					datos);
		}
		return DeportistaDetalle.de(deportista);
	}

	@Transactional
	public DeportistaDetalle activar(UsuarioAutenticado admin, UUID id, DatosSolicitud datos) {
		Deportista deportista = cargar(admin, id);
		if (deportista.activar()) {
			deportistas.saveAndFlush(deportista);
			registrar(admin, AccionAuditoria.DEPORTISTA_ACTIVADO, deportista, Map.of(), datos);
		}
		return DeportistaDetalle.de(deportista);
	}

	@Transactional
	public DeportistaDetalle desactivar(UsuarioAutenticado admin, UUID id, DatosSolicitud datos) {
		Deportista deportista = cargar(admin, id);
		if (deportista.desactivar()) {
			deportistas.saveAndFlush(deportista);
			registrar(admin, AccionAuditoria.DEPORTISTA_DESACTIVADO, deportista, Map.of(), datos);
		}
		return DeportistaDetalle.de(deportista);
	}

	private Deportista cargar(UsuarioAutenticado admin, UUID id) {
		return deportistas.findByIdAndEscuelaId(id, admin.escuelaId())
				.orElseThrow(DeportistaAdminService::deportistaNoEncontrado);
	}

	/** Titular activo -> DNI_DUPLICADO; titular inactivo (sigue reservando el DNI) -> DNI_RESERVADO_POR_INACTIVO. */
	private static void exigirDniLibre(Optional<Boolean> titularActivo) {
		titularActivo.ifPresent(activo -> {
			throw activo ? dniDuplicado() : dniReservadoPorInactivo();
		});
	}

	/**
	 * {@code saveAndFlush} dentro del try: una violacion de unicidad que gane la carrera a la comprobacion previa se
	 * traduce a 409 segun el NOMBRE de la restriccion (el cliente nunca lo ve) y deja un WARN saneado (restriccion,
	 * operacion y clase, sin valores); cualquier otra se relanza (el manejador global la registra como ERROR saneado).
	 */
	private Deportista guardar(String operacion, Deportista deportista) {
		try {
			return deportistas.saveAndFlush(deportista);
		} catch (DataIntegrityViolationException e) {
			String restriccion = RestriccionViolada.nombre(e).orElse("");
			if (RESTRICCION_DNI.equals(restriccion)) {
				RestriccionViolada.registrarMapeada("DeportistaAdminService." + operacion, e);
				throw dniDuplicado();
			}
			if (RESTRICCION_CUIL.equals(restriccion)) {
				RestriccionViolada.registrarMapeada("DeportistaAdminService." + operacion, e);
				throw cuilDuplicado();
			}
			throw e;
		}
	}

	private void registrar(UsuarioAutenticado admin, AccionAuditoria accion, Deportista deportista,
			Map<String, Object> detalle, DatosSolicitud datos) {
		auditoria.registrar(new EventoAuditoria(admin.escuelaId(), admin.id(), accion, RECURSO, deportista.getId(),
				detalle, datos));
	}
}
