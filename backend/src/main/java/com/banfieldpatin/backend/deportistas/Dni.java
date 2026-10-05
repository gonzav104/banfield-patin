package com.banfieldpatin.backend.deportistas;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * DNI argentino: entre 7 y 9 digitos una vez quitados puntos y espacios. Acepta null (la obligatoriedad la decide
 * {@code @NotBlank}).
 */
@Documented
@Constraint(validatedBy = DniValidador.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
public @interface Dni {

	String message() default "El DNI debe tener entre 7 y 9 dígitos";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};
}
