package com.banfieldpatin.backend.usuarios.mfa;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Arrays;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.banfieldpatin.backend.compartido.auditoria.AccionAuditoria;
import com.banfieldpatin.backend.compartido.auditoria.AuditoriaService;
import com.banfieldpatin.backend.compartido.auditoria.EventoAuditoria;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.escuelas.EscuelaRepository;
import com.banfieldpatin.backend.seguridad.LimitadorIntentosLogin;
import com.banfieldpatin.backend.seguridad.SeguridadPropiedades;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.seguridad.mfa.Base32;
import com.banfieldpatin.backend.seguridad.mfa.CifradorSecretoMfa;
import com.banfieldpatin.backend.seguridad.mfa.Totp;
import com.banfieldpatin.backend.usuarios.Rol;
import com.banfieldpatin.backend.usuarios.Usuario;
import com.banfieldpatin.backend.usuarios.UsuarioRepository;
import com.banfieldpatin.backend.usuarios.dto.UsuarioActualRespuesta;
import com.banfieldpatin.backend.usuarios.mfa.dto.EnrolamientoMfaRespuesta;

/**
 * Segundo factor TOTP de las cuentas ADMIN (RNF-03).
 * <ul>
 * <li>Un codigo incorrecto, vencido, repetido (paso ya usado) o bloqueado por intentos produce la MISMA respuesta
 * 401 CODIGO_MFA_INVALIDO; el motivo real solo va a auditoria (MFA_FALLO).</li>
 * <li>El limitador es por usuario (en memoria) y se consulta sin acortar el trabajo: el codigo se evalua igual y
 * el bloqueo solo cambia el resultado, para no abrir un canal por tiempos.</li>
 * <li>La identidad (usuario y escuela) sale siempre del JWT; nunca del cuerpo ni de la ruta, salvo el objetivo del
 * reinicio, que se busca dentro de la escuela del actor.</li>
 * </ul>
 */
@Service
public class MfaService {

	private static final String MOTIVO_CODIGO = "CODIGO";
	private static final String MOTIVO_BLOQUEO = "BLOQUEO";
	private static final String MOTIVO_REPETIDO = "REPETIDO";

	private final UsuarioRepository usuarios;
	private final EscuelaRepository escuelas;
	private final UsuarioMfaRepository mfas;
	private final CifradorSecretoMfa cifrador;
	private final AuditoriaService auditoria;
	private final Clock reloj;
	private final String emisor;
	/** Instancia propia (no el bean del login): clave = id de usuario, mismos limites que el login. */
	private final LimitadorIntentosLogin limitador;
	private final SecureRandom azar = new SecureRandom();

	@Autowired
	public MfaService(UsuarioRepository usuarios, EscuelaRepository escuelas, UsuarioMfaRepository mfas,
			CifradorSecretoMfa cifrador, AuditoriaService auditoria, Clock reloj, SeguridadPropiedades propiedades) {
		this.usuarios = usuarios;
		this.escuelas = escuelas;
		this.mfas = mfas;
		this.cifrador = cifrador;
		this.auditoria = auditoria;
		this.reloj = reloj;
		this.emisor = propiedades.mfa().emisor();
		this.limitador = new LimitadorIntentosLogin(propiedades.login(), reloj);
	}

	public static ExcepcionNegocio codigoInvalido() {
		return new ExcepcionNegocio(HttpStatus.UNAUTHORIZED, "CODIGO_MFA_INVALIDO",
				"El código ingresado no es válido o ya venció.");
	}

	public static ExcepcionNegocio estadoInvalido() {
		return new ExcepcionNegocio(HttpStatus.CONFLICT, "MFA_ESTADO_INVALIDO",
				"El segundo factor no está en el estado esperado para esta operación.");
	}

	private static ExcepcionNegocio noAutenticado() {
		return new ExcepcionNegocio(HttpStatus.UNAUTHORIZED, "NO_AUTENTICADO", "Necesitás iniciar sesión para acceder.");
	}

