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
 * Pre-computed match scores between users.
 * Stores individual dimension scores (music, personality, lifestyle, location)
 * and overall weighted score for faster retrieval.
 */
@Entity
@Getter
@Setter
@ToString
@Table(name = "user_match_scores",
       uniqueConstraints = {
           @UniqueConstraint(name = "uk_user_match", columnNames = {"user_id", "matched_user_id"})
       },
       indexes = {
           @Index(name = "idx_match_score_user", columnList = "user_id"),
           @Index(name = "idx_match_score_overall", columnList = "user_id,overall_score"),
           @Index(name = "idx_match_score_computed", columnList = "computed_at"),
           // Batch C: findAllByUserIdAndVersion() — cache batch-fetch before every feed load
           @Index(name = "idx_user_version", columnList = "user_id,algorithm_version")
       })
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserMatchScore {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    /**
     * The user for whom this match score was computed (perspective user).
     */
    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    /**
     * The potential match being scored.
     */
    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "matched_user_id", nullable = false)
    private UserEntity matchedUser;

    /**
     * Music compatibility score (0-100).
     * Based on genre overlap, artist similarity, audio features, etc.
     */
    @Column(name = "music_score")
    private Double musicScore;

    /**
     * Personality compatibility score (0-100).
     * Based on MBTI compatibility, interest overlap, bio analysis, etc.
     */
    @Column(name = "personality_score")
    private Double personalityScore;

    /**
     * Lifestyle compatibility score (0-100).
     * Based on habits, values, goals (smoking, drinking, kids, religion, etc.)
     */
    @Column(name = "lifestyle_score")
    private Double lifestyleScore;

    /**
     * Location proximity score (0-100).
     * Based on physical distance between users.
     */
    @Column(name = "location_score")
    private Double locationScore;

    /**
     * Interests compatibility score (0-100).
     * Based on Jaccard similarity of interest tags.
     */
    @Column(name = "interests_score")
    private Double interestsScore;

    /**
     * Behavioral compatibility score (0-100).
     * Based on learned swipe patterns and genre weight similarity.
     */
    @Column(name = "behavioral_score")
    private Double behavioralScore;

    /**
     * Overall weighted match score (0-100).
     * Combines individual scores using user's personalized weights.
     * This is what's used for ranking matches.
     */
    @Column(name = "overall_score", nullable = false)
    private Double overallScore;

    /**
     * JSON object containing match explanation.
     * Example: {"common_genres": ["indie", "rock"], "common_interests": ["hiking"], "mbti_match": "high"}
     * Used for "You matched because..." UI explanations.
     */
    @Column(name = "match_explanation", columnDefinition = "TEXT")
    private String matchExplanation;

    /**
     * Full MatchBreakdown serialized as JSON.
     * Populated on cache write; null for legacy rows written before Batch F.
     */
    @Column(name = "breakdown_json", columnDefinition = "TEXT")
    private String breakdownJson;

    /**
     * Human-readable insights list serialized as JSON (e.g. ["Great lifestyle compatibility"]).
     * Populated on cache write; null for legacy rows written before Batch F.
     */
    @Column(name = "insights_json", columnDefinition = "TEXT")
    private String insightsJson;

    /**
     * When this score was computed.
     * Used to determine if recalculation is needed (stale scores).
     */
    @Column(name = "computed_at", nullable = false)
    @Builder.Default
    private LocalDateTime computedAt = LocalDateTime.now();

    /**
     * Version number for the matching algorithm used.
     * Allows tracking which algorithm version produced this score.
     * Example: "v1.0", "v2.0-beta", etc.
     */
    @Column(name = "algorithm_version", length = 20)
    private String algorithmVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserMatchScore other)) return false;
        return id != null && Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return id != null ? Objects.hashCode(id) : getClass().hashCode();
    }
}
