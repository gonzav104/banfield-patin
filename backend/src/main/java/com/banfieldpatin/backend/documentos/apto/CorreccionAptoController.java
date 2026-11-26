package com.banfieldpatin.backend.documentos.apto;

import java.util.UUID;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;

import jakarta.validation.Valid;

/** Corrección administrativa de los datos de un apto físico (U21); solo ADMIN, con CSRF como toda escritura. */
@RestController
public class CorreccionAptoController {
    private static final Logger LOG = LoggerFactory.getLogger(CorreccionAptoController.class);
    private final CorreccionAptoService servicio;

    public CorreccionAptoController(CorreccionAptoService servicio) {
        this.servicio = servicio;
    }

    @PutMapping("/api/admin/deportistas/{deportistaId}/documentos/{documentoId}/datos-apto")
    public AptoFisicoTemporada.DocumentoApto corregir(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deportistaId,
            @PathVariable UUID documentoId, @Valid @RequestBody CorreccionAptoSolicitud solicitud) {
        try {
            return servicio.corregir(UsuarioAutenticado.desde(jwt), deportistaId, documentoId, solicitud.versionEsperada(),
                    solicitud.datos());
        } catch (ExcepcionNegocio | PessimisticLockingFailureException e) {
            throw e;
        } catch (RuntimeException e) {
            // El error de persistencia puede incluir valores SQL: nunca al cliente ni al log.
            LOG.error("No se pudo confirmar la corrección del apto; excepción={}", e.getClass().getSimpleName());
            throw new ExcepcionNegocio(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_INTERNO",
                    "No se pudo confirmar la corrección del apto.");
        }
    }
}
