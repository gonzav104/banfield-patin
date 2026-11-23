package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.familias.responsables.CausaCierreVigencia;
import com.banfieldpatin.backend.familias.responsables.VinculoTutorRespuesta;
import com.banfieldpatin.backend.familias.responsables.VinculosTutorConsultaAdmin;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;

/** Instalación limpia mediante Flyway y validación Hibernate, con proyección y paginado reales. */
@PruebaDb
@Import(VinculosTutorConsultaAdmin.class)
class VinculosTutorConsultaDbTest extends BaseDbTest {

	@Autowired
	JdbcClient jdbc;
	@Autowired
	VinculosTutorConsultaAdmin consultas;

	@Test
	@Transactional
	void adminVeVigentesEHistorialPaginadoSoloEnSuEscuela() {
		var datos = new DatosDb(jdbc);
		var escuela = datos.escuela("responsables-a");
		var otraEscuela = datos.escuela("responsables-b");
		var familia = datos.familia(escuela, "A", true);
		var tutor = datos.tutor(escuela, familia, "Nombre", "Apellido");
		var deportista = datos.deportista(escuela, "V14-LECTURA", "Deportista", "A");
		var admin = new UsuarioAutenticado(datos.admin(escuela, "admin@a.example.invalid", true), escuela, null, Rol.ADMIN);
		var otroAdmin = new UsuarioAutenticado(datos.admin(otraEscuela, "admin@b.example.invalid", true), otraEscuela,
				null, Rol.ADMIN);
		datos.vinculo(escuela, familia, deportista, "ACTIVO", false, admin.id());

		assertThat(consultas.listar(admin, deportista, false, 0, 20).contenido()).isEmpty();
		var original = insertar(admin, familia, tutor, deportista);
		jdbc.sql("""
				UPDATE gestion_patin.tutor_deportista SET cerrado_en=clock_timestamp(), cerrado_por=creado_por,
				    causa_cierre='REVOCACION_ADMIN', version=1 WHERE id=:id
				""").param("id", original).update();
		var reciente = insertar(admin, familia, tutor, deportista);

		var vigentes = consultas.listar(admin, deportista, false, 0, 20);
		assertThat(vigentes.totalElementos()).isEqualTo(1);
		assertThat(vigentes.contenido()).extracting(VinculoTutorRespuesta::id).containsExactly(reciente);
		var historial = consultas.listar(admin, deportista, true, 0, 1);
		assertThat(historial.totalElementos()).isEqualTo(2);
		assertThat(historial.contenido()).extracting(VinculoTutorRespuesta::id).containsExactly(reciente);
		assertThat(consultas.listar(admin, deportista, true, 1, 1).contenido()).singleElement().satisfies(v -> {
			assertThat(v.id()).isEqualTo(original);
			assertThat(v.tutorId()).isEqualTo(tutor);
			assertThat(v.tipo()).isNull();
			assertThat(v.causaCierre()).isEqualTo(CausaCierreVigencia.REVOCACION_ADMIN);
			assertThat(v.version()).isEqualTo("1");
		});

		for (var id : new UUID[] { deportista, UUID.randomUUID() }) {
			assertThatThrownBy(() -> consultas.listar(otroAdmin, id, true, 0, 20))
					.isInstanceOfSatisfying(ExcepcionNegocio.class,
							e -> assertThat(e.getCodigo()).isEqualTo("DEPORTISTA_NO_ENCONTRADO"));
		}
	}

	private UUID insertar(UsuarioAutenticado admin, UUID familia, UUID tutor, UUID deportista) {
		return jdbc.sql("""
				INSERT INTO gestion_patin.tutor_deportista
				    (escuela_id, familia_id, deportista_id, tutor_id, creado_por, solicitud_id, huella_solicitud)
				VALUES (:e, :f, :d, :t, :a, gen_random_uuid(), repeat('a', 64))
				RETURNING id
				""").param("e", admin.escuelaId()).param("f", familia).param("d", deportista)
				.param("t", tutor).param("a", admin.id()).query(UUID.class).single();
	}
}
