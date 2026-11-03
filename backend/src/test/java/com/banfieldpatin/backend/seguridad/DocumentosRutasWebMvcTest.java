package com.banfieldpatin.backend.seguridad;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import com.banfieldpatin.backend.compartido.web.Pagina;
import com.banfieldpatin.backend.documentos.*;
import com.banfieldpatin.backend.deportistas.DeportistaAdminService;
import com.banfieldpatin.backend.familias.tutores.TutorAdminService;
import com.banfieldpatin.backend.usuarios.Rol;
import tools.jackson.databind.json.JsonMapper;
class DocumentosRutasWebMvcTest extends BaseOpenApiWebMvc {
 private final UUID id=UUID.randomUUID();
 private String ruta(String rol,String p) { return "/api/"+rol+"/"+p+"/"+id+"/documentos"; }
 @BeforeEach void preparar() {
  verificador.reiniciar();
  var fila=new DocumentoMetadatos(id,TipoDocumento.OTRO,EstadoDocumento.OBSERVADO,null,
   Instant.parse("2026-01-01T00:00:00Z"),true,null,"Documento ilegible");
  var pagina=new Pagina<>(List.of(fila),0,20,1);
  when(documentoConsultaService.deportista(any(),any(),any(),any())).thenReturn(pagina);
  when(documentoConsultaService.tutor(any(),any(),any(),any())).thenReturn(pagina);
 }
 @Test void adminPuedeConsultarMetadatosDeAmbosPropietarios() throws Exception { comprobar(Rol.ADMIN); }
 @Test void familiaPuedeConsultarMetadatosDeAmbosPropietarios() throws Exception { comprobar(Rol.FAMILIA); }
 private void comprobar(Rol rol) throws Exception {
  for(String p:List.of("deportistas","tutores")) {
   var respuesta=mvc.perform(get(ruta(rol.name().toLowerCase(),p)).cookie(sesion(rol)))
    .andExpect(status().isOk()).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")))
    .andExpect(jsonPath("$.totalElementos").value(1)).andExpect(jsonPath("$.contenido[0].motivoObservacion").value("Documento ilegible"))
    .andReturn().getResponse().getContentAsString();
   var campos=new JsonMapper().readTree(respuesta).path("contenido").get(0).propertyNames();
   assertThat(campos).containsExactlyInAnyOrder("id","tipo","estado","temporadaId","creadoEn","vigente","fechaVencimiento","motivoObservacion");
  }
 }
 @Test void anonimatoRolIncorrectoYUuidInvalidoNoConsultanServicio() throws Exception {
  clearInvocations(documentoConsultaService);
  for(String rol:List.of("admin","familia")) for(String p:List.of("deportistas","tutores")) {
   mvc.perform(get(ruta(rol,p))).andExpect(status().isUnauthorized());
   mvc.perform(get(ruta(rol,p)).cookie(sesion(rol.equals("admin")?Rol.FAMILIA:Rol.ADMIN))).andExpect(status().isForbidden());
   mvc.perform(get(ruta(rol,p).replace(id.toString(),"no-uuid")).cookie(sesion(Rol.valueOf(rol.toUpperCase())))).andExpect(status().isBadRequest());
  }
  verifyNoInteractions(documentoConsultaService);
 }
 @Test void identidadSeDerivaDeSesionYPaginacionSeAcota() throws Exception {
  var usuario=UUID.randomUUID();var escuela=UUID.randomUUID();var familia=UUID.randomUUID();
  var cookie=new jakarta.servlet.http.Cookie(COOKIE,tokens.emitir(usuario,Rol.FAMILIA,escuela,familia));
  for(String p:List.of("deportistas","tutores")) mvc.perform(get(ruta("familia",p)).cookie(cookie)
   .param("escuelaId",UUID.randomUUID().toString()).param("familiaId",UUID.randomUUID().toString())
   .param("page","-1").param("size","1000")).andExpect(status().isOk());
  var u=ArgumentCaptor.forClass(UsuarioAutenticado.class);var pagina=ArgumentCaptor.forClass(Pageable.class);
  verify(documentoConsultaService).deportista(u.capture(),eq(id),pagina.capture(),any());
  assertThat(u.getValue()).isEqualTo(new UsuarioAutenticado(usuario,escuela,familia,Rol.FAMILIA));
  assertThat(pagina.getValue().getPageNumber()).isZero();assertThat(pagina.getValue().getPageSize()).isEqualTo(100);
  verify(documentoConsultaService).tutor(eq(u.getValue()),eq(id),any(),any());
 }
 @Test void alcanceDenegadoTiene404YNoRevelaCampos() throws Exception {
  when(documentoConsultaService.deportista(any(),any(),any(),any())).thenThrow(DeportistaAdminService.deportistaNoEncontrado());
  when(documentoConsultaService.tutor(any(),any(),any(),any())).thenThrow(TutorAdminService.tutorNoEncontrado());
  for(String rol:List.of("admin","familia")) for(String p:List.of("deportistas","tutores"))
   mvc.perform(get(ruta(rol,p)).cookie(sesion(Rol.valueOf(rol.toUpperCase())))).andExpect(status().isNotFound())
    .andExpect(jsonPath("$.codigo").value(p.equals("tutores")?"TUTOR_NO_ENCONTRADO":"DEPORTISTA_NO_ENCONTRADO"));
 }
 @Test void sesionRevocadaYBaseNoDisponibleFallaCerrado() throws Exception {
  clearInvocations(documentoConsultaService);verificador.rechazar();
  for(String p:List.of("deportistas","tutores")) mvc.perform(get(ruta("familia",p)).cookie(sesion(Rol.FAMILIA))).andExpect(status().isUnauthorized());
  verificador.fallarConBaseNoDisponible();
  mvc.perform(get(ruta("admin","tutores")).cookie(sesion(Rol.ADMIN))).andExpect(status().isServiceUnavailable());
  verifyNoInteractions(documentoConsultaService);
 }
 @Test void noHayOperacionesDeArchivos() throws Exception {
  clearInvocations(documentoConsultaService);
  for(String rol:List.of("admin","familia")) for(String p:List.of("deportistas","tutores"))
   mvc.perform(post(ruta(rol,p)).cookie(sesion(Rol.valueOf(rol.toUpperCase()))).with(csrf())).andExpect(status().isMethodNotAllowed());
  verifyNoInteractions(documentoConsultaService);
 }
 @Test void openApiDocumentaLasCuatroRutasYElDtoMinimo() throws Exception {
  var spec=arbol();
  for(String rol:List.of("admin","familia")) for(String p:List.of("deportistas","tutores")) {
   var op=spec.path("paths").path("/api/"+rol+"/"+p+"/{id}/documentos").path("get");
   assertThat(op.path("responses").propertyNames()).contains("200","400","401","403","404");
  }
  assertThat(spec.path("components").path("schemas").path("DocumentoMetadatos").path("properties").propertyNames())
   .containsExactlyInAnyOrder("id","tipo","estado","temporadaId","creadoEn","vigente","fechaVencimiento","motivoObservacion");
 }
}
