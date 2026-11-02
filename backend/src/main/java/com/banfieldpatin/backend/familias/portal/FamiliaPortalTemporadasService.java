package com.banfieldpatin.backend.familias.portal;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.banfieldpatin.backend.deportistas.DeportistaAdminService;
import com.banfieldpatin.backend.familias.portal.dto.TemporadaDeFamilia;
import com.banfieldpatin.backend.familias.vinculos.FamiliaDeportistaRepository;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.temporadas.DeportistaTemporadaAdminService;

/**
 * Datos deportivos anuales de los deportistas de la FAMILIA, SOLO LECTURA. Sigue las reglas del portal: la identidad (escuela
 * y familia) sale SIEMPRE del {@link UsuarioAutenticado} derivado del JWT, ya revalidado contra la base en cada solicitud, y
 * la autorizacion es el WHERE de la consulta (vinculo ACTIVO, familia activa, misma escuela y temporada visible, ver
 * {@code FamiliaDeportistaRepository}): nunca se carga nada para comparar despues. A diferencia de la administracion, NO
 * diagnostica que falta: cada endpoint tiene UN solo 404 para todas sus causas, de modo que un deportista de otra familia o
 * escuela, con vinculo revocado, inexistente, o una temporada futura, inexistente o ajena son indistinguibles. Este servicio
 * no comparte codigo de permisos con el de ADMIN. Las lecturas no se auditan.
 */
@Service
public class FamiliaPortalTemporadasService {

	private final FamiliaDeportistaRepository vinculos;

	public FamiliaPortalTemporadasService(FamiliaDeportistaRepository vinculos) {
		this.vinculos = vinculos;
	}

	/**
	 * Temporadas visibles del deportista, de la mas nueva a la mas vieja (en una sentencia). Un historial vacio puede
	 * significar "no es suyo" o "no tiene ninguna visible": solo en ese caso una segunda sentencia, tambien con el alcance de
	 * la familia, decide entre {@code 404 DEPORTISTA_NO_ENCONTRADO} y {@code 200 []}.
	 */
	@Transactional(readOnly = true)
	public List<TemporadaDeFamilia> historial(UsuarioAutenticado usuario, UUID deportistaId) {
		if (usuario.familiaId() == null) {
			throw DeportistaAdminService.deportistaNoEncontrado();
		}
		List<TemporadaDeFamilia> filas = vinculos.temporadasDelPortal(usuario.familiaId(), usuario.escuelaId(), deportistaId);
		if (filas.isEmpty()
				&& vinculos.deportistaEnAlcance(usuario.familiaId(), usuario.escuelaId(), deportistaId).isEmpty()) {
			throw DeportistaAdminService.deportistaNoEncontrado();
		}
		return filas;
	}

	/** Una temporada visible de un deportista propio (1 sentencia); cualquier otra causa es el mismo 404, sin diagnosticar. */
	@Transactional(readOnly = true)
	public TemporadaDeFamilia temporada(UsuarioAutenticado usuario, UUID deportistaId, UUID temporadaId) {
		if (usuario.familiaId() == null) {
			throw DeportistaTemporadaAdminService.filaNoEncontrada();
		}
		return vinculos.temporadaDelPortal(usuario.familiaId(), usuario.escuelaId(), deportistaId, temporadaId)
				.orElseThrow(DeportistaTemporadaAdminService::filaNoEncontrada);
	}
}
