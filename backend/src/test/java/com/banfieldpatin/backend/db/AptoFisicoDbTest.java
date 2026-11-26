package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.documentos.apto.AptoFisicoService;
import com.banfieldpatin.backend.documentos.apto.AptoFisicoTemporada;
import com.banfieldpatin.backend.documentos.apto.EstadoApto;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;

/** El apto físico por temporada contra PostgreSQL: estado derivado con reloj fijo, versiones, alcance familiar y aislamiento. */
@PruebaDb
@Import({ ConfigFamiliasAdminDb.class, AptoFisicoService.class, AptoFisicoDbTest.RelojFijo.class })
class AptoFisicoDbTest extends BaseVinculosDb {
	static final AtomicReference<Instant> AHORA = new AtomicReference<>(Instant.parse("2026-06-15T15:00:00Z"));

	@TestConfiguration(proxyBeanMethods = false)
	static class RelojFijo {
		@Bean
		@org.springframework.context.annotation.Primary
		Clock relojFijo() {
			return new Clock() {
				@Override
				public ZoneId getZone() {
					return ZoneId.of("UTC");
				}

				@Override
				public Clock withZone(ZoneId zona) {
					return Clock.fixed(instant(), zona);
				}

				@Override
				public Instant instant() {
					return AHORA.get();
				}
			};
		}
	}

	@Autowired
	AptoFisicoService servicio;
	UUID familia, deportista, temporada2026, temporada2027;
	UsuarioAutenticado familiar;

	@BeforeEach
	void preparar() {
		AHORA.set(Instant.parse("2026-06-15T15:00:00Z"));
		familia = datos.familia(escuelaA, "Familia apto", true);
		familiar = new UsuarioAutenticado(datos.usuarioFamilia(escuelaA, familia, "apto@example.invalid"), escuelaA, familia, Rol.FAMILIA);
		deportista = datos.deportista(escuelaA, "APTO" + UUID.randomUUID().toString().substring(0, 8), "Lucia", "Sintetica");
		datos.vinculo(escuelaA, familia, deportista, "ACTIVO", false, adminId);
		temporada2026 = datos.temporada(escuelaA, 2026, false);
		temporada2027 = datos.temporada(escuelaA, 2027, false);
	}

	@AfterEach
	void limpiar() {
		for (UUID escuela : List.of(escuelaA, escuelaB)) {
			jdbc.sql("DELETE FROM gestion_patin.documento WHERE escuela_id = :e").param("e", escuela).update();
		}
	}

	UUID apto(UUID escuela, UUID titular, UUID temporada, String estado, boolean vigente, String matricula) {
		return jdbc.sql("""
				INSERT INTO gestion_patin.documento(escuela_id, deportista_id, temporada_id, tipo, nombre_original, storage_key,
				  mime_type, tamanio_bytes, estado, vigente, cargado_por, motivo_observacion, fecha_emision, medico_nombre,
				  medico_matricula)
				VALUES (:e, :d, :t, 'APTO_FISICO', 'NOMBRE_PRIVADO.pdf', :key, 'application/pdf', 100, :estado, :vigente, :autor,
				  CASE WHEN :estado = 'OBSERVADO' THEN 'MOTIVO_FICTICIO' END, DATE '2026-03-01', 'Medico Sintetico', :m)
				RETURNING id
				""").param("e", escuela).param("d", titular).param("t", temporada).param("key", "RUTA_PRIVADA/" + UUID.randomUUID())
				.param("estado", estado).param("vigente", vigente)
				.param("autor", escuela.equals(escuelaA) ? adminId : adminDeB.id()).param("m", matricula)
				.query(UUID.class).single();
	}

	void noEncontrado(Runnable lectura, String codigo) {
		assertThatThrownBy(lectura::run).isInstanceOfSatisfying(ExcepcionNegocio.class, e -> {
			assertThat(e.getEstado().value()).isEqualTo(404);
			assertThat(e.getCodigo()).isEqualTo(codigo);
		});
	}

	@Test
	void sinDocumentoFaltaConLaRegla2026Confirmada() {
		var r = servicio.consultar(familiar, deportista, temporada2026);
		assertThat(r.estado()).isEqualTo(EstadoApto.FALTA);
		assertThat(r.documento()).isNull();
		assertThat(r.bloqueante()).isFalse();
		assertThat(r.anio()).isEqualTo(2026);
		assertThat(r.vigencia().hasta()).hasToString("2026-12-31");
		assertThat(r.vigencia().confirmada()).isTrue();
		assertThat(r.avisos()).isEmpty();
	}

