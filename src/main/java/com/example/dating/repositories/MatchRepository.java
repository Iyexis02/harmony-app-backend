package com.example.dating.repositories;

import com.example.dating.enums.matching.MatchSource;
import com.example.dating.enums.matching.MatchStatus;
import com.example.dating.models.matching.dao.Match;
import org.springframework.data.domain.Page;
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
 * Repository for managing matches between users.
 *
 * <p>All JPQL queries that filter on {@code status} or {@code matchSource} use typed
 * enum parameters or JPQL enum literals so that the
 * {@link com.example.dating.models.matching.dao.MatchStatusConverter} /
 * {@link com.example.dating.models.matching.dao.MatchSourceConverter} are applied
 * correctly — avoiding raw string comparisons that would silently fail if the stored
 * value drifted from the expected format.
 */
@Repository
public interface MatchRepository extends JpaRepository<Match, String> {

    /**
     * Find match between two specific users (bidirectional search), regardless of status.
     *
     * <p>Use {@link #findActiveMatchBetweenUsers} when only an ACTIVE match is meaningful
     * (e.g. block-triggered unmatch, UI display). This method is kept for internal use by
     * {@link com.example.dating.services.matching.MatchService#createMatch} which needs to
     * load whatever row the upsert left behind.
     */
    @Query("SELECT m FROM Match m WHERE " +
           "(m.userA.id = :user1Id AND m.userB.id = :user2Id) OR " +
           "(m.userA.id = :user2Id AND m.userB.id = :user1Id)")
    Optional<Match> findMatchBetweenUsers(@Param("user1Id") String user1Id, @Param("user2Id") String user2Id);

    /**
     * Find the ACTIVE match between two users (bidirectional search).
     *
     * <p>Returns {@link Optional#empty()} when the pair has only an UNMATCHED history row.
     * Use this instead of {@link #findMatchBetweenUsers} in any context that should not
     * accidentally act on a previously-unmatched pair.
     */
    @Query("SELECT m FROM Match m WHERE " +
           "((m.userA.id = :user1Id AND m.userB.id = :user2Id) OR " +
           "(m.userA.id = :user2Id AND m.userB.id = :user1Id)) " +
           "AND m.status = com.example.dating.enums.matching.MatchStatus.ACTIVE")
    Optional<Match> findActiveMatchBetweenUsers(@Param("user1Id") String user1Id, @Param("user2Id") String user2Id);

    /**
     * Find all matches for a user filtered by status.
     * Use {@code MatchStatus} enum constants as the {@code status} argument.
     */
    @Query("SELECT m FROM Match m WHERE " +
           "(m.userA.id = :userId OR m.userB.id = :userId) AND m.status = :status " +
           "ORDER BY m.matchedAt DESC")
    List<Match> findMatchesByUserIdAndStatus(@Param("userId") String userId,
                                             @Param("status") MatchStatus status,
                                             Pageable pageable);

    /**
     * Find active matches for a user — paginated.
     */
    @Query(value = "SELECT m FROM Match m WHERE " +
                   "(m.userA.id = :userId OR m.userB.id = :userId) " +
                   "AND m.status = com.example.dating.enums.matching.MatchStatus.ACTIVE " +
                   "ORDER BY m.matchedAt DESC",
           countQuery = "SELECT COUNT(m) FROM Match m WHERE " +
                        "(m.userA.id = :userId OR m.userB.id = :userId) " +
                        "AND m.status = com.example.dating.enums.matching.MatchStatus.ACTIVE")
    Page<Match> findActiveMatchesByUserId(@Param("userId") String userId, Pageable pageable);

    /**
     * Find all matches for a user (any status) — paginated.
     */
    @Query(value = "SELECT m FROM Match m WHERE " +
                   "(m.userA.id = :userId OR m.userB.id = :userId) " +
                   "ORDER BY m.matchedAt DESC",
           countQuery = "SELECT COUNT(m) FROM Match m WHERE " +
                        "(m.userA.id = :userId OR m.userB.id = :userId)")
    Page<Match> findAllMatchesByUserId(@Param("userId") String userId, Pageable pageable);

    /**
     * Find matches with conversations (at least one message sent).
     */
    @Query("SELECT m FROM Match m WHERE " +
           "(m.userA.id = :userId OR m.userB.id = :userId) AND " +
           "m.status = com.example.dating.enums.matching.MatchStatus.ACTIVE " +
           "AND m.conversationStarted = true " +
           "ORDER BY m.lastMessageAt DESC")
    List<Match> findMatchesWithConversations(@Param("userId") String userId, Pageable pageable);

    /**
     * Find matches without conversations (new matches).
     */
    @Query("SELECT m FROM Match m WHERE " +
           "(m.userA.id = :userId OR m.userB.id = :userId) AND " +
           "m.status = com.example.dating.enums.matching.MatchStatus.ACTIVE " +
           "AND m.conversationStarted = false " +
           "ORDER BY m.matchedAt DESC")
    List<Match> findMatchesWithoutConversations(@Param("userId") String userId, Pageable pageable);

    /**
     * Find recent matches for a user (within last N days).
     */
    @Query("SELECT m FROM Match m WHERE " +
           "(m.userA.id = :userId OR m.userB.id = :userId) AND " +
           "m.status = com.example.dating.enums.matching.MatchStatus.ACTIVE " +
           "AND m.matchedAt >= :since " +
           "ORDER BY m.matchedAt DESC")
    List<Match> findRecentMatches(@Param("userId") String userId,
                                  @Param("since") LocalDateTime since,
                                  Pageable pageable);

