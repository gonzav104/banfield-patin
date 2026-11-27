package com.banfieldpatin.backend.documentos.carga;

import com.banfieldpatin.backend.compartido.auditoria.*;
import com.banfieldpatin.backend.compartido.bd.LockTimeoutTransaccion;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.deportistas.*;
import com.banfieldpatin.backend.documentos.*;
import com.banfieldpatin.backend.documentos.apto.MetadatosApto;
import com.banfieldpatin.backend.documentos.almacen.DestinoPreparado;
import com.banfieldpatin.backend.familias.tutores.TutorAdminService;
import com.banfieldpatin.backend.familias.vinculos.FamiliaDeportistaRepository;
import com.banfieldpatin.backend.seguridad.VerificadorSesionVigente;
import com.banfieldpatin.backend.usuarios.Rol;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Todas las transacciones son cortas y separadas del parser, scanner y Storage. Nunca obtiene un
 * CAS nuevo para reintentar.
 */
final class OperacionesCargaJdbc {
  record Operacion(
      UUID id,
      long version,
      String estado,
      UUID atleta,
      String tipo,
      UUID temporada,
      DestinoPreparado destino,
      byte[] sha,
      String mime,
      long bytes,
      UUID predecesor,
      UUID tutor,
      MetadatosApto apto) {
    Operacion(
        UUID id,
        long version,
        String estado,
        UUID atleta,
        String tipo,
        UUID temporada,
        DestinoPreparado destino,
        byte[] sha,
        String mime,
        long bytes,
        UUID predecesor,
        UUID tutor) {
      this(id, version, estado, atleta, tipo, temporada, destino, sha, mime, bytes, predecesor, tutor, null);
    }

    Operacion(
        UUID id,
        long version,
        String estado,
        UUID atleta,
        String tipo,
        UUID temporada,
        DestinoPreparado destino,
        byte[] sha,
        String mime,
        long bytes) {
      this(id, version, estado, atleta, tipo, temporada, destino, sha, mime, bytes, null, null, null);
    }

    /** El dueño de la operación: un tutor (U19) o, si no, el deportista. */
    Propietario propietario() {
      return tutor != null ? Propietario.deTutor(tutor) : Propietario.deDeportista(atleta);
    }

    @Override
    public String toString() {
      return "OperacionCarga[privada]";
    }
  }

  /**
   * {@code retomada}: la clave ya existía en RESERVADA o ALMACENADA con la reserva vencida y esta
   * solicitud la adquirió por CAS; {@code operacion} lleva la versión renovada.
   */
  record Reserva(Operacion operacion, DocumentoMetadatos publicada, boolean retomada) {
    Reserva(Operacion operacion, DocumentoMetadatos publicada) {
      this(operacion, publicada, false);
    }
  }

  /**
   * Estados que un reintento puede retomar con la reserva vencida. TRANSFIRIENDO pasa a
   * RECONCILIANDO (resultado de la transferencia desconocido); RECONCILIANDO renueva su reserva para
   * reconciliar. Ninguno de los dos se publica: la existencia en Storage no prueba integridad.
   */
  private static final Set<String> RETOMABLES =
      Set.of("RESERVADA", "ALMACENADA", "TRANSFIRIENDO", "RECONCILIANDO");

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final Clock reloj;
  private final VerificadorSesionVigente sesiones;
  private final DeportistaRepository deportistas;
  private final FamiliaDeportistaRepository vinculos;
  private final AuditoriaService auditoria;
  private final CargaPropiedades p;

  OperacionesCargaJdbc(
      JdbcClient jdbc,
      PlatformTransactionManager manager,
      Clock reloj,
      VerificadorSesionVigente sesiones,
      DeportistaRepository deportistas,
      FamiliaDeportistaRepository vinculos,
      AuditoriaService auditoria,
      CargaPropiedades p) {
    this.jdbc = jdbc;
    this.reloj = reloj;
    this.sesiones = sesiones;
    this.deportistas = deportistas;
    this.vinculos = vinculos;
    this.auditoria = auditoria;
    this.p = p;
    tx = new TransactionTemplate(manager);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    tx.setTimeout(8);
  }

  void preautorizar(ContextoCarga c, UUID atleta) {
    tx.executeWithoutResult(s -> autorizar(c, atleta));
  }

  void preautorizarTutor(ContextoCarga c, UUID tutor) {
    tx.executeWithoutResult(s -> autorizarTutor(c, tutor));
  }

