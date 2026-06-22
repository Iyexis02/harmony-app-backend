package com.example.dating.enums.matching;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Type-safe representation of how a user genre preference was established.
 *
 * <p>The companion {@link com.example.dating.models.matching.dao.GenrePreferenceSourceConverter}
 * (autoApply) maps each constant to its lowercase underscore DB column value so existing rows
 * ({@code "spotify_derived"}, {@code "manual_selection"}, {@code "seed_data"}) are readable
 * without a data migration.
 *
 * <p>{@link #getValue()} is annotated with {@code @JsonValue} so Jackson serialises the enum
 * as a lowercase underscore string, preserving the existing API contract.
 */
public enum GenrePreferenceSource {

    SPOTIFY_DERIVED,
    MANUAL_SELECTION,
    INFERRED,
    HYBRID,
    SEED_DATA;

    /**
     * Returns the lowercase DB / JSON representation (e.g. {@code "spotify_derived"}).
     * Used by the JPA converter and by Jackson for serialisation.
     */
    @JsonValue
    public String getValue() {
        return name().toLowerCase();
    }

    /**
     * Parses a DB / JSON string back to the matching constant.
     * Case-insensitive. Returns {@link #MANUAL_SELECTION} for unrecognised values
     * to avoid converter failures on unexpected legacy data.
     */
    public static GenrePreferenceSource fromValue(String value) {
        if (value == null) {
            return MANUAL_SELECTION;
        }
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return MANUAL_SELECTION;
        }
    }
}