    /**
     * Check if two users are matched (both must have ACTIVE status).
     */
    @Query("SELECT CASE WHEN COUNT(m) > 0 THEN true ELSE false END FROM Match m WHERE " +
           "((m.userA.id = :user1Id AND m.userB.id = :user2Id) OR " +
           "(m.userA.id = :user2Id AND m.userB.id = :user1Id)) AND " +
           "m.status = com.example.dating.enums.matching.MatchStatus.ACTIVE")
    boolean areUsersMatched(@Param("user1Id") String user1Id, @Param("user2Id") String user2Id);

    /**
     * Count active matches for a user.
     */
    @Query("SELECT COUNT(m) FROM Match m WHERE " +
           "(m.userA.id = :userId OR m.userB.id = :userId) " +
           "AND m.status = com.example.dating.enums.matching.MatchStatus.ACTIVE")
    long countActiveMatchesByUserId(@Param("userId") String userId);

    /**
     * Count matches with conversations for a user.
     */
    @Query("SELECT COUNT(m) FROM Match m WHERE " +
           "(m.userA.id = :userId OR m.userB.id = :userId) AND " +
           "m.status = com.example.dating.enums.matching.MatchStatus.ACTIVE " +
           "AND m.conversationStarted = true")
    long countMatchesWithConversations(@Param("userId") String userId);

    /**
     * Find matches by source.
     * Use {@code MatchSource} enum constants as the {@code source} argument.
     */
    @Query("SELECT m FROM Match m WHERE " +
           "(m.userA.id = :userId OR m.userB.id = :userId) AND " +
           "m.matchSource = :source " +
           "AND m.status = com.example.dating.enums.matching.MatchStatus.ACTIVE " +
           "ORDER BY m.matchedAt DESC")
    List<Match> findMatchesBySource(@Param("userId") String userId,
                                    @Param("source") MatchSource source,
                                    Pageable pageable);

    /**
     * Atomically insert a new match row, or reactivate an existing UNMATCHED row.
     *
     * <p>Returns 1 when a row was inserted (new pair) or when an existing UNMATCHED row
     * was updated to status {@code 'active'} (re-match after unmatch). Returns 0 when
     * the pair already has an ACTIVE match — the {@code WHERE matches.status != 'active'}
     * predicate prevents overwriting an ongoing match during concurrent swipe races.
     *
     * <p>On reactivation the following fields are reset to their initial values:
     * {@code unmatched_at}, {@code unmatched_by} (cleared to NULL),
     * {@code conversation_started} (false), {@code message_count} (0), and
     * {@code version} (incremented by 1 so any in-flight JPA writes on the old version
     * fail with OptimisticLockingFailureException rather than silently overwriting).
     *
     * <p>Callers must supply a pre-generated UUID as {@code id} because
     * {@link jakarta.persistence.GeneratedValue} cannot be used with native DML.
     *
     * <p>The {@code matchSource} parameter must be the DB-side lowercase string value
     * (e.g. {@code "mutual_swipe"}) obtained via {@code MatchSource.getValue()} — native
     * queries bypass JPA converters.
     */
    @Modifying(clearAutomatically = true)
    @Query(nativeQuery = true, value =
            "INSERT INTO matches " +
            "  (id, user_a_id, user_b_id, match_score, match_score_b, status, conversation_started," +
            "   match_source, matched_at, created_at, updated_at, message_count, version) " +
            "VALUES " +
            "  (:id, :userAId, :userBId, :matchScoreA, :matchScoreB, 'active', false," +
            "   :matchSource, :matchedAt, :createdAt, :updatedAt, 0, 0) " +
            "ON CONFLICT (user_a_id, user_b_id) DO UPDATE SET " +
            "  status              = 'active'," +
            "  match_score         = EXCLUDED.match_score," +
            "  match_score_b       = EXCLUDED.match_score_b," +
            "  match_source        = EXCLUDED.match_source," +
            "  matched_at          = EXCLUDED.matched_at," +
            "  updated_at          = EXCLUDED.updated_at," +
            "  unmatched_at        = NULL," +
            "  unmatched_by        = NULL," +
            "  conversation_started = false," +
            "  message_count       = 0," +
            "  version             = matches.version + 1 " +
            "WHERE matches.status != 'active'")
    int insertMatchIfAbsent(@Param("id")          String        id,
                            @Param("userAId")     String        userAId,
                            @Param("userBId")     String        userBId,
                            @Param("matchScoreA") Double        matchScoreA,
                            @Param("matchScoreB") Double        matchScoreB,
                            @Param("matchSource") String        matchSource,
                            @Param("matchedAt")   LocalDateTime matchedAt,
                            @Param("createdAt")   LocalDateTime createdAt,
                            @Param("updatedAt")   LocalDateTime updatedAt);

    /**
     * Backfill the B→A directional score for a match that was created without it.
     *
     * <p>The {@code WHERE match_score_b IS NULL} guard ensures this is a no-op if the
     * score was set by another path between the async dispatch and execution.
     *
     * @return 1 if the row was updated, 0 if {@code match_score_b} was already set
     */
    @Modifying(clearAutomatically = true)
    @Query(nativeQuery = true,
           value = "UPDATE matches SET match_score_b = :score WHERE id = :matchId AND match_score_b IS NULL")
    int updateMatchScoreBIfNull(@Param("matchId") String matchId, @Param("score") double score);

    /**
     * Delete all matches involving a user.
     */
    @Modifying
    @Query("DELETE FROM Match m WHERE m.userA.id = :userId OR m.userB.id = :userId")
    void deleteAllByUserId(@Param("userId") String userId);

    /**
     * Get match quality analytics: average score of matches that led to conversations.
     */
    @Query("SELECT AVG(m.matchScore) FROM Match m WHERE " +
           "(m.userA.id = :userId OR m.userB.id = :userId) AND " +
           "m.conversationStarted = true")
    Double getAverageScoreOfConversationMatches(@Param("userId") String userId);
}
