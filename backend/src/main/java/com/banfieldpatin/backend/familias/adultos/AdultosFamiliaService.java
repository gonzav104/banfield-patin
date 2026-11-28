package com.banfieldpatin.backend.familias.adultos;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.banfieldpatin.backend.compartido.auditoria.AccionAuditoria;
import com.banfieldpatin.backend.compartido.auditoria.AuditoriaService;
import com.banfieldpatin.backend.compartido.auditoria.EventoAuditoria;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.deportistas.DocumentoIdentidad;
import com.banfieldpatin.backend.deportistas.EnmascaradoDni;
import com.banfieldpatin.backend.familias.FamiliaAdminService;
import com.banfieldpatin.backend.familias.adultos.dto.AdultoActualizacion;
import com.banfieldpatin.backend.familias.adultos.dto.AdultoAlta;
import com.banfieldpatin.backend.familias.adultos.dto.AdultoDeFamilia;
import com.banfieldpatin.backend.familias.adultos.dto.AdultosRespuesta;
import com.banfieldpatin.backend.familias.perfil.AutoservicioPropiedades;
import com.banfieldpatin.backend.familias.perfil.PerfilPropioService;
import com.banfieldpatin.backend.familias.perfil.ReglasAutoservicioTutores;
import com.banfieldpatin.backend.familias.tutores.Tutor;
import com.banfieldpatin.backend.familias.tutores.TutorAdminService;
import com.banfieldpatin.backend.familias.tutores.TutorRepository;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;

import jakarta.persistence.EntityManager;

/**
 * Otros adultos de la familia (U21-BIS C2): la familia carga a la pareja, abuelos u otras personas sin exigir que tengan
 * cuenta, y consulta y edita lo que le corresponde.
 *
 * <ul>
 * <li><b>Declarar no autoriza.</b> Ningún camino de este servicio toca {@code familia_deportista}, {@code tutor_deportista},
 * cuentas ni roles: un adulto declarado no gana acceso a deportistas ni responsabilidades.</li>
 * <li><b>Alcance:</b> escuela, familia y cuenta salen del token; solo adultos ACTIVOS de la propia familia. Cualquier otro id
 * (otra familia o escuela, inactivo, inexistente) es el mismo 404 {@code TUTOR_NO_ENCONTRADO}. Nunca se devuelve el DNI
 * completo ni se consulta otra familia (los duplicados se buscan solo en la propia).</li>
 * <li><b>Qué se edita:</b> adultos sin cuenta y el propio perfil; uno con cuenta propia actualiza sus datos él mismo (409
 * {@code TUTOR_CON_CUENTA}). La identidad respaldada (VERIFICADA o con un DNI APROBADO) no se cambia desde acá.</li>
 * <li><b>Concurrencia:</b> familia {@code FOR SHARE} → bloqueo consultivo de la familia (tope y duplicados sin carreras) →
 * tutor {@code FOR NO KEY UPDATE} → documentos {@code FOR SHARE}; la edición exige la revisión observada. El alta es
 * repetible con la misma {@code Idempotency-Key}.</li>
 * <li><b>Bandera:</b> apagada, solo se puede consultar.</li>
 * </ul>
 */
@Service
@EnableConfigurationProperties(AutoservicioPropiedades.class)
public class AdultosFamiliaService {

	private static final String RECURSO = "TUTOR";

	private final TutorRepository tutores;
	private final ReglasAutoservicioTutores reglas;
	private final AutoservicioPropiedades propiedades;
	private final AuditoriaService auditoria;
	private final EntityManager em;

	public AdultosFamiliaService(TutorRepository tutores, ReglasAutoservicioTutores reglas,
			AutoservicioPropiedades propiedades, AuditoriaService auditoria, EntityManager em) {
		this.tutores = tutores;
		this.reglas = reglas;
		this.propiedades = propiedades;
		this.auditoria = auditoria;
		this.em = em;
	}

	public static ExcepcionNegocio deshabilitado() {
		return new ExcepcionNegocio(HttpStatus.SERVICE_UNAVAILABLE, "AUTOSERVICIO_TUTORES_DESHABILITADO",
				"Cargar o editar adultos de tu familia desde acá todavía no está habilitado.");
	}

	public static ExcepcionNegocio conCuentaPropia() {
		return new ExcepcionNegocio(HttpStatus.CONFLICT, "TUTOR_CON_CUENTA",
				"Esta persona tiene su propia cuenta y actualiza sus datos desde ahí.");
	}

	public static ExcepcionNegocio claveReutilizada() {
		return new ExcepcionNegocio(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUTILIZADA",
				"Ese envío ya se usó con otros datos. Actualizá la pantalla y volvé a intentar.");
	}

	@Transactional(readOnly = true)
	public AdultosRespuesta listar(UsuarioAutenticado u) {
		exigirFamilia(u);
		List<Tutor> activos = tutores.activosDeFamilia(u.escuelaId(), u.familiaId());
		Set<UUID> conDniAprobado = reglas.conDniAprobado(u.escuelaId(), activos.stream().map(Tutor::getId).toList());
		long cargados = activos.stream().filter(t -> "FAMILIA".equals(t.getOrigen())).count();
		return new AdultosRespuesta(propiedades.tutoresHabilitado(), reglas.tope(), cargados,
				activos.stream().map(t -> vista(u, t, conDniAprobado.contains(t.getId()))).toList());
	}

