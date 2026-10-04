package com.banfieldpatin.backend.usuarios;

import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.banfieldpatin.backend.compartido.auditoria.AccionAuditoria;
import com.banfieldpatin.backend.compartido.auditoria.AuditoriaService;
import com.banfieldpatin.backend.compartido.auditoria.EventoAuditoria;
import com.banfieldpatin.backend.compartido.auditoria.MascaraEmail;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.escuelas.Escuela;
import com.banfieldpatin.backend.escuelas.EscuelaActual;
import com.banfieldpatin.backend.escuelas.EscuelaRepository;
import com.banfieldpatin.backend.familias.FamiliaRepository;
import com.banfieldpatin.backend.seguridad.LimitadorIntentosLogin;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.dto.UsuarioActualRespuesta;

/**
 * Login de FAMILIA y ADMIN. Toda causa de rechazo (credenciales, usuario/familia/escuela inactivos, rol equivocado,
 * bloqueo por intentos) produce la misma excepcion 401; el motivo real solo va a auditoria.
 */
@Service
public class AutenticacionService {

	private static final String MOTIVO_CREDENCIALES = "CREDENCIALES";
	private static final String MOTIVO_INACTIVO = "INACTIVO";
	private static final String MOTIVO_ROL = "ROL";
	private static final String MOTIVO_BLOQUEO = "BLOQUEO";

	private final EscuelaActual escuelaActual;
	private final EscuelaRepository escuelas;
	private final UsuarioRepository usuarios;
	private final FamiliaRepository familias;
	private final PasswordEncoder passwordEncoder;
	private final LimitadorIntentosLogin limitador;
	private final AuditoriaService auditoria;
	private final Clock reloj;
	/** Hash bcrypt aleatorio calculado al arrancar: iguala el costo de verificar cuando el email no existe. */
	private final String hashFicticio;

	public AutenticacionService(EscuelaActual escuelaActual, EscuelaRepository escuelas, UsuarioRepository usuarios,
			FamiliaRepository familias, PasswordEncoder passwordEncoder, LimitadorIntentosLogin limitador,
			AuditoriaService auditoria, Clock reloj) {
		this.escuelaActual = escuelaActual;
		this.escuelas = escuelas;
		this.usuarios = usuarios;
		this.familias = familias;
		this.passwordEncoder = passwordEncoder;
		this.limitador = limitador;
		this.auditoria = auditoria;
		this.reloj = reloj;
		this.hashFicticio = passwordEncoder.encode(UUID.randomUUID().toString());
	}

	public static ExcepcionNegocio credencialesInvalidas() {
		return new ExcepcionNegocio(HttpStatus.UNAUTHORIZED, "CREDENCIALES_INVALIDAS",
				"Email o contraseña incorrectos.");
	}

	@Transactional
	public UsuarioActualRespuesta autenticar(Rol rolRequerido, String emailCrudo, String password,
			DatosSolicitud solicitud) {
		String email = emailCrudo.trim().toLowerCase(Locale.ROOT);
		String clave = LimitadorIntentosLogin.clave(solicitud.ip(), email);
		Optional<Escuela> escuelaOpt = escuelaActual.obtener();

		// El limitador se consulta antes de tocar la base y el rechazo es identico a una contrasena incorrecta.
		if (limitador.estaBloqueado(clave)) {
			coincide(password, hashFicticio);
			throw fallo(escuelaOpt.orElse(null), null, rolRequerido, MOTIVO_BLOQUEO, email, clave, solicitud);
		}
		if (escuelaOpt.isEmpty()) {
			coincide(password, hashFicticio);
			throw fallo(null, null, rolRequerido, MOTIVO_CREDENCIALES, email, clave, solicitud);
		}
		Escuela escuela = escuelaOpt.get();

		Usuario usuario = usuarios.buscarPorEmail(escuela.getId(), email).orElse(null);
		if (usuario == null) {
			coincide(password, hashFicticio);
			throw fallo(escuela, null, rolRequerido, MOTIVO_CREDENCIALES, email, clave, solicitud);
		}
		if (!coincide(password, usuario.getPasswordHash())) {
			throw fallo(escuela, usuario, rolRequerido, MOTIVO_CREDENCIALES, email, clave, solicitud);
		}
		if (usuario.getRol() != rolRequerido) {
			throw fallo(escuela, usuario, rolRequerido, MOTIVO_ROL, email, clave, solicitud);
		}
		if (!principalesActivos(usuario, escuela)) {
			throw fallo(escuela, usuario, rolRequerido, MOTIVO_INACTIVO, email, clave, solicitud);
		}

		usuario.registrarAcceso(reloj.instant());
		if (passwordEncoder.upgradeEncoding(usuario.getPasswordHash())) {
			usuario.cambiarPasswordHash(passwordEncoder.encode(password));
		}
		limitador.registrarExito(clave);
		auditoria.registrar(new EventoAuditoria(usuario.getEscuelaId(), usuario.getId(),
				AccionAuditoria.LOGIN_EXITOSO, "USUARIO", usuario.getId(), Map.of("canal", rolRequerido.name()),
				solicitud));
		return UsuarioActualRespuesta.de(usuario);
	}

	/** Recarga el usuario para que una desactivacion posterior al JWT se respete; vacio = sesion invalida. */
	@Transactional(readOnly = true)
	public Optional<UsuarioActualRespuesta> actual(UsuarioAutenticado identidad) {
		return usuarios.findByIdAndEscuelaId(identidad.id(), identidad.escuelaId())
				.filter(u -> escuelas.findById(u.getEscuelaId())
						.filter(e -> principalesActivos(u, e)).isPresent())
				.map(UsuarioActualRespuesta::de);
	}

	public void cerrarSesion(UsuarioAutenticado identidad, DatosSolicitud solicitud) {
		auditoria.registrar(new EventoAuditoria(identidad.escuelaId(), identidad.id(), AccionAuditoria.LOGOUT,
				"USUARIO", identidad.id(), Map.of(), solicitud));
	}

	private boolean principalesActivos(Usuario usuario, Escuela escuela) {
		if (!usuario.isActivo() || !escuela.isActiva()) {
			return false;
		}
		return usuario.getRol() != Rol.FAMILIA || (usuario.getFamiliaId() != null
				&& familias.findByIdAndEscuelaId(usuario.getFamiliaId(), usuario.getEscuelaId())
						.filter(f -> f.isActiva()).isPresent());
	}

	/** bcrypt rechaza claves de mas de 72 bytes con una excepcion: aca equivale a "no coincide". */
	private boolean coincide(String password, String hash) {
		try {
			return passwordEncoder.matches(password, hash);
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	private ExcepcionNegocio fallo(Escuela escuela, Usuario usuario, Rol canal, String motivo, String email,
			String clave, DatosSolicitud solicitud) {
		limitador.registrarFallo(clave);
		if (escuela != null) {
			auditoria.registrarFallo(new EventoAuditoria(escuela.getId(), usuario == null ? null : usuario.getId(),
					AccionAuditoria.LOGIN_FALLIDO, "USUARIO", usuario == null ? null : usuario.getId(),
					Map.of("canal", canal.name(), "motivo", motivo, "emailEnmascarado",
							MascaraEmail.enmascarar(email)),
					solicitud));
		}
		return credencialesInvalidas();
	}
}
