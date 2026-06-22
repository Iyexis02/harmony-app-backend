package com.example.dating.repositories;

import com.example.dating.models.matching.dao.UserSwipe;
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
 * Repository for managing user swipes.
 */
@Repository
public interface UserSwipeRepository extends JpaRepository<UserSwipe, String> {

    /**
     * Find swipe between two users.
     */
    Optional<UserSwipe> findBySwiperUserAndSwipedUser(UserEntity swiperUser, UserEntity swipedUser);

    /**
     * Find swipe by user IDs.
     */
    @Query("SELECT us FROM UserSwipe us WHERE us.swiperUser.id = :swiperId AND us.swipedUser.id = :swipedId")
    Optional<UserSwipe> findByUserIds(@Param("swiperId") String swiperId, @Param("swipedId") String swipedId);

    /**
     * Check if user has already swiped on another user.
     */
    @Query("SELECT CASE WHEN COUNT(us) > 0 THEN true ELSE false END " +
           "FROM UserSwipe us WHERE us.swiperUser.id = :swiperId AND us.swipedUser.id = :swipedId")
    boolean hasUserSwipedOn(@Param("swiperId") String swiperId, @Param("swipedId") String swipedId);

    /**
     * Check if user has liked (or super_liked) another user.
     */
    @Query("SELECT CASE WHEN COUNT(us) > 0 THEN true ELSE false END " +
           "FROM UserSwipe us WHERE us.swiperUser.id = :swiperId AND us.swipedUser.id = :swipedId AND us.action IN ('like', 'super_like')")
    boolean hasUserLiked(@Param("swiperId") String swiperId, @Param("swipedId") String swipedId);

    /**
     * Acquires a PostgreSQL advisory transaction lock for the given pair key.
     * The lock is automatically released when the enclosing transaction commits or rolls back.
     *
     * <p>Both A→B and B→A swipes must sort their user IDs before calling this method so
     * that both directions resolve to the same lock integer, fully serializing the
     * mutual-match check-then-insert sequence across concurrent threads.
     */
    @Query(value = "SELECT pg_advisory_xact_lock(hashtext(CAST(:pairKey AS text)))", nativeQuery = true)
    Object acquirePairLock(@Param("pairKey") String pairKey);

    /**
     * Returns true iff a 'block' swipe exists in either direction between users a and b.
     * Single SELECT EXISTS — preferred over loading {@link #findBlockedUserIds} +
     * {@link #findBlockedByUserIds} into memory and using {@code List.contains}.
     * Used by hot-path authorization checks (score endpoints, profile read).
     */
    @Query("SELECT CASE WHEN COUNT(us) > 0 THEN true ELSE false END " +
           "FROM UserSwipe us WHERE us.action = 'block' AND (" +
           "  (us.swiperUser.id = :a AND us.swipedUser.id = :b) OR " +
           "  (us.swiperUser.id = :b AND us.swipedUser.id = :a))")
    boolean existsBlockBetween(@Param("a") String a, @Param("b") String b);

    /**
     * Find user IDs that this user has blocked.
     * Capped at 10 000 rows — prevents unbounded heap growth for power users.
     */
    @Query("SELECT us.swipedUser.id FROM UserSwipe us WHERE us.swiperUser.id = :userId AND us.action = 'block'")
    List<String> findBlockedUserIds(@Param("userId") String userId, Pageable pageable);

    /**
     * Find user IDs that have blocked this user.
     * Capped at 10 000 rows — prevents unbounded heap growth for power users.
     */
    @Query("SELECT us.swiperUser.id FROM UserSwipe us WHERE us.swipedUser.id = :userId AND us.action = 'block'")
    List<String> findBlockedByUserIds(@Param("userId") String userId, Pageable pageable);

    /**
     * Find all users that a user has swiped on (to exclude from future matches).
     * Capped at 10 000 rows — prevents unbounded heap growth for power users.
     */
    @Query("SELECT us.swipedUser.id FROM UserSwipe us WHERE us.swiperUser.id = :userId")
    List<String> findAllSwipedUserIds(@Param("userId") String userId, Pageable pageable);