	/**
	 * Genera un secreto de 20 bytes y lo guarda cifrado SIN confirmar. Antes de confirmar es idempotente: repetir la
	 * llamada reemplaza el secreto anterior (p. ej. si el usuario no llego a escanear el QR).
	 */
	@Transactional
	public EnrolamientoMfaRespuesta enrolar(UsuarioAutenticado identidad, DatosSolicitud solicitud) {
		Usuario usuario = cargarAdminActivo(identidad);
		if (usuario.isMfaHabilitado()) {
			throw estadoInvalido();
		}
		byte[] secreto = new byte[Totp.LONGITUD_SECRETO_BYTES];
		azar.nextBytes(secreto);
		try {
			byte[] cifrado = cifrador.cifrar(secreto, usuario.getId());
			if (mfas.guardarEnrolamiento(usuario.getId(), usuario.getEscuelaId(), cifrado) != 1) {
				// Otra peticion lo confirmo entre la lectura y la escritura.
				throw estadoInvalido();
			}
			auditoria.registrar(evento(AccionAuditoria.MFA_ENROLADO, usuario.getEscuelaId(), usuario.getId(),
					usuario.getId(), Map.of(), solicitud));
			String base32 = Base32.codificar(secreto, false);
			return new EnrolamientoMfaRespuesta(urlOtpauth(usuario.getEmail(), base32), base32);
		} finally {
			Arrays.fill(secreto, (byte) 0);
		}
	}

	/** Verifica el primer codigo contra el secreto sin confirmar y, si coincide, activa el MFA del usuario. */
	@Transactional
	public UsuarioActualRespuesta confirmar(UsuarioAutenticado identidad, String codigo, DatosSolicitud solicitud) {
		return completar(identidad, codigo, solicitud, true);
	}

	/** Verifica el codigo de un ADMIN ya enrolado (inicio de sesion normal). */
	@Transactional
	public UsuarioActualRespuesta verificar(UsuarioAutenticado identidad, String codigo, DatosSolicitud solicitud) {
		return completar(identidad, codigo, solicitud, false);
	}

	/**
	 * Reinicia el MFA de OTRO administrador de la misma escuela (borra su secreto; vuelve a enrolar al iniciar sesion).
	 * Decision: un ADMIN NO puede reiniciar su propio MFA (403): con una sesion robada eso permitiria saltar el
	 * segundo factor. Si se pierden los dispositivos de todos los ADMIN, el procedimiento es manual en la base
	 * (docs/SEGURIDAD.md). No cierra las sesiones ya emitidas del objetivo (el JWT sigue valido hasta vencer).
	 */
	@Transactional
	public void reiniciar(UsuarioAutenticado actor, UUID objetivoId, DatosSolicitud solicitud) {
		if (actor.id().equals(objetivoId)) {
			throw new ExcepcionNegocio(HttpStatus.FORBIDDEN, "MFA_AUTOREINICIO_NO_PERMITIDO",
					"No podés reiniciar tu propio segundo factor; pedile a otro administrador.");
		}
		Usuario objetivo = usuarios.findByIdAndEscuelaId(objetivoId, actor.escuelaId())
				.filter(u -> u.getRol() == Rol.ADMIN)
				.orElseThrow(() -> new ExcepcionNegocio(HttpStatus.NOT_FOUND, "USUARIO_NO_ENCONTRADO",
						"El administrador no existe."));
		mfas.eliminar(objetivo.getId());
		objetivo.deshabilitarMfa();
		// Quien perdio su dispositivo no debe quedar bloqueado por intentos fallidos previos.
		limitador.registrarExito(objetivo.getId().toString());
		auditoria.registrar(evento(AccionAuditoria.MFA_REINICIADO, actor.escuelaId(), actor.id(), objetivo.getId(),
				Map.of(), solicitud));
	}

