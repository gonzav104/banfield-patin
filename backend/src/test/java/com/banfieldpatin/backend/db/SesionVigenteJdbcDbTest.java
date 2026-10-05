package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.banfieldpatin.backend.usuarios.SesionVigenteJdbc;

/**
 * Tabla de verdad de la sentencia unica de {@link SesionVigenteJdbc} (REQ-XC-09, design 9.4) contra PostgreSQL 17 real:
 * usuario, escuela y (FAMILIA) familia activos, y coherencia del {@code rol} y el {@code familia_id} del token con la
 * fila. Tambien prueba que un {@code familiaId} nulo (ADMIN) se enlaza como parametro tipado sin romper la consulta.
 */
@PruebaDb
@Import(SesionVigenteJdbc.class)
class SesionVigenteJdbcDbTest extends BaseDbTest {

	@Autowired
	JdbcClient jdbc;
	@Autowired
	SesionVigenteJdbc verificador;

	private DatosDb datos;
	private UUID escuelaId;
	private UUID otraEscuelaId;
	private UUID familiaId;
	private UUID otraFamiliaId;
	private UUID adminId;
	private UUID usuarioFamiliaId;

	@BeforeEach
	void preparar() {
		datos = new DatosDb(jdbc);
		escuelaId = datos.escuela("sv-a-" + UUID.randomUUID());
		otraEscuelaId = datos.escuela("sv-b-" + UUID.randomUUID());
		familiaId = datos.familia(escuelaId, "Familia uno", true);
		otraFamiliaId = datos.familia(escuelaId, "Familia dos", true);
		adminId = datos.admin(escuelaId, "admin@sv.example", true);
		usuarioFamiliaId = datos.usuarioFamilia(escuelaId, familiaId, "familia@sv.example");
	}

	@AfterEach
	void limpiar() {
		datos.limpiarEscuela(escuelaId);
		datos.limpiarEscuela(otraEscuelaId);
	}

	@Test
	void usuarioFamiliaActivoConFamiliaActivaEsVigente() {
		assertThat(verificador.vigente(usuarioFamiliaId, escuelaId, "FAMILIA", familiaId)).isTrue();
	}

	@Test
	void adminActivoConFamiliaNulaEsVigente() {
		assertThat(verificador.vigente(adminId, escuelaId, "ADMIN", null)).isTrue();
	}

	@Test
	void usuarioInactivoNoEsVigenteYAlReactivarloVuelveASerlo() {
		datos.desactivarUsuario(adminId);
		datos.desactivarUsuario(usuarioFamiliaId);

		assertThat(verificador.vigente(adminId, escuelaId, "ADMIN", null)).isFalse();
		assertThat(verificador.vigente(usuarioFamiliaId, escuelaId, "FAMILIA", familiaId)).isFalse();

		datos.reactivarUsuario(adminId);
		datos.reactivarUsuario(usuarioFamiliaId);

		assertThat(verificador.vigente(adminId, escuelaId, "ADMIN", null)).isTrue();
		assertThat(verificador.vigente(usuarioFamiliaId, escuelaId, "FAMILIA", familiaId)).isTrue();
	}

	@Test
	void usuarioInexistenteNoEsVigente() {
		assertThat(verificador.vigente(UUID.randomUUID(), escuelaId, "ADMIN", null)).isFalse();
		assertThat(verificador.vigente(UUID.randomUUID(), escuelaId, "FAMILIA", familiaId)).isFalse();

		UUID borrado = datos.admin(escuelaId, "borrado@sv.example", true);
		assertThat(verificador.vigente(borrado, escuelaId, "ADMIN", null)).isTrue();
		datos.borrarUsuario(borrado);
		assertThat(verificador.vigente(borrado, escuelaId, "ADMIN", null)).isFalse();
	}

	@Test
	void escuelaInactivaNoEsVigenteYAlReactivarlaVuelveASerlo() {
		datos.desactivarEscuela(escuelaId);

		assertThat(verificador.vigente(adminId, escuelaId, "ADMIN", null)).isFalse();
		assertThat(verificador.vigente(usuarioFamiliaId, escuelaId, "FAMILIA", familiaId)).isFalse();

		datos.reactivarEscuela(escuelaId);

		assertThat(verificador.vigente(adminId, escuelaId, "ADMIN", null)).isTrue();
	}

	@Test
	void unaEscuelaDelTokenQueNoEsLaDelUsuarioNoEsVigente() {
		assertThat(verificador.vigente(adminId, otraEscuelaId, "ADMIN", null)).isFalse();
		assertThat(verificador.vigente(usuarioFamiliaId, otraEscuelaId, "FAMILIA", familiaId)).isFalse();
	}

	@Test
	void unRolDelTokenQueNoEsElDeLaFilaNoEsVigente() {
		// Token de ADMIN para quien hoy es FAMILIA, y token de FAMILIA para quien es ADMIN.
		assertThat(verificador.vigente(usuarioFamiliaId, escuelaId, "ADMIN", null)).isFalse();
		assertThat(verificador.vigente(adminId, escuelaId, "FAMILIA", familiaId)).isFalse();
		assertThat(verificador.vigente(adminId, escuelaId, "ROL_INEXISTENTE", null)).isFalse();
	}

	@Test
	void unaFamiliaDelTokenQueNoEsLaDelUsuarioNoEsVigente() {
		assertThat(verificador.vigente(usuarioFamiliaId, escuelaId, "FAMILIA", otraFamiliaId)).isFalse();
		assertThat(verificador.vigente(usuarioFamiliaId, escuelaId, "FAMILIA", UUID.randomUUID())).isFalse();
		assertThat(verificador.vigente(usuarioFamiliaId, escuelaId, "FAMILIA", null)).isFalse();
	}

	@Test
	void familiaInactivaNoEsVigenteSoloParaSusUsuariosYAlReactivarlaVuelveASerlo() {
		datos.desactivarFamilia(familiaId);

		assertThat(verificador.vigente(usuarioFamiliaId, escuelaId, "FAMILIA", familiaId)).isFalse();
		// Un ADMIN de la misma escuela no depende de ninguna familia.
		assertThat(verificador.vigente(adminId, escuelaId, "ADMIN", null)).isTrue();

		datos.reactivarFamilia(familiaId);

		assertThat(verificador.vigente(usuarioFamiliaId, escuelaId, "FAMILIA", familiaId)).isTrue();
	}

	@Test
	void unUsuarioFamiliaInactivoNoEsVigenteAunqueSuFamiliaLoSea() {
		UUID inactivo = datos.usuarioFamilia(escuelaId, familiaId, "inactivo@sv.example", false);

		assertThat(verificador.vigente(inactivo, escuelaId, "FAMILIA", familiaId)).isFalse();
	}
}
