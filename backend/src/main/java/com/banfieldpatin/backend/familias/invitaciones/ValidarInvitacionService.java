package com.banfieldpatin.backend.familias.invitaciones;

import java.time.Clock;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.escuelas.Escuela;
import com.banfieldpatin.backend.escuelas.EscuelaRepository;
import com.banfieldpatin.backend.familias.FamiliaRepository;
import com.banfieldpatin.backend.familias.invitaciones.dto.InvitacionPublicaRespuesta;
import com.banfieldpatin.backend.seguridad.LimitadorIntentosLogin;
import com.banfieldpatin.backend.seguridad.SeguridadPropiedades;

/**
 * Validacion publica de un token de invitacion. Token mal formado, inexistente, vencido, revocado, usado, o con
 * escuela/familia inactivas: todos producen la misma excepcion (mismo estado, codigo y mensaje), de modo que la
 * respuesta no revela cual fue la causa. El limitador por IP usa su propia instancia (no comparte contadores con el
 * login) y un bloqueo tambien responde con el error uniforme, sin 429 ni tiempos restantes (R3).
 */
@Service
public class ValidarInvitacionService {

	/** Formato de GeneradorTokenInvitacion: 32 bytes en Base64 URL sin relleno. */
	private static final Pattern FORMATO_TOKEN = Pattern.compile("^[A-Za-z0-9_-]{43}$");

	private final InvitacionRepository invitaciones;
	private final EscuelaRepository escuelas;
	private final FamiliaRepository familias;
	private final LimitadorIntentosLogin limitador;
	private final Clock reloj;

	@Autowired
	public ValidarInvitacionService(InvitacionRepository invitaciones, EscuelaRepository escuelas,
			FamiliaRepository familias, SeguridadPropiedades propiedades, Clock reloj) {
		this(invitaciones, escuelas, familias, new LimitadorIntentosLogin(propiedades.login(), reloj), reloj);
	}

	ValidarInvitacionService(InvitacionRepository invitaciones, EscuelaRepository escuelas,
			FamiliaRepository familias, LimitadorIntentosLogin limitador, Clock reloj) {
		this.invitaciones = invitaciones;
		this.escuelas = escuelas;
		this.familias = familias;
		this.limitador = limitador;
		this.reloj = reloj;
	}

	/** Error unico para toda invitacion no utilizable (tambien lo usa el registro). */
	public static ExcepcionNegocio invitacionNoDisponible() {
		return new ExcepcionNegocio(HttpStatus.BAD_REQUEST, "INVITACION_NO_DISPONIBLE",
				"La invitación no está disponible.");
	}

	/** Un token con formato valido de GeneradorTokenInvitacion (no implica que exista). */
	static boolean tieneFormatoValido(String token) {
		return token != null && FORMATO_TOKEN.matcher(token).matches();
	}

	@Transactional(readOnly = true)
	public InvitacionPublicaRespuesta validar(String token, DatosSolicitud solicitud) {
		// La clave es solo la IP: el token no se usa como parte de ninguna clave ni log.
		String clave = LimitadorIntentosLogin.clave(solicitud.ip(), null);
		if (limitador.estaBloqueado(clave)) {
			limitador.registrarFallo(clave);
			throw invitacionNoDisponible();
		}
		Optional<InvitacionPublicaRespuesta> respuesta = resolver(token);
		if (respuesta.isEmpty()) {
			limitador.registrarFallo(clave);
			throw invitacionNoDisponible();
		}
		// El exito no reinicia el contador: un token valido no debe servir para borrar los fallos acumulados.
		return respuesta.get();
	}

	private Optional<InvitacionPublicaRespuesta> resolver(String token) {
		if (!tieneFormatoValido(token)) {
			return Optional.empty();
		}
		return invitaciones.findByTokenHash(GeneradorTokenInvitacion.sha256Hex(token))
				.filter(i -> EstadoInvitacion.desde(i, reloj.instant()) == EstadoInvitacion.PENDIENTE)
				.flatMap(i -> escuelas.findById(i.getEscuelaId())
						.filter(Escuela::isActiva)
						.filter(e -> familias.findByIdAndEscuelaId(i.getFamiliaId(), i.getEscuelaId())
								.filter(f -> f.isActiva()).isPresent())
						.map(e -> new InvitacionPublicaRespuesta(true, e.getNombre(), i.getEmailSugerido(),
								i.getExpiraEn())));
	}
}
