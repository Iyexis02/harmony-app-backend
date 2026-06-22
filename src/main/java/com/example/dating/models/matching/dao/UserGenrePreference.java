package com.example.dating.models.matching.dao;

import com.example.dating.enums.matching.GenrePreferenceSource;
import com.example.dating.models.user.common.dao.UserEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Stores user's music genre preferences with weights.
 * Works for both Spotify users (derived from listening history) and
 * non-Spotify users (from manual selections).
 */
@Entity
@Getter
@Setter
@ToString
@Table(name = "user_genre_preferences",
       uniqueConstraints = {
           @UniqueConstraint(name = "uk_user_genre", columnNames = {"user_id", "genre_id"})
       },
       indexes = {
           @Index(name = "idx_user_genre_user", columnList = "user_id"),
           @Index(name = "idx_user_genre_genre", columnList = "genre_id"),
           @Index(name = "idx_user_genre_weight", columnList = "weight")
       })
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserGenrePreference {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "genre_id", nullable = false)
    private CanonicalGenre genre;

    /**
     * Preference weight (0.0 to 1.0).
     * Higher values indicate stronger preference.
     * For Spotify users: calculated from listening frequency.
     * For manual users: equal weights for selected genres.
     */
    @Column(name = "weight", nullable = false)
    private Double weight;

    /**
     * Source of this preference.
     * Stored as a lowercase underscore string via {@link GenrePreferenceSourceConverter}.
     */
    @Column(name = "source", nullable = false, length = 50)
    private GenrePreferenceSource source;

    /**
     * Confidence score (0.0 to 1.0).
     * How confident are we in this preference?
     * Spotify: high (0.9-1.0), Manual: medium (0.7-0.8), Inferred: low (0.3-0.6)
     */
    @Column(name = "confidence", nullable = false)
    @Builder.Default
    private Double confidence = 1.0;

    /**
     * Rank/priority of this genre for the user (1 = most preferred).
     * Used for displaying "Top 5 genres" etc.
     */
    @Column(name = "rank")
    private Integer rank;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserGenrePreference other)) return false;
        return id != null && Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return id != null ? Objects.hashCode(id) : getClass().hashCode();
    }
}
