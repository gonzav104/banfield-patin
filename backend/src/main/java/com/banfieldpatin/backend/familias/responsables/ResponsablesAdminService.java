package com.banfieldpatin.backend.familias.responsables;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.banfieldpatin.backend.compartido.error.DetalleError;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.deportistas.Deportista;
import com.banfieldpatin.backend.deportistas.DeportistaAdminService;
import com.banfieldpatin.backend.deportistas.DeportistaRepository;
import com.banfieldpatin.backend.familias.Familia;
import com.banfieldpatin.backend.familias.FamiliaAdminService;
import com.banfieldpatin.backend.familias.FamiliaRepository;
import com.banfieldpatin.backend.familias.tutores.Tutor;
import com.banfieldpatin.backend.familias.tutores.TutorAdminService;
import com.banfieldpatin.backend.familias.tutores.TutorRepository;
import com.banfieldpatin.backend.familias.vinculos.FamiliaDeportistaRepository;
import com.banfieldpatin.backend.familias.vinculos.LockTimeoutVinculos;
import com.banfieldpatin.backend.familias.vinculos.RevisionVinculos;
import com.banfieldpatin.backend.seguridad.UsuarioAutenticado;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;

/**
 * Alta individual, edición del tipo declarado y revocación de responsables (vigencias tutor–deportista) para ADMIN.
 * Escuela y actor salen siempre del JWT; un recurso de otra escuela es indistinguible de uno inexistente (404).
 *
 * <p><b>Alta.</b> Orden: bandera (503 antes de leer nada) → ADMIN → plazo de bloqueo → deportista {@code FOR UPDATE}
 * (404) → repetición de la clave: se resuelve en la base y devuelve la vigencia original, también cerrada, sin exigir
 * revisiones (la autorización y el aislamiento se comprueban igual) → tutor y familia del tutor (404) → revisiones
 * observadas de familia, deportista, tutor y vínculos (409 EDICION_DESACTUALIZADA) → bloqueos {@code FOR SHARE} de familia
 * y tutor con el predicado de versión de Hibernate → función V16, que comprueba los cuatro estados (409
 * ESTADO_INCOMPATIBLE) con el mismo bloqueo que las FKs de V14. Orden global de bloqueos: deportista → familia → tutor →
 * marcas → vigencias. El alta no incrementa {@code versionVinculos}: no cambia {@code familia_deportista}.
 *
 * <p><b>Clave de idempotencia.</b> {@code Idempotency-Key} (UUID) es {@code solicitud_id}, único por escuela. La huella
 * (SHA-256) la calcula el servidor sobre escuela, deportista, tutor y tipo; no incluye las revisiones, que cambian entre
 * un intento y su repetición. Misma clave y huella: la vigencia original. Misma clave y otra huella: 409
 * IDEMPOTENCY_KEY_REUTILIZADA. Una nueva responsabilidad requiere una clave nueva.
 *
 * <p><b>Edición y revocación.</b> Solo toman la fila de la vigencia (última en el orden global): revisión de la vigencia
 * (409 EDICION_DESACTUALIZADA), vigencia cerrada (409 ESTADO_INCOMPATIBLE), sin cambio efectivo no se escribe, audita ni
 * mueve la revisión. La auditoría se escribe en la misma transacción desde la función.
 */
@Service
@EnableConfigurationProperties(ResponsablesPropiedades.class)
public class ResponsablesAdminService {

	/** {@code creada} es false cuando la solicitud repite una ya procesada. */
	public record ResultadoAlta(VinculoTutorRespuesta respuesta, boolean creada) {
	}

	private final DeportistaRepository deportistas;
	private final FamiliaRepository familias;
	private final TutorRepository tutores;
	private final FamiliaDeportistaRepository vinculos;
	private final TutorDeportistaRepository vigencias;
	private final EscrituraVigenciasTutor escritura;
	private final LockTimeoutVinculos lockTimeout;
	private final ResponsablesPropiedades propiedades;
	private final EntityManager em;

	public ResponsablesAdminService(DeportistaRepository deportistas, FamiliaRepository familias,
			TutorRepository tutores, FamiliaDeportistaRepository vinculos, TutorDeportistaRepository vigencias,
			EscrituraVigenciasTutor escritura, LockTimeoutVinculos lockTimeout, ResponsablesPropiedades propiedades,
			EntityManager em) {
		this.deportistas = deportistas;
		this.familias = familias;
		this.tutores = tutores;
		this.vinculos = vinculos;
		this.vigencias = vigencias;
		this.escritura = escritura;
		this.lockTimeout = lockTimeout;
		this.propiedades = propiedades;
		this.em = em;
	}

	public static ExcepcionNegocio responsableNoEncontrado() {
		return new ExcepcionNegocio(HttpStatus.NOT_FOUND, "RESPONSABLE_NO_ENCONTRADO", "El responsable no existe.");
	}

