package com.banfieldpatin.backend.documentos.carga;

import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.documentos.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.*;

@RestController
@RequestMapping("/api/familia/deportistas/{id}/documentos")
public class CargaDocumentalController {
  private final ObjectProvider<CargaDocumentalService> servicios;

  public CargaDocumentalController(ObjectProvider<CargaDocumentalService> servicios) {
    this.servicios = servicios;
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<DocumentoMetadatos> cargar(
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID idempotencia,
      @RequestParam TipoDocumento tipo,
      @RequestParam(required = false) UUID temporadaId,
      @RequestPart("archivo") MultipartFile archivo,
      HttpServletRequest request)
      throws Exception {
    var servicio = servicios.getIfAvailable();
    if (servicio == null) throw ErrorCarga.indisponible();
    if (!(request.getAttribute(PreautorizacionCargaFiltro.CONTEXTO)
        instanceof ContextoCarga contexto)) throw ErrorCarga.indisponible();
    var conteos = new HashMap<String, Integer>();
    for (var part : request.getParts()) conteos.merge(part.getName(), 1, Integer::sum);
    if (!Set.of("archivo", "tipo", "temporadaId").containsAll(conteos.keySet())
        || conteos.getOrDefault("archivo", 0) != 1
        || conteos.getOrDefault("tipo", 0) != 1
        || conteos.getOrDefault("temporadaId", 0) > 1
        || Collections.list(request.getHeaders("Idempotency-Key")).size() != 1)
      throw ErrorCarga.invalido();
    var resultado =
        servicio.cargar(
            contexto, id, tipo, temporadaId, idempotencia, archivo, DatosSolicitud.de(request));
    return ResponseEntity.status(resultado.repetida() ? HttpStatus.OK : HttpStatus.CREATED)
        .body(resultado.documento());
  }
}
