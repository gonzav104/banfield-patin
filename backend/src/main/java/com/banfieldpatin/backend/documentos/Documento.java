package com.banfieldpatin.backend.documentos;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
/** Vista JPA inmutable del esquema V1. No mapea binarios, keys, bucket ni nombres originales. */
@Entity
@Table(name = "documento")
@Immutable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Documento {

    @Id

    private UUID id;

    @Column(name = "escuela_id", nullable = false)
    private UUID escuelaId;

    @Column(name = "deportista_id")
    private UUID deportistaId;

    @Column(name = "tutor_id")
    private UUID tutorId;

    @Column(name = "temporada_id")
    private UUID temporadaId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private TipoDocumento tipo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private EstadoDocumento estado;

    @Column(nullable = false)
    private boolean vigente;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;

    @Column(name = "fecha_vencimiento")
    private LocalDate fechaVencimiento;

    @Column(name = "motivo_observacion", length = 500)
    private String motivoObservacion;
}
