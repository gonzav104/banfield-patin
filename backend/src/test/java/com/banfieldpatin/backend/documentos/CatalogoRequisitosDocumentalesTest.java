package com.banfieldpatin.backend.documentos;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** El cálculo puro del checklist parcial: sin base, sin red. */
class CatalogoRequisitosDocumentalesTest {
	private static final UUID DEPORTISTA = UUID.randomUUID();
	private static final Instant T0 = Instant.parse("2026-03-10T10:00:00Z");

	private static DocumentoMetadatos doc(TipoDocumento tipo, EstadoDocumento estado, boolean vigente, Instant creado, String motivo) {
		return new DocumentoMetadatos(UUID.randomUUID(), tipo, estado, null, creado, vigente, null, motivo);
	}

	private static List<RequisitoDocumental> calcular(DocumentoMetadatos... docs) {
		return CatalogoRequisitosDocumentales.calcular(DEPORTISTA, List.of(docs)).requisitos();
	}

	@Test
	void sinDocumentosAmbosLadosDelDniFaltan() {
		var checklist = CatalogoRequisitosDocumentales.calcular(DEPORTISTA, List.of());
		assertThat(checklist.requisitos()).containsExactly(
				new RequisitoDocumental(TipoDocumento.DNI_DEPORTISTA_FRENTE, EstadoRequisito.FALTA, null),
				new RequisitoDocumental(TipoDocumento.DNI_DEPORTISTA_DORSO, EstadoRequisito.FALTA, null));
	}

	@Test
	void elChecklistSeDeclaraParcialYNombraSuAlcanceSinPorcentajeNiEstadoFederativo() {
		var checklist = CatalogoRequisitosDocumentales.calcular(DEPORTISTA, List.of());
		assertThat(checklist.parcial()).isTrue();
		assertThat(checklist.alcance()).isEqualTo("DNI_DEPORTISTA");
		assertThat(checklist.deportistaId()).isEqualTo(DEPORTISTA);
		assertThat(ChecklistDocumental.class.getRecordComponents()).extracting(c -> c.getName())
				.containsExactly("deportistaId", "parcial", "alcance", "requisitos");
	}

	@Test
	void soloUnDocumentoVigenteYAprobadoCumple() {
		assertThat(calcular(doc(TipoDocumento.DNI_DEPORTISTA_FRENTE, EstadoDocumento.APROBADO, true, T0, null)))
				.extracting(RequisitoDocumental::estado).containsExactly(EstadoRequisito.CUMPLIDO, EstadoRequisito.FALTA);
		// Aprobado pero NO vigente (reemplazado): no cumple nada.
		assertThat(calcular(doc(TipoDocumento.DNI_DEPORTISTA_FRENTE, EstadoDocumento.APROBADO, false, T0, null)))
				.extracting(RequisitoDocumental::estado).containsExactly(EstadoRequisito.FALTA, EstadoRequisito.FALTA);
	}

	@Test
	void cargadoEstaEnRevisionYObservadoConservaSuMotivo() {
		var requisitos = calcular(
				doc(TipoDocumento.DNI_DEPORTISTA_FRENTE, EstadoDocumento.CARGADO, true, T0, null),
				doc(TipoDocumento.DNI_DEPORTISTA_DORSO, EstadoDocumento.OBSERVADO, true, T0, "El dorso está cortado."));
		assertThat(requisitos).containsExactly(
				new RequisitoDocumental(TipoDocumento.DNI_DEPORTISTA_FRENTE, EstadoRequisito.EN_REVISION, null),
				new RequisitoDocumental(TipoDocumento.DNI_DEPORTISTA_DORSO, EstadoRequisito.OBSERVADO, "El dorso está cortado."));
	}

	@Test
	void soloElObservadoLlevaMotivoAunqueElDatoVengaConUnoViejo() {
		var requisitos = calcular(
				doc(TipoDocumento.DNI_DEPORTISTA_FRENTE, EstadoDocumento.CARGADO, true, T0, "texto viejo"),
				doc(TipoDocumento.DNI_DEPORTISTA_DORSO, EstadoDocumento.APROBADO, true, T0, "texto viejo"));
		assertThat(requisitos).extracting(RequisitoDocumental::motivoObservacion).containsOnlyNulls();
	}

	@Test
	void unaVersionHistoricaNuncaDecideSiHayUnaVigente() {
		var requisitos = calcular(
				doc(TipoDocumento.DNI_DEPORTISTA_FRENTE, EstadoDocumento.OBSERVADO, false, T0, "Borroso."),
				doc(TipoDocumento.DNI_DEPORTISTA_FRENTE, EstadoDocumento.CARGADO, true, T0.plusSeconds(60), null));
		assertThat(requisitos.getFirst()).isEqualTo(new RequisitoDocumental(TipoDocumento.DNI_DEPORTISTA_FRENTE, EstadoRequisito.EN_REVISION, null));
	}

	@Test
	void losDocumentosDeTutoresYOtrosTiposNoCuentan() {
		var requisitos = calcular(
				doc(TipoDocumento.DNI_TUTOR_FRENTE, EstadoDocumento.APROBADO, true, T0, null),
				doc(TipoDocumento.DNI_TUTOR_DORSO, EstadoDocumento.APROBADO, true, T0, null),
				doc(TipoDocumento.APTO_FISICO, EstadoDocumento.APROBADO, true, T0, null),
				doc(TipoDocumento.OTRO, EstadoDocumento.APROBADO, true, T0, null));
		assertThat(requisitos).extracting(RequisitoDocumental::estado).containsExactly(EstadoRequisito.FALTA, EstadoRequisito.FALTA);
	}

	@Test
	void unEstadoQueNingunFlujoProduceParaUnDniNoCumpleNiEsperaRevision() {
		var requisitos = calcular(
				doc(TipoDocumento.DNI_DEPORTISTA_FRENTE, EstadoDocumento.PENDIENTE, true, T0, null),
				doc(TipoDocumento.DNI_DEPORTISTA_DORSO, EstadoDocumento.VENCIDO, true, T0, null));
		assertThat(requisitos).extracting(RequisitoDocumental::estado).containsExactly(EstadoRequisito.FALTA, EstadoRequisito.FALTA);
	}

	@Test
	void conVariosVigentesDelMismoTipoDecideElMasReciente() {
		var requisitos = calcular(
				doc(TipoDocumento.DNI_DEPORTISTA_DORSO, EstadoDocumento.APROBADO, true, T0, null),
				doc(TipoDocumento.DNI_DEPORTISTA_DORSO, EstadoDocumento.OBSERVADO, true, T0.plusSeconds(5), "Reciente."));
		assertThat(requisitos.get(1)).isEqualTo(new RequisitoDocumental(TipoDocumento.DNI_DEPORTISTA_DORSO, EstadoRequisito.OBSERVADO, "Reciente."));
	}

	@Test
	void elCatalogoEvaluaSoloLosDosLadosDelDniDelDeportista() {
		assertThat(CatalogoRequisitosDocumentales.DNI_DEPORTISTA)
				.containsExactly(TipoDocumento.DNI_DEPORTISTA_FRENTE, TipoDocumento.DNI_DEPORTISTA_DORSO);
	}
}
