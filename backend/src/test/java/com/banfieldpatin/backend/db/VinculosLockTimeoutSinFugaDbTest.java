package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;

/**
 * {@code SET LOCAL lock_timeout} no se filtra: con un pool de UNA sola conexion fisica, la transaccion siguiente (de
 * vinculos o no) vuelve a la MISMA sesion de PostgreSQL y {@code SHOW lock_timeout} devuelve el valor por defecto ({@code 0}),
 * tras una transaccion confirmada, tras una revertida por un error de negocio y tras una vinculacion. Plazo por defecto
 * (3 s) a proposito: no se fija banfield.vinculos.lock-timeout.
 */
@PruebaDb
@Import(ConfigFamiliasAdminDb.class)
@TestPropertySource(properties = { "spring.datasource.hikari.maximum-pool-size=1",
		"spring.datasource.hikari.minimum-idle=1" })
class VinculosLockTimeoutSinFugaDbTest extends BaseVinculosDb {

	/**
	 * Flyway necesita dos conexiones a la vez (la del bloqueo del historial y la de la migracion): con un pool de UNA no
	 * arranca. Se le da una conexion propia (url/usuario/clave), asi el pool de la aplicacion queda en una sola conexion.
	 */
	@DynamicPropertySource
	static void flywayConConexionPropia(DynamicPropertyRegistry registro) {
		registro.add("spring.flyway.url", PostgresDescartable::url);
		registro.add("spring.flyway.user", PostgresDescartable::usuario);
		registro.add("spring.flyway.password", PostgresDescartable::clave);
	}

	private String lockTimeout() {
		return jdbc.sql("SHOW lock_timeout").query(String.class).single();
	}

	private int pid() {
		return jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
	}

	@Test
	void dentroDeLaTransaccionDeVinculosElPlazoPorDefectoEsDeTresSegundosYAlTerminarVuelveACero() {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID d = deportista("Uno");
		int pidAntes = pid();
		assertThat(lockTimeout()).isEqualTo("0");

		String dentro = transaccion.execute(estado -> {
			servicio.vincular(admin, f1, List.of(d), DATOS); // se une a la transaccion externa (REQUIRED)
			return lockTimeout();
		});

		assertThat(dentro).isEqualTo("3s");
		assertThat(pid()).as("misma sesion de PostgreSQL (pool de una conexion)").isEqualTo(pidAntes);
		assertThat(lockTimeout()).as("tras el COMMIT").isEqualTo("0");
	}

	@Test
	void tras_vincular_revocar_y_cambiar_principal_confirmados_la_conexion_del_pool_sigue_en_cero() {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID f2 = datos.familia(escuelaA, "Dos", true);
		UUID d = deportista("Uno");
		int pidAntes = pid();

		servicio.vincular(admin, f1, List.of(d), DATOS);
		assertThat(lockTimeout()).as("tras vincular").isEqualTo("0");
		servicio.vincular(admin, f2, List.of(d), DATOS);
		servicio.cambiarPrincipal(admin, f2, d, DATOS);
		assertThat(lockTimeout()).as("tras cambiar principal").isEqualTo("0");
		servicio.revocar(admin, f1, d, DATOS);
		assertThat(lockTimeout()).as("tras revocar").isEqualTo("0");
		assertThat(pid()).isEqualTo(pidAntes);
	}

	@Test
	void tras_una_transaccion_revertida_por_un_error_de_negocio_la_conexion_del_pool_sigue_en_cero() {
		UUID inexistente = UUID.randomUUID();
		int pidAntes = pid();

		Throwable e = catchThrowable(() -> servicio.vincular(admin, inexistente, List.of(UUID.randomUUID()), DATOS));

		assertThat(e).isInstanceOf(ExcepcionNegocio.class);
		assertThat(lockTimeout()).isEqualTo("0");
		assertThat(pid()).isEqualTo(pidAntes);
	}

	@Test
	void una_transaccion_revertida_a_mano_despues_de_aplicar_el_plazo_tampoco_lo_deja_en_la_conexion() {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID d = deportista("Uno");

		Throwable e = catchThrowable(() -> transaccion.execute(estado -> {
			servicio.vincular(admin, f1, List.of(d), DATOS);
			throw new IllegalStateException("revierte la transaccion externa");
		}));

		assertThat(e).isInstanceOf(IllegalStateException.class);
		assertThat(fila(f1, d)).as("revertido").isEmpty();
		assertThat(lockTimeout()).isEqualTo("0");
	}

	@Test
	void una_transaccion_que_no_es_de_vinculos_no_hereda_el_plazo_de_la_anterior_en_la_misma_conexion() {
		UUID f1 = datos.familia(escuelaA, "Uno", true);
		UUID d = deportista("Uno");
		int pidAntes = pid();
		servicio.vincular(admin, f1, List.of(d), DATOS);

		String generica = transaccion.execute(estado -> {
			servicio.listarDeFamilia(admin, f1);
			return lockTimeout();
		});

		assertThat(generica).isEqualTo("0");
		assertThat(pid()).isEqualTo(pidAntes);
	}
}
