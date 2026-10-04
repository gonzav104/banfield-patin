package com.banfieldpatin.backend.familias.invitaciones;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.familias.invitaciones.dto.InvitacionPublicaRespuesta;
import com.banfieldpatin.backend.familias.invitaciones.dto.RegistroSolicitud;
import com.banfieldpatin.backend.familias.invitaciones.dto.ValidarInvitacionSolicitud;
import com.banfieldpatin.backend.usuarios.dto.UsuarioActualRespuesta;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * Endpoints publicos (siguen protegidos por CSRF). El registro NO inicia sesion: responde 201 sin cookie de sesion
 * y la persona entra despues por /api/auth/login (R1). Es la unica ruta HTTP que crea usuarios FAMILIA.
 */
@RestController
@RequestMapping("/api/auth")
public class RegistroController {

	private final ValidarInvitacionService validacion;
	private final RegistroPorInvitacionService registro;

	public RegistroController(ValidarInvitacionService validacion, RegistroPorInvitacionService registro) {
		this.validacion = validacion;
		this.registro = registro;
	}

	@PostMapping("/invitaciones/validar")
	public ResponseEntity<InvitacionPublicaRespuesta> validar(@Valid @RequestBody ValidarInvitacionSolicitud solicitud,
			HttpServletRequest request) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(validacion.validar(solicitud.token(), DatosSolicitud.de(request)));
	}

	@PostMapping("/registro/invitacion")
	public ResponseEntity<UsuarioActualRespuesta> registrar(@Valid @RequestBody RegistroSolicitud solicitud,
			HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
				.body(registro.registrar(solicitud, DatosSolicitud.de(request)));
	}
}
