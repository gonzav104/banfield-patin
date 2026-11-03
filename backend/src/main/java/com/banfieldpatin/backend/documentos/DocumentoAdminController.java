package com.banfieldpatin.backend.documentos;

import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.compartido.web.Pagina;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import jakarta.servlet.http.HttpServletRequest;
/** Consulta documental; no registra operaciones de archivos ni acepta parametros de alcance. */
@RestController
@RequestMapping("/api/admin")
public class DocumentoAdminController {

          private final DocumentoConsultaService servicio;

    public DocumentoAdminController(DocumentoConsultaService servicio) {
        this.servicio = servicio;
    }

    @GetMapping("/deportistas/{id}/documentos")
    public Pagina<DocumentoMetadatos> deportista(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, HttpServletRequest request) {
        return servicio.deportista(UsuarioAutenticado.desde(jwt), id, Pagina.pedir(page, size), DatosSolicitud.de(request));
    }

    @GetMapping("/tutores/{id}/documentos")
    public Pagina<DocumentoMetadatos> tutor(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, HttpServletRequest request) {
        return servicio.tutor(UsuarioAutenticado.desde(jwt), id, Pagina.pedir(page, size), DatosSolicitud.de(request));
    }
}
