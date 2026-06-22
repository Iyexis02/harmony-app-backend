package com.example.dating.enums.matching;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Type-safe representation of a match's lifecycle state.
 *
 * <p>The companion {@link com.example.dating.models.matching.dao.MatchStatusConverter}
 * (autoApply) maps each constant to its lowercase DB column value so existing rows
 * ({@code "active"}, {@code "unmatched"}, …) are read and written without a migration.
 *
 * <p>{@link #getValue()} is annotated with {@code @JsonValue} so Jackson serialises
 * the enum as a lowercase string — preserving the existing public API contract.
 */
public enum MatchStatus {

    ACTIVE,
    UNMATCHED,
    DELETED,
    BLOCKED;

    /**
     * Returns the lowercase DB / JSON representation (e.g. {@code "active"}).
     * Used by the JPA converter and by Jackson for serialisation.
     */
    @JsonValue
    public String getValue() {
        return name().toLowerCase();
    }
}
