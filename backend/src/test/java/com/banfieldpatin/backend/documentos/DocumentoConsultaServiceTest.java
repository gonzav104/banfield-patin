package com.banfieldpatin.backend.documentos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.PageImpl;
import com.banfieldpatin.backend.compartido.auditoria.*;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.*;
import com.banfieldpatin.backend.deportistas.DeportistaRepository;
import com.banfieldpatin.backend.familias.tutores.TutorRepository;
import com.banfieldpatin.backend.familias.vinculos.FamiliaDeportistaRepository;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;

@ExtendWith(MockitoExtension.class)
class DocumentoConsultaServiceTest {
 @Mock DocumentoRepository documentos;
 @Mock DeportistaRepository deportistas;
 @Mock TutorRepository tutores;
 @Mock FamiliaDeportistaRepository vinculos;
 @Mock AuditoriaService auditoria;
 @InjectMocks DocumentoConsultaService servicio;
 private final UUID id=UUID.randomUUID();
 private final UsuarioAutenticado admin=new UsuarioAutenticado(UUID.randomUUID(),UUID.randomUUID(),null,Rol.ADMIN);
 @Test void familiaSinIdentidadNoConsultaNiAudita() {
  var u=new UsuarioAutenticado(admin.id(),admin.escuelaId(),null,Rol.FAMILIA);
  assertThatThrownBy(()->servicio.deportista(u,id,Pagina.pedir(0,20),DatosSolicitud.NINGUNA)).isInstanceOf(ExcepcionNegocio.class);
  assertThatThrownBy(()->servicio.tutor(u,id,Pagina.pedir(0,20),DatosSolicitud.NINGUNA)).isInstanceOf(ExcepcionNegocio.class);
  verifyNoInteractions(documentos,deportistas,tutores,vinculos,auditoria);
 }
 @Test void auditFailureNoDevuelveMetadatos() {
  when(documentos.deportistaAdmin(any(),any(),any())).thenReturn(new PageImpl<>(List.of()));
  when(deportistas.existsByIdAndEscuelaId(id,admin.escuelaId())).thenReturn(true);
  doThrow(new DataAccessResourceFailureException("simulada")).when(auditoria).registrar(any());
  assertThatThrownBy(()->servicio.deportista(admin,id,Pagina.pedir(0,20),DatosSolicitud.NINGUNA))
   .isInstanceOf(DataAccessResourceFailureException.class);
 }
 @Test void ownerInexistenteNoGeneraAuditoriaYUsaErrorUniforme() {
  when(documentos.tutorAdmin(any(),any(),any())).thenReturn(new PageImpl<>(List.of()));
  assertThatThrownBy(()->servicio.tutor(admin,id,Pagina.pedir(0,20),DatosSolicitud.NINGUNA))
   .isInstanceOfSatisfying(ExcepcionNegocio.class,e->assertThat(e.getCodigo()).isEqualTo("TUTOR_NO_ENCONTRADO"));
  verifyNoInteractions(auditoria);
 }
 @Test void resultadoVacioAutorizadoAuditaSoloContadores() {
  when(documentos.tutorAdmin(any(),any(),any())).thenReturn(new PageImpl<>(List.of()));
  when(tutores.existsByIdAndEscuelaId(id,admin.escuelaId())).thenReturn(true);
  assertThat(servicio.tutor(admin,id,Pagina.pedir(0,20),DatosSolicitud.NINGUNA).contenido()).isEmpty();
  verify(auditoria).registrar(argThat(e->e.accion()==AccionAuditoria.DOCUMENTOS_CONSULTADOS
   && e.recursoId().equals(id) && e.usuarioId().equals(admin.id()) && e.escuelaId().equals(admin.escuelaId())
   && e.detalle().keySet().equals(java.util.Set.of("cantidad","pagina"))));
 }
}
