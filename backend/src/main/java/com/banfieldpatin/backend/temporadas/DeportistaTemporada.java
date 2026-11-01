package com.banfieldpatin.backend.temporadas;

import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Mapeo de gestion_patin.deportista_temporada (V1): datos deportivos de UN deportista en UNA temporada, sin asociaciones
 * (UUID planos). La escuela, el deportista y la temporada son la identidad de la fila y no se modifican. Los datos se
 * reemplazan solo con {@link #reemplazar}, que informa los NOMBRES de los campos modificados (nunca sus valores). Las marcas
 * de tiempo las asigna la base y no se mapean.
 */
@Entity
@Table(name = "deportista_temporada")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeportistaTemporada {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "escuela_id", nullable = false, updatable = false)
	private UUID escuelaId;

	@Column(name = "deportista_id", nullable = false, updatable = false)
	private UUID deportistaId;

	@Column(name = "temporada_id", nullable = false, updatable = false)
	private UUID temporadaId;

	private String categoria;

	private String rama;

	private String divisional;

	@Column(name = "numero_corredor")
	private String numeroCorredor;

	@Enumerated(EnumType.STRING)
	@Column(name = "estado_deportivo", nullable = false)
	private EstadoDeportivo estadoDeportivo;

	public static DeportistaTemporada crear(UUID escuelaId, UUID deportistaId, UUID temporadaId, String categoria,
			String rama, String divisional, String numeroCorredor, EstadoDeportivo estadoDeportivo) {
		DeportistaTemporada dt = new DeportistaTemporada();
		dt.escuelaId = escuelaId;
		dt.deportistaId = deportistaId;
		dt.temporadaId = temporadaId;
		dt.categoria = categoria;
		dt.rama = rama;
		dt.divisional = divisional;
		dt.numeroCorredor = numeroCorredor;
		dt.estadoDeportivo = estadoDeportivo;
		return dt;
	}

	/** Reemplazo completo; devuelve los nombres de los campos cuyo valor cambio, en el orden de la solicitud. */
	public List<String> reemplazar(String categoria, String rama, String divisional, String numeroCorredor,
			EstadoDeportivo estadoDeportivo) {
		throw new UnsupportedOperationException("pendiente");
	}
}
