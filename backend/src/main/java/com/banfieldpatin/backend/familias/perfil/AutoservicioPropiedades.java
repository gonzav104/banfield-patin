package com.banfieldpatin.backend.familias.perfil;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Banderas y límites del autoservicio familiar (banfield.autoservicio.*, U21-BIS). Todas las banderas están apagadas por
 * defecto: apagadas, la familia puede consultar pero las escrituras responden 503 antes de leer o escribir nada.
 *
 * <ul>
 * <li>{@code perfilHabilitado} (C1): perfil propio y reclamo, 503 {@code AUTOSERVICIO_PERFIL_DESHABILITADO}.</li>
 * <li>{@code tutoresHabilitado} (C2): otros adultos de la familia, 503 {@code AUTOSERVICIO_TUTORES_DESHABILITADO}.</li>
 * <li>{@code topeTutoresPorFamilia} (C2): adultos ACTIVOS con origen FAMILIA (perfil propio incluido) que una familia puede
 * cargar sola; los que carga el club no cuentan. Valor provisorio (10) a validar con el club antes de producción.</li>
 * </ul>
 */
@ConfigurationProperties("banfield.autoservicio")
public record AutoservicioPropiedades(@DefaultValue("false") boolean perfilHabilitado,
		@DefaultValue("false") boolean tutoresHabilitado, @DefaultValue("10") int topeTutoresPorFamilia) {

	public AutoservicioPropiedades {
		if (topeTutoresPorFamilia < 1 || topeTutoresPorFamilia > 100) {
			throw new IllegalArgumentException("banfield.autoservicio.tope-tutores-por-familia debe estar entre 1 y 100");
		}
	}
}