  private void vigente(ContextoCarga c) {
    if (c.expira() == null || !c.expira().isAfter(reloj.instant()))
      throw new ExcepcionNegocio(
          HttpStatus.UNAUTHORIZED, "SESION_NO_VIGENTE", "La sesión ya no está vigente.");
  }

  private void autorizar(ContextoCarga c, Propietario p) {
    if (p.esTutor()) autorizarTutor(c, p.tutor());
    else autorizar(c, p.deportista());
  }

  /**
   * Un usuario FAMILIA activo gestiona los documentos de los tutores ACTIVOS de su familia (activa) y su escuela (U19). No
   * acredita la identidad del titular del documento: {@code tutor.usuario_id} no se gestiona. Mismo orden de bloqueos que el
   * deportista: escuela, usuario y familia FOR SHARE y el dueño (aquí el tutor) FOR UPDATE; la edición administrativa del
   * tutor toma la familia FOR SHARE y luego el tutor, el mismo orden.
   */
  private void autorizarTutor(ContextoCarga c, UUID tutor) {
    autorizarFamilia(c);
    var u = c.usuario();
    if (jdbc.sql(
                "SELECT id FROM gestion_patin.tutor WHERE id=:t AND escuela_id=:e AND familia_id=:f AND activo"
                    + " FOR UPDATE")
            .param("t", tutor)
            .param("e", u.escuelaId())
            .param("f", u.familiaId())
            .query(UUID.class)
            .optional()
            .isEmpty()) throw TutorAdminService.tutorNoEncontrado();
    if (!sesiones.vigente(u.id(), u.escuelaId(), u.rol().name(), u.familiaId()))
      throw new ExcepcionNegocio(
          HttpStatus.UNAUTHORIZED, "SESION_NO_VIGENTE", "La sesión ya no está vigente.");
    vigente(c);
  }

  private void autorizar(ContextoCarga c, UUID atleta) {
    autorizarFamilia(c);
    var u = c.usuario();
    if (deportistas.bloquearParaVincular(u.escuelaId(), List.of(atleta)).isEmpty())
      throw DeportistaAdminService.deportistaNoEncontrado();
    if (jdbc.sql(
                "SELECT id FROM gestion_patin.familia_deportista WHERE escuela_id=:e AND"
                    + " familia_id=:f AND deportista_id=:d AND estado='ACTIVO' FOR SHARE")
            .param("e", u.escuelaId())
            .param("f", u.familiaId())
            .param("d", atleta)
            .query(UUID.class)
            .optional()
            .isEmpty()
        || vinculos.deportistaEnAlcance(u.familiaId(), u.escuelaId(), atleta).isEmpty())
      throw DeportistaAdminService.deportistaNoEncontrado();
    if (!sesiones.vigente(u.id(), u.escuelaId(), u.rol().name(), u.familiaId()))
      throw new ExcepcionNegocio(
          HttpStatus.UNAUTHORIZED, "SESION_NO_VIGENTE", "La sesión ya no está vigente.");
    vigente(c);
  }

  /** Sesión, escuela, usuario y familia activos (FOR SHARE), común a todo dueño. */
  private void autorizarFamilia(ContextoCarga c) {
    new LockTimeoutTransaccion(jdbc).aplicar(Duration.ofSeconds(2));
    vigente(c);
    var u = c.usuario();
    if (u.rol() != Rol.FAMILIA || u.familiaId() == null)
      throw new ExcepcionNegocio(HttpStatus.FORBIDDEN, "ACCESO_DENEGADO", "Acceso denegado.");
    if (jdbc.sql("SELECT id FROM gestion_patin.escuela WHERE id=:e AND activa FOR SHARE")
            .param("e", u.escuelaId())
            .query(UUID.class)
            .optional()
            .isEmpty()
        || jdbc.sql(
                "SELECT id FROM gestion_patin.usuario WHERE id=:u AND escuela_id=:e AND"
                    + " familia_id=:f AND rol='FAMILIA' AND activo FOR SHARE")
            .param("u", u.id())
            .param("e", u.escuelaId())
            .param("f", u.familiaId())
            .query(UUID.class)
            .optional()
            .isEmpty()
        || jdbc.sql(
                "SELECT id FROM gestion_patin.familia WHERE id=:f AND escuela_id=:e AND activa FOR"
                    + " SHARE")
            .param("f", u.familiaId())
            .param("e", u.escuelaId())
            .query(UUID.class)
            .optional()
            .isEmpty())
      throw new ExcepcionNegocio(
          HttpStatus.UNAUTHORIZED, "SESION_NO_VIGENTE", "La sesión ya no está vigente.");
  }