	/** 201 con el adulto creado o, si la clave repite un alta ya hecha con los mismos datos, con el original. */
	@Transactional
	public AdultoDeFamilia crear(UsuarioAutenticado u, UUID idempotencia, AdultoAlta s, DatosSolicitud datos) {
		exigirHabilitado();
		exigirFamilia(u);
		bloquearFamilia(u);
		reglas.serializarAltasDeLaFamilia(u.escuelaId(), u.familiaId());
		String dni = DocumentoIdentidad.normalizarDni(s.dni());
		String huella = huella(u, s.nombre(), s.apellido(), dni, s.telefono(), s.email(), s.parentesco());
		Tutor previo = tutores.altaPorIdempotencia(u.escuelaId(), u.id(), idempotencia).orElse(null);
		if (previo != null) {
			if (!huella.equals(previo.getHuellaAlta())) {
				throw claveReutilizada();
			}
			return vista(u, previo, !reglas.conDniAprobado(u.escuelaId(), List.of(previo.getId())).isEmpty());
		}
		reglas.exigirLugar(u.escuelaId(), u.familiaId());
		reglas.exigirSinDuplicado(u.escuelaId(), u.familiaId(), null, s.nombre(), s.apellido(), dni);
		// La clave es única por cuenta (V22) y las altas de la familia están serializadas: una violación aquí sería un defecto.
		Tutor nuevo = tutores.saveAndFlush(Tutor.declararPorFamilia(u.escuelaId(), u.familiaId(), u.id(), s.nombre(),
				s.apellido(), dni, s.telefono(), s.email(), s.parentesco(), idempotencia, huella));
		registrar(u, AccionAuditoria.TUTOR_DECLARADO, nuevo, Map.of("familiaId", u.familiaId().toString()), datos);
		return vista(u, nuevo, false);
	}

	@Transactional
	public AdultoDeFamilia actualizar(UsuarioAutenticado u, UUID id, AdultoActualizacion s, DatosSolicitud datos) {
		exigirHabilitado();
		exigirFamilia(u);
		bloquearFamilia(u);
		reglas.serializarAltasDeLaFamilia(u.escuelaId(), u.familiaId());
		if (!reglas.bloquearTutorDeLaFamilia(u.escuelaId(), u.familiaId(), id)) {
			throw TutorAdminService.tutorNoEncontrado();
		}
		Tutor tutor = tutores.findByIdAndEscuelaId(id, u.escuelaId()).orElseThrow(TutorAdminService::tutorNoEncontrado);
		em.refresh(tutor); // releído bajo el bloqueo de la fila
		if (tutor.getUsuarioId() != null && !tutor.getUsuarioId().equals(u.id())) {
			throw conCuentaPropia();
		}
		if (tutor.getVersion() != s.versionEsperada()) {
			throw PerfilPropioService.edicionDesactualizada();
		}
		List<String> cambios = reglas.editarPorFamilia(tutor, s.nombre(), s.apellido(), s.dni(), s.telefono(), s.email(),
				s.parentesco());
		if (!cambios.isEmpty()) {
			tutores.saveAndFlush(tutor);
			registrar(u, AccionAuditoria.TUTOR_ACTUALIZADO_POR_FAMILIA, tutor,
					Map.of("familiaId", u.familiaId().toString(), "camposModificados", cambios), datos);
		}
		return vista(u, tutor, reglas.tieneDniAprobado(tutor));
	}

	private AdultoDeFamilia vista(UsuarioAutenticado u, Tutor t, boolean dniAprobado) {
		boolean esMiPerfil = u.id().equals(t.getUsuarioId());
		boolean cuentaPropia = t.getUsuarioId() != null && !esMiPerfil;
		boolean editable = t.isActivo() && !cuentaPropia;
		return new AdultoDeFamilia(t.getId(), t.getNombre(), t.getApellido(), EnmascaradoDni.de(t.getDni()), t.getTelefono(),
				t.getEmail(), t.getParentesco(), t.getVerificacion(), t.getMotivoObservacion(), "ADMIN".equals(t.getOrigen()),
				esMiPerfil, cuentaPropia, editable, editable && !t.identidadVerificada() && !dniAprobado,
				Long.toString(t.getVersion()));
	}

	/**
	 * SHA-256 de los datos normalizados del alta (no incluye la revisión ni la clave). Misma clave y misma huella: el alta
	 * original; misma clave y otra huella: 409.
	 */
	static String huella(UsuarioAutenticado u, String nombre, String apellido, String dni, String telefono, String email,
			String parentesco) {
		String canonico = String.join("\n", "adulto-alta/v1", u.escuelaId().toString(), u.familiaId().toString(),
				Objects.toString(nombre, "-"), Objects.toString(apellido, "-"), Objects.toString(dni, "-"),
				Objects.toString(telefono, "-"), Objects.toString(email, "-"), Objects.toString(parentesco, "-"));
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(canonico.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private void exigirHabilitado() {
		if (!propiedades.tutoresHabilitado()) {
			throw deshabilitado();
		}
	}

	private static void exigirFamilia(UsuarioAutenticado u) {
		if (u.rol() != Rol.FAMILIA || u.familiaId() == null) {
			throw new ExcepcionNegocio(HttpStatus.FORBIDDEN, "ACCESO_DENEGADO", "No tenés permisos para esta acción.");
		}
	}

	private void bloquearFamilia(UsuarioAutenticado u) {
		if (!reglas.bloquearFamiliaActiva(u.escuelaId(), u.familiaId())) {
			throw FamiliaAdminService.familiaNoEncontrada();
		}
	}

	private void registrar(UsuarioAutenticado u, AccionAuditoria accion, Tutor tutor, Map<String, Object> detalle,
			DatosSolicitud datos) {
		auditoria.registrar(new EventoAuditoria(u.escuelaId(), u.id(), accion, RECURSO, tutor.getId(), detalle, datos));
	}
}
