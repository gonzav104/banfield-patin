package com.banfieldpatin.backend.seguridad;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/** Politica de contrasenas (REQ-AUTH-11): minimo 10 caracteres y maximo 72 bytes UTF-8. */
@Documented
@Constraint(validatedBy = ValidadorContrasena.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
public @interface ContrasenaValida {

	String message() default "La contrasena debe tener entre 10 caracteres y 72 bytes";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};
}
