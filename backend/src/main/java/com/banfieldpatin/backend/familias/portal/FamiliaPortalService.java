package com.banfieldpatin.backend.familias.portal;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.banfieldpatin.backend.compartido.web.Pagina;
import com.banfieldpatin.backend.deportistas.DeportistaAdminService;
import com.banfieldpatin.backend.familias.FamiliaAdminService;
import com.banfieldpatin.backend.familias.FamiliaRepository;
import com.banfieldpatin.backend.familias.portal.dto.DeportistaDeFamilia;
import com.banfieldpatin.backend.familias.portal.dto.DeportistaDeFamiliaDetalle;
import com.banfieldpatin.backend.familias.portal.dto.MiFamiliaRespuesta;
import com.banfieldpatin.backend.familias.portal.dto.TutorDeFamilia;
import com.banfieldpatin.backend.familias.tutores.TutorRepository;
import com.banfieldpatin.backend.familias.vinculos.FamiliaDeportistaRepository;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;

/**
 * Portal de solo lectura de la FAMILIA. La identidad (escuela y familia) sale SIEMPRE del {@link UsuarioAutenticado}
 * derivado del JWT, ya revalidado contra la base en cada solicitud; ningun parametro de la solicitud la influye. Cada
 * lectura es UNA sentencia con el alcance de la familia (ver {@code FamiliaDeportistaRepository.ALCANCE_FAMILIA}): no se
 * carga nada para comparar despues, de modo que un deportista de otra familia, de otra escuela o inexistente es
 * indistinguible (404 uniforme). Una cuenta FAMILIA sin familia asociada no recibe datos y ni siquiera consulta la base.
 * Las lecturas del portal no se auditan.
 */
@Service
public class FamiliaPortalService {

	private final FamiliaRepository familias;
	private final TutorRepository tutores;
	private final FamiliaDeportistaRepository vinculos;

	public FamiliaPortalService(FamiliaRepository familias, TutorRepository tutores,
			FamiliaDeportistaRepository vinculos) {
		this.familias = familias;
		this.tutores = tutores;
		this.vinculos = vinculos;
	}

	/** La familia propia y sus tutores activos (2 sentencias). 404 FAMILIA_NO_ENCONTRADA si el alcance no devuelve nada. */
	@Transactional(readOnly = true)
	public MiFamiliaRespuesta miFamilia(UsuarioAutenticado usuario) {
		UUID familiaId = usuario.familiaId();
		if (familiaId == null) {
			throw FamiliaAdminService.familiaNoEncontrada();
		}
		var familia = familias.buscarDelPortal(familiaId, usuario.escuelaId())
				.orElseThrow(FamiliaAdminService::familiaNoEncontrada);
		List<TutorDeFamilia> deLaFamilia = tutores.activosDeFamilia(usuario.escuelaId(), familiaId).stream()
				.map(TutorDeFamilia::de).toList();
		return MiFamiliaRespuesta.de(familia, deLaFamilia);
	}

	/** Deportistas con vinculo ACTIVO (incluidos los inactivos, con {@code activo=false}); pagina vacia si no hay ninguno. */
	@Transactional(readOnly = true)
	public Pagina<DeportistaDeFamilia> deportistas(UsuarioAutenticado usuario, Pageable pagina) {
		if (usuario.familiaId() == null) {
			return Pagina.de(new PageImpl<DeportistaDeFamilia>(List.of(), pagina, 0), d -> d);
		}
		return Pagina.de(vinculos.deportistasDelPortal(usuario.familiaId(), usuario.escuelaId(), pagina), d -> d);
	}

	/** Un deportista de la propia familia (1 sentencia); cualquier otro id es el mismo 404 DEPORTISTA_NO_ENCONTRADO. */
	@Transactional(readOnly = true)
	public DeportistaDeFamiliaDetalle deportista(UsuarioAutenticado usuario, UUID deportistaId) {
		if (usuario.familiaId() == null) {
			throw DeportistaAdminService.deportistaNoEncontrado();
		}
		return vinculos.deportistaDelPortal(usuario.familiaId(), usuario.escuelaId(), deportistaId)
				.orElseThrow(DeportistaAdminService::deportistaNoEncontrado);
	}
}
