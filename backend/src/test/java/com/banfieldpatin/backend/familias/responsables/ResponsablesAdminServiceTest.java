package com.banfieldpatin.backend.familias.responsables;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.deportistas.DeportistaRepository;
import com.banfieldpatin.backend.familias.FamiliaRepository;
import com.banfieldpatin.backend.familias.tutores.TutorRepository;
import com.banfieldpatin.backend.familias.vinculos.FamiliaDeportistaRepository;
import com.banfieldpatin.backend.familias.vinculos.LockTimeoutVinculos;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;

import jakarta.persistence.EntityManager;

class ResponsablesAdminServiceTest {

	@Test
	void conLaBanderaApagadaElAltaRespondeTresCincoCeroTresAntesDeLeerOEscribirNada() {
		var deportistas = mock(DeportistaRepository.class);
		var vigencias = mock(TutorDeportistaRepository.class);
		var escritura = mock(EscrituraVigenciasTutor.class);
		var lock = mock(LockTimeoutVinculos.class);
		var servicio = new ResponsablesAdminService(deportistas, mock(FamiliaRepository.class), mock(TutorRepository.class),
				mock(FamiliaDeportistaRepository.class), vigencias, escritura, lock, new ResponsablesPropiedades(false),
				mock(EntityManager.class));
		var admin = new UsuarioAutenticado(UUID.randomUUID(), UUID.randomUUID(), null, Rol.ADMIN);
		var pedido = new ResponsableAlta(UUID.randomUUID(), null, 0L, 0L, 0L, 0L);

		assertThatThrownBy(() -> servicio.crear(admin, UUID.randomUUID(), UUID.randomUUID(), pedido))
				.isInstanceOfSatisfying(ExcepcionNegocio.class, e -> {
					assertThat(e.getEstado()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
					assertThat(e.getCodigo()).isEqualTo("ALTA_RESPONSABLES_DESHABILITADA");
				});
		verifyNoInteractions(deportistas, vigencias, escritura, lock);
	}

	@Test
	void laHuellaEsEstableDistingueTipoAusenteDeSinDeterminarYNoDependeDeLasRevisiones() {
		var e = UUID.randomUUID();
		var d = UUID.randomUUID();
		var t = UUID.randomUUID();
		assertThat(ResponsablesAdminService.huella(e, d, t, null)).matches("[0-9a-f]{64}")
				.isEqualTo(ResponsablesAdminService.huella(e, d, t, null))
				.isNotEqualTo(ResponsablesAdminService.huella(e, d, t, TipoVinculoTutor.SIN_DETERMINAR))
				.isNotEqualTo(ResponsablesAdminService.huella(UUID.randomUUID(), d, t, null))
				.isNotEqualTo(ResponsablesAdminService.huella(e, d, UUID.randomUUID(), null));
	}
}