    /**
     * Find all likes (right swipes) by a user.
     * JOIN FETCH eliminates the N+1 lazy-load on swipedUser when callers access that field.
     * countQuery omits the JOIN FETCH so Spring Data can derive an efficient COUNT.
     */
    @Query(value = "SELECT us FROM UserSwipe us JOIN FETCH us.swipedUser " +
                   "WHERE us.swiperUser.id = :userId AND us.action = 'like' " +
                   "ORDER BY us.swipedAt DESC",
           countQuery = "SELECT COUNT(us) FROM UserSwipe us " +
                        "WHERE us.swiperUser.id = :userId AND us.action = 'like'")
    List<UserSwipe> findLikesByUserId(@Param("userId") String userId, Pageable pageable);

    /**
     * Find all passes (left swipes) by a user.
     * JOIN FETCH eliminates the N+1 lazy-load on swipedUser.
     */
    @Query(value = "SELECT us FROM UserSwipe us JOIN FETCH us.swipedUser " +
                   "WHERE us.swiperUser.id = :userId AND us.action = 'pass' " +
                   "ORDER BY us.swipedAt DESC",
           countQuery = "SELECT COUNT(us) FROM UserSwipe us " +
                        "WHERE us.swiperUser.id = :userId AND us.action = 'pass'")
    List<UserSwipe> findPassesByUserId(@Param("userId") String userId, Pageable pageable);

    /**
     * Find users who liked a specific user (potential mutual matches).
     */
    @Query("SELECT us.swiperUser FROM UserSwipe us WHERE us.swipedUser.id = :userId AND us.action = 'like'")
    List<UserEntity> findUsersWhoLiked(@Param("userId") String userId, Pageable pageable);

    /**
     * Find swipes that resulted in matches.
     * JOIN FETCH eliminates the N+1 lazy-load on swipedUser.
     */
    @Query(value = "SELECT us FROM UserSwipe us JOIN FETCH us.swipedUser " +
                   "WHERE us.swiperUser.id = :userId AND us.resultedInMatch = true " +
                   "ORDER BY us.swipedAt DESC",
           countQuery = "SELECT COUNT(us) FROM UserSwipe us " +
                        "WHERE us.swiperUser.id = :userId AND us.resultedInMatch = true")
    List<UserSwipe> findSwipesThatMatched(@Param("userId") String userId, Pageable pageable);

    /**
     * Find recent swipes by a user (for analytics/learning).
     */
    @Query("SELECT us FROM UserSwipe us WHERE us.swiperUser.id = :userId AND us.swipedAt >= :since ORDER BY us.swipedAt DESC")
    List<UserSwipe> findRecentSwipes(@Param("userId") String userId, @Param("since") LocalDateTime since, Pageable pageable);

    /**
     * Count total swipes by a user.
     */
    @Query("SELECT COUNT(us) FROM UserSwipe us WHERE us.swiperUser.id = :userId")
    long countSwipesByUserId(@Param("userId") String userId);

    /**
     * Count likes (including super-likes) by a user.
     * Used by SwipeService.getLikeCount() — must include super_like so the behavioral
     * profile confidence calculation (totalLikes / 50.0) is not under-counted.
     */
    @Query("SELECT COUNT(us) FROM UserSwipe us WHERE us.swiperUser.id = :userId AND us.action IN ('like', 'super_like')")
    long countLikesByUserId(@Param("userId") String userId);

    /**
     * Count passes by a user.
     */
    @Query("SELECT COUNT(us) FROM UserSwipe us WHERE us.swiperUser.id = :userId AND us.action = 'pass'")
    long countPassesByUserId(@Param("userId") String userId);

    /**
     * Calculate swipe-through rate (likes / total swipes).
     * Useful for understanding user pickiness.
     */
    @Query("SELECT CAST(SUM(CASE WHEN us.action = 'like' THEN 1 ELSE 0 END) AS double) / COUNT(us) " +
           "FROM UserSwipe us WHERE us.swiperUser.id = :userId")
    Double calculateSwipeThroughRate(@Param("userId") String userId);

