package com.banfieldpatin.backend.familias.invitaciones;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.banfieldpatin.backend.compartido.auditoria.AccionAuditoria;
import com.banfieldpatin.backend.compartido.auditoria.AuditoriaService;
import com.banfieldpatin.backend.compartido.auditoria.EventoAuditoria;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.compartido.web.Pagina;
import com.banfieldpatin.backend.familias.Familia;
import com.banfieldpatin.backend.familias.FamiliaRepository;
import com.banfieldpatin.backend.familias.dto.FamiliaResumen;
import com.banfieldpatin.backend.familias.invitaciones.dto.CrearInvitacionSolicitud;
import com.banfieldpatin.backend.familias.invitaciones.dto.InvitacionCreadaRespuesta;
import com.banfieldpatin.backend.familias.invitaciones.dto.InvitacionRespuesta;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;

/**
 * Alta, listado y revocacion de invitaciones por un ADMIN. La escuela y el creador salen siempre del JWT
 * ({@link UsuarioAutenticado}); un id ajeno a la escuela es indistinguible de uno inexistente (sin IDOR).
 * El token en claro solo existe en la respuesta de creacion: se guarda unicamente su hash y nunca se audita.
 */
@Service
@EnableConfigurationProperties(InvitacionesPropiedades.class)
public class InvitacionAdminService {

	private final InvitacionRepository invitaciones;
	private final FamiliaRepository familias;
	private final AuditoriaService auditoria;
	private final GeneradorTokenInvitacion generador;
	private final InvitacionesPropiedades propiedades;
	private final Clock reloj;
	private final String urlBaseFrontend;

	public InvitacionAdminService(InvitacionRepository invitaciones, FamiliaRepository familias,
			AuditoriaService auditoria, GeneradorTokenInvitacion generador, InvitacionesPropiedades propiedades,
			Clock reloj, @Value("${banfield.frontend.url-base:}") String urlBaseFrontend) {
		this.invitaciones = invitaciones;
		this.familias = familias;
		this.auditoria = auditoria;
		this.generador = generador;
		this.propiedades = propiedades;
		this.reloj = reloj;
		this.urlBaseFrontend = urlBaseFrontend == null ? "" : urlBaseFrontend.strip();
	}

	public static ExcepcionNegocio familiaNoEncontrada() {
		return new ExcepcionNegocio(HttpStatus.NOT_FOUND, "FAMILIA_NO_ENCONTRADA", "La familia no existe.");
	}

	public static ExcepcionNegocio invitacionNoEncontrada() {
		return new ExcepcionNegocio(HttpStatus.NOT_FOUND, "INVITACION_NO_ENCONTRADA", "La invitación no existe.");
	}

	public static ExcepcionNegocio invitacionNoRevocable() {
		return new ExcepcionNegocio(HttpStatus.CONFLICT, "INVITACION_NO_REVOCABLE",
				"La invitación ya fue utilizada y no puede revocarse.");
	}

	@Transactional
	public InvitacionCreadaRespuesta crear(UsuarioAutenticado admin, CrearInvitacionSolicitud solicitud,
			DatosSolicitud datos) {
		if ((solicitud.familiaId() != null) == (solicitud.nuevaFamilia() != null)) {
			throw new ExcepcionNegocio(HttpStatus.BAD_REQUEST, "VALIDACION",
					"Indicá familiaId o nuevaFamilia, pero no ambos ni ninguno.");
		}
		Instant ahora = reloj.instant();
		Duration vigencia = vigencia(solicitud.diasVigencia());

		Familia familia;
		boolean familiaCreada = solicitud.nuevaFamilia() != null;
		if (familiaCreada) {
			familia = familias.save(Familia.crear(admin.escuelaId(), solicitud.nuevaFamilia().nombreReferencia().strip()));
			auditoria.registrar(new EventoAuditoria(admin.escuelaId(), admin.id(), AccionAuditoria.FAMILIA_CREADA,
					"FAMILIA", familia.getId(), Map.of("origen", "INVITACION"), datos));
		} else {
			// Otra escuela, inexistente o inactiva: mismo 404, sin revelar cual de los casos es.
			familia = familias.findByIdAndEscuelaId(solicitud.familiaId(), admin.escuelaId())
					.filter(Familia::isActiva)
					.orElseThrow(InvitacionAdminService::familiaNoEncontrada);
		}

		String token = generador.generar();
		String emailSugerido = normalizarEmail(solicitud.emailSugerido());
		Invitacion invitacion = invitaciones.save(Invitacion.crear(admin.escuelaId(), familia.getId(),
				GeneradorTokenInvitacion.sha256Hex(token), emailSugerido, ahora, ahora.plus(vigencia), admin.id()));

		// El detalle nunca incluye el token ni su hash.
		Map<String, Object> detalle = new LinkedHashMap<>();
		detalle.put("familiaId", familia.getId().toString());
		detalle.put("familiaCreada", familiaCreada);
		detalle.put("expiraEn", invitacion.getExpiraEn().toString());
		detalle.put("conEmailSugerido", emailSugerido != null);
		auditoria.registrar(new EventoAuditoria(admin.escuelaId(), admin.id(), AccionAuditoria.INVITACION_CREADA,
				"INVITACION", invitacion.getId(), detalle, datos));

		return new InvitacionCreadaRespuesta(invitacion.getId(), EstadoInvitacion.PENDIENTE, token,
				enlace(token), invitacion.getExpiraEn(), resumen(familia), emailSugerido);
	}

