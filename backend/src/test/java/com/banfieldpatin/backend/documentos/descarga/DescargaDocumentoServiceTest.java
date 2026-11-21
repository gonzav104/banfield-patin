package com.banfieldpatin.backend.documentos.descarga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.banfieldpatin.backend.compartido.auditoria.AccionAuditoria;
import com.banfieldpatin.backend.compartido.auditoria.AuditoriaService;
import com.banfieldpatin.backend.compartido.auditoria.EventoAuditoria;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.documentos.almacen.AlmacenDocumentos;
import com.banfieldpatin.backend.documentos.almacen.ContenidoLeido;
import com.banfieldpatin.backend.documentos.almacen.DestinoPreparado;
import com.banfieldpatin.backend.documentos.almacen.FalloAlmacenDocumentos;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;
import com.banfieldpatin.backend.usuarios.Rol;

@ExtendWith(OutputCaptureExtension.class)
class DescargaDocumentoServiceTest {
	private static final byte[] CONTENIDO = "BYTES-DOCUMENTALES-SINTETICOS".getBytes(StandardCharsets.UTF_8);
	private static final UUID CLAVE = UUID.randomUUID();
	private final UUID escuela = UUID.randomUUID(), deportista = UUID.randomUUID(), documento = UUID.randomUUID();
	private final UsuarioAutenticado admin = new UsuarioAutenticado(UUID.randomUUID(), escuela, null, Rol.ADMIN);
	private final DatosSolicitud datos = new DatosSolicitud("127.0.0.1", "prueba");
	private ConsultaDescargaJdbc consulta;
	private PlatformTransactionManager tx;
	private AuditoriaService auditoria;
	private AlmacenDocumentos almacen;
	private ObjectProvider<AlmacenDocumentos> almacenes;
	private final List<String> entregas = new ArrayList<>();