    /**
     * Calculate match rate (swipes that resulted in match / total likes).
     */
    @Query("SELECT CAST(SUM(CASE WHEN us.resultedInMatch = true THEN 1 ELSE 0 END) AS double) / " +
           "SUM(CASE WHEN us.action = 'like' THEN 1 ELSE 0 END) " +
           "FROM UserSwipe us WHERE us.swiperUser.id = :userId")
    Double calculateMatchRate(@Param("userId") String userId);

    /**
     * Delete all swipes by a user.
     */
    @Modifying
    @Query("DELETE FROM UserSwipe us WHERE us.swiperUser.id = :userId")
    void deleteBySwiperId(@Param("userId") String userId);

    /**
     * Delete all swipes involving a user (as swiper or swiped).
     */
    @Modifying
    @Query("DELETE FROM UserSwipe us WHERE us.swiperUser.id = :userId OR us.swipedUser.id = :userId")
    void deleteAllInvolvingUser(@Param("userId") String userId);

    /**
     * Find block swipes where the blocked pair still has an ACTIVE match.
     *
     * <p>Used by {@link com.example.dating.services.matching.MatchReconciliationService}
     * to detect the failure case where {@code MatchLifecycleListener.onUserBlocked()} threw
     * after exhausting its two {@code OptimisticLockingFailureException} retry attempts —
     * leaving the match ACTIVE despite the block swipe being committed.
     *
     * <p>Capped at {@code limit} rows via {@code Pageable} to prevent long-running scans.
     */
    @Query("SELECT s FROM UserSwipe s WHERE s.action = 'block' " +
           "AND EXISTS (" +
           "  SELECT m FROM Match m WHERE " +
           "  m.status = com.example.dating.enums.matching.MatchStatus.ACTIVE AND " +
           "  ((m.userA.id = s.swiperUser.id AND m.userB.id = s.swipedUser.id) OR " +
           "   (m.userA.id = s.swipedUser.id AND m.userB.id = s.swiperUser.id))" +
           ")")
    List<UserSwipe> findBlockSwipesWithActiveMatches(Pageable pageable);

    /**
     * Delete old swipes (cleanup for storage/privacy).
     */
    @Modifying
    @Query("DELETE FROM UserSwipe us WHERE us.swipedAt < :before")
    int deleteOlderThan(@Param("before") LocalDateTime before);

    /**
     * Find swipes with high match scores that user passed on.
     * JOIN FETCH eliminates the N+1 lazy-load on swipedUser.
     */
    @Query(value = "SELECT us FROM UserSwipe us JOIN FETCH us.swipedUser " +
                   "WHERE us.swiperUser.id = :userId AND us.action = 'pass' " +
                   "AND us.matchScoreAtSwipe >= :minScore " +
                   "ORDER BY us.matchScoreAtSwipe DESC",
           countQuery = "SELECT COUNT(us) FROM UserSwipe us " +
                        "WHERE us.swiperUser.id = :userId AND us.action = 'pass' " +
                        "AND us.matchScoreAtSwipe >= :minScore")
    List<UserSwipe> findHighScoringPasses(@Param("userId") String userId,
                                          @Param("minScore") double minScore,
                                          Pageable pageable);

    /**
     * Find swipes with low match scores that user liked.
     * JOIN FETCH eliminates the N+1 lazy-load on swipedUser.
     */
    @Query(value = "SELECT us FROM UserSwipe us JOIN FETCH us.swipedUser " +
                   "WHERE us.swiperUser.id = :userId AND us.action = 'like' " +
                   "AND us.matchScoreAtSwipe <= :maxScore " +
                   "ORDER BY us.matchScoreAtSwipe ASC",
           countQuery = "SELECT COUNT(us) FROM UserSwipe us " +
                        "WHERE us.swiperUser.id = :userId AND us.action = 'like' " +
                        "AND us.matchScoreAtSwipe <= :maxScore")
    List<UserSwipe> findLowScoringLikes(@Param("userId") String userId,
                                        @Param("maxScore") double maxScore,
                                        Pageable pageable);
}
