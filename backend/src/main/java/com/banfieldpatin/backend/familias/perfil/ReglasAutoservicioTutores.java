package com.banfieldpatin.backend.familias.perfil;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.deportistas.DocumentoIdentidad;
import com.banfieldpatin.backend.familias.tutores.Tutor;
import com.banfieldpatin.backend.familias.vinculos.LockTimeoutVinculos;

/**
 * Reglas que comparten TODOS los caminos con los que una familia crea o edita a un adulto (perfil propio de C1 y «otros
 * adultos» de C2), para que ninguno las eluda.
 *
 * <ul>
 * <li><b>Serialización por familia:</b> {@link #serializarAltasDeLaFamilia} toma un bloqueo consultivo de transacción por
 * familia (después del {@code FOR SHARE} de la familia, con {@code lock_timeout}). Solo lo toman estos caminos, así que no
 * bloquea logins, cargas documentales ni la edición administrativa, que toman la familia {@code FOR SHARE}; dos altas
 * simultáneas de la misma familia se ordenan y la segunda ve a la primera (tope y duplicados sin carreras).</li>
 * <li><b>Tope:</b> adultos ACTIVOS con origen FAMILIA (perfil propio incluido). Los que carga el club no cuentan: es la vía
 * para los casos excepcionales.</li>
 * <li><b>Duplicados:</b> solo dentro de la propia familia (mismo DNI, o mismo nombre y apellido si falta un DNI) y sin decir
 * cuál; nunca se consulta otra familia.</li>
 * <li><b>Identidad respaldada:</b> la familia no cambia nombre, apellido ni DNI de un adulto VERIFICADO ni de uno con un DNI
 * vigente APROBADO. Aprobar la imagen no verifica la identidad, pero cambiarla después dejaría una imagen aprobada de otra
 * persona; la corrección pasa por el club. Los documentos se leen {@code FOR SHARE}: una aprobación concurrente espera a
 * esta transacción o esta lectura ve la aprobación. No se modifica el historial documental.</li>
 * </ul>
 */
@Component
@EnableConfigurationProperties(AutoservicioPropiedades.class)
public class ReglasAutoservicioTutores {

	/** Espacio de nombres del bloqueo consultivo (clave 1 de {@code pg_advisory_xact_lock(int, int)}). */
	static final int ESPACIO_BLOQUEO = 2102;

	private final JdbcClient jdbc;
	private final LockTimeoutVinculos lockTimeout;
	private final AutoservicioPropiedades propiedades;

	public ReglasAutoservicioTutores(JdbcClient jdbc, LockTimeoutVinculos lockTimeout, AutoservicioPropiedades propiedades) {
		this.jdbc = jdbc;
		this.lockTimeout = lockTimeout;
		this.propiedades = propiedades;
	}

	public static ExcepcionNegocio topeAlcanzado() {
		return new ExcepcionNegocio(HttpStatus.CONFLICT, "TOPE_TUTORES_ALCANZADO",
				"Tu familia ya tiene el máximo de adultos que se pueden cargar desde acá. Si necesitás sumar a otra persona, avisale al club.");
	}

	public static ExcepcionNegocio duplicado() {
		return new ExcepcionNegocio(HttpStatus.CONFLICT, "TUTOR_DUPLICADO",
				"Esa persona ya está cargada en tu familia. Revisá la lista antes de agregarla de nuevo.");
	}

	public static ExcepcionNegocio identidadConDniAprobado() {
		return new ExcepcionNegocio(HttpStatus.CONFLICT, "IDENTIDAD_CON_DNI_APROBADO",
				"El club ya aprobó el DNI de esta persona. Para cambiar el nombre, el apellido o el DNI, avisale al club.");
	}

	public int tope() {
		return propiedades.topeTutoresPorFamilia();
	}

	/**
	 * Familia activa tomada {@code FOR SHARE} (el mismo bloqueo que la edición administrativa de tutores y la carga documental):
	 * una baja concurrente de la familia espera o ya se ve. Falso si la familia no está activa.
	 */
	public boolean bloquearFamiliaActiva(UUID escuelaId, UUID familiaId) {
		return bloqueando(() -> jdbc.sql("SELECT id FROM gestion_patin.familia WHERE id = :f AND escuela_id = :e AND activa FOR SHARE")
				.param("f", familiaId).param("e", escuelaId).query(UUID.class).optional().isPresent());
	}

