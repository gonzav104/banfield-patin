package com.banfieldpatin.backend.familias.responsables;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.banfieldpatin.backend.compartido.web.Pagina;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;

@RestController
public class VinculosTutorAdminController {

	private final VinculosTutorConsultaAdmin consultas;

	public VinculosTutorAdminController(VinculosTutorConsultaAdmin consultas) {
		this.consultas = consultas;
	}

	@GetMapping("/api/admin/deportistas/{deportistaId}/vinculos-tutores")
	public Pagina<VinculoTutorRespuesta> listar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deportistaId,
			@RequestParam(defaultValue = "false") boolean historial, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return consultas.listar(UsuarioAutenticado.desde(jwt), deportistaId, historial, page, size);
	}
}
