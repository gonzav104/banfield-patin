package com.banfieldpatin.backend.familias.vinculos;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.banfieldpatin.backend.compartido.auditoria.AccionAuditoria;
import com.banfieldpatin.backend.compartido.auditoria.AuditoriaService;
import com.banfieldpatin.backend.compartido.auditoria.EventoAuditoria;
import com.banfieldpatin.backend.compartido.error.DetalleError;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.compartido.error.RestriccionViolada;
import com.banfieldpatin.backend.compartido.web.DatosSolicitud;
import com.banfieldpatin.backend.deportistas.Deportista;
import com.banfieldpatin.backend.deportistas.DeportistaAdminService;
import com.banfieldpatin.backend.deportistas.DeportistaRepository;
import com.banfieldpatin.backend.familias.Familia;
import com.banfieldpatin.backend.familias.FamiliaAdminService;
import com.banfieldpatin.backend.familias.FamiliaRepository;
import com.banfieldpatin.backend.familias.vinculos.dto.ResultadoVinculacion;
import com.banfieldpatin.backend.familias.vinculos.dto.VinculacionRespuesta;
import com.banfieldpatin.backend.familias.vinculos.dto.VinculoRespuesta;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;

/**
 * Vinculos familia-deportista para ADMIN. La escuela y el actor salen del JWT; un id de otra escuela es indistinguible de
 * uno inexistente.
 *
 * <p>Concurrencia: toda vinculacion, revocacion o cambio de principal toma primero el bloqueo de FILA del deportista
 * ({@code FOR UPDATE}, en orden de id, asi que dos lotes con ids superpuestos en orden inverso no se bloquean entre si)
 * y recien despues lee los vinculos: dentro de una transaccion READ COMMITTED cada sentencia ve lo que la anterior
 * confirmo, de modo que las decisiones de principal se toman sobre datos vigentes. El indice parcial unico
 * {@code uq_fd_principal_activo} (V4) es la red de seguridad: si aun asi se violara, la respuesta es 409
 * VINCULO_PRINCIPAL_EN_CONFLICTO y toda la transaccion (incluida la auditoria) se revierte. La fila de la familia NO se
 * bloquea: una desactivacion concurrente de la familia es benigna (una familia inactiva no concede nada y el vinculo
 * resultante es visible y revocable por el ADMIN).
 *
 * <p>Espera acotada: las tres transacciones que bloquean ejecutan {@code SET LOCAL lock_timeout} (ver
 * {@link LockTimeoutVinculos}) antes de su primera sentencia. Si otra transaccion retiene el deportista mas de ese plazo,
 * la base responde 55P03, Spring lo traduce a {@code CannotAcquireLockException} y el manejador global responde 409
 * CONFLICTO_CONCURRENCIA; la transaccion se revierte (nada escrito, nada auditado).
 *
 * <p>La auditoria (solo ids, banderas y nombres de campos) se escribe en la transaccion del cambio y solo ante cambios
 * reales. Ningun log contiene DNI, CUIL ni nombres.
 */
@Service
public class VinculoAdminService {

	private static final String RECURSO = "FAMILIA_DEPORTISTA";
	private static final String RESTRICCION_PRINCIPAL = "uq_fd_principal_activo";
	private static final String RESTRICCION_PAR = "uq_familia_deportista";

	private final FamiliaRepository familias;
	private final DeportistaRepository deportistas;
	private final FamiliaDeportistaRepository vinculos;
	private final AuditoriaService auditoria;
	private final Clock reloj;
	private final LockTimeoutVinculos lockTimeout;

	public VinculoAdminService(FamiliaRepository familias, DeportistaRepository deportistas,
			FamiliaDeportistaRepository vinculos, AuditoriaService auditoria, Clock reloj,
			LockTimeoutVinculos lockTimeout) {
		this.familias = familias;
		this.deportistas = deportistas;
		this.vinculos = vinculos;
		this.auditoria = auditoria;
		this.reloj = reloj;
		this.lockTimeout = lockTimeout;
	}

	public static ExcepcionNegocio vinculoNoEncontrado() {
		return new ExcepcionNegocio(HttpStatus.NOT_FOUND, "VINCULO_NO_ENCONTRADO", "El vinculo no existe.");
	}