	@Transactional
	public ResultadoAlta crear(UsuarioAutenticado admin, UUID deportistaId, UUID idempotencia, ResponsableAlta pedido) {
		if (!propiedades.altaHabilitada()) {
			throw new ExcepcionNegocio(HttpStatus.SERVICE_UNAVAILABLE, "ALTA_RESPONSABLES_DESHABILITADA",
					"El alta de responsables no está habilitada.");
		}
		CierreVigenciasTutor.exigirAdmin(admin);
		lockTimeout.aplicar();
		UUID escuelaId = admin.escuelaId();
		Deportista deportista = deportistas.bloquearParaVincular(escuelaId, List.of(deportistaId)).stream().findFirst()
				.orElseThrow(DeportistaAdminService::deportistaNoEncontrado);
		String huella = huella(escuelaId, deportistaId, pedido.tutorId(), pedido.tipo());

		if (!vigencias.existeSolicitud(escuelaId, idempotencia)) {
			Tutor tutor = tutores.findByIdAndEscuelaId(pedido.tutorId(), escuelaId)
					.orElseThrow(TutorAdminService::tutorNoEncontrado);
			Familia familia = familias.findByIdAndEscuelaId(tutor.getFamiliaId(), escuelaId)
					.orElseThrow(FamiliaAdminService::familiaNoEncontrada);
			exigirRevisiones(pedido, familia, deportista, tutor, revisionVinculos(escuelaId, deportistaId));
			// FOR SHARE con el predicado de versión: una baja concurrente no se confirma mientras se escribe la vigencia.
			bloquearParaLeer(familia);
			bloquearParaLeer(tutor);
		}
		var alta = escritura.crear(admin, deportistaId, pedido.tutorId(), pedido.tipo(), idempotencia, huella);
		return new ResultadoAlta(leer(escuelaId, deportistaId, alta.vigenciaId()), alta.creada());
	}

	@Transactional
	public VinculoTutorRespuesta actualizarTipo(UsuarioAutenticado admin, UUID deportistaId, UUID vigenciaId,
			ResponsableTipo pedido) {
		CierreVigenciasTutor.exigirAdmin(admin);
		lockTimeout.aplicar();
		exigirDelDeportista(admin.escuelaId(), deportistaId, vigenciaId);
		escritura.actualizarTipo(admin, vigenciaId, pedido.versionEsperada(), pedido.tipo());
		return leer(admin.escuelaId(), deportistaId, vigenciaId);
	}

	@Transactional
	public VinculoTutorRespuesta revocar(UsuarioAutenticado admin, UUID deportistaId, UUID vigenciaId,
			ResponsableRevision pedido) {
		CierreVigenciasTutor.exigirAdmin(admin);
		lockTimeout.aplicar();
		exigirDelDeportista(admin.escuelaId(), deportistaId, vigenciaId);
		escritura.revocar(admin, vigenciaId, pedido.versionEsperada());
		return leer(admin.escuelaId(), deportistaId, vigenciaId);
	}

	private void exigirDelDeportista(UUID escuelaId, UUID deportistaId, UUID vigenciaId) {
		if (!vigencias.existeDelDeportista(escuelaId, deportistaId, vigenciaId)) {
			throw responsableNoEncontrado();
		}
	}

	private VinculoTutorRespuesta leer(UUID escuelaId, UUID deportistaId, UUID vigenciaId) {
		return vigencias.respuesta(escuelaId, deportistaId, vigenciaId).orElseThrow(
				ResponsablesAdminService::responsableNoEncontrado);
	}

	/**
	 * {@code EntityManager.lock} no pasa por la traducción de excepciones de los repositorios: un plazo vencido (55P03) o
	 * una instantánea que no ve la baja confirmada (40001) llegarían como excepciones de JPA y terminarían en 500.
	 */
	private void bloquearParaLeer(Object entidad) {
		try {
			em.lock(entidad, LockModeType.PESSIMISTIC_READ);
		} catch (PessimisticLockException | LockTimeoutException e) {
			throw new CannotAcquireLockException("Conflicto de concurrencia al bloquear el alta de responsables.", e);
		}
	}

	private long revisionVinculos(UUID escuelaId, UUID deportistaId) {
		return vinculos.revisionesVinculos(escuelaId, List.of(deportistaId)).stream().findFirst()
				.map(RevisionVinculos::versionVinculos).orElseThrow(DeportistaAdminService::deportistaNoEncontrado);
	}

	/** Una entrada por campo observado que ya no es el vigente; nunca se devuelven los valores. */
	private static void exigirRevisiones(ResponsableAlta pedido, Familia familia, Deportista deportista, Tutor tutor,
			long versionVinculos) {
		List<DetalleError> obsoletas = new ArrayList<>();
		if (pedido.versionFamilia() != familia.getVersion()) {
			obsoletas.add(new DetalleError("versionFamilia", "La revisión de la familia cambió."));
		}
		if (pedido.versionDeportista() != deportista.getVersion()) {
			obsoletas.add(new DetalleError("versionDeportista", "La revisión del deportista cambió."));
		}
		if (pedido.versionTutor() != tutor.getVersion()) {
			obsoletas.add(new DetalleError("versionTutor", "La revisión del tutor cambió."));
		}
		if (pedido.versionVinculos() != versionVinculos) {
			obsoletas.add(new DetalleError("versionVinculos", "La revisión de los vínculos del deportista cambió."));
		}
		if (!obsoletas.isEmpty()) {
			throw new ExcepcionNegocio(HttpStatus.CONFLICT, "EDICION_DESACTUALIZADA",
					FamiliaAdminService.edicionDesactualizada().getMessage(), obsoletas);
		}
	}

	/** SHA-256 hexadecimal; {@code -} distingue el tipo ausente del tipo SIN_DETERMINAR. */
	static String huella(UUID escuelaId, UUID deportistaId, UUID tutorId, TipoVinculoTutor tipo) {
		String canonico = String.join("\n", "responsable-alta/v1", escuelaId.toString(), deportistaId.toString(),
				tutorId.toString(), tipo == null ? "-" : tipo.name());
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(canonico.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
