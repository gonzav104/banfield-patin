package com.banfieldpatin.backend.familias.tutores;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.banfieldpatin.backend.compartido.auditoria.AccionAuditoria;
import com.banfieldpatin.backend.compartido.auditoria.AuditoriaService;
import com.banfieldpatin.backend.compartido.auditoria.EventoAuditoria;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.deportistas.DocumentoIdentidad;
import com.banfieldpatin.backend.familias.Familia;
import com.banfieldpatin.backend.familias.FamiliaAdminService;
import com.banfieldpatin.backend.familias.FamiliaRepository;
import com.banfieldpatin.backend.familias.tutores.dto.TutorRespuesta;
import com.banfieldpatin.backend.familias.tutores.dto.TutorSolicitud;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;

/**
 * Alta, edicion y lectura de tutores para ADMIN. La escuela y el actor salen siempre del JWT; un id de otra escuela es
 * indistinguible de uno inexistente (mismo 404). Alta y edicion exigen una familia ACTIVA: el orden de comprobaciones
 * es 404 (familia o tutor) -> 409 FAMILIA_INACTIVA -> escritura, de modo que una familia inactiva rechaza TODO, incluso
 * una edicion identica, sin escribir ni auditar. Las lecturas no dependen del estado de la familia. El DNI es opcional,
 * se normaliza y NO es unico. La auditoria lleva ids y NOMBRES de campos, nunca valores personales.
 */
@Service
public class TutorAdminService {

	private static final String RECURSO = "TUTOR";

	private final TutorRepository tutores;
	private final FamiliaRepository familias;
	private final AuditoriaService auditoria;

	public TutorAdminService(TutorRepository tutores, FamiliaRepository familias, AuditoriaService auditoria) {
		this.tutores = tutores;
		this.familias = familias;
		this.auditoria = auditoria;
	}

	public static ExcepcionNegocio tutorNoEncontrado() {
		return new ExcepcionNegocio(HttpStatus.NOT_FOUND, "TUTOR_NO_ENCONTRADO", "El tutor no existe.");
	}

	@Transactional
	public TutorRespuesta crear(UsuarioAutenticado admin, UUID familiaId, TutorSolicitud solicitud,
			DatosSolicitud datos) {
		Familia familia = familias.findByIdAndEscuelaId(familiaId, admin.escuelaId())
				.orElseThrow(FamiliaAdminService::familiaNoEncontrada);
		exigirActiva(familia);
		Tutor tutor = tutores.saveAndFlush(Tutor.crear(admin.escuelaId(), familia.getId(), solicitud.nombre(),
				solicitud.apellido(), DocumentoIdentidad.normalizarDni(solicitud.dni()), solicitud.telefono(),
				solicitud.email(), solicitud.parentesco()));
		registrar(admin, AccionAuditoria.TUTOR_CREADO, tutor, Map.of("familiaId", familia.getId().toString()), datos);
		return TutorRespuesta.de(tutor);
	}

	@Transactional(readOnly = true)
	public TutorRespuesta obtener(UsuarioAutenticado admin, UUID id) {
		return TutorRespuesta.de(cargar(admin, id));
	}

	/** Reemplazo completo; auditoria solo si algun campo cambio realmente. */
	@Transactional
	public TutorRespuesta actualizar(UsuarioAutenticado admin, UUID id, TutorSolicitud solicitud,
			DatosSolicitud datos) {
		Tutor tutor = cargar(admin, id);
		Familia familia = familias.findByIdAndEscuelaId(tutor.getFamiliaId(), admin.escuelaId())
				.orElseThrow(FamiliaAdminService::familiaNoEncontrada);
		exigirActiva(familia);
		List<String> cambios = tutor.actualizar(solicitud.nombre(), solicitud.apellido(),
				DocumentoIdentidad.normalizarDni(solicitud.dni()), solicitud.telefono(), solicitud.email(),
				solicitud.parentesco());
		if (!cambios.isEmpty()) {
			tutores.saveAndFlush(tutor);
			registrar(admin, AccionAuditoria.TUTOR_ACTUALIZADO, tutor,
					Map.of("familiaId", tutor.getFamiliaId().toString(), "camposModificados", cambios), datos);
		}
		return TutorRespuesta.de(tutor);
	}

	private Tutor cargar(UsuarioAutenticado admin, UUID id) {
		return tutores.findByIdAndEscuelaId(id, admin.escuelaId()).orElseThrow(TutorAdminService::tutorNoEncontrado);
	}

	private static void exigirActiva(Familia familia) {
		if (!familia.isActiva()) {
			throw FamiliaAdminService.familiaInactiva();
		}
	}

	private void registrar(UsuarioAutenticado admin, AccionAuditoria accion, Tutor tutor, Map<String, Object> detalle,
			DatosSolicitud datos) {
		auditoria.registrar(new EventoAuditoria(admin.escuelaId(), admin.id(), accion, RECURSO, tutor.getId(), detalle,
				datos));
	}
}
