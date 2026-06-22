package com.example.dating.repositories;

import com.example.dating.models.matching.dao.UserMatchScore;
import com.example.dating.models.user.common.dao.UserEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for managing pre-computed match scores between users.
 */
@Repository
public interface UserMatchScoreRepository extends JpaRepository<UserMatchScore, String> {

    /**
     * Find match score between two specific users.
     */
    Optional<UserMatchScore> findByUserAndMatchedUser(UserEntity user, UserEntity matchedUser);

    /**
     * Batch-fetch all cached scores for a user with a given algorithm version.
     * Used for cache-first scoring in MatchRecommendationService.
     */
    @Query("SELECT ums FROM UserMatchScore ums WHERE ums.user.id = :userId AND ums.algorithmVersion = :version")
    List<UserMatchScore> findAllByUserIdAndVersion(@Param("userId") String userId, @Param("version") String version);

    /**
     * Find match score by user IDs.
     */
    @Query("SELECT ums FROM UserMatchScore ums WHERE ums.user.id = :userId AND ums.matchedUser.id = :matchedUserId")
    Optional<UserMatchScore> findByUserIdAndMatchedUserId(@Param("userId") String userId, @Param("matchedUserId") String matchedUserId);

    /**
     * Find all match scores for a user, ordered by overall score descending.
     * This is the main query for showing potential matches.
     */
    @Query("SELECT ums FROM UserMatchScore ums WHERE ums.user.id = :userId ORDER BY ums.overallScore DESC")
    List<UserMatchScore> findTopMatchesByUserId(@Param("userId") String userId, Pageable pageable);

    /**
     * Find match scores above a certain threshold.
     */
    @Query("SELECT ums FROM UserMatchScore ums " +
           "WHERE ums.user.id = :userId AND ums.overallScore >= :minScore " +
           "ORDER BY ums.overallScore DESC")
    List<UserMatchScore> findMatchesAboveThreshold(@Param("userId") String userId,
                                                    @Param("minScore") double minScore,
                                                    Pageable pageable);

    /**
     * Find stale match scores that need recalculation.
     * Stale = computed before a certain timestamp.
     */
    @Query("SELECT ums FROM UserMatchScore ums WHERE ums.computedAt < :before")
    List<UserMatchScore> findStaleMatches(@Param("before") LocalDateTime before, Pageable pageable);

    /**
     * Delete all match scores for a user.
     * Used when user deletes account or needs score refresh.
     */
    @Modifying
    @Query("DELETE FROM UserMatchScore ums WHERE ums.user.id = :userId")
    void deleteByUserId(@Param("userId") String userId);

    /**
     * Delete match scores involving a specific user (as user or matched_user).
     * Used when user deletes account.
     */
    @Modifying
    @Query("DELETE FROM UserMatchScore ums WHERE ums.user.id = :userId OR ums.matchedUser.id = :userId")
    void deleteAllInvolvingUser(@Param("userId") String userId);

    /**
     * Delete match scores older than a certain date.
     */
    @Modifying
    @Query("DELETE FROM UserMatchScore ums WHERE ums.computedAt < :before")
    int deleteOlderThan(@Param("before") LocalDateTime before);

    /**
     * Count total match scores for a user.
     */
    long countByUser(UserEntity user);

    /**
     * Check if match score exists between two users.
     */
    @Query("SELECT CASE WHEN COUNT(ums) > 0 THEN true ELSE false END " +
           "FROM UserMatchScore ums WHERE ums.user.id = :userId AND ums.matchedUser.id = :matchedUserId")
    boolean existsByUserIdAndMatchedUserId(@Param("userId") String userId, @Param("matchedUserId") String matchedUserId);

    /**
     * Get average match score for a user (useful for analytics).
     */
    @Query("SELECT AVG(ums.overallScore) FROM UserMatchScore ums WHERE ums.user.id = :userId")
    Double getAverageMatchScore(@Param("userId") String userId);

    /**
     * Find top N matches for a user by specific dimension.
     * Example: Top matches by music score.
     */
    @Query("SELECT ums FROM UserMatchScore ums WHERE ums.user.id = :userId ORDER BY ums.musicScore DESC")
    List<UserMatchScore> findTopByMusicScore(@Param("userId") String userId, Pageable pageable);

    @Query("SELECT ums FROM UserMatchScore ums WHERE ums.user.id = :userId ORDER BY ums.personalityScore DESC")
    List<UserMatchScore> findTopByPersonalityScore(@Param("userId") String userId, Pageable pageable);

    @Query("SELECT ums FROM UserMatchScore ums WHERE ums.user.id = :userId ORDER BY ums.lifestyleScore DESC")
    List<UserMatchScore> findTopByLifestyleScore(@Param("userId") String userId, Pageable pageable);

    /**
     * Atomic upsert: insert a new score row or update the existing one for the same
     * (user_id, matched_user_id) pair.
     *
     * <p>Replaces the non-atomic read-then-write pattern in upsertMatchScoreCache().
     * ON CONFLICT DO UPDATE is serialised by PostgreSQL's unique constraint, so
     * concurrent calls for the same pair cannot produce duplicate rows.
     *
     * <p>clearAutomatically = true discards the Hibernate 1st-level cache for
     * UserMatchScore after the DML executes, preventing stale reads within the
     * same transaction.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            INSERT INTO user_match_scores
                (id, user_id, matched_user_id,
                 music_score, lifestyle_score, interests_score,
                 location_score, behavioral_score, overall_score,
                 algorithm_version, computed_at, created_at, updated_at,
                 breakdown_json, insights_json)
            VALUES
                (:id, :userId, :matchedUserId,
                 :musicScore, :lifestyleScore, :interestsScore,
                 :locationScore, :behavioralScore, :overallScore,
                 :algorithmVersion, :computedAt, :computedAt, :computedAt,
                 :breakdownJson, :insightsJson)
            ON CONFLICT (user_id, matched_user_id) DO UPDATE SET
                music_score       = EXCLUDED.music_score,
                lifestyle_score   = EXCLUDED.lifestyle_score,
                interests_score   = EXCLUDED.interests_score,
                location_score    = EXCLUDED.location_score,
                behavioral_score  = EXCLUDED.behavioral_score,
                overall_score     = EXCLUDED.overall_score,
                algorithm_version = EXCLUDED.algorithm_version,
                computed_at       = EXCLUDED.computed_at,
                updated_at        = EXCLUDED.updated_at,
                breakdown_json    = EXCLUDED.breakdown_json,
                insights_json     = EXCLUDED.insights_json
            """, nativeQuery = true)
    void upsertScore(
            @Param("id") String id,
            @Param("userId") String userId,
            @Param("matchedUserId") String matchedUserId,
            @Param("musicScore") double musicScore,
            @Param("lifestyleScore") double lifestyleScore,
            @Param("interestsScore") double interestsScore,
            @Param("locationScore") double locationScore,
            @Param("behavioralScore") double behavioralScore,
            @Param("overallScore") double overallScore,
            @Param("algorithmVersion") String algorithmVersion,
            @Param("computedAt") LocalDateTime computedAt,
            @Param("breakdownJson") String breakdownJson,
            @Param("insightsJson") String insightsJson);
}
