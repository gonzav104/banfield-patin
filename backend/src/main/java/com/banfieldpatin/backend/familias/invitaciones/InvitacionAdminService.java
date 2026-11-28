package com.banfieldpatin.backend.familias.invitaciones;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Collection;
import java.util.Set;
import java.util.List;
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
import com.banfieldpatin.backend.familias.tutores.TutorRepository;
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
	private final TutorRepository tutores;
	private final AuditoriaService auditoria;
	private final GeneradorTokenInvitacion generador;
	private final InvitacionesPropiedades propiedades;
	private final Clock reloj;
	private final String urlBaseFrontend;

	public InvitacionAdminService(InvitacionRepository invitaciones, FamiliaRepository familias, TutorRepository tutores,
			AuditoriaService auditoria, GeneradorTokenInvitacion generador, InvitacionesPropiedades propiedades,
			Clock reloj, @Value("${banfield.frontend.url-base:}") String urlBaseFrontend) {
		this.invitaciones = invitaciones;
		this.familias = familias;
		this.tutores = tutores;
		this.auditoria = auditoria;
		this.generador = generador;
		this.propiedades = propiedades;
		this.reloj = reloj;
		this.urlBaseFrontend = UrlBaseFrontend.normalizar(urlBaseFrontend);
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

		if (solicitud.tutorId() != null) {
			if (familiaCreada) {
				throw new ExcepcionNegocio(HttpStatus.BAD_REQUEST, "VALIDACION",
						"Solo se puede invitar a un tutor de una familia existente.");
			}
			// Otra familia o escuela, inexistente, inactivo o ya con cuenta: mismo 409, sin revelar cuál.
			boolean disponible = tutores.findByIdAndEscuelaId(solicitud.tutorId(), admin.escuelaId())
					.filter(t -> t.getFamiliaId().equals(familia.getId()) && t.isActivo() && t.getUsuarioId() == null)
					.isPresent();
			if (!disponible) {
				throw new ExcepcionNegocio(HttpStatus.CONFLICT, "TUTOR_NO_DISPONIBLE",
						"Esa persona no está disponible para invitarla: revisá que sea de la familia, esté activa y no tenga cuenta.");
			}
		}

		String token = generador.generar();
		String emailSugerido = normalizarEmail(solicitud.emailSugerido());
		Invitacion invitacion = invitaciones.save(Invitacion.crear(admin.escuelaId(), familia.getId(), solicitud.tutorId(),
				GeneradorTokenInvitacion.sha256Hex(token), emailSugerido, ahora, ahora.plus(vigencia), admin.id()));

		// El detalle nunca incluye el token ni su hash.
		Map<String, Object> detalle = new LinkedHashMap<>();
		detalle.put("familiaId", familia.getId().toString());
		detalle.put("familiaCreada", familiaCreada);
		detalle.put("expiraEn", invitacion.getExpiraEn().toString());
		detalle.put("conEmailSugerido", emailSugerido != null);
		detalle.put("conTutor", solicitud.tutorId() != null);
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
		Map<UUID, UUID> cuentas = cuentasDeLasPersonas(pagina.getContent(), admin.escuelaId());
		return Pagina.de(pagina, i -> respuesta(i, porId.get(i.getFamiliaId()), ahora, cuentas));
	}

	/** Invitaciones de una familia de la escuela; una familia ajena o inexistente da una página vacía (sin oráculo). */
	@Transactional(readOnly = true)
	public Pagina<InvitacionRespuesta> listarDeFamilia(UsuarioAutenticado admin, UUID familiaId,
			EstadoInvitacion estado, Pageable pageable) {
		Instant ahora = reloj.instant();
		Page<Invitacion> pagina = invitaciones.listarDeFamilia(admin.escuelaId(), familiaId,
				estado == null ? InvitacionRepository.TODOS : estado.name(), ahora, pageable);
		Familia familia = pagina.isEmpty() ? null
				: familias.findByIdAndEscuelaId(familiaId, admin.escuelaId()).orElse(null);
		Map<UUID, UUID> cuentas = cuentasDeLasPersonas(pagina.getContent(), admin.escuelaId());
		return Pagina.de(pagina, i -> respuesta(i, familia, ahora, cuentas));
	}

	@Transactional(readOnly = true)
	public InvitacionRespuesta obtener(UsuarioAutenticado admin, UUID id) {
		Invitacion invitacion = invitaciones.findByIdAndEscuelaId(id, admin.escuelaId())
				.orElseThrow(InvitacionAdminService::invitacionNoEncontrada);
		return respuesta(invitacion, reloj.instant());
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
		// El token va en el fragmento (#): el navegador no lo envia en la peticion del documento, asi que no queda en
		// logs de acceso del servidor web, proxies ni CDN. El frontend lo retira de la URL apenas lo lee.
		return urlBaseFrontend + "/registro/invitacion#" + token;
	}

	private static FamiliaResumen resumen(Familia f) {
		return new FamiliaResumen(f.getId(), f.getNombreReferencia());
	}

	private InvitacionRespuesta respuesta(Invitacion i, Instant ahora) {
		Familia familia = familias.findByIdAndEscuelaId(i.getFamiliaId(), i.getEscuelaId()).orElse(null);
		return respuesta(i, familia, ahora, cuentasDeLasPersonas(List.of(i), i.getEscuelaId()));
	}

	/** Para las invitaciones que nombran a una persona: la cuenta que hoy tiene cada tutor invitado (null si ninguna). */
	private Map<UUID, UUID> cuentasDeLasPersonas(Collection<Invitacion> lista, UUID escuelaId) {
		Set<UUID> ids = lista.stream().map(Invitacion::getTutorId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
		Map<UUID, UUID> cuentas = new HashMap<>();
		if (!ids.isEmpty()) {
			tutores.findAllById(ids).stream().filter(t -> t.getEscuelaId().equals(escuelaId))
					.forEach(t -> cuentas.put(t.getId(), t.getUsuarioId()));
		}
		return cuentas;
	}

	private static InvitacionRespuesta respuesta(Invitacion i, Familia familia, Instant ahora, Map<UUID, UUID> cuentas) {
		String asociacion = null;
		if (i.getTutorId() != null) {
			if (i.getUsadoEn() == null) {
				asociacion = InvitacionRespuesta.PENDIENTE;
			} else {
				asociacion = i.getUsuarioId() != null && i.getUsuarioId().equals(cuentas.get(i.getTutorId()))
						? InvitacionRespuesta.ASOCIADA : InvitacionRespuesta.NO_ASOCIADA;
			}
		}
		return new InvitacionRespuesta(i.getId(),
				familia == null ? new FamiliaResumen(i.getFamiliaId(), null) : resumen(familia),
				i.getEmailSugerido(), EstadoInvitacion.desde(i, ahora), i.getCreadoEn(), i.getExpiraEn(),
				i.getUsadoEn(), i.getRevocadaEn(), i.getTutorId(), asociacion);
	}
}
