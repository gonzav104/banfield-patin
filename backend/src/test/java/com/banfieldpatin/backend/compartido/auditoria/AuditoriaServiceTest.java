package com.banfieldpatin.backend.compartido.auditoria;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.banfieldpatin.backend.compartido.web.DatosSolicitud;

import tools.jackson.databind.json.JsonMapper;

class AuditoriaServiceTest {

	private final JdbcClient jdbc = mock(JdbcClient.class);
	private final JdbcClient.StatementSpec sentencia = mock(JdbcClient.StatementSpec.class, RETURNS_SELF);
	private final AuditoriaService servicio = new AuditoriaService(jdbc, JsonMapper.builder().build());

	AuditoriaServiceTest() {
		when(jdbc.sql(anyString())).thenReturn(sentencia);
	}

	private Map<String, Object> parametros() {
		ArgumentCaptor<String> nombres = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<Object> valores = ArgumentCaptor.forClass(Object.class);
		verify(sentencia, atLeastOnce()).param(nombres.capture(), valores.capture());
		Map<String, Object> mapa = new HashMap<>();
		for (int i = 0; i < nombres.getAllValues().size(); i++) {
			mapa.put(nombres.getAllValues().get(i), valores.getAllValues().get(i));
		}
		return mapa;
	}

	private EventoAuditoria evento(Map<String, Object> detalle, DatosSolicitud solicitud) {
		return new EventoAuditoria(UUID.randomUUID(), null, AccionAuditoria.LOGIN_FALLIDO, "USUARIO", null,
				detalle, solicitud);
	}

	@Test
	void insertaEnAuditoriaConCastsParaJsonbEInet() {
		UUID escuela = UUID.randomUUID();
		UUID usuario = UUID.randomUUID();
		servicio.registrar(new EventoAuditoria(escuela, usuario, AccionAuditoria.LOGIN_EXITOSO, "USUARIO", usuario,
				Map.of("canal", "FAMILIA"), new DatosSolicitud("10.0.0.5", "JUnit")));

		ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
		verify(jdbc).sql(sql.capture());
		assertThat(sql.getValue()).contains("INSERT INTO gestion_patin.auditoria", "CAST(:detalle AS jsonb)",
				"CAST(:ip AS inet)");
		verify(sentencia).update();
		Map<String, Object> p = parametros();
		assertThat(p).containsEntry("escuelaId", escuela.toString())
				.containsEntry("usuarioId", usuario.toString())
				.containsEntry("accion", "LOGIN_EXITOSO")
				.containsEntry("ip", "10.0.0.5")
				.containsEntry("userAgent", "JUnit");
		assertThat((String) p.get("detalle")).contains("\"canal\"").contains("FAMILIA");
	}

	@Test
	void detalleNuncaContieneClavesDePasswordOToken() {
		Map<String, Object> detalle = new HashMap<>();
		detalle.put("motivo", "CREDENCIALES");
		detalle.put("password", "secreto");
		detalle.put("tokenInvitacion", "abc");
		detalle.put("tokenHash", "def");
		servicio.registrar(evento(detalle, null));

		String json = (String) parametros().get("detalle");
		assertThat(json).contains("motivo").doesNotContain("password", "secreto", "token", "abc", "def");
	}

	@Test
	void truncaUserAgentEIgnoraIpNoParseable() {
		servicio.registrar(evento(Map.of(), new DatosSolicitud("no-es-una-ip", "x".repeat(2000))));

		Map<String, Object> p = parametros();
		assertThat((String) p.get("userAgent")).hasSize(AuditoriaService.USER_AGENT_MAX);
		assertThat(p.get("ip")).isNull();
	}

	@Test
	void ipv6EsValida() {
		servicio.registrar(evento(Map.of(), new DatosSolicitud("::1", null)));
		assertThat(parametros().get("ip")).isEqualTo("0:0:0:0:0:0:0:1");
	}

	@Test
	void registrarFalloTragaYRegistraLosErrores() {
		when(sentencia.update()).thenThrow(new DataAccessResourceFailureException("bd caida"));

		assertThatCode(() -> servicio.registrarFallo(evento(Map.of(), null))).doesNotThrowAnyException();
	}

	@Test
	void registrarPropagaLosErroresParaFallarJuntoAlNegocio() {
		when(sentencia.update()).thenThrow(new DataAccessResourceFailureException("bd caida"));

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> servicio.registrar(evento(Map.of(), null)))
				.isInstanceOf(DataAccessResourceFailureException.class);
	}

	@Test
	void mascaraEmails() {
		assertThat(MascaraEmail.enmascarar("juan@dominio.com")).isEqualTo("j***@d***.com");
		assertThat(MascaraEmail.enmascarar("juan@localhost")).isEqualTo("j***@l***");
		assertThat(MascaraEmail.enmascarar("sin-arroba")).isEqualTo("s***");
		assertThat(MascaraEmail.enmascarar(" ")).isEqualTo("***");
		assertThat(MascaraEmail.enmascarar(null)).isEqualTo("***");
	}
}