	@Test
	void unaTemporadaSinReglaConfirmadaAvisaQueLosRequisitosEstanPendientes() {
		var r = servicio.consultar(familiar, deportista, temporada2027);
		assertThat(r.vigencia().confirmada()).isFalse();
		assertThat(r.avisos()).containsExactly(AptoFisicoTemporada.AVISO_REQUISITOS_SIN_CONFIRMAR);
	}

	@Test
	void cadaEstadoDelDocumentoVigenteSeMapeaAlEstadoDerivado() {
		var id = apto(escuelaA, deportista, temporada2026, "CARGADO", true, "MP 0001");
		assertThat(servicio.consultar(familiar, deportista, temporada2026).estado()).isEqualTo(EstadoApto.EN_REVISION);
		jdbc.sql("UPDATE gestion_patin.documento SET estado='OBSERVADO', motivo_observacion='MOTIVO_FICTICIO' WHERE id=:i").param("i", id).update();
		var observado = servicio.consultar(familiar, deportista, temporada2026);
		assertThat(observado.estado()).isEqualTo(EstadoApto.OBSERVADO);
		assertThat(observado.documento().motivoObservacion()).isEqualTo("MOTIVO_FICTICIO");
		jdbc.sql("UPDATE gestion_patin.documento SET estado='APROBADO', motivo_observacion=NULL WHERE id=:i").param("i", id).update();
		var aprobado = servicio.consultar(familiar, deportista, temporada2026);
		assertThat(aprobado.estado()).isEqualTo(EstadoApto.CUMPLIDO);
		assertThat(aprobado.documento().motivoObservacion()).isNull();
	}

	@Test
	void unAptoAprobadoVenceAlCambiarElAnioEnLaZonaDeLaEscuela() {
		apto(escuelaA, deportista, temporada2026, "APROBADO", true, "MP 0001");
		// 31/12 a las 22:00 en Buenos Aires (01:00 UTC del 1/1): todavía vigente
		AHORA.set(Instant.parse("2027-01-01T01:00:00Z"));
		assertThat(servicio.consultar(familiar, deportista, temporada2026).estado()).isEqualTo(EstadoApto.CUMPLIDO);
		// 1/1 a las 00:00 en Buenos Aires (03:00 UTC): vencido
		AHORA.set(Instant.parse("2027-01-01T03:00:00Z"));
		assertThat(servicio.consultar(familiar, deportista, temporada2026).estado()).isEqualTo(EstadoApto.VENCIDO);
	}

	@Test
	void soloCuentaLaVersionVigenteYLasHistoricasNuncaSeMezclan() {
		apto(escuelaA, deportista, temporada2026, "OBSERVADO", false, "MP HISTORICA");
		apto(escuelaA, deportista, temporada2026, "CARGADO", true, "MP VIGENTE");
		var r = servicio.consultar(familiar, deportista, temporada2026);
		assertThat(r.estado()).isEqualTo(EstadoApto.EN_REVISION);
		assertThat(r.documento().medicoMatricula()).isEqualTo("MP VIGENTE");
		// el apto de otra temporada no se cuela
		assertThat(servicio.consultar(familiar, deportista, temporada2027).estado()).isEqualTo(EstadoApto.FALTA);
	}

	@Test
	void adminRecibeLaRevisionYLaFamiliaNo() {
		apto(escuelaA, deportista, temporada2026, "CARGADO", true, "MP 0001");
		assertThat(servicio.consultar(admin, deportista, temporada2026).documento().revision()).isEqualTo(0L);
		var porFamilia = servicio.consultar(familiar, deportista, temporada2026);
		assertThat(porFamilia.documento().revision()).isNull();
		assertThat(porFamilia.documento().medicoNombre()).isEqualTo("Medico Sintetico");
		assertThat(porFamilia.documento().toString()).doesNotContain("Medico", "MP 0001");
	}

