package com.banfieldpatin.backend.familias.tutores.dto;

import com.banfieldpatin.backend.deportistas.Dni;
import com.banfieldpatin.backend.familias.dto.VersionFamiliaDecimal;
import com.fasterxml.jackson.annotation.JsonAnySetter;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Alta de un tutor. Exige la revision de la familia que el ADMIN observo ({@code versionFamiliaEsperada}, cadena
 * decimal): el alta solo vale si la familia sigue en esa revision, es decir, si no cambio de nombre ni de estado desde que se
 * la miro. Todo se recorta; un opcional en blanco pasa a null y el email se guarda en minusculas. El DNI conserva el
 * formato recibido (hasta 20 caracteres, con puntos o espacios): el servicio lo normaliza. La escuela, la familia, el
 * usuario y el estado salen del contexto, nunca del cuerpo: cualquier otra propiedad (familiaId, usuarioId, escuelaId,
 * activo, ...) se rechaza con 400 SOLICITUD_INVALIDA sin eco del valor.
 */
public record TutorSolicitud(
		@NotBlank @Size(max = 100) String nombre,
		@NotBlank @Size(max = 100) String apellido,
		@Size(max = 20) @Dni String dni,
		@Size(max = 40) String telefono,
		@Email @Size(max = 180) String email,
		@Size(max = 40) String parentesco,
		@Schema(type = "string", pattern = "[0-9]{1,19}", description = "Revision de la familia observada, decimal no negativa dentro de BIGINT")
		@NotNull @PositiveOrZero @JsonDeserialize(using = VersionFamiliaDecimal.class) Long versionFamiliaEsperada)
		implements DatosTutor {

	public TutorSolicitud {
		nombre = DatosTutor.recortar(nombre);
		apellido = DatosTutor.recortar(apellido);
		dni = DatosTutor.opcional(dni);
		telefono = DatosTutor.opcional(telefono);
		email = DatosTutor.emailNormalizado(email);
		parentesco = DatosTutor.opcional(parentesco);
	}

	@JsonAnySetter
	void rechazarPropiedadDesconocida(String nombre, Object valor) {
		throw new IllegalArgumentException("Propiedad no admitida");
	}

	/** Los datos personales no se imprimen nunca (logs, excepciones). */
	@Override
	public String toString() {
		return "TutorSolicitud[***]";
	}
}
