package com.example.dating.annotations.validators;

import com.example.dating.annotations.MinimumAge;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.time.LocalDate;
import java.time.Period;

public class MinimumAgeValidator implements ConstraintValidator<MinimumAge, LocalDate> {

    private int minimumAge;

    @Override
    public void initialize(MinimumAge annotation) {
        this.minimumAge = annotation.value();
    }

    @Override
    public boolean isValid(LocalDate dateOfBirth, ConstraintValidatorContext context) {
        if (dateOfBirth == null) {
            return true;  // Use @NotNull for null checks
        }

        int age = Period.between(dateOfBirth, LocalDate.now()).getYears();

        if (age < minimumAge) {
            // Customize error message with actual age
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(
                    String.format("Must be at least %d years old. Current age: %d", minimumAge, age)
            ).addConstraintViolation();
            return false;
        }

        return true;
    }
}