	/** Llamar con la familia ya tomada {@code FOR SHARE}, antes de leer el tope o los duplicados. */
	public void serializarAltasDeLaFamilia(UUID escuelaId, UUID familiaId) {
		lockTimeout.aplicar();
		bloqueando(() -> jdbc.sql("SELECT pg_advisory_xact_lock(:espacio, hashtext(:clave))").param("espacio", ESPACIO_BLOQUEO)
				.param("clave", escuelaId + "/" + familiaId).query(Object.class).optional());
	}

	/**
	 * Toma la fila del tutor ACTIVO de la familia ({@code FOR NO KEY UPDATE}) antes de leer sus documentos: el mismo orden que
	 * la carga documental (tutor y después documento), así una edición y una carga del mismo tutor no se cruzan. No bloquea
	 * las FK de los documentos nuevos (toman {@code FOR KEY SHARE}). Falso si el tutor no es de esa familia o no está activo.
	 */
	public boolean bloquearTutorDeLaFamilia(UUID escuelaId, UUID familiaId, UUID tutorId) {
		return bloqueando(() -> jdbc.sql("""
				SELECT id FROM gestion_patin.tutor
				WHERE id = :t AND escuela_id = :e AND familia_id = :f AND activo
				FOR NO KEY UPDATE
				""").param("t", tutorId).param("e", escuelaId).param("f", familiaId).query(UUID.class).optional()
				.isPresent());
	}

	/** Adultos activos que la familia cargó sola (origen FAMILIA). */
	public long cargadosPorLaFamilia(UUID escuelaId, UUID familiaId) {
		return jdbc.sql("""
				SELECT count(*) FROM gestion_patin.tutor
				WHERE escuela_id = :e AND familia_id = :f AND activo AND origen = 'FAMILIA'
				""").param("e", escuelaId).param("f", familiaId).query(Long.class).single();
	}

	/** Exige lugar para un adulto más con origen FAMILIA. Requiere {@link #serializarAltasDeLaFamilia}. */
	public void exigirLugar(UUID escuelaId, UUID familiaId) {
		if (cargadosPorLaFamilia(escuelaId, familiaId) >= propiedades.topeTutoresPorFamilia()) {
			throw topeAlcanzado();
		}
	}

	/**
	 * Otro adulto ACTIVO de la misma familia que parece la misma persona: el mismo DNI normalizado, o, cuando alguno de los dos
	 * no tiene DNI, el mismo nombre y apellido (sin distinguir mayúsculas ni espacios). Dos DNI distintos son dos personas aunque
	 * se llamen igual. El correo no cuenta: es común que una familia comparta uno. {@code excluir} es el propio tutor al editar.
	 * Requiere {@link #serializarAltasDeLaFamilia}.
	 */
	public void exigirSinDuplicado(UUID escuelaId, UUID familiaId, UUID excluir, String nombre, String apellido, String dni) {
		boolean existe = jdbc.sql("""
				SELECT EXISTS (
				  SELECT 1 FROM gestion_patin.tutor
				  WHERE escuela_id = :e AND familia_id = :f AND activo
				    AND (CAST(:excluir AS uuid) IS NULL OR id <> CAST(:excluir AS uuid))
				    AND ((CAST(:dni AS text) IS NOT NULL AND dni = CAST(:dni AS text))
				      OR ((CAST(:dni AS text) IS NULL OR dni IS NULL)
				        AND lower(btrim(nombre)) = lower(btrim(CAST(:nombre AS text)))
				        AND lower(btrim(apellido)) = lower(btrim(CAST(:apellido AS text))))))
				""").param("e", escuelaId).param("f", familiaId).param("excluir", excluir).param("dni", dni)
				.param("nombre", nombre).param("apellido", apellido).query(Boolean.class).single();
		if (existe) {
			throw duplicado();
		}
	}

