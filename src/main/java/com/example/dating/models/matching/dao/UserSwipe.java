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
 * Tracks all user swipe actions (likes and passes).
 * Used for:
 * - Preventing showing the same profile twice
 * - Learning user preferences (behavioral analysis)
 * - A/B testing different matching algorithms
 * - Analytics (swipe-through rate, match rate, etc.)
 */
@Entity
@Getter
@Setter
@ToString
@Table(name = "user_swipes",
       uniqueConstraints = {
           @UniqueConstraint(name = "uk_swiper_swiped", columnNames = {"swiper_user_id", "swiped_user_id"})
       },
       indexes = {
           @Index(name = "idx_swipe_swiper", columnList = "swiper_user_id"),
           @Index(name = "idx_swipe_swiped", columnList = "swiped_user_id"),
           @Index(name = "idx_swipe_action", columnList = "action"),
           @Index(name = "idx_swipe_timestamp", columnList = "swiped_at"),
           // Batch C: hasUserLiked() filters on swiped_user_id + action IN ('like','super_like')
           // Composite allows index-only scan; without it Postgres must heap-fetch to evaluate action.
           @Index(name = "idx_swiped_action", columnList = "swiped_user_id,action")
       })
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserSwipe {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    /**
     * The user who performed the swipe action.
     */
    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "swiper_user_id", nullable = false)
    private UserEntity swiperUser;

    /**
     * The user who was swiped on.
     */
    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "swiped_user_id", nullable = false)
    private UserEntity swipedUser;

    /**
     * Swipe action taken.
     * Values: "like", "pass", "super_like", "block"
     */
    @Column(name = "action", nullable = false, length = 20)
    private String action;

    /**
     * When the swipe occurred.
     */
    @Column(name = "swiped_at", nullable = false)
    @Builder.Default
    private LocalDateTime swipedAt = LocalDateTime.now();

    /**
     * The overall match score at the time of swiping.
     * Used for learning: did users actually like high-scoring profiles?
     */
    @Column(name = "match_score_at_swipe")
    private Double matchScoreAtSwipe;

    /**
     * Individual dimension scores at time of swipe (stored as JSON).
     * Example: {"music": 85, "personality": 70, "lifestyle": 60, "location": 90}
     * Helps understand which dimensions actually predict likes.
     */
    @Column(name = "dimension_scores", columnDefinition = "TEXT")
    private String dimensionScores;

    /**
     * Which profile attributes were visible/emphasized when user swiped.
     * Example: "primary_photo,bio,top_artists,interests"
     * Helps understand what drives swipe decisions.
     */
    @Column(name = "visible_attributes", columnDefinition = "TEXT")
    private String visibleAttributes;

    /**
     * Did this swipe result in a match?
     * True if both users liked each other.
     */
    @Column(name = "resulted_in_match", nullable = false)
    @Builder.Default
    private Boolean resultedInMatch = false;

    /**
     * If this resulted in a match, reference to the Match entity.
     */
    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "match_id")
    private Match match;

    /**
     * Algorithm version that generated the match score.
     * Useful for A/B testing different algorithms.
     */
    @Column(name = "algorithm_version", length = 20)
    private String algorithmVersion;

    /**
     * Device/platform where swipe occurred.
     * Values: "ios", "android", "web"
     */
    @Column(name = "platform", length = 20)
    private String platform;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserSwipe other)) return false;
        return id != null && Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return id != null ? Objects.hashCode(id) : getClass().hashCode();
    }
}
