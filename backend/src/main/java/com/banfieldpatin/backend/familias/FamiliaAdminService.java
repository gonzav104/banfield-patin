package com.banfieldpatin.backend.familias;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.banfieldpatin.backend.compartido.auditoria.AccionAuditoria;
import com.banfieldpatin.backend.compartido.auditoria.AuditoriaService;
import com.banfieldpatin.backend.compartido.auditoria.EventoAuditoria;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.compartido.web.FiltroEstado;
import com.banfieldpatin.backend.compartido.web.Pagina;
import com.banfieldpatin.backend.familias.dto.FamiliaAdminResumen;
import com.banfieldpatin.backend.familias.dto.FamiliaDetalle;
import com.banfieldpatin.backend.familias.dto.FamiliaSolicitud;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;

/**
 * Gestion administrativa de familias. La escuela y el actor salen siempre del JWT ({@link UsuarioAutenticado}); un id
 * de otra escuela es indistinguible de uno inexistente (mismo 404). Activar/desactivar NO tiene cascada: no toca
 * tutores, vinculos, deportistas, usuarios ni invitaciones. La auditoria se escribe en la transaccion del cambio y
 * solo cuando hubo un cambio real (las llamadas idempotentes no escriben nada).
 */
@Service
public class FamiliaAdminService {

	private static final String RECURSO = "FAMILIA";

	private final FamiliaRepository familias;
	private final AuditoriaService auditoria;

	public FamiliaAdminService(FamiliaRepository familias, AuditoriaService auditoria) {
		this.familias = familias;
		this.auditoria = auditoria;
	}

	public static ExcepcionNegocio familiaNoEncontrada() {
		return new ExcepcionNegocio(HttpStatus.NOT_FOUND, "FAMILIA_NO_ENCONTRADA", "La familia no existe.");
	}

	/**
	 * {@code cantidadTutores} y {@code cantidadDeportistasActivos} valen 0 hasta que existan tutores y vinculos (se
	 * cablean con consultas agrupadas en sus slices).
	 */
	@Transactional(readOnly = true)
	public Pagina<FamiliaAdminResumen> listar(UsuarioAutenticado admin, FiltroEstado estado, String busquedaEscapada,
			Pageable pageable) {
		return Pagina.de(familias.buscar(admin.escuelaId(), estado.name(), busquedaEscapada, pageable),
				f -> FamiliaAdminResumen.de(f, 0, 0));
	}

	@Transactional(readOnly = true)
	public FamiliaDetalle obtener(UsuarioAutenticado admin, UUID id) {
		return detalle(cargar(admin, id));
	}

	@Transactional
	public FamiliaDetalle crear(UsuarioAutenticado admin, FamiliaSolicitud solicitud, DatosSolicitud datos) {
		Familia familia = familias.saveAndFlush(Familia.crear(admin.escuelaId(), solicitud.nombreReferencia()));
		registrar(admin, AccionAuditoria.FAMILIA_CREADA, familia, Map.of("origen", "ADMIN"), datos);
		return detalle(familia);
	}

	/** Reemplazo completo; auditoria solo si el nombre cambio. Permitido sobre una familia inactiva. */
	@Transactional
	public FamiliaDetalle actualizar(UsuarioAutenticado admin, UUID id, FamiliaSolicitud solicitud,
			DatosSolicitud datos) {
		Familia familia = cargar(admin, id);
		if (familia.renombrar(solicitud.nombreReferencia())) {
			familias.saveAndFlush(familia);
			registrar(admin, AccionAuditoria.FAMILIA_ACTUALIZADA, familia,
					Map.of("camposModificados", List.of("nombreReferencia")), datos);
		}
		return detalle(familia);
	}

	@Transactional
	public FamiliaDetalle activar(UsuarioAutenticado admin, UUID id, DatosSolicitud datos) {
		Familia familia = cargar(admin, id);
		if (familia.activar()) {
			familias.saveAndFlush(familia);
			registrar(admin, AccionAuditoria.FAMILIA_ACTIVADA, familia, Map.of(), datos);
		}
		return detalle(familia);
	}

	@Transactional
	public FamiliaDetalle desactivar(UsuarioAutenticado admin, UUID id, DatosSolicitud datos) {
		Familia familia = cargar(admin, id);
		if (familia.desactivar()) {
			familias.saveAndFlush(familia);
			registrar(admin, AccionAuditoria.FAMILIA_DESACTIVADA, familia, Map.of(), datos);
		}
		return detalle(familia);
	}

	private Familia cargar(UsuarioAutenticado admin, UUID id) {
		return familias.findByIdAndEscuelaId(id, admin.escuelaId()).orElseThrow(FamiliaAdminService::familiaNoEncontrada);
	}

	/** Los tutores se agregan cuando existe la entidad Tutor; hasta entonces la lista es vacia. */
	private static FamiliaDetalle detalle(Familia familia) {
		return FamiliaDetalle.de(familia, List.of());
	}

	private void registrar(UsuarioAutenticado admin, AccionAuditoria accion, Familia familia,
			Map<String, Object> detalle, DatosSolicitud datos) {
		auditoria.registrar(new EventoAuditoria(admin.escuelaId(), admin.id(), accion, RECURSO, familia.getId(),
				detalle, datos));
	}
}