	/**
	 * Edición familiar de un adulto, común a «Mis datos» y a «Otros adultos». Requiere la familia {@code FOR SHARE},
	 * {@link #serializarAltasDeLaFamilia} y {@link #bloquearTutorDeLaFamilia}, y la revisión ya comprobada. Un DNI nulo conserva
	 * el cargado (la familia nunca lo recibe completo). Si cambia la identidad: exige que no esté respaldada y que no duplique a
	 * otro adulto. Devuelve los nombres de los campos cambiados; un OBSERVADO corregido vuelve a DECLARADO.
	 */
	public List<String> editarPorFamilia(Tutor tutor, String nombre, String apellido, String dniPedido, String telefono,
			String email, String parentesco) {
		String dni = dniPedido == null ? tutor.getDni() : DocumentoIdentidad.normalizarDni(dniPedido);
		boolean cambiaIdentidad = !Objects.equals(tutor.getNombre(), nombre) || !Objects.equals(tutor.getApellido(), apellido)
				|| !Objects.equals(tutor.getDni(), dni);
		if (cambiaIdentidad) {
			exigirIdentidadModificable(tutor);
			exigirSinDuplicado(tutor.getEscuelaId(), tutor.getFamiliaId(), tutor.getId(), nombre, apellido, dni);
		}
		return tutor.actualizarPorFamilia(nombre, apellido, dni, telefono, email, parentesco);
	}

	/**
	 * La familia quiere cambiar la identidad del tutor: exige que no esté respaldada (VERIFICADO, o un DNI vigente APROBADO
	 * leído {@code FOR SHARE}).
	 */
	public void exigirIdentidadModificable(Tutor tutor) {
		if (tutor.identidadVerificada()) {
			throw PerfilPropioService.identidadVerificada();
		}
		if (tieneDniAprobado(tutor)) {
			throw identidadConDniAprobado();
		}
	}

	/**
	 * DNI del tutor vigente y APROBADO (frente o dorso), con los documentos vigentes del tutor tomados {@code FOR SHARE}.
	 * Requiere {@link #bloquearTutorDeLaFamilia} antes (orden tutor → documento).
	 */
	public boolean tieneDniAprobado(Tutor tutor) {
		return bloqueando(() -> jdbc.sql("""
				SELECT estado FROM gestion_patin.documento
				WHERE escuela_id = :e AND tutor_id = :t AND vigente AND tipo IN ('DNI_TUTOR_FRENTE', 'DNI_TUTOR_DORSO')
				FOR SHARE
				""").param("e", tutor.getEscuelaId()).param("t", tutor.getId()).query(String.class).list().stream()
				.anyMatch("APROBADO"::equals));
	}

	/** Para lecturas (sin bloqueo, válido en transacciones de solo lectura): cuáles de esos tutores tienen un DNI vigente APROBADO. */
	public Set<UUID> conDniAprobado(UUID escuelaId, Collection<UUID> tutores) {
		if (tutores.isEmpty()) {
			return Set.of();
		}
		return Set.copyOf(jdbc.sql("""
				SELECT DISTINCT tutor_id FROM gestion_patin.documento
				WHERE escuela_id = :e AND tutor_id IN (:t) AND vigente AND estado = 'APROBADO'
				  AND tipo IN ('DNI_TUTOR_FRENTE', 'DNI_TUTOR_DORSO')
				""").param("e", escuelaId).param("t", tutores).query(UUID.class).list());
	}

	/**
	 * El traductor de {@code JdbcClient} deja un plazo de bloqueo vencido (55P03), un deadlock (40P01) o un conflicto de
	 * serialización (40001) como {@code UncategorizedSQLException}: se convierten en {@code CannotAcquireLockException} para
	 * que respondan 409 {@code CONFLICTO_CONCURRENCIA} (y {@code OCUPADO} en el lote) y no 500. Mismo criterio que
	 * {@code CierreVigenciasTutor}.
	 */
	private static <T> T bloqueando(java.util.function.Supplier<T> sentencia) {
		try {
			return sentencia.get();
		} catch (UncategorizedSQLException e) {
			String estado = e.getSQLException() == null ? null : e.getSQLException().getSQLState();
			if ("55P03".equals(estado) || "40P01".equals(estado) || "40001".equals(estado)) {
				throw new CannotAcquireLockException("Conflicto de concurrencia al tomar un bloqueo del autoservicio.", e);
			}
			throw e;
		}
	}
}
