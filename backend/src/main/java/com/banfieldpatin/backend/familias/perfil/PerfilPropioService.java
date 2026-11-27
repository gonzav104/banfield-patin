package com.banfieldpatin.backend.familias.perfil;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.banfieldpatin.backend.compartido.auditoria.AccionAuditoria;
import com.banfieldpatin.backend.compartido.auditoria.AuditoriaService;
import com.banfieldpatin.backend.compartido.auditoria.EventoAuditoria;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.deportistas.DocumentoIdentidad;
import com.banfieldpatin.backend.deportistas.EnmascaradoDni;
import com.banfieldpatin.backend.familias.perfil.dto.PerfilActualizacion;
import com.banfieldpatin.backend.familias.perfil.dto.PerfilDatos;
import com.banfieldpatin.backend.familias.perfil.dto.PerfilRespuesta;
import com.banfieldpatin.backend.familias.perfil.dto.PerfilSolicitud;
import com.banfieldpatin.backend.familias.perfil.dto.PersonaDeLaFamilia;
import com.banfieldpatin.backend.familias.perfil.dto.ReclamoSolicitud;
import com.banfieldpatin.backend.familias.tutores.Tutor;
import com.banfieldpatin.backend.familias.tutores.TutorRepository;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/**
 * Perfil propio de una cuenta FAMILIA y vínculo declarativo cuenta ↔ tutor (U21-BIS C1).
 *
 * <ul>
 * <li><b>Declarar no autoriza.</b> El perfil y el reclamo solo registran quién declaró qué; ningún camino de este servicio toca
 * {@code familia_deportista}, roles ni responsabilidades. El acceso sigue siendo por familia.</li>
 * <li><b>Alcance:</b> escuela, familia y cuenta salen del token. Un tutor de otra familia o escuela, inexistente o ya con
 * cuenta da el mismo 404/409 neutro. Nunca se devuelve el DNI completo ni datos de otras familias.</li>
 * <li><b>Identidad vs contacto:</b> nombre, apellido y DNI son identidad; teléfono, correo y parentesco, contacto. Una
 * identidad {@code VERIFICADA} no se cambia desde acá (409 {@code IDENTIDAD_VERIFICADA}, sin eco).</li>
 * <li><b>Concurrencia:</b> la edición lleva la revisión que la persona vio; el reclamo es un UPDATE condicional (uno gana, el
 * otro recibe un conflicto neutro); una cuenta tiene a lo sumo un perfil (índice único), así que no hace falta ningún tope.</li>
 * <li><b>Bandera:</b> apagada, solo se puede consultar.</li>
 * </ul>
 */
@Service
@EnableConfigurationProperties(AutoservicioPropiedades.class)
public class PerfilPropioService {

	private static final String RECURSO = "TUTOR";

	private final TutorRepository tutores;
	private final JdbcClient jdbc;
	private final EntityManager em;
	private final AuditoriaService auditoria;
	private final AutoservicioPropiedades propiedades;

	public PerfilPropioService(TutorRepository tutores, JdbcClient jdbc, EntityManager em, AuditoriaService auditoria,
			AutoservicioPropiedades propiedades) {
		this.tutores = tutores;
		this.jdbc = jdbc;
		this.em = em;
		this.auditoria = auditoria;
		this.propiedades = propiedades;
	}

	public static ExcepcionNegocio deshabilitado() {
		return new ExcepcionNegocio(HttpStatus.SERVICE_UNAVAILABLE, "AUTOSERVICIO_PERFIL_DESHABILITADO",
				"Completar tus datos desde acá todavía no está habilitado.");
	}

	public static ExcepcionNegocio perfilNoEncontrado() {
		return new ExcepcionNegocio(HttpStatus.NOT_FOUND, "PERFIL_NO_ENCONTRADO", "Todavía no cargaste tus datos.");
	}

	public static ExcepcionNegocio perfilYaExiste() {
		return new ExcepcionNegocio(HttpStatus.CONFLICT, "PERFIL_YA_EXISTE", "Ya tenés tus datos cargados.");
	}