  /** Sin datos de apto: para cualquier tipo que no sea APTO_FISICO. */
  private static final MetadatosApto.Solicitado SIN_APTO = new MetadatosApto.Solicitado(null, null, null);

  Reserva reservar(
      ContextoCarga c,
      UUID atleta,
      TipoDocumento tipo,
      UUID temporada,
      UUID idem,
      DestinoPreparado destino,
      ArchivoPreparado a) {
    return reservar(c, Propietario.deDeportista(atleta), tipo, temporada, null, SIN_APTO, idem, destino, a);
  }

  /** Carga inicial de un deportista con los metadatos del apto (U21): exigidos si y solo si el tipo es APTO_FISICO. */
  Reserva reservar(
      ContextoCarga c,
      UUID atleta,
      TipoDocumento tipo,
      UUID temporada,
      MetadatosApto.Solicitado apto,
      UUID idem,
      DestinoPreparado destino,
      ArchivoPreparado a) {
    return reservar(c, Propietario.deDeportista(atleta), tipo, temporada, null, apto, idem, destino, a);
  }

  /** Carga inicial de un DNI de un tutor (U19): el tipo debe ser DNI_TUTOR_*; la temporada nunca aplica. */
  Reserva reservarTutor(
      ContextoCarga c, UUID tutor, TipoDocumento tipo, UUID idem, DestinoPreparado destino, ArchivoPreparado a) {
    return reservar(c, Propietario.deTutor(tutor), tipo, null, null, SIN_APTO, idem, destino, a);
  }

  /**
   * Reemplazo de un DNI OBSERVADO (U18). El tipo sale del predecesor; la temporada nunca aplica. Es la
   * misma reserva que la carga original, con el predecesor como parte de la identidad idempotente.
   */
  Reserva reservarReemplazo(
      ContextoCarga c, UUID atleta, UUID predecesor, UUID idem, DestinoPreparado destino, ArchivoPreparado a) {
    return reservar(c, Propietario.deDeportista(atleta), null, null, predecesor, SIN_APTO, idem, destino, a);
  }

  /** Reemplazo de un apto OBSERVADO (U21): la nueva versión trae sus propios metadatos y hereda la temporada del predecesor. */
  Reserva reservarReemplazo(
      ContextoCarga c,
      UUID atleta,
      UUID predecesor,
      MetadatosApto.Solicitado apto,
      UUID idem,
      DestinoPreparado destino,
      ArchivoPreparado a) {
    return reservar(c, Propietario.deDeportista(atleta), null, null, predecesor, apto, idem, destino, a);
  }

  /** Reemplazo de un DNI OBSERVADO de un tutor (U19). */
  Reserva reservarReemplazoTutor(
      ContextoCarga c, UUID tutor, UUID predecesor, UUID idem, DestinoPreparado destino, ArchivoPreparado a) {
    return reservar(c, Propietario.deTutor(tutor), null, null, predecesor, SIN_APTO, idem, destino, a);
  }

