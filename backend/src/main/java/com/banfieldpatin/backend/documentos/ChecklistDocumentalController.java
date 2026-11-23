package com.banfieldpatin.backend.documentos;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;

/** Checklist documental parcial de un deportista: mismo cálculo para ADMIN y FAMILIA, sin parámetros de alcance (U20). */
@RestController
public class ChecklistDocumentalController {

    private final ChecklistDocumentalService servicio;

    public ChecklistDocumentalController(ChecklistDocumentalService servicio) {
        this.servicio = servicio;
    }

    @GetMapping("/api/admin/deportistas/{id}/checklist-documental")
    public ChecklistDocumental admin(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return servicio.deportista(UsuarioAutenticado.desde(jwt), id);
    }

    @GetMapping("/api/familia/deportistas/{id}/checklist-documental")
    public ChecklistDocumental familia(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return servicio.deportista(UsuarioAutenticado.desde(jwt), id);
    }
}
