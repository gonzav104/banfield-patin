package com.banfieldpatin.backend.documentos.carga;

import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.documentos.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.*;

/**
 * Carga y reemplazo familiar de los DNI de un tutor (U19). Misma seguridad previa al cuerpo
 * ({@link PreautorizacionCargaFiltro}), CSRF e idempotencia que la carga del deportista; el contrato de esta no cambia.
 * Permiso por familia: no acredita la identidad del titular del documento.
 */
@RestController
@RequestMapping("/api/familia/tutores/{id}/documentos")
public class CargaTutorDocumentalController {
  private static final Set<TipoDocumento> TIPOS =
      EnumSet.of(TipoDocumento.DNI_TUTOR_FRENTE, TipoDocumento.DNI_TUTOR_DORSO);
  private final ObjectProvider<CargaDocumentalService> servicios;

  public CargaTutorDocumentalController(ObjectProvider<CargaDocumentalService> servicios) {
    this.servicios = servicios;
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<DocumentoMetadatos> cargar(
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID idempotencia,
      @RequestParam TipoDocumento tipo,
      @RequestPart("archivo") MultipartFile archivo,
      HttpServletRequest request)
      throws Exception {
    var servicio = servicios.getIfAvailable();
    if (servicio == null) throw ErrorCarga.indisponible();
    var contexto = contexto(request);
    validarPartes(request, Set.of("archivo", "tipo"), Set.of("archivo", "tipo"));
    if (!TIPOS.contains(tipo)) throw ErrorCarga.invalido();
    var resultado =
        servicio.cargarTutor(contexto, id, tipo, idempotencia, archivo, DatosSolicitud.de(request));
    return respuesta(resultado);
  }

  @PostMapping(path = "/{documentoId}/reemplazo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<DocumentoMetadatos> reemplazar(
      @PathVariable UUID id,
      @PathVariable UUID documentoId,
      @RequestHeader("Idempotency-Key") UUID idempotencia,
      @RequestPart("archivo") MultipartFile archivo,
      HttpServletRequest request)
      throws Exception {
    var servicio = servicios.getIfAvailable();
    if (servicio == null) throw ErrorCarga.indisponible();
    var contexto = contexto(request);
    validarPartes(request, Set.of("archivo"), Set.of("archivo"));
    var resultado =
        servicio.reemplazarTutor(
            contexto, id, documentoId, idempotencia, archivo, DatosSolicitud.de(request));
    return respuesta(resultado);
  }

  private static ContextoCarga contexto(HttpServletRequest request) {
    if (!(request.getAttribute(PreautorizacionCargaFiltro.CONTEXTO) instanceof ContextoCarga contexto))
      throw ErrorCarga.indisponible();
    return contexto;
  }

  /** Lista cerrada de partes, cada una exactamente una vez, y una única cabecera Idempotency-Key. */
  private static void validarPartes(HttpServletRequest request, Set<String> permitidas, Set<String> exigidas)
      throws Exception {
    var conteos = new HashMap<String, Integer>();
    for (var part : request.getParts()) conteos.merge(part.getName(), 1, Integer::sum);
    if (!permitidas.containsAll(conteos.keySet())
        || !exigidas.stream().allMatch(n -> conteos.getOrDefault(n, 0) == 1)
        || Collections.list(request.getHeaders("Idempotency-Key")).size() != 1)
      throw ErrorCarga.invalido();
  }

  private static ResponseEntity<DocumentoMetadatos> respuesta(CargaDocumentalService.Resultado resultado) {
    return ResponseEntity.status(resultado.repetida() ? HttpStatus.OK : HttpStatus.CREATED)
        .body(resultado.documento());
  }
}
