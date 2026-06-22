package com.example.dating.models.matching.dao;

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
 * Stores a per-user behavioural profile learned from swipe actions.
 * Used to personalise match scoring beyond static profile attributes.
 */
@Entity
@Table(name = "user_behavioral_profile", indexes = {
    @Index(name = "idx_behavioral_user", columnList = "user_id", unique = true)
})
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserBehavioralProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(length = 36, nullable = false, updatable = false)
    private String id;

    /** Optimistic-locking version counter. Hibernate increments this on every update.
     *  Two concurrent reads of the same row will both see the same version; the second
     *  writer gets an {@code OptimisticLockingFailureException} at commit time. */
    @Version
    private Long version;

    @ToString.Exclude
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private UserEntity user;

    /**
     * JSON map: {"rock": 0.88, "indie-folk": 0.74, ...}
     * Running average of genre weights for users that were liked.
     */
    @Column(name = "learned_genre_weights", columnDefinition = "TEXT")
    private String learnedGenreWeights;

    /** Running average age of liked users. */
    @Column(name = "avg_liked_age")
    private Double avgLikedAge;

    /** Running average distance (km) of liked users. */
    @Column(name = "avg_liked_distance_km")
    private Double avgLikedDistanceKm;

    /**
     * JSON frequency map: {"SERIOUS_RELATIONSHIP": 8, "CASUAL_DATING": 2}
     * Counts how often each relationship goal appeared in liked users.
     */
    @Column(name = "top_liked_relationship_goals", columnDefinition = "TEXT")
    private String topLikedRelationshipGoals;

    /**
     * EMA of overall match scores at the time of a like.
     * Used as a calibrated per-user threshold — users who like fewer candidates
     * will have a higher threshold than those who like freely.
     */
    @Column(name = "effective_score_threshold")
    private Double effectiveScoreThreshold;

    @Column(name = "total_likes", nullable = false)
    @Builder.Default
    private Integer totalLikes = 0;

    @Column(name = "total_passes", nullable = false)
    @Builder.Default
    private Integer totalPasses = 0;

    /**
     * Confidence in the learned profile: min(1.0, totalLikes / 50.0).
     * At 0 confidence the behavioural component has no weight.
     * At 1.0 confidence (50+ likes) it contributes up to 40 % of the final score.
     */
    @Column(name = "confidence_level")
    @Builder.Default
    private Double confidenceLevel = 0.0;

    @Column(name = "last_updated_at")
    private LocalDateTime lastUpdatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserBehavioralProfile other)) return false;
        return id != null && Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return id != null ? Objects.hashCode(id) : getClass().hashCode();
    }
}