	static ExcepcionNegocio vinculoNoActivo() {
		return new ExcepcionNegocio(HttpStatus.CONFLICT, "VINCULO_NO_ACTIVO", "El vinculo no esta activo.");
	}

	static ExcepcionNegocio conflictoConcurrente() {
		return new ExcepcionNegocio(HttpStatus.CONFLICT, "VINCULO_PRINCIPAL_EN_CONFLICTO",
				"Otro cambio sobre el mismo deportista ocurrio al mismo tiempo. Reintentá.");
	}

	// ---------- lecturas ----------

	@Transactional(readOnly = true)
	public List<VinculoRespuesta> listarDeFamilia(UsuarioAutenticado admin, UUID familiaId) {
		cargarFamilia(admin, familiaId);
		return vinculos.deFamilia(admin.escuelaId(), familiaId);
	}

	@Transactional(readOnly = true)
	public List<VinculoRespuesta> listarDeDeportista(UsuarioAutenticado admin, UUID deportistaId) {
		deportistas.findByIdAndEscuelaId(deportistaId, admin.escuelaId())
				.orElseThrow(DeportistaAdminService::deportistaNoEncontrado);
		return vinculos.deDeportista(admin.escuelaId(), deportistaId);
	}

	// ---------- vincular (lote, todo o nada) ----------

	/**
	 * Precedencia: familia 404 -> familia inactiva 409 (primero, incluso para un lote que seria todo SIN_CAMBIOS) ->
	 * bloqueo de los deportistas -> algun id inexistente o de otra escuela 404 (lista cada posicion {@code deportistaIds[i]},
	 * nunca el valor) -> algun deportista inactivo 409 -> escritura. Cualquier fallo no deja nada escrito ni auditado.
	 * Los ids repetidos se quitan conservando el orden; el tope de 50 se valida antes (en el DTO).
	 */
	@Transactional
	public VinculacionRespuesta vincular(UsuarioAutenticado admin, UUID familiaId, List<UUID> solicitados,
			DatosSolicitud datos) {
		lockTimeout.aplicar();
		UUID escuelaId = admin.escuelaId();
		Familia familia = cargarFamilia(admin, familiaId);
		if (!familia.isActiva()) {
			throw FamiliaAdminService.familiaInactiva();
		}
		List<UUID> ids = solicitados.stream().distinct().toList();
		Map<UUID, Deportista> porId = deportistas.bloquearParaVincular(escuelaId, ids).stream()
				.collect(Collectors.toMap(Deportista::getId, Function.identity()));
		exigirExistentes(solicitados, porId);
		exigirActivos(solicitados, porId);

		Map<UUID, FamiliaDeportista> existentes = vinculos.deFamiliaYDeportistas(escuelaId, familiaId, ids).stream()
				.collect(Collectors.toMap(FamiliaDeportista::getDeportistaId, Function.identity()));
		Set<UUID> conPrincipal = new HashSet<>(vinculos.principalesActivos(escuelaId, ids));
		Instant ahora = Instant.now(reloj);

		List<VinculacionRespuesta.Resultado> resultados = new ArrayList<>();
		for (UUID deportistaId : ids) {
			Deportista deportista = porId.get(deportistaId);
			FamiliaDeportista vinculo = existentes.get(deportistaId);
			if (vinculo != null && vinculo.getEstado() == EstadoVinculo.ACTIVO) {
				resultados.add(resultado(vinculo, familia, deportista, ResultadoVinculacion.SIN_CAMBIOS));
				continue;
			}
			// Principal solo si no hay otro ACTIVO principal; se decide con el deportista bloqueado.
			boolean principal = !conPrincipal.contains(deportistaId);
			ResultadoVinculacion resultado;
			if (vinculo == null) {
				vinculo = FamiliaDeportista.activo(escuelaId, familiaId, deportistaId, admin.id(), ahora, principal);
				resultado = ResultadoVinculacion.CREADO;
			} else {
				vinculo.reactivar(admin.id(), ahora, principal);
				resultado = ResultadoVinculacion.REACTIVADO;
			}
			vinculo = guardar("vincular", vinculo);
			if (principal) {
				conPrincipal.add(deportistaId);
			}
			registrar(admin, AccionAuditoria.VINCULO_ACTIVADO, vinculo,
					Map.of("familiaId", familiaId, "deportistaId", deportistaId, "esPrincipal", principal, "origen",
							resultado == ResultadoVinculacion.CREADO ? "NUEVO" : "REUTILIZADO"),
					datos);
			resultados.add(resultado(vinculo, familia, deportista, resultado));
		}
		return new VinculacionRespuesta(resultados);
	}

