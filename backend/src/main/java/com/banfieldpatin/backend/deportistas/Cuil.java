package com.banfieldpatin.backend.deportistas;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/** CUIL/CUIT: 11 digitos con digito verificador correcto. Acepta null y no se cruza con el DNI. */
@Documented
@Constraint(validatedBy = CuilValidador.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
public @interface Cuil {

	String message() default "El CUIL debe tener 11 dígitos y un dígito verificador válido";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};
}
