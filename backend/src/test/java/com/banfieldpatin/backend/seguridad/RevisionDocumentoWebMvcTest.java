package com.banfieldpatin.backend.seguridad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.documentos.DocumentoAdminMetadatos;
import com.banfieldpatin.backend.documentos.EstadoDocumento;
import com.banfieldpatin.backend.documentos.TipoDocumento;
import com.banfieldpatin.backend.usuarios.Rol;

import jakarta.servlet.http.Cookie;

@ExtendWith(OutputCaptureExtension.class)
class RevisionDocumentoWebMvcTest extends BaseOpenApiWebMvc {
    private static final String APROBAR = """
            {"estado":"APROBADO","versionEsperada":"0"}
            """;
    private static final String RUTA = "/api/admin/deportistas/{deportistaId}/documentos/{documentoId}/revision";
    private final UUID deportista = UUID.randomUUID(), documento = UUID.randomUUID();
    private Cookie xsrf;

    @BeforeEach
    void preparar() throws Exception {
        verificador.reiniciar();
        xsrf = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrf).isNotNull();
    }

    private MockHttpServletRequestBuilder pedido(String cuerpo) {
        return post(RUTA, deportista, documento)
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo);
    }

    private MockHttpServletRequestBuilder conCsrf(MockHttpServletRequestBuilder pedido) {
        return pedido.cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue());
    }

    @Test
    void observarSinMotivoRechazaLaSolicitud() throws Exception {
        mvc.perform(conCsrf(pedido("""
                {"estado":"OBSERVADO","versionEsperada":"0"}
                """)).cookie(sesion(Rol.ADMIN)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
    }

    @Test
    void soloAdminConMfaYCsrfLlegaAlServicio() throws Exception {
        mvc.perform(conCsrf(pedido(APROBAR))).andExpect(status().isUnauthorized());
        for (var cookie : List.of(sesion(Rol.FAMILIA), sesionMfaPendiente())) {
            mvc.perform(conCsrf(pedido(APROBAR)).cookie(cookie)).andExpect(status().isForbidden());
        }
        mvc.perform(pedido(APROBAR).cookie(sesion(Rol.ADMIN))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
        mvc.perform(pedido(APROBAR).cookie(sesion(Rol.ADMIN), xsrf).header("X-XSRF-TOKEN", "incorrecto"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
        verifyNoInteractions(revisionDocumentoService);

        when(revisionDocumentoService.revisar(any(), eq(deportista), eq(documento), eq(EstadoDocumento.APROBADO),
                eq(0L), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new DocumentoAdminMetadatos(documento, TipoDocumento.DNI_DEPORTISTA_FRENTE,
                        EstadoDocumento.APROBADO, null, Instant.EPOCH, true, null, null, 1L));
        mvc.perform(conCsrf(pedido(APROBAR)).cookie(sesion(Rol.ADMIN))).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("1")).andExpect(jsonPath("$.estado").value("APROBADO"));
        verify(revisionDocumentoService).revisar(any(), eq(deportista), eq(documento), eq(EstadoDocumento.APROBADO),
                eq(0L), org.mockito.ArgumentMatchers.isNull());
    }

    // ---- U19: la misma ruta y las mismas reglas para los DNI de un tutor ----

    private static final String RUTA_TUTOR = "/api/admin/tutores/{tutorId}/documentos/{documentoId}/revision";

    private MockHttpServletRequestBuilder pedidoTutor(String cuerpo) {
        return post(RUTA_TUTOR, deportista, documento).contentType(MediaType.APPLICATION_JSON).content(cuerpo);
    }

    @Test
    void laRutaDeTutorSoloAceptaAdminConMfaYCsrfYLlegaAlServicioDeTutor() throws Exception {
        mvc.perform(conCsrf(pedidoTutor(APROBAR))).andExpect(status().isUnauthorized());
        for (var cookie : List.of(sesion(Rol.FAMILIA), sesionMfaPendiente())) {
            mvc.perform(conCsrf(pedidoTutor(APROBAR)).cookie(cookie)).andExpect(status().isForbidden());
        }
        mvc.perform(pedidoTutor(APROBAR).cookie(sesion(Rol.ADMIN))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));
        verifyNoInteractions(revisionDocumentoService);

        when(revisionDocumentoService.revisarTutor(any(), eq(deportista), eq(documento), eq(EstadoDocumento.APROBADO),
                eq(0L), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new DocumentoAdminMetadatos(documento, TipoDocumento.DNI_TUTOR_FRENTE,
                        EstadoDocumento.APROBADO, null, Instant.EPOCH, true, null, null, 1L));
        mvc.perform(conCsrf(pedidoTutor(APROBAR)).cookie(sesion(Rol.ADMIN))).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("1")).andExpect(jsonPath("$.tipo").value("DNI_TUTOR_FRENTE"));
        verify(revisionDocumentoService).revisarTutor(any(), eq(deportista), eq(documento), eq(EstadoDocumento.APROBADO),
                eq(0L), org.mockito.ArgumentMatchers.isNull());
        // La ruta del deportista no llega al servicio del tutor.
        verify(revisionDocumentoService, org.mockito.Mockito.never()).revisar(any(), any(), any(), any(), eq(0L), any());
    }

    @Test
    void laRutaDeTutorValidaElCuerpoExactamenteComoLaDelDeportista() throws Exception {
        for (String cuerpo : List.of("""
                {"estado":"OBSERVADO","versionEsperada":"0"}
                """, """
                {"estado":"APROBADO","versionEsperada":0}
                """, """
                {"estado":"APROBADO"}
                """, """
                {"estado":"APROBADO","versionEsperada":"0","motivoObservacion":"no va"}
                """, """
                {"estado":"OTRO","versionEsperada":"0"}
                """)) {
            var deDeportista = mvc.perform(conCsrf(pedido(cuerpo)).cookie(sesion(Rol.ADMIN))).andReturn().getResponse();
            var deTutor = mvc.perform(conCsrf(pedidoTutor(cuerpo)).cookie(sesion(Rol.ADMIN))).andReturn().getResponse();
            assertThat(deTutor.getStatus()).as(cuerpo).isEqualTo(deDeportista.getStatus()).isEqualTo(400);
            assertThat(deTutor.getContentAsString()).as(cuerpo).isEqualTo(deDeportista.getContentAsString());
        }
        mvc.perform(conCsrf(post(RUTA_TUTOR, "no-uuid", documento).contentType(MediaType.APPLICATION_JSON)
                .content(APROBAR)).cookie(sesion(Rol.ADMIN))).andExpect(status().isBadRequest());
        verifyNoInteractions(revisionDocumentoService);
    }

    @Test
    void rechazaCoercionesVersionAusenteYTextosInvalidosSinEco() throws Exception {
        for (String cuerpo : List.of(
                APROBAR.replace("\"0\"", "0"), APROBAR.replace("\"0\"", "\"-1\""),
                APROBAR.replace("\"0\"", "\"9223372036854775808\""), "{\"estado\":\"APROBADO\"}",
                APROBAR.replace("APROBADO", "VENCIDO"),
                APROBAR.replace("}", ",\"motivoObservacion\":\"marca-sensible\"}"),
                "{\"estado\":\"OBSERVADO\",\"versionEsperada\":\"0\",\"motivoObservacion\":42}",
                "{\"estado\":\"OBSERVADO\",\"versionEsperada\":\"0\",\"motivoObservacion\":\" \u00a0 \"}",
                "{\"estado\":\"OBSERVADO\",\"versionEsperada\":\"0\",\"motivoObservacion\":\"" + "x".repeat(501) + "\"}")) {
            var respuesta = mvc.perform(conCsrf(pedido(cuerpo)).cookie(sesion(Rol.ADMIN)))
                    .andExpect(status().isBadRequest()).andReturn().getResponse();
            assertThat(respuesta.getContentAsString()).doesNotContain("marca-sensible", "9223372036854775808");
        }
        verifyNoInteractions(revisionDocumentoService);
    }

    @Test
    void aceptaQuinientosPuntosDeCodigoYVersionPorEncimaDelRangoExactoDeJavascript() throws Exception {
        var motivo = "🛼".repeat(500);
        mvc.perform(conCsrf(pedido("""
                {"estado":"OBSERVADO","versionEsperada":"9007199254740993","motivoObservacion":"  %s  "}
                """.formatted(motivo))).cookie(sesion(Rol.ADMIN))).andExpect(status().isOk());
        verify(revisionDocumentoService).revisar(any(), eq(deportista), eq(documento), eq(EstadoDocumento.OBSERVADO),
                eq(9007199254740993L), eq(motivo));
    }

    @Test
    void erroresDeAlcanceConflictoYFalloDePersistenciaNoExponenDatos(CapturedOutput salida) throws Exception {
        when(revisionDocumentoService.revisar(any(), any(), any(), any(), eq(0L), any()))
                .thenThrow(new ExcepcionNegocio(HttpStatus.NOT_FOUND, "DOCUMENTO_NO_ENCONTRADO", "Documento no encontrado."))
                .thenThrow(new ExcepcionNegocio(HttpStatus.CONFLICT, "EDICION_DESACTUALIZADA", "Volvé a cargar el documento."))
                .thenThrow(new CannotAcquireLockException("SQL marca-sensible"))
                .thenThrow(new IllegalStateException("error de persistencia marca-sensible"));
        for (var esperado : List.of("DOCUMENTO_NO_ENCONTRADO", "EDICION_DESACTUALIZADA", "CONFLICTO_CONCURRENCIA", "ERROR_INTERNO")) {
            var respuesta = mvc.perform(conCsrf(pedido(APROBAR)).cookie(sesion(Rol.ADMIN)))
                    .andExpect(status().is(switch (esperado) {
                        case "DOCUMENTO_NO_ENCONTRADO" -> 404;
                        case "ERROR_INTERNO" -> 500;
                        default -> 409;
                    })).andExpect(jsonPath("$.codigo").value(esperado)).andReturn().getResponse();
            assertThat(respuesta.getContentAsString()).doesNotContain("marca-sensible");
        }
        assertThat(salida.getAll()).doesNotContain("marca-sensible");
    }

    @Test
    void openApiDeclaraLaEscrituraYLaVersionComoCadena() throws Exception {
        var spec = arbol();
        var operacion = spec.path("paths").path(RUTA).path("post");
        assertThat(operacion.path("responses").propertyNames()).contains("200", "400", "401", "403", "404", "409");
        assertThat(spec.path("components").path("schemas").path("RevisionDocumentoSolicitud")
                .path("properties").path("versionEsperada").path("type").asString()).isEqualTo("string");
    }
}
