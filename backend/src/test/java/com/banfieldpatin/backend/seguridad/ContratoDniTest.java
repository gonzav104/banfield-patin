package com.banfieldpatin.backend.seguridad;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

/**
 * Guarda estructural del DNI (O8/D6): el DNI completo solo viaja en los DETALLES individuales autorizados y en las solicitudes;
 * todo DTO de listado lo expone enmascarado (componente {@code dniEnmascarado}), nunca junto al completo. Un DTO nuevo que
 * exponga {@code dni} hace fallar esta prueba para decidirlo a conciencia. Sin contexto Spring ni base de datos.
 */
class ContratoDniTest {

	/** Records de los paquetes dto que PUEDEN llevar el DNI completo. */
	private static final Set<String> CON_DNI_COMPLETO = Set.of("DeportistaDetalle", "DeportistaSolicitud", "TutorRespuesta",
			"TutorSolicitud", "DeportistaDeFamiliaDetalle");
	/** Records que llevan el DNI enmascarado (listados y tutores anidados). */
	private static final Set<String> CON_DNI_ENMASCARADO = Set.of("DeportistaResumen", "TutorResumen");

	private static Set<Class<?>> dtos() throws ClassNotFoundException {
		ClassPathScanningCandidateComponentProvider escaner = new ClassPathScanningCandidateComponentProvider(false);
		escaner.addIncludeFilter(new AssignableTypeFilter(Record.class));
		Set<Class<?>> clases = new java.util.LinkedHashSet<>();
		for (BeanDefinition definicion : escaner.findCandidateComponents("com.banfieldpatin.backend")) {
			Class<?> tipo = Class.forName(definicion.getBeanClassName());
			if (tipo.getPackageName().contains(".dto")) {
				clases.add(tipo);
			}
		}
		return clases;
	}

	private static Set<String> componentes(Class<?> registro) {
		return new TreeSet<>(Arrays.stream(registro.getRecordComponents()).map(RecordComponent::getName).toList());
	}

	@Test
	void soloLosDtoAutorizadosTienenUnComponenteDniCompleto() throws Exception {
		Set<String> conDni = new TreeSet<>();
		for (Class<?> dto : dtos()) {
			if (componentes(dto).contains("dni")) {
				conDni.add(dto.getSimpleName());
			}
		}

		assertThat(dtos()).as("el escaneo debe encontrar los DTO").hasSizeGreaterThan(20);
		assertThat(conDni).containsExactlyInAnyOrderElementsOf(CON_DNI_COMPLETO);
	}

	@Test
	void losDtoDeListadoExponenElDniEnmascaradoYNuncaElCompleto() throws Exception {
		Set<String> enmascarados = new TreeSet<>();
		for (Class<?> dto : dtos()) {
			Set<String> campos = componentes(dto);
			if (campos.contains("dniEnmascarado")) {
				enmascarados.add(dto.getSimpleName());
				assertThat(campos).as(dto.getSimpleName() + " no debe llevar tambien el DNI completo").doesNotContain("dni");
			}
			if (dto.getSimpleName().endsWith("Resumen")) {
				assertThat(campos).as(dto.getSimpleName()).doesNotContain("dni");
			}
		}

		assertThat(enmascarados).containsExactlyInAnyOrderElementsOf(CON_DNI_ENMASCARADO);
	}

	@Test
	void losDetallesAutorizadosConservanElDniCompletoYNoTienenCampoEnmascarado() throws Exception {
		for (Class<?> dto : dtos()) {
			if (CON_DNI_COMPLETO.contains(dto.getSimpleName())) {
				assertThat(componentes(dto)).as(dto.getSimpleName()).contains("dni").doesNotContain("dniEnmascarado");
			}
		}
	}
}
