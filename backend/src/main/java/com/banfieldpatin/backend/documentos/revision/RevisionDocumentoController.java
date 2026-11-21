package com.banfieldpatin.backend.documentos.revision;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.documentos.DocumentoAdminMetadatos;
import com.banfieldpatin.backend.documentos.EstadoDocumento;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;

import jakarta.validation.Valid;

@RestController
public class RevisionDocumentoController {
    private static final Logger LOG = LoggerFactory.getLogger(RevisionDocumentoController.class);
    private final RevisionDocumentoService servicio;

    public RevisionDocumentoController(RevisionDocumentoService servicio) {
        this.servicio = servicio;
    }

    @PostMapping("/api/admin/deportistas/{deportistaId}/documentos/{documentoId}/revision")
    public DocumentoAdminMetadatos revisar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deportistaId,
            @PathVariable UUID documentoId, @Valid @RequestBody RevisionDocumentoSolicitud solicitud) {
        return ejecutar(solicitud, (destino, motivo) -> servicio.revisar(UsuarioAutenticado.desde(jwt), deportistaId,
                documentoId, destino, solicitud.versionEsperada(), motivo));
    }

    /** Revisión de un DNI de un tutor (U19): mismo cuerpo, mismas reglas y mismas respuestas. */
    @PostMapping("/api/admin/tutores/{tutorId}/documentos/{documentoId}/revision")
    public DocumentoAdminMetadatos revisarTutor(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tutorId,
            @PathVariable UUID documentoId, @Valid @RequestBody RevisionDocumentoSolicitud solicitud) {
        return ejecutar(solicitud, (destino, motivo) -> servicio.revisarTutor(UsuarioAutenticado.desde(jwt), tutorId,
                documentoId, destino, solicitud.versionEsperada(), motivo));
    }

    private DocumentoAdminMetadatos ejecutar(RevisionDocumentoSolicitud solicitud,
            java.util.function.BiFunction<EstadoDocumento, String, DocumentoAdminMetadatos> revision) {
        var destino = solicitud.destino();
        var motivo = solicitud.motivoPara(destino);
        try {
            return revision.apply(destino, motivo);
        } catch (ExcepcionNegocio | PessimisticLockingFailureException e) {
            throw e;
        } catch (RuntimeException e) {
            // El error de persistencia (incluso al confirmar la transacción) puede incluir valores SQL.
            LOG.error("No se pudo confirmar la revisión documental; excepción={}", e.getClass().getSimpleName());
            throw new ExcepcionNegocio(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_INTERNO",
                    "No se pudo confirmar la revisión del documento.");
        }
    }
}