	private static VinculacionRespuesta.Resultado resultado(FamiliaDeportista vinculo, Familia familia,
			Deportista deportista, ResultadoVinculacion resultado) {
		return new VinculacionRespuesta.Resultado(VinculoRespuesta.de(vinculo, familia, deportista), resultado);
	}

	private static void exigirExistentes(List<UUID> solicitados, Map<UUID, Deportista> porId) {
		List<DetalleError> faltantes = detalles(solicitados, id -> !porId.containsKey(id), "El deportista no existe.");
		if (!faltantes.isEmpty()) {
			throw new ExcepcionNegocio(HttpStatus.NOT_FOUND, "DEPORTISTA_NO_ENCONTRADO",
					"Algun deportista de la solicitud no existe.", faltantes);
		}
	}

	private static void exigirActivos(List<UUID> solicitados, Map<UUID, Deportista> porId) {
		List<DetalleError> inactivos = detalles(solicitados, id -> !porId.get(id).isActivo(),
				"El deportista esta inactivo.");
		if (!inactivos.isEmpty()) {
			throw new ExcepcionNegocio(HttpStatus.CONFLICT, "DEPORTISTA_INACTIVO",
					"Algun deportista de la solicitud esta inactivo. Reactivalo para vincularlo.", inactivos);
		}
	}

	/** Una entrada por cada POSICION de la solicitud que cumple el predicado (nunca se devuelve el valor del id). */
	private static List<DetalleError> detalles(List<UUID> solicitados, Predicate<UUID> alcanza,
			String mensaje) {
		List<DetalleError> detalles = new ArrayList<>();
		for (int i = 0; i < solicitados.size(); i++) {
			if (alcanza.test(solicitados.get(i))) {
				detalles.add(new DetalleError("deportistaIds[" + i + "]", mensaje));
			}
		}
		return detalles;
	}

	// ---------- revocar ----------

	/**
	 * Siempre permitido (tambien con familia o deportista inactivos). Bajo el bloqueo del deportista, una actualizacion
	 * CONDICIONAL ACTIVO -> REVOCADO decide si hubo cambio real: solo entonces se audita. Repetir es un 200 idempotente
	 * sin auditoria; PENDIENTE/RECHAZADO -> 409 VINCULO_NO_ACTIVO; sin vinculo -> 404. No promueve a otro vinculo.
	 */
	@Transactional
	public VinculoRespuesta revocar(UsuarioAutenticado admin, UUID familiaId, UUID deportistaId, DatosSolicitud datos) {
		lockTimeout.aplicar();
		UUID escuelaId = admin.escuelaId();
		deportistas.bloquearParaVincular(escuelaId, List.of(deportistaId));
		FamiliaDeportista vinculo = vinculos.buscarVinculo(escuelaId, familiaId, deportistaId)
				.orElseThrow(VinculoAdminService::vinculoNoEncontrado);
		if (vinculo.getEstado() == EstadoVinculo.PENDIENTE || vinculo.getEstado() == EstadoVinculo.RECHAZADO) {
			throw vinculoNoActivo();
		}
		boolean eraPrincipal = vinculo.isEsPrincipal();
		UUID vinculoId = vinculo.getId();
		if (vinculo.getEstado() == EstadoVinculo.ACTIVO
				&& vinculos.revocarSiActivo(escuelaId, familiaId, deportistaId) == 1) {
			auditoria.registrar(new EventoAuditoria(escuelaId, admin.id(), AccionAuditoria.VINCULO_REVOCADO, RECURSO,
					vinculoId, Map.of("familiaId", familiaId, "deportistaId", deportistaId, "eraPrincipal", eraPrincipal),
					datos));
		}
		return vinculos.respuesta(escuelaId, familiaId, deportistaId).orElseThrow(VinculoAdminService::vinculoNoEncontrado);
	}