  private Reserva reservar(
      ContextoCarga c,
      Propietario prop,
      TipoDocumento tipoSolicitado,
      UUID temporadaSolicitada,
      UUID predecesor,
      MetadatosApto.Solicitado solicitado,
      UUID idem,
      DestinoPreparado destino,
      ArchivoPreparado a) {
    return tx.execute(
        s -> {
          autorizar(c, prop);
          var u = c.usuario();
          // Orden de bloqueos (V12, R3): ...dueño (autorizar) -> predecesor -> operación. Todo el que pueda
          // llegar a INSERT/PUBLICADA de un reemplazo toma el predecesor ANTES de la fila de la operación.
          final Predecesor anterior = predecesor == null ? null : bloquearPredecesor(u.escuelaId(), prop, predecesor);
          final TipoDocumento tipo = anterior == null ? tipoSolicitado : anterior.tipo();
          // Un reemplazo hereda la temporada de su predecesor (el trigger de contexto la hace inmutable en la serie).
          final UUID temporada = anterior == null ? temporadaSolicitada : anterior.temporada();
          final boolean esApto = tipo == TipoDocumento.APTO_FISICO;
          if (!permitidos(prop).contains(tipo)
              || temporada != null && tipo.name().startsWith("DNI_")
              || esApto && temporada == null) throw ErrorCarga.invalido();
          final MetadatosApto apto = esApto ? MetadatosApto.validar(solicitado, reloj) : sinMetadatos(solicitado);
          if (temporada != null
              && jdbc.sql("SELECT id FROM gestion_patin.temporada WHERE id=:t AND escuela_id=:e")
                  .param("t", temporada)
                  .param("e", u.escuelaId())
                  .query(UUID.class)
                  .optional()
                  .isEmpty()) throw ErrorCarga.invalido();
          UUID id = UUID.randomUUID();
          boolean nueva =
              jdbc.sql(
                      """
                      INSERT INTO gestion_patin.operacion_carga_documental(id,escuela_id,solicitante_id,deportista_id,tutor_id,tipo,
                        temporada_id,storage_bucket,objeto_id,idempotencia,sha256_archivo,mime_type,tamanio_bytes,reserva_hasta,
                        predecesor_id,fecha_emision,medico_nombre,medico_matricula)
                      VALUES(:id,:e,:u,CAST(:d AS uuid),CAST(:tu AS uuid),:tipo,CAST(:t AS uuid),:b,:o,:k,:sha,:m,:n,
                        clock_timestamp()+(:ms * interval '1 millisecond'),CAST(:pre AS uuid),
                        CAST(:fe AS date),CAST(:mn AS varchar),CAST(:mm AS varchar))
                      ON CONFLICT(escuela_id,solicitante_id,idempotencia) DO NOTHING RETURNING id
                      """)
                  .param("id", id)
                  .param("e", u.escuelaId())
                  .param("u", u.id())
                  .param("d", prop.deportista())
                  .param("tu", prop.tutor())
                  .param("tipo", tipo.name())
                  .param("t", temporada)
                  .param("b", destino.bucketInterno())
                  .param("o", destino.objetoIdInterno())
                  .param("k", idem)
                  .param("sha", a.sha)
                  .param("m", a.mime)
                  .param("n", a.bytes)
                  .param("ms", p.reserva().toMillis())
                  .param("pre", predecesor)
                  .param("fe", apto == null ? null : apto.fechaEmision())
                  .param("mn", apto == null ? null : apto.medicoNombre())
                  .param("mm", apto == null ? null : apto.medicoMatricula())
                  .query(UUID.class)
                  .optional()
                  .isPresent();
          Operacion op =
              jdbc.sql(
                      "SELECT * FROM gestion_patin.operacion_carga_documental WHERE escuela_id=:e"
                          + " AND solicitante_id=:u AND idempotencia=:k FOR UPDATE")
                  .param("e", u.escuelaId())
                  .param("u", u.id())
                  .param("k", idem)
                  .query(OperacionesCargaJdbc::leer)
                  .single();
          if (!Objects.equals(op.atleta(), prop.deportista())
              || !Objects.equals(op.tutor(), prop.tutor())
              || !op.tipo().equals(tipo.name())
              || !Objects.equals(op.temporada(), temporada)
              || op.bytes() != a.bytes
              || !op.mime().equals(a.mime)
              || !Objects.equals(op.predecesor(), predecesor)
              || !Objects.equals(op.apto(), apto)
              || !MessageDigest.isEqual(op.sha(), a.sha)) throw ErrorCarga.conflicto();
          // El manifiesto ya coincide: sólo entonces un reintento puede retomar la operación.
          boolean retomada = false;
          if (!nueva && !op.estado().equals("PUBLICADA")) {
            if (!RETOMABLES.contains(op.estado())) throw ErrorCarga.conflicto();
            if (!retomar(op)) throw reservaViva(op) ? ErrorCarga.enCurso() : ErrorCarga.conflicto();
            op =
                conEstado(op, op.estado().equals("TRANSFIRIENDO") ? "RECONCILIANDO" : op.estado());
            retomada = true;
          }
          if (nueva || retomada && op.estado().equals("RESERVADA")) {
            if (predecesor == null) comprobarPrimera(u.escuelaId(), prop, tipo.name(), temporada);
            else comprobarReemplazo(op, predecesor);
          }
          return new Reserva(op, nueva || retomada ? null : metadatos(op.id()), retomada);
        });
  }

  /**
   * Adquiere por CAS una reserva vencida, en el mismo estado y bajo el FOR UPDATE de la reserva. El
   * vencimiento se mide con el reloj de PostgreSQL, el mismo que usa el trigger. No supone que el
   * propietario anterior terminó: la versión incrementada hace fallar cualquier transición tardía
   * suya (iniciar, almacenada, publicar, cierre o reconciliación).
   */
  private boolean retomar(Operacion o) {
    // TRANSFIRIENDO vencida no puede renovarse en su estado (V6): pasa a RECONCILIANDO.
    String nuevo = o.estado().equals("TRANSFIRIENDO") ? "RECONCILIANDO" : o.estado();
    return jdbc.sql(
                "UPDATE gestion_patin.operacion_carga_documental SET estado=:nuevo,version=version+1,"
                    + "reserva_hasta=clock_timestamp()+(:ms * interval '1 millisecond')"
                    + " WHERE id=:id AND version=:v AND estado=:e AND reserva_hasta IS NOT NULL"
                    + " AND reserva_hasta <= clock_timestamp()")
            .param("nuevo", nuevo)
            .param("ms", p.reserva().toMillis())
            .param("id", o.id())
            .param("v", o.version())
            .param("e", o.estado())
            .update()
        == 1;
  }

