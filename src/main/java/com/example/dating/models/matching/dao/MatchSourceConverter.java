package com.example.dating.models.matching.dao;

import com.example.dating.enums.matching.MatchSource;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA converter that maps {@link MatchSource} enum constants to lowercase underscore
 * strings in the database column (e.g. {@code MUTUAL_SWIPE} ↔ {@code "mutual_swipe"}).
 *
 * <p>{@code autoApply = true} means every entity field of type {@link MatchSource}
 * is converted automatically without requiring a {@code @Convert} annotation on the field.
 *
 * <p>Because the DB column already stores lowercase values ({@code "mutual_swipe"},
 * {@code "super_like"}) from the original string-field era, no data migration is needed.
 */
@Converter(autoApply = true)
public class MatchSourceConverter implements AttributeConverter<MatchSource, String> {

    @Override
    public String convertToDatabaseColumn(MatchSource source) {
        return source == null ? null : source.getValue();
    }

    @Override
    public MatchSource convertToEntityAttribute(String value) {
        if (value == null) {
            return null;
        }
        return MatchSource.valueOf(value.toUpperCase());
    }
}
