package com.banfieldpatin.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.Pagina;
import com.banfieldpatin.backend.documentos.*;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;

@PruebaDb
@Import({ConfigFamiliasAdminDb.class,DocumentoConsultaService.class})
@TestPropertySource(properties={"spring.datasource.hikari.maximum-pool-size=3","spring.datasource.hikari.minimum-idle=1","spring.jpa.properties.hibernate.generate_statistics=true"})
class DocumentosConsultaDbTest extends BaseVinculosDb {
 @Autowired DocumentoConsultaService documentos;
 UUID familiaA,familiaOtra,deportistaA,tutorA,vinculoA;
 UsuarioAutenticado familiar;
 @BeforeEach void prepararDocumentos() {
  familiaA=datos.familia(escuelaA,"Familia ficticia A",true);
  familiaOtra=datos.familia(escuelaA,"Familia ficticia B",true);
  familiar=new UsuarioAutenticado(datos.usuarioFamilia(escuelaA,familiaA,"docs@example.invalid"),escuelaA,familiaA,Rol.FAMILIA);
  deportistaA=deportista("Prueba");
  tutorA=datos.tutor(escuelaA,familiaA,"Tutor ficticio","Prueba");
  vinculoA=datos.vinculo(escuelaA,familiaA,deportistaA,"ACTIVO",false,adminId);
 }
 @AfterEach void limpiarDocumentos() {
  for(UUID escuela:List.of(escuelaA,escuelaB)) jdbc.sql("DELETE FROM gestion_patin.documento WHERE escuela_id=:e").param("e",escuela).update();
 }
 UUID documento(UUID escuela,UUID propietario,boolean tutor,boolean vigente,String estado) {
  return jdbc.sql("""
   INSERT INTO gestion_patin.documento(escuela_id,deportista_id,tutor_id,tipo,nombre_original,
    storage_key,mime_type,tamanio_bytes,estado,vigente,cargado_por,motivo_observacion)
   VALUES(:e,CAST(:d AS uuid),CAST(:t AS uuid),'OTRO','NOMBRE_PRIVADO.pdf',:key,'application/pdf',100,
    :estado,:vigente,:autor,'MOTIVO_FICTICIO') RETURNING id
   """).param("e",escuela).param("d",tutor?null:propietario).param("t",tutor?propietario:null)
   .param("key","RUTA_PRIVADA/"+UUID.randomUUID()).param("estado",estado).param("vigente",vigente)
   .param("autor",escuela.equals(escuelaA)?adminId:adminDeB.id()).query(UUID.class).single();
 }
 Pagina<DocumentoMetadatos> leer(UsuarioAutenticado u,UUID id,boolean tutor,int page,int size) {
  return tutor?documentos.tutor(u,id,Pagina.pedir(page,size),DATOS):documentos.deportista(u,id,Pagina.pedir(page,size),DATOS);
 }
 void denegado(UsuarioAutenticado u,UUID id,boolean tutor) {
  String documentosAntes=instantaneaDocumentos();
  long antes=contar("auditoria",u.escuelaId());
  assertThatThrownBy(()->leer(u,id,tutor,0,1)).isInstanceOfSatisfying(ExcepcionNegocio.class,
   e->{assertThat(e.getEstado().value()).isEqualTo(404);assertThat(e.getCodigo()).isEqualTo(tutor?"TUTOR_NO_ENCONTRADO":"DEPORTISTA_NO_ENCONTRADO");});
  assertThat(contar("auditoria",u.escuelaId())).isEqualTo(antes);
  assertThat(instantaneaDocumentos()).isEqualTo(documentosAntes);
 }
 String instantaneaDocumentos() {
  return jdbc.sql("SELECT coalesce(json_agg(d ORDER BY id)::text,'[]') FROM gestion_patin.documento d WHERE escuela_id IN (:a,:b)")
   .param("a",escuelaA).param("b",escuelaB).query(String.class).single();
 }
 @Test void consultaAmbosPropietariosSinMezclarEscuelasFamiliasNiHistorial() {
  var d=documento(escuelaA,deportistaA,false,true,"CARGADO");
  var t=documento(escuelaA,tutorA,true,true,"OBSERVADO");
  documento(escuelaA,deportistaA,false,false,"APROBADO");
  var ajeno=deportista("Ajeno");documento(escuelaA,ajeno,false,true,"APROBADO");
  var tutorAjeno=datos.tutor(escuelaA,familiaOtra,"Ajeno","Ficticio");documento(escuelaA,tutorAjeno,true,true,"CARGADO");
  var familiaB=datos.familia(escuelaB,"Otra escuela",true);
  var dB=deportista(escuelaB,"B",true);documento(escuelaB,dB,false,true,"CARGADO");
  var tB=datos.tutor(escuelaB,familiaB,"B","Ficticio");documento(escuelaB,tB,true,true,"CARGADO");
  for(var u:List.of(admin,familiar)) {
   assertThat(leer(u,deportistaA,false,0,20).contenido()).extracting(DocumentoMetadatos::id).containsExactly(d);
   assertThat(leer(u,tutorA,true,0,20).contenido()).extracting(DocumentoMetadatos::id).containsExactly(t);
   denegado(u,dB,false);denegado(u,tB,true);denegado(u,UUID.randomUUID(),true);denegado(u,UUID.randomUUID(),false);
  }
  denegado(familiar,ajeno,false);denegado(familiar,tutorAjeno,true);
  assertThat(leer(admin,ajeno,false,0,20).totalElementos()).isEqualTo(1);
  assertThat(leer(admin,tutorAjeno,true,0,20).totalElementos()).isEqualTo(1);
  // Cambiar el tipo de propietario de la ruta nunca busca por un ID documental ni revela el otro propietario.
  denegado(familiar,tutorA,false);denegado(familiar,deportistaA,true);
 }
 @Test void vinculosTodosLosEstadosRevocacionConfirmadaEInactividadDeportiva() {
  documento(escuelaA,deportistaA,false,true,"CARGADO");
  datos.desactivarDeportista(deportistaA);
  assertThat(leer(familiar,deportistaA,false,0,20).totalElementos()).isEqualTo(1);
  for(String estado:List.of("PENDIENTE","RECHAZADO","REVOCADO")) {
   datos.estadoVinculo(vinculoA,estado,null);denegado(familiar,deportistaA,false);
  }
  datos.estadoVinculo(vinculoA,"ACTIVO",adminId);
  assertThat(leer(familiar,deportistaA,false,0,20).totalElementos()).isEqualTo(1);
  // Un vinculo ACTIVO no principal de una segunda familia conserva su acceso.
  datos.vinculo(escuelaA,familiaOtra,deportistaA,"ACTIVO",false,adminId);
  var otra=new UsuarioAutenticado(datos.usuarioFamilia(escuelaA,familiaOtra,"otra@example.invalid"),escuelaA,familiaOtra,Rol.FAMILIA);
  assertThat(leer(otra,deportistaA,false,0,20).totalElementos()).isEqualTo(1);
 }
 @Test void tutorNoNecesitaVinculoDeportistaPeroSiFamiliaYTutorActivos() {
  documento(escuelaA,tutorA,true,true,"CARGADO");
  datos.estadoVinculo(vinculoA,"REVOCADO",null);
  assertThat(leer(familiar,tutorA,true,0,20).totalElementos()).isEqualTo(1);
  datos.desactivarTutor(tutorA);denegado(familiar,tutorA,true);
  assertThat(leer(admin,tutorA,true,0,20).totalElementos()).isEqualTo(1);
 }
 @Test void familiaInactivaNoRecibeMetadatosAunqueExistanDocumentos() {
  documento(escuelaA,tutorA,true,true,"CARGADO");documento(escuelaA,deportistaA,false,true,"CARGADO");
  datos.desactivarFamilia(familiaA);denegado(familiar,tutorA,true);denegado(familiar,deportistaA,false);
 }
 @Test void padreVacioYPaginaFueraDeRangoNoSon404YConteoEstaAcotado() {
  for(boolean tutor:List.of(false,true)) {
   UUID id=tutor?tutorA:deportistaA;
   for(var u:List.of(admin,familiar)) {
    assertThat(leer(u,id,tutor,0,1).totalElementos()).isZero();
   }
   documento(escuelaA,id,tutor,true,"CARGADO");
   for(var u:List.of(admin,familiar)) {
    assertThat(leer(u,id,tutor,99,1).contenido()).isEmpty();
    assertThat(leer(u,id,tutor,99,1).totalElementos()).isEqualTo(1);
   }
  }
 }
 @Test void paginacionEstableSinNMasUnoYConteoSinFiltraciones() {
  for(boolean tutor:List.of(false,true)) {
   UUID id=tutor?tutorA:deportistaA;
   documento(escuelaA,id,tutor,true,"CARGADO");documento(escuelaA,id,tutor,true,"CARGADO");
   long antes=sentencias(()->leer(familiar,id,tutor,0,1));
   for(int i=0;i<12;i++) documento(escuelaA,id,tutor,true,"CARGADO");
   long despues=sentencias(()->leer(familiar,id,tutor,0,1));
   assertThat(despues).isEqualTo(antes).isLessThanOrEqualTo(2);
   var primera=leer(familiar,id,tutor,0,1);var segunda=leer(familiar,id,tutor,1,1);
   assertThat(primera.totalElementos()).isEqualTo(14);assertThat(primera.contenido()).doesNotContainAnyElementsOf(segunda.contenido());
   assertThat(leer(familiar,id,tutor,0,1)).isEqualTo(primera);
  }
  // Hibernate cuenta SELECTs JPA; el INSERT de auditoria via JDBC es adicional y constante.
 }
 @Test void estadosCanonicosSinTransicionesNiDatosDeStorageEnRespuestaOAuditoria() {
  for(var estado:EstadoDocumento.values()) documento(escuelaA,deportistaA,false,true,estado.name());
  String antes=jdbc.sql("SELECT json_agg(d ORDER BY id)::text FROM gestion_patin.documento d WHERE escuela_id=:e").param("e",escuelaA).query(String.class).single();
  var resultado=leer(familiar,deportistaA,false,0,20);
  assertThat(resultado.contenido()).extracting(DocumentoMetadatos::estado).containsExactlyInAnyOrder(EstadoDocumento.values());
  assertThat(resultado.contenido().stream().filter(d->d.estado()!=EstadoDocumento.OBSERVADO)).allMatch(d->d.motivoObservacion()==null);
  String json=new tools.jackson.databind.json.JsonMapper().writeValueAsString(resultado);
  assertThat(json).doesNotContain("RUTA_PRIVADA","NOMBRE_PRIVADO","storage","bucket","http");
  String despues=jdbc.sql("SELECT json_agg(d ORDER BY id)::text FROM gestion_patin.documento d WHERE escuela_id=:e").param("e",escuelaA).query(String.class).single();
  assertThat(despues).isEqualTo(antes);
  String detalle=jdbc.sql("SELECT detalle::text FROM gestion_patin.auditoria WHERE escuela_id=:e AND accion='DOCUMENTOS_CONSULTADOS'").param("e",escuelaA).query(String.class).single();
  assertThat(detalle).contains("cantidad","pagina").doesNotContain("PRIVAD","MOTIVO","storage","bucket");
 }
}
