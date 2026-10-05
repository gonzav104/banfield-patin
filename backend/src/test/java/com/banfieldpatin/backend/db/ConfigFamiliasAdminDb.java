package com.banfieldpatin.backend.db;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import com.banfieldpatin.backend.compartido.auditoria.AuditoriaService;
import com.banfieldpatin.backend.familias.FamiliaAdminService;

import tools.jackson.databind.json.JsonMapper;

/**
 * Beans reales de la gestion de familias para las pruebas de BD (servicio, auditoria con jsonb/inet reales). Compartida
 * por varias clases para que el contexto se cachee una sola vez (el contenedor tiene max_connections acotado).
 */
@TestConfiguration
@Import({ FamiliaAdminService.class, AuditoriaService.class })
class ConfigFamiliasAdminDb {

	@Bean
	JsonMapper jsonMapper() {
		return JsonMapper.builder().build();
	}
}
