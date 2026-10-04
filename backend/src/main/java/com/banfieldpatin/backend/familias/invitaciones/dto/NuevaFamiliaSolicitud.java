package com.banfieldpatin.backend.familias.invitaciones.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record NuevaFamiliaSolicitud(@NotBlank @Size(max = 150) String nombreReferencia) {
}
