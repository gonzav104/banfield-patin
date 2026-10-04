package com.banfieldpatin.backend.compartido.auditoria;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

/**
 * Escribe en gestion_patin.auditoria con JdbcClient (sin entidad JPA: jsonb/inet/bigserial).
 * registrar() se une a la transaccion del llamador; registrarFallo() usa una transaccion propia y nunca falla.
 */
@Service
public class AuditoriaService {

	private static final Logger log = LoggerFactory.getLogger(AuditoriaService.class);
	static final int USER_AGENT_MAX = 512;
	private static final Set<String> CLAVES_PROHIBIDAS = Set.of("password", "token", "hash", "secret");

	private static final String SQL = """
			INSERT INTO gestion_patin.auditoria
			    (escuela_id, usuario_id, accion, recurso_tipo, recurso_id, detalle, ip, user_agent)
			VALUES
			    (CAST(:escuelaId AS uuid), CAST(:usuarioId AS uuid), :accion, :recursoTipo,
			     CAST(:recursoId AS uuid), CAST(:detalle AS jsonb), CAST(:ip AS inet), :userAgent)
			""";

	private final JdbcClient jdbc;
	private final JsonMapper jsonMapper;

	public AuditoriaService(JdbcClient jdbc, JsonMapper jsonMapper) {
		this.jdbc = jdbc;
		this.jsonMapper = jsonMapper;
	}

	/** Eventos de exito: atomicos con el cambio de negocio (se unen a la transaccion en curso). */
	public void registrar(EventoAuditoria evento) {
		jdbc.sql(SQL)
				.param("escuelaId", texto(evento.escuelaId()))
				.param("usuarioId", texto(evento.usuarioId()))
				.param("accion", evento.accion().name())
				.param("recursoTipo", evento.recursoTipo())
				.param("recursoId", texto(evento.recursoId()))
				.param("detalle", jsonMapper.writeValueAsString(sanear(evento.detalle())))
				.param("ip", ipValida(evento.solicitud().ip()))
				.param("userAgent", truncar(evento.solicitud().userAgent()))
				.update();
	}

	/** Eventos de fallo: transaccion nueva (sobrevive al rollback del llamador); un error de auditoria se registra y se ignora. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void registrarFallo(EventoAuditoria evento) {
		try {
			registrar(evento);
		} catch (RuntimeException e) {
			log.error("No se pudo registrar el evento de auditoria {}", evento.accion(), e);
		}
	}

	private static Map<String, Object> sanear(Map<String, Object> detalle) {
		Map<String, Object> limpio = new LinkedHashMap<>();
		detalle.forEach((clave, valor) -> {
			String k = clave.toLowerCase(Locale.ROOT);
			if (CLAVES_PROHIBIDAS.stream().noneMatch(k::contains)) {
				limpio.put(clave, valor);
			}
		});
		return limpio;
	}

	private static String texto(Object valor) {
		return valor == null ? null : valor.toString();
	}

	private static String truncar(String userAgent) {
		if (userAgent == null) {
			return null;
		}
		return userAgent.length() <= USER_AGENT_MAX ? userAgent : userAgent.substring(0, USER_AGENT_MAX);
	}

	/** Solo literales IPv4/IPv6 (sin hostnames, para no disparar DNS); cualquier otra cosa se guarda como null. */
	private static String ipValida(String ip) {
		if (ip == null || !ip.matches("[0-9a-fA-F:.]{2,45}") || !(ip.contains(".") || ip.contains(":"))) {
			return null;
		}
		try {
			return InetAddress.getByName(ip).getHostAddress();
		} catch (UnknownHostException e) {
			return null;
		}
	}
}
