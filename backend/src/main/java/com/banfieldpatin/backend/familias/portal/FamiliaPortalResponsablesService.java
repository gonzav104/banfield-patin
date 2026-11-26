package com.banfieldpatin.backend.familias.portal;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.banfieldpatin.backend.deportistas.DeportistaAdminService;
import com.banfieldpatin.backend.familias.portal.dto.ResponsablesDeDeportista;
import com.banfieldpatin.backend.familias.vinculos.FamiliaDeportistaRepository;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;

/**
 * Responsables vigentes de un deportista propio para la FAMILIA, SOLO LECTURA. La identidad (escuela y familia) sale siempre
 * del {@link UsuarioAutenticado} derivado del JWT; la autorización es el WHERE de cada consulta (vínculo ACTIVO, familia
 * activa, misma familia y escuela). Un deportista de otra familia o escuela, con vínculo no activo o inexistente produce el
 * MISMO 404 DEPORTISTA_NO_ENCONTRADO que el resto del portal, de modo que ninguna familia infiere la existencia, la cantidad
 * ni la identidad de responsables ajenos: solo se proyectan los tutores de la propia familia con vigencia abierta. Sin
 * historial. Una cuenta sin familia asociada no consulta la base. Las lecturas no se auditan.
 *
 * <p>Dos sentencias en una única instantánea {@code REPEATABLE_READ} de solo lectura: la primera decide el acceso y la segunda
 * lista; una respuesta 200 nunca mezcla estados de instantes distintos. Un cambio confirmado después de la primera sentencia
 * (por ejemplo, la revocación del vínculo) recién se nota en la solicitud siguiente.
 */
@Service
public class FamiliaPortalResponsablesService {

	private final FamiliaDeportistaRepository vinculos;

	public FamiliaPortalResponsablesService(FamiliaDeportistaRepository vinculos) {
		this.vinculos = vinculos;
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public ResponsablesDeDeportista responsables(UsuarioAutenticado usuario, UUID deportistaId) {
		if (usuario.familiaId() == null
				|| !vinculos.deportistaEsDelPortal(usuario.familiaId(), usuario.escuelaId(), deportistaId)) {
			throw DeportistaAdminService.deportistaNoEncontrado();
		}
		return new ResponsablesDeDeportista(
				vinculos.responsablesDelPortal(usuario.familiaId(), usuario.escuelaId(), deportistaId));
	}
}
