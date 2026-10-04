package com.banfieldpatin.backend.familias.invitaciones;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.banfieldpatin.backend.compartido.auditoria.AccionAuditoria;
import com.banfieldpatin.backend.compartido.auditoria.AuditoriaService;
import com.banfieldpatin.backend.compartido.auditoria.EventoAuditoria;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.escuelas.Escuela;
import com.banfieldpatin.backend.escuelas.EscuelaActual;
import com.banfieldpatin.backend.escuelas.EscuelaRepository;
import com.banfieldpatin.backend.familias.FamiliaRepository;
import com.banfieldpatin.backend.familias.invitaciones.dto.RegistroSolicitud;
import com.banfieldpatin.backend.usuarios.Rol;
import com.banfieldpatin.backend.usuarios.Usuario;
import com.banfieldpatin.backend.usuarios.UsuarioRepository;
import com.banfieldpatin.backend.usuarios.dto.UsuarioActualRespuesta;

/**
 * Unica via para crear cuentas FAMILIA. Todo ocurre en una transaccion:
 * <ol>
 * <li>se bloquea la fila de la invitacion (SELECT ... FOR UPDATE): dos registros con el mismo token se serializan;</li>
 * <li>se exige estado PENDIENTE y escuela/familia activas; si no, error uniforme;</li>
 * <li>se inserta el usuario PRIMERO (rol FAMILIA forzado; familia y escuela salen de la invitacion);</li>
 * <li>un UPDATE condicional marca la invitacion como usada y debe afectar exactamente 1 fila.</li>
 * </ol>
 * El orden insert-luego-update es obligatorio por el CHECK (usado_en IS NULL) = (usuario_id IS NULL) y la FK
 * compuesta al usuario. Cualquier excepcion revierte todo: un email duplicado deja la invitacion PENDIENTE.
 * El token nunca se registra ni se audita; los fallos se auditan con un motivo generico.
 */
@Service
public class RegistroPorInvitacionService {

	static final String MOTIVO_NO_DISPONIBLE = "INVITACION_NO_DISPONIBLE";
	static final String MOTIVO_EMAIL = "EMAIL_YA_REGISTRADO";
	private static final String RESTRICCION_EMAIL = "uq_usuario_email_escuela";

	private final InvitacionRepository invitaciones;
	private final UsuarioRepository usuarios;
	private final EscuelaRepository escuelas;
	private final FamiliaRepository familias;
	private final EscuelaActual escuelaActual;
	private final PasswordEncoder passwordEncoder;
	private final AuditoriaService auditoria;
	private final VinculacionPorInvitacion vinculacion;
	private final Clock reloj;

	public RegistroPorInvitacionService(InvitacionRepository invitaciones, UsuarioRepository usuarios,
			EscuelaRepository escuelas, FamiliaRepository familias, EscuelaActual escuelaActual,
			PasswordEncoder passwordEncoder, AuditoriaService auditoria,
			ObjectProvider<VinculacionPorInvitacion> vinculacion, Clock reloj) {
		this(invitaciones, usuarios, escuelas, familias, escuelaActual, passwordEncoder, auditoria,
				vinculacion.getIfAvailable(SinVinculacion::new), reloj);
	}

	RegistroPorInvitacionService(InvitacionRepository invitaciones, UsuarioRepository usuarios,
			EscuelaRepository escuelas, FamiliaRepository familias, EscuelaActual escuelaActual,
			PasswordEncoder passwordEncoder, AuditoriaService auditoria, VinculacionPorInvitacion vinculacion,
			Clock reloj) {
		this.invitaciones = invitaciones;
		this.usuarios = usuarios;
		this.escuelas = escuelas;
		this.familias = familias;
		this.escuelaActual = escuelaActual;
		this.passwordEncoder = passwordEncoder;
		this.auditoria = auditoria;
		this.vinculacion = vinculacion;
		this.reloj = reloj;
	}