	private UsuarioActualRespuesta completar(UsuarioAutenticado identidad, String codigo, DatosSolicitud solicitud,
			boolean confirmacion) {
		Usuario usuario = cargarAdminActivo(identidad);
		UsuarioMfa mfa = mfas.findById(usuario.getId()).orElse(null);
		// Confirmar exige un enrolamiento sin confirmar; verificar, uno confirmado y el usuario con MFA habilitado.
		boolean estadoEsperado = mfa != null && mfa.estaConfirmado() == !confirmacion
				&& usuario.isMfaHabilitado() == !confirmacion;
		if (!estadoEsperado) {
			throw estadoInvalido();
		}
		String clave = usuario.getId().toString();
		String etapa = confirmacion ? "CONFIRMACION" : "VERIFICACION";

		byte[] secreto = cifrador.descifrar(mfa.getSecretoCifrado(), usuario.getId());
		OptionalLong paso;
		try {
			paso = Totp.verificar(secreto, codigo, reloj.instant(), mfa.getUltimoPasoUsado());
		} finally {
			Arrays.fill(secreto, (byte) 0);
		}
		if (limitador.estaBloqueado(clave)) {
			throw fallo(usuario, etapa, MOTIVO_BLOQUEO, solicitud);
		}
		if (paso.isEmpty()) {
			throw fallo(usuario, etapa, MOTIVO_CODIGO, solicitud);
		}
		int filas = confirmacion
				? mfas.confirmar(usuario.getId(), paso.getAsLong(), reloj.instant())
				: mfas.consumirPaso(usuario.getId(), paso.getAsLong());
		if (filas != 1) {
			// Repeticion concurrente del mismo codigo: la sentencia condicional solo deja pasar a una peticion.
			throw fallo(usuario, etapa, MOTIVO_REPETIDO, solicitud);
		}
		if (confirmacion) {
			usuario.habilitarMfa();
		}
		limitador.registrarExito(clave);
		auditoria.registrar(evento(confirmacion ? AccionAuditoria.MFA_CONFIRMADO : AccionAuditoria.MFA_VERIFICADO,
				usuario.getEscuelaId(), usuario.getId(), usuario.getId(), Map.of(), solicitud));
		return UsuarioActualRespuesta.de(usuario);
	}

	private ExcepcionNegocio fallo(Usuario usuario, String etapa, String motivo, DatosSolicitud solicitud) {
		limitador.registrarFallo(usuario.getId().toString());
		auditoria.registrarFallo(evento(AccionAuditoria.MFA_FALLO, usuario.getEscuelaId(), usuario.getId(),
				usuario.getId(), Map.of("etapa", etapa, "motivo", motivo), solicitud));
		return codigoInvalido();
	}

	/** El usuario del token debe seguir existiendo, ser ADMIN y estar activo con su escuela activa. */
	private Usuario cargarAdminActivo(UsuarioAutenticado identidad) {
		Usuario usuario = usuarios.findByIdAndEscuelaId(identidad.id(), identidad.escuelaId())
				.orElseThrow(MfaService::noAutenticado);
		boolean escuelaActiva = escuelas.findById(usuario.getEscuelaId()).filter(e -> e.isActiva()).isPresent();
		if (usuario.getRol() != Rol.ADMIN || !usuario.isActivo() || !escuelaActiva) {
			throw noAutenticado();
		}
		return usuario;
	}

	private static EventoAuditoria evento(AccionAuditoria accion, UUID escuelaId, UUID actorId, UUID recursoId,
			Map<String, Object> detalle, DatosSolicitud solicitud) {
		return new EventoAuditoria(escuelaId, actorId, accion, "USUARIO", recursoId, detalle, solicitud);
	}

	/** URI estandar de Google Authenticator (Key URI Format). Contiene el secreto: nunca se registra. */
	private String urlOtpauth(String email, String secretoBase32) {
		return "otpauth://totp/" + codificar(emisor) + ":" + codificar(email) + "?secret=" + secretoBase32
				+ "&issuer=" + codificar(emisor) + "&algorithm=SHA1&digits=" + Totp.DIGITOS + "&period="
				+ Totp.PASO_SEGUNDOS;
	}

	private static String codificar(String texto) {
		return URLEncoder.encode(texto, StandardCharsets.UTF_8).replace("+", "%20");
	}
}
