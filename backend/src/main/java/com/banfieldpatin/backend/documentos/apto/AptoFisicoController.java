package com.banfieldpatin.backend.documentos.apto;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;

/** Apto físico de un deportista en una temporada: mismo cálculo para ADMIN y FAMILIA, sin parámetros de alcance (U21). */
@RestController
public class AptoFisicoController {

    private final AptoFisicoService servicio;

    public AptoFisicoController(AptoFisicoService servicio) {
        this.servicio = servicio;
    }

    @GetMapping("/api/admin/deportistas/{id}/temporadas/{temporadaId}/apto-fisico")
    public AptoFisicoTemporada admin(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @PathVariable UUID temporadaId) {
        return servicio.consultar(UsuarioAutenticado.desde(jwt), id, temporadaId);
    }

    @GetMapping("/api/familia/deportistas/{id}/temporadas/{temporadaId}/apto-fisico")
    public AptoFisicoTemporada familia(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @PathVariable UUID temporadaId) {
        return servicio.consultar(UsuarioAutenticado.desde(jwt), id, temporadaId);
    }
}