	public static ExcepcionNegocio emailYaRegistrado() {
		return new ExcepcionNegocio(HttpStatus.CONFLICT, "EMAIL_YA_REGISTRADO",
				"Ya existe una cuenta con ese email.");
	}

	@Transactional
	public UsuarioActualRespuesta registrar(RegistroSolicitud solicitud, DatosSolicitud datos) {
		if (!ValidarInvitacionService.tieneFormatoValido(solicitud.token())) {
			throw noDisponible(datos);
		}
		Invitacion invitacion = invitaciones
				.findByTokenHashParaActualizar(GeneradorTokenInvitacion.sha256Hex(solicitud.token()))
				.orElseThrow(() -> noDisponible(datos));
		Instant ahora = reloj.instant();
		if (EstadoInvitacion.desde(invitacion, ahora) != EstadoInvitacion.PENDIENTE) {
			throw noDisponible(datos);
		}
		boolean escuelaActiva = escuelas.findById(invitacion.getEscuelaId()).filter(Escuela::isActiva).isPresent();
		boolean familiaActiva = familias.findByIdAndEscuelaId(invitacion.getFamiliaId(), invitacion.getEscuelaId())
				.filter(f -> f.isActiva()).isPresent();
		if (!escuelaActiva || !familiaActiva) {
			throw noDisponible(datos);
		}

		String email = solicitud.email().strip().toLowerCase(Locale.ROOT);
		if (usuarios.existeEmail(invitacion.getEscuelaId(), email)) {
			throw emailDuplicado(datos);
		}

		// Rol, familia y escuela no vienen del cliente: el rol es fijo y el resto sale de la invitacion.
		Usuario nuevo = Usuario.crear(invitacion.getEscuelaId(), invitacion.getFamiliaId(),
				solicitud.nombre().strip(), solicitud.apellido().strip(), email,
				passwordEncoder.encode(solicitud.password()), Rol.FAMILIA);
		Usuario guardado;
		try {
			guardado = usuarios.saveAndFlush(nuevo);
		} catch (DataIntegrityViolationException e) {
			if (esEmailDuplicado(e)) {
				throw emailDuplicado(datos);
			}
			throw e;
		}

		if (invitaciones.marcarUsada(invitacion.getId(), guardado.getId(), ahora) != 1) {
			// Otra transaccion la uso, revoco o vencio: se revierte el alta del usuario.
			throw noDisponible(datos);
		}

		vinculacion.alRegistrar(invitacion, guardado);

		Map<String, Object> detalle = new LinkedHashMap<>();
		detalle.put("invitacionId", invitacion.getId().toString());
		detalle.put("familiaId", invitacion.getFamiliaId().toString());
		auditoria.registrar(new EventoAuditoria(guardado.getEscuelaId(), guardado.getId(),
				AccionAuditoria.REGISTRO_POR_INVITACION, "USUARIO", guardado.getId(), detalle, datos));
		return UsuarioActualRespuesta.de(guardado);
	}

	private ExcepcionNegocio noDisponible(DatosSolicitud datos) {
		auditarFallo(MOTIVO_NO_DISPONIBLE, datos);
		return ValidarInvitacionService.invitacionNoDisponible();
	}

	private ExcepcionNegocio emailDuplicado(DatosSolicitud datos) {
		auditarFallo(MOTIVO_EMAIL, datos);
		return emailYaRegistrado();
	}

	/** Solo el motivo: nunca el token, el email ni la contrasena. Sin escuela configurada no hay donde auditar. */
	private void auditarFallo(String motivo, DatosSolicitud datos) {
		escuelaActual.obtener().ifPresent(escuela -> auditoria.registrarFallo(new EventoAuditoria(escuela.getId(),
				null, AccionAuditoria.REGISTRO_FALLIDO, "INVITACION", null, Map.of("motivo", motivo), datos)));
	}

	private static boolean esEmailDuplicado(DataIntegrityViolationException e) {
		Throwable causa = e.getMostSpecificCause();
		String mensaje = causa == null ? null : causa.getMessage();
		return mensaje != null && mensaje.contains(RESTRICCION_EMAIL);
	}
}
