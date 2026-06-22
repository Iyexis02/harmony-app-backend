package com.example.dating.models.matching.dao;

import com.example.dating.enums.matching.GenrePreferenceSource;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA converter that maps {@link GenrePreferenceSource} enum constants to lowercase underscore
 * strings in the database column (e.g. {@code SPOTIFY_DERIVED} ↔ {@code "spotify_derived"}).
 *
 * <p>{@code autoApply = true} means every entity field of type {@link GenrePreferenceSource}
 * is converted automatically without requiring a {@code @Convert} annotation on the field.
 *
 * <p>Existing DB values ({@code "spotify_derived"}, {@code "manual_selection"},
 * {@code "seed_data"}) are compatible without a data migration — the stored lowercase strings
 * match the enum constant names after {@code toUpperCase()}.
 */
@Converter(autoApply = true)
public class GenrePreferenceSourceConverter implements AttributeConverter<GenrePreferenceSource, String> {

    @Override
    public String convertToDatabaseColumn(GenrePreferenceSource source) {
        return source == null ? null : source.getValue();
    }

    @Override
    public GenrePreferenceSource convertToEntityAttribute(String value) {
        if (value == null) {
            return null;
        }
        return GenrePreferenceSource.valueOf(value.toUpperCase());
    }
}
