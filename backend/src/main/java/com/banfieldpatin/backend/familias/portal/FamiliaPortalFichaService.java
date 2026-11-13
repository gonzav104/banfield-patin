package com.banfieldpatin.backend.familias.portal;

import java.time.Clock;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.banfieldpatin.backend.deportistas.DeportistaAdminService;
import com.banfieldpatin.backend.familias.portal.dto.DatosBaseDeFicha;
import com.banfieldpatin.backend.familias.portal.dto.FichaDeportistaDeFamilia;
import com.banfieldpatin.backend.familias.portal.dto.TemporadaVigenteDeFicha;
import com.banfieldpatin.backend.familias.vinculos.FamiliaDeportistaRepository;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;

/**
 * Ficha minima de un deportista propio para la FAMILIA, SOLO LECTURA. Sigue las reglas del portal: la identidad (escuela y
 * familia) sale SIEMPRE del {@link UsuarioAutenticado} derivado del JWT, ya revalidado contra la base en cada solicitud, y la
 * autorizacion es el WHERE de cada consulta (vinculo ACTIVO, familia activa, misma familia y escuela, ver
 * {@code FamiliaDeportistaRepository.ALCANCE_FAMILIA}): nunca se carga nada para comparar despues. Un deportista de otra
 * familia o escuela, con vinculo no activo o inexistente produce el MISMO 404 DEPORTISTA_NO_ENCONTRADO que el resto del
 * portal. Una cuenta sin familia asociada no recibe datos y ni siquiera consulta la base. No comparte codigo de permisos con
 * el de ADMIN. Las lecturas no se auditan.
 *
 * <p>
 * <b>Dos consultas, una instantanea.</b> La ficha usa dos sentencias (datos generales y temporada vigente), AMBAS con el
 * alcance completo de la familia: ninguna confia en lo que decidio la otra. El metodo corre en una transaccion de solo
 * lectura {@code REPEATABLE_READ}: en PostgreSQL la instantanea se fija en la PRIMERA sentencia y la segunda lee el mismo
 * estado confirmado, asi que una respuesta 200 nunca mezcla un deportista leido en un instante con una temporada leida en
 * otro. Consecuencia, dicha sin rodeos: un cambio confirmado DESPUES de la primera sentencia (por ejemplo, la revocacion del
 * vinculo) NO se ve en esta solicitud, y la respuesta es la de la instantanea; recien la solicitud siguiente lo nota. Un
 * cambio confirmado ANTES de la primera sentencia si se ve (404 si se revoco el vinculo).
 */
@Service
public class FamiliaPortalFichaService {

	private final FamiliaDeportistaRepository vinculos;
	private final Clock reloj;

	public FamiliaPortalFichaService(FamiliaDeportistaRepository vinculos, Clock reloj) {
		this.vinculos = vinculos;
		this.reloj = reloj;
	}

	/**
	 * La ficha de un deportista de la propia familia: la primera sentencia decide el acceso (vacio = el 404 uniforme) y solo
	 * entonces se busca la temporada vigente; sin datos de la vigente, {@code temporadaVigente} es {@code null}.
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public FichaDeportistaDeFamilia ficha(UsuarioAutenticado usuario, UUID deportistaId) {
		if (usuario.familiaId() == null) {
			throw DeportistaAdminService.deportistaNoEncontrado();
		}
		DatosBaseDeFicha base = vinculos.fichaBaseDelPortal(usuario.familiaId(), usuario.escuelaId(), deportistaId)
				.orElseThrow(DeportistaAdminService::deportistaNoEncontrado);
		TemporadaVigenteDeFicha vigente = vinculos
				.temporadaVigenteDelPortal(usuario.familiaId(), usuario.escuelaId(), deportistaId)
				.map(TemporadaVigenteDeFicha::de).orElse(null);
		return new FichaDeportistaDeFamilia(base.nombre(), base.apellido(),
				EdadDeportista.calcular(base.fechaNacimiento(), reloj), base.activo(), vigente);
	}
}
