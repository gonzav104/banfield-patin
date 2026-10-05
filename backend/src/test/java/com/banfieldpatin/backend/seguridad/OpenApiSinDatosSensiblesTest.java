package com.banfieldpatin.backend.seguridad;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

/**
 * La especificacion no expone rutas internas ni de prueba, entidades, datos con apariencia real ni campos de secretos fuera
 * de los que el diseno ya muestra (token de invitacion y secreto TOTP una sola vez, token CSRF, contrasenas en las
 * solicitudes). Una incorporacion nueva de un campo sensible hace fallar esta prueba para decidirlo a conciencia.
 */
class OpenApiSinDatosSensiblesTest extends BaseOpenApiWebMvc {

	private static final Pattern NOMBRE_SENSIBLE = Pattern.compile("(?i)token|password|secret|hash|clave|otpauth|contrasena");

	@Test
	void soloHayRutasDeLaApiYNingunaInternaNiDePrueba() throws Exception {
		Set<String> rutas = new TreeSet<>();
		arbol().path("paths").propertyNames().forEach(rutas::add);

		assertThat(rutas).isNotEmpty().allMatch(r -> r.startsWith("/api/"));
		assertThat(rutas).noneMatch(r -> Pattern.compile("(?i)actuator|sonda|ping|swagger|error|h2|debug|internal").matcher(r).find());
	}

	@Test
	void losControladoresSondaVivenSoloEnLasClasesDePrueba() throws Exception {
		ClassPathScanningCandidateComponentProvider escaner = new ClassPathScanningCandidateComponentProvider(false);
		escaner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
		int sondas = 0;
		for (BeanDefinition definicion : escaner.findCandidateComponents("com.banfieldpatin.backend")) {
			Class<?> tipo = Class.forName(definicion.getBeanClassName());
			if (tipo.getName().contains("Sonda")) {
				sondas++;
				URL origen = tipo.getProtectionDomain().getCodeSource().getLocation();
				assertThat(origen.getPath()).as(tipo.getName()).endsWith("/test-classes/");
			}
		}
		assertThat(sondas).as("hay sondas de prueba que este test debe poder ver").isGreaterThanOrEqualTo(2);
	}

	@Test
	void noHayEjemplosNiValoresConAparienciaReal() throws Exception {
		String texto = especificacion();

		assertThat(texto).doesNotContain("\"example\"", "\"examples\"", "\"default\":\"admin", "eyJ", "Bearer ", "@");
		// Ninguna secuencia de 7 o mas digitos (DNI, CUIL, telefonos): los unicos numeros son limites de longitud.
		assertThat(Pattern.compile("\\d{7,}").matcher(texto).find()).isFalse();
		// Ningun UUID literal (los ids son "format":"uuid", sin valores).
		assertThat(Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matcher(texto).find()).isFalse();
	}

	@Test
	void losCamposDeSecretosSonSoloLosQueElDisenoMuestra() throws Exception {
		Set<String> sensibles = new TreeSet<>();
		Set<String> enRespuestas = new TreeSet<>();
		for (Map.Entry<String, JsonNode> esquema : arbol().path("components").path("schemas").properties()) {
			for (String propiedad : esquema.getValue().path("properties").propertyNames()) {
				if (NOMBRE_SENSIBLE.matcher(propiedad).find()) {
					String campo = esquema.getKey() + "." + propiedad;
					sensibles.add(campo);
					if (!esquema.getKey().endsWith("Solicitud")) {
						enRespuestas.add(campo);
					}
				}
			}
		}

		assertThat(sensibles).containsExactlyInAnyOrder("CsrfRespuesta.token", "EnrolamientoMfaRespuesta.otpauthUri",
				"EnrolamientoMfaRespuesta.secretoBase32", "InvitacionCreadaRespuesta.token", "LoginSolicitud.password",
				"RegistroSolicitud.password", "RegistroSolicitud.token", "ValidarInvitacionSolicitud.token");
		// En una RESPUESTA solo: el token CSRF, el secreto TOTP (una vez) y el token de la invitacion recien creada.
		assertThat(enRespuestas).containsExactlyInAnyOrder("CsrfRespuesta.token", "EnrolamientoMfaRespuesta.otpauthUri",
				"EnrolamientoMfaRespuesta.secretoBase32", "InvitacionCreadaRespuesta.token");
	}

	@Test
	void noSeExponenEntidadesYLosDtoDelPortalOmitenLosCamposInternos() throws Exception {
		JsonNode esquemas = arbol().path("components").path("schemas");

		assertThat(esquemas.propertyNames()).doesNotContain("Usuario", "Familia", "Deportista", "Tutor", "FamiliaDeportista",
				"Invitacion", "UsuarioMfa", "Escuela", "Auditoria");
		for (String portal : new String[] { "MiFamiliaRespuesta", "TutorDeFamilia", "DeportistaDeFamilia",
				"DeportistaDeFamiliaDetalle" }) {
			assertThat(esquemas.path(portal).path("properties").propertyNames()).as(portal).doesNotContain("escuelaId",
					"esPrincipal", "autorizadoPor", "autorizadoEn", "vinculoId", "creadoEn", "usuarioId");
		}
		assertThat(esquemas.path("TutorDeFamilia").path("properties").propertyNames()).doesNotContain("dni", "activo");
		assertThat(esquemas.path("DeportistaDeFamilia").path("properties").propertyNames()).contains("activo");
		// Las solicitudes no aceptan campos que decide el servidor (mass assignment): escuela, estado, rol, principal, actor.
		for (Map.Entry<String, JsonNode> esquema : esquemas.properties()) {
			if (esquema.getKey().endsWith("Solicitud")) {
				assertThat(esquema.getValue().path("properties").propertyNames()).as(esquema.getKey()).doesNotContain(
						"escuelaId", "activo", "activa", "estado", "rol", "usuarioId", "esPrincipal", "autorizadoPor");
			}
		}
	}
}