	public static ExcepcionNegocio personaNoDisponible() {
		return new ExcepcionNegocio(HttpStatus.CONFLICT, "PERSONA_NO_DISPONIBLE",
				"No pudimos asociar a esa persona con tu cuenta. Actualizá la pantalla y revisá de nuevo.");
	}

	public static ExcepcionNegocio identidadVerificada() {
		return new ExcepcionNegocio(HttpStatus.CONFLICT, "IDENTIDAD_VERIFICADA",
				"Tu nombre y tu DNI ya fueron verificados por el club. Para cambiarlos, avisale al club.");
	}

	public static ExcepcionNegocio edicionDesactualizada() {
		return new ExcepcionNegocio(HttpStatus.CONFLICT, "EDICION_DESACTUALIZADA",
				"Tus datos cambiaron mientras los editabas. Actualizá la pantalla y volvé a intentar.");
	}

	@Transactional(readOnly = true)
	public PerfilRespuesta obtener(UsuarioAutenticado u) {
		exigirFamilia(u);
		Tutor propio = tutores.deCuenta(u.escuelaId(), u.familiaId(), u.id()).orElse(null);
		if (propio != null) {
			return new PerfilRespuesta(propiedades.perfilHabilitado(), datos(propio), List.of());
		}
		List<PersonaDeLaFamilia> sinCuenta = tutores.sinCuentaDeLaFamilia(u.escuelaId(), u.familiaId()).stream()
				.map(t -> new PersonaDeLaFamilia(t.getId(), t.getNombre(), t.getApellido(), t.getParentesco(),
						Long.toString(t.getVersion())))
				.toList();
		return new PerfilRespuesta(propiedades.perfilHabilitado(), null, sinCuenta);
	}

	@Transactional
	public PerfilRespuesta crear(UsuarioAutenticado u, PerfilSolicitud s, DatosSolicitud datos) {
		exigirHabilitado();
		exigirFamilia(u);
		bloquearFamiliaActiva(u);
		if (tutores.deCuenta(u.escuelaId(), u.familiaId(), u.id()).isPresent()) {
			throw perfilYaExiste();
		}
		Tutor nuevo;
		try {
			nuevo = tutores.saveAndFlush(Tutor.crearPerfilPropio(u.escuelaId(), u.familiaId(), u.id(), s.nombre(),
					s.apellido(), DocumentoIdentidad.normalizarDni(s.dni()), s.telefono(), s.email(), s.parentesco()));
		} catch (DataIntegrityViolationException e) {
			// Dos pedidos simultáneos de la misma cuenta: el índice único (uq_tutor_usuario) deja uno solo.
			throw perfilYaExiste();
		}
		registrar(u, AccionAuditoria.PERFIL_DECLARADO, nuevo, Map.of("familiaId", u.familiaId().toString()), datos);
		return new PerfilRespuesta(true, datos(nuevo), List.of());
	}

	@Transactional
	public PerfilRespuesta actualizar(UsuarioAutenticado u, PerfilActualizacion s, DatosSolicitud datos) {
		exigirHabilitado();
		exigirFamilia(u);
		bloquearFamiliaActiva(u);
		Tutor tutor = tutores.deCuenta(u.escuelaId(), u.familiaId(), u.id()).orElseThrow(PerfilPropioService::perfilNoEncontrado);
		if (tutor.getVersion() != s.versionEsperada()) {
			throw edicionDesactualizada();
		}
		em.lock(tutor, LockModeType.OPTIMISTIC);
		// El DNI nunca viaja completo hacia la familia, así que no puede reenviarlo: sin DNI en el pedido se conserva el cargado.
		// Cambiarlo es mandar uno nuevo (permitido solo mientras la identidad no esté verificada); borrarlo no es posible desde acá.
		String dni = s.dni() == null ? tutor.getDni() : DocumentoIdentidad.normalizarDni(s.dni());
		boolean cambiaIdentidad = !java.util.Objects.equals(tutor.getNombre(), s.nombre())
				|| !java.util.Objects.equals(tutor.getApellido(), s.apellido())
				|| !java.util.Objects.equals(tutor.getDni(), dni);
		if (cambiaIdentidad && tutor.identidadVerificada()) {
			throw identidadVerificada();
		}
		List<String> cambios = tutor.actualizar(s.nombre(), s.apellido(), dni, s.telefono(), s.email(), s.parentesco());
		if (!cambios.isEmpty()) {
			tutores.saveAndFlush(tutor);
			registrar(u, AccionAuditoria.PERFIL_ACTUALIZADO, tutor,
					Map.of("familiaId", u.familiaId().toString(), "camposModificados", cambios), datos);
		}
		return new PerfilRespuesta(true, datos(tutor), List.of());
	}

