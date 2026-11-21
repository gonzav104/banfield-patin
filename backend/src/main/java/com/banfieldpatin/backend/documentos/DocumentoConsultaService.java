package com.banfieldpatin.backend.documentos;

import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.banfieldpatin.backend.compartido.auditoria.AccionAuditoria;
import com.banfieldpatin.backend.compartido.auditoria.AuditoriaService;
import com.banfieldpatin.backend.compartido.auditoria.EventoAuditoria;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.compartido.web.Pagina;
import com.banfieldpatin.backend.deportistas.DeportistaAdminService;
import com.banfieldpatin.backend.deportistas.DeportistaRepository;
import com.banfieldpatin.backend.familias.tutores.TutorAdminService;
import com.banfieldpatin.backend.familias.tutores.TutorRepository;
import com.banfieldpatin.backend.familias.vinculos.FamiliaDeportistaRepository;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;

/** Alcance en cada lectura; auditoria de acceso atomica, sin mutaciones de Documento. */
@Service
public class DocumentoConsultaService {

    private final DocumentoRepository documentos;
    private final DeportistaRepository deportistas;
    private final TutorRepository tutores;
    private final FamiliaDeportistaRepository vinculos;
    private final AuditoriaService auditoria;

    public DocumentoConsultaService(DocumentoRepository documentos, DeportistaRepository deportistas,
            TutorRepository tutores, FamiliaDeportistaRepository vinculos, AuditoriaService auditoria) {
        this.documentos = documentos;
        this.deportistas = deportistas;
        this.tutores = tutores;
        this.vinculos = vinculos;
        this.auditoria = auditoria;
    }

    private boolean esAdmin(UsuarioAutenticado usuario) {
        if (usuario.rol() != Rol.ADMIN && usuario.rol() != Rol.FAMILIA) {
            throw new ExcepcionNegocio(HttpStatus.FORBIDDEN, "ACCESO_DENEGADO", "Acceso denegado.");
        }
        return usuario.rol() == Rol.ADMIN;
    }

    // No readOnly: el acceso agrega un evento de auditoria que debe confirmar antes de entregar el DTO.
    @Transactional
    public Pagina<DocumentoMetadatos> deportista(UsuarioAutenticado usuario, UUID id, Pageable pagina,
            DatosSolicitud datos) {
        if (esAdmin(usuario)) {
            return respuesta(usuario, id, "DEPORTISTA", buscarDeportistaAdmin(usuario, id, pagina)
                    .map(DocumentoAdminMetadatos::sinVersion), datos);
        }
        if (usuario.familiaId() == null) {
            throw DeportistaAdminService.deportistaNoEncontrado();
        }
        Page<DocumentoMetadatos> resultado =
                documentos.deportistaFamilia(usuario.escuelaId(), id, usuario.familiaId(), pagina);
        if (resultado.isEmpty()
                && vinculos.deportistaEnAlcance(usuario.familiaId(), usuario.escuelaId(), id).isEmpty()) {
            throw DeportistaAdminService.deportistaNoEncontrado();
        }
        return respuesta(usuario, id, "DEPORTISTA", resultado, datos);
    }

    @Transactional
    public Pagina<DocumentoAdminMetadatos> deportistaAdmin(UsuarioAutenticado usuario, UUID id, Pageable pagina,
            DatosSolicitud datos) {
        if (!esAdmin(usuario)) {
            throw new ExcepcionNegocio(HttpStatus.FORBIDDEN, "ACCESO_DENEGADO", "Acceso denegado.");
        }
        return respuesta(usuario, id, "DEPORTISTA", buscarDeportistaAdmin(usuario, id, pagina), datos);
    }

    private Page<DocumentoAdminMetadatos> buscarDeportistaAdmin(UsuarioAutenticado usuario, UUID id, Pageable pagina) {
        Page<DocumentoAdminMetadatos> resultado = documentos.deportistaAdmin(usuario.escuelaId(), id, pagina);
        if (resultado.isEmpty() && !deportistas.existsByIdAndEscuelaId(id, usuario.escuelaId())) {
            throw DeportistaAdminService.deportistaNoEncontrado();
        }
        return resultado;
    }

    @Transactional
    public Pagina<DocumentoMetadatos> tutor(UsuarioAutenticado usuario, UUID id, Pageable pagina,
            DatosSolicitud datos) {
        if (esAdmin(usuario)) {
            return respuesta(usuario, id, "TUTOR", buscarTutorAdmin(usuario, id, pagina)
                    .map(DocumentoAdminMetadatos::sinVersion), datos);
        }
        if (usuario.familiaId() == null) {
            throw TutorAdminService.tutorNoEncontrado();
        }
        Page<DocumentoMetadatos> resultado =
                documentos.tutorFamilia(usuario.escuelaId(), id, usuario.familiaId(), pagina);
        if (resultado.isEmpty() && tutores.tutorEnAlcance(usuario.familiaId(), usuario.escuelaId(), id).isEmpty()) {
            throw TutorAdminService.tutorNoEncontrado();
        }
        return respuesta(usuario, id, "TUTOR", resultado, datos);
    }

    /** Listado administrativo de los documentos vigentes de un tutor, con la versión de cada uno (U19). */
    @Transactional
    public Pagina<DocumentoAdminMetadatos> tutorAdmin(UsuarioAutenticado usuario, UUID id, Pageable pagina,
            DatosSolicitud datos) {
        if (!esAdmin(usuario)) {
            throw new ExcepcionNegocio(HttpStatus.FORBIDDEN, "ACCESO_DENEGADO", "Acceso denegado.");
        }
        return respuesta(usuario, id, "TUTOR", buscarTutorAdmin(usuario, id, pagina), datos);
    }

    private Page<DocumentoAdminMetadatos> buscarTutorAdmin(UsuarioAutenticado usuario, UUID id, Pageable pagina) {
        Page<DocumentoAdminMetadatos> resultado = documentos.tutorAdmin(usuario.escuelaId(), id, pagina);
        if (resultado.isEmpty() && !tutores.existsByIdAndEscuelaId(id, usuario.escuelaId())) {
            throw TutorAdminService.tutorNoEncontrado();
        }
        return resultado;
    }

    private <T> Pagina<T> respuesta(UsuarioAutenticado usuario, UUID id, String tipo,
            Page<T> pagina, DatosSolicitud datos) {
        auditoria.registrar(new EventoAuditoria(usuario.escuelaId(), usuario.id(),
                AccionAuditoria.DOCUMENTOS_CONSULTADOS, tipo, id,
                Map.of("cantidad", pagina.getNumberOfElements(), "pagina", pagina.getNumber()), datos));
        return Pagina.de(pagina, documento -> documento);
    }
}