	// ---------- cambiar el principal ----------

	/**
	 * Unico camino para cambiar el principal (nunca revocar y recrear), solo entre vinculos ACTIVOS y con la familia
	 * activa. Orden: familia 404 -> familia inactiva 409 -> bloqueo del deportista -> vinculo 404 -> no ACTIVO 409 ->
	 * ya es principal: 200 sin escribir ni auditar -> bajar al principal actual (aunque su familia este inactiva: solo se
	 * valida la familia destino) y subir el elegido, cada uno con {@code saveAndFlush} y en ESE orden para que
	 * {@code uq_fd_principal_activo} no se viole nunca a mitad de la transaccion.
	 */
	@Transactional
	public VinculoRespuesta cambiarPrincipal(UsuarioAutenticado admin, UUID familiaId, UUID deportistaId,
			DatosSolicitud datos) {
		lockTimeout.aplicar();
		UUID escuelaId = admin.escuelaId();
		Familia familia = cargarFamilia(admin, familiaId);
		if (!familia.isActiva()) {
			throw FamiliaAdminService.familiaInactiva();
		}
		Deportista deportista = deportistas.bloquearParaVincular(escuelaId, List.of(deportistaId)).stream().findFirst()
				.orElseThrow(VinculoAdminService::vinculoNoEncontrado);
		FamiliaDeportista vinculo = vinculos.buscarVinculo(escuelaId, familiaId, deportistaId)
				.orElseThrow(VinculoAdminService::vinculoNoEncontrado);
		if (vinculo.getEstado() != EstadoVinculo.ACTIVO) {
			throw vinculoNoActivo();
		}
		if (vinculo.isEsPrincipal()) {
			return VinculoRespuesta.de(vinculo, familia, deportista);
		}
		FamiliaDeportista anterior = vinculos.principalActivo(escuelaId, deportistaId).orElse(null);
		if (anterior != null) {
			anterior.quitarPrincipal();
			guardar("cambiarPrincipal", anterior);
		}
		vinculo.marcarPrincipal();
		guardar("cambiarPrincipal", vinculo);
		Map<String, Object> detalle = new LinkedHashMap<>();
		detalle.put("familiaId", familiaId);
		detalle.put("deportistaId", deportistaId);
		detalle.put("anteriorVinculoId", anterior == null ? null : anterior.getId());
		registrar(admin, AccionAuditoria.VINCULO_PRINCIPAL_CAMBIADO, vinculo, detalle, datos);
		return VinculoRespuesta.de(vinculo, familia, deportista);
	}

	// ---------- auxiliares ----------

	private Familia cargarFamilia(UsuarioAutenticado admin, UUID familiaId) {
		return familias.findByIdAndEscuelaId(familiaId, admin.escuelaId())
				.orElseThrow(FamiliaAdminService::familiaNoEncontrada);
	}

	/**
	 * {@code saveAndFlush} dentro del try: si la base arbitra una carrera que el bloqueo no evito
	 * (uq_fd_principal_activo, o uq_familia_deportista si dos altas del mismo par se cruzaran) la respuesta es 409
	 * VINCULO_PRINCIPAL_EN_CONFLICTO con un WARN saneado; cualquier otra violacion se relanza (500 saneado en el manejador).
	 */
	private FamiliaDeportista guardar(String operacion, FamiliaDeportista vinculo) {
		try {
			return vinculos.saveAndFlush(vinculo);
		} catch (DataIntegrityViolationException e) {
			String restriccion = RestriccionViolada.nombre(e).orElse("");
			if (RESTRICCION_PRINCIPAL.equals(restriccion) || RESTRICCION_PAR.equals(restriccion)) {
				RestriccionViolada.registrarMapeada("VinculoAdminService." + operacion, e);
				throw conflictoConcurrente();
			}
			throw e;
		}
	}

	private void registrar(UsuarioAutenticado admin, AccionAuditoria accion, FamiliaDeportista vinculo,
			Map<String, Object> detalle, DatosSolicitud datos) {
		auditoria.registrar(new EventoAuditoria(admin.escuelaId(), admin.id(), accion, RECURSO, vinculo.getId(), detalle,
				datos));
	}
}