	@SuppressWarnings("unchecked")
	@BeforeEach
	void preparar() {
		consulta = mock(ConsultaDescargaJdbc.class);
		tx = mock(PlatformTransactionManager.class);
		when(tx.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
		auditoria = mock(AuditoriaService.class);
		almacen = mock(AlmacenDocumentos.class);
		almacenes = mock(ObjectProvider.class);
		when(almacenes.getIfAvailable()).thenReturn(almacen);
		registro("DNI_DEPORTISTA_FRENTE", "image/jpeg", CLAVE.toString(), CONTENIDO.length, sha(CONTENIDO));
		when(almacen.leer(any(), anyLong())).thenReturn(new ContenidoLeido(CONTENIDO.clone(), "image/jpeg"));
	}

	private void registro(String tipo, String mime, String clave, long tamanio, byte[] sha) {
		when(consulta.buscar(escuela, deportista, documento))
				.thenReturn(Optional.of(new DocumentoDescargable(tipo, "privado", clave, mime, tamanio, sha)));
	}

	private static byte[] sha(byte[] b) {
		try { return MessageDigest.getInstance("SHA-256").digest(b); }
		catch (Exception ex) { throw new IllegalStateException(ex); }
	}

	private DescargaDocumentoService servicio(int concurrencia) {
		return new DescargaDocumentoService(consulta, tx, auditoria, almacenes, new DescargaPropiedades(concurrencia));
	}

	private DescargaDocumentoService.Entrega registrarEntrega() {
		return (mime, nombre, bytes) -> entregas.add(mime + "|" + nombre + "|" + new String(bytes, StandardCharsets.UTF_8));
	}

	private static void fallaCon(Runnable accion, HttpStatus estado, String codigo) {
		assertThatThrownBy(accion::run).isInstanceOfSatisfying(ExcepcionNegocio.class, ex -> {
			assertThat(ex.getEstado()).isEqualTo(estado);
			assertThat(ex.getCodigo()).isEqualTo(codigo);
			assertThat(ex.getMessage()).doesNotContain("BYTES-DOCUMENTALES", CLAVE.toString(), "privado");
		});
	}

	@Test
	void entregaSoloTrasVerificarYAuditarYRetieneElPermisoHastaTerminar() {
		var s = servicio(2);
		AtomicInteger disponiblesDuranteEntrega = new AtomicInteger(-1);
		s.descargar(admin, deportista, documento, datos, (mime, nombre, bytes) -> {
			disponiblesDuranteEntrega.set(s.permisosDisponibles());
			entregas.add(mime + "|" + nombre + "|" + new String(bytes, StandardCharsets.UTF_8));
		});
		assertThat(entregas).containsExactly("image/jpeg|dni-frente.jpg|BYTES-DOCUMENTALES-SINTETICOS");
		assertThat(disponiblesDuranteEntrega).hasValue(1);
		assertThat(s.permisosDisponibles()).isEqualTo(2);
		var destino = ArgumentCaptor.forClass(DestinoPreparado.class);
		verify(almacen).leer(destino.capture(), org.mockito.ArgumentMatchers.eq((long) CONTENIDO.length));
		assertThat(destino.getValue().bucketInterno()).isEqualTo("privado");
		assertThat(destino.getValue().objetoIdInterno()).isEqualTo(CLAVE);
		var evento = ArgumentCaptor.forClass(EventoAuditoria.class);
		var orden = inOrder(tx, almacen, auditoria);
		orden.verify(almacen).leer(any(), anyLong());
		orden.verify(tx).getTransaction(any());
		orden.verify(auditoria).registrar(evento.capture());
		orden.verify(tx).commit(any());
		assertThat(evento.getValue().accion()).isEqualTo(AccionAuditoria.DOCUMENTO_DESCARGADO);
		assertThat(evento.getValue().escuelaId()).isEqualTo(escuela);
		assertThat(evento.getValue().usuarioId()).isEqualTo(admin.id());
		assertThat(evento.getValue().recursoTipo()).isEqualTo("DOCUMENTO");
		assertThat(evento.getValue().recursoId()).isEqualTo(documento);
		assertThat(evento.getValue().solicitud()).isEqualTo(datos);
		assertThat(evento.getValue().detalle().toString()).doesNotContain("BYTES-DOCUMENTALES", CLAVE.toString(), "privado");
		verify(auditoria, never()).registrarFallo(any());
	}

	@Test
	void laAutorizacionSeLeeEnUnaTransaccionDeSoloLecturaYLaAuditoriaEnUnaPropia() {
		servicio(2).descargar(admin, deportista, documento, datos, registrarEntrega());
		var definiciones = ArgumentCaptor.forClass(TransactionDefinition.class);
		verify(tx, org.mockito.Mockito.times(2)).getTransaction(definiciones.capture());
		assertThat(definiciones.getAllValues().get(0).isReadOnly()).isTrue();
		assertThat(definiciones.getAllValues().get(1).isReadOnly()).isFalse();
		assertThat(definiciones.getAllValues().get(1).getPropagationBehavior())
				.isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	@Test
	void sinDocumentoAutorizadoEsUn404UniformeSinTocarStorage() {
		when(consulta.buscar(any(), any(), any())).thenReturn(Optional.empty());
		var s = servicio(2);
		fallaCon(() -> s.descargar(admin, deportista, documento, datos, registrarEntrega()), HttpStatus.NOT_FOUND,
				"DOCUMENTO_NO_ENCONTRADO");
		verifyNoInteractions(almacen, auditoria);
		assertThat(s.permisosDisponibles()).isEqualTo(2);
	}

	@Test
	void laIdentidadDeLaConsultaSaleDeLaSesionYDeLaRuta() {
		servicio(2).descargar(admin, deportista, documento, datos, registrarEntrega());
		verify(consulta).buscar(escuela, deportista, documento);
	}

	@Test
	void unaSesionQueNoEsAdminNoConsulta() {
		var familia = new UsuarioAutenticado(UUID.randomUUID(), escuela, UUID.randomUUID(), Rol.FAMILIA);
		fallaCon(() -> servicio(2).descargar(familia, deportista, documento, datos, registrarEntrega()),
				HttpStatus.FORBIDDEN, "ACCESO_DENEGADO");
		verifyNoInteractions(consulta, almacen, auditoria);
	}

	@Test
	void sinOperacionPublicadaLaIntegridadNoEsVerificable() {
		registro("OTRO", "application/pdf", CLAVE.toString(), CONTENIDO.length, null);
		fallaCon(() -> servicio(2).descargar(admin, deportista, documento, datos, registrarEntrega()),
				HttpStatus.CONFLICT, "INTEGRIDAD_NO_VERIFICABLE");
		verifyNoInteractions(almacen, auditoria);
	}

	@ParameterizedTest
	@CsvSource({ "text/html,clave", "application/octet-stream,clave", "image/png,RUTA_PRIVADA/no-uuid", "image/png,''" })
	void tipoNoServibleOClaveIlegibleNoSeLeen(String mime, String clave) {
		registro("OTRO", mime, clave.equals("clave") ? CLAVE.toString() : clave, CONTENIDO.length, sha(CONTENIDO));
		fallaCon(() -> servicio(2).descargar(admin, deportista, documento, datos, registrarEntrega()),
				HttpStatus.CONFLICT, "INTEGRIDAD_NO_VERIFICABLE");
		verifyNoInteractions(almacen, auditoria);
	}

	@Test
	void storageDeshabilitadoNoEstaDisponible() {
		when(almacenes.getIfAvailable()).thenReturn(null);
		var s = servicio(2);
		fallaCon(() -> s.descargar(admin, deportista, documento, datos, registrarEntrega()),
				HttpStatus.SERVICE_UNAVAILABLE, "DESCARGA_NO_DISPONIBLE");
		verifyNoInteractions(auditoria);
		assertThat(s.permisosDisponibles()).isEqualTo(2);
	}

	@ParameterizedTest
	@EnumSource(FalloAlmacenDocumentos.Codigo.class)
	void cualquierFalloDeStorageEsNoDisponibleYLiberaElPermiso(FalloAlmacenDocumentos.Codigo codigo, CapturedOutput salida)
			throws Exception {
		var constructor = FalloAlmacenDocumentos.class.getDeclaredConstructor(FalloAlmacenDocumentos.Codigo.class, boolean.class);
		constructor.setAccessible(true);
		when(almacen.leer(any(), anyLong())).thenThrow(constructor.newInstance(codigo, false));
		var s = servicio(2);
		fallaCon(() -> s.descargar(admin, deportista, documento, datos, registrarEntrega()),
				HttpStatus.SERVICE_UNAVAILABLE, "DESCARGA_NO_DISPONIBLE");
		assertThat(entregas).isEmpty();
		verifyNoInteractions(auditoria);
		assertThat(s.permisosDisponibles()).isEqualTo(2);
		assertThat(salida.getAll()).contains("fase=lectura", "codigo=" + codigo.name())
				.doesNotContain(CLAVE.toString(), escuela.toString(), documento.toString());
	}

	private static FalloAlmacenDocumentos falloDeStorage(FalloAlmacenDocumentos.Codigo codigo) throws Exception {
		var constructor = FalloAlmacenDocumentos.class.getDeclaredConstructor(FalloAlmacenDocumentos.Codigo.class, boolean.class);
		constructor.setAccessible(true);
		return constructor.newInstance(codigo, false);
	}

	private static com.banfieldpatin.backend.documentos.almacen.InspeccionAlmacen inspeccion(
			com.banfieldpatin.backend.documentos.almacen.InspeccionAlmacen.Estado estado, Long tamanio) {
		var i = mock(com.banfieldpatin.backend.documentos.almacen.InspeccionAlmacen.class);
		when(i.estado()).thenReturn(estado);
		when(i.tamanioBytes()).thenReturn(tamanio == null ? java.util.OptionalLong.empty() : java.util.OptionalLong.of(tamanio));
		return i;
	}

	/** Objeto mayor que el registrado: la lectura lo rechaza y el HEAD confirma otro tamaño ⇒ 502 auditado, sin bytes. */
	@Test
	void unObjetoDeTamanioDistintoConfirmadoEsUnFalloDeIntegridadAuditado() throws Exception {
		when(almacen.leer(any(), anyLong())).thenThrow(falloDeStorage(FalloAlmacenDocumentos.Codigo.RESPUESTA_INESPERADA));
		var mayor = inspeccion(com.banfieldpatin.backend.documentos.almacen.InspeccionAlmacen.Estado.EXISTENCIA_CONFIRMADA,
				(long) CONTENIDO.length + 1);
		when(almacen.inspeccionar(any())).thenReturn(mayor);
		var s = servicio(2);
		fallaCon(() -> s.descargar(admin, deportista, documento, datos, registrarEntrega()),
				HttpStatus.BAD_GATEWAY, "INTEGRIDAD_FALLIDA");
		assertThat(entregas).isEmpty();
		verify(auditoria).registrarFallo(org.mockito.ArgumentMatchers.argThat(
				e -> e.accion() == com.banfieldpatin.backend.compartido.auditoria.AccionAuditoria.DOCUMENTO_INTEGRIDAD_FALLIDA));
		verify(auditoria, org.mockito.Mockito.never()).registrar(any());
		assertThat(s.permisosDisponibles()).isEqualTo(2);
	}

	/** Sin confirmación de un tamaño distinto (mismo tamaño, incierta, ausente) sigue siendo una indisponibilidad. */
	@ParameterizedTest
	@org.junit.jupiter.params.provider.CsvSource({ "EXISTENCIA_CONFIRMADA,SAME", "INCIERTA,", "AUSENCIA_OBSERVADA," })
	void sinTamanioDistintoConfirmadoSigueSiendoNoDisponible(String estado, String tamanio) throws Exception {
		when(almacen.leer(any(), anyLong())).thenThrow(falloDeStorage(FalloAlmacenDocumentos.Codigo.RESPUESTA_INESPERADA));
		var observada = inspeccion(com.banfieldpatin.backend.documentos.almacen.InspeccionAlmacen.Estado.valueOf(estado),
				tamanio == null ? null : (long) CONTENIDO.length);
		when(almacen.inspeccionar(any())).thenReturn(observada);
		var s = servicio(2);
		fallaCon(() -> s.descargar(admin, deportista, documento, datos, registrarEntrega()),
				HttpStatus.SERVICE_UNAVAILABLE, "DESCARGA_NO_DISPONIBLE");
		assertThat(entregas).isEmpty();
		verifyNoInteractions(auditoria);
		assertThat(s.permisosDisponibles()).isEqualTo(2);
	}

	/** Si la propia inspección falla o no responde nada, no hay confirmación: sigue siendo 503 sin auditar ni entregar. */
	@ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(booleans = { true, false })
	void unaInspeccionFallidaOVaciaNoConvierteEnFalloDeIntegridad(boolean lanza) throws Exception {
		when(almacen.leer(any(), anyLong())).thenThrow(falloDeStorage(FalloAlmacenDocumentos.Codigo.RESPUESTA_INESPERADA));
		if (lanza) when(almacen.inspeccionar(any())).thenThrow(new IllegalStateException("inspeccion caida"));
		else when(almacen.inspeccionar(any())).thenReturn(null);
		var s = servicio(2);
		fallaCon(() -> s.descargar(admin, deportista, documento, datos, registrarEntrega()),
				HttpStatus.SERVICE_UNAVAILABLE, "DESCARGA_NO_DISPONIBLE");
		assertThat(entregas).isEmpty();
		verifyNoInteractions(auditoria);
		assertThat(s.permisosDisponibles()).isEqualTo(2);
	}

	/** Sólo una respuesta inesperada justifica consultar el estado; un plazo vencido o un rechazo no lo consultan. */
	@ParameterizedTest
	@org.junit.jupiter.params.provider.EnumSource(value = FalloAlmacenDocumentos.Codigo.class, names = "RESPUESTA_INESPERADA",
			mode = org.junit.jupiter.params.provider.EnumSource.Mode.EXCLUDE)
	void otrosFallosNoConsultanElEstadoDelObjeto(FalloAlmacenDocumentos.Codigo codigo) throws Exception {
		when(almacen.leer(any(), anyLong())).thenThrow(falloDeStorage(codigo));
		var s = servicio(2);
		fallaCon(() -> s.descargar(admin, deportista, documento, datos, registrarEntrega()),
				HttpStatus.SERVICE_UNAVAILABLE, "DESCARGA_NO_DISPONIBLE");
		verify(almacen, org.mockito.Mockito.never()).inspeccionar(any());
	}

	@Test
	void unErrorInesperadoDelAdaptadorTampocoEntregaNada() {
		when(almacen.leer(any(), anyLong())).thenThrow(new IllegalStateException("BYTES-DOCUMENTALES en el mensaje"));
		var s = servicio(2);
		fallaCon(() -> s.descargar(admin, deportista, documento, datos, registrarEntrega()),
				HttpStatus.SERVICE_UNAVAILABLE, "DESCARGA_NO_DISPONIBLE");
		assertThat(s.permisosDisponibles()).isEqualTo(2);
	}

	@ParameterizedTest
	@CsvSource({ "tamanio", "sha", "corto" })
	void tamanioOShaDistintosSonIntegridadFallidaAuditadaSinEntregar(String caso, CapturedOutput salida) {
		byte[] alterado = CONTENIDO.clone();
		alterado[0] ^= 1;
		switch (caso) {
			case "tamanio" -> registro("DNI_DEPORTISTA_FRENTE", "image/jpeg", CLAVE.toString(), CONTENIDO.length + 1L, sha(CONTENIDO));
			case "sha" -> when(almacen.leer(any(), anyLong())).thenReturn(new ContenidoLeido(alterado, "image/jpeg"));
			default -> when(almacen.leer(any(), anyLong()))
					.thenReturn(new ContenidoLeido(java.util.Arrays.copyOf(CONTENIDO, 3), "image/jpeg"));
		}
		var s = servicio(2);
		fallaCon(() -> s.descargar(admin, deportista, documento, datos, registrarEntrega()), HttpStatus.BAD_GATEWAY,
				"INTEGRIDAD_FALLIDA");
		assertThat(entregas).isEmpty();
		var evento = ArgumentCaptor.forClass(EventoAuditoria.class);
		verify(auditoria).registrarFallo(evento.capture());
		verify(auditoria, never()).registrar(any());
		assertThat(evento.getValue().accion()).isEqualTo(AccionAuditoria.DOCUMENTO_INTEGRIDAD_FALLIDA);
		assertThat(evento.getValue().recursoId()).isEqualTo(documento);
		assertThat(s.permisosDisponibles()).isEqualTo(2);
		assertThat(salida.getAll()).contains("fase=integridad").doesNotContain("BYTES-DOCUMENTALES");
	}

	@Test
	void unFalloSecundarioAlAuditarLaIntegridadNoReemplazaEl502(CapturedOutput salida) {
		byte[] alterado = CONTENIDO.clone();
		alterado[0] ^= 1;
		when(almacen.leer(any(), anyLong())).thenReturn(new ContenidoLeido(alterado, "image/jpeg"));
		doThrow(new IllegalStateException("auditoria caida " + CLAVE)).when(auditoria).registrarFallo(any());
		var s = servicio(1);
		fallaCon(() -> s.descargar(admin, deportista, documento, datos, registrarEntrega()), HttpStatus.BAD_GATEWAY,
				"INTEGRIDAD_FALLIDA");
		assertThat(entregas).isEmpty();
		verify(auditoria).registrarFallo(any());
		verify(auditoria, never()).registrar(any());
		assertThat(s.permisosDisponibles()).isEqualTo(1);
		assertThat(salida.getAll()).contains("fase=auditoria-integridad", "causa=IllegalStateException")
				.doesNotContain("auditoria caida", CLAVE.toString(), escuela.toString(), documento.toString(),
						"BYTES-DOCUMENTALES");
		// El permiso quedó libre: la siguiente descarga íntegra se entrega.
		when(almacen.leer(any(), anyLong())).thenReturn(new ContenidoLeido(CONTENIDO.clone(), "image/jpeg"));
		s.descargar(admin, deportista, documento, datos, registrarEntrega());
		assertThat(entregas).hasSize(1);
	}

	@Test
	void conElRegistroDeFallosCaidoLaAuditoriaObligatoriaSigueSiendoFailClosed() {
		doThrow(new IllegalStateException("auditoria caida")).when(auditoria).registrarFallo(any());
		doThrow(new IllegalStateException("base caida")).when(auditoria).registrar(any());
		var s = servicio(1);
		fallaCon(() -> s.descargar(admin, deportista, documento, datos, registrarEntrega()),
				HttpStatus.SERVICE_UNAVAILABLE, "DESCARGA_NO_DISPONIBLE");
		assertThat(entregas).isEmpty();
		assertThat(s.permisosDisponibles()).isEqualTo(1);
	}

	@Test
	void unaLecturaLentaQueVenceEsNoDisponibleYLaCapacidadSeRecupera() throws Exception {
		var constructor = FalloAlmacenDocumentos.class.getDeclaredConstructor(FalloAlmacenDocumentos.Codigo.class, boolean.class);
		constructor.setAccessible(true);
		var vencida = constructor.newInstance(FalloAlmacenDocumentos.Codigo.TIMEOUT, true);
		var s = servicio(1);
		AtomicInteger disponiblesDuranteLectura = new AtomicInteger(-1);
		when(almacen.leer(any(), anyLong())).thenAnswer(i -> {
			disponiblesDuranteLectura.set(s.permisosDisponibles());
			Thread.sleep(200);
			throw vencida;
		}).thenReturn(new ContenidoLeido(CONTENIDO.clone(), "image/jpeg"));
		fallaCon(() -> s.descargar(admin, deportista, documento, datos, registrarEntrega()),
				HttpStatus.SERVICE_UNAVAILABLE, "DESCARGA_NO_DISPONIBLE");
		assertThat(disponiblesDuranteLectura).hasValue(0);
		assertThat(entregas).isEmpty();
		assertThat(s.permisosDisponibles()).isEqualTo(1);
		s.descargar(admin, deportista, documento, datos, registrarEntrega());
		assertThat(entregas).containsExactly("image/jpeg|dni-frente.jpg|BYTES-DOCUMENTALES-SINTETICOS");
	}

	@Test
	void siLaAuditoriaNoSeConfirmaNoSeEntregaNingunByte() {
		doThrow(new IllegalStateException("base caida")).when(auditoria).registrar(any());
		var s = servicio(2);
		fallaCon(() -> s.descargar(admin, deportista, documento, datos, registrarEntrega()),
				HttpStatus.SERVICE_UNAVAILABLE, "DESCARGA_NO_DISPONIBLE");
		assertThat(entregas).isEmpty();
		assertThat(s.permisosDisponibles()).isEqualTo(2);
	}

	@Test
	void siElCommitDeLaAuditoriaFallaNoSeEntregaNingunByte() {
		// La primera confirmación es la de la lectura autorizada; la segunda, la de la auditoría.
		doNothing().doThrow(new TransactionSystemException("commit fallido")).when(tx).commit(any());
		var s = servicio(2);
		fallaCon(() -> s.descargar(admin, deportista, documento, datos, registrarEntrega()),
				HttpStatus.SERVICE_UNAVAILABLE, "DESCARGA_NO_DISPONIBLE");
		assertThat(entregas).isEmpty();
		assertThat(s.permisosDisponibles()).isEqualTo(2);
	}

	@Test
	void sinPermisoDisponibleRespondeOcupadoSinColaYSinLeer() throws Exception {
		var s = servicio(1);
		var enLectura = new CountDownLatch(1);
		var liberar = new CountDownLatch(1);
		when(almacen.leer(any(), anyLong())).thenAnswer(i -> {
			enLectura.countDown();
			assertThat(liberar.await(5, TimeUnit.SECONDS)).isTrue();
			return new ContenidoLeido(CONTENIDO.clone(), "image/jpeg");
		});
		var primera = CompletableFuture.runAsync(() -> s.descargar(admin, deportista, documento, datos, registrarEntrega()));
		assertThat(enLectura.await(5, TimeUnit.SECONDS)).isTrue();
		fallaCon(() -> s.descargar(admin, deportista, documento, datos, registrarEntrega()), HttpStatus.TOO_MANY_REQUESTS,
				"DESCARGA_OCUPADA");
		verify(almacen).leer(any(), anyLong());
		liberar.countDown();
		primera.get(5, TimeUnit.SECONDS);
		assertThat(s.permisosDisponibles()).isEqualTo(1);
		s.descargar(admin, deportista, documento, datos, registrarEntrega());
		assertThat(entregas).hasSize(2);
	}

	@Test
	void unaDesconexionDuranteLaEntregaLiberaElPermisoSinOtraRespuesta(CapturedOutput salida) {
		var s = servicio(1);
		s.descargar(admin, deportista, documento, datos, (mime, nombre, bytes) -> {
			throw new IOException("Broken pipe BYTES-DOCUMENTALES");
		});
		assertThat(s.permisosDisponibles()).isEqualTo(1);
		assertThat(salida.getAll()).contains("fase=entrega", "causa=IOException").doesNotContain("Broken pipe");
		verify(auditoria).registrar(any());
	}

	@Test
	void unErrorInesperadoDuranteLaEntregaTambienLiberaElPermiso() {
		var s = servicio(1);
		assertThatThrownBy(() -> s.descargar(admin, deportista, documento, datos, (mime, nombre, bytes) -> {
			throw new IllegalStateException("fallo");
		})).isInstanceOf(IllegalStateException.class);
		assertThat(s.permisosDisponibles()).isEqualTo(1);
	}

	@ParameterizedTest
	@CsvSource({ "DNI_DEPORTISTA_FRENTE,image/jpeg,dni-frente.jpg", "DNI_DEPORTISTA_DORSO,application/pdf,dni-dorso.pdf",
			"SOLICITUD_LICENCIA,application/pdf,solicitud-licencia.pdf", "CERTIFICADO_WADA,image/png,certificado-wada.png",
			"APTO_FISICO,image/png,apto-fisico.png", "OTRO,image/jpeg,documento.jpg", "INVENTADO,image/jpeg,documento.jpg" })
	void elNombreDelArchivoEsNeutroYDerivaDelTipoYElFormato(String tipo, String mime, String nombre) {
		assertThat(DescargaDocumentoService.nombreNeutro(tipo, mime)).isEqualTo(nombre);
	}
}