  /**
   * Distingue, tras perder el CAS de {@link #retomar}, una reserva todavía activa (transitoria) de
   * cualquier otra causa. La fila sigue bloqueada por esta transacción: estado y versión no cambian,
   * así que el CAS sólo pudo fallar porque la reserva no venció. Un estado terminal nunca tiene
   * reserva (ck_operacion_terminal), por lo que no se clasifica como en curso.
   */
  private boolean reservaViva(Operacion o) {
    return jdbc.sql(
                "SELECT true FROM gestion_patin.operacion_carga_documental WHERE id=:id"
                    + " AND estado NOT IN ('PUBLICADA','CERRADA') AND reserva_hasta IS NOT NULL")
            .param("id", o.id())
            .query(Boolean.class)
            .optional()
            .isPresent();
  }

  /** Tipos que un dueño puede cargar. U19 limita al tutor a los DNI (frente y dorso). */
  private static Set<TipoDocumento> permitidos(Propietario prop) {
    return prop.esTutor()
        ? EnumSet.of(TipoDocumento.DNI_TUTOR_FRENTE, TipoDocumento.DNI_TUTOR_DORSO)
        : EnumSet.of(
            TipoDocumento.DNI_DEPORTISTA_FRENTE,
            TipoDocumento.DNI_DEPORTISTA_DORSO,
            TipoDocumento.SOLICITUD_LICENCIA,
            TipoDocumento.CERTIFICADO_WADA,
            TipoDocumento.APTO_FISICO,
            TipoDocumento.OTRO);
  }

  /** Tipo y temporada del predecesor ya bloqueado: el reemplazo hereda ambos. */
  private record Predecesor(TipoDocumento tipo, UUID temporada) {}

  /**
   * Bloquea el predecesor y devuelve su tipo y su temporada. Solo los DNI del deportista, de esta escuela y de este
   * deportista (y, desde U21, su apto físico) son reemplazables: cualquier otro caso es el mismo 404 (sin distinguir ajeno de
   * inexistente).
   */
  private Predecesor bloquearPredecesor(UUID escuela, Propietario prop, UUID predecesor) {
    return jdbc.sql(
            "SELECT tipo, temporada_id FROM gestion_patin.documento WHERE id=:p AND escuela_id=:e AND "
                + prop.columna()
                + "=:d AND tipo IN ("
                + (prop.esTutor()
                    ? "'DNI_TUTOR_FRENTE','DNI_TUTOR_DORSO'"
                    : "'DNI_DEPORTISTA_FRENTE','DNI_DEPORTISTA_DORSO','APTO_FISICO'")
                + ") FOR UPDATE")
        .param("p", predecesor)
        .param("e", escuela)
        .param("d", prop.id())
        .query((r, n) -> new Predecesor(TipoDocumento.valueOf(r.getString(1)), r.getObject(2, UUID.class)))
        .optional()
        .orElseThrow(ErrorCarga::documentoNoEncontrado);
  }

  /** Los datos del apto solo existen para APTO_FISICO: enviarlos con otro tipo es un pedido inválido (sin eco de valores). */
  private static MetadatosApto sinMetadatos(MetadatosApto.Solicitado solicitado) {
    if (!solicitado.vacio())
      throw new ExcepcionNegocio(
          HttpStatus.BAD_REQUEST,
          "VALIDACION",
          "Hay datos inválidos en la solicitud.",
          java.util.List.of(
              new com.banfieldpatin.backend.compartido.error.DetalleError(
                  "apto", "Los datos del apto solo corresponden al apto físico.")));
    return null;
  }

