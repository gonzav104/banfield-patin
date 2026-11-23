package com.banfieldpatin.backend.familias.responsables;

import java.util.UUID;
import java.util.function.Function;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.banfieldpatin.backend.compartido.web.Pagina;
import com.banfieldpatin.backend.deportistas.DeportistaAdminService;
import com.banfieldpatin.backend.deportistas.DeportistaRepository;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;

@Service
public class VinculosTutorConsultaAdmin {

	private final TutorDeportistaRepository vinculos;
	private final DeportistaRepository deportistas;

	public VinculosTutorConsultaAdmin(TutorDeportistaRepository vinculos, DeportistaRepository deportistas) {
		this.vinculos = vinculos;
		this.deportistas = deportistas;
	}

	/** El alcance, la página y su conteo observan la misma instantánea. */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public Pagina<VinculoTutorRespuesta> listar(UsuarioAutenticado admin, UUID deportistaId, boolean historial,
			int page, int size) {
		var escuelaId = admin.escuelaId();
		if (!deportistas.existsByIdAndEscuelaId(deportistaId, escuelaId)) {
			throw DeportistaAdminService.deportistaNoEncontrado();
		}
		return Pagina.de(vinculos.admin(escuelaId, deportistaId, historial, Pagina.pedir(page, size)), Function.identity());
	}
}