	/**
	 * «Soy esta persona». Declaración, no acreditación: no verifica identidad, no cambia permisos y no toca los datos del tutor.
	 */
	@Transactional
	public PerfilRespuesta reclamar(UsuarioAutenticado u, ReclamoSolicitud s, DatosSolicitud datos) {
		exigirHabilitado();
		exigirFamilia(u);
		bloquearFamiliaActiva(u);
		int cambiadas;
		try {
			cambiadas = jdbc.sql("""
					UPDATE gestion_patin.tutor SET usuario_id = :yo, vinculo_cuenta = 'DECLARADO', declarado_por = :yo,
					  version = version + 1
					WHERE id = :t AND escuela_id = :e AND familia_id = :f AND usuario_id IS NULL AND activo AND version = :v
					""").param("yo", u.id()).param("t", s.tutorId()).param("e", u.escuelaId()).param("f", u.familiaId())
					.param("v", s.versionEsperada()).update();
		} catch (DataIntegrityViolationException e) {
			// Esta cuenta ya tiene un perfil (uq_tutor_usuario): mismo conflicto neutro.
			throw personaNoDisponible();
		}
		if (cambiadas != 1) {
			throw personaNoDisponible();
		}
		em.clear();
		Tutor tutor = tutores.deCuenta(u.escuelaId(), u.familiaId(), u.id()).orElseThrow(PerfilPropioService::personaNoDisponible);
		registrar(u, AccionAuditoria.TUTOR_RECLAMADO, tutor, Map.of("familiaId", u.familiaId().toString()), datos);
		return new PerfilRespuesta(true, datos(tutor), List.of());
	}

	private PerfilDatos datos(Tutor t) {
		return new PerfilDatos(t.getId(), t.getNombre(), t.getApellido(), EnmascaradoDni.de(t.getDni()),
				t.getTelefono(), t.getEmail(), t.getParentesco(), t.getVerificacion(), !t.identidadVerificada(),
				Long.toString(t.getVersion()));
	}

	private void exigirHabilitado() {
		if (!propiedades.perfilHabilitado()) {
			throw deshabilitado();
		}
	}

	private static void exigirFamilia(UsuarioAutenticado u) {
		if (u.rol() != Rol.FAMILIA || u.familiaId() == null) {
			throw new ExcepcionNegocio(HttpStatus.FORBIDDEN, "ACCESO_DENEGADO", "No tenés permisos para esta acción.");
		}
	}

	/** Familia activa tomada FOR SHARE (mismo bloqueo que la edición administrativa de tutores); inactiva = 404 neutro. */
	private void bloquearFamiliaActiva(UsuarioAutenticado u) {
		boolean activa = !em.createNativeQuery("SELECT id FROM gestion_patin.familia WHERE id = :f AND escuela_id = :e AND activa FOR SHARE")
				.setParameter("f", u.familiaId()).setParameter("e", u.escuelaId()).getResultList().isEmpty();
		if (!activa) {
			throw perfilNoEncontrado();
		}
	}

	private void registrar(UsuarioAutenticado u, AccionAuditoria accion, Tutor tutor, Map<String, Object> detalle,
			DatosSolicitud datos) {
		auditoria.registrar(new EventoAuditoria(u.escuelaId(), u.id(), accion, RECURSO, tutor.getId(), detalle, datos));
	}
}
