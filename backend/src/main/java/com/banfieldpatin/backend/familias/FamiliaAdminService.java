package com.banfieldpatin.backend.familias;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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
import com.banfieldpatin.backend.compartido.web.FiltroEstado;
import com.banfieldpatin.backend.compartido.web.Pagina;
import com.banfieldpatin.backend.familias.dto.FamiliaAdminResumen;
import com.banfieldpatin.backend.familias.dto.FamiliaDetalle;
import com.banfieldpatin.backend.familias.dto.FamiliaSolicitud;
import com.banfieldpatin.backend.familias.tutores.ConteoTutores;
import com.banfieldpatin.backend.familias.tutores.TutorRepository;
import com.banfieldpatin.backend.familias.tutores.dto.TutorRespuesta;
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
	private final TutorRepository tutores;
	private final AuditoriaService auditoria;

	public FamiliaAdminService(FamiliaRepository familias, TutorRepository tutores, AuditoriaService auditoria) {
		this.familias = familias;
		this.tutores = tutores;
		this.auditoria = auditoria;
	}

	public static ExcepcionNegocio familiaNoEncontrada() {
		return new ExcepcionNegocio(HttpStatus.NOT_FOUND, "FAMILIA_NO_ENCONTRADA", "La familia no existe.");
	}

	/** La familia esta inactiva: se rechazan las altas y ediciones que dependen de ella (tutores, vinculos). */
	public static ExcepcionNegocio familiaInactiva() {
		return new ExcepcionNegocio(HttpStatus.CONFLICT, "FAMILIA_INACTIVA",
				"La familia esta inactiva. Reactivala para realizar esta operacion.");
	}

	/**
	 * {@code cantidadTutores} sale de UNA consulta agrupada para toda la pagina (sin N+1; ninguna si la pagina esta
	 * vacia). {@code cantidadDeportistasActivos} vale 0 hasta que existan los vinculos (se cablea en su slice).
	 */
	@Transactional(readOnly = true)
	public Pagina<FamiliaAdminResumen> listar(UsuarioAutenticado admin, FiltroEstado estado, String busquedaEscapada,
			Pageable pageable) {
		Page<Familia> pagina = familias.buscar(admin.escuelaId(), estado.name(), busquedaEscapada, pageable);
		Map<UUID, Long> tutoresPorFamilia = contarTutores(admin.escuelaId(), pagina.getContent());
		return Pagina.de(pagina, f -> FamiliaAdminResumen.de(f, tutoresPorFamilia.getOrDefault(f.getId(), 0L), 0));
	}

	private Map<UUID, Long> contarTutores(UUID escuelaId, List<Familia> pagina) {
		if (pagina.isEmpty()) {
			return Map.of();
		}
		List<UUID> ids = pagina.stream().map(Familia::getId).toList();
		return tutores.contarPorFamilia(escuelaId, ids).stream()
				.collect(Collectors.toMap(ConteoTutores::familiaId, ConteoTutores::cantidad));
	}

	/** Los tutores se devuelven tambien para una familia inactiva (las lecturas no dependen del estado). */
	@Transactional(readOnly = true)
	public FamiliaDetalle obtener(UsuarioAutenticado admin, UUID id) {
		return detalle(cargar(admin, id), admin.escuelaId());
	}

	@Transactional
	public FamiliaDetalle crear(UsuarioAutenticado admin, FamiliaSolicitud solicitud, DatosSolicitud datos) {
		Familia familia = familias.saveAndFlush(Familia.crear(admin.escuelaId(), solicitud.nombreReferencia()));
		registrar(admin, AccionAuditoria.FAMILIA_CREADA, familia, Map.of("origen", "ADMIN"), datos);
		return FamiliaDetalle.de(familia, List.of());
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
		return detalle(familia, admin.escuelaId());
	}

	@Transactional
	public FamiliaDetalle activar(UsuarioAutenticado admin, UUID id, DatosSolicitud datos) {
		Familia familia = cargar(admin, id);
		if (familia.activar()) {
			familias.saveAndFlush(familia);
			registrar(admin, AccionAuditoria.FAMILIA_ACTIVADA, familia, Map.of(), datos);
		}
		return detalle(familia, admin.escuelaId());
	}

	@Transactional
	public FamiliaDetalle desactivar(UsuarioAutenticado admin, UUID id, DatosSolicitud datos) {
		Familia familia = cargar(admin, id);
		if (familia.desactivar()) {
			familias.saveAndFlush(familia);
			registrar(admin, AccionAuditoria.FAMILIA_DESACTIVADA, familia, Map.of(), datos);
		}
		return detalle(familia, admin.escuelaId());
	}

	private Familia cargar(UsuarioAutenticado admin, UUID id) {
		return familias.findByIdAndEscuelaId(id, admin.escuelaId()).orElseThrow(FamiliaAdminService::familiaNoEncontrada);
	}

	private FamiliaDetalle detalle(Familia familia, UUID escuelaId) {
		List<TutorRespuesta> lista = tutores.deFamilia(escuelaId, familia.getId()).stream().map(TutorRespuesta::de)
				.toList();
		return FamiliaDetalle.de(familia, lista);
	}

	private void registrar(UsuarioAutenticado admin, AccionAuditoria accion, Familia familia,
			Map<String, Object> detalle, DatosSolicitud datos) {
		auditoria.registrar(new EventoAuditoria(admin.escuelaId(), admin.id(), accion, RECURSO, familia.getId(),
				detalle, datos));
	}
}
