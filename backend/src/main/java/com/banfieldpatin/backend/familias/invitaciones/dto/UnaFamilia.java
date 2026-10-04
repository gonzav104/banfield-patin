package com.banfieldpatin.backend.familias.invitaciones.dto;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/** Exige exactamente una de las dos formas de elegir familia: familiaId existente o nuevaFamilia en linea. */
@Documented
@Constraint(validatedBy = UnaFamiliaValidador.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface UnaFamilia {

	String message() default "Indicá familiaId o nuevaFamilia, pero no ambos ni ninguno";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};
}
