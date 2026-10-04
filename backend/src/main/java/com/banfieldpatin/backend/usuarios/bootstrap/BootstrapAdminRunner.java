package com.banfieldpatin.backend.usuarios.bootstrap;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.banfieldpatin.backend.compartido.auditoria.AccionAuditoria;
import com.banfieldpatin.backend.compartido.auditoria.AuditoriaService;
import com.banfieldpatin.backend.compartido.auditoria.EventoAuditoria;
import com.banfieldpatin.backend.compartido.auditoria.MascaraEmail;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.escuelas.Escuela;
import com.banfieldpatin.backend.escuelas.EscuelaRepository;
import com.banfieldpatin.backend.seguridad.ValidadorContrasena;
import com.banfieldpatin.backend.usuarios.Rol;
import com.banfieldpatin.backend.usuarios.Usuario;
import com.banfieldpatin.backend.usuarios.UsuarioRepository;

/**
 * Crea el primer ADMIN de la escuela configurada. Solo existe como bean si
 * banfield.bootstrap-admin.habilitado=true. Idempotente, respeta max_administradores y falla el arranque si la
 * configuracion esta incompleta o es debil. Nunca registra la password ni el email completo.
 */
@Component
@ConditionalOnBooleanProperty("banfield.bootstrap-admin.habilitado")
@EnableConfigurationProperties(BootstrapAdminPropiedades.class)
public class BootstrapAdminRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(BootstrapAdminRunner.class);
	private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
	private static final int CAMPO_MAX = 150;
	private static final int EMAIL_MAX = 254;

	private final BootstrapAdminPropiedades propiedades;
	private final String slugEscuela;
	private final EscuelaRepository escuelas;
	private final UsuarioRepository usuarios;
	private final AuditoriaService auditoria;
	private final PasswordEncoder codificador;

	public BootstrapAdminRunner(BootstrapAdminPropiedades propiedades,
			@Value("${banfield.escuela.slug}") String slugEscuela, EscuelaRepository escuelas,
			UsuarioRepository usuarios, AuditoriaService auditoria, PasswordEncoder codificador) {
		this.propiedades = propiedades;
		this.slugEscuela = slugEscuela;
		this.escuelas = escuelas;
		this.usuarios = usuarios;
		this.auditoria = auditoria;
		this.codificador = codificador;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		String email = validarConfiguracion();

		Escuela escuela = escuelas.findBySlugParaActualizar(slugEscuela)
				.filter(Escuela::isActiva)
				.orElseThrow(() -> new IllegalStateException(
						"Bootstrap ADMIN: la escuela configurada no existe o esta inactiva"));

		if (usuarios.existeEmail(escuela.getId(), email)) {
			log.info("Bootstrap ADMIN omitido: ya existe un usuario con el email {}", MascaraEmail.enmascarar(email));
			return;
		}
		long administradores = usuarios.contarAdminsActivos(escuela.getId());
		if (administradores >= escuela.getMaxAdministradores()) {
			log.warn("Bootstrap ADMIN omitido: la escuela ya alcanzo el maximo de administradores ({})",
					escuela.getMaxAdministradores());
			return;
		}

		// saveAndFlush: el id es generado, Hibernate difiere el INSERT; la auditoria (JDBC, misma transaccion) exige
		// que la fila del usuario exista fisicamente (fk_auditoria_usuario_misma_escuela).
		Usuario admin = usuarios.saveAndFlush(Usuario.crear(escuela.getId(), null, propiedades.nombre().trim(),
				propiedades.apellido().trim(), email, codificador.encode(propiedades.password()), Rol.ADMIN));
		auditoria.registrar(new EventoAuditoria(escuela.getId(), admin.getId(), AccionAuditoria.ADMIN_BOOTSTRAP,
				"usuario", admin.getId(), Map.of("email", MascaraEmail.enmascarar(email)), DatosSolicitud.NINGUNA));
		log.info("Bootstrap ADMIN: administrador {} creado", MascaraEmail.enmascarar(email));
	}

	/** Devuelve el email normalizado o falla sin incluir nunca el valor de la password. */
	private String validarConfiguracion() {
		List<String> faltantes = new java.util.ArrayList<>();
		if (esVacio(propiedades.email())) {
			faltantes.add("email");
		}
		if (esVacio(propiedades.nombre())) {
			faltantes.add("nombre");
		}
		if (esVacio(propiedades.apellido())) {
			faltantes.add("apellido");
		}
		if (esVacio(propiedades.password())) {
			faltantes.add("password");
		}
		if (!faltantes.isEmpty()) {
			throw new IllegalStateException(
					"Bootstrap ADMIN habilitado pero faltan propiedades: " + String.join(", ", faltantes));
		}
		String email = propiedades.email().trim().toLowerCase(Locale.ROOT);
		if (email.length() > EMAIL_MAX || !EMAIL.matcher(email).matches()) {
			throw new IllegalStateException("Bootstrap ADMIN: el email configurado no tiene un formato valido");
		}
		if (propiedades.nombre().trim().length() > CAMPO_MAX || propiedades.apellido().trim().length() > CAMPO_MAX) {
			throw new IllegalStateException("Bootstrap ADMIN: nombre o apellido superan " + CAMPO_MAX + " caracteres");
		}
		if (!new ValidadorContrasena().esValida(propiedades.password())) {
			throw new IllegalStateException("Bootstrap ADMIN: la password no cumple la politica (minimo "
					+ ValidadorContrasena.MIN_LONGITUD_POR_DEFECTO + " caracteres y maximo "
					+ ValidadorContrasena.MAX_BYTES + " bytes UTF-8)");
		}
		return email;
	}

	private static boolean esVacio(String valor) {
		return valor == null || valor.isBlank();
	}
}
