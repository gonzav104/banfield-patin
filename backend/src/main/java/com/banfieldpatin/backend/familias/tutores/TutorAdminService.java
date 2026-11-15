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
import com.banfieldpatin.backend.familias.tutores.dto.TutorActualizacion;
import com.banfieldpatin.backend.familias.tutores.dto.TutorRespuesta;
import com.banfieldpatin.backend.familias.tutores.dto.TutorSolicitud;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/**
 * Alta, edicion y lectura de tutores para ADMIN. La escuela y el actor salen siempre del JWT; un id de otra escuela es
 * indistinguible de uno inexistente (mismo 404). Alta y edicion exigen una familia ACTIVA y las revisiones observadas
 * (FE-05/U4): el orden de comprobaciones es 404 (familia o tutor) -> 409 FAMILIA_INACTIVA -> revision de la familia ->
 * revision del tutor (409 EDICION_DESACTUALIZADA, incluso si el valor ya coincide) -> bloqueos -> escritura. Una familia
 * inactiva rechaza TODO, incluso una edicion identica y con revisiones viejas, sin escribir ni auditar. Las lecturas
 * no dependen del estado de la familia. El DNI es opcional, se normaliza y NO es unico. La auditoria lleva ids y
 * NOMBRES de campos, nunca valores personales.
 *
 * <p><b>Revisiones.</b> {@code Familia.version} es la revision de nombre y estado y {@code Tutor.version} la de los datos del
 * tutor; son independientes y ninguna escritura del tutor incrementa la de la familia. Alta y edicion toman sobre la
 * fila de la familia un bloqueo {@code PESSIMISTIC_READ} (FOR SHARE) con el predicado de version de Hibernate: una
 * desactivacion o un renombre concurrentes no pueden confirmarse mientras el tutor se escribe, y si ya se confirmaron la
 * comprobacion falla con 409. Comprobar la version solo al commit dejaria una ventana en la que la familia se desactiva
 * entre la lectura y el INSERT. Varias altas o ediciones en la misma familia no se serializan entre si (los FOR SHARE son
 * compatibles). El tutor se comprueba con {@code OPTIMISTIC}: el UPDATE lleva el predicado de version (un unico ganador)
 * y un "sin cambios" tambien falla si el tutor cambio antes del commit.
 */
@Service
public class TutorAdminService {

	private static final String RECURSO = "TUTOR";

	private final TutorRepository tutores;
	private final FamiliaRepository familias;
	private final AuditoriaService auditoria;
	private final EntityManager em;

	public TutorAdminService(TutorRepository tutores, FamiliaRepository familias, AuditoriaService auditoria,
			EntityManager em) {
		this.tutores = tutores;
		this.familias = familias;
		this.auditoria = auditoria;
		this.em = em;
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
		exigirRevisionDeFamilia(familia, solicitud.versionFamiliaEsperada());
		bloquearFamilia(familia);
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
	public TutorRespuesta actualizar(UsuarioAutenticado admin, UUID id, TutorActualizacion solicitud,
			DatosSolicitud datos) {
		Tutor tutor = cargar(admin, id);
		Familia familia = familias.findByIdAndEscuelaId(tutor.getFamiliaId(), admin.escuelaId())
				.orElseThrow(FamiliaAdminService::familiaNoEncontrada);
		exigirActiva(familia);
		exigirRevisionDeFamilia(familia, solicitud.versionFamiliaEsperada());
		exigirRevisionDelTutor(tutor, solicitud.versionEsperada());
		bloquearFamilia(familia);
		em.lock(tutor, LockModeType.OPTIMISTIC); // el UPDATE lleva el predicado de version; esto cubre tambien un "sin cambios"
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

	private static ExcepcionNegocio revisionObligatoria() {
		return new ExcepcionNegocio(HttpStatus.BAD_REQUEST, "VALIDACION", "La revisión es obligatoria y no negativa.");
	}

	private static void exigirRevisionDeFamilia(Familia familia, Long versionEsperada) {
		if (versionEsperada == null || versionEsperada < 0) {
			throw revisionObligatoria();
		}
		if (familia.getVersion() != versionEsperada) {
			throw FamiliaAdminService.edicionDesactualizada();
		}
	}

	private static void exigirRevisionDelTutor(Tutor tutor, Long versionEsperada) {
		if (versionEsperada == null || versionEsperada < 0) {
			throw revisionObligatoria();
		}
		if (tutor.getVersion() != versionEsperada) {
			throw FamiliaAdminService.edicionDesactualizada();
		}
	}

	/**
	 * FOR SHARE con el predicado de version: si la familia cambio (nombre o estado) despues de leerla, o cambia mientras
	 * esta transaccion escribe, la desactivacion espera o esta comprobacion falla; nunca se escribe un tutor sobre una
	 * familia que ya no es la observada. No incrementa la revision de la familia.
	 */
	private void bloquearFamilia(Familia familia) {
		em.lock(familia, LockModeType.PESSIMISTIC_READ);
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