  /**
   * Con el predecesor bloqueado: un predecesor que ya no es vigente (ya reemplazado) o no está OBSERVADO es un
   * conflicto definitivo; otra operación de OTRA clave con la reserva todavía vigente es transitoria. Una
   * operación cerrada, abandonada o con la reserva vencida no bloquea: no hay índice único sobre ellas.
   */
  private void comprobarReemplazo(Operacion op, UUID predecesor) {
    var doc =
        jdbc.sql("SELECT estado, vigente FROM gestion_patin.documento WHERE id=:p")
            .param("p", predecesor)
            .query((r, n) -> new boolean[] {r.getString(1).equals("OBSERVADO"), r.getBoolean(2)})
            .single();
    if (!doc[0] || !doc[1]) throw ErrorCarga.conflicto();
    if (jdbc.sql(
            "SELECT 1 FROM gestion_patin.operacion_carga_documental WHERE predecesor_id=:p AND id<>:o"
                + " AND estado NOT IN ('PUBLICADA','CERRADA') AND reserva_hasta > clock_timestamp()")
        .param("p", predecesor)
        .param("o", op.id())
        .query(Integer.class)
        .optional()
        .isPresent()) throw ErrorCarga.enCurso();
  }

  Operacion iniciar(ContextoCarga c, Operacion o) {
    return tx.execute(
        s -> {
          autorizar(c, o.propietario());
          cambiar(o, "TRANSFIRIENDO", false);
          return conEstado(o, "TRANSFIRIENDO");
        });
  }

  Operacion almacenada(Operacion o) {
    return tx.execute(
        s -> {
          cambiar(o, "ALMACENADA", false);
          return conEstado(o, "ALMACENADA");
        });
  }

  DocumentoMetadatos publicar(ContextoCarga c, Operacion o, DatosSolicitud datos) {
    return tx.execute(
        s -> {
          autorizar(c, o.propietario());
          var u = c.usuario();
          if (o.predecesor() == null) {
            comprobarPrimera(u.escuelaId(), o.propietario(), o.tipo(), o.temporada());
            // El id de Documento y la raíz de su serie son la identidad durable de la operación.
            jdbc.sql(
                    """
                    INSERT INTO gestion_patin.documento(id,escuela_id,deportista_id,tutor_id,temporada_id,tipo,nombre_original,
                        storage_bucket,storage_key,mime_type,tamanio_bytes,estado,vigente,cargado_por,serie_id,version,
                        fecha_emision,medico_nombre,medico_matricula)
                    VALUES(:id,:e,CAST(:d AS uuid),CAST(:tu AS uuid),CAST(:t AS uuid),:tipo,'documento',:b,:k,:m,:n,'CARGADO',true,:u,:id,0,
                        CAST(:fe AS date),CAST(:mn AS varchar),CAST(:mm AS varchar))
                    """)
                .param("id", o.id())
                .param("e", u.escuelaId())
                .param("d", o.atleta())
                .param("tu", o.tutor())
                .param("t", o.temporada())
                .param("tipo", o.tipo())
                .param("b", o.destino().bucketInterno())
                .param("k", o.destino().objetoIdInterno().toString())
                .param("m", o.mime())
                .param("n", o.bytes())
                .param("u", u.id())
                .param("fe", o.apto() == null ? null : o.apto().fechaEmision())
                .param("mn", o.apto() == null ? null : o.apto().medicoNombre())
                .param("mm", o.apto() == null ? null : o.apto().medicoMatricula())
                .update();
            cambiar(o, "PUBLICADA", true);
            auditoria.registrar(
                new EventoAuditoria(
                    u.escuelaId(),
                    u.id(),
                    AccionAuditoria.DOCUMENTO_CARGADO,
                    "DOCUMENTO",
                    o.id(),
                    Map.of(),
                    datos));
          } else {
            publicarReemplazo(u, o, datos);
          }
          vigente(c);
          return metadatos(o.id());
        });
  }