	@Test
	void adminNoVeNiConfirmaDeportistasNiTemporadasDeOtraEscuela() {
		var deB = datos.deportista(escuelaB, "APTOB" + UUID.randomUUID().toString().substring(0, 6), "Otra", "Escuela");
		var temporadaB = datos.temporada(escuelaB, 2026, false);
		apto(escuelaB, deB, temporadaB, "APROBADO", true, "MP B");
		noEncontrado(() -> servicio.consultar(admin, deB, temporadaB), "DEPORTISTA_NO_ENCONTRADO");
		noEncontrado(() -> servicio.consultar(admin, UUID.randomUUID(), temporada2026), "DEPORTISTA_NO_ENCONTRADO");
		// deportista propio con una temporada que pertenece a otra escuela, o que no existe: el mismo 404 de temporada
		noEncontrado(() -> servicio.consultar(admin, deportista, temporadaB), "TEMPORADA_NO_ENCONTRADA");
		noEncontrado(() -> servicio.consultar(admin, deportista, UUID.randomUUID()), "TEMPORADA_NO_ENCONTRADA");
		assertThat(servicio.consultar(adminDeB, deB, temporadaB).estado()).isEqualTo(EstadoApto.CUMPLIDO);
	}

	@Test
	void laFamiliaSoloVeASusDeportistasConVinculoActivoYElResto_esElMismo404() {
		var ajeno = datos.deportista(escuelaA, "AJENO" + UUID.randomUUID().toString().substring(0, 6), "Ajena", "Sintetica");
		var otraFamilia = datos.familia(escuelaA, "Otra familia", true);
		datos.vinculo(escuelaA, otraFamilia, ajeno, "ACTIVO", false, adminId);
		apto(escuelaA, ajeno, temporada2026, "APROBADO", true, "MP AJENA");
		noEncontrado(() -> servicio.consultar(familiar, ajeno, temporada2026), "DEPORTISTA_NO_ENCONTRADO");
		noEncontrado(() -> servicio.consultar(familiar, UUID.randomUUID(), temporada2026), "DEPORTISTA_NO_ENCONTRADO");
		for (String estado : List.of("PENDIENTE", "RECHAZADO", "REVOCADO")) {
			var d = datos.deportista(escuelaA, "V" + estado.substring(0, 3) + UUID.randomUUID().toString().substring(0, 6), "Vinculo", estado);
			datos.vinculo(escuelaA, familia, d, estado, false, adminId);
			apto(escuelaA, d, temporada2026, "APROBADO", true, "MP " + estado);
			noEncontrado(() -> servicio.consultar(familiar, d, temporada2026), "DEPORTISTA_NO_ENCONTRADO");
		}
	}

	@Test
	void dosFamiliasConVinculoActivoVenElMismoAptoYUnaFamiliaConVinculoRevocadoDejaDeVerlo() {
		apto(escuelaA, deportista, temporada2026, "APROBADO", true, "MP COMPARTIDA");
		var otra = datos.familia(escuelaA, "Segunda familia", true);
		datos.vinculo(escuelaA, otra, deportista, "ACTIVO", false, adminId);
		var segunda = new UsuarioAutenticado(datos.usuarioFamilia(escuelaA, otra, "segunda@example.invalid"), escuelaA, otra, Rol.FAMILIA);
		assertThat(servicio.consultar(segunda, deportista, temporada2026).estado()).isEqualTo(EstadoApto.CUMPLIDO);
		jdbc.sql("UPDATE gestion_patin.familia_deportista SET estado='REVOCADO' WHERE familia_id=:f AND deportista_id=:d")
				.param("f", otra).param("d", deportista).update();
		noEncontrado(() -> servicio.consultar(segunda, deportista, temporada2026), "DEPORTISTA_NO_ENCONTRADO");
		assertThat(servicio.consultar(familiar, deportista, temporada2026).estado()).isEqualTo(EstadoApto.CUMPLIDO);
	}

	@Test
	void laLecturaNoEscribeNiAudita() {
		apto(escuelaA, deportista, temporada2026, "APROBADO", true, "MP 0001");
		long auditoria = contar("auditoria", escuelaA);
		String antes = jdbc.sql("SELECT coalesce(json_agg(d ORDER BY id)::text,'[]') FROM gestion_patin.documento d WHERE escuela_id = :e")
				.param("e", escuelaA).query(String.class).single();
		servicio.consultar(admin, deportista, temporada2026);
		servicio.consultar(familiar, deportista, temporada2026);
		assertThat(contar("auditoria", escuelaA)).isEqualTo(auditoria);
		assertThat(jdbc.sql("SELECT coalesce(json_agg(d ORDER BY id)::text,'[]') FROM gestion_patin.documento d WHERE escuela_id = :e")
				.param("e", escuelaA).query(String.class).single()).isEqualTo(antes);
	}
}
