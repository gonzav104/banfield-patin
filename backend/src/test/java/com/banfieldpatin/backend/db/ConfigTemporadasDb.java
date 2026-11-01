package com.banfieldpatin.backend.db;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import com.banfieldpatin.backend.compartido.auditoria.AuditoriaService;
import com.banfieldpatin.backend.temporadas.DeportistaTemporadaAdminService;
import com.banfieldpatin.backend.temporadas.LockTimeoutTemporadas;
import com.banfieldpatin.backend.temporadas.TemporadaAdminService;

import tools.jackson.databind.json.JsonMapper;

/** Beans reales del modulo de temporadas para las pruebas de BD (servicio y auditoria con jsonb/inet reales). */
@TestConfiguration
@Import({ TemporadaAdminService.class, DeportistaTemporadaAdminService.class, LockTimeoutTemporadas.class,
		AuditoriaService.class })
class ConfigTemporadasDb {

	@Bean
	JsonMapper jsonMapper() {
		return JsonMapper.builder().build();
	}
}