	@Transactional(readOnly = true)
	public Pagina<InvitacionRespuesta> listar(UsuarioAutenticado admin, EstadoInvitacion estado, Pageable pageable) {
		Instant ahora = reloj.instant();
		Page<Invitacion> pagina = invitaciones.listar(admin.escuelaId(),
				estado == null ? InvitacionRepository.TODOS : estado.name(), ahora, pageable);
		Map<UUID, Familia> porId = familias
				.findAllById(pagina.getContent().stream().map(Invitacion::getFamiliaId).collect(Collectors.toSet()))
				.stream().collect(Collectors.toMap(Familia::getId, f -> f, (a, b) -> a, HashMap::new));
		return Pagina.de(pagina, i -> respuesta(i, porId.get(i.getFamiliaId()), ahora));
	}

	/**
	 * PENDIENTE o EXPIRADA (sin usar) se revocan; REVOCADA responde 200 sin tocar los datos originales
	 * (idempotente); USADA da 409. La auditoria solo se escribe cuando esta llamada revoca de verdad.
	 */
	@Transactional
	public InvitacionRespuesta revocar(UsuarioAutenticado admin, UUID id, DatosSolicitud datos) {
		Instant ahora = reloj.instant();
		Invitacion actual = invitaciones.findByIdAndEscuelaId(id, admin.escuelaId())
				.orElseThrow(InvitacionAdminService::invitacionNoEncontrada);
		EstadoInvitacion estado = EstadoInvitacion.desde(actual, ahora);
		if (estado == EstadoInvitacion.USADA) {
			throw invitacionNoRevocable();
		}
		if (estado == EstadoInvitacion.REVOCADA) {
			return respuesta(actual, ahora);
		}

		int afectadas = invitaciones.marcarRevocada(id, admin.escuelaId(), admin.id(), ahora);
		// La actualizacion limpia el contexto de persistencia: se relee el estado real.
		Invitacion despues = invitaciones.findByIdAndEscuelaId(id, admin.escuelaId())
				.orElseThrow(InvitacionAdminService::invitacionNoEncontrada);
		if (afectadas == 0) {
			// Otra transaccion la uso o la revoco entre la lectura y la actualizacion condicional.
			if (EstadoInvitacion.desde(despues, ahora) == EstadoInvitacion.USADA) {
				throw invitacionNoRevocable();
			}
			return respuesta(despues, ahora);
		}
		auditoria.registrar(new EventoAuditoria(admin.escuelaId(), admin.id(), AccionAuditoria.INVITACION_REVOCADA,
				"INVITACION", id, Map.of("familiaId", despues.getFamiliaId().toString()), datos));
		return respuesta(despues, ahora);
	}

	private Duration vigencia(Integer dias) {
		if (dias == null) {
			return propiedades.vigenciaPorDefecto();
		}
		Duration pedida = Duration.ofDays(dias);
		if (dias < 1 || pedida.compareTo(propiedades.vigenciaMaxima()) > 0) {
			throw new ExcepcionNegocio(HttpStatus.BAD_REQUEST, "VALIDACION",
					"La vigencia pedida supera el máximo permitido de " + propiedades.vigenciaMaxima().toDays()
							+ " días.");
		}
		return pedida;
	}

	private static String normalizarEmail(String email) {
		if (email == null || email.isBlank()) {
			return null;
		}
		return email.strip().toLowerCase(Locale.ROOT);
	}

	private String enlace(String token) {
		if (urlBaseFrontend.isEmpty()) {
			return null;
		}
		String base = urlBaseFrontend.endsWith("/") ? urlBaseFrontend.substring(0, urlBaseFrontend.length() - 1)
				: urlBaseFrontend;
		return base + "/registro/invitacion/" + token;
	}

	private static FamiliaResumen resumen(Familia f) {
		return new FamiliaResumen(f.getId(), f.getNombreReferencia());
	}

	private InvitacionRespuesta respuesta(Invitacion i, Instant ahora) {
		Familia familia = familias.findByIdAndEscuelaId(i.getFamiliaId(), i.getEscuelaId()).orElse(null);
		return respuesta(i, familia, ahora);
	}

	private static InvitacionRespuesta respuesta(Invitacion i, Familia familia, Instant ahora) {
		return new InvitacionRespuesta(i.getId(),
				familia == null ? new FamiliaResumen(i.getFamiliaId(), null) : resumen(familia),
				i.getEmailSugerido(), EstadoInvitacion.desde(i, ahora), i.getCreadoEn(), i.getExpiraEn(),
				i.getUsadoEn(), i.getRevocadaEn());
	}
}
