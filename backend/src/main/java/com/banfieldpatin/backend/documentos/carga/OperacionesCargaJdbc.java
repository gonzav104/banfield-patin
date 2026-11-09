package com.banfieldpatin.backend.documentos.carga;

import com.banfieldpatin.backend.compartido.auditoria.*;
import com.banfieldpatin.backend.compartido.bd.LockTimeoutTransaccion;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.deportistas.*;
import com.banfieldpatin.backend.documentos.*;
import com.banfieldpatin.backend.documentos.almacen.DestinoPreparado;
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
      long bytes) {
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

  /** Estados que un reintento puede retomar; TRANSFIRIENDO y RECONCILIANDO requieren reconciliar. */
  private static final Set<String> RETOMABLES = Set.of("RESERVADA", "ALMACENADA");

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

  private void vigente(ContextoCarga c) {
    if (c.expira() == null || !c.expira().isAfter(reloj.instant()))
      throw new ExcepcionNegocio(
          HttpStatus.UNAUTHORIZED, "SESION_NO_VIGENTE", "La sesión ya no está vigente.");
  }

  private void autorizar(ContextoCarga c, UUID atleta) {
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

  Reserva reservar(
      ContextoCarga c,
      UUID atleta,
      TipoDocumento tipo,
      UUID temporada,
      UUID idem,
      DestinoPreparado destino,
      ArchivoPreparado a) {
    return tx.execute(
        s -> {
          autorizar(c, atleta);
          var u = c.usuario();
          if (!EnumSet.of(
                      TipoDocumento.DNI_DEPORTISTA_FRENTE,
                      TipoDocumento.DNI_DEPORTISTA_DORSO,
                      TipoDocumento.SOLICITUD_LICENCIA,
                      TipoDocumento.CERTIFICADO_WADA,
                      TipoDocumento.OTRO)
                  .contains(tipo)
              || temporada != null && tipo.name().startsWith("DNI_")) throw ErrorCarga.invalido();
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
                      INSERT INTO gestion_patin.operacion_carga_documental(id,escuela_id,solicitante_id,deportista_id,tipo,temporada_id,
                        storage_bucket,objeto_id,idempotencia,sha256_archivo,mime_type,tamanio_bytes,reserva_hasta)
                      VALUES(:id,:e,:u,:d,:tipo,CAST(:t AS uuid),:b,:o,:k,:sha,:m,:n,clock_timestamp()+(:ms * interval '1 millisecond'))
                      ON CONFLICT(escuela_id,solicitante_id,idempotencia) DO NOTHING RETURNING id
                      """)
                  .param("id", id)
                  .param("e", u.escuelaId())
                  .param("u", u.id())
                  .param("d", atleta)
                  .param("tipo", tipo.name())
                  .param("t", temporada)
                  .param("b", destino.bucketInterno())
                  .param("o", destino.objetoIdInterno())
                  .param("k", idem)
                  .param("sha", a.sha)
                  .param("m", a.mime)
                  .param("n", a.bytes)
                  .param("ms", p.reserva().toMillis())
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
          if (!Objects.equals(op.atleta(), atleta)
              || !op.tipo().equals(tipo.name())
              || !Objects.equals(op.temporada(), temporada)
              || op.bytes() != a.bytes
              || !op.mime().equals(a.mime)
              || !MessageDigest.isEqual(op.sha(), a.sha)) throw ErrorCarga.conflicto();
          // El manifiesto ya coincide: sólo entonces un reintento puede retomar la operación.
          boolean retomada = false;
          if (!nueva && !op.estado().equals("PUBLICADA")) {
            if (!RETOMABLES.contains(op.estado()) || !retomar(op)) throw ErrorCarga.conflicto();
            op = conEstado(op, op.estado());
            retomada = true;
          }
          if (nueva || retomada && op.estado().equals("RESERVADA"))
            comprobarPrimera(u.escuelaId(), atleta, tipo.name());
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
    return jdbc.sql(
                "UPDATE gestion_patin.operacion_carga_documental SET version=version+1,"
                    + "reserva_hasta=clock_timestamp()+(:ms * interval '1 millisecond')"
                    + " WHERE id=:id AND version=:v AND estado=:e AND reserva_hasta IS NOT NULL"
                    + " AND reserva_hasta <= clock_timestamp()")
            .param("ms", p.reserva().toMillis())
            .param("id", o.id())
            .param("v", o.version())
            .param("e", o.estado())
            .update()
        == 1;
  }

  Operacion iniciar(ContextoCarga c, Operacion o) {
    return tx.execute(
        s -> {
          autorizar(c, o.atleta());
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
          autorizar(c, o.atleta());
          var u = c.usuario();
          comprobarPrimera(u.escuelaId(), o.atleta(), o.tipo());
          // El id de Documento y la raíz de su serie son la identidad durable de la operación.
          jdbc.sql(
                  """
                  INSERT INTO gestion_patin.documento(id,escuela_id,deportista_id,temporada_id,tipo,nombre_original,
                      storage_bucket,storage_key,mime_type,tamanio_bytes,estado,vigente,cargado_por,serie_id,version)
                  VALUES(:id,:e,:d,CAST(:t AS uuid),:tipo,'documento',:b,:k,:m,:n,'CARGADO',true,:u,:id,0)
                  """)
              .param("id", o.id())
              .param("e", u.escuelaId())
              .param("d", o.atleta())
              .param("t", o.temporada())
              .param("tipo", o.tipo())
              .param("b", o.destino().bucketInterno())
              .param("k", o.destino().objetoIdInterno().toString())
              .param("m", o.mime())
              .param("n", o.bytes())
              .param("u", u.id())
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
          vigente(c);
          return metadatos(o.id());
        });
  }

  void cerrarAntesDeTransferir(Operacion o) {
    try {
      tx.executeWithoutResult(s -> cambiar(o, "CERRADA", true));
    } catch (RuntimeException ex) {
      /* Se conserva el registro; no se afirma cierre si falló el commit. */
      DiagnosticoCarga.fallo(o.id(), "cierre", ex);
    }
  }

  void incierta(Operacion o) {
    try {
      tx.executeWithoutResult(s -> cambiar(o, "RECONCILIANDO", false));
    } catch (RuntimeException ex) {
      /* No recarga versión ni borra; incluso PUBLICADA puede haber confirmado un commit incierto. */
      DiagnosticoCarga.fallo(o.id(), "reconciliacion", ex);
    }
  }

  private void comprobarPrimera(UUID escuela, UUID atleta, String tipo) {
    if (tipo.startsWith("DNI_")
        && jdbc.sql(
                "SELECT id FROM gestion_patin.documento WHERE escuela_id=:e AND deportista_id=:d"
                    + " AND tipo=:t AND vigente")
            .param("e", escuela)
            .param("d", atleta)
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
        o.bytes());
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
        r.getLong("tamanio_bytes"));
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
