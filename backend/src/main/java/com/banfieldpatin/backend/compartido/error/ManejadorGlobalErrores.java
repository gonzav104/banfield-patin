package com.banfieldpatin.backend.compartido.error;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Formato uniforme {codigo, mensaje, detalles}. Nunca expone trazas, clases ni valores enviados.
 */
@RestControllerAdvice
public class ManejadorGlobalErrores {

	private static final Logger log = LoggerFactory.getLogger(ManejadorGlobalErrores.class);

	@ExceptionHandler(ExcepcionNegocio.class)
	ResponseEntity<ErrorRespuesta> negocio(ExcepcionNegocio e) {
		return responder(e.getEstado(), new ErrorRespuesta(e.getCodigo(), e.getMessage(), e.getDetalles()));
	}

	/**
	 * Una violacion de restriccion que ningun servicio tradujo a 409: 500 generico y UNA linea ERROR saneada (restriccion,
	 * operacion = metodo + patron de la ruta, clase de la excepcion). No se usa el manejador general porque este registra
	 * la traza completa, y el mensaje del servidor PostgreSQL incluye los valores de la clave (DNI, CUIL).
	 */
	@ExceptionHandler(DataIntegrityViolationException.class)
	ResponseEntity<ErrorRespuesta> integridad(DataIntegrityViolationException e, HttpServletRequest request) {
		Object patron = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
		RestriccionViolada.registrarNoMapeada(request.getMethod() + " " + (patron == null ? "desconocida" : patron), e);
		return responder(HttpStatus.INTERNAL_SERVER_ERROR,
				ErrorRespuesta.de("ERROR_INTERNO", "Ocurrió un error inesperado."));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ErrorRespuesta> validacion(MethodArgumentNotValidException e) {
		List<DetalleError> detalles = e.getBindingResult().getAllErrors().stream()
				.map(err -> new DetalleError(
						err instanceof org.springframework.validation.FieldError fe ? fe.getField() : err.getObjectName(),
						err.getDefaultMessage()))
				.toList();
		return validacion(detalles);
	}

	@ExceptionHandler(HandlerMethodValidationException.class)
	ResponseEntity<ErrorRespuesta> validacionMetodo(HandlerMethodValidationException e) {
		List<DetalleError> detalles = e.getParameterValidationResults().stream()
				.flatMap(r -> r.getResolvableErrors().stream()
						.map(err -> new DetalleError(r.getMethodParameter().getParameterName(),
								err.getDefaultMessage())))
				.toList();
		return validacion(detalles);
	}

	@ExceptionHandler({ HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
			MethodArgumentTypeMismatchException.class })
	ResponseEntity<ErrorRespuesta> solicitudInvalida(Exception e) {
		return responder(HttpStatus.BAD_REQUEST,
				ErrorRespuesta.de("SOLICITUD_INVALIDA", "La solicitud no es válida."));
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	ResponseEntity<ErrorRespuesta> metodo(HttpRequestMethodNotSupportedException e) {
		return responder(HttpStatus.METHOD_NOT_ALLOWED,
				ErrorRespuesta.de("METODO_NO_PERMITIDO", "Método no permitido."));
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	ResponseEntity<ErrorRespuesta> tipoMedio(HttpMediaTypeNotSupportedException e) {
		return responder(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
				ErrorRespuesta.de("TIPO_MEDIO_NO_SOPORTADO", "Tipo de contenido no soportado."));
	}

	@ExceptionHandler(NoResourceFoundException.class)
	ResponseEntity<ErrorRespuesta> noEncontrado(NoResourceFoundException e) {
		return responder(HttpStatus.NOT_FOUND,
				ErrorRespuesta.de("RECURSO_NO_ENCONTRADO", "Recurso no encontrado."));
	}

	// Las excepciones de seguridad las resuelve la cadena de filtros (401/403 JSON).
	@ExceptionHandler({ AccessDeniedException.class, AuthenticationException.class })
	void seguridad(Exception e) throws Exception {
		throw e;
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ErrorRespuesta> inesperado(Exception e) {
		log.error("Error interno no controlado", e);
		return responder(HttpStatus.INTERNAL_SERVER_ERROR,
				ErrorRespuesta.de("ERROR_INTERNO", "Ocurrió un error inesperado."));
	}

	private ResponseEntity<ErrorRespuesta> validacion(List<DetalleError> detalles) {
		return responder(HttpStatus.BAD_REQUEST,
				new ErrorRespuesta("VALIDACION", "Hay datos inválidos en la solicitud.", detalles));
	}

	private ResponseEntity<ErrorRespuesta> responder(HttpStatus estado, ErrorRespuesta cuerpo) {
		return ResponseEntity.status(estado).contentType(MediaType.APPLICATION_JSON).body(cuerpo);
	}
}