  /**
   * Publicación atómica de un reemplazo: bloquea y revalida el predecesor ANTES de tocar la operación, retira su
   * vigencia ANTES de insertar al sucesor (los índices únicos de vigencia se validan de inmediato), hereda la serie,
   * cierra la operación y audita, todo en esta transacción. El predecesor conserva estado y motivo de observación.
   */
  private void publicarReemplazo(
      com.banfieldpatin.backend.seguridad.UsuarioAutenticado u, Operacion o, DatosSolicitud datos) {
    var anterior =
        jdbc.sql(
                "SELECT estado, vigente, serie_id FROM gestion_patin.documento WHERE id=:p AND escuela_id=:e"
                    + " AND "
                    + o.propietario().columna()
                    + "=:d FOR UPDATE")
            .param("p", o.predecesor())
            .param("e", u.escuelaId())
            .param("d", o.propietario().id())
            .query((r, n) -> new Object[] {r.getString(1), r.getBoolean(2), r.getObject(3, UUID.class)})
            .optional()
            .orElseThrow(ErrorCarga::conflicto);
    if (!"OBSERVADO".equals(anterior[0]) || !(boolean) anterior[1]) throw ErrorCarga.conflicto();
    if (jdbc.sql(
                "UPDATE gestion_patin.documento SET vigente=false,version=version+1 WHERE id=:p AND vigente")
            .param("p", o.predecesor())
            .update()
        != 1) throw ErrorCarga.conflicto();
    jdbc.sql(
            """
            INSERT INTO gestion_patin.documento(id,escuela_id,deportista_id,tutor_id,temporada_id,tipo,nombre_original,storage_bucket,
                storage_key,mime_type,tamanio_bytes,estado,vigente,cargado_por,serie_id,documento_anterior_id,version,
                fecha_emision,medico_nombre,medico_matricula)
            VALUES(:id,:e,CAST(:d AS uuid),CAST(:tu AS uuid),CAST(:t AS uuid),:tipo,'documento',:b,:k,:m,:n,'CARGADO',true,:u,:serie,:p,0,
                CAST(:fe AS date),CAST(:mn AS varchar),CAST(:mm AS varchar))
            """)
        .param("id", o.id())
        .param("e", u.escuelaId())
        .param("d", o.atleta())
        .param("tu", o.tutor())
        .param("tipo", o.tipo())
        .param("b", o.destino().bucketInterno())
        .param("k", o.destino().objetoIdInterno().toString())
        .param("m", o.mime())
        .param("n", o.bytes())
        .param("u", u.id())
        .param("serie", anterior[2])
        .param("p", o.predecesor())
        .param("t", o.temporada())
        .param("fe", o.apto() == null ? null : o.apto().fechaEmision())
        .param("mn", o.apto() == null ? null : o.apto().medicoNombre())
        .param("mm", o.apto() == null ? null : o.apto().medicoMatricula())
        .update();
    cambiar(o, "PUBLICADA", true);
    auditoria.registrar(
        new EventoAuditoria(
            u.escuelaId(),
            u.id(),
            AccionAuditoria.DOCUMENTO_REEMPLAZADO,
            "DOCUMENTO",
            o.id(),
            Map.of("documentoAnteriorId", o.predecesor().toString()),
            datos));
  }

  void cerrarAntesDeTransferir(Operacion o) {
    try {
      tx.executeWithoutResult(s -> cambiar(o, "CERRADA", true));
    } catch (RuntimeException ex) {
      /* Se conserva el registro; no se afirma cierre si falló el commit. */
      DiagnosticoCarga.fallo(o.id(), "cierre", ex);
    }
  }

  /**
   * Libera la reserva que esta solicitud mantiene, sin cambiar el estado, para que un reintento
   * pueda retomarla de inmediato. Si la versión ya no es la propia (otro trabajador, o un commit que
   * sí se confirmó), el CAS falla y no se toca la operación.
   */
  void liberar(Operacion o) {
    try {
      tx.executeWithoutResult(
          s -> {
            new LockTimeoutTransaccion(jdbc).aplicar(Duration.ofSeconds(2));
            int filas =
                jdbc.sql(
                        "UPDATE gestion_patin.operacion_carga_documental SET version=version+1,"
                            + "reserva_hasta=clock_timestamp() WHERE id=:id AND version=:v AND"
                            + " estado=:e AND reserva_hasta > clock_timestamp()")
                    .param("id", o.id())
                    .param("v", o.version())
                    .param("e", o.estado())
                    .update();
            if (filas != 1) throw ErrorCarga.conflicto();
          });
    } catch (RuntimeException ex) {
      DiagnosticoCarga.fallo(o.id(), "liberacion", ex);
    }
  }

  /**
   * Cierra una operación ALMACENADA cuya publicación falló por un conflicto permanente (sin acceso o
   * DNI ya vigente). V6 no admite ALMACENADA→CERRADA: pasa por RECONCILIANDO en la misma
   * transacción y con la reserva propia. El objeto confirmado queda huérfano, nunca publicado.
   */
  boolean cerrarConfirmada(Operacion o) {
    try {
      tx.executeWithoutResult(
          s -> {
            cambiar(o, "RECONCILIANDO", false);
            cambiar(conEstado(o, "RECONCILIANDO"), "CERRADA", true);
          });
      return true;
    } catch (RuntimeException ex) {
      DiagnosticoCarga.fallo(o.id(), "cierre", ex);
      return false;
    }
  }

  /** Cierra por CAS una operación RECONCILIANDO cuya reserva mantiene esta solicitud. */
  void cerrarReconciliada(Operacion o) {
    tx.executeWithoutResult(s -> cambiar(o, "CERRADA", true));
  }

