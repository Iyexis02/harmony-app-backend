package com.example.dating.repositories;

import com.example.dating.enums.matching.GenrePreferenceSource;
import com.example.dating.models.matching.dao.CanonicalGenre;
import com.example.dating.models.matching.dao.UserGenrePreference;
import com.example.dating.models.user.common.dao.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for managing user genre preferences.
 */
@Repository
public interface UserGenrePreferenceRepository extends JpaRepository<UserGenrePreference, String> {

    /**
     * Find all genre preferences for a user, ordered by weight descending.
     */
    List<UserGenrePreference> findByUserOrderByWeightDesc(UserEntity user);

    /**
     * Find all genre preferences for a user by user ID, ordered by weight descending.
     * Does NOT join-fetch the genre association — use {@link #findByUserIdWithGenreOrderByWeightDesc}
     * whenever {@code pref.getGenre()} will be accessed outside a transaction.
     */
    @Query("SELECT ugp FROM UserGenrePreference ugp WHERE ugp.user.id = :userId ORDER BY ugp.weight DESC")
    List<UserGenrePreference> findByUserIdOrderByWeightDesc(@Param("userId") String userId);

    /**
     * Find all genre preferences for a user by user ID, with the genre JOIN FETCHed in the same
     * query so that {@code pref.getGenre()} is accessible outside the originating transaction.
     * Use this in every code path that reads {@code pref.getGenre().getName()} or
     * {@code pref.getGenre().getDisplayName()}.
     */
    @Query("SELECT p FROM UserGenrePreference p JOIN FETCH p.genre WHERE p.user.id = :userId ORDER BY p.weight DESC")
    List<UserGenrePreference> findByUserIdWithGenreOrderByWeightDesc(@Param("userId") String userId);

    /**
     * Find top N genre preferences for a user.
     * Does NOT join-fetch the genre — use {@link #findTopNByUserIdWithGenre} when genre access
     * is needed outside a transaction.
     */
    @Query("SELECT ugp FROM UserGenrePreference ugp " +
           "WHERE ugp.user.id = :userId " +
           "ORDER BY ugp.weight DESC " +
           "LIMIT :limit")
    List<UserGenrePreference> findTopNByUserId(@Param("userId") String userId, @Param("limit") int limit);

    /**
     * Find top N genre preferences for a user with the genre JOIN FETCHed.
     * Use this whenever {@code pref.getGenre()} will be accessed outside a transaction
     * (e.g. in controller response building or service code without an active session).
     */
    @Query("SELECT p FROM UserGenrePreference p JOIN FETCH p.genre WHERE p.user.id = :userId ORDER BY p.weight DESC LIMIT :limit")
    List<UserGenrePreference> findTopNByUserIdWithGenre(@Param("userId") String userId, @Param("limit") int limit);

    /**
     * Batch-load genre preferences for multiple users in a single query, with the genre
     * JOIN FETCHed so that {@code pref.getGenre()} is accessible outside a transaction.
     *
     * <p>Used by {@link com.example.dating.services.matching.MatchRecommendationService}
     * before the scoring loop to pre-load all candidate prefs at once, eliminating the
     * per-candidate queries that {@link com.example.dating.services.matching.MatchScoreCalculator}
     * and {@link com.example.dating.services.matching.BehavioralScoreCalculator} would otherwise issue.
     *
     * @param userIds list of user IDs to fetch preferences for
     * @return flat list of preferences for all requested users, ordered by user then weight desc
     */
    @Query("SELECT p FROM UserGenrePreference p JOIN FETCH p.genre WHERE p.user.id IN :userIds ORDER BY p.user.id, p.weight DESC")
    List<UserGenrePreference> findByUserIdsWithGenre(@Param("userIds") List<String> userIds);

    /**
     * Find a specific user-genre preference.
     */
    Optional<UserGenrePreference> findByUserAndGenre(UserEntity user, CanonicalGenre genre);

    /**
     * Find preferences by user ID and genre ID.
     */
    @Query("SELECT ugp FROM UserGenrePreference ugp WHERE ugp.user.id = :userId AND ugp.genre.id = :genreId")
    Optional<UserGenrePreference> findByUserIdAndGenreId(@Param("userId") String userId, @Param("genreId") String genreId);

    /**
     * Find all preferences from a specific source.
     */
    List<UserGenrePreference> findByUserAndSource(UserEntity user, GenrePreferenceSource source);

    /**
     * Delete all genre preferences for a user.
     */
    @Modifying
    @Query("DELETE FROM UserGenrePreference ugp WHERE ugp.user.id = :userId")
    void deleteByUserId(@Param("userId") String userId);

    /**
     * Delete all genre preferences from a specific source for a user.
     * Useful when refreshing Spotify data.
     */
    @Modifying
    @Query("DELETE FROM UserGenrePreference ugp WHERE ugp.user.id = :userId AND ugp.source = :source")
    void deleteByUserIdAndSource(@Param("userId") String userId, @Param("source") GenrePreferenceSource source);

    /**
     * Check if user has any genre preferences.
     */
    boolean existsByUser(UserEntity user);

    /**
     * Count genre preferences for a user.
     */
    long countByUser(UserEntity user);

    /**
     * Find users who prefer a specific genre (for genre-based matching).
     */
    @Query("SELECT ugp.user FROM UserGenrePreference ugp WHERE ugp.genre.id = :genreId AND ugp.weight >= :minWeight")
    List<UserEntity> findUsersByGenreAndMinWeight(@Param("genreId") String genreId, @Param("minWeight") double minWeight);
}
