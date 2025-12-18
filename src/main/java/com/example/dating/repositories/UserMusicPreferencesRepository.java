package com.example.dating.repositories;

import com.example.dating.models.user.preferences.dao.UserMusicPreferences;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserMusicPreferencesRepository extends JpaRepository<UserMusicPreferences, String> {
    Optional<UserMusicPreferences> findByUserId(String userId);
}
