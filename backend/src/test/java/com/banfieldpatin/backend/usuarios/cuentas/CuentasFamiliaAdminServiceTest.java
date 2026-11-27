package com.banfieldpatin.backend.usuarios.cuentas;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.banfieldpatin.backend.compartido.auditoria.AuditoriaService;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;

class CuentasFamiliaAdminServiceTest {

	private final JdbcClient jdbc = mock(JdbcClient.class);
	private final AuditoriaService auditoria = mock(AuditoriaService.class);
	private final UsuarioAutenticado admin = new UsuarioAutenticado(UUID.randomUUID(), UUID.randomUUID(), null, Rol.ADMIN);
	private final DatosSolicitud solicitud = new DatosSolicitud(null, null);

	private CuentasFamiliaAdminService servicio(boolean habilitada) {
		return new CuentasFamiliaAdminService(jdbc, auditoria, new CuentasPropiedades(habilitada), Clock.systemUTC());
	}

	@Test
	void conLaGestionApagadaNoLeeNiEscribeNada() {
		for (boolean desactivar : new boolean[] { true, false }) {
			assertThatThrownBy(() -> {
				if (desactivar) {
					servicio(false).desactivar(admin, UUID.randomUUID(), UUID.randomUUID(), solicitud);
				} else {
					servicio(false).reactivar(admin, UUID.randomUUID(), UUID.randomUUID(), solicitud);
				}
			}).isInstanceOfSatisfying(ExcepcionNegocio.class, e -> {
				org.assertj.core.api.Assertions.assertThat(e.getCodigo()).isEqualTo("GESTION_CUENTAS_DESHABILITADA");
				org.assertj.core.api.Assertions.assertThat(e.getEstado().value()).isEqualTo(503);
			});
		}
		verifyNoInteractions(jdbc, auditoria);
	}

	@Test
	void unaSesionQueNoEsAdminNoLlegaALaBase() {
		UsuarioAutenticado familia = new UsuarioAutenticado(UUID.randomUUID(), admin.escuelaId(), UUID.randomUUID(),
				Rol.FAMILIA);
		assertThatThrownBy(() -> servicio(true).listar(familia, UUID.randomUUID()))
				.isInstanceOfSatisfying(ExcepcionNegocio.class,
						e -> org.assertj.core.api.Assertions.assertThat(e.getEstado().value()).isEqualTo(403));
		// También con la gestión apagada: primero el permiso (403), después la bandera.
		for (boolean habilitada : new boolean[] { true, false }) {
			assertThatThrownBy(() -> servicio(habilitada).desactivar(familia, UUID.randomUUID(), UUID.randomUUID(), solicitud))
					.isInstanceOfSatisfying(ExcepcionNegocio.class,
							e -> org.assertj.core.api.Assertions.assertThat(e.getEstado().value()).isEqualTo(403));
		}
		verifyNoInteractions(jdbc, auditoria);
	}
}
