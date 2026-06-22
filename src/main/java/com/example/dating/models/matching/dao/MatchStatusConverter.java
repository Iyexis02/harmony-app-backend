package com.example.dating.models.matching.dao;

import com.example.dating.enums.matching.MatchStatus;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA converter that maps {@link MatchStatus} enum constants to lowercase strings
 * in the database column (e.g. {@code ACTIVE} ↔ {@code "active"}).
 *
 * <p>{@code autoApply = true} means every entity field of type {@link MatchStatus}
 * is converted automatically without requiring a {@code @Convert} annotation on the field.
 *
 * <p>Because the DB column already stores lowercase values from the original string-field
 * era, no data migration is needed — the converter simply preserves the existing format.
 */
@Converter(autoApply = true)
public class MatchStatusConverter implements AttributeConverter<MatchStatus, String> {

    @Override
    public String convertToDatabaseColumn(MatchStatus status) {
        return status == null ? null : status.getValue();
    }

    @Override
    public MatchStatus convertToEntityAttribute(String value) {
        if (value == null) {
            return null;
        }
        return MatchStatus.valueOf(value.toUpperCase());
    }
}
