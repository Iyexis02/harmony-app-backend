package com.example.dating.enums.matching;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Type-safe representation of how a match was created.
 *
 * <p>The companion {@link com.example.dating.models.matching.dao.MatchSourceConverter}
 * (autoApply) maps each constant to its lowercase DB column value (e.g.
 * {@code "mutual_swipe"}) so existing rows are compatible without a migration.
 *
 * <p>{@link #getValue()} is annotated with {@code @JsonValue} so Jackson serialises
 * the enum as a lowercase underscore string — preserving the existing API contract.
 */
public enum MatchSource {

    MUTUAL_SWIPE,
    ALGORITHM_BOOST,
    SUPER_LIKE;

    /**
     * Returns the lowercase DB / JSON representation (e.g. {@code "mutual_swipe"}).
     * Used by the JPA converter and by Jackson for serialisation.
     */
    @JsonValue
    public String getValue() {
        return name().toLowerCase();
    }
}
