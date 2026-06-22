package com.example.dating.repositories;

import com.example.dating.models.matching.dao.UserBehavioralProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public interface UserBehavioralProfileRepository extends JpaRepository<UserBehavioralProfile, String> {

    Optional<UserBehavioralProfile> findByUserId(String userId);

    /**
     * Return only the profile ID for a given user — avoids loading the full entity
     * (and its {@code @OneToOne UserEntity} association) into the persistence context.
     * Used by {@code deleteAccount()} to invalidate the Caffeine cache without
     * causing a stale-entity conflict during the subsequent bulk delete.
     */
    @Query("SELECT p.id FROM UserBehavioralProfile p WHERE p.user.id = :userId")
    Optional<String> findProfileIdByUserId(@Param("userId") String userId);

    /**
     * Delete the behavioral profile for a user in a single DML statement.
     * Replaces the find-then-delete pattern in {@code UserServiceImpl.deleteAccount()},
     * reducing two DB round-trips to one and eliminating a non-atomic find+delete.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM UserBehavioralProfile p WHERE p.user.id = :userId")
    void deleteByUserId(@Param("userId") String userId);
}
