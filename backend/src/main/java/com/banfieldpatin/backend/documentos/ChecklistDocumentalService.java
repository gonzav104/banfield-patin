package com.banfieldpatin.backend.documentos;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.deportistas.DeportistaAdminService;
import com.banfieldpatin.backend.deportistas.DeportistaRepository;
import com.banfieldpatin.backend.familias.vinculos.FamiliaDeportistaRepository;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;

/**
 * Checklist documental parcial de un deportista (U20), calculado en cada lectura desde sus documentos vigentes (no se guarda).
 * Mismo alcance que la consulta de documentos: ADMIN, un deportista de su escuela (incluso inactivo); FAMILIA, un deportista de
 * su familia activa con vínculo ACTIVO, y un único 404 para todo lo demás. Solo lectura y no audita, como la ficha y los
 * listados: no entrega archivos y un indicador en el panel no debe generar un evento por cada carga de pantalla.
 */
@Service
public class ChecklistDocumentalService {

    private final DocumentoRepository documentos;
    private final DeportistaRepository deportistas;
    private final FamiliaDeportistaRepository vinculos;

    public ChecklistDocumentalService(DocumentoRepository documentos, DeportistaRepository deportistas,
            FamiliaDeportistaRepository vinculos) {
        this.documentos = documentos;
        this.deportistas = deportistas;
        this.vinculos = vinculos;
    }

    @Transactional(readOnly = true)
    public ChecklistDocumental deportista(UsuarioAutenticado usuario, UUID id) {
        if (usuario.rol() == Rol.ADMIN) {
            List<DocumentoMetadatos> vigentes = documentos.requisitosAdmin(usuario.escuelaId(), id,
                    CatalogoRequisitosDocumentales.DNI_DEPORTISTA);
            if (vigentes.isEmpty() && !deportistas.existsByIdAndEscuelaId(id, usuario.escuelaId())) {
                throw DeportistaAdminService.deportistaNoEncontrado();
            }
            return CatalogoRequisitosDocumentales.calcular(id, vigentes);
        }
        if (usuario.rol() != Rol.FAMILIA) {
            throw new ExcepcionNegocio(HttpStatus.FORBIDDEN, "ACCESO_DENEGADO", "Acceso denegado.");
        }
        if (usuario.familiaId() == null
                || vinculos.deportistaEnAlcance(usuario.familiaId(), usuario.escuelaId(), id).isEmpty()) {
            throw DeportistaAdminService.deportistaNoEncontrado();
        }
        return CatalogoRequisitosDocumentales.calcular(id, documentos.requisitosFamilia(usuario.escuelaId(), id,
                usuario.familiaId(), CatalogoRequisitosDocumentales.DNI_DEPORTISTA));
    }
}
