package com.banfieldpatin.backend.documentos;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.banfieldpatin.backend.compartido.web.Pagina;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;

/** Bandeja administrativa de documentos pendientes de revisión (solo ADMIN, vía /api/admin/**; sin parámetros de alcance). */
@RestController
@RequestMapping("/api/admin/documentos")
public class DocumentoPendienteAdminController {

    private final DocumentoPendienteService servicio;

    public DocumentoPendienteAdminController(DocumentoPendienteService servicio) {
        this.servicio = servicio;
    }

    @GetMapping("/pendientes")
    public Pagina<DocumentoPendienteAdmin> pendientes(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) TitularDocumento titular,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + Pagina.TAMANIO_POR_DEFECTO) int size) {
        return servicio.listar(UsuarioAutenticado.desde(jwt), titular, Pagina.pedir(page, size));
    }
}