  void incierta(Operacion o) {
    try {
      tx.executeWithoutResult(s -> cambiar(o, "RECONCILIANDO", false));
    } catch (RuntimeException ex) {
      /* No recarga versión ni borra; incluso PUBLICADA puede haber confirmado un commit incierto. */
      DiagnosticoCarga.fallo(o.id(), "reconciliacion", ex);
    }
  }

  private void comprobarPrimera(UUID escuela, Propietario prop, String tipo, UUID temporada) {
    // Una sola serie vigente de apto por deportista y temporada (V17 lo exige también con un índice único parcial).
    if (tipo.equals("APTO_FISICO")
        && jdbc.sql(
                "SELECT id FROM gestion_patin.documento WHERE escuela_id=:e AND deportista_id=:d AND"
                    + " temporada_id=:t AND tipo='APTO_FISICO' AND vigente")
            .param("e", escuela)
            .param("d", prop.id())
            .param("t", temporada)
            .query(UUID.class)
            .optional()
            .isPresent()) throw ErrorCarga.conflicto();
    if (tipo.startsWith("DNI_")
        && jdbc.sql(
                "SELECT id FROM gestion_patin.documento WHERE escuela_id=:e AND "
                    + prop.columna()
                    + "=:d AND tipo=:t AND vigente")
            .param("e", escuela)
            .param("d", prop.id())
            .param("t", tipo)
            .query(UUID.class)
            .optional()
            .isPresent()) throw ErrorCarga.conflicto();
  }

  private void cambiar(Operacion o, String estado, boolean terminal) {
    new LockTimeoutTransaccion(jdbc).aplicar(Duration.ofSeconds(2));
    String reserva = terminal ? "NULL" : "clock_timestamp()+(:ms * interval '1 millisecond')";
    int filas =
        jdbc.sql(
                "UPDATE gestion_patin.operacion_carga_documental SET"
                    + " estado=:nuevo,version=version+1,reserva_hasta="
                    + reserva
                    + ",documento_id="
                    + (estado.equals("PUBLICADA") ? "id" : "NULL")
                    + " WHERE id=:id AND version=:v AND estado=:anterior")
            .param("nuevo", estado)
            .param("ms", p.reserva().toMillis())
            .param("id", o.id())
            .param("v", o.version())
            .param("anterior", o.estado())
            .update();
    if (filas != 1) throw ErrorCarga.conflicto();
  }

  private static Operacion conEstado(Operacion o, String estado) {
    return new Operacion(
        o.id(),
        o.version() + 1,
        estado,
        o.atleta(),
        o.tipo(),
        o.temporada(),
        o.destino(),
        o.sha(),
        o.mime(),
        o.bytes(),
        o.predecesor(),
        o.tutor(),
        o.apto());
  }

  private static Operacion leer(ResultSet r, int n) throws SQLException {
    return new Operacion(
        r.getObject("id", UUID.class),
        r.getLong("version"),
        r.getString("estado"),
        r.getObject("deportista_id", UUID.class),
        r.getString("tipo"),
        r.getObject("temporada_id", UUID.class),
        DestinoPreparado.desdeRegistro(
            r.getString("storage_bucket"), r.getObject("objeto_id", UUID.class)),
        r.getBytes("sha256_archivo"),
        r.getString("mime_type"),
        r.getLong("tamanio_bytes"),
        r.getObject("predecesor_id", UUID.class),
        r.getObject("tutor_id", UUID.class),
        r.getObject("fecha_emision", java.time.LocalDate.class) == null
            ? null
            : new MetadatosApto(
                r.getObject("fecha_emision", java.time.LocalDate.class),
                r.getString("medico_nombre"),
                r.getString("medico_matricula")));
  }

  private DocumentoMetadatos metadatos(UUID id) {
    return jdbc.sql(
            "SELECT"
                + " id,tipo,estado,temporada_id,creado_en,vigente,fecha_vencimiento,motivo_observacion"
                + " FROM gestion_patin.documento WHERE id=:id")
        .param("id", id)
        .query(
            (r, n) ->
                new DocumentoMetadatos(
                    id,
                    TipoDocumento.valueOf(r.getString("tipo")),
                    EstadoDocumento.valueOf(r.getString("estado")),
                    r.getObject("temporada_id", UUID.class),
                    r.getTimestamp("creado_en").toInstant(),
                    r.getBoolean("vigente"),
                    r.getObject("fecha_vencimiento", java.time.LocalDate.class),
                    r.getString("motivo_observacion")))
        .single();
  }
}
