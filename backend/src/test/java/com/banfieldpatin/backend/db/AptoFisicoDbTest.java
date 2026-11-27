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
	UUID usuarioDeLaFamilia;

	@BeforeEach
	void preparar() {
		AHORA.set(Instant.parse("2026-06-15T15:00:00Z"));
		familia = datos.familia(escuelaA, "Familia apto", true);
		usuarioDeLaFamilia = datos.usuarioFamilia(escuelaA, familia, "apto@example.invalid");
		familiar = new UsuarioAutenticado(usuarioDeLaFamilia, escuelaA, familia, Rol.FAMILIA);
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
		return apto(escuela, titular, temporada, estado, vigente, matricula,
				escuela.equals(escuelaA) ? usuarioDeLaFamilia : adminDeB.id());
	}

	/** {@code cargadoPor} es la procedencia real de la carga: el usuario que subió el apto. */
	UUID apto(UUID escuela, UUID titular, UUID temporada, String estado, boolean vigente, String matricula, UUID cargadoPor) {
		return jdbc.sql("""
				INSERT INTO gestion_patin.documento(escuela_id, deportista_id, temporada_id, tipo, nombre_original, storage_key,
				  mime_type, tamanio_bytes, estado, vigente, cargado_por, motivo_observacion, fecha_emision, medico_nombre,
				  medico_matricula)
				VALUES (:e, :d, :t, 'APTO_FISICO', 'NOMBRE_PRIVADO.pdf', :key, 'application/pdf', 100, :estado, :vigente, :autor,
				  CASE WHEN :estado = 'OBSERVADO' THEN 'MOTIVO_FICTICIO' END, DATE '2026-03-01', 'Medico Sintetico', :m)
				RETURNING id
				""").param("e", escuela).param("d", titular).param("t", temporada).param("key", "RUTA_PRIVADA/" + UUID.randomUUID())
				.param("estado", estado).param("vigente", vigente)
				.param("autor", cargadoPor).param("m", matricula)
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

	// ---------- procedencia: los datos de una familia no pasan a otra ----------

	UsuarioAutenticado segundaFamilia() {
		var otra = datos.familia(escuelaA, "Segunda familia", true);
		datos.vinculo(escuelaA, otra, deportista, "ACTIVO", false, adminId);
		return new UsuarioAutenticado(datos.usuarioFamilia(escuelaA, otra, "segunda-" + UUID.randomUUID() + "@example.invalid"),
				escuelaA, otra, Rol.FAMILIA);
	}

	@Test
	void laFamiliaQueCargoVeSusDatosYLaSegundaFamiliaSoloVeElEstado() {
		apto(escuelaA, deportista, temporada2026, "CARGADO", true, "MP PRIVADA");
		var segunda = segundaFamilia();
		var propia = servicio.consultar(familiar, deportista, temporada2026);
		assertThat(propia.documento().datosVisibles()).isTrue();
		assertThat(propia.documento().medicoMatricula()).isEqualTo("MP PRIVADA");
		var ajena = servicio.consultar(segunda, deportista, temporada2026);
		assertThat(ajena.estado()).isEqualTo(EstadoApto.EN_REVISION);
		assertThat(ajena.vigencia().hasta()).hasToString("2026-12-31");
		assertThat(ajena.documento().datosVisibles()).isFalse();
		assertThat(ajena.documento().fechaEmision()).isNull();
		assertThat(ajena.documento().medicoNombre()).isNull();
		assertThat(ajena.documento().medicoMatricula()).isNull();
		assertThat(ajena.documento().id()).isEqualTo(propia.documento().id());
		// ADMIN lo ve todo
		assertThat(servicio.consultar(admin, deportista, temporada2026).documento().medicoMatricula()).isEqualTo("MP PRIVADA");
	}

	@Test
	void unaCorreccionAdminNoHaceVisiblesLosDatosParaLaOtraFamilia() {
		var id = apto(escuelaA, deportista, temporada2026, "CARGADO", true, "MP PRIVADA");
		var segunda = segundaFamilia();
		jdbc.sql("UPDATE gestion_patin.documento SET medico_matricula='MP CORREGIDA', version=version+1 WHERE id=:i").param("i", id).update();
		assertThat(servicio.consultar(familiar, deportista, temporada2026).documento().medicoMatricula()).isEqualTo("MP CORREGIDA");
		assertThat(servicio.consultar(segunda, deportista, temporada2026).documento().medicoMatricula()).isNull();
	}

	@Test
	void unReemplazoPasaLaProcedenciaALaFamiliaQueLoHizo() {
		var segunda = segundaFamilia();
		apto(escuelaA, deportista, temporada2026, "OBSERVADO", false, "MP DE LA PRIMERA");
		apto(escuelaA, deportista, temporada2026, "CARGADO", true, "MP DE LA SEGUNDA", segunda.id());
		assertThat(servicio.consultar(segunda, deportista, temporada2026).documento().medicoMatricula()).isEqualTo("MP DE LA SEGUNDA");
		var primera = servicio.consultar(familiar, deportista, temporada2026);
		assertThat(primera.estado()).isEqualTo(EstadoApto.EN_REVISION);
		assertThat(primera.documento().datosVisibles()).isFalse();
		assertThat(primera.documento().medicoMatricula()).isNull();
	}

	@Test
	void sinVinculoDejaDeVerTodoYLaOtraFamiliaSigueSinVerLosDatosDeLaRevocada() {
		apto(escuelaA, deportista, temporada2026, "CARGADO", true, "MP PRIVADA");
		var segunda = segundaFamilia();
		jdbc.sql("UPDATE gestion_patin.familia_deportista SET estado='REVOCADO' WHERE familia_id=:f AND deportista_id=:d")
				.param("f", familia).param("d", deportista).update();
		noEncontrado(() -> servicio.consultar(familiar, deportista, temporada2026), "DEPORTISTA_NO_ENCONTRADO");
		var ajena = servicio.consultar(segunda, deportista, temporada2026);
		assertThat(ajena.estado()).isEqualTo(EstadoApto.EN_REVISION);
		assertThat(ajena.documento().medicoMatricula()).isNull();
	}

	@Test
	void unAptoCargadoPorUnAdminOUnUsuarioAjenoNoMuestraDatosALasFamilias() {
		apto(escuelaA, deportista, temporada2026, "CARGADO", true, "MP DE ADMIN", adminId);
		var r = servicio.consultar(familiar, deportista, temporada2026);
		assertThat(r.documento().datosVisibles()).isFalse();
		assertThat(r.documento().medicoMatricula()).isNull();
		assertThat(servicio.consultar(admin, deportista, temporada2026).documento().medicoMatricula()).isEqualTo("MP DE ADMIN");
	}

	@Test
	void elMotivoDeLaObservacionSoloLoVeLaFamiliaQueCargoYLaOtraRecibeUnAvisoGenerico() {
		apto(escuelaA, deportista, temporada2026, "OBSERVADO", true, "MP PRIVADA");
		var segunda = segundaFamilia();
		var propia = servicio.consultar(familiar, deportista, temporada2026);
		assertThat(propia.estado()).isEqualTo(EstadoApto.OBSERVADO);
		assertThat(propia.documento().motivoObservacion()).isEqualTo("MOTIVO_FICTICIO");
		assertThat(propia.avisos()).doesNotContain(AptoFisicoTemporada.AVISO_OBSERVADO_REQUIERE_REEMPLAZO);
		var ajena = servicio.consultar(segunda, deportista, temporada2026);
		assertThat(ajena.estado()).isEqualTo(EstadoApto.OBSERVADO);
		assertThat(ajena.documento().estado()).isEqualTo("OBSERVADO");
		assertThat(ajena.documento().motivoObservacion()).isNull();
		assertThat(ajena.avisos()).contains(AptoFisicoTemporada.AVISO_OBSERVADO_REQUIERE_REEMPLAZO);
		assertThat(ajena.toString()).doesNotContain("MOTIVO_FICTICIO", "MP PRIVADA");
		var admin = servicio.consultar(this.admin, deportista, temporada2026);
		assertThat(admin.documento().motivoObservacion()).isEqualTo("MOTIVO_FICTICIO");
		assertThat(admin.avisos()).doesNotContain(AptoFisicoTemporada.AVISO_OBSERVADO_REQUIERE_REEMPLAZO);
	}

	@Test
	void unAptoQueNoEstaObservadoNoAgregaElAvisoDeReemplazo() {
		apto(escuelaA, deportista, temporada2026, "CARGADO", true, "MP PRIVADA");
		assertThat(servicio.consultar(segundaFamilia(), deportista, temporada2026).avisos())
				.doesNotContain(AptoFisicoTemporada.AVISO_OBSERVADO_REQUIERE_REEMPLAZO);
	}
}
