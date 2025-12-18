package com.example.dating.annotations;

import com.example.dating.annotations.validators.MinimumAgeValidator;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/**
 * Custom validation annotation for minimum age
 * Usage: @MinimumAge(18)
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = MinimumAgeValidator.class)
@Documented

public @interface MinimumAge {

    String message() default "Must be at least {value} years old";

    int value();  // Minimum age required

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}


