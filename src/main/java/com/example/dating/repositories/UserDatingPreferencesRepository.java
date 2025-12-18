package com.example.dating.repositories;

import com.example.dating.models.user.dating.dao.UserDatingPreferences;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserDatingPreferencesRepository extends JpaRepository<UserDatingPreferences, String> {
    Optional<UserDatingPreferences> findByUserId(String userId);
}
