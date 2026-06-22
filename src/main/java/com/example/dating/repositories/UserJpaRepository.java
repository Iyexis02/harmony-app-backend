package com.example.dating.repositories;


import com.example.dating.enums.user.RegistrationStage;
import com.example.dating.models.user.common.dao.UserEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserJpaRepository extends JpaRepository<UserEntity, String> {

    /**
     * Acquire a {@code SELECT … FOR UPDATE} (pessimistic write) lock on the user row.
     * Used by {@code deleteAccount()} to prevent concurrent swipe/match inserts
     * referencing this user while deletion is in progress.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM UserEntity u WHERE u.id = :userId")
    Optional<UserEntity> findByIdForUpdate(@Param("userId") String userId);

    Optional<UserEntity> findByEmail(String email);

    Optional<UserEntity> findBySpotifyId(String spotifyId);

    Optional<UserEntity> findByEmailVerificationToken(String token);

    Optional<UserEntity> findByPasswordResetToken(String token);

    Optional<UserEntity> findByEmailVerificationTokenHash(String hash);

    Optional<UserEntity> findByPasswordResetTokenHash(String hash);

    /**
     * Find candidate users for matching:
     * - Excludes the requesting user
     * - Excludes users already swiped on
     * - Only returns users who completed onboarding (FINISHED stage)
     * - Filters out non-discoverable / incognito users
     * - Filters by age via date-of-birth bounds (pushed from service layer)
     * Gender / distance filtering is still done in-memory (not safely expressible in JPQL).
     *
     * <p>Batch D: JOIN FETCHes {@code datingPreferences} because it is accessed for every
     * candidate in the gender and distance hard-filter passes (100% access rate).
     * JOIN FETCH on a @OneToOne avoids 500 individual SELECTs for datingPreferences.
     * Cannot JOIN FETCH both @OneToOne and @OneToMany (photos) in one query —
     * Hibernate throws MultipleBagFetchException; photos are batch-loaded via @BatchSize(50).
     *
     * <p>A separate {@link #countCandidateUsers} method is required because Spring Data
     * cannot derive a correct COUNT query when JOIN FETCH is present.
     *
     * @param minDob lower DOB bound (today - maxAge): candidate must be born ON OR AFTER this date (i.e. not older than maxAge)
     * @param maxDob upper DOB bound (today - minAge): candidate must be born ON OR BEFORE this date (i.e. not younger than minAge)
     */
    @Query(
        value = """
            SELECT u FROM UserEntity u
            LEFT JOIN FETCH u.datingPreferences dp
            LEFT JOIN u.privacySettings ps
            WHERE u.id != :userId
              AND u.registrationStage = :stage
              AND u.id NOT IN :excludedIds
              AND (ps IS NULL OR (ps.discoverable = true AND ps.incognitoMode = false))
              AND (COALESCE(:minDob, u.dateOfBirth) = u.dateOfBirth OR u.dateOfBirth >= :minDob)
              AND (COALESCE(:maxDob, u.dateOfBirth) = u.dateOfBirth OR u.dateOfBirth <= :maxDob)
              AND u.deleted = false
            ORDER BY FUNCTION('RANDOM')
            """,
        countQuery = """
            SELECT COUNT(u) FROM UserEntity u
            LEFT JOIN u.privacySettings ps
            WHERE u.id != :userId
              AND u.registrationStage = :stage
              AND u.id NOT IN :excludedIds
              AND (ps IS NULL OR (ps.discoverable = true AND ps.incognitoMode = false))
              AND (COALESCE(:minDob, u.dateOfBirth) = u.dateOfBirth OR u.dateOfBirth >= :minDob)
              AND (COALESCE(:maxDob, u.dateOfBirth) = u.dateOfBirth OR u.dateOfBirth <= :maxDob)
              AND u.deleted = false
            """
    )
    Page<UserEntity> findCandidateUsers(
            @Param("userId") String userId,
            @Param("stage") RegistrationStage stage,
            @Param("excludedIds") List<String> excludedIds,
            @Param("minDob") LocalDate minDob,
            @Param("maxDob") LocalDate maxDob,
            Pageable pageable);

    /**
     * Count query companion to {@link #findCandidateUsers}.
     * Required because the JOIN FETCH in findCandidateUsers breaks Spring Data's
     * automatic count-query derivation for pagination.
     */
    @Query("""
        SELECT COUNT(u) FROM UserEntity u
        LEFT JOIN u.privacySettings ps
        WHERE u.id != :userId
          AND u.registrationStage = :stage
          AND u.id NOT IN :excludedIds
          AND (ps IS NULL OR (ps.discoverable = true AND ps.incognitoMode = false))
          AND (COALESCE(:minDob, u.dateOfBirth) = u.dateOfBirth OR u.dateOfBirth >= :minDob)
          AND (COALESCE(:maxDob, u.dateOfBirth) = u.dateOfBirth OR u.dateOfBirth <= :maxDob)
          AND u.deleted = false
        """)
    long countCandidateUsers(
            @Param("userId") String userId,
            @Param("stage") RegistrationStage stage,
            @Param("excludedIds") List<String> excludedIds,
            @Param("minDob") LocalDate minDob,
            @Param("maxDob") LocalDate maxDob);

    /**
     * Bump {@code updatedAt} on a user entity so that cached match scores
     * (which check {@code max(userA.updatedAt, userB.updatedAt)} for freshness)
     * are correctly invalidated after a behavioral profile change.
     *
     * <p>Uses a bulk {@code @Modifying @Query} instead of {@code save()} so the
     * JPA {@code @Version} field is <b>not</b> incremented — this is a
     * cache-invalidation signal, not a domain mutation.
     */
    @Modifying
    @Query("UPDATE UserEntity u SET u.updatedAt = CURRENT_TIMESTAMP WHERE u.id = :userId")
    void touchUpdatedAt(@Param("userId") String userId);
}